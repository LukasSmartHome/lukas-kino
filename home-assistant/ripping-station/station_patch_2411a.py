# Claude B 24.11.2026: Station 1.3.0
#  - status.json: ARM-Platzhalter "not identified"/"None" nie als Titel zeigen (Dashboard zeigte "verarbeitet not identified")
#  - laufwerk_haengt_s: seit wie vielen Sekunden das Laufwerk nicht antwortet (beim Einlesen einer DVD normal,
#    das Dashboard zeigt erst nach 5 min rot)
import py_compile, sys

P = "/opt/station/station.py"
z = open(P, encoding="utf-8").read().split("\n")
fehler = []


def finde(text, ab=0, bis=None, start=False):
    bis = len(z) if bis is None else bis
    for i in range(ab, bis):
        s = z[i].strip()
        if (s.startswith(text) if start else s == text):
            return i
    return -1


def einzug(i):
    return z[i][: len(z[i]) - len(z[i].lstrip())]


def def_zeile(name):
    for i, zl in enumerate(z):
        if zl.startswith(f"def {name}("):
            return i
    fehler.append(f"def {name} fehlt")
    return -1


sd = def_zeile("status_dict")
if sd >= 0:
    z[sd:sd] = [
        'PLATZHALTER = re.compile(r"^\\s*(not[ _]identified|none|unknown|unbekannt)?\\s*(\\((none|\\d{4})?\\))?\\s*$", re.I)  # Claude B 24.11.2026',
        "",
        "",
        "def _titel_sauber(t):",
        "    return None if t is None or PLATZHALTER.match(str(t)) else t",
        "",
        "",
    ]
sd = def_zeile("status_dict")
i = finde('d["frage"] = frage_an_lukas()', sd, sd + 40, start=True)
if i < 0:
    fehler.append("frage-Zeile in status_dict fehlt")
else:
    e = einzug(i)
    z[i:i] = [
        f'{e}for _e in d.get("auftraege") or []:  # Claude B 24.11.2026: ARM-Platzhalter raus',
        f'{e}    _e["titel"] = _titel_sauber(_e.get("titel"))',
        f'{e}    if _titel_sauber(_e.get("disc_name")) is None:',
        f'{e}        _e["disc_name"] = None',
        f'{e}d["laufwerk_haengt_s"] = int(time.time() - _laufwerk_haengt_seit[0]) if _laufwerk_haengt_seit[0] else 0',
    ]
    r = finde("return d", i, i + 20)
    if r < 0:
        fehler.append("return d fehlt")
    else:
        z[r:r] = [f'{einzug(r)}d["titel"] = _titel_sauber(d.get("titel"))  # Claude B 24.11.2026']

i = finde('VERSION = "1.2.0"', start=True)
if i < 0:
    fehler.append("VERSION 1.2.0 fehlt")
else:
    z[i] = 'VERSION = "1.3.0"  # Claude B 24.11.2026: Erkennung 3 mit Leuten im Abspann, saubere Titel, Laufwerk-Wartezeit'

if fehler:
    print("ABBRUCH:", fehler)
    sys.exit(1)
open(P + ".neu", "w", encoding="utf-8").write("\n".join(z))
py_compile.compile(P + ".neu", doraise=True)
print("ok", P + ".neu", len(z))
