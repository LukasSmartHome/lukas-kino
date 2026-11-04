/*
 * Lukas Kino – Musik-Bereich mit Karaoke (seit 04.11.2026, Wunsch Lukas). Version 3 (04.11.2026 abends).
 *
 * Der ganze Musik-Bereich steckt in dieser Datei, für beide Fernseher-Arten gleich:
 *  - LG: app.js lädt die Datei nach und ruft LukasMusik.bereich(...) auf, sobald in der Leiste „Musik“ gewählt wird.
 *    Links am Rand zeigt app.js seine eigene Leiste (Musik läuft weiter, bis ein anderer Bereich gewählt wird).
 *  - TCL: MusikActivity zeigt musik.html (vom Pi, Port 8098) in einer WebView, mit eigener Leiste (o.eigeneLeiste).
 *
 * Ansichten:
 *  - start: oben Kacheln (Karaoke-Modus, Gelikte Songs, Genres nach eigenem Hören, Alle Lieder, Alles mischen),
 *    darunter Am meisten gehört, Zuletzt gehört, Lieblings-Genres, Neu dazugekommen.
 *  - raster: Matrix mit Knöpfen oben (mischen, Karaoke, alle Lieder). Genre -> Künstler (rund, meistgehört oben
 *    links, sonst die bekanntesten), Künstler -> Lieder. Auch Gelikte Songs, Alle Lieder, Karaoke-Auswahl.
 *  - suche, player (Knöpfe unten, nach Ruhe großes Künstlerbild), karaoke (Satz oben, Zeitspur unten).
 * Hoch aus der ersten Reihe = Leiste oben: Lupe und Mini-Player (öffnet wieder Player bzw. Karaoke).
 * Geliked wird über die Favoriten von Jellyfin (pro Konto: Lukas, Eltern, Jonas). Gehört-Zähler je Fernseher.
 *
 * Daten vom Pi (Lukas Kino Web, /share/kino):
 *  - /karaoke/index.json  Dateiname -> {status, lrc, wort, sprache}; /karaoke/lrc/*.lrc, später /karaoke/wort/*.json
 *  - /musik/kuenstler.json Name -> {bild, fans} (Deezer), /musik/kuenstler/*.jpg, /musik/beliebt.json Datei -> {rank}
 */
(function () {
  'use strict';

  const GELB = '#F2B705';
  const STAGE_W = 1920, STAGE_H = 1080, LINKS = 110;
  const KW = 352, KH = 198, ABST = 26;
  const SUCH_TASTEN = [['a', 'b', 'c', 'd', 'e', 'f'], ['g', 'h', 'i', 'j', 'k', 'l'], ['m', 'n', 'o', 'p', 'q', 'r'],
    ['s', 't', 'u', 'v', 'w', 'x'], ['y', 'z', 'ä', 'ö', 'ü', 'ß'], ['1', '2', '3', '4', '5', '6'], ['7', '8', '9', '0', '-', '.'],
    ['␣', '␣', '␣', '⌫', '⌫', '⌫']];
  // Matrix-Maße je Art: Spalten, Kachelbreite, Bildhöhe, Zeilenabstand, Lücke
  const MASS = {
    lieder: { sp: 7, w: 222, h: 222, zeile: 304, luecke: 24 },
    kuenstler: { sp: 7, w: 222, h: 222, zeile: 312, luecke: 24 },
    wahl: { sp: 4, w: KW, h: KH, zeile: 250, luecke: ABST }
  };

  // ------------------------------------------------------------------ Genres -> wenige große Gruppen
  const GRUPPEN = [
    ['Weihnachten', /weihnacht|christmas|holiday|xmas/i, '#B3262E'],
    ['Film und Soundtrack', /soundtrack|filmmusik|score|musical/i, '#1592C4'],
    ['Kinder', /kinder|children/i, '#3E9BF0'],
    ['Deutsch und Schlager', /schlager|volksmusik|deutschpop|deutsch|liedermacher|neue deutsche/i, '#2E8B3F'],
    ['Hip-Hop und R&B', /hip-hop|hip hop|rap|r&b|rnb|soul|urban/i, '#F26B3A'],
    ['Latin und Reggae', /latin|reggae|spanisch|reggaeton|salsa|dancehall/i, '#E9A23B'],
    ['Dance und Elektro', /dance|electronic|elektro|house|techno|edm|trance|disco/i, '#6A3DE8'],
    ['Country und Folk', /country|folk|bluegrass/i, '#9C7442'],
    ['Klassik und Chill', /classical|clasic|klassik|new age|ambient|chill|lounge|jazz|easy listening/i, '#2F6E73'],
    ['Rock', /rock|alternative|indie|metal|punk|blues|grunge/i, '#B83A3A'],
    ['Pop', /pop|top 40|top 100|singer|songwriter|chanson|vari[eé]t[eé]|charts/i, '#E8559A']
  ];
  const SONSTIGES = ['Sonstiges', null, '#56637A'];

  function gruppe(e) {
    const g = ((e && e.Genres) || []).join(' | ');
    if (!g) return SONSTIGES[0];
    for (let i = 0; i < GRUPPEN.length; i++) if (GRUPPEN[i][1].test(g)) return GRUPPEN[i][0];
    return SONSTIGES[0];
  }

  function gruppenFarbe(name) {
    for (let i = 0; i < GRUPPEN.length; i++) if (GRUPPEN[i][0] === name) return GRUPPEN[i][2];
    return SONSTIGES[2];
  }

  function interpret(e) {
    if (!e) return '';
    if (e.Artists && e.Artists.length) return e.Artists.join(', ');
    return e.AlbumArtist || '';
  }

  function hauptKuenstler(e) {
    const a = (e && e.Artists && e.Artists[0]) || (e && e.AlbumArtist) || '';
    return a.trim() || 'Unbekannt';
  }

  function dateiname(e) { return ((e && e.Path) || '').split('/').pop(); }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; });
  }

  function zeit(s) {
    s = Math.max(0, Math.floor(s || 0));
    return Math.floor(s / 60) + ':' + ('0' + (s % 60)).slice(-2);
  }

  function dauerText(ticks) {
    const s = Math.round((ticks || 0) / 1e7);
    return s > 0 ? zeit(s) : '';
  }

  function anzahl(n) { return n + (n === 1 ? ' Lied' : ' Lieder'); }

  function normal(s) {
    return String(s || '').toLowerCase().replace(/[^a-z0-9äöüß ]/g, ' ').replace(/\s+/g, ' ').trim();
  }

  function speicher(k, v) {
    try {
      if (v === undefined) return JSON.parse(localStorage.getItem(k) || 'null');
      localStorage.setItem(k, JSON.stringify(v));
    } catch (e) { /* ohne Speicher */ }
    return null;
  }

  const SYM = {
    lupe: '<circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.2-4.2"/>',
    mikro: '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v3M8.5 21h7"/>',
    mischen: '<path d="M3 7h3.5c3 0 5 10 8 10H21M18 14l3 3-3 3M3 17h3.5c1.3 0 2.3-1.7 3.3-3.7M14.5 7H21M18 4l3 3-3 3"/>',
    note: '<path d="M9 17.5V6l10.5-2v11.5M9 9.5l10.5-2"/><circle cx="6.6" cy="17.5" r="2.4"/><circle cx="17.1" cy="15.5" r="2.4"/>',
    alle: '<rect x="4" y="4" width="7" height="7" rx="1.5"/><rect x="13" y="4" width="7" height="7" rx="1.5"/><rect x="4" y="13" width="7" height="7" rx="1.5"/><rect x="13" y="13" width="7" height="7" rx="1.5"/>',
    herz: '<path d="M12 20s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.3a4.3 4.3 0 0 1 7.5 2.5C19.5 15.4 12 20 12 20z"/>',
    herzVoll: '<path fill="currentColor" d="M12 20s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.3a4.3 4.3 0 0 1 7.5 2.5C19.5 15.4 12 20 12 20z"/>',
    stern: '<path d="M12 3.2l2.7 5.5 6 .9-4.35 4.2 1 6-5.35-2.8-5.35 2.8 1-6L3.3 9.6l6-.9z"/>',
    person: '<circle cx="12" cy="8.5" r="4"/><path d="M4.5 20.5c1.2-3.6 4.2-5.5 7.5-5.5s6.3 1.9 7.5 5.5"/>',
    flagge: '<path d="M5 21V4M5 4h11l-2 4 2 4H5"/>',
    zurueck: '<path fill="currentColor" stroke="none" d="M6 6h2.4v12H6zM20 6v12L10 12z"/>',
    vor: '<path fill="currentColor" stroke="none" d="M15.6 6H18v12h-2.4zM4 6v12l10-6z"/>',
    play: '<path fill="currentColor" stroke="none" d="M8 5v14l11-7z"/>',
    pause: '<path fill="currentColor" stroke="none" d="M7 5h4v14H7zM13 5h4v14h-4z"/>',
    app: '<path d="M14 3.5h6.5V10M20.5 3.5L11.5 12.5"/><path d="M18.5 14v4.5a2 2 0 0 1-2 2h-11a2 2 0 0 1-2-2v-11a2 2 0 0 1 2-2H10"/>',
    film: '<rect x="3" y="5" width="18" height="14" rx="3"/><path d="M10 9l5 3-5 3z"/>',
    serie: '<rect x="3" y="7" width="18" height="11" rx="2.5"/><path d="M8.5 3L12 7l3.5-4M8 21h8"/>',
    merk: '<path d="M6.5 3.5h11a1 1 0 0 1 1 1v16l-6.5-4.2-6.5 4.2v-16a1 1 0 0 1 1-1z"/>',
    foto: '<rect x="3" y="5" width="18" height="14" rx="3"/><path d="M3.5 17L9 11.5l4 4 2.5-2.5 5 4.5"/><circle cx="16.2" cy="9.2" r="1.6"/>'
  };

  function svg(inhalt, klasse) {
    return '<svg ' + (klasse ? 'class="' + klasse + '" ' : '') + 'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
      + 'stroke-linecap="round" stroke-linejoin="round">' + inhalt + '</svg>';
  }

  // ------------------------------------------------------------------ Zustand

  const M = {
    o: {}, aktiv: false, sichtbar: false, sicht: 'start', zurueckZu: 'start', letzteWiedergabe: 'player',
    katalog: null, katalogLaedt: null, index: {}, nachId: {}, kuenstler: {}, beliebt: {},
    reihen: [], akt: 0, kopf: -1, kopfZiele: [], startVeraltet: false,
    raster: null, stapel: [], rasterVon: 'start',
    such: { text: '', z: 0, s: 0, inTreffern: false, treffer: [], tf: 0 },
    leiste: false, leistePos: 0,
    audio: null, liste: [], pos: 0, zufall: false, karaokeModus: false, mischQuelle: null,
    text: null, textFuer: null, textCache: {}, gemeldet: null, gezaehlt: false, fehlerZaehler: 0,
    knopf: 1, ruheTimer: 0,
    raf: 0, timer: 0, hinweisTimer: 0, el: null, ctx: null
  };

  function basis() { return M.o.karaokeBasis || '/karaoke/'; }
  function musikBasis() { return M.o.musikBasis || '/musik/'; }
  function server() { return (M.o.server || 'http://192.168.178.44:8096').replace(/\/+$/, ''); }

  function auth() {
    const o = M.o;
    if (typeof o.auth === 'function') return o.auth();
    if (o.auth) return o.auth;
    return 'MediaBrowser Client="Lukas Kino Musik", Device="' + (o.geraetName || 'Fernseher') + '", DeviceId="'
      + (o.geraet || 'lukas-kino-musik') + '", Version="3.0", Token="' + (o.token || '') + '"';
  }

  async function jf(methode, pfad, body) {
    const opt = { method: methode, headers: { 'Authorization': auth(), 'Accept': 'application/json' } };
    if (body !== undefined) {
      opt.headers['Content-Type'] = 'application/json';
      opt.body = JSON.stringify(body);
    }
    const r = await fetch(server() + pfad, opt);
    if (!r.ok) {
      const f = new Error('Jellyfin ' + r.status);
      f.status = r.status;
      throw f;
    }
    const t = await r.text();
    return t ? JSON.parse(t) : {};
  }

  async function jsonVomPi(pfad) {
    try {
      const r = await fetch(pfad + '?t=' + Date.now());
      return r.ok ? await r.json() : {};
    } catch (e) {
      return {};
    }
  }

  const FELDER = 'Path,Genres,AlbumArtist,Artists,ImageTags,AlbumId,AlbumPrimaryImageTag,RunTimeTicks,DateCreated,ProductionYear,Album,UserData';

  async function katalog(o, frisch) {
    if (o) M.o = Object.assign({}, M.o, o);
    if (M.katalog && !frisch) return M.katalog;
    if (M.katalogLaedt) return M.katalogLaedt;
    M.katalogLaedt = (async function () {
      try {
        const geladen = await Promise.all([
          jf('GET', '/Items?userId=' + M.o.benutzer + '&includeItemTypes=Audio&recursive=true&sortBy=SortName'
            + '&fields=' + FELDER + '&enableImageTypes=Primary'),
          jsonVomPi(basis() + 'index.json'), jsonVomPi(musikBasis() + 'kuenstler.json'), jsonVomPi(musikBasis() + 'beliebt.json')]);
        M.index = geladen[1] || {};
        M.kuenstler = geladen[2] || {};
        M.beliebt = geladen[3] || {};
        M.nachId = {};
        // Doppelte (gleicher Titel und Interpret, z. B. mp3 und m4a) nur einmal – lieber die mit Cover bzw. Text
        const schluessel = {};
        const lieder = [];
        (geladen[0].Items || []).forEach(function (e) {
          const k = M.index[dateiname(e)];
          e._gruppe = gruppe(e);
          e._karaoke = !!(k && (k.wort || k.lrc));
          e._sprache = k && k.sprache;
          e._kuenstler = hauptKuenstler(e);
          const b = M.beliebt[dateiname(e)];
          e._rang = (b && b.rank) || 0;
          const s = normal(e.Name) + '|' + normal(e._kuenstler);
          const alt = schluessel[s];
          if (alt !== undefined) {
            const vorher = lieder[alt];
            const besser = (e.ImageTags && e.ImageTags.Primary && !(vorher.ImageTags && vorher.ImageTags.Primary)) || (e._karaoke && !vorher._karaoke);
            if (besser) lieder[alt] = e;
            return;
          }
          schluessel[s] = lieder.length;
          lieder.push(e);
        });
        lieder.forEach(function (e) { M.nachId[e.Id] = e; });
        M.katalog = { lieder: lieder };
        return M.katalog;
      } finally {
        M.katalogLaedt = null;
      }
    })();
    return M.katalogLaedt;
  }

  // Wie oft gehört (dieser Fernseher plus Zähler von Jellyfin für dieses Konto)
  function gehoert() { return speicher('lm_gehoert') || {}; }
  function spiele(e, g) { return ((g || gehoert())[e.Id] || 0) + ((e.UserData && e.UserData.PlayCount) || 0); }
  function geliked(e) { return !!(e && e.UserData && e.UserData.IsFavorite); }

  function nachWert(liste) {
    const g = gehoert();
    return liste.slice().sort(function (a, b) {
      return (spiele(b, g) - spiele(a, g)) || (b._rang - a._rang) || (a.Name || '').localeCompare(b.Name || '', 'de');
    });
  }

  function bildUrl(e, breite) {
    if (!e) return '';
    const tags = e.ImageTags || {};
    if (tags.Primary) return server() + '/Items/' + e.Id + '/Images/Primary?fillWidth=' + breite + '&fillHeight=' + breite + '&quality=88&tag=' + tags.Primary;
    if (e.AlbumId && e.AlbumPrimaryImageTag)
      return server() + '/Items/' + e.AlbumId + '/Images/Primary?fillWidth=' + breite + '&fillHeight=' + breite + '&quality=88&tag=' + e.AlbumPrimaryImageTag;
    return '';
  }

  function kuenstlerBild(name) {
    const k = M.kuenstler[name];
    return k && k.bild ? musikBasis() + 'kuenstler/' + encodeURIComponent(k.bild) : '';
  }

  function streamUrl(e) {
    const t = M.o.token || '';
    return server() + '/Audio/' + e.Id + '/stream?static=true&api_key=' + t + '&ApiKey=' + t;
  }

  /** Bild als Hintergrund (kein <img>: kaputte Bilder zeichnen sonst weiße Striche). Ersatz: Gruppenfarbe + Symbol. */
  function coverSetzen(el, e, breite, url) {
    const u = url !== undefined ? url : bildUrl(e, breite);
    el.style.setProperty('--lm-f', gruppenFarbe(e && e._gruppe));
    if (u) {
      el.style.backgroundImage = 'url("' + u + '")';
      el.classList.add('mit');
    } else {
      el.style.backgroundImage = '';
      el.classList.remove('mit');
    }
  }

  function coverHtml(klasse, sym) {
    return '<div class="lm-cov ' + (klasse || '') + '"><span>' + svg(sym || SYM.note) + '</span></div>';
  }

  // ------------------------------------------------------------------ Aussehen

  const CSS = ''
    + '@font-face{font-family:"Inter Tight";src:url("' + (window.LUKAS_MUSIK_FONT || 'fonts/InterTight.ttf') + '") format("truetype");font-weight:100 900}'
    + '.lm-w{position:fixed;left:0;top:0;width:100%;height:100%;background:#07080B;z-index:9000;overflow:hidden;font-family:"Inter Tight",system-ui,sans-serif;'
    + 'color:#fff;-webkit-font-smoothing:antialiased;opacity:0;visibility:hidden;transition:opacity .3s,visibility 0s linear .3s}'
    + '.lm-w.an{opacity:1;visibility:visible;transition:opacity .3s}'
    + '.lm-b{position:absolute;left:0;top:0;width:1920px;height:1080px;transform-origin:0 0;overflow:hidden}'
    + '.lm-b *{box-sizing:border-box}'
    // Schlieren im Hintergrund (nur transform -> leicht für den Fernseher)
    + '.lm-au{position:absolute;left:0;top:0;width:1920px;height:1080px;overflow:hidden}'
    + '.lm-au i{position:absolute;width:1300px;height:1300px;border-radius:50%;opacity:.34;will-change:transform;transition:background 1.2s}'
    + '.lm-au i:nth-child(1){left:620px;top:-520px;background:radial-gradient(circle,var(--lm-a1,#F2B705) 0%,transparent 64%);animation:lmA 23s ease-in-out infinite alternate}'
    + '.lm-au i:nth-child(2){left:-420px;top:380px;background:radial-gradient(circle,var(--lm-a2,#6A3DE8) 0%,transparent 64%);animation:lmB 29s ease-in-out infinite alternate;opacity:.26}'
    + '.lm-au i:nth-child(3){left:1100px;top:420px;background:radial-gradient(circle,var(--lm-a3,#E8559A) 0%,transparent 62%);animation:lmC 34s ease-in-out infinite alternate;opacity:.22}'
    + '@keyframes lmA{0%{transform:translate(0,0) scale(1)}50%{transform:translate(-260px,140px) scale(1.15)}100%{transform:translate(180px,60px) scale(.92)}}'
    + '@keyframes lmB{0%{transform:translate(0,0) scale(1)}50%{transform:translate(380px,-160px) scale(1.2)}100%{transform:translate(120px,-320px) scale(.95)}}'
    + '@keyframes lmC{0%{transform:translate(0,0) scale(1.05)}50%{transform:translate(-420px,-120px) scale(.9)}100%{transform:translate(-160px,-380px) scale(1.18)}}'
    + '.lm-leicht .lm-au i,.lm-leicht .lm-amb .bg{animation:none!important}'
    + '.lm-dk{position:absolute;left:0;top:0;width:1920px;height:1080px;background:linear-gradient(90deg,rgba(7,8,11,.86) 0%,rgba(7,8,11,.55) 60%,rgba(7,8,11,.4) 100%)}'
    + '.lm-v{position:absolute;left:0;top:0;width:1920px;height:1080px;opacity:0;visibility:hidden;transition:opacity .35s,visibility 0s linear .35s}'
    + '.s-start .lm-vs,.s-raster .lm-vr,.s-suche .lm-vq,.s-player .lm-vp,.s-karaoke .lm-vk{opacity:1;visibility:visible;transition:opacity .35s}'
    + '.lm-cov{position:relative;background:linear-gradient(135deg,var(--lm-f,#56637A),#15171C);background-size:cover;background-position:center;overflow:hidden}'
    + '.lm-cov span{position:absolute;left:0;top:0;width:100%;height:100%;display:flex;align-items:center;justify-content:center;color:rgba(255,255,255,.55)}'
    + '.lm-cov span svg{width:38%;height:38%}'
    + '.lm-cov.mit span{display:none}'
    // Leiste oben
    + '.lm-ob{position:absolute;left:0;top:40px;height:80px;width:1920px;transition:opacity .3s}'
    + '.s-player .lm-ob,.s-karaoke .lm-ob,.s-suche .lm-ob{opacity:0}'
    + '.lm-lu,.lm-mi,.lm-kn{position:absolute;top:0;height:76px;border-radius:38px;background:rgba(255,255,255,.1);display:flex;align-items:center;transition:background .2s,color .2s,transform .2s}'
    + '.lm-lu{left:110px;width:340px;padding:0 28px;font-size:30px;font-weight:600;color:rgba(255,255,255,.85)}'
    + '.lm-lu svg{width:34px;height:34px;margin-right:16px;flex:none}'
    + '.lm-mi{left:480px;width:700px;padding:0 24px 0 8px;display:none}'
    + '.lm-mi .lm-cov{width:60px;height:60px;border-radius:30px;flex:none;margin-right:18px}'
    + '.lm-mi p{margin:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:560px}'
    + '.lm-mi p:first-child{font-size:26px;font-weight:700}.lm-mi p+p{font-size:21px;color:rgba(255,255,255,.65)}'
    + '.lm-mi.da,.lm-kn.da{display:flex}'
    + '.lm-kn{width:76px;justify-content:center;display:none}.lm-kn svg{width:34px;height:34px}'
    + '.lm-k1{left:1200px}.lm-k2{left:1290px}.lm-k3{left:1380px}.lm-k4{left:1470px}'
    + '.lm-k4.an{color:' + GELB + '}'
    + '.lm-ob .f{background:rgba(255,255,255,.95)!important;color:#15171C!important;transform:scale(1.06)}'
    + '.lm-ob .f p+p{color:rgba(21,23,28,.7)}'
    + '.lm-eq{display:inline-flex;gap:3px;align-items:flex-end;height:20px;margin-left:12px;vertical-align:-2px}'
    + '.lm-eq b{width:4px;background:' + GELB + ';border-radius:2px;animation:lmEq .9s ease-in-out infinite}'
    + '.lm-eq b:nth-child(2){animation-delay:-.3s}.lm-eq b:nth-child(3){animation-delay:-.6s}'
    + '@keyframes lmEq{0%,100%{height:5px}50%{height:20px}}'
    + '.lm-ps .lm-eq b{animation:none;height:5px}'
    // Startseite
    + '.lm-in{position:absolute;left:110px;top:170px;width:1250px}'
    + '.lm-in .k,.lm-rk .k{margin:0;font-size:26px;font-weight:700;letter-spacing:.12em;color:' + GELB + '}'
    + '.lm-in h1{margin:10px 0 0;font-size:92px;line-height:1.02;font-weight:800;letter-spacing:-.02em;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}'
    + '.lm-in .m{margin:16px 0 0;font-size:30px;font-weight:600;color:rgba(255,255,255,.88);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}'
    + '.lm-in .t{margin:12px 0 0;font-size:27px;line-height:1.35;color:rgba(255,255,255,.72);max-width:1050px}'
    + '.lm-rh{position:absolute;left:0;width:1920px;height:300px;transition:top .3s cubic-bezier(.2,.8,.2,1),opacity .3s}'
    + '.lm-rh h3{position:absolute;left:110px;top:-50px;margin:0;font-size:30px;font-weight:650;color:rgba(255,255,255,.9)}'
    + '.lm-st{position:absolute;left:110px;top:0;height:198px;transition:transform .28s cubic-bezier(.2,.8,.2,1)}'
    + '.lm-ka{position:absolute;top:0;width:352px;height:198px;border-radius:18px;overflow:hidden;background:rgba(255,255,255,.1);'
    + 'transition:transform .24s cubic-bezier(.2,.8,.2,1),opacity .3s}'
    + '.lm-ka.f{transform:scale(1.1);box-shadow:0 22px 50px rgba(0,0,0,.6);z-index:3}'
    + '.lm-ka.v{opacity:.25}'
    + '.lm-ka .lm-cov{position:absolute;left:0;top:0;width:198px;height:198px}'
    + '.lm-ka .tx{position:absolute;left:214px;right:14px;top:20px}'
    + '.lm-ka .ti{font-size:25px;font-weight:700;line-height:1.15;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden}'
    + '.lm-ka .ia{margin-top:8px;font-size:20px;color:rgba(255,255,255,.65);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}'
    + '.lm-ka.g{background:linear-gradient(135deg,var(--lm-f),rgba(10,11,14,.9) 140%)}'
    + '.lm-ka.g svg.gr{position:absolute;right:-26px;bottom:-40px;width:190px;height:190px;color:rgba(255,255,255,.16)}'
    + '.lm-ka.g .gs{position:absolute;left:28px;top:28px;width:52px;height:52px;color:#fff}'
    + '.lm-ka.g .gs svg{width:52px;height:52px}'
    + '.lm-ka.g .gn{position:absolute;left:28px;right:28px;top:96px;font-size:34px;font-weight:800;line-height:1.05;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}'
    + '.lm-ka.g .gz{position:absolute;left:28px;top:146px;font-size:21px;font-weight:600;opacity:.8}'
    + '.lm-ka.kar{background:linear-gradient(135deg,#F2B705,#B07F00);color:#1A1400}'
    + '.lm-ka.kar .gs{color:#1A1400}.lm-ka.kar svg.gr{color:rgba(26,20,0,.16)}'
    // Matrix
    + '.lm-rk{position:absolute;left:110px;top:150px;width:1700px;height:300px;transition:opacity .3s,transform .3s}'
    + '.lm-rk .bi{position:absolute;left:0;top:0;width:170px;height:170px;border-radius:85px;box-shadow:0 14px 40px rgba(0,0,0,.5)}'
    + '.lm-rk .tx{position:absolute;left:0;top:0;width:1500px}'
    + '.lm-rk.mitbild .tx{left:204px;top:14px}'
    + '.lm-rk h1{margin:8px 0 0;font-size:72px;font-weight:800;letter-spacing:-.02em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}'
    + '.lm-rk .m{margin:6px 0 0;font-size:28px;color:rgba(255,255,255,.75)}'
    + '.lm-kr{position:absolute;left:0;top:206px;display:flex;gap:18px}'
    + '.lm-kr div{height:72px;border-radius:36px;padding:0 30px 0 22px;background:rgba(255,255,255,.12);display:flex;align-items:center;font-size:27px;font-weight:650;transition:background .2s,color .2s,transform .2s}'
    + '.lm-kr div svg{width:32px;height:32px;margin-right:14px}'
    + '.lm-kr div.f{background:#fff;color:#15171C;transform:scale(1.06)}'
    + '.lm-gi{position:absolute;left:0;top:0;width:1920px;transition:transform .3s cubic-bezier(.2,.8,.2,1)}'
    + '.lm-ze{position:absolute;transition:transform .22s cubic-bezier(.2,.8,.2,1)}'
    + '.lm-ze .lm-cov{border-radius:16px}'
    + '.lm-ze p{margin:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}'
    + '.lm-ze .ti{margin-top:12px;font-size:22px;font-weight:700}.lm-ze .ia{font-size:19px;color:rgba(255,255,255,.62)}'
    + '.lm-ze.f{transform:scale(1.08);z-index:3}.lm-ze.f .lm-cov{box-shadow:0 0 0 4px #fff,0 20px 44px rgba(0,0,0,.6)}'
    + '.lm-ze.kue .lm-cov{border-radius:50%}.lm-ze.kue p{text-align:center}'
    + '.lm-ze.ka.f{transform:scale(1.07)}.lm-ze.ka.f .lm-ka{box-shadow:0 0 0 4px #fff,0 20px 44px rgba(0,0,0,.6)}'
    + '.lm-ze .lm-ka{position:relative}'
    // Suche
    + '.lm-sf{position:absolute;left:110px;top:60px;width:760px;height:88px;border-radius:44px;background:rgba(255,255,255,.12);display:flex;align-items:center;padding:0 32px;font-size:38px;font-weight:600}'
    + '.lm-sf svg{width:38px;height:38px;margin-right:18px;flex:none;opacity:.8}'
    + '.lm-sf .lt{color:rgba(255,255,255,.45)}'
    + '.lm-sf .cu{display:inline-block;width:3px;height:42px;background:' + GELB + ';margin-left:3px;vertical-align:-8px;animation:lmCu 1s steps(2) infinite}'
    + '@keyframes lmCu{50%{opacity:0}}'
    + '.lm-ta{position:absolute;left:110px;top:190px}'
    + '.lm-ta b{position:absolute;height:84px;border-radius:20px;background:rgba(255,255,255,.1);font-size:34px;font-weight:650;display:flex;align-items:center;justify-content:center;transition:background .15s,color .15s,transform .15s}'
    + '.lm-ta b.f{background:#fff;color:#15171C;transform:scale(1.06)}'
    + '.lm-tr{position:absolute;left:900px;top:60px;width:920px;height:1020px;overflow:hidden}'
    + '.lm-tr .h{position:absolute;left:0;top:28px;font-size:30px;color:rgba(255,255,255,.6)}'
    + '.lm-tl{position:absolute;left:0;top:130px;width:920px;transition:transform .25s}'
    + '.lm-te{position:absolute;width:445px;height:112px;border-radius:18px;background:rgba(255,255,255,.08);overflow:hidden;transition:transform .2s}'
    + '.lm-te .lm-cov{position:absolute;left:0;top:0;width:112px;height:112px}'
    + '.lm-te p{position:absolute;left:130px;right:16px;margin:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}'
    + '.lm-te .ti{top:24px;font-size:25px;font-weight:700}.lm-te .ia{top:62px;font-size:20px;color:rgba(255,255,255,.62)}'
    + '.lm-te.f{background:rgba(255,255,255,.95);color:#15171C;transform:scale(1.04);z-index:2}.lm-te.f .ia{color:rgba(21,23,28,.65)}'
    // Player
    + '.lm-pn0{transition:opacity .6s}'
    + '.lm-ruhe .lm-pn0{opacity:0}'
    + '.lm-pc{position:absolute;left:110px;top:170px;width:560px;height:560px;border-radius:32px;box-shadow:0 30px 80px rgba(0,0,0,.6)}'
    + '.lm-pa{position:absolute;left:750px;top:206px;font-size:26px;font-weight:700;letter-spacing:.14em;color:' + GELB + '}'
    + '.lm-pt{position:absolute;left:750px;top:252px;width:1060px;font-size:72px;font-weight:800;line-height:1.08;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}'
    + '.lm-pn{position:absolute;left:750px;width:1060px;font-size:40px;font-weight:500;color:rgba(255,255,255,.88);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}'
    + '.lm-pl{position:absolute;left:750px;width:1060px;font-size:30px;color:rgba(255,255,255,.55);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}'
    + '.lm-pb{position:absolute;left:750px;top:640px;width:1060px;height:10px;border-radius:5px;background:rgba(255,255,255,.18)}'
    + '.lm-pb i{position:absolute;left:0;top:0;height:10px;border-radius:5px;background:' + GELB + '}'
    + '.lm-pz{position:absolute;top:664px;font-size:28px;color:rgba(255,255,255,.75)}'
    + '.lm-pk{position:absolute;left:750px;top:722px;display:flex;align-items:center;gap:20px}'
    + '.lm-pk div{height:84px;min-width:84px;border-radius:42px;background:rgba(255,255,255,.12);display:flex;align-items:center;justify-content:center;'
    + 'padding:0 26px;font-size:26px;font-weight:650;transition:background .2s,color .2s,transform .2s}'
    + '.lm-pk div svg{width:36px;height:36px;flex:none}.lm-pk div span{margin-left:12px}'
    + '.lm-pk div.gross{height:100px;min-width:100px;border-radius:50px}.lm-pk div.gross svg{width:44px;height:44px}'
    + '.lm-pk div.an{color:' + GELB + '}'
    + '.lm-pk div.f{background:#fff;color:#15171C;transform:scale(1.08)}.lm-pk div.f.an{color:#B07F00}'
    + '.lm-nx{position:absolute;left:110px;top:868px;width:1700px}'
    + '.lm-nx h3{margin:0 0 16px;font-size:24px;font-weight:700;letter-spacing:.1em;color:rgba(255,255,255,.55)}'
    + '.lm-nr{display:flex;gap:26px}'
    + '.lm-ne{position:relative;width:400px;height:100px;border-radius:20px;background:rgba(255,255,255,.07);overflow:hidden}'
    + '.lm-ne .lm-cov{position:absolute;left:0;top:0;width:100px;height:100px}'
    + '.lm-ne p{position:absolute;left:118px;right:16px;margin:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}'
    + '.lm-ne p:first-of-type{top:20px;font-size:27px;font-weight:700}.lm-ne p:last-of-type{top:56px;font-size:21px;color:rgba(255,255,255,.6)}'
    // Ruhe-Ansicht im Player: großes Künstlerbild, langsam heranzoomend
    + '.lm-amb{position:absolute;left:0;top:0;width:1920px;height:1080px;opacity:0;transition:opacity 1s;overflow:hidden}'
    + '.lm-ruhe .lm-amb{opacity:1}'
    + '.lm-amb .bg{position:absolute;left:-60px;top:-60px;width:2040px;height:1200px;background-size:cover;background-position:center 25%;animation:lmKb 40s ease-in-out infinite alternate}'
    + '.lm-amb .bg.weich{filter:blur(28px);transform:scale(1.15);animation:none}'
    + '@keyframes lmKb{0%{transform:scale(1) translate(0,0)}100%{transform:scale(1.14) translate(-40px,-20px)}}'
    + '.lm-amb .sch{position:absolute;left:0;top:0;width:1920px;height:1080px;background:linear-gradient(0deg,rgba(7,8,11,.92) 0%,rgba(7,8,11,.35) 45%,rgba(7,8,11,.1) 70%,rgba(7,8,11,.45) 100%)}'
    + '.lm-amb .lm-cov{position:absolute;left:110px;top:640px;width:300px;height:300px;border-radius:24px;box-shadow:0 24px 70px rgba(0,0,0,.7)}'
    + '.lm-amb h2{position:absolute;left:460px;bottom:224px;width:1350px;margin:0;font-size:84px;font-weight:800;line-height:1.05;letter-spacing:-.02em;'
    + 'display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;text-shadow:0 4px 30px rgba(0,0,0,.5)}'
    + '.lm-amb p{position:absolute;left:460px;top:874px;margin:0;font-size:40px;color:rgba(255,255,255,.85);text-shadow:0 2px 16px rgba(0,0,0,.6)}'
    + '.lm-amb .lb{position:absolute;left:110px;top:1000px;width:1700px;height:6px;border-radius:3px;background:rgba(255,255,255,.2)}'
    + '.lm-amb .lb i{position:absolute;left:0;top:0;height:6px;border-radius:3px;background:' + GELB + '}'
    + '.lm-ht{position:absolute;right:110px;top:66px;font-size:24px;color:rgba(255,255,255,.55);text-align:right}'
    + '.lm-ht b{color:#fff;font-weight:700;padding:2px 10px;border-radius:8px;background:rgba(255,255,255,.12);margin:0 4px}'
    // Karaoke
    + '.lm-kp{position:absolute;left:110px;top:60px;display:flex;align-items:center;gap:24px}'
    + '.lm-kp .lm-cov{width:96px;height:96px;border-radius:18px;flex:none}'
    + '.lm-kp p{margin:0;white-space:nowrap;max-width:1200px;overflow:hidden;text-overflow:ellipsis}'
    + '.lm-kp p:first-of-type{font-size:40px;font-weight:800}.lm-kp p:last-of-type{font-size:28px;color:rgba(255,255,255,.7)}'
    + '.lm-cn{position:absolute;left:0;top:0;width:1920px;height:1080px}'
    // eigene Leiste (TCL)
    + '.lm-ld{position:absolute;left:0;top:0;width:1920px;height:1080px;background:#000;opacity:0;transition:opacity .3s;pointer-events:none}'
    + '.lm-leiste-auf .lm-ld{opacity:.45}'
    + '.lm-le{position:absolute;left:40px;top:40px;width:420px;height:1000px;border-radius:48px;background:rgba(36,38,46,.94);'
    + 'box-shadow:0 30px 80px rgba(0,0,0,.5);transform:translate3d(-480px,0,0);opacity:0;transition:transform .38s cubic-bezier(.2,.8,.2,1),opacity .25s}'
    + '.lm-leiste-auf .lm-le{transform:none;opacity:1}'
    + '.lm-le .pil{position:absolute;left:22px;right:22px;height:80px;border-radius:40px;background:rgba(255,255,255,.95);transition:top .28s cubic-bezier(.2,.8,.2,1)}'
    + '.lm-le .ein{position:absolute;left:0;right:0;height:80px;color:#fff;transition:color .2s}'
    + '.lm-le .ein.f{color:#15171C}'
    + '.lm-le .ein svg{position:absolute;left:52px;top:20px;width:40px;height:40px}'
    + '.lm-le .ein span{position:absolute;left:114px;top:0;line-height:80px;font-size:36px;font-weight:650}'
    + '.lm-le .pu{position:absolute;left:355px;width:14px;height:14px;border-radius:7px;background:' + GELB + '}'
    + '.lm-le .lin{position:absolute;left:52px;right:52px;height:2px;background:rgba(255,255,255,.14)}'
    + '.lm-hw{position:absolute;left:50%;bottom:120px;transform:translateX(-50%);padding:18px 34px;border-radius:999px;'
    + 'background:rgba(20,22,28,.92);font-size:30px;font-weight:600;opacity:0;transition:opacity .3s;white-space:nowrap;z-index:20}'
    + '.lm-hw.an{opacity:1}'
    + '.s-karaoke .lm-hw{bottom:auto;top:150px}';

  function bauen() {
    if (M.el) return;
    const st = document.createElement('style');
    st.textContent = CSS;
    document.head.appendChild(st);
    const w = document.createElement('div');
    w.className = 'lm-w s-start';
    w.innerHTML = '<div class="lm-b">'
      + '<div class="lm-au"><i></i><i></i><i></i></div><div class="lm-dk"></div>'
      + '<div class="lm-v lm-vs"><div class="lm-in"></div><div class="lm-rs"></div></div>'
      + '<div class="lm-v lm-vr"><div class="lm-rk"></div><div class="lm-gi"></div></div>'
      + '<div class="lm-v lm-vq"><div class="lm-sf">' + svg(SYM.lupe) + '<span class="sx"></span></div><div class="lm-ta"></div>'
      + '<div class="lm-tr"><div class="h"></div><div class="lm-tl"></div></div></div>'
      + '<div class="lm-v lm-vp"><div class="lm-amb"><div class="bg"></div><div class="sch"></div>' + coverHtml() + '<h2></h2><p></p><div class="lb"><i></i></div></div>'
      + '<div class="lm-pn0">' + coverHtml('lm-pc')
      + '<div class="lm-pa"></div><div class="lm-pt"></div><div class="lm-pn"></div><div class="lm-pl"></div>'
      + '<div class="lm-pb"><i></i></div><div class="lm-pz" style="left:750px"></div><div class="lm-pz" style="right:110px"></div>'
      + '<div class="lm-pk"></div><div class="lm-nx"><h3>ALS NÄCHSTES</h3><div class="lm-nr"></div></div></div></div>'
      + '<div class="lm-v lm-vk"><canvas class="lm-cn" width="1920" height="1080"></canvas>'
      + '<div class="lm-kp">' + coverHtml() + '<div><p></p><p></p></div></div><div class="lm-ht lm-kh"></div></div>'
      + '<div class="lm-ob"><div class="lm-lu">' + svg(SYM.lupe) + 'Lieder suchen</div>'
      + '<div class="lm-mi">' + coverHtml() + '<div><p></p><p></p></div></div>'
      + '<div class="lm-kn lm-k1">' + svg(SYM.zurueck) + '</div><div class="lm-kn lm-k2">' + svg(SYM.pause) + '</div>'
      + '<div class="lm-kn lm-k3">' + svg(SYM.vor) + '</div><div class="lm-kn lm-k4">' + svg(SYM.herz) + '</div></div>'
      + '<div class="lm-ld"></div><div class="lm-le"></div>'
      + '<div class="lm-hw"></div>'
      + '</div>';
    document.body.appendChild(w);
    const q = function (s) { return w.querySelector(s); };
    M.el = {
      w: w, b: q('.lm-b'), info: q('.lm-in'), reihen: q('.lm-rs'), rk: q('.lm-rk'), gitter: q('.lm-gi'),
      sx: q('.lm-sf .sx'), tasten: q('.lm-ta'), trH: q('.lm-tr .h'), trL: q('.lm-tl'),
      ambBg: q('.lm-amb .bg'), ambCov: q('.lm-amb .lm-cov'), ambT: q('.lm-amb h2'), ambI: q('.lm-amb p'), ambB: q('.lm-amb .lb i'),
      pc: q('.lm-pc'), pa: q('.lm-pa'), pt: q('.lm-pt'), pn: q('.lm-pn'), pl: q('.lm-pl'), pb: q('.lm-pb i'),
      pz1: w.querySelectorAll('.lm-pz')[0], pz2: w.querySelectorAll('.lm-pz')[1], pk: q('.lm-pk'), nr: q('.lm-nr'),
      cn: q('.lm-cn'), kpCov: q('.lm-kp .lm-cov'), kpT: q('.lm-kp p'), kpI: w.querySelectorAll('.lm-kp p')[1], kh: q('.lm-kh'),
      lu: q('.lm-lu'), mi: q('.lm-mi'), miCov: q('.lm-mi .lm-cov'), miT: q('.lm-mi p'), miI: w.querySelectorAll('.lm-mi p')[1],
      k1: q('.lm-k1'), k2: q('.lm-k2'), k3: q('.lm-k3'), k4: q('.lm-k4'), le: q('.lm-le'), hw: q('.lm-hw')
    };
    M.ctx = M.el.cn.getContext('2d');
    tastaturBauen();
    window.addEventListener('resize', passen);
    passen();
  }

  function passen() {
    if (!M.el) return;
    const w = window.innerWidth || STAGE_W, h = window.innerHeight || STAGE_H;
    const s = Math.min(w / STAGE_W, h / STAGE_H);
    M.el.b.style.transform = 'translate(' + (w - STAGE_W * s) / 2 + 'px,' + (h - STAGE_H * s) / 2 + 'px) scale(' + s + ')';
  }

  function hinweis(t, ms) {
    if (!M.el) return;
    M.el.hw.textContent = t;
    M.el.hw.classList.add('an');
    clearTimeout(M.hinweisTimer);
    M.hinweisTimer = setTimeout(function () { M.el.hw.classList.remove('an'); }, ms || 2200);
  }

  function stimmung(farbe) {
    if (!M.el) return;
    const s = M.el.w.style;
    s.setProperty('--lm-a1', farbe || GELB);
    s.setProperty('--lm-a2', farbe === GELB ? '#E8559A' : GELB);
    s.setProperty('--lm-a3', farbe === '#6A3DE8' ? '#1592C4' : '#6A3DE8');
  }

  function sichtSetzen(s) {
    if (M.sicht === 'player' || M.sicht === 'karaoke') M.letzteWiedergabe = M.sicht;
    M.sicht = s;
    const c = M.el.w.classList;
    ['s-start', 's-raster', 's-suche', 's-player', 's-karaoke'].forEach(function (k) { c.remove(k); });
    c.add('s-' + s);
    c.remove('lm-ruhe');
    if (s === 'player') ruhePlanen();
    if (s === 'start' || s === 'raster') obenZeichnen();
    schleife();
  }

  // ------------------------------------------------------------------ Startseite

  function liedKarte(e) {
    const d = document.createElement('div');
    d.className = 'lm-ka';
    d.innerHTML = coverHtml() + '<div class="tx"><div class="ti"></div><div class="ia"></div></div>';
    d.querySelector('.ti').textContent = e.Name || '';
    d.querySelector('.ia').textContent = interpret(e);
    d._cov = d.querySelector('.lm-cov');
    d._cov.style.setProperty('--lm-f', gruppenFarbe(e._gruppe));
    return d;
  }

  function sonderKarte(e) {
    const d = document.createElement('div');
    d.className = 'lm-ka g' + (e.art === 'karaoke' ? ' kar' : '');
    d.style.setProperty('--lm-f', e.farbe || '#56637A');
    d.innerHTML = '<span class="gs">' + svg(e.sym || SYM.note) + '</span>' + svg(SYM.note, 'gr') + '<div class="gn"></div><div class="gz"></div>';
    d.querySelector('.gn').textContent = e.name;
    d.querySelector('.gz').textContent = e.zahl;
    return d;
  }

  function gruppenListe(lieder) {
    const g = gehoert();
    const gr = {};
    lieder.forEach(function (e) {
      const x = gr[e._gruppe] = gr[e._gruppe] || { name: e._gruppe, liste: [], spiele: 0 };
      x.liste.push(e);
      x.spiele += spiele(e, g);
    });
    return Object.keys(gr).map(function (k) { return gr[k]; }).sort(function (a, b) {
      if (a.name === SONSTIGES[0]) return 1;
      if (b.name === SONSTIGES[0]) return -1;
      return (b.spiele - a.spiele) || (b.liste.length - a.liste.length);
    });
  }

  async function startAufbauen() {
    const k = await katalog(null);
    const lieder = k.lieder;
    const g = gehoert();
    const kar = lieder.filter(function (e) { return e._karaoke; });
    const fav = lieder.filter(geliked);
    const gruppen = gruppenListe(lieder);
    const kacheln = [
      { art: 'karaoke', name: 'Karaoke-Modus', zahl: kar.length ? kar.length + ' Lieder zum Mitsingen' : 'kommt bald', farbe: GELB, sym: SYM.mikro },
      { art: 'fav', name: 'Gelikte Songs', zahl: fav.length ? anzahl(fav.length) : 'Im Player aufs Herz drücken', farbe: '#E8559A', sym: SYM.herzVoll }
    ];
    gruppen.forEach(function (x) {
      kacheln.push({ art: 'genre', name: x.name, zahl: anzahl(x.liste.length), farbe: gruppenFarbe(x.name), liste: x.liste, sym: SYM.note });
    });
    kacheln.push({ art: 'alle', name: 'Alle Lieder', zahl: anzahl(lieder.length), farbe: '#56637A', liste: lieder, sym: SYM.alle });
    kacheln.push({ art: 'mix', name: 'Alles mischen', zahl: 'Zufall über alles', farbe: '#3E4A5E', sym: SYM.mischen });
    const reihen = [{ titel: 'Entdecken', sonder: true, eintraege: kacheln }];
    const gespielt = lieder.filter(function (e) { return spiele(e, g) > 0; });
    if (gespielt.length >= 3) reihen.push({ titel: 'Am meisten gehört', eintraege: nachWert(gespielt).slice(0, 30) });
    else {
      const hits = lieder.filter(function (e) { return e._rang > 0; }).sort(function (a, b) { return b._rang - a._rang; }).slice(0, 30);
      if (hits.length) reihen.push({ titel: 'Beliebte Hits', eintraege: hits });
    }
    const zul = (speicher('lm_zuletzt') || []).map(function (id) { return M.nachId[id]; })
      .filter(function (e, i, a) { return e && a.indexOf(e) === i; });
    if (zul.length) reihen.push({ titel: 'Zuletzt gehört', eintraege: zul });
    gruppen.filter(function (x) { return x.name !== SONSTIGES[0] && x.liste.length >= 4; }).slice(0, 4).forEach(function (x) {
      reihen.push({ titel: x.name, eintraege: nachWert(x.liste).slice(0, 30) });
    });
    const neu = lieder.slice().sort(function (a, b) { return (b.DateCreated || '').localeCompare(a.DateCreated || ''); }).slice(0, 30);
    if (neu.length) reihen.push({ titel: 'Neu dazugekommen', eintraege: neu });
    const alt = M.reihen;
    reihen.forEach(function (r, i) {
      r.fokus = alt[i] && alt[i].titel === r.titel ? Math.min(alt[i].fokus || 0, r.eintraege.length - 1) : 0;
    });
    M.reihen = reihen;
    M.akt = Math.max(0, Math.min(M.akt, reihen.length - 1));
    startZeichnen(true);
  }

  function startZeichnen(neu) {
    const box = M.el.reihen;
    if (neu) {
      box.innerHTML = '';
      M.reihen.forEach(function (r) {
        const rh = document.createElement('div');
        rh.className = 'lm-rh';
        rh.innerHTML = '<h3></h3><div class="lm-st"></div>';
        rh.querySelector('h3').textContent = r.titel;
        const st = rh.querySelector('.lm-st');
        r.karten = r.eintraege.map(function (e, i) {
          const d = r.sonder ? sonderKarte(e) : liedKarte(e);
          d.style.left = (i * (KW + ABST)) + 'px';
          st.appendChild(d);
          return d;
        });
        r.el = rh;
        r.st = st;
        box.appendChild(rh);
      });
    }
    M.reihen.forEach(function (r, i) {
      const d = i - M.akt;
      r.el.style.top = (580 + d * 300) + 'px';
      r.el.style.opacity = d < 0 ? '0' : d === 0 ? '1' : d === 1 ? '.55' : '0';
      r.st.style.transform = 'translate3d(' + (-r.fokus * (KW + ABST)) + 'px,0,0)';
      r.karten.forEach(function (k, j) {
        k.classList.toggle('f', d === 0 && j === r.fokus && M.kopf < 0 && !M.leiste);
        k.classList.toggle('v', j < r.fokus);
        if (d >= 0 && d <= 1 && j >= r.fokus - 1 && j <= r.fokus + 6 && k._cov && !k._cov._geladen) {
          k._cov._geladen = true;
          coverSetzen(k._cov, r.eintraege[j], 300);
        }
      });
    });
    infoZeichnen();
  }

  function infoZeichnen() {
    const r = M.reihen[M.akt];
    const e = r && r.eintraege[r.fokus];
    let html = '';
    if (!e) html = '<p class="k">MUSIK</p><h1>Noch keine Musik</h1><p class="m">Lege Lieder in den Ordner musik auf dem Pi.</p>';
    else if (r.sonder) {
      const texte = {
        karaoke: 'Mitsingen wie im Club: oben die ganze Zeile zum Mitlesen, unten die Zeitspur. Du wählst gleich, was gesungen wird – alles, Hits, Deutsch, Rock …',
        fav: 'Alle Lieder, die du mit dem Herz markiert hast – nur für dein Profil.',
        alle: 'Alle Lieder als große Übersicht zum Stöbern.',
        mix: 'Einfach laufen lassen – alles bunt gemischt, ohne Wiederholungen.',
        genre: 'Erst die Künstler – oben links die, die du am meisten hörst. Oben drüber: ' + e.name + ' mischen oder Karaoke.'
      };
      html = '<p class="k">' + (e.art === 'genre' ? 'GENRE' : 'MUSIK') + '</p><h1>' + esc(e.name) + '</h1><p class="m">' + esc(e.zahl)
        + '</p><p class="t">' + esc(texte[e.art] || '') + '</p>';
      stimmung(e.farbe);
    } else {
      const teile = [interpret(e), e.Album, e.ProductionYear > 0 ? e.ProductionYear : '', dauerText(e.RunTimeTicks)].filter(Boolean);
      html = '<p class="k">' + esc((e._gruppe || 'Musik').toUpperCase()) + '</p><h1>' + esc(e.Name) + '</h1><p class="m">'
        + esc(teile.join('  ·  ')) + '</p>';
      stimmung(gruppenFarbe(e._gruppe));
    }
    M.el.info.innerHTML = html;
  }

  function startTaste(n) {
    if (M.kopf >= 0) return obenTaste(n);
    const r = M.reihen[M.akt];
    if (!r) {
      if (n === 'links' || n === 'zurueck') leisteAuf();
      else if (n === 'hoch') {
        M.kopf = 0;
        obenZeichnen();
      } else wiedergabeTaste(n);
      return;
    }
    switch (n) {
      case 'links':
        if (r.fokus > 0) r.fokus--;
        else return leisteAuf();
        break;
      case 'rechts':
        r.fokus = Math.min(r.eintraege.length - 1, r.fokus + 1);
        break;
      case 'hoch':
        if (M.akt > 0) M.akt--;
        else M.kopf = 0;
        break;
      case 'runter':
        M.akt = Math.min(M.reihen.length - 1, M.akt + 1);
        break;
      case 'ok':
        return startOeffnen(r, r.eintraege[r.fokus]);
      case 'zurueck':
        if (r.fokus > 0 || M.akt > 0) {
          r.fokus = 0;
          M.akt = 0;
        } else return leisteAuf();
        break;
      default:
        return wiedergabeTaste(n);
    }
    neuZeichnen();
  }

  function startOeffnen(r, e) {
    if (!e) return;
    if (!r.sonder) return abspielen(r.eintraege, r.fokus, false);
    const lieder = M.katalog.lieder;
    if (e.art === 'karaoke') return karaokeAuswahl();
    if (e.art === 'mix') return abspielen(lieder, 0, true);
    if (e.art === 'fav') return liederOeffnen({ kopf: 'MUSIK', titel: 'Gelikte Songs', farbe: '#E8559A', liste: lieder.filter(geliked),
      leer: 'Noch nichts geliked – im Player auf das Herz drücken.' });
    if (e.art === 'alle') return liederOeffnen({ kopf: 'MUSIK', titel: 'Alle Lieder', farbe: '#56637A', liste: lieder, abc: true });
    if (e.art === 'genre') return genreOeffnen(e.name, e.farbe, e.liste);
  }

  // ------------------------------------------------------------------ Leiste oben (Lupe, Mini-Player)

  function obenZeichnen() {
    if (!M.el) return;
    const e = aktuell();
    const da = !!(e && M.audio && M.audio.getAttribute('src'));
    M.kopfZiele = da ? ['lupe', 'mini', 'zurueck', 'pause', 'vor', 'herz'] : ['lupe'];
    if (M.kopf >= M.kopfZiele.length) M.kopf = M.kopfZiele.length - 1;
    [M.el.mi, M.el.k1, M.el.k2, M.el.k3, M.el.k4].forEach(function (x) { x.classList.toggle('da', da); });
    if (da) {
      if (M.el.mi._id !== e.Id) {
        M.el.mi._id = e.Id;
        coverSetzen(M.el.miCov, e, 120);
      }
      M.el.miT.innerHTML = esc(e.Name) + '<span class="lm-eq"><b></b><b></b><b></b></span>';
      M.el.miI.textContent = interpret(e);
      M.el.k2.innerHTML = svg(M.audio.paused ? SYM.play : SYM.pause);
      M.el.k4.innerHTML = svg(geliked(e) ? SYM.herzVoll : SYM.herz);
      M.el.k4.classList.toggle('an', geliked(e));
    }
    const ziele = { lupe: M.el.lu, mini: M.el.mi, zurueck: M.el.k1, pause: M.el.k2, vor: M.el.k3, herz: M.el.k4 };
    Object.keys(ziele).forEach(function (k) {
      ziele[k].classList.toggle('f', M.kopf >= 0 && M.kopfZiele[M.kopf] === k && !M.leiste);
    });
  }

  function obenTaste(n) {
    const z = M.kopfZiele[M.kopf];
    if (n === 'links') {
      if (M.kopf > 0) M.kopf--;
      else return leisteAuf();
    } else if (n === 'rechts') M.kopf = Math.min(M.kopfZiele.length - 1, M.kopf + 1);
    else if (n === 'runter' || n === 'zurueck') M.kopf = -1;
    else if (n === 'ok') {
      if (z === 'lupe') return sucheOeffnen();
      if (z === 'mini') return wiedergabeOeffnen();
      if (z === 'zurueck') weiter(-1);
      if (z === 'vor') weiter(1);
      if (z === 'pause') umschalten();
      if (z === 'herz') liken();
    } else if (n !== 'hoch') return wiedergabeTaste(n);
    neuZeichnen();
  }

  function neuZeichnen() {
    if (M.sicht === 'start') startZeichnen(false);
    else if (M.sicht === 'raster') rasterZeichnen();
    obenZeichnen();
  }

  // ------------------------------------------------------------------ Matrix (Künstler, Lieder, Karaoke-Auswahl)

  /** R: {art, kopf, titel, meta, bild?, farbe, gruppe?, knoepfe:[{text, sym, tun}], eintraege, leer?} */
  function rasterZeigen(R, alsNeu) {
    if (M.sicht === 'raster' && M.raster && alsNeu) M.stapel.push(M.raster);
    else if (M.sicht !== 'raster') {
      M.stapel = [];
      M.rasterVon = M.sicht === 'suche' ? 'suche' : 'start';
    }
    R.fokus = R.fokus || 0;
    R.knopf = R.eintraege.length ? -1 : (R.knoepfe.length ? 0 : -1);
    R.zeilen = {};
    M.raster = R;
    M.kopf = -1;
    rasterKopfBauen();
    M.el.gitter.innerHTML = '';
    stimmung(R.farbe);
    sichtSetzen('raster');
    rasterZeichnen();
  }

  function rasterKopfBauen() {
    const R = M.raster;
    M.el.rk.className = 'lm-rk' + (R.bild !== undefined ? ' mitbild' : '');
    let html = '';
    if (R.bild !== undefined) html += coverHtml('bi', SYM.person);
    html += '<div class="tx"><p class="k">' + esc(R.kopf) + '</p><h1>' + esc(R.titel) + '</h1><p class="m">' + esc(R.meta || '') + '</p></div>';
    html += '<div class="lm-kr">' + R.knoepfe.map(function (k) { return '<div>' + svg(k.sym) + esc(k.text) + '</div>'; }).join('') + '</div>';
    if (!R.eintraege.length && R.leer) html += '<p class="m" style="position:absolute;left:0;top:320px;font-size:32px">' + esc(R.leer) + '</p>';
    M.el.rk.innerHTML = html;
    if (R.bild !== undefined) coverSetzen(M.el.rk.querySelector('.bi'), { _gruppe: R.gruppe }, 0, R.bild);
  }

  function rasterZeichnen() {
    const R = M.raster;
    if (!R) return;
    const ms = MASS[R.art];
    const n = R.eintraege.length;
    const zeile = R.knopf >= 0 ? 0 : Math.floor(R.fokus / ms.sp);
    const zeilen = Math.ceil(n / ms.sp);
    const start = R.knoepfe.length ? 490 : 400;
    const versatz = zeile === 0 ? 0 : (start - 160) + (zeile - 1) * ms.zeile;
    M.el.gitter.style.transform = 'translate3d(0,' + (start - versatz) + 'px,0)';
    M.el.rk.style.opacity = zeile > 0 ? '0' : '1';
    M.el.rk.style.transform = 'translate3d(0,' + (zeile > 0 ? -120 : 0) + 'px,0)';
    Array.prototype.forEach.call(M.el.rk.querySelectorAll('.lm-kr div'), function (d, i) {
      d.classList.toggle('f', R.knopf === i && M.kopf < 0 && !M.leiste);
    });
    // nur die Zeilen um den Fokus herum als Kacheln (bei tausenden Liedern sonst zu viel für den Fernseher)
    const von = Math.max(0, zeile - 2), bis = Math.min(zeilen - 1, zeile + 4);
    Object.keys(R.zeilen).forEach(function (z) {
      z = +z;
      if (z < von || z > bis) {
        R.zeilen[z].forEach(function (d) { d.remove(); });
        delete R.zeilen[z];
      }
    });
    for (let z = von; z <= bis; z++) {
      if (R.zeilen[z]) continue;
      R.zeilen[z] = [];
      for (let s = 0; s < ms.sp; s++) {
        const i = z * ms.sp + s;
        if (i >= n) break;
        const d = zelleBauen(R, R.eintraege[i]);
        d.style.left = (LINKS + s * (ms.w + ms.luecke)) + 'px';
        d.style.top = (z * ms.zeile) + 'px';
        d._i = i;
        M.el.gitter.appendChild(d);
        R.zeilen[z].push(d);
      }
    }
    Object.keys(R.zeilen).forEach(function (z) {
      R.zeilen[z].forEach(function (d) { d.classList.toggle('f', d._i === R.fokus && R.knopf < 0 && M.kopf < 0 && !M.leiste); });
    });
  }

  function zelleBauen(R, e) {
    const ms = MASS[R.art];
    const d = document.createElement('div');
    if (R.art === 'wahl') {
      d.className = 'lm-ze ka';
      d.appendChild(sonderKarte(e));
      return d;
    }
    d.className = 'lm-ze' + (R.art === 'kuenstler' ? ' kue' : '');
    d.style.width = ms.w + 'px';
    d.innerHTML = coverHtml('', R.art === 'kuenstler' ? SYM.person : SYM.note) + '<p class="ti"></p><p class="ia"></p>';
    const cov = d.querySelector('.lm-cov');
    cov.style.width = ms.w + 'px';
    cov.style.height = ms.h + 'px';
    if (R.art === 'kuenstler') {
      d.querySelector('.ti').textContent = e.name;
      d.querySelector('.ia').textContent = anzahl(e.liste.length);
      coverSetzen(cov, e.liste[0], 300, e.bild || bildUrl(e.liste[0], 300));
    } else {
      d.querySelector('.ti').textContent = e.Name || '';
      d.querySelector('.ia').textContent = R.ohneInterpret ? (e.Album || dauerText(e.RunTimeTicks)) : interpret(e);
      coverSetzen(cov, e, 300);
    }
    return d;
  }

  function rasterTaste(n) {
    if (M.kopf >= 0) return obenTaste(n);
    const R = M.raster;
    const ms = MASS[R.art];
    const anz = R.eintraege.length;
    if (R.knopf >= 0) {
      if (n === 'links') {
        if (R.knopf > 0) R.knopf--;
        else return leisteAuf();
      } else if (n === 'rechts') R.knopf = Math.min(R.knoepfe.length - 1, R.knopf + 1);
      else if (n === 'runter') { if (anz) R.knopf = -1; }
      else if (n === 'hoch') M.kopf = 0;
      else if (n === 'ok') return R.knoepfe[R.knopf].tun();
      else if (n === 'zurueck') return rasterZurueck();
      else return wiedergabeTaste(n);
      return neuZeichnen();
    }
    const s = R.fokus % ms.sp;
    switch (n) {
      case 'links':
        if (s > 0) R.fokus--;
        else return leisteAuf();
        break;
      case 'rechts':
        if (s < ms.sp - 1 && R.fokus + 1 < anz) R.fokus++;
        break;
      case 'hoch':
        if (R.fokus >= ms.sp) R.fokus -= ms.sp;
        else if (R.knoepfe.length) R.knopf = 0;
        else M.kopf = 0;
        break;
      case 'runter':
        if (R.fokus + ms.sp < anz) R.fokus += ms.sp;
        else if (Math.floor(R.fokus / ms.sp) < Math.floor((anz - 1) / ms.sp)) R.fokus = anz - 1;
        break;
      case 'ok':
        return rasterOk(R, R.eintraege[R.fokus]);
      case 'zurueck':
        return rasterZurueck();
      default:
        return wiedergabeTaste(n);
    }
    neuZeichnen();
  }

  function rasterZurueck() {
    if (M.stapel.length) {
      M.raster = M.stapel.pop();
      M.raster.zeilen = {};
      M.el.gitter.innerHTML = '';
      rasterKopfBauen();
      stimmung(M.raster.farbe);
      sichtSetzen('raster');
      rasterZeichnen();
      return;
    }
    M.raster = null;
    if (M.rasterVon === 'suche') {
      sichtSetzen('suche');
      return sucheZeichnen(false);
    }
    sichtSetzen('start');
    if (M.startVeraltet) {
      M.startVeraltet = false;
      startAufbauen();
    } else startZeichnen(false);
  }

  function rasterOk(R, e) {
    if (!e) return;
    if (R.art === 'wahl') return karaokeStarten(e.liste, e.name);
    if (R.art === 'kuenstler') return kuenstlerOeffnen(e, R.farbe);
    abspielen(R.eintraege, R.fokus, false);
  }

  function genreOeffnen(name, farbe, liste) {
    const g = gehoert();
    const nachKuenstler = {};
    liste.forEach(function (e) { (nachKuenstler[e._kuenstler] = nachKuenstler[e._kuenstler] || []).push(e); });
    const kuenstler = Object.keys(nachKuenstler).map(function (k) {
      const l = nachWert(nachKuenstler[k]);
      const info = M.kuenstler[k] || {};
      return { name: k, liste: l, bild: kuenstlerBild(k), spiele: l.reduce(function (a, e) { return a + spiele(e, g); }, 0), fans: info.fans || 0 };
    }).sort(function (a, b) {
      return (b.spiele - a.spiele) || (b.fans - a.fans) || (b.liste.length - a.liste.length) || a.name.localeCompare(b.name, 'de');
    });
    const kar = liste.filter(function (e) { return e._karaoke; });
    const knoepfe = [{ text: name + ' mischen', sym: SYM.mischen, tun: function () { abspielen(liste, 0, true); } }];
    if (kar.length) knoepfe.push({ text: 'Karaoke: ' + name, sym: SYM.mikro, tun: function () { karaokeStarten(kar, name); } });
    knoepfe.push({ text: 'Alle Lieder', sym: SYM.alle, tun: function () {
      liederOeffnen({ kopf: name.toUpperCase(), titel: 'Alle Lieder', farbe: farbe, liste: liste }, true);
    } });
    rasterZeigen({ art: 'kuenstler', kopf: 'GENRE', titel: name, farbe: farbe, gruppe: name,
      meta: kuenstler.length + ' Künstler  ·  ' + anzahl(liste.length) + '  ·  oben links, was du am meisten hörst',
      knoepfe: knoepfe, eintraege: kuenstler });
  }

  function kuenstlerOeffnen(k, farbe) {
    const kar = k.liste.filter(function (e) { return e._karaoke; });
    const knoepfe = [
      { text: 'Abspielen', sym: SYM.play, tun: function () { abspielen(k.liste, 0, false); } },
      { text: 'Mischen', sym: SYM.mischen, tun: function () { abspielen(k.liste, 0, true); } }];
    if (kar.length) knoepfe.push({ text: 'Karaoke', sym: SYM.mikro, tun: function () { karaokeStarten(kar, k.name); } });
    rasterZeigen({ art: 'lieder', kopf: 'KÜNSTLER', titel: k.name, bild: k.bild || bildUrl(k.liste[0], 300), farbe: farbe,
      gruppe: k.liste[0] && k.liste[0]._gruppe, ohneInterpret: true,
      meta: anzahl(k.liste.length), knoepfe: knoepfe, eintraege: k.liste }, true);
  }

  function liederOeffnen(x, alsNeu) {
    let liste = x.liste.slice();
    if (x.abc) liste.sort(function (a, b) { return (a.Name || '').localeCompare(b.Name || '', 'de'); });
    else liste = nachWert(liste);
    const kar = liste.filter(function (e) { return e._karaoke; });
    const knoepfe = [];
    if (liste.length) knoepfe.push({ text: 'Mischen', sym: SYM.mischen, tun: function () { abspielen(liste, 0, true); } });
    if (kar.length) knoepfe.push({ text: 'Karaoke', sym: SYM.mikro, tun: function () { karaokeStarten(kar, x.titel); } });
    rasterZeigen({ art: 'lieder', kopf: x.kopf, titel: x.titel, farbe: x.farbe, meta: anzahl(liste.length), leer: x.leer,
      knoepfe: knoepfe, eintraege: liste }, alsNeu);
  }

  /** Karaoke-Modus: erst auswählen, was gesungen wird. */
  function karaokeAuswahl() {
    const kar = M.katalog.lieder.filter(function (e) { return e._karaoke; });
    if (!kar.length) return hinweis('Die ersten Karaoke-Texte kommen gerade');
    const g = gehoert();
    const wahl = [{ name: 'Alles gemischt', liste: kar, farbe: GELB, sym: SYM.mikro, art: 'karaoke' }];
    const hits = kar.filter(function (e) { return e._rang > 0; }).sort(function (a, b) { return b._rang - a._rang; }).slice(0, 40);
    if (hits.length >= 5) wahl.push({ name: 'Beliebte Hits', liste: hits, farbe: '#F26B3A', sym: SYM.stern });
    const lieb = kar.filter(function (e) { return geliked(e) || spiele(e, g) > 0; });
    if (lieb.length >= 3) wahl.push({ name: 'Deine Lieblinge', liste: lieb, farbe: '#E8559A', sym: SYM.herzVoll });
    const de = kar.filter(function (e) { return e._sprache === 'de'; });
    if (de.length >= 3) wahl.push({ name: 'Deutsch', liste: de, farbe: '#2E8B3F', sym: SYM.flagge });
    const drap = de.filter(function (e) { return e._gruppe === 'Hip-Hop und R&B'; });
    if (drap.length >= 3) wahl.push({ name: 'Deutschrap', liste: drap, farbe: '#F26B3A', sym: SYM.mikro });
    const en = kar.filter(function (e) { return e._sprache === 'en'; });
    if (en.length >= 3 && de.length >= 3) wahl.push({ name: 'Englisch', liste: en, farbe: '#1592C4', sym: SYM.flagge });
    gruppenListe(kar).forEach(function (x) {
      if (x.liste.length >= 3 && x.name !== SONSTIGES[0]) wahl.push({ name: x.name, liste: x.liste, farbe: gruppenFarbe(x.name), sym: SYM.note });
    });
    wahl.forEach(function (w) { w.zahl = anzahl(w.liste.length); });
    rasterZeigen({ art: 'wahl', kopf: 'KARAOKE-MODUS', titel: 'Was singen wir?', farbe: GELB,
      meta: 'Jedes Mal frisch gemischt – was du gerade erst hattest, kommt ans Ende.', knoepfe: [], eintraege: wahl });
  }

  // ------------------------------------------------------------------ Suche

  function tastaturBauen() {
    let html = '';
    SUCH_TASTEN.forEach(function (zeile, z) {
      let s = 0;
      while (s < zeile.length) {
        let breite = 1;
        while (s + breite < zeile.length && zeile[s + breite] === zeile[s]) breite++;
        const t = zeile[s];
        const text = t === '␣' ? 'Leerzeichen' : t === '⌫' ? 'Löschen' : t === 'ß' ? 'ß' : t.toUpperCase();
        html += '<b data-z="' + z + '" data-s="' + s + '" style="left:' + (s * 116) + 'px;top:' + (z * 96) + 'px;width:'
          + (breite * 116 - 12) + 'px' + (breite > 1 ? ';font-size:28px' : '') + '">' + text + '</b>';
        s += breite;
      }
    });
    M.el.tasten.innerHTML = html;
  }

  function sucheOeffnen() {
    M.zurueckZu = M.sicht === 'raster' ? 'raster' : 'start';
    M.kopf = -1;
    M.such.inTreffern = false;
    sichtSetzen('suche');
    sucheZeichnen(true);
  }

  function sucheRechnen() {
    const q = normal(M.such.text);
    if (!q) {
      M.such.treffer = [];
      return;
    }
    const woerter = q.split(' ');
    const bewertet = [];
    (M.katalog ? M.katalog.lieder : []).forEach(function (e) {
      const ti = normal(e.Name), ia = normal(interpret(e)), al = normal(e.Album);
      const alles = ti + ' ' + ia + ' ' + al;
      if (!woerter.every(function (w) { return alles.indexOf(w) >= 0; })) return;
      const wert = ti.indexOf(q) === 0 ? 0 : ti.indexOf(q) >= 0 ? 1 : ia.indexOf(q) === 0 ? 2 : 3;
      bewertet.push([wert, e]);
    });
    bewertet.sort(function (a, b) { return a[0] - b[0] || (a[1].Name || '').localeCompare(b[1].Name || '', 'de'); });
    M.such.treffer = bewertet.slice(0, 120).map(function (x) { return x[1]; });
    M.such.tf = Math.min(M.such.tf, Math.max(0, M.such.treffer.length - 1));
  }

  function sucheZeichnen(neu) {
    const S = M.such;
    M.el.sx.innerHTML = S.text ? esc(S.text) + '<span class="cu"></span>' : '<span class="lt">Titel, Interpret oder Album</span>';
    M.el.tasten.querySelectorAll('b').forEach(function (b) {
      const z = +b.getAttribute('data-z'), s = +b.getAttribute('data-s');
      const t = SUCH_TASTEN[S.z][S.s];
      const gleich = z === S.z && (s === S.s || (SUCH_TASTEN[z][s] === t && (t === '␣' || t === '⌫')));
      b.classList.toggle('f', !S.inTreffern && gleich);
    });
    if (neu) {
      sucheRechnen();
      M.el.trL.innerHTML = '';
      S.treffer.forEach(function (e, i) {
        const d = document.createElement('div');
        d.className = 'lm-te';
        d.style.left = ((i % 2) * 475) + 'px';
        d.style.top = (Math.floor(i / 2) * 132) + 'px';
        d.innerHTML = coverHtml() + '<p class="ti"></p><p class="ia"></p>';
        d.querySelector('.ti').textContent = e.Name || '';
        d.querySelector('.ia').textContent = interpret(e);
        d._cov = d.querySelector('.lm-cov');
        d._cov.style.setProperty('--lm-f', gruppenFarbe(e._gruppe));
        M.el.trL.appendChild(d);
      });
      M.el.trH.textContent = !S.text ? 'Tippe ein paar Buchstaben – Titel, Interpret oder Album.'
        : S.treffer.length ? anzahl(S.treffer.length) + ' gefunden' : 'Nichts gefunden';
    }
    const zeile = Math.floor(S.tf / 2);
    M.el.trL.style.transform = 'translate3d(0,' + (-Math.max(0, zeile - 4) * 132) + 'px,0)';
    Array.prototype.forEach.call(M.el.trL.children, function (d, i) {
      d.classList.toggle('f', S.inTreffern && i === S.tf);
      const z = Math.floor(i / 2);
      if (z >= zeile - 5 && z <= zeile + 7 && !d._cov._geladen) {
        d._cov._geladen = true;
        coverSetzen(d._cov, S.treffer[i], 200);
      }
    });
  }

  function tippen(z) {
    if (!M.sichtbar || M.sicht !== 'suche') return false;
    if (z === '⌫') M.such.text = M.such.text.slice(0, -1);
    else if (z === '␣') {
      if (M.such.text && !/ $/.test(M.such.text)) M.such.text += ' ';
    } else if (M.such.text.length < 40) M.such.text += z;
    M.such.tf = 0;
    sucheZeichnen(true);
    return true;
  }

  function sucheTaste(n) {
    const S = M.such;
    if (S.inTreffern) {
      const s = S.tf % 2;
      if (n === 'links') {
        if (s > 0) S.tf--;
        else S.inTreffern = false;
      } else if (n === 'rechts') {
        if (s === 0 && S.tf + 1 < S.treffer.length) S.tf++;
      } else if (n === 'hoch') {
        if (S.tf >= 2) S.tf -= 2;
      } else if (n === 'runter') {
        if (S.tf + 2 < S.treffer.length) S.tf += 2;
      } else if (n === 'ok') return abspielen(S.treffer, S.tf, false);
      else if (n === 'zurueck') S.inTreffern = false;
      else return wiedergabeTaste(n);
      return sucheZeichnen(false);
    }
    const zeile = SUCH_TASTEN[S.z];
    const t = zeile[S.s];
    if (n === 'links') {
      let s = S.s - 1;
      while (s >= 0 && zeile[s] === t && (t === '␣' || t === '⌫')) s--;
      if (s >= 0) S.s = s;
      else return leisteAuf();
    } else if (n === 'rechts') {
      let s = S.s + 1;
      while (s < zeile.length && zeile[s] === t && (t === '␣' || t === '⌫')) s++;
      if (s < zeile.length) S.s = s;
      else if (S.treffer.length) {
        S.inTreffern = true;
        S.tf = Math.min(S.tf, S.treffer.length - 1);
      }
    } else if (n === 'hoch') S.z = Math.max(0, S.z - 1);
    else if (n === 'runter') S.z = Math.min(SUCH_TASTEN.length - 1, S.z + 1);
    else if (n === 'ok') return tippen(t);
    else if (n === 'zurueck') {
      if (M.zurueckZu === 'raster' && M.raster) {
        sichtSetzen('raster');
        rasterZeichnen();
      } else {
        sichtSetzen('start');
        startZeichnen(false);
      }
      return;
    } else return wiedergabeTaste(n);
    sucheZeichnen(false);
  }

  // ------------------------------------------------------------------ eigene Leiste (TCL) bzw. Leiste von app.js (LG)

  function leisteAuf() {
    if (!M.o.eigeneLeiste) {
      if (M.o.leiste) M.o.leiste();
      return;
    }
    M.leiste = true;
    M.leistePos = M.o.musikPos || 0;
    leisteZeichnen();
    M.el.w.classList.add('lm-leiste-auf');
    neuZeichnen();
  }

  function leisteZu() {
    M.leiste = false;
    M.el.w.classList.remove('lm-leiste-auf');
    neuZeichnen();
  }

  function leisteTop(i) {
    const apps = M.o.apps || 4;
    return 28 + 84 * i + (i >= apps ? 24 : 0);
  }

  function leisteZeichnen() {
    const namen = M.o.leisteNamen || [];
    const apps = M.o.apps || 4;
    const symbole = [SYM.lupe, SYM.film, SYM.serie, SYM.note, SYM.merk, SYM.stern, SYM.foto];
    let html = '<div class="pil" style="top:' + leisteTop(M.leistePos) + 'px"></div>'
      + '<div class="lin" style="top:' + (leisteTop(apps) - 13) + 'px"></div>';
    namen.forEach(function (n, i) {
      const s = i < apps ? SYM.app : (symbole[i - apps] || SYM.note);
      html += '<div class="ein' + (i === M.leistePos ? ' f' : '') + '" style="top:' + leisteTop(i) + 'px">' + svg(s)
        + '<span>' + esc(n) + '</span></div>';
    });
    if (M.o.musikPos != null) html += '<div class="pu" style="top:' + (leisteTop(M.o.musikPos) + 33) + 'px"></div>';
    M.el.le.innerHTML = html;
  }

  function leisteTaste(n) {
    const anz = (M.o.leisteNamen || []).length;
    if (n === 'zurueck' || n === 'rechts') return leisteZu();
    if (n === 'hoch') M.leistePos = Math.max(0, M.leistePos - 1);
    else if (n === 'runter') M.leistePos = Math.min(anz - 1, M.leistePos + 1);
    else if (n === 'ok') {
      if (M.leistePos === M.o.musikPos) return leisteZu();
      const ziel = M.leistePos;
      beenden();
      if (M.o.verlassen) M.o.verlassen(ziel);
      return;
    } else return wiedergabeTaste(n);
    leisteZeichnen();
  }

  // ------------------------------------------------------------------ Abspielen

  function aktuell() { return M.liste[M.pos] || null; }

  /** Mischen ohne Wiederholungen; was zuletzt lief, kommt ans Ende (damit nicht immer dasselbe zuerst kommt). */
  function mischenFrisch(liste) {
    const a = liste.slice();
    for (let i = a.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      const t = a[i];
      a[i] = a[j];
      a[j] = t;
    }
    const zuletzt = (speicher('lm_zuletzt') || []).slice(0, Math.min(25, Math.floor(a.length / 2)));
    const frisch = a.filter(function (e) { return zuletzt.indexOf(e.Id) < 0; });
    const alt = a.filter(function (e) { return zuletzt.indexOf(e.Id) >= 0; });
    return frisch.concat(alt);
  }

  function abspielen(liste, start, zufall) {
    if (!liste.length) return hinweis('Keine Lieder gefunden');
    if (M.sicht !== 'player' && M.sicht !== 'karaoke') M.zurueckZu = M.sicht;
    M.karaokeModus = false;
    M.zufall = !!zufall;
    M.mischQuelle = zufall ? liste.slice() : null;
    M.liste = zufall ? mischenFrisch(liste) : liste.slice();
    M.knopf = 1;
    spielen(zufall ? 0 : (start || 0));
    sichtSetzen('player');
    knoepfeZeichnen();
  }

  function karaokeStarten(liste, name) {
    if (!liste.length) return hinweis('Hier gibt es noch keine Karaoke-Texte');
    if (M.sicht !== 'player' && M.sicht !== 'karaoke') M.zurueckZu = M.sicht;
    M.zufall = true;
    M.karaokeModus = true;
    M.mischQuelle = liste.slice();
    M.liste = mischenFrisch(liste);
    M.karaokeName = name;
    spielen(0);
    sichtSetzen('karaoke');
  }

  function wiedergabeOeffnen() {
    const e = aktuell();
    if (!e) return;
    if (M.sicht !== 'player' && M.sicht !== 'karaoke') M.zurueckZu = M.sicht;
    M.kopf = -1;
    const ziel = M.letzteWiedergabe === 'karaoke' && (M.karaokeModus || e._karaoke) ? 'karaoke' : 'player';
    playerZeigen();
    sichtSetzen(ziel);
    knoepfeZeichnen();
  }

  function spielen(i) {
    if (!M.liste.length) return;
    meldenStopp();
    M.pos = (i + M.liste.length) % M.liste.length;
    const e = aktuell();
    M.gezaehlt = false;
    if (!M.audio) {
      M.audio = new Audio();
      M.audio.preload = 'auto';
      M.audio.addEventListener('ended', function () {
        M.fehlerZaehler = 0;
        zaehlen();
        weiter(1, true);
      });
      M.audio.addEventListener('error', function () {
        if (!M.audio.getAttribute('src')) return;
        M.fehlerZaehler++;
        hinweis('Lied lässt sich nicht abspielen');
        if (M.fehlerZaehler < 5) setTimeout(function () { weiter(1, true); }, 1500);
      });
      M.audio.addEventListener('playing', function () {
        M.el.w.classList.remove('lm-ps');
        meldenStart();
        obenZeichnen();
        knoepfeZeichnen();
      });
      M.audio.addEventListener('pause', function () {
        M.el.w.classList.add('lm-ps');
        obenZeichnen();
        knoepfeZeichnen();
      });
      M.audio.addEventListener('timeupdate', function () { if (!M.gezaehlt && M.audio.currentTime >= 30) zaehlen(); });
    }
    M.audio.src = streamUrl(e);
    const p = M.audio.play();
    if (p && p.catch) p.catch(function () { /* Autoplay: erster OK-Druck startet */ });
    M.text = null;
    M.textFuer = e.Id;
    textLaden(e).then(function (t) {
      if (M.textFuer !== e.Id) return;
      M.text = t;
      if (M.karaokeModus && !t) weiter(1, true);
    });
    const n = M.liste[M.pos + 1];
    if (n && n._karaoke) textLaden(n);
    if (M.sicht === 'karaoke' && !e._karaoke && !M.karaokeModus) sichtSetzen('player');
    playerZeigen();
    obenZeichnen();
    zuletztMerken(e);
  }

  function zaehlen() {
    const e = aktuell();
    if (!e || M.gezaehlt) return;
    M.gezaehlt = true;
    const g = gehoert();
    g[e.Id] = (g[e.Id] || 0) + 1;
    speicher('lm_gehoert', g);
    M.startVeraltet = true;
  }

  function zuletztMerken(e) {
    const l = (speicher('lm_zuletzt') || []).filter(function (id) { return id !== e.Id; });
    l.unshift(e.Id);
    speicher('lm_zuletzt', l.slice(0, 40));
    M.startVeraltet = true;
  }

  /** Nächstes/voriges Lied. Am Ende einer gemischten Liste: neu mischen (ohne das gerade gespielte vorne). */
  function weiter(d, vonSelbst) {
    if (!M.liste.length) return;
    if (M.audio && d < 0 && M.audio.currentTime > 4) {
      M.audio.currentTime = 0;
      return;
    }
    if (d > 0 && M.pos + 1 >= M.liste.length && M.zufall && M.mischQuelle) {
      const letzt = aktuell();
      const neu = mischenFrisch(M.mischQuelle);
      if (neu.length > 1 && letzt && neu[0].Id === letzt.Id) neu.push(neu.shift());
      M.liste = neu;
      return spielen(0);
    }
    if (d > 0 && M.pos + 1 >= M.liste.length && vonSelbst && !M.zufall) return spielen(0);
    spielen(M.pos + d);
  }

  function umschalten() {
    if (!M.audio || !M.audio.getAttribute('src')) return;
    if (M.audio.paused) M.audio.play();
    else M.audio.pause();
  }

  function spulen(s) {
    if (!M.audio || !isFinite(M.audio.duration)) return;
    M.audio.currentTime = Math.max(0, Math.min(M.audio.duration - 1, M.audio.currentTime + s));
  }

  function zufallUmschalten() {
    const e = aktuell();
    if (!e) return;
    M.zufall = !M.zufall;
    const rest = M.liste.slice();
    rest.splice(M.pos, 1);
    if (M.zufall) {
      M.mischQuelle = M.liste.slice();
      M.liste = [e].concat(mischenFrisch(rest));
    } else {
      rest.sort(function (a, b) { return (a.Name || '').localeCompare(b.Name || '', 'de'); });
      M.liste = [e].concat(rest);
    }
    M.pos = 0;
    hinweis(M.zufall ? 'Zufall an' : 'Zufall aus');
    playerZeigen();
  }

  /** Herz: Favorit in Jellyfin (pro Konto, also pro Profil). */
  function liken() {
    const e = aktuell();
    if (!e) return;
    const an = !geliked(e);
    e.UserData = e.UserData || {};
    e.UserData.IsFavorite = an;
    hinweis(an ? 'Zu deinen gelikten Songs hinzugefügt' : 'Aus den gelikten Songs entfernt');
    M.startVeraltet = true;
    knoepfeZeichnen();
    obenZeichnen();
    const u = M.o.benutzer;
    jf(an ? 'POST' : 'DELETE', '/UserFavoriteItems/' + e.Id + '?userId=' + u).catch(function (f) {
      if (f && f.status === 404) return jf(an ? 'POST' : 'DELETE', '/Users/' + u + '/FavoriteItems/' + e.Id);
      throw f;
    }).catch(function () {
      e.UserData.IsFavorite = !an;
      hinweis('Das Herz ließ sich nicht speichern');
      knoepfeZeichnen();
      obenZeichnen();
    });
  }

  function meldenStart() {
    const e = aktuell();
    if (!e || M.gemeldet === e.Id) return;
    M.gemeldet = e.Id;
    jf('POST', '/Sessions/Playing', { ItemId: e.Id, PositionTicks: 0, IsPaused: false, PlayMethod: 'DirectPlay', CanSeek: true })
      .catch(function () {});
  }

  function meldenStopp() {
    if (!M.gemeldet || !M.audio) return;
    const id = M.gemeldet;
    M.gemeldet = null;
    jf('POST', '/Sessions/Playing/Stopped', { ItemId: id, PositionTicks: Math.round((M.audio.currentTime || 0) * 1e7) })
      .catch(function () {});
  }

  // ------------------------------------------------------------------ Player

  function playerKnoepfe() {
    const e = aktuell();
    const l = [{ id: 'zurueck', sym: SYM.zurueck }, { id: 'pause', sym: M.audio && !M.audio.paused ? SYM.pause : SYM.play, gross: true },
      { id: 'vor', sym: SYM.vor }, { id: 'herz', sym: geliked(e) ? SYM.herzVoll : SYM.herz, an: geliked(e) },
      { id: 'zufall', sym: SYM.mischen, text: M.zufall ? 'Zufall an' : 'Zufall', an: M.zufall }];
    if (e && e._karaoke) l.push({ id: 'karaoke', sym: SYM.mikro, text: 'Karaoke' });
    return l;
  }

  function knoepfeZeichnen() {
    if (!M.el || M.sicht !== 'player') return;
    const l = playerKnoepfe();
    if (M.knopf >= l.length) M.knopf = l.length - 1;
    M.el.pk.innerHTML = l.map(function (k, i) {
      return '<div class="' + (k.gross ? 'gross ' : '') + (k.an ? 'an ' : '') + (i === M.knopf ? 'f' : '') + '">' + svg(k.sym)
        + (k.text ? '<span>' + esc(k.text) + '</span>' : '') + '</div>';
    }).join('');
  }

  function playerZeigen() {
    const e = aktuell();
    if (!e || !M.el) return;
    coverSetzen(M.el.pc, e, 640);
    coverSetzen(M.el.kpCov, e, 200);
    coverSetzen(M.el.ambCov, e, 400);
    M.el.pa.textContent = M.karaokeModus ? 'KARAOKE' : (e._gruppe || 'MUSIK').toUpperCase();
    M.el.pt.textContent = e.Name || dateiname(e);
    setTimeout(function () {
      const hoehe = M.el.pt.offsetHeight || 78;
      M.el.pn.style.top = (252 + hoehe + 20) + 'px';
      M.el.pl.style.top = (252 + hoehe + 78) + 'px';
    }, 0);
    M.el.pn.textContent = interpret(e);
    M.el.pl.textContent = [e.Album, e.ProductionYear].filter(Boolean).join(' · ');
    M.el.kpT.textContent = e.Name || '';
    M.el.kpI.textContent = interpret(e) + (M.karaokeModus && M.karaokeName ? '   ·   Karaoke: ' + M.karaokeName : '');
    M.el.ambT.textContent = e.Name || '';
    M.el.ambI.textContent = interpret(e);
    // Ruhe-Ansicht: Künstlerbild, sonst das Cover ganz unscharf
    const kb = kuenstlerBild(e._kuenstler);
    const bg = M.el.ambBg;
    if (kb) {
      bg.className = 'bg';
      bg.style.backgroundImage = 'url("' + kb + '")';
    } else {
      bg.className = 'bg weich';
      const c = bildUrl(e, 64);
      bg.style.backgroundImage = c ? 'url("' + c + '")' : 'none';
    }
    M.el.nr.innerHTML = '';
    for (let i = 1; i <= 4 && M.pos + i < M.liste.length; i++) {
      const n = M.liste[M.pos + i];
      const d = document.createElement('div');
      d.className = 'lm-ne';
      d.innerHTML = coverHtml() + '<p></p><p></p>';
      d.querySelectorAll('p')[0].textContent = n.Name || '';
      d.querySelectorAll('p')[1].textContent = interpret(n);
      coverSetzen(d.querySelector('.lm-cov'), n, 200);
      M.el.nr.appendChild(d);
    }
    M.el.nr.parentNode.style.display = M.el.nr.children.length ? '' : 'none';
    M.el.kh.innerHTML = '<b>OK</b> Pause &nbsp; <b>◀ ▶</b> Lied &nbsp; ' + (M.karaokeModus ? '' : '<b>▲</b> Player &nbsp; ') + '<b>Zurück</b> Stöbern';
    knoepfeZeichnen();
    if (M.sicht === 'player' || M.sicht === 'karaoke') stimmung(gruppenFarbe(e._gruppe));
  }

  function ruhePlanen() {
    clearTimeout(M.ruheTimer);
    if (!M.el) return;
    M.el.w.classList.remove('lm-ruhe');
    M.ruheTimer = setTimeout(function () {
      if (M.sichtbar && M.sicht === 'player') M.el.w.classList.add('lm-ruhe');
    }, 12000);
  }

  function wiedergabeTaste(n) {
    if (n === 'playpause') umschalten();
    else if (n === 'play') { if (M.audio && M.audio.getAttribute('src')) M.audio.play(); }
    else if (n === 'pause') { if (M.audio) M.audio.pause(); }
    else if (n === 'vor') spulen(10);
    else if (n === 'rueck') spulen(-10);
    else return false;
    return true;
  }

  function zurueckZumStoebern() {
    const ziel = M.zurueckZu === 'raster' && M.raster ? 'raster' : M.zurueckZu === 'suche' ? 'suche' : 'start';
    sichtSetzen(ziel);
    if (ziel === 'start') {
      if (M.startVeraltet) {
        M.startVeraltet = false;
        startAufbauen();
      } else startZeichnen(false);
    } else if (ziel === 'raster') rasterZeichnen();
    else sucheZeichnen(false);
  }

  function playerTaste(n) {
    // Erster Druck in der Ruhe-Ansicht holt nur die Knöpfe zurück
    if (M.el.w.classList.contains('lm-ruhe') && n !== 'playpause' && n !== 'play' && n !== 'pause') {
      ruhePlanen();
      return;
    }
    ruhePlanen();
    const l = playerKnoepfe();
    switch (n) {
      case 'links':
        M.knopf = Math.max(0, M.knopf - 1);
        break;
      case 'rechts':
        M.knopf = Math.min(l.length - 1, M.knopf + 1);
        break;
      case 'ok': {
        const k = l[M.knopf] && l[M.knopf].id;
        if (k === 'zurueck') weiter(-1);
        else if (k === 'vor') weiter(1);
        else if (k === 'pause') umschalten();
        else if (k === 'herz') liken();
        else if (k === 'zufall') zufallUmschalten();
        else if (k === 'karaoke') return sichtSetzen('karaoke');
        break;
      }
      case 'hoch': {
        const e = aktuell();
        if (e && e._karaoke) return sichtSetzen('karaoke');
        break;
      }
      case 'zurueck':
        return zurueckZumStoebern();
      default:
        wiedergabeTaste(n);
    }
    knoepfeZeichnen();
  }

  function karaokeTaste(n) {
    // Gedrückt halten soll nicht zehn Lieder überspringen
    if (n === 'links' || n === 'rechts') {
      const jetzt = Date.now();
      if (jetzt - (M.sprung || 0) < 700) return;
      M.sprung = jetzt;
    }
    switch (n) {
      case 'ok':
        umschalten();
        break;
      case 'rechts':
        weiter(1);
        break;
      case 'links':
        weiter(-1);
        break;
      case 'hoch':
        if (!M.karaokeModus) {
          M.knopf = 1;
          sichtSetzen('player');
          knoepfeZeichnen();
        }
        break;
      case 'zurueck':
        if (M.karaokeModus) zurueckZumStoebern();
        else {
          M.knopf = 1;
          sichtSetzen('player');
          knoepfeZeichnen();
        }
        break;
      default:
        wiedergabeTaste(n);
    }
  }

  // ------------------------------------------------------------------ Texte

  function silben(w) {
    const m = w.toLowerCase().match(/[aeiouyäöüàáâéèêíìîóòôúùû]+/g);
    return Math.max(1, m ? m.length : 1);
  }

  /** LRC lesen: [mm:ss.xx]Text, auch mehrere Zeitstempel pro Zeile und <mm:ss.xx>Wort (Enhanced LRC). */
  function lrcLesen(roh) {
    const zeilen = [];
    const zeitRe = /\[(\d+):(\d+(?:\.\d+)?)\]/g;
    roh.split(/\r?\n/).forEach(function (l) {
      const zeiten = [];
      let m;
      zeitRe.lastIndex = 0;
      while ((m = zeitRe.exec(l))) zeiten.push(parseInt(m[1], 10) * 60 + parseFloat(m[2]));
      if (!zeiten.length) return;
      const text = l.replace(/\[[^\]]*\]/g, '').trim();
      zeiten.forEach(function (t) { zeilen.push({ s: t, roh: text }); });
    });
    zeilen.sort(function (a, b) { return a.s - b.s; });
    const aus = [];
    let geschaetzt = false;
    for (let i = 0; i < zeilen.length; i++) {
      const z = zeilen[i];
      const naechste = zeilen[i + 1] ? zeilen[i + 1].s : z.s + 6;
      const text = z.roh.replace(/<\d+:\d+(?:\.\d+)?>/g, '').replace(/\s+/g, ' ').trim();
      if (!text) continue;
      const enh = z.roh.match(/<(\d+):(\d+(?:\.\d+)?)>\s*([^<]*)/g);
      const w = [];
      if (enh && enh.length > 1) {
        enh.forEach(function (stk) {
          const mm = stk.match(/<(\d+):(\d+(?:\.\d+)?)>\s*([^<]*)/);
          const s = parseInt(mm[1], 10) * 60 + parseFloat(mm[2]);
          mm[3].trim().split(/\s+/).filter(Boolean).forEach(function (wort) { w.push({ s: s, e: 0, t: wort }); });
        });
        for (let k = 0; k < w.length; k++) w[k].e = k + 1 < w.length ? Math.max(w[k].s + 0.1, w[k + 1].s - 0.05) : Math.min(naechste - 0.15, w[k].s + 0.8);
      } else {
        geschaetzt = true;
        const woerter = text.split(' ');
        const sil = woerter.map(silben);
        const summe = sil.reduce(function (a, b) { return a + b; }, 0);
        const luecke = naechste - z.s;
        const dauer = luecke <= 6.5 ? Math.max(0.6, luecke - 0.25) : Math.min(luecke - 0.25, summe * 0.36 + 0.9);
        const gew = sil.map(function (s, k) { return s + (k === sil.length - 1 ? 0.8 : 0.25); });
        const gsum = gew.reduce(function (a, b) { return a + b; }, 0);
        let t = z.s;
        woerter.forEach(function (wort, k) {
          const d = dauer * gew[k] / gsum;
          w.push({ s: t, e: t + d * 0.9, t: wort });
          t += d;
        });
      }
      aus.push({ s: w[0].s, e: w[w.length - 1].e, text: text, w: w });
    }
    return aus.length ? { zeilen: aus, geschaetzt: geschaetzt } : null;
  }

  function wortLesen(j) {
    const aus = (j.zeilen || []).map(function (z) {
      const w = (z.w || []).map(function (x) { return { s: x[0], e: x[1], t: x[2] }; });
      return { s: w.length ? w[0].s : z.s, e: w.length ? w[w.length - 1].e : z.e, text: w.map(function (x) { return x.t; }).join(' '), w: w };
    }).filter(function (z) { return z.w.length; });
    return aus.length ? { zeilen: aus, geschaetzt: false } : null;
  }

  async function textLaden(e) {
    if (!e) return null;
    if (M.textCache[e.Id] !== undefined) return M.textCache[e.Id];
    const k = M.index && M.index[dateiname(e)];
    let t = null;
    try {
      if (k && k.wort) {
        const r = await fetch(basis() + 'wort/' + encodeURIComponent(k.wort));
        if (r.ok) t = wortLesen(await r.json());
      }
      if (!t && k && k.lrc) {
        const r = await fetch(basis() + 'lrc/' + encodeURIComponent(k.lrc));
        if (r.ok) t = lrcLesen(await r.text());
      }
    } catch (x) { t = null; }
    M.textCache[e.Id] = t;
    return t;
  }

  // ------------------------------------------------------------------ Karaoke zeichnen

  const SPUR = { links: 80, rechts: 1840, oben: 712, unten: 928, jetzt: 470, pps: 280, balken: 860, schrift: 778 };
  const SCHRIFT = '"Inter Tight", system-ui, sans-serif';

  function rund(c, x, y, w, h, r) {
    r = Math.max(0, Math.min(r, w / 2, h / 2));
    c.beginPath();
    c.moveTo(x + r, y);
    c.arcTo(x + w, y, x + w, y + h, r);
    c.arcTo(x + w, y + h, x, y + h, r);
    c.arcTo(x, y + h, x, y, r);
    c.arcTo(x, y, x + w, y, r);
    c.closePath();
  }

  function satzLayout(c, z, groesse, oben, maxB) {
    c.font = '700 ' + groesse + 'px ' + SCHRIFT;
    const leer = c.measureText(' ').width;
    const reihen = [[]];
    let breite = 0;
    z.w.forEach(function (w) {
      const b = c.measureText(w.t).width;
      if (breite > 0 && breite + leer + b > maxB) {
        reihen.push([]);
        breite = 0;
      }
      reihen[reihen.length - 1].push({ w: w, b: b });
      breite += (breite > 0 ? leer : 0) + b;
    });
    const aus = [];
    const zh = groesse * 1.22;
    reihen.forEach(function (r, ri) {
      const gesamt = r.reduce(function (a, x) { return a + x.b; }, 0) + leer * (r.length - 1);
      let x = (STAGE_W - gesamt) / 2;
      r.forEach(function (x2) {
        aus.push({ w: x2.w, x: x, y: oben + ri * zh, b: x2.b });
        x += x2.b + leer;
      });
    });
    return { teile: aus, hoehe: reihen.length * zh };
  }

  function satzZeichnen(c, z, t, groesse, oben, alpha, aktiv) {
    const l = satzLayout(c, z, groesse, oben, 1640);
    c.textBaseline = 'alphabetic';
    l.teile.forEach(function (p) {
      const w = p.w;
      if (!aktiv || t < w.s) {
        c.fillStyle = 'rgba(255,255,255,' + alpha + ')';
        c.fillText(w.t, p.x, p.y);
      } else if (t >= w.e) {
        c.fillStyle = GELB;
        c.fillText(w.t, p.x, p.y);
      } else {
        c.fillStyle = '#fff';
        c.fillText(w.t, p.x, p.y);
        const f = Math.max(0, Math.min(1, (t - w.s) / Math.max(0.05, w.e - w.s)));
        c.save();
        c.beginPath();
        c.rect(p.x - 2, p.y - groesse, (p.b + 4) * f, groesse * 1.4);
        c.clip();
        c.shadowColor = 'rgba(242,183,5,.7)';
        c.shadowBlur = 22;
        c.fillStyle = GELB;
        c.fillText(w.t, p.x, p.y);
        c.restore();
      }
    });
    return l.hoehe;
  }

  function wellenZeichnen(c) {
    const tt = performance.now() / 1000;
    for (let k = 0; k < 3; k++) {
      c.beginPath();
      for (let x = 0; x <= STAGE_W; x += 24) {
        const y = SPUR.balken - 40 + Math.sin(x * 0.006 + tt * (0.7 + k * 0.25) + k * 2) * (26 + k * 14)
          + Math.sin(x * 0.0021 - tt * 0.4 + k) * 18;
        if (x === 0) c.moveTo(x, y);
        else c.lineTo(x, y);
      }
      c.strokeStyle = k === 0 ? 'rgba(242,183,5,.10)' : 'rgba(255,255,255,' + (0.05 - k * 0.012) + ')';
      c.lineWidth = 3 + k * 2;
      c.stroke();
    }
  }

  /**
   * Schrift-Reihe über den Balken: je Zeile so klein, dass alle Wörter in die Zeit der Zeile passen
   * (26 px, höchstens runter bis 15 px). Jedes Wort startet an seinem Balken; ist der Platz knapp,
   * rutscht es ein Stück nach rechts – nie übereinander. Die Schriftgröße je Zeile wird gemerkt.
   */
  function zeilenSchrift(c, z) {
    if (z._schrift) return z._schrift;
    c.font = '700 26px ' + SCHRIFT;
    let summe = 0;
    z.w.forEach(function (w) { summe += c.measureText(w.t).width + 12; });
    const platz = (z.e - z.s) * SPUR.pps + 40;
    z._schrift = Math.max(15, Math.min(26, Math.floor(26 * platz / Math.max(1, summe))));
    return z._schrift;
  }

  function karaokeZeichnen() {
    const c = M.ctx;
    c.clearRect(0, 0, STAGE_W, STAGE_H);
    const e = aktuell();
    const tx = M.text;
    const t = M.audio ? M.audio.currentTime : 0;
    wellenZeichnen(c);
    if (!tx) {
      c.fillStyle = 'rgba(255,255,255,.75)';
      c.font = '600 46px ' + SCHRIFT;
      c.textAlign = 'center';
      c.fillText(e && e._karaoke ? 'Text wird geladen …' : 'Zu diesem Lied gibt es noch keinen Text', STAGE_W / 2, 480);
      c.textAlign = 'left';
      fortschrittZeichnen(c, t);
      return;
    }
    const Z = tx.zeilen;
    let ai = -1;
    for (let i = 0; i < Z.length; i++) if (Z[i].s <= t + 0.05) ai = i;
    const akt = ai >= 0 && t <= Z[ai].e + 0.5 ? Z[ai] : null;
    const naechste = Z[ai + 1] || null;

    // ---- Satz oben
    if (akt) {
      const h = satzZeichnen(c, akt, t, 70, 380, 0.95, true);
      if (naechste) satzZeichnen(c, naechste, t, 44, 380 + h + 40, 0.5, false);
    } else if (naechste) {
      const rest = naechste.s - t;
      c.textAlign = 'center';
      if (rest <= 3) {
        const zahl = Math.max(1, Math.min(3, Math.ceil(rest)));
        const puls = 1 + 0.12 * (1 - (rest - Math.floor(rest)));
        c.fillStyle = 'rgba(242,183,5,.16)';
        c.beginPath();
        c.arc(STAGE_W / 2, 270, 66 * puls, 0, Math.PI * 2);
        c.fill();
        c.fillStyle = GELB;
        c.font = '800 80px ' + SCHRIFT;
        c.fillText(String(zahl), STAGE_W / 2, 298);
      } else {
        c.fillStyle = 'rgba(255,255,255,.6)';
        c.font = '600 34px ' + SCHRIFT;
        c.fillText((ai < 0 ? 'Intro' : 'Pause') + '  ·  noch ' + Math.ceil(rest) + ' s', STAGE_W / 2, 290);
      }
      c.textAlign = 'left';
      satzZeichnen(c, naechste, t, 62, 420, 0.72, false);
    } else {
      c.fillStyle = 'rgba(255,255,255,.55)';
      c.font = '600 40px ' + SCHRIFT;
      c.textAlign = 'center';
      c.fillText('♪', STAGE_W / 2, 460);
      c.textAlign = 'left';
    }

    // ---- Zeitspur unten: oben die Schrift-Reihe, darunter nur Balken
    const S = SPUR;
    c.fillStyle = 'rgba(255,255,255,.06)';
    rund(c, S.links, S.oben, S.rechts - S.links, S.unten - S.oben, 34);
    c.fill();
    c.save();
    rund(c, S.links, S.oben, S.rechts - S.links, S.unten - S.oben, 34);
    c.clip();
    const tMin = t - (S.jetzt - S.links) / S.pps, tMax = t + (S.rechts - S.jetzt) / S.pps;
    const X = function (zp) { return S.jetzt + (zp - t) * S.pps; };
    let schriftEnde = -1e9;
    for (let i = 0; i < Z.length; i++) {
      const z = Z[i];
      const vorEnde = i > 0 ? Z[i - 1].e : 0;
      const luecke = z.s - vorEnde;
      if (z.e < tMin - 3 || z.s - 4 > tMax) continue;
      if (luecke >= 3) {
        if (luecke >= 4.5) {
          c.font = '600 24px ' + SCHRIFT;
          c.textBaseline = 'middle';
          c.fillStyle = 'rgba(255,255,255,.42)';
          c.textAlign = 'center';
          c.fillText((i === 0 ? 'Intro ' : 'Pause ') + Math.round(luecke) + ' s', (X(vorEnde) + X(z.s - 3)) / 2, S.balken);
          c.textAlign = 'left';
        }
        for (let k = 1; k <= 3; k++) {
          c.fillStyle = t >= z.s - k ? 'rgba(255,255,255,.18)' : GELB;
          c.beginPath();
          c.arc(X(z.s - k), S.balken, 8, 0, Math.PI * 2);
          c.fill();
        }
      }
      c.fillStyle = 'rgba(255,255,255,.18)';
      c.fillRect(X(z.s) - 2, S.oben + 18, 3, S.unten - S.oben - 36);
      const gr = zeilenSchrift(c, z);
      c.font = '700 ' + gr + 'px ' + SCHRIFT;
      c.textBaseline = 'middle';
      z.w.forEach(function (w) {
        const x1 = X(w.s) + 3, x2 = X(w.e) - 3;
        const vorbei = t >= w.e, laeuft = t >= w.s && !vorbei;
        if (x2 >= S.links - 20 && x1 <= S.rechts + 20) {
          const breite = Math.max(8, x2 - x1);
          const y = S.balken - 16, h = 32;
          if (vorbei) {
            c.fillStyle = 'rgba(255,255,255,.16)';
            rund(c, x1, y, breite, h, 12);
            c.fill();
          } else if (laeuft) {
            c.fillStyle = 'rgba(242,183,5,.35)';
            rund(c, x1, y, breite, h, 12);
            c.fill();
            c.save();
            c.beginPath();
            c.rect(x1, y, breite * Math.min(1, (t - w.s) / Math.max(0.05, w.e - w.s)), h);
            c.clip();
            c.shadowColor = 'rgba(242,183,5,.8)';
            c.shadowBlur = 18;
            c.fillStyle = GELB;
            rund(c, x1, y, breite, h, 12);
            c.fill();
            c.restore();
          } else {
            c.fillStyle = 'rgba(255,255,255,.78)';
            rund(c, x1, y, breite, h, 12);
            c.fill();
          }
        }
        // Wort darüber – immer, nie im Balken
        const tb = c.measureText(w.t).width;
        const lx = Math.max(x1, schriftEnde + 10);
        schriftEnde = lx + tb;
        if (lx > S.rechts + 40 || lx + tb < S.links - 40) return;
        if (laeuft) {
          c.save();
          c.shadowColor = 'rgba(242,183,5,.75)';
          c.shadowBlur = 16;
          c.fillStyle = GELB;
          c.fillText(w.t, lx, S.schrift);
          c.restore();
        } else {
          c.fillStyle = vorbei ? 'rgba(255,255,255,.32)' : 'rgba(255,255,255,.92)';
          c.fillText(w.t, lx, S.schrift);
        }
      });
    }
    c.restore();
    c.textBaseline = 'alphabetic';
    let singt = false;
    if (akt) akt.w.forEach(function (w) { if (t >= w.s && t < w.e) singt = true; });
    c.save();
    if (singt) {
      c.shadowColor = 'rgba(242,183,5,.9)';
      c.shadowBlur = 24;
    }
    c.fillStyle = GELB;
    c.fillRect(S.jetzt - 2, S.oben - 14, 4, S.unten - S.oben + 28);
    c.beginPath();
    c.moveTo(S.jetzt - 12, S.oben - 22);
    c.lineTo(S.jetzt + 12, S.oben - 22);
    c.lineTo(S.jetzt, S.oben - 8);
    c.closePath();
    c.fill();
    c.restore();
    if (tx.geschaetzt) {
      c.fillStyle = 'rgba(255,255,255,.35)';
      c.font = '500 22px ' + SCHRIFT;
      c.textAlign = 'right';
      c.fillText('Zeiten pro Zeile, Wörter geschätzt', S.rechts, S.unten + 40);
      c.textAlign = 'left';
    }
    fortschrittZeichnen(c, t);
  }

  function fortschrittZeichnen(c, t) {
    const d = M.audio && isFinite(M.audio.duration) ? M.audio.duration : 0;
    const x = 110, w = 1700, y = 1000;
    c.fillStyle = 'rgba(255,255,255,.16)';
    rund(c, x, y, w, 8, 4);
    c.fill();
    if (d > 0) {
      c.fillStyle = GELB;
      rund(c, x, y, Math.max(8, w * t / d), 8, 4);
      c.fill();
    }
    c.fillStyle = 'rgba(255,255,255,.7)';
    c.font = '500 26px ' + SCHRIFT;
    c.fillText(zeit(t), x, y + 48);
    c.textAlign = 'right';
    c.fillText(d ? zeit(d) : '', x + w, y + 48);
    c.textAlign = 'left';
  }

  function playerTick() {
    if (!M.audio || !M.el) return;
    const d = M.audio.duration, t = M.audio.currentTime || 0;
    const p = (isFinite(d) && d > 0 ? Math.min(100, t / d * 100) : 0) + '%';
    M.el.pb.style.width = p;
    M.el.ambB.style.width = p;
    M.el.pz1.textContent = zeit(t);
    M.el.pz2.textContent = isFinite(d) ? zeit(d) : '';
  }

  function schleife() {
    cancelAnimationFrame(M.raf);
    clearInterval(M.timer);
    if (!M.sichtbar) return;
    if (M.sicht === 'karaoke') {
      const lauf = function () {
        if (!M.sichtbar || M.sicht !== 'karaoke') return;
        karaokeZeichnen();
        M.raf = requestAnimationFrame(lauf);
      };
      M.raf = requestAnimationFrame(lauf);
    } else if (M.sicht === 'player') {
      playerTick();
      M.timer = setInterval(playerTick, 250);
    }
  }

  // ------------------------------------------------------------------ Öffnen, Tasten, Schließen

  /**
   * Musik-Bereich öffnen. o: {server, token, benutzer, auth?, geraet?, leicht?, eigeneLeiste?, leisteNamen?, apps?,
   * musikPos?, verlassen(pos)?, leiste()?}. Läuft schon Musik, bleibt sie an und der Bereich zeigt sich wieder.
   */
  async function bereich(o) {
    M.o = Object.assign({}, M.o, o || {});
    bauen();
    M.el.w.classList.toggle('lm-leicht', !!M.o.leicht);
    zeigen();
    if (M.aktiv && M.reihen.length) {
      if (M.startVeraltet && M.sicht === 'start') {
        M.startVeraltet = false;
        startAufbauen().catch(function () {});
      }
      return;
    }
    M.aktiv = true;
    M.kopf = -1;
    M.akt = 0;
    sichtSetzen('start');
    M.el.info.innerHTML = '<p class="k">MUSIK</p><h1>Musik</h1><p class="m">wird geladen …</p>';
    stimmung(GELB);
    try {
      await katalog(null, true);
      await startAufbauen();
    } catch (x) {
      M.el.info.innerHTML = '<p class="k">MUSIK</p><h1>Jellyfin ist nicht erreichbar</h1>';
    }
  }

  function zeigen() {
    if (!M.el) return;
    M.sichtbar = true;
    M.el.w.classList.add('an');
    if (M.leiste) leisteZu();
    if (M.sicht === 'player') ruhePlanen();
    schleife();
    obenZeichnen();
  }

  function verstecken() {
    if (!M.el) return;
    M.sichtbar = false;
    M.el.w.classList.remove('an');
    clearTimeout(M.ruheTimer);
    schleife();
  }

  function beenden() {
    meldenStopp();
    if (M.audio) {
      M.audio.pause();
      M.audio.removeAttribute('src');
      M.audio.load();
    }
    M.liste = [];
    M.pos = 0;
    M.aktiv = false;
    M.leiste = false;
    if (M.el) M.el.w.classList.remove('lm-leiste-auf');
    verstecken();
  }

  function taste(n) {
    if (!M.sichtbar) return false;
    if (M.leiste) leisteTaste(n);
    else if (M.sicht === 'start') startTaste(n);
    else if (M.sicht === 'raster') rasterTaste(n);
    else if (M.sicht === 'suche') sucheTaste(n);
    else if (M.sicht === 'player') playerTaste(n);
    else if (M.sicht === 'karaoke') karaokeTaste(n);
    return true;
  }

  window.LukasMusik = {
    bereich: bereich,
    taste: taste,
    tippen: tippen,
    zeigen: zeigen,
    verstecken: verstecken,
    beenden: beenden,
    offen: function () { return M.sichtbar; },
    aktiv: function () { return M.aktiv; },
    sucheOffen: function () { return M.sichtbar && M.sicht === 'suche' && !M.such.inTreffern && !M.leiste; },
    katalog: katalog,
    gruppe: gruppe,
    lrcLesen: lrcLesen
  };
})();
