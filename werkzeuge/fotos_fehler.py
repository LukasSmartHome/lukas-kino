#!/usr/bin/env python3
"""Welche Fotos kann Jellyfin nicht als Vorschaubild liefern? (03.11.2026, Konto B)

Geht alle Fotos der Foto-Bibliothek durch, fragt das kleine Vorschaubild (fillWidth=360, wie die Bilderwand) ab
und schreibt die Fehler nach fotos_fehler.txt (Status, Dateigröße, Pfad) und eine Zusammenfassung nach
fotos_fehler_zusammenfassung.txt (nach Endung, Ordner, Status, Größe, erste Fehlermeldungen).
Nur lesen – ändert nichts. Aufruf: nice -n 15 python3 fotos_fehler.py
"""
import collections, json, os, sys, time, urllib.error, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor

S = "http://192.168.178.44:8096"
WERK = os.path.dirname(os.path.abspath(__file__))
KEY = open("/homeassistant/jellyfin_schluessel.txt", encoding="utf-8").read().strip()
KOPF = {"Authorization": 'MediaBrowser Token="{}"'.format(KEY)}


def holen(pfad, timeout=60):
    with urllib.request.urlopen(urllib.request.Request(S + pfad, headers=KOPF), timeout=timeout) as r:
        return r.read()


def j(pfad):
    return json.loads(holen(pfad))


user = j("/Users")[0]["Id"]
fotos = None
for v in j("/UserViews?userId=" + user).get("Items") or []:
    if v.get("CollectionType") in ("photos", "homevideos") and fotos is None:
        fotos = v["Id"]

alle, start = [], 0
while True:
    q = urllib.parse.urlencode({"userId": user, "parentId": fotos, "recursive": "true", "includeItemTypes": "Photo",
                                "fields": "Path,ImageTags", "startIndex": start, "limit": 1000, "enableUserData": "false"})
    s = j("/Items?" + q).get("Items") or []
    alle.extend(s)
    if len(s) < 1000:
        break
    start += 1000

fehler, meldungen = [], []
ohne_tag = 0


def pruefen(x):
    global ohne_tag
    tg = (x.get("ImageTags") or {}).get("Primary")
    if not tg:
        ohne_tag += 1
        return ("kein Bild", x.get("Path", ""))
    try:
        holen("/Items/{}/Images/Primary?fillWidth=360&quality=85&tag={}".format(x["Id"], tg), timeout=120)
        return None
    except urllib.error.HTTPError as ex:
        try:
            body = ex.read()[:200].decode("utf-8", "replace")
        except Exception:
            body = ""
        if len(meldungen) < 8:
            meldungen.append("{} {} -> {}".format(ex.code, x.get("Path", ""), body.replace("\n", " ")))
        return (str(ex.code), x.get("Path", ""))
    except Exception as ex:
        return (type(ex).__name__, x.get("Path", ""))


t0 = time.time()
with ThreadPoolExecutor(2) as pool:
    for r in pool.map(pruefen, alle):
        if r:
            fehler.append(r)


def groesse(p):
    try:
        return os.path.getsize(p)
    except OSError:
        return -1


with open(os.path.join(WERK, "fotos_fehler.txt"), "w", encoding="utf-8") as f:
    for code, p in fehler:
        f.write("{}\t{}\t{}\n".format(code, groesse(p), p))

endung = collections.Counter(os.path.splitext(p)[1].lower() for _, p in fehler)
endung_alle = collections.Counter(os.path.splitext(x.get("Path", ""))[1].lower() for x in alle)
ordner = collections.Counter("/".join(p.split("/")[:5]) for _, p in fehler)
codes = collections.Counter(c for c, _ in fehler)
gr = [groesse(p) for _, p in fehler]
gross = sum(1 for g in gr if g > 15 * 1024 * 1024)
with open(os.path.join(WERK, "fotos_fehler_zusammenfassung.txt"), "w", encoding="utf-8") as f:
    f.write("Fotos in Jellyfin: {}, Fehler: {} ({:.1f} %), ohne Bild-Tag: {}, Dauer {} min\n".format(
        len(alle), len(fehler), 100.0 * len(fehler) / max(1, len(alle)), ohne_tag, round((time.time() - t0) / 60)))
    f.write("Status: {}\n".format(dict(codes.most_common())))
    f.write("Endung (Fehler / alle): {}\n".format(
        {e: "{}/{}".format(n, endung_alle.get(e, 0)) for e, n in endung.most_common()}))
    f.write("über 15 MB: {}\n".format(gross))
    f.write("Ordner:\n" + "".join("  {:5d}  {}\n".format(n, o) for o, n in ordner.most_common(15)))
    f.write("Meldungen:\n" + "".join("  " + m + "\n" for m in meldungen))
print("fertig")
