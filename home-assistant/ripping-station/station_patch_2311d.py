# Claude B 23.11.2026 abends: Station 1.2.0 - Erkennung 3 einbauen
#  - einsammeln: findet waehle() nichts Sicheres, schaut die Station selbst ins Bild (erkennung3.py: Texterkennung
#    von Vor- und Abspann, Suche nur ueber Filme, Laufzeit, Jahr) - klarer Sieger wird genommen
#  - sonst: Vorschlaege + Standbild am Posten (Spalten vorschlaege, bild) -> status.json "frage" -> HA fragt per Push
#  - /bild/NNNNN.jpg liefert das Standbild (fuer die Push-Nachricht und die Dashboard-Karte)
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


# 1. Import
i = finde("import subprocess, threading, time, traceback, urllib.parse, urllib.request")
if i < 0:
    fehler.append("Import-Zeile fehlt")
else:
    z[i + 1:i + 1] = [
        "try:  # Claude B 23.11.2026: Erkennung 3 (Texterkennung im Bild, /opt/station/erkennung3.py)",
        "    import erkennung3",
        "except Exception as _e3:",
        "    erkennung3 = None",
        '    print(f"erkennung3 nicht geladen: {_e3}")',
    ]

# 2. Spalten
i = finde('for s, typ in (("imdb", "text"), ("benannt", "integer default 0"), ("pi_ziel", "text"),')
if i < 0:
    fehler.append("Spalten-Zeile fehlt")
else:
    z[i] = z[i].replace('(("imdb", "text"), ', '(("imdb", "text"), ("vorschlaege", "text"), ("bild", "text"), ')

# 3. einsammeln: Erkennung 3
ei = def_zeile("einsammeln")
i = finde('info, quelle, text = waehle(k, ist, job["disctype"] == "dvd")', ei, ei + 80)
if i < 0:
    fehler.append("waehle-Zeile in einsammeln fehlt")
else:
    e = einzug(i)
    z[i + 1:i + 1] = [
        f"{e}vorschlaege, bild = None, None",
        f"{e}if not info and erkennung3:  # Claude B 23.11.2026: selbst ins Bild schauen (Vor-/Abspann lesen)",
        f"{e}    try:",
        f"{e}        os.makedirs(erkennung3.BILDER_ORDNER, exist_ok=True)",
        f"{e}        bild = os.path.join(erkennung3.BILDER_ORDNER, f\"job{{job['job_id']:05d}}.jpg\")",
        f"{e}        i3, q3, t3, vorschlaege = erkennung3.erkennen_mit_bild(__import__(\"sys\").modules[__name__], job,",
        f"{e}                                                              haupt, ist, k, bild)",
        f"{e}        if i3:",
        f"{e}            info, quelle, text = i3, q3, t3",
        f"{e}        else:",
        f"{e}            text = f\"{{text}} | Bild: {{t3}}\"",
        f"{e}    except Exception as e3:",
        f"{e}        log(f\"Erkennung 3: {{e3}}\")",
        f"{e}        log(traceback.format_exc())",
    ]
ei = def_zeile("einsammeln")
i = finde('c.execute("update posten set imdb=? where id=?", (imdb, pid))', ei, ei + 120)
if i < 0:
    fehler.append("imdb-Update in einsammeln fehlt")
else:
    e = einzug(i - 1)  # Einzug von "with sdb() as c:"
    z[i + 1:i + 1] = [
        f"{e}if vorschlaege is not None and notiz:  # Claude B 23.11.2026: fuer die Frage an Lukas (Push mit Standbild)",
        f"{e}    ziel_bild = None",
        f"{e}    if bild and os.path.exists(bild):",
        f"{e}        ziel_bild = os.path.join(erkennung3.BILDER_ORDNER, f\"{{pid:05d}}.jpg\")",
        f"{e}        os.replace(bild, ziel_bild)",
        f"{e}    with sdb() as c:",
        f"{e}        c.execute(\"update posten set vorschlaege=?, bild=? where id=?\",",
        f"{e}                  (json.dumps(vorschlaege, ensure_ascii=False), ziel_bild, pid))",
        f"{e}elif bild and os.path.exists(bild):",
        f"{e}    os.remove(bild)",
    ]

# 4. frage_an_lukas + status_dict
sd = def_zeile("status_dict")
if sd >= 0:
    z[sd:sd] = [
        "def frage_an_lukas():",
        '    """Claude B 23.11.2026: aeltester nicht erkannter Film mit Vorschlaegen -> Home Assistant fragt per Push."""',
        "    try:",
        "        with sdb() as c:",
        "            p = c.execute(\"select * from posten where zustand in ('pruefen','pruefen_pi') and coalesce(benannt,0)=0\"",
        "                          \" and vorschlaege is not null order by id limit 1\").fetchone()",
        "    except sqlite3.Error:",
        "        return None",
        "    if not p:",
        "        return None",
        "    try:",
        '        v = json.loads(p["vorschlaege"] or "[]")',
        "    except ValueError:",
        "        v = []",
        '    return {"nr": p["id"], "disc": p["label"], "titel": p["titel"], "vorschlaege": v[:3], "seit": p["erstellt"],',
        '            "bild": f"/bild/{p[\'id\']:05d}.jpg" if p["bild"] and os.path.exists(p["bild"]) else None}',
        "",
        "",
    ]
sd = def_zeile("status_dict")
i = finde("_zustand_ableiten(d)", sd, sd + 40, start=True)
if i < 0:
    fehler.append("_zustand_ableiten-Aufruf fehlt")
else:
    z[i:i] = [f'{einzug(i)}d["frage"] = frage_an_lukas()  # Claude B 23.11.2026']

# 5. /bild/NNNNN.jpg
i = finde('if self.path.split("?")[0] not in ("/status.json", "/"):')
if i < 0:
    fehler.append("do_GET-Pruefung fehlt")
else:
    e = einzug(i)
    z[i:i] = [
        f'{e}m = re.fullmatch(r"/bild/(\\d{{5}})\\.jpg", self.path.split("?")[0])  # Claude B 23.11.2026: Standbild',
        f"{e}if m:",
        f"{e}    try:",
        f'{e}        daten = open(os.path.join("/var/lib/station/bilder", m.group(1) + ".jpg"), "rb").read()',
        f"{e}    except OSError:",
        f"{e}        self.send_error(404)",
        f"{e}        return",
        f"{e}    self.send_response(200)",
        f'{e}    self.send_header("Content-Type", "image/jpeg")',
        f'{e}    self.send_header("Content-Length", str(len(daten)))',
        f'{e}    self.send_header("Access-Control-Allow-Origin", "*")',
        f'{e}    self.send_header("Cache-Control", "max-age=3600")',
        f"{e}    self.end_headers()",
        f"{e}    self.wfile.write(daten)",
        f"{e}    return",
    ]

# 6. Version
i = finde('VERSION = "1.1.0"', start=True)
if i < 0:
    fehler.append("VERSION 1.1.0 fehlt")
else:
    z[i] = 'VERSION = "1.2.0"  # Claude B 23.11.2026: Erkennung 3 (Texterkennung) und Frage per Push'

if fehler:
    print("ABBRUCH:", fehler)
    sys.exit(1)
open(P + ".neu", "w", encoding="utf-8").write("\n".join(z))
py_compile.compile(P + ".neu", doraise=True)
print("ok", P + ".neu", len(z))
