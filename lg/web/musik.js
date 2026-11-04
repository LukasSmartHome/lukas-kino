/*
 * Lukas Kino – Musik-Player und Karaoke (04.11.2026, Wunsch Lukas).
 *
 * Eine Datei für beide Fernseher-Arten:
 *  - LG: app.js lädt diese Datei nach und ruft LukasMusik.oeffnen(...) auf (Overlay über der Kino-Oberfläche).
 *  - TCL: MusikActivity zeigt musik.html (gleiche Datei vom Pi, Port 8098) in einer WebView.
 *
 * Karaoke („Zeitspur“): oben die ganze Zeile als Satz (Wort für Wort gelb gefüllt, nächste Zeile kleiner zum
 * Vorauslesen), darunter die Zeitspur mit Balken von rechts nach links – Länge = wie lange gesungen wird,
 * Lücke = Pause, Punkte = Einzählen. Schrift überlappt nie, weil in der Spur nur dort ein Wort steht, wo Platz ist.
 *
 * Texte: /karaoke/index.json (Dateiname -> {status, lrc, wort}) im Ordner /share/kino/karaoke auf dem Pi.
 *  - wort/<name>.json  genaue Wort-Zeiten (kommt später vom PC: Stimme freistellen + Forced Alignment)
 *  - lrc/<name>.lrc    Zeiten pro Zeile (LRCLIB); Wörter werden innerhalb der Zeile nach Silben geschätzt
 */
(function () {
  'use strict';

  const GELB = '#F2B705';
  const STAGE_W = 1920, STAGE_H = 1080;

  // ------------------------------------------------------------------ Genres -> wenige große Gruppen
  // Die Tags sind wild gemischt (iTunes, YouTube). Für die Reihen am Fernseher reichen ein paar Gruppen.
  const GRUPPEN = [
    ['Weihnachten', /weihnacht|christmas|holiday|xmas/i, '#B3262E'],
    ['Film und Soundtrack', /soundtrack|filmmusik|score|musical/i, '#1592C4'],
    ['Deutsch und Schlager', /schlager|volksmusik|deutschpop|deutsch|liedermacher|neue deutsche/i, '#2E8B3F'],
    ['Hip-Hop und R&B', /hip-hop|hip hop|rap|r&b|rnb|soul|urban/i, '#F26B3A'],
    ['Latin und Reggae', /latin|reggae|spanisch|reggaeton|salsa|dancehall/i, '#E9A23B'],
    ['Dance und Elektro', /dance|electronic|elektro|house|techno|edm|trance|disco/i, '#6A3DE8'],
    ['Country und Folk', /country|folk|bluegrass/i, '#9C7442'],
    ['Klassik und Chill', /classical|clasic|klassik|new age|ambient|chill|lounge|jazz/i, '#2F6E73'],
    ['Rock', /rock|alternative|indie|metal|punk|blues|grunge/i, '#B83A3A'],
    ['Pop', /pop|top 40|top 100|singer|songwriter|chanson|vari[eé]t[eé]|charts/i, '#E8559A']
  ];
  const SONSTIGES = ['Sonstiges', null, '#3E4A5E'];

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

  function dateiname(e) {
    const p = (e && e.Path) || '';
    return p.split('/').pop();
  }

  // ------------------------------------------------------------------ Zustand

  const M = {
    o: null, offen: false, katalog: null, katalogLaedt: null, index: null,
    liste: [], pos: 0, audio: null, ansicht: 'player', karaokeModus: false,
    text: null, textFuer: null, textCache: {}, raf: 0, timer: 0, el: null, ctx: null,
    hinweisTimer: 0, zufall: false, gemeldet: null, fehlerZaehler: 0
  };

  function basis() { return (M.o && M.o.karaokeBasis) || '/karaoke/'; }
  function server() { return ((M.o && M.o.server) || 'http://192.168.178.44:8096').replace(/\/+$/, ''); }

  function auth() {
    const o = M.o || {};
    if (typeof o.auth === 'function') return o.auth();
    if (o.auth) return o.auth;
    return 'MediaBrowser Client="Lukas Kino Musik", Device="' + (o.geraetName || 'Fernseher') + '", DeviceId="'
      + (o.geraet || 'lukas-kino-musik') + '", Version="1.0", Token="' + (o.token || '') + '"';
  }

  async function jf(methode, pfad, body) {
    const opt = { method: methode, headers: { 'Authorization': auth(), 'Accept': 'application/json' } };
    if (body !== undefined) {
      opt.headers['Content-Type'] = 'application/json';
      opt.body = JSON.stringify(body);
    }
    const r = await fetch(server() + pfad, opt);
    if (!r.ok) throw new Error('Jellyfin ' + r.status);
    const t = await r.text();
    return t ? JSON.parse(t) : {};
  }

  const FELDER = 'Path,Genres,AlbumArtist,Artists,ImageTags,AlbumId,AlbumPrimaryImageTag,RunTimeTicks,DateCreated,ProductionYear,Album,UserData';

  /** Alle Lieder plus Karaoke-Verzeichnis. Wird einmal geladen und gemerkt (beide Seiten nutzen das). */
  async function katalog(o, frisch) {
    if (o) M.o = Object.assign({}, M.o || {}, o);
    if (M.katalog && !frisch) return M.katalog;
    if (M.katalogLaedt && !frisch) return M.katalogLaedt;
    M.katalogLaedt = (async function () {
      const u = M.o.benutzer;
      const items = await jf('GET', '/Items?userId=' + u + '&includeItemTypes=Audio&recursive=true&sortBy=SortName'
        + '&fields=' + FELDER + '&enableImageTypes=Primary');
      let index = {};
      try {
        const r = await fetch(basis() + 'index.json?t=' + Date.now());
        if (r.ok) index = await r.json();
      } catch (e) { /* ohne Karaoke weiter */ }
      M.index = index;
      const lieder = (items.Items || []).map(function (e) {
        const k = index[dateiname(e)];
        e._gruppe = gruppe(e);
        e._karaoke = !!(k && (k.wort || k.lrc));
        return e;
      });
      M.katalog = { lieder: lieder, index: index };
      M.katalogLaedt = null;
      return M.katalog;
    })();
    return M.katalogLaedt;
  }

  function bildUrl(e, breite) {
    if (!e) return '';
    const tags = e.ImageTags || {};
    if (tags.Primary) return server() + '/Items/' + e.Id + '/Images/Primary?fillWidth=' + breite + '&quality=90&tag=' + tags.Primary;
    if (e.AlbumId && e.AlbumPrimaryImageTag)
      return server() + '/Items/' + e.AlbumId + '/Images/Primary?fillWidth=' + breite + '&quality=90&tag=' + e.AlbumPrimaryImageTag;
    return '';
  }

  function streamUrl(e) {
    const t = (M.o && M.o.token) || '';
    return server() + '/Audio/' + e.Id + '/stream?static=true&api_key=' + t + '&ApiKey=' + t;
  }

  // ------------------------------------------------------------------ Oberfläche

  const CSS = ''
    + '@font-face{font-family:"Inter Tight";src:url("' + (window.LUKAS_MUSIK_FONT || 'fonts/InterTight.ttf') + '") format("truetype");font-weight:100 900}'
    + '.lm-w{position:fixed;left:0;top:0;width:100%;height:100%;background:#07080B;z-index:9000;overflow:hidden;'
    + 'font-family:"Inter Tight",system-ui,sans-serif;color:#fff;-webkit-font-smoothing:antialiased;opacity:0;transition:opacity .35s}'
    + '.lm-w.an{opacity:1}'
    + '.lm-b{position:absolute;left:0;top:0;width:1920px;height:1080px;transform-origin:0 0;overflow:hidden}'
    + '.lm-bg{position:absolute;left:-40px;top:-40px;width:2000px;height:1160px;object-fit:cover;opacity:.55;transition:opacity .8s}'
    + '.lm-bg.weg{opacity:0}'
    + '.lm-dk{position:absolute;left:0;top:0;width:1920px;height:1080px;background:linear-gradient(90deg,rgba(7,8,11,.92) 0%,rgba(7,8,11,.72) 55%,rgba(7,8,11,.55) 100%)}'
    + '.lm-k .lm-dk{background:rgba(7,8,11,.8)}'
    + '.lm-p{position:absolute;left:0;top:0;width:1920px;height:1080px;transition:opacity .4s}'
    + '.lm-k .lm-p{opacity:0}'
    + '.lm-cv{position:absolute;left:110px;top:200px;width:560px;height:560px;border-radius:32px;background:rgba(255,255,255,.08);'
    + 'box-shadow:0 30px 80px rgba(0,0,0,.6);overflow:hidden}'
    + '.lm-cv img{width:100%;height:100%;object-fit:cover;display:block}'
    + '.lm-cv .lm-note{position:absolute;left:0;top:0;width:100%;height:100%;display:flex;align-items:center;justify-content:center;font-size:180px;color:rgba(255,255,255,.25)}'
    + '.lm-art{position:absolute;left:750px;top:236px;font-size:26px;font-weight:700;letter-spacing:.14em;color:' + GELB + '}'
    + '.lm-ti{position:absolute;left:750px;top:282px;width:1060px;font-size:72px;font-weight:800;line-height:1.08;'
    + 'display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}'
    + '.lm-in{position:absolute;left:750px;width:1060px;font-size:40px;font-weight:500;color:rgba(255,255,255,.88);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}'
    + '.lm-al{position:absolute;left:750px;width:1060px;font-size:30px;color:rgba(255,255,255,.55);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}'
    + '.lm-pill{display:inline-block;margin-left:18px;padding:6px 16px;border-radius:999px;background:rgba(242,183,5,.18);color:' + GELB + ';font-size:24px;font-weight:700;vertical-align:4px}'
    + '.lm-bar{position:absolute;left:750px;top:690px;width:1060px;height:10px;border-radius:5px;background:rgba(255,255,255,.18)}'
    + '.lm-bar i{position:absolute;left:0;top:0;height:10px;border-radius:5px;background:' + GELB + '}'
    + '.lm-z{position:absolute;top:718px;font-size:28px;color:rgba(255,255,255,.75)}'
    + '.lm-pause{position:absolute;left:310px;top:400px;width:160px;height:160px;border-radius:50%;background:rgba(0,0,0,.6);display:none}'
    + '.lm-pause:before,.lm-pause:after{content:"";position:absolute;top:44px;width:22px;height:72px;border-radius:6px;background:#fff}'
    + '.lm-pause:before{left:50px}.lm-pause:after{left:88px}'
    + '.lm-ps .lm-pause{display:block}'
    + '.lm-nx{position:absolute;left:110px;top:830px;width:1700px}'
    + '.lm-nx h3{margin:0 0 18px;font-size:26px;font-weight:700;letter-spacing:.1em;color:rgba(255,255,255,.55)}'
    + '.lm-nx .lm-r{display:flex;gap:26px}'
    + '.lm-nx .lm-e{display:flex;align-items:center;gap:18px;width:400px;height:100px;border-radius:20px;background:rgba(255,255,255,.07);padding:0 18px 0 0;overflow:hidden}'
    + '.lm-nx .lm-e img,.lm-nx .lm-e b{width:100px;height:100px;object-fit:cover;flex:none;background:rgba(255,255,255,.08)}'
    + '.lm-nx .lm-e div{min-width:0}.lm-nx .lm-e p{margin:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}'
    + '.lm-nx .lm-e p:first-child{font-size:28px;font-weight:700}.lm-nx .lm-e p+p{font-size:22px;color:rgba(255,255,255,.6)}'
    + '.lm-ht{position:absolute;right:110px;top:66px;font-size:24px;color:rgba(255,255,255,.55);text-align:right;line-height:1.6}'
    + '.lm-ht b{color:#fff;font-weight:700;padding:2px 10px;border-radius:8px;background:rgba(255,255,255,.12);margin:0 4px}'
    + '.lm-kp{position:absolute;left:110px;top:60px;display:flex;align-items:center;gap:24px;opacity:0;transition:opacity .4s}'
    + '.lm-k .lm-kp{opacity:1}'
    + '.lm-kp img,.lm-kp b{width:96px;height:96px;border-radius:18px;object-fit:cover;background:rgba(255,255,255,.08)}'
    + '.lm-kp p{margin:0;white-space:nowrap;max-width:1200px;overflow:hidden;text-overflow:ellipsis}'
    + '.lm-kp p:first-child{font-size:40px;font-weight:800}.lm-kp p+p{font-size:28px;color:rgba(255,255,255,.7)}'
    + '.lm-cn{position:absolute;left:0;top:0;width:1920px;height:1080px;opacity:0;transition:opacity .4s}'
    + '.lm-k .lm-cn{opacity:1}'
    + '.lm-hw{position:absolute;left:50%;bottom:120px;transform:translateX(-50%);padding:18px 34px;border-radius:999px;'
    + 'background:rgba(20,22,28,.92);font-size:30px;font-weight:600;opacity:0;transition:opacity .3s;white-space:nowrap}'
    + '.lm-hw.an{opacity:1}';

  function bauen() {
    if (M.el) return;
    const st = document.createElement('style');
    st.textContent = CSS;
    document.head.appendChild(st);
    const w = document.createElement('div');
    w.className = 'lm-w';
    w.innerHTML = '<div class="lm-b">'
      + '<img class="lm-bg weg" alt="">'
      + '<div class="lm-dk"></div>'
      + '<div class="lm-p">'
      + '<div class="lm-cv"><img alt=""><div class="lm-note">♪</div><div class="lm-pause"></div></div>'
      + '<div class="lm-art">MUSIK</div><div class="lm-ti"></div><div class="lm-in"></div><div class="lm-al"></div>'
      + '<div class="lm-bar"><i></i></div><div class="lm-z" style="left:750px"></div><div class="lm-z" style="right:110px"></div>'
      + '<div class="lm-nx"><h3>ALS NÄCHSTES</h3><div class="lm-r"></div></div>'
      + '</div>'
      + '<canvas class="lm-cn" width="1920" height="1080"></canvas>'
      + '<div class="lm-kp"><img alt=""><div><p></p><p></p></div></div>'
      + '<div class="lm-ht"></div>'
      + '<div class="lm-hw"></div>'
      + '</div>';
    document.body.appendChild(w);
    const q = function (s) { return w.querySelector(s); };
    M.el = {
      w: w, b: q('.lm-b'), bg: q('.lm-bg'), cvImg: q('.lm-cv img'), note: q('.lm-note'), art: q('.lm-art'),
      ti: q('.lm-ti'), in: q('.lm-in'), al: q('.lm-al'), bar: q('.lm-bar i'), z1: w.querySelectorAll('.lm-z')[0],
      z2: w.querySelectorAll('.lm-z')[1], nx: q('.lm-nx .lm-r'), cn: q('.lm-cn'), kpImg: q('.lm-kp img'),
      kpT: q('.lm-kp p'), kpI: w.querySelectorAll('.lm-kp p')[1], ht: q('.lm-ht'), hw: q('.lm-hw')
    };
    M.ctx = M.el.cn.getContext('2d');
    M.el.cvImg.onload = function () { M.el.note.style.display = 'none'; };
    M.el.cvImg.onerror = function () { M.el.cvImg.removeAttribute('src'); M.el.note.style.display = 'flex'; };
    M.el.bg.onload = function () { M.el.bg.classList.remove('weg'); };
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

  function zeit(s) {
    s = Math.max(0, Math.floor(s || 0));
    return Math.floor(s / 60) + ':' + ('0' + (s % 60)).slice(-2);
  }

  function esc(s) {
    return String(s || '').replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; });
  }

  function hilfeText() {
    const e = aktuell();
    const k = e && e._karaoke;
    if (M.ansicht === 'karaoke')
      return '<b>OK</b> Pause &nbsp; <b>◀ ▶</b> Lied &nbsp; ' + (M.karaokeModus ? '' : '<b>▲</b> Player &nbsp; ') + '<b>Zurück</b> Ende';
    return '<b>OK</b> Pause &nbsp; <b>◀ ▶</b> Lied &nbsp; ' + (k ? '<b>▲</b> Karaoke &nbsp; ' : '')
      + '<b>▼</b> ' + (M.zufall ? 'Zufall aus' : 'Zufall an');
  }

  function aktuell() { return M.liste[M.pos] || null; }

  function playerZeigen() {
    const e = aktuell();
    if (!e || !M.el) return;
    const gross = bildUrl(e, 640), klein = bildUrl(e, 64);
    M.el.note.style.display = gross ? 'none' : 'flex';
    if (gross) M.el.cvImg.src = gross; else M.el.cvImg.removeAttribute('src');
    if (klein) {
      M.el.bg.classList.add('weg');
      M.el.bg.src = klein;
    } else M.el.bg.classList.add('weg');
    M.el.art.innerHTML = (M.karaokeModus ? 'KARAOKE' : 'MUSIK') + (e._karaoke && !M.karaokeModus ? '<span class="lm-pill">Karaoke</span>' : '')
      + (M.zufall ? '<span class="lm-pill">Zufall</span>' : '');
    M.el.ti.textContent = e.Name || dateiname(e);
    // Interpret und Album unter den Titel (Titel kann 1 oder 2 Zeilen haben)
    setTimeout(function () {
      const hoehe = M.el.ti.offsetHeight || 78;
      M.el.in.style.top = (282 + hoehe + 22) + 'px';
      M.el.al.style.top = (282 + hoehe + 82) + 'px';
    }, 0);
    M.el.in.textContent = interpret(e);
    M.el.al.textContent = [e.Album, e.ProductionYear].filter(Boolean).join(' · ');
    M.el.kpT.textContent = e.Name || '';
    M.el.kpI.textContent = interpret(e);
    if (gross) M.el.kpImg.src = bildUrl(e, 200); else M.el.kpImg.removeAttribute('src');
    let html = '';
    for (let i = 1; i <= 4 && M.pos + i < M.liste.length; i++) {
      const n = M.liste[M.pos + i];
      const b = bildUrl(n, 200);
      html += '<div class="lm-e">' + (b ? '<img src="' + esc(b) + '" alt="">' : '<b></b>') + '<div><p>' + esc(n.Name)
        + '</p><p>' + esc(interpret(n)) + '</p></div></div>';
    }
    M.el.nx.innerHTML = html;
    M.el.nx.parentNode.style.display = html ? '' : 'none';
    M.el.ht.innerHTML = hilfeText();
  }

  function ansichtSetzen(a) {
    M.ansicht = a;
    M.el.w.classList.toggle('lm-k', a === 'karaoke');
    M.el.ht.innerHTML = hilfeText();
    schleife();
  }

  // ------------------------------------------------------------------ Abspielen

  function spielen(i) {
    if (!M.liste.length) {
      hinweis(M.karaokeModus ? 'Noch keine Karaoke-Lieder da' : 'Keine Lieder gefunden', 3000);
      return;
    }
    meldenStopp();
    M.pos = (i + M.liste.length) % M.liste.length;
    const e = aktuell();
    if (!M.audio) {
      M.audio = new Audio();
      M.audio.preload = 'auto';
      M.audio.addEventListener('ended', function () { M.fehlerZaehler = 0; weiter(1); });
      M.audio.addEventListener('error', function () {
        M.fehlerZaehler++;
        hinweis('Lied lässt sich nicht abspielen');
        if (M.fehlerZaehler < 5) setTimeout(function () { weiter(1); }, 1500);
      });
      M.audio.addEventListener('playing', function () { M.el.w.classList.remove('lm-ps'); meldenStart(); });
      M.audio.addEventListener('pause', function () { M.el.w.classList.add('lm-ps'); });
    }
    M.audio.src = streamUrl(e);
    const p = M.audio.play();
    if (p && p.catch) p.catch(function () { /* Autoplay: erster OK-Druck startet */ });
    M.text = null;
    M.textFuer = e.Id;
    textLaden(e).then(function (t) {
      if (M.textFuer !== e.Id) return;
      M.text = t;
      if (M.karaokeModus && !t) weiter(1);
    });
    const n = M.liste[M.pos + 1];
    if (n && n._karaoke) textLaden(n);
    if (M.ansicht === 'karaoke' && !e._karaoke && !M.karaokeModus) ansichtSetzen('player');
    playerZeigen();
    schleife();
  }

  function weiter(d) {
    if (M.audio && d < 0 && M.audio.currentTime > 4) {
      M.audio.currentTime = 0;
      return;
    }
    spielen(M.pos + d);
  }

  function umschalten() {
    if (!M.audio) return;
    if (M.audio.paused) M.audio.play();
    else M.audio.pause();
  }

  function spulen(s) {
    if (!M.audio || !isFinite(M.audio.duration)) return;
    M.audio.currentTime = Math.max(0, Math.min(M.audio.duration - 1, M.audio.currentTime + s));
  }

  function mischen(a) {
    for (let i = a.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      const t = a[i];
      a[i] = a[j];
      a[j] = t;
    }
    return a;
  }

  function zufallUmschalten() {
    const e = aktuell();
    M.zufall = !M.zufall;
    const rest = M.liste.slice();
    rest.splice(M.pos, 1);
    if (M.zufall) mischen(rest);
    else rest.sort(function (a, b) { return (a.SortName || a.Name || '').localeCompare(b.SortName || b.Name || '', 'de'); });
    M.liste = [e].concat(rest);
    M.pos = 0;
    hinweis(M.zufall ? 'Zufall an' : 'Zufall aus');
    playerZeigen();
  }

  // Jellyfin soll wissen, was läuft („Zuletzt gehört“, Zähler)
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
      if (!text) continue; // leere Zeile = Instrumental
      const enh = z.roh.match(/<(\d+):(\d+(?:\.\d+)?)>\s*([^<]*)/g);
      const w = [];
      if (enh && enh.length > 1) {
        enh.forEach(function (stk, k) {
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
        // Singdauer: bis kurz vor der nächsten Zeile – bei langer Lücke (Instrumental danach) nach Silben schätzen
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

  /** Genaue Wort-Zeiten vom PC: {zeilen:[{s,e,w:[[s,e,"wort"],...]}]} */
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

  const SPUR = { links: 80, rechts: 1840, oben: 738, unten: 902, jetzt: 470, pps: 230, mitte: 820 };

  function rund(c, x, y, w, h, r) {
    r = Math.min(r, w / 2, h / 2);
    c.beginPath();
    c.moveTo(x + r, y);
    c.arcTo(x + w, y, x + w, y + h, r);
    c.arcTo(x + w, y + h, x, y + h, r);
    c.arcTo(x, y + h, x, y, r);
    c.arcTo(x, y, x + w, y, r);
    c.closePath();
  }

  /** Wörter einer Zeile in Bildschirmzeilen umbrechen; liefert [{w, x, y}] zentriert. */
  function satzLayout(c, z, groesse, oben, maxB) {
    c.font = '700 ' + groesse + 'px "Inter Tight", system-ui, sans-serif';
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
      if (!aktiv) {
        c.fillStyle = 'rgba(255,255,255,' + alpha + ')';
        c.fillText(w.t, p.x, p.y);
        return;
      }
      if (t >= w.e) {
        c.fillStyle = GELB;
        c.fillText(w.t, p.x, p.y);
      } else if (t >= w.s) {
        c.fillStyle = '#fff';
        c.fillText(w.t, p.x, p.y);
        const f = Math.max(0, Math.min(1, (t - w.s) / Math.max(0.05, w.e - w.s)));
        c.save();
        c.beginPath();
        c.rect(p.x - 2, p.y - groesse, (p.b + 4) * f, groesse * 1.4);
        c.clip();
        c.fillStyle = GELB;
        c.fillText(w.t, p.x, p.y);
        c.restore();
      } else {
        c.fillStyle = 'rgba(255,255,255,' + alpha + ')';
        c.fillText(w.t, p.x, p.y);
      }
    });
    return l.hoehe;
  }

  function karaokeZeichnen() {
    const c = M.ctx;
    c.clearRect(0, 0, STAGE_W, STAGE_H);
    const e = aktuell();
    const tx = M.text;
    const t = M.audio ? M.audio.currentTime : 0;
    if (!tx) {
      c.fillStyle = 'rgba(255,255,255,.75)';
      c.font = '600 46px "Inter Tight", system-ui, sans-serif';
      c.textAlign = 'center';
      c.fillText(e && e._karaoke ? 'Text wird geladen …' : 'Zu diesem Lied gibt es noch keinen Text', STAGE_W / 2, 480);
      c.textAlign = 'left';
      fortschrittZeichnen(c, t);
      return;
    }
    const Z = tx.zeilen;
    // aktuelle Zeile: die letzte, die schon angefangen hat und noch nicht lange vorbei ist
    let ai = -1;
    for (let i = 0; i < Z.length; i++) if (Z[i].s <= t + 0.05) ai = i;
    const akt = ai >= 0 && t <= Z[ai].e + 0.5 ? Z[ai] : null;
    const ni = akt ? ai + 1 : ai + 1;
    const naechste = Z[ni] || null;

    // ---- Satz oben
    if (akt) {
      const h = satzZeichnen(c, akt, t, 70, 380, 0.95, true);
      if (naechste) satzZeichnen(c, naechste, t, 44, 380 + h + 40, 0.5, false);
    } else if (naechste) {
      const rest = naechste.s - t;
      c.textAlign = 'center';
      if (rest <= 3.2) {
        const zahl = Math.max(1, Math.ceil(rest));
        c.fillStyle = 'rgba(242,183,5,.16)';
        c.beginPath();
        c.arc(STAGE_W / 2, 270, 66, 0, Math.PI * 2);
        c.fill();
        c.fillStyle = GELB;
        c.font = '800 80px "Inter Tight", system-ui, sans-serif';
        c.fillText(String(zahl), STAGE_W / 2, 298);
      } else {
        c.fillStyle = 'rgba(255,255,255,.6)';
        c.font = '600 34px "Inter Tight", system-ui, sans-serif';
        c.fillText((ai < 0 ? 'Intro' : 'Pause') + '  ·  noch ' + Math.ceil(rest) + ' s', STAGE_W / 2, 290);
      }
      c.textAlign = 'left';
      satzZeichnen(c, naechste, t, 62, 420, 0.7, false);
    } else {
      c.fillStyle = 'rgba(255,255,255,.55)';
      c.font = '600 40px "Inter Tight", system-ui, sans-serif';
      c.textAlign = 'center';
      c.fillText('♪', STAGE_W / 2, 460);
      c.textAlign = 'left';
    }

    // ---- Zeitspur unten
    const S = SPUR;
    c.fillStyle = 'rgba(255,255,255,.06)';
    rund(c, S.links, S.oben, S.rechts - S.links, S.unten - S.oben, 30);
    c.fill();
    c.save();
    rund(c, S.links, S.oben, S.rechts - S.links, S.unten - S.oben, 30);
    c.clip();
    const tMin = t - (S.jetzt - S.links) / S.pps, tMax = t + (S.rechts - S.jetzt) / S.pps;
    const X = function (zeitpunkt) { return S.jetzt + (zeitpunkt - t) * S.pps; };
    c.font = '700 24px "Inter Tight", system-ui, sans-serif';
    c.textBaseline = 'middle';
    for (let i = 0; i < Z.length; i++) {
      const z = Z[i];
      const vorEnde = i > 0 ? Z[i - 1].e : 0;
      const luecke = z.s - vorEnde;
      if (z.e < tMin - 1 || z.s - 4 > tMax) continue;
      // Pause beschriften und einzählen
      if (luecke >= 3) {
        if (luecke >= 4.5) {
          const mx = (X(vorEnde) + X(z.s - 3)) / 2;
          c.fillStyle = 'rgba(255,255,255,.4)';
          c.textAlign = 'center';
          c.fillText((i === 0 ? 'Intro ' : 'Pause ') + Math.round(luecke) + ' s', mx, S.mitte);
          c.textAlign = 'left';
        }
        for (let k = 1; k <= 3; k++) {
          const px = X(z.s - k);
          c.fillStyle = t >= z.s - k ? 'rgba(255,255,255,.18)' : GELB;
          c.beginPath();
          c.arc(px, S.mitte, 8, 0, Math.PI * 2);
          c.fill();
        }
      }
      // Zeilenanfang
      c.fillStyle = 'rgba(255,255,255,.22)';
      c.fillRect(X(z.s) - 2, S.oben + 22, 3, S.unten - S.oben - 44);
      // Balken je Wort
      z.w.forEach(function (w) {
        const x1 = X(w.s) + 4, x2 = X(w.e) - 4;
        if (x2 < S.links || x1 > S.rechts) return;
        const breite = Math.max(10, x2 - x1);
        const y = S.mitte - 21, h = 42;
        let textFarbe;
        if (t >= w.e) {
          c.fillStyle = 'rgba(255,255,255,.16)';
          rund(c, x1, y, breite, h, 14);
          c.fill();
          textFarbe = 'rgba(255,255,255,.4)';
        } else if (t >= w.s) {
          c.fillStyle = 'rgba(242,183,5,.35)';
          rund(c, x1, y, breite, h, 14);
          c.fill();
          c.save();
          c.beginPath();
          c.rect(x1, y, breite * Math.min(1, (t - w.s) / Math.max(0.05, w.e - w.s)), h);
          c.clip();
          c.fillStyle = GELB;
          rund(c, x1, y, breite, h, 14);
          c.fill();
          c.restore();
          textFarbe = '#141414';
        } else {
          c.fillStyle = 'rgba(255,255,255,.78)';
          rund(c, x1, y, breite, h, 14);
          c.fill();
          textFarbe = '#141414';
        }
        // Wort nur in den Balken schreiben, wenn es reinpasst – so überlappt nie etwas
        const tb = c.measureText(w.t).width;
        if (tb + 18 <= breite) {
          c.fillStyle = textFarbe;
          c.fillText(w.t, x1 + 9, S.mitte + 1);
        }
      });
    }
    c.restore();
    c.textBaseline = 'alphabetic';
    // Jetzt-Linie
    c.fillStyle = GELB;
    c.fillRect(S.jetzt - 2, S.oben - 14, 4, S.unten - S.oben + 28);
    c.beginPath();
    c.moveTo(S.jetzt - 12, S.oben - 22);
    c.lineTo(S.jetzt + 12, S.oben - 22);
    c.lineTo(S.jetzt, S.oben - 8);
    c.closePath();
    c.fill();
    if (tx.geschaetzt) {
      c.fillStyle = 'rgba(255,255,255,.35)';
      c.font = '500 22px "Inter Tight", system-ui, sans-serif';
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
    c.font = '500 26px "Inter Tight", system-ui, sans-serif';
    c.fillText(zeit(t), x, y + 48);
    c.textAlign = 'right';
    c.fillText(d ? zeit(d) : '', x + w, y + 48);
    c.textAlign = 'left';
  }

  function playerTick() {
    if (!M.audio || !M.el) return;
    const d = M.audio.duration, t = M.audio.currentTime || 0;
    M.el.bar.style.width = (isFinite(d) && d > 0 ? Math.min(100, t / d * 100) : 0) + '%';
    M.el.z1.textContent = zeit(t);
    M.el.z2.textContent = isFinite(d) ? zeit(d) : '';
  }

  function schleife() {
    cancelAnimationFrame(M.raf);
    clearInterval(M.timer);
    if (!M.offen) return;
    if (M.ansicht === 'karaoke') {
      const lauf = function () {
        if (!M.offen || M.ansicht !== 'karaoke') return;
        karaokeZeichnen();
        M.raf = requestAnimationFrame(lauf);
      };
      M.raf = requestAnimationFrame(lauf);
    } else {
      playerTick();
      M.timer = setInterval(playerTick, 250);
    }
  }

  // ------------------------------------------------------------------ Öffnen, Tasten, Schließen

  /**
   * o: {server, token, benutzer, auth?, geraet?, quelle: 'karaoke'|'alle'|'genre:<Gruppe>'|'ids:a,b', start, karaoke, zufall, zu}
   */
  async function oeffnen(o) {
    M.o = Object.assign({}, M.o || {}, o);
    bauen();
    M.offen = true;
    M.karaokeModus = !!o.karaoke;
    M.zufall = !!o.zufall;
    M.ansicht = o.karaoke ? 'karaoke' : 'player';
    M.el.w.classList.toggle('lm-k', M.ansicht === 'karaoke');
    M.el.w.style.display = '';
    void M.el.w.offsetWidth;
    M.el.w.classList.add('an');
    M.el.ti.textContent = '';
    M.el.in.textContent = '';
    M.el.al.textContent = '';
    hinweis('Musik wird geladen …', 1500);
    let k;
    try {
      k = await katalog(null);
    } catch (x) {
      hinweis('Jellyfin ist nicht erreichbar', 4000);
      return;
    }
    if (!M.offen) return;
    const q = o.quelle || 'alle';
    let liste = k.lieder.slice();
    if (q === 'karaoke') liste = liste.filter(function (e) { return e._karaoke; });
    else if (q.indexOf('genre:') === 0) {
      const g = q.slice(6);
      liste = liste.filter(function (e) { return e._gruppe === g; });
    } else if (q.indexOf('ids:') === 0) {
      const ids = q.slice(4).split(',');
      const nachId = {};
      liste.forEach(function (e) { nachId[e.Id] = e; });
      liste = ids.map(function (id) { return nachId[id]; }).filter(Boolean);
    }
    let start = 0;
    if (M.zufall) {
      mischen(liste);
      if (o.start) {
        const i = liste.findIndex(function (e) { return e.Id === o.start; });
        if (i > 0) liste.unshift(liste.splice(i, 1)[0]);
      }
    } else if (o.start) {
      start = Math.max(0, liste.findIndex(function (e) { return e.Id === o.start; }));
    }
    M.liste = liste;
    M.el.ht.innerHTML = hilfeText();
    spielen(start);
  }

  function schliessen() {
    if (!M.offen) return;
    M.offen = false;
    meldenStopp();
    if (M.audio) {
      M.audio.pause();
      M.audio.removeAttribute('src');
      M.audio.load();
    }
    cancelAnimationFrame(M.raf);
    clearInterval(M.timer);
    M.el.w.classList.remove('an');
    setTimeout(function () { if (!M.offen) M.el.w.style.display = 'none'; }, 380);
    const zu = M.o && M.o.zu;
    if (zu) setTimeout(zu, 0);
  }

  /** Taste vom Fernseher: 'ok','links','rechts','hoch','runter','zurueck','playpause','play','pause','vor','rueck','stop'. */
  function taste(n) {
    if (!M.offen) return false;
    const e = aktuell();
    switch (n) {
      case 'ok':
      case 'playpause':
        umschalten();
        break;
      case 'play':
        if (M.audio) M.audio.play();
        break;
      case 'pause':
        if (M.audio) M.audio.pause();
        break;
      case 'rechts':
        weiter(1);
        break;
      case 'links':
        weiter(-1);
        break;
      case 'vor':
        spulen(10);
        break;
      case 'rueck':
        spulen(-10);
        break;
      case 'hoch':
        if (M.ansicht === 'karaoke') {
          if (!M.karaokeModus) ansichtSetzen('player');
        } else if (e && e._karaoke) ansichtSetzen('karaoke');
        else hinweis('Zu diesem Lied gibt es noch keinen Karaoke-Text');
        break;
      case 'runter':
        if (M.ansicht === 'player') zufallUmschalten();
        break;
      case 'zurueck':
        if (M.ansicht === 'karaoke' && !M.karaokeModus) ansichtSetzen('player');
        else schliessen();
        break;
      case 'stop':
        schliessen();
        break;
      default:
        return false;
    }
    return true;
  }

  window.LukasMusik = {
    oeffnen: oeffnen,
    taste: taste,
    schliessen: schliessen,
    offen: function () { return M.offen; },
    katalog: katalog,
    gruppe: gruppe,
    gruppenFarbe: gruppenFarbe,
    interpret: interpret,
    bildUrl: bildUrl,
    lrcLesen: lrcLesen,
    GRUPPEN: GRUPPEN.map(function (g) { return g[0]; }).concat([SONSTIGES[0]])
  };
})();
