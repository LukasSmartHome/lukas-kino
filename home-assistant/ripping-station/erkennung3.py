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
# Namen im Vor-/Abspann (Produzent, Regie, Darsteller) -> deren Filme bei Wikidata (Claude B 24.11.2026)
NAME_TRENNER = re.compile(r"\b(?:and|und|by|von|presents?|pr[äa]sentier\w*|starring|in|als|mit|with|as|a|an|film|"
                          r"production|produktion|picture)\b|[&,;:|/•·]", re.I)
NAME_STOPP = set("the der die das film films production productions pictures picture presents music united artists eon "
                 "ltd inc gmbh dvd video copyright rights reserved studio studios entertainment distributed release all "
                 "warner bros universal paramount columbia metro goldwyn mayer twentieth century fox walt disney "
                 "home company corporation limited star wars lego directed produced screenplay written casting".split())
# Wikidata: Produzent, Regie, Drehbuch, Darsteller, Musik, Kamera, Ausfuehrender Produzent
PERSON_ROLLEN = ["P162", "P57", "P58", "P161", "P86", "P344", "P1431"]


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
def zeitpunkte(dauer_s, teile=("anfang", "ende")):
    """Vorspann 4 s bis 5 min alle 10 s, Abspann die letzten 6 min alle 20 s."""
    d = max(int(dauer_s or 0), 60)
    aus = []
    if "anfang" in teile:
        aus += [("anfang", t) for t in range(4, min(480, d // 2), 10)]  # bis 8 min: Bond-Titelsequenz kommt spaet
    if "ende" in teile:
        aus += [("ende", t) for t in range(max(d - 360, d // 2), d - 8, 20)]
    return aus[:MAX_BILDER]


def _bild(datei, t, ziel):
    r = subprocess.run(["ffmpeg", "-v", "error", "-ss", str(t), "-i", datei, "-frames:v", "1",
                        "-vf", "scale=1280:-2,format=gray,negate", "-y", ziel],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    return r.returncode == 0 and os.path.exists(ziel)


def _ocr_zeilen(png):
    """Zeilen mit Sinn: ueberwiegend Buchstaben. tesseract --psm 11 (verstreuter Text) liefert je Textzeile einen
    Block - passt fuer Titelschrift auf schwarzem Grund und fuer Lauftext/Abspann."""
    try:
        r = subprocess.run(["tesseract", png, "-", "-l", "eng+deu", "--psm", "11"],
                           stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=60, text=True,
                           env=dict(os.environ, OMP_THREAD_LIMIT="1"))
        roh = r.stdout or ""
    except Exception:
        roh = ""
    aus = []
    for z in roh.splitlines():
        z = re.sub(r"\s+", " ", z).strip(" |-—_.,:;'\"")
        buchst = sum(ch.isalpha() for ch in z)
        if len(z) >= 5 and buchst >= 0.7 * len(z.replace(" ", "")) and len(re.findall(r"[A-Za-zÄÖÜäöüß]{2,}", z)) >= 1:
            aus.append(z)
    return aus


def lesen(datei, dauer_s, log=None, standbild=None, teile=("anfang", "ende")):
    """Standbilder holen und lesen. Gibt {anfang: [zeilen], ende: [zeilen], woerter: set, jahre: [..],
    bild_bestes: pfad} zurueck. standbild = Pfad, unter dem ein normales Farbbild (Titelkarte) abgelegt wird."""
    t0 = time.time()
    tmp = tempfile.mkdtemp(prefix="erk3_")
    ergebnis = {"anfang": [], "ende": [], "woerter": set(), "jahre": [], "text": "", "bilder": 0, "zeilen": []}
    try:
        punkte = zeitpunkte(dauer_s, teile)
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
        ergebnis["zeilen"] = alle
        ergebnis["woerter"] = set(woerter(text))
        jahre = re.findall(r"(?:©|\(c\)|copyright|MCM|MM)[^\n]{0,40}?((?:19|20)\d\d)", text, re.I)
        ergebnis["jahre"] = sorted(set(int(j) for j in jahre))
        if standbild and "anfang" in teile:
            t = bestes if bestes is not None else int((dauer_s or 600) * 0.1)
            subprocess.run(["ffmpeg", "-v", "error", "-ss", str(t), "-i", datei, "-frames:v", "1",
                            "-vf", "scale=640:-2", "-q:v", "4", "-y", standbild],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
        _log(log, f"Erkennung 3: {len(pngs)} Standbilder ({'/'.join(teile)}) gelesen in {time.time() - t0:.0f} s, "
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


# ------------------------------------------------------------------ Namen im Bild -> Filme dieser Leute
def _ist_name(teil):
    t = [x for x in teil.split() if x]
    if not 2 <= len(t) <= 4:
        return None
    for x in t:
        if not re.fullmatch(r"[A-ZÄÖÜ][A-Za-zÄÖÜäöüß'\-]*\.?", x):
            return None
        if x.strip(".").lower() in NAME_STOPP:
            return None
        if len(x.strip(".")) < 2 and not re.fullmatch(r"[A-Z]\.", x):
            return None
    if sum(len(x.strip(".")) for x in t) < 8 or not any(len(x.strip(".")) >= 4 for x in t):
        return None
    return " ".join(x.capitalize() if x.isupper() and len(x.strip(".")) > 2 else x for x in t)


def namen(lesung, n=6):
    """Personennamen aus Vor- und Abspann (ALBERT R. BROCCOLI, HARRY SALTZMAN ...), haeufigste zuerst."""
    zaehler = {}
    for z in lesung.get("zeilen", []):
        for teil in NAME_TRENNER.split(z):
            nm = _ist_name(re.sub(r"\s+", " ", teil or "").strip(" .-'\""))
            if nm:
                zaehler[nm] = zaehler.get(nm, 0) + 1
    return [k for k, _ in sorted(zaehler.items(), key=lambda x: -x[1])[:n]]


def _wd_infos(st, ids):
    """Wikidata-Eintraege -> info-Dicts wie st.wd_treffer (titel deutsch, jahr, imdb, laufzeit_min, englisch)."""
    aus = []
    for i in range(0, len(ids), 50):
        try:
            ents = st.wikidata(action="wbgetentities", ids="|".join(ids[i:i + 50]), props="labels|claims",
                               languages="de|en")["entities"]
        except Exception as e:
            _log(st.log, f"Erkennung 3: Wikidata-Eintraege: {e}")
            continue
        for q in ids[i:i + 50]:
            e = ents.get(q, {})
            c = e.get("claims", {})
            werte = lambda p: [x["mainsnak"].get("datavalue", {}).get("value") for x in c.get(p, [])
                               if x["mainsnak"].get("datavalue")]
            imdb = next((x for x in werte("P345") if str(x).startswith("tt")), None)
            jahre = sorted(x["time"][1:5] for x in werte("P577") if isinstance(x, dict) and "time" in x)
            lab = e.get("labels", {})
            titel = lab.get("de", lab.get("en", {})).get("value")
            if imdb and jahre and titel:
                dauer = next((float(x["amount"]) for x in werte("P2047") if isinstance(x, dict) and "amount" in x), None)
                aus.append({"titel": st.sauber(titel), "jahr": jahre[0], "imdb": imdb, "serie": False,
                            "laufzeit_min": dauer, "englisch": lab.get("en", {}).get("value"), "_q": q})
    return aus


def personen_filme(st, nm_liste, jahre=None):
    """Namen -> Personen (Mensch, Q5) -> ihre Filme. Gibt ({imdb: info}, {imdb: anzahl Personen}, [gefundene Namen])."""
    log = st.log
    personen = []
    for nm in nm_liste:
        try:
            ids = [x["id"] for x in st.wikidata(action="wbsearchentities", search=nm, language="en", type="item",
                                                limit=3).get("search", [])]
            if not ids:
                continue
            ents = st.wikidata(action="wbgetentities", ids="|".join(ids), props="claims")["entities"]
        except Exception as e:
            _log(log, f"Erkennung 3: Person '{nm}': {e}")
            continue
        for q in ids:
            p31 = [x["mainsnak"].get("datavalue", {}).get("value", {}).get("id")
                   for x in ents.get(q, {}).get("claims", {}).get("P31", [])]
            if "Q5" in p31:
                personen.append((nm, q))
                break
    filme, zahl = {}, {}
    film_filter = "|".join(f"P31={t}" for t in FILMTYPEN)
    for nm, q in personen:
        rollen = "|".join(f"{r}={q}" for r in PERSON_ROLLEN)
        try:
            r = st.wikidata(action="query", list="search", srsearch=f"haswbstatement:{film_filter} haswbstatement:{rollen}",
                            srlimit=50)
            ids = [x["title"] for x in r.get("query", {}).get("search", [])]
        except Exception as e:
            _log(log, f"Erkennung 3: Filme von {nm}: {e}")
            continue
        for info in _wd_infos(st, ids):
            if jahre:
                try:
                    if min(abs(int(info["jahr"]) - j) for j in jahre) > 2:
                        continue
                except ValueError:
                    continue
            filme.setdefault(info["imdb"], info)
            zahl[info["imdb"]] = zahl.get(info["imdb"], 0) + 1
    if personen:
        _log(log, f"Erkennung 3: Personen im Bild: {', '.join(n for n, _ in personen)} -> {len(filme)} Filme"
                  + (f" um {'/'.join(str(j) for j in jahre)}" if jahre else ""))
    return filme, zahl, [n for n, _ in personen]


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


def bewerten(st, kand, ist_s, dvd, lesung, label_texte, personen=None):
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
        jahr_ok, jahr_diff = None, None
        if lesung.get("jahre"):
            try:
                jahr_diff = min(abs(int(info["jahr"]) - j) for j in lesung["jahre"])
                jahr_ok = jahr_diff <= 1
            except (TypeError, ValueError):
                jahr_ok = None
        pers = (personen or {}).get(info["imdb"], 0)
        punkte = (3.0 * bild + 1.0 * name + (0.8 if jahr_diff == 0 else (0.3 if jahr_ok else 0))
                  + 0.7 * min(pers, 2) + max(0.0, 0.5 - 5 * abw))
        aus.append({"info": info, "punkte": round(punkte, 2), "bild": round(bild, 2), "name": round(name, 2),
                    "abw": round(abw, 3), "soll": soll, "jahr_ok": jahr_ok, "jahr_diff": jahr_diff, "personen": pers})
    return sorted(aus, key=lambda x: -x["punkte"])


def entscheiden(bewertet):
    """Nur ein klarer Sieger: genug Titelwoerter im Bild (oder Bild plus Name) und deutlicher Abstand."""
    if not bewertet:
        return None, "kein Film mit passender Laufzeit gefunden"
    a = bewertet[0]
    b = bewertet[1] if len(bewertet) > 1 else None
    sicher = a["bild"] >= 0.66 or (a["bild"] >= 0.5 and a["name"] >= 0.5)
    # Claude B 24.11.2026: ohne Titel im Bild - Leute aus dem Vor-/Abspann, Copyright-Jahr genau und Laufzeit fast genau
    if not sicher and a.get("personen", 0) >= 1 and a.get("jahr_diff") == 0 and a["abw"] <= 0.03:
        gleich_gut = [x for x in bewertet[1:] if x.get("jahr_diff") == 0 and x["abw"] <= 0.03 and x.get("personen", 0) >= 1]
        sicher = not gleich_gut
    abstand = (a["punkte"] - b["punkte"]) if b else 9
    if sicher and abstand >= 0.8:
        return a, "klar" if a["bild"] >= 0.5 else "klar (Leute im Abspann, Jahr und Laufzeit)"
    if sicher and b and b["bild"] < 0.34 and abstand >= 0.5:
        return a, "klar (nur einer im Bild)"
    grund = "unsicher: " + " / ".join(f"{x['info']['titel']} ({x['info']['jahr']}) Bild {round(100 * x['bild'])} von 100"
                                      for x in bewertet[:3])
    return None, grund


def _suchen(st, zeilen, label_texte, schon):
    kand = []
    for z in zeilen[:6]:
        if z in schon:
            continue
        schon.add(z)
        kand += wd_film_suche(st, z, 4)
        kand += omdb_film_suche(st, z, 4)
        w = z.split()  # Lauftext haengt oft Text an den Titel: die ersten Woerter allein
        if len(w) > 4:
            kand += wd_film_suche(st, " ".join(w[:4]), 3)
            kand += omdb_film_suche(st, " ".join(w[:3]), 3)
    for t in label_texte:
        if t in schon:
            continue
        schon.add(t)
        kand += wd_film_suche(st, t, 4)
        kand += omdb_film_suche(st, t, 4)
    return kand


def erkennen_mit_bild(st, job, datei, ist_s, k_alt=None, standbild=None):
    """Hauptaufruf aus station.einsammeln, wenn waehle() nichts Sicheres gefunden hat.
    Erst den Vorspann lesen (meist reicht der Titel), nur wenn noetig auch den Abspann.
    Gibt (info, quelle, text, vorschlaege) zurueck; info None = nicht sicher -> pruefen + Frage an Lukas."""
    log = st.log
    t0 = time.time()
    dvd = job.get("disctype") == "dvd"
    label_texte = st.label_varianten(job.get("label")) if not st.label_unbrauchbar(job.get("label")) else []
    kand = [x[3] for x in (k_alt or [])]
    schon = set()
    lesung = {"anfang": [], "ende": [], "woerter": set(), "jahre": [], "text": ""}
    sieger, grund, bewertet = None, "", []
    pers_zahl, pers_gesucht = {}, set()
    for teil in ("anfang", "ende"):
        neu = lesen(datei, ist_s, log, standbild, (teil,))
        lesung[teil] = neu[teil]
        lesung["woerter"] |= neu["woerter"]
        lesung["jahre"] = sorted(set(lesung["jahre"]) | set(neu["jahre"]))
        zeilen = suchzeilen(lesung)
        if zeilen:
            _log(log, "Erkennung 3: lese im Bild: " + " | ".join(zeilen[:6]))
        kand += _suchen(st, zeilen, label_texte, schon)
        bewertet = bewerten(st, kand, ist_s, dvd, lesung, label_texte, pers_zahl)
        sieger, grund = entscheiden(bewertet)
        if sieger:
            break
        lesung["zeilen"] = lesung.get("zeilen", []) + neu.get("zeilen", [])
        nm = [n for n in namen(lesung) if n not in pers_gesucht]
        if nm:  # Claude B 24.11.2026: Leute im Vor-/Abspann -> ihre Filme (Bond: Broccoli/Saltzman 1964 -> Goldfinger)
            pers_gesucht |= set(nm)
            filme, zahl, gefunden = personen_filme(st, nm, lesung.get("jahre"))
            for imdb, n in zahl.items():
                pers_zahl[imdb] = pers_zahl.get(imdb, 0) + n
            kand += list(filme.values())
            bewertet = bewerten(st, kand, ist_s, dvd, lesung, label_texte, pers_zahl)
            sieger, grund = entscheiden(bewertet)
            if sieger:
                break
    for x in bewertet[:4]:
        _log(log, f"Erkennung 3: {x['info']['titel']} ({x['info']['jahr']}) {x['info']['imdb']} - Punkte {x['punkte']}, "
                  f"Bild {x['bild']}, Name {x['name']}, Leute {x.get('personen', 0)}, Laufzeit {ist_s / 60:.0f}/{x['soll']} min, "
                  f"Jahr {x['jahr_ok']}")
    _log(log, f"Erkennung 3: fertig nach {time.time() - t0:.0f} s - {'erkannt' if sieger else grund}")
    vorschlaege = [{"imdb": x["info"]["imdb"], "titel": x["info"]["titel"], "jahr": x["info"]["jahr"],
                    "bild": x["bild"], "punkte": x["punkte"]} for x in bewertet[:3]]
    if sieger:
        i = dict(sieger["info"])
        if "lego" in norm(i.get("englisch")) and "lego" not in norm(i.get("titel")):  # Wikidata: "Star Wars – Die Padawan-Bedrohung"
            i["titel"] = f"LEGO {i['titel']}"
        return i, "Texterkennung", (f"Texterkennung ({grund}), Titel im Bild {sieger['bild']:.2f}, Leute {sieger.get('personen', 0)}, "
                                    f"Laufzeit {ist_s / 60:.0f}/{sieger['soll']} min"), vorschlaege
    return None, None, grund, vorschlaege


# ------------------------------------------------------------------ Daten-Discs (gebrannte Discs mit Filmdateien)
SCHLECHT_WOERTER = {"cam", "camrip", "ts", "hdts", "telesync", "tc", "telecine", "mic", "md", "ld", "screener", "scr",
                    "dvdscr", "hdcam", "tsrip", "workprint"}
WEG_WOERTER = SCHLECHT_WOERTER | {"german", "deutsch", "ger", "deu", "dl", "ac3", "dts", "xvid", "divx", "x264", "x265",
                                  "h264", "h265", "hevc", "dvdrip", "bdrip", "brrip", "webrip", "web", "dvd", "dvd5",
                                  "dvd9", "720p", "1080p", "2160p", "dub", "dubbed", "line", "proper", "repack", "cd1",
                                  "cd2", "avi", "mkv", "mp4", "aac", "mp3", "internal", "uncut", "ws", "fs", "pal"}


def _probe(datei):
    try:
        r = subprocess.run(["ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams", datei],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60, text=True)
        return json.loads(r.stdout or "{}")
    except Exception:
        return {}


def daten_disc_pruefen(st, job, ziel, videos):
    """Claude B 24.11.2026: gebrannte Disc mit Filmdatei selbst pruefen - Laufzeit, Aufloesung, Ton, Hinweise im
    Dateinamen (mic, ts, cam = im Kino aufgenommen) - und einen Film vorschlagen (Name, sonst Bild).
    Verschoben wird NICHTS (Wunsch Lukas 23.11.: Daten-Discs bleiben in _Pruefen, Claude sortiert ein).
    Gibt einen Text fuer die Notiz zurueck."""
    rel, groesse = videos[0]
    datei = os.path.join(ziel, rel)
    j = _probe(datei)
    dauer = float(j.get("format", {}).get("duration") or 0)
    v = next((x for x in j.get("streams", []) if x.get("codec_type") == "video"), {})
    a = [x for x in j.get("streams", []) if x.get("codec_type") == "audio"]
    w, h = v.get("width") or 0, v.get("height") or 0
    name = os.path.splitext(os.path.basename(rel))[0]
    wort = norm(re.sub(r"[_.\-]+", " ", name)).split()
    flags = sorted(set(wort) & SCHLECHT_WOERTER)
    if flags:
        qual, schlecht = f"schlecht (Dateiname: {', '.join(flags)} = im Kino aufgenommen)", True
    elif h and h < 480:
        qual, schlecht = f"schlecht ({w}x{h})", True
    elif h and h < 720:
        qual, schlecht = "DVD-Qualität", False
    elif h:
        qual, schlecht = "HD", False
    else:
        qual, schlecht = "unbekannt", False
    teile = [f"Pruefung: {rel} {dauer / 60:.0f} min, {w}x{h} {v.get('codec_name', '?')}, "
             f"Ton {', '.join(str(x.get('channels', '?')) + '-Kanal ' + str((x.get('tags') or {}).get('language', '')) for x in a[:3]).strip() or 'keiner'}",
             f"Qualitaet {qual}"]
    vorschlag = None
    if dauer >= 40 * 60:
        such = " ".join(x for x in wort if x not in WEG_WOERTER and not re.fullmatch(r"(19|20)\d\d", x))
        jahr = next((int(x) for x in wort if re.fullmatch(r"(19|20)\d\d", x)), None)
        job2 = dict(job, label=(such or job.get("label") or "").upper().replace(" ", "_"), disctype="dvd",
                    crc_id=None, hasnicetitle=0, imdb_id=None, imdb_id_auto=None)
        try:
            k = st.kandidaten(job2) if such else []
            info, quelle, text = st.waehle(k, dauer, True)
            if not info:
                info, quelle, text, _ = erkennen_mit_bild(st, job2, datei, dauer, k, None)
            if info:
                vorschlag = f"{info['titel']} ({info['jahr']}) {info['imdb']}"
                if jahr and str(jahr) != str(info["jahr"]):
                    vorschlag += f" (Dateiname sagt {jahr} - pruefen)"
        except Exception as e:
            _log(st.log, f"Daten-Disc pruefen: {e}")
    teile.append(f"Vorschlag: {vorschlag}" if vorschlag else "Film nicht erkannt")
    teile.append("gehoert nach: " + ("Lukas Filme nochmal drueber schauen/4 Schlechte Qualitaet" if schlecht
                                     else "Ripping-Station/Filme (in Jellyfin)"))
    text = "; ".join(teile)
    _log(st.log, f"Daten-Disc {job.get('label')}: {text}")
    return text


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
    elif len(sys.argv) >= 4 and sys.argv[1] == "daten":
        import importlib.machinery
        import importlib.util
        lo = importlib.machinery.SourceFileLoader("station", "/opt/station/station.py")
        st = importlib.util.module_from_spec(importlib.util.spec_from_loader("station", lo))
        lo.exec_module(st)
        st.LOG = "/tmp/erkennung3_test.log"
        ordner, rel = sys.argv[2], sys.argv[3]
        print(daten_disc_pruefen(st, {"label": os.path.basename(ordner), "job_id": 0}, ordner,
                                 [(rel, os.path.getsize(os.path.join(ordner, rel)))]))
    else:
        print(__doc__)
