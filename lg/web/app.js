/*
 * Lukas Kino für die LG-Fernseher (webOS) – Web-Version der Kino-Oberfläche aus der Android-App
 * (FilmeActivity, PlayerActivity, Suche, Jellyfin). Läuft in der offiziellen Jellyfin-App aus dem LG Store:
 * Dort wird als Server http://192.168.178.44:8123/local/kino eingetragen. Die App lädt dann
 * /local/kino/web/index.html als Vollbild – und das ist diese Oberfläche.
 *
 * Bedienung wie am TCL: Pfeile, OK, Zurück (461). Links in einer Reihe = Glas-Seitenleiste.
 * Die Magic-Remote-Maus geht auch: Antippen öffnet, ganz an den linken Rand = Seitenleiste.
 *
 * Hier stehen keine Schlüssel: /local ist ohne Anmeldung lesbar. Der Jellyfin-Schlüssel entsteht
 * am Fernseher per Quick Connect und liegt nur dort (localStorage).
 */
(function () {
  'use strict';

  // ------------------------------------------------------------------ Einstellungen

  const parameter = {};
  location.search.replace(/^\?/, '').split('&').forEach(function (p) {
    if (!p) return;
    const t = p.split('=');
    parameter[decodeURIComponent(t[0])] = decodeURIComponent(t.slice(1).join('=') || '');
  });

  const SERVER = (parameter.server || 'http://192.168.178.44:8096').replace(/\/+$/, '');
  // Home Assistant (für die Merkliste): gleicher Rechner wie diese Seite, Port 8123
  const HA = (parameter.ha || (location.protocol + '//' + (location.hostname || '192.168.178.44') + ':8123')).replace(/\/+$/, '');

  // Wer schaut an diesem Fernseher? Steht in der Server-Adresse der LG-App:
  //   http://192.168.178.44:8098        -> Eltern (Wohnzimmer, Moni und Uli gemeinsam)
  //   http://192.168.178.44:8098/jonas  -> Jonas
  //   http://192.168.178.44:8098/lukas  -> Lukas
  // ich = Namen, die in HA zu diesem Fernseher gehören (Bewertungen, Verlauf, Merkliste).
  // ohneTon = Tonformate, die der Fernseher im Browser nicht abspielt – die wandelt der Pi um.
  const PERSONEN = {
    eltern: { name: 'Eltern', ha: 'Eltern', ich: ['Moni', 'Uli', 'Eltern'], listen: ['Moni', 'Uli', 'Eltern'], ohneTon: ['truehd', 'mlp'] },
    jonas: { name: 'Jonas', ha: 'Jonas', ich: ['Jonas'], listen: ['Jonas'], ohneTon: ['dts', 'dca', 'truehd', 'mlp'], leicht: true },
    lukas: { name: 'Lukas', ha: 'Lukas', ich: ['Lukas'], listen: ['Lukas'], ohneTon: ['truehd', 'mlp'] }
  };
  const PERSON = (function () {
    let k = String(parameter.person || '').toLowerCase();
    if (!PERSONEN[k]) {
      const m = String(location.pathname || '').match(/\/(eltern|jonas|lukas)\//i);
      k = m ? m[1].toLowerCase() : 'eltern';
    }
    return Object.assign({ schluessel: k }, PERSONEN[k]);
  })();
  // Leichter Modus (ohne Glas-Unschärfe und große Schatten) für schwächere Fernseher; ?leicht=1/0 erzwingt
  const LEICHT = parameter.leicht === '1' || (parameter.leicht !== '0' && !!PERSON.leicht);
  if (LEICHT) document.documentElement.classList.add('leicht');
  const CLIENT = 'Lukas Kino Web';
  const VERSION = '1.0.0';
  const GELB = '#F2B705';

  // Maße der Bühne (wie in der Android-App)
  const PW = 196, PH = 294;   // Poster 2:3
  const QW = 352, QH = 198;   // Querformat 16:9
  const ABSTAND = 26, LINKS = 110, REIHE_Y = 668;
  const POSTER = 0, QUER = 1;

  const FELDER = 'Overview,Genres,OfficialRating,CommunityRating,ProductionYear,RunTimeTicks,'
    + 'ImageTags,BackdropImageTags,UserData,ChildCount,OriginalTitle,ParentBackdropImageTags,DateCreated,PremiereDate,EndDate,ProviderIds,RemoteTrailers';
  const FELDER_FOTO = 'ImageTags,ChildCount,DateCreated,PremiereDate,RunTimeTicks,UserData,Width,Height';

  const FARBEN = {
    'Action': '#E3261B', 'Komödien': '#FFC61A', 'Horror': '#4A0F5E', 'Thriller': '#3B414B',
    'Science-Fiction': '#1592C4', 'Krimi': '#1D438F', 'Drama': '#8E2345', 'Abenteuer': '#2E8B3F',
    'Fantasy': '#6A3DE8', 'Mystery': '#2F6E73', 'Liebesfilme': '#E8508D', 'Familie & Animation': '#3E9BF0',
    'Familie und Animation': '#3E9BF0', 'Kriegsfilme': '#5E6B2B', 'Historienfilme': '#9C7442', 'Western': '#E9A23B'
  };

  // Oben in der Seitenleiste die Apps des Fernsehers (seit 04.11.2026, wie am TCL), dann Suche und die Bereiche.
  // Öffnen geht über den Webhook an Automation 84 (art=app) – die startet die App am Fernseher dieser Person.
  // Seit 04.11.2026 mittags wie am TCL (1.0.47): Spotify ganz oben, keine Überschrift, Reiter Musik unter Serien.
  const APPS = [
    { name: 'Spotify', app: 'spotify' },
    { name: 'Prime Video', app: 'prime' },
    { name: 'Netflix', app: 'netflix' },
    { name: 'YouTube', app: 'youtube' }
  ];
  const LEISTE = APPS.map(function (a) { return a.name; }).concat(['Suche', 'Filme', 'Serien', 'Musik', 'Merkliste', 'Empfohlen', 'Fotos']);
  // Oberkante des Leisten-Eintrags i (11 Einträge, Schritt 84, zwischen Apps und Suche 24 px Luft für die Linie).
  // Ohne Überschrift beginnt die Leiste oben; Abstand oben und unten zum Glasrand gleich (je 28 px).
  function leisteTop(i) { return 28 + 84 * i + (i >= APPS.length ? 24 : 0); }
  const BEREICHE = ['filme', 'serien', 'musik', 'merkliste', 'empfohlen', 'fotos'];
  const TASTEN = [
    ['a', 'b', 'c', 'd', 'e', 'f'], ['g', 'h', 'i', 'j', 'k', 'l'], ['m', 'n', 'o', 'p', 'q', 'r'],
    ['s', 't', 'u', 'v', 'w', 'x'], ['y', 'z', 'ä', 'ö', 'ü', 'ß'], ['1', '2', '3', '4', '5', '6'],
    ['7', '8', '9', '0', '-', '.']];
  const SUCH_SPALTEN = 5;

  const A = { LADEN: 'laden', ANMELDEN: 'anmelden', FEHLER: 'fehler', LISTE: 'liste', DETAIL: 'detail', SUCHE: 'suche', FOTO: 'foto' };

  // ------------------------------------------------------------------ kleine Helfer

  function speicherLesen(k) {
    try { return window.localStorage.getItem(k); } catch (e) { return null; }
  }

  function speicherSetzen(k, v) {
    try {
      if (v == null) window.localStorage.removeItem(k);
      else window.localStorage.setItem(k, v);
    } catch (e) { /* kein Speicher – dann eben nur für diese Sitzung */ }
  }

  function div(klasse, text) {
    const d = document.createElement('div');
    if (klasse) d.className = klasse;
    if (text != null) d.textContent = text;
    return d;
  }

  function svg(inhalt, klasse) {
    return '<svg' + (klasse ? ' class="' + klasse + '"' : '') + ' viewBox="0 0 24 24" fill="none" stroke="currentColor" '
      + 'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + inhalt + '</svg>';
  }

  const APP_SYMBOL = '<path d="M14 3.5h6.5V10M20.5 3.5L11.5 12.5"/><path d="M18.5 14v4.5a2 2 0 0 1-2 2h-11a2 2 0 0 1-2-2v-11a2 2 0 0 1 2-2H10"/>';
  const SYMBOLE = [
    '<circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.2-4.2"/>',
    '<rect x="3" y="5" width="18" height="14" rx="3"/><path d="M10 9l5 3-5 3z"/>',
    '<rect x="3" y="7" width="18" height="11" rx="2.5"/><path d="M8.5 3L12 7l3.5-4M8 21h8"/>',
    '<path d="M9 17.5V6l10.5-2v11.5M9 9.5l10.5-2"/><circle cx="6.6" cy="17.5" r="2.4"/><circle cx="17.1" cy="15.5" r="2.4"/>',
    '<path d="M6.5 3.5h11a1 1 0 0 1 1 1v16l-6.5-4.2-6.5 4.2v-16a1 1 0 0 1 1-1z"/>',
    '<path d="M12 3.2l2.7 5.5 6 .9-4.35 4.2 1 6-5.35-2.8-5.35 2.8 1-6L3.3 9.6l6-.9z"/>',
    '<rect x="3" y="5" width="18" height="14" rx="3"/><path d="M3.5 17L9 11.5l4 4 2.5-2.5 5 4.5"/><circle cx="16.2" cy="9.2" r="1.6"/>'
  ];

  function begrenzen(v, a, b) { return Math.max(a, Math.min(b, v)); }

  function zeit(sek) {
    sek = Math.max(0, Math.floor(sek || 0));
    const h = Math.floor(sek / 3600), m = Math.floor((sek % 3600) / 60), s = sek % 60;
    const zz = function (n) { return (n < 10 ? '0' : '') + n; };
    return h > 0 ? h + ':' + zz(m) + ':' + zz(s) : m + ':' + zz(s);
  }

  function dauer(ticks) {
    const min = Math.floor((ticks || 0) / 600000000);
    if (min <= 0) return null;
    const std = Math.floor(min / 60), rest = min % 60;
    return std > 0 ? std + ' Std. ' + rest + ' Min.' : rest + ' Min.';
  }

  function fsk(r) {
    if (!r) return null;
    const z = String(r).replace(/[^0-9]/g, '');
    return z ? 'FSK ' + z : null;
  }

  const MONATE = ['Januar', 'Februar', 'März', 'April', 'Mai', 'Juni', 'Juli', 'August', 'September', 'Oktober', 'November', 'Dezember'];

  function datum(e) {
    let d = e.PremiereDate || e.DateCreated || '';
    if (d.length < 10) return null;
    const t = d.substring(0, 10).split('-');
    const m = parseInt(t[1], 10), tag = parseInt(t[2], 10);
    if (!m || !tag) return null;
    return tag + '. ' + MONATE[begrenzen(m - 1, 0, 11)] + ' ' + t[0];
  }

  function mischen(a, b, t) {
    const pa = parseInt(a.slice(1), 16), pb = parseInt(b.slice(1), 16);
    const k = function (x, s) { return (x >> s) & 255; };
    const r = Math.round(k(pa, 16) + (k(pb, 16) - k(pa, 16)) * t);
    const g = Math.round(k(pa, 8) + (k(pb, 8) - k(pa, 8)) * t);
    const bl = Math.round(k(pa, 0) + (k(pb, 0) - k(pa, 0)) * t);
    return '#' + ((1 << 24) | (r << 16) | (g << 8) | bl).toString(16).slice(1);
  }

  function farbVerlauf(f) {
    return 'radial-gradient(circle 1300px at 1450px 300px, ' + f + ' 0%, ' + mischen(f, '#050608', 0.55) + ' 55%, #050608 100%)';
  }

  // ------------------------------------------------------------------ Gerät

  let geraetId = speicherLesen('lk_geraet');
  if (!geraetId) {
    geraetId = 'lukaskino-web-' + Math.random().toString(36).slice(2, 10);
    speicherSetzen('lk_geraet', geraetId);
  }
  const istWebos = /web0s|webos/i.test(navigator.userAgent);

  function geraetName() {
    // eindeutiger Name je Fernseher (in Jellyfin unter Geräte/Aktivität zu sehen)
    let n = parameter.name || ('Kino ' + PERSON.name);
    n = String(n).replace(/ä/g, 'ae').replace(/ö/g, 'oe').replace(/ü/g, 'ue').replace(/ß/g, 'ss');
    return n.replace(/[^\x20-\x7e]/g, '').replace(/"/g, '') || 'LG Fernseher';
  }

  // ------------------------------------------------------------------ Jellyfin

  const jf = {
    token: speicherLesen('lk_token'),
    benutzer: speicherLesen('lk_user'),

    angemeldet: function () { return !!(this.token && this.benutzer); },

    abmelden: function () {
      this.token = null;
      this.benutzer = null;
      speicherSetzen('lk_token', null);
      speicherSetzen('lk_user', null);
    },

    auth: function () {
      let s = 'MediaBrowser Client="' + CLIENT + '", Device="' + geraetName() + '", DeviceId="' + geraetId
        + '", Version="' + VERSION + '"';
      if (this.token) s += ', Token="' + this.token + '"';
      return s;
    },

    anfrage: async function (methode, pfad, body, alsText) {
      const opt = { method: methode, headers: { 'Authorization': this.auth(), 'Accept': 'application/json' } };
      let timer = 0;
      if (window.AbortController) {
        const ctrl = new AbortController();
        opt.signal = ctrl.signal;
        timer = setTimeout(function () { ctrl.abort(); }, 20000);
      }
      if (body !== undefined) {
        opt.headers['Content-Type'] = 'application/json';
        opt.body = JSON.stringify(body);
      }
      try {
        const r = await fetch(SERVER + pfad, opt);
        if (r.status === 401 && this.token) this.abmelden();
        if (!r.ok) {
          const f = new Error('Jellyfin ' + r.status + ' ' + pfad);
          f.status = r.status;
          throw f;
        }
        const t = await r.text();
        if (alsText) return t;
        return t ? JSON.parse(t) : {};
      } finally {
        clearTimeout(timer);
      }
    },

    liste: async function (pfad) {
      const o = await this.anfrage('GET', pfad);
      return (o && o.Items) || [];
    },

    weiterschauen: function () {
      return this.liste('/UserItems/Resume?userId=' + this.benutzer + '&mediaTypes=Video&includeItemTypes=Movie&limit=24&fields='
        + FELDER + '&enableImageTypes=Primary,Backdrop,Logo');
    },
    neu: function () {
      return this.liste('/Items?userId=' + this.benutzer + '&includeItemTypes=Movie&recursive=true&sortBy=DateCreated'
        + '&sortOrder=Descending&limit=40&fields=' + FELDER);
    },
    sammlungen: function () {
      return this.liste('/Items?userId=' + this.benutzer + '&includeItemTypes=BoxSet&recursive=true&sortBy=SortName&fields=ChildCount');
    },
    inhalt: function (id) {
      return this.liste('/Items?userId=' + this.benutzer + '&parentId=' + id
        + '&includeItemTypes=Movie&sortBy=PremiereDate,SortName&sortOrder=Descending&fields=' + FELDER);
    },
    alle: function () {
      return this.liste('/Items?userId=' + this.benutzer + '&includeItemTypes=Movie&recursive=true&sortBy=SortName&fields=' + FELDER);
    },
    bibliotheken: function () { return this.liste('/UserViews?userId=' + this.benutzer); },
    serienWeiterschauen: function () {
      return this.liste('/UserItems/Resume?userId=' + this.benutzer + '&mediaTypes=Video&includeItemTypes=Episode&limit=24&fields=' + FELDER);
    },
    naechsteFolgen: function () {
      return this.liste('/Shows/NextUp?userId=' + this.benutzer + '&limit=24&fields=' + FELDER);
    },
    serien: function () {
      return this.liste('/Items?userId=' + this.benutzer + '&includeItemTypes=Series&recursive=true&sortBy=SortName&fields=' + FELDER);
    },
    neueSerien: function () {
      return this.liste('/Items?userId=' + this.benutzer + '&includeItemTypes=Series&recursive=true'
        + '&sortBy=DateLastContentAdded,DateCreated&sortOrder=Descending&limit=30&fields=' + FELDER);
    },
    staffeln: function (serie) {
      return this.liste('/Shows/' + serie + '/Seasons?userId=' + this.benutzer + '&fields=' + FELDER);
    },
    folgen: function (serie, staffel) {
      return this.liste('/Shows/' + serie + '/Episodes?userId=' + this.benutzer + '&seasonId=' + staffel + '&fields=' + FELDER);
    },
    // Oberste Ebene der Fotos nur nach Name: die Datums-Sortierung kostet am Pi mehrere Sekunden
    fotoWurzel: function (id) {
      return this.liste('/Items?userId=' + this.benutzer + '&parentId=' + id + '&sortBy=SortName&fields=ChildCount,ImageTags,DateCreated,PremiereDate');
    },
    // Erstes Foto eines Ordners: erst direkt (ca. 40 ms), sonst in Unterordnern – ohne Sortierung (sortiert ca. 5 s)
    erstesFoto: async function (ordner) {
      // nur Fotos, die schon ein Vorschaubild haben (imageTypes=Primary) – der Scan erzeugt sie erst nach und nach
      let a = await this.liste('/Items?userId=' + this.benutzer + '&parentId=' + ordner
        + '&includeItemTypes=Photo&imageTypes=Primary&limit=1&fields=ImageTags');
      if (!a.length) {
        a = await this.liste('/Items?userId=' + this.benutzer + '&parentId=' + ordner
          + '&recursive=true&includeItemTypes=Photo&imageTypes=Primary&limit=1&fields=ImageTags');
      }
      return a[0] || null;
    },
    ordner: function (id) {
      return this.liste('/Items?userId=' + this.benutzer + '&parentId=' + id
        + '&sortBy=IsFolder,PremiereDate,DateCreated,SortName&sortOrder=Ascending&fields=' + FELDER_FOTO);
    },
    suchIndex: function () {
      return this.liste('/Items?userId=' + this.benutzer + '&includeItemTypes=Movie,Series&recursive=true&sortBy=SortName&fields=' + FELDER);
    },
    film: function (id) {
      return this.anfrage('GET', '/Items/' + id + '?userId=' + this.benutzer + '&fields=' + FELDER + ',MediaSources,MediaStreams');
    },
    streamUrl: function (id, quelle) {
      return SERVER + '/Videos/' + id + '/stream?static=true&mediaSourceId=' + encodeURIComponent(quelle)
        + '&deviceId=' + encodeURIComponent(geraetId) + '&ApiKey=' + this.token + '&api_key=' + this.token;
    },
    // Über den Pi: Bild wird nur umverpackt (kein Umrechnen), der Ton wird zu Dolby Digital 5.1 (AC3) –
    // oder ebenfalls nur kopiert, wenn die gewählte Spur schon AC3/E-AC3/AAC ist.
    hlsUrl: function (id, quelle, sitzung, tonIndex) {
      let u = SERVER + '/Videos/' + id + '/master.m3u8?MediaSourceId=' + encodeURIComponent(quelle)
        + '&DeviceId=' + encodeURIComponent(geraetId) + '&PlaySessionId=' + encodeURIComponent(sitzung)
        + '&VideoCodec=hevc,h264,av1&AudioCodec=ac3,eac3,aac,mp3&AllowVideoStreamCopy=true&AllowAudioStreamCopy=true'
        + '&TranscodingMaxAudioChannels=6&AudioBitrate=640000&SegmentContainer=mp4&MinSegments=1&RequireAvc=false'
        + '&ApiKey=' + this.token + '&api_key=' + this.token;
      if (tonIndex != null && tonIndex >= 0) u += '&AudioStreamIndex=' + tonIndex;
      return u;
    }
  };

  // Bilder (Jellyfin liefert sie ohne Anmeldung)
  function mitTag(tag) { return tag ? '&tag=' + tag : ''; }
  function posterUrl(id, tag, h) { return SERVER + '/Items/' + id + '/Images/Primary?fillHeight=' + h + '&quality=90' + mitTag(tag); }
  function hintergrundBild(id, tag) {
    return SERVER + '/Items/' + id + '/Images/Backdrop/0?fillWidth=' + (LEICHT ? 1280 : 1920) + '&quality=' + (LEICHT ? 80 : 85) + mitTag(tag);
  }
  function logoBild(id, tag) { return SERVER + '/Items/' + id + '/Images/Logo?fillHeight=220&quality=90' + mitTag(tag); }
  function querUrl(id, tag, b) { return SERVER + '/Items/' + id + '/Images/Primary?fillWidth=' + b + '&quality=85' + mitTag(tag); }
  function fotoUrl(id, tag) { return SERVER + '/Items/' + id + '/Images/Primary?maxWidth=1920&maxHeight=1080&quality=90' + mitTag(tag); }

  function tag(e, art) { return (e && e.ImageTags && e.ImageTags[art]) || null; }
  function hintergrundTag(e) { return e && e.BackdropImageTags && e.BackdropImageTags.length ? e.BackdropImageTags[0] : null; }
  function typ(e) { return e ? e.Type || '' : ''; }
  function abspielbar(e) { const t = typ(e); return t === 'Movie' || t === 'Episode'; }
  function istOrdner(e) { const t = typ(e); return t === 'Folder' || t === 'PhotoAlbum' || t === 'CollectionFolder'; }
  function position(e) { return (e && e.UserData && e.UserData.PlaybackPositionTicks) || 0; }
  function gesehen(e) { return !!(e && e.UserData && e.UserData.Played); }

  function fortschritt(e) {
    const u = e && e.UserData;
    if (!u) return 0;
    let p = u.PlayedPercentage || 0;
    if (p <= 0) {
      const pos = u.PlaybackPositionTicks || 0, ges = e.RunTimeTicks || 0;
      if (pos > 0 && ges > 0) p = 100 * pos / ges;
    }
    return begrenzen(p / 100, 0, 1);
  }

  // ------------------------------------------------------------------ Unscharfe Suche (aus Suche.java)

  const Suche = {
    normal: function (s) {
      if (!s) return '';
      let t = String(s).toLowerCase().replace(/ä/g, 'ae').replace(/ö/g, 'oe').replace(/ü/g, 'ue').replace(/ß/g, 'ss')
        .replace(/&/g, ' und ');
      if (t.normalize) t = t.normalize('NFD').replace(/[̀-ͯ]+/g, '');
      return t.replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim();
    },
    kompakt: function (s) { return s.replace(/ /g, ''); },
    erlaubt: function (n) { return n <= 3 ? 0 : n <= 5 ? 1 : n <= 8 ? 2 : 3; },

    punkte: function (anfrage, titel) {
      const q = this.normal(anfrage);
      if (!q) return 0;
      const qk = this.kompakt(q);
      let best = 0;
      for (let i = 0; i < titel.length; i++) {
        const roh = titel[i];
        if (!roh) continue;
        const t = this.normal(roh), tk = this.kompakt(t);
        let p = 0;
        if (t.indexOf(q) === 0 || tk.indexOf(qk) === 0) p = 1000 - Math.min(200, t.length - q.length);
        else if (this.wortAnfang(t, q)) p = 900 - Math.min(200, t.length - q.length);
        else if (t.indexOf(q) >= 0 || tk.indexOf(qk) >= 0) p = 800 - Math.min(200, t.length - q.length);
        else {
          const fehler = Math.min(this.abstandIrgendwo(qk, tk), this.wortAbstand(q, t));
          if (fehler <= this.erlaubt(qk.length)) p = 600 - fehler * 120 - Math.min(100, Math.abs(tk.length - qk.length));
        }
        best = Math.max(best, p);
      }
      return best;
    },

    wortAnfang: function (t, q) {
      const ws = t.split(' ');
      for (let i = 0; i < ws.length; i++) if (ws[i].indexOf(q) === 0) return true;
      const qs = q.split(' ');
      if (qs.length < 2) return false;
      for (let a = 0; a < qs.length; a++) {
        let gef = false;
        for (let b = 0; b < ws.length; b++) if (ws[b].indexOf(qs[a]) === 0) { gef = true; break; }
        if (!gef) return false;
      }
      return true;
    },

    wortAbstand: function (q, t) {
      const ws = t.split(' '), qq = q.replace(/ /g, '');
      let best = Infinity;
      for (let i = 0; i < ws.length; i++) {
        best = Math.min(best, this.damerau(qq, ws[i]));
        if (i + 1 < ws.length) best = Math.min(best, this.damerau(qq, ws[i] + ws[i + 1]));
      }
      return best;
    },

    abstandIrgendwo: function (q, t) {
      const m = q.length, n = t.length;
      if (m === 0) return 0;
      if (n === 0) return m;
      const d = [];
      for (let i = 0; i <= m; i++) { d.push(new Array(n + 1)); d[i][0] = i; }
      for (let j = 0; j <= n; j++) d[0][j] = 0;
      for (let i = 1; i <= m; i++) {
        for (let j = 1; j <= n; j++) {
          const k = q[i - 1] === t[j - 1] ? 0 : 1;
          let v = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + k);
          if (i > 1 && j > 1 && q[i - 1] === t[j - 2] && q[i - 2] === t[j - 1]) v = Math.min(v, d[i - 2][j - 2] + 1);
          d[i][j] = v;
        }
      }
      let best = Infinity;
      for (let j = 0; j <= n; j++) best = Math.min(best, d[m][j]);
      return best;
    },

    damerau: function (a, b) {
      const m = a.length, n = b.length, d = [];
      for (let i = 0; i <= m; i++) { d.push(new Array(n + 1)); d[i][0] = i; }
      for (let j = 0; j <= n; j++) d[0][j] = j;
      for (let i = 1; i <= m; i++) {
        for (let j = 1; j <= n; j++) {
          const k = a[i - 1] === b[j - 1] ? 0 : 1;
          let v = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + k);
          if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) v = Math.min(v, d[i - 2][j - 2] + 1);
          d[i][j] = v;
        }
      }
      return d[m][n];
    },

    suchen: function (anfrage, alle, max) {
      const l = [];
      for (let i = 0; i < alle.length; i++) {
        const e = alle[i];
        const p = this.punkte(anfrage, [e.Name, e.OriginalTitle]);
        if (p > 0) l.push({ e: e, p: p, t: e.Name || '' });
      }
      l.sort(function (a, b) { return a.p !== b.p ? b.p - a.p : a.t.toLowerCase().localeCompare(b.t.toLowerCase()); });
      return l.slice(0, max).map(function (x) { return x.e; });
    }
  };
  window.LukasKinoSuche = Suche; // für Tests

  // ------------------------------------------------------------------ Filme nach Titel finden
  // Home Assistant kennt Filme mit Plex-ID und "Titel (Jahr)". Zugeordnet wird über Name und Jahr
  // (Jahr ±1, weil Plex und Jellyfin manchmal ein Jahr auseinander liegen), sonst über einen eindeutigen Namen.

  const filmIndex = { laedt: null, fertig: false, schluessel: {}, name: {}, jfZuSchluessel: {} };

  function filmIndexLaden() {
    if (filmIndex.laedt) return filmIndex.laedt;
    filmIndex.laedt = jf.liste('/Items?userId=' + jf.benutzer + '&includeItemTypes=Movie&recursive=true'
      + '&fields=OriginalTitle&enableImages=false&enableUserData=false').then(function (a) {
      const k = {}, n = {}, r = {};
      a.forEach(function (e) {
        const s = familienSchluessel(e.Name, e.ProductionYear);
        if (!k[s]) k[s] = e.Id;
        r[e.Id] = s;
        if (e.OriginalTitle) {
          const o = familienSchluessel(e.OriginalTitle, e.ProductionYear);
          if (!k[o]) k[o] = e.Id;
        }
        const nn = Suche.normal(e.Name);
        n[nn] = n[nn] === undefined ? e.Id : null; // null = mehrdeutig
      });
      filmIndex.schluessel = k;
      filmIndex.name = n;
      filmIndex.jfZuSchluessel = r;
      filmIndex.fertig = true;
    }).catch(function () { filmIndex.laedt = null; });
    return filmIndex.laedt;
  }

  /** Jellyfin-ID zu einem Plex-Titel "Name (Jahr)", sonst null. */
  function jfIdFuerTitel(t) {
    const p = plexTitel(t);
    if (!p.name) return null;
    const j = parseInt(p.jahr, 10);
    const k = filmIndex.schluessel;
    return k[familienSchluessel(p.name, p.jahr)]
      || (j ? k[familienSchluessel(p.name, j - 1)] || k[familienSchluessel(p.name, j + 1)] : null)
      || filmIndex.name[Suche.normal(p.name)] || null;
  }

  // ------------------------------------------------------------------ Merkliste (dieselbe wie im HA-Profil)
  // Quelle ist sensor.plex_merkliste in Home Assistant (über bewertungen.json, Paket kino_export.yaml).
  // Am Eltern-Fernseher gilt die gemeinsame Liste von Moni und Uli, bei Jonas seine, bei Lukas seine.
  // Merken/Entfernen geht per Webhook an Automation 84 – die ändert dieselbe Liste wie das Dashboard.
  // Die Webhook-Adresse steht nur in /share/kino/kino_geheim.json (nicht im Code).

  const merk = {
    ids: [],          // Jellyfin-IDs, zuletzt gemerkt zuerst
    items: {},        // Jellyfin-ID -> Eintrag
    haIds: {},        // Jellyfin-ID -> [IDs in HA] (Plex-ID oder jf:<Jellyfin-ID>)
    lokal: {},        // Jellyfin-ID -> {an, bis}: gerade geändert, HA-Datei hängt noch hinterher
    plex: null,       // Name|Jahr -> Plex-ID (aus plexfilme.json und den Bewertungen)
    webhook: null,
    roh: null,        // letzte Daten aus HA

    laden: async function () {
      // nur noch für den Start: HA-Daten kommen über familieLaden()
      await filmIndexLaden();
      if (this.roh) await this.ausHa(this.roh);
    },

    /** Liste dieses Fernsehers aus den HA-Daten übernehmen. */
    ausHa: async function (j) {
      this.roh = j;
      if (!filmIndex.fertig) await filmIndexLaden();
      const listen = (j && j.merkliste) || {}, titel = (j && j.merktitel) || {};
      const reihenfolge = [], haIds = {};
      PERSON.listen.forEach(function (w) {
        (listen[w] || []).forEach(function (roh) {
          const id = String(roh);
          let jfId = null;
          if (id.indexOf('jf:') === 0) jfId = id.substring(3);
          else if (titel[id]) jfId = jfIdFuer(id, titel[id]);
          else jfId = jfIdFuer(id, null);
          if (!jfId) return;
          if (reihenfolge.indexOf(jfId) < 0) reihenfolge.push(jfId);
          (haIds[jfId] = haIds[jfId] || []);
          if (haIds[jfId].indexOf(id) < 0) haIds[jfId].push(id);
        });
      });
      // eigene Änderungen der letzten Sekunden gelten, bis HA sie bestätigt
      const jetzt = Date.now(), self = this;
      Object.keys(this.lokal).forEach(function (jfId) {
        const l = self.lokal[jfId];
        const drin = reihenfolge.indexOf(jfId) >= 0;
        if (l.bis < jetzt || drin === l.an) { delete self.lokal[jfId]; return; }
        if (l.an) reihenfolge.unshift(jfId);
        else reihenfolge.splice(reihenfolge.indexOf(jfId), 1);
      });
      const fehlen = reihenfolge.filter(function (id) { return !self.items[id]; });
      if (fehlen.length) {
        for (let i = 0; i < fehlen.length; i += 60) {
          try {
            const a = await jf.liste('/Items?userId=' + jf.benutzer + '&ids=' + fehlen.slice(i, i + 60).join(',') + '&fields=' + FELDER);
            a.forEach(function (e) { self.items[e.Id] = e; });
          } catch (e) { /* dann fehlen sie eben */ }
        }
      }
      const neu = reihenfolge.filter(function (id) { return !!self.items[id]; });
      this.haIds = haIds;
      if (neu.join(',') !== this.ids.join(',')) {
        this.ids = neu;
        merklisteGeaendert();
      }
    },

    hat: function (id) { return this.ids.indexOf(id) >= 0; },

    liste: function () {
      const self = this;
      return this.ids.map(function (id) { return self.items[id]; }).filter(Boolean);
    },

    /** HA-ID zu einem Jellyfin-Film (alte Plex-ID oder jf:<ID>, für dieselbe Liste wie im Dashboard), sonst null. */
    plexIdFuer: function (e) {
      if (this.jfZuHa && this.jfZuHa[e.Id]) return this.jfZuHa[e.Id];
      if (!this.plex) return null;
      const j = parseInt(e.ProductionYear, 10) || 0;
      const namen = [e.Name, e.OriginalTitle].filter(Boolean);
      for (let i = 0; i < namen.length; i++) {
        const n = namen[i];
        const t = this.plex[familienSchluessel(n, j || '')] || (j ? this.plex[familienSchluessel(n, j - 1)] || this.plex[familienSchluessel(n, j + 1)] : null);
        if (t) return t;
      }
      return null;
    },

    // plexfilme.json = Film-Katalog aus HA (seit 02.11.2026 aus Jellyfin, sensor.jellyfin_katalog):
    // { filme: { HA-ID: {t, j, jf} } } – HA-ID ist die alte Plex-ID, sonst jf:<Jellyfin-ID>
    plexLaden: async function () {
      if (this.plex && Date.now() - (this.plexZeit || 0) < 1800000) return;
      const p = {}, zuHa = {}, zuJf = {};
      try {
        const r = await fetch('../plexfilme.json?t=' + Date.now());
        if (r.ok) {
          const f = ((await r.json()) || {}).filme || {};
          Object.keys(f).forEach(function (id) {
            if (f[id] && f[id].t) p[familienSchluessel(f[id].t, f[id].j || '')] = id;
            if (f[id] && f[id].jf) {
              zuHa[f[id].jf] = id;
              zuJf[id] = f[id].jf;
            }
          });
        }
      } catch (e) { /* ohne Katalog: dann jf:<ID> */ }
      this.jfZuHa = zuHa;
      this.haZuJf = zuJf;
      this.plexZeit = Date.now();
      const roh = this.roh || {};
      [roh.bewertungen || {}, roh.merktitel || {}].forEach(function (q, art) {
        Object.keys(q).forEach(function (id) {
          if (id.indexOf('jf:') === 0) return;
          const t = plexTitel(art === 0 ? q[id].t : q[id]);
          const k = familienSchluessel(t.name, t.jahr);
          if (!p[k]) p[k] = id;
        });
      });
      this.plex = p;
    },

    webhookLaden: async function () {
      if (this.webhook) return this.webhook;
      const r = await fetch('../kino_geheim.json?t=' + Date.now());
      if (!r.ok) throw new Error('kein Webhook');
      const j = await r.json();
      if (!j || !j.merkWebhook) throw new Error('kein Webhook');
      this.webhook = HA + '/api/webhook/' + encodeURIComponent(j.merkWebhook);
      return this.webhook;
    },

    senden: async function (daten) {
      const url = await this.webhookLaden();
      const body = Object.keys(daten).map(function (k) {
        return encodeURIComponent(k) + '=' + encodeURIComponent(daten[k]);
      }).join('&');
      // no-cors: HA antwortet ohne CORS-Freigabe, die Antwort brauchen wir nicht
      await fetch(url, { method: 'POST', mode: 'no-cors', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body });
    },

    umschalten: function (e) {
      const self = this;
      const an = !this.hat(e.Id);
      if (an) {
        this.ids.unshift(e.Id);
        this.items[e.Id] = e;
      } else {
        this.ids = this.ids.filter(function (x) { return x !== e.Id; });
      }
      this.lokal[e.Id] = { an: an, bis: Date.now() + 120000 };
      merklisteGeaendert();
      (async function () {
        try {
          await self.plexLaden();
          const titel = (e.Name || '') + (e.ProductionYear ? ' (' + e.ProductionYear + ')' : '');
          let ids;
          if (an) ids = [self.plexIdFuer(e) || 'jf:' + e.Id];
          else ids = (self.haIds[e.Id] || []).concat([self.plexIdFuer(e), 'jf:' + e.Id]).filter(function (x, i, a) { return x && a.indexOf(x) === i; });
          for (let i = 0; i < ids.length; i++) {
            await self.senden({ wer: PERSON.ha, id: ids[i], titel: titel, an: an ? 'true' : 'false' });
          }
          // HA schreibt die Datei sofort neu – kurz danach abholen
          setTimeout(familieLaden, 2500);
        } catch (x) {
          hinweis('Merkliste ließ sich nicht speichern');
          delete self.lokal[e.Id];
          setTimeout(familieLaden, 500);
        }
      })();
    }
  };

  /** Jellyfin-ID zu einer Plex-ID aus HA (über den Titel). */
  function jfIdFuer(plexId, titel) {
    if (titel) return jfIdFuerTitel(titel);
    const b = merk.roh && merk.roh.bewertungen && merk.roh.bewertungen[plexId];
    return b && b.t ? jfIdFuerTitel(b.t) : null;
  }

  function merklisteSeite() {
    const s = neueSeite('Merkliste');
    s.ohneFilter = true;
    const l = merk.liste();
    const filme = l.filter(function (e) { return typ(e) !== 'Series'; });
    const serien = l.filter(function (e) { return typ(e) === 'Series'; });
    if (filme.length) s.reihen.push(reiheAus('Filme', filme, POSTER));
    if (serien.length) s.reihen.push(reiheAus('Serien', serien, POSTER));
    if (!s.reihen.length) s.leer = 'Noch nichts gemerkt';
    return s;
  }

  /** Merkliste geändert: Reihe auf der Filme-Startseite und die Merkliste-Seite nachziehen. */
  function merklisteGeaendert() {
    const l = merk.liste();
    const start = startseiten.filme;
    if (start) {
      let i = -1;
      start.reihen.forEach(function (r, k) { if (r.titel === 'Merkliste' && r._merk) i = k; });
      if (l.length && i >= 0) {
        const r = start.reihen[i];
        r.eintraege = l.slice();
        r.fokus = Math.min(r.fokus, l.length - 1);
        r.version++;
      } else if (l.length) {
        const r = reiheAus('Merkliste', l, POSTER);
        r._merk = true;
        const pos = start.reihen.length && start.reihen[0].titel === 'Weiterschauen' ? 1 : 0;
        start.reihen.splice(pos, 0, r);
        if (start.akt >= pos && start.reihen.length > 1) start.akt++;
      } else if (i >= 0) {
        start.reihen.splice(i, 1);
        if (start.akt > i || start.akt >= start.reihen.length) start.akt = Math.max(0, start.akt - 1);
      }
    }
    const alt = startseiten.merkliste;
    const neu = merklisteSeite();
    startseiten.merkliste = neu;
    if (alt && seite === alt) {
      neu.akt = Math.min(alt.akt, Math.max(0, neu.reihen.length - 1));
      seite = neu;
    }
    zeichnen();
  }

  // ------------------------------------------------------------------ Trailer (KinoCheck auf Deutsch, sonst Jellyfin)

  let trailerSpeicher = {};
  try { trailerSpeicher = JSON.parse(speicherLesen('lk_trailer') || '{}') || {}; } catch (e) { trailerSpeicher = {}; }

  function ytId(u) {
    const m = String(u || '').match(/(?:v=|youtu\.be\/|embed\/|shorts\/)([A-Za-z0-9_-]{11})/);
    return m ? m[1] : null;
  }

  function deutschWert(name) { return /german|deutsch|\bde\b|offiziell/i.test(name || '') ? 1 : 0; }

  // Liste vom Pi (werkzeuge/kino_pflege.py trailer, /share/kino/trailer.json): dort ist jeder Film schon geprüft
  // (gibt es den Trailer, darf man ihn einbetten, deutsch zuerst, ohne Untertitel). Nur für Filme, die dort noch
  // fehlen (ganz neu), sucht die Oberfläche selbst wie bisher.
  let trailerListe = null, trailerListeZeit = 0;

  async function trailerListeHolen() {
    if (trailerListe && Date.now() - trailerListeZeit < 10 * 60000) return trailerListe;
    try {
      const r = await fetch('../trailer.json?t=' + Date.now());
      if (r.ok) {
        const j = await r.json();
        trailerListe = (j && j.filme) || {};
        trailerListeZeit = Date.now();
      }
    } catch (x) { /* dann selbst suchen */ }
    return trailerListe || {};
  }

  async function trailerSuchen(e) {
    const liste = await trailerListeHolen();
    const eintrag = liste[e.Id];
    if (eintrag) return eintrag.yt ? { yt: eintrag.yt, titel: eintrag.t || '', quelle: eintrag.q || 'Pi' } : null;
    const tmdb = e.ProviderIds && (e.ProviderIds.Tmdb || e.ProviderIds.tmdb);
    const key = tmdb ? 'm' + tmdb : 'j' + e.Id;
    const s = trailerSpeicher[key];
    if (s && (s.yt || Date.now() - s.z < 3 * 86400000)) return s.yt ? s : null;
    let tr = null;
    if (tmdb) {
      try {
        const r = await fetch('https://api.kinocheck.de/movies?tmdb_id=' + encodeURIComponent(tmdb) + '&language=de&categories=Trailer');
        if (r.ok) {
          const j = await r.json();
          let v = j && j.trailer && j.trailer.youtube_video_id ? j.trailer : null;
          if (!v && j && j.videos) {
            v = j.videos.filter(function (x) {
              return x.youtube_video_id && x.language === 'de' && (x.categories || []).indexOf('Trailer') >= 0;
            })[0] || null;
          }
          if (v) tr = { yt: v.youtube_video_id, titel: v.title || '', quelle: 'KinoCheck' };
        }
      } catch (x) { /* KinoCheck nicht erreichbar – dann Jellyfin */ }
    }
    if (!tr && e.RemoteTrailers && e.RemoteTrailers.length) {
      const l = e.RemoteTrailers.map(function (t) { return { yt: ytId(t.Url), titel: t.Name || '' }; })
        .filter(function (t) { return t.yt; });
      l.sort(function (a, b) { return deutschWert(b.titel) - deutschWert(a.titel); });
      if (l.length) tr = { yt: l[0].yt, titel: l[0].titel, quelle: 'Jellyfin' };
    }
    trailerSpeicher[key] = tr ? Object.assign({ z: Date.now() }, tr) : { yt: '', z: Date.now() };
    speicherSetzen('lk_trailer', JSON.stringify(trailerSpeicher));
    return tr;
  }

  // ------------------------------------------------------------------ Familien-Bewertungen aus Home Assistant
  // HA schreibt sie alle 5 Minuten nach /share/kino/bewertungen.json (Paket kino_export.yaml).
  // Plex-Titel "Name (Jahr)" werden über Name und Jahr den Jellyfin-Filmen zugeordnet.

  const FAMILIE = { Lukas: '#F2B705', Moni: '#E8559A', Uli: '#1FA463', Jonas: '#2F6FEB', Eltern: '#E8559A' };
  const familie = { noten: {}, gesehen: {}, nurName: {} };

  function familienSchluessel(name, jahr) { return Suche.normal(name) + '|' + (jahr || ''); }

  function plexTitel(t) {
    const m = String(t || '').match(/^(.*)\s\((\d{4})\)\s*$/);
    return m ? { name: m[1], jahr: m[2] } : { name: String(t || ''), jahr: '' };
  }

  async function familieLaden() {
    try {
      const r = await fetch('../bewertungen.json?t=' + Date.now());
      if (!r.ok) return;
      const j = await r.json();
      const noten = {}, gesehen = {}, nurName = {};
      Object.keys(j.bewertungen || {}).forEach(function (id) {
        const f = j.bewertungen[id], t = plexTitel(f.t);
        const k = familienSchluessel(t.name, t.jahr);
        noten[k] = f.w || {};
        nurName[Suche.normal(t.name)] = nurName[Suche.normal(t.name)] === undefined ? k : null; // null = mehrdeutig
      });
      (j.verlauf || []).forEach(function (v) {
        if (v.typ && v.typ !== 'movie') return;
        const t = plexTitel(v.titel), k = familienSchluessel(t.name, t.jahr);
        (gesehen[k] = gesehen[k] || []);
        if (v.wer && gesehen[k].indexOf(v.wer) < 0) gesehen[k].push(v.wer);
        if (nurName[Suche.normal(t.name)] === undefined) nurName[Suche.normal(t.name)] = k;
      });
      familie.noten = noten;
      familie.gesehen = gesehen;
      familie.nurName = nurName;
      infoSchluessel = '';
      if (ansicht === A.LISTE || ansicht === A.DETAIL) zeichnen();
      try { await merk.ausHa(j); } catch (x) { /* Merkliste bleibt, wie sie war */ }
      try { await gesehenAusHa(j); } catch (x) { /* Gesehen bleibt, wie es war */ }
      try { qualitaetAusHa(j); } catch (x) { /* Meldungen bleiben, wie sie waren */ }
      try { await empfohlenAusHa(j); } catch (x) { /* Empfohlen bleibt, wie es war */ }
    } catch (e) { /* ohne Bewertungen weiter */ }
  }

  // ------------------------------------------------------------------ Empfohlen: alles, was jemand in der Familie mit 7 bis 10 bewertet hat
  // Seit 04.11.2026 (Wunsch Jonas/Lukas): auch die eigenen Bewertungen und auch schon Gesehenes – so dient Empfohlen
  // gleichzeitig als Liste der eigenen Lieblingsfilme zum Wiederanschauen.
  // Gruppiert nach der besten Note; innerhalb einer Gruppe zuerst, was man selbst noch nicht gesehen/bewertet hat.

  const empfohlen = { sig: null, gruppen: [] };   // gruppen: [{note, eintraege}]
  const eintragSpeicher = {};                      // Jellyfin-ID -> Eintrag (für Empfohlen)

  async function empfohlenAusHa(j) {
    if (!filmIndex.fertig) await filmIndexLaden();
    if (!filmIndex.fertig) return;
    const andere = function (n) { return PERSON.ich.indexOf(n) < 0; };
    const k = {};
    Object.keys(j.bewertungen || {}).forEach(function (pid) {
      const f = j.bewertungen[pid] || {}, w = f.w || {};
      const gut = Object.keys(w).filter(function (n) { return Number(w[n]) >= 7; }); // alle, auch die eigenen
      if (!gut.length) return;
      const id = jfIdFuerTitel(f.t);
      if (!id) return;
      const x = k[id] || (k[id] = { id: id, best: 0, summe: 0, n: 0, selbst: false });
      gut.forEach(function (n) {
        const v = Number(w[n]);
        x.best = Math.max(x.best, v);
        x.summe += v;
        x.n++;
      });
      if (Object.keys(w).some(function (n) { return !andere(n); })) x.selbst = true;
    });
    (j.verlauf || []).forEach(function (v) {
      if (PERSON.ich.indexOf(v.wer) < 0 || (v.typ && v.typ !== 'movie')) return;
      const id = jfIdFuerTitel(v.titel);
      if (id && k[id]) k[id].selbst = true;
    });
    const l = Object.keys(k).map(function (id) { return k[id]; });
    l.sort(function (a, b) {
      if (a.best !== b.best) return b.best - a.best;
      if (a.selbst !== b.selbst) return a.selbst ? 1 : -1;
      if (a.summe / a.n !== b.summe / b.n) return b.summe / b.n - a.summe / a.n;
      return b.n - a.n;
    });
    const sig = l.map(function (x) { return x.id + ':' + x.best + (x.selbst ? 's' : ''); }).join(',');
    if (sig === empfohlen.sig) return;
    const fehlen = l.map(function (x) { return x.id; }).filter(function (id) { return !eintragSpeicher[id]; });
    for (let i = 0; i < fehlen.length; i += 60) {
      try {
        const a = await jf.liste('/Items?userId=' + jf.benutzer + '&ids=' + fehlen.slice(i, i + 60).join(',') + '&fields=' + FELDER);
        a.forEach(function (e) { eintragSpeicher[e.Id] = e; });
      } catch (e) { /* dann fehlen sie eben */ }
    }
    const gruppen = [];
    l.forEach(function (x) {
      const e = eintragSpeicher[x.id];
      if (!e) return;
      let g = gruppen[gruppen.length - 1];
      if (!g || g.note !== x.best) gruppen.push(g = { note: x.best, eintraege: [] });
      g.eintraege.push(e);
    });
    empfohlen.sig = sig;
    empfohlen.gruppen = gruppen;
    empfohlenGeaendert();
  }

  function empfohlenSeite() {
    const s = neueSeite('Empfohlen');
    empfohlen.gruppen.forEach(function (g) {
      // Gesehenes bleibt drin (Haken auf dem Poster zeigt es an)
      if (g.eintraege.length) {
        s.reihen.push(reiheAus('Mit ' + g.note + ' bewertet', g.eintraege, POSTER));
      }
    });
    if (!s.reihen.length) s.leer = empfohlen.sig === null ? 'Bewertungen werden geladen …' : 'Noch nichts mit 7 oder besser bewertet';
    return s;
  }

  function empfohlenGeaendert() {
    const alt = startseiten.empfohlen;
    const neu = empfohlenSeite();
    startseiten.empfohlen = neu;
    if (alt && seite === alt) {
      neu.akt = Math.min(alt.akt, Math.max(0, neu.reihen.length - 1));
      const r = neu.reihen[neu.akt], ra = alt.reihen[alt.akt];
      if (r && ra) r.fokus = Math.min(ra.fokus, r.eintraege.length - 1);
      seite = neu;
      infoSchluessel = '';
      zeichnen();
    }
  }

  // ------------------------------------------------------------------ Live: wer schaut gerade was, wo (ganz links in Weiterschauen)
  // Quelle: sensor.kino_jetzt in HA, kommt mit auftrag.json (alle 4 s). Die Zeit läuft am Fernseher weiter.

  const LIVE_W = 470, LIVE_LUECKE = 64;
  const live = { liste: [], sig: '', laedt: false };

  function liveWo(w) {
    if (w === PERSON.ha) return PERSON.schluessel === 'eltern' ? 'Ihr  ·  Wohnzimmer' : 'Du  ·  dein Zimmer';
    if (w === 'Eltern') return 'Mama und Papa  ·  Wohnzimmer';
    return w + '  ·  in seinem Zimmer';
  }

  function liveFarbe(w) { return w === 'Eltern' ? '#E8559A' : PERSONEN_FARBE[w] || GELB; }

  async function liveNeu(laeuft, jetzt) {
    const wer = Object.keys(laeuft).filter(function (w) {
      const x = laeuft[w];
      return x && (!x.typ || x.typ === 'movie' || x.typ === 'episode');
    });
    wer.sort(function (a, b) { return (Number(laeuft[b].ts) || 0) - (Number(laeuft[a].ts) || 0); }); // das Aktuellste ganz links
    const sig = wer.map(function (w) { return w + '|' + laeuft[w].id; }).join(',');
    if (sig === live.sig) {
      live.liste.forEach(function (e) {
        const li = e._live, x = laeuft[li.wer];
        if (!x) return;
        if (li.jetzt !== jetzt) {
          li.jetzt = jetzt;
          li.empfangen = Date.now();
        }
        li.pos = Number(x.pos) || 0;
        li.dauer = Number(x.dauer) || 0;
        li.status = x.status || 'playing';
        li.ts = Number(x.ts) || jetzt;
      });
      liveTexte();
      return;
    }
    if (live.laedt) return;
    live.laedt = true;
    try {
      await merk.plexLaden();
      const ids = {};
      wer.forEach(function (w) {
        const id = String(laeuft[w].id || '');
        const jid = id.indexOf('jf:') === 0 ? id.substring(3) : (merk.haZuJf || {})[id];
        if (jid) ids[w] = jid;
      });
      const uniq = Object.keys(ids).map(function (k) { return ids[k]; }).filter(function (x, i, a) { return a.indexOf(x) === i; });
      const items = {};
      if (uniq.length) {
        (await jf.liste('/Items?userId=' + jf.benutzer + '&ids=' + uniq.join(',') + '&fields=' + FELDER))
          .forEach(function (e) { items[e.Id] = e; });
      }
      const weiter = await jf.weiterschauen();
      const liste = [];
      wer.forEach(function (w) {
        const f = items[ids[w]], x = laeuft[w];
        if (!f) return;
        const k = Object.assign({}, f);
        k._live = { wer: w, pos: Number(x.pos) || 0, dauer: Number(x.dauer) || 0, status: x.status || 'playing',
          ts: Number(x.ts) || jetzt, jetzt: jetzt, empfangen: Date.now() };
        liste.push(k);
      });
      live.sig = sig;
      live.liste = liste;
      liveEinsetzen(weiter);
    } catch (e) {
      /* beim nächsten Mal nochmal */
    } finally {
      live.laedt = false;
    }
  }

  /** Live-Kacheln vorne in die Reihe Weiterschauen der Filme-Startseite (weiter = frische Liste von Jellyfin oder null). */
  function liveEinsetzen(weiter) {
    const start = startseiten.filme;
    if (!start) return;
    let w = start.reihen[0];
    const da = !!w && w.titel === 'Weiterschauen';
    const alt = da ? (w.live || 0) : 0;
    const liveIds = live.liste.map(function (e) { return e.Id; });
    let regulaer = weiter ? weiter.slice() : (da ? w.eintraege.filter(function (e) { return e && !e._live; }) : []);
    regulaer = regulaer.filter(function (e) { return e && liveIds.indexOf(e.Id) < 0; });
    const alle = live.liste.concat(regulaer);
    if (!alle.length) {
      if (da && start.reihen.length > 1) {
        start.reihen.shift();
        if (start.akt > 0) start.akt--;
      }
    } else {
      if (!da) {
        w = reiheAus('Weiterschauen', [], POSTER);
        start.reihen.unshift(w);
        if (seite === start) start.akt++;
      }
      w.eintraege = alle;
      w.live = live.liste.length;
      w.version++;
      // stand man ganz vorne, bleibt man vorne (Live zuerst sehen), sonst bleibt der Fokus auf demselben Film
      w.fokus = begrenzen(alt === 0 && w.fokus === 0 ? 0 : w.fokus + w.live - alt, 0, alle.length - 1);
    }
    if (seite === start && (ansicht === A.LISTE || ansicht === A.DETAIL)) {
      infoSchluessel = '';
      zeichnen();
    }
  }

  function liveKarteBauen(e) {
    const li = e._live || {};
    const c = div('karte live');
    c.style.setProperty('--farbe', liveFarbe(li.wer));
    let url = null;
    if (e.BackdropImageTags && e.BackdropImageTags.length) {
      url = SERVER + '/Items/' + e.Id + '/Images/Backdrop/0?fillWidth=' + LIVE_W * 2 + '&quality=85&tag=' + e.BackdropImageTags[0];
    } else if (e.ParentBackdropItemId && e.ParentBackdropImageTags && e.ParentBackdropImageTags.length) {
      url = SERVER + '/Items/' + e.ParentBackdropItemId + '/Images/Backdrop/0?fillWidth=' + LIVE_W * 2 + '&quality=85&tag=' + e.ParentBackdropImageTags[0];
    } else url = kartenUrl(e, POSTER);
    if (url) {
      const img = document.createElement('img');
      img.alt = '';
      img.decoding = 'async';
      img.onerror = function () { if (img.parentNode) img.parentNode.removeChild(img); };
      img.src = url;
      c.appendChild(img);
    }
    c.insertAdjacentHTML('beforeend', '<div class="lv-verlauf"></div><div class="lv-live"><i></i>LIVE</div><div class="lv-wer"></div>'
      + '<div class="lv-titel"></div><div class="lv-bahn"><i></i></div><div class="lv-zeit"></div>');
    c.querySelector('.lv-wer').textContent = liveWo(li.wer);
    c.querySelector('.lv-titel').textContent = typ(e) === 'Episode'
      ? (e.SeriesName || '') + '  ·  S' + (e.ParentIndexNumber || 0) + ' E' + (e.IndexNumber || 0) : (e.Name || '');
    c._liveEintrag = e;
    liveKarteText(c);
    return c;
  }

  function liveKarteText(c) {
    const li = c._liveEintrag && c._liveEintrag._live;
    if (!li) return;
    const dauer = li.dauer || 0, pause = li.status !== 'playing';
    let pos = li.pos || 0;
    if (!pause) pos += Math.max(0, (li.jetzt || 0) - (li.ts || 0)) + (Date.now() - (li.empfangen || Date.now())) / 1000;
    if (dauer > 0) pos = Math.min(pos, dauer);
    c.querySelector('.lv-bahn i').style.width = (dauer > 0 ? 100 * pos / dauer : 0) + '%';
    const rest = Math.max(0, Math.round((dauer - pos) / 60));
    const restText = rest >= 60 ? Math.floor(rest / 60) + ' Std. ' + (rest % 60) + ' Min.' : rest + ' Min.';
    let z;
    if (dauer <= 0) z = pause ? 'pausiert' : 'läuft';
    else if (pause) z = 'pausiert  ·  noch ' + restText;
    else {
      const ende = new Date(Date.now() + (dauer - pos) * 1000);
      z = 'noch ' + restText + '  ·  fertig ca. ' + ende.getHours() + ':' + zweistellig(ende.getMinutes());
    }
    c.querySelector('.lv-zeit').textContent = z;
  }

  function liveTexte() {
    const start = startseiten.filme, w = start && start.reihen[0];
    if (!w || !w.live || !w.karten) return;
    Object.keys(w.karten).forEach(function (k) { if (w.karten[k].classList.contains('live')) liveKarteText(w.karten[k]); });
  }

  // ------------------------------------------------------------------ Gesehen je Profil (ab 80 %)
  // Quelle: Home Assistant (sensor.kino_gesehen, Paket kino_gesehen.yaml) über bewertungen.json → gesehen → Profil.
  // Gesehene Filme verschwinden bei diesem Profil aus Neu, den Kategorien und Alle Filme (Empfohlen behält sie) und stehen in der
  // Kategorie „Gesehen“ ganz rechts. Weiterschauen, Merkliste und Suche zeigen weiter alles.

  const GESEHEN_GRENZE = 0.8;
  const ges = { ids: [], menge: {}, lokal: {}, sig: '' };

  function istGesehen(e) { return !!(e && typ(e) === 'Movie' && ges.menge[e.Id]); }

  async function gesehenAusHa(j) {
    await merk.plexLaden();
    await filmIndexLaden();
    const mein = ((j && j.gesehen) || {})[PERSON.ha] || {};
    const l = [];
    Object.keys(mein).forEach(function (id) {
      const x = mein[id] || {};
      let jfId = id.indexOf('jf:') === 0 ? id.substring(3) : (merk.haZuJf || {})[id];
      if (!jfId) jfId = jfIdFuerTitel(x.t);
      if (jfId) l.push({ id: jfId, z: String(x.z || '') });
    });
    l.sort(function (a, b) { return b.z.localeCompare(a.z); });
    const ids = [];
    l.forEach(function (x) { if (ids.indexOf(x.id) < 0) ids.push(x.id); });
    Object.keys(ges.lokal).forEach(function (id) {
      if (ids.indexOf(id) >= 0 || ges.lokal[id] < Date.now()) delete ges.lokal[id];
      else ids.unshift(id);
    });
    gesehenSetzen(ids);
  }

  function gesehenSetzen(ids) {
    const sig = ids.join(',');
    if (sig === ges.sig) return;
    ges.ids = ids;
    ges.menge = {};
    ids.forEach(function (id) { ges.menge[id] = true; });
    ges.sig = sig;
    gesehenGeaendert();
  }

  /** Gerade eben ab 80 % geschaut: sofort als gesehen führen und HA Bescheid geben. */
  function gesehenDazu(f) {
    if (!f || typ(f) !== 'Movie' || istGesehen(f)) return;
    ges.lokal[f.Id] = Date.now() + 15 * 60000;
    gesehenSetzen([f.Id].concat(ges.ids));
    (async function () {
      try {
        await merk.plexLaden();
        await merk.senden({ art: 'gesehen', wer: PERSON.ha, id: merk.plexIdFuer(f) || 'jf:' + f.Id,
          titel: (f.Name || '') + (f.ProductionYear ? ' (' + f.ProductionYear + ')' : ''), an: 'true' });
      } catch (x) { /* Automation 44 trägt es sonst ohnehin ein */ }
    })();
  }

  // ------------------------------------------------------------------ Schlechte Qualität melden (03.11.2026)
  // Kleiner Fahnen-Knopf ganz rechts in der Detailansicht. Jeder kann melden, die Liste sieht nur Lukas (am TCL).
  // Quelle: sensor.kino_qualitaet (Paket kino_qualitaet.yaml) über bewertungen.json → qualitaet
  // { HA-ID: { t, wer: [Profile], z } }. Schreiben: Webhook (art=qualitaet, an=true/false).
  const qual = { wer: {}, lokal: {} };

  function qualitaetAn(e) {
    const l = e ? qual.wer[e.Id] : null;
    if (!l || !l.length) return false;
    return PERSON.ha === 'Lukas' || l.indexOf(PERSON.ha) >= 0;
  }

  function qualitaetAnwenden(w, id, an) {
    let l = (w[id] || []).slice();
    if (an) { if (l.indexOf(PERSON.ha) < 0) l.push(PERSON.ha); }
    else l = PERSON.ha === 'Lukas' ? [] : l.filter(function (x) { return x !== PERSON.ha; });
    if (l.length) w[id] = l; else delete w[id];
  }

  function qualitaetAusHa(j) {
    const alle = (j && j.qualitaet) || {}, w = {};
    Object.keys(alle).forEach(function (id) {
      const x = alle[id] || {};
      let jfId = id.indexOf('jf:') === 0 ? id.substring(3) : (merk.haZuJf || {})[id];
      if (!jfId) jfId = jfIdFuerTitel(x.t);
      if (jfId && x.wer && x.wer.length) w[jfId] = x.wer.slice();
    });
    Object.keys(qual.lokal).forEach(function (id) {
      const lo = qual.lokal[id];
      const ist = !!(w[id] && w[id].length && (PERSON.ha === 'Lukas' || w[id].indexOf(PERSON.ha) >= 0));
      if (ist === lo.an || lo.bis < Date.now()) delete qual.lokal[id];
      else qualitaetAnwenden(w, id, lo.an);
    });
    qual.wer = w;
    knoepfeAktualisieren();
  }

  function qualitaetUmschalten(f) {
    if (!f || typ(f) !== 'Movie') return;
    const an = !qualitaetAn(f);
    qual.lokal[f.Id] = { an: an, bis: Date.now() + 15 * 60000 };
    qualitaetAnwenden(qual.wer, f.Id, an);
    knoepfeAktualisieren();
    hinweis(an ? 'Danke! Schlechte Qualität gemeldet' : PERSON.ha === 'Lukas' ? 'Meldung erledigt' : 'Meldung zurückgenommen');
    (async function () {
      try {
        await merk.plexLaden();
        await merk.senden({ art: 'qualitaet', wer: PERSON.ha, id: merk.plexIdFuer(f) || 'jf:' + f.Id,
          titel: (f.Name || '') + (f.ProductionYear ? ' (' + f.ProductionYear + ')' : ''), an: String(an) });
      } catch (x) {
        delete qual.lokal[f.Id];
        hinweis('Meldung ließ sich nicht senden');
      }
    })();
  }

  function gesehenKachel() {
    return { Type: 'Gesehen', Id: '@gesehen', Name: 'Gesehen', ChildCount: ges.ids.length };
  }

  /** Neue Gesehen-Liste: Gesehenes aus allen offenen Reihen nehmen, Kachel nachziehen. */
  function gesehenGeaendert() {
    const alle = Object.keys(startseiten).map(function (k) { return startseiten[k]; }).concat(stapel);
    if (alle.indexOf(seite) < 0) alle.push(seite);
    alle.forEach(function (s) {
      if (s.ohneFilter || s.raster) return;
      for (let i = s.reihen.length - 1; i >= 0; i--) {
        const r = s.reihen[i];
        if (r.titel === 'Weiterschauen' || r.titel === 'Merkliste') continue;
        const vorher = r.eintraege.length;
        r.eintraege = r.eintraege.filter(function (e) { return !istGesehen(e); });
        if (r.eintraege.length === vorher) continue;
        r.version++;
        r.fokus = Math.min(r.fokus, Math.max(0, r.eintraege.length - 1));
        if (!r.eintraege.length && r.geladen && s.reihen.length > 1) {
          s.reihen.splice(i, 1);
          if (s.akt > i || s.akt >= s.reihen.length) s.akt = Math.max(0, s.akt - 1);
        }
      }
    });
    const start = startseiten.filme;
    if (start) {
      start.reihen.forEach(function (r) {
        if (r.titel !== 'Kategorien') return;
        r.eintraege = r.eintraege.filter(function (e) { return typ(e) !== 'Gesehen'; });
        if (ges.ids.length) r.eintraege.push(gesehenKachel());
        r.fokus = Math.min(r.fokus, Math.max(0, r.eintraege.length - 1));
        r.version++;
      });
    }
    if (startseiten.empfohlen) empfohlenGeaendert();
    infoSchluessel = '';
    if (ansicht === A.LISTE || ansicht === A.DETAIL) zeichnen();
  }

  function gesehenOeffnen(k) {
    const neu = neueSeite('Gesehen');
    neu.kopf = k;
    neu.ohneFilter = true;
    neu.reihen.push(neueReihe('Gesehen', null, POSTER));
    seiteZeigen(neu);
    const ids = ges.ids.slice();
    (async function () {
      const gefunden = {};
      for (let i = 0; i < ids.length; i += 60) {
        try {
          const a = await jf.liste('/Items?userId=' + jf.benutzer + '&ids=' + ids.slice(i, i + 60).join(',') + '&fields=' + FELDER);
          a.forEach(function (e) { gefunden[e.Id] = e; });
        } catch (x) { /* dann fehlen sie */ }
      }
      const l = ids.map(function (id) { return gefunden[id]; }).filter(Boolean);
      neu.reihen = [];
      if (l.length) neu.reihen.push(reiheAus(l.length === 1 ? '1 Film' : l.length + ' Filme', l, POSTER));
      if (!neu.reihen.length) neu.leer = 'Noch nichts gesehen';
      if (seite === neu) fokusGeaendert();
    })();
  }

  // ------------------------------------------------------------------ Bewerten (nach dem Film ab 80 % und in den Details)
  // Wie am TCL (BewertenKarte.java, Wunsch Lukas 03.11.2026):
  // - Feld ohne Bewertung zeigt „–“, erst ↑ oder ↓ holt die 5. Abgeben schickt nur geänderte Felder
  //   (ändert Papa seine Note, bleibt Mama unberührt).
  // - Neben jedem Zähler ein Feld „Keine Bewertung“ (links neben Papa bzw. dem eigenen Zähler, rechts neben Mama):
  //   OK darauf nimmt die Bewertung heraus (Note 0). Lukas/Jonas: geht sofort raus.
  // - Jede Note hat ihren eigenen Look: 1 Gewitter mit Riss, ab 7 grün, 8 funkelt, 9 grün mit Konfetti,
  //   10 golden mit Konfetti, Luftschlangen und Lichtschein.
  // Geht über den Webhook von Automation 84 an Skript 00v (Plex bewerten) – dieselben Bewertungen wie im Dashboard.

  const B = { offen: false, film: null, namen: [], alt: [], wert: [], geaendert: [], fokus: 1, danke: 0, timer: 0,
    nurEntfernt: false, stufe: [], dom: null };
  const PERSONEN_FARBE = { Lukas: '#F2B705', Moni: '#E8559A', Uli: '#1FA463', Jonas: '#2F6FEB' };
  const B_WORT = ['Noch keine Note', 'Katastrophe', 'Schlecht', 'Schwach', 'Mäh', 'Geht so', 'Ganz okay', 'Gut', 'Sehr gut',
    'Super!', 'Meisterwerk!'];
  const B_FARBE = ['#FFFFFF', '#E0322F', '#EF4444', '#F26B3A', '#F59E0B', '#F2C94C', '#B5D334', '#6BD48A', '#22C55E', '#39E072', '#FFD54A'];
  const B_BUNT = ['#F2B705', '#E8559A', '#22C55E', '#3E9BF0', '#FF6B35', '#A855F7', '#FFFFFF', '#2DD4BF'];
  const B_GRUEN = ['#4ADE80', '#22C55E', '#BBF7D0', '#F2B705', '#FFFFFF'];

  function bewNamen() { return PERSON.schluessel === 'eltern' ? ['Uli', 'Moni'] : [PERSON.ha]; }
  function bewFrage() {
    return PERSON.schluessel === 'eltern' ? 'Mama und Papa, wie hat euch der Film gefallen?' : PERSON.ha + ', wie hat dir der Film gefallen?';
  }
  function bewSchild(n) { return n === 'Uli' ? 'Papa · Uli' : n === 'Moni' ? 'Mama · Moni' : n; }
  function notenFarbe(n) {
    const st = ['#E5484D', '#F97316', '#F2B705', '#22C55E'];
    const t = begrenzen((n - 1) / 9, 0, 1) * (st.length - 1), i = Math.min(st.length - 2, Math.floor(t));
    return mischen(st[i], st[i + 1], t - i);
  }

  function eigeneNoten(e) {
    const f = familieFuer(e), l = [];
    bewNamen().forEach(function (n) { if (f && f.noten[n]) l.push(f.noten[n]); });
    return l.length ? 'Bewertet  ·  ' + l.join(' / ') : 'Bewerten';
  }

  function bewZwei() { return B.namen.length > 1; }
  function bewSlots() { return bewZwei() ? 4 : 2; }
  function bewIstX(s) { return s === 0 || (bewZwei() && s === 3); }
  function bewPerson(s) { return !bewZwei() ? 0 : (s <= 1 ? 0 : 1); }

  function bewAenderungen() {
    const l = [];
    B.namen.forEach(function (n, i) {
      if (!B.geaendert[i] || B.wert[i] === B.alt[i]) return;
      if (B.wert[i] === 0 && B.alt[i] === 0) return;
      l.push({ i: i, note: B.wert[i] });
    });
    return l;
  }

  function bewertenOeffnen(f) {
    if (!f || typ(f) !== 'Movie') return;
    const fam = familieFuer(f);
    B.film = f;
    B.namen = bewNamen();
    B.alt = B.namen.map(function (n) {
      const w = fam && fam.noten[n] ? Number(fam.noten[n]) : 0;
      return w >= 1 && w <= 10 ? w : 0;
    });
    B.wert = B.alt.slice();
    B.geaendert = B.namen.map(function () { return false; });
    B.stufe = B.namen.map(function () { return -1; });
    B.fokus = 1;
    B.danke = 0;
    B.nurEntfernt = false;
    B.offen = true;
    B.dom = null;
    if (leiste) leisteZu();
    bewertenZeichnen();
  }

  function bewertenSchliessen() {
    B.offen = false;
    B.danke = 0;
    B.dom = null;
    clearTimeout(B.timer);
    bewertenZeichnen();
  }

  function bewertenTaste(k) {
    if (B.danke) {
      if (k === TASTE.ZURUECK || k === TASTE.OK) bewertenSchliessen();
      return;
    }
    if (k === TASTE.ZURUECK) return bewertenSchliessen();
    const pi = bewPerson(B.fokus);
    if (k === TASTE.HOCH || k === TASTE.RUNTER) {
      if (bewIstX(B.fokus)) {
        B.fokus = bewZwei() ? (B.fokus === 0 ? 1 : 2) : 1;
      } else {
        const vorher = B.wert[pi];
        if (!B.wert[pi]) B.wert[pi] = 5;
        else if (k === TASTE.HOCH) B.wert[pi] = Math.min(10, B.wert[pi] + 1);
        else B.wert[pi] = Math.max(1, B.wert[pi] - 1);
        if (B.wert[pi] !== vorher) {
          B.geaendert[pi] = true;
          bewertenZeichnen(pi);
          return;
        }
      }
    } else if (k === TASTE.LINKS && wiederholung === 0) {
      B.fokus = Math.max(0, B.fokus - 1);
    } else if (k === TASTE.RECHTS && wiederholung === 0) {
      B.fokus = Math.min(bewSlots() - 1, B.fokus + 1);
    } else if (k === TASTE.OK && wiederholung === 0) {
      if (bewIstX(B.fokus)) {
        B.wert[pi] = 0;
        B.geaendert[pi] = true;
        if (!bewZwei()) {
          B.nurEntfernt = true;
          return bewAenderungen().length ? bewertenSenden() : bewertenSchliessen();
        }
        B.fokus = B.fokus === 0 ? 1 : 2;
        bewertenZeichnen(pi);
        return;
      }
      B.nurEntfernt = !bewAenderungen().some(function (a) { return a.note > 0; });
      return bewertenSenden();
    }
    bewertenZeichnen();
  }

  function bewertenSenden() {
    const f = B.film, namen = B.namen.slice(), l = bewAenderungen();
    if (!l.length) {
      bewertenSchliessen();
      hinweis('Nichts geändert');
      return;
    }
    B.danke = Date.now();
    B.dom = null;
    bewertenZeichnen();
    B.timer = setTimeout(bewertenSchliessen, l.some(function (a) { return a.note === 10; }) ? 3200 : 2200);
    (async function () {
      try {
        await merk.plexLaden();
        const id = merk.plexIdFuer(f) || 'jf:' + f.Id;
        const titel = (f.Name || '') + (f.ProductionYear ? ' (' + f.ProductionYear + ')' : '');
        for (let i = 0; i < l.length; i++) {
          await merk.senden({ art: 'bewerten', wer: namen[l[i].i], id: id, titel: titel, wertung: String(l[i].note) });
        }
        setTimeout(familieLaden, 3000);
      } catch (x) {
        hinweis('Bewertung ließ sich nicht speichern');
      }
    })();
  }

  // --- Effekte je Note (nur neu gebaut, wenn sich die Stufe ändert, damit die Animationen weiterlaufen)
  function bewZufall(k) {
    let x = (k * 374761393 + 668265263) | 0;
    x = Math.imul(x ^ (x >>> 13), 1274126177);
    x ^= x >>> 16;
    return (x & 0xFFFFFF) / 0x1000000;
  }

  function bewKonfetti(n, farben, tempo, saat) {
    let h = '';
    for (let k = 0; k < n; k++) {
      const s = k * 3 + saat * 997;
      const dauer = (2.2 + 2.4 * bewZufall(s + 2)) / tempo;
      h += '<i class="bx-k" style="left:' + (bewZufall(s + 1) * 100).toFixed(1) + '%;width:' + (10 + 6 * bewZufall(s + 3)).toFixed(0)
        + 'px;background:' + farben[k % farben.length] + ';animation-duration:' + dauer.toFixed(2)
        + 's;animation-delay:-' + (bewZufall(s + 4) * dauer).toFixed(2) + 's"></i>';
    }
    return h;
  }

  function bewFunkeln(n, farbe) {
    let h = '';
    for (let k = 0; k < n; k++) {
      const g = 16 + 20 * bewZufall(k * 7 + 5);
      h += '<i class="bx-f" style="left:' + (7 + bewZufall(k * 7 + 3) * 86).toFixed(1) + '%;top:' + (12 + bewZufall(k * 7 + 4) * 70).toFixed(1)
        + '%;width:' + g.toFixed(0) + 'px;height:' + g.toFixed(0) + 'px;color:' + farbe + ';animation-delay:-' + (k * 0.73).toFixed(2) + 's"></i>';
    }
    return h;
  }

  function bewSchlangen() {
    const stellen = [8, 28, 50, 72, 92];
    let h = '<svg class="bx-l" viewBox="0 0 380 430" preserveAspectRatio="none">';
    stellen.forEach(function (x0, i) {
      const x = x0 * 3.8, laenge = 110 + 90 * bewZufall(i * 11 + 5);
      let d = 'M' + x.toFixed(1) + ' -4';
      for (let s = 6; s <= laenge; s += 6) {
        const amp = 16 * (s / laenge) + 4;
        d += ' L' + (x + amp * Math.sin(s / 11 + i * 1.3)).toFixed(1) + ' ' + s;
      }
      h += '<path d="' + d + '" stroke="' + B_BUNT[(i * 3) % B_BUNT.length] + '" style="animation-delay:-' + (i * 0.4).toFixed(1) + 's"/>';
    });
    return h + '</svg>';
  }

  function bewRegen(n, breite, links) {
    let h = '';
    for (let k = 0; k < n; k++) {
      const dauer = 0.55 + 0.3 * bewZufall(k * 5 + 2);
      h += '<i class="bx-r" style="left:calc(' + links + '% + ' + ((bewZufall(k * 5 + 1) - 0.5) * breite).toFixed(0)
        + 'px);animation-duration:' + dauer.toFixed(2) + 's;animation-delay:-' + (bewZufall(k * 5 + 3) * dauer).toFixed(2) + 's"></i>';
    }
    return h;
  }

  // Jonas' Fernseher (leichter Modus, Wunsch Lukas 03.11.2026): deutlich weniger bewegte Teile, keine 3D-Drehungen,
  // kein Lichtschein, stehende Luftschlangen – der Look je Note bleibt, nur ruhiger.
  function bewEffekt(note, saat) {
    const wenig = LEICHT;
    if (note === 10) {
      return (wenig ? '' : '<div class="bx-schein"></div>') + bewKonfetti(wenig ? 10 : 54, B_BUNT, wenig ? 0.8 : 1, saat) + bewSchlangen();
    }
    if (note === 9) return (wenig ? '' : bewKonfetti(16, B_GRUEN, 0.7, saat)) + bewFunkeln(wenig ? 3 : 8, '#FFFFFF');
    if (note === 8) return bewFunkeln(wenig ? 2 : 4, '#D1FAE5');
    if (note === 1) {
      return '<svg class="bx-riss" viewBox="0 0 380 430" preserveAspectRatio="none"><path d="M27 0 L57 56 L38 95 L72 150 M57 56 L91 73'
        + ' M361 430 L327 374 L346 331 L308 284"/></svg>'
        + bewRegen(wenig ? 6 : 22, 150, 50) + '<div class="bx-wolke"></div><div class="bx-blitz"></div>' + (wenig ? '' : '<div class="bx-flash"></div>');
    }
    if (note === 2) return bewRegen(wenig ? 3 : 6, 110, 68) + '<div class="bx-wolke klein"></div>';
    return '';
  }

  function bewXFeld(pi, slot) {
    const entfernt = B.geaendert[pi] && B.wert[pi] === 0 && B.alt[pi] > 0;
    return '<div class="b-x' + (B.fokus === slot ? ' aktiv' : '') + (entfernt ? ' entfernt' : '') + '">'
      + '<div class="b-x-symbol"></div><div class="b-x-text">' + (entfernt ? 'Wird<br>entfernt' : 'Keine<br>Bewertung') + '</div></div>';
  }

  function bewertenAufbauen() {
    const b = el.bewerten, zwei = bewZwei();
    let h = '<div class="glas b-karte' + (zwei ? ' zwei' : '') + '"><div class="b-film"></div><div class="b-frage"></div><div class="b-reihe">';
    const spalte = function (pi) {
      return '<div class="b-spalte" data-p="' + pi + '"><div class="b-fx"></div><div class="b-rand"></div>'
        + '<div class="b-pfeil hoch"></div><div class="b-zahl"></div><div class="b-wort"></div><div class="b-pfeil runter"></div>'
        + '<div class="b-punkte">' + '<i></i>'.repeat(10) + '</div><div class="b-wer"></div><div class="b-knall"></div></div>';
    };
    if (zwei) h += '<div class="b-x-platz" data-x="0"></div>' + spalte(0) + spalte(1) + '<div class="b-x-platz" data-x="1"></div>';
    else h += '<div class="b-x-platz" data-x="0"></div>' + spalte(0) + '<div class="b-x-leer"></div>';
    h += '</div></div><div class="b-hilfe"></div>';
    b.innerHTML = h;
    b.querySelector('.b-film').textContent = B.film ? String(B.film.Name || '') : '';
    b.querySelector('.b-frage').textContent = bewFrage();
    B.dom = { spalten: Array.prototype.slice.call(b.querySelectorAll('.b-spalte')), x: Array.prototype.slice.call(b.querySelectorAll('.b-x-platz')),
      hilfe: b.querySelector('.b-hilfe') };
    B.dom.spalten.forEach(function (sp, i) {
      const n = B.namen[i];
      const w = sp.querySelector('.b-wer');
      w.textContent = bewSchild(n);
      w.style.color = PERSONEN_FARBE[n] || '#fff';
    });
  }

  function bewertenZeichnen(geaendertBei) {
    const b = el.bewerten;
    b.classList.toggle('aus', !B.offen);
    if (!B.offen) {
      b.innerHTML = '';
      return;
    }
    if (B.danke) return bewertenDankeZeichnen();
    if (!B.dom) bewertenAufbauen();
    const zwei = bewZwei();
    B.dom.spalten.forEach(function (sp, i) {
      const note = B.wert[i], slot = zwei ? i + 1 : 1;
      const entfernt = B.geaendert[i] && note === 0 && B.alt[i] > 0;
      sp.className = 'b-spalte s' + note + (B.fokus === slot ? ' aktiv' : '');
      const z = sp.querySelector('.b-zahl');
      z.textContent = note ? String(note) : '–';
      z.style.color = note === 10 ? '' : (note ? B_FARBE[note] : 'rgba(255,255,255,.5)');
      const wo = sp.querySelector('.b-wort');
      wo.textContent = entfernt ? 'Wird entfernt' : B_WORT[note];
      wo.style.color = entfernt ? '#FCA5A5' : (note >= 9 || (note && note <= 2) ? B_FARBE[note] : '');
      sp.querySelector('.b-pfeil.hoch').classList.toggle('grau', note >= 10);
      sp.querySelector('.b-pfeil.runter').classList.toggle('grau', note === 1);
      const punkte = sp.querySelectorAll('.b-punkte i');
      for (let q = 0; q < 10; q++) {
        const an = q < note;
        punkte[q].style.background = an ? (note === 10 ? B_BUNT[q % B_BUNT.length] : B_FARBE[note]) : '';
        punkte[q].style.opacity = an ? '1' : '';
        punkte[q].className = (q + 1 === note ? 'jetzt' : '') + (note === 10 && an ? ' huepf' : '');
        punkte[q].style.animationDelay = note === 10 ? (-q * 0.12).toFixed(2) + 's' : '';
      }
      if (B.stufe[i] !== note) {
        sp.querySelector('.b-fx').innerHTML = bewEffekt(note, i);
        B.stufe[i] = note;
      }
      if (geaendertBei === i) {
        // kurzer Pop der Zahl, bei 10 platzt Konfetti aus der Zahl, bei 1 wackelt sie
        z.classList.remove('pop', 'wackeln');
        void z.offsetWidth;
        z.classList.add(note === 1 ? 'wackeln' : 'pop');
        const knall = sp.querySelector('.b-knall');
        if (note === 10) {
          let h = '';
          for (let k = 0; k < (LEICHT ? 10 : 36); k++) {
            const w = bewZufall(k * 13 + i * 101) * Math.PI * 2, v = 160 + 260 * bewZufall(k * 13 + 7 + i * 101);
            h += '<i style="background:' + B_BUNT[k % B_BUNT.length] + ';--dx:' + (Math.cos(w) * v).toFixed(0) + 'px;--dy:'
              + (Math.sin(w) * v + 120).toFixed(0) + 'px"></i>';
          }
          knall.innerHTML = h;
        } else knall.innerHTML = '';
      }
    });
    B.dom.x.forEach(function (xp) {
      const pi = Number(xp.getAttribute('data-x'));
      const slot = pi === 0 ? 0 : 3;
      xp.innerHTML = bewXFeld(pi, slot);
    });
    let hilfe;
    if (bewIstX(B.fokus)) {
      hilfe = zwei ? 'OK  keine Bewertung für ' + (B.fokus === 0 ? 'Papa' : 'Mama') + '      ← →  wechseln      Zurück  später'
        : 'OK  keine Bewertung      →  zurück zur Note      Zurück  später';
    } else {
      hilfe = zwei ? '↑ ↓  Note      ← →  Papa / Mama / keine      OK  abgeben      Zurück  später'
        : '↑ ↓  Note      ←  keine Bewertung      OK  abgeben      Zurück  später';
    }
    B.dom.hilfe.textContent = hilfe;
  }

  function bewertenDankeZeichnen() {
    const b = el.bewerten, zwei = bewZwei(), l = bewAenderungen();
    const zehn = l.some(function (a) { return a.note === 10; });
    let h = '<div class="glas b-karte' + (zwei ? ' zwei' : '') + ' danke">';
    if (zehn) h += '<div class="b-fx">' + bewKonfetti(LEICHT ? 12 : 70, B_BUNT, 1.1, 3) + '</div>';
    h += '<div class="b-haken' + (B.nurEntfernt ? ' grau' : '') + '"><svg viewBox="0 0 40 40" fill="none" stroke="#fff" stroke-width="4.5" '
      + 'stroke-linecap="round" stroke-linejoin="round"><path d="M11 21l6 6 13-14"/></svg></div>'
      + '<div class="b-danke"></div><div class="b-zus">';
    l.forEach(function (a) {
      const n = B.namen[a.i];
      h += '<span style="color:' + (a.note ? B_FARBE[a.note] : '#FCA5A5') + '">' + (zwei ? bewSchild(n) + ':  ' : '')
        + (a.note ? a.note + ' von 10' : 'entfernt') + '</span>';
    });
    h += '</div><div class="b-film b-name-unten"></div></div>';
    b.innerHTML = h;
    b.querySelector('.b-danke').textContent = B.nurEntfernt ? 'Bewertung entfernt'
      : (PERSON.schluessel === 'eltern' ? 'Danke für eure Bewertung!' : 'Danke für deine Bewertung!');
    b.querySelector('.b-name-unten').textContent = B.film ? String(B.film.Name || '') : '';
  }

  function familieFuer(e) {
    if (!e || typ(e) !== 'Movie') return null;
    let k = familienSchluessel(e.Name, e.ProductionYear);
    if (!familie.noten[k] && !familie.gesehen[k]) {
      const n = familie.nurName[Suche.normal(e.Name)];
      if (n) k = n;
      else if (e.OriginalTitle) k = familienSchluessel(e.OriginalTitle, e.ProductionYear);
    }
    const w = familie.noten[k] || {}, g = familie.gesehen[k] || [];
    if (!Object.keys(w).length && !g.length) return null;
    return { noten: w, gesehen: g };
  }

  function familienZeile(e, immer) {
    const f = familieFuer(e);
    if (!f) return null;
    const d = div('i-familie' + (immer ? '' : ' nur-detail'));
    const namen = Object.keys(f.noten).sort(function (a, b) { return f.noten[b] - f.noten[a]; });
    if (namen.length) {
      const summe = namen.reduce(function (a, n) { return a + Number(f.noten[n] || 0); }, 0);
      const schnitt = div('f-schnitt', 'Ø ' + (summe / namen.length).toFixed(1).replace('.', ','));
      d.appendChild(schnitt);
      namen.forEach(function (n) {
        const p = div('f-note');
        const nm = document.createElement('b');
        nm.textContent = n;
        nm.style.color = FAMILIE[n] || '#fff';
        const z = document.createElement('span');
        z.textContent = f.noten[n];
        p.appendChild(nm);
        p.appendChild(z);
        d.appendChild(p);
      });
    }
    if (f.gesehen.length) d.appendChild(div('f-gesehen', 'Gesehen: ' + f.gesehen.join(', ')));
    return d;
  }

  // ------------------------------------------------------------------ Zustand

  function neueReihe(titel, quelle, form) {
    return { titel: titel, quelle: quelle, form: form, eintraege: [], fokus: 0, geladen: false, laedt: false,
      el: null, titelEl: null, streifen: null, karten: {}, version: 0, gebaut: -1, platzhalter: false };
  }

  function reiheAus(titel, liste, form) {
    const r = neueReihe(titel, null, form);
    r.eintraege = liste.filter(Boolean);
    r.geladen = true;
    return r;
  }

  function neueSeite(titel) { return { titel: titel, reihen: [], akt: 0, leer: null, kopf: null, ohneFilter: false, raster: false }; }

  let ansicht = A.LADEN;
  let bereich = 'filme';
  const startseiten = {};
  const stapel = [];
  let seite = neueSeite('');
  let filmeId = null, serienId = null, fotosId = null;

  let qcCode = null, qcSecret = null, qcTimer = 0, qcFehler = 0, fehlerText = null;
  let detailKnopf = 0, detailEintrag = null, detailZurueck = A.LISTE;
  let detailKnoepfe = [];        // [{art, text}] – Abspielen/Fortsetzen, Von vorne, Trailer, Merken
  let detailTrailer = null;      // {id, yt, titel} zum Film in der Detailansicht

  let leiste = false, leistePos = 0, leisteMaus = false;

  let suchText = '';
  let suchIndex = null, suchIndexLaedt = false;
  let treffer = [], trefferVersion = 0;
  let tasteZeile = 1, tasteSpalte = 0, trefferFokus = 0, inTreffern = false;
  let sucheZurueck = A.LISTE;

  const fotos = [];
  let fotoIdx = 0, diashow = false, fotoInfoAn = false, fotoInfoTimer = 0, diaTimer = 0;

  const titelbilder = {};      // Ordner-Id -> Bild-URL ('' = keins)
  const titelbildLaedt = {};
  // gefundene Titelbilder bleiben am Fernseher gespeichert (Fotos in alten Ordnern ändern sich praktisch nie)
  let titelSpeicher = {};
  try { titelSpeicher = JSON.parse(speicherLesen('lk_titelbilder2') || '{}') || {}; } catch (e) { titelSpeicher = {}; }
  const titelWarteschlange = [];
  let titelLaufend = 0;

  // ------------------------------------------------------------------ DOM aufbauen

  const el = {};

  function bauen() {
    const b = div();
    b.id = 'buehne';
    b.innerHTML = ''
      + '<div class="ebene" id="bgs"><div class="bg"></div><div class="bg"></div></div>'
      + '<div class="ebene" id="verlauf"></div>'
      + '<div class="ebene" id="liste"><div id="reihen"></div><div id="info"></div></div>'
      + '<div class="ebene aus" id="wand"><div class="w-kopf"><div class="w-name"></div><div class="w-info"></div>'
      + '  <div class="w-rechts"></div></div><div class="w-fenster"><div class="w-gitter"></div><div class="w-leer"></div></div></div>'
      + '<div class="ebene aus" id="suche">'
      + '  <div class="glas feld">' + svg(SYMBOLE[0]) + '<div class="text"></div></div>'
      + '  <div class="tastatur"></div><div class="kopf"></div><div class="held"></div>'
      + '  <div class="fenster"><div class="gitter"></div></div>'
      + '</div>'
      + '<div class="ebene aus" id="mitte"></div>'
      + '<div class="ebene aus" id="foto"><div class="fbild"></div><div class="fbild"></div>'
      + '  <div class="f-video aus"></div><div class="f-pille aus"></div></div>'
      + '<div class="ebene" id="leiste-dunkel"></div>'
      + '<div class="glas" id="leiste"><div class="pille"></div><div class="punkt"></div></div>'
      + '<div class="ebene aus" id="trailer"><div class="t-box"></div>'
      + '  <div class="ebene p-leiste t-leiste"><div class="p-oben"></div><div class="p-unten"></div>'
      + '    <div class="t-marke">Trailer</div><div class="p-titel t-titel"></div>'
      + '    <div class="p-bahn"><div class="p-pos t-pos"></div><div class="p-knubbel t-knubbel"></div></div>'
      + '    <div class="p-zeit t-zeit"></div><div class="p-rest t-rest"></div>'
      + '    <div class="p-pause t-pause aus"></div><div class="p-meldung t-meldung aus"></div></div></div>'
      + '<div class="hinweis aus"></div>'
      + '<div class="ebene aus" id="bewerten"></div>'
      + '<div class="ebene aus" id="player">'
      + '  <video preload="auto"></video>'
      + '  <div class="p-untertitel"></div>'
      + '  <div class="ebene p-leiste">'
      + '    <div class="p-oben"></div><div class="p-unten"></div>'
      + '    <img class="p-logo aus" alt=""><div class="p-titel"></div>'
      + '    <div class="p-bahn"><div class="p-puffer"></div><div class="p-pos"></div><div class="p-knubbel"></div></div>'
      + '    <div class="p-zeit"></div><div class="p-rest"></div><div class="p-blase aus"></div>'
      + '    <div class="p-pause aus"></div><div class="p-meldung aus"></div>'
      + '    <div class="p-menue aus"></div>'
      + '  </div>'
      + '</div>';
    document.body.appendChild(b);

    const q = function (s) { return b.querySelector(s); };
    el.buehne = b;
    el.bg = Array.prototype.slice.call(b.querySelectorAll('.bg'));
    el.bgs = q('#bgs');
    el.verlauf = q('#verlauf');
    el.liste = q('#liste');
    el.reihen = q('#reihen');
    el.info = q('#info');
    el.suche = q('#suche');
    el.sText = q('#suche .feld .text');
    el.tastatur = q('#suche .tastatur');
    el.sKopf = q('#suche .kopf');
    el.sHeld = q('#suche .held');
    el.gitter = q('#suche .gitter');
    el.mitte = q('#mitte');
    el.foto = q('#foto');
    el.fbild = Array.prototype.slice.call(b.querySelectorAll('.fbild'));
    el.fVideo = q('.f-video');
    el.fPille = q('.f-pille');
    el.dunkel = q('#leiste-dunkel');
    el.leiste = q('#leiste');
    el.lPille = q('#leiste .pille');
    el.lPunkt = q('#leiste .punkt');
    el.player = q('#player');
    el.video = q('#player video');
    el.untertitel = q('.p-untertitel');
    el.pLeiste = q('.p-leiste');
    el.pLogo = q('.p-logo');
    el.pTitel = q('.p-titel');
    el.pPuffer = q('.p-puffer');
    el.pPos = q('.p-pos');
    el.pKnubbel = q('.p-knubbel');
    el.pZeit = q('.p-zeit');
    el.pRest = q('.p-rest');
    el.pBlase = q('.p-blase');
    el.pPause = q('.p-pause');
    el.pMeldung = q('.p-meldung');
    el.pMenue = q('.p-menue');
    el.trailer = q('#trailer');
    el.tBox = q('#trailer .t-box');
    el.tLeiste = q('#trailer .t-leiste');
    el.tTitel = q('#trailer .t-titel');
    el.tPos = q('#trailer .t-pos');
    el.tKnubbel = q('#trailer .t-knubbel');
    el.tZeit = q('#trailer .t-zeit');
    el.tRest = q('#trailer .t-rest');
    el.tPause = q('#trailer .t-pause');
    el.tMeldung = q('#trailer .t-meldung');
    el.hinweis = q('.hinweis');
    el.bewerten = q('#bewerten');
    el.wand = q('#wand');
    el.wName = q('#wand .w-name');
    el.wInfo = q('#wand .w-info');
    el.wRechts = q('#wand .w-rechts');
    el.wGitter = q('#wand .w-gitter');
    el.wLeer = q('#wand .w-leer');

    // Seitenleiste
    el.lEintraege = LEISTE.map(function (name, i) {
      const e = div('l-eintrag' + (i < APPS.length ? ' l-app' : ''));
      e.innerHTML = svg(i < APPS.length ? APP_SYMBOL : SYMBOLE[i - APPS.length]) + '<span></span>';
      e.querySelector('span').textContent = name;
      e.style.top = leisteTop(i) + 'px';
      e._leiste = i;
      el.leiste.appendChild(e);
      return e;
    });
    const linie = div('l-linie');
    linie.style.top = Math.round((leisteTop(APPS.length - 1) + leisteTop(APPS.length)) / 2 + 39) + 'px';
    el.leiste.appendChild(linie);

    // Bildschirmtastatur
    el.tasten = [];
    for (let i = 0; i < 2; i++) {
      const t = div('taste breit', i === 0 ? 'Leerzeichen' : 'Löschen');
      t.style.left = (LINKS + i * 3 * 112) + 'px';
      t.style.top = '222px';
      t._taste = [0, i * 3];
      el.tastatur.appendChild(t);
      el.tasten.push(t);
    }
    for (let z = 0; z < TASTEN.length; z++) {
      for (let s = 0; s < 6; s++) {
        const t = div('taste', TASTEN[z][s] === 'ß' ? 'ß' : TASTEN[z][s].toUpperCase());
        t.style.left = (LINKS + s * 112) + 'px';
        t.style.top = (222 + (z + 1) * 96) + 'px';
        t._taste = [z + 1, s];
        el.tastatur.appendChild(t);
        el.tasten.push(t);
      }
    }

    playerVorbereiten();
  }

  function skalieren() {
    const w = window.innerWidth || 1920, h = window.innerHeight || 1080;
    const s = Math.min(w / 1920, h / 1080);
    el.skala = s;
    el.versatzX = (w - 1920 * s) / 2;
    el.versatzY = (h - 1080 * s) / 2;
    el.buehne.style.transform = 'translate(' + el.versatzX + 'px,' + el.versatzY + 'px) scale(' + s + ')';
  }

  function sofort(e) {
    e.classList.add('sofort');
    void e.offsetWidth;
  }

  function sofortEnde(e) {
    void e.offsetWidth;
    e.classList.remove('sofort');
  }

  // ------------------------------------------------------------------ Anmelden (Quick Connect)

  async function anmelden() {
    ansicht = A.ANMELDEN;
    qcCode = null;
    qcSecret = null;
    qcFehler = 0;
    clearTimeout(qcTimer);
    zeichnen();
    try {
      const o = await jf.anfrage('POST', '/QuickConnect/Initiate');
      qcCode = o.Code;
      qcSecret = o.Secret;
      zeichnen();
      qcTimer = setTimeout(qcPruefen, 3000);
    } catch (e) {
      fehler(e && e.status === 401 ? 'Quick Connect ist in Jellyfin ausgeschaltet' : 'Jellyfin ist nicht erreichbar');
    }
  }

  async function qcPruefen() {
    if (ansicht !== A.ANMELDEN || !qcSecret) return;
    const s = qcSecret;
    try {
      const o = await jf.anfrage('GET', '/QuickConnect/Connect?secret=' + encodeURIComponent(s));
      if (ansicht !== A.ANMELDEN || s !== qcSecret) return;
      qcFehler = 0;
      if (o.Authenticated) {
        const a = await jf.anfrage('POST', '/Users/AuthenticateWithQuickConnect', { Secret: s });
        jf.token = a.AccessToken;
        jf.benutzer = a.User.Id;
        speicherSetzen('lk_token', jf.token);
        speicherSetzen('lk_user', jf.benutzer);
        laden();
      } else {
        qcTimer = setTimeout(qcPruefen, 3000);
      }
    } catch (e) {
      // abgelaufener Code: nach ein paar Fehlversuchen einen neuen holen
      if (++qcFehler >= 3) anmelden();
      else qcTimer = setTimeout(qcPruefen, 5000);
    }
  }

  function fehler(text) {
    fehlerText = text;
    ansicht = A.FEHLER;
    zeichnen();
  }

  // ------------------------------------------------------------------ Laden der Bereiche

  async function laden() {
    ansicht = A.LADEN;
    zeichnen();
    try {
      const views = await jf.bibliotheken();
      views.forEach(function (v) {
        const t = v.CollectionType;
        if (t === 'movies') filmeId = v.Id;
        else if (t === 'tvshows') serienId = v.Id;
        else if ((t === 'homevideos' || t === 'photos') && !fotosId) fotosId = v.Id;
      });
      // Merkliste und Bewertungen aus HA vor der Startseite holen (höchstens 4 Sekunden warten)
      await Promise.race([
        (async function () {
          try { await merk.laden(); } catch (e) { /* ohne Merkliste weiter */ }
          await familieLaden();
        })(),
        new Promise(function (ok) { setTimeout(ok, 4000); })
      ]);
      clearInterval(laden.familieTimer);
      laden.familieTimer = setInterval(familieLaden, 180000);
      const s = await filmeSeite();
      zeigeStart('filme', s);
      clearInterval(laden.auftragTimer);
      laden.auftragTimer = setInterval(auftragPruefen, 4000);
      auftragPruefen();
    } catch (e) {
      if (!jf.angemeldet()) anmelden();
      else fehler('Jellyfin ist nicht erreichbar');
    }
  }

  async function bereichOeffnen(b) {
    stapel.length = 0;
    if (b === 'merkliste') startseiten.merkliste = merklisteSeite();
    if (b === 'empfohlen') startseiten.empfohlen = empfohlenSeite();
    if (b === 'merkliste' || b === 'empfohlen') familieLaden(); // frisch aus HA nachziehen
    const fertig = startseiten[b];
    bereich = b;
    if (fertig) {
      seite = fertig;
      ansicht = A.LISTE;
      nachladen();
      fokusGeaendert();
      return;
    }
    ansicht = A.LADEN;
    zeichnen();
    try {
      const s = b === 'serien' ? await serienSeite() : b === 'fotos' ? await fotosSeite()
        : b === 'musik' ? await musikSeite() : await filmeSeite();
      zeigeStart(b, s);
    } catch (e) {
      fehler('Jellyfin ist nicht erreichbar');
    }
  }

  function zeigeStart(b, s) {
    startseiten[b] = s;
    if (b === 'filme') liveEinsetzen(null);
    if (bereich !== b) return;
    seite = s;
    ansicht = A.LISTE;
    nachladen();
    fokusGeaendert();
  }

  async function filmeSeite() {
    const s = neueSeite('Filme');
    const l = await Promise.all([jf.weiterschauen(), jf.neu(), jf.sammlungen()]);
    const weiter = l[0], neu = l[1], samml = l[2];
    if (weiter.length) s.reihen.push(reiheAus('Weiterschauen', weiter, POSTER));
    const gemerkt = merk.liste();
    if (gemerkt.length) {
      const r = reiheAus('Merkliste', gemerkt, POSTER);
      r._merk = true;
      s.reihen.push(r);
    }
    s.reihen.push(reiheAus('Neu', neu.filter(function (e) { return !istGesehen(e); }), POSTER));
    if (samml.length || ges.ids.length) {
      const kat = reiheAus('Kategorien', samml, POSTER);
      if (ges.ids.length) kat.eintraege.push(gesehenKachel());
      s.reihen.push(kat);
      samml.forEach(function (k) { s.reihen.push(neueReihe(k.Name, 'samml:' + k.Id, POSTER)); });
    }
    s.reihen.push(neueReihe('Alle Filme', '@alle', POSTER));
    return s;
  }

  // -------------------------------------------------- Musik und Karaoke (04.11.2026, Wunsch Lukas)
  // Player und Karaoke stecken in musik.js (gleiche Datei wie am TCL, dort in einer WebView). Hier nur die Reihen.
  // Karaoke-Modus steht immer ganz oben – auch wenn es noch keine Lieder mit Text gibt (Wunsch Lukas: nicht vergessen).

  let musikSkript = null;

  function musikModul() {
    if (window.LukasMusik) return Promise.resolve(window.LukasMusik);
    if (!musikSkript) {
      musikSkript = new Promise(function (ok, nein) {
        const sk = document.createElement('script');
        sk.src = 'musik.js?t=' + Date.now();
        sk.onload = function () { ok(window.LukasMusik); };
        sk.onerror = function () { musikSkript = null; nein(new Error('musik.js fehlt')); };
        document.body.appendChild(sk);
      });
    }
    return musikSkript;
  }

  function musikOpts() {
    return { server: SERVER, token: jf.token, benutzer: jf.benutzer, auth: function () { return jf.auth(); } };
  }

  function musikKopie(e, quelle) { return Object.assign({}, e, { _quelle: quelle }); }

  async function musikSeite() {
    const s = neueSeite('Musik');
    let lieder = [];
    try {
      const LM = await musikModul();
      lieder = (await LM.katalog(musikOpts(), true)).lieder;
    } catch (e) { /* ohne Musik bleibt wenigstens der Karaoke-Eintrag */ }
    const kar = lieder.filter(function (e) { return e._karaoke; });
    s.reihen.push(reiheAus('Karaoke', [{ Type: 'Karaoke', Id: '@karaoke', Name: 'Karaoke-Modus', ChildCount: kar.length }]
      .concat(kar.map(function (e) { return musikKopie(e, 'karaoke'); })), QUER));
    if (!lieder.length) return s;
    const gruppen = {};
    lieder.forEach(function (e) { (gruppen[e._gruppe] = gruppen[e._gruppe] || []).push(e); });
    Object.keys(gruppen).sort(function (a, b) {
      if (a === 'Sonstiges') return 1;
      if (b === 'Sonstiges') return -1;
      return gruppen[b].length - gruppen[a].length;
    }).forEach(function (g) {
      s.reihen.push(reiheAus(g, gruppen[g].map(function (e) { return musikKopie(e, 'genre:' + g); }), QUER));
    });
    s.reihen.push(reiheAus('Alle Lieder', [{ Type: 'MusikMix', Id: '@mix', Name: 'Alles mischen', ChildCount: lieder.length }]
      .concat(lieder.map(function (e) { return musikKopie(e, 'alle'); })), QUER));
    return s;
  }

  function musikStarten(o) {
    musikModul().then(function (LM) {
      LM.oeffnen(Object.assign(musikOpts(), o, { zu: function () { zeichnen(); } }));
    }, function () { hinweis('Musik lässt sich gerade nicht laden'); });
  }

  const MUSIK_TASTEN = {};
  function musikTaste(k) {
    if (!MUSIK_TASTEN[TASTE.OK]) {
      MUSIK_TASTEN[TASTE.OK] = 'ok'; MUSIK_TASTEN[TASTE.LINKS] = 'links'; MUSIK_TASTEN[TASTE.RECHTS] = 'rechts';
      MUSIK_TASTEN[TASTE.HOCH] = 'hoch'; MUSIK_TASTEN[TASTE.RUNTER] = 'runter'; MUSIK_TASTEN[TASTE.ZURUECK] = 'zurueck';
      MUSIK_TASTEN[MEDIEN.PLAY] = 'play'; MUSIK_TASTEN[MEDIEN.PAUSE] = 'pause'; MUSIK_TASTEN[MEDIEN.STOP] = 'stop';
      MUSIK_TASTEN[MEDIEN.VOR] = 'vor'; MUSIK_TASTEN[MEDIEN.RUECK] = 'rueck'; MUSIK_TASTEN[MEDIEN.PLAYPAUSE] = 'playpause';
    }
    if (MUSIK_TASTEN[k]) window.LukasMusik.taste(MUSIK_TASTEN[k]);
  }

  function istMusik(e) { const t = typ(e); return t === 'Audio' || t === 'Karaoke' || t === 'MusikMix'; }

  function musikInfo(box, e) {
    const LM = window.LukasMusik;
    const t = typ(e);
    if (t === 'Karaoke') {
      box.appendChild(div('i-kopf', 'MUSIK'));
      box.appendChild(div('i-titel', 'Karaoke-Modus'));
      box.appendChild(div('i-meta', e.ChildCount ? e.ChildCount + ' Lieder zum Mitsingen · gemischt' : 'Die ersten Texte kommen gerade'));
      box.appendChild(div('i-text', 'Oben steht die ganze Zeile zum Mitlesen, darunter läuft die Zeitspur: Balken zeigen, '
        + 'wann und wie lange gesungen wird, Lücken sind Pausen, die gelben Punkte zählen ein.'));
      return;
    }
    if (t === 'MusikMix') {
      box.appendChild(div('i-kopf', 'MUSIK'));
      box.appendChild(div('i-titel', 'Alles mischen'));
      box.appendChild(div('i-meta', e.ChildCount + ' Lieder'));
      return;
    }
    box.appendChild(div('i-kopf', (e._gruppe || 'Musik').toUpperCase()));
    const ti = div('i-titel');
    const sp = document.createElement('span');
    sp.textContent = e.Name || '';
    ti.appendChild(sp);
    box.appendChild(ti);
    const teile = [LM ? LM.interpret(e) : '', e.Album, e.ProductionYear > 0 ? String(e.ProductionYear) : '', dauer(e.RunTimeTicks)];
    box.appendChild(div('i-meta', teile.filter(Boolean).join('  ·  ')));
    if (e._karaoke) box.appendChild(div('i-text', 'Mit Karaoke-Text – im Player ▲ drücken.'));
  }

  function musikKarte(c, e) {
    const t = typ(e);
    if (t === 'Karaoke' || t === 'MusikMix') {
      c.classList.add(t === 'Karaoke' ? 'k-karaoke' : 'k-mix');
      c.innerHTML = (t === 'Karaoke'
        ? '<svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round">'
          + '<rect x="17" y="5" width="14" height="24" rx="7"/><path d="M10 23a14 14 0 0 0 28 0M24 37v7M16 44h16"/></svg>'
        : '<svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round">'
          + '<path d="M6 14h7c6 0 10 20 16 20h12M35 28l6 6-6 6M6 34h7c2.5 0 4.6-3.4 6.6-7.4M29 14h12M35 8l6 6-6 6"/></svg>')
        + '<div class="km-name"></div><div class="km-zahl"></div>';
      c.querySelector('.km-name').textContent = e.Name;
      const n = e.ChildCount || 0;
      c.querySelector('.km-zahl').textContent = t === 'Karaoke' ? (n ? n + ' Lieder' : 'kommt bald') : n + ' Lieder';
      return c;
    }
    c.classList.add('k-lied');
    const url = window.LukasMusik ? window.LukasMusik.bildUrl(e, 400) : '';
    c.innerHTML = '<div class="kl-cover">♪</div><div class="kl-text"><div class="kl-titel"></div><div class="kl-in"></div></div>'
      + (e._karaoke ? '<div class="kl-mikro">Karaoke</div>' : '');
    c.querySelector('.kl-titel').textContent = e.Name || '';
    c.querySelector('.kl-in').textContent = window.LukasMusik ? window.LukasMusik.interpret(e) : '';
    if (url) {
      const img = document.createElement('img');
      img.alt = '';
      img.decoding = 'async';
      img.onerror = function () { if (img.parentNode) img.parentNode.removeChild(img); };
      img.src = url;
      c.querySelector('.kl-cover').appendChild(img);
    }
    return c;
  }

  async function serienSeite() {
    const s = neueSeite('Serien');
    const l = await Promise.all([jf.serienWeiterschauen(), jf.naechsteFolgen(), jf.neueSerien(), jf.serien()]);
    if (l[0].length) s.reihen.push(reiheAus('Weiterschauen', l[0], QUER));
    if (l[1].length) s.reihen.push(reiheAus('Nächste Folgen', l[1], QUER));
    if (l[2].length) s.reihen.push(reiheAus('Neu', l[2], POSTER));
    if (l[3].length) s.reihen.push(reiheAus('Alle Serien', l[3], POSTER));
    if (!s.reihen.length) s.leer = 'Noch keine Serien';
    return s;
  }

  async function fotosSeite() {
    const s = neueSeite('Fotos');
    if (!fotosId) {
      s.leer = 'Noch keine Fotos';
      return s;
    }
    // Die oberste Ebene ändert sich selten: aus dem Speicher sofort zeigen, im Hintergrund auffrischen
    const schluessel = 'lk_fotos_' + fotosId;
    let inhalt = null;
    try { inhalt = JSON.parse(speicherLesen(schluessel) || 'null'); } catch (e) { inhalt = null; }
    if (!inhalt) {
      inhalt = await jf.fotoWurzel(fotosId);
      speicherSetzen(schluessel, JSON.stringify(inhalt));
    } else {
      jf.fotoWurzel(fotosId).then(function (a) { speicherSetzen(schluessel, JSON.stringify(a)); }, function () {});
    }
    // Wie in Plex: alle Ordner nach Namen als Kachel-Wand, lose Bilder dahinter
    const ordner = inhalt.filter(function (o) { return o && o.IsFolder; });
    const lose = inhalt.filter(function (o) { return o && !o.IsFolder; });
    if (!ordner.length && !lose.length) {
      s.leer = 'Die Fotos werden gerade eingelesen';
      return s;
    }
    rasterFuellen(s, ordner.concat(lose));
    s.kopfInfo = ordner.length + ' Ordner' + (lose.length ? ' · ' + anzahlText(lose) : '');
    return s;
  }

  /** Reihen in der Nähe des Fokus nachladen. */
  function nachladen() {
    const s = seite;
    for (let i = Math.max(0, s.akt - 1); i < Math.min(s.reihen.length, s.akt + 3); i++) {
      const r = s.reihen[i];
      if (r.geladen || r.laedt || !r.quelle) continue;
      r.laedt = true;
      holen(r.quelle).then(function (a) {
        r.eintraege = a.filter(function (e) { return e && (s.ohneFilter || !istGesehen(e)); });
        r.geladen = true;
        r.laedt = false;
        r.version++;
        if (seite === s) {
          if (s.reihen.indexOf(r) === s.akt) fokusGeaendert();
          else zeichnen();
        }
      }, function () { r.laedt = false; });
    }
  }

  function holen(quelle) {
    if (quelle === '@alle') return jf.alle();
    if (quelle.indexOf('samml:') === 0) return jf.inhalt(quelle.substring(6));
    if (quelle.indexOf('ordner:') === 0) return jf.ordner(quelle.substring(7));
    if (quelle.indexOf('staffel:') === 0) {
      const t = quelle.split(':');
      return jf.folgen(t[1], t[2]);
    }
    return Promise.resolve([]);
  }

  /** Nach dem Film: Weiterschauen und den Film selbst frisch holen. */
  async function nachDemFilm(f, quote) {
    const b = bereich;
    // ab 80 %: bei diesem Profil gesehen, und fragen, wie er gefallen hat
    if (f && typ(f) === 'Movie' && quote >= GESEHEN_GRENZE) {
      gesehenDazu(f);
      bewertenOeffnen(f);
    }
    try {
      const l = await Promise.all([f ? jf.film(f.Id) : Promise.resolve(null),
        b === 'serien' ? jf.serienWeiterschauen() : jf.weiterschauen()]);
      const frisch = l[0], weiter = l[1];
      if (frisch) {
        ersetzen(frisch);
        if (detailEintrag && frisch.Id === detailEintrag.Id) detailEintrag = frisch;
      }
      const start = startseiten[b];
      if (start && b !== 'fotos') {
        const w = start.reihen[0];
        if (w && w.titel === 'Weiterschauen') {
          w.eintraege = weiter.slice();
          w.version++;
          w.fokus = Math.min(w.fokus, Math.max(0, w.eintraege.length - 1));
          if (!w.eintraege.length && start.reihen.length > 1) {
            start.reihen.shift();
            if (start.akt > 0) start.akt--;
          }
        } else if (weiter.length) {
          start.reihen.unshift(reiheAus('Weiterschauen', weiter, b === 'serien' ? QUER : POSTER));
          if (seite === start) start.akt++;
        }
        if (b === 'filme') liveEinsetzen(null);
      }
      fokusGeaendert();
    } catch (e) { /* nicht schlimm */ }
  }

  function ersetzen(neu) {
    const alle = Object.keys(startseiten).map(function (k) { return startseiten[k]; }).concat(stapel, [seite]);
    alle.forEach(function (s) {
      s.reihen.forEach(function (r) {
        for (let i = 0; i < r.eintraege.length; i++) {
          if (r.eintraege[i] && r.eintraege[i].Id === neu.Id && !r.eintraege[i]._live) {
            r.eintraege[i] = neu;
            r.version++;
          }
        }
      });
    });
  }

  // ------------------------------------------------------------------ Fokus

  function aktuelle() {
    if (!seite.reihen.length) return null;
    return seite.reihen[begrenzen(seite.akt, 0, seite.reihen.length - 1)];
  }

  function fokusEintrag() {
    const r = aktuelle();
    if (!r || !r.eintraege.length) return null;
    return r.eintraege[begrenzen(r.fokus, 0, r.eintraege.length - 1)];
  }

  function heldEintrag() {
    if (ansicht === A.DETAIL && detailEintrag) return detailEintrag;
    if (ansicht === A.SUCHE) return inTreffern && trefferFokus < treffer.length ? treffer[trefferFokus] : null;
    return fokusEintrag();
  }

  let bgTimer = 0;

  function fokusGeaendert() {
    clearTimeout(bgTimer);
    bgTimer = setTimeout(hintergrundLaden, LEICHT ? 420 : 220);
    zeichnen();
  }

  function hintergrundLaden() {
    // In der Foto-Wand bleibt der Hintergrund ruhig: Titelbild des Ordners (Startseite: dunkel)
    const wand = seite.raster && ansicht === A.LISTE;
    const e = wand ? seite.kopf : heldEintrag();
    if (!e) {
      if (wand) hintergrundSetzen(null, '#1B1E25');
      return;
    }
    if (typ(e) === 'BoxSet' || typ(e) === 'Gesehen') {
      hintergrundSetzen(null, typ(e) === 'Gesehen' ? '#3E4A5E' : FARBEN[e.Name] || '#444444');
      return;
    }
    if (istMusik(e)) {
      const LM = window.LukasMusik;
      hintergrundSetzen(null, typ(e) === 'Karaoke' ? '#9A7404' : typ(e) === 'MusikMix' ? '#3E4A5E'
        : (LM ? LM.gruppenFarbe(e._gruppe) : '#3E4A5E'));
      return;
    }
    const url = hintergrundUrl(e);
    if (!url) hintergrundSetzen(null, '#1D2027');
    else hintergrundSetzen(url, null);
  }

  function hintergrundUrl(e) {
    const t = typ(e), id = e.Id;
    if (t === 'Episode') {
      const pt = e.ParentBackdropImageTags, pid = e.ParentBackdropItemId;
      if (pt && pt.length && pid) return hintergrundBild(pid, pt[0]);
      const pr = tag(e, 'Primary');
      return pr ? querUrl(id, pr, 1280) : null;
    }
    if (t === 'Photo' || t === 'Video') return querUrl(id, tag(e, 'Primary'), 1280);
    if (istOrdner(e)) {
      const pr = tag(e, 'Primary');
      if (pr) return querUrl(id, pr, 1280);
      const tb = titelbilder[id];
      return tb ? tb.replace('fillWidth=' + QW * 2, 'fillWidth=1280') : null;
    }
    const h = hintergrundTag(e);
    if (h) return hintergrundBild(id, h);
    const pr = tag(e, 'Primary');
    return pr ? posterUrl(id, pr, 1080) : null;
  }

  function logoUrlFuer(e) {
    if (!e) return null;
    const lt = tag(e, 'Logo');
    if (lt) return logoBild(e.Id, lt);
    if (e.ParentLogoItemId && e.ParentLogoImageTag) return logoBild(e.ParentLogoItemId, e.ParentLogoImageTag);
    return null;
  }

  function kartenUrl(e, form) {
    if (!e) return null;
    const id = e.Id, t = typ(e);
    if (form === QUER) {
      if (t === 'Folder' || t === 'PhotoAlbum') {
        const pr = tag(e, 'Primary');
        if (pr) return querUrl(id, pr, QW * 2);
        let tb = titelbilder[id];
        if (tb === undefined) {
          titelbildSuchen(id);
          tb = titelbilder[id]; // kann jetzt schon aus dem Speicher da sein
        }
        return tb || null;
      }
      const pr = tag(e, 'Primary');
      if (pr || t === 'Photo' || t === 'Video') return querUrl(id, pr, QW * 2);
      const h = hintergrundTag(e);
      return h ? hintergrundBild(id, h) : null;
    }
    const pr = tag(e, 'Primary');
    if (pr) return posterUrl(id, pr, 330);
    if ((t === 'Episode' || t === 'Season') && e.SeriesId && e.SeriesPrimaryImageTag) return posterUrl(e.SeriesId, e.SeriesPrimaryImageTag, 330);
    return null;
  }

  /** Titelbild eines Foto-Ordners: aus dem Speicher, sonst höchstens 2 Suchen gleichzeitig, die zuletzt gewünschte zuerst. */
  function titelbildSuchen(ordner) {
    if (titelbildLaedt[ordner]) return;
    titelbildLaedt[ordner] = true;
    if (titelSpeicher[ordner]) {
      titelbilder[ordner] = titelSpeicher[ordner];
      return;
    }
    titelWarteschlange.push(ordner);
    titelWeiter();
  }

  function titelWeiter() {
    while (titelLaufend < 2 && titelWarteschlange.length) {
      const ordner = titelWarteschlange.pop();
      titelLaufend++;
      titelbildHolen(ordner).then(function () {
        titelLaufend--;
        titelWeiter();
      });
    }
  }

  async function titelbildHolen(ordner) {
    let url = '';
    try {
      const f = await jf.erstesFoto(ordner);
      if (f && tag(f, 'Primary')) url = querUrl(f.Id, tag(f, 'Primary'), QW * 2);
    } catch (e) { /* später nochmal */ }
    titelbilder[ordner] = url;
    if (url) {
      titelSpeicher[ordner] = url;
      speicherSetzen('lk_titelbilder2', JSON.stringify(titelSpeicher));
    } else {
      setTimeout(function () {
        delete titelbilder[ordner];
        delete titelbildLaedt[ordner];
      }, 90000);
    }
    // nur die betroffenen Karten ergänzen, nicht die ganze Reihe neu bauen (sonst flackert es)
    if (url) {
      seite.reihen.forEach(function (r) {
        Object.keys(r.karten).forEach(function (k) {
          const c = r.karten[k], e = r.eintraege[c._index];
          if (!e || e.Id !== ordner || c.querySelector('img')) return;
          const img = document.createElement('img');
          img.alt = '';
          img.decoding = 'async';
          img.onerror = function () { if (img.parentNode) img.parentNode.removeChild(img); };
          img.src = url;
          c.insertBefore(img, c.firstChild);
        });
      });
    }
    if (url && seite.raster && seite.wKarten) {
      Object.keys(seite.wKarten).forEach(function (k) {
        const c = seite.wKarten[k];
        if (c._id !== ordner || c.querySelector('img')) return;
        wandBild(c, url.replace('fillWidth=' + QW * 2, 'fillWidth=' + ORDNER_RASTER.kw * 2));
      });
    }
    const e = heldEintrag();
    if (e && e.Id === ordner) fokusGeaendert();
  }

  // ------------------------------------------------------------------ Hintergrund mit Überblendung

  let bgOben = 0, bgSchluessel = null, bgAufraeumen = 0;

  function hintergrundSetzen(url, farbe) {
    const key = url || farbe;
    if (key === bgSchluessel) return;
    bgSchluessel = key;
    const zeigen = function () {
      if (bgSchluessel !== key) return;
      const neu = el.bg[1 - bgOben], alt = el.bg[bgOben];
      sofort(neu);
      neu.classList.remove('an');
      neu.style.zIndex = '2';
      alt.style.zIndex = '1';
      neu.style.backgroundImage = url ? 'url("' + url + '")' : farbVerlauf(farbe);
      sofortEnde(neu);
      neu.classList.add('an');
      bgOben = 1 - bgOben;
      clearTimeout(bgAufraeumen);
      bgAufraeumen = setTimeout(function () {
        sofort(alt);
        alt.classList.remove('an');
        alt.style.backgroundImage = 'none';
        sofortEnde(alt);
      }, 520);
    };
    if (url) {
      const img = new Image();
      img.onload = zeigen;
      img.onerror = function () {
        if (bgSchluessel === key) {
          bgSchluessel = null;
          hintergrundSetzen(null, '#1D2027');
        }
      };
      img.src = url;
    } else {
      zeigen();
    }
  }

  // ------------------------------------------------------------------ Logos (dunkle werden weiß oder bekommen einen Schein)
  // Fast ganz dunkle, einfarbige Logos (schwarze Schrift) werden weiß. Dunkle Logos mit hellen oder bunten Teilen
  // (z. B. Masters of the Universe) bleiben wie sie sind und bekommen nur einen hellen Schein – früher wurden auch sie
  // ganz weiß und waren dann ein weißer Klecks (Meldung Lukas 03.11.2026). Gleiche Regel wie Logo.java am TCL.

  let logoArt = {};
  try { logoArt = JSON.parse(speicherLesen('lk_logos2') || '{}') || {}; } catch (e) { logoArt = {}; }
  let logoSpeichern = 0;

  function logoKlasse(img, art) {
    img.classList.toggle('weiss', art === 'w');
    img.classList.toggle('schein', art === 's');
  }

  function logoPruefen(img, url) {
    if (logoArt[url] !== undefined) {
      logoKlasse(img, logoArt[url]);
      return;
    }
    try {
      const c = document.createElement('canvas');
      c.width = 64;
      c.height = 32;
      const g = c.getContext('2d');
      g.drawImage(img, 0, 0, 64, 32);
      const d = g.getImageData(0, 0, 64, 32).data;
      let summe = 0, n = 0, hell = 0, bunt = 0;
      for (let i = 0; i < d.length; i += 4) {
        if (d[i + 3] < 128) continue;
        const l = (d[i] * 299 + d[i + 1] * 587 + d[i + 2] * 114) / 1000;
        const sat = Math.max(d[i], d[i + 1], d[i + 2]) - Math.min(d[i], d[i + 1], d[i + 2]);
        summe += l;
        if (l > 160) hell++;
        if (sat > 60 && l > 50) bunt++;
        n++;
      }
      let art = '';
      if (n > 0) {
        const L = summe / n, H = hell / n, B = bunt / n;
        if (L < 95 && H < 0.10 && B < 0.15) art = 'w';
        else if (L < 115) art = 's';
      }
      logoArt[url] = art;
      clearTimeout(logoSpeichern);
      logoSpeichern = setTimeout(function () {
        const k = Object.keys(logoArt);
        if (k.length > 800) k.slice(0, k.length - 800).forEach(function (x) { delete logoArt[x]; });
        speicherSetzen('lk_logos2', JSON.stringify(logoArt));
      }, 3000);
    } catch (e) {
      logoArt[url] = '';
    }
    logoKlasse(img, logoArt[url]);
  }

  // ------------------------------------------------------------------ Zeichnen

  function zeichnen() {
    const player = P.offen;
    const liste = ansicht === A.LISTE || ansicht === A.DETAIL;
    const suche = ansicht === A.SUCHE;
    const mitte = ansicht === A.LADEN || ansicht === A.ANMELDEN || ansicht === A.FEHLER;

    el.player.classList.toggle('aus', !player);
    el.trailer.classList.toggle('aus', !T.offen);
    el.bgs.classList.toggle('aus', !(liste || suche));
    el.verlauf.classList.toggle('aus', !(liste || suche));
    const wand = liste && seite.raster && ansicht === A.LISTE;
    el.liste.classList.toggle('aus', !liste || wand);
    el.wand.classList.toggle('aus', !wand);
    el.suche.classList.toggle('aus', !suche);
    el.mitte.classList.toggle('aus', !mitte);
    el.foto.classList.toggle('aus', ansicht !== A.FOTO);

    if (wand) wandZeichnen();
    else if (liste) {
      reihenZeichnen();
      infoPlanen();
    }
    if (suche) sucheZeichnen();
    if (mitte) mitteZeichnen();
    if (ansicht === A.FOTO) fotoZeichnen();
    leisteZeichnen();
  }

  // -------------------------------------------------- Reihen

  function abstandVon(r) { return r.form === QUER ? QH + 118 : PH + 116; }

  function versatz(s, i) {
    let v = 0;
    for (let k = 0; k < Math.min(i, s.reihen.length); k++) v += abstandVon(s.reihen[k]);
    return v;
  }

  function reihenZeichnen() {
    const s = seite, cont = el.reihen;
    let neu = false;
    if (cont._seite !== s) {
      cont.innerHTML = '';
      cont._seite = s;
      s.reihen.forEach(function (r) { r.el = null; r.karten = {}; r.gebaut = -1; r.platzhalter = false; });
      neu = true;
    }
    // Reihen entfernen, die es auf der Seite nicht mehr gibt
    Array.prototype.slice.call(cont.children).forEach(function (c) {
      if (s.reihen.indexOf(c._reihe) < 0) {
        cont.removeChild(c);
        if (c._reihe) c._reihe.el = null;
      }
    });
    const detail = ansicht === A.DETAIL;
    if (neu) sofort(cont);
    cont.style.transform = 'translate3d(0,' + (-versatz(s, s.akt) + (detail ? 520 : 0)) + 'px,0)';
    cont.style.opacity = detail || s.leer ? '0' : '1';
    for (let i = 0; i < s.reihen.length; i++) {
      const r = s.reihen[i];
      const nah = !s.leer && i >= s.akt - 1 && i <= s.akt + 2;
      if (!nah) {
        if (r.el) r.el.style.display = 'none';
        continue;
      }
      if (!r.el) reiheBauen(r);
      r.el.style.display = '';
      r.el.style.top = (REIHE_Y + versatz(s, i)) + 'px';
      r.el.style.opacity = i < s.akt ? '0' : i > s.akt ? '0.6' : '1';
      r.titelEl.style.opacity = i === s.akt ? '0.9' : '0.67';
      // Reihen darunter: nur die Überschrift, keine angeschnittenen Kacheln am unteren Rand (Wunsch Lukas 02.11.2026)
      const obenAmSchirm = REIHE_Y + versatz(s, i) - versatz(s, s.akt);
      const sicht = (1080 - obenAmSchirm) / (r.form === QUER ? QH : PH);
      r.streifen.style.opacity = i > s.akt ? String(Math.max(0, Math.min(1, (sicht - 0.55) / 0.35))) : '1';
      r.titelEl.textContent = r.live ? '' : r.titel;
      kartenZeichnen(r, i === s.akt);
    }
    if (neu) sofortEnde(cont);
  }

  function reiheBauen(r) {
    const d = div('reihe');
    d._reihe = r;
    const t = div('reihe-titel');
    const st = div('streifen');
    d.appendChild(t);
    d.appendChild(st);
    r.el = d;
    r.titelEl = t;
    r.streifen = st;
    r.karten = {};
    r.gebaut = -1;
    r.platzhalter = false;
    el.reihen.appendChild(d);
  }

  /** x-Position einer Karte; in Weiterschauen stehen vorne die breiteren Live-Kacheln mit Abstand. */
  function kartenX(r, k) {
    const n = r.live || 0, sw = (r.form === QUER ? QW : PW) + ABSTAND;
    if (!n) return k * sw;
    return k < n ? k * (LIVE_W + ABSTAND) : n * (LIVE_W + ABSTAND) + LIVE_LUECKE + (k - n) * sw;
  }

  function kartenZeichnen(r, aktiv) {
    const quer = r.form === QUER, schritt = (quer ? QW : PW) + ABSTAND;
    if (r.gebaut !== r.version) {
      r.streifen.innerHTML = '';
      r.karten = {};
      r.platzhalter = false;
      r.gebaut = r.version;
      if (r.live) {
        // Überschriften wandern mit: „Läuft gerade“ über den Live-Kacheln, „Weiterschauen“ über dem Rest
        const t1 = div('live-titel');
        t1.innerHTML = '<i></i>Läuft gerade';
        t1.style.left = '0px';
        r.streifen.appendChild(t1);
        if (r.eintraege.length > r.live) {
          const t2 = div('live-titel', 'Weiterschauen');
          t2.style.left = kartenX(r, r.live) + 'px';
          r.streifen.appendChild(t2);
        }
      }
    }
    r.streifen.style.transform = 'translate3d(' + (-kartenX(r, r.fokus)) + 'px,0,0)';
    if (!r.geladen && !r.eintraege.length) {
      if (!r.platzhalter) {
        for (let k = 0; k < 8; k++) {
          const c = div('karte platz' + (quer ? ' quer' : ''));
          c.style.left = (k * schritt) + 'px';
          r.streifen.appendChild(c);
        }
        r.platzhalter = true;
      }
      return;
    }
    if (r.platzhalter) {
      r.streifen.innerHTML = '';
      r.karten = {};
      r.platzhalter = false;
    }
    const von = Math.max(0, r.fokus - 3);
    const bis = Math.min(r.eintraege.length - 1, r.fokus + Math.ceil(1810 / schritt) + 3);
    Object.keys(r.karten).forEach(function (k) {
      const n = +k;
      if (n < von || n > bis) {
        r.streifen.removeChild(r.karten[k]);
        delete r.karten[k];
      }
    });
    for (let k = von; k <= bis; k++) {
      let c = r.karten[k];
      if (!c) {
        c = k < (r.live || 0) ? liveKarteBauen(r.eintraege[k]) : karteBauen(r.eintraege[k], r.form);
        c.style.left = kartenX(r, k) + 'px';
        c._reihe = r;
        c._index = k;
        r.streifen.appendChild(c);
        r.karten[k] = c;
      }
      c.classList.toggle('fokus', aktiv && k === r.fokus);
      c.classList.toggle('vorher', k < r.fokus);
    }
  }

  function karteBauen(e, form) {
    const t = typ(e), ordner = istOrdner(e);
    const c = div('karte' + (form === QUER ? ' quer' : '') + (ordner ? ' ordner' : ''));
    if (!e) return c;
    if (istMusik(e)) return musikKarte(c, e);
    if (t === 'Gesehen') {
      c.classList.add('k-gesehen');
      c.innerHTML = '<svg viewBox="0 0 120 80" fill="none"><path d="M14 40Q60 -4 106 40Q60 84 14 40Z" stroke="#fff" stroke-width="5" '
        + 'stroke-linejoin="round"/><circle cx="60" cy="40" r="13" fill="#fff"/><circle cx="98" cy="64" r="16" fill="' + GELB + '"/>'
        + '<path d="M90 64l6 6 10-12" stroke="#1A1C22" stroke-width="4.2" stroke-linecap="round" stroke-linejoin="round"/></svg>'
        + '<div class="kg-name">Gesehen</div><div class="kg-zahl"></div>';
      const n = e.ChildCount || 0;
      c.querySelector('.kg-zahl').textContent = n === 1 ? '1 Film' : n + ' Filme';
      return c;
    }
    if (!ordner && t !== 'Photo' && t !== 'Video') c.appendChild(div('k-name', e.Name || ''));
    const url = kartenUrl(e, form);
    if (url) {
      const img = document.createElement('img');
      img.alt = '';
      img.decoding = 'async';
      img.onerror = function () { if (img.parentNode) img.parentNode.removeChild(img); };
      img.src = url;
      c.appendChild(img);
    }
    let unten = null;
    if (ordner) unten = e.Name || '';
    else if (t === 'Episode') unten = (e.IndexNumber > 0 ? e.IndexNumber + '. ' : '') + (e.Name || '');
    if (unten != null) {
      const u = div('k-unten');
      const sp = document.createElement('span');
      sp.textContent = unten;
      u.appendChild(sp);
      c.appendChild(u);
    }
    if (t === 'Video') c.appendChild(div('k-video'));
    // Fortschritt nur bei Filmen/Folgen, nicht bei Kategorien, Serien oder Ordnern (Wunsch Lukas 02.11.2026)
    const f = e.IsFolder ? 0 : fortschritt(e);
    if (f > 0.01 && f < 0.97 && !gesehen(e)) {
      const b = div('k-balken');
      b.innerHTML = '<i></i>';
      b.firstChild.style.width = (f * 100) + '%';
      c.appendChild(b);
    }
    if ((t === 'Movie' ? istGesehen(e) : gesehen(e)) && !ordner) {
      const h = div('k-haken');
      h.innerHTML = '<svg viewBox="0 0 20 20" fill="none" stroke="' + GELB + '" stroke-width="3.2" stroke-linecap="round" '
        + 'stroke-linejoin="round"><path d="M4 10l4.5 5L16 5"/></svg>';
      c.appendChild(h);
    }
    return c;
  }

  // -------------------------------------------------- Infos oben links

  let infoSchluessel = '';

  // Beim schnellen Durchscrollen (Taste gehalten oder schnell hintereinander) erst die Reihe bewegen –
  // Titel, Logo und Text oben links erst, wenn man kurz anhält. Spart dem Fernseher viel Arbeit.
  let infoTimer = 0;

  function infoPlanen() {
    clearTimeout(infoTimer);
    const schnell = ansicht === A.LISTE && wiederholung > 0 && Date.now() - letzteZeit < 200;
    if (schnell) infoTimer = setTimeout(infoZeichnen, 140);
    else infoZeichnen();
  }

  function infoZeichnen() {
    clearTimeout(infoTimer);
    const e = heldEintrag();
    const detail = ansicht === A.DETAIL;
    el.info.classList.toggle('detail', detail);
    el.info.classList.toggle('empf', bereich === 'empfohlen');
    let key;
    if (seite.leer) key = 'leer|' + seite.leer + '|' + seite.titel;
    else if (!e) key = '';
    else {
      const u = e.UserData || {};
      key = e.Id + '|' + bereich + '|' + (u.PlaybackPositionTicks || 0) + '|' + (u.Played ? 1 : 0) + '|' + (seite.kopf ? seite.kopf.Id : '')
        + '|' + (titelbilder[e.Id] || '');
    }
    if (key !== infoSchluessel) {
      infoSchluessel = key;
      el.info.innerHTML = '';
      const box = div('neu');
      box.style.display = 'contents';
      if (seite.leer) leerInfo(box);
      else if (e) {
        const t = typ(e);
        if (t === 'BoxSet' || t === 'Gesehen') kategorieInfo(box, e);
        else if (istMusik(e)) musikInfo(box, e);
        else if (istOrdner(e)) ordnerInfo(box, e);
        else if (t === 'Photo' || t === 'Video') fotoInfo(box, e);
        else filmInfo(box, e);
      }
      // "display: contents" mögen ältere Fernseher nicht immer – darum die Kinder direkt einhängen
      while (box.firstChild) {
        const k = box.firstChild;
        // Einblenden nur für Text und Bild – die Knöpfe sind außerhalb der Detailansicht unsichtbar und sollen es bleiben
        if (k.classList && !k.classList.contains('i-knoepfe') && !k.classList.contains('i-platz')) k.classList.add('neu');
        el.info.appendChild(k);
      }
    }
    knoepfeAktualisieren();
  }

  function filmInfo(box, e) {
    const folge = typ(e) === 'Episode';
    if (folge) {
      const st = e.ParentIndexNumber || 0, fo = e.IndexNumber || 0;
      box.appendChild(div('i-kopf', (e.SeriesName || '') + (st > 0 ? '  ·  Staffel ' + st : '') + (fo > 0 ? '  ·  Folge ' + fo : '')));
    }
    const lurl = folge ? null : logoUrlFuer(e);
    const titelBox = function () {
      const d = div('i-titel' + (folge ? ' folge' : ''));
      const sp = document.createElement('span');
      sp.textContent = e.Name || '';
      d.appendChild(sp);
      return d;
    };
    if (lurl) {
      const d = div('i-logo');
      const img = document.createElement('img');
      img.crossOrigin = 'anonymous';
      img.alt = '';
      img.style.opacity = '0';
      img.style.transition = 'opacity .15s';
      img.onload = function () {
        logoPruefen(img, lurl);
        img.style.opacity = '1';
      };
      img.onerror = function () {
        if (d.parentNode) d.parentNode.replaceChild(titelBox(), d);
      };
      img.src = lurl;
      d.appendChild(img);
      box.appendChild(d);
    } else {
      box.appendChild(titelBox());
    }

    const meta = div('i-meta');
    const teile = [];
    if (typ(e) === 'Series') {
      const ende = e.EndDate || '';
      if (e.ProductionYear > 0) teile.push(e.ProductionYear + (ende.length >= 4 ? '–' + ende.substring(0, 4) : ''));
      const n = e.ChildCount || 0;
      if (n > 0) teile.push(n === 1 ? '1 Staffel' : n + ' Staffeln');
    } else {
      if (e.ProductionYear > 0 && !folge) teile.push(String(e.ProductionYear));
      const d = dauer(e.RunTimeTicks);
      if (d) teile.push(d);
    }
    teile.forEach(function (t, i) {
      const sp = document.createElement('span');
      sp.className = 'teil';
      sp.textContent = (i > 0 ? '·  ' : '') + t;
      meta.appendChild(sp);
    });
    const f = fsk(e.OfficialRating);
    if (f) {
      const sp = document.createElement('span');
      sp.className = 'fsk';
      sp.textContent = f;
      meta.appendChild(sp);
    }
    if (e.CommunityRating > 0) {
      const sp = document.createElement('span');
      sp.className = 'stern';
      sp.textContent = '★ ' + e.CommunityRating.toFixed(1).replace('.', ',');
      meta.appendChild(sp);
    }
    if (e.Genres && e.Genres.length) {
      const sp = document.createElement('span');
      sp.textContent = '·  ' + e.Genres.slice(0, 2).join(', ');
      meta.appendChild(sp);
    }
    box.appendChild(meta);

    const fam = familienZeile(e, bereich === 'empfohlen');
    if (fam) box.appendChild(fam);

    const pos = position(e), ges = e.RunTimeTicks || 0;
    if (pos > 0 && ges > pos && !gesehen(e)) {
      const r = div('i-rest');
      r.innerHTML = '<div class="bar"><i></i></div><span></span>';
      r.querySelector('i').style.width = (fortschritt(e) * 100) + '%';
      r.querySelector('span').textContent = 'noch ' + dauer(ges - pos);
      box.appendChild(r);
    }

    if (e.Overview) box.appendChild(div('i-inhalt', e.Overview));

    if (abspielbar(e)) {
      box.appendChild(div('i-platz'));
      box.appendChild(div('i-knoepfe'));
    }
  }

  const KNOPF_SYMBOL = {
    abspielen: '<svg viewBox="0 0 30 30"><path d="M8 4l18 11-18 11z" fill="currentColor"/></svg>',
    vorne: '<svg viewBox="0 0 30 30" fill="none" stroke="currentColor" stroke-width="3.5" stroke-linecap="round">'
      + '<path d="M21.5 6.3A13 13 0 1 1 9 4.5"/><path d="M19 1.5l3.5 5-6 1.5" fill="currentColor" stroke-width="1"/></svg>',
    trailer: '<svg viewBox="0 0 30 30" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linejoin="round">'
      + '<rect x="3" y="6" width="24" height="18" rx="4"/><path d="M12.5 11l6 4-6 4z" fill="currentColor"/></svg>',
    merken: '<svg viewBox="0 0 30 30" fill="none" stroke="currentColor" stroke-width="3.4" stroke-linecap="round">'
      + '<path d="M15 5v20M5 15h20"/></svg>',
    gemerkt: '<svg viewBox="0 0 30 30" fill="none" stroke="currentColor" stroke-width="3.4" stroke-linecap="round" '
      + 'stroke-linejoin="round"><path d="M5 15.5l6.5 6.5L25 8.5"/></svg>',
    bewerten: '<svg viewBox="0 0 30 30"><path d="M15 3.5l3.4 7 7.6 1-5.5 5.3 1.4 7.6L15 20.8l-6.9 3.6 1.4-7.6L4 11.5l7.6-1z" '
      + 'fill="currentColor"/></svg>',
    melden: '<svg viewBox="0 0 30 30" fill="none" stroke="currentColor" stroke-width="2.8" stroke-linecap="round" '
      + 'stroke-linejoin="round"><path d="M6 2.5v26"/><path d="M6 3.5h18l-5 6 5 6H6z"/></svg>',
    gemeldet: '<svg viewBox="0 0 30 30" fill="currentColor" stroke="currentColor" stroke-width="2.8" stroke-linecap="round" '
      + 'stroke-linejoin="round"><path d="M6 2.5v26" fill="none"/><path d="M6 3.5h18l-5 6 5 6H6z"/></svg>'
  };

  function knoepfeFuer(e) {
    const l = [];
    if (!e) return l;
    if (abspielbar(e)) {
      const pos = position(e);
      if (pos > 0) {
        l.push({ art: 'weiter', text: 'Fortsetzen  ·  ' + zeit(pos / 10000000), symbol: 'abspielen' });
        l.push({ art: 'vorne', text: 'Von vorne', symbol: 'vorne' });
      } else {
        l.push({ art: 'abspielen', text: 'Abspielen', symbol: 'abspielen' });
      }
    }
    if (detailTrailer && detailTrailer.id === e.Id && detailTrailer.yt) l.push({ art: 'trailer', text: 'Trailer', symbol: 'trailer' });
    if (typ(e) === 'Movie') {
      const drin = merk.hat(e.Id);
      l.push({ art: 'merken', text: drin ? 'Gemerkt' : 'Merken', symbol: drin ? 'gemerkt' : 'merken' });
      l.push({ art: 'bewerten', text: eigeneNoten(e), symbol: 'bewerten' });
      const qa = qualitaetAn(e);
      l.push({ art: 'qualitaet', text: '', symbol: qa ? 'gemeldet' : 'melden', an: qa,
        tipp: qa ? (PERSON.ha === 'Lukas' ? 'Gemeldet von ' + (qual.wer[e.Id] || []).join(', ') + '  ·  drücken = erledigt'
          : 'Gemeldet  ·  nochmal drücken nimmt es zurück') : 'Schlechte Qualität melden' });
    }
    return l;
  }

  function knoepfeAktualisieren() {
    const k = el.info.querySelector('.i-knoepfe');
    const detail = ansicht === A.DETAIL;
    detailKnoepfe = detail ? knoepfeFuer(detailEintrag) : [];
    if (!k) return;
    if (!detail) return; // außerhalb der Detailansicht unsichtbar, Inhalt bleibt
    const sig = detailKnoepfe.map(function (x) { return x.art + ':' + x.text + ':' + (x.tipp || ''); }).join('|');
    if (k._sig !== sig) {
      k._sig = sig;
      k.innerHTML = '';
      detailKnoepfe.forEach(function (x, i) {
        const b = div('knopf');
        b._knopf = i;
        b.innerHTML = KNOPF_SYMBOL[x.symbol] + '<span></span>';
        b.querySelector('span').textContent = x.text;
        if (!x.text) b.classList.add('nur-symbol');
        if (x.an) b.classList.add('an');
        if (x.tipp) {
          const t = document.createElement('em');
          t.className = 'tipp';
          t.textContent = x.tipp;
          b.appendChild(t);
        }
        k.appendChild(b);
      });
    }
    detailKnopf = Math.max(0, Math.min(detailKnopf, detailKnoepfe.length - 1));
    for (let i = 0; i < k.children.length; i++) k.children[i].classList.toggle('fokus', i === detailKnopf);
  }

  function knopfAusfuehren(x) {
    const f = detailEintrag;
    if (!x || !f) return;
    if (x.art === 'weiter' || x.art === 'abspielen') abspielen(f, true);
    else if (x.art === 'vorne') abspielen(f, false);
    else if (x.art === 'trailer' && detailTrailer) trailerAbspielen(detailTrailer, f.Name || '');
    else if (x.art === 'bewerten') bewertenOeffnen(f);
    else if (x.art === 'qualitaet') qualitaetUmschalten(f);
    else if (x.art === 'merken') {
      merk.umschalten(f);
      hinweis(merk.hat(f.Id) ? 'Auf der Merkliste' : 'Von der Merkliste entfernt');
    }
  }

  function kategorieInfo(box, e) {
    const g = div('i-gross', e.Name || '');
    g.style.marginTop = '66px';
    box.appendChild(g);
    const n = e.ChildCount || 0;
    if (n > 0) {
      const z = div('i-zahl', n === 1 ? '1 Film' : n + ' Filme');
      z.style.marginTop = '8px';
      box.appendChild(z);
    }
  }

  function grossBox(box, text, klein) {
    const b = div('i-gross-box');
    const g = div('i-gross ' + (klein ? 'klein' : 'mittel'), text);
    b.appendChild(g);
    box.appendChild(b);
  }

  function ordnerInfo(box, e) {
    const name = String(e.Name || '').trim();
    grossBox(box, name, name.length > 16);
    const n = e.ChildCount || 0;
    if (n > 0) box.appendChild(div('i-zahl', n === 1 ? '1 Element' : n + ' Elemente'));
  }

  function fotoInfo(box, e) {
    const kopf = seite.kopf ? String(seite.kopf.Name || '').trim() : seite.titel;
    grossBox(box, kopf, true);
    const d = datum(e);
    if (d) box.appendChild(div('i-zahl', d));
  }

  function leerInfo(box) {
    box.appendChild(div('i-leer', seite.leer));
    box.appendChild(div('i-leer-titel', seite.titel));
  }

  // -------------------------------------------------- Mitte (Laden, Anmelden, Fehler)

  let mitteSchluessel = '';

  function mitteZeichnen() {
    const key = ansicht + '|' + (qcCode || '') + '|' + (fehlerText || '');
    if (key === mitteSchluessel) return;
    mitteSchluessel = key;
    const m = el.mitte;
    m.className = 'ebene ' + (ansicht === A.ANMELDEN ? 'anmelden-bg' : ansicht === A.FEHLER ? 'fehler-bg' : 'laden-bg');
    m.innerHTML = '';
    if (ansicht === A.LADEN) {
      m.appendChild(div('lade-punkt'));
    } else if (ansicht === A.ANMELDEN) {
      m.appendChild(div('glas kasten'));
      m.appendChild(div('zeile z-kopf', 'Filme verbinden'));
      if (qcCode) {
        const c = String(qcCode);
        m.appendChild(div('zeile z-code', c.length === 6 ? c.substring(0, 3) + ' ' + c.substring(3) : c));
        m.appendChild(div('zeile z-hilfe', 'Jellyfin am Handy oder PC  →  Profil  →  Quick Connect'));
      } else {
        const p = div('lade-punkt');
        p.style.top = '520px';
        m.appendChild(p);
      }
    } else {
      m.appendChild(div('zeile z-fehler', fehlerText || 'Fehler'));
      m.appendChild(div('zeile z-fehler2', 'OK = nochmal versuchen'));
    }
  }

  // -------------------------------------------------- Seitenleiste

  function leisteZeichnen() {
    el.leiste.classList.toggle('auf', leiste);
    el.dunkel.classList.toggle('auf', leiste);
    el.leiste.style.pointerEvents = leiste ? '' : 'none';
    el.lPille.style.top = leisteTop(leistePos) + 'px';
    let aktiv = APPS.length + (ansicht === A.SUCHE ? 0 : BEREICHE.indexOf(bereich) + 1);
    el.lEintraege.forEach(function (e, i) { e.classList.toggle('fokus', i === leistePos); });
    el.lPunkt.style.top = (leisteTop(aktiv) + 40 - 7) + 'px';
  }

  function leisteAuf(maus) {
    leiste = true;
    leisteMaus = !!maus;
    leistePos = APPS.length + (ansicht === A.SUCHE ? 0 : BEREICHE.indexOf(bereich) + 1); // ausgewählt ist, wo man gerade ist
    zeichnen();
  }

  function leisteZu() {
    leiste = false;
    leisteMaus = false;
    zeichnen();
  }

  function leisteWaehlen(i) {
    if (i < APPS.length) return appOeffnen(i); // Leiste bleibt offen und steht nach der Rückkehr noch dort
    leiste = false;
    leisteMaus = false;
    if (i === APPS.length) sucheOeffnen();
    else bereichOeffnen(BEREICHE[i - APPS.length - 1]);
    zeichnen();
  }

  // App am Fernseher öffnen (Prime Video, Netflix, YouTube): HA startet sie per LG-Integration (Automation 84, art=app).
  let appZeit = 0;
  function appOeffnen(i) {
    const a = APPS[i];
    const jetzt = Date.now();
    if (jetzt - appZeit < 2500) return; // doppeltes OK nicht zweimal schicken
    appZeit = jetzt;
    hinweis(a.name + ' wird geöffnet …');
    merk.senden({ art: 'app', wer: PERSON.ha, app: a.app }).catch(function () {
      hinweis(a.name + ' ließ sich nicht öffnen');
    });
  }

  // -------------------------------------------------- Suche

  let gitterVersion = -1;

  function sucheZeichnen() {
    // Suchfeld
    const t = suchText;
    el.sText.innerHTML = '';
    if (!t) {
      el.sText.className = 'text leer';
      el.sText.textContent = 'Filme und Serien';
    } else {
      el.sText.className = 'text';
      // rtl-Trick: bei langem Text bleibt das Ende sichtbar; das <bdi> hält die Reihenfolge richtig
      const bd = document.createElement('bdi');
      bd.textContent = t;
      el.sText.appendChild(bd);
      el.sText.appendChild(document.createElement('b'));
    }
    // Tastatur
    el.tasten.forEach(function (k) {
      const z = k._taste[0], s = k._taste[1];
      let f = !inTreffern && tasteZeile === z;
      if (f) f = z === 0 ? (s === 0 ? tasteSpalte < 3 : tasteSpalte >= 3) : tasteSpalte === s;
      k.classList.toggle('fokus', f);
    });
    el.suche.classList.toggle('in-treffern', inTreffern);
    // Kopf und Infozeile
    el.sKopf.textContent = suchIndex == null ? 'Wird geladen …'
      : !t.trim() ? 'Neu hinzugefügt' : !treffer.length ? 'Nichts gefunden' : 'Treffer';
    const held = inTreffern && trefferFokus < treffer.length ? treffer[trefferFokus] : null;
    let info = '';
    if (held) {
      info = held.Name || '';
      if (held.ProductionYear > 0) info += '  ·  ' + held.ProductionYear;
      if (typ(held) === 'Series') info += '  ·  Serie';
    }
    el.sHeld.textContent = info;
    // Treffer
    if (gitterVersion !== trefferVersion) {
      gitterVersion = trefferVersion;
      el.gitter.innerHTML = '';
      treffer.forEach(function (e, i) {
        const c = div('treffer');
        c.style.left = (40 + (i % SUCH_SPALTEN) * 194) + 'px';
        c.style.top = (60 + Math.floor(i / SUCH_SPALTEN) * 295) + 'px';
        c._treffer = i;
        c.appendChild(div('k-name', e.Name || ''));
        const url = kartenUrl(e, POSTER);
        if (url) {
          const img = document.createElement('img');
          img.alt = '';
          img.decoding = 'async';
          img.onerror = function () { if (img.parentNode) img.parentNode.removeChild(img); };
          img.src = url;
          c.appendChild(img);
        }
        el.gitter.appendChild(c);
      });
    }
    const karten = el.gitter.children;
    for (let i = 0; i < karten.length; i++) karten[i].classList.toggle('fokus', inTreffern && i === trefferFokus);
    const zeile = Math.floor(trefferFokus / SUCH_SPALTEN);
    el.gitter.style.transform = 'translate3d(0,' + (-Math.max(0, zeile - 1) * 295) + 'px,0)';
  }

  function sucheOeffnen() {
    if (ansicht !== A.SUCHE) sucheZurueck = ansicht === A.DETAIL ? A.LISTE : ansicht === A.FOTO ? A.LISTE : ansicht;
    if (sucheZurueck !== A.LISTE) sucheZurueck = A.LISTE;
    ansicht = A.SUCHE;
    inTreffern = false;
    tasteZeile = 1;
    tasteSpalte = 0;
    if (suchIndex == null && !suchIndexLaedt) {
      suchIndexLaedt = true;
      jf.suchIndex().then(function (a) {
        suchIndex = a;
        suchenJetzt();
      }, function () { suchIndexLaedt = false; });
    }
    suchenJetzt();
  }

  function tippen(s) {
    if (suchText.length < 40) suchText += s;
    suchenJetzt();
  }

  function loeschen() {
    if (suchText.length) suchText = suchText.slice(0, -1);
    suchenJetzt();
  }

  function suchenJetzt() {
    let neu = [];
    if (suchIndex) {
      const q = suchText.trim();
      if (!q) {
        neu = suchIndex.slice().sort(function (a, b) { return String(b.DateCreated || '').localeCompare(String(a.DateCreated || '')); })
          .slice(0, 15);
      } else {
        neu = Suche.suchen(q, suchIndex, 30);
      }
    }
    treffer = neu;
    trefferVersion++;
    trefferFokus = Math.min(trefferFokus, Math.max(0, treffer.length - 1));
    if (!treffer.length) inTreffern = false;
    fokusGeaendert();
  }

  // -------------------------------------------------- Öffnen

  function oeffnen(e, von) {
    if (!e) return;
    const t = typ(e);
    if (t === 'BoxSet') kategorieOeffnen(e);
    else if (t === 'Karaoke') musikStarten({ quelle: 'karaoke', karaoke: true, zufall: true });
    else if (t === 'MusikMix') musikStarten({ quelle: 'alle', zufall: true });
    else if (t === 'Audio') musikStarten({ quelle: e._quelle || 'alle', start: e.Id });
    else if (t === 'Gesehen') gesehenOeffnen(e);
    else if (t === 'Series') serieOeffnen(e);
    else if (istOrdner(e)) ordnerOeffnen(e);
    else if (t === 'Photo') fotoOeffnen(e);
    else if (t === 'Video') abspielen(e, true);
    else {
      detailEintrag = e;
      detailZurueck = von;
      ansicht = A.DETAIL;
      detailKnopf = 0;
      detailTrailer = null;
      fokusGeaendert();
      if (t === 'Movie') {
        trailerSuchen(e).then(function (tr) {
          if (tr && detailEintrag && detailEintrag.Id === e.Id) {
            detailTrailer = { id: e.Id, yt: tr.yt, titel: tr.titel };
            zeichnen();
          }
        }, function () {});
      }
    }
  }

  function seiteZeigen(s) {
    stapel.push(seite);
    seite = s;
    ansicht = A.LISTE;
    fokusGeaendert();
  }

  // Eine geöffnete Kategorie in Reihen, die man auch sieht (Meldung Lukas 03.11.2026: Kanu des Manitu fehlte scheinbar
  // in Komödien). Reihen unter der aktiven zeigen nur ihre Überschrift – die alten Reihen „zu je 8 ohne Überschrift“
  // waren dadurch unsichtbar. Jetzt: bis 10 Filme eine Reihe, sonst nach Jahren (neueste zuerst), Jahre mit weniger
  // als 4 Filmen zusammengefasst („2019 – 2024“). Gleiche Regel wie am TCL (reihenNachJahren).
  function reihenNachJahren(titel, roh) {
    if (!roh.length) return [];
    if (roh.length <= 10) return [reiheAus(titel, roh, POSTER)];
    const jahr = function (e) { return Number(e.ProductionYear) || 0; };
    const l = roh.map(function (e, i) { return { e: e, i: i }; })
      .sort(function (a, b) { return jahr(b.e) - jahr(a.e) || a.i - b.i; }).map(function (x) { return x.e; });
    const anzahl = {};
    l.forEach(function (e) { anzahl[jahr(e)] = (anzahl[jahr(e)] || 0) + 1; });
    const titelVon = function (von, bis) { return !von && !bis ? 'Ohne Jahr' : (von === bis ? String(von) : von + ' – ' + bis); };
    const out = [];
    let gruppe = [], von = 0, bis = 0, grossGruppe = false;
    l.forEach(function (e) {
      const j = jahr(e), gross = j !== 0 && anzahl[j] >= 4;
      if (gruppe.length) {
        const passt = grossGruppe ? (gross && j === von) : (!gross && (j === 0) === (von === 0));
        if (!passt) {
          out.push(reiheAus(titelVon(von, bis), gruppe, POSTER));
          gruppe = [];
        }
      }
      if (!gruppe.length) {
        von = j;
        bis = j;
        grossGruppe = gross;
      }
      von = Math.min(von, j);
      bis = Math.max(bis, j);
      gruppe.push(e);
    });
    if (gruppe.length) out.push(reiheAus(titelVon(von, bis), gruppe, POSTER));
    if (out.length) out[0].titel = titel + '  ·  ' + out[0].titel;   // oben steht die Kategorie dabei
    return out;
  }

  function kategorieOeffnen(k) {
    const neu = neueSeite(k.Name);
    neu.kopf = k;
    neu.reihen.push(neueReihe(k.Name, null, POSTER));
    seiteZeigen(neu);
    jf.inhalt(k.Id).then(function (roh) {
      neu.reihen = [];
      const a = roh.filter(function (e) { return !istGesehen(e); });
      reihenNachJahren(k.Name, a).forEach(function (r) { neu.reihen.push(r); });
      if (!neu.reihen.length) neu.leer = roh.length ? 'Alles schon gesehen' : 'Keine Filme';
      if (seite === neu) fokusGeaendert();
    }, function () { /* bleibt leer */ });
  }

  function serieOeffnen(serie) {
    const neu = neueSeite(serie.Name);
    neu.kopf = serie;
    neu.reihen.push(neueReihe('Staffeln', null, QUER));
    seiteZeigen(neu);
    jf.staffeln(serie.Id).then(function (st) {
      neu.reihen = st.map(function (s) { return neueReihe(s.Name, 'staffel:' + serie.Id + ':' + s.Id, QUER); });
      if (!neu.reihen.length) neu.leer = 'Keine Folgen';
      if (seite === neu) {
        nachladen();
        fokusGeaendert();
      }
    }, function () {});
  }

  function anzahlText(l) {
    let f = 0, v = 0;
    l.forEach(function (o) { if (typ(o) === 'Video') v++; else f++; });
    let s = '';
    if (f > 0) s += f + (f === 1 ? ' Foto' : ' Fotos');
    if (v > 0) s += (s ? ' · ' : '') + v + (v === 1 ? ' Video' : ' Videos');
    return s;
  }

  function ordnerOeffnen(ordner) {
    const neu = neueSeite(ordnerName(ordner));
    neu.kopf = ordner;
    neu.raster = true; // Kopf sofort, die Wand kommt, sobald der Inhalt da ist
    neu.kopfInfo = '';
    seiteZeigen(neu);
    jf.ordner(ordner.Id).then(function (a) {
      const unter = [], bilder = [];
      a.forEach(function (o) {
        if (!o) return;
        if (o.IsFolder) unter.push(o);
        else bilder.push(o);
      });
      rasterFuellen(neu, unter.concat(bilder));
      const info = [];
      const zr = zeitraum(bilder);
      if (zr) info.push(zr);
      if (unter.length) info.push(unter.length + ' Ordner');
      if (bilder.length) info.push(anzahlText(bilder));
      neu.kopfInfo = info.join('   ·   ');
      if (!neu.reihen.length) neu.leer = 'Leer';
      if (seite === neu) fokusGeaendert();
    }, function () {});
  }

  // -------------------------------------------------- Foto-Wand (wie in Plex)
  // Fotos-Startseite = alle Ordner als Kachel-Wand (6 nebeneinander), im Ordner eine dichte Bilder-Wand
  // (7 nebeneinander). In der ersten Zeile steht oben groß, was es ist; ab der zweiten rutscht das als
  // kleine Zeile nach oben und die ganze Fläche gehört den Bildern. Jede Reihe ist eine Zeile, fokus = Spalte.

  const ORDNER_RASTER = { spalten: 6, kw: 268, kh: 151, luecke: 17 };
  const FOTO_RASTER = { spalten: 7, kw: 230, kh: 172, luecke: 14 };
  const WAND_OBEN_KLEIN = 112, WAND_OBEN_GROSS = 300;

  function ordnerName(e) { return String((e && e.Name) || '').trim().replace(/\s+/g, ' '); }

  function rasterFuellen(s, eintraege) {
    const nurOrdner = eintraege.every(function (o) { return o && o.IsFolder; });
    const m = nurOrdner ? ORDNER_RASTER : FOTO_RASTER;
    s.raster = true;
    s.spalten = m.spalten;
    s.kw = m.kw;
    s.kh = m.kh;
    s.luecke = m.luecke;
    s.reihen = [];
    for (let i = 0; i < eintraege.length; i += m.spalten) s.reihen.push(reiheAus('', eintraege.slice(i, i + m.spalten), QUER));
    s.akt = 0;
    s.wandVersion = (s.wandVersion || 0) + 1;
  }

  /** Zeitraum der Aufnahmen („3.–12. April 2025“), aus dem Aufnahmedatum der Fotos. */
  function zeitraum(l) {
    let von = null, bis = null;
    l.forEach(function (o) {
      const d = String((o && o.PremiereDate) || '');
      if (d.length < 10 || o.IsFolder) return;
      const t = d.substring(0, 10);
      if (!von || t < von) von = t;
      if (!bis || t > bis) bis = t;
    });
    if (!von) return null;
    const M = ['Januar', 'Februar', 'März', 'April', 'Mai', 'Juni', 'Juli', 'August', 'September', 'Oktober', 'November', 'Dezember'];
    const a = von.split('-'), b = bis.split('-');
    const ta = parseInt(a[2], 10), tb = parseInt(b[2], 10), ma = M[parseInt(a[1], 10) - 1], mb = M[parseInt(b[1], 10) - 1];
    if (von === bis) return ta + '. ' + ma + ' ' + a[0];
    if (a[0] === b[0] && a[1] === b[1]) return ta + '.–' + tb + '. ' + ma + ' ' + a[0];
    if (a[0] === b[0]) return ta + '. ' + ma + ' – ' + tb + '. ' + mb + ' ' + a[0];
    return ma + ' ' + a[0] + ' – ' + mb + ' ' + b[0];
  }

  /** Bild einer Kachel in der Wand – kleiner als in den Reihen. */
  function rasterUrl(e) {
    if (e.IsFolder) {
      const pr = tag(e, 'Primary');
      if (pr) return querUrl(e.Id, pr, ORDNER_RASTER.kw * 2);
      let tb = titelbilder[e.Id];
      if (tb === undefined) {
        titelbildSuchen(e.Id);
        tb = titelbilder[e.Id];
      }
      return tb ? tb.replace('fillWidth=' + QW * 2, 'fillWidth=' + ORDNER_RASTER.kw * 2) : null;
    }
    return querUrl(e.Id, tag(e, 'Primary'), 360);
  }

  function wandBild(c, url) {
    const img = document.createElement('img');
    img.alt = '';
    img.decoding = 'async';
    img.onerror = function () { if (img.parentNode) img.parentNode.removeChild(img); };
    img.src = url;
    c.insertBefore(img, c.firstChild);
  }

  function wandKarte(e, s, r, i, j) {
    const c = div('wk' + (e.IsFolder ? ' ordner' : ''));
    c.style.left = (LINKS + j * (s.kw + s.luecke)) + 'px';
    c.style.top = (i * (s.kh + s.luecke)) + 'px';
    c.style.width = s.kw + 'px';
    c.style.height = s.kh + 'px';
    c._id = e.Id;
    c._reihe = r;
    c._index = j;
    const url = rasterUrl(e);
    if (url) wandBild(c, url);
    if (e.IsFolder) {
      const u = div('wk-name');
      const sp = document.createElement('span');
      sp.textContent = ordnerName(e);
      u.appendChild(sp);
      c.appendChild(u);
    }
    if (typ(e) === 'Video') c.appendChild(div('k-video'));
    return c;
  }

  function wandZeichnen() {
    const s = seite, g = el.wGitter;
    const gross = s.akt === 0;
    el.wand.classList.toggle('klein', !gross);
    el.wName.textContent = s.kopf ? ordnerName(s.kopf) : s.titel;
    el.wInfo.textContent = s.kopfInfo || '';
    // rechts: Aufnahmedatum und Nummer des Fotos im Fokus
    const f = fokusEintrag();
    let rechts = '';
    if (f && !f.IsFolder) {
      let nr = 0, gesamt = 0;
      s.reihen.forEach(function (r, i) {
        r.eintraege.forEach(function (o, j) {
          if (!o || o.IsFolder) return;
          gesamt++;
          if (i < s.akt || (i === s.akt && j <= r.fokus)) nr++;
        });
      });
      const d = datum(f);
      rechts = (d ? d + '      ' : '') + nr + ' / ' + gesamt;
    }
    el.wRechts.textContent = rechts;
    const neu = g._seite !== s || g._version !== s.wandVersion;
    if (neu) {
      g.innerHTML = '';
      g._seite = s;
      g._version = s.wandVersion;
      s.wKarten = {};
    }
    el.wLeer.textContent = s.reihen.length ? '' : (s.leer || '');
    if (!s.reihen.length) {
      if (!s.leer && !g.children.length) {
        for (let i = 0; i < 4; i++) {
          for (let j = 0; j < FOTO_RASTER.spalten; j++) {
            const c = div('wk platz');
            c.style.left = (LINKS + j * (FOTO_RASTER.kw + FOTO_RASTER.luecke)) + 'px';
            c.style.top = (i * (FOTO_RASTER.kh + FOTO_RASTER.luecke)) + 'px';
            c.style.width = FOTO_RASTER.kw + 'px';
            c.style.height = FOTO_RASTER.kh + 'px';
            g.appendChild(c);
          }
        }
      }
      g.style.transform = 'translate3d(0,14px,0)';
      return;
    }
    const schritt = s.kh + s.luecke;
    const gesamtH = s.reihen.length * schritt - s.luecke;
    const max = Math.max(0, gesamtH - (1080 - WAND_OBEN_KLEIN - 40));
    // die Zeile im Fokus steht als zweite Zeile, so sieht man immer, was darüber war
    const scroll = Math.min(max, Math.max(0, s.akt - 1) * schritt);
    if (neu) sofort(g);
    g.style.transform = 'translate3d(0,' + (14 - scroll) + 'px,0)';
    const von = Math.max(0, s.akt - 3), bis = Math.min(s.reihen.length - 1, s.akt + 7);
    Object.keys(s.wKarten).forEach(function (k) {
      const i = parseInt(k, 10);
      if (i < von || i > bis) {
        const c = s.wKarten[k];
        if (c.parentNode) c.parentNode.removeChild(c);
        delete s.wKarten[k];
      }
    });
    for (let i = von; i <= bis; i++) {
      const r = s.reihen[i];
      r.eintraege.forEach(function (e, j) {
        if (!e) return;
        const k = i + ':' + j;
        let c = s.wKarten[k];
        if (!c) {
          c = wandKarte(e, s, r, i, j);
          g.appendChild(c);
          s.wKarten[k] = c;
        }
        c.classList.toggle('fokus', i === s.akt && j === r.fokus);
      });
    }
    if (neu) sofortEnde(g);
  }

  /** Pfeile in der Wand: frei in alle Richtungen, rechts am Zeilenende geht es in der nächsten Zeile weiter. */
  function wandTaste(k, r) {
    const s = seite, spalte = r.fokus;
    if (k === TASTE.LINKS) {
      if (r.fokus > 0) r.fokus--;
      else if (wiederholung === 0) return leisteAuf();
      else return;
    } else if (k === TASTE.RECHTS) {
      if (r.fokus < r.eintraege.length - 1) r.fokus++;
      else if (s.akt < s.reihen.length - 1) {
        s.akt++;
        s.reihen[s.akt].fokus = 0;
      }
    } else if (k === TASTE.HOCH) {
      if (s.akt > 0) {
        s.akt--;
        s.reihen[s.akt].fokus = Math.min(spalte, s.reihen[s.akt].eintraege.length - 1);
      }
    } else if (k === TASTE.RUNTER) {
      if (s.akt < s.reihen.length - 1) {
        s.akt++;
        s.reihen[s.akt].fokus = Math.min(spalte, s.reihen[s.akt].eintraege.length - 1);
      }
    } else if (k === TASTE.OK || k === MEDIEN.PLAY || k === MEDIEN.PLAYPAUSE) {
      return oeffnen(fokusEintrag(), A.LISTE);
    } else return;
    fokusGeaendert();
  }

  // -------------------------------------------------- Fotos im Vollbild

  let fotoOben = 0, fotoSchluessel = null;

  function fotoOeffnen(e) {
    fotos.length = 0;
    seite.reihen.forEach(function (r) {
      r.eintraege.forEach(function (o) { if (typ(o) === 'Photo' || typ(o) === 'Video') fotos.push(o); });
    });
    fotoIdx = 0;
    for (let i = 0; i < fotos.length; i++) if (fotos[i].Id === e.Id) fotoIdx = i;
    ansicht = A.FOTO;
    diashow = false;
    fotoInfoZeigen(2500);
    fotoLaden();
    zeichnen();
  }

  function fotoInfoZeigen(ms) {
    fotoInfoAn = true;
    clearTimeout(fotoInfoTimer);
    if (ms > 0) fotoInfoTimer = setTimeout(function () { fotoInfoAn = false; zeichnen(); }, ms);
  }

  function fotoLaden() {
    if (!fotos.length) return;
    const e = fotos[fotoIdx];
    const url = fotoUrl(e.Id, tag(e, 'Primary'));
    fotoSchluessel = url;
    const img = new Image();
    img.onload = function () {
      if (fotoSchluessel !== url) return;
      const neu = el.fbild[1 - fotoOben], alt = el.fbild[fotoOben];
      sofort(neu);
      neu.classList.remove('an');
      neu.style.zIndex = '2';
      alt.style.zIndex = '1';
      neu.style.backgroundImage = 'url("' + url + '")';
      sofortEnde(neu);
      neu.classList.add('an');
      alt.classList.remove('an');
      fotoOben = 1 - fotoOben;
    };
    img.src = url;
    [1, -1].forEach(function (d) {
      const n = fotoIdx + d;
      if (n >= 0 && n < fotos.length) (new Image()).src = fotoUrl(fotos[n].Id, tag(fotos[n], 'Primary'));
    });
    zeichnen();
  }

  function fotoZeichnen() {
    if (!fotos.length) return;
    const e = fotos[fotoIdx];
    el.fVideo.classList.toggle('aus', typ(e) !== 'Video');
    el.fPille.classList.toggle('aus', !fotoInfoAn);
    const d = datum(e);
    el.fPille.textContent = (d ? d + '   ·   ' : '') + (fotoIdx + 1) + ' / ' + fotos.length + (diashow ? '   ·   Diashow' : '');
  }

  function fotoSchliessen() {
    diashow = false;
    clearTimeout(diaTimer);
    if (fotos.length) {
      const id = fotos[fotoIdx].Id;
      seite.reihen.forEach(function (r, i) {
        r.eintraege.forEach(function (o, j) {
          if (o && o.Id === id) {
            seite.akt = i;
            r.fokus = j;
          }
        });
      });
    }
    el.fbild.forEach(function (b) { b.classList.remove('an'); b.style.backgroundImage = 'none'; });
    fotoSchluessel = null;
    ansicht = A.LISTE;
    nachladen();
    fokusGeaendert();
  }

  function diaWeiter() {
    if (ansicht !== A.FOTO || !diashow) return;
    fotoIdx = fotoIdx < fotos.length - 1 ? fotoIdx + 1 : 0;
    fotoLaden();
    diaTimer = setTimeout(diaWeiter, 5000);
  }

  // ------------------------------------------------------------------ Player

  const P = {
    offen: false, eintrag: null, id: null, quelle: null, sitzung: null, gemeldet: false, gestoppt: false,
    streams: [], spulZiel: -1, spulSchritte: 0, letzteSpul: 0, spulTimer: 0,
    leisteAn: false, bisWann: 0, menue: false, spalte: 0, zeile: 0, ton: [], unter: [],
    tonAktiv: -1, uAktiv: -1, cues: null, meldung: null, tick: 0, meldeTick: 0, start: 0,
    art: 'direkt', tonStandard: -1, tonWunsch: -1, spurenFertig: false, hlsVersucht: false, direktVersucht: false, startNr: 0
  };

  function playerVorbereiten() {
    const v = el.video;
    v.addEventListener('loadedmetadata', function () {
      if (!P.offen) return;
      if (P.start > 0 && P.start < (v.duration || Infinity) - 5) {
        try { v.currentTime = P.start; } catch (e) { /* egal */ }
      }
      if (!P.spurenFertig) {
        P.spurenFertig = true;
        spurenStart();
      }
      playerLeisteZeichnen();
    });
    v.addEventListener('playing', function () {
      if (!P.offen) return;
      P.meldung = null;
      if (!P.gemeldet) {
        P.gemeldet = true;
        melde(0);
      } else {
        melde(1);
      }
      leisteZeigen(2500);
    });
    v.addEventListener('pause', function () {
      if (!P.offen || v.ended) return;
      if (P.gemeldet) melde(1);
      leisteZeigen(0);
    });
    v.addEventListener('ended', function () {
      if (!P.offen) return;
      melde(2);
      P.gestoppt = true;
      playerSchliessen();
    });
    v.addEventListener('error', function () {
      if (!P.offen || !v.getAttribute('src')) return;
      const c = v.error ? v.error.code : 0;
      const pos = v.currentTime > 1 ? v.currentTime : P.start;
      // Direkt ging nicht (z. B. Tonformat) -> über den Pi; über den Pi ging nicht -> direkt mit der Standardspur
      if (P.art === 'direkt' && !P.hlsVersucht) {
        P.meldung = 'Wird über den Pi angepasst …';
        leisteZeigen(0);
        streamStarten('hls', pos);
        return;
      }
      const std = P.ton[P.tonStandard];
      if (P.art === 'hls' && !P.direktVersucht && (!std || kompatibel(std.stream))) {
        P.tonAktiv = P.tonStandard;
        P.meldung = 'Ging über den Pi nicht – spiele direkt';
        leisteZeigen(0);
        streamStarten('direkt', pos);
        return;
      }
      P.meldung = 'Abspielen ging nicht' + (c ? ' (Fehler ' + c + ')' : '');
      leisteZeigen(0);
    });
    v.addEventListener('progress', function () { if (P.leisteAn) playerLeisteZeichnen(); });
  }

  async function abspielen(f, fortsetzen) {
    if (!f) return;
    clearTimeout(diaTimer);
    diashow = false;
    Object.assign(P, {
      offen: true, eintrag: f, id: f.Id, quelle: f.Id, sitzung: 'lk' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7),
      gemeldet: false, gestoppt: false, streams: [], spulZiel: -1, spulSchritte: 0, menue: false, tonAktiv: -1, uAktiv: -1,
      cues: null, meldung: null, start: fortsetzen ? position(f) / 10000000 : 0, ton: [], unter: [],
      art: 'direkt', tonStandard: -1, tonWunsch: -1, spurenFertig: false, hlsVersucht: false, direktVersucht: false
    });
    el.untertitel.textContent = '';
    let titel = f.Name || '';
    if (typ(f) === 'Episode') titel = (f.SeriesName || '') + ' · ' + titel;
    el.pTitel.textContent = titel;
    el.pTitel.classList.remove('aus');
    el.pLogo.classList.add('aus');
    const lurl = typ(f) === 'Movie' ? logoUrlFuer(f) : null;
    if (lurl) {
      el.pLogo.crossOrigin = 'anonymous';
      el.pLogo.onload = function () {
        if (!P.offen || P.id !== f.Id) return;
        logoPruefen(el.pLogo, lurl);
        el.pLogo.classList.remove('aus');
        el.pTitel.classList.add('aus');
      };
      el.pLogo.src = lurl;
    }
    leisteZeigen(4000);
    zeichnen();

    try {
      const voll = await jf.film(f.Id);
      if (!P.offen || P.id !== f.Id) return;
      const ms = voll && voll.MediaSources && voll.MediaSources[0];
      if (ms) {
        P.quelle = ms.Id || f.Id;
        P.streams = ms.MediaStreams || voll.MediaStreams || [];
      }
      spurenListen();
    } catch (e) { /* dann eben ohne Spurliste */ }
    if (!P.offen || P.id !== f.Id) return;
    const v = el.video;
    const w = tonWahl();
    P.tonWunsch = w.j;
    P.tonAktiv = w.hls ? w.j : P.tonStandard;
    if (w.hls) {
      P.meldung = 'Ton wird über den Pi angepasst …';
      leisteZeigen(0);
    }
    streamStarten(w.hls ? 'hls' : 'direkt', P.start);
    clearInterval(P.tick);
    clearInterval(P.meldeTick);
    P.tick = setInterval(playerTick, 250);
    P.meldeTick = setInterval(function () { if (P.gemeldet) melde(1); }, 10000); // auch pausiert: HA soll wissen, dass noch geschaut wird
    requestAnimationFrame(untertitelSchleife);
  }

  // -------------------------------------------------- Direkt oder über den Pi

  function kompatibel(s) {
    const c = String((s && s.Codec) || '').toLowerCase();
    return PERSON.ohneTon.indexOf(c) < 0;
  }

  /** Welche Tonspur, und geht das direkt? Deutsch bevorzugt, davon am liebsten eine, die der Fernseher selbst kann. */
  function tonWahl() {
    if (!P.ton.length) return { j: -1, hls: false };
    const std = P.tonStandard >= 0 ? P.tonStandard : 0;
    const ok = function (i) { return kompatibel(P.ton[i].stream); };
    let j = std;
    const de = [];
    P.ton.forEach(function (t, i) { if (istDeutsch(t.stream)) de.push(i); });
    if (de.length) {
      const deOk = de.filter(ok);
      if (de.indexOf(std) >= 0 && ok(std)) j = std;
      else if (deOk.length) j = deOk[0];
      else j = de.indexOf(std) >= 0 ? std : de[0];
    } else if (!ok(std)) {
      const sprache = P.ton[std].stream.Language;
      for (let i = 0; i < P.ton.length; i++) {
        if (ok(i) && P.ton[i].stream.Language === sprache) { j = i; break; }
      }
    }
    // Ohne audioTracks spielt der Fernseher immer die Standardspur der Datei – eine andere geht dann nur über den Pi
    const umschaltbar = !!el.video.audioTracks;
    return { j: j, hls: !ok(j) || (j !== std && !umschaltbar) };
  }

  function encodingStoppen() {
    if (!jf.token || !P.sitzung) return Promise.resolve();
    return jf.anfrage('DELETE', '/Videos/ActiveEncodings?deviceId=' + encodeURIComponent(geraetId)
      + '&playSessionId=' + encodeURIComponent(P.sitzung)).catch(function () { /* egal */ });
  }

  /** Stream (neu) starten – direkt vom Server oder als HLS über den Pi – ab Sekunde ab. */
  async function streamStarten(art, ab) {
    const nr = ++P.startNr, id = P.id;
    const v = el.video;
    if (P.art === 'hls' && v.getAttribute('src')) {
      try { v.pause(); } catch (e) { /* egal */ }
      await encodingStoppen();
      if (!P.offen || P.id !== id || nr !== P.startNr) return;
    }
    P.art = art;
    if (art === 'hls') P.hlsVersucht = true;
    else P.direktVersucht = true;
    P.start = ab || 0;
    const t = P.ton[P.tonAktiv];
    v.src = art === 'hls'
      ? jf.hlsUrl(P.id, P.quelle, P.sitzung, t && t.stream ? t.stream.Index : -1)
      : jf.streamUrl(P.id, P.quelle);
    const p = v.play();
    if (p && p.catch) p.catch(function () { /* Autoplay im Browser ggf. gesperrt – OK drücken */ });
  }

  /** Tonspur wechseln: direkt, wenn der Browser umschalten kann, sonst Neustart an derselben Stelle (ggf. über den Pi). */
  function tonWechseln(j) {
    const t = P.ton[j];
    if (!t || j === P.tonAktiv) return;
    if (P.art === 'direkt' && kompatibel(t.stream) && tonSetzen(j)) {
      melde(1);
      return;
    }
    const v = el.video;
    const pos = v.currentTime > 1 ? v.currentTime : P.start;
    P.tonAktiv = j;
    P.hlsVersucht = false;
    P.direktVersucht = false;
    P.meldung = 'Tonspur wird gewechselt …';
    leisteZeigen(0);
    streamStarten(j === P.tonStandard && kompatibel(t.stream) ? 'direkt' : 'hls', pos);
  }

  /** 0 = Start, 1 = Fortschritt, 2 = Stopp */
  function melde(art) {
    if (!P.id || !jf.token) return;
    const v = el.video;
    const ticks = Math.round((v.currentTime || 0) * 10000000);
    const pfad = art === 0 ? '/Sessions/Playing' : art === 1 ? '/Sessions/Playing/Progress' : '/Sessions/Playing/Stopped';
    const body = {
      ItemId: P.id, MediaSourceId: P.quelle, PositionTicks: ticks, IsPaused: !!v.paused, CanSeek: true,
      PlayMethod: P.art === 'hls' ? 'Transcode' : 'DirectPlay', PlaySessionId: P.sitzung
    };
    const ton = P.ton[P.tonAktiv];
    if (ton && ton.stream) body.AudioStreamIndex = ton.stream.Index;
    body.SubtitleStreamIndex = P.uAktiv >= 0 ? P.uAktiv : -1;
    jf.anfrage('POST', pfad, body).catch(function () { /* nicht kritisch */ });
    haWiedergabe(art === 0 ? 'start' : art === 2 ? 'stopp' : 'stand');
  }

  // Home Assistant erfährt, wer was schaut (Automation 84 → sensor.kino_jetzt): für den Verlauf im Dashboard,
  // den Hinweis bei Lukas, wenn jemand etwas startet, und das Abspann-Licht. Stand höchstens jede Minute,
  // Start, Pause/Weiter und Stopp sofort.
  let haLetzte = 0, haPause = null;

  function zweistellig(n) { n = parseInt(n, 10) || 0; return (n < 10 ? '0' : '') + n; }

  function haWiedergabe(art) {
    const e = P.eintrag, v = el.video;
    if (!e || !P.id) return;
    const jetzt = Date.now(), pause = !!v.paused;
    if (art === 'stand' && jetzt - haLetzte < 55000 && pause === haPause) return;
    haLetzte = jetzt;
    haPause = pause;
    const folge = typ(e) === 'Episode';
    let titel = e.Name || '';
    if (folge) titel = (e.SeriesName || '') + ' – S' + zweistellig(e.ParentIndexNumber) + 'E' + zweistellig(e.IndexNumber);
    else if (e.ProductionYear) titel += ' (' + e.ProductionYear + ')';
    const bildId = folge && e.SeriesId ? e.SeriesId : e.Id;
    const dauer = v.duration && isFinite(v.duration) ? v.duration : (e.RunTimeTicks || 0) / 10000000;
    const daten = {
      art: art, wer: PERSON.ha, id: 'jf:' + e.Id, titel: titel, typ: folge ? 'episode' : 'movie',
      serie: folge ? (e.SeriesName || '') : '', pos: Math.round(v.currentTime || 0), dauer: Math.round(dauer || 0),
      status: art === 'stopp' ? 'stopped' : pause ? 'paused' : 'playing',
      bild: SERVER + '/Items/' + bildId + '/Images/Primary?fillHeight=450&quality=90'
    };
    (async function () {
      // Filme mit derselben ID wie im Dashboard melden (alte Plex-ID), damit Verlauf, Bewertungen und Poster passen
      if (!folge) {
        try { await merk.plexLaden(); } catch (x) { /* dann jf:<ID> */ }
        daten.id = merk.plexIdFuer(e) || daten.id;
      }
      await merk.senden(daten);
    })().catch(function () { /* HA nicht erreichbar – nicht schlimm */ });
  }

  // ------------------------------------------------------------------ Film vom Dashboard starten
  // Skript 01g (script.kino_film_abspielen) legt in HA einen Auftrag für diesen Fernseher ab, kino_export schreibt ihn
  // nach /share/kino/auftrag.json: { auftraege: { Eltern|Jonas|Lukas: {id, titel, ts} } }. Die Oberfläche schaut alle
  // 4 Sekunden nach und spielt einen neuen Auftrag einmal ab (gemerkt über ts, auch über einen Neustart der App).

  let auftragZuletzt = null, auftragErster = true, auftragLaeuft = false;
  try { auftragZuletzt = localStorage.getItem('lk_auftrag'); } catch (e) { /* ohne Speicher */ }

  async function auftragPruefen() {
    if (auftragLaeuft || document.hidden || !jf.angemeldet() || ansicht === A.LADEN) return;
    auftragLaeuft = true;
    const erster = auftragErster;
    auftragErster = false;
    try {
      const r = await fetch('../auftrag.json?t=' + Date.now());
      if (!r.ok) return;
      const j = await r.json();
      liveNeu((j && j.laeuft) || {}, Number(j && j.jetzt) || Date.now() / 1000);
      const a = j && j.auftraege && j.auftraege[PERSON.ha];
      if (!a || !a.id || !a.ts) return;
      const kennung = String(a.ts) + '|' + a.id;
      if (kennung === auftragZuletzt) return;
      auftragZuletzt = kennung;
      try { localStorage.setItem('lk_auftrag', kennung); } catch (e) { /* egal */ }
      // Beim ersten Blick nach dem Start (App gerade vom Skript geöffnet) nur, wenn er frisch ist –
      // danach ist jeder neue Auftrag frisch, egal wie die Uhr des Fernsehers geht.
      if (erster && Math.abs(Date.now() / 1000 - Number(a.ts)) > 150) return;
      let id = String(a.id);
      if (id.indexOf('jf:') === 0) id = id.substring(3);
      const f = await jf.film(id);
      if (!f || !f.Id) return;
      if (T.offen) trailerSchliessen();
      if (P.offen) playerSchliessen();
      const lauf = f.RunTimeTicks || 0, pos = position(f);
      abspielen(f, pos > 0 && (!lauf || pos < lauf * 0.95));
    } catch (e) {
      /* kein Auftrag oder HA/Pi nicht erreichbar */
    } finally {
      auftragLaeuft = false;
    }
  }

  function playerSchliessen() {
    if (!P.offen) return;
    if (P.gemeldet && !P.gestoppt) melde(2);
    if (P.art === 'hls') encodingStoppen();
    P.startNr++;
    clearInterval(P.tick);
    clearInterval(P.meldeTick);
    clearTimeout(P.spulTimer);
    const f = P.eintrag;
    const v = el.video;
    const lauf = (f && f.RunTimeTicks ? f.RunTimeTicks / 10000000 : 0) || v.duration || 0;
    const quote = lauf > 0 ? Math.min(1, (v.currentTime || 0) / lauf) : 0;
    P.offen = false;
    P.menue = false;
    P.cues = null;
    try {
      v.pause();
      v.removeAttribute('src');
      v.load();
    } catch (e) { /* egal */ }
    el.untertitel.textContent = '';
    zeichnen();
    nachDemFilm(f, quote);
  }

  function leisteZeigen(ms) {
    P.leisteAn = true;
    P.bisWann = ms <= 0 ? Infinity : Date.now() + ms;
    playerLeisteZeichnen();
  }

  function playerTick() {
    if (!P.offen) return;
    const v = el.video;
    if (P.leisteAn && Date.now() > P.bisWann && !P.menue && P.spulZiel < 0 && !v.paused && !P.meldung) P.leisteAn = false;
    playerLeisteZeichnen();
  }

  function playerLeisteZeichnen() {
    const v = el.video;
    el.pLeiste.classList.toggle('an', P.leisteAn);
    el.player.classList.toggle('leiste-an', P.leisteAn);
    if (!P.leisteAn && !P.menue) return;
    const ges = v.duration && isFinite(v.duration) ? v.duration : 0;
    const pos = P.spulZiel >= 0 ? P.spulZiel : v.currentTime || 0;
    let puffer = 0;
    try {
      for (let i = 0; i < v.buffered.length; i++) {
        if (v.buffered.start(i) <= pos + 1 && v.buffered.end(i) >= pos) puffer = v.buffered.end(i);
      }
    } catch (e) { /* egal */ }
    const anteil = ges > 0 ? begrenzen(pos / ges, 0, 1) : 0;
    el.pPos.style.width = (anteil * 1700) + 'px';
    el.pPuffer.style.width = (ges > 0 ? begrenzen(puffer / ges, 0, 1) * 1700 : 0) + 'px';
    el.pKnubbel.style.left = (anteil * 1700) + 'px';
    el.pKnubbel.classList.toggle('gross', P.spulZiel >= 0);
    el.pZeit.textContent = zeit(pos);
    el.pRest.textContent = ges > 0 ? '−' + zeit(ges - pos) : '';
    if (P.spulZiel >= 0) {
      el.pBlase.textContent = zeit(P.spulZiel);
      el.pBlase.classList.remove('aus');
      const w = el.pBlase.offsetWidth || 160;
      el.pBlase.style.left = begrenzen(110 + anteil * 1700 - w / 2, 110, 1810 - w) + 'px';
    } else {
      el.pBlase.classList.add('aus');
    }
    el.pPause.classList.toggle('aus', !(v.paused && P.gemeldet && !P.menue && P.spulZiel < 0 && !P.meldung));
    el.pMeldung.classList.toggle('aus', !P.meldung);
    el.pMeldung.textContent = P.meldung || '';
    menueZeichnen();
  }

  function spulen(richtung) {
    const v = el.video;
    if (!v.duration || !isFinite(v.duration)) return;
    const jetzt = Date.now();
    if (jetzt - P.letzteSpul > 700) P.spulSchritte = 0;
    P.letzteSpul = jetzt;
    P.spulSchritte++;
    const schritt = P.spulSchritte < 4 ? 10 : P.spulSchritte < 10 ? 30 : P.spulSchritte < 20 ? 60 : 180;
    const basis = P.spulZiel >= 0 ? P.spulZiel : v.currentTime;
    P.spulZiel = begrenzen(basis + richtung * schritt, 0, Math.max(0, v.duration - 2));
    leisteZeigen(3000);
    clearTimeout(P.spulTimer);
    P.spulTimer = setTimeout(spulAusfuehren, 650);
  }

  function spulAusfuehren() {
    clearTimeout(P.spulTimer);
    if (P.spulZiel >= 0) {
      try { el.video.currentTime = P.spulZiel; } catch (e) { /* egal */ }
      setTimeout(function () { melde(1); }, 300);
    }
    P.spulZiel = -1;
    P.spulSchritte = 0;
    leisteZeigen(2500);
  }

  // -------------------------------------------------- Ton und Untertitel

  const SPRACHEN = {
    ger: 'Deutsch', deu: 'Deutsch', de: 'Deutsch', eng: 'Englisch', en: 'Englisch', fre: 'Französisch', fra: 'Französisch',
    fr: 'Französisch', spa: 'Spanisch', es: 'Spanisch', ita: 'Italienisch', it: 'Italienisch', jpn: 'Japanisch', ja: 'Japanisch',
    tur: 'Türkisch', tr: 'Türkisch', rus: 'Russisch', ru: 'Russisch', pol: 'Polnisch', pl: 'Polnisch', por: 'Portugiesisch',
    pt: 'Portugiesisch', nld: 'Niederländisch', dut: 'Niederländisch', nl: 'Niederländisch', swe: 'Schwedisch', dan: 'Dänisch',
    nor: 'Norwegisch', fin: 'Finnisch', kor: 'Koreanisch', chi: 'Chinesisch', zho: 'Chinesisch', hin: 'Hindi', ara: 'Arabisch',
    ces: 'Tschechisch', cze: 'Tschechisch', hun: 'Ungarisch', gre: 'Griechisch', ell: 'Griechisch', ukr: 'Ukrainisch',
    hrv: 'Kroatisch', srp: 'Serbisch', bos: 'Bosnisch', ron: 'Rumänisch', rum: 'Rumänisch', bul: 'Bulgarisch', tha: 'Thailändisch'
  };

  function istDeutsch(s) { const l = String(s.Language || '').toLowerCase(); return l === 'ger' || l === 'deu' || l === 'de'; }

  function spurName(s, audio) {
    const l = String(s.Language || '').toLowerCase();
    let name = SPRACHEN[l] || (l && l !== 'und' ? l.toUpperCase() : (audio ? 'Tonspur' : 'Untertitel'));
    const titel = s.Title || '';
    if (titel && titel.toLowerCase() !== name.toLowerCase() && titel.length < 40) name += ' · ' + titel;
    if (audio) {
      const c = String(s.Codec || '').toLowerCase();
      if (c === 'eac3') name += ' · Dolby Digital+';
      else if (c === 'ac3') name += ' · Dolby Digital';
      else if (c === 'truehd') name += ' · Dolby TrueHD';
      else if (c.indexOf('dts') === 0) name += ' · DTS';
      if (s.Channels >= 6) name += ' ' + (s.Channels === 8 ? '7.1' : '5.1');
    } else if (s.IsForced) {
      name += ' · erzwungen';
    }
    return name;
  }

  function textUntertitel(s) {
    if (s.IsTextSubtitleStream === true) return true;
    if (s.IsTextSubtitleStream === false) return false;
    return /^(srt|subrip|ass|ssa|webvtt|vtt|mov_text|text)$/i.test(s.Codec || '');
  }

  function tonUmschaltbar() {
    const at = el.video.audioTracks;
    return !!(at && at.length > 1);
  }

  function spurenListen() {
    const audio = P.streams.filter(function (s) { return s.Type === 'Audio'; });
    P.ton = audio.map(function (s) { return { name: spurName(s, true), stream: s }; });
    // aktive Spur: die Standardspur der Datei
    P.tonAktiv = -1;
    audio.forEach(function (s, j) { if (s.IsDefault && P.tonAktiv < 0) P.tonAktiv = j; });
    if (P.tonAktiv < 0 && audio.length) P.tonAktiv = 0;
    P.tonStandard = P.tonAktiv;
    const unter = P.streams.filter(function (s) { return s.Type === 'Subtitle' && textUntertitel(s); });
    P.unter = [{ name: 'Aus', stream: null }].concat(unter.map(function (s) { return { name: spurName(s, false), stream: s }; }));
  }

  function spurenStart() {
    const audio = P.streams.filter(function (s) { return s.Type === 'Audio'; });
    // gewünschte Spur (Deutsch bevorzugt, siehe tonWahl) – direkt nur, wenn der Browser umschalten kann
    const at = el.video.audioTracks;
    if (P.art === 'direkt' && at && at.length === audio.length && audio.length > 1) {
      for (let i = 0; i < at.length; i++) if (at[i].enabled) P.tonAktiv = i;
      if (P.tonWunsch >= 0 && P.tonWunsch !== P.tonAktiv) tonSetzen(P.tonWunsch);
    }
    // Untertitel: nur erzwungene deutsche (bzw. erzwungene in der Sprache der Tonspur) automatisch
    const unter = P.streams.filter(function (s) { return s.Type === 'Subtitle' && textUntertitel(s); });
    const tonSprache = String(((P.ton[P.tonAktiv] || {}).stream || {}).Language || 'ger').toLowerCase();
    let auto = null;
    unter.forEach(function (s) {
      if (auto || !s.IsForced) return;
      const l = String(s.Language || '').toLowerCase();
      if (istDeutsch(s) || l === tonSprache) auto = s;
    });
    if (auto) untertitelLaden(auto);
  }

  function tonSetzen(j) {
    const at = el.video.audioTracks;
    if (!at || at.length <= j) return false;
    for (let i = 0; i < at.length; i++) at[i].enabled = i === j;
    P.tonAktiv = j;
    return true;
  }

  async function untertitelLaden(s) {
    if (!s) {
      P.cues = null;
      P.uAktiv = -1;
      el.untertitel.textContent = '';
      melde(1);
      return;
    }
    const id = P.id;
    P.uAktiv = s.Index;
    try {
      const t = await jf.anfrage('GET', '/Videos/' + P.id + '/' + P.quelle + '/Subtitles/' + s.Index + '/0/Stream.vtt', undefined, true);
      if (P.id !== id || P.uAktiv !== s.Index) return;
      P.cues = vttLesen(t);
      melde(1);
    } catch (e) {
      if (P.id === id) {
        P.meldung = 'Untertitel ließen sich nicht laden';
        leisteZeigen(2500);
        setTimeout(function () { P.meldung = null; }, 2500);
      }
    }
  }

  function zeitWert(s) {
    const t = s.replace(',', '.').split(':').map(parseFloat);
    return t.length === 3 ? t[0] * 3600 + t[1] * 60 + t[2] : t.length === 2 ? t[0] * 60 + t[1] : t[0];
  }

  function vttLesen(text) {
    const cues = [];
    String(text).replace(/\r/g, '').split(/\n\s*\n/).forEach(function (b) {
      const z = b.split('\n');
      let i = -1;
      for (let k = 0; k < z.length; k++) if (z[k].indexOf('-->') >= 0) { i = k; break; }
      if (i < 0) return;
      const m = z[i].match(/([\d:.,]+)\s*-->\s*([\d:.,]+)/);
      if (!m) return;
      const t = z.slice(i + 1).join('\n').replace(/<[^>]+>/g, '').replace(/&amp;/g, '&').replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>').replace(/&nbsp;/g, ' ').trim();
      if (t) cues.push({ a: zeitWert(m[1]), b: zeitWert(m[2]), t: t });
    });
    cues.sort(function (x, y) { return x.a - y.a; });
    return cues;
  }

  let untertitelText = '';

  function untertitelSchleife() {
    if (!P.offen) return;
    let t = '';
    if (P.cues) {
      const ct = el.video.currentTime;
      const teile = [];
      for (let i = 0; i < P.cues.length; i++) {
        const c = P.cues[i];
        if (c.a > ct) break;
        if (ct < c.b) teile.push(c.t);
      }
      t = teile.join('\n');
    }
    if (t !== untertitelText) {
      untertitelText = t;
      el.untertitel.textContent = t;
    }
    requestAnimationFrame(untertitelSchleife);
  }

  function menueAuf() {
    P.menue = true;
    P.spalte = 0;
    P.zeile = Math.max(0, P.tonAktiv);
    leisteZeigen(0);
  }

  function menueZeichnen() {
    const m = el.pMenue;
    m.classList.toggle('aus', !P.menue);
    if (!P.menue) return;
    const n = Math.max(P.ton.length, P.unter.length, 1);
    const sichtbar = Math.min(n, 9);
    m.style.height = (150 + sichtbar * 64 + 40) + 'px';
    m.innerHTML = '';
    [P.ton, P.unter].forEach(function (liste, sp) {
      const s = div('p-spalte' + (sp === P.spalte ? ' aktiv' : ''));
      s.style.left = (sp * 330) + 'px';
      const h = document.createElement('h3');
      h.textContent = sp === 0 ? 'Ton' : 'Untertitel';
      s.appendChild(h);
      const start = sp === P.spalte ? Math.max(0, Math.min(P.zeile - 4, liste.length - 9)) : 0;
      if (!liste.length) {
        const e = div('p-eintrag', sp === 0 ? 'Standard' : 'Keine');
        e.style.top = '100px';
        s.appendChild(e);
      }
      for (let i = start; i < Math.min(liste.length, start + 9); i++) {
        const x = liste[i];
        const an = sp === 0 ? i === P.tonAktiv : (x.stream ? x.stream.Index === P.uAktiv : P.uAktiv < 0);
        const e = div('p-eintrag' + (an ? ' an' : '') + (sp === P.spalte && i === P.zeile ? ' fokus' : ''),
          (an ? '✓ ' : '    ') + x.name);
        e.style.top = (100 + (i - start) * 64) + 'px';
        s.appendChild(e);
      }
      m.appendChild(s);
    });
    if (P.ton.length > 1 && !tonUmschaltbar()) {
      m.appendChild(div('p-hinweis', 'Andere Tonspuren laufen über den Pi – Start dauert ein paar Sekunden.'));
    }
  }

  function menueTaste(k) {
    const liste = P.spalte === 0 ? P.ton : P.unter;
    if (k === TASTE.HOCH) P.zeile = Math.max(0, P.zeile - 1);
    else if (k === TASTE.RUNTER) P.zeile = Math.min(Math.max(0, liste.length - 1), P.zeile + 1);
    else if (k === TASTE.LINKS || k === TASTE.RECHTS) {
      P.spalte = 1 - P.spalte;
      const l2 = P.spalte === 0 ? P.ton : P.unter;
      P.zeile = 0;
      l2.forEach(function (x, i) {
        const an = P.spalte === 0 ? i === P.tonAktiv : (x.stream ? x.stream.Index === P.uAktiv : P.uAktiv < 0);
        if (an) P.zeile = i;
      });
    } else if (k === TASTE.OK) {
      const x = liste[P.zeile];
      if (x) {
        if (P.spalte === 0) {
          tonWechseln(P.zeile);
        } else {
          untertitelLaden(x.stream);
        }
      }
      P.menue = false;
      leisteZeigen(2500);
      return true;
    } else if (k === TASTE.ZURUECK) {
      P.menue = false;
      leisteZeigen(1500);
      return true;
    } else {
      return false;
    }
    playerLeisteZeichnen();
    return true;
  }

  // ------------------------------------------------------------------ Trailer-Player (YouTube im eigenen Vollbild)

  const T = { offen: false, player: null, tick: 0, bis: 0, an: false, meldung: null, zeitZiel: -1 };
  let ytBereit = null;

  function ytLaden() {
    if (ytBereit) return ytBereit;
    ytBereit = new Promise(function (ok, nein) {
      if (window.YT && window.YT.Player) {
        ok();
        return;
      }
      const vorher = window.onYouTubeIframeAPIReady;
      window.onYouTubeIframeAPIReady = function () {
        if (vorher) try { vorher(); } catch (e) { /* egal */ }
        ok();
      };
      const sc = document.createElement('script');
      sc.src = 'https://www.youtube.com/iframe_api';
      sc.onerror = function () {
        ytBereit = null;
        nein(new Error('YouTube nicht erreichbar'));
      };
      document.head.appendChild(sc);
      setTimeout(function () { nein(new Error('YouTube antwortet nicht')); }, 15000);
    });
    return ytBereit;
  }

  async function trailerAbspielen(tr, titel) {
    T.offen = true;
    T.meldung = 'Trailer wird geladen …';
    T.zeitZiel = -1;
    T.utZ = 0;
    el.tTitel.textContent = titel;
    el.tBox.innerHTML = '';
    trailerLeiste(0);
    zeichnen();
    try {
      await ytLaden();
    } catch (e) {
      T.meldung = 'YouTube ist gerade nicht erreichbar';
      trailerLeiste(0);
      setTimeout(trailerSchliessen, 3000);
      return;
    }
    if (!T.offen) return;
    const ziel = document.createElement('div');
    ziel.id = 'yt-ziel';
    el.tBox.appendChild(ziel);
    T.player = new window.YT.Player('yt-ziel', {
      width: 1920,
      height: 1080,
      videoId: tr.yt,
      playerVars: {
        autoplay: 1, controls: 0, disablekb: 1, fs: 0, rel: 0, iv_load_policy: 3, modestbranding: 1,
        playsinline: 1, hl: 'de', cc_load_policy: 0, cc_lang_pref: 'xx', origin: location.origin
      },
      events: {
        onApiChange: function () { trailerOhneUntertitel(); },
        onReady: function (ev) {
          trailerOhneUntertitel();
          try {
            const f = ev.target.getIframe();
            f.setAttribute('tabindex', '-1');
            f.style.pointerEvents = 'none';
          } catch (e) { /* egal */ }
          ev.target.playVideo();
          T.meldung = null;
          trailerLeiste(3000);
          window.focus();
        },
        onStateChange: function (ev) {
          trailerOhneUntertitel();
          if (ev.data === 0) trailerSchliessen();           // zu Ende
          else if (ev.data === 1) {
            T.meldung = null;
            trailerLeiste(2500);
          } else if (ev.data === 2) trailerLeiste(0);       // Pause
          window.focus();
        },
        onError: function () {
          T.meldung = 'Dieser Trailer lässt sich hier leider nicht abspielen';
          trailerLeiste(0);
          setTimeout(trailerSchliessen, 3500);
        }
      }
    });
    clearInterval(T.tick);
    T.tick = setInterval(trailerTick, 250);
  }

  // Untertitel immer aus (Wunsch Lukas 03.11.2026): cc_load_policy allein reicht nicht, YouTube blendet bei
  // fremdsprachigen Trailern sonst automatisch Untertitel ein – darum das Untertitel-Modul jedes Mal entladen.
  function trailerOhneUntertitel() {
    try {
      if (!T.player || !T.player.unloadModule) return;
      T.player.unloadModule('captions');
      T.player.unloadModule('cc');
      if (T.player.setOption) T.player.setOption('captions', 'track', {});
    } catch (e) { /* egal */ }
  }

  function trailerSchliessen() {
    if (!T.offen) return;
    T.offen = false;
    clearInterval(T.tick);
    try { if (T.player) T.player.destroy(); } catch (e) { /* egal */ }
    T.player = null;
    el.tBox.innerHTML = '';
    zeichnen();
    window.focus();
  }

  function trailerLeiste(ms) {
    T.an = true;
    T.bis = ms <= 0 ? Infinity : Date.now() + ms;
    trailerZeichnen();
  }

  function trailerZustand() {
    try { return T.player && T.player.getPlayerState ? T.player.getPlayerState() : -1; } catch (e) { return -1; }
  }

  function trailerTick() {
    if (!T.offen) return;
    T.utZ = (T.utZ || 0) + 1;
    if (T.utZ <= 40 && T.utZ % 4 === 0) trailerOhneUntertitel();   // die ersten 10 Sekunden immer wieder
    if (T.an && Date.now() > T.bis && trailerZustand() === 1 && !T.meldung) T.an = false;
    trailerZeichnen();
  }

  function trailerZeichnen() {
    el.tLeiste.classList.toggle('an', T.an);
    let pos = 0, ges = 0;
    try {
      if (T.player && T.player.getDuration) {
        ges = T.player.getDuration() || 0;
        pos = T.zeitZiel >= 0 ? T.zeitZiel : T.player.getCurrentTime() || 0;
      }
    } catch (e) { /* noch nicht bereit */ }
    const anteil = ges > 0 ? begrenzen(pos / ges, 0, 1) : 0;
    el.tPos.style.width = (anteil * 1700) + 'px';
    el.tKnubbel.style.left = (anteil * 1700) + 'px';
    el.tZeit.textContent = ges > 0 ? zeit(pos) : '';
    el.tRest.textContent = ges > 0 ? '−' + zeit(ges - pos) : '';
    el.tPause.classList.toggle('aus', !(trailerZustand() === 2 && !T.meldung));
    el.tMeldung.classList.toggle('aus', !T.meldung);
    el.tMeldung.textContent = T.meldung || '';
  }

  function trailerTaste(k) {
    const p = T.player;
    if (k === TASTE.ZURUECK || k === MEDIEN.STOP) {
      trailerSchliessen();
      return;
    }
    if (!p || !p.getPlayerState) return;
    try {
      if (k === TASTE.OK || k === MEDIEN.PLAYPAUSE) {
        if (p.getPlayerState() === 1) p.pauseVideo();
        else p.playVideo();
      } else if (k === MEDIEN.PLAY) p.playVideo();
      else if (k === MEDIEN.PAUSE) p.pauseVideo();
      else if (k === TASTE.LINKS || k === TASTE.RECHTS || k === MEDIEN.RUECK || k === MEDIEN.VOR) {
        const r = k === TASTE.LINKS || k === MEDIEN.RUECK ? -10 : 10;
        const ges = p.getDuration() || 0;
        const ziel = begrenzen((T.zeitZiel >= 0 ? T.zeitZiel : p.getCurrentTime()) + r, 0, Math.max(0, ges - 1));
        T.zeitZiel = ziel;
        p.seekTo(ziel, true);
        setTimeout(function () { T.zeitZiel = -1; }, 800);
      }
    } catch (e) { /* Player noch nicht bereit */ }
    trailerLeiste(3000);
  }

  // ------------------------------------------------------------------ kurzer Hinweis unten

  let hinweisTimer = 0;

  function hinweis(text) {
    el.hinweis.textContent = text;
    el.hinweis.classList.remove('aus');
    clearTimeout(hinweisTimer);
    hinweisTimer = setTimeout(function () { el.hinweis.classList.add('aus'); }, 2200);
  }

  // ------------------------------------------------------------------ Tasten

  const TASTE = { LINKS: 37, HOCH: 38, RECHTS: 39, RUNTER: 40, OK: 13, ZURUECK: 461 };
  const MEDIEN = { PLAY: 415, PAUSE: 19, STOP: 413, VOR: 417, RUECK: 412, PLAYPAUSE: 179 };
  let letzteTaste = 0, letzteZeit = 0, wiederholung = 0, letztesOk = 0;

  function beenden() {
    if (window.top !== window) {
      try { window.top.postMessage({ type: 'AppHost.exit' }, '*'); } catch (e) { /* egal */ }
    }
  }

  document.addEventListener('keydown', function (ev) {
    let k = ev.keyCode;
    const jetzt = Date.now();
    wiederholung = (k === letzteTaste && (ev.repeat || jetzt - letzteZeit < 180)) ? wiederholung + 1 : 0;
    letzteTaste = k;
    letzteZeit = jetzt;

    // Tastatur (PC, Handy-App) tippt direkt in die Suche
    if (ansicht === A.SUCHE && !leiste && !P.offen) {
      if (k === 8) {
        loeschen();
        ev.preventDefault();
        return;
      }
      const z = ev.key;
      if (z && z.length === 1 && /[a-z0-9äöüß \-.]/i.test(z) && !ev.ctrlKey && !ev.metaKey && !ev.altKey) {
        tippen(z.toLowerCase());
        ev.preventDefault();
        return;
      }
    }
    if (k === 27 || k === 8 || k === 10009) k = TASTE.ZURUECK;
    if (k === 32 && !(ansicht === A.SUCHE && !leiste)) k = TASTE.OK;
    if (k === TASTE.OK) letztesOk = jetzt;

    const bekannt = [TASTE.LINKS, TASTE.HOCH, TASTE.RECHTS, TASTE.RUNTER, TASTE.OK, TASTE.ZURUECK,
      MEDIEN.PLAY, MEDIEN.PAUSE, MEDIEN.STOP, MEDIEN.VOR, MEDIEN.RUECK, MEDIEN.PLAYPAUSE].indexOf(k) >= 0;
    if (!bekannt) return;
    ev.preventDefault();
    ev.stopPropagation();

    if (window.LukasMusik && window.LukasMusik.offen()) return musikTaste(k);
    if (T.offen) return trailerTaste(k);
    if (P.offen) return playerTaste(k);
    if (B.offen) return bewertenTaste(k);
    if (leiste) return leisteTaste(k);
    switch (ansicht) {
      case A.ANMELDEN:
      case A.LADEN:
        if (k === TASTE.ZURUECK) beenden();
        return;
      case A.FEHLER:
        if (k === TASTE.ZURUECK) beenden();
        else if (k === TASTE.OK) {
          if (jf.angemeldet()) laden();
          else anmelden();
        }
        return;
      case A.DETAIL: return detailTaste(k);
      case A.SUCHE: return sucheTaste(k);
      case A.FOTO: return fotoTaste(k);
      default: return listeTaste(k);
    }
  }, true);

  function leisteTaste(k) {
    if (k === TASTE.ZURUECK || k === TASTE.RECHTS) leisteZu();
    else if (k === TASTE.HOCH) leistePos = Math.max(0, leistePos - 1);
    else if (k === TASTE.RUNTER) leistePos = Math.min(LEISTE.length - 1, leistePos + 1);
    else if (k === TASTE.OK) leisteWaehlen(leistePos);
    zeichnen();
  }

  function listeTaste(k) {
    const r = aktuelle();
    if (k === TASTE.ZURUECK) {
      if (stapel.length) {
        seite = stapel.pop();
        fokusGeaendert();
      } else if (seite.akt > 0 || (r && r.fokus > 0)) {
        if (r) r.fokus = 0;
        seite.akt = 0;
        const erste = aktuelle();
        if (erste) erste.fokus = 0;
        fokusGeaendert();
      } else if (bereich !== 'filme') {
        bereichOeffnen('filme');
      } else {
        beenden();
      }
      return;
    }
    if (!r) {
      if (k === TASTE.LINKS) leisteAuf();
      return;
    }
    if (seite.raster) return wandTaste(k, r);
    switch (k) {
      case TASTE.LINKS:
        if (r.fokus > 0) {
          r.fokus -= wiederholung > 6 ? Math.min(r.fokus, 3) : 1;
          fokusGeaendert();
        } else if (wiederholung === 0) {
          leisteAuf();
        }
        return;
      case TASTE.RECHTS:
        if (r.fokus < r.eintraege.length - 1) {
          r.fokus = Math.min(r.eintraege.length - 1, r.fokus + (wiederholung > 6 ? 3 : 1));
          fokusGeaendert();
        }
        return;
      case TASTE.HOCH:
        if (seite.akt > 0) {
          seite.akt--;
          nachladen();
          fokusGeaendert();
        }
        return;
      case TASTE.RUNTER:
        if (seite.akt < seite.reihen.length - 1) {
          seite.akt++;
          nachladen();
          fokusGeaendert();
        }
        return;
      case MEDIEN.PLAY:
      case MEDIEN.PLAYPAUSE: {
        const f = fokusEintrag();
        if (abspielbar(f)) abspielen(f, true);
        return;
      }
      case TASTE.OK:
        oeffnen(fokusEintrag(), A.LISTE);
        return;
      default:
    }
  }

  function detailTaste(k) {
    const f = detailEintrag;
    if (k === TASTE.ZURUECK) {
      ansicht = detailZurueck;
      detailEintrag = null;
      detailTrailer = null;
      fokusGeaendert();
      return;
    }
    if (k === TASTE.LINKS) detailKnopf = Math.max(0, detailKnopf - 1);
    else if (k === TASTE.RECHTS) detailKnopf = Math.min(detailKnoepfe.length - 1, detailKnopf + 1);
    else if (k === TASTE.OK) {
      knopfAusfuehren(detailKnoepfe[detailKnopf]);
      return;
    } else if ((k === MEDIEN.PLAY || k === MEDIEN.PLAYPAUSE) && f && abspielbar(f)) {
      abspielen(f, true);
      return;
    }
    zeichnen();
  }

  function sucheTaste(k) {
    if (k === TASTE.ZURUECK) {
      ansicht = sucheZurueck;
      fokusGeaendert();
      return;
    }
    if (inTreffern) {
      const sp = trefferFokus % SUCH_SPALTEN;
      if (k === TASTE.LINKS) {
        if (sp === 0) {
          inTreffern = false;
          tasteSpalte = 5;
        } else trefferFokus--;
      } else if (k === TASTE.RECHTS) {
        if (sp < SUCH_SPALTEN - 1 && trefferFokus < treffer.length - 1) trefferFokus++;
      } else if (k === TASTE.HOCH) {
        if (trefferFokus >= SUCH_SPALTEN) trefferFokus -= SUCH_SPALTEN;
      } else if (k === TASTE.RUNTER) {
        if (trefferFokus + SUCH_SPALTEN < treffer.length) trefferFokus += SUCH_SPALTEN;
        else if (Math.floor(trefferFokus / SUCH_SPALTEN) < Math.floor((treffer.length - 1) / SUCH_SPALTEN)) trefferFokus = treffer.length - 1;
      } else if (k === TASTE.OK || k === MEDIEN.PLAY || k === MEDIEN.PLAYPAUSE) {
        const e = treffer[trefferFokus];
        if (e) {
          if (k !== TASTE.OK && abspielbar(e)) abspielen(e, true);
          else oeffnen(e, A.SUCHE);
        }
        return;
      }
      fokusGeaendert();
      return;
    }
    const zeilen = TASTEN.length + 1;
    if (k === TASTE.LINKS) {
      if (tasteZeile === 0) {
        if (tasteSpalte >= 3) tasteSpalte = 0;
        else leisteAuf();
      } else if (tasteSpalte > 0) tasteSpalte--;
      else leisteAuf();
    } else if (k === TASTE.RECHTS) {
      if (tasteZeile === 0 && tasteSpalte < 3) tasteSpalte = 3;
      else if (tasteZeile > 0 && tasteSpalte < 5) tasteSpalte++;
      else if (treffer.length) {
        inTreffern = true;
        const zeile = Math.floor(Math.max(0, tasteZeile - 1) / 3);
        trefferFokus = Math.min(treffer.length - 1, zeile * SUCH_SPALTEN);
        fokusGeaendert();
        return;
      }
    } else if (k === TASTE.HOCH) {
      if (tasteZeile > 0) {
        tasteZeile--;
        if (tasteZeile === 0) tasteSpalte = tasteSpalte >= 3 ? 3 : 0;
      }
    } else if (k === TASTE.RUNTER) {
      if (tasteZeile < zeilen - 1) {
        if (tasteZeile === 0) tasteSpalte = tasteSpalte >= 3 ? 3 : 0;
        tasteZeile++;
      }
    } else if (k === TASTE.OK) {
      if (tasteZeile === 0) {
        if (tasteSpalte < 3) tippen(' ');
        else loeschen();
      } else {
        tippen(TASTEN[tasteZeile - 1][tasteSpalte]);
      }
      return;
    }
    zeichnen();
  }

  function fotoTaste(k) {
    if (k === TASTE.ZURUECK) {
      fotoSchliessen();
      return;
    }
    if (k === TASTE.LINKS) {
      if (fotoIdx > 0) {
        fotoIdx--;
        fotoLaden();
      }
    } else if (k === TASTE.RECHTS) {
      if (fotoIdx < fotos.length - 1) {
        fotoIdx++;
        fotoLaden();
      }
    } else if (k === TASTE.HOCH || k === TASTE.RUNTER) {
      fotoInfoAn = !fotoInfoAn;
      clearTimeout(fotoInfoTimer);
    } else if (fotos.length) {
      const e = fotos[fotoIdx];
      if (typ(e) === 'Video') {
        abspielen(e, false);
        return;
      }
      if (k === TASTE.OK || k === MEDIEN.PLAY || k === MEDIEN.PLAYPAUSE) {
        diashow = !diashow;
        clearTimeout(diaTimer);
        if (diashow) diaTimer = setTimeout(diaWeiter, 5000);
        fotoInfoZeigen(1800);
      }
    }
    zeichnen();
  }

  function playerTaste(k) {
    const v = el.video;
    if (P.menue && menueTaste(k)) return;
    switch (k) {
      case TASTE.OK:
      case MEDIEN.PLAYPAUSE:
        if (P.spulZiel >= 0) {
          spulAusfuehren();
          return;
        }
        if (P.meldung && !v.getAttribute('src')) return;
        if (v.paused) v.play().catch(function () {});
        else v.pause();
        return;
      case MEDIEN.PLAY:
        v.play().catch(function () {});
        return;
      case MEDIEN.PAUSE:
        v.pause();
        return;
      case TASTE.LINKS:
      case MEDIEN.RUECK:
        spulen(-1);
        return;
      case TASTE.RECHTS:
      case MEDIEN.VOR:
        spulen(1);
        return;
      case TASTE.HOCH:
      case TASTE.RUNTER:
        menueAuf();
        return;
      case TASTE.ZURUECK:
        if (P.spulZiel >= 0) {
          clearTimeout(P.spulTimer);
          P.spulZiel = -1;
          P.spulSchritte = 0;
          playerLeisteZeichnen();
          return;
        }
        playerSchliessen();
        return;
      case MEDIEN.STOP:
        playerSchliessen();
        return;
      default:
    }
  }

  // ------------------------------------------------------------------ Magic-Remote-Zeiger und Maus

  function buehnenPunkt(ev) {
    return { x: (ev.clientX - el.versatzX) / el.skala, y: (ev.clientY - el.versatzY) / el.skala };
  }

  document.addEventListener('click', function (ev) {
    if (Date.now() - letztesOk < 450) return; // OK-Taste der Fernbedienung löst manchmal zusätzlich einen Klick aus
    window.focus();
    let t = ev.target;
    while (t && t !== document.body && !(t._reihe || t._treffer != null || t._taste || t._leiste != null || t._knopf != null)) {
      t = t.parentNode;
    }
    if (T.offen) {
      trailerTaste(TASTE.OK);
      return;
    }
    if (P.offen) {
      if (P.menue) return;
      const v = el.video;
      if (v.paused) v.play().catch(function () {});
      else v.pause();
      return;
    }
    if (B.offen) {
      if (B.danke) bewertenSchliessen();
      return;
    }
    if (!t || t === document.body) {
      if (ansicht === A.FEHLER) {
        if (jf.angemeldet()) laden();
        else anmelden();
      } else if (ansicht === A.FOTO) {
        fotoTaste(TASTE.OK);
      } else if (leiste) {
        leisteZu();
      }
      return;
    }
    if (t._leiste != null) {
      leisteWaehlen(t._leiste);
      return;
    }
    if (t._knopf != null && ansicht === A.DETAIL) {
      detailKnopf = t._knopf;
      knopfAusfuehren(detailKnoepfe[detailKnopf]);
      return;
    }
    if (t._taste && ansicht === A.SUCHE) {
      inTreffern = false;
      tasteZeile = t._taste[0];
      tasteSpalte = t._taste[1];
      sucheTaste(TASTE.OK);
      return;
    }
    if (t._treffer != null && ansicht === A.SUCHE) {
      inTreffern = true;
      trefferFokus = t._treffer;
      oeffnen(treffer[trefferFokus], A.SUCHE);
      return;
    }
    if (t._reihe && (ansicht === A.LISTE)) {
      const i = seite.reihen.indexOf(t._reihe);
      if (i < 0) return;
      seite.akt = i;
      t._reihe.fokus = t._index;
      nachladen();
      oeffnen(t._reihe.eintraege[t._index], A.LISTE);
    } else if (ansicht === A.DETAIL) {
      detailTaste(TASTE.ZURUECK);
    }
  });

  document.addEventListener('mousemove', function (ev) {
    if (T.offen) {
      trailerLeiste(2500);
      return;
    }
    if (P.offen) {
      leisteZeigen(2500);
      return;
    }
    if (ansicht !== A.LISTE && ansicht !== A.SUCHE) return;
    const p = buehnenPunkt(ev);
    if (!leiste && p.x < 40) leisteAuf(true);
    else if (leiste && leisteMaus && p.x > 520) leisteZu();
    else if (leiste && p.x < 460) {
      let i = -1, best = 1e9;
      for (let k = 0; k < LEISTE.length; k++) {
        const d = Math.abs(p.y - (40 + leisteTop(k) + 40));
        if (d < best) { best = d; i = k; }
      }
      if (best > 60) i = -1;
      if (i >= 0 && i < LEISTE.length && i !== leistePos) {
        leistePos = i;
        zeichnen();
      }
    }
  });

  let radZeit = 0;
  document.addEventListener('wheel', function (ev) {
    const jetzt = Date.now();
    if (jetzt - radZeit < 220 || P.offen || leiste) return;
    radZeit = jetzt;
    if (ansicht === A.LISTE) listeTaste(ev.deltaY > 0 ? TASTE.RUNTER : TASTE.HOCH);
    else if (ansicht === A.FOTO) fotoTaste(ev.deltaY > 0 ? TASTE.RECHTS : TASTE.LINKS);
  }, { passive: true });

  // App in den Hintergrund (andere App, Fernseher aus): Film anhalten und melden
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) auftragErster = true; // nach dem Zurückkommen nur frische Aufträge
    else setTimeout(auftragPruefen, 300);
    if (document.hidden && T.offen) trailerSchliessen();
    if (document.hidden && P.offen) {
      el.video.pause();
      melde(1);
    }
  });
  window.addEventListener('pagehide', function () { if (P.offen && P.gemeldet) melde(2); });

  // ------------------------------------------------------------------ Eigenes Jellyfin-Konto je Fernseher
  // (04.11.2026, Konto B) Eltern- und Jonas-Fernseher nutzen ihr eigenes Jellyfin-Konto (Eltern bzw. Jonas,
  // ohne Passwort), damit Weiterschauen und Favoriten zu ihren Handys (Moonfin) passen. Ist ein Fernseher noch
  // als jemand anderes angemeldet, meldet er sich einmal um. Klappt das nicht, bleibt alles wie vorher.
  async function kontoPruefen() {
    if (PERSON.schluessel === 'lukas') return;
    const soll = String(PERSON.name).toLowerCase();
    let alt = null;
    if (jf.angemeldet()) {
      try {
        const ich = await jf.anfrage('GET', '/Users/Me');
        if (String((ich || {}).Name || '').toLowerCase() === soll) return;
        alt = { token: jf.token, benutzer: jf.benutzer };
      } catch (e) {
        if (jf.angemeldet()) return;   // Netz- oder Serverfehler: nichts anfassen
      }
    }
    const merkToken = jf.token, merkBenutzer = jf.benutzer;
    jf.token = null;
    try {
      const a = await jf.anfrage('POST', '/Users/AuthenticateByName', { Username: PERSON.name, Pw: '' });
      if (a) {
        if (a.AccessToken) {
          if (a.User) {
            jf.token = a.AccessToken;
            jf.benutzer = a.User.Id;
            speicherSetzen('lk_token', jf.token);
            speicherSetzen('lk_user', jf.benutzer);
            return;
          }
        }
      }
    } catch (e) { /* kein passendes Konto ohne Passwort: alter Stand bleibt */ }
    if (alt) {
      jf.token = alt.token;
      jf.benutzer = alt.benutzer;
    } else {
      jf.token = merkToken;
      jf.benutzer = merkBenutzer;
    }
  }

  // ------------------------------------------------------------------ Start-Intro (seit 04.11.2026, wie am TCL)
  // Das private Intro (SOURCE, mit Fotos) liegt nur auf dem Pi unter /share/kino/intro.mp4, nicht im Repo.
  // Es läuft beim Start im Vollbild über der Oberfläche, die dahinter schon lädt, und blendet am Ende weich aus.
  // Jede Taste (außer Lautstärke) und jeder Klick überspringt. Fehlt die Datei oder geht Abspielen nicht: ohne Intro.
  // Lädt die Seite innerhalb einer Minute neu (z. B. Kontowechsel), kommt es nicht noch einmal.

  const intro = { el: null, video: null, weg: true };
  const LAUTSTAERKE = [173, 174, 175, 447, 448, 449];

  function introZeigen() {
    try {
      const vorher = Number(sessionStorage.getItem('lk_intro') || 0);
      if (Date.now() - vorher < 60000) return;
      sessionStorage.setItem('lk_intro', String(Date.now()));
    } catch (e) { /* ohne Speicher eben immer */ }
    const d = document.createElement('div');
    d.id = 'intro';
    const v = document.createElement('video');
    v.src = '../intro.mp4';
    v.preload = 'auto';
    v.setAttribute('playsinline', '');
    d.appendChild(v);
    document.body.appendChild(d);
    intro.el = d;
    intro.video = v;
    intro.weg = false;
    v.addEventListener('ended', introAusblenden);
    v.addEventListener('error', introAusblenden);
    d.addEventListener('click', introAusblenden);
    setTimeout(introAusblenden, 25000); // Notbremse, falls das Video hängt
    const p = v.play();
    if (p && p.catch) {
      p.catch(function () {
        // Ton ohne Tastendruck nicht erlaubt: dann stumm, sonst ganz ohne Intro
        v.muted = true;
        const q = v.play();
        if (q && q.catch) q.catch(introAusblenden);
      });
    }
  }

  function introAusblenden() {
    if (intro.weg) return;
    intro.weg = true;
    const d = intro.el, v = intro.video;
    d.style.opacity = '0';
    setTimeout(function () {
      try { v.pause(); v.removeAttribute('src'); v.load(); } catch (e) { /* egal */ }
      if (d.parentNode) d.parentNode.removeChild(d);
    }, 380);
  }

  // Läuft das Intro, schluckt jede Taste (außer Lautstärke) das Überspringen, bevor die Oberfläche sie sieht.
  document.addEventListener('keydown', function (ev) {
    if (intro.weg || LAUTSTAERKE.indexOf(ev.keyCode) >= 0) return;
    introAusblenden();
    ev.preventDefault();
    ev.stopImmediatePropagation();
  }, true);

  // ------------------------------------------------------------------ Start

  function start() {
    bauen();
    skalieren();
    introZeigen();
    window.addEventListener('resize', skalieren);
    try {
      document.body.tabIndex = -1;
      document.body.focus();
      window.focus();
    } catch (e) { /* egal */ }
    if (parameter.abmelden === '1') jf.abmelden();
    const weiter = function () {
      if (jf.angemeldet()) laden();
      else anmelden();
    };
    kontoPruefen().then(weiter, weiter);
  }

  if (document.body) start();
  else document.addEventListener('DOMContentLoaded', start);
})();
