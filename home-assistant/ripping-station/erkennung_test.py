#!/usr/bin/env python3
"""Pruefstand fuer die Film-Erkennung der Ripping-Station (Claude B, 24.11.2026).

Tut so, als laege eine Disc mit typischer Bezeichnung im Laufwerk (ohne Laufwerk, ohne Rip):
  Laufzeit = Laufzeit laut OMDb (DVD: PAL-schneller, 25/24) mit +-1,5 Prozent Abweichung wie bei echten Discs.
  Stufe 1 = Station (kandidaten + waehle), Stufe 2 = erkennung3.erkennen_ohne_bild (nur Name, nur Filme).
Aufruf:  python3 /opt/station/erkennung_test.py [Datei mit Faellen]  -> /tmp/erk_test.txt
Fall-Zeile:  BEZEICHNUNG;dvd|bluray;tt-Nummer (oder - fuer "darf nichts erkennen");Titel als Hinweis
Verbraucht OMDb-Abfragen (Tagesgrenze 1000): bricht bei 700 ab.
"""
import hashlib
import importlib.machinery
import importlib.util
import json
import sys
import time
import urllib.request

sys.path.insert(0, "/opt/station")
lo = importlib.machinery.SourceFileLoader("station", "/opt/station/station.py")
st = importlib.util.module_from_spec(importlib.util.spec_from_loader("station", lo))
lo.exec_module(st)
st.LOG = "/tmp/erk_test_station.log"
import erkennung3 as e3  # noqa: E402

ZAEHLER = {"omdb": 0, "wikidata": 0}
_orig = urllib.request.urlopen


def _zaehl(req, *a, **k):
    url = req.full_url if hasattr(req, "full_url") else str(req)
    if "omdbapi.com" in url:
        ZAEHLER["omdb"] += 1
        if ZAEHLER["omdb"] > 700:
            raise RuntimeError("OMDb-Grenze fuer den Test erreicht")
    if "wikidata.org" in url:
        ZAEHLER["wikidata"] += 1
    return _orig(req, *a, **k)


urllib.request.urlopen = _zaehl

FAELLE = """
SKYFALL;bluray;tt1074638;Skyfall
007_SKYFALL;dvd;tt1074638;Skyfall
CASINO_ROYALE;dvd;tt0381061;Casino Royale
QUANTUM_OF_SOLACE;bluray;tt0830515;Ein Quantum Trost
SPECTRE_DE;bluray;tt2379713;Spectre
GOLDENEYE;dvd;tt0113189;GoldenEye
THE_DARK_KNIGHT;bluray;tt0468569;The Dark Knight
DARK_KNIGHT_RISES_D1;bluray;tt1345836;The Dark Knight Rises
INCEPTION;bluray;tt1375666;Inception
AVATAR;bluray;tt0499549;Avatar
LOTR_FOTR;dvd;tt0120737;Herr der Ringe Gefaehrten
HP_DEATHLY_HALLOWS_PT2;bluray;tt1201607;Heiligtuemer des Todes 2
TERMINATOR_2;dvd;tt0103064;Terminator 2
THE_MATRIX;dvd;tt0133093;Matrix
MATRIX_RELOADED;dvd;tt0234215;Matrix Reloaded
BACK_TO_THE_FUTURE;dvd;tt0088763;Zurueck in die Zukunft
BTTF2;dvd;tt0096874;Zurueck in die Zukunft II
JURASSIC_PARK;bluray;tt0107290;Jurassic Park
JURASSIC_WORLD_DE;bluray;tt0369610;Jurassic World
TITANIC_DISC1;dvd;tt0120338;Titanic
FINDET_NEMO;dvd;tt0266543;Findet Nemo
SHREK2;dvd;tt0298148;Shrek 2
ICE_AGE_3;dvd;tt1080016;Ice Age 3
DER_SCHUH_DES_MANITU;dvd;tt0248408;Der Schuh des Manitu
KEINOHRHASEN;dvd;tt1047011;Keinohrhasen
LOLA_RENNT;dvd;tt0130827;Lola rennt
GOOD_BYE_LENIN;dvd;tt0301357;Good Bye Lenin
DIE_WELLE;dvd;tt1063669;Die Welle
DAS_BOOT;dvd;tt0082096;Das Boot
DIE_EISKOENIGIN;bluray;tt2294629;Die Eiskoenigin
TOY_STORY_3;bluray;tt0435761;Toy Story 3
CARS;dvd;tt0317219;Cars
OBEN;dvd;tt1049413;Oben
WALL_E;dvd;tt0910970;WALL-E
RATATOUILLE;dvd;tt0382932;Ratatouille
AVENGERS_ENDGAME;bluray;tt4154796;Avengers Endgame
IRON_MAN_2;bluray;tt1228705;Iron Man 2
SPIDER_MAN_3;dvd;tt0413300;Spider-Man 3
GLADIATOR;dvd;tt0172495;Gladiator
FORREST_GUMP;dvd;tt0109830;Forrest Gump
PULP_FICTION;dvd;tt0110912;Pulp Fiction
FIGHT_CLUB;dvd;tt0137523;Fight Club
THE_GODFATHER;dvd;tt0068646;Der Pate
INTERSTELLAR;bluray;tt0816692;Interstellar
DER_MARSIANER;bluray;tt3659388;Der Marsianer
KOENIG_DER_LOEWEN;dvd;tt0110357;Der Koenig der Loewen (1994)
THE_LION_KING_2019;bluray;tt6105098;Der Koenig der Loewen (2019)
DIE_UNGLAUBLICHEN;dvd;tt0317705;Die Unglaublichen
ZOOMANIA;bluray;tt2948356;Zoomania
ICH_EINFACH_UNVERBESSERLICH;dvd;tt1323594;Ich einfach unverbesserlich
DIE_VERURTEILTEN;dvd;tt0111161;Die Verurteilten
INGLOURIOUS_BASTERDS;bluray;tt0361748;Inglourious Basterds
MAD_MAX_FURY_ROAD;bluray;tt1392190;Mad Max Fury Road
JOHN_WICK;bluray;tt2911666;John Wick
96_HOURS;dvd;tt0936501;96 Hours
MEN_IN_BLACK;dvd;tt0119654;Men in Black
STAR_WARS_EP4;dvd;tt0076759;Krieg der Sterne
DUNE_PART_TWO;bluray;tt15239678;Dune Part Two
BDROM;bluray;-;nichts
DVD_VIDEO;dvd;-;nichts
"""


def faelle(text):
    for z in text.strip().splitlines():
        if z.strip() and not z.startswith("#"):
            t = [x.strip() for x in z.split(";")]
            yield t[0], t[1], t[2], t[3] if len(t) > 3 else ""


def laufzeit(imdb, dvd, label):
    o = st.omdb(imdb) or {}
    m = __import__("re").match(r"(\d+)", o.get("Runtime", ""))
    if not m:
        return None, o.get("Title")
    soll = int(m.group(1)) * 60
    rausch = (int(hashlib.md5(label.encode()).hexdigest(), 16) % 300 - 150) / 10000  # -1,5 .. +1,5 Prozent
    ist = soll * (24 / 25 if dvd else 1) * (1 + rausch)
    return ist, o.get("Title")


def main():
    text = open(sys.argv[1], encoding="utf-8").read() if len(sys.argv) > 1 else FAELLE
    aus = open("/tmp/erk_test.txt", "w", encoding="utf-8")
    zahl = {"richtig": 0, "falsch": 0, "nichts": 0}
    ergebnisse = []
    for label, art, soll_imdb, hinweis in faelle(text):
        t0 = time.time()
        dvd = art == "dvd"
        if soll_imdb == "-":
            ist, otitel = 5400.0, "-"
        else:
            ist, otitel = laufzeit(soll_imdb, dvd, label)
            if not ist:
                aus.write(f"?? {label}: keine Laufzeit fuer {soll_imdb} ({otitel})\n"); aus.flush()
                continue
        job = {"label": label, "disctype": art, "crc_id": None, "hasnicetitle": 0, "imdb_id": None,
               "imdb_id_auto": None, "imdb_id_manual": None, "mountpoint": None, "job_id": 0}
        stufe, info, text_e = "-", None, ""
        try:
            k = st.kandidaten(job)
            info, quelle, text_e = st.waehle(k, ist, dvd)
            stufe = "Station" if info else "-"
            if not info:
                info, quelle, text_e, _ = e3.erkennen_ohne_bild(st, job, ist, k)
                stufe = "Name" if info else "-"
        except Exception as e:
            text_e = f"FEHLER {e}"
        got = info["imdb"] if info else None
        if soll_imdb == "-":
            urteil = "richtig" if not got else "falsch"
        else:
            urteil = "richtig" if got == soll_imdb else ("nichts" if not got else "falsch")
        zahl[urteil] += 1
        zeile = (f"{ {'richtig': 'OK ', 'falsch': 'XX ', 'nichts': '-- '}[urteil]}{label:30s} {art:6s} soll {soll_imdb} ({hinweis}) "
                 f"ist {got} {(info or {}).get('titel', '')} ({(info or {}).get('jahr', '')}) [{stufe}] "
                 f"{time.time() - t0:.0f}s | {text_e[:160]}")
        aus.write(zeile + "\n"); aus.flush()
        ergebnisse.append({"label": label, "art": art, "soll": soll_imdb, "ist": got, "urteil": urteil, "stufe": stufe,
                           "text": text_e})
    aus.write(f"\nSUMME: {zahl} | OMDb-Abfragen {ZAEHLER['omdb']}, Wikidata {ZAEHLER['wikidata']}\n")
    aus.close()
    json.dump(ergebnisse, open("/tmp/erk_test.json", "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
