# Claude B 23.11.2026: Auftraege - abgebrochene ARM-Jobs MIT Posten (Daten-Discs) zeigen den Stand des Postens
# (vorher stand Wall-E als "abgebrochen" im Verlauf, obwohl der Inhalt gesichert und einsortiert war). Version 1.1.0.
import py_compile, sys
p = "/opt/station/station.py"
z = open(p, encoding="utf-8").read().split("\n")
n = 0
for i, zl in enumerate(z):
    if zl.strip() == 'elif j["status"] == "fail":':
        z[i] = zl.replace('elif j["status"] == "fail":', 'elif j["status"] == "fail" and not p:  # Claude B 23.11.2026: Daten-Disc mit Posten -> Posten-Stand')
        n += 1
    if zl.strip() == 'VERSION = "1.0.0"':
        z[i] = 'VERSION = "1.1.0"  # Claude B 23.11.2026: live und geduldig (siehe station.py.vor_live_2311b)'
        n += 1
if n != 2:
    print("ABBRUCH", n); sys.exit(1)
open(p + ".neu", "w", encoding="utf-8").write("\n".join(z))
py_compile.compile(p + ".neu", doraise=True)
print("ok")
