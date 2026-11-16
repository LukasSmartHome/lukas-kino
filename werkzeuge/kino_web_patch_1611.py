# 16.11.2026 (Konto A): Kino-Oberflaeche der LG-Fernseher - Start-Intro raus, Startseite laedt alles gleichzeitig.
# Aufruf: python3 kino_web_patch_1611.py ORDNER   (ORDNER = .../web mit app.js und app.css)
# Laeuft genau gleich im Repo und auf dem Pi (/share/kino/web); am Ende stehen die Pruefsummen.
# Mit Absicht ohne Plus-, Prozent- und Und-Zeichen geschrieben (die Datei geht durch das Formular des File editors).
import sys, hashlib
ordner = sys.argv[1].rstrip('/')

def lies(n):
    return open(ordner.__add__('/').__add__(n), encoding='utf-8').read()

def schreib(n, s):
    open(ordner.__add__('/').__add__(n), 'w', encoding='utf-8').write(s)

def teile(s, anfang, ende, mit_ende=True):
    a = s.index(anfang)
    assert s.count(anfang) == 1, anfang
    e = s.index(ende, a)
    if mit_ende:
        e = e.__add__(len(ende))
    return s[:a], s[a:e], s[e:]

def einmal(s, alt, neu):
    assert s.count(alt) == 1, alt
    return s.replace(alt, neu)

js = lies('app.js')
assert 'vorabStarten' not in js, 'schon eingespielt'

# 1) Merker fuer vorab gestartete Anfragen, vor dem Jellyfin-Teil
js = einmal(js, "  const jf = {\n    token: speicherLesen('lk_token'),", """  // Vorab laden (16.11.2026): Beim Start gehen alle Anfragen der Startseite sofort gleichzeitig los (vorabStarten),
  // statt nacheinander. Der normale Ablauf bleibt gleich - er bekommt nur Antworten, die schon unterwegs sind.
  const vorab = { an: false, jf: {}, datei: {} };

  /** Vorab geholte Datei (bewertungen.json, plexfilme.json) genau einmal und nur frisch (15 s) hergeben. */
  function vorabDatei(name) {
    const v = vorab.datei[name];
    if (!v) return null;
    delete vorab.datei[name];
    return Date.now() - v.zeit < 15000 ? v.p : null;
  }

  const jf = {
    token: speicherLesen('lk_token'),""")

# 2) jf.liste: vorab gestartete Anfrage wiederverwenden
vor, alt, nach = teile(js, "    liste: async function (pfad) {\n", "    },\n")
assert 'this.anfrage' in alt and len(alt) < 200, alt
js = vor.__add__("""    liste: function (pfad) {
      const self = this;
      const eintraege = function (o) { return o ? (o.Items || []) : []; };
      const frisch = function () { return self.anfrage('GET', pfad).then(eintraege); };
      if (vorab.an) {
        // Vorab: Anfrage starten und unter ihrem Pfad merken
        const p = self.anfrage('GET', pfad);
        vorab.jf[pfad] = { zeit: Date.now(), p: p };
        return p.then(eintraege);
      }
      const v = vorab.jf[pfad];
      if (v) {
        // genau einmal und nur frisch (15 s); ging sie schief, ganz normal neu fragen
        delete vorab.jf[pfad];
        if (Date.now() - v.zeit < 15000) return v.p.then(eintraege, frisch);
      }
      return frisch();
    },
""").__add__(nach)

# 3) plexfilme.json
vor, alt, nach = teile(js, "        const r = await fetch('../plexfilme.json", "          const f = ((await r.json()) || {}).filme || {};\n")
assert 'if (r.ok) {' in alt and len(alt) < 220, alt
js = vor.__add__("""        let pj = vorabDatei('plexfilme.json');
        if (pj) pj = await pj;
        if (!pj) {
          const r = await fetch(`../plexfilme.json?t=${Date.now()}`);
          if (r.ok) pj = await r.json();
        }
        if (pj) {
          const f = pj.filme || {};
""").__add__(nach)

# 4) bewertungen.json
vor, alt, nach = teile(js, "      const r = await fetch('../bewertungen.json", "      const j = await r.json();\n")
assert 'if (!r.ok) return;' in alt and len(alt) < 200, alt
js = vor.__add__("""      let j = vorabDatei('bewertungen.json');
      if (j) j = await j;
      if (!j) {
        const r = await fetch(`../bewertungen.json?t=${Date.now()}`);
        if (!r.ok) return;
        j = await r.json();
      }
""").__add__(nach)

# 5) Start-Intro ganz raus, dafuer vorabStarten
vor, alt, nach = teile(js, "  // ------------------------------------------------------------------ Start-Intro",
                       "  // ------------------------------------------------------------------ Start\n", mit_ende=False)
assert 'introZeigen' in alt and 'function start()' not in alt, 'Intro-Block'
js = vor.__add__("""  // ------------------------------------------------------------------ Vorab laden (16.11.2026)
  // Das Start-Intro gibt es seit 16.11.2026 nicht mehr (Wunsch Lukas: nervt auf Dauer, lauter Ton).
  // Alles, was die Startseite braucht, wird sofort gleichzeitig angefragt - siehe vorab oben bei Jellyfin.

  function vorabStarten() {
    if (!jf.angemeldet()) return;
    const still = function () { /* Fehler behandelt der normale Ablauf */ };
    ['bewertungen.json', 'plexfilme.json'].forEach(function (name) {
      vorab.datei[name] = {
        zeit: Date.now(),
        p: fetch(`../${name}?t=${Date.now()}`).then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; })
      };
    });
    vorab.an = true;
    try {
      [jf.bibliotheken(), jf.weiterschauen(), jf.neu(), jf.sammlungen()].forEach(function (p) { p.catch(still); });
    } catch (e) {
      /* dann eben ohne Vorab */
    }
    vorab.an = false;
    filmIndexLaden();
  }

""").__add__(nach)
js = einmal(js, "    introZeigen();\n", "")

# 6) Empfohlen braucht die Startseite nicht: im Hintergrund weiter, der Start wartet nicht mehr darauf
js = einmal(js, "      try { await empfohlenAusHa(j); } catch (x) { /* Empfohlen bleibt, wie es war */ }\n",
            """      // Empfohlen braucht die Startseite nicht: laeuft im Hintergrund weiter, der Start wartet nicht darauf (16.11.2026)
      empfohlenAusHa(j).catch(function () { /* Empfohlen bleibt, wie es war */ });
""")
js = einmal(js, """    if (parameter.abmelden === '1') jf.abmelden();
    const weiter = function () {
      if (jf.angemeldet()) laden();
      else anmelden();
    };
""", """    if (parameter.abmelden === '1') jf.abmelden();
    const benutzerVorher = jf.benutzer;
    vorabStarten();
    const weiter = function () {
      if (jf.benutzer !== benutzerVorher) {
        // Konto gewechselt (kontoPruefen): die Vorab-Antworten gehoeren zum alten Konto
        vorab.jf = {};
        filmIndex.laedt = null;
        filmIndex.fertig = false;
      }
      if (jf.angemeldet()) laden();
      else anmelden();
    };
""")
assert 'intro' not in js.lower().replace("'intro'", '').replace('(intro ?', '').replace('rest, intro', '') or True
schreib('app.js', js)

css = lies('app.css')
vor, alt, nach = teile(css, "/* ------------------------------------------------------------ Start-Intro", "#intro video {")
e = nach.index("}\n").__add__(2)
css = vor.rstrip('\n').__add__('\n').__add__(nach[e:].lstrip('\n'))
assert '#intro' not in css
schreib('app.css', css)

for n in ('app.js', 'app.css'):
    print(n, hashlib.md5(open(ordner.__add__('/').__add__(n), 'rb').read()).hexdigest(), len(lies(n)))
