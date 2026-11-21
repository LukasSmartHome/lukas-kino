#!/usr/bin/env python3
# SSD-Filme (/media/Filme) auf Filme4 umziehen – Claude, Konto B, 21.11.2026 (Wunsch Lukas: SSD frei machen, Bilder bleiben).
#
# GRUNDREGELN: Es wird nie etwas überschrieben. Jede Datei wird kopiert (.teil), mit SHA-256 beim Lesen der Quelle und
# nach dem Schreiben noch einmal von der Platte gelesen und verglichen. Erst dann bekommt sie ihren Namen.
# Gelöscht wird auf der SSD nur mit „loeschen --los“, und nur Dateien, deren Kopie geprüft ist und noch genauso groß da liegt.
# Jeder Schritt steht in ssd_umzug/protokoll.jsonl.
#
#   plan                  Plan aus Jellyfin + Dateien bauen (nur lesen) -> ssd_umzug/plan.json, Übersicht
#   kopieren [--los] [--mbs N]   kopieren und prüfen (Hintergrund), Stand in ssd_umzug/stand.txt
#   daten [--los]         Gesehen/Position/Favorit/Bewertung aller Nutzer vom alten auf den neuen Jellyfin-Eintrag
#   loeschen [--los]      geprüfte Quellen auf der SSD löschen, leere Ordner weg
import hashlib, json, os, re, sys, time, urllib.request

QUELLE = "/media/Filme"
ZIEL = "/media/Filme4/Lukas Filme geordnet"
DOPPELT = "/media/Filme4/Kino/_Pruefen/Doppelt/SSD"
BEISEITE = "/media/Filme4/Kino/_Pruefen/Beiseite/SSD"
WERK = "/homeassistant/kino_werkzeuge/ssd_umzug"
PLAN = os.path.join(WERK, "plan.json")
PROT = os.path.join(WERK, "protokoll.jsonl")
STAND = os.path.join(WERK, "stand.txt")
VIDEO = (".mkv", ".mp4", ".avi", ".ts", ".m4v", ".mov", ".wmv", ".mpg", ".m2ts")

S = "http://192.168.178.44:8096"
K = open(os.environ.get("JF_KEY", "/homeassistant/jellyfin_schluessel.txt")).read().strip()
H = {"Authorization": 'MediaBrowser Client="Claude", Device="Pi", DeviceId="claude-b-qc", Version="1.0", Token="{}"'.format(K),
     "Content-Type": "application/json"}


def req(p, d=None, m=None):
    r = urllib.request.Request(S + p, headers=H, data=None if d is None else json.dumps(d).encode(),
                               method=m or ("GET" if d is None else "POST"))
    x = urllib.request.urlopen(r, timeout=120).read()
    return json.loads(x) if x else None


def prot(**z):
    z["zeit"] = time.strftime("%Y-%m-%d %H:%M:%S")
    with open(PROT, "a", encoding="utf-8") as f:
        f.write(json.dumps(z, ensure_ascii=False) + "\n")


def stand(t):
    with open(STAND, "w", encoding="utf-8") as f:
        f.write(time.strftime("%d.%m. %H:%M ") + t + "\n")


def admin():
    return [x for x in req("/Users") if x.get("Policy", {}).get("IsAdministrator")][0]["Id"]


def filme(u):
    f = "Path,ProviderIds,MediaSources,ProductionYear"
    return req("/Users/{}/Items?Recursive=true&IncludeItemTypes=Movie&Fields={}".format(u, f)).get("Items") or []


def info(e):
    ms = (e.get("MediaSources") or [{}])[0]
    st = ms.get("MediaStreams") or []
    v = [s for s in st if s.get("Type") == "Video"]
    de = any((s.get("Language") or "").lower() in ("ger", "deu", "de") for s in st if s.get("Type") == "Audio")
    return {"hoehe": (v[0].get("Height") or 0) if v else 0, "breite": (v[0].get("Width") or 0) if v else 0,
            "deutsch": de, "groesse": ms.get("Size") or 0}


def sauber(t):
    """Dateiname wie beim filmsortierer: Doppelpunkt weg, verbotene Zeichen weg, keine Punkte/Leerzeichen am Ende."""
    t = t.replace(":", "").replace("/", " ").replace("\\", " ")
    t = re.sub(r'[?*"<>|]', "", t)
    t = re.sub(r"\s+", " ", t).strip().rstrip(".")
    return t


def ist_dvd(i):
    return 0 < i["hoehe"] <= 576 and i["breite"] <= 1024


def besser(a, b):
    """True, wenn Fassung a klar besser ist als b (höhere Auflösung, oder gleiche und nur a mit deutschem Ton)."""
    if a["hoehe"] > b["hoehe"] + 40:
        return True
    if abs(a["hoehe"] - b["hoehe"]) <= 40 and a["deutsch"] and not b["deutsch"]:
        return True
    return False


def befehl_plan():
    os.makedirs(WERK, exist_ok=True)
    u = admin()
    alle = filme(u)
    nach_pfad = {e.get("Path"): e for e in alle}
    f4 = {}
    for e in alle:
        p = e.get("Path") or ""
        t = (e.get("ProviderIds") or {}).get("Tmdb")
        if p.startswith(ZIEL + "/") and t:
            f4.setdefault(t, []).append(e)
    # Filmreihen: Jellyfin-Sammlungen mit TMDB-ID (keine Kategorien)
    reihe_von = {}
    for b in req("/Users/{}/Items?Recursive=true&IncludeItemTypes=BoxSet&Fields=ProviderIds".format(u)).get("Items") or []:
        if not (b.get("ProviderIds") or {}).get("Tmdb"):
            continue
        kinder = req("/Users/{}/Items?ParentId={}&Fields=Path".format(u, b["Id"])).get("Items") or []
        ordner = {os.path.dirname(k.get("Path") or "") for k in kinder if (k.get("Path") or "").startswith(ZIEL + "/")}
        ordner = {o for o in ordner if o != ZIEL}
        for k in kinder:
            if len(ordner) == 1:
                reihe_von[k["Id"]] = list(ordner)[0]
    plan, belegt = [], set()
    for wurzel, _, dateien in os.walk(QUELLE):
        for d in sorted(dateien):
            q = os.path.join(wurzel, d)
            groesse = os.path.getsize(q)
            e = nach_pfad.get(q)
            if not d.lower().endswith(VIDEO) or e is None:
                plan.append({"art": "beiseite", "quelle": q, "ziel": os.path.join(BEISEITE, os.path.relpath(q, QUELLE)),
                             "groesse": groesse, "grund": "kein Film in Jellyfin"})
                continue
            i = info(e)
            tmdb = (e.get("ProviderIds") or {}).get("Tmdb")
            ext = os.path.splitext(d)[1].lower()
            eintrag = {"quelle": q, "groesse": groesse, "alt_id": e["Id"], "name": e.get("Name"),
                       "jahr": e.get("ProductionYear"), "tmdb": tmdb, "q": i}
            andere = f4.get(tmdb) if tmdb else None
            if andere:
                o = andere[0]
                oi = info(o)
                eintrag["filme4"] = {"pfad": o.get("Path"), "id": o["Id"], "q": oi}
                if besser(i, oi):
                    stamm = os.path.splitext(os.path.basename(o["Path"]))[0].replace(" [DVD]", "")
                    if ist_dvd(i):
                        stamm += " [DVD]"
                    eintrag.update(art="ersetzen", ziel=os.path.join(os.path.dirname(o["Path"]), stamm + ext),
                                   weg=o["Path"], weg_ziel=os.path.join(DOPPELT, "von Filme4", os.path.basename(o["Path"])))
                elif os.path.exists(o["Path"]) and os.path.getsize(o["Path"]) == groesse:
                    # gleich groß wie die Fassung auf Filme4: vermutlich dieselbe Datei. Beim Kopieren wird das per
                    # SHA-256 beider Dateien bestätigt – dann keine zweite Kopie nötig (sonst doch nach Doppelt).
                    eintrag.update(art="gleich", ziel=o["Path"], ersatz_ziel=os.path.join(DOPPELT, d))
                else:
                    eintrag.update(art="doppelt", ziel=os.path.join(DOPPELT, d))
            else:
                titel = sauber(e.get("Name") or os.path.splitext(d)[0])
                jahr = e.get("ProductionYear")
                stamm = titel + (" ({})".format(jahr) if jahr else "")
                ordner = reihe_von.get(e["Id"], ZIEL)
                if ordner != ZIEL:
                    rn = os.path.basename(ordner)
                    if stamm.startswith(rn + " - "):
                        stamm = rn + stamm[len(rn) + 2:]
                if ist_dvd(i):
                    stamm += " [DVD]"
                eintrag.update(art="neu", ziel=os.path.join(ordner, stamm + ext))
            plan.append(eintrag)
    # Kollisionen: nie auf etwas Vorhandenes oder doppelt geplantes
    for e in plan:
        z = e["ziel"]
        if e["art"] == "gleich":
            e["status"] = "offen"
            continue
        if os.path.exists(z) or z in belegt:
            s, x = os.path.splitext(z)
            z = "{} [tmdbid-{}]{}".format(s, e.get("tmdb") or "x", x) if e.get("tmdb") else "{} (SSD){}".format(s, x)
            n = 2
            while os.path.exists(z) or z in belegt:
                z = "{} ({}){}".format(s, n, x)
                n += 1
            e["ziel"] = z
        belegt.add(z)
        e["status"] = "offen"
    json.dump(plan, open(PLAN, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    zahl = {}
    for e in plan:
        zahl[e["art"]] = zahl.get(e["art"], 0) + 1
    gb = sum(e["groesse"] for e in plan) / 1e9
    print("Dateien:", len(plan), "| GB:", round(gb, 1), "|", zahl)
    for e in plan:
        if e["art"] in ("ersetzen", "doppelt", "gleich"):
            print(" ", e["art"], "|", e["name"], e["q"]["hoehe"], "p de" if e["q"]["deutsch"] else "p", "<->",
                  e["filme4"]["q"]["hoehe"], "p de" if e["filme4"]["q"]["deutsch"] else "p", "|", os.path.basename(e["ziel"]))
    print("neu, Beispiele:")
    for e in [x for x in plan if x["art"] == "neu"][:400]:
        print("  ", os.path.relpath(e["ziel"], ZIEL))
    for e in plan:
        if e["art"] == "beiseite":
            print("  beiseite:", e["quelle"])


def pruefsumme(p, mbs=None):
    """SHA-256 einer Datei; mit mbs gebremst (schont die HA-SSD ohne eigenes Netzteil)."""
    h = hashlib.sha256()
    n, t0 = 0, time.time()
    with open(p, "rb") as f:
        while True:
            b = f.read(8 << 20)
            if not b:
                break
            h.update(b)
            n += len(b)
            if mbs:
                soll = n / (mbs * 1e6) - (time.time() - t0)
                if soll > 0:
                    time.sleep(soll)
        try:
            os.posix_fadvise(f.fileno(), 0, 0, os.POSIX_FADV_DONTNEED)
        except Exception:
            pass
    return h.hexdigest()


def kopie(q, z, mbs):
    """q -> z.teil -> prüfen -> z. Gibt SHA-256 zurück, wirft bei jedem Fehler (Teil-Datei bleibt dann liegen)."""
    if os.path.exists(z):
        raise RuntimeError("Ziel gibt es schon: " + z)
    os.makedirs(os.path.dirname(z), exist_ok=True)
    teil = z + ".teil"
    if os.path.exists(teil):
        os.remove(teil)  # eigene, unfertige Teil-Datei von einem abgebrochenen Lauf
    h = hashlib.sha256()
    n, t0 = 0, time.time()
    with open(q, "rb") as fq, open(teil, "wb") as fz:
        while True:
            b = fq.read(8 << 20)
            if not b:
                break
            h.update(b)
            fz.write(b)
            n += len(b)
            soll = n / (mbs * 1e6)
            jetzt = time.time() - t0
            if soll > jetzt:
                time.sleep(soll - jetzt)
        fz.flush()
        os.fsync(fz.fileno())
        try:
            os.posix_fadvise(fq.fileno(), 0, 0, os.POSIX_FADV_DONTNEED)
            os.posix_fadvise(fz.fileno(), 0, 0, os.POSIX_FADV_DONTNEED)
        except Exception:
            pass
    if os.path.getsize(teil) != os.path.getsize(q):
        raise RuntimeError("Größe weicht ab")
    h2 = hashlib.sha256()
    with open(teil, "rb") as fz:
        while True:
            b = fz.read(8 << 20)
            if not b:
                break
            h2.update(b)
        try:
            os.posix_fadvise(fz.fileno(), 0, 0, os.POSIX_FADV_DONTNEED)
        except Exception:
            pass
    if h.hexdigest() != h2.hexdigest():
        raise RuntimeError("Prüfsumme weicht ab")
    if os.path.exists(z):
        raise RuntimeError("Ziel gibt es plötzlich: " + z)
    os.rename(teil, z)
    st = os.stat(q)
    try:
        os.utime(z, (st.st_atime, st.st_mtime))
    except Exception:
        pass
    return h.hexdigest()


def befehl_kopieren(los, mbs):
    plan = json.load(open(PLAN, encoding="utf-8"))
    offen = [e for e in plan if e["status"] == "offen"]
    gb = sum(e["groesse"] for e in offen) / 1e9
    print("offen:", len(offen), "Dateien,", round(gb, 1), "GB, Tempo höchstens", mbs, "MB/s")
    if not los:
        return
    fertig_gb = 0.0
    for nr, e in enumerate(plan):
        if e["status"] != "offen":
            continue
        stand("kopiere {}/{} ({:.0f} von {:.0f} GB): {}".format(nr + 1, len(plan), fertig_gb, gb, os.path.basename(e["quelle"])))
        try:
            if not os.path.exists(e["quelle"]):
                raise RuntimeError("Quelle fehlt")
            if e["art"] == "ersetzen":
                if os.path.exists(e["weg"]):
                    if os.path.exists(e["weg_ziel"]):
                        raise RuntimeError("Doppelt-Ziel gibt es schon: " + e["weg_ziel"])
                    os.makedirs(os.path.dirname(e["weg_ziel"]), exist_ok=True)
                    os.rename(e["weg"], e["weg_ziel"])
                    prot(schritt="beiseite_filme4", alt=e["weg"], neu=e["weg_ziel"])
                    stamm = os.path.splitext(e["weg"])[0]
                    ordner = os.path.dirname(e["weg"])
                    for s in os.listdir(ordner):
                        p = os.path.join(ordner, s)
                        if p.startswith(stamm + ".") and os.path.isfile(p):
                            ziel = os.path.join(os.path.dirname(e["weg_ziel"]), s)
                            if not os.path.exists(ziel):
                                os.rename(p, ziel)
                                prot(schritt="beiseite_filme4", alt=p, neu=ziel)
            if e["art"] == "gleich":
                sq, sz = pruefsumme(e["quelle"], mbs), pruefsumme(e["ziel"])
                if sq == sz:
                    e["status"], e["sha256"] = "kopiert", sq
                    prot(schritt="gleich", quelle=e["quelle"], ziel=e["ziel"], groesse=e["groesse"], sha256=sq)
                    fertig_gb += e["groesse"] / 1e9
                    json.dump(plan, open(PLAN + ".tmp", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
                    os.replace(PLAN + ".tmp", PLAN)
                    continue
                prot(schritt="nicht_gleich", quelle=e["quelle"], ziel=e["ziel"], sha_quelle=sq, sha_ziel=sz)
                e["art"], e["ziel"] = "doppelt", e["ersatz_ziel"]
            sha = kopie(e["quelle"], e["ziel"], mbs)
            e["status"], e["sha256"] = "kopiert", sha
            prot(schritt="kopiert", art=e["art"], quelle=e["quelle"], ziel=e["ziel"], groesse=e["groesse"], sha256=sha)
        except Exception as ex:
            e["status"], e["fehler"] = "fehler", str(ex)
            prot(schritt="fehler", quelle=e["quelle"], ziel=e.get("ziel"), fehler=str(ex))
        fertig_gb += e["groesse"] / 1e9
        json.dump(plan, open(PLAN + ".tmp", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        os.replace(PLAN + ".tmp", PLAN)
    f = sum(1 for e in plan if e["status"] == "fehler")
    k = sum(1 for e in plan if e["status"] == "kopiert")
    stand("kopieren fertig: {} kopiert und geprüft, {} Fehler".format(k, f))


def befehl_daten(los):
    plan = json.load(open(PLAN, encoding="utf-8"))
    nutzer = req("/Users")
    alle = filme(admin())
    nach_pfad = {e.get("Path"): e["Id"] for e in alle}
    felder = ("Played", "PlayCount", "PlaybackPositionTicks", "IsFavorite", "Rating", "LastPlayedDate")
    n = 0
    for e in plan:
        if e.get("status") not in ("kopiert", "geloescht") or not e.get("alt_id"):
            continue
        neu = e["filme4"]["id"] if e["art"] in ("doppelt", "gleich") else nach_pfad.get(e["ziel"])
        if not neu:
            print("  noch nicht in Jellyfin:", os.path.basename(e["ziel"]))
            continue
        for us in nutzer:
            try:
                alt = req("/UserItems/{}/UserData?userId={}".format(e["alt_id"], us["Id"]))
            except Exception:
                continue
            if not any(alt.get(k) for k in ("Played", "PlaybackPositionTicks", "IsFavorite", "Rating")):
                continue
            jetzt = req("/UserItems/{}/UserData?userId={}".format(neu, us["Id"]))
            d = {k: alt.get(k) for k in felder if alt.get(k) is not None}
            if jetzt.get("Played") and not alt.get("Played"):
                d.pop("PlaybackPositionTicks", None)
            print("  ", us["Name"], "|", e["name"], "|", {k: d.get(k) for k in ("Played", "IsFavorite", "Rating")})
            if los:
                req("/UserItems/{}/UserData?userId={}".format(neu, us["Id"]), d)
                prot(schritt="nutzerdaten", nutzer=us["Name"], film=e["name"], alt=e["alt_id"], neu=neu, daten=d)
            n += 1
    print("Übertragungen:", n, "" if los else "(nur Vorschau)")


def befehl_loeschen(los):
    plan = json.load(open(PLAN, encoding="utf-8"))
    n, frei = 0, 0
    for e in plan:
        if e["status"] != "kopiert":
            continue
        q, z = e["quelle"], e["ziel"]
        if not (os.path.exists(z) and os.path.getsize(z) == e["groesse"] and os.path.exists(q) and os.path.getsize(q) == e["groesse"]):
            print("  NICHT geloescht (Kopie oder Quelle anders):", q)
            continue
        if los:
            os.remove(q)
            e["status"] = "geloescht"
            prot(schritt="geloescht", quelle=q, kopie=z, sha256=e.get("sha256"))
        n += 1
        frei += e["groesse"]
    if los:
        for wurzel, ordner, dateien in os.walk(QUELLE, topdown=False):
            if wurzel != QUELLE and not os.listdir(wurzel):
                os.rmdir(wurzel)
        json.dump(plan, open(PLAN, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("geloescht" if los else "wuerde loeschen", n, "Dateien,", round(frei / 1e9, 1), "GB")


if __name__ == "__main__":
    a = sys.argv[1:]
    los = "--los" in a
    mbs = float(a[a.index("--mbs") + 1]) if "--mbs" in a else 60
    if not a or a[0] == "plan":
        befehl_plan()
    elif a[0] == "kopieren":
        befehl_kopieren(los, mbs)
    elif a[0] == "daten":
        befehl_daten(los)
    elif a[0] == "loeschen":
        befehl_loeschen(los)
