# Claude B 23.11.2026 abends: Station-Patch "live und geduldig"
#  1. Puffer-Probe geduldig: eine einzelne langsame Schreibprobe (> 20 s) bricht keinen Rip mehr ab, erst 2 min ohne Antwort
#  2. Einsammeln (Erkennen, Untertitel umpacken - bis 15 min) laeuft in einem eigenen Faden: die Hauptschleife zeigt
#     waehrenddessen den Fortschritt der naechsten Disc weiter an (vorher fehlte der Live-Wert minutenlang)
#  3. status.json: Hauptzustand aus den Auftraegen (liest > verarbeitet > uebertraegt), Laufwerk-Zustand, "einlegen"
#     (darf die naechste Disc rein?), "nebenbei", CORS fuer die Dashboard-Karte
#  4. Klarere Meldung nach Puffer-Abbruch
import py_compile, sys

QUELLE = "/opt/station/station.py"
ZIEL = "/opt/station/station.py.neu"
z = open(QUELLE, encoding="utf-8").read().split("\n")
fehler = []


def finde(text, ab=0, bis=None):
    bis = len(z) if bis is None else bis
    for i in range(ab, bis):
        if z[i].strip() == text:
            return i
    return -1


def einzug(i):
    return z[i][: len(z[i]) - len(z[i].lstrip())]


def def_zeile(name):
    i = finde(f"def {name}():")
    if i < 0:
        fehler.append(f"def {name} fehlt")
    return i


# ---- 1. Puffer-Probe geduldig
ph = def_zeile("puffer_heilen")
i = finde("ok, stumm, text = puffer_probe()", ph, ph + 12)
if i < 0:
    fehler.append("Probe-Zeile in puffer_heilen fehlt")
else:
    e = einzug(i)
    z[i + 1:i + 1] = [
        f"{e}if ok:  # Claude B 23.11.2026",
        f"{e}    _probe_langsam[0] = None",
        f"{e}elif stumm and _probe_langsam_ok():  # langsam ist nicht kaputt - erst nach 2 min abbrechen",
        f"{e}    return",
    ]
ph = def_zeile("puffer_heilen")
i = finde("if arm_beschaeftigt():", ph, ph + 45)
if i < 0:
    fehler.append("arm_beschaeftigt in puffer_heilen fehlt")
else:
    z[i] = z[i].replace("if arm_beschaeftigt():", "if arm_beschaeftigt() or einsammeln_laeuft():  # Claude B 23.11.2026")
ra = def_zeile("raw_aufraeumen")
i = finde("if arm_beschaeftigt():", ra, ra + 4)
if i < 0:
    fehler.append("arm_beschaeftigt in raw_aufraeumen fehlt")
else:
    z[i] = z[i].replace("if arm_beschaeftigt():", "if arm_beschaeftigt() or einsammeln_laeuft():  # Claude B 23.11.2026")

pp = def_zeile("puffer_probe")
if pp >= 0:
    z[pp:pp] = [
        "_probe_langsam = [None]  # Claude B 23.11.2026",
        "",
        "",
        "def _probe_langsam_ok():",
        '    """Claude B 23.11.2026: Stolz und Vorurteil (23.11. 19:58) wurde abgebrochen, weil eine einzelne Schreibprobe',
        "    auf der USB-HDD laenger als 20 s brauchte (kein USB- oder Plattenfehler im dmesg). Langsam ist nicht kaputt:",
        '    erst wenn die Probe 2 min lang nicht fertig wird, gilt der Puffer als gestoert. True = noch abwarten."""',
        "    if not _probe_langsam[0]:",
        "        _probe_langsam[0] = time.time()",
        '        log("Puffer antwortet langsam (Schreibprobe ueber 20 s) - warte bis 2 min, bevor etwas abgebrochen wird")',
        "        return True",
        "    if time.time() - _probe_langsam[0] < 120:",
        "        return True",
        "    _probe_langsam[0] = None",
        "    return False",
        "",
        "",
    ]

# ---- 2. Einsammeln im eigenen Faden
wf = def_zeile("wache_fertig")
if wf >= 0:
    z[wf] = "def _wache_fertig():"
    z[wf:wf] = [
        "_einsammeln_faden = [None]  # Claude B 23.11.2026",
        "",
        "",
        "def einsammeln_laeuft():",
        "    f = _einsammeln_faden[0]",
        "    return bool(f and f.is_alive())",
        "",
        "",
        "def wache_fertig():",
        '    """Claude B 23.11.2026: Einsammeln (Erkennen, Untertitel umpacken - bei Blu-rays bis 15 min) lief in der',
        "    Hauptschleife und hielt sie an: Fortschritt, Haenger-Wache und Erkennung der naechsten Disc standen solange still",
        "    (Dashboard ohne Live-Wert, Daybreakers -> Dr. No 23.11.). Jetzt in einem eigenen Faden, immer nur einer.\"\"\"",
        "    if einsammeln_laeuft():",
        "        return",
        "",
        "    def faden():",
        "        try:",
        "            _wache_fertig()",
        "        except Exception as e:",
        '            fehler(f"Einsammeln: {e}")',
        "            log(traceback.format_exc())",
        '    t = threading.Thread(target=faden, daemon=True, name="einsammeln")',
        "    _einsammeln_faden[0] = t",
        "    t.start()",
        "",
        "",
    ]

# ---- 4. Klarere Meldung nach Puffer-Abbruch
for i, zl in enumerate(z):
    if 'rip_abbrechen_alle("Puffer-Fehler - Disc wird nach der Heilung neu gelesen")' in zl:
        z[i] = zl.replace("Disc wird nach der Heilung neu gelesen", "Disc bitte nochmal einlegen")
    if 'fehler("Puffer-Fehler mitten im Rip - abgebrochen, Disc wird nach der Heilung von selbst neu gelesen")' in zl:
        z[i] = zl.replace("abgebrochen, Disc wird nach der Heilung von selbst neu gelesen",
                          "abgebrochen - Disc bitte nochmal einlegen")

# ---- 3. Laufwerk merken (Hauptschleife) + status.json
i = finde("wache_disc_ohne_job()", def_zeile("main"))
if i < 0:
    fehler.append("wache_disc_ohne_job in main fehlt")
else:
    z[i + 1:i + 1] = [f"{einzug(i)}laufwerk_merken()  # Claude B 23.11.2026"]

sd = def_zeile("status_dict")
if sd >= 0:
    z[sd:sd] = [
        'FACH_TEXT = {1: "leer", 2: "offen", 3: "nicht_bereit", 4: "disc", 0: "unbekannt", -1: "haengt"}  # Claude B 23.11.2026',
        "",
        "",
        "def laufwerk_merken():",
        "    try:",
        "        s = fach_status(laufwerk())",
        "    except Exception:",
        "        s = 0",
        "    with lock:",
        '        S["laufwerk"] = FACH_TEXT.get(s, "unbekannt")',
        "",
        "",
        "def _zustand_ableiten(d):",
        '    """Claude B 23.11.2026: Hauptzustand aus den Auftraegen. Vorher kippte er zwischen liest/uebertraegt/verarbeitet',
        "    (Hochladen ueberschrieb das Lesen der naechsten Disc), und im Dashboard sah verarbeitet wie bereit aus.",
        "    Dazu: einlegen = darf die naechste Disc rein? (nur wenn das Laufwerk frei ist), nebenbei = was sonst noch laeuft.\"\"\"",
        '    au = d.get("auftraege") or []',
        '    name = lambda e: e.get("titel") or e.get("disc_name") or "Disc"',
        '    lesend = [e for e in au if e.get("stufe") in ("erkannt", "analyse", "lesen")]',
        '    verarb = [e for e in au if e.get("stufe") == "erkennen"]',
        '    hoch = [e for e in au if e.get("stufe") == "hochladen"]',
        '    if lesend and d["zustand"] in ("bereit", "verarbeitet", "uebertraegt", "wartet_auf_pi"):',
        "        e = lesend[0]",
        '        d.update(zustand="liest", titel=name(e), prozent=e.get("prozent"), rest_min=e.get("rest_min"))',
        '    elif verarb and d["zustand"] in ("bereit", "uebertraegt"):',
        '        d.update(zustand="verarbeitet", titel=name(verarb[0]), prozent=None, rest_min=None)',
        '    elif hoch and d["zustand"] in ("bereit", "uebertraegt"):',
        "        e = hoch[0]",
        '        d.update(zustand="uebertraegt", titel=name(e), prozent=e.get("prozent"), rest_min=e.get("rest_min"))',
        "    neben = []",
        "    for e in verarb:",
        '        if d["zustand"] != "verarbeitet" or name(e) != d.get("titel"):',
        '            neben.append(f"verarbeitet {name(e)}")',
        "    for e in hoch:",
        '        if d["zustand"] != "uebertraegt" or name(e) != d.get("titel"):',
        '            pz = e.get("prozent")',
        '            neben.append(f"laedt {name(e)} hoch" + (f" ({round(pz)} {chr(37)})" if pz is not None else ""))',
        '    d["nebenbei"] = " · ".join(neben)[:240] or None',
        '    lw = d.get("laufwerk")',
        "    if lesend:",
        '        ein, text = False, "Laufwerk liest gerade"',
        '    elif lw == "haengt":',
        '        ein, text = False, "Laufwerk antwortet gerade nicht"',
        '    elif lw in ("disc", "nicht_bereit"):',
        '        ein, text = False, "Disc liegt noch im Laufwerk"',
        '    elif d.get("puffer_ok") is False:',
        '        ein, text = False, "Zwischenspeicher-Platte gestoert"',
        "    elif _heilung_laeuft:",
        '        ein, text = False, "Zwischenspeicher wird repariert"',
        "    else:",
        '        ein, text = True, "Naechste Disc kann rein"',
        '    d["einlegen"], d["einlegen_text"] = ein, text',
        '    d["einsammeln"] = einsammeln_laeuft()',
        "",
        "",
    ]
i = finde('pi_ok = d.pop("_pi", None)', def_zeile("status_dict"))
if i < 0:
    fehler.append("pi_ok-Zeile fehlt")
else:
    # nach dem bisherigen Zustands-Block (bereit/wartet_auf_pi/fehler) ableiten -> vor dem return
    r = finde("return d", i, i + 12)
    if r < 0:
        fehler.append("return d in status_dict fehlt")
    else:
        z[r:r] = [f"{einzug(r)}_zustand_ableiten(d)  # Claude B 23.11.2026"]
for i, zl in enumerate(z):
    if 'self.send_header("Content-Type", "application/json; charset=utf-8")' in zl:
        z[i + 1:i + 1] = [f'{einzug(i)}self.send_header("Access-Control-Allow-Origin", "*")  # Claude B 23.11.2026: Dashboard-Karte']
        break
else:
    fehler.append("Content-Type-Zeile fehlt")

if fehler:
    print("ABBRUCH:", fehler)
    sys.exit(1)
open(ZIEL, "w", encoding="utf-8").write("\n".join(z))
py_compile.compile(ZIEL, doraise=True)
print("ok", ZIEL, len(z), "Zeilen")
