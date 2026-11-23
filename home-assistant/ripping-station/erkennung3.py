#!/usr/bin/env python3
"""Erkennung 3 der Ripping-Station (Claude, Konto B, 23.11.2026)

Die Station schaut selbst in den Film, wenn Disc-Name, ARM und Gedaechtnis nicht reichen:
  1. Standbilder vom Vorspann (Titel, Lauftext) und vom Abspann, Texterkennung mit tesseract (deu+eng)
  2. Suche nach den gelesenen Zeilen und dem Disc-Namen - nur echte Filme (Wikidata-Volltext mit Filmtyp,
     OMDb mit type=movie), damit z. B. kein Videospiel gewinnt (Liebesgruesse aus Moskau, 23.11.)
  3. Bewertung je Kandidat: Laufzeit (wie bisher, PAL beachtet), wie viele Titelwoerter im Bild stehen,
     Jahr aus dem Copyright im Abspann, Aehnlichkeit zum Disc-Namen
  4. Nur ein klarer Sieger wird genommen. Sonst bleibt der Film in _Pruefen und die Station merkt sich die
     besten Vorschlaege und ein Standbild - Home Assistant fragt Lukas per Push ("Ist das X?").

Wird von station.py aufgerufen (erkennen_mit_bild). Eigenstaendig testbar:
  python3 /opt/station/erkennung3.py test <datei.mkv> <Disc-Name> [dvd|bluray]
Nur Standardbibliothek plus ffmpeg/ffprobe und tesseract (apt: tesseract-ocr tesseract-ocr-deu).
"""
import concurrent.futures
import difflib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request

# Wikidata-Typen, die als Film zaehlen (P31): Film, Spielfilm, Zeichentrickfilm, Fernsehfilm, Kurzfilm,
# Fernseh-Special, Dokumentarfilm, Stummfilm, animierter Spielfilm, animierter Kurzfilm, 3D-Film
FILMTYPEN = ["Q11424", "Q24869", "Q202866", "Q506240", "Q24862", "Q1261214", "Q93204", "Q226730",
             "Q29168811", "Q17517379", "Q229390", "Q130232", "Q20650540"]
STOPP = set("the der die das den dem des and und of a an to in im on at vs von mit zu ein eine ist it is as for "
            "les le la el los".split())
BILDER_ORDNER = "/var/lib/station/bilder"
# Rauschen aus dem Abspann/Vorspann, das nie ein Titel ist
RAUSCHEN = re.compile(r"(copyright|all rights|rights reserved|presents|präsentiert|praesentiert|production|"
                      r"produktion|directed|regie|screenplay|drehbuch|music by|musik|starring|casting|edited|"
                      r"executive|produced|producer|kamera|camera|cinematography|visual effects|soundtrack|"
                      r"dolby|dts|thx|www\.|\.com|\.de\b|fsk|warner|universal|disney|paramount|sony|fox|mgm|"
                      r"lionsgate|studiocanal|constantin|tobis|concorde|senator|leonine|eurovideo|kinowelt)", re.I)
MAX_BILDER = 70


def _log(log, text):
    try:
        (log or print)(text)
    except Exception:
        pass


def norm(s):
    s = (s or "").lower()
    for x, y in (("ä", "ae"), ("ö", "oe"), ("ü", "ue"), ("ß", "ss"), ("&", " und ")):
        s = s.replace(x, y)
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


def woerter(s):
    return [w for w in norm(s).split() if len(w) >= 3 and w not in STOPP]


# ------------------------------------------------------------------ Bilder und Texterkennung
def zeitpunkte(dauer_s):
    """Vorspann 4 s bis 6 min alle 8 s, Abspann die letzten 8 min alle 16 s (hoechstens MAX_BILDER)."""
    d = max(int(dauer_s or 0), 60)
    anfang = list(range(4, min(360, d // 2), 8))
    ende = list(range(max(d - 480, d // 2), d - 8, 16))
    return [("anfang", t) for t in anfang][:45] + [("ende", t) for t in ende][: MAX_BILDER - 45]


def _bild(datei, t, ziel):
    r = subprocess.run(["ffmpeg", "-v", "error", "-ss", str(t), "-i", datei, "-frames:v", "1",
                        "-vf", "scale=1280:-2,format=gray,negate", "-y", ziel],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    return r.returncode == 0 and os.path.exists(ziel)


def _ocr_zeilen(png):
    """Zeilen mit Sinn: ueberwiegend Buchstaben. Erst Seitenlayout (Lauftext, Abspann), ist da nichts,
    noch einmal als verstreuter Text (einzelne Titelschrift auf schwarzem Grund)."""
    roh = ""
    for psm in ("3", "11"):
        try:
            r = subprocess.run(["tesseract", png, "-", "-l", "eng+deu", "--psm", psm],
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=60, text=True)
            roh = r.stdout or ""
        except Exception:
            roh = ""
        if len(re.findall(r"[A-Za-zÄÖÜäöüß]{3,}", roh)) >= 2:
            break
    aus = []
    for z in roh.splitlines():
        z = re.sub(r"\s+", " ", z).strip(" |-—_.,:;'\"")
        buchst = sum(ch.isalpha() for ch in z)
        if len(z) >= 5 and buchst >= 0.7 * len(z.replace(" ", "")) and len(re.findall(r"[A-Za-zÄÖÜäöüß]{2,}", z)) >= 1:
            aus.append(z)
    return aus


def lesen(datei, dauer_s, log=None, standbild=None):
    """Standbilder holen und lesen. Gibt {anfang: [zeilen], ende: [zeilen], woerter: set, jahre: [..],
    bild_bestes: pfad} zurueck. standbild = Pfad, unter dem ein normales Farbbild (Titelkarte) abgelegt wird."""
    t0 = time.time()
    tmp = tempfile.mkdtemp(prefix="erk3_")
    ergebnis = {"anfang": [], "ende": [], "woerter": set(), "jahre": [], "text": "", "bilder": 0}
    try:
        punkte = zeitpunkte(dauer_s)
        pngs = []
        for i, (teil, t) in enumerate(punkte):
            p = os.path.join(tmp, f"{i:03d}_{teil}_{t}.png")
            if _bild(datei, t, p):
                pngs.append((teil, t, p))
        ergebnis["bilder"] = len(pngs)

        def lese(x):
            teil, t, p = x
            return teil, t, p, _ocr_zeilen(p)
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            gelesen = list(pool.map(lese, pngs))
        bestes, beste_zahl = None, 0
        alle = []
        for teil, t, p, zeilen in gelesen:
            ergebnis[teil].extend(zeilen)
            alle.extend(zeilen)
            # Titelkarte: im Vorspann das Bild mit den meisten kurzen, "sauberen" Woertern, ohne Rauschen
            if teil == "anfang":
                n = sum(len(woerter(z)) for z in zeilen if not RAUSCHEN.search(z))
                if n > beste_zahl:
                    bestes, beste_zahl = t, n
        text = "\n".join(alle)
        ergebnis["text"] = text
        ergebnis["woerter"] = set(woerter(text))
        jahre = re.findall(r"(?:©|\(c\)|copyright|MCM|MM)[^\n]{0,40}?((?:19|20)\d\d)", text, re.I)
        ergebnis["jahre"] = sorted(set(int(j) for j in jahre))
        if standbild:
            t = bestes if bestes is not None else int((dauer_s or 600) * 0.1)
            subprocess.run(["ffmpeg", "-v", "error", "-ss", str(t), "-i", datei, "-frames:v", "1",
                            "-vf", "scale=640:-2", "-q:v", "4", "-y", standbild],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
        _log(log, f"Erkennung 3: {len(pngs)} Standbilder gelesen in {time.time() - t0:.0f} s, "
                  f"{len(ergebnis['woerter'])} Woerter, Jahre {ergebnis['jahre'] or '-'}")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return ergebnis


def suchzeilen(lesung, n=8):
    """Die vielversprechendsten Zeilen fuer die Suche: Vorspann zuerst, wiederholte Zeilen (Lauftext ueber
    mehrere Bilder) vorne, Rauschen (Studio, Copyright, Namen-Listen) raus."""
    zaehler = {}
    for teil, gewicht in (("anfang", 2), ("ende", 1)):
        for z in lesung.get(teil, []):
            if RAUSCHEN.search(z):
                continue
            w = woerter(z)
            if not w or len(" ".join(w)) < 5:
                continue
            k = " ".join(w[:6])
            zaehler[k] = zaehler.get(k, 0) + gewicht
    besten = sorted(zaehler.items(), key=lambda x: (-x[1], -len(x[0])))
    aus = []
    for k, _ in besten:
        if any(difflib.SequenceMatcher(None, k, a).ratio() > 0.8 for a in aus):
            continue
        aus.append(k)
        if len(aus) >= n:
            break
    return aus


# ------------------------------------------------------------------ Suche (nur Filme)
def wd_film_suche(st, text, n=5):
    """Wikidata-Volltextsuche nur ueber Filme -> Liste wie st.wd_treffer (titel, jahr, imdb, laufzeit_min ...)."""
    if not text or len(text) < 3:
        return []
    filter_ = "|".join(f"P31={q}" for q in FILMTYPEN)
    try:
        r = st.wikidata(action="query", list="search", srsearch=f"{text} haswbstatement:{filter_}", srlimit=n)
        ids = [x["title"] for x in r.get("query", {}).get("search", [])]
        if not ids:
            return []
        ents = st.wikidata(action="wbgetentities", ids="|".join(ids), props="claims", languages="de")["entities"]
    except Exception as e:
        _log(st.log, f"Erkennung 3: Wikidata-Suche '{text}': {e}")
        return []
    aus = []
    for q in ids:
        c = ents.get(q, {}).get("claims", {})
        imdb = next((x["mainsnak"]["datavalue"]["value"] for x in c.get("P345", [])
                     if x["mainsnak"].get("datavalue") and str(x["mainsnak"]["datavalue"]["value"]).startswith("tt")), None)
        if imdb:
            i = st.film_de(imdb_id=imdb)
            if i and not i.get("serie"):
                aus.append(i)
    return aus


def omdb_film_suche(st, text, n=5):
    key = st.arm_yaml("OMDB_API_KEY")
    if not key or not text:
        return []
    try:
        with urllib.request.urlopen("https://www.omdbapi.com/?" + urllib.parse.urlencode(
                {"apikey": key, "s": text, "type": "movie"}), timeout=15) as r:
            treffer = json.load(r).get("Search", [])[:n]
    except Exception:
        treffer = []
    aus = []
    for t in treffer:
        if not t.get("imdbID", "").startswith("tt"):
            continue
        i = st.film_de(imdb_id=t["imdbID"]) or {"titel": st.sauber(t["Title"]), "jahr": t["Year"][:4],
                                                 "imdb": t["imdbID"], "serie": False, "laufzeit_min": None,
                                                 "englisch": t["Title"]}
        aus.append(i)
    return aus


# ------------------------------------------------------------------ Bewertung
def titel_im_bild(info, wortset, st=None):
    """Anteil der Titelwoerter (deutsch, englisch, OMDb), die im Bild gelesen wurden - bester Titel zaehlt.
    Unscharf: ein Wort zaehlt auch, wenn es zu 80 Prozent passt (MENACE ~ MENACF)."""
    titel = [info.get("titel"), info.get("englisch")]
    if st is not None:
        try:
            titel.append(st.omdb_info(info["imdb"]).get("Title"))
        except Exception:
            pass
    best = 0.0
    for t in titel:
        w = woerter(t)
        if not w:
            continue
        treffer = 0
        for x in w:
            if x in wortset or (len(x) >= 5 and any(difflib.SequenceMatcher(None, x, y).ratio() >= 0.8
                                                    for y in wortset if abs(len(y) - len(x)) <= 2)):
                treffer += 1
        anteil = treffer / len(w)
        if len(w) == 1 and len(w[0]) < 5:
            anteil *= 0.5  # kurze Ein-Wort-Titel ("Up", "Her") sind im Bild zu oft zufaellig
        best = max(best, anteil)
    return best


def laufzeit_abw(st, info, ist_s, dvd):
    soll = st.soll_minuten(info)
    if not soll:
        return None, None
    soll_s = soll * 60
    abw = abs(ist_s - soll_s) / soll_s
    if dvd:
        abw = min(abw, abs(ist_s - soll_s * 24 / 25) / soll_s)
    return abw, soll


def bewerten(st, kand, ist_s, dvd, lesung, label_texte):
    """kand: Liste info-Dicts (doppelte IMDb-IDs erlaubt). Gibt sortierte Liste
    [{info, punkte, bild, abw, soll, jahr_ok, name}] zurueck (nur Filme mit passender Laufzeit)."""
    gesehen, aus = set(), []
    for info in kand:
        if not info or info.get("serie") or info["imdb"] in gesehen:
            continue
        gesehen.add(info["imdb"])
        abw, soll = laufzeit_abw(st, info, ist_s, dvd)
        if abw is None or abw > st.TOLERANZ:
            continue
        bild = titel_im_bild(info, lesung.get("woerter", set()), st)
        name = max([st.aehnlich(t, info.get("titel")) for t in label_texte] +
                   [st.aehnlich(t, info.get("englisch")) for t in label_texte] + [0.0])
        jahr_ok = None
        if lesung.get("jahre"):
            try:
                jahr_ok = any(abs(int(info["jahr"]) - j) <= 1 for j in lesung["jahre"])
            except (TypeError, ValueError):
                jahr_ok = None
        punkte = 3.0 * bild + 1.0 * name + (0.6 if jahr_ok else 0) + max(0.0, 0.5 - 5 * abw)
        aus.append({"info": info, "punkte": round(punkte, 2), "bild": round(bild, 2), "name": round(name, 2),
                    "abw": round(abw, 3), "soll": soll, "jahr_ok": jahr_ok})
    return sorted(aus, key=lambda x: -x["punkte"])


def entscheiden(bewertet):
    """Nur ein klarer Sieger: genug Titelwoerter im Bild (oder Bild plus Name) und deutlicher Abstand."""
    if not bewertet:
        return None, "kein Film mit passender Laufzeit gefunden"
    a = bewertet[0]
    b = bewertet[1] if len(bewertet) > 1 else None
    sicher = a["bild"] >= 0.66 or (a["bild"] >= 0.5 and a["name"] >= 0.5)
    abstand = (a["punkte"] - b["punkte"]) if b else 9
    if sicher and abstand >= 0.8:
        return a, "klar"
    if sicher and b and b["bild"] < 0.34 and abstand >= 0.5:
        return a, "klar (nur einer im Bild)"
    grund = "unsicher: " + " / ".join(f"{x['info']['titel']} ({x['info']['jahr']}) Bild {round(100 * x['bild'])} von 100"
                                      for x in bewertet[:3])
    return None, grund


def erkennen_mit_bild(st, job, datei, ist_s, k_alt=None, standbild=None):
    """Hauptaufruf aus station.einsammeln, wenn waehle() nichts Sicheres gefunden hat.
    Gibt (info, quelle, text, vorschlaege) zurueck; info None = nicht sicher -> pruefen + Frage an Lukas."""
    log = st.log
    dvd = job.get("disctype") == "dvd"
    label_texte = st.label_varianten(job.get("label")) if not st.label_unbrauchbar(job.get("label")) else []
    lesung = lesen(datei, ist_s, log, standbild)
    kand = [x[3] for x in (k_alt or [])]
    zeilen = suchzeilen(lesung)
    if zeilen:
        _log(log, "Erkennung 3: lese im Bild: " + " | ".join(zeilen[:6]))
    for z in zeilen[:6]:
        kand += wd_film_suche(st, z, 4)
        kand += omdb_film_suche(st, z, 4)
        # Teilstuecke: die ersten 3-4 Woerter allein (Lauftext haengt oft Text an den Titel)
        w = z.split()
        if len(w) > 4:
            kand += wd_film_suche(st, " ".join(w[:4]), 3)
            kand += omdb_film_suche(st, " ".join(w[:3]), 3)
    for t in label_texte:
        kand += wd_film_suche(st, t, 4)
        kand += omdb_film_suche(st, t, 4)
    bewertet = bewerten(st, kand, ist_s, dvd, lesung, label_texte)
    for x in bewertet[:4]:
        _log(log, f"Erkennung 3: {x['info']['titel']} ({x['info']['jahr']}) {x['info']['imdb']} - Punkte {x['punkte']}, "
                  f"Bild {x['bild']}, Name {x['name']}, Laufzeit {ist_s / 60:.0f}/{x['soll']} min, Jahr {x['jahr_ok']}")
    sieger, grund = entscheiden(bewertet)
    vorschlaege = [{"imdb": x["info"]["imdb"], "titel": x["info"]["titel"], "jahr": x["info"]["jahr"],
                    "bild": x["bild"], "punkte": x["punkte"]} for x in bewertet[:3]]
    if sieger:
        i = sieger["info"]
        return i, "Texterkennung", (f"Texterkennung ({grund}), Titel im Bild {sieger['bild']:.2f}, "
                                    f"Laufzeit {ist_s / 60:.0f}/{sieger['soll']} min"), vorschlaege
    return None, None, grund, vorschlaege


# ------------------------------------------------------------------ Test von Hand
if __name__ == "__main__":
    if len(sys.argv) >= 4 and sys.argv[1] == "test":
        import importlib.machinery
        import importlib.util
        lo = importlib.machinery.SourceFileLoader("station", "/opt/station/station.py")
        st = importlib.util.module_from_spec(importlib.util.spec_from_loader("station", lo))
        lo.exec_module(st)
        st.LOG = "/tmp/erkennung3_test.log"
        datei, label = sys.argv[2], sys.argv[3]
        art = sys.argv[4] if len(sys.argv) > 4 else "dvd"
        job = {"label": label, "disctype": art, "crc_id": None, "hasnicetitle": 0}
        ist = st.dauer_s(datei)
        k = st.kandidaten(job)
        info, quelle, text = st.waehle(k, ist, art == "dvd")
        print("BISHER:", (info or {}).get("titel"), (info or {}).get("jahr"), quelle, "-", text)
        i, q, t, v = erkennen_mit_bild(st, job, datei, ist, k, "/tmp/erkennung3_test.jpg")
        print("NEU:", (i or {}).get("titel"), (i or {}).get("jahr"), (i or {}).get("imdb"), q, "-", t)
        print("VORSCHLAEGE:", json.dumps(v, ensure_ascii=False))
    else:
        print(__doc__)
