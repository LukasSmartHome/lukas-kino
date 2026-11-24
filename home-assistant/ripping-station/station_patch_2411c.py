# Claude B 24.11.2026: Station 1.4.0 - vor dem Bild erst den Disc-Namen in allen Varianten pruefen
# (erkennung3.erkennen_ohne_bild: Abkuerzungen, Umlaute, Untertitel, nur Filme, Laufzeit). Schnell, ohne Standbilder.
import py_compile, sys

P = "/opt/station/station.py"
z = open(P, encoding="utf-8").read().split("\n")
fehler = []
i = next((n for n, zl in enumerate(z) if zl.strip().startswith("if not info and erkennung3:  # Claude B 23.11.2026")), -1)
if i < 0:
    fehler.append("Bild-Stufe in einsammeln fehlt")
else:
    e = z[i][: len(z[i]) - len(z[i].lstrip())]
    z[i:i] = [
        f"{e}if not info and erkennung3:  # Claude B 24.11.2026: erst der Disc-Name in allen Varianten (schnell)",
        f"{e}    try:",
        f"{e}        i2, q2, t2, _v2 = erkennung3.erkennen_ohne_bild(__import__(\"sys\").modules[__name__], job, ist, k)",
        f"{e}        if i2:",
        f"{e}            info, quelle, text = i2, q2, t2",
        f"{e}    except Exception as e2:",
        f"{e}        log(f\"Erkennung Name: {{e2}}\")",
    ]
j = next((n for n, zl in enumerate(z) if zl.startswith('VERSION = "1.3.0"')), -1)
if j < 0:
    fehler.append("VERSION 1.3.0 fehlt")
else:
    z[j] = 'VERSION = "1.4.0"  # Claude B 24.11.2026: Name-Stufe, Netz-Zwischenspeicher, Plakat in der Frage'
if fehler:
    print("ABBRUCH:", fehler); sys.exit(1)
open(P + ".neu", "w", encoding="utf-8").write("\n".join(z))
py_compile.compile(P + ".neu", doraise=True)
print("ok 2411c", len(z))
