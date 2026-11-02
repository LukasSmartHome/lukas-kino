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

  const LEISTE = ['Suche', 'Filme', 'Serien', 'Merkliste', 'Fotos'];
  const BEREICHE = ['filme', 'serien', 'merkliste', 'fotos'];
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

  const SYMBOLE = [
    '<circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.2-4.2"/>',
    '<rect x="3" y="5" width="18" height="14" rx="3"/><path d="M10 9l5 3-5 3z"/>',
    '<rect x="3" y="7" width="18" height="11" rx="2.5"/><path d="M8.5 3L12 7l3.5-4M8 21h8"/>',
    '<path d="M6.5 3.5h11a1 1 0 0 1 1 1v16l-6.5-4.2-6.5 4.2v-16a1 1 0 0 1 1-1z"/>',
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
    let n = parameter.name || (window.AppInfo && window.AppInfo.deviceName) || (istWebos ? 'LG Fernseher' : 'Browser');
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
    }
  };

  // Bilder (Jellyfin liefert sie ohne Anmeldung)
  function mitTag(tag) { return tag ? '&tag=' + tag : ''; }
  function posterUrl(id, tag, h) { return SERVER + '/Items/' + id + '/Images/Primary?fillHeight=' + h + '&quality=90' + mitTag(tag); }
  function hintergrundBild(id, tag) { return SERVER + '/Items/' + id + '/Images/Backdrop/0?fillWidth=1920&quality=85' + mitTag(tag); }
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

  // ------------------------------------------------------------------ Merkliste (je Gerät)
  // Gespeichert in Jellyfin (DisplayPreferences des Benutzers, je Gerät eine eigene Liste):
  // übersteht App-Updates und ist am Eltern-Fernseher eine andere Liste als an Lukas' TCL.

  const merk = {
    ids: [],
    items: {},
    dto: null,

    client: function () {
      const d = window.DeviceInfo || {};
      const name = parameter.liste || d.modelName || d.model || geraetId;
      return 'lukaskino-' + String(name).replace(/[^A-Za-z0-9_-]/g, '');
    },

    pfad: function () {
      return '/DisplayPreferences/lukaskino-merkliste?userId=' + jf.benutzer + '&client=' + encodeURIComponent(this.client());
    },

    laden: async function () {
      try { this.dto = await jf.anfrage('GET', this.pfad()); } catch (e) { this.dto = null; }
      const roh = this.dto && this.dto.CustomPrefs && this.dto.CustomPrefs.merkliste;
      const ids = roh ? String(roh).split(',').filter(Boolean) : [];
      if (ids.length) {
        const a = await jf.liste('/Items?userId=' + jf.benutzer + '&ids=' + ids.join(',') + '&fields=' + FELDER);
        const self = this;
        a.forEach(function (i) { self.items[i.Id] = i; });
      }
      const self = this;
      this.ids = ids.filter(function (id) { return !!self.items[id]; });
    },

    hat: function (id) { return this.ids.indexOf(id) >= 0; },

    liste: function () {
      const self = this;
      return this.ids.map(function (id) { return self.items[id]; }).filter(Boolean);
    },

    umschalten: function (e) {
      if (this.hat(e.Id)) this.ids = this.ids.filter(function (x) { return x !== e.Id; });
      else {
        this.ids.unshift(e.Id);
        this.items[e.Id] = e;
      }
      merklisteGeaendert();
      this.speichern();
    },

    speichern: async function () {
      const dto = this.dto || {
        Id: 'lukaskino-merkliste', SortBy: 'SortName', SortOrder: 'Ascending', RememberIndexing: false,
        RememberSorting: false, ScrollDirection: 'Horizontal', ShowBackdrop: true, ShowSidebar: false,
        PrimaryImageHeight: 250, PrimaryImageWidth: 250, CustomPrefs: {}
      };
      dto.Client = this.client();
      dto.CustomPrefs = Object.assign({}, dto.CustomPrefs || {}, { merkliste: this.ids.join(',') });
      this.dto = dto;
      try {
        await jf.anfrage('POST', this.pfad(), dto);
      } catch (e) {
        hinweis('Merkliste ließ sich nicht speichern');
      }
    }
  };

  function merklisteSeite() {
    const s = neueSeite('Merkliste');
    const l = merk.liste();
    const filme = l.filter(function (e) { return typ(e) !== 'Series'; });
    const serien = l.filter(function (e) { return typ(e) === 'Series'; });
    for (let i = 0; i < filme.length; i += 8) s.reihen.push(reiheAus(i === 0 ? 'Filme' : '', filme.slice(i, i + 8), POSTER));
    for (let i = 0; i < serien.length; i += 8) s.reihen.push(reiheAus(i === 0 ? 'Serien' : '', serien.slice(i, i + 8), POSTER));
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

  async function trailerSuchen(e) {
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

  function neueSeite(titel) { return { titel: titel, reihen: [], akt: 0, leer: null, kopf: null }; }

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
      + '<div class="ebene aus" id="suche">'
      + '  <div class="glas feld">' + svg(SYMBOLE[0]) + '<div class="text"></div></div>'
      + '  <div class="tastatur"></div><div class="kopf"></div><div class="held"></div>'
      + '  <div class="fenster"><div class="gitter"></div></div>'
      + '</div>'
      + '<div class="ebene aus" id="mitte"></div>'
      + '<div class="ebene aus" id="foto"><div class="fbild"></div><div class="fbild"></div>'
      + '  <div class="f-video aus"></div><div class="f-pille aus"></div></div>'
      + '<div class="ebene" id="leiste-dunkel"></div>'
      + '<div class="glas" id="leiste"><div class="marke">Lukas Kino</div><div class="pille"></div><div class="punkt"></div></div>'
      + '<div class="ebene aus" id="trailer"><div class="t-box"></div>'
      + '  <div class="ebene p-leiste t-leiste"><div class="p-oben"></div><div class="p-unten"></div>'
      + '    <div class="t-marke">Trailer</div><div class="p-titel t-titel"></div>'
      + '    <div class="p-bahn"><div class="p-pos t-pos"></div><div class="p-knubbel t-knubbel"></div></div>'
      + '    <div class="p-zeit t-zeit"></div><div class="p-rest t-rest"></div>'
      + '    <div class="p-pause t-pause aus"></div><div class="p-meldung t-meldung aus"></div></div></div>'
      + '<div class="hinweis aus"></div>'
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

    // Seitenleiste
    el.lEintraege = LEISTE.map(function (name, i) {
      const e = div('l-eintrag');
      e.innerHTML = svg(SYMBOLE[i]) + '<span></span>';
      e.querySelector('span').textContent = name;
      e.style.top = (174 + 112 * i) + 'px';
      e._leiste = i;
      el.leiste.appendChild(e);
      return e;
    });

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
      try { await merk.laden(); } catch (e) { /* ohne Merkliste weiter */ }
      const s = await filmeSeite();
      zeigeStart('filme', s);
    } catch (e) {
      if (!jf.angemeldet()) anmelden();
      else fehler('Jellyfin ist nicht erreichbar');
    }
  }

  async function bereichOeffnen(b) {
    stapel.length = 0;
    if (b === 'merkliste') startseiten.merkliste = merklisteSeite();
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
      const s = b === 'serien' ? await serienSeite() : b === 'fotos' ? await fotosSeite() : await filmeSeite();
      zeigeStart(b, s);
    } catch (e) {
      fehler('Jellyfin ist nicht erreichbar');
    }
  }

  function zeigeStart(b, s) {
    startseiten[b] = s;
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
    s.reihen.push(reiheAus('Neu', neu, POSTER));
    if (samml.length) {
      s.reihen.push(reiheAus('Kategorien', samml, POSTER));
      samml.forEach(function (k) { s.reihen.push(neueReihe(k.Name, 'samml:' + k.Id, POSTER)); });
    }
    s.reihen.push(neueReihe('Alle Filme', '@alle', POSTER));
    return s;
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
    const jahre = [], tage = [], lose = [];
    let urlaube = null;
    inhalt.forEach(function (o) {
      const name = String(o.Name || '').trim();
      if (!o.IsFolder) lose.push(o);
      else if (name.toLowerCase().indexOf('urlaube') === 0) urlaube = o;
      else if (/^\d{4}$/.test(name)) jahre.push(o);
      else tage.push(o);
    });
    jahre.reverse();
    if (urlaube) s.reihen.push(neueReihe('Urlaube', 'ordner:' + urlaube.Id, QUER));
    if (jahre.length) s.reihen.push(reiheAus('Jahre', jahre, QUER));
    if (tage.length) s.reihen.push(reiheAus('Erinnerungen', tage, QUER));
    if (lose.length) s.reihen.push(reiheAus('Einzelne Bilder', lose, QUER));
    if (!s.reihen.length) s.leer = 'Die Fotos werden gerade eingelesen';
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
        r.eintraege = a.filter(Boolean);
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
  async function nachDemFilm(f) {
    const b = bereich;
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
      }
      fokusGeaendert();
    } catch (e) { /* nicht schlimm */ }
  }

  function ersetzen(neu) {
    const alle = Object.keys(startseiten).map(function (k) { return startseiten[k]; }).concat(stapel, [seite]);
    alle.forEach(function (s) {
      s.reihen.forEach(function (r) {
        for (let i = 0; i < r.eintraege.length; i++) {
          if (r.eintraege[i] && r.eintraege[i].Id === neu.Id) {
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
    bgTimer = setTimeout(hintergrundLaden, 220);
    zeichnen();
  }

  function hintergrundLaden() {
    const e = heldEintrag();
    if (!e) return;
    if (typ(e) === 'BoxSet') {
      hintergrundSetzen(null, FARBEN[e.Name] || '#444444');
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

  // ------------------------------------------------------------------ Logos (dunkle werden weiß)

  const logoDunkel = {};

  function logoPruefen(img, url) {
    if (logoDunkel[url] !== undefined) {
      img.classList.toggle('weiss', logoDunkel[url]);
      return;
    }
    try {
      const c = document.createElement('canvas');
      c.width = 48;
      c.height = 24;
      const g = c.getContext('2d');
      g.drawImage(img, 0, 0, 48, 24);
      const d = g.getImageData(0, 0, 48, 24).data;
      let summe = 0, n = 0;
      for (let i = 0; i < d.length; i += 4) {
        if (d[i + 3] < 128) continue;
        summe += (d[i] * 299 + d[i + 1] * 587 + d[i + 2] * 114) / 1000;
        n++;
      }
      logoDunkel[url] = n > 0 && summe / n < 95;
    } catch (e) {
      logoDunkel[url] = false;
    }
    img.classList.toggle('weiss', logoDunkel[url]);
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
    el.liste.classList.toggle('aus', !liste);
    el.suche.classList.toggle('aus', !suche);
    el.mitte.classList.toggle('aus', !mitte);
    el.foto.classList.toggle('aus', ansicht !== A.FOTO);

    if (liste) {
      reihenZeichnen();
      infoZeichnen();
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
      r.titelEl.textContent = r.titel;
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

  function kartenZeichnen(r, aktiv) {
    const quer = r.form === QUER, schritt = (quer ? QW : PW) + ABSTAND;
    if (r.gebaut !== r.version) {
      r.streifen.innerHTML = '';
      r.karten = {};
      r.platzhalter = false;
      r.gebaut = r.version;
    }
    r.streifen.style.transform = 'translate3d(' + (-r.fokus * schritt) + 'px,0,0)';
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
        c = karteBauen(r.eintraege[k], r.form);
        c.style.left = (k * schritt) + 'px';
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
    const f = fortschritt(e);
    if (f > 0.01 && f < 0.97 && !gesehen(e)) {
      const b = div('k-balken');
      b.innerHTML = '<i></i>';
      b.firstChild.style.width = (f * 100) + '%';
      c.appendChild(b);
    }
    if (gesehen(e) && !ordner) {
      const h = div('k-haken');
      h.innerHTML = '<svg viewBox="0 0 20 20" fill="none" stroke="' + GELB + '" stroke-width="3.2" stroke-linecap="round" '
        + 'stroke-linejoin="round"><path d="M4 10l4.5 5L16 5"/></svg>';
      c.appendChild(h);
    }
    return c;
  }

  // -------------------------------------------------- Infos oben links

  let infoSchluessel = '';

  function infoZeichnen() {
    const e = heldEintrag();
    const detail = ansicht === A.DETAIL;
    el.info.classList.toggle('detail', detail);
    let key;
    if (seite.leer) key = 'leer|' + seite.leer + '|' + seite.titel;
    else if (!e) key = '';
    else {
      const u = e.UserData || {};
      key = e.Id + '|' + (u.PlaybackPositionTicks || 0) + '|' + (u.Played ? 1 : 0) + '|' + (seite.kopf ? seite.kopf.Id : '')
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
        if (t === 'BoxSet') kategorieInfo(box, e);
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
      + 'stroke-linejoin="round"><path d="M5 15.5l6.5 6.5L25 8.5"/></svg>'
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
    }
    return l;
  }

  function knoepfeAktualisieren() {
    const k = el.info.querySelector('.i-knoepfe');
    const detail = ansicht === A.DETAIL;
    detailKnoepfe = detail ? knoepfeFuer(detailEintrag) : [];
    if (!k) return;
    if (!detail) return; // außerhalb der Detailansicht unsichtbar, Inhalt bleibt
    const sig = detailKnoepfe.map(function (x) { return x.art + ':' + x.text; }).join('|');
    if (k._sig !== sig) {
      k._sig = sig;
      k.innerHTML = '';
      detailKnoepfe.forEach(function (x, i) {
        const b = div('knopf');
        b._knopf = i;
        b.innerHTML = KNOPF_SYMBOL[x.symbol] + '<span></span>';
        b.querySelector('span').textContent = x.text;
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
    el.lPille.style.top = (174 + 112 * leistePos) + 'px';
    let aktiv = ansicht === A.SUCHE ? 0 : BEREICHE.indexOf(bereich) + 1;
    el.lEintraege.forEach(function (e, i) { e.classList.toggle('fokus', i === leistePos); });
    el.lPunkt.style.top = (220 + 112 * aktiv - 7) + 'px';
  }

  function leisteAuf(maus) {
    leiste = true;
    leisteMaus = !!maus;
    leistePos = ansicht === A.SUCHE ? 0 : BEREICHE.indexOf(bereich) + 1; // ausgewählt ist, wo man gerade ist
    zeichnen();
  }

  function leisteZu() {
    leiste = false;
    leisteMaus = false;
    zeichnen();
  }

  function leisteWaehlen(i) {
    leiste = false;
    leisteMaus = false;
    if (i === 0) sucheOeffnen();
    else bereichOeffnen(BEREICHE[i - 1]);
    zeichnen();
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

  function kategorieOeffnen(k) {
    const neu = neueSeite(k.Name);
    neu.kopf = k;
    neu.reihen.push(neueReihe(k.Name, null, POSTER));
    seiteZeigen(neu);
    jf.inhalt(k.Id).then(function (a) {
      neu.reihen = [];
      for (let i = 0; i < a.length; i += 8) neu.reihen.push(reiheAus(i === 0 ? k.Name : '', a.slice(i, i + 8), POSTER));
      if (!neu.reihen.length) neu.leer = 'Keine Filme';
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
    const neu = neueSeite(ordner.Name);
    neu.kopf = ordner;
    neu.reihen.push(neueReihe(ordner.Name, null, QUER));
    seiteZeigen(neu);
    jf.ordner(ordner.Id).then(function (a) {
      neu.reihen = [];
      const unter = [], bilder = [];
      a.forEach(function (o) {
        if (!o) return;
        if (o.IsFolder) unter.push(o);
        else bilder.push(o);
      });
      if (unter.length) neu.reihen.push(reiheAus('Ordner', unter, QUER));
      for (let i = 0; i < bilder.length; i += 12) neu.reihen.push(reiheAus(i === 0 ? anzahlText(bilder) : '', bilder.slice(i, i + 12), QUER));
      if (!neu.reihen.length) neu.leer = 'Leer';
      if (seite === neu) fokusGeaendert();
    }, function () {});
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
    tonAktiv: -1, uAktiv: -1, cues: null, meldung: null, tick: 0, meldeTick: 0, start: 0
  };

  function playerVorbereiten() {
    const v = el.video;
    v.addEventListener('loadedmetadata', function () {
      if (!P.offen) return;
      if (P.start > 0 && P.start < (v.duration || Infinity) - 5) {
        try { v.currentTime = P.start; } catch (e) { /* egal */ }
      }
      spurenStart();
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
      cues: null, meldung: null, start: fortsetzen ? position(f) / 10000000 : 0
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
    v.src = jf.streamUrl(P.id, P.quelle);
    const p = v.play();
    if (p && p.catch) p.catch(function () { /* Autoplay im Browser ggf. gesperrt – OK drücken */ });
    clearInterval(P.tick);
    clearInterval(P.meldeTick);
    P.tick = setInterval(playerTick, 250);
    P.meldeTick = setInterval(function () { if (P.gemeldet && !v.paused) melde(1); }, 10000);
    requestAnimationFrame(untertitelSchleife);
  }

  /** 0 = Start, 1 = Fortschritt, 2 = Stopp */
  function melde(art) {
    if (!P.id || !jf.token) return;
    const v = el.video;
    const ticks = Math.round((v.currentTime || 0) * 10000000);
    const pfad = art === 0 ? '/Sessions/Playing' : art === 1 ? '/Sessions/Playing/Progress' : '/Sessions/Playing/Stopped';
    const body = {
      ItemId: P.id, MediaSourceId: P.quelle, PositionTicks: ticks, IsPaused: !!v.paused, CanSeek: true,
      PlayMethod: 'DirectPlay', PlaySessionId: P.sitzung
    };
    const ton = P.ton[P.tonAktiv];
    if (ton && ton.stream) body.AudioStreamIndex = ton.stream.Index;
    body.SubtitleStreamIndex = P.uAktiv >= 0 ? P.uAktiv : -1;
    jf.anfrage('POST', pfad, body).catch(function () { /* nicht kritisch */ });
  }

  function playerSchliessen() {
    if (!P.offen) return;
    if (P.gemeldet && !P.gestoppt) melde(2);
    clearInterval(P.tick);
    clearInterval(P.meldeTick);
    clearTimeout(P.spulTimer);
    const f = P.eintrag;
    P.offen = false;
    P.menue = false;
    P.cues = null;
    const v = el.video;
    try {
      v.pause();
      v.removeAttribute('src');
      v.load();
    } catch (e) { /* egal */ }
    el.untertitel.textContent = '';
    zeichnen();
    nachDemFilm(f);
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
    const unter = P.streams.filter(function (s) { return s.Type === 'Subtitle' && textUntertitel(s); });
    P.unter = [{ name: 'Aus', stream: null }].concat(unter.map(function (s) { return { name: spurName(s, false), stream: s }; }));
  }

  function spurenStart() {
    const audio = P.streams.filter(function (s) { return s.Type === 'Audio'; });
    // Deutsch bevorzugen
    const at = el.video.audioTracks;
    if (at && at.length === audio.length && audio.length > 1) {
      for (let i = 0; i < at.length; i++) if (at[i].enabled) P.tonAktiv = i;
      let de = -1;
      audio.forEach(function (s, j) { if (de < 0 && istDeutsch(s)) de = j; });
      if (de >= 0 && !istDeutsch(audio[P.tonAktiv] || {})) tonSetzen(de);
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
      m.appendChild(div('p-hinweis', 'Die Tonspur lässt sich an diesem Fernseher noch nicht umschalten.'));
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
          if (!tonSetzen(P.zeile) && P.ton.length > 1) {
            P.meldung = 'Tonspur umschalten geht hier noch nicht';
            setTimeout(function () { P.meldung = null; }, 2500);
          } else {
            melde(1);
          }
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
        playsinline: 1, hl: 'de', cc_load_policy: 0, origin: location.origin
      },
      events: {
        onReady: function (ev) {
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

    if (T.offen) return trailerTaste(k);
    if (P.offen) return playerTaste(k);
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
      const i = Math.round((p.y - 260) / 112);
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
    if (document.hidden && T.offen) trailerSchliessen();
    if (document.hidden && P.offen) {
      el.video.pause();
      melde(1);
    }
  });
  window.addEventListener('pagehide', function () { if (P.offen && P.gemeldet) melde(2); });

  // ------------------------------------------------------------------ Start

  function start() {
    bauen();
    skalieren();
    window.addEventListener('resize', skalieren);
    try {
      document.body.tabIndex = -1;
      document.body.focus();
      window.focus();
    } catch (e) { /* egal */ }
    if (parameter.abmelden === '1') jf.abmelden();
    if (jf.angemeldet()) laden();
    else anmelden();
  }

  if (document.body) start();
  else document.addEventListener('DOMContentLoaded', start);
})();
