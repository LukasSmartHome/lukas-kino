#!/usr/bin/env python3
"""Filmsortierer für Lukas' Jellyfin (Claude, Konto A, 02.11.2026 – v2 mit Eingangs-Modus für ganze Platten;
v3 Konto B, 20.11.2026: Karte, Runde 1 nur Filme, Qualitäts-Ampel, Endtest, lange Windows-Pfade, sicheres Protokoll).

Läuft im File editor auf dem Pi (dort sind /media und /share eingehängt) und genauso am Windows-PC (Python 3.8+).
Braucht nur Python. ffprobe/ffmpeg sind optional (Qualität, Ampel, Endtest, Kontaktbögen) – für die Ampel nötig.
Der Jellyfin-Schlüssel wird aus jellyfin_schluessel.txt gelesen und nie ausgegeben.
GRUNDREGEL: Es wird NIE etwas gelöscht oder überschrieben. Alles ohne --los ist nur Vorschau.
Gelesen wird nur (Ordner auflisten, Dateikopf per ffprobe, Anfang/Ende für Prüfsummen). Geschrieben wird nur in
QUELLE/Kino/ – und dort nur durch ausfuehren --los (Umbenennen auf derselben Platte, Daten bleiben, wo sie sind).

A) Eingang – eine ganze Platte oder einen Ordner mit unsortierten Filmen, Serien, Musik durchgehen
  karte QUELLE                  ERSTER SCHRITT, schnell: listet nur Namen und Größen (öffnet keine Datei), sichert den
                                Bestand (bestand_*.tsv = alte Ordnung) und zeigt, wo Filme und wo Musik liegen.
                                Reine Musik-Ordner landen in bereiche.json und werden danach übersprungen.
  bereich QUELLE "ORDNER"=musik|filme [...]   Einordnung von Hand korrigieren (Pfad relativ zu QUELLE)
  bestand QUELLE                wie karte
  pruefen QUELLE                Filme (und Serien) einordnen, bei TMDB erkennen, Qualität messen (Ampel), Doppelte
                                finden (exakt gleiche Datei, gleicher Film, schon in der Bibliothek)
                                -> Kino/_sortierer/plan.json, bericht.md (für Lukas), qualitaet.tsv, unsicher.tsv (für Claude)
                                Runde 1 = nur Filme: Musik-Ordner aus bereiche.json werden gar nicht betreten, Musik-
                                Dateien anderswo bleiben liegen. --mit-musik schaltet das ab (Runde 2).
                                Optionen: --ohne-tmdb  --ohne-ffprobe  --ohne-bibliothek (beim großen Umzug: die
                                SSD-Filme sollen ja mit auf die Platte und nicht als „schon da“ gelten)
                                --ohne="Ordner A;Ordner B" (zusätzlich überspringen)
  endtest QUELLE [--alle] [--neu]  spielt bei jedem Film die letzten Sekunden ab: findet abgebrochene Downloads/Kopien
                                (MKV zeigt sonst die volle Laufzeit). Nur lesen. Ergebnis fließt in die Ampel.
  vorschau QUELLE NR [NR ...]   Kontaktbogen mit 12 Standbildern für unsichere Fälle (braucht ffmpeg)
  entscheiden QUELLE NR=AKTION [NR=AKTION ...]
                                unsichere Fälle festlegen. AKTION: tmdb:603 | imdb:tt0133093 | titel:Name_mit_Unterstrich:1999
                                | serie:Name_der_Serie[:Staffel] | musik | beiseite | doppelt | lassen
  ausfuehren QUELLE [--los]     nach Plan verschieben (gleiche Platte = sofort). Ziel: QUELLE/Kino/
                                Filme/Titel (Jahr) [tmdbid-N]/…, Serien/Name/Staffel NN/…, Musik/<alter Pfad>,
                                _Pruefen/Doppelt/<alter Pfad>, _Pruefen/Beiseite/<alter Pfad>. Unsichere bleiben liegen.
  zurueck-lauf QUELLE [--los]   den letzten ausfuehren-Lauf komplett rückgängig machen
  kopieren VON NACH [--los]     ganzen Ordner kopieren und jede Datei prüfen (Größe + Prüfsumme), Quelle bleibt unberührt

B) Bibliothek – Filme, die schon in Jellyfin sind
  inventar                      alle Filme mit Größe, Laufzeit, Ton, TMDB-ID, Ordner -> /share/kino/pruefung/inventar.json
  offen                         Filme ohne TMDB-/IMDb-Zuordnung, mit Namensvorschlag aus dem Dateinamen
  suche NAME [JAHR]             Kandidaten bei TMDB (über Jellyfin, kein eigener TMDB-Schlüssel nötig)
  bilder ID [ID ...]            Kontaktbogen mit 12 Standbildern -> /share/kino/pruefung/ID.jpg
                                (im Heimnetz: http://192.168.178.44:8098/pruefung/ID.jpg)
  doppelte                      gleiche TMDB-ID, gleicher Titel+Jahr oder gleiche Größe+Laufzeit
  umbenennen ID TMDB|ttIMDB [--titel=Eigener_Titel] [--jahr=JJJJ] [--los]
                                Datei nach Schema „Titel (Jahr) [tmdbid-N]/Titel (Jahr) [tmdbid-N].ext“
                                in /media/Filme verschieben (Titel und Jahr von TMDB), Jellyfin neu einlesen.
  stapel ID,ZIEL[,--titel=..] ... [--los]   mehrere auf einmal
  scan                          nur die Film-Bibliothek in Jellyfin neu einlesen
  titel ID Neuer_Titel          Anzeigename in Jellyfin setzen und sperren
  zurueck [--los]               letzte umbenennen-Verschiebung rückgängig (Protokoll /media/Filme/.filmsortierer_log.jsonl)

Allgemeine Optionen: --server=http://192.168.178.44:8096  --schluessel=PFAD  --ausgabe=Kino
"""
import errno, hashlib, json, os, re, shutil, subprocess, sys, tempfile, time, unicodedata, urllib.parse, urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


def opt(name, standard=None):
    """Globale Option --name=wert (oder Umgebungsvariable FS_NAME)."""
    for x in sys.argv:
        if x.startswith("--" + name + "="):
            return x.split("=", 1)[1]
    return os.environ.get("FS_" + name.upper().replace("-", "_"), standard)


S = opt("server", "http://192.168.178.44:8096")
ZIEL = "/media/Filme"
PRUEF = "/share/kino/pruefung"
LOG = ZIEL + "/.filmsortierer_log.jsonl"
DATEI_TYPEN = (".mkv", ".mp4", ".avi", ".m4v", ".ts", ".mov", ".wmv", ".mpg", ".mpeg", ".m2ts", ".webm")
MUELL = r"(german|deutsch|dl|ml|dual|ac3|eac3|dts|dd5[ .]?1|truehd|atmos|720p|1080p|2160p|4k|uhd|hdr|sdr|web|webrip|web-?dl|bluray|bdrip|brrip|hdtv|dvdrip|x264|x265|h264|h265|hevc|avc|remux|repack|proper|internal|extended|uncut|unrated|ld|md|sd|hd)"

_KEY = None


def L(p):
    """Windows: lange Pfade (ab 240 Zeichen) mit dem Vorsatz \\\\?\\ ansprechen – sonst sieht Windows sie nicht
    (Ordner würden still übersprungen). Auf dem Pi und bei kurzen Pfaden bleibt alles, wie es ist."""
    if os.name != "nt" or not p:
        return p
    p = os.path.abspath(p)
    if len(p) < 240 or p.startswith("\\\\?\\"):
        return p
    return "\\\\?\\UNC\\" + p[2:] if p.startswith("\\\\") else "\\\\?\\" + p


def schluessel():
    global _KEY
    if _KEY:
        return _KEY
    for p in (opt("schluessel"), "/config/jellyfin_schluessel.txt", "/homeassistant/jellyfin_schluessel.txt",
              r"\\192.168.178.44\config\jellyfin_schluessel.txt"):
        if p and os.path.exists(p):
            _KEY = open(p, encoding="utf-8").read().strip().strip('"')
            return _KEY
    sys.exit("kein Jellyfin-Schlüssel gefunden (--schluessel=PFAD angeben)")


def jf(pfad, body=None, methode=None, roh=False):
    d = json.dumps(body).encode() if body is not None else None
    m = methode or ("POST" if d is not None else "GET")
    H = {"Authorization": 'MediaBrowser Token="%s"' % schluessel(), "Content-Type": "application/json"}
    r = urllib.request.urlopen(urllib.request.Request(S + pfad, data=d, headers=H, method=m), timeout=120)
    t = r.read()
    if roh:
        return t
    return json.loads(t) if t else None


_UID = None


def uid():
    global _UID
    if not _UID:
        u = jf("/Users")
        admin = [x for x in u if (x.get("Policy") or {}).get("IsAdministrator")]
        _UID = (admin or u)[0]["Id"]
    return _UID


def filme():
    return jf("/Items?Recursive=true&IncludeItemTypes=Movie&userId=%s&Fields=Path,ProviderIds,MediaSources,DateCreated,OriginalTitle" % uid())["Items"]


def normal(s):
    s = unicodedata.normalize("NFKD", (s or "").lower().replace("ß", "ss"))
    s = "".join(c for c in s if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]", "", s)


def aus_dateiname(pfad):
    """Grober Vorschlag (Name, Jahr) aus einem Szene-Dateinamen. Kurzformen wie „exq-zulu“ bleiben unsicher."""
    n = os.path.splitext(os.path.basename(pfad))[0]
    jahr = None
    ms = list(re.finditer(r"(?<!\d)(19[2-9]\d|20[0-4]\d)(?!\d)", n))
    m = ms[-1] if ms and ms[-1].start() > 0 else (ms[0] if ms else None)  # das letzte Jahr gilt („Blade Runner 2049 2017“)
    if m:
        jahr = int(m.group(1))
        n = n[:m.start()].rstrip(" ([._-")
    n = re.sub(r"[\[(].*?[\])]", " ", n)
    n = re.sub(r"[._]+", " ", n)
    n = re.split(r"\s" + MUELL + r"\b", n, flags=re.I)[0]
    kurz = bool(re.match(r"^[a-z0-9]{2,10}-[a-z0-9]+(-\w+)?$", n.strip()))
    if kurz:  # Szene-Kurzform „gruppe-kurzname“
        n = re.sub(r"-\d{3,4}p?$", "", n.strip().split("-", 1)[1])
    n = re.sub(r"-[A-Za-z0-9]+$", "", n.strip()) if not kurz else n
    return n.strip(" -"), jahr, kurz


def kandidaten_tmdb(namen, jahr):
    res, gesehen = [], set()
    for n in namen:
        p = {"query": n, "language": "de-DE", "include_adult": "false"}
        if jahr:
            p["year"] = jahr
        d = tmdb_api("/search/movie", p)
        if d is None:
            return [{"fehler": "TMDB nicht erreichbar"}]
        for r in (d or {}).get("results") or []:
            if r.get("id") not in gesehen:
                gesehen.add(r.get("id"))
                res.append(r)
    return [{"name": r.get("title"), "original": r.get("original_title"), "jahr": tmdb_jahr(r.get("release_date")),
             "tmdb": str(r.get("id")), "imdb": None, "inhalt": (r.get("overview") or "")[:140]} for r in res[:6]]


def kandidaten(name, jahr=None):
    namen = [name]
    umlaut = re.sub(r"ae", "ä", re.sub(r"oe", "ö", re.sub(r"ue", "ü", name)))
    if tmdb_schluessel():
        return kandidaten_tmdb(namen + ([umlaut] if umlaut != name else []), jahr)
    if umlaut != name:
        namen.append(umlaut)  # „Goettliche“ -> „Göttliche“
    res, gesehen = [], set()
    for n in namen:
        info = {"Name": n}
        if jahr:
            info["Year"] = jahr
        try:
            for r in jf("/Items/RemoteSearch/Movie", {"SearchInfo": info}) or []:
                k = (r.get("ProviderIds") or {}).get("Tmdb") or r.get("Name")
                if k not in gesehen:
                    gesehen.add(k)
                    res.append(r)
        except Exception as e:
            return [{"fehler": str(e)}]
    return [{"name": r.get("Name"), "jahr": r.get("ProductionYear"), "tmdb": (r.get("ProviderIds") or {}).get("Tmdb"),
             "imdb": (r.get("ProviderIds") or {}).get("Imdb"), "inhalt": (r.get("Overview") or "")[:140]} for r in res[:6]]


def kurzinfo(f):
    ms = (f.get("MediaSources") or [{}])[0]
    st = ms.get("MediaStreams") or []
    v = [s for s in st if s.get("Type") == "Video"]
    return {"id": f["Id"], "name": f.get("Name"), "jahr": f.get("ProductionYear"), "pfad": f.get("Path"),
            "gb": round((ms.get("Size") or 0) / 1e9, 2), "bytes": ms.get("Size") or 0, "min": round((f.get("RunTimeTicks") or 0) / 6e8, 1),
            "bild": ("%sx%s" % (v[0].get("Width"), v[0].get("Height"))) if v else "",
            "videotitel": v[0].get("Title") if v else None,
            "ton": ["%s %s %sch" % (s.get("Language"), s.get("Codec"), s.get("Channels")) for s in st if s.get("Type") == "Audio"],
            "tmdb": (f.get("ProviderIds") or {}).get("Tmdb"), "imdb": (f.get("ProviderIds") or {}).get("Imdb"),
            "neu": (f.get("DateCreated") or "")[:10]}


def cmd_inventar(_):
    os.makedirs(PRUEF, exist_ok=True)
    l = [kurzinfo(f) for f in filme() if (f.get("Path") or "").startswith("/media")]
    json.dump(l, open(PRUEF + "/inventar.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    ordner = {}
    for x in l:
        rel = os.path.relpath(x["pfad"], ZIEL)
        k = "flach in Filme/" if os.sep not in rel else "eigener Ordner"
        ordner[k] = ordner.get(k, 0) + 1
    print("Filme:", len(l), "| ohne Zuordnung:", sum(1 for x in l if not (x["tmdb"] or x["imdb"])),
          "| Größe:", round(sum(x["gb"] for x in l)), "GB |", ordner)


def cmd_offen(_):
    for f in filme():
        if not (f.get("Path") or "").startswith("/media"):
            continue
        p = f.get("ProviderIds") or {}
        if p.get("Tmdb") or p.get("Imdb"):
            continue
        i = kurzinfo(f)
        n, j, kurz = aus_dateiname(i["pfad"])
        i["vorschlag"] = {"name": n, "jahr": j, "kurzform": kurz}
        print(json.dumps(i, ensure_ascii=False))


def cmd_suche(a):
    """suche NAME [JAHR] – oder mehrere auf einmal: suche Name_mit_Unterstrichen:Jahr Anderer_Film:2011 ..."""
    if len(a) >= 1 and ":" in a[0] or (len(a) > 2):
        for x in a:
            name, _, j = x.partition(":")
            print("##", name.replace("_", " "), j)
            for k in kandidaten(name.replace("_", " "), int(j) if j.isdigit() else None):
                print(json.dumps(k, ensure_ascii=False))
        print("FERTIG")
        return
    name = a[0]
    jahr = int(a[1]) if len(a) > 1 and a[1].isdigit() else None
    for k in kandidaten(name, jahr):
        print(json.dumps(k, ensure_ascii=False))


def ffmpeg():
    p = shutil.which("ffmpeg")
    if not p:
        sys.exit("ffmpeg fehlt (File editor: apk add --no-cache ffmpeg)")
    return p


def cmd_bilder(ids):
    ff = ffmpeg()
    os.makedirs(PRUEF, exist_ok=True)
    for i in ids:
        f = jf("/Items/%s?userId=%s&Fields=Path" % (i, uid()))
        pfad, dauer = f["Path"], (f.get("RunTimeTicks") or 0) / 1e7
        if dauer <= 0:
            print(i, "keine Laufzeit"); continue
        # Vorspann (Titel), dann quer durch den Film
        punkte = [60, 150, 300] + [dauer * x for x in (0.15, 0.25, 0.35, 0.45, 0.55, 0.65, 0.75, 0.85, 0.97)]
        with tempfile.TemporaryDirectory() as t:
            n = 0
            for s in punkte:
                if s >= dauer:
                    continue
                out = "%s/b%02d.jpg" % (t, n)
                subprocess.run([ff, "-v", "quiet", "-ss", str(int(s)), "-i", pfad, "-frames:v", "1", "-vf", "scale=480:-2",
                                "-y", out], timeout=120)
                if os.path.exists(out):
                    n += 1
            if not n:
                print(i, "keine Bilder"); continue
            ziel = "%s/%s.jpg" % (PRUEF, i)
            subprocess.run([ff, "-v", "quiet", "-i", t + "/b%02d.jpg", "-vf", "pad=480:ih+8:0:4:black,tile=3x%d" % ((n + 2) // 3),
                            "-frames:v", "1", "-q:v", "4", "-y", ziel], timeout=120)
            print(i, "->", ziel, n, "Bilder")


def cmd_doppelte(_):
    l = [kurzinfo(f) for f in filme() if (f.get("Path") or "").startswith("/media")]
    gruppen = {}
    for x in l:
        for k in (("tmdb", x["tmdb"]), ("titel", normal(x["name"]) + "|" + str(x["jahr"] or "")),
                  ("groesse", "%s|%s|%s" % (x["bytes"], round(x["min"]), x["bild"]))):
            if k[1] and not k[1].endswith("|") and not k[1].startswith("0|"):
                gruppen.setdefault(k, []).append(x)
    gefunden = False
    for (art, wert), xs in gruppen.items():
        if len(xs) > 1:
            gefunden = True
            print("DOPPELT (%s %s):" % (art, wert))
            for x in xs:
                print("   ", x["gb"], "GB", x["bild"], x["ton"], x["pfad"])
    if not gefunden:
        print("keine Doppelten gefunden")


def sauber(s):
    s = re.sub(r'[<>:"/\\|?*]', " ", s)
    s = re.sub(r"\s+", " ", s).strip(" .")
    return s


_TMDB = []


def tmdb_schluessel():
    """Eigener TMDB-Schlüssel (v3-Key oder v4-Lesetoken) aus tmdb_schluessel.txt – Lukas legt die Datei selbst an.
    Wird nie ausgegeben. Ohne Schlüssel gibt es keine Filmreihen und keinen Laufzeit-Vergleich, sonst läuft alles."""
    if not _TMDB:
        k = None
        for p in (opt("tmdb-schluessel"), os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmdb_schluessel.txt"),
                  "/config/tmdb_schluessel.txt", "/homeassistant/tmdb_schluessel.txt"):
            if p and os.path.exists(p):
                k = open(p, encoding="utf-8").read().strip().strip('"') or None
                break
        _TMDB.append(k)
    return _TMDB[0]


TMDB_PRO_SEKUNDE = 20  # TMDB lässt ca. 40 Anfragen pro Sekunde zu (Stand 2026) – wir bleiben bei der Hälfte
_TMDB_LETZTE = [0.0]


def tmdb_api(pfad, params=None):
    """GET auf api.themoviedb.org/3 mit dem eigenen Schlüssel. {} = gibt es nicht (404), None = gerade nicht erreichbar.
    Der Schlüssel taucht in keiner Meldung auf."""
    key = tmdb_schluessel()
    if not key:
        return None
    params = dict(params or {})
    hdr = {"Accept": "application/json", "User-Agent": "filmsortierer"}
    if len(key) > 40:
        hdr["Authorization"] = "Bearer " + key
    else:
        params["api_key"] = key
    url = "https://api.themoviedb.org/3" + pfad + "?" + urllib.parse.urlencode(params)
    for versuch in range(4):
        warte = _TMDB_LETZTE[0] + 1.0 / TMDB_PRO_SEKUNDE - time.time()
        if warte > 0:
            time.sleep(warte)
        _TMDB_LETZTE[0] = time.time()
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=hdr), timeout=20) as r:
                return json.load(r)
        except urllib.error.HTTPError as x:
            if x.code == 404:
                return {}
            if x.code == 401:
                sys.exit("TMDB-Schlüssel wird abgelehnt (401) – tmdb_schluessel.txt prüfen")
            nach = (x.headers or {}).get("Retry-After") if x.code == 429 else None  # 429 = zu schnell
            time.sleep(int(nach) if nach and str(nach).isdigit() else 2 + 3 * versuch)
        except Exception:
            time.sleep(2 + 3 * versuch)
    return None


def per_laufzeit(e, kand, dcache):
    """Zweiter Blick für unsichere Filme: Kandidaten mit genau gleichem Namen (deutsch oder Original), Jahr gleich, ±1
    oder unbekannt – und die Laufzeit der Datei passt zu TMDB (höchstens 8 Min. bzw. 8 % daneben). Passt genau einer,
    ist es sicher. Gibt es im genauen Jahr einen anderen Kandidaten, wird nicht geraten."""
    q = e.get("q") or {}
    m = q.get("min") or 0
    if m < 40 or q.get("kaputt"):
        return None, None
    name, jahr = e.get("vorschlag") or "", e.get("vorschlag_jahr")
    nn = normal(name)
    exakt = [x for x in kand if x.get("tmdb") and (normal(x.get("name")) == nn or (x.get("original") and normal(x["original"]) == nn))]
    if jahr:
        if any(x.get("jahr") == jahr for x in kand if x not in exakt):
            return None, None
        exakt = [x for x in exakt if not x.get("jahr") or abs(x["jahr"] - jahr) <= 1]
    passt = []
    for x in exakt[:4]:
        info = tmdb_film(x["tmdb"], dcache) or {}
        lz = info.get("min") or 0
        if lz >= 40 and abs(lz - m) <= max(8, 0.08 * lz):
            passt.append((x, lz))
    if len(passt) == 1:
        x, lz = passt[0]
        return x, "Name passt und Laufzeit passt (%d Min., TMDB %d Min.)" % (m, lz)
    return None, None


def tmdb_jahr(rd):
    rd = rd or ""
    return int(rd[:4]) if rd[:4].isdigit() else None


def tmdb_film(tmdb_id, cache):
    """TMDB-Details auf Deutsch: Laufzeit und Filmreihe (belongs_to_collection). Gecacht; None = gerade nicht abrufbar."""
    k = str(tmdb_id)
    if k in cache:
        return cache[k]
    if not tmdb_schluessel() or not k.isdigit():
        return None
    d = tmdb_api("/movie/" + k, {"language": "de-DE"})
    if d is None:
        return None
    if not d:
        cache[k] = {}
        return {}
    c = d.get("belongs_to_collection") or {}
    rd = d.get("release_date") or ""
    cache[k] = {"titel": d.get("title"), "jahr": int(rd[:4]) if rd[:4].isdigit() else None, "min": d.get("runtime") or 0,
                "sammlung": reihe_name(c.get("name")) if c else None, "sammlung_id": c.get("id")}
    return cache[k]


def remote_daten(item_id, ziel):
    """ziel = TMDB-Nummer oder IMDb-ID (tt...). Holt Titel und Jahr in Jellyfins Metadatensprache (Deutsch)."""
    art = "Imdb" if ziel.startswith("tt") else "Tmdb"
    res = jf("/Items/RemoteSearch/Movie", {"SearchInfo": {"ProviderIds": {art: ziel}}, "ItemId": item_id}) or []
    for r in res:
        if str((r.get("ProviderIds") or {}).get(art)) == ziel:
            return art, r
    sys.exit("%s %s nicht gefunden" % (art, ziel))


def optionen(a):
    o = {"los": "--los" in a}
    for x in a:
        if x.startswith("--titel="):
            o["titel"] = x[8:].replace("_", " ")
        if x.startswith("--jahr="):
            o["jahr"] = int(x[7:])
    return [x for x in a if not x.startswith("--")], o


def cmd_umbenennen(a):
    """umbenennen ITEM-ID TMDB-NR|IMDB-ID [--titel=Eigener_Titel] [--jahr=JJJJ] [--los]"""
    a, o = optionen(a)
    item_id, ziel = a[0], a[1].replace("tmdb:", "").replace("imdb:", "")
    f = jf("/Items/%s?userId=%s&Fields=Path" % (item_id, uid()))
    alt = f["Path"]
    art, r = remote_daten(item_id, ziel)
    titel = sauber(o.get("titel") or r.get("Name") or "")
    jahr = o.get("jahr") or r.get("ProductionYear")
    marke = "[%sid-%s]" % (art.lower(), ziel)
    basis = "%s (%s) %s" % (titel, jahr, marke) if jahr else "%s %s" % (titel, marke)
    neu = os.path.join(ZIEL, basis, basis + os.path.splitext(alt)[1].lower())
    print("ALT:", alt)
    print("NEU:", neu)
    if alt == neu:
        print("schon richtig benannt")
        return
    if os.path.exists(neu):
        sys.exit("Ziel gibt es schon – nichts gemacht")
    if not o["los"]:
        print("(nur Vorschau – mit --los ausführen)")
        return
    os.makedirs(os.path.dirname(neu), exist_ok=True)
    os.rename(alt, neu)  # gleiche Platte: sofort, nichts wird kopiert
    with open(LOG, "a", encoding="utf-8") as lg:
        lg.write(json.dumps({"zeit": time.strftime("%Y-%m-%d %H:%M"), "alt": alt, "neu": neu, "ziel": ziel, "titel": titel}, ensure_ascii=False) + "\n")
    try:
        jf("/Library/Media/Updated", {"Updates": [{"Path": alt, "UpdateType": "Deleted"}, {"Path": neu, "UpdateType": "Created"}]})
    except Exception as e:
        print("Jellyfin-Meldung ging nicht (wird beim nächsten Scan gefunden):", e)
    print("verschoben")


def cmd_stapel(a):
    """stapel ID,ZIEL[,--titel=..][,--jahr=..] ID,ZIEL ... [--los] – mehrere Filme auf einmal (Fehler stoppen nur den einen)"""
    los = ["--los"] if "--los" in a else []
    for e in a:
        if e == "--los":
            continue
        print("##", e)
        try:
            cmd_umbenennen(e.split(",") + los)
        except SystemExit as x:
            print("   übersprungen:", x)
        except Exception as x:
            print("   Fehler:", x)
    print("FERTIG")


def cmd_scan(_):
    """Nur die Film-Bibliothek in Jellyfin neu einlesen (nicht die Fotos)."""
    for v in jf("/Library/VirtualFolders"):
        if v.get("CollectionType") == "movies":
            jf("/Items/%s/Refresh?Recursive=true&MetadataRefreshMode=Default&ImageRefreshMode=Default" % v["ItemId"], {})
            print("Scan gestartet:", v.get("Name"))


def cmd_titel(a):
    """titel ITEM-ID Neuer_Titel – Anzeigename in Jellyfin setzen und sperren (z. B. deutscher Titel statt Original)"""
    item_id, name = a[0], a[1].replace("_", " ")
    f = jf("/Items/%s?userId=%s" % (item_id, uid()))
    f["Name"] = name
    f["LockedFields"] = sorted(set((f.get("LockedFields") or []) + ["Name"]))
    jf("/Items/%s" % item_id, f)
    print("Titel gesetzt:", name)


def cmd_zurueck(a):
    if not os.path.exists(LOG):
        sys.exit("kein Protokoll")
    zeilen = open(LOG, encoding="utf-8").read().splitlines()
    if not zeilen:
        sys.exit("Protokoll leer")
    e = json.loads(zeilen[-1])
    print("ZURÜCK:", e["neu"], "->", e["alt"])
    if "--los" not in a:
        print("(nur Vorschau – mit --los ausführen)")
        return
    os.rename(e["neu"], e["alt"])
    try:
        os.rmdir(os.path.dirname(e["neu"]))
    except OSError:
        pass
    open(LOG, "w", encoding="utf-8").write("\n".join(zeilen[:-1]) + ("\n" if len(zeilen) > 1 else ""))
    jf("/Library/Media/Updated", {"Updates": [{"Path": e["neu"], "UpdateType": "Deleted"}, {"Path": e["alt"], "UpdateType": "Created"}]})
    print("zurückgeschoben")


# ====================================================================== A) Eingang: ganze Platten sortieren
# Ablauf: bestand -> pruefen -> (vorschau/entscheiden für Unsichere) -> ausfuehren (Vorschau) -> ausfuehren --los
# Nie löschen, nie überschreiben. Doppeltes und Zusatzkram kommen nach Kino/_Pruefen/…, mit dem alten Pfad dahinter.

VIDEO = DATEI_TYPEN + (".vob", ".iso", ".divx", ".flv", ".3gp", ".ogm", ".rmvb", ".img")
AUDIO = (".mp3", ".flac", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".wma", ".alac", ".ape", ".aiff", ".aif", ".dsf", ".mka")
UNTERTITEL = (".srt", ".sub", ".idx", ".ass", ".ssa", ".vtt", ".sup", ".smi")
BILD = (".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".heic", ".tif", ".tiff")
SYSTEM = {"$RECYCLE.BIN", "System Volume Information", "lost+found", ".Trashes", ".Spotlight-V100", ".fseventsd",
          ".TemporaryItems", ".DocumentRevisions-V100", "@eaDir", "#recycle"}
EXTRA_ORDNER = re.compile(r"^(extras?|featurettes?|behind[ ._-]the[ ._-]scenes|deleted[ ._-]scenes|bonus|trailers?|samples?|"
                          r"interviews?|making[ ._-]of|specials?[ ._-]features)$", re.I)
EPISODE = re.compile(r"(?i)(?:^|[^a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,3})(?![0-9])|(?:^|[^0-9a-z])(\d{1,2})x(\d{2,3})(?![0-9])")
STAFFEL_ORDNER = re.compile(r"(?i)^(staffel|season|s)[ ._-]?\d{1,2}\b")
GENERISCH = re.compile(r"(?i)^(movie|film|video|main|title\d*|t\d\d|video_ts|bdmv|stream|disc ?\d*|[a-f0-9]{8,})$")
ID_IM_NAMEN = re.compile(r"(?i)(tmdb(?:id)?|imdb(?:id)?)[-=_ ](tt\d+|\d+)")
TEIL = re.compile(r"(?i)[ ._-](cd|part|teil|disc|disk)[ ._-]?(\d)(?![0-9])")


def ausgabe(quelle):
    """Ordner, in den sortiert wird – auf derselben Platte, damit Verschieben sofort geht."""
    return os.path.join(quelle, opt("ausgabe", "Kino"))


def sdir(quelle):
    d = os.path.join(ausgabe(quelle), "_sortierer")
    os.makedirs(d, exist_ok=True)
    return d


def lade(pfad, standard):
    try:
        return json.load(open(pfad, encoding="utf-8"))
    except Exception:
        return standard


def speichere(pfad, daten, kompakt=False):
    tmp = pfad + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(daten, f, ensure_ascii=False, indent=None if kompakt else 1)
    os.replace(tmp, pfad)


def gb(b):
    return round(b / 1e9, 1)


def art_grob(rel):
    e = os.path.splitext(rel)[1].lower()
    n = os.path.basename(rel)
    if n.startswith("._") or n in (".DS_Store", "Thumbs.db", "desktop.ini"):
        return "system"
    if e in VIDEO:
        return "video"
    if e in AUDIO:
        return "audio"
    if e in UNTERTITEL:
        return "untertitel"
    if e in BILD:
        return "bild"
    if e in (".nfo", ".txt", ".url", ".sfv", ".md5", ".nzb", ".torrent", ".exe", ".lnk", ".ini", ".db", ".htm", ".html",
             ".xml", ".log", ".par2", ".cue", ".m3u", ".m3u8", ".pls", ".jpg_large", ".srr", ".diz"):
        return "neben"
    return "sonst"


HIER = os.path.dirname(os.path.abspath(__file__))  # Ordner des Skripts (am PC C:\Kino) – dort liegt ein Spiegel des Stands


def melde(d, text):
    """Stand in Kino/_sortierer/fortschritt.txt UND neben dem Skript (status.txt) – so kann Claude ihn über die
    Desktop-App lesen, ohne die Film-Platte anzufassen."""
    zeile = time.strftime("%Y-%m-%d %H:%M:%S ") + text + "\n"
    for p_ in (os.path.join(d, "fortschritt.txt"), os.path.join(HIER, "status.txt")):
        try:
            open(p_, "w", encoding="utf-8").write(zeile)
        except OSError:
            pass


NICHT_LESBAR = []  # (Pfad, Fehler) – Ordner/Dateien, die nicht gelesen werden konnten; wird immer gemeldet


def dateien(quelle, ohne_ausgabe=True, ohne=()):
    """Alle Dateien unter QUELLE: (relativer Pfad, Bytes, Änderungszeit) – ohne Systemordner, ohne den Ausgabe-Ordner
    und ohne die Ordner in OHNE (relativ zu QUELLE, werden gar nicht betreten). Liest nur Ordnereinträge, öffnet keine
    Datei. Was nicht lesbar ist, landet in NICHT_LESBAR statt still zu fehlen."""
    quelle = os.path.abspath(quelle)
    aus = os.path.normcase(os.path.abspath(ausgabe(quelle))) if ohne_ausgabe else None
    ohne_n = {os.path.normcase(os.path.normpath(o)) for o in ohne if o}

    def lauf(abs_ordner, rel_ordner):
        try:
            with os.scandir(L(abs_ordner)) as it:
                eintraege = sorted(it, key=lambda x: x.name)
        except OSError as x:
            NICHT_LESBAR.append((rel_ordner or ".", str(x)[:160]))
            return
        nur_film = False
        if not rel_ordner:
            # Lukas' Markierung ganz oben auf der Platte: „(MUSIK)“ wird nie betreten; gibt es Ordner mit „(FILM)“,
            # dann NUR diese (auch keine losen Dateien im Hauptordner)
            eintraege = [de for de in eintraege if "(musik)" not in de.name.lower()]
            nur_film = any("(film)" in de.name.lower() and de.is_dir(follow_symlinks=False) for de in eintraege)
            if nur_film:
                eintraege = [de for de in eintraege if "(film)" in de.name.lower() and de.is_dir(follow_symlinks=False)]
        unter = []
        for de in eintraege:
            rel = os.path.join(rel_ordner, de.name) if rel_ordner else de.name
            try:
                if de.is_dir(follow_symlinks=False):
                    unter.append((de.name, rel))
                    continue
                if not de.is_file(follow_symlinks=False):
                    continue
                st = de.stat(follow_symlinks=False)
            except OSError as x:
                NICHT_LESBAR.append((rel, str(x)[:160]))
                continue
            yield rel, st.st_size, int(st.st_mtime)
        for n, rel in unter:
            if n in SYSTEM or n.startswith(".Trash"):
                continue
            p = os.path.join(abs_ordner, n)
            if aus and os.path.normcase(p) == aus:
                continue
            if os.path.normcase(rel) in ohne_n:
                continue
            for x in lauf(p, rel):
                yield x

    for x in lauf(quelle, ""):
        yield x


def fingerabdruck(pfad, groesse):
    """Schnelle Prüfsumme: Größe + erste und letzte 4 MiB (reicht, um gleiche Dateien sicher zu erkennen)."""
    h = hashlib.md5(str(groesse).encode())
    with open(L(pfad), "rb") as f:
        h.update(f.read(4 << 20))
        if groesse > 8 << 20:
            f.seek(-(4 << 20), 2)
            h.update(f.read(4 << 20))
    return h.hexdigest()


def sauber_name(n):
    n = re.sub(r"[\[(].*?[\])]", " ", n)
    n = re.sub(r"[._]+", " ", n)
    n = re.split(r"\s" + MUELL + r"\b", n, flags=re.I)[0]
    n = re.sub(r"\s+", " ", n)
    return n.strip(" -")


def jahr_aus(n):
    m = re.search(r"(?<!\d)(19[2-9]\d|20[0-4]\d)(?!\d)", n)
    return (int(m.group(1)), m) if m else (None, None)


def film_vorschlag(rel, filmordner):
    """(Name, Jahr, Quelle) aus Dateiname oder Ordnername. Den Ordnernamen nur nehmen, wenn es ein Filmordner ist
    (genau ein Film darin) – sonst wäre „Onkel“ oder „Filme“ der Titel. Generische Namen zählen nicht."""
    stem = TEIL.sub("", os.path.splitext(os.path.basename(rel))[0])
    teile = rel.split(os.sep)
    ordner = teile[-2] if len(teile) > 1 and filmordner else ""
    datei = None
    n, j, kurz = aus_dateiname(stem + ".x")
    if n and not GENERISCH.match(n) and not kurz:
        datei = (n, j, "datei")
    ordn = None
    if ordner and not GENERISCH.match(ordner) and not EXTRA_ORDNER.match(ordner) and not STAFFEL_ORDNER.match(ordner):
        on, oj, ok = aus_dateiname(ordner + ".x")
        if on and not ok:
            ordn = (on, oj, "ordner")
    if datei and ordn and ordn[1] and not datei[1]:
        return ordn + (datei[:2],)
    if datei and ordn and normal(datei[0]) in normal(ordn[0]) and len(normal(ordn[0])) > len(normal(datei[0])) + 3:
        # „west.ac3.xvid-FiSTER.avi“ im Ordner „A Million Ways to Die in the West german“ -> Ordner gewinnt
        return (ordn[0], ordn[1] or datei[1], "ordner", datei[:2])
    if datei:
        return datei + ((ordn[:2],) if ordn else (None,))
    if ordn:
        return ordn + (None,)
    if kurz and n:
        return n, j, "kurzform", None
    return stem, None, "unklar", None


def serie_vorschlag(rel, m):
    stem = os.path.splitext(os.path.basename(rel))[0]
    vorne = sauber_name(stem[:m.start()]) if m.start() > 0 else ""
    if not vorne or len(vorne) < 2:
        for o in reversed(rel.split(os.sep)[:-1]):
            if STAFFEL_ORDNER.match(o) or EXTRA_ORDNER.match(o):
                continue
            vorne = sauber_name(o)
            break
    staffel = int(m.group(1) or m.group(3))
    folge = int(m.group(2) or m.group(4))
    return vorne, staffel, folge


def qualitaet_aus_name(rel, groesse):
    n = rel.lower()
    hoehe = 2160 if re.search(r"2160p|\b4k\b|\buhd\b", n) else 1080 if "1080" in n else 720 if "720" in n else \
        576 if re.search(r"576p|dvd", n) else 0
    deutsch = bool(re.search(r"german|deutsch|\bger\b|\bdl\b|\bde\b", n))
    hdr = bool(re.search(r"\bhdr|dolby[ ._-]?vision|\bdv\b", n))
    return {"deutsch": deutsch, "h": hoehe, "hdr": hdr, "kanaele": 0, "bytes": groesse, "quelle": "name"}


def ffprobe_info(pfad, groesse):
    """Liest nur den Dateikopf. Ergebnis None = nicht gemessen (kein ffprobe, Zeitüberschreitung);
    kaputt=True = ffprobe lief, findet aber kein Bild (z. B. MP4 ohne Inhaltsverzeichnis = abgebrochene Kopie)."""
    fp = shutil.which("ffprobe")
    if not fp:
        return None
    r = None
    try:
        r = subprocess.run([fp, "-v", "error", "-show_entries",
                            "stream=codec_type,codec_name,width,height,channels,color_transfer:stream_tags=language,title:format=duration",
                            "-of", "json", L(pfad)], capture_output=True, timeout=60)
        d = json.loads(r.stdout or b"{}")
    except subprocess.TimeoutExpired:
        return None
    except Exception:
        d = {}
    st = d.get("streams") or []
    v = [x for x in st if x.get("codec_type") == "video" and x.get("codec_name") not in ("mjpeg", "png")]
    if not v:
        q = qualitaet_aus_name(pfad, groesse)  # nicht lesbar -> Qualität aus dem Dateinamen, aber als kaputt markiert
        zeilen = ((r.stderr if r is not None else b"") or b"").decode("utf-8", "replace").strip().splitlines()
        f = (zeilen[-1] if zeilen else "kein Bild gefunden").replace(L(pfad) + ": ", "").replace(pfad + ": ", "")
        q.update(kaputt=True, fehler=f[:160], quelle="ffprobe")
        return q
    a = [x for x in st if x.get("codec_type") == "audio"]
    spr = [((x.get("tags") or {}).get("language") or "?").lower() for x in a]
    deutsch = any(s in ("ger", "deu", "de", "german") for s in spr)
    if not deutsch and all(s in ("?", "und") for s in spr):
        deutsch = qualitaet_aus_name(pfad, groesse)["deutsch"]  # Spuren ohne Sprache: Dateiname entscheidet
    return {"deutsch": deutsch, "h": v[0].get("height") or 0, "w": v[0].get("width") or 0,
            "codec": (v[0].get("codec_name") or "").lower(),
            "hdr": any(x.get("color_transfer") in ("smpte2084", "arib-std-b67") for x in v),
            "kanaele": max([x.get("channels") or 0 for x in a] or [0]), "bytes": groesse, "ton": spr,
            "min": round(float((d.get("format") or {}).get("duration") or 0) / 60, 1), "quelle": "ffprobe"}


def klasse(q):
    """Auflösungsklasse 2160/1080/720/576/360 (0 = unbekannt). Nach der Breite, damit Breitbild-Filme
    (1920x800) als 1080p zählen; ohne Breite nach der Höhe."""
    w, h = q.get("w") or 0, q.get("h") or 0
    if w:
        return 2160 if w >= 3200 else 1080 if w >= 1700 else 720 if w >= 1180 else 576 if w >= 640 else 360
    if h:
        return 2160 if h >= 1500 else 1080 if h >= 800 else 720 if h >= 600 else 576 if h >= 480 else 360
    return 0


def heil(q):
    return not q.get("kaputt") and not str(q.get("ende") or "").startswith("kaputt")


def rang(q):
    """Bessere Fassung: heile Datei vor deutschem Ton vor Auflösung vor HDR vor Tonkanälen vor Größe (Vorgabe Lukas)."""
    return (1 if heil(q) else 0, 1 if q.get("deutsch") else 0, klasse(q), 1 if q.get("hdr") else 0,
            q.get("kanaele") or 0, q.get("bytes") or 0)


# ---- Qualitäts-Ampel (nur Anzeige – deswegen wird nichts verschoben, Lukas entscheidet)
ROT_NAME = re.compile(r"(?i)(?:^|[^a-z0-9])(cam|camrip|hdcam|hd-cam|telesync|hdts|hd-ts|telecine|hdtc|predvd|pdvd)(?=[^a-z0-9]|$)")
GELB_NAME = re.compile(r"(?i)(?:^|[^a-z0-9])(ts|tc|scr|screener|dvdscr|bdscr|r5|md|ld|mic-?dubbed|line-?dubbed)(?=[^a-z0-9]|$)")
GELB_TEXT = {"ts": "Kino-Mitschnitt (TS)?", "tc": "Kino-Mitschnitt (TC)?", "scr": "Vorab-Kopie (Screener)?",
             "screener": "Vorab-Kopie (Screener)?", "dvdscr": "Vorab-Kopie (Screener)?", "bdscr": "Vorab-Kopie (Screener)?",
             "r5": "Vorab-Fassung (R5)?", "md": "Ton im Kino mit Mikrofon aufgenommen (MD)?",
             "ld": "Ton aus dem Kino (LD)?", "micdubbed": "Ton im Kino mit Mikrofon aufgenommen?",
             "mic-dubbed": "Ton im Kino mit Mikrofon aufgenommen?", "linedubbed": "Ton aus dem Kino?",
             "line-dubbed": "Ton aus dem Kino?"}
ALT_CODEC = {"mpeg4", "msmpeg4v1", "msmpeg4v2", "msmpeg4v3", "wmv1", "wmv2", "wmv3", "mpeg1video", "rv30", "rv40",
             "flv1", "h263", "vp6f", "theora"}
SPARSAM_CODEC = {"hevc", "av1", "vp9"}  # brauchen für gleiches Bild nur ca. 60 % der Datenmenge
# GB pro Stunde (ganze Datei): (darunter gelb, darunter rot) für H.264 – sparsame Codecs mal 0,6
GRENZEN = {2160: (6.0, 3.0), 1080: (1.5, 0.6), 720: (0.7, 0.3), 576: (0.4, 0.18), 360: (0.25, 0.1)}


def gb_pro_stunde(q):
    m = q.get("min") or 0
    return (q.get("bytes") or 0) / 1e9 / (m / 60.0) if m > 0 else 0


# Review-Ordner (Wunsch Lukas 20.11.): alles, was nicht sicher gut ist, nach Grund sortiert – Nummer = Wichtigkeit
REVIEW_KAT = {"kaputt": "1 Kaputt oder unvollständig", "kino": "2 Im Kino abgefilmt", "tv": "3 TV-Aufnahme",
              "schlecht": "4 Schlechte Qualität", "vielleicht": "5 Vielleicht schlechte Qualität",
              "ungeprueft": "5 Vielleicht schlechte Qualität", "disc": "6 DVD und Blu-ray (umwandeln)",
              "serie": "7 Serien (kommen später)"}
SENDER = re.compile(r"(?i)(?:^|[^a-z0-9])(kabel ?(?:1|eins)|pro ?7|prosieben|sat ?\.? ?1|rtl ?(?:2|zwei|nitro|up)?|vox|zdf(?:neo)?|"
                    r"das ?erste|ard|arte|3sat|tele ?5|sixx|super ?rtl|dmax|servus ?tv|orf ?[123]?|srf ?[12]?|kika|"
                    r"hdtv|dvb-?[ct]2?|tv-?aufnahme|aufnahme)(?=[^a-z0-9]|$)")


def ampel(e):
    """(Farbe, Gründe) – rot = wirklich schlecht/kaputt, gelb = ansehen, gruen = ok, grau = nicht gemessen.
    Setzt außerdem e["review_kat"] = wichtigster Grund (für den Review-Ordner)."""
    q = e.get("q") or {}
    rot, gelb = [], []  # Einträge: (Kategorie, Text)
    if q.get("kaputt"):
        rot.append(("kaputt", "lässt sich nicht lesen (%s)" % (q.get("fehler") or "?")[:80]))
    ende = str(q.get("ende") or "")
    if ende.startswith("kaputt"):
        rot.append(("kaputt", "Ende fehlt – Datei vermutlich unvollständig"))
    elif ende.startswith("fehler"):
        gelb.append(("kaputt", "Fehler beim Abspielen am Ende"))
    name = os.path.splitext(" / ".join(e["pfad"].split(os.sep)[-2:]))[0]
    m = ROT_NAME.search(name)
    if m:
        rot.append(("kino", "im Kino abgefilmt? („%s“ im Namen)" % m.group(1)))
    else:
        m = GELB_NAME.search(name)
        if m:
            gelb.append(("kino", "%s („%s“ im Namen)" % (GELB_TEXT.get(m.group(1).lower(), "Vorab-Fassung?"), m.group(1))))
    # Sendernamen nur außerhalb des Filmtitels suchen („Vox Lux“ ist ein Film, keine VOX-Aufnahme)
    probe = " %s " % re.sub(r"[._\-()\[\]]+", " ", name.lower())
    for t_ in (e.get("titel"), e.get("vorschlag")):
        t_ = re.sub(r"[._\-()\[\]:]+", " ", (t_ or "").lower()).strip()
        if len(t_) > 2:
            probe = probe.replace(" %s " % t_, "  ")
    m = SENDER.search(probe)
    if m or e["pfad"].lower().endswith((".ts", ".m2t", ".rec")):
        gelb.append(("tv", "vom Fernsehen aufgenommen? („%s“)" % (m.group(1) if m else os.path.splitext(e["pfad"])[1])))
    k = klasse(q)
    if k == 360:
        rot.append(("schlecht", "sehr niedrige Auflösung"))
    elif k == 576:
        gelb.append(("vielleicht", "nur DVD-/SD-Auflösung"))
    codec = (q.get("codec") or "").lower()
    if codec in ALT_CODEC:
        gelb.append(("vielleicht", "alter Codec (%s, z. B. XviD/DivX)" % codec))
    gbh = gb_pro_stunde(q)
    if gbh and k and not q.get("kaputt"):
        g, r = GRENZEN[k]
        f = 0.6 if codec in SPARSAM_CODEC else 1.0
        if gbh < r * f:
            rot.append(("schlecht", "sehr stark komprimiert (%s GB pro Stunde)" % zahl(gbh)))
        elif gbh < g * f:
            gelb.append(("vielleicht", "stark komprimiert (%s GB pro Stunde)" % zahl(gbh)))
    tm = e.get("tmdb_min") or 0
    if e.get("art") == "film" and q.get("min") and not q.get("kaputt"):
        if tm >= 40:
            if q["min"] < 0.7 * tm:
                rot.append(("kaputt", "nur %d von %d Min. – unvollständig oder falscher Film?" % (q["min"], tm)))
            elif q["min"] < 0.88 * tm:
                gelb.append(("kaputt", "%d von %d Min. – gekürzt oder unvollständig?" % (q["min"], tm)))
            elif q["min"] > tm + max(12, 0.12 * tm):
                gelb.append(("tv", "%d statt %d Min. – Fernsehaufnahme mit Werbung/Vorlauf? (oder Extended)" % (q["min"], tm)))
        elif q["min"] < 50:
            gelb.append(("kaputt", "nur %d Min. – Ausschnitt oder unvollständig?" % q["min"]))
    farbe = "rot" if rot else "gelb" if gelb else ("gruen" if q.get("quelle") == "ffprobe" else "grau")
    # Ordner nach dem schwersten Grund: erst rote Gründe, sonst gelbe – bei gelb schlägt „TV-Aufnahme“ alles andere
    # (eine Fernsehaufnahme ist oft gekürzt, das ist dann kein Defekt)
    if rot:
        e["review_kat"] = min((k_ for k_, _ in rot), key=list(REVIEW_KAT).index)
    elif gelb:
        reihe = ["tv", "kino", "kaputt", "schlecht", "vielleicht"]
        e["review_kat"] = min((k_ for k_, _ in gelb), key=reihe.index)
    else:
        e["review_kat"] = "ungeprueft" if farbe == "grau" else None
    return farbe, [t for _, t in rot + gelb]


def q_text(q):
    if not q:
        return "?"
    t = []
    if q.get("kaputt") or str(q.get("ende") or "").startswith("kaputt"):
        t.append("KAPUTT")
    if klasse(q):
        t.append({2160: "4K", 576: "SD", 360: "<SD"}.get(klasse(q), "%dp" % klasse(q)))
    if q.get("codec"):
        t.append(q["codec"])
    if q.get("hdr"):
        t.append("HDR")
    t.append("Deutsch" if q.get("deutsch") else "ohne dt. Ton" if q.get("quelle") == "ffprobe" else "Ton ?")
    if q.get("kanaele"):
        t.append("%sch" % q["kanaele"])
    t.append("%.1f GB" % (q.get("bytes", 0) / 1e9))
    return " ".join(t)


def tmdb_treffer(name, jahr, cache):
    """Kandidaten über Jellyfin (gecacht). Sicher nur bei genau passendem Namen+Jahr oder genau einem Treffer im Jahr."""
    k = "%s|%s" % (name, jahr or "")
    if k not in cache:
        kand = kandidaten(name, jahr)
        if jahr and not kand:
            kand = kandidaten(name, None)  # manchmal stimmt das Jahr im Dateinamen nicht
        if any(x.get("fehler") for x in kand):
            return None, False, "TMDB-Abfrage ging nicht (%s) – später nochmal pruefen" % kand[0]["fehler"][:60], []
        cache[k] = kand
    alle = [x for x in cache[k] if not x.get("fehler")]
    if not alle:
        return None, False, "kein Treffer bei TMDB", []
    # Jellyfin liefert jeden Film oft doppelt (TMDB und IMDb/OMDb) – mit TMDB-ID zählt, IMDb nur als Rückfall
    kand = [x for x in alle if x.get("tmdb")] or alle
    nn = normal(name)
    exakt = [x for x in kand if normal(x.get("name")) == nn or (x.get("original") and normal(x["original"]) == nn)]
    if jahr:
        t = [x for x in exakt if x.get("jahr") == jahr]
        if len(t) == 1:
            return t[0], True, "Name und Jahr passen genau", kand
        im_jahr = [x for x in kand if x.get("jahr") == jahr]
        if len(im_jahr) == 1 and len(kand) == 1:
            return im_jahr[0], True, "einziger Treffer in diesem Jahr (anderer Titel: %s)" % im_jahr[0].get("name"), kand
        nah = [x for x in exakt if x.get("jahr") and abs(x["jahr"] - jahr) <= 1]
        if len(nah) == 1:
            return nah[0], False, "Name passt, Jahr weicht 1 ab", kand
    elif len(exakt) == 1 and len(kand) == 1:
        return exakt[0], True, "einziger Treffer, Name passt genau (ohne Jahr)", kand
    return (exakt or kand)[0], False, "nicht eindeutig", kand


def film_ordner(titel, jahr, tmdb=None, imdb=None):
    titel = sauber(titel or "Unbekannt")
    marke = " [tmdbid-%s]" % tmdb if tmdb else " [imdbid-%s]" % imdb if imdb else ""
    return ("%s (%s)%s" % (titel, jahr, marke)) if jahr else (titel + marke)


def bibliothek():
    """TMDB-ID -> (Pfad, Qualität) der Filme, die schon in Jellyfin sind."""
    lib = {}
    for f in filme():
        i = kurzinfo(f)
        if not i["tmdb"]:
            continue
        h = int(i["bild"].split("x")[1]) if "x" in (i["bild"] or "") and i["bild"].split("x")[1].isdigit() else 0
        w = int(i["bild"].split("x")[0]) if "x" in (i["bild"] or "") and i["bild"].split("x")[0].isdigit() else 0
        q = {"deutsch": any(t.split(" ")[0] in ("ger", "deu") for t in i["ton"]), "h": h, "w": w, "hdr": False,
             "kanaele": max([int(re.sub(r"\D", "", t.split(" ")[-1]) or 0) for t in i["ton"]] or [0]),
             "bytes": i["bytes"], "quelle": "jellyfin"}
        lib.setdefault(str(i["tmdb"]), []).append((i["pfad"], q))
    return lib


def per_id(art, wert, cache):
    """Titel und Jahr zu einer TMDB- oder IMDb-ID (über Jellyfin, gecacht)."""
    k = "id|%s|%s" % (art, wert)
    if k not in cache and tmdb_schluessel():
        if art == "Tmdb":
            d = tmdb_api("/movie/" + str(wert), {"language": "de-DE"})
        else:
            d = ((tmdb_api("/find/" + str(wert), {"external_source": "imdb_id", "language": "de-DE"}) or {}).get("movie_results") or [None])[0]
        if d is None:
            return None
        cache[k] = {"name": d.get("title"), "jahr": tmdb_jahr(d.get("release_date")), "tmdb": str(d.get("id"))} if d else None
    if k not in cache:
        try:
            res = jf("/Items/RemoteSearch/Movie", {"SearchInfo": {"ProviderIds": {art: wert}}}) or []
            r = [x for x in res if str((x.get("ProviderIds") or {}).get(art)) == wert]
            cache[k] = {"name": r[0].get("Name"), "jahr": r[0].get("ProductionYear"),
                        "tmdb": (r[0].get("ProviderIds") or {}).get("Tmdb")} if r else None
        except Exception as x:
            print("Nachschlagen ging nicht:", x)
            return None
    return cache[k]


def entscheidung_anwenden(e, aktion, cache):
    """Eine Entscheidung (von Claude/Lukas) auf einen Plan-Eintrag anwenden."""
    e["entschieden"] = aktion
    if aktion.endswith("!") or aktion == "gut":
        e["qualitaet_ok"] = True  # Lukas: trotzdem in „Lukas Filme geordnet“
        aktion = aktion.rstrip("!")
        if aktion in ("gut", ""):
            return
    a, _, rest = aktion.partition(":")
    e["sicher"] = True
    if a in ("tmdb", "imdb"):
        art = "Tmdb" if a == "tmdb" else "Imdb"
        t = per_id(art, rest, cache)
        if not t:
            e["sicher"] = False
            e["grund"] = "%s %s nicht gefunden" % (art, rest)
            return
        e.update(aktion="film", titel=t["name"], jahr=t["jahr"], tmdb=rest if a == "tmdb" else t.get("tmdb"),
                 imdb=rest if a == "imdb" and not t.get("tmdb") else None, grund="entschieden: " + aktion)
    elif a == "titel":
        name, _, j = rest.rpartition(":")
        if not name:
            name, j = rest, ""
        e.update(aktion="film", titel=name.replace("_", " "), jahr=int(j) if j.isdigit() else None, tmdb=None, imdb=None,
                 grund="entschieden: eigener Titel")
    elif a == "serie":
        name, _, st = rest.partition(":")
        e.update(aktion="serie", serie=name.replace("_", " "), grund="entschieden: Serie")
        if st.isdigit():
            e["staffel"] = int(st)
        e.setdefault("staffel", 1)
    elif a in ("musik", "beiseite", "doppelt", "lassen"):
        e.update(aktion=a, grund="entschieden: " + a)
    else:
        e["sicher"] = False
        e["grund"] = "unbekannte Entscheidung " + aktion


ZIELORDNER = None  # --ziel="Lukas Filme geordnet": Filme flach dorthin, Filmreihen als Unterordner (Wunsch Lukas 20.11.)
REVIEWORDNER = None  # --review="Lukas Filme nochmal drüber schauen": alles, was nicht sicher gut ist, nach Grund sortiert
REVIEW_STANDARD = "Lukas Filme nochmal drüber schauen"


def reihe_name(n):
    """„Star Wars Filmreihe“ -> „Star Wars“, „Taxi Collection“ -> „Taxi“."""
    n = re.sub(r"(?i)\s*[-–:]?\s*(filmreihe|reihe|collection|sammlung|kollektion|saga)\s*$", "", n or "").strip()
    return n or None


def film_name(e):
    t = sauber(e.get("titel") or "Unbekannt")
    return "%s (%s)" % (t, e["jahr"]) if e.get("jahr") else t


def ohne_strich(name, reihe):
    """Jellyfin hält Dateien im selben Ordner für VERSIONEN eines Films, wenn alle mit dem Ordnernamen und
    dann „-“ beginnen („Star Wars - Episode I…“). Deshalb den Strich direkt nach dem Reihennamen weglassen."""
    if name.lower().startswith(reihe.lower()):
        return reihe + re.sub(r"^\s*[-–]+\s*", " ", name[len(reihe):])
    return name


def ziel_setzen(e):
    """Zielpfad relativ zur QUELLE (Platte) aus der Aktion."""
    rel, ext = e["pfad"], os.path.splitext(e["pfad"])[1].lower()
    a = e["aktion"]
    k = opt("ausgabe", "Kino")
    teil = (" - part%s" % e["teil"]) if e.get("teil") else ""
    if a == "film" and ZIELORDNER:
        name = film_name(e)
        if e.get("sammlung"):
            r = sauber(e["sammlung"])
            e["ziel"] = os.path.join(ZIELORDNER, r, ohne_strich(name, r) + teil + ext)
        else:
            e["ziel"] = os.path.join(ZIELORDNER, name + teil + ext)
    elif a == "review" and REVIEWORDNER:
        basis = os.path.join(REVIEWORDNER, e["review"])
        if e.get("disc"):  # ganze DVD/Blu-ray bleibt zusammen: <Review>/6 …/<Disc-Name>/<Rest wie vorher>
            e["ziel"] = os.path.join(basis, e["disc_name"], rel[len(e["disc"]) + 1:])
        elif e["art"] == "disc":
            e["ziel"] = os.path.join(basis, os.path.basename(rel))
        elif e["art"] == "folge" and e.get("serie"):
            e["ziel"] = os.path.join(basis, sauber(e["serie"]), "Staffel %02d" % (e.get("staffel") or 1), os.path.basename(rel))
        elif e["art"] == "film" and e.get("sicher") and e.get("titel"):
            e["ziel"] = os.path.join(basis, film_name(e) + teil + ext)
        else:
            e["ziel"] = os.path.join(basis, rel)
    elif a == "film":
        o = film_ordner(e.get("titel"), e.get("jahr"), e.get("tmdb"), e.get("imdb"))
        e["ziel"] = os.path.join(k, "Filme", o, o + teil + ext)
    elif a == "serie":
        e["ziel"] = os.path.join(k, "Serien", sauber(e.get("serie") or "Unbekannt"), "Staffel %02d" % (e.get("staffel") or 1),
                                 os.path.basename(rel))
    elif a == "musik":
        teile = rel.split(os.sep)
        if len(teile) > 1 and teile[0].lower() in ("musik", "music", "mp3", "audio"):
            teile = teile[1:]  # „Musik/Musik/…“ vermeiden
        e["ziel"] = os.path.join(k, "Musik", *teile)
    elif a == "doppelt":
        e["ziel"] = os.path.join(k, "_Pruefen", "Doppelt", rel)
    elif a == "beiseite":
        e["ziel"] = os.path.join(k, "_Pruefen", "Beiseite", rel)
    else:
        e["ziel"] = None


def plan_bauen(quelle, ohne_tmdb=False, ohne_ff=False, ohne_bib=False, ohne=(), mit_musik=False, ziel=None, mit_serien=False,
               review=None):
    """OHNE = Ordner, die gar nicht betreten werden (Musik-Ordner aus bereiche.json). Ohne MIT_MUSIK bleibt jede
    Musik-Datei liegen (Runde 1 = nur Filme). ZIEL = Ordner auf der Platte für die fertigen Filme (Runde 1:
    nur Filme dorthin; Serien, DVD-Abbilder und rote Filme bleiben liegen, nur erzwungene Untertitel gehen mit)."""
    global ZIELORDNER, REVIEWORDNER
    ZIELORDNER = ziel or None
    REVIEWORDNER = (review or REVIEW_STANDARD) if ZIELORDNER else None
    d = sdir(quelle)
    del NICHT_LESBAR[:]
    tcache = lade(os.path.join(d, "tmdb_cache.json"), {})
    icache = lade(os.path.join(d, "info_cache.json"), {})
    hcache = lade(os.path.join(d, "hash_cache.json"), {})
    entsch = lade(os.path.join(d, "entscheidungen.json"), {})
    if not shutil.which("ffprobe"):
        ohne_ff = True
    plan = []

    def fortschritt(t):
        melde(d, t)

    fortschritt("Liste der Dateien …")
    for rel, g, mt in dateien(quelle, ohne=ohne):
        plan.append({"nr": len(plan) + 1, "pfad": rel, "bytes": g, "art": art_grob(rel)})
    fortschritt("Liste fertig: %d Dateien" % len(plan))
    ordner_inhalt = {}
    for e in plan:
        ordner_inhalt.setdefault(os.path.dirname(e["pfad"]), set()).add(e["art"])

    # DVD-/Blu-ray-Strukturen: ALLE Dateien darin gehören zusammen (VOB, IFO, BUP, BDMV …) – nie auseinanderreißen
    disc_wurzel = set()
    for e in plan:
        teile = e["pfad"].split(os.sep)
        gross = [t.upper() for t in teile[:-1]]
        marke = next((m for m in ("VIDEO_TS", "BDMV", "AUDIO_TS", "CERTIFICATE") if m in gross), None)
        if marke and gross.index(marke) > 0:
            disc_wurzel.add(os.sep.join(teile[:gross.index(marke)]))
        elif teile[-1].lower().endswith((".ifo", ".vob", ".bup")) and len(teile) > 1:
            disc_wurzel.add(os.sep.join(teile[:-1]))
    disc_name, schon = {}, {}
    for w in sorted(disc_wurzel):
        n = sauber(os.path.basename(w)) or "Disc"
        schon[n.lower()] = schon.get(n.lower(), 0) + 1
        disc_name[w] = n if schon[n.lower()] == 1 else "%s (%d)" % (n, schon[n.lower()])

    def disc_von(rel):
        for w in disc_wurzel:
            if rel.startswith(w + os.sep):
                return w
        return None

    # wie viele echte Filme (ohne Samples/Extras) liegen in jedem Ordner?
    filmordner = {}
    for e in plan:
        st = os.path.splitext(os.path.basename(e["pfad"]))[0]
        if e["art"] == "video" and not re.search(r"(?i)\b(sample|trailer)\b", st) and not EPISODE.search(st):
            k = os.path.dirname(e["pfad"])
            filmordner[k] = filmordner.get(k, 0) + 1

    # 1) einordnen
    for e in plan:
        rel, g, a = e["pfad"], e["bytes"], e["art"]
        teile = rel.split(os.sep)
        stem = os.path.splitext(teile[-1])[0]
        e["sicher"] = True
        w = disc_von(rel) if disc_wurzel else None
        if w:
            e.update(art="disc", aktion="lassen", sicher=False, disc=w, disc_name=disc_name[w],
                     grund="DVD-/Blu-ray-Abbild – umwandeln")
            continue
        if a == "video":
            extra = next((o for o in teile[:-1] if EXTRA_ORDNER.match(o)), None)
            if extra or (re.search(r"(?i)\bsample\b", stem) and g < 400e6) or (re.search(r"(?i)\btrailer\b", stem) and g < 600e6):
                e.update(art="zusatz", aktion="beiseite", grund="Sample/Trailer/Extra" + (" (Ordner %s)" % extra if extra else ""))
            elif teile[-1].lower().endswith((".iso", ".img", ".vob")) or any(o.upper() in ("VIDEO_TS", "BDMV") for o in teile):
                e.update(art="disc", aktion="lassen", sicher=False, grund="DVD-/Blu-ray-Abbild – von Hand")
            else:
                m = EPISODE.search(stem)
                if m:
                    name, st, fo = serie_vorschlag(rel, m)
                    e.update(art="folge", aktion="serie", serie=name, staffel=st, folge=fo, sicher=bool(name),
                             grund="Folge S%02dE%02d" % (st, fo) if name else "Folge ohne Serienname")
                else:
                    n, j, herkunft, alt = film_vorschlag(rel, filmordner.get(os.path.dirname(rel), 0) == 1)
                    tm = TEIL.search(os.path.splitext(teile[-1])[0])
                    e.update(art="film", aktion="film", vorschlag=n, vorschlag_jahr=j, herkunft=herkunft, alt=alt,
                             teil=int(tm.group(2)) if tm else None)
        elif a == "audio":
            if mit_musik:
                e.update(aktion="musik", grund="Musik (Ordnung bleibt)")
            else:
                e.update(aktion="lassen", grund="Musik – kommt in Runde 2")
        elif a in ("bild", "neben", "system"):
            arten = ordner_inhalt.get(os.path.dirname(rel), set())
            if "audio" in arten and "video" not in arten and a != "system":
                if mit_musik:
                    e.update(aktion="musik", grund="gehört zum Album (Cover/Liste)")
                else:
                    e.update(aktion="lassen", grund="gehört zu Musik – Runde 2")
            elif "video" in arten or a == "system":
                e.update(aktion="beiseite", grund="Zusatzdatei neben Film (%s)" % a if a != "system" else "Systemdatei")
            else:
                e.update(aktion="lassen", grund="kein Film/Musik-Ordner (%s)" % a)
        elif a == "untertitel":
            e.update(aktion="lassen", grund="Untertitel (wird unten zugeordnet)")
        else:
            e.update(aktion="lassen", grund="unbekannter Dateityp")

    # 2) Qualität der Videos (ffprobe, gecacht) bzw. aus dem Namen
    vids = [e for e in plan if e["art"] in ("film", "folge")]
    for i, e in enumerate(vids):
        k = "%s|%s" % (e["pfad"], e["bytes"])
        q = icache.get(k)
        if q is None and not ohne_ff:
            q = ffprobe_info(os.path.join(quelle, e["pfad"]), e["bytes"])
            if q:
                icache[k] = q
            if i % 50 == 0:
                fortschritt("ffprobe %d/%d" % (i, len(vids)))
                speichere(os.path.join(d, "info_cache.json"), icache, True)
        e["q"] = q or qualitaet_aus_name(e["pfad"], e["bytes"])
        e["ampel"], e["probleme"] = ampel(e)
    speichere(os.path.join(d, "info_cache.json"), icache, True)

    # 3) Filme bei TMDB erkennen (über Jellyfin, gecacht)
    filmliste = [e for e in plan if e["art"] == "film"]
    dcache_lz = lade(os.path.join(d, "tmdb_details.json"), {})
    for i, e in enumerate(filmliste):
        mid = ID_IM_NAMEN.search(e["pfad"])
        if mid:
            ist_imdb = mid.group(2).startswith("tt")
            t = None if ohne_tmdb else per_id("Imdb" if ist_imdb else "Tmdb", mid.group(2), tcache)
            e.update(titel=(t or {}).get("name") or e["vorschlag"], jahr=(t or {}).get("jahr") or e["vorschlag_jahr"],
                     tmdb=(t or {}).get("tmdb") or (None if ist_imdb else mid.group(2)),
                     imdb=mid.group(2) if ist_imdb and not (t or {}).get("tmdb") else None, sicher=True, grund="ID steht schon im Namen")
            continue
        if ohne_tmdb:
            e.update(sicher=False, grund="ohne TMDB geprüft", titel=e["vorschlag"], jahr=e["vorschlag_jahr"])
            continue
        t, sicher, grund, kand = tmdb_treffer(e["vorschlag"], e["vorschlag_jahr"], tcache)
        if not sicher and e.get("alt") and e["alt"][0]:
            t2, s2, g2, k2 = tmdb_treffer(e["alt"][0], e["alt"][1], tcache)
            if s2:
                t, sicher, grund, kand = t2, s2, "über den anderen Namen erkannt (%s): %s" % (e["alt"][0], g2), k2
        if not sicher and kand and tmdb_schluessel():
            t3, g3 = per_laufzeit(e, kand, dcache_lz)
            if t3:
                t, sicher, grund = t3, True, g3
        e["kandidaten"] = ["%s (%s) tmdb:%s" % (x.get("name"), x.get("jahr"), x.get("tmdb")) for x in kand[:3]]
        if t:
            e.update(titel=t.get("name"), jahr=t.get("jahr"), tmdb=t.get("tmdb"), imdb=None if t.get("tmdb") else t.get("imdb"))
        else:
            e.update(titel=e["vorschlag"], jahr=e["vorschlag_jahr"])
        e.update(sicher=sicher, grund=grund)
        if i % 25 == 0:
            fortschritt("TMDB %d/%d" % (i, len(filmliste)))
            speichere(os.path.join(d, "tmdb_cache.json"), tcache)
    speichere(os.path.join(d, "tmdb_cache.json"), tcache)
    speichere(os.path.join(d, "tmdb_details.json"), dcache_lz)

    # 4) Entscheidungen von Claude/Lukas (nach Pfad gespeichert)
    for e in plan:
        if e["pfad"] in entsch:
            entscheidung_anwenden(e, entsch[e["pfad"]], tcache)
    speichere(os.path.join(d, "tmdb_cache.json"), tcache)

    # 4b) TMDB-Details (Filmreihe, Laufzeit) – nur mit eigenem TMDB-Schlüssel, gecacht
    dcache = lade(os.path.join(d, "tmdb_details.json"), {})
    if not ohne_tmdb and tmdb_schluessel():
        mit_id = [e for e in plan if e["art"] == "film" and e.get("tmdb")]
        for i, e in enumerate(mit_id):
            info = tmdb_film(e["tmdb"], dcache)
            if info:
                e.update(sammlung=info.get("sammlung"), tmdb_min=info.get("min") or 0)
            if i % 50 == 0:
                fortschritt("TMDB-Details %d/%d" % (i, len(mit_id)))
                speichere(os.path.join(d, "tmdb_details.json"), dcache)
        speichere(os.path.join(d, "tmdb_details.json"), dcache)
    for e in plan:
        if e["art"] in ("film", "folge"):
            e["ampel"], e["probleme"] = ampel(e)

    # 5) Doppelte: a) exakt gleiche Dateien (gleiche Größe, dann Prüfsumme)
    nach_groesse = {}
    for e in plan:
        if (e["art"] in ("film", "folge", "zusatz", "disc") or (mit_musik and e["art"] == "audio")) and e["bytes"] > 1 << 20:
            nach_groesse.setdefault(e["bytes"], []).append(e)
    for g, es in nach_groesse.items():
        if len(es) < 2:
            continue
        gruppen = {}
        for e in es:
            k = "%s|%s" % (e["pfad"], g)
            if k not in hcache:
                try:
                    hcache[k] = fingerabdruck(os.path.join(quelle, e["pfad"]), g)
                except OSError:
                    hcache[k] = "fehler-" + e["pfad"]
            gruppen.setdefault(hcache[k], []).append(e)
        for es2 in gruppen.values():
            if len(es2) < 2:
                continue
            es2.sort(key=lambda x: (0 if x.get("sicher") else 1, 0 if x.get("vorschlag_jahr") or x.get("jahr") else 1, len(x["pfad"]), x["pfad"]))
            for x in es2[1:]:
                if x.get("entschieden"):
                    continue
                x.update(aktion="doppelt", sicher=True, doppelt_von=es2[0]["pfad"],
                         grund="exakt gleiche Datei wie %s" % es2[0]["pfad"])
    speichere(os.path.join(d, "hash_cache.json"), hcache, True)

    # b) gleicher Film (gleiche TMDB-ID und gleicher Teil) bzw. gleiche Folge -> die bessere Fassung bleibt
    gleich = {}
    for e in plan:
        if e["aktion"] == "film" and e.get("sicher") and (e.get("tmdb") or e.get("imdb")):
            gleich.setdefault(("f", str(e.get("tmdb") or e.get("imdb")), e.get("teil")), []).append(e)
        elif e["aktion"] == "serie" and e.get("sicher") and e.get("serie"):
            gleich.setdefault(("s", normal(e["serie"]), e.get("staffel"), e.get("folge")), []).append(e)
    for k, es in gleich.items():
        if len(es) < 2:
            continue
        es.sort(key=lambda x: (0 if x.get("ampel") == "rot" else 1,) + rang(x.get("q") or {}), reverse=True)
        for x in es[1:]:
            if x.get("entschieden"):
                continue
            x.update(aktion="doppelt", doppelt_von=es[0]["pfad"],
                     grund="gleicher %s, schlechtere Fassung (%s) als %s (%s)" % (
                         "Film" if k[0] == "f" else "Folge", q_text(x.get("q")), es[0]["pfad"], q_text(es[0].get("q"))))

    # c) schon in der Jellyfin-Bibliothek?
    besser = []
    if not ohne_tmdb and not ohne_bib:
        try:
            lib = bibliothek()
        except Exception as x:
            lib = {}
            print("Bibliothek nicht lesbar:", x)
        for e in plan:
            if e["aktion"] != "film" or not e.get("sicher") or not e.get("tmdb") or e.get("entschieden"):
                continue
            vorhanden = lib.get(str(e["tmdb"]))
            if not vorhanden:
                continue
            bester = max(vorhanden, key=lambda v: rang(v[1]))
            if rang(e.get("q") or {}) <= rang(bester[1]):
                e.update(aktion="doppelt", doppelt_von=bester[0],
                         grund="schon in der Bibliothek: %s (%s), diese Fassung %s" % (bester[0], q_text(bester[1]), q_text(e.get("q"))))
            else:
                e["besser_als"] = bester[0]
                besser.append(e)

    # 5d) Runde 1 mit Zielordner: in „Lukas Filme geordnet“ kommen nur sicher gute Filme (Ampel grün). Alles andere
    #     kommt in den Review-Ordner, sortiert nach dem wichtigsten Grund (Wunsch Lukas 20.11. 23:46).
    if ZIELORDNER:
        for e in plan:
            if e["aktion"] == "serie" and not mit_serien:
                e.update(aktion="review", review=REVIEW_KAT["serie"], grund="Serie – kommt später (Runde 1 nur Filme)")
            elif e["art"] == "disc" and e["aktion"] == "lassen":
                e.update(aktion="review", review=REVIEW_KAT["disc"], sicher=True,
                         grund="DVD-/Blu-ray-Abbild – erst umwandeln (verlustfrei in eine MKV)")
            elif e["aktion"] == "film" and e.get("sicher") and e.get("ampel") != "gruen" and not e.get("qualitaet_ok"):
                kat = e.get("review_kat") or "vielleicht"
                e.update(aktion="review", review=REVIEW_KAT[kat], grund="; ".join(e.get("probleme") or []) or "nicht gemessen")

    # 6) Untertitel folgen ihrem Film/ihrer Folge (gleicher Name oder einziges Video im Ordner bzw. im Ordner darüber)
    videos_im = {}
    for e in plan:
        if e["art"] in ("film", "folge"):
            videos_im.setdefault(os.path.dirname(e["pfad"]), []).append(e)
    for e in plan:
        if e["art"] != "untertitel":
            continue
        ordner = os.path.dirname(e["pfad"])
        stem = os.path.splitext(os.path.basename(e["pfad"]))[0]
        kand = videos_im.get(ordner) or (videos_im.get(os.path.dirname(ordner)) if re.match(r"(?i)^(subs?|untertitel)$", os.path.basename(ordner)) else None) or []
        v = [x for x in kand if stem.lower().startswith(os.path.splitext(os.path.basename(x["pfad"]))[0].lower())]
        v = v or (kand if len(kand) == 1 else [])
        if len(v) != 1:
            e.update(aktion="beiseite", grund="Untertitel ohne eindeutigen Film")
            continue
        v = v[0]
        vstem = os.path.splitext(os.path.basename(v["pfad"]))[0]
        zusatz = stem[len(vstem):] if stem.lower().startswith(vstem.lower()) else ""
        if ZIELORDNER:
            # Wunsch Lukas: nur erzwungene Untertitel (fremdsprachige Stellen) bleiben beim Film, der Rest kommt beiseite
            if not re.search(r"(?i)(?<![a-z])(forced|erzwungen)(?![a-z])", stem):
                e.update(aktion="beiseite", grund="normaler Untertitel – nur erzwungene (forced) bleiben beim Film")
                continue
            engl = re.search(r"(?i)(?<![a-z])(english|eng|en)(?![a-z])", stem)
            zusatz = ".en.forced" if engl else ".de.forced"
        elif not zusatz:
            sp = re.findall(r"(?i)(?<![a-z])(german|deutsch|ger|de|english|eng|en|forced)(?![a-z])", stem)
            zusatz = "".join("." + s.lower() for s in sp)
        e.update(gehoert_zu=v["nr"], untertitel_zusatz=zusatz, sicher=v.get("sicher"), grund="Untertitel zu " + v["pfad"])

    # 7) Ziele setzen
    nach_nr = {e["nr"]: e for e in plan}
    for e in plan:
        if e.get("gehoert_zu"):
            continue
        ziel_setzen(e)
    for e in plan:
        if not e.get("gehoert_zu"):
            continue
        v = nach_nr[e["gehoert_zu"]]
        e["aktion"], e["sicher"] = v["aktion"], v.get("sicher")
        if v.get("ziel") and v["aktion"] in ("film", "serie", "review"):
            vz = v["ziel"]
            e["ziel"] = os.path.join(os.path.dirname(vz), os.path.splitext(os.path.basename(vz))[0] + e["untertitel_zusatz"] +
                                     os.path.splitext(e["pfad"])[1].lower())
        else:
            ziel_setzen(e)
    # gleiche Ziele dürfen nicht doppelt belegt werden
    belegt = {}
    for e in plan:
        if e.get("ziel") and e.get("sicher"):
            k = os.path.normcase(e["ziel"])
            if k in belegt:
                e.update(sicher=False, grund="Zielname schon vergeben an %s – von Hand" % belegt[k])
            else:
                belegt[k] = e["pfad"]
    fortschritt("fertig")
    speichere(os.path.join(d, "plan.json"), plan, True)
    return plan


def filmname(e):
    if e.get("aktion") == "serie" or e.get("art") == "folge":
        return "%s S%02dE%02d" % (e.get("serie") or "?", e.get("staffel") or 0, e.get("folge") or 0)
    return "%s (%s)" % (e.get("titel") or e.get("vorschlag") or "?", e.get("jahr") or e.get("vorschlag_jahr") or "?")


def zahl(x, stellen=1):
    return ("%%.%df" % stellen % x).replace(".", ",")  # Komma, damit Excel (deutsch) es als Zahl liest


def qualitaet_schreiben(d, plan):
    """qualitaet.tsv (alle Filme und Folgen, schlechteste zuerst) und die Überblicks-Zeilen für Bericht und Ausgabe."""
    filme_l = [e for e in plan if e["art"] == "film" and e["aktion"] in ("film", "review")]
    folgen_l = [e for e in plan if e["art"] == "folge" and e["aktion"] in ("serie", "review")]
    doppelt_l = [e for e in plan if e["art"] in ("film", "folge") and e["aktion"] == "doppelt"]
    amp = {"rot": [], "gelb": [], "gruen": [], "grau": []}
    for e in filme_l + folgen_l:
        amp[e.get("ampel") or "grau"].append(e)
    reihe = {"rot": 0, "gelb": 1, "grau": 2, "gruen": 3}
    alle = sorted(filme_l + folgen_l + doppelt_l, key=lambda e: (reihe.get(e.get("ampel") or "grau", 2), filmname(e)))
    with open(os.path.join(d, "qualitaet.tsv"), "w", encoding="utf-8") as f:
        f.write("nr\tampel\ttitel\taufloesung\tcodec\thdr\tdeutsch\tkanaele\tgb\tminuten\tgb_pro_stunde\taktion\tsicher\tprobleme\tpfad\n")
        for e in alle:
            q = e.get("q") or {}
            k = klasse(q)
            f.write("\t".join(str(x) for x in (
                e["nr"], e.get("ampel") or "grau", filmname(e), {2160: "4K", 576: "SD", 360: "<SD", 0: "?"}.get(k, "%dp" % k),
                q.get("codec") or "", "ja" if q.get("hdr") else "", "ja" if q.get("deutsch") else "nein",
                q.get("kanaele") or "", zahl(e["bytes"] / 1e9), zahl(q.get("min") or 0, 0), zahl(gb_pro_stunde(q)),
                e["aktion"] + (" → " + e["review"] if e.get("review") else ""), "ja" if e.get("sicher") else "nein",
                "; ".join(e.get("probleme") or []), e["pfad"])) + "\n")
    kl = {}
    for e in filme_l:
        k = klasse(e.get("q") or {})
        kl[k] = kl.get(k, 0) + 1
    gemessen = [e for e in filme_l if (e.get("q") or {}).get("quelle") == "ffprobe" and not (e.get("q") or {}).get("kaputt")]
    zeilen = [
        "Filme: %d (%s TB) – sicher gut: %d · zum Drüberschauen: %d · noch unsicher, welcher Film: %d" % (
            len(filme_l), zahl(sum(e["bytes"] for e in filme_l) / 1e12),
            len([e for e in filme_l if e["aktion"] == "film" and e.get("sicher")]),
            len([e for e in filme_l if e["aktion"] == "review"]), len([e for e in filme_l if not e.get("sicher")])),
        "Auflösung: 4K %d · 1080p %d · 720p %d · SD %d · darunter %d · unbekannt %d" % (
            kl.get(2160, 0), kl.get(1080, 0), kl.get(720, 0), kl.get(576, 0), kl.get(360, 0), kl.get(0, 0)),
        "Ohne deutschen Ton (gemessen): %d · mit HDR: %d" % (
            len([e for e in gemessen if not e["q"].get("deutsch")]), len([e for e in gemessen if e["q"].get("hdr")])),
        "Qualität (Filme und Folgen): grün %d · gelb %d · rot %d · nicht gemessen %d" % (
            len(amp["gruen"]), len(amp["gelb"]), len(amp["rot"]), len(amp["grau"])),
        "Doppelt (schlechtere Fassungen, kommen nach _Pruefen/Doppelt): %d (%s GB)" % (
            len(doppelt_l), zahl(sum(e["bytes"] for e in doppelt_l) / 1e9)),
    ]
    if folgen_l:
        zeilen.append("Serien: %d Folgen in %d Serien" % (len(folgen_l), len({normal(e.get("serie")) for e in folgen_l})))
    reihen = {}
    for e in filme_l:
        if e.get("sammlung"):
            reihen.setdefault(e["sammlung"], []).append(e)
    if ZIELORDNER:
        if tmdb_schluessel():
            zeilen.append("Filmreihen (eigene Ordner in %s): %d mit %d Filmen" % (ZIELORDNER, len(reihen), sum(len(v) for v in reihen.values())))
        else:
            zeilen.append("Filmreihen: noch nicht erkannt – dafür fehlt tmdb_schluessel.txt neben dem Skript (Filme landen sonst alle flach)")
        rv, discs = {}, set()
        for e in plan:
            if e["aktion"] != "review":
                continue
            if e.get("disc"):
                if e["disc"] in discs:
                    continue
                discs.add(e["disc"])
            elif e["art"] not in ("film", "folge", "disc"):
                continue
            rv[e["review"]] = rv.get(e["review"], 0) + 1
        if rv:
            zeilen.append("Nach „%s“: " % REVIEWORDNER + " · ".join("%s: %d" % kv for kv in sorted(rv.items())))
    musik = [e for e in plan if e["art"] == "audio"]
    if musik:
        zeilen.append("Musik-Dateien außerhalb der Musik-Ordner: %d (%s GB) – %s" % (
            len(musik), zahl(sum(e["bytes"] for e in musik) / 1e9),
            "bleiben liegen (Runde 2)" if all(e["aktion"] == "lassen" for e in musik) else "werden einsortiert"))
    return {"ampel": amp, "zeilen": zeilen, "reihen": reihen}


def bericht_schreiben(quelle, plan, besser):
    d = sdir(quelle)
    zaehl = {}
    for e in plan:
        k = e["aktion"] if e.get("sicher") else "unsicher" if e["art"] in ("film", "folge", "disc") or e.get("gehoert_zu") else e["aktion"]
        z = zaehl.setdefault(k, [0, 0])
        z[0] += 1
        z[1] += e["bytes"]
    unsicher = [e for e in plan if not e.get("sicher") and e["art"] in ("film", "folge", "disc")]
    doppelt = [e for e in plan if e["aktion"] == "doppelt" and e.get("sicher")]
    beiseite = [e for e in plan if e["aktion"] == "beiseite" and e.get("sicher")]
    lassen = [e for e in plan if e["aktion"] == "lassen" and e["art"] not in ("film", "folge", "disc")]
    serien = {}
    for e in plan:
        if e["aktion"] == "serie" and e.get("sicher") and e["art"] == "folge":
            serien[e["serie"]] = serien.get(e["serie"], 0) + 1
    info = qualitaet_schreiben(d, plan)
    with open(os.path.join(d, "unsicher.tsv"), "w", encoding="utf-8") as f:
        f.write("nr\tpfad\tgb\tqualitaet\tvorschlag\tgrund\tkandidaten\n")
        for e in unsicher:
            f.write("%s\t%s\t%s\t%s\t%s\t%s\t%s\n" % (e["nr"], e["pfad"], gb(e["bytes"]), q_text(e.get("q")),
                                                    ("%s (%s)" % (e.get("vorschlag") or e.get("serie"), e.get("vorschlag_jahr") or "?")) if (e.get("vorschlag") or e.get("serie")) else "-",
                                                    e.get("grund"), " | ".join(e.get("kandidaten") or [])))
    namen = {"film": "Filme (sicher erkannt)", "serie": "Serien-Folgen", "musik": "Musik (Ordnung bleibt)",
             "doppelt": "Doppelt → _Pruefen/Doppelt", "beiseite": "Zusatzkram → _Pruefen/Beiseite",
             "lassen": "bleibt liegen", "unsicher": "unsicher (bleibt liegen, bis entschieden)"}
    z = ["# Bericht Filmsortierer – %s" % time.strftime("%d.%m.%Y %H:%M"), "",
         "Quelle: `%s` · Ziel: `%s` · Es wird nichts gelöscht und nichts überschrieben." % (quelle, ausgabe(quelle)), "",
         "## Überblick", ""] + ["- " + x for x in info["zeilen"]] + ["",
         "| Was | Dateien | GB |", "|---|---:|---:|"]
    for k in ("film", "serie", "musik", "doppelt", "beiseite", "unsicher", "lassen"):
        if k in zaehl:
            z.append("| %s | %d | %s |" % (namen[k], zaehl[k][0], gb(zaehl[k][1])))
    if serien:
        z += ["", "## Serien", ""] + ["- %s: %d Folgen" % (n, c) for n, c in sorted(serien.items())]
    if info.get("reihen"):
        z += ["", "## Filmreihen (%d)" % len(info["reihen"]), ""]
        z += ["- **%s**: %s" % (r, ", ".join(sorted(filmname(e) for e in es))) for r, es in sorted(info["reihen"].items())]
    for farbe, titel in (("rot", "Qualität ROT – wirklich schlecht oder kaputt, bitte ansehen"),
                         ("gelb", "Qualität GELB – mal reinschauen, oft trotzdem ok")):
        es = info["ampel"][farbe]
        z += ["", "## %s (%d) – Liste auch in qualitaet.tsv" % (titel, len(es)), ""]
        z += ["- %s · %s · `%s` – %s" % (e["nr"], filmname(e), e["pfad"], "; ".join(e.get("probleme") or [])) for e in es]
    if NICHT_LESBAR:
        z += ["", "## Nicht lesbar (%d) – NICHT geprüft, bitte am PC ansehen" % len(NICHT_LESBAR), ""]
        z += ["- `%s` – %s" % (p_, f_) for p_, f_ in NICHT_LESBAR]
    z += ["", "## Doppelt (%d) – wandert nach _Pruefen/Doppelt, die bessere Fassung kommt in die Bibliothek" % len(doppelt), ""]
    z += ["- `%s` (%s GB) – %s" % (e["pfad"], gb(e["bytes"]), e["grund"]) for e in doppelt]
    if besser:
        z += ["", "## Bessere Fassung als in der Bibliothek (%d) – alte Fassung von Hand prüfen" % len(besser), ""]
        z += ["- neu `%s` (%s) – bisher `%s`" % (e["pfad"], q_text(e.get("q")), e["besser_als"]) for e in besser]
    z += ["", "## Unsicher (%d) – bleibt liegen, bis entschieden (unsicher.tsv)" % len(unsicher), ""]
    z += ["- %s · `%s` – %s · Vorschlag: %s" % (e["nr"], e["pfad"], e.get("grund"), " | ".join(e.get("kandidaten") or []) or "-")
          for e in unsicher]
    gruende = {}
    for e in beiseite:
        gruende.setdefault(e["grund"], []).append(e)
    z += ["", "## Beiseite (%d) – wandert nach _Pruefen/Beiseite" % len(beiseite), ""]
    for g, es in sorted(gruende.items()):
        z.append("- **%s** (%d, %s GB)" % (g, len(es), gb(sum(x["bytes"] for x in es))))
        z += ["  - `%s`" % x["pfad"] for x in es]
    z += ["", "## Bleibt liegen (%d)" % len(lassen), ""] + ["- `%s` – %s" % (e["pfad"], e["grund"]) for e in lassen]
    open(os.path.join(d, "bericht.md"), "w", encoding="utf-8").write("\n".join(z) + "\n")
    return zaehl, unsicher, info


def zusammenfassung(quelle, plan):
    d = sdir(quelle)
    zaehl, unsicher, info = bericht_schreiben(quelle, plan, [e for e in plan if e.get("besser_als")])
    gesamt = sum(e["bytes"] for e in plan)
    print("Geprüft: %d Dateien, %s GB" % (len(plan), gb(gesamt)))
    print("  " + " · ".join("%s %d (%s GB)" % (k, v[0], gb(v[1])) for k, v in sorted(zaehl.items())))
    for x in info["zeilen"]:
        print("  " + x)
    print("Qualitäts-Liste:", os.path.join(d, "qualitaet.tsv"))
    if NICHT_LESBAR:
        print("ACHTUNG nicht lesbar: %d (nicht geprüft), z. B. %s – %s" % (len(NICHT_LESBAR), NICHT_LESBAR[0][0], NICHT_LESBAR[0][1]))
    print("Bericht für Lukas:", os.path.join(d, "bericht.md"))
    print("Unsichere für Claude:", os.path.join(d, "unsicher.tsv"), "(%d)" % len(unsicher))
    print("Weiter: entscheiden QUELLE NR=tmdb:… | dann ausfuehren QUELLE (Vorschau) | dann ausfuehren QUELLE --los")
    # Spiegel der Berichte neben dem Skript (am PC C:\Kino\bericht) – für Claude lesbar, ohne die Platte anzufassen
    ziel_ = os.path.join(HIER, "bericht")
    try:
        os.makedirs(ziel_, exist_ok=True)
        for n_ in ("bericht.md", "unsicher.tsv", "qualitaet.tsv"):
            if os.path.exists(os.path.join(d, n_)):
                shutil.copyfile(os.path.join(d, n_), os.path.join(ziel_, n_))
    except OSError:
        pass
    melde(d, "Plan fertig: %d Dateien, unsicher %d – Bericht liegt bereit" % (len(plan), len(unsicher)))


FILM_MIN = 300e6  # Videos ab 300 MB zählen in der Karte als Film (kleiner = Clip, Sample, Musikvideo)


def ist_musik(z):
    """Ordner-Summe z = [filme_n, filme_b, klein_n, klein_b, audio_n, audio_b, sonst_n, sonst_b]:
    kein Film, mindestens 5 Lieder, höchstens 1 kleines Video auf 5 Lieder (Musikvideos dürfen dabei sein)."""
    return z[0] == 0 and z[4] >= 5 and z[2] * 5 <= z[4]


def musik_ordner(quelle):
    """Ordner, die in Runde 1 gar nicht betreten werden: automatisch erkannte plus von Hand (bereich)."""
    b = lade(os.path.join(sdir(quelle), "bereiche.json"), None) or {}
    eigen = b.get("eigen") or {}
    m = set(b.get("auto") or []) | {p for p, art in eigen.items() if art == "musik"}
    return sorted(p for p in m if eigen.get(p) != "filme")


def lauf_optionen(quelle, neu=False):
    """Optionen von pruefen werden gemerkt (optionen.json), damit entscheiden und endtest denselben Plan bauen."""
    pfad = os.path.join(sdir(quelle), "optionen.json")
    if neu:
        o = {"ohne_tmdb": "--ohne-tmdb" in sys.argv, "ohne_bib": "--ohne-bibliothek" in sys.argv,
             "mit_musik": "--mit-musik" in sys.argv, "mit_serien": "--mit-serien" in sys.argv,
             "ziel": (opt("ziel") or "").strip().strip('"').strip("/\\") or None,
             "review": (opt("review") or "").strip().strip('"').strip("/\\") or None,
             "ohne_extra": [x.strip().strip('"') for x in (opt("ohne") or "").split(";") if x.strip()]}
        speichere(pfad, o)
    else:
        o = lade(pfad, None) or {"ohne_tmdb": False, "ohne_bib": "--ohne-bibliothek" in sys.argv,
                                 "mit_musik": "--mit-musik" in sys.argv, "ohne_extra": []}
    ohne = list(o.get("ohne_extra") or [])
    if not o.get("mit_musik"):
        ohne += musik_ordner(quelle)
    if o.get("ziel"):
        ohne += [o["ziel"], o.get("review") or REVIEW_STANDARD]  # schon Sortiertes nie wieder anfassen
    return {"ohne_tmdb": bool(o.get("ohne_tmdb")), "ohne_bib": bool(o.get("ohne_bib")), "ohne": ohne,
            "mit_musik": bool(o.get("mit_musik")), "ziel": o.get("ziel"), "mit_serien": bool(o.get("mit_serien")),
            "review": o.get("review")}


def cmd_karte(a):
    """Schneller Überblick: nur Ordnereinträge lesen (Namen, Größen), keine Datei öffnen. Sichert dabei den Bestand."""
    a, _ = optionen(a)
    quelle = os.path.abspath(a[0])
    if not os.path.isdir(L(quelle)):
        sys.exit("Ordner gibt es nicht: " + quelle)
    d = sdir(quelle)
    tiefe = int(opt("tiefe", "3"))
    del NICHT_LESBAR[:]
    name = os.path.join(d, "bestand_%s.tsv" % time.strftime("%Y-%m-%d_%H%M"))
    agg, ges, n, t0 = {}, [0] * 8, 0, time.time()
    with open(name, "w", encoding="utf-8") as f:
        f.write("pfad\tbytes\tgeaendert\n")
        for rel, g, mt in dateien(quelle):
            f.write("%s\t%d\t%s\n" % (rel, g, time.strftime("%Y-%m-%d %H:%M", time.localtime(mt))))
            art = art_grob(rel)
            i = 0 if art == "video" and g >= FILM_MIN else 2 if art == "video" else 4 if art == "audio" else 6
            ges[i] += 1
            ges[i + 1] += g
            teile = rel.split(os.sep)[:-1]
            for j in range(len(teile) + 1):
                z = agg.setdefault(os.sep.join(teile[:j]), [0] * 8)
                z[i] += 1
                z[i + 1] += g
            n += 1
            if n % 20000 == 0:
                melde(d, "karte: %d Dateien gelesen" % n)

    def art_von(z):
        if ist_musik(z):
            return "musik"
        if z[0] and z[4] >= 50:
            return "gemischt"
        if z[0]:
            return "filme"
        return "kleine Videos" if z[2] else "sonst"

    auto = sorted(k for k, z in agg.items() if k and ist_musik(z)
                  and not (os.path.dirname(k) and ist_musik(agg[os.path.dirname(k)])))
    alt = lade(os.path.join(d, "bereiche.json"), None) or {}
    speichere(os.path.join(d, "bereiche.json"), {"auto": auto, "eigen": alt.get("eigen") or {},
                                                  "stand": time.strftime("%Y-%m-%d %H:%M")})
    with open(os.path.join(d, "karte.tsv"), "w", encoding="utf-8") as f:
        f.write("ordner\ttiefe\tfilme\tfilme_gb\tkleine_videos\tmusik_dateien\tmusik_gb\tsonstige\tgesamt_gb\tart\n")
        for k in sorted(agg):
            t = k.count(os.sep) + 1 if k else 0
            if t > tiefe:
                continue
            z = agg[k]
            f.write("%s\t%d\t%d\t%s\t%d\t%d\t%s\t%d\t%s\t%s\n" % (k or ".", t, z[0], zahl(z[1] / 1e9), z[2], z[4],
                                                               zahl(z[5] / 1e9), z[6], zahl((z[1] + z[3] + z[5] + z[7]) / 1e9), art_von(z)))
    melde(d, "karte fertig: %d Dateien" % n)
    m = musik_ordner(quelle)
    print("Bestand gesichert: %s (%d Dateien, %s GB, %d s)" % (name, n, gb(sum(ges[1::2])), time.time() - t0))
    print("Ganze Platte: Filme (Videos ab 300 MB) %d (%s GB) · kleine Videos %d (%s GB) · Musik %d (%s GB) · Sonstiges %d (%s GB)" % (
        ges[0], gb(ges[1]), ges[2], gb(ges[3]), ges[4], gb(ges[5]), ges[6], gb(ges[7])))
    mz = [agg[k] for k in m if k in agg]
    print("Reine Musik-Ordner: %d (%s GB, %d Lieder) – werden bei pruefen gar nicht betreten:" % (
        len(m), gb(sum(z[5] + z[3] + z[7] for z in mz)), sum(z[4] for z in mz)))
    for k in sorted(m, key=lambda k: -(agg.get(k) or [0] * 8)[5])[:8]:
        z = agg.get(k) or [0] * 8
        print("   %s (%s GB, %d Lieder%s)" % (k, gb(z[5]), z[4], ", %d kleine Videos" % z[2] if z[2] else ""))
    klein = sum(agg[k][2] for k in m if k in agg)
    if klein:
        print("   darin %d kleine Videos unter 300 MB (Musikvideos?) – werden mit übersprungen; falls Filme: bereich ...=filme" % klein)
    oben = sorted((k for k in agg if k and os.sep not in k), key=lambda k: -sum(agg[k][1::2]))
    print("Oberste Ordner (%d, größte zuerst):" % len(oben))
    for k in oben[:30]:
        z = agg[k]
        print("   %-40s Filme %5d · Musik %6d · %8s GB · %s" % (k[:40], z[0], z[4], gb(sum(z[1::2])), art_von(z)))
    gem =[k for k in sorted(agg) if k and k.count(os.sep) < 2 and art_von(agg[k]) == "gemischt"]
    if gem:
        print("Gemischt (Filme und viel Musik zusammen – Musik darin wird in Runde 1 nur liegen gelassen): %s" % ", ".join(gem[:10]))
    if NICHT_LESBAR:
        print("ACHTUNG nicht lesbar: %d, z. B. %s – %s" % (len(NICHT_LESBAR), NICHT_LESBAR[0][0], NICHT_LESBAR[0][1]))
    print("Liste je Ordner: %s" % os.path.join(d, "karte.tsv"))
    print('Weiter: pruefen QUELLE --ohne-bibliothek   (Korrektur vorher: bereich QUELLE "Ordner"=filme|musik)')


def cmd_bereich(a):
    a, _ = optionen(a)
    quelle = os.path.abspath(a[0])
    d = sdir(quelle)
    b = lade(os.path.join(d, "bereiche.json"), None) or {"auto": [], "eigen": {}}
    for x in a[1:]:
        pfad, _, art = x.rpartition("=")
        art = art.strip().lower()
        pfad = os.path.normpath(pfad.strip().strip('"').strip("/\\"))
        if art not in ("musik", "filme") or not pfad or pfad == ".":
            print("übersprungen:", x)
            continue
        if not os.path.isdir(L(os.path.join(quelle, pfad))):
            print("Ordner gibt es nicht:", pfad)
            continue
        b.setdefault("eigen", {})[pfad] = art
        print(pfad, "->", art)
    speichere(os.path.join(d, "bereiche.json"), b)
    print("Musik-Ordner, die in Runde 1 übersprungen werden: %d" % len(musik_ordner(quelle)))


def cmd_tmdbtest(a):
    """Eine Probe-Anfrage an TMDB (Star Wars). Zeigt nie den Schlüssel."""
    if not tmdb_schluessel():
        sys.exit("FEHLER: keine tmdb_schluessel.txt neben dem Skript (%s)" % HIER)
    d = tmdb_api("/movie/11", {"language": "de-DE"})
    if not d:
        sys.exit("FEHLER: TMDB antwortet nicht (Internet?)")
    c = (d.get("belongs_to_collection") or {}).get("name")
    print("TMDB ok: %s (%s), Filmreihe: %s" % (d.get("title"), (d.get("release_date") or "")[:4], reihe_name(c)))


def cmd_pruefen(a):
    a, _ = optionen(a)
    quelle = os.path.abspath(a[0])
    if not os.path.isdir(L(quelle)):
        sys.exit("Ordner gibt es nicht: " + quelle)
    lo = lauf_optionen(quelle, neu=True)
    if not lo["mit_musik"]:
        tipp = "" if os.path.exists(os.path.join(sdir(quelle), "bereiche.json")) else " (Tipp: vorher karte laufen lassen)"
        print("Runde 1 – nur Filme: %d Musik-Ordner werden übersprungen, Musik-Dateien bleiben liegen%s" % (
            len(musik_ordner(quelle)), tipp))
    melde(sdir(quelle), "pruefen gestartet")
    plan = plan_bauen(quelle, ohne_ff="--ohne-ffprobe" in sys.argv, **lo)
    zusammenfassung(quelle, plan)


def ende_pruefen(ff, pfad):
    """Spielt die letzten Sekunden ab (nur lesen). Abgebrochene MKV-Kopien zeigen sonst die volle Laufzeit."""
    try:
        r = subprocess.run([ff, "-v", "error", "-nostdin", "-sseof", "-25", "-i", L(pfad), "-t", "10", "-map", "0:V:0",
                            "-f", "null", "-progress", "pipe:1", "-nostats", "-"], capture_output=True, timeout=240)
    except subprocess.TimeoutExpired:
        return "fehler: Zeitüberschreitung beim Abspielen"
    frames = [int(x.split("=", 1)[1]) for x in r.stdout.decode("utf-8", "replace").splitlines()
              if x.startswith("frame=") and x.split("=", 1)[1].strip().isdigit()]
    zeilen = [z.strip() for z in r.stderr.decode("utf-8", "replace").splitlines() if z.strip() and "Last message repeated" not in z]
    abgebrochen = [z for z in zeilen if re.search(r"(?i)ended prematurely|truncat|end of file|unexpected eof", z)]
    if r.returncode != 0 or not frames or frames[-1] == 0 or abgebrochen:
        return "kaputt: " + ((abgebrochen or zeilen or ["am Ende kein Bild"])[0])[:120]
    if len(zeilen) >= 5:
        return "fehler: %d Meldungen, z. B. %s" % (len(zeilen), zeilen[0][:80])
    return "ok"


def cmd_endtest(a):
    a, o = optionen(a)
    quelle = os.path.abspath(a[0])
    d = sdir(quelle)
    plan = lade(os.path.join(d, "plan.json"), None)
    if not plan:
        sys.exit("erst pruefen laufen lassen")
    ff = shutil.which("ffmpeg")
    if not ff:
        sys.exit("ffmpeg fehlt")
    icache = lade(os.path.join(d, "info_cache.json"), {})
    alle, neu_ = "--alle" in sys.argv, "--neu" in sys.argv
    jobs = [e for e in plan if e["art"] in ("film", "folge") and (alle or e["aktion"] in ("film", "serie", "review"))]
    geprueft = kaputt = fehler = 0
    t0 = time.time()
    for i, e in enumerate(jobs):
        k = "%s|%s" % (e["pfad"], e["bytes"])
        q = icache.get(k)
        if not q or q.get("quelle") != "ffprobe" or q.get("kaputt") or (q.get("ende") and not neu_):
            continue
        if not os.path.exists(L(os.path.join(quelle, e["pfad"]))):
            continue
        q["ende"] = ende_pruefen(ff, os.path.join(quelle, e["pfad"]))
        geprueft += 1
        kaputt += q["ende"].startswith("kaputt")
        fehler += q["ende"].startswith("fehler")
        if geprueft % 20 == 0:
            speichere(os.path.join(d, "info_cache.json"), icache, True)
            rest = (time.time() - t0) / geprueft * (len(jobs) - i - 1)
            melde(d, "endtest %d/%d, noch ca. %d min, Ende fehlt bisher bei %d" % (i + 1, len(jobs), rest / 60, kaputt))
    speichere(os.path.join(d, "info_cache.json"), icache, True)
    print("Endtest: %d geprüft in %d min – Ende fehlt (kaputt): %d · Fehlermeldungen: %d" % (
        geprueft, (time.time() - t0) / 60, kaputt, fehler))
    plan = plan_bauen(quelle, ohne_ff=True, **lauf_optionen(quelle))
    zusammenfassung(quelle, plan)


def cmd_entscheiden(a):
    a, _ = optionen(a)
    quelle = os.path.abspath(a[0])
    d = sdir(quelle)
    plan = lade(os.path.join(d, "plan.json"), None)
    if not plan:
        sys.exit("erst pruefen laufen lassen")
    nach_nr = {str(e["nr"]): e for e in plan}
    entsch = lade(os.path.join(d, "entscheidungen.json"), {})
    for x in a[1:]:
        nr, _, aktion = x.partition("=")
        if nr not in nach_nr or not aktion:
            print("übersprungen:", x)
            continue
        entsch[nach_nr[nr]["pfad"]] = aktion
        print(nr, nach_nr[nr]["pfad"], "->", aktion)
    speichere(os.path.join(d, "entscheidungen.json"), entsch)
    plan = plan_bauen(quelle, ohne_ff=True, **lauf_optionen(quelle))  # gleiche Optionen wie pruefen, Caches machen es schnell
    for x in a[1:]:
        nr = x.partition("=")[0]
        alt = nach_nr.get(nr)
        neu = next((e for e in plan if alt and e["pfad"] == alt["pfad"]), None)
        if neu:
            print("  ", nr, "->", neu.get("ziel") or neu["aktion"], "" if neu.get("sicher") else "(noch unsicher: %s)" % neu.get("grund"))
    zusammenfassung(quelle, plan)


def cmd_vorschau(a):
    a, _ = optionen(a)
    quelle = os.path.abspath(a[0])
    d = sdir(quelle)
    plan = {str(e["nr"]): e for e in lade(os.path.join(d, "plan.json"), [])}
    ff, fp = ffmpeg(), shutil.which("ffprobe")
    ziel_ordner = PRUEF if os.path.isdir(os.path.dirname(PRUEF)) else os.path.join(d, "vorschau")
    os.makedirs(ziel_ordner, exist_ok=True)
    for nr in a[1:]:
        e = plan.get(nr)
        if not e:
            print(nr, "gibt es nicht")
            continue
        pfad = L(os.path.join(quelle, e["pfad"]))
        dauer = ((e.get("q") or {}).get("min") or 0) * 60
        if not dauer and fp:
            r = subprocess.run([fp, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", pfad], capture_output=True, timeout=60)
            try:
                dauer = float(r.stdout.strip() or 0)
            except ValueError:
                dauer = 0
        if not dauer:
            print(nr, "Laufzeit unbekannt")
            continue
        punkte = [60, 150, 300] + [dauer * x for x in (0.15, 0.25, 0.35, 0.45, 0.55, 0.65, 0.75, 0.85, 0.97)]
        with tempfile.TemporaryDirectory() as t:
            n = 0
            for s in punkte:
                if s >= dauer:
                    continue
                out = os.path.join(t, "b%02d.jpg" % n)
                subprocess.run([ff, "-v", "quiet", "-ss", str(int(s)), "-i", pfad, "-frames:v", "1", "-vf", "scale=480:-2", "-y", out], timeout=120)
                if os.path.exists(out):
                    n += 1
            ziel = os.path.join(ziel_ordner, "e%s.jpg" % nr)
            if n:
                subprocess.run([ff, "-v", "quiet", "-i", os.path.join(t, "b%02d.jpg"), "-vf",
                                "pad=480:ih+8:0:4:black,tile=3x%d" % ((n + 2) // 3), "-frames:v", "1", "-q:v", "4", "-y", ziel], timeout=120)
                print(nr, "->", ziel, "(im Heimnetz: http://192.168.178.44:8098/pruefung/e%s.jpg)" % nr if ziel_ordner == PRUEF else "")


def cmd_ausfuehren(a):
    a, o = optionen(a)
    quelle = os.path.abspath(a[0])
    d = sdir(quelle)
    plan = lade(os.path.join(d, "plan.json"), None)
    if not plan:
        sys.exit("erst pruefen laufen lassen")
    jobs = [e for e in plan if e.get("sicher") and e.get("ziel")
            and e["aktion"] in ("film", "serie", "musik", "doppelt", "beiseite", "review")]
    fehlt, besetzt, ok = [], [], []
    for e in jobs:
        src, dst = os.path.join(quelle, e["pfad"]), os.path.join(quelle, e["ziel"])
        if not os.path.exists(L(src)):
            fehlt.append(e)
        elif os.path.exists(L(dst)):
            besetzt.append(e)
        else:
            ok.append(e)
    # eine DVD/Blu-ray wird nur ganz oder gar nicht verschoben
    kaputte_gruppen = {e.get("disc") for e in fehlt + besetzt if e.get("disc")}
    if kaputte_gruppen:
        ok = [e for e in ok if e.get("disc") not in kaputte_gruppen]
        print("DVD/Blu-ray bleibt ganz liegen (eine Datei fehlt oder Ziel besetzt): %d" % len(kaputte_gruppen))
    zaehl = {}
    for e in ok:
        z = zaehl.setdefault(e["aktion"], [0, 0])
        z[0] += 1
        z[1] += e["bytes"]
    print("Bereit:", " · ".join("%s %d (%s GB)" % (k, v[0], gb(v[1])) for k, v in sorted(zaehl.items())) or "nichts")
    if besetzt:
        print("Ziel gibt es schon (wird NICHT überschrieben, bleibt liegen): %d, z. B. %s" % (len(besetzt), besetzt[0]["ziel"]))
    if fehlt:
        print("Quelle fehlt inzwischen: %d" % len(fehlt))
    if not o["los"]:
        for e in ok[:int(opt("zeigen", "12"))]:
            print("  %s\n    -> %s" % (e["pfad"], e["ziel"]))
        print("(nur Vorschau – mit --los ausführen)")
        return
    lauf = time.strftime("%Y-%m-%d_%H%M%S")
    log = os.path.join(d, "verschoben_%s.jsonl" % lauf)
    n = kopiert = fehler = 0
    with open(log, "a", encoding="utf-8") as lg:
        for i, e in enumerate(ok):
            src, dst = os.path.join(quelle, e["pfad"]), os.path.join(quelle, e["ziel"])
            try:
                os.makedirs(L(os.path.dirname(dst)), exist_ok=True)
                if os.path.exists(L(dst)):
                    continue
                zeile = {"alt": e["pfad"], "neu": e["ziel"], "aktion": e["aktion"], "basis": "quelle"}
                # erst ins Protokoll (sofort auf die Platte), dann verschieben: auch bei Stromausfall mitten im Lauf
                # weiß zurueck-lauf, was wo liegt (es schiebt nur zurück, was wirklich am neuen Platz liegt)
                lg.write(json.dumps(dict(zeile, art="verschoben"), ensure_ascii=False) + "\n")
                lg.flush()
                os.fsync(lg.fileno())
                try:
                    os.rename(L(src), L(dst))
                except OSError as x:
                    if x.errno != errno.EXDEV and getattr(x, "winerror", 0) != 17:  # andere Platte -> kopieren
                        raise
                    shutil.copy2(L(src), L(dst))
                    if os.path.getsize(L(dst)) != e["bytes"] or fingerabdruck(dst, e["bytes"]) != fingerabdruck(src, e["bytes"]):
                        raise IOError("Kopie stimmt nicht: " + dst)
                    lg.write(json.dumps(dict(zeile, art="kopiert (Quelle bleibt, andere Platte)"), ensure_ascii=False) + "\n")
                    lg.flush()
                    kopiert += 1
                n += 1
            except Exception as x:
                fehler += 1
                print("Fehler bei", e["pfad"], ":", x)
            if i % 200 == 0:
                melde(d, "ausfuehren %d/%d" % (i, len(ok)))
    print("Fertig: %d erledigt (%d davon kopiert), %d Fehler. Protokoll: %s" % (n, kopiert, fehler, log))
    melde(d, "ausfuehren fertig: %d verschoben, %d Fehler" % (n, fehler))
    print("Rückgängig: zurueck-lauf QUELLE --los")


def cmd_zurueck_lauf(a):
    a, o = optionen(a)
    quelle = os.path.abspath(a[0])
    d, aus = sdir(quelle), ausgabe(quelle)
    laeufe = sorted(x for x in os.listdir(d) if x.startswith("verschoben_") and x.endswith(".jsonl"))
    if not laeufe:
        sys.exit("kein Lauf zum Zurücknehmen")
    log = os.path.join(d, laeufe[-1])
    zeilen = [json.loads(z) for z in open(log, encoding="utf-8").read().splitlines() if z.strip()]
    print("Lauf %s: %d Dateien zurück an den alten Platz" % (laeufe[-1], len(zeilen)))
    if not o["los"]:
        print("(nur Vorschau – mit --los ausführen)")
        return
    n = 0
    for z in reversed(zeilen):
        src, dst = os.path.join(quelle if z.get("basis") == "quelle" else aus, z["neu"]), os.path.join(quelle, z["alt"])
        if z["art"].startswith("kopiert"):
            continue  # Quelle liegt noch da, die Kopie bleibt einfach stehen
        if os.path.exists(L(src)) and not os.path.exists(L(dst)):
            os.makedirs(L(os.path.dirname(dst)), exist_ok=True)
            os.rename(L(src), L(dst))
            n += 1
    os.replace(log, log + ".zurueckgenommen")
    print("zurückgeschoben:", n)


def cmd_kopieren(a):
    a, o = optionen(a)
    von, nach = os.path.abspath(a[0]), os.path.abspath(a[1])
    lauf = time.strftime("%Y-%m-%d_%H%M%S")
    neu = gleich = konflikt = fehler = 0
    summe = 0
    if o["los"]:
        os.makedirs(nach, exist_ok=True)
    lg = open(os.path.join(nach, ".kopierprotokoll_%s.jsonl" % lauf), "a", encoding="utf-8") if o["los"] else None
    for rel, g, mt in dateien(von, ohne_ausgabe=False):
        dst = os.path.join(nach, rel)
        if os.path.exists(L(dst)):
            if os.path.getsize(L(dst)) == g and fingerabdruck(dst, g) == fingerabdruck(os.path.join(von, rel), g):
                gleich += 1
            else:
                konflikt += 1
                print("Konflikt (anderer Inhalt, nicht angefasst):", rel)
            continue
        neu += 1
        summe += g
        if not o["los"]:
            continue
        try:
            os.makedirs(L(os.path.dirname(dst)), exist_ok=True)
            shutil.copy2(L(os.path.join(von, rel)), L(dst))
            if os.path.getsize(L(dst)) != g or fingerabdruck(dst, g) != fingerabdruck(os.path.join(von, rel), g):
                raise IOError("Prüfsumme stimmt nicht")
            if lg:
                lg.write(json.dumps({"datei": rel, "bytes": g}, ensure_ascii=False) + "\n")
                lg.flush()
        except Exception as x:
            fehler += 1
            print("Fehler:", rel, x)
    print("%s: %d neu (%s GB), %d schon gleich da, %d Konflikte, %d Fehler. Die Quelle bleibt unberührt." % (
        "Kopiert" if o["los"] else "Vorschau", neu, gb(summe), gleich, konflikt, fehler))
    if not o["los"]:
        print("(nur Vorschau – mit --los ausführen)")


BEFEHLE = {"tmdbtest": cmd_tmdbtest, "karte": cmd_karte, "bestand": cmd_karte, "bereich": cmd_bereich, "endtest": cmd_endtest, "pruefen": cmd_pruefen, "entscheiden": cmd_entscheiden, "vorschau": cmd_vorschau,
           "ausfuehren": cmd_ausfuehren, "zurueck-lauf": cmd_zurueck_lauf, "kopieren": cmd_kopieren,
           "inventar": cmd_inventar, "offen": cmd_offen, "suche": cmd_suche, "bilder": cmd_bilder,
           "doppelte": cmd_doppelte, "umbenennen": cmd_umbenennen, "stapel": cmd_stapel, "scan": cmd_scan, "titel": cmd_titel, "zurueck": cmd_zurueck}

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in BEFEHLE:
        print(__doc__)
        sys.exit(1)
    BEFEHLE[sys.argv[1]](sys.argv[2:])
