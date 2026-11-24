# Claude B 24.11.2026: station_neu.py (Pi) - die bessere Fassung bleibt in Jellyfin, die schlechtere kommt raus.
#   Bisher: eigener Rip ersetzt immer die Fassung aus anderen Quellen; DVD-Rip gegen HD-Fassung -> beide blieben.
#   Neu (Wunsch Lukas 24.11.): ist der neue Rip deutlich schlechter (Aufloesung, z. B. DVD-Rip 576 Zeilen gegen
#   Blu-ray/HD 720/1080), bleibt die vorhandene Fassung und der NEUE Rip kommt aus Jellyfin - nur umbenannt auf
#   derselben Platte nach "Lukas Filme nochmal drüber schauen/9 Schlechter als vorhandene Fassung", nie geloescht.
#   Gleich gut oder besser -> wie bisher: eigener Rip gewinnt.
import py_compile, sys

P = sys.argv[1] if len(sys.argv) > 1 else "/homeassistant/kino_werkzeuge/station_neu.py"
z = open(P, encoding="utf-8").read().split("\n")
fehler = []


def finde(text, ab=0, start=False):
    for i in range(ab, len(z)):
        s = z[i].strip()
        if (s.startswith(text) if start else s == text):
            return i
    fehler.append("fehlt: " + text[:60])
    return -1


def einzug(i):
    return z[i][: len(z[i]) - len(z[i].lstrip())]


# 1. Kommentar + Ablage fuer schlechtere neue Rips
i = finde("# Ein DVD-Rip (unter 700 Zeilen) ersetzt keine HD-Fassung (ab 1080p) - dann bleiben beide, Eintrag im Tausch-Log.")
if i >= 0:
    z[i] = ("# Claude B 24.11.2026: Ist der NEUE Rip deutlich schlechter (Aufloesung mehr als 15 Prozent kleiner, z. B. DVD gegen HD),"
            " bleibt die vorhandene Fassung und der neue Rip kommt aus Jellyfin (umbenannt nach ABLAGE_SCHLECHTER, nie geloescht).")
i = finde("TAUSCH_LOG = os.path.join(WERK, \"station_tausch_log.txt\")")
if i >= 0:
    z[i:i] = ['ABLAGE_SCHLECHTER = [("/media/Filme4/", "/media/Filme4/Lukas Filme nochmal drüber schauen/9 Schlechter als vorhandene Fassung"),',
              '                     ("/media/Filme2/", "/media/Filme2/Schlechter als vorhandene Fassung")]  # Claude B 24.11.2026',
              "",
              "",
              "def bildhoehe(e):",
              '    """Wirksame Bildhoehe: 1920x800 (Cinemascope) zaehlt wie 1080, 720x576 wie 576."""',
              '    return max(e.get("Height") or 0, (e.get("Width") or 0) * 9 / 16)',
              ""]

# 2. Qualitaetsvergleich in tausch_kandidaten
a = finde('neu_sd = 0 < (neu.get("Height") or 0) < 700 and (neu.get("Width") or 0) < 1100')
if a >= 0:
    e = einzug(a)
    b = finde("aus.append((e, None))", a)
    if b >= 0:
        z[a:b + 1] = [
            f"{e}hn, ha = bildhoehe(neu), bildhoehe(e)",
            f"{e}if not rn or not ra:",
            f'{e}    aus.append((e, "Laufzeit unbekannt"))',
            f"{e}elif abs(rn - ra) > max(6.0, 0.06 * rn):",
            f'{e}    aus.append((e, "andere Fassung? Laufzeit {{:.0f}} statt {{:.0f}} min".format(ra, rn)))',
            f"{e}elif hn and ha and ha > hn * 1.15:  # Claude B 24.11.2026: neuer Rip schlechter -> der neue kommt raus",
            f'{e}    aus.append((e, "NEU_SCHLECHTER: neuer Rip {{}}x{{}}, vorhanden {{}}x{{}}".format(neu.get("Width"), neu.get("Height"),',
            f'{e}                                                                       e.get("Width"), e.get("Height"))))',
            f"{e}else:",
            f"{e}    aus.append((e, None))",
        ]

# 3. beiseite_legen mit waehlbarer Ablage
i = finde("def beiseite_legen(pfad):")
if i >= 0:
    z[i] = "def beiseite_legen(pfad, ablage=None):"
    j = finde("ziel = next((z for v, z in ABLAGE_ALT if pfad.startswith(v)), None)", i)
    if j >= 0:
        z[j] = z[j].replace("in ABLAGE_ALT if", "in (ablage or ABLAGE_ALT) if")

# 4. tauschen: schlechteren neuen Rip beiseitelegen
i = finde("for alt, grund in tausch_kandidaten(filme, neu):")
if i >= 0:
    j = finde("if grund:", i)
    if j >= 0:
        e = einzug(j)
        z[j:j] = [
            f'{e}if grund and grund.startswith("NEU_SCHLECHTER"):  # Claude B 24.11.2026',
            f"{e}    if trocken:",
            f'{e}        tausch_log("Test (nichts geaendert): {{}} - wuerde den NEUEN Rip beiseitelegen, {{}} bleibt".format(grund, alt["Path"]))',
            f"{e}        break",
            f"{e}    try:",
            f"{e}        abgelegt = beiseite_legen(p, ABLAGE_SCHLECHTER)",
            f"{e}    except Exception as f:",
            f'{e}        tausch_log("Neuer Rip schlechter, aber Umbenennen ging nicht (beide bleiben): {{}} - {{}}".format(name, f))',
            f"{e}        break",
            f'{e}    k.anfrage("/Library/Media/Updated", key, "POST", {{"Updates": [{{"Path": p, "UpdateType": "Deleted"}}]}})',
            f"{e}    try:",
            f"{e}        os.rmdir(os.path.dirname(p))  # nur wenn der Ordner jetzt leer ist",
            f"{e}    except OSError:",
            f"{e}        pass",
            f'{e}    tausch_log("Neuer Rip schlechter als vorhandene Fassung ({{}}): {{}} bleibt in Jellyfin ({{}}), Rip liegt jetzt in {{}}".format(',
            f'{e}        grund.split(": ", 1)[1], name, alt["Path"], abgelegt[0]))',
            f"{e}    getauscht += 1",
            f"{e}    break",
        ]

# 5. tausch_test mit Aufloesung des neuen Rips
i = finde("def tausch_test(imdb, minuten):")
if i >= 0:
    z[i] = "def tausch_test(imdb, minuten, breite=0, hoehe=0):"
    j = finde('neu = {"Id": "-", "ProviderIds": {"Imdb": imdb}, "RunTimeTicks": int(float(minuten) * 6e8)}', i)
    if j >= 0:
        z[j] = z[j].replace('"RunTimeTicks": int(float(minuten) * 6e8)}',
                            '"RunTimeTicks": int(float(minuten) * 6e8), "Width": int(breite), "Height": int(hoehe)}')
i = finde("tausch_test(sys.argv[2], sys.argv[3])")
if i >= 0:
    z[i] = z[i].replace("tausch_test(sys.argv[2], sys.argv[3])", "tausch_test(*sys.argv[2:6])")

if fehler:
    print("ABBRUCH:", fehler)
    sys.exit(1)
open(P + ".neu", "w", encoding="utf-8").write("\n".join(z))
py_compile.compile(P + ".neu", doraise=True)
print("ok", P + ".neu", len(z))
