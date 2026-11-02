#!/usr/bin/env python3
"""Filmsortierer für Lukas' Jellyfin (Claude, Konto A, 02.11.2026 – v2 mit Eingangs-Modus für ganze Platten).

Läuft im File editor auf dem Pi (dort sind /media und /share eingehängt) und genauso am Windows-PC (Python 3.8+).
Braucht nur Python. ffprobe/ffmpeg sind optional (bessere Qualitätsvergleiche, Kontaktbögen).
Der Jellyfin-Schlüssel wird aus jellyfin_schluessel.txt gelesen und nie ausgegeben.
GRUNDREGEL: Es wird NIE etwas gelöscht oder überschrieben. Alles ohne --los ist nur Vorschau.

A) Eingang – eine ganze Platte oder einen Ordner mit unsortierten Filmen, Serien, Musik durchgehen
  bestand QUELLE                vorhandene Ordnung festhalten: Liste aller Dateien -> QUELLE/Kino/_sortierer/bestand_*.tsv
  pruefen QUELLE                alles einordnen (Film, Serie, Musik, Untertitel, Zusatzkram), Filme bei TMDB erkennen,
                                Doppelte finden (exakt gleiche Datei, gleicher Film, schon in der Bibliothek)
                                -> Kino/_sortierer/plan.json, bericht.md (für Lukas), unsicher.tsv (für Claude)
                                Optionen: --ohne-tmdb  --ohne-ffprobe
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


def kandidaten(name, jahr=None):
    namen = [name]
    umlaut = re.sub(r"ae", "ä", re.sub(r"oe", "ö", re.sub(r"ue", "ü", name)))
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


def dateien(quelle, ohne_ausgabe=True):
    """Alle Dateien unter QUELLE: (relativer Pfad, Bytes, Änderungszeit) – ohne Systemordner und ohne den Ausgabe-Ordner."""
    aus = os.path.normcase(os.path.abspath(ausgabe(quelle))) if ohne_ausgabe else None
    for wurzel, ordner, namen in os.walk(quelle):
        ordner[:] = sorted(o for o in ordner if o not in SYSTEM and not o.startswith(".Trash")
                           and os.path.normcase(os.path.abspath(os.path.join(wurzel, o))) != aus)
        for n in sorted(namen):
            p = os.path.join(wurzel, n)
            try:
                st = os.stat(p)
            except OSError:
                continue
            yield os.path.relpath(p, quelle), st.st_size, int(st.st_mtime)


def fingerabdruck(pfad, groesse):
    """Schnelle Prüfsumme: Größe + erste und letzte 4 MiB (reicht, um gleiche Dateien sicher zu erkennen)."""
    h = hashlib.md5(str(groesse).encode())
    with open(pfad, "rb") as f:
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
        return ordn
    if datei:
        return datei
    if ordn:
        return ordn
    if kurz and n:
        return n, j, "kurzform"
    return stem, None, "unklar"


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
    fp = shutil.which("ffprobe")
    if not fp:
        return None
    try:
        r = subprocess.run([fp, "-v", "error", "-show_entries",
                            "stream=codec_type,codec_name,width,height,channels,color_transfer:stream_tags=language,title:format=duration",
                            "-of", "json", pfad], capture_output=True, timeout=60)
        d = json.loads(r.stdout or b"{}")
    except Exception:
        return None
    st = d.get("streams") or []
    v = [x for x in st if x.get("codec_type") == "video" and x.get("codec_name") not in ("mjpeg", "png")]
    if not v:
        return None  # nicht lesbar -> Qualität aus dem Dateinamen
    a = [x for x in st if x.get("codec_type") == "audio"]
    spr = [((x.get("tags") or {}).get("language") or "?").lower() for x in a]
    deutsch = any(s in ("ger", "deu", "de", "german") for s in spr)
    if not deutsch and all(s in ("?", "und") for s in spr):
        deutsch = qualitaet_aus_name(pfad, groesse)["deutsch"]  # Spuren ohne Sprache: Dateiname entscheidet
    return {"deutsch": deutsch, "h": v[0].get("height") or 0,
            "hdr": any(x.get("color_transfer") in ("smpte2084", "arib-std-b67") for x in v),
            "kanaele": max([x.get("channels") or 0 for x in a] or [0]), "bytes": groesse, "ton": spr,
            "min": round(float((d.get("format") or {}).get("duration") or 0) / 60, 1), "quelle": "ffprobe"}


def rang(q):
    """Bessere Fassung: deutscher Ton vor Auflösung vor HDR vor Tonkanälen vor Größe (Vorgabe Lukas)."""
    return (1 if q.get("deutsch") else 0, q.get("h") or 0, 1 if q.get("hdr") else 0, q.get("kanaele") or 0, q.get("bytes") or 0)


def q_text(q):
    if not q:
        return "?"
    t = []
    if q.get("h"):
        t.append("%dp" % q["h"])
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
    kand = [x for x in cache[k] if not x.get("fehler")]
    if not kand:
        return None, False, "kein Treffer bei TMDB", []
    nn = normal(name)
    exakt = [x for x in kand if normal(x.get("name")) == nn]
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
        q = {"deutsch": any(t.split(" ")[0] in ("ger", "deu") for t in i["ton"]), "h": h, "hdr": False,
             "kanaele": max([int(re.sub(r"\D", "", t.split(" ")[-1]) or 0) for t in i["ton"]] or [0]),
             "bytes": i["bytes"], "quelle": "jellyfin"}
        lib.setdefault(str(i["tmdb"]), []).append((i["pfad"], q))
    return lib


def per_id(art, wert, cache):
    """Titel und Jahr zu einer TMDB- oder IMDb-ID (über Jellyfin, gecacht)."""
    k = "id|%s|%s" % (art, wert)
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
    a, _, rest = aktion.partition(":")
    e["entschieden"] = aktion
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


def ziel_setzen(e):
    """Zielpfad (relativ zum Ausgabe-Ordner) aus der Aktion."""
    rel, ext = e["pfad"], os.path.splitext(e["pfad"])[1].lower()
    a = e["aktion"]
    if a == "film":
        o = film_ordner(e.get("titel"), e.get("jahr"), e.get("tmdb"), e.get("imdb"))
        teil = (" - part%s" % e["teil"]) if e.get("teil") else ""
        e["ziel"] = os.path.join("Filme", o, o + teil + ext)
    elif a == "serie":
        e["ziel"] = os.path.join("Serien", sauber(e.get("serie") or "Unbekannt"), "Staffel %02d" % (e.get("staffel") or 1),
                                 os.path.basename(rel))
    elif a == "musik":
        teile = rel.split(os.sep)
        if len(teile) > 1 and teile[0].lower() in ("musik", "music", "mp3", "audio"):
            teile = teile[1:]  # „Musik/Musik/…“ vermeiden
        e["ziel"] = os.path.join("Musik", *teile)
    elif a == "doppelt":
        e["ziel"] = os.path.join("_Pruefen", "Doppelt", rel)
    elif a == "beiseite":
        e["ziel"] = os.path.join("_Pruefen", "Beiseite", rel)
    else:
        e["ziel"] = None


def plan_bauen(quelle, ohne_tmdb=False, ohne_ff=False, still=False):
    d = sdir(quelle)
    tcache = lade(os.path.join(d, "tmdb_cache.json"), {})
    icache = lade(os.path.join(d, "info_cache.json"), {})
    hcache = lade(os.path.join(d, "hash_cache.json"), {})
    entsch = lade(os.path.join(d, "entscheidungen.json"), {})
    if not shutil.which("ffprobe"):
        ohne_ff = True
    plan = []
    for rel, g, mt in dateien(quelle):
        plan.append({"nr": len(plan) + 1, "pfad": rel, "bytes": g, "art": art_grob(rel)})
    ordner_inhalt = {}
    for e in plan:
        ordner_inhalt.setdefault(os.path.dirname(e["pfad"]), set()).add(e["art"])

    def fortschritt(t):
        try:
            open(os.path.join(d, "fortschritt.txt"), "w", encoding="utf-8").write(time.strftime("%H:%M:%S ") + t + "\n")
        except OSError:
            pass

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
                    n, j, herkunft = film_vorschlag(rel, filmordner.get(os.path.dirname(rel), 0) == 1)
                    tm = TEIL.search(os.path.splitext(teile[-1])[0])
                    e.update(art="film", aktion="film", vorschlag=n, vorschlag_jahr=j, herkunft=herkunft,
                             teil=int(tm.group(2)) if tm else None)
        elif a == "audio":
            e.update(aktion="musik", grund="Musik (Ordnung bleibt)")
        elif a in ("bild", "neben", "system"):
            arten = ordner_inhalt.get(os.path.dirname(rel), set())
            if "audio" in arten and "video" not in arten and a != "system":
                e.update(aktion="musik", grund="gehört zum Album (Cover/Liste)")
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
    speichere(os.path.join(d, "info_cache.json"), icache, True)

    # 3) Filme bei TMDB erkennen (über Jellyfin, gecacht)
    filmliste = [e for e in plan if e["art"] == "film"]
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

    # 4) Entscheidungen von Claude/Lukas (nach Pfad gespeichert)
    for e in plan:
        if e["pfad"] in entsch:
            entscheidung_anwenden(e, entsch[e["pfad"]], tcache)
    speichere(os.path.join(d, "tmdb_cache.json"), tcache)

    # 5) Doppelte: a) exakt gleiche Dateien (gleiche Größe, dann Prüfsumme)
    nach_groesse = {}
    for e in plan:
        if e["art"] in ("film", "folge", "audio", "zusatz", "disc") and e["bytes"] > 1 << 20:
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
            es2.sort(key=lambda x: (0 if x.get("sicher") else 1, len(x["pfad"]), x["pfad"]))
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
        es.sort(key=lambda x: rang(x.get("q") or {}), reverse=True)
        for x in es[1:]:
            if x.get("entschieden"):
                continue
            x.update(aktion="doppelt", doppelt_von=es[0]["pfad"],
                     grund="gleicher %s, schlechtere Fassung (%s) als %s (%s)" % (
                         "Film" if k[0] == "f" else "Folge", q_text(x.get("q")), es[0]["pfad"], q_text(es[0].get("q"))))

    # c) schon in der Jellyfin-Bibliothek?
    besser = []
    if not ohne_tmdb:
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
        if not zusatz:
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
        if v.get("ziel") and v["aktion"] in ("film", "serie"):
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
         "| Was | Dateien | GB |", "|---|---:|---:|"]
    for k in ("film", "serie", "musik", "doppelt", "beiseite", "unsicher", "lassen"):
        if k in zaehl:
            z.append("| %s | %d | %s |" % (namen[k], zaehl[k][0], gb(zaehl[k][1])))
    if serien:
        z += ["", "## Serien", ""] + ["- %s: %d Folgen" % (n, c) for n, c in sorted(serien.items())]
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
    return zaehl, unsicher


def zusammenfassung(quelle, plan):
    d = sdir(quelle)
    zaehl, unsicher = bericht_schreiben(quelle, plan, [e for e in plan if e.get("besser_als")])
    gesamt = sum(e["bytes"] for e in plan)
    print("Geprüft: %d Dateien, %s GB" % (len(plan), gb(gesamt)))
    print("  " + " · ".join("%s %d (%s GB)" % (k, v[0], gb(v[1])) for k, v in sorted(zaehl.items())))
    print("Bericht für Lukas:", os.path.join(d, "bericht.md"))
    print("Unsichere für Claude:", os.path.join(d, "unsicher.tsv"), "(%d)" % len(unsicher))
    print("Weiter: entscheiden QUELLE NR=tmdb:… | dann ausfuehren QUELLE (Vorschau) | dann ausfuehren QUELLE --los")


def cmd_bestand(a):
    a, _ = optionen(a)
    quelle = os.path.abspath(a[0])
    d = sdir(quelle)
    name = os.path.join(d, "bestand_%s.tsv" % time.strftime("%Y-%m-%d_%H%M"))
    arten, n, summe = {}, 0, 0
    with open(name, "w", encoding="utf-8") as f:
        f.write("pfad\tbytes\tgeaendert\n")
        for rel, g, mt in dateien(quelle):
            f.write("%s\t%d\t%s\n" % (rel, g, time.strftime("%Y-%m-%d %H:%M", time.localtime(mt))))
            k = art_grob(rel)
            z = arten.setdefault(k, [0, 0])
            z[0] += 1
            z[1] += g
            n += 1
            summe += g
    print("Bestand gesichert:", name)
    print("%d Dateien, %s GB:" % (n, gb(summe)), " · ".join("%s %d (%s GB)" % (k, v[0], gb(v[1])) for k, v in sorted(arten.items())))


def cmd_pruefen(a):
    a, _ = optionen(a)
    quelle = os.path.abspath(a[0])
    if not os.path.isdir(quelle):
        sys.exit("Ordner gibt es nicht: " + quelle)
    plan = plan_bauen(quelle, ohne_tmdb="--ohne-tmdb" in sys.argv, ohne_ff="--ohne-ffprobe" in sys.argv)
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
    plan = plan_bauen(quelle, ohne_tmdb="--ohne-tmdb" in sys.argv, ohne_ff=True)  # Caches machen das schnell
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
        pfad = os.path.join(quelle, e["pfad"])
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
    d, aus = sdir(quelle), ausgabe(quelle)
    plan = lade(os.path.join(d, "plan.json"), None)
    if not plan:
        sys.exit("erst pruefen laufen lassen")
    jobs = [e for e in plan if e.get("sicher") and e.get("ziel") and e["aktion"] in ("film", "serie", "musik", "doppelt", "beiseite")]
    fehlt, besetzt, ok = [], [], []
    for e in jobs:
        src, dst = os.path.join(quelle, e["pfad"]), os.path.join(aus, e["ziel"])
        if not os.path.exists(src):
            fehlt.append(e)
        elif os.path.exists(dst):
            besetzt.append(e)
        else:
            ok.append(e)
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
        for e in ok[:12]:
            print("  %s\n    -> %s" % (e["pfad"], e["ziel"]))
        print("(nur Vorschau – mit --los ausführen)")
        return
    lauf = time.strftime("%Y-%m-%d_%H%M%S")
    log = os.path.join(d, "verschoben_%s.jsonl" % lauf)
    n = kopiert = fehler = 0
    with open(log, "a", encoding="utf-8") as lg:
        for i, e in enumerate(ok):
            src, dst = os.path.join(quelle, e["pfad"]), os.path.join(aus, e["ziel"])
            try:
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                if os.path.exists(dst):
                    continue
                try:
                    os.rename(src, dst)
                    art = "verschoben"
                except OSError as x:
                    if x.errno != errno.EXDEV and getattr(x, "winerror", 0) != 17:  # andere Platte -> kopieren
                        raise
                    shutil.copy2(src, dst)
                    if os.path.getsize(dst) != e["bytes"] or fingerabdruck(dst, e["bytes"]) != fingerabdruck(src, e["bytes"]):
                        raise IOError("Kopie stimmt nicht: " + dst)
                    art = "kopiert (Quelle bleibt, andere Platte)"
                    kopiert += 1
                lg.write(json.dumps({"alt": e["pfad"], "neu": e["ziel"], "art": art, "aktion": e["aktion"]}, ensure_ascii=False) + "\n")
                n += 1
            except Exception as x:
                fehler += 1
                print("Fehler bei", e["pfad"], ":", x)
            if i % 200 == 0:
                open(os.path.join(d, "fortschritt.txt"), "w", encoding="utf-8").write("ausfuehren %d/%d\n" % (i, len(ok)))
    print("Fertig: %d erledigt (%d davon kopiert), %d Fehler. Protokoll: %s" % (n, kopiert, fehler, log))
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
        src, dst = os.path.join(aus, z["neu"]), os.path.join(quelle, z["alt"])
        if z["art"].startswith("kopiert"):
            continue  # Quelle liegt noch da, die Kopie bleibt einfach stehen
        if os.path.exists(src) and not os.path.exists(dst):
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            os.rename(src, dst)
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
        if os.path.exists(dst):
            if os.path.getsize(dst) == g and fingerabdruck(dst, g) == fingerabdruck(os.path.join(von, rel), g):
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
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(os.path.join(von, rel), dst)
            if os.path.getsize(dst) != g or fingerabdruck(dst, g) != fingerabdruck(os.path.join(von, rel), g):
                raise IOError("Prüfsumme stimmt nicht")
            if lg:
                lg.write(json.dumps({"datei": rel, "bytes": g}, ensure_ascii=False) + "\n")
        except Exception as x:
            fehler += 1
            print("Fehler:", rel, x)
    print("%s: %d neu (%s GB), %d schon gleich da, %d Konflikte, %d Fehler. Die Quelle bleibt unberührt." % (
        "Kopiert" if o["los"] else "Vorschau", neu, gb(summe), gleich, konflikt, fehler))
    if not o["los"]:
        print("(nur Vorschau – mit --los ausführen)")


BEFEHLE = {"bestand": cmd_bestand, "pruefen": cmd_pruefen, "entscheiden": cmd_entscheiden, "vorschau": cmd_vorschau,
           "ausfuehren": cmd_ausfuehren, "zurueck-lauf": cmd_zurueck_lauf, "kopieren": cmd_kopieren,
           "inventar": cmd_inventar, "offen": cmd_offen, "suche": cmd_suche, "bilder": cmd_bilder,
           "doppelte": cmd_doppelte, "umbenennen": cmd_umbenennen, "stapel": cmd_stapel, "scan": cmd_scan, "titel": cmd_titel, "zurueck": cmd_zurueck}

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in BEFEHLE:
        print(__doc__)
        sys.exit(1)
    BEFEHLE[sys.argv[1]](sys.argv[2:])
