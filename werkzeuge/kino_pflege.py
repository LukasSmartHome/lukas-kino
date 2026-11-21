#!/usr/bin/env python3
# Kino-Pflege (Claude, Konto B, 03.11.2026) – Trailer-Liste und Kategorien für Lukas Kino (TCL und LG).
#
# Läuft auf dem Pi: im File editor (zum Testen, Pfade /homeassistant/...) oder in Home Assistant über
# den command_line-Sensor sensor.kino_pflege (Paket packages/kino_pflege.yaml, Pfade /config/...).
# Jellyfin-Schlüssel nur aus <config>/jellyfin_schluessel.txt (wird nie ausgegeben).
#
# Befehle:
#   vorwaermen [alles|filme|fotos] [--faeden N]   alle Vorschaubilder in den Größen der Fernseher bei Jellyfin
#                            einmal anfragen (danach liegen sie fertig im Zwischenspeicher), Stand in vorwaermen_stand.json
#   logos [--los]            verpixelte Logos (SVG oder klein) gegen große PNG-Logos aus TMDB/Fanart tauschen
#   daten [ziel.json]        alle Filme mit Genres, Sammlungen, Trailer-Links (zum Prüfen)
#   trailer [--alle|--ohne|--suche]  /share/kino/trailer.json neu (--suche: Treffer der YouTube-Suche nochmal prüfen): je Film der beste YouTube-Trailer
#                            (eigene Liste > KinoCheck deutsch > Jellyfin deutsch > KinoCheck englisch > Jellyfin),
#                            jeder Kandidat wird per YouTube-oEmbed geprüft (gibt es ihn, darf man ihn einbetten?).
#                            Ohne --alle nur Filme, die noch nicht in der Liste stehen oder ohne Trailer älter als 7 Tage.
#   kategorien [--los]       Sammlungen in Jellyfin nach kategorien.json (Genres plus Ausnahmen je Film) abgleichen.
#                            Ohne --los nur anzeigen, was sich ändern würde.
#   qualitaet [--los]        Jellyfin-Tags „DVD“ und „Blu-ray“ (ab 1080p, ohne Kino-Mitschnitte) für die Kacheln in der App
#   filmreihen [--los]       TMDB-Filmreihen (ab 2 Filmen) und „Marvel Cinematic Universe“ als Sammlungen anlegen/ergänzen
#   reihen [--los]           Filmreihen: Namen aufräumen, Reihenfolge (Episoden), Poster des ersten Films -> /share/kino/reihen.json
#   luecken [--los]          fehlende Beschreibungen, Hintergründe und Poster (statt Standbild) von TMDB/Fanart nachziehen
#   taeglich                 alles für den täglichen Lauf (Trailer, Kategorien, Qualität, Filmreihen, Lücken, Logos,
#                            nur neue Vorschaubilder)
#   auto                     trailer (höchstens 60 neue Filme je Lauf) und kategorien --los (für den Sensor, kurze Ausgabe)
import json, os, re, sys, time, unicodedata, urllib.request, urllib.parse, urllib.error

S = "http://192.168.178.44:8096"
C = "/config" if os.path.isdir("/config/.storage") else "/homeassistant"
WERK = os.path.join(C, "kino_werkzeuge")
TRAILER_ZIEL = "/share/kino/trailer.json"
TRAILER_EIGEN = os.path.join(WERK, "trailer_eigen.json")      # {Jellyfin-ID oder tmdb:N: {"yt": "...", "t": "..."}} oder {"keiner": true}
KATEGORIEN = os.path.join(WERK, "kategorien.json")


def lesen(pfad, leer=None):
    try:
        with open(pfad, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {} if leer is None else leer


def schreiben(pfad, daten):
    tmp = pfad + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(daten, f, ensure_ascii=False, indent=1)
    os.replace(tmp, pfad)


def schluessel():
    k = open(os.path.join(C, "jellyfin_schluessel.txt"), encoding="utf-8").read().strip().strip('"')
    if len(k) < 20:
        raise SystemExit("kein Jellyfin-Schlüssel")
    return k


def anfrage(pfad, key, methode="GET", daten=None):
    url = S + pfad
    kopf = {"Authorization": 'MediaBrowser Token="{}"'.format(key), "Accept": "application/json"}
    body = None
    if daten is not None:
        body = json.dumps(daten).encode("utf-8")
        kopf["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=kopf, method=methode)
    with urllib.request.urlopen(req, timeout=60) as r:
        d = r.read()
    return json.loads(d) if d and d[:1] in (b"{", b"[") else None


def anfrage_sicher(pfad, key, stand=None):
    """Wie anfrage, aber ein Fehler (z. B. 502 bei einem einzelnen Ordner) bricht den Lauf nicht ab:
    einmal nach 3 s nochmal versuchen, sonst None (und als Fehler zählen)."""
    for versuch in (0, 1):
        try:
            return anfrage(pfad, key)
        except Exception:
            if versuch == 0:
                time.sleep(3)
    if stand is not None:
        stand["fehler"] = stand.get("fehler", 0) + 1
        stand["ordner_fehler"] = stand.get("ordner_fehler", 0) + 1
    return None


def q(**werte):
    return "&".join("{}={}".format(k, urllib.parse.quote(str(v), safe=",")) for k, v in werte.items())


def filme_holen(key, user):
    felder = "Genres,Tags,ProviderIds,RemoteTrailers,ProductionYear,OriginalTitle,Overview,Studios,ImageTags"
    return anfrage("/Items?" + q(userId=user, includeItemTypes="Movie", recursive="true", fields=felder), key).get("Items") or []


def sammlungen_holen(key, user):
    out = []
    for b in anfrage("/Items?" + q(userId=user, includeItemTypes="BoxSet", recursive="true", sortBy="SortName"), key).get("Items") or []:
        kinder = anfrage("/Items?" + q(userId=user, parentId=b["Id"]), key).get("Items") or []
        out.append({"name": b.get("Name"), "id": b["Id"], "filme": [x["Id"] for x in kinder]})
    return out


# ------------------------------------------------------------------ daten

def befehl_daten(ziel):
    key = schluessel()
    user = [u for u in anfrage("/Users", key) if u["Policy"]["IsAdministrator"]][0]["Id"]
    filme = filme_holen(key, user)
    samm = sammlungen_holen(key, user)
    drin = {}
    for s in samm:
        for i in s["filme"]:
            drin.setdefault(i, []).append(s["name"])
    out = []
    for e in sorted(filme, key=lambda x: (x.get("Name") or "").lower()):
        ids = e.get("ProviderIds") or {}
        out.append({"id": e["Id"], "t": e.get("Name"), "o": e.get("OriginalTitle"), "j": e.get("ProductionYear"),
                    "tmdb": ids.get("Tmdb") or ids.get("tmdb"), "imdb": ids.get("Imdb") or ids.get("imdb"),
                    "g": e.get("Genres") or [], "tags": e.get("Tags") or [], "k": sorted(drin.get(e["Id"], [])),
                    "tr": len(e.get("RemoteTrailers") or []), "s": (e.get("Overview") or "")[:160]})
    schreiben(ziel, {"stand": time.strftime("%Y-%m-%d %H:%M"), "sammlungen": [{"name": s["name"], "id": s["id"], "n": len(s["filme"])} for s in samm],
                     "filme": out})
    print("{} Filme, {} Sammlungen -> {}".format(len(out), len(samm), ziel))



# ------------------------------------------------------------------ trailer

YT = re.compile(r"(?:v=|youtu\.be/|embed/|shorts/)([A-Za-z0-9_-]{11})")
UNTERTITEL = re.compile(r"\bomu\b|\bov\b|untertitel|subtitle|\bsub\b|\bomdu\b", re.I)
DEUTSCH = re.compile(r"german|deutsch|\bde\b|offiziell", re.I)


def web(url, timeout=20):
    req = urllib.request.Request(url, headers={"User-Agent": "LukasKino/1.0", "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def yt_pruefen(yt, merker):
    """True = gibt es und darf eingebettet werden, False = nicht einbettbar/weg, None = unklar (Netz)."""
    if yt in merker:
        return merker[yt]
    url = "https://www.youtube.com/oembed?format=json&url=" + urllib.parse.quote("https://www.youtube.com/watch?v=" + yt, safe="")
    try:
        web(url, 15)
        ok = True
    except urllib.error.HTTPError as ex:
        ok = False if ex.code in (400, 401, 403, 404) else None
    except Exception:
        ok = None
    merker[yt] = ok
    return ok


def kinocheck(tmdb, sprache):
    out = []
    try:
        j = web("https://api.kinocheck.de/movies?" + q(tmdb_id=tmdb, language=sprache, categories="Trailer"))
    except Exception:
        return out
    if not isinstance(j, dict):
        return out
    kand = []
    if isinstance(j.get("trailer"), dict):
        kand.append(j["trailer"])
    kand.extend(x for x in (j.get("videos") or []) if isinstance(x, dict))
    for v in kand:
        yt = v.get("youtube_video_id") or ""
        kat = v.get("categories") or []
        if not yt or (kat and "Trailer" not in kat):
            continue
        if v.get("language") and v.get("language") != sprache:
            continue
        out.append({"yt": yt, "t": v.get("title") or "", "q": "KinoCheck", "spr": sprache})
    return out


def normal(t):
    t = (t or "").lower().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss").replace("&", " und ")
    t = "".join(c for c in unicodedata.normalize("NFD", t) if unicodedata.category(c) != "Mn")
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", " ", t)).strip()


NICHT_TRAILER = re.compile(r"reaction|review|kritik|explained|breakdown|fan ?made|concept|ending|ende erkl|recap|interview|behind|making of|clip|featurette|scene|szene", re.I)


def yt_suche(suche):
    """YouTube-Suche ohne Schlüssel (Ergebnisseite lesen). Liste {yt, t, sek}."""
    url = "https://www.youtube.com/results?" + q(search_query=suche, hl="de", gl="DE")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36",
                                               "Accept-Language": "de-DE,de;q=0.9", "Cookie": "SOCS=CAI; CONSENT=YES+1"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            html = r.read().decode("utf-8", "replace")
    except Exception:
        return []
    out = []
    for m in re.finditer(r'"videoRenderer":\{"videoId":"([A-Za-z0-9_-]{11})"(.{0,4000}?)"lengthText":\{.{0,300}?"simpleText":"([0-9:]+)"', html):
        if '"videoRenderer"' in m.group(2):
            continue
        tm = re.search(r'"title":\{"runs":\[\{"text":"((?:[^"\\]|\\.)*)"', m.group(2))
        titel = json.loads('"' + tm.group(1) + '"') if tm else ""
        teile = [int(x) for x in m.group(3).split(":")]
        sek = 0
        for x in teile:
            sek = sek * 60 + x
        out.append({"yt": m.group(1), "t": titel, "sek": sek})
    return out


def youtube_kandidaten(e):
    namen = [x for x in [e.get("Name"), e.get("OriginalTitle")] if x]
    jahr = e.get("ProductionYear") or ""
    l, gesehen = [], set()
    for suche, spr in [("{} {} Trailer Deutsch".format(namen[0], jahr), "de"), ("{} {} official trailer".format(namen[-1], jahr), "en")]:
        for v in yt_suche(suche)[:10]:
            t = v["t"]
            nt = normal(t)
            if v["yt"] in gesehen or v["sek"] > 300 or v["sek"] < 30 or NICHT_TRAILER.search(t):
                continue
            if "trailer" not in nt and "teaser" not in nt:
                continue
            # der ganze Filmname muss als zusammenhängende Wörter im Videotitel stehen (Titel, Originaltitel oder der Teil
            # vor „ - “/„:“), und eine Jahreszahl im Videotitel muss passen – sonst landet z. B. „In the Line of Fire“
            # bei „A Line of Fire“ oder „Der Passfälscher (2022)“ bei „Der Fälscher (2025)“
            pad = " " + nt + " "
            treffer = False
            for n in namen:
                for teil in [n] + re.split(r"\s+[-–:]\s+|:\s*", n)[:1]:
                    nn = normal(teil)
                    if len(nn) >= 3 and (" " + nn + " ") in pad:
                        treffer = True
            jahre = [int(x) for x in re.findall(r"\b(19[0-9]{2}|20[0-9]{2})\b", t)]
            if jahre and jahr and not any(abs(x - int(jahr)) <= 1 for x in jahre):
                treffer = False
            if not treffer:
                continue
            gesehen.add(v["yt"])
            l.append({"yt": v["yt"], "t": t, "q": "YouTube-Suche", "spr": "de" if DEUTSCH.search(t) else spr})
    return l


def kandidaten(e, eigen):
    ids = e.get("ProviderIds") or {}
    tmdb = ids.get("Tmdb") or ids.get("tmdb") or ""
    l = []
    x = eigen.get(e["Id"]) or (eigen.get("tmdb:" + tmdb) if tmdb else None)
    if x:
        if x.get("keiner"):
            return None
        l.append({"yt": x.get("yt", ""), "t": x.get("t", ""), "q": "eigen", "spr": x.get("spr", "de"), "fest": True})
    if tmdb:
        l.extend(kinocheck(tmdb, "de"))
    jf = []
    for t in e.get("RemoteTrailers") or []:
        m = YT.search(t.get("Url") or "")
        if m:
            name = t.get("Name") or ""
            jf.append({"yt": m.group(1), "t": name, "q": "Jellyfin", "spr": "de" if DEUTSCH.search(name) else "en"})
    l.extend(x for x in jf if x["spr"] == "de")
    if tmdb:
        l.extend(kinocheck(tmdb, "en"))
    l.extend(x for x in jf if x["spr"] != "de")
    if not [x for x in l if x.get("yt")]:
        l.extend(youtube_kandidaten(e))
    return l


def rang(k, i):
    # eigene Wahl zuerst, dann deutscher Ton ohne Untertitel, dann Original ohne Untertitel, ganz hinten OmU/Untertitel
    if k.get("fest"):
        return (0, i)
    unter = 1 if UNTERTITEL.search(k.get("t") or "") else 0
    return (1 + unter * 2 + (0 if k.get("spr") == "de" else 1), i)


def befehl_trailer(alle=False, still=False, ohne_neu=False, suche_neu=False, max_neu=None):
    key = schluessel()
    user = [u for u in anfrage("/Users", key) if u["Policy"]["IsAdministrator"]][0]["Id"]
    filme = filme_holen(key, user)
    eigen = lesen(TRAILER_EIGEN)
    alt = lesen(TRAILER_ZIEL).get("filme") or {}
    merker, neu, gezaehlt = {}, {}, {"neu": 0, "ohne": 0, "geprueft": 0}
    jetzt = int(time.time())
    for e in filme:
        a = alt.get(e["Id"])
        fest = eigen.get(e["Id"]) or eigen.get("tmdb:" + str((e.get("ProviderIds") or {}).get("Tmdb", "")))
        if suche_neu and a and a.get("q") == "YouTube-Suche":
            a = None
        if a and not alle and not fest and (a.get("yt") or (not ohne_neu and jetzt - a.get("z", 0) < 7 * 86400)):
            neu[e["Id"]] = a
            continue
        if max_neu is not None and gezaehlt["geprueft"] >= max_neu:
            if a:
                neu[e["Id"]] = a
            continue          # Rest beim nächsten Lauf (viele neue Filme auf einmal, z. B. Filme4)
        gezaehlt["geprueft"] += 1
        l = kandidaten(e, eigen)
        bester = None
        if l:
            for k in sorted(l, key=lambda k: rang(k, l.index(k))):
                if not k.get("yt"):
                    continue
                if k.get("fest") or yt_pruefen(k["yt"], merker) is not False:
                    bester = k
                    break
        if bester:
            neu[e["Id"]] = {"yt": bester["yt"], "t": bester.get("t", ""), "q": bester["q"], "spr": bester.get("spr", ""),
                            "name": e.get("Name"), "z": jetzt}
            gezaehlt["neu"] += 1
            if not still and (a is None or a.get("yt") != bester["yt"]):
                print("  neu: {} -> {} ({}, {})".format(e.get("Name"), bester["yt"], bester["q"], bester.get("t", "")[:70]))
        else:
            neu[e["Id"]] = {"yt": "", "keiner": True, "name": e.get("Name"), "j": e.get("ProductionYear"),
                            "tmdb": (e.get("ProviderIds") or {}).get("Tmdb"), "z": jetzt}
            gezaehlt["ohne"] += 1
    ohne = sorted([v.get("name") or k for k, v in neu.items() if not v.get("yt")])
    schreiben(TRAILER_ZIEL, {"stand": time.strftime("%Y-%m-%d %H:%M"), "hinweis": "kino_pflege.py trailer", "filme": neu, "ohne": ohne})
    if not still:
        print("Filme {}, geprüft {}, mit Trailer {}, ohne {}".format(len(filme), gezaehlt["geprueft"],
                                                                     sum(1 for v in neu.values() if v.get("yt")), len(ohne)))
        for n in ohne:
            print("  ohne:", n)
    return len(ohne)



# ------------------------------------------------------------------ kategorien

def befehl_kategorien(los=False, still=False):
    regeln = lesen(KATEGORIEN)
    if not regeln:
        raise SystemExit("keine Regeln in " + KATEGORIEN)
    zuo = regeln.get("zuordnung") or {}
    aus = regeln.get("filme") or {}
    key = schluessel()
    user = [u for u in anfrage("/Users", key) if u["Policy"]["IsAdministrator"]][0]["Id"]
    filme = filme_holen(key, user)
    # Nur die Genre-Kategorien aus kategorien.json anfassen. Alle anderen Sammlungen in Jellyfin sind Filmreihen
    # (Star Wars, Eberhofer ... - seit 16.11.2026) und bleiben, wie sie sind; frueher haette dieser Abgleich sie geleert.
    samm = {s["name"]: s for s in sammlungen_holen(key, user) if s["name"] in set(zuo.values())}
    fehlt = sorted(set(zuo.values()) - set(samm))
    if fehlt:
        print("Sammlungen fehlen in Jellyfin (werden nicht angelegt):", ", ".join(fehlt))
    soll = {n: set() for n in samm}
    namen = {}
    for e in filme:
        namen[e["Id"]] = e.get("Name")
        k = {zuo[g] for g in (e.get("Genres") or []) if g in zuo}
        a = aus.get("tmdb:" + str((e.get("ProviderIds") or {}).get("Tmdb", ""))) or aus.get(e["Id"]) or {}
        k |= set(a.get("dazu") or [])
        k -= set(a.get("weg") or [])
        for n in k:
            if n in soll:
                soll[n].add(e["Id"])
    film_ids = set(namen)
    dazu_n = weg_n = 0
    zeilen = []
    for n, s in samm.items():
        ist = set(s["filme"]) & film_ids          # nur Filme anfassen (falls jemand Serien hineinlegt)
        dazu = sorted(soll[n] - ist, key=lambda i: namen[i] or "")
        weg = sorted(ist - soll[n], key=lambda i: namen[i] or "")
        if not dazu and not weg:
            continue
        zeilen.append("{}: +{} -{}".format(n, len(dazu), len(weg)))
        for i in dazu:
            zeilen.append("   + " + str(namen[i]))
        for i in weg:
            zeilen.append("   - " + str(namen[i]))
        dazu_n += len(dazu)
        weg_n += len(weg)
        if los:
            for i in range(0, len(dazu), 40):
                anfrage("/Collections/{}/Items?{}".format(s["id"], q(ids=",".join(dazu[i:i + 40]))), key, "POST")
            for i in range(0, len(weg), 40):
                anfrage("/Collections/{}/Items?{}".format(s["id"], q(ids=",".join(weg[i:i + 40]))), key, "DELETE")
    ohne = sorted(str(namen[i]) for i in film_ids if not any(i in s for s in soll.values()))
    if not still:
        print("\n".join(zeilen) if zeilen else "Alles passt schon.")
        print("{}: {} dazu, {} raus{}".format("Geändert" if los else "Vorschau", dazu_n, weg_n, "" if los else " (mit --los ausführen)"))
        if ohne:
            print("Ohne Kategorie:", ", ".join(ohne))
    return dazu_n, weg_n



# ------------------------------------------------------------------ logos

SPRACHE_RANG = {"de": 0, "en": 1, None: 2, "": 2}


def befehl_logos(los=False):
    """Logos, die als SVG oder sehr klein vorliegen, sehen am Fernseher verpixelt aus (Jellyfin rechnet SVG in der
    winzigen Grundgröße, z. B. 36x24, und die Apps ziehen das hoch). Für diese Filme ein großes PNG-Logo aus den
    Bildquellen von Jellyfin (TMDB, Fanart) holen. Ohne --los nur anzeigen."""
    key = schluessel()
    user = [u for u in anfrage("/Users", key) if u["Policy"]["IsAdministrator"]][0]["Id"]
    filme = filme_holen(key, user)
    schlecht, besser, ohne = 0, 0, []
    for e in filme:
        if not (e.get("ImageTags") or {}).get("Logo"):
            continue
        info = [x for x in (anfrage("/Items/{}/Images".format(e["Id"]), key) or []) if x.get("ImageType") == "Logo"]
        if not info:
            continue
        i = info[0]
        svg = (i.get("Path") or "").lower().endswith(".svg")
        if not svg and (i.get("Width") or 0) >= 400:
            continue
        schlecht += 1
        fern = anfrage("/Items/{}/RemoteImages?".format(e["Id"]) + q(type="Logo", includeAllLanguages="true"), key) or {}
        kand = [b for b in (fern.get("Images") or []) if b.get("Url") and not b["Url"].lower().endswith(".svg")
                and (b.get("Width") or 0) >= 400]
        kand.sort(key=lambda b: (SPRACHE_RANG.get(b.get("Language"), 3), -(b.get("Width") or 0)))
        if not kand:
            ohne.append("{} ({}x{}{})".format(e.get("Name"), i.get("Width"), i.get("Height"), ", svg" if svg else ""))
            continue
        b = kand[0]
        print("{}: {}x{}{} -> {}x{} {} {}".format(e.get("Name"), i.get("Width"), i.get("Height"), " svg" if svg else "",
                                                b.get("Width"), b.get("Height"), b.get("Language") or "-", b.get("ProviderName")))
        if los:
            anfrage("/Items/{}/RemoteImages/Download?".format(e["Id"]) + q(type="Logo", imageUrl=b["Url"]), key, "POST")
        besser += 1
    print("Logos verpixelt/klein: {}, {} {}".format(schlecht, "ersetzt" if los else "ersetzbar", besser))
    for x in ohne:
        print("  kein besseres gefunden:", x)



# ------------------------------------------------------------------ qualitaet (Tags „DVD“ und „Blu-ray“)

KINO_MITSCHNITT = re.compile(r"(?i)(^|[ ._\-\[(])(cam|hdcam|camrip|ts|hdts|telesync|tc|hdtc|telecine|scr|screener|dvdscr)([ ._\-\])]|$)")


def befehl_qualitaet(los=False, still=False):
    """Jellyfin-Tags für die Qualitäts-Kacheln in Lukas Kino (seit 21.11.2026):
    „DVD“ (rote Kachel) für Dateien mit „ [DVD]“ im Namen, „Blu-ray“ (blaue Kachel) für alles ab Full HD
    (1080p und 4K) – außer DVDs und Kino-Mitschnitten (CAM, TS …). Die Reihe „Neu“ zeigt nur Filme mit „Blu-ray“.
    Ändert nur die Jellyfin-Datenbank, nichts auf der Platte. Ohne --los nur anzeigen."""
    key = schluessel()
    user = [u for u in anfrage("/Users", key) if u["Policy"]["IsAdministrator"]][0]["Id"]
    filme = anfrage("/Items?" + q(userId=user, includeItemTypes="Movie", recursive="true", fields="Path,Tags,Width,Height"),
                    key).get("Items") or []
    eigen = ("DVD", "Blu-ray")
    n, zahl = 0, {"DVD": 0, "Blu-ray": 0}
    for e in filme:
        datei = os.path.basename(e.get("Path") or "")
        tags = list(e.get("Tags") or [])
        dvd = " [DVD]" in datei
        w, h = e.get("Width") or 0, e.get("Height") or 0
        bd = not dvd and (h >= 1000 or w >= 1900) and not KINO_MITSCHNITT.search(os.path.splitext(datei)[0])
        soll = (["DVD"] if dvd else []) + (["Blu-ray"] if bd else [])
        for t in soll:
            zahl[t] += 1
        if sorted(t for t in tags if t in eigen) == sorted(soll):
            continue
        n += 1
        if not still:
            print("  {} {}: {} -> {}".format("ändere" if los else "würde ändern", e.get("Name"),
                                             [t for t in tags if t in eigen] or "-", soll or "-"))
        if los:
            voll = anfrage("/Users/{}/Items/{}".format(user, e["Id"]), key)
            voll["Tags"] = [t for t in (voll.get("Tags") or []) if t not in eigen] + soll
            anfrage("/Items/{}".format(e["Id"]), key, "POST", voll)
    if not still:
        print("Qualität: {} DVD, {} Blu-ray, {} {}".format(zahl["DVD"], zahl["Blu-ray"], n, "geändert" if los else "zu ändern"))
    return n


# ------------------------------------------------------------------ filmreihen

def befehl_filmreihen(los=False, still=False):
    """Filmreihen als Jellyfin-Sammlungen (seit 21.11.2026): jede TMDB-Filmreihe, von der mindestens 2 verschiedene
    Filme da sind (Star Wars, Mission: Impossible, Deadpool …), dazu „Marvel Cinematic Universe“ (Studio „Marvel
    Studios“, ab 2008). Name und Bilder holt Jellyfin selbst von TMDB (Fernsuche, kein eigener TMDB-Schlüssel).
    Legt nur an und fügt nur hinzu, nimmt nie etwas heraus. Die Genre-Kategorien bleiben unberührt."""
    genres = set((lesen(KATEGORIEN).get("zuordnung") or {}).values())
    key = schluessel()
    user = [u for u in anfrage("/Users", key) if u["Policy"]["IsAdministrator"]][0]["Id"]
    filme = anfrage("/Items?" + q(userId=user, includeItemTypes="Movie", recursive="true",
                                  fields="ProviderIds,Studios,ProductionYear"), key).get("Items") or []
    gruppen = {}
    for e in filme:
        p = e.get("ProviderIds") or {}
        film = p.get("Tmdb") or e["Id"]
        if p.get("TmdbCollection"):
            g = gruppen.setdefault("c" + p["TmdbCollection"], {"tmdb": p["TmdbCollection"], "name": None, "ids": set(), "filme": set()})
            g["ids"].add(e["Id"])
            g["filme"].add(film)
        if (e.get("ProductionYear") or 0) >= 2008 and any((s.get("Name") or "") == "Marvel Studios" for s in (e.get("Studios") or [])):
            g = gruppen.setdefault("mcu", {"tmdb": None, "name": "Marvel Cinematic Universe", "ids": set(), "filme": set()})
            g["ids"].add(e["Id"])
            g["filme"].add(film)
    da = anfrage("/Items?" + q(userId=user, includeItemTypes="BoxSet", recursive="true", fields="ProviderIds"), key).get("Items") or []
    nach_tmdb = {(b.get("ProviderIds") or {}).get("Tmdb"): b for b in da if b.get("Name") not in genres}
    nach_name = {(b.get("Name") or "").lower(): b for b in da}
    neu_n = dazu_n = 0
    for g in sorted(gruppen.values(), key=lambda x: -len(x["filme"])):
        if len(g["filme"]) < 2:
            continue
        b = nach_tmdb.get(g["tmdb"]) if g["tmdb"] else None
        if not b and not g["name"]:
            r = anfrage("/Items/RemoteSearch/BoxSet", key, "POST",
                        {"SearchInfo": {"ProviderIds": {"Tmdb": g["tmdb"]}, "MetadataLanguage": "de", "MetadataCountryCode": "DE"}}) or []
            g["name"] = (r[0].get("Name") or "").strip() if r else ""
            if not g["name"]:
                if not still:
                    print("  ohne Namen bei TMDB, übersprungen: Reihe", g["tmdb"])
                continue
        if not b:
            # auch unter dem aufgeräumten Namen suchen (reihen benennt „Star Wars Filmreihe“ in „Star Wars“ um, 21.11.2026)
            b = nach_name.get(g["name"].lower()) or nach_name.get(reihen_name(g["name"]).lower())
        if b and b.get("Name") in genres:
            continue
        if b:
            ist = {x["Id"] for x in anfrage("/Items?" + q(userId=user, parentId=b["Id"]), key).get("Items") or []}
            dazu = sorted(g["ids"] - ist)
            if not dazu:
                continue
            dazu_n += len(dazu)
            if not still:
                print("  {}: +{}".format(b.get("Name"), len(dazu)))
            if los:
                anfrage("/Collections/{}/Items?{}".format(b["Id"], q(ids=",".join(dazu))), key, "POST")
            continue
        neu_n += 1
        if not still:
            print("  neu: {} ({} Filme)".format(g["name"], len(g["filme"])))
        if not los:
            continue
        c = anfrage("/Collections?" + q(name=g["name"], ids=",".join(sorted(g["ids"]))), key, "POST") or {}
        cid = c.get("Id")
        if cid and g["tmdb"]:
            # TMDB-Nummer eintragen, damit Jellyfin Poster, Hintergrund und Text der Reihe holt; Name bleibt fest
            voll = anfrage("/Users/{}/Items/{}".format(user, cid), key)
            voll["ProviderIds"] = dict(voll.get("ProviderIds") or {}, Tmdb=g["tmdb"])
            voll["LockedFields"] = sorted(set((voll.get("LockedFields") or []) + ["Name"]))
            anfrage("/Items/{}".format(cid), key, "POST", voll)
            anfrage("/Items/{}/Refresh?{}".format(cid, q(metadataRefreshMode="FullRefresh", imageRefreshMode="FullRefresh",
                                                          replaceAllMetadata="false", replaceAllImages="false")), key, "POST")
    if not still:
        print("Filmreihen: {} {}, {} Filme {}".format(neu_n, "neu angelegt" if los else "würden neu angelegt",
                                                       dazu_n, "hinzugefügt" if los else "würden hinzugefügt"))
    return neu_n, dazu_n


# ------------------------------------------------------------------ reihen (Kacheln und Reihenfolge der Filmreihen)

REIHEN_ZIEL = "/share/kino/reihen.json"
# schöne Namen für die Kacheln (sonst fällt nur „Filmreihe“/„Collection“ weg) – Wunsch Lukas 21.11.2026: sinnige Titel
REIHEN_NAMEN = {
    "spider-man (avengers)": "Spider-Man",
    "star trek: die kelvin-zeitachse": "Star Trek",
    "asterix und obelix (animation)": "Asterix & Obelix",
    "millennium (remake)": "Millennium – Neuverfilmung",
    "ich - einfach unverbesserlich": "Ich – Einfach unverbesserlich",
    "winnetou - der mythos lebt": "Winnetou – Der Mythos lebt",
}
EPISODE = re.compile(r"(?i)\bEpisode\s+([IVX]+|\d+)\b")
# Star-Wars-Filme, deren Titel keine Episode nennt (deutsch „Krieg der Sterne“ usw.), nach Originaltitel
SW_EPISODE = {"star wars": 4, "the empire strikes back": 5, "return of the jedi": 6, "the force awakens": 7,
              "the last jedi": 8, "the rise of skywalker": 9}


def _roemisch(t):
    if t.isdigit():
        return int(t)
    w = {"I": 1, "V": 5, "X": 10}
    t = t.upper()
    n = 0
    for i, ch in enumerate(t):
        v, nach = w.get(ch, 0), (w.get(t[i + 1], 0) if i + 1 < len(t) else 0)
        n += -v if v < nach else v
    return n


def reihen_name(n):
    k = re.sub(r"(?i)[\s-]*\b(Filmreihe|Collection|Sammlung)\b", "", n or "")
    k = re.sub(r"\s+", " ", k).replace(" :", ":").strip(" -–:")
    return REIHEN_NAMEN.get(k.lower(), k) or n


def _episode(f):
    for t in (f.get("Name") or "", f.get("OriginalTitle") or ""):
        m = EPISODE.search(t)
        if m:
            return _roemisch(m.group(1))
    o = (f.get("OriginalTitle") or "").strip().lower()
    o2 = o[len("star wars: "):] if o.startswith("star wars: ") else o
    return SW_EPISODE.get(o, SW_EPISODE.get(o2)) if "star wars" in o or o2 in SW_EPISODE else None


def reihen_ordnung(filme):
    """Reihenfolge in einer Filmreihe: nach Episode (Star Wars I, II, III …), sonst nach Erscheinen.
    Filme ohne Episode kommen hinter die letzte Episode, die vor ihnen erschienen ist."""
    def datum(f):
        return f.get("PremiereDate") or "{:04d}".format(f.get("ProductionYear") or 9999)
    ep = {f["Id"]: _episode(f) for f in filme}
    if not any(v is not None for v in ep.values()):
        return sorted(filme, key=datum)

    def schl(f):
        e = ep[f["Id"]]
        if e is None:
            frueher = [ep[g["Id"]] for g in filme if ep[g["Id"]] is not None and datum(g) <= datum(f)]
            e = (max(frueher) if frueher else 0) + 0.5
        return (e, datum(f))
    return sorted(filme, key=schl)


def befehl_reihen(los=False, still=False):
    """Seit 21.11.2026: für jede Filmreihe (keine Genre-Kategorie) Name aufräumen (Jellyfin, gesperrt), Reihenfolge der
    Filme und Poster des ersten Films nach /share/kino/reihen.json – daraus bauen TCL und LG die Sammlungs-Kacheln
    (eigenes Bild statt des Sammlungs-Posters mit „3 Movie Collection“ und doppelter Schrift). Ohne --los nur anzeigen."""
    genres = set((lesen(KATEGORIEN).get("zuordnung") or {}).values())
    key = schluessel()
    user = [u for u in anfrage("/Users", key) if u["Policy"]["IsAdministrator"]][0]["Id"]
    da = anfrage("/Items?" + q(userId=user, includeItemTypes="BoxSet", recursive="true"), key).get("Items") or []
    daten, umbenannt = {}, 0
    for b in da:
        if b.get("Name") in genres:
            continue
        kinder = anfrage("/Items?" + q(userId=user, parentId=b["Id"], fields="OriginalTitle,PremiereDate,PrimaryImageAspectRatio"),
                         key).get("Items") or []
        kinder = [f for f in kinder if f.get("Type") == "Movie"]
        if not kinder:
            continue
        ordnung = reihen_ordnung(kinder)
        bild = next((f for f in ordnung if (f.get("ImageTags") or {}).get("Primary")
                     and (f.get("PrimaryImageAspectRatio") or 1) < 1), None)
        schoen = reihen_name(b["Name"])
        daten[b["Id"]] = {"name": schoen, "filme": [f["Id"] for f in ordnung], "n": len(ordnung),
                          "bild": {"id": bild["Id"], "tag": bild["ImageTags"]["Primary"]} if bild else None}
        if not still:
            print("  {} -> {} | {}".format(b["Name"], schoen, " · ".join(f.get("Name") for f in ordnung)))
        if schoen != b["Name"]:
            umbenannt += 1
            if los:
                voll = anfrage("/Users/{}/Items/{}".format(user, b["Id"]), key)
                voll["Name"] = schoen
                voll["LockedFields"] = sorted(set((voll.get("LockedFields") or []) + ["Name"]))
                anfrage("/Items/{}".format(b["Id"]), key, "POST", voll)
    if los:
        schreiben(REIHEN_ZIEL, {"stand": time.strftime("%Y-%m-%d %H:%M"), "hinweis": "kino_pflege.py reihen", "reihen": daten})
    if not still:
        print("Reihen: {} Filmreihen, {} Namen {}".format(len(daten), umbenannt, "geändert" if los else "zu ändern"))
    return len(daten)



# ------------------------------------------------------------------ lücken (Beschreibung, Hintergrund, Poster)

def befehl_luecken(los=False, still=False):
    """Seit 21.11.2026: Filme ohne Beschreibung bekommen den Text von TMDB (deutsch, sonst englisch) über die
    Jellyfin-Fernsuche. Filme ohne Hintergrundbild bekommen das größte von TMDB/Fanart (am liebsten ohne Schrift).
    Hat ein Film statt eines Posters nur ein querformatiges Standbild aus dem Film (so macht es Jellyfin, wenn TMDB
    kein Poster hat), wird es gegen ein echtes Poster getauscht, falls es eins gibt. Ohne --los nur anzeigen."""
    key = schluessel()
    user = [u for u in anfrage("/Users", key) if u["Policy"]["IsAdministrator"]][0]["Id"]
    filme = anfrage("/Items?" + q(userId=user, includeItemTypes="Movie", recursive="true",
                                  fields="Overview,ProviderIds,BackdropImageTags,PrimaryImageAspectRatio"), key).get("Items") or []
    n_text = n_hg = n_poster = 0
    rest = []
    for e in filme:
        name = e.get("Name")
        tmdb = (e.get("ProviderIds") or {}).get("Tmdb")
        if not (e.get("Overview") or "").strip() and tmdb:
            txt, spr = "", ""
            for spr, land in (("de", "DE"), ("en", "US")):
                r = anfrage("/Items/RemoteSearch/Movie", key, "POST",
                            {"SearchInfo": {"ProviderIds": {"Tmdb": tmdb}, "MetadataLanguage": spr, "MetadataCountryCode": land}}) or []
                txt = ((r[0].get("Overview") if r else "") or "").strip()
                if txt:
                    break
            if txt:
                n_text += 1
                if not still:
                    print("  Beschreibung ({}): {}".format(spr, name))
                if los:
                    voll = anfrage("/Users/{}/Items/{}".format(user, e["Id"]), key)
                    voll["Overview"] = txt
                    anfrage("/Items/{}".format(e["Id"]), key, "POST", voll)
            else:
                rest.append("{} (keine Beschreibung bei TMDB)".format(name))
        for typ, fehlt, mind in (("Backdrop", not e.get("BackdropImageTags"), 1280),
                                 ("Primary", (e.get("PrimaryImageAspectRatio") or 0) > 1.0, 500)):
            if not fehlt:
                continue
            fern = anfrage("/Items/{}/RemoteImages?".format(e["Id"]) + q(type=typ, includeAllLanguages="true"), key) or {}
            kand = [b for b in (fern.get("Images") or []) if b.get("Url") and (b.get("Width") or 0) >= mind]
            if typ == "Backdrop":
                kand.sort(key=lambda b: (b.get("Language") not in (None, ""), -(b.get("Width") or 0)))
            else:
                kand = [b for b in kand if (b.get("Height") or 0) > (b.get("Width") or 0)]
                kand.sort(key=lambda b: (SPRACHE_RANG.get(b.get("Language"), 3), -(b.get("Height") or 0)))
            if not kand:
                rest.append("{} (kein {} gefunden)".format(name, "Hintergrund" if typ == "Backdrop" else "Poster"))
                continue
            if typ == "Backdrop":
                n_hg += 1
            else:
                n_poster += 1
            if not still:
                print("  {}: {} {}x{} {}".format("Hintergrund" if typ == "Backdrop" else "Poster", name,
                                                 kand[0].get("Width"), kand[0].get("Height"), kand[0].get("ProviderName")))
            if los:
                anfrage("/Items/{}/RemoteImages/Download?".format(e["Id"]) + q(type=typ, imageUrl=kand[0]["Url"]), key, "POST")
    if not still:
        print("Lücken: {} Beschreibungen, {} Hintergründe, {} Poster {}".format(n_text, n_hg, n_poster,
                                                                                "ergänzt" if los else "ergänzbar"))
        for x in rest:
            print("  offen:", x)
    return n_text + n_hg + n_poster



# ------------------------------------------------------------------ vorwaermen

WEBP = "image/webp,image/apng,image/*,*/*;q=0.8"   # so fragt der Browser der LG-Fernseher (bekommt WebP)


def befehl_vorwaermen(teil="alles", faeden=2, nur_neue=False):
    """Alle Vorschaubilder einmal in genau den Größen anfragen, die die Fernseher benutzen. Jellyfin rechnet jedes Bild
    beim ersten Abruf aus dem Original (bei Fotos 12 Megapixel) klein und legt es in seinen Zwischenspeicher – danach
    kommt es sofort. TCL (ohne Accept, bekommt JPEG) und LG-Browser (WebP) bekommen je eine eigene Fassung.
    teil: alles, filme, fotos oder ordner. Arbeitet sofort los (die Liste wird nebenbei geholt), Stand in
    vorwaermen_stand.json."""
    import threading, concurrent.futures
    # läuft schon ein Vorwärmen (auch aus dem anderen Container, z. B. von Hand im File editor)? Dann nicht doppelt.
    sp = os.path.join(WERK, "vorwaermen_stand.json")
    alt = lesen(sp)
    if alt and alt.get("phase") != "fertig" and os.path.exists(sp) and time.time() - os.path.getmtime(sp) < 600:
        print("Vorwärmen läuft schon – übersprungen")
        return
    key = schluessel()
    user = [u for u in anfrage("/Users", key) if u["Policy"]["IsAdministrator"]][0]["Id"]
    stand = {"phase": "start", "fertig": 0, "fehler": 0, "bytes": 0, "angefragt": 0}
    kaputt = set()   # Abrufe mit Fehler – werden NICHT als erledigt gemerkt (04.11.2026)
    sperre = threading.Lock()
    beginn = time.time()

    def melden(zwang=False):
        if zwang or stand["fertig"] % 200 == 0:
            dauer = max(1, time.time() - beginn)
            schreiben(os.path.join(WERK, "vorwaermen_stand.json"), {
                "phase": stand["phase"], "fertig": stand["fertig"], "angefragt": stand["angefragt"], "fehler": stand["fehler"],
                "mb": round(stand["bytes"] / 1e6), "pro_sekunde": round(stand["fertig"] / dauer, 1),
                "minuten": round(dauer / 60), "zeit": time.strftime("%H:%M")})

    def eins(arg):
        pfad, accept = arg
        kopf = {"Authorization": 'MediaBrowser Token="{}"'.format(key)}
        if accept:
            kopf["Accept"] = accept
        try:
            with urllib.request.urlopen(urllib.request.Request(S + pfad, headers=kopf), timeout=120) as r:
                n = len(r.read())
            ok = True
        except Exception:
            n, ok = 0, False
        with sperre:
            stand["fertig"] += 1
            stand["bytes"] += n
            if not ok:
                stand["fehler"] += 1
                kaputt.add(pfad)
            melden()

    ex = concurrent.futures.ThreadPoolExecutor(max_workers=faeden)
    offen = []
    merk_pfad = os.path.join(WERK, "vorwaermen_erledigt.txt")
    erledigt = set()
    if nur_neue:
        try:
            with open(merk_pfad, encoding="utf-8") as f:
                erledigt = set(z.strip() for z in f if z.strip())
        except Exception:
            erledigt = set()
    neu_gemerkt = []

    def dazu(pfad):
        if pfad in erledigt:
            return
        erledigt.add(pfad)
        neu_gemerkt.append(pfad)
        for a in (None, WEBP):
            offen.append(ex.submit(eins, (pfad, a)))
            stand["angefragt"] += 1
        # nicht zu viel auf Vorrat (Speicher): warten, wenn mehr als 400 Abrufe offen sind
        while len(offen) > 400:
            offen.pop(0).result()

    if teil in ("alles", "filme"):
        stand["phase"] = "filme"
        melden(True)
        for e in anfrage("/Items?" + q(userId=user, includeItemTypes="Movie,Series", recursive="true",
                                         fields="ImageTags,BackdropImageTags"), key).get("Items") or []:
            i, t, b = e["Id"], e.get("ImageTags") or {}, e.get("BackdropImageTags") or []
            if t.get("Primary"):
                dazu("/Items/{}/Images/Primary?fillHeight=330&quality=90&tag={}".format(i, t["Primary"]))
                if not b:
                    dazu("/Items/{}/Images/Primary?fillHeight=1080&quality=90&tag={}".format(i, t["Primary"]))
            if t.get("Logo"):
                dazu("/Items/{}/Images/Logo?fillHeight=220&quality=90&tag={}".format(i, t["Logo"]))
            if b:
                for w, qu in ((1920, 85), (1280, 80), (940, 85)):
                    dazu("/Items/{}/Images/Backdrop/0?fillWidth={}&quality={}&tag={}".format(i, w, qu, b[0]))
    fotos = None
    if teil in ("alles", "fotos", "ordner"):
        for v in anfrage("/UserViews?" + q(userId=user), key).get("Items") or []:
            if v.get("CollectionType") in ("photos", "homevideos") and fotos is None:
                fotos = v["Id"]
    if fotos and teil in ("alles", "ordner"):
        # zuerst die Titelbilder der obersten Ordner (das sieht man beim Umschalten auf Fotos zuerst)
        stand["phase"] = "ordner"
        melden(True)
        wurzel = anfrage("/Items?" + q(userId=user, parentId=fotos, fields="ImageTags", enableUserData="false"),
                         key).get("Items") or []
        ebene = [o["Id"] for o in wurzel if o.get("IsFolder")]
        mit_fotos = teil == "alles"     # beim Durchgehen gleich alle Fotos für die Bilderwand mitnehmen
        if mit_fotos:
            for x in wurzel:
                tg = (x.get("ImageTags") or {}).get("Primary")
                if x.get("Type") == "Photo" and tg:
                    dazu("/Items/{}/Images/Primary?fillWidth=360&quality=85&tag={}".format(x["Id"], tg))
        gesehen = set()
        while ebene:
            naechste = []
            for oid in ebene:
                if oid in gesehen:
                    continue
                gesehen.add(oid)
                inhalt = (anfrage_sicher("/Items?" + q(userId=user, parentId=oid, fields="ImageTags",
                                                       enableUserData="false"), key, stand) or {}).get("Items") or []
                naechste.extend(x["Id"] for x in inhalt if x.get("IsFolder"))
                if mit_fotos:
                    for x in inhalt:
                        tg = (x.get("ImageTags") or {}).get("Primary")
                        if x.get("Type") == "Photo" and tg:
                            dazu("/Items/{}/Images/Primary?fillWidth=360&quality=85&tag={}".format(x["Id"], tg))
                a = [x for x in inhalt if x.get("Type") == "Photo" and (x.get("ImageTags") or {}).get("Primary")][:1]
                if not a:
                    a = (anfrage_sicher("/Items?" + q(userId=user, parentId=oid, recursive="true", includeItemTypes="Photo",
                                                      imageTypes="Primary", limit=1, fields="ImageTags"), key, stand)
                         or {}).get("Items") or []
                if a and (a[0].get("ImageTags") or {}).get("Primary"):
                    tg = a[0]["ImageTags"]["Primary"]
                    for w in (536, 704, 1280):
                        dazu("/Items/{}/Images/Primary?fillWidth={}&quality=85&tag={}".format(a[0]["Id"], w, tg))
            ebene = naechste
    if fotos and teil == "fotos":
        stand["phase"] = "fotos"
        melden(True)
        start = 0
        while True:
            seite = anfrage("/Items?" + q(userId=user, parentId=fotos, recursive="true", includeItemTypes="Photo",
                                          fields="ImageTags", enableUserData="false", startIndex=start, limit=500), key).get("Items") or []
            for e in seite:
                tg = (e.get("ImageTags") or {}).get("Primary")
                if tg:
                    dazu("/Items/{}/Images/Primary?fillWidth=360&quality=85&tag={}".format(e["Id"], tg))
            if len(seite) < 500:
                break
            start += 500
    for f in offen:
        f.result()
    ex.shutdown(wait=True)
    # gemerkt wird nur, was geklappt hat (beim ersten Lauf ohne --neue die ganze Liste) – Fehlschläge
    # (z. B. Jellyfin wegen Speichermangel kurz weg) holt der nächste Tageslauf nach (04.11.2026)
    with open(merk_pfad, "a" if nur_neue else "w", encoding="utf-8") as f:
        f.write("\n".join(x for x in (neu_gemerkt if nur_neue else sorted(erledigt)) if x not in kaputt) + "\n")
    stand["phase"] = "fertig"
    melden(True)
    print("vorgewärmt: {} Abrufe, Fehler {}, {} MB, {} Minuten".format(stand["fertig"], stand["fehler"],
                                                                       round(stand["bytes"] / 1e6), round((time.time() - beginn) / 60)))


if __name__ == "__main__":
    a = sys.argv[1:]
    if not a:
        print(__doc__ or "Befehle: daten, trailer, kategorien, auto")
    elif a[0] == "daten":
        befehl_daten(a[1] if len(a) > 1 else os.path.join(WERK, "film_daten.json"))
    elif a[0] == "trailer":
        befehl_trailer(alle="--alle" in a, ohne_neu="--ohne" in a, suche_neu="--suche" in a)
    elif a[0] == "vorwaermen":
        befehl_vorwaermen(a[1] if len(a) > 1 and not a[1].startswith("-") else "alles",
                          int(a[a.index("--faeden") + 1]) if "--faeden" in a else 2, nur_neue="--neue" in a)
    elif a[0] == "taeglich":
        # täglicher Lauf (Automation 89 über shell_command.kino_pflege_starten): Trailer und Kategorien für neue Filme,
        # Logos, dann nur neue Vorschaubilder. Kurzer Stand für sensor.kino_pflege in kino_pflege_stand.txt.
        stand_pfad = os.path.join(WERK, "kino_pflege_stand.txt")
        teile = []
        try:
            teile.append("{} ohne Trailer".format(befehl_trailer(still=True, max_neu=60)))
            d, w = befehl_kategorien(los=True, still=True)
            teile.append("Kategorien +{} -{}".format(d, w))
            # die drei neuen Schritte (21.11.2026) dürfen den restlichen Lauf nie aufhalten
            for titel, schritt in (("Qualität", befehl_qualitaet), ("Reihen", befehl_filmreihen), ("Kacheln", befehl_reihen),
                                   ("Lücken", befehl_luecken)):
                try:
                    teile.append("{} {}".format(titel, schritt(los=True, still=True)))
                except Exception as fx:
                    teile.append("{} fehler {}".format(titel, str(fx)[:40]))
            befehl_logos(los=True)
            if os.path.exists(os.path.join(WERK, "vorwaermen_pause")):
                # pausiert (Lukas 03.11.2026): Vorwärmen füllt über Jellyfin die Platte, erst nach dem Umzug der Filme
                # auf die 14-TB-Platte fortsetzen – dann die Datei vorwaermen_pause löschen
                teile.append("Bilder pausiert")
            else:
                befehl_vorwaermen("alles", 1, nur_neue=True)   # 1 Faden: Pi hat nur 4 GB RAM (04.11.2026)
                teile.append("Bilder ok")
            text = "ok " + time.strftime("%d.%m. %H:%M") + ": " + ", ".join(teile)
        except BaseException as ex:
            text = "fehler " + time.strftime("%d.%m. %H:%M") + ": " + str(ex)[:150]
        with open(stand_pfad, "w", encoding="utf-8") as f:
            f.write(text[:250] + "\n")
    elif a[0] == "logos":
        befehl_logos(los="--los" in a)
    elif a[0] == "kategorien":
        befehl_kategorien(los="--los" in a)
    elif a[0] == "qualitaet":
        befehl_qualitaet(los="--los" in a)
    elif a[0] == "filmreihen":
        befehl_filmreihen(los="--los" in a)
    elif a[0] == "reihen":
        befehl_reihen(los="--los" in a)
    elif a[0] == "luecken":
        befehl_luecken(los="--los" in a)
    elif a[0] == "auto":
        # für den Sensor: kurze Zeile, Fehler als „fehler: …“
        try:
            o = befehl_trailer(still=True, max_neu=60)
            d, w = befehl_kategorien(los=True, still=True)
            print("ok {} ohne Trailer, Kategorien +{} -{}".format(o, d, w))
        except SystemExit as ex:
            print("fehler: " + str(ex)[:100])
        except Exception as ex:
            print("fehler: " + str(ex)[:100])
