#!/usr/bin/env python3
"""Bild-Pruefstand (Claude B, 24.11.2026): echte Rips vom Pi so pruefen, als haette die Disc einen nichtssagenden
Namen ("M000253") - nur Texterkennung im Bild und Leute im Abspann duerfen entscheiden.
Aufruf: python3 /opt/station/erkennung_bildtest.py [Anzahl] -> /tmp/erk_bild.txt
Die richtige Antwort steht im Ordnernamen ([imdbid-tt...])."""
import importlib.machinery
import importlib.util
import os
import re
import sys
import time

sys.path.insert(0, "/opt/station")
lo = importlib.machinery.SourceFileLoader("station", "/opt/station/station.py")
st = importlib.util.module_from_spec(importlib.util.spec_from_loader("station", lo))
lo.exec_module(st)
st.LOG = "/tmp/erk_bild_station.log"
import erkennung3 as e3  # noqa: E402

WURZEL = "/run/station/lesen/Filme4_Ripping_Station/Filme"
anzahl = int(sys.argv[1]) if len(sys.argv) > 1 else 8
aus = open("/tmp/erk_bild.txt", "w", encoding="utf-8")
ordner = sorted(os.listdir(WURZEL))
# gemischt: nicht nur Bond und Karibik
gewaehlt, gesehen = [], set()
for o in ordner:
    reihe = o.split(" ")[0]
    if reihe in gesehen and len(ordner) > anzahl:
        continue
    gesehen.add(reihe)
    gewaehlt.append(o)
zahl = {"richtig": 0, "falsch": 0, "nichts": 0}
for o in gewaehlt[:anzahl]:
    m = re.search(r"\[imdbid-(tt\d+)\]", o)
    dateien = [f for f in os.listdir(os.path.join(WURZEL, o)) if f.endswith(".mkv")]
    if not m or not dateien:
        continue
    datei = os.path.join(WURZEL, o, dateien[0])
    t0 = time.time()
    ist = st.dauer_s(datei)
    j = e3._probe(datei)
    h = next((x.get("height") for x in j.get("streams", []) if x.get("codec_type") == "video"), 0) or 0
    job = {"label": "M000000", "disctype": "dvd" if h <= 576 else "bluray", "crc_id": None, "hasnicetitle": 0,
           "imdb_id": None, "job_id": 0}
    try:
        info, q, text, v = e3.erkennen_mit_bild(st, job, datei, ist, [], None)
    except Exception as e:
        info, text, v = None, f"FEHLER {e}", []
    got = (info or {}).get("imdb")
    urteil = "richtig" if got == m.group(1) else ("nichts" if not got else "falsch")
    zahl[urteil] += 1
    aus.write(f"{ {'richtig': 'OK ', 'falsch': 'XX ', 'nichts': '-- '}[urteil]}{o[:60]:60s} -> {got} {(info or {}).get('titel', '')} "
              f"{time.time() - t0:.0f}s | {text[:150]} | Vorschlaege: {[x['titel'] for x in v][:3]}\n")
    aus.flush()
aus.write(f"\nSUMME {zahl}\n")
aus.close()
