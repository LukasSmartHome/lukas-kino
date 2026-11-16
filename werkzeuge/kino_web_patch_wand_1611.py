# 16.11.2026: LG/Web – Kategorie als Poster-Wand, Sammlungen (Filmreihen). Gleiche Regeln wie am TCL (FilmeActivity).
js = 'lg/web/app.js'
css = 'lg/web/app.css'
s = open(js, encoding='utf-8').read()
def ers(alt, neu, n=1):
    global s
    assert s.count(alt) == n, (alt[:70], s.count(alt))
    s = s.replace(alt, neu)

# 1 Seite
ers("function neueSeite(titel) { return { titel: titel, reihen: [], akt: 0, leer: null, kopf: null, ohneFilter: false, raster: false }; }",
    "function neueSeite(titel) { return { titel: titel, reihen: [], akt: 0, leer: null, kopf: null, ohneFilter: false, raster: false, poster: false }; }")

# 2 Helfer bei gesehenKachel
ers("  function gesehenKachel() {\n", '''  // Filmreihen (Star Wars, Eberhofer …) sind in Jellyfin Sammlungen wie die Genre-Kategorien – unterschieden wird am Namen
  // (seit 16.11.2026, wie am TCL): Genre = hat eine Farbe, alles andere ist eine Filmreihe.
  let filmreihen = [], reiheVonFilm = null, reiheVonFilmZeit = 0;

  function istGenre(k) { return Object.prototype.hasOwnProperty.call(FARBEN, (k && k.Name) || ''); }
  function istFilmreihe(e) { return typ(e) === 'BoxSet' && !istGenre(e); }

  /** Name einer Filmreihe ohne das angehängte „Filmreihe“ bzw. „Collection“ von TMDb. */
  function reihenName(e) {
    const voll = String((e && e.Name) || '').trim().replace(/\\s+/g, ' ');
    const kurz = voll.replace(/\\s*[-–]?\\s*(Filmreihe|Collection|Sammlung)$/i, '').trim();
    return kurz || voll;
  }

  function sammlungenKachel(anzahl) { return { Type: 'Sammlungen', Id: '@sammlungen', Name: 'Sammlungen', ChildCount: anzahl }; }

  /** Film-Id → Filmreihe; wird 10 Minuten gemerkt. Geht etwas schief, gibt es eben keine Filmreihen-Kacheln. */
  function filmreihenMitglieder() {
    if (reiheVonFilm && Date.now() - reiheVonFilmZeit < 600000) return Promise.resolve(reiheVonFilm);
    const l = filmreihen.slice();
    return Promise.all(l.map(function (fr) { return jf.inhalt(fr.Id).then(function (a) { return a; }, function () { return null; }); }))
      .then(function (alle) {
        const m = {};
        let ganz = true;
        alle.forEach(function (kinder, i) {
          if (!kinder) { ganz = false; return; }
          kinder.forEach(function (o) { if (o && o.Id) m[o.Id] = l[i]; });
        });
        if (ganz) {
          reiheVonFilm = m;
          reiheVonFilmZeit = Date.now();
        }
        return m;
      });
  }

  function gesehenKachel() {
''')

# 3 Gesehen-Kachel nachziehen: Poster-Wände neu aufbauen (Haken)
ers("      if (s.ohneFilter || s.raster) return;\n", '''      if (s.poster) s.wandVersion = (s.wandVersion || 0) + 1;   // Haken auf den Postern nachziehen
      if (s.ohneFilter || s.raster) return;
''')

# 4 Gesehen als Wand
ers('''    neu.ohneFilter = true;
    neu.reihen.push(neueReihe('Gesehen', null, POSTER));
    seiteZeigen(neu);
''', '''    neu.ohneFilter = true;
    neu.raster = true;   // Poster-Wand wie bei den Kategorien (seit 16.11.2026)
    neu.poster = true;
    seiteZeigen(neu);
''')
ers('''      neu.reihen = [];
      if (l.length) neu.reihen.push(reiheAus(l.length === 1 ? '1 Film' : l.length + ' Filme', l, POSTER));
      if (!neu.reihen.length) neu.leer = 'Noch nichts gesehen';
''', '''      posterWandFuellen(neu, l);
      neu.kopfInfo = l.length === 1 ? '1 Film' : l.length + ' Filme';
      if (!l.length) neu.leer = 'Noch nichts gesehen';
''')

# 5 Startseite
ers('''    if (samml.length || ges.ids.length) {
      const kat = reiheAus('Kategorien', samml, POSTER);
      if (ges.ids.length) kat.eintraege.push(gesehenKachel());
      s.reihen.push(kat);
      samml.forEach(function (k) { s.reihen.push(neueReihe(k.Name, 'samml:' + k.Id, POSTER)); });
    }
''', '''    // Genre-Kategorien und Filmreihen trennen; die Filmreihen stecken hinter der Kachel „Sammlungen“
    const genres = samml.filter(istGenre);
    filmreihen = samml.filter(function (k) { return !istGenre(k); });
    reiheVonFilm = null;
    if (genres.length || filmreihen.length || ges.ids.length) {
      const kat = reiheAus('Kategorien', genres, POSTER);
      if (filmreihen.length) kat.eintraege.unshift(sammlungenKachel(filmreihen.length));
      if (ges.ids.length) kat.eintraege.push(gesehenKachel());
      s.reihen.push(kat);
      genres.forEach(function (k) { s.reihen.push(neueReihe(k.Name, 'samml:' + k.Id, POSTER)); });
    }
''')

# 6 Hintergrund
ers('''    if (typ(e) === 'BoxSet' || typ(e) === 'Gesehen') {
      hintergrundSetzen(null, typ(e) === 'Gesehen' ? '#3E4A5E' : FARBEN[e.Name] || '#444444');
      return;
    }
''', '''    const reiheMitBild = istFilmreihe(e) && !!hintergrundTag(e);   // Filmreihe: ihr eigenes Bild
    if ((typ(e) === 'BoxSet' && !reiheMitBild) || typ(e) === 'Gesehen' || typ(e) === 'Sammlungen') {
      hintergrundSetzen(null, typ(e) === 'Gesehen' ? '#3E4A5E' : typ(e) === 'Sammlungen' ? '#3A3380' : FARBEN[e.Name] || '#444444');
      return;
    }
''')

# 7 Zeichnen: aus der Wand ins Detail – keine Reihen aufbauen
ers('''    if (wand) wandZeichnen();
    else if (liste) {
      reihenZeichnen();
''', '''    if (wand) wandZeichnen();
    else if (liste) {
      if (seite.raster) el.reihen.style.opacity = '0';   // Detail aus der Wand heraus: dahinter gibt es keine Reihen
      else reihenZeichnen();
''')

# 8 Karte: Sammlungen-Kachel, Name auf Filmreihen
ers('''    if (!ordner && t !== 'Photo' && t !== 'Video') c.appendChild(div('k-name', e.Name || ''));
    const url = kartenUrl(e, form);''', '''    if (t === 'Sammlungen') {
      c.classList.add('k-sammlungen');
      c.innerHTML = '<svg viewBox="0 0 120 80" fill="none"><rect x="12" y="14" width="34" height="50" rx="5" fill="#fff" fill-opacity=".25"/>'
        + '<rect x="74" y="14" width="34" height="50" rx="5" fill="#fff" fill-opacity=".25"/>'
        + '<rect x="43" y="6" width="34" height="50" rx="5" fill="' + GELB + '"/></svg>'
        + '<div class="kg-name">Sammlungen</div><div class="kg-zahl"></div>';
      const n = e.ChildCount || 0;
      c.querySelector('.kg-zahl').textContent = n === 1 ? '1 Filmreihe' : n + ' Filmreihen';
      return c;
    }
    if (!ordner && t !== 'Photo' && t !== 'Video') c.appendChild(div('k-name', istFilmreihe(e) ? reihenName(e) : e.Name || ''));
    const url = kartenUrl(e, form);''')
ers('''    else if (t === 'Episode') unten = (e.IndexNumber > 0 ? e.IndexNumber + '. ' : '') + (e.Name || '');
    if (unten != null) {
      const u = div('k-unten');''', '''    else if (t === 'Episode') unten = (e.IndexNumber > 0 ? e.IndexNumber + '. ' : '') + (e.Name || '');
    else if (istFilmreihe(e)) unten = reihenName(e);   // Filmreihe: Name im Bild, damit man sie von einem Film unterscheidet
    if (unten != null) {
      const u = div('k-unten');''')

# 9 Info oben links
ers("        if (t === 'BoxSet' || t === 'Gesehen') kategorieInfo(box, e);", "        if (t === 'BoxSet' || t === 'Gesehen' || t === 'Sammlungen') kategorieInfo(box, e);")
ers('''    const g = div('i-gross', e.Name || '');
    g.style.marginTop = '66px';
    box.appendChild(g);
    const n = e.ChildCount || 0;
    if (n > 0) {
      const z = div('i-zahl', n === 1 ? '1 Film' : n + ' Filme');''', '''    const g = div('i-gross', istFilmreihe(e) ? reihenName(e) : e.Name || '');
    g.style.marginTop = '66px';
    box.appendChild(g);
    const n = e.ChildCount || 0;
    if (n > 0) {
      const wort = typ(e) === 'Sammlungen' ? ['1 Filmreihe', ' Filmreihen'] : ['1 Film', ' Filme'];
      const z = div('i-zahl', n === 1 ? wort[0] : n + wort[1]);''')

# 10 Öffnen
ers('''    else if (t === 'Gesehen') gesehenOeffnen(e);
    else if (t === 'Series') serieOeffnen(e);''', '''    else if (t === 'Gesehen') gesehenOeffnen(e);
    else if (t === 'Sammlungen') sammlungenOeffnen(e);
    else if (t === 'Series') serieOeffnen(e);''')

# 11 Kategorie öffnen
a = s.index("  function kategorieOeffnen(k) {")
b = s.index("\n  }\n", a) + len("\n  }\n")
s = s[:a] + '''  /**
   * Kategorie oder Filmreihe öffnen: seit 16.11.2026 als Poster-Wand über den ganzen Bildschirm (Wunsch Lukas – in den
   * Reihen nach Jahren hat man leicht übersehen, dass darunter noch Filme kommen). Oben steht groß der Name.
   * Genre: zuerst die Filmreihen als eigene Kachel (ab 2 Filmen der Reihe in diesem Genre), dann die übrigen Filme,
   * die am besten bewerteten zuerst; Gesehenes fehlt wie bisher. Filmreihe: alle Teile in der Reihenfolge des Erscheinens.
   */
  function kategorieOeffnen(k) {
    const istReihe = istFilmreihe(k);
    const neu = neueSeite(istReihe ? reihenName(k) : k.Name);
    neu.kopf = k;
    neu.raster = true;
    neu.poster = true;
    seiteZeigen(neu);
    Promise.all([jf.inhalt(k.Id), istReihe ? Promise.resolve({}) : filmreihenMitglieder().then(null, function () { return {}; })]).then(function (l) {
      const roh = l[0], zuReihe = l[1] || {};
      const filme = roh.filter(function (e) { return istReihe || !istGesehen(e); });
      let wand = [], reihenN = 0;
      if (istReihe) wand = filme.slice().reverse();   // Jellyfin liefert die neuesten zuerst – eine Filmreihe beginnt beim ersten Teil
      else {
        const zahl = {};
        filme.forEach(function (o) {
          const fr = zuReihe[o.Id];
          if (fr) zahl[fr.Id] = (zahl[fr.Id] || 0) + 1;
        });
        const drin = {}, rest = [];
        filme.forEach(function (o, i) {
          const fr = zuReihe[o.Id];
          if (fr && zahl[fr.Id] >= 2) {
            if (!drin[fr.Id]) {
              drin[fr.Id] = true;
              wand.push(fr);
            }
          } else rest.push({ o: o, i: i });
        });
        reihenN = wand.length;
        wand.sort(function (x, y) { return reihenName(x).toLowerCase() < reihenName(y).toLowerCase() ? -1 : 1; });
        rest.sort(function (x, y) { return (Number(y.o.CommunityRating) || 0) - (Number(x.o.CommunityRating) || 0) || x.i - y.i; });
        wand = wand.concat(rest.map(function (x) { return x.o; }));
      }
      posterWandFuellen(neu, wand);
      const n = wand.length - reihenN;
      neu.kopfInfo = (n === 1 ? '1 Film' : n + ' Filme')
        + (reihenN > 0 ? '   ·   ' + (reihenN === 1 ? '1 Filmreihe' : reihenN + ' Filmreihen') : '');
      if (!wand.length) neu.leer = roh.length ? 'Alles schon gesehen' : 'Keine Filme';
      if (seite === neu) fokusGeaendert();
    }, function () {
      neu.reihen = [];
      neu.leer = 'Keine Filme';
      if (seite === neu) fokusGeaendert();
    });
  }

  /** Kachel „Sammlungen“: alle Filmreihen (Star Wars, Eberhofer …) als Poster-Wand. */
  function sammlungenOeffnen(k) {
    const neu = neueSeite('Sammlungen');
    neu.kopf = k;
    const l = filmreihen.slice().sort(function (x, y) { return reihenName(x).toLowerCase() < reihenName(y).toLowerCase() ? -1 : 1; });
    posterWandFuellen(neu, l);
    neu.kopfInfo = l.length === 1 ? '1 Filmreihe' : l.length + ' Filmreihen';
    if (!l.length) neu.leer = 'Noch keine Filmreihen';
    seiteZeigen(neu);
  }
''' + s[b:]

# 12 Wand: Maße und Füllen
ers("  function rasterFuellen(s, eintraege) {\n", '''  // Poster-Wand (geöffnete Kategorie oder Filmreihe, seit 16.11.2026): 9 Poster nebeneinander über die ganze Fläche
  const POSTER_RASTER = { spalten: 9, kw: 172, kh: 258, luecke: 25 };

  function posterWandFuellen(s, eintraege) {
    const m = POSTER_RASTER;
    s.raster = true;
    s.poster = true;
    s.spalten = m.spalten;
    s.kw = m.kw;
    s.kh = m.kh;
    s.luecke = m.luecke;
    s.reihen = [];
    for (let i = 0; i < eintraege.length; i += m.spalten) s.reihen.push(reiheAus('', eintraege.slice(i, i + m.spalten), POSTER));
    s.akt = 0;
    s.wandVersion = (s.wandVersion || 0) + 1;
  }

  function rasterFuellen(s, eintraege) {
''')

# 13 Wand: Karte
ers('''  function wandKarte(e, s, r, i, j) {
    const c = div('wk' + (e.IsFolder ? ' ordner' : ''));
''', '''  function wandKarte(e, s, r, i, j) {
    if (s.poster) {
      // dieselbe Karte wie in den Reihen (Poster, Haken, Name der Filmreihe), nur etwas kleiner
      const p = karteBauen(e, POSTER);
      p.classList.add('wp');
      p.style.left = (LINKS + j * (s.kw + s.luecke)) + 'px';
      p.style.top = (i * (s.kh + s.luecke)) + 'px';
      p._id = e.Id;
      p._reihe = r;
      p._index = j;
      return p;
    }
    const c = div('wk' + (e.IsFolder ? ' ordner' : ''));
''')

# 14 Wand zeichnen
ers('''    el.wand.classList.toggle('klein', !gross);
    el.wName.textContent = s.kopf ? ordnerName(s.kopf) : s.titel;
    el.wInfo.textContent = s.kopfInfo || '';
    // rechts: Aufnahmedatum und Nummer des Fotos im Fokus
    const f = fokusEintrag();
    let rechts = '';
    if (f && !f.IsFolder) {''', '''    el.wand.classList.toggle('klein', !gross);
    el.wand.classList.toggle('poster', !!s.poster);
    el.wName.textContent = s.poster ? s.titel : s.kopf ? ordnerName(s.kopf) : s.titel;
    el.wInfo.textContent = s.kopfInfo || '';
    // rechts: Aufnahmedatum und Nummer des Fotos im Fokus; in der Poster-Wand der Film im Fokus (die Poster haben keine Schrift)
    const f = fokusEintrag();
    let rechts = '';
    if (s.poster) {
      if (f && typ(f) === 'BoxSet') {
        const fn = f.ChildCount || 0;
        rechts = reihenName(f) + (fn > 0 ? '   ·   ' + (fn === 1 ? '1 Film' : fn + ' Filme') : '');
      } else if (f) rechts = (f.Name || '') + (f.ProductionYear > 0 ? '   ·   ' + f.ProductionYear : '');
    } else if (f && !f.IsFolder) {''')
ers('''      if (!s.leer && !g.children.length) {
        for (let i = 0; i < 4; i++) {
          for (let j = 0; j < FOTO_RASTER.spalten; j++) {
            const c = div('wk platz');
            c.style.left = (LINKS + j * (FOTO_RASTER.kw + FOTO_RASTER.luecke)) + 'px';
            c.style.top = (i * (FOTO_RASTER.kh + FOTO_RASTER.luecke)) + 'px';
            c.style.width = FOTO_RASTER.kw + 'px';
            c.style.height = FOTO_RASTER.kh + 'px';
            g.appendChild(c);''', '''      if (!s.leer && !g.children.length) {
        const pm = s.poster ? POSTER_RASTER : FOTO_RASTER;
        for (let i = 0; i < (s.poster ? 3 : 4); i++) {
          for (let j = 0; j < pm.spalten; j++) {
            const c = div('wk platz');
            c.style.left = (LINKS + j * (pm.kw + pm.luecke)) + 'px';
            c.style.top = (i * (pm.kh + pm.luecke)) + 'px';
            c.style.width = pm.kw + 'px';
            c.style.height = pm.kh + 'px';
            g.appendChild(c);''')
ers("    const von = Math.max(0, s.akt - 3), bis = Math.min(s.reihen.length - 1, s.akt + 7);\n",
    "    const von = Math.max(0, s.akt - (s.poster ? 2 : 3)), bis = Math.min(s.reihen.length - 1, s.akt + (s.poster ? 4 : 7));\n")
open(js, 'w', encoding='utf-8').write(s)

c = open(css, encoding='utf-8').read()
alt = "/* ------------------------------------------------------------ Kachel „Gesehen“ in den Kategorien */\n"
assert c.count(alt) == 1
c = c.replace(alt, '''/* ------------------------------------------------------------ Poster-Wand (Kategorie, Filmreihe, Sammlungen – 16.11.2026) */

#wand.poster .w-name { top: 46px; font-size: 68px; max-width: 900px; }
#wand.poster .w-info { top: 128px; }
#wand.poster .w-rechts { top: 80px; max-width: 800px; overflow: hidden; text-overflow: ellipsis; }
#wand.poster .w-fenster { top: 176px; }
#wand.poster.klein .w-name { top: 38px; transform: scale(.65); max-width: 1380px; }
#wand.poster.klein .w-rechts { top: 52px; }
#wand.poster.klein .w-fenster { top: 98px; }

.karte.wp { width: 172px; height: 258px; border-radius: 16px; transition: transform .2s cubic-bezier(.2, .8, .2, 1); }
.karte.wp.fokus { transform: scale(1.1); }
.karte.wp.fokus:after {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  border-radius: 16px;
  box-shadow: inset 0 0 0 4px rgba(255, 255, 255, .95);
}
.karte.wp .k-unten span { left: 14px; right: 14px; bottom: 12px; font-size: 22px; }

/* Kachel „Sammlungen“ (Filmreihen) in den Kategorien */
.karte.k-sammlungen { background: linear-gradient(135deg, #5A4FCF, #1C1947); }
.karte.k-sammlungen svg { position: absolute; left: 38px; top: 70px; width: 120px; height: 80px; }
.karte.k-sammlungen .kg-name { font-size: 28px; }

''' + alt)
open(css, 'w', encoding='utf-8').write(c)
print('ok', len(s), len(c))
