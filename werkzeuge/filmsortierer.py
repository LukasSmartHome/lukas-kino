#!/usr/bin/env python3
"""Filmsortierer für Lukas' Jellyfin (Claude, Konto A, 02.11.2026).

Läuft im File editor auf dem Pi (dort sind /media und /share eingehängt), braucht nur Python.
Für Standbilder zusätzlich ffmpeg (im File editor: apk add ffmpeg – verschwindet beim Neustart der App wieder).
Der Jellyfin-Schlüssel wird aus /config bzw. /homeassistant/jellyfin_schluessel.txt gelesen und nie ausgegeben.

Befehle (alles ohne --los ist nur Anschauen, es wird nichts verändert):
  inventar                      alle Filme mit Größe, Laufzeit, Ton, TMDB-ID, Ordner -> /share/kino/pruefung/inventar.json
  offen                         Filme ohne TMDB-/IMDb-Zuordnung, mit Namensvorschlag aus dem Dateinamen
  suche NAME [JAHR]             Kandidaten bei TMDB (über Jellyfin, kein eigener TMDB-Schlüssel nötig)
  bilder ID [ID ...]            Kontaktbogen mit 12 Standbildern -> /share/kino/pruefung/ID.jpg
                                (im Heimnetz: http://192.168.178.44:8098/pruefung/ID.jpg)
  doppelte                      gleiche TMDB-ID, gleicher Titel+Jahr oder gleiche Größe+Laufzeit
  umbenennen ID TMDB|ttIMDB [--titel=Eigener_Titel] [--jahr=JJJJ] [--los]
                                Datei nach Schema „Titel (Jahr) [tmdbid-N]/Titel (Jahr) [tmdbid-N].ext“
                                in /media/Filme verschieben (Titel und Jahr von TMDB), Jellyfin neu einlesen.
                                Ohne TMDB-Eintrag mit IMDb-ID: [imdbid-tt...].
  stapel ID,ZIEL[,--titel=..] ... [--los]   mehrere auf einmal
  titel ID Neuer_Titel          Anzeigename in Jellyfin setzen und sperren
                                Jede Verschiebung steht in /media/Filme/.filmsortierer_log.jsonl (Rückweg).
  zurueck [--los]               letzte Verschiebung rückgängig machen
"""
import json, os, re, shutil, subprocess, sys, tempfile, time, unicodedata, urllib.parse, urllib.request

S = "http://192.168.178.44:8096"
ZIEL = "/media/Filme"
PRUEF = "/share/kino/pruefung"
LOG = ZIEL + "/.filmsortierer_log.jsonl"
DATEI_TYPEN = (".mkv", ".mp4", ".avi", ".m4v", ".ts", ".mov", ".wmv", ".mpg", ".mpeg", ".m2ts", ".webm")
MUELL = r"(german|deutsch|dl|ml|dual|ac3|eac3|dts|dd5[ .]?1|truehd|atmos|720p|1080p|2160p|4k|uhd|hdr|sdr|web|webrip|web-?dl|bluray|bdrip|brrip|hdtv|dvdrip|x264|x265|h264|h265|hevc|avc|remux|repack|proper|internal|extended|uncut|unrated|ld|md|sd|hd)"


def schluessel():
    for p in ("/config/jellyfin_schluessel.txt", "/homeassistant/jellyfin_schluessel.txt"):
        if os.path.exists(p):
            return open(p, encoding="utf-8").read().strip().strip('"')
    sys.exit("kein Jellyfin-Schlüssel gefunden")


KEY = schluessel()
H = {"Authorization": 'MediaBrowser Token="%s"' % KEY, "Content-Type": "application/json"}


def jf(pfad, body=None, methode=None, roh=False):
    d = json.dumps(body).encode() if body is not None else None
    m = methode or ("POST" if d is not None else "GET")
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
    m = re.search(r"(?<!\d)(19[2-9]\d|20[0-4]\d)(?!\d)", n)
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


BEFEHLE = {"inventar": cmd_inventar, "offen": cmd_offen, "suche": cmd_suche, "bilder": cmd_bilder,
           "doppelte": cmd_doppelte, "umbenennen": cmd_umbenennen, "stapel": cmd_stapel, "titel": cmd_titel, "zurueck": cmd_zurueck}

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in BEFEHLE:
        print(__doc__)
        sys.exit(1)
    BEFEHLE[sys.argv[1]](sys.argv[2:])
