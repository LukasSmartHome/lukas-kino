#!/usr/bin/env python3
# Kino-Pflege (Claude, Konto B, 03.11.2026) – Trailer-Liste und Kategorien für Lukas Kino (TCL und LG).
#
# Läuft auf dem Pi: im File editor (zum Testen, Pfade /homeassistant/...) oder in Home Assistant über
# den command_line-Sensor sensor.kino_pflege (Paket packages/kino_pflege.yaml, Pfade /config/...).
# Jellyfin-Schlüssel nur aus <config>/jellyfin_schluessel.txt (wird nie ausgegeben).
#
# Befehle:
#   daten [ziel.json]        alle Filme mit Genres, Sammlungen, Trailer-Links (zum Prüfen)
#   trailer [--alle]         /share/kino/trailer.json neu: je Film der beste YouTube-Trailer
#                            (eigene Liste > KinoCheck deutsch > Jellyfin deutsch > KinoCheck englisch > Jellyfin),
#                            jeder Kandidat wird per YouTube-oEmbed geprüft (gibt es ihn, darf man ihn einbetten?).
#                            Ohne --alle nur Filme, die noch nicht in der Liste stehen oder ohne Trailer älter als 7 Tage.
#   kategorien [--los]       Sammlungen in Jellyfin nach kategorien.json (Genres plus Ausnahmen je Film) abgleichen.
#                            Ohne --los nur anzeigen, was sich ändern würde.
#   auto                     trailer und kategorien --los nacheinander (für den Sensor, kurze Ausgabe)
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
    felder = "Genres,Tags,ProviderIds,RemoteTrailers,ProductionYear,OriginalTitle,Overview,Studios"
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
    return l


def rang(k, i):
    # eigene Wahl zuerst, dann deutscher Ton ohne Untertitel, dann Original ohne Untertitel, ganz hinten OmU/Untertitel
    if k.get("fest"):
        return (0, i)
    unter = 1 if UNTERTITEL.search(k.get("t") or "") else 0
    return (1 + unter * 2 + (0 if k.get("spr") == "de" else 1), i)


def befehl_trailer(alle=False, still=False):
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
        if a and not alle and not fest and (a.get("yt") or jetzt - a.get("z", 0) < 7 * 86400):
            neu[e["Id"]] = a
            continue
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


if __name__ == "__main__":
    a = sys.argv[1:]
    if not a:
        print(__doc__ or "Befehle: daten, trailer, kategorien, auto")
    elif a[0] == "daten":
        befehl_daten(a[1] if len(a) > 1 else os.path.join(WERK, "film_daten.json"))
    elif a[0] == "trailer":
        befehl_trailer(alle="--alle" in a)
