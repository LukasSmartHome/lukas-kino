# Claude B 24.11.2026: Daten-Discs selbst pruefen (nach dem Auswerfen, im eigenen Faden)
#   Laufzeit, Aufloesung, Ton, Hinweise im Dateinamen (mic/ts/cam), Film-Vorschlag (Name, sonst Texterkennung)
#   -> Text haengt an der Notiz des Postens (Dashboard). Verschoben wird nichts (Wunsch Lukas 23.11.).
#   Laeuft NACH station_patch_2411a.py (arbeitet auf station.py.neu).
import py_compile, sys

P = "/opt/station/station.py.neu"
z = open(P, encoding="utf-8").read().split("\n")
fehler = []

i = next((n for n, zl in enumerate(z) if zl.strip() ==
          'pid = neuer_posten(job, "film", label, "", ziel, label, zustand="pruefen", notiz=notiz)'), -1)
if i < 0:
    fehler.append("neuer_posten-Zeile in _daten_disc_lesen fehlt")
else:
    e = z[i][: len(z[i]) - len(z[i].lstrip())]
    z[i + 1:i + 1] = [
        f"{e}if videos and erkennung3:  # Claude B 24.11.2026: selbst pruefen, nach dem Auswerfen (eigener Faden)",
        f"{e}    threading.Thread(target=_daten_disc_pruefen_spaeter, args=(dict(job), pid, list(videos)), daemon=True).start()",
    ]

j = next((n for n, zl in enumerate(z) if zl.startswith("def _daten_disc_lesen(")), -1)
if j < 0:
    fehler.append("def _daten_disc_lesen fehlt")
else:
    z[j:j] = [
        "def _daten_disc_pruefen_spaeter(job, pid, videos):",
        '    """Claude B 24.11.2026: Daten-Disc pruefen und einen Film vorschlagen (erkennung3.daten_disc_pruefen)."""',
        "    time.sleep(20)  # erst auswerfen lassen",
        "    try:",
        "        with sdb() as c:",
        '            p = c.execute("select * from posten where id=?", (pid,)).fetchone()',
        '        ordner = os.path.join(p["lokal"] or os.path.join(MEDIEN, "pruefen", f"{pid:05d}"), p["rel"])',
        '        text = erkennung3.daten_disc_pruefen(__import__("sys").modules[__name__], job, ordner, videos)',
        "        with sdb() as c:",
        "            c.execute(\"update posten set fehler = coalesce(fehler, '') || '; ' || ? where id=?\", (text, pid))",
        '        meldung(f"Daten-Disc {p[\'label\']} geprueft: {text[:220]}")',
        "    except Exception as e:",
        '        log(f"Daten-Disc pruefen (Nr. {pid:05d}): {e}")',
        "        log(traceback.format_exc())",
        "",
        "",
    ]

if fehler:
    print("ABBRUCH:", fehler)
    sys.exit(1)
open(P, "w", encoding="utf-8").write("\n".join(z))
py_compile.compile(P, doraise=True)
print("ok 2411b", P, len(z))
