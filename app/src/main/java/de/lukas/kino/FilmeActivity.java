package de.lukas.kino;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Filmbereich von Lukas Kino – eigene Oberflaeche fuer die Filme auf dem Jellyfin-Server.
 * Grosses Hintergrundbild des ausgewaehlten Films, Titel und Infos links, darunter Reihen nur mit Postern.
 * Buehne 1920x1080 wie die Startseite, alles selbst gezeichnet (kein Leanback, wenig Speicher).
 */
public class FilmeActivity extends Activity {

    // ------------------------------------------------------------------ Masse der Buehne
    static final int PW = 196, PH = 294, ABSTAND = 26, SCHRITT = PW + ABSTAND;
    static final int LINKS = 110, REIHE_Y = 668, REIHEN_ABSTAND = 410;
    static final int GELB = 0xFFF2B705;

    /** Farben der Kategorien (wie die Sammlungsbilder in Plex und Jellyfin). */
    static final Map<String, Integer> FARBEN = new HashMap<>();

    static {
        FARBEN.put("Action", 0xFFE3261B);
        FARBEN.put("Komödien", 0xFFFFC61A);
        FARBEN.put("Horror", 0xFF4A0F5E);
        FARBEN.put("Thriller", 0xFF3B414B);
        FARBEN.put("Science-Fiction", 0xFF1592C4);
        FARBEN.put("Krimi", 0xFF1D438F);
        FARBEN.put("Drama", 0xFF8E2345);
        FARBEN.put("Abenteuer", 0xFF2E8B3F);
        FARBEN.put("Fantasy", 0xFF6A3DE8);
        FARBEN.put("Mystery", 0xFF2F6E73);
        FARBEN.put("Liebesfilme", 0xFFE8508D);
        FARBEN.put("Familie & Animation", 0xFF3E9BF0);
        FARBEN.put("Kriegsfilme", 0xFF5E6B2B);
        FARBEN.put("Historienfilme", 0xFF9C7442);
        FARBEN.put("Western", 0xFFE9A23B);
    }

    // ------------------------------------------------------------------ Daten

    static final class Reihe {
        final String titel;
        final String sammlungId; // Reihe einer Kategorie, wird erst bei Bedarf geladen
        final List<JSONObject> eintraege = new ArrayList<>();
        int fokus;
        float scroll; // animiert, in Eintraegen
        boolean geladen, laedt;

        Reihe(String titel, String sammlungId) {
            this.titel = titel;
            this.sammlungId = sammlungId;
        }
    }

    enum Ansicht { LADEN, ANMELDEN, FEHLER, LISTE, DETAIL }

    private Jellyfin jf;
    private Bilder bilder;
    private Buehne buehne;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService netz = Executors.newFixedThreadPool(2);

    private Ansicht ansicht = Ansicht.LADEN;
    private final List<Reihe> haupt = new ArrayList<>();
    private List<Reihe> reihen = haupt;
    private int aktReihe;
    private float reiheAnim;
    // Kategorie geoeffnet: eigene Reihen, Rueckweg merken
    private String kategorie;
    private int kategorieZurueck;

    private String qcCode, qcSecret, fehlerText;
    private int detailKnopf;
    private boolean ausPlayer;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        jf = Jellyfin.get(this);
        bilder = Bilder.get(jf);
        buehne = new Buehne(this);
        setContentView(buehne);
        verstecken();
        if (jf.angemeldet()) laden();
        else anmelden();
    }

    @Override
    protected void onResume() {
        super.onResume();
        verstecken();
        if (ausPlayer) {
            ausPlayer = false;
            nachDemFilm();
        }
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        netz.shutdownNow();
        super.onDestroy();
    }

    private void verstecken() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ------------------------------------------------------------------ Anmelden (Quick Connect)

    private void anmelden() {
        ansicht = Ansicht.ANMELDEN;
        qcCode = null;
        buehne.invalidate();
        netz.execute(() -> {
            try {
                JSONObject o = jf.quickConnectStart();
                String code = o.getString("Code"), secret = o.getString("Secret");
                h.post(() -> {
                    qcCode = code;
                    qcSecret = secret;
                    buehne.invalidate();
                    h.postDelayed(qcPruefen, 3000);
                });
            } catch (Exception e) {
                fehler("Jellyfin ist nicht erreichbar");
            }
        });
    }

    private final Runnable qcPruefen = new Runnable() {
        @Override
        public void run() {
            if (ansicht != Ansicht.ANMELDEN || qcSecret == null) return;
            final String s = qcSecret;
            netz.execute(() -> {
                try {
                    if (jf.quickConnectBestaetigt(s)) {
                        jf.quickConnectAbschliessen(s);
                        h.post(FilmeActivity.this::laden);
                    } else {
                        h.postDelayed(qcPruefen, 3000);
                    }
                } catch (Exception e) {
                    h.postDelayed(qcPruefen, 5000);
                }
            });
        }
    };

    private void fehler(String text) {
        h.post(() -> {
            fehlerText = text;
            ansicht = Ansicht.FEHLER;
            buehne.invalidate();
        });
    }

    // ------------------------------------------------------------------ Laden

    private void laden() {
        ansicht = Ansicht.LADEN;
        buehne.invalidate();
        netz.execute(() -> {
            try {
                JSONArray weiter = jf.weiterschauen();
                JSONArray neu = jf.neu();
                JSONArray samml = jf.sammlungen();
                List<Reihe> r = new ArrayList<>();
                if (weiter.length() > 0) r.add(reihe("Weiterschauen", weiter));
                r.add(reihe("Neu", neu));
                if (samml.length() > 0) {
                    Reihe k = reihe("Kategorien", samml);
                    r.add(k);
                    for (int i = 0; i < samml.length(); i++) {
                        JSONObject s = samml.getJSONObject(i);
                        r.add(new Reihe(s.optString("Name"), s.optString("Id")));
                    }
                }
                Reihe alle = new Reihe("Alle Filme", "@alle");
                r.add(alle);
                h.post(() -> {
                    haupt.clear();
                    haupt.addAll(r);
                    reihen = haupt;
                    aktReihe = 0;
                    reiheAnim = 0;
                    ansicht = Ansicht.LISTE;
                    nachladen();
                    fokusGeaendert();
                    buehne.invalidate();
                });
            } catch (Exception e) {
                if (!jf.angemeldet()) h.post(this::anmelden);
                else fehler("Jellyfin ist nicht erreichbar");
            }
        });
    }

    private static Reihe reihe(String titel, JSONArray a) {
        Reihe r = new Reihe(titel, null);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) r.eintraege.add(o);
        }
        r.geladen = true;
        return r;
    }

    /** Kategorie-Reihen in der Naehe des Fokus nachladen. */
    private void nachladen() {
        for (int i = Math.max(0, aktReihe - 1); i < Math.min(reihen.size(), aktReihe + 3); i++) {
            final Reihe r = reihen.get(i);
            if (r.geladen || r.laedt || r.sammlungId == null) continue;
            r.laedt = true;
            netz.execute(() -> {
                try {
                    JSONArray a = "@alle".equals(r.sammlungId) ? jf.alle() : jf.inhalt(r.sammlungId);
                    h.post(() -> {
                        for (int k = 0; k < a.length(); k++) r.eintraege.add(a.optJSONObject(k));
                        r.geladen = true;
                        r.laedt = false;
                        if (reihen.indexOf(r) == aktReihe) fokusGeaendert();
                        buehne.invalidate();
                    });
                } catch (Exception e) {
                    h.post(() -> r.laedt = false);
                }
            });
        }
        // Poster der sichtbaren Reihen vorladen
        for (int i = Math.max(0, aktReihe - 1); i < Math.min(reihen.size(), aktReihe + 2); i++) {
            Reihe r = reihen.get(i);
            int von = Math.max(0, r.fokus - 2), bis = Math.min(r.eintraege.size(), r.fokus + 10);
            for (int k = von; k < bis; k++) bilder.laden(posterUrl(r.eintraege.get(k)), (u, bm) -> buehne.invalidate());
        }
    }

    /** Nach dem Film: Weiterschauen und den Film selbst frisch holen. */
    private void nachDemFilm() {
        final JSONObject f = fokusFilm();
        netz.execute(() -> {
            try {
                JSONArray weiter = jf.weiterschauen();
                JSONObject frisch = f != null ? jf.film(f.optString("Id")) : null;
                h.post(() -> {
                    if (frisch != null) ersetzen(frisch);
                    Reihe w = haupt.isEmpty() ? null : haupt.get(0);
                    if (w != null && "Weiterschauen".equals(w.titel)) {
                        w.eintraege.clear();
                        for (int i = 0; i < weiter.length(); i++) w.eintraege.add(weiter.optJSONObject(i));
                        if (w.eintraege.isEmpty()) {
                            haupt.remove(0);
                            if (reihen == haupt && aktReihe > 0) aktReihe--;
                        }
                        w.fokus = Math.min(w.fokus, Math.max(0, w.eintraege.size() - 1));
                    } else if (weiter.length() > 0) {
                        Reihe neu = reihe("Weiterschauen", weiter);
                        haupt.add(0, neu);
                        if (reihen == haupt) aktReihe++;
                        reiheAnim = aktReihe;
                    }
                    fokusGeaendert();
                    buehne.invalidate();
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void ersetzen(JSONObject neu) {
        String id = neu.optString("Id");
        for (List<Reihe> l : java.util.Arrays.asList(haupt, reihen))
            for (Reihe r : l)
                for (int i = 0; i < r.eintraege.size(); i++)
                    if (id.equals(r.eintraege.get(i).optString("Id"))) r.eintraege.set(i, neu);
    }

    // ------------------------------------------------------------------ Fokus und Hintergrund

    private Reihe aktuelle() {
        return reihen.isEmpty() ? null : reihen.get(Math.max(0, Math.min(aktReihe, reihen.size() - 1)));
    }

    private JSONObject fokusEintrag() {
        Reihe r = aktuelle();
        if (r == null || r.eintraege.isEmpty()) return null;
        return r.eintraege.get(Math.max(0, Math.min(r.fokus, r.eintraege.size() - 1)));
    }

    private JSONObject fokusFilm() {
        JSONObject e = fokusEintrag();
        return e != null && "Movie".equals(e.optString("Type")) ? e : null;
    }

    // Hintergrund: aktuelles und vorheriges Bild fuer die Ueberblendung
    private Bitmap bildAlt, bildNeu, logo;
    private int farbeAlt, farbeNeu; // Kategorie-Farbe statt Bild
    private long ueberblendStart;
    private String bildUrl, logoUrl;

    private final Runnable hintergrundLaden = () -> {
        JSONObject e = fokusEintrag();
        if (e == null) return;
        if ("BoxSet".equals(e.optString("Type"))) {
            Integer f = FARBEN.get(e.optString("Name"));
            setzeHintergrund(null, f != null ? f : 0xFF444444, null);
            return;
        }
        String id = e.optString("Id");
        String tag = Jellyfin.hintergrundTag(e);
        final String url = tag != null ? Jellyfin.hintergrund(id, tag) : Jellyfin.poster(id, Jellyfin.tag(e, "Primary"), 1080);
        String lt = Jellyfin.tag(e, "Logo");
        final String lurl = lt != null ? Jellyfin.logo(id, lt) : null;
        logoUrl = lurl;
        if (lurl == null) logo = null;
        else bilder.laden(lurl, (u, bm) -> {
            if (u.equals(logoUrl)) {
                logo = bm;
                buehne.invalidate();
            }
        });
        bildUrl = url;
        bilder.laden(url, (u, bm) -> {
            if (u.equals(bildUrl)) setzeHintergrund(bm, 0, u);
        });
    };

    private void setzeHintergrund(Bitmap b, int farbe, String url) {
        if (b == bildNeu && farbe == farbeNeu) return;
        bildAlt = bildNeu;
        farbeAlt = farbeNeu;
        bildNeu = b;
        farbeNeu = farbe;
        ueberblendStart = SystemClock.uptimeMillis();
        buehne.invalidate();
    }

    private void fokusGeaendert() {
        h.removeCallbacks(hintergrundLaden);
        JSONObject e = fokusEintrag();
        // Logo sofort, wenn schon im Speicher; sonst nach kurzer Pause (schnelles Durchblaettern laedt nichts)
        if (e != null) {
            String lt = Jellyfin.tag(e, "Logo");
            String lurl = lt != null ? Jellyfin.logo(e.optString("Id"), lt) : null;
            logoUrl = lurl;
            logo = lurl != null ? bilder.sofort(lurl) : null;
        }
        h.postDelayed(hintergrundLaden, 220);
        buehne.invalidate();
    }

    // ------------------------------------------------------------------ Tasten

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(ev);
        int k = ev.getKeyCode();
        boolean zurueck = k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE;
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;

        boolean steuer = zurueck || ok || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        // Lautstaerke, Quellen, Einstellungen usw. gehen normal weiter
        if (!steuer) return super.dispatchKeyEvent(ev);

        switch (ansicht) {
            case ANMELDEN:
            case LADEN:
                if (zurueck) finish();
                return true;
            case FEHLER:
                if (zurueck) finish();
                else if (ok) {
                    if (jf.angemeldet()) laden();
                    else anmelden();
                }
                return true;
            case DETAIL:
                return detailTaste(k, ok, zurueck);
            case LISTE:
            default:
                return listeTaste(k, ok, zurueck, ev.getRepeatCount());
        }
    }

    private boolean listeTaste(int k, boolean ok, boolean zurueck, int wiederholung) {
        Reihe r = aktuelle();
        if (zurueck) {
            if (kategorie != null) {
                kategorieSchliessen();
            } else if (aktReihe > 0 || (r != null && r.fokus > 0)) {
                if (r != null) r.fokus = 0;
                aktReihe = 0;
                Reihe erste = aktuelle();
                if (erste != null) erste.fokus = 0;
                fokusGeaendert();
            } else {
                finish();
            }
            return true;
        }
        if (r == null) return true;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (r.fokus > 0) {
                    r.fokus -= wiederholung > 6 ? Math.min(r.fokus, 3) : 1;
                    fokusGeaendert();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (r.fokus < r.eintraege.size() - 1) {
                    r.fokus = Math.min(r.eintraege.size() - 1, r.fokus + (wiederholung > 6 ? 3 : 1));
                    fokusGeaendert();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (aktReihe > 0) {
                    aktReihe--;
                    nachladen();
                    fokusGeaendert();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (aktReihe < reihen.size() - 1) {
                    aktReihe++;
                    nachladen();
                    fokusGeaendert();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: {
                JSONObject f = fokusFilm();
                if (f != null) abspielen(f, true);
                return true;
            }
            default:
                if (ok) {
                    JSONObject e = fokusEintrag();
                    if (e == null) return true;
                    if ("BoxSet".equals(e.optString("Type"))) kategorieOeffnen(e);
                    else {
                        ansicht = Ansicht.DETAIL;
                        detailKnopf = 0;
                        buehne.invalidate();
                    }
                    return true;
                }
                return true;
        }
    }

    private boolean detailTaste(int k, boolean ok, boolean zurueck) {
        JSONObject f = fokusFilm();
        int knoepfe = f != null && Jellyfin.position(f) > 0 ? 2 : 1;
        if (zurueck) {
            ansicht = Ansicht.LISTE;
        } else if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
            detailKnopf = Math.max(0, detailKnopf - 1);
        } else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            detailKnopf = Math.min(knoepfe - 1, detailKnopf + 1);
        } else if (ok || k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (f != null) abspielen(f, detailKnopf == 0);
        }
        buehne.invalidate();
        return true;
    }

    private void kategorieOeffnen(JSONObject s) {
        final String id = s.optString("Id"), name = s.optString("Name");
        kategorie = name;
        kategorieZurueck = aktReihe;
        List<Reihe> neu = new ArrayList<>();
        Reihe lade = new Reihe(name, null);
        neu.add(lade);
        reihen = neu;
        aktReihe = 0;
        reiheAnim = 0;
        fokusGeaendert();
        netz.execute(() -> {
            try {
                JSONArray a = jf.inhalt(id);
                h.post(() -> {
                    if (!name.equals(kategorie)) return;
                    List<Reihe> l = new ArrayList<>();
                    int proReihe = 8;
                    for (int i = 0; i < a.length(); i += proReihe) {
                        Reihe r = new Reihe(i == 0 ? name : "", null);
                        for (int k = i; k < Math.min(a.length(), i + proReihe); k++) r.eintraege.add(a.optJSONObject(k));
                        r.geladen = true;
                        l.add(r);
                    }
                    reihen = l.isEmpty() ? neu : l;
                    aktReihe = 0;
                    nachladen();
                    fokusGeaendert();
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void kategorieSchliessen() {
        kategorie = null;
        reihen = haupt;
        aktReihe = Math.min(kategorieZurueck, haupt.size() - 1);
        reiheAnim = aktReihe;
        fokusGeaendert();
    }

    private void abspielen(JSONObject f, boolean fortsetzen) {
        String id = f.optString("Id");
        String lt = Jellyfin.tag(f, "Logo");
        ausPlayer = true;
        ansicht = Ansicht.LISTE;
        PlayerActivity.starten(this, id, f.optString("Name"), fortsetzen ? Jellyfin.position(f) : 0,
                lt != null ? Jellyfin.logo(id, lt) : null);
    }

    static String posterUrl(JSONObject e) {
        if (e == null) return null;
        String t = Jellyfin.tag(e, "Primary");
        return t != null ? Jellyfin.poster(e.optString("Id"), t, 330) : null;
    }

    // ------------------------------------------------------------------ Texte

    static String dauer(long ticks) {
        long min = ticks / 600_000_000L;
        if (min <= 0) return null;
        long std = min / 60, rest = min % 60;
        return std > 0 ? std + " Std. " + rest + " Min." : rest + " Min.";
    }

    static String fsk(String r) {
        if (r == null || r.isEmpty()) return null;
        String z = r.replaceAll("[^0-9]", "");
        if (z.isEmpty()) return null;
        return "FSK " + z;
    }

    // ------------------------------------------------------------------ Zeichnen

    private final class Buehne extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint titel, meta, text, reiheText, knopf, gross, klein;
        private final RectF r = new RectF();
        private final Matrix m = new Matrix();
        private long letzt = SystemClock.uptimeMillis();
        private final Map<Reihe, Float> scrolls = new HashMap<>();
        private float fokusZoom = 1f;
        private JSONObject letzterFokus;
        private float detailAnim;

        Buehne(Context c) {
            super(c);
            setBackgroundColor(0xFF07080B);
            titel = stift(c, 88, 800, 0xFFFFFFFF);
            titel.setLetterSpacing(-0.02f);
            meta = stift(c, 28, 600, 0xE6FFFFFF);
            text = stift(c, 28, 450, 0xC8FFFFFF);
            reiheText = stift(c, 30, 650, 0xE6FFFFFF);
            knopf = stift(c, 30, 650, 0xFFFFFFFF);
            gross = stift(c, 130, 800, 0xFFFFFFFF);
            klein = stift(c, 26, 500, 0xB3FFFFFF);
        }

        private TextPaint stift(Context c, float g, int w, int farbe) {
            TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            t.setTypeface(Schriften.gewicht(c, w));
            t.setTextSize(g);
            t.setColor(farbe);
            return t;
        }

        /** weiche Annaeherung, zeitbasiert (unabhaengig von der Bildrate) */
        private float naeher(float ist, float soll, float dt, float tau) {
            float k = 1f - (float) Math.exp(-dt / tau);
            float v = ist + (soll - ist) * k;
            return Math.abs(soll - v) < 0.001f ? soll : v;
        }

        @Override
        protected void onDraw(Canvas c) {
            long jetzt = SystemClock.uptimeMillis();
            float dt = Math.min(64, jetzt - letzt);
            letzt = jetzt;
            boolean weiter = false;

            float s = getWidth() / 1920f;
            c.save();
            c.scale(s, s);

            switch (ansicht) {
                case ANMELDEN:
                    anmeldenZeichnen(c);
                    break;
                case LADEN:
                    hintergrundLeer(c, 0xFF2A1F05);
                    break;
                case FEHLER:
                    hintergrundLeer(c, 0xFF3A0F0F);
                    mitte(c, fehlerText != null ? fehlerText : "Fehler", "OK = nochmal versuchen");
                    break;
                default:
                    weiter = listeZeichnen(c, dt);
            }
            c.restore();
            if (weiter) postInvalidateOnAnimation();
        }

        private void hintergrundLeer(Canvas c, int farbe) {
            p.setShader(new RadialGradient(1500, 160, 1100, farbe, 0xFF07080B, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, 1920, 1080, p);
            p.setShader(null);
        }

        private void mitte(Canvas c, String oben, String unten) {
            knopf.setTextAlign(Paint.Align.CENTER);
            knopf.setTextSize(44);
            c.drawText(oben, 960, 520, knopf);
            knopf.setTextSize(30);
            knopf.setTextAlign(Paint.Align.LEFT);
            if (unten != null) {
                klein.setTextAlign(Paint.Align.CENTER);
                c.drawText(unten, 960, 580, klein);
                klein.setTextAlign(Paint.Align.LEFT);
            }
        }

        private void anmeldenZeichnen(Canvas c) {
            hintergrundLeer(c, 0xFF3A2C05);
            glas(c, 510, 300, 1410, 780, 44);
            knopf.setTextAlign(Paint.Align.CENTER);
            knopf.setTextSize(40);
            c.drawText("Filme verbinden", 960, 400, knopf);
            if (qcCode != null) {
                String code = qcCode.length() == 6 ? qcCode.substring(0, 3) + " " + qcCode.substring(3) : qcCode;
                gross.setTextAlign(Paint.Align.CENTER);
                gross.setColor(GELB);
                c.drawText(code, 960, 590, gross);
                gross.setColor(0xFFFFFFFF);
                gross.setTextAlign(Paint.Align.LEFT);
                klein.setTextAlign(Paint.Align.CENTER);
                c.drawText("Jellyfin am Handy oder PC  →  Profil  →  Quick Connect", 960, 690, klein);
                klein.setTextAlign(Paint.Align.LEFT);
            }
            knopf.setTextSize(30);
            knopf.setTextAlign(Paint.Align.LEFT);
        }

        private void glas(Canvas c, float l, float t, float rr, float b, float rad) {
            r.set(l, t, rr, b);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x2EFFFFFF);
            c.drawRoundRect(r, rad, rad, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
            p.setColor(0x4DFFFFFF);
            c.drawRoundRect(r, rad, rad, p);
            p.setStyle(Paint.Style.FILL);
        }

        // -------------------------------------------------- Liste mit Hintergrund, Infos und Reihen

        private boolean listeZeichnen(Canvas c, float dt) {
            boolean weiter = false;
            // Ueberblendung
            float blend = Math.min(1f, (SystemClock.uptimeMillis() - ueberblendStart) / 450f);
            blend = Ani.EASE.getInterpolation(blend);
            if (blend < 1f) weiter = true;
            if (blend < 1f) bildMalen(c, bildAlt, farbeAlt, 1f);
            bildMalen(c, bildNeu, farbeNeu, blend);

            // Abdunkeln links und unten, damit Schrift und Poster gut lesbar sind
            boolean detail = ansicht == Ansicht.DETAIL;
            float zielDetail = detail ? 1f : 0f;
            detailAnim = naeher(detailAnim, zielDetail, dt, 70);
            if (detailAnim != zielDetail) weiter = true;
            p.setShader(new LinearGradient(0, 0, 1250, 0, new int[]{0xF2050608, 0x99050608, 0x00050608},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, 1920, 1080, p);
            p.setShader(new LinearGradient(0, 1080, 0, 420, new int[]{0xFA050608, 0xB3050608, 0x00050608},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 420, 1920, 1080, p);
            p.setShader(null);

            JSONObject e = fokusEintrag();
            if (e != letzterFokus) {
                letzterFokus = e;
                fokusZoom = 1f;
            }
            fokusZoom = naeher(fokusZoom, 1.1f, dt, 60);
            if (fokusZoom != 1.1f) weiter = true;

            // Reihen (fahren im Detail nach unten weg)
            reiheAnim = naeher(reiheAnim, aktReihe, dt, 85);
            if (reiheAnim != aktReihe) weiter = true;
            float wegY = detailAnim * 520;
            float reihenAlpha = 1f - detailAnim;
            if (reihenAlpha > 0.01f) {
                for (int i = 0; i < reihen.size(); i++) {
                    float y = REIHE_Y + (i - reiheAnim) * REIHEN_ABSTAND + wegY;
                    if (y < -PH - 100 || y > 1180) continue;
                    float a = reihenAlpha;
                    if (i < aktReihe) a *= Math.max(0f, Math.min(1f, (y - REIHE_Y + REIHEN_ABSTAND * 0.6f) / (REIHEN_ABSTAND * 0.6f)));
                    if (i > aktReihe) a *= 0.6f;
                    if (a <= 0.01f) continue;
                    weiter |= reiheZeichnen(c, reihen.get(i), y, i == aktReihe, a, dt);
                }
            }

            // Infos zum Fokus
            if (e != null) {
                if ("BoxSet".equals(e.optString("Type"))) kategorieInfo(c, e);
                else filmInfo(c, e, detail);
            }
            return weiter;
        }

        private void bildMalen(Canvas c, Bitmap b, int farbe, float alpha) {
            int al = Math.round(255 * alpha);
            if (b != null) {
                float sk = Math.max(1920f / b.getWidth(), 1080f / b.getHeight());
                m.setScale(sk, sk);
                m.postTranslate((1920 - b.getWidth() * sk) / 2f, (1080 - b.getHeight() * sk) / 2f);
                p.setAlpha(al);
                c.drawBitmap(b, m, p);
                p.setAlpha(255);
            } else if (farbe != 0) {
                int dunkel = mischen(farbe, 0xFF050608, 0.55f);
                p.setShader(new RadialGradient(1450, 300, 1300, new int[]{farbe, dunkel, 0xFF050608},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                p.setAlpha(al);
                c.drawRect(0, 0, 1920, 1080, p);
                p.setShader(null);
                p.setAlpha(255);
            }
        }

        private int mischen(int a, int b, float t) {
            int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
        }

        private boolean reiheZeichnen(Canvas c, Reihe rh, float y, boolean aktiv, float alpha, float dt) {
            boolean weiter = false;
            int al = Math.round(255 * alpha);
            if (!rh.titel.isEmpty()) {
                reiheText.setAlpha(Math.round((aktiv ? 230 : 170) * alpha));
                c.drawText(rh.titel, LINKS, y - 24, reiheText);
            }
            // Scroll: der ausgewaehlte Film steht links, am Anfang einfach ab der ersten Stelle
            float soll = rh.fokus;
            Float ist = scrolls.get(rh);
            float sc = ist == null ? soll : naeher(ist, soll, dt, 80);
            scrolls.put(rh, sc);
            if (sc != soll) weiter = true;
            if (!rh.geladen && rh.eintraege.isEmpty()) {
                // Platzhalter waehrend des Ladens
                for (int k = 0; k < 8; k++) {
                    r.set(LINKS + k * SCHRITT, y, LINKS + k * SCHRITT + PW, y + PH);
                    p.setColor(0x14FFFFFF);
                    p.setAlpha(Math.round(0x14 * alpha));
                    c.drawRoundRect(r, 18, 18, p);
                }
                return weiter;
            }
            int erster = Math.max(0, (int) Math.floor(sc) - 1);
            int letzter = Math.min(rh.eintraege.size() - 1, (int) Math.ceil(sc) + 9);
            // erst die anderen, dann der Fokus obendrauf
            for (int k = erster; k <= letzter; k++) {
                if (aktiv && k == rh.fokus) continue;
                posterZeichnen(c, rh.eintraege.get(k), LINKS + (k - sc) * SCHRITT, y, 1f, al, false);
            }
            if (rh.fokus >= erster && rh.fokus <= letzter && aktiv) {
                posterZeichnen(c, rh.eintraege.get(rh.fokus), LINKS + (rh.fokus - sc) * SCHRITT, y,
                        fokusZoom, al, true);
            }
            return weiter;
        }

        private void posterZeichnen(Canvas c, JSONObject e, float x, float y, float zoom, int al, boolean fokus) {
            if (x > 1920 || x + PW < -40) return;
            float cx = x + PW / 2f, cy = y + PH / 2f;
            float w = PW * zoom, hh = PH * zoom;
            r.set(cx - w / 2, cy - hh / 2, cx + w / 2, cy + hh / 2);
            float rad = 18 * zoom;
            // Schatten
            if (fokus) {
                p.setColor(0xFF000000);
                p.setAlpha(al);
                p.setShadowLayer(Ani.schatten(50), 0, 22, Ani.farbe(0x000000, 0.6f * al / 255f));
                c.drawRoundRect(r, rad, rad, p);
                p.clearShadowLayer();
            }
            String url = posterUrl(e);
            Bitmap b = url != null ? bilder.sofort(url) : null;
            if (b == null && url != null && !bilder.fehlgeschlagen(url)) bilder.laden(url, (u, bm) -> invalidate());
            if (b != null) {
                BitmapShader sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                float sk = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
                m.setScale(sk, sk);
                m.postTranslate(r.left + (r.width() - b.getWidth() * sk) / 2f, r.top + (r.height() - b.getHeight() * sk) / 2f);
                sh.setLocalMatrix(m);
                p.setShader(sh);
                p.setAlpha(al);
                c.drawRoundRect(r, rad, rad, p);
                p.setShader(null);
            } else {
                // ohne Bild: Glas mit Titel
                p.setColor(0x26FFFFFF);
                p.setAlpha(Math.round(0x26 * al / 255f));
                c.drawRoundRect(r, rad, rad, p);
                klein.setAlpha(al);
                StaticLayout sl = umbruch(e.optString("Name"), klein, (int) (r.width() - 32), 4);
                c.save();
                c.translate(r.left + 16, r.top + 20);
                sl.draw(c);
                c.restore();
                klein.setAlpha(0xB3);
            }
            // Fortschritt angefangener Filme
            float f = Jellyfin.fortschritt(e);
            if (f > 0.01f && f < 0.97f && !Jellyfin.gesehen(e)) {
                float l = r.left + 14 * zoom, rr = r.right - 14 * zoom, by = r.bottom - 16 * zoom;
                RectF bar = new RectF(l, by - 6 * zoom, rr, by);
                p.setColor(0x99000000);
                p.setAlpha(Math.round(0x99 * al / 255f));
                c.drawRoundRect(bar, 3 * zoom, 3 * zoom, p);
                bar.right = l + (rr - l) * f;
                p.setColor(GELB);
                p.setAlpha(al);
                c.drawRoundRect(bar, 3 * zoom, 3 * zoom, p);
            }
            // gesehen: kleiner Haken
            if (Jellyfin.gesehen(e)) {
                float cx2 = r.right - 26 * zoom, cy2 = r.top + 26 * zoom;
                p.setColor(0xD9000000);
                p.setAlpha(Math.round(0xD9 * al / 255f));
                c.drawCircle(cx2, cy2, 15 * zoom, p);
                p.setColor(GELB);
                p.setAlpha(al);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3.2f * zoom);
                p.setStrokeCap(Paint.Cap.ROUND);
                c.drawLine(cx2 - 6 * zoom, cy2, cx2 - 1.5f * zoom, cy2 + 5 * zoom, p);
                c.drawLine(cx2 - 1.5f * zoom, cy2 + 5 * zoom, cx2 + 7 * zoom, cy2 - 5 * zoom, p);
                p.setStyle(Paint.Style.FILL);
            }
            p.setAlpha(255);
        }

        private StaticLayout umbruch(String t, TextPaint tp, int breite, int zeilen) {
            return StaticLayout.Builder.obtain(t, 0, t.length(), tp, Math.max(10, breite))
                    .setMaxLines(zeilen)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0, 1.12f)
                    .build();
        }

        // -------------------------------------------------- Infos

        private void filmInfo(Canvas c, JSONObject e, boolean detail) {
            float y = 150 - detailAnim * 20;
            // Titel: Logo, sonst Schrift
            if (logo != null) {
                float maxW = 640, maxH = 200;
                float sk = Math.min(maxW / logo.getWidth(), maxH / logo.getHeight());
                float w = logo.getWidth() * sk, hh = logo.getHeight() * sk;
                r.set(LINKS, y + (maxH - hh), LINKS + w, y + maxH);
                c.drawBitmap(logo, null, r, p);
                y += maxH + 46;
            } else {
                titel.setShadowLayer(Ani.schatten(24), 0, 3, 0x80000000);
                StaticLayout sl = umbruch(e.optString("Name"), titel, 1000, 2);
                c.save();
                c.translate(LINKS, y + 200 - sl.getHeight());
                sl.draw(c);
                c.restore();
                titel.clearShadowLayer();
                y += 246;
            }

            // Infozeile: Jahr · Dauer · FSK · Bewertung · Genres
            float x = LINKS;
            List<String> teile = new ArrayList<>();
            int jahr = e.optInt("ProductionYear", 0);
            if (jahr > 0) teile.add(String.valueOf(jahr));
            String d = dauer(e.optLong("RunTimeTicks", 0));
            if (d != null) teile.add(d);
            for (int i = 0; i < teile.size(); i++) {
                String t = (i > 0 ? "·  " : "") + teile.get(i) + "  ";
                c.drawText(t, x, y, meta);
                x += meta.measureText(t);
            }
            String f = fsk(e.optString("OfficialRating", null));
            if (f != null) {
                float w = meta.measureText(f) + 24;
                r.set(x, y - 30, x + w, y + 10);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f);
                p.setColor(0xB3FFFFFF);
                c.drawRoundRect(r, 9, 9, p);
                p.setStyle(Paint.Style.FILL);
                c.drawText(f, x + 12, y, meta);
                x += w + 16;
            }
            double bew = e.optDouble("CommunityRating", 0);
            if (bew > 0) {
                String t = "★ " + String.format(Locale.GERMAN, "%.1f", bew) + "  ";
                meta.setColor(GELB);
                c.drawText(t, x, y, meta);
                meta.setColor(0xE6FFFFFF);
                x += meta.measureText(t);
            }
            JSONArray g = e.optJSONArray("Genres");
            if (g != null && g.length() > 0) {
                StringBuilder sb = new StringBuilder("·  ");
                for (int i = 0; i < Math.min(2, g.length()); i++) sb.append(i > 0 ? ", " : "").append(g.optString(i));
                c.drawText(sb.toString(), x, y, meta);
            }
            y += 56;

            // Angefangen: Rest-Zeit
            long pos = Jellyfin.position(e), ges = e.optLong("RunTimeTicks", 0);
            if (pos > 0 && ges > pos && !Jellyfin.gesehen(e)) {
                float bw = 220;
                RectF bar = new RectF(LINKS, y - 14, LINKS + bw, y - 7);
                p.setColor(0x4DFFFFFF);
                c.drawRoundRect(bar, 4, 4, p);
                bar.right = LINKS + bw * Jellyfin.fortschritt(e);
                p.setColor(GELB);
                c.drawRoundRect(bar, 4, 4, p);
                String rest = "noch " + dauer(ges - pos);
                c.drawText(rest, LINKS + bw + 20, y, meta);
                y += 50;
            }

            // Inhalt
            String inhalt = e.optString("Overview", "");
            if (!inhalt.isEmpty()) {
                StaticLayout sl = umbruch(inhalt, text, 880, detail ? 8 : 3);
                c.save();
                c.translate(LINKS, y - 22);
                text.setAlpha(Math.round(200 + 40 * detailAnim));
                sl.draw(c);
                c.restore();
                y += sl.getHeight() + 30;
            }

            if (detailAnim > 0.01f) knoepfe(c, e, Math.max(y + 40, 780), detailAnim);
        }

        private void knoepfe(Canvas c, JSONObject e, float y, float a) {
            long pos = Jellyfin.position(e);
            String[] k = pos > 0 ? new String[]{"Fortsetzen  ·  " + PlayerActivity.zeit(pos / 10_000L), "Von vorne"}
                    : new String[]{"Abspielen"};
            float x = LINKS;
            for (int i = 0; i < k.length; i++) {
                boolean f = i == detailKnopf;
                float w = knopf.measureText(k[i]) + 120;
                r.set(x, y, x + w, y + 84);
                if (f) {
                    p.setColor(0xFFFFFFFF);
                    p.setAlpha(Math.round(255 * a));
                    p.setShadowLayer(Ani.schatten(30), 0, 10, Ani.farbe(0x000000, 0.45f * a));
                    c.drawRoundRect(r, 42, 42, p);
                    p.clearShadowLayer();
                } else {
                    p.setColor(0x33FFFFFF);
                    p.setAlpha(Math.round(0x33 * a));
                    c.drawRoundRect(r, 42, 42, p);
                }
                int farbe = f ? 0xFF111317 : 0xFFFFFFFF;
                // Play-Dreieck
                p.setColor(farbe);
                p.setAlpha(Math.round(255 * a));
                android.graphics.Path d = new android.graphics.Path();
                float tx = x + 42, ty = y + 42;
                if (i == 0 || pos == 0) {
                    d.moveTo(tx - 9, ty - 13);
                    d.lineTo(tx + 13, ty);
                    d.lineTo(tx - 9, ty + 13);
                    d.close();
                    c.drawPath(d, p);
                } else {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(3.5f);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    c.drawArc(new RectF(tx - 13, ty - 13, tx + 13, ty + 13), -60, 300, false, p);
                    p.setStyle(Paint.Style.FILL);
                    d.moveTo(tx + 4, ty - 19);
                    d.lineTo(tx + 13, ty - 12);
                    d.lineTo(tx + 3, ty - 6);
                    d.close();
                    c.drawPath(d, p);
                }
                knopf.setColor(farbe);
                knopf.setAlpha(Math.round(255 * a));
                c.drawText(k[i], x + 74, y + 53, knopf);
                knopf.setColor(0xFFFFFFFF);
                x += w + 24;
            }
            p.setAlpha(255);
        }

        private void kategorieInfo(Canvas c, JSONObject e) {
            String name = e.optString("Name");
            gross.setShadowLayer(Ani.schatten(30), 0, 4, 0x66000000);
            c.drawText(name, LINKS - 6, 330, gross);
            gross.clearShadowLayer();
            int n = e.optInt("ChildCount", 0);
            if (n > 0) c.drawText(n == 1 ? "1 Film" : n + " Filme", LINKS, 400, meta);
        }
    }
}
