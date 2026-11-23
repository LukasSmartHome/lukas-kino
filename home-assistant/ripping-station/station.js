/* Ripping-Station-Karte fuer Home Assistant
   v2.0.1 (Claude, Konto B, 23.11.2026) - v1.0.1 von Konto A (06.11.2026) liegt als station_v1.0.1.js daneben (Rueckweg)
   type: custom:lukas-station
     ansicht: kachel  - Glas-Pille: Zustand in einer Zeile, beim Rippen/Hochladen gelber Fortschritt im Hintergrund,
                        gruener Punkt = naechste Disc kann rein. Antippen oeffnet das Vollbild (hash).
     ansicht: menue   - Inhalt fuer custom:lukas-vollbild (hash #station, modus fuellen):
                        Ampel "Disc einlegen?", was gerade laeuft (je Disc mit Stufen Lesen > Verarbeiten > Hochladen > Jellyfin),
                        Werte, Verlauf nach Tagen (Titel, wo gespeichert, Groesse, in Jellyfin ja/nein, Fehler),
                        Knoepfe (Auswerfen, Fach zu, beim Rippen Abbrechen) und aufklappbare Wartung.
     entity: sensor.ripping_station (Standard)   hash: "#station" (Standard)   name: Ripping-Station (Standard)
     status_url: http://192.168.178.118:8090/status.json (Standard) - die Karte holt den Live-Stand direkt vom IPC
                 (alle 3 s, solange sie sichtbar ist). Geht das nicht, nimmt sie den Sensor (alle 10 s).
     jellyfin: sensor.ripping_station_jellyfin (Standard) - welche Rips Jellyfin schon kennt (station_neu_erledigt.json).
   Daten: packages/ripping_station.yaml. Knoepfe: shell_command.station_* mit Antwort (eine Zeile).
   Abbrechen, Neu starten und Ausschalten fragen einmal nach (zweites Antippen innerhalb von 4 s).
   Datei ohne Plus-, Prozent- und Und-Zeichen (Upload ueber die File-editor-API ohne Kodierung). */
(function () {
  var TAG = "lukas-station";
  if (customElements.get(TAG)) return;
  var PRO = String.fromCharCode(37);
  var haptic = function (art) { try { window.dispatchEvent(new CustomEvent("haptic", { detail: art })); } catch (e) { /* egal */ } };
  var esc = function (s) { return String(s == null ? "" : s).replace(/[<>"]/g, function (c) { return c === "<" ? "‹" : (c === ">" ? "›" : "”"); }); };
  var zahl = function (v) { if (typeof v === "number") return v; if (v == null || v === "") return null; return isNaN(Number(v)) ? null : Number(v); };
  var teile = function (z) { return String(z || "").match(/^(\d\d)\.(\d\d)\.(\d{4}) (\d\d):(\d\d)/); };
  var datum = function (z) { var m = teile(z); return m ? new Date(Number(m[3]), Number(m[2]) - 1, Number(m[1]), Number(m[4]), Number(m[5])) : null; };
  var tagText = function (d) { return d.toLocaleDateString("de-DE", { day: "2-digit", month: "2-digit", year: "numeric" }); };
  var uhr = function (z) { var m = teile(z); return m ? `${m[4]}:${m[5]}` : ""; };
  var wann = function (z) {
    var m = teile(z);
    if (!m) return z ? String(z) : "";
    return [m[1], m[2], m[3]].join(".") === tagText(new Date()) ? `heute ${m[4]}:${m[5]}` : `${m[1]}.${m[2]}. ${m[4]}:${m[5]}`;
  };
  var jung = function (z, std) {
    var d = datum(z);
    if (!d) return true;
    return (Date.now() - d.getTime()) / 3600000 < (std || 24);
  };
  var dauer = function (a, b) {
    var x = datum(a), y = datum(b);
    if (!x || !y) return null;
    return Math.max(0, Math.round((y.getTime() - x.getTime()) / 60000));
  };
  var ohneKennung = function (s) { return String(s || "").replace(/\s*\[imdbid-[^\]]*\]/g, "").replace(/\.(mkv|avi|mp4|m4v)$/i, ""); };
  var ordnerText = function (ziel) {
    // "Filme4/Ripping-Station/Filme/Titel (Jahr) [imdbid-tt..]/Titel.mkv" -> "Filme4 › Ripping-Station › Filme › Titel (Jahr)"
    var t = String(ziel || "").split("/").filter(Boolean);
    if (!t.length) return "";
    var letzte = t[t.length - 1];
    if (/\.(mkv|avi|mp4|m4v)$/i.test(letzte)) t = t.slice(0, t.length - 1).length > 1 ? t.slice(0, t.length - 1) : t;
    return t.map(ohneKennung).join(" › ");
  };
  var datei = function (ziel) { var t = String(ziel || "").split("/"); return t[t.length - 1] || ""; };
  var zielKurz = function (z) { return z ? String(z).split("/")[0] : ""; };

  var SVG_PFEIL = `<svg viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" d="M9 6l6 6-6 6"/></svg>`;
  var GLAS = `background: linear-gradient(135deg, rgba(255,255,255,0.66), rgba(255,255,255,0.36)); border: 1px solid rgba(255,255,255,0.75);
    box-shadow: inset 0 1px 1px rgba(255,255,255,0.95), 0 10px 26px rgba(36,56,104,0.12); backdrop-filter: blur(22px) saturate(1.8); -webkit-backdrop-filter: blur(22px) saturate(1.8);`;
  var SCHRIFT = `font-family: var(--ha-font-family-body, -apple-system, BlinkMacSystemFont, "SF Pro Text", "Segoe UI", Roboto, sans-serif);`;
  var GELB = "#F5B800";
  var ROT = "#E5484D";
  var GRUEN = "#1F9D55";
  var ART = { film: "Film", serie: "Serie", musik: "Musik-CD" };
  var ZT = { bereit: "Bereit", liest: "Liest", verarbeitet: "Verarbeitet", uebertraegt: "Lädt hoch", wartet_auf_pi: "Wartet auf den Pi", fehler: "Fehler" };
  // Stufen eines Auftrags (status.json -> auftraege[].stufe)
  var STUFE = {
    erkannt: { t: "Disc erkannt", s: 0, lauf: true }, analyse: { t: "Analysiert die Disc", s: 0, lauf: true },
    lesen: { t: "Liest", s: 0, lauf: true }, erkennen: { t: "Verarbeitet (Erkennen, Untertitel)", s: 1, lauf: true },
    warten_hochladen: { t: "Wartet aufs Hochladen", s: 2, lauf: true }, hochladen: { t: "Lädt auf den Pi", s: 2, lauf: true },
    wartet_auf_pi: { t: "Wartet auf den Pi", s: 2, lauf: true }, pruefen: { t: "Nicht erkannt – liegt zum Prüfen", s: 2, warn: true },
    fertig: { t: "Fertig", s: 3, ok: true }, abgebrochen: { t: "Abgebrochen", s: 0, rot: true },
    fehler: { t: "Fehler", s: 0, rot: true }, leer: { t: "Nichts gefunden", s: 1, rot: true }
  };
  var SCHRITTE = ["Lesen", "Verarbeiten", "Hochladen", "Jellyfin"];

  var CSS_KACHEL = `
    :host { display: block; min-width: 0; }
    .k { position: relative; height: 50px; border-radius: 32px; display: flex; align-items: center; gap: 8px; box-sizing: border-box; overflow: hidden; cursor: pointer;
      ${GLAS} color: #0F1A2C; padding: 0 12px 0 6px; -webkit-user-select: none; user-select: none; -webkit-tap-highlight-color: transparent; ${SCHRIFT}
      transition: box-shadow 0.35s ease, border-color 0.35s ease; }
    .k:active { transform: scale(0.985); }
    .fuell { position: absolute; left: 0; top: 0; bottom: 0; background: linear-gradient(90deg, rgba(255,216,77,0.7), rgba(245,184,0,0.85)); transition: width 0.8s ease; pointer-events: none; }
    .k.aktiv { border-color: rgba(245,184,0,0.7); box-shadow: 0 8px 22px rgba(245,184,0,0.28); }
    .k.fehler { background: linear-gradient(135deg, rgba(255,236,236,0.9), rgba(255,214,214,0.65)); border-color: rgba(229,72,77,0.45); }
    .k.aus { opacity: 0.7; }
    .ic { position: relative; flex: 0 0 auto; width: 34px; height: 34px; border-radius: 999px; display: grid; place-items: center; background: rgba(255,255,255,0.8); color: #33415A; --mdc-icon-size: 20px; }
    .k.aktiv .ic { color: #1F1500; background: rgba(255,255,255,0.7); }
    .k.aktiv .ic ha-icon { animation: dreh 2.4s linear infinite; }
    .k.fehler .ic { color: ${ROT}; }
    .punkt { position: absolute; right: -1px; bottom: -1px; width: 11px; height: 11px; border-radius: 999px; background: ${GRUEN}; border: 2px solid #FFFFFF; }
    @keyframes dreh { to { transform: rotate(360deg); } }
    .tx { position: relative; flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; line-height: 1.15; }
    .n { font-size: 13px; font-weight: 700; white-space: nowrap; }
    .s { font-size: 11.5px; font-weight: 600; color: #4B5870; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .k.aktiv .s { color: #3A2E00; }
    .k.fehler .s { color: #A1262B; }
    .r { position: relative; flex: 0 0 auto; text-align: right; line-height: 1.1; }
    .r b { display: block; font-size: 15px; font-weight: 800; }
    .r span { display: block; font-size: 11px; font-weight: 650; color: #3A2E00; }
    .pf { position: relative; flex: 0 0 auto; width: 16px; height: 16px; color: #8A97AE; }
    .pf svg { width: 16px; height: 16px; }
  `;

  var CSS_MENUE = `
    :host { display: block; height: 100${PRO}; }
    .w { height: 100${PRO}; overflow-y: auto; -webkit-overflow-scrolling: touch; display: flex; flex-direction: column; gap: 12px; box-sizing: border-box;
      padding: 2px 4px 18px; color: #0F1A2C; -webkit-user-select: none; user-select: none; -webkit-tap-highlight-color: transparent; ${SCHRIFT} }
    .w > * { flex: 0 0 auto; }
    .kopf { display: flex; align-items: center; gap: 12px; padding: 14px; border-radius: 26px; ${GLAS} transition: background 0.35s ease; }
    .kopf .ic { width: 50px; height: 50px; border-radius: 16px; display: grid; place-items: center; background: rgba(255,255,255,0.85); color: #33415A; flex: 0 0 auto; --mdc-icon-size: 30px; }
    .kopf.aktiv { background: linear-gradient(135deg, #FFE27A, ${GELB}); border-color: rgba(255,255,255,0.5); box-shadow: 0 10px 28px rgba(245,184,0,0.35); color: #1F1500; }
    .kopf.aktiv .ic { background: rgba(255,255,255,0.45); color: #1F1500; }
    .kopf.aktiv .ic ha-icon { animation: dreh 2.4s linear infinite; }
    .kopf.fehler { background: linear-gradient(135deg, #FFE6E6, #FFCBCC); border-color: rgba(229,72,77,0.4); }
    .kopf.fehler .ic { color: ${ROT}; }
    .kopf.aus { opacity: 0.8; }
    @keyframes dreh { to { transform: rotate(360deg); } }
    @keyframes puls { 0${PRO} { box-shadow: 0 0 0 0 rgba(31,157,85,0.45); } 70${PRO} { box-shadow: 0 0 0 10px rgba(31,157,85,0); } 100${PRO} { box-shadow: 0 0 0 0 rgba(31,157,85,0); } }
    .kt { flex: 1 1 auto; min-width: 0; }
    .kt b { display: block; font-size: 20px; font-weight: 800; letter-spacing: -0.01em; }
    .kt span { display: block; margin-top: 3px; font-size: 13.5px; font-weight: 650; color: #4B5870; line-height: 1.3; word-break: break-word; }
    .kopf.aktiv .kt span { color: #3A2E00; }
    .kopf.fehler .kt span { color: #A1262B; }
    .ampel { display: flex; align-items: center; gap: 12px; padding: 12px 14px; border-radius: 22px; ${GLAS} }
    .ampel i { flex: 0 0 auto; width: 16px; height: 16px; border-radius: 999px; background: #B8C2D3; }
    .ampel.ja i { background: ${GRUEN}; animation: puls 2s infinite; }
    .ampel.nein i { background: ${GELB}; }
    .ampel.rot i { background: ${ROT}; }
    .ampel b { display: block; font-size: 16px; font-weight: 800; }
    .ampel span { display: block; margin-top: 2px; font-size: 12.5px; font-weight: 650; color: #5B6B86; line-height: 1.3; }
    .ampel.ja { background: linear-gradient(135deg, rgba(225,247,234,0.95), rgba(196,238,212,0.7)); border-color: rgba(31,157,85,0.35); }
    .ampel.ja b { color: #116B39; }
    .lauf { padding: 12px 14px; border-radius: 22px; ${GLAS} }
    .lauf .t { display: flex; justify-content: space-between; align-items: baseline; gap: 10px; }
    .lauf .t b { font-size: 15.5px; font-weight: 800; line-height: 1.25; min-width: 0; word-break: break-word; }
    .lauf .t span { flex: 0 0 auto; font-size: 12px; font-weight: 750; color: #8A6A00; }
    .lauf .u { margin-top: 2px; font-size: 12.5px; font-weight: 650; color: #5B6B86; }
    .schritte { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 4px; margin-top: 10px; }
    .schritte div { text-align: center; font-size: 10.5px; font-weight: 800; letter-spacing: 0.02em; color: #9AA6BA; }
    .schritte div i { display: block; height: 5px; border-radius: 999px; background: rgba(15,26,44,0.08); margin-bottom: 4px; }
    .schritte div.fertig { color: #116B39; }
    .schritte div.fertig i { background: ${GRUEN}; }
    .schritte div.jetzt { color: #8A6A00; }
    .schritte div.jetzt i { background: linear-gradient(90deg, #FFD84D, ${GELB}); }
    .schritte div.rot { color: #A1262B; }
    .schritte div.rot i { background: ${ROT}; }
    .balken { height: 12px; border-radius: 999px; background: rgba(15,26,44,0.08); overflow: hidden; margin-top: 10px; }
    .balken i { display: block; height: 100${PRO}; border-radius: 999px; background: linear-gradient(90deg, #FFD84D, ${GELB}); transition: width 0.8s ease; }
    .balken.unklar i { width: 35${PRO}; animation: hin 1.6s ease-in-out infinite alternate; }
    @keyframes hin { from { transform: translateX(-30${PRO}); } to { transform: translateX(220${PRO}); } }
    .fz { display: flex; justify-content: space-between; align-items: baseline; margin-top: 8px; }
    .fz b { font-size: 22px; font-weight: 800; }
    .fz span { font-size: 13px; font-weight: 650; color: #4B5870; }
    .werte { display: flex; flex-wrap: wrap; gap: 8px; }
    .wt { flex: 1 1 0; min-width: 84px; padding: 9px 12px; border-radius: 18px; ${GLAS} }
    .wt span, .zeile .l, .tag { display: block; font-size: 11px; font-weight: 750; letter-spacing: 0.04em; text-transform: uppercase; color: #8A97AE; }
    .wt b { display: block; margin-top: 2px; font-size: 15.5px; font-weight: 800; white-space: nowrap; }
    .wt.warn b { color: #B25E00; }
    .wt.rot b { color: ${ROT}; }
    .wt.gruen b { color: #116B39; }
    .zeile { padding: 11px 14px; border-radius: 20px; ${GLAS} }
    .zeile b { display: block; margin-top: 3px; font-size: 14.5px; font-weight: 750; line-height: 1.3; word-break: break-word; }
    .zeile .z { display: block; margin-top: 2px; font-size: 12px; font-weight: 600; color: #5B6B86; }
    .zeile.rot { background: linear-gradient(135deg, rgba(255,236,236,0.9), rgba(255,220,220,0.65)); border-color: rgba(229,72,77,0.35); }
    .zeile.rot b { color: #A1262B; }
    .tag { display: flex; justify-content: space-between; align-items: baseline; margin: 6px 8px -4px; }
    .tag em { font-style: normal; text-transform: none; letter-spacing: 0; font-size: 12px; font-weight: 700; color: #5B6B86; }
    .liste { border-radius: 22px; overflow: hidden; ${GLAS} }
    .ein { display: flex; gap: 10px; padding: 11px 14px; border-top: 1px solid rgba(15,26,44,0.06); }
    .ein:first-child { border-top: 0; }
    .ein .sy { flex: 0 0 auto; width: 30px; height: 30px; border-radius: 10px; display: grid; place-items: center; background: rgba(15,26,44,0.06); color: #5B6B86; --mdc-icon-size: 18px; margin-top: 1px; }
    .ein .sy.ok { background: rgba(31,157,85,0.14); color: ${GRUEN}; }
    .ein .sy.lauf { background: rgba(245,184,0,0.2); color: #8A6A00; }
    .ein .sy.warn { background: rgba(245,140,0,0.16); color: #B25E00; }
    .ein .sy.rot { background: rgba(229,72,77,0.13); color: ${ROT}; }
    .ein .mi { flex: 1 1 auto; min-width: 0; }
    .ein .mi b { display: block; font-size: 14.5px; font-weight: 800; line-height: 1.25; word-break: break-word; }
    .ein .mi .st { display: block; margin-top: 2px; font-size: 12.5px; font-weight: 700; color: #5B6B86; }
    .ein .mi .st.ok { color: #116B39; }
    .ein .mi .st.warn { color: #B25E00; }
    .ein .mi .st.rot { color: #A1262B; }
    .ein .mi .wo { display: block; margin-top: 3px; font-size: 11.5px; font-weight: 600; color: #7A879D; line-height: 1.3; word-break: break-word; }
    .ein .mi .wo ha-icon { --mdc-icon-size: 13px; vertical-align: -2px; margin-right: 3px; color: #9AA6BA; }
    .ein .re { flex: 0 0 auto; text-align: right; font-size: 12px; font-weight: 700; color: #7A879D; line-height: 1.35; }
    .ein.alt { opacity: 0.55; }
    .mehr { margin: -4px 8px 0; font-size: 12.5px; font-weight: 750; color: #4A6FB5; cursor: pointer; }
    .reihe { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
    .reihe.eins { grid-template-columns: 1fr; }
    .kn { height: 52px; border-radius: 18px; display: flex; align-items: center; justify-content: center; gap: 8px; font-size: 16px; font-weight: 800; cursor: pointer;
      ${GLAS} color: #0F1A2C; --mdc-icon-size: 20px; transition: opacity 0.2s ease, background 0.2s ease; }
    .kn:active { transform: scale(0.98); }
    .kn.haupt { background: linear-gradient(135deg, #FFD84D, ${GELB}); color: #1F1500; border-color: rgba(255,255,255,0.5); box-shadow: 0 10px 24px rgba(245,184,0,0.35); }
    .kn.rot { color: ${ROT}; }
    .kn.frage { background: ${ROT}; color: #FFFFFF; border-color: rgba(255,255,255,0.4); box-shadow: 0 10px 24px rgba(229,72,77,0.35); }
    .kn.zu { opacity: 0.4; pointer-events: none; }
    .kn.kl { height: 44px; font-size: 14px; font-weight: 750; border-radius: 16px; }
    .antwort { margin: -2px 8px 0; font-size: 13px; font-weight: 700; color: #12804A; }
    .antwort.rot { color: #A1262B; }
    .wartung { display: flex; align-items: center; justify-content: space-between; margin: 6px 8px 0; font-size: 12px; font-weight: 800; letter-spacing: 0.06em;
      text-transform: uppercase; color: #8A97AE; cursor: pointer; }
    .wartung svg { width: 14px; height: 14px; transition: transform 0.2s ease; }
    .wartung.offen svg { transform: rotate(90deg); }
    .hinweis { margin: 0 8px; font-size: 12px; font-weight: 600; color: #5B6B86; line-height: 1.35; }
  `;

  class LukasStation extends HTMLElement {
    setConfig(c) {
      this._c = Object.assign({ ansicht: "kachel", entity: "sensor.ripping_station", hash: "#station", name: "Ripping-Station",
        status_url: "http://192.168.178.118:8090/status.json", jellyfin: "sensor.ripping_station_jellyfin" }, c || {});
      if (!this.shadowRoot) this.attachShadow({ mode: "open" });
      if (!this._klick) {
        var self = this;
        this._klick = function (ev) {
          var el = ev.target ? (ev.target.closest ? ev.target.closest("[data-b], [data-url]") : null) : null;
          if (!el) return;
          if (el.dataset.url) { haptic("light"); window.open(el.dataset.url, "_blank"); return; }
          var b = el.dataset.b;
          if (b === "_oeffnen") { haptic("light"); self._oeffnen(); return; }
          if (b === "_wartung") { haptic("light"); self._wartung = !self._wartung; self._sig = ""; self._render(); return; }
          if (b === "_alt") { haptic("light"); self._alteZeigen = !self._alteZeigen; self._sig = ""; self._render(); return; }
          if (b === "_mehrtage") { haptic("light"); self._alleTage = !self._alleTage; self._sig = ""; self._render(); return; }
          self._befehl(b);
        };
        this.shadowRoot.addEventListener("click", this._klick);
      }
      this._sig = "";
      if (this._hass) this._render();
    }
    set hass(h) { this._hass = h; this._render(); }
    getCardSize() { return this._c.ansicht === "kachel" ? 1 : 8; }
    getGridOptions() { return { columns: 12, rows: 1 }; }
    connectedCallback() { this._holenStarten(); }
    disconnectedCallback() { clearTimeout(this._frageT); clearTimeout(this._antwortT); clearTimeout(this._holT); this._holT = null; }

    // ---- Live-Stand direkt vom IPC (status.json), Sensor als Rueckfall
    _holenStarten() {
      if (this._holT) return;
      var self = this;
      var takt = function () {
        self._holT = setTimeout(takt, self._c ? (self._c.ansicht === "kachel" ? 6000 : 3000) : 5000);
        if (!self._c) return;
        if (document.hidden) return;
        if (!self.isConnected) return;
        if (self._c.ansicht !== "kachel" ? !self.offsetParent : false) return; // Vollbild zu -> nicht holen
        self._holen();
      };
      this._holT = setTimeout(takt, 50);
    }
    _holen() {
      var self = this;
      if (this._holLaeuft) return;
      this._holLaeuft = true;
      var ctrl = typeof AbortController !== "undefined" ? new AbortController() : null;
      var abbruch = setTimeout(function () { if (ctrl) ctrl.abort(); }, 4000);
      fetch(this._c.status_url, { cache: "no-store", signal: ctrl ? ctrl.signal : undefined }).then(function (r) {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.json();
      }).then(function (j) {
        self._st = j; self._stZeit = Date.now(); self._stFehler = 0;
      }).catch(function () {
        self._stFehler = (self._stFehler || 0) - -1;
      }).finally(function () {
        clearTimeout(abbruch);
        self._holLaeuft = false;
        self._render();
      });
    }

    _lage() {
      var s = this._hass ? this._hass.states[this._c.entity] : null;
      var frisch = this._st ? (Date.now() - (this._stZeit || 0) < 20000) : false;
      var a = frisch ? this._st : (s ? (s.attributes || {}) : {});
      var z = frisch ? this._st.zustand : (s ? s.state : "unavailable");
      var p = zahl(a.prozent);
      var jf = this._hass ? this._hass.states[this._c.jellyfin] : null;
      return {
        z: z, a: a, live: frisch,
        aus: frisch ? false : (!s || ["unavailable", "unknown"].indexOf(z) >= 0),
        aktiv: ["liest", "uebertraegt", "verarbeitet"].indexOf(z) >= 0,
        fehler: z === "fehler",
        prozent: p == null ? null : Math.max(0, Math.min(100, p)),
        rest: zahl(a.rest_min),
        titel: a.titel || "",
        art: ART[a.art] || "",
        ziel: zielKurz(a.ziel),
        ws: zahl(a.warteschlange) || 0,
        pr: zahl(a.pruefen) || 0,
        puffer: zahl(a.puffer_frei_gb),
        pufferOk: a.puffer_ok !== false,
        cpu: zahl(a.temperatur),
        platte: zahl(a.temperatur_platte),
        fehlerInfo: a.letzter_fehler || null,
        meldung: a.letzte_meldung || null,
        fertig: a.letzte_fertige || null,
        laufwerk: a.laufwerk || null,
        einlegen: a.einlegen,
        einlegenText: a.einlegen_text || "",
        nebenbei: a.nebenbei || "",
        auftraege: frisch ? (this._st.auftraege || []) : null,
        jellyfin: jf ? ((jf.attributes || {}).dateien || {}) : null
      };
    }

    _ampel(l) {
      // gibt {an: ja|nein|rot|aus, b, s}
      if (l.aus) return { an: "aus", b: "Station nicht erreichbar", s: "IPC aus oder nicht im Netz." };
      var t = String(l.einlegenText || "");
      var ja = l.einlegen === true;
      if (l.einlegen == null) ja = l.z === "bereit" ? l.pufferOk : false; // alte Station ohne Feld
      if (ja) {
        var neben = l.nebenbei ? `Die letzte wird nebenbei fertig: ${l.nebenbei}.` : "Einlegen – der Rest läuft von allein.";
        return { an: "ja", b: "Nächste Disc kann rein", s: neben };
      }
      if (/liest/i.test(t) || l.z === "liest") return { an: "nein", b: "Noch nicht einlegen", s: "Das Laufwerk liest gerade – warte, bis die Disc rauskommt." };
      if (/liegt noch/i.test(t)) return { an: "nein", b: "Disc liegt noch im Laufwerk", s: "Sie wird gleich erkannt oder ausgeworfen. Erst rausnehmen, dann die nächste." };
      if (/antwortet/i.test(t)) return { an: "rot", b: "Laufwerk antwortet nicht", s: "Kurz warten. Hilft das nicht: Wartung › USB neu." };
      if (/speicher|puffer/i.test(t) || !l.pufferOk) return { an: "rot", b: "Zwischenspeicher gestört", s: "Die Station repariert die Platte selbst. Bis dahin keine Disc einlegen." };
      return { an: "nein", b: "Bitte kurz warten", s: t || ZT[l.z] || "" };
    }

    _kurz(l) {
      if (l.aus) return "Aus oder nicht erreichbar";
      if (l.z === "liest") return l.titel ? `Liest: ${l.titel}` : "Liest die Disc";
      if (l.z === "verarbeitet") return l.titel ? `Verarbeitet: ${l.titel}` : "Verarbeitet den Rip";
      if (l.z === "uebertraegt") return l.titel ? `Lädt hoch: ${l.titel}` : "Lädt auf den Pi";
      if (l.z === "wartet_auf_pi") return "Wartet auf den Pi";
      if (!l.pufferOk) return "Zwischenspeicher gestört";
      if (l.fehler) return l.fehlerInfo ? l.fehlerInfo.text : "Fehler";
      var am = this._ampel(l);
      if (am.an !== "ja") return am.b;
      if (l.pr) return `Bereit · ${l.pr} zum Prüfen`;
      if (l.ws) return `Bereit · ${l.ws} ${l.ws === 1 ? "Rip wartet" : "Rips warten"} aufs Hochladen`;
      return "Bereit · Disc kann rein";
    }

    _render() {
      if (!this._hass || !this._c || !this.shadowRoot) return;
      var l = this._lage();
      var sig = [l.z, l.live, l.prozent, l.rest, l.titel, l.ws, l.pr, l.puffer, l.pufferOk, l.cpu, l.platte, l.laufwerk, l.einlegen, l.einlegenText, l.nebenbei,
        JSON.stringify(l.fehlerInfo), JSON.stringify(l.meldung), JSON.stringify(l.fertig),
        this._c.ansicht === "kachel" ? "" : JSON.stringify(l.auftraege), this._c.ansicht === "kachel" ? "" : JSON.stringify(l.jellyfin),
        this._frage, this._laeuft, this._antwort, this._antwortOk, this._wartung, this._alteZeigen, this._alleTage].join("|");
      if (sig === this._sig) return;
      this._sig = sig;
      if (this._c.ansicht === "kachel") this._kachel(l); else this._menue(l);
    }

    _oeffnen() {
      history.pushState(null, "", `${location.pathname}${location.search}${this._c.hash}`);
      window.dispatchEvent(new CustomEvent("location-changed", { detail: { replace: false } }));
    }

    _kachel(l) {
      var kl = ["k"];
      if (l.aktiv) kl.push("aktiv");
      if (l.fehler ? true : !l.pufferOk) kl.push("fehler");
      if (l.aus) kl.push("aus");
      var rechts = `<div class="pf">${SVG_PFEIL}</div>`;
      var mitZahl = l.aktiv ? (l.prozent != null ? l.z !== "verarbeitet" : false) : false;
      if (mitZahl) {
        rechts = `<div class="r"><b>${Math.round(l.prozent)} ${PRO}</b>${l.rest != null ? `<span>noch ${Math.round(l.rest)} min</span>` : ""}</div>`;
      }
      var breite = mitZahl ? l.prozent : 0;
      var punkt = this._ampel(l).an === "ja" ? `<i class="punkt"></i>` : "";
      this.shadowRoot.innerHTML = `<style>${CSS_KACHEL}</style>
        <div class="${kl.join(" ")}" data-b="_oeffnen" title="Ripping-Station öffnen">
          <div class="fuell" style="width: ${breite}${PRO};"></div>
          <div class="ic"><ha-icon icon="${l.z === "uebertraegt" ? "mdi:upload" : "mdi:disc"}"></ha-icon>${punkt}</div>
          <div class="tx"><div class="n">${esc(this._c.name)}</div><div class="s">${esc(this._kurz(l))}</div></div>
          ${rechts}
        </div>`;
    }

    // ---- ein Auftrag (Disc) als Eintrag
    _inJellyfin(e, l) {
      if (!l.jellyfin || !e.ziel) return null;
      var schluessel = datei(e.ziel);
      return Object.prototype.hasOwnProperty.call(l.jellyfin, schluessel) ? (l.jellyfin[schluessel] || "ja") : null;
    }
    _eintrag(e, l, alt) {
      var st = STUFE[e.stufe] || { t: e.stufe || "?", s: 0 };
      var jf = e.stufe === "fertig" ? this._inJellyfin(e, l) : null;
      var imJf = e.stufe === "fertig" ? /Ripping-Station\/(Filme|Serien|Musik)\//.test(String(e.ziel || "")) : false;
      var sy = "lauf", icon = "mdi:progress-clock", text = st.t, klasse = "";
      if (st.ok) {
        sy = "ok"; icon = "mdi:check-bold"; klasse = "ok";
        if (jf) text = teile(String(jf)) ? `In Jellyfin · seit ${wann(String(jf))}` : "In Jellyfin";
        else if (imJf ? l.jellyfin != null : false) { text = "Fertig · Jellyfin nimmt ihn gerade auf"; sy = "lauf"; icon = "mdi:progress-upload"; klasse = ""; }
        else text = "Fertig";
        if (/nochmal dr/i.test(String(e.ziel || ""))) { text = "Aussortiert – nicht in Jellyfin (schlechte Kopie)"; sy = "warn"; icon = "mdi:archive-outline"; klasse = "warn"; }
      } else if (/Daten-Disc.*gesichert/i.test(String(e.meldung || ""))) { sy = "warn"; icon = "mdi:folder-zip-outline"; klasse = "warn"; text = "Daten-Disc gesichert – Claude sortiert sie ein";
      } else if (st.rot) { sy = "rot"; icon = e.stufe === "leer" ? "mdi:disc-alert" : "mdi:close-thick"; klasse = "rot"; }
      else if (st.warn) { sy = "warn"; icon = "mdi:help-circle-outline"; klasse = "warn"; }
      else if (e.stufe === "hochladen") icon = "mdi:upload";
      else if (e.stufe === "erkennen") icon = "mdi:cog-outline";
      else icon = "mdi:disc";
      var titel = e.titel || (e.disc_name ? `Disc „${e.disc_name}“` : `Auftrag ${e.id}`);
      var wo = "";
      if (e.ziel) wo = `<span class="wo"><ha-icon icon="mdi:folder-outline"></ha-icon>${esc(ordnerText(e.ziel))}</span>`;
      var mel = "";
      if (e.meldung ? (st.rot ? true : (st.warn ? true : e.stufe !== "fertig")) : false) mel = `<span class="wo">${esc(this._meldungText(e.meldung))}</span>`;
      var rechts = [];
      if (e.groesse_gb) rechts.push(`${e.groesse_gb >= 10 ? Math.round(e.groesse_gb) : e.groesse_gb.toFixed(1)} GB`);
      var start = uhr(e.start);
      if (start) rechts.push(start);
      var min = dauer(e.start, e.ende);
      if (min ? st.ok : false) rechts.push(`${min} min`);
      return `<div class="ein${alt ? " alt" : ""}"><div class="sy ${sy}"><ha-icon icon="${icon}"></ha-icon></div>
        <div class="mi"><b>${esc(titel)}</b><span class="st ${klasse}">${esc(text)}</span>${wo}${mel}</div>
        <div class="re">${rechts.map(esc).join("<br>")}</div></div>`;
    }
    _meldungText(m) {
      var t = String(m || "");
      if (/Error while running MakeMKV/i.test(t)) return "MakeMKV konnte die Disc nicht lesen";
      if (/Puffer-Fehler/i.test(t)) return "Abgebrochen: Zwischenspeicher war zu langsam (seit 23.11. abends behoben) – Disc nochmal einlegen";
      if (/^nicht erkannt/i.test(t)) return "Nicht erkannt – Claude benennt ihn (sag kurz, was es war)";
      return t;
    }

    // ---- laufende Auftraege gross
    _lauf(e, l) {
      var st = STUFE[e.stufe] || { t: e.stufe, s: 0 };
      var schritte = SCHRITTE.map(function (n, i) {
        var k = i < st.s ? "fertig" : (i === st.s ? "jetzt" : "");
        return `<div class="${k}"><i></i>${n}</div>`;
      }).join("");
      var pz = zahl(e.prozent);
      var unklar = pz == null;
      var gross = unklar ? st.t : `${Math.round(pz)} ${PRO}`;
      var rechts = [];
      if (e.rest_min != null) rechts.push(`noch ca. ${Math.round(e.rest_min)} min`);
      if (e.mb_s) rechts.push(`${Math.round(e.mb_s)} MB/s`);
      var titel = e.titel || (e.disc_name ? `Disc „${e.disc_name}“` : "Disc");
      var unter = [ART[e.art] || "", e.disc_name ? `Disc-Name ${e.disc_name}` : "", e.start ? `seit ${uhr(e.start)}` : ""].filter(Boolean).join(" · ");
      var hinweis = e.stufe === "erkennen" ? `<div class="u">Erkennen und Untertitel aufräumen – bei Blu-rays bis ca. 15 min. Die nächste Disc darf schon rein.</div>` : "";
      if (e.meldung) hinweis = `<div class="u">${esc(this._meldungText(e.meldung))}</div>`;
      return `<div class="lauf"><div class="t"><b>${esc(titel)}</b><span>${esc(st.t)}</span></div>
        <div class="u">${esc(unter)}</div>
        <div class="schritte">${schritte}</div>
        <div class="balken${unklar ? " unklar" : ""}"><i style="${unklar ? "" : `width: ${pz}${PRO};`}"></i></div>
        <div class="fz"><b>${esc(gross)}</b><span>${esc(rechts.join(" · "))}</span></div>${hinweis}</div>`;
    }

    _menue(l) {
      var self = this;
      var alt = this.shadowRoot.querySelector(".w");
      var scroll = alt ? alt.scrollTop : 0;
      var kl = ["kopf"];
      if (l.aktiv) kl.push("aktiv");
      if (l.fehler ? true : !l.pufferOk) kl.push("fehler");
      if (l.aus) kl.push("aus");
      var titel = l.aus ? "Nicht erreichbar" : (ZT[l.z] || l.z);
      var unter;
      if (l.aus) unter = "IPC aus oder nicht im Netz – die Knöpfe gehen erst wieder, wenn er läuft.";
      else if (l.z === "liest") unter = [l.art, l.titel || "Disc wird erkannt …"].filter(Boolean).join(" · ");
      else if (l.z === "verarbeitet") unter = [l.titel, "Erkennen, Untertitel aufräumen"].filter(Boolean).join(" · ");
      else if (l.z === "uebertraegt") unter = [l.titel, l.ziel ? `nach ${l.ziel}` : ""].filter(Boolean).join(" · ");
      else if (l.z === "wartet_auf_pi") unter = `${l.ws ? l.ws : "Fertige"} ${l.ws === 1 ? "Rip wartet" : "Rips warten"}, bis der Pi wieder da ist.`;
      else if (!l.pufferOk) unter = "Zwischenspeicher-Platte fehlt oder lässt sich nicht einhängen.";
      else if (l.fehler) unter = l.fehlerInfo ? `${l.fehlerInfo.text} (${wann(l.fehlerInfo.zeit)})` : "Unbekannter Fehler";
      else unter = "Alles erledigt.";
      var erste = l.auftraege ? l.auftraege.filter(function (e) { var s = STUFE[e.stufe]; return s ? s.lauf === true : false; })[0] : null;
      if (erste ? ["liest", "verarbeitet", "uebertraegt"].indexOf(l.z) >= 0 : false) {
        var nm = erste.titel || (erste.disc_name ? `Disc „${erste.disc_name}“ (wird nach dem Lesen erkannt)` : "Disc");
        unter = [ART[erste.art] || l.art, nm].filter(Boolean).join(" · ");
      }
      if (l.nebenbei ? !l.aus : false) unter = `${unter} Nebenbei: ${l.nebenbei}.`;

      // Ampel
      var am = this._ampel(l);
      var ampel = `<div class="ampel ${am.an}"><i></i><div><b>${esc(am.b)}</b><span>${esc(am.s)}</span></div></div>`;

      // Laufende Auftraege (oder Rueckfall: Fortschritt aus dem Sensor)
      var au = l.auftraege;
      var laufend = "";
      if (au) {
        laufend = au.filter(function (e) { var s = STUFE[e.stufe]; return s ? s.lauf === true : false; }).map(function (e) { return self._lauf(e, l); }).join("");
      } else if (l.aktiv) {
        laufend = this._lauf({ titel: l.titel, stufe: l.z === "liest" ? "lesen" : (l.z === "verarbeitet" ? "erkennen" : "hochladen"), prozent: l.prozent, rest_min: l.rest, art: "" }, l);
      }

      // Werte
      var w = [];
      if (!l.aus) {
        var lw = { leer: "leer", offen: "Fach offen", disc: "Disc drin", nicht_bereit: "liest ein", haengt: "hängt", unbekannt: "?" }[l.laufwerk] || null;
        if (lw) w.push(`<div class="wt${l.laufwerk === "haengt" ? " rot" : ""}"><span>Laufwerk</span><b>${esc(lw)}</b></div>`);
        w.push(`<div class="wt${l.pufferOk ? "" : " rot"}"><span>Zwischenspeicher</span><b>${l.pufferOk ? (l.puffer == null ? "–" : `${Math.round(l.puffer)} GB frei`) : "gestört"}</b></div>`);
        if (l.cpu != null) w.push(`<div class="wt${l.cpu >= 85 ? " warn" : ""}"><span>IPC</span><b>${Math.round(l.cpu)} °C</b></div>`);
        if (l.platte != null) w.push(`<div class="wt${l.platte >= 60 ? " warn" : ""}"><span>Platte</span><b>${Math.round(l.platte)} °C</b></div>`);
        if (l.pr) w.push(`<div class="wt warn"><span>Zum Prüfen</span><b>${l.pr}</b></div>`);
      }

      // Verlauf nach Tagen
      var verlauf = "";
      if (au ? au.length : false) {
        var fertigNamen = {};
        au.forEach(function (e) { if (["fehler", "abgebrochen", "leer"].indexOf(e.stufe) < 0) { fertigNamen[e.disc_name || ""] = Math.max(fertigNamen[e.disc_name || ""] || 0, e.id); } });
        var tage = {}, reihenfolge = [], ausgeblendet = 0;
        au.forEach(function (e) {
          var s = STUFE[e.stufe];
          if (s ? s.lauf === true : false) return; // steht oben gross
          var ersetzt = ["fehler", "abgebrochen", "leer"].indexOf(e.stufe) >= 0 ? (fertigNamen[e.disc_name || ""] || 0) > e.id : false;
          if (ersetzt ? !self._alteZeigen : false) { ausgeblendet = ausgeblendet - -1; return; }
          var d = datum(e.start);
          var tag = d ? tagText(d) : "ohne Datum";
          if (!tage[tag]) { tage[tag] = []; reihenfolge.push(tag); }
          tage[tag].push({ e: e, alt: ersetzt });
        });
        var heute = tagText(new Date());
        var gestern = tagText(new Date(Date.now() - 86400000));
        var zeigeTage = this._alleTage ? reihenfolge : reihenfolge.slice(0, 2);
        verlauf = zeigeTage.map(function (tag) {
          var liste = tage[tag];
          var fertige = liste.filter(function (x) { return x.e.stufe === "fertig"; });
          var gb = fertige.reduce(function (s, x) { return s - -(x.e.groesse_gb || 0); }, 0);
          var nameTag = tag === heute ? "Heute" : (tag === gestern ? "Gestern" : tag);
          var info = `${fertige.length} von ${liste.length} fertig${gb ? ` · ${Math.round(gb)} GB` : ""}`;
          return `<div class="tag"><span>${esc(nameTag)}</span><em>${esc(info)}</em></div>
            <div class="liste">${liste.map(function (x) { return self._eintrag(x.e, l, x.alt); }).join("")}</div>`;
        }).join("");
        var schalter = [];
        if (ausgeblendet ? true : this._alteZeigen) schalter.push(`<div class="mehr" data-b="_alt">${this._alteZeigen ? "Fehlversuche wieder ausblenden" : `${ausgeblendet} ältere Fehlversuche anzeigen (später geklappt)`}</div>`);
        if (reihenfolge.length > 2) schalter.push(`<div class="mehr" data-b="_mehrtage">${this._alleTage ? "Nur heute und gestern" : "Ältere Tage anzeigen (bis 7 Tage)"}</div>`);
        verlauf = `${verlauf}${schalter.join("")}`;
      } else if (l.fertig) {
        verlauf = `<div class="zeile"><span class="l">Zuletzt fertig</span><b>${esc(l.fertig.titel || "")}</b><span class="z">${esc([wann(l.fertig.zeit), zielKurz(l.fertig.ziel)].filter(Boolean).join(" · "))}</span></div>
          <div class="hinweis">Den ganzen Verlauf zeigt die Karte, sobald sie den IPC direkt erreicht (nur im Heimnetz oder über WireGuard).</div>`;
      }

      var zeile = function (lbl, text, zeit, rot) {
        return `<div class="zeile${rot ? " rot" : ""}"><span class="l">${esc(lbl)}</span><b>${esc(text)}</b>${zeit ? `<span class="z">${esc(zeit)}</span>` : ""}</div>`;
      };
      var z = [];
      if (l.fehlerInfo ? (!l.fehler ? jung(l.fehlerInfo.zeit, 3) : false) : false) z.push(zeile("Letzter Fehler", this._meldungText(l.fehlerInfo.text), wann(l.fehlerInfo.zeit), true));

      var kn = function (name, text, icon, klasse) {
        var k = ["kn"].concat(klasse ? klasse.split(" ") : []);
        if (self._frage === name) { k.push("frage"); text = "Sicher? Nochmal tippen"; }
        if (self._laeuft ? true : l.aus) k.push("zu");
        return `<div class="${k.join(" ")}" data-b="${name}"><ha-icon icon="${icon}"></ha-icon>${esc(text)}</div>`;
      };
      var link = function (text, url, icon) { return `<div class="kn kl" data-url="${url}"><ha-icon icon="${icon}"></ha-icon>${esc(text)}</div>`; };
      var haupt = l.z === "liest"
        ? `<div class="reihe">${kn("auswerfen", "Auswerfen", "mdi:eject", "haupt")}${kn("rip_abbrechen", "Abbrechen", "mdi:stop", "rot")}</div>`
        : `<div class="reihe">${kn("auswerfen", "Auswerfen", "mdi:eject", "haupt")}${kn("einfahren", "Fach zu", "mdi:tray-arrow-down", "")}</div>`;
      var antw = this._antwort ? `<div class="antwort${this._antwortOk ? "" : " rot"}">${esc(this._antwort)}</div>` : "";
      var wart = `<div class="wartung${this._wartung ? " offen" : ""}" data-b="_wartung"><span>Wartung</span>${SVG_PFEIL}</div>`;
      if (this._wartung) {
        wart = [wart,
          `<div class="reihe">${kn("usb_neu", "USB neu", "mdi:usb", "kl")}${kn("puffer_pruefen", "Puffer prüfen", "mdi:harddisk", "kl")}`,
          `${kn("neustart", "Neu starten", "mdi:restart", "kl")}${kn("herunterfahren", "Ausschalten", "mdi:power", "kl rot")}</div>`,
          `<div class="reihe">${link("ARM öffnen", "http://192.168.178.118:8080/", "mdi:open-in-new")}${link("Cockpit", "https://192.168.178.118:9090/", "mdi:console")}</div>`,
          `<div class="hinweis">USB neu und Puffer prüfen gehen nur, wenn gerade nichts gerippt wird. Nach dem Ausschalten kommt der IPC nur über den Stecker wieder an. ARM und Cockpit nur im Heimnetz.${l.live ? " Live vom IPC (alle 3 s)." : " Stand vom Sensor (alle 10 s)."}</div>`
        ].join("");
      }

      this.shadowRoot.innerHTML = `<style>${CSS_MENUE}</style><div class="w">
        <div class="${kl.join(" ")}"><div class="ic"><ha-icon icon="${l.z === "uebertraegt" ? "mdi:upload" : "mdi:disc"}"></ha-icon></div><div class="kt"><b>${esc(titel)}</b><span>${esc(unter)}</span></div></div>
        ${ampel}
        ${laufend}
        ${haupt}
        ${antw}
        ${w.length ? `<div class="werte">${w.join("")}</div>` : ""}
        ${z.join("")}
        ${verlauf}
        ${wart}
      </div>`;
      var neu = this.shadowRoot.querySelector(".w");
      if (neu) neu.scrollTop = scroll;
    }

    _befehl(n) {
      var self = this;
      if (!n) return;
      if (this._laeuft ? true : this._lage().aus) return;
      var mitFrage = ["rip_abbrechen", "neustart", "herunterfahren"].indexOf(n) >= 0;
      if (mitFrage ? this._frage !== n : false) {
        this._frage = n;
        haptic("warning");
        this._sig = ""; this._render();
        clearTimeout(this._frageT);
        this._frageT = setTimeout(function () { self._frage = null; self._sig = ""; self._render(); }, 4000);
        return;
      }
      clearTimeout(this._frageT);
      this._frage = null;
      this._laeuft = n;
      this._antwort = "Wird gesendet …";
      this._antwortOk = true;
      haptic("medium");
      this._sig = ""; this._render();
      this._hass.callService("shell_command", `station_${n}`, {}, undefined, false, true).then(function (r) {
        var res = (r ? r.response : null) || {};
        var zl = String(res.stdout || "").split("\n").map(function (x) { return x.trim(); }).filter(Boolean);
        var t = zl.length ? zl[zl.length - 1] : String(res.stderr || "").trim();
        var ok = res.returncode == null ? true : res.returncode === 0;
        self._fertig(t || (ok ? "Erledigt." : `Fehler (Code ${res.returncode})`), ok);
      }).catch(function (e) {
        self._fertig(`Hat nicht geklappt: ${(e ? (e.message || e.code) : "") || "keine Antwort"}`, false);
      });
    }

    _fertig(t, ok) {
      var self = this;
      this._laeuft = null;
      this._antwort = t;
      this._antwortOk = ok;
      haptic(ok ? "success" : "failure");
      this._sig = ""; this._render();
      this._holen();
      clearTimeout(this._antwortT);
      this._antwortT = setTimeout(function () { self._antwort = ""; self._sig = ""; self._render(); }, 12000);
    }
  }

  customElements.define(TAG, LukasStation);
  window.customCards = window.customCards || [];
  window.customCards.push({ type: TAG, name: "Lukas Ripping-Station", description: "Status, Verlauf und Knöpfe der Ripping-Station (Glas)" });
})();
