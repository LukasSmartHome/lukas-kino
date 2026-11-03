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
    user = anfrage("/Users", key)[0]["Id"]
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
    user = anfrage("/Users", key)[0]["Id"]
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
    user = anfrage("/Users", key)[0]["Id"]
    filme = filme_holen(key, user)
    samm = {s["name"]: s for s in sammlungen_holen(key, user)}
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
    user = anfrage("/Users", key)[0]["Id"]
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



# ------------------------------------------------------------------ vorwaermen

WEBP = "image/webp,image/apng,image/*,*/*;q=0.8"   # so fragt der Browser der LG-Fernseher (bekommt WebP)


def befehl_vorwaermen(teil="alles", faeden=2):
    """Alle Vorschaubilder einmal in genau den Größen anfragen, die die Fernseher benutzen. Jellyfin rechnet jedes Bild
    beim ersten Abruf aus dem Original (bei Fotos 12 Megapixel) klein und legt es in seinen Zwischenspeicher – danach
    kommt es sofort. TCL (ohne Accept, bekommt JPEG) und LG-Browser (WebP) bekommen je eine eigene Fassung.
    teil: alles, filme oder fotos."""
    import threading, concurrent.futures
    key = schluessel()
    user = anfrage("/Users", key)[0]["Id"]
    urls = []
    if teil in ("alles", "filme"):
        for e in anfrage("/Items?" + q(userId=user, includeItemTypes="Movie,Series", recursive="true",
                                         fields="ImageTags,BackdropImageTags"), key).get("Items") or []:
            i, t, b = e["Id"], e.get("ImageTags") or {}, e.get("BackdropImageTags") or []
            if t.get("Primary"):
                urls.append("/Items/{}/Images/Primary?fillHeight=330&quality=90&tag={}".format(i, t["Primary"]))
                if not b:
                    urls.append("/Items/{}/Images/Primary?fillHeight=1080&quality=90&tag={}".format(i, t["Primary"]))
            if t.get("Logo"):
                urls.append("/Items/{}/Images/Logo?fillHeight=220&quality=90&tag={}".format(i, t["Logo"]))
            if b:
                urls.append("/Items/{}/Images/Backdrop/0?fillWidth=1920&quality=85&tag={}".format(i, b[0]))
                urls.append("/Items/{}/Images/Backdrop/0?fillWidth=1280&quality=80&tag={}".format(i, b[0]))
                urls.append("/Items/{}/Images/Backdrop/0?fillWidth=940&quality=85&tag={}".format(i, b[0]))
    if teil in ("alles", "fotos"):
        fotos = None
        for v in anfrage("/UserViews?" + q(userId=user), key).get("Items") or []:
            if v.get("CollectionType") in ("photos", "homevideos") and fotos is None:
                fotos = v["Id"]
        if fotos:
            start = 0
            while True:
                seite = anfrage("/Items?" + q(userId=user, parentId=fotos, recursive="true", includeItemTypes="Photo",
                                              imageTypes="Primary", fields="ImageTags", enableUserData="false",
                                              startIndex=start, limit=2000), key).get("Items") or []
                for e in seite:
                    tg = (e.get("ImageTags") or {}).get("Primary")
                    if tg:
                        urls.append("/Items/{}/Images/Primary?fillWidth=360&quality=85&tag={}".format(e["Id"], tg))
                if len(seite) < 2000:
                    break
                start += 2000
            # Titelbilder der Ordner: wie die Apps das erste Foto suchen (erst direkt, sonst in Unterordnern)
            ordner = anfrage("/Items?" + q(userId=user, parentId=fotos, recursive="true", isFolder="true",
                                           enableImages="false", enableUserData="false"), key).get("Items") or []
            for o in ordner:
                a = anfrage("/Items?" + q(userId=user, parentId=o["Id"], includeItemTypes="Photo", imageTypes="Primary",
                                          limit=1, fields="ImageTags"), key).get("Items") or []
                if not a:
                    a = anfrage("/Items?" + q(userId=user, parentId=o["Id"], recursive="true", includeItemTypes="Photo",
                                              imageTypes="Primary", limit=1, fields="ImageTags"), key).get("Items") or []
                if a and (a[0].get("ImageTags") or {}).get("Primary"):
                    tg = a[0]["ImageTags"]["Primary"]
                    for w in (536, 704, 1280):
                        urls.append("/Items/{}/Images/Primary?fillWidth={}&quality=85&tag={}".format(a[0]["Id"], w, tg))
    gesamt = len(urls) * 2
    zaehler = {"fertig": 0, "fehler": 0, "bytes": 0}
    sperre = threading.Lock()
    beginn = time.time()

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
            zaehler["fertig"] += 1
            zaehler["bytes"] += n
            if not ok:
                zaehler["fehler"] += 1
            f = zaehler["fertig"]
            if f % 500 == 0 or f == gesamt:
                dauer = time.time() - beginn
                rest = (gesamt - f) / max(0.1, f / max(1, dauer))
                schreiben(os.path.join(WERK, "vorwaermen_stand.json"), {"fertig": f, "gesamt": gesamt, "fehler": zaehler["fehler"],
                          "mb": round(zaehler["bytes"] / 1e6), "pro_sekunde": round(f / max(1, dauer), 1),
                          "rest_minuten": round(rest / 60), "zeit": time.strftime("%H:%M")})

    auftraege = [(u, a) for u in urls for a in (None, WEBP)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=faeden) as ex:
        list(ex.map(eins, auftraege))
    print("vorgewärmt: {} Bilder ({} Abrufe), Fehler {}, {} MB, {} Minuten".format(len(urls), gesamt, zaehler["fehler"],
                                                                                 round(zaehler["bytes"] / 1e6), round((time.time() - beginn) / 60)))


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
                          int(a[a.index("--faeden") + 1]) if "--faeden" in a else 2)
    elif a[0] == "logos":
        befehl_logos(los="--los" in a)
    elif a[0] == "kategorien":
        befehl_kategorien(los="--los" in a)
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
