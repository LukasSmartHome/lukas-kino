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


if __name__ == "__main__":
    a = sys.argv[1:]
    if not a:
        print(__doc__ or "Befehle: daten, trailer, kategorien, auto")
    elif a[0] == "daten":
        befehl_daten(a[1] if len(a) > 1 else os.path.join(WERK, "film_daten.json"))
