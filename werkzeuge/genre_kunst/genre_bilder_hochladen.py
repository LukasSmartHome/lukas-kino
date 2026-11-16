#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Genre-Bilder in Jellyfin einspielen (Claude, Konto A, 16.11.2026, Wunsch Lukas).

Setzt bei den 15 Kategorie-Sammlungen (Action, Horror, Western …) das gezeichnete Kachel-Bild (Primary) und den
gezeichneten Hintergrund (Backdrop). Lukas Kino am TCL und an den LGs zeigt beides dann von selbst.

Läuft auf dem Pi (File editor oder Home Assistant). Jellyfin-Schlüssel nur aus <config>/jellyfin_schluessel.txt
(wird nie ausgegeben).

Aufruf:
  python3 genre_bilder_hochladen.py BILDER [--los]      BILDER = Ordner mit kachel/ und breit/ oder die .tar-Datei
  python3 genre_bilder_hochladen.py BILDER --zurueck    alte Kachel-Bilder aus der Sicherung zurück, Hintergründe weg

Ohne --los wird nur angezeigt, was passieren würde. Vor dem ersten Einspielen werden die bisherigen Kachel-Bilder
nach <config>/kino_werkzeuge/genre_bilder_sicherung/ gesichert (einmalig, wird nie überschrieben).
"""
import base64
import json
import os
import sys
import tarfile
import time
import urllib.error
import urllib.parse
import urllib.request

S = "http://192.168.178.44:8096"
C = "/config" if os.path.isdir("/config/.storage") else "/homeassistant"
SICHERUNG = os.path.join(C, "kino_werkzeuge", "genre_bilder_sicherung")

# Name der Sammlung in Jellyfin -> Dateiname
BILDER = {
    "Action": "action", "Komödien": "komoedien", "Horror": "horror", "Thriller": "thriller",
    "Science-Fiction": "science-fiction", "Krimi": "krimi", "Drama": "drama", "Abenteuer": "abenteuer",
    "Fantasy": "fantasy", "Mystery": "mystery", "Liebesfilme": "liebesfilme",
    "Familie & Animation": "familie-animation", "Familie und Animation": "familie-animation",
    "Kriegsfilme": "kriegsfilme", "Historienfilme": "historienfilme", "Western": "western",
}


def schluessel():
    k = open(os.path.join(C, "jellyfin_schluessel.txt"), encoding="utf-8").read().strip().strip('"')
    if len(k) < 20:
        raise SystemExit("kein Jellyfin-Schlüssel")
    return k


def anfrage(pfad, key, methode="GET", daten=None, typ=None, roh=False):
    kopf = {"Authorization": 'MediaBrowser Token="{}"'.format(key), "Accept": "*/*" if roh else "application/json"}
    if typ:
        kopf["Content-Type"] = typ
    req = urllib.request.Request(S + pfad, data=daten, headers=kopf, method=methode)
    with urllib.request.urlopen(req, timeout=60) as r:
        d = r.read()
    if roh:
        return d
    return json.loads(d) if d and d[:1] in (b"{", b"[") else None


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    los = "--los" in sys.argv
    zurueck = "--zurueck" in sys.argv
    if not args:
        print(__doc__)
        return 2
    quelle = args[0]
    if quelle.endswith(".tar") and not zurueck:
        ziel = quelle[:-4] + "_ausgepackt"
        os.makedirs(ziel, exist_ok=True)
        with tarfile.open(quelle) as t:
            for m in t.getmembers():
                # nur einfache Dateien in kachel/ und breit/ – nichts, was aus dem Ordner herausführt
                if m.isfile() and ".." not in m.name and not m.name.startswith("/") and m.name.split("/")[0] in ("kachel", "breit"):
                    t.extract(m, ziel)
        quelle = ziel
    key = schluessel()
    nutzer = anfrage("/Users", key) or []
    admin = [u for u in nutzer if (u.get("Policy") or {}).get("IsAdministrator")]
    user = (admin or nutzer)[0]["Id"]
    samml = anfrage("/Items?userId={}&includeItemTypes=BoxSet&recursive=true&sortBy=SortName".format(user), key).get("Items") or []
    os.makedirs(SICHERUNG, exist_ok=True)
    fertig, fehlt = 0, []
    for name, datei in BILDER.items():
        s = [x for x in samml if x.get("Name") == name]
        if not s:
            if name != "Familie und Animation":
                fehlt.append(name)
            continue
        sid = s[0]["Id"]
        sich = os.path.join(SICHERUNG, datei + ".kachel_alt.jpg")
        if zurueck:
            if not os.path.isfile(sich):
                print("keine Sicherung:", name)
                continue
            if los:
                anfrage("/Items/{}/Images/Primary".format(sid), key, "POST", base64.b64encode(open(sich, "rb").read()), "image/jpeg")
                for _ in range(len(s[0].get("BackdropImageTags") or [])):
                    anfrage("/Items/{}/Images/Backdrop/0".format(sid), key, "DELETE")
            print("zurück:", name)
            fertig += 1
            continue
        kachel = os.path.join(quelle, "kachel", datei + ".jpg")
        breit = os.path.join(quelle, "breit", datei + ".jpg")
        if not (os.path.isfile(kachel) and os.path.isfile(breit)):
            print("Bild fehlt:", name)
            continue
        if not los:
            print("würde setzen:", name, os.path.getsize(kachel), os.path.getsize(breit))
            continue
        try:
            if not os.path.isfile(sich) and (s[0].get("ImageTags") or {}).get("Primary"):
                alt = anfrage("/Items/{}/Images/Primary".format(sid), key, roh=True)
                with open(sich, "wb") as f:
                    f.write(alt)
            anfrage("/Items/{}/Images/Primary".format(sid), key, "POST", base64.b64encode(open(kachel, "rb").read()), "image/jpeg")
            for _ in range(len(s[0].get("BackdropImageTags") or [])):
                anfrage("/Items/{}/Images/Backdrop/0".format(sid), key, "DELETE")
            anfrage("/Items/{}/Images/Backdrop".format(sid), key, "POST", base64.b64encode(open(breit, "rb").read()), "image/jpeg")
            fertig += 1
            print("ok:", name)
        except urllib.error.HTTPError as e:
            print("FEHLER", name, e.code)
        time.sleep(0.2)
    print("fertig: {} Kategorien{}".format(fertig, ", nicht gefunden: " + ", ".join(fehlt) if fehlt else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
