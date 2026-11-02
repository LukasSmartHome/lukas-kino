package de.lukas.kino;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kino-Bereich von Lukas Kino: eigene Oberflaeche fuer Filme, Serien und Fotos auf dem Jellyfin-Server.
 * Grosses Hintergrundbild, Infos links, Reihen nur mit Bildern, Glas-Seitenleiste (Suche, Filme, Serien, Fotos),
 * Suche mit Bildschirmtastatur und Tippfehler-Toleranz, Foto-Vollbild mit Diashow.
 * Buehne 1920x1080 wie die Startseite, alles selbst gezeichnet (schnell, wenig Speicher).
 */
public class FilmeActivity extends Activity {

    // ------------------------------------------------------------------ Masse der Buehne
    static final int PW = 196, PH = 294;          // Poster 2:3
    static final int QW = 352, QH = 198;          // Querformat 16:9 (Folgen, Fotos, Alben)
    static final int ABSTAND = 26, LINKS = 110, REIHE_Y = 668;
    static final int GELB = 0xFFF2B705;
    static final int POSTER = 0, QUER = 1;

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
        final String quelle; // wird erst bei Bedarf geladen (samml:, @alle, staffel:, ordner:)
        final int form;
        final List<JSONObject> eintraege = new ArrayList<>();
        int fokus;
        boolean geladen, laedt;

        Reihe(String titel, String quelle, int form) {
            this.titel = titel;
            this.quelle = quelle;
            this.form = form;
        }

        float abstand() {
            return form == QUER ? QH + 118 : PH + 116;
        }
    }

    static final class Seite {
        final String titel;
        final List<Reihe> reihen = new ArrayList<>();
        int akt;
        float anim;
        String leer; // Text, wenn nichts da ist
        JSONObject kopf; // Serie oder Ordner, zu dem die Seite gehoert

        Seite(String titel) {
            this.titel = titel;
        }
    }

    enum Bereich { FILME, SERIEN, FOTOS }

    enum Ansicht { LADEN, ANMELDEN, FEHLER, LISTE, DETAIL, SUCHE, FOTO }

    private Jellyfin jf;
    private Bilder bilder;
    private Buehne buehne;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService netz = Executors.newFixedThreadPool(2);
    // Titelbilder der Foto-Ordner eigen, damit sie das Öffnen von Ordnern und Reihen nicht ausbremsen
    private final ExecutorService bildSuche = Executors.newFixedThreadPool(2);
    private SharedPreferences titelSpeicher;

    private Ansicht ansicht = Ansicht.LADEN;
    private Bereich bereich = Bereich.FILME;
    private final Map<Bereich, Seite> startseiten = new EnumMap<>(Bereich.class);
    private final Deque<Seite> stapel = new ArrayDeque<>();
    private Seite seite = new Seite("");
    private String filmeId, serienId, fotosId;

    private String qcCode, qcSecret, fehlerText;
    private int detailKnopf;
    private JSONObject detailEintrag;
    private Ansicht detailZurueck = Ansicht.LISTE;
    private boolean ausPlayer;

    // Seitenleiste
    private boolean leiste;
    private int leistePos;
    private static final String[] LEISTE = {"Suche", "Filme", "Serien", "Fotos"};

    // Suche
    private final StringBuilder suchText = new StringBuilder();
    private List<JSONObject> suchIndex;
    private final List<JSONObject> treffer = new ArrayList<>();
    private boolean suchIndexLaedt;
    private int tasteZeile, tasteSpalte, trefferFokus;
    private boolean inTreffern;
    private Ansicht sucheZurueck = Ansicht.LISTE;
    static final String[][] TASTEN = {
            {"a", "b", "c", "d", "e", "f"}, {"g", "h", "i", "j", "k", "l"}, {"m", "n", "o", "p", "q", "r"},
            {"s", "t", "u", "v", "w", "x"}, {"y", "z", "ä", "ö", "ü", "ß"}, {"1", "2", "3", "4", "5", "6"},
            {"7", "8", "9", "0", "-", "."}};
    static final int SUCH_SPALTEN = 5;

    // Foto-Vollbild
    private final List<JSONObject> fotos = new ArrayList<>();
    private int fotoIdx;
    private boolean diashow, fotoInfo;
    private Reihe fotoReihe;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        jf = Jellyfin.get(this);
        bilder = Bilder.get(jf);
        titelSpeicher = getSharedPreferences("lukaskino_titelbilder", MODE_PRIVATE);
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
        bildSuche.shutdownNow();
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
            final Runnable ich = this;
            netz.execute(() -> {
                try {
                    if (jf.quickConnectBestaetigt(s)) {
                        jf.quickConnectAbschliessen(s);
                        h.post(FilmeActivity.this::laden);
                    } else {
                        h.postDelayed(ich, 3000);
                    }
                } catch (Exception e) {
                    h.postDelayed(ich, 5000);
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

    // ------------------------------------------------------------------ Laden der Bereiche

    private void laden() {
        ansicht = Ansicht.LADEN;
        buehne.invalidate();
        netz.execute(() -> {
            try {
                JSONArray views = jf.bibliotheken();
                for (int i = 0; i < views.length(); i++) {
                    JSONObject v = views.getJSONObject(i);
                    String typ = v.optString("CollectionType");
                    if ("movies".equals(typ)) filmeId = v.optString("Id");
                    else if ("tvshows".equals(typ)) serienId = v.optString("Id");
                    else if ("homevideos".equals(typ) || "photos".equals(typ)) fotosId = v.optString("Id");
                }
                Seite s = filmeSeite();
                h.post(() -> zeigeStart(Bereich.FILME, s));
            } catch (Exception e) {
                if (!jf.angemeldet()) h.post(this::anmelden);
                else fehler("Jellyfin ist nicht erreichbar");
            }
        });
    }

    /** Bereich wechseln (Seitenleiste). Startseiten werden einmal geladen und dann gemerkt. */
    private void bereichOeffnen(Bereich b) {
        stapel.clear();
        Seite fertig = startseiten.get(b);
        if (fertig != null) {
            bereich = b;
            seite = fertig;
            ansicht = Ansicht.LISTE;
            fokusGeaendert();
            return;
        }
        ansicht = Ansicht.LADEN;
        bereich = b;
        buehne.invalidate();
        netz.execute(() -> {
            try {
                Seite s = b == Bereich.SERIEN ? serienSeite() : b == Bereich.FOTOS ? fotosSeite() : filmeSeite();
                h.post(() -> zeigeStart(b, s));
            } catch (Exception e) {
                fehler("Jellyfin ist nicht erreichbar");
            }
        });
    }

    private void zeigeStart(Bereich b, Seite s) {
        startseiten.put(b, s);
        if (bereich != b) return;
        seite = s;
        ansicht = Ansicht.LISTE;
        nachladen();
        fokusGeaendert();
    }

    private Seite filmeSeite() throws Exception {
        Seite s = new Seite("Filme");
        JSONArray weiter = jf.weiterschauen();
        JSONArray neu = jf.neu();
        JSONArray samml = jf.sammlungen();
        if (weiter.length() > 0) s.reihen.add(reihe("Weiterschauen", weiter, POSTER));
        s.reihen.add(reihe("Neu", neu, POSTER));
        if (samml.length() > 0) {
            s.reihen.add(reihe("Kategorien", samml, POSTER));
            for (int i = 0; i < samml.length(); i++) {
                JSONObject k = samml.getJSONObject(i);
                s.reihen.add(new Reihe(k.optString("Name"), "samml:" + k.optString("Id"), POSTER));
            }
        }
        s.reihen.add(new Reihe("Alle Filme", "@alle", POSTER));
        return s;
    }

    private Seite serienSeite() throws Exception {
        Seite s = new Seite("Serien");
        JSONArray weiter = jf.serienWeiterschauen();
        JSONArray naechste = jf.naechsteFolgen();
        JSONArray neu = jf.neueSerien();
        JSONArray alle = jf.serien();
        if (weiter.length() > 0) s.reihen.add(reihe("Weiterschauen", weiter, QUER));
        if (naechste.length() > 0) s.reihen.add(reihe("Nächste Folgen", naechste, QUER));
        if (neu.length() > 0) s.reihen.add(reihe("Neu", neu, POSTER));
        if (alle.length() > 0) s.reihen.add(reihe("Alle Serien", alle, POSTER));
        if (s.reihen.isEmpty()) s.leer = "Noch keine Serien";
        return s;
    }

    private Seite fotosSeite() throws Exception {
        Seite s = new Seite("Fotos");
        if (fotosId == null) {
            s.leer = "Noch keine Fotos";
            return s;
        }
        // Die oberste Ebene ändert sich selten: aus dem Speicher sofort zeigen, im Hintergrund auffrischen
        JSONArray inhalt = null;
        String gemerkt = titelSpeicher.getString("@wurzel:" + fotosId, null);
        if (gemerkt != null) {
            try {
                inhalt = new JSONArray(gemerkt);
            } catch (Exception ignored) {
            }
        }
        final String fid = fotosId;
        if (inhalt == null) {
            inhalt = jf.fotoWurzel(fid);
            titelSpeicher.edit().putString("@wurzel:" + fid, inhalt.toString()).apply();
        } else {
            bildSuche.execute(() -> {
                try {
                    titelSpeicher.edit().putString("@wurzel:" + fid, jf.fotoWurzel(fid).toString()).apply();
                } catch (Exception ignored) {
                }
            });
        }
        List<JSONObject> jahre = new ArrayList<>(), tage = new ArrayList<>(), lose = new ArrayList<>();
        JSONObject urlaube = null;
        for (int i = 0; i < inhalt.length(); i++) {
            JSONObject o = inhalt.getJSONObject(i);
            String name = o.optString("Name").trim();
            if (!o.optBoolean("IsFolder", false)) lose.add(o);
            else if (name.toLowerCase(Locale.GERMAN).startsWith("urlaube")) urlaube = o;
            else if (name.matches("\\d{4}")) jahre.add(o);
            else tage.add(o);
        }
        Collections.reverse(jahre); // neueste Jahre zuerst
        if (urlaube != null) s.reihen.add(new Reihe("Urlaube", "ordner:" + urlaube.optString("Id"), QUER));
        if (!jahre.isEmpty()) s.reihen.add(reiheAus("Jahre", jahre, QUER));
        if (!tage.isEmpty()) s.reihen.add(reiheAus("Erinnerungen", tage, QUER));
        if (!lose.isEmpty()) s.reihen.add(reiheAus("Einzelne Bilder", lose, QUER));
        if (s.reihen.isEmpty()) s.leer = "Die Fotos werden gerade eingelesen";
        return s;
    }

    private static Reihe reihe(String titel, JSONArray a, int form) {
        Reihe r = new Reihe(titel, null, form);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) r.eintraege.add(o);
        }
        r.geladen = true;
        return r;
    }

    private static Reihe reiheAus(String titel, List<JSONObject> l, int form) {
        Reihe r = new Reihe(titel, null, form);
        r.eintraege.addAll(l);
        r.geladen = true;
        return r;
    }

    /** Reihen in der Naehe des Fokus nachladen. */
    private void nachladen() {
        final Seite s = seite;
        for (int i = Math.max(0, s.akt - 1); i < Math.min(s.reihen.size(), s.akt + 3); i++) {
            final Reihe r = s.reihen.get(i);
            if (r.geladen || r.laedt || r.quelle == null) continue;
            r.laedt = true;
            netz.execute(() -> {
                try {
                    JSONArray a = holen(r.quelle);
                    h.post(() -> {
                        for (int k = 0; k < a.length(); k++) r.eintraege.add(a.optJSONObject(k));
                        r.geladen = true;
                        r.laedt = false;
                        if (seite == s && s.reihen.indexOf(r) == s.akt) fokusGeaendert();
                        buehne.invalidate();
                    });
                } catch (Exception e) {
                    h.post(() -> r.laedt = false);
                }
            });
        }
        for (int i = Math.max(0, s.akt - 1); i < Math.min(s.reihen.size(), s.akt + 2); i++) {
            Reihe r = s.reihen.get(i);
            int von = Math.max(0, r.fokus - 2), bis = Math.min(r.eintraege.size(), r.fokus + 9);
            for (int k = von; k < bis; k++) bilder.laden(kartenUrl(r.eintraege.get(k), r.form), (u, bm) -> buehne.invalidate());
        }
    }

    private JSONArray holen(String quelle) throws Exception {
        if ("@alle".equals(quelle)) return jf.alle();
        if (quelle.startsWith("samml:")) return jf.inhalt(quelle.substring(6));
        if (quelle.startsWith("ordner:")) return jf.ordner(quelle.substring(7));
        if (quelle.startsWith("staffel:")) {
            String[] t = quelle.split(":");
            return jf.folgen(t[1], t[2]);
        }
        return new JSONArray();
    }

    /** Nach dem Film: Weiterschauen und den Film selbst frisch holen. */
    private void nachDemFilm() {
        final JSONObject f = detailEintrag != null ? detailEintrag : fokusEintrag();
        final Bereich b = bereich;
        netz.execute(() -> {
            try {
                JSONObject frisch = f != null ? jf.film(f.optString("Id")) : null;
                JSONArray weiter = b == Bereich.SERIEN ? jf.serienWeiterschauen() : jf.weiterschauen();
                h.post(() -> {
                    if (frisch != null) {
                        ersetzen(frisch);
                        if (detailEintrag != null && frisch.optString("Id").equals(detailEintrag.optString("Id")))
                            detailEintrag = frisch;
                    }
                    Seite start = startseiten.get(b);
                    if (start != null) {
                        Reihe w = start.reihen.isEmpty() ? null : start.reihen.get(0);
                        if (w != null && "Weiterschauen".equals(w.titel)) {
                            w.eintraege.clear();
                            for (int i = 0; i < weiter.length(); i++) w.eintraege.add(weiter.optJSONObject(i));
                            w.fokus = Math.min(w.fokus, Math.max(0, w.eintraege.size() - 1));
                            if (w.eintraege.isEmpty() && start.reihen.size() > 1) {
                                start.reihen.remove(0);
                                if (start.akt > 0) start.akt--;
                                start.anim = start.akt;
                            }
                        } else if (weiter.length() > 0) {
                            start.reihen.add(0, reihe("Weiterschauen", weiter, b == Bereich.SERIEN ? QUER : POSTER));
                            if (seite == start) {
                                start.akt++;
                                start.anim = start.akt;
                            }
                        }
                    }
                    fokusGeaendert();
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void ersetzen(JSONObject neu) {
        String id = neu.optString("Id");
        List<Seite> alle = new ArrayList<>(startseiten.values());
        alle.addAll(stapel);
        alle.add(seite);
        for (Seite s : alle)
            for (Reihe r : s.reihen)
                for (int i = 0; i < r.eintraege.size(); i++)
                    if (id.equals(r.eintraege.get(i).optString("Id"))) r.eintraege.set(i, neu);
    }

    // ------------------------------------------------------------------ Fokus und Hintergrund

    private Reihe aktuelle() {
        if (seite.reihen.isEmpty()) return null;
        return seite.reihen.get(Math.max(0, Math.min(seite.akt, seite.reihen.size() - 1)));
    }

    private JSONObject fokusEintrag() {
        Reihe r = aktuelle();
        if (r == null || r.eintraege.isEmpty()) return null;
        return r.eintraege.get(Math.max(0, Math.min(r.fokus, r.eintraege.size() - 1)));
    }

    static String typ(JSONObject e) {
        return e == null ? "" : e.optString("Type");
    }

    static boolean abspielbar(JSONObject e) {
        String t = typ(e);
        return "Movie".equals(t) || "Episode".equals(t);
    }

    /** Das Element, dessen Infos und Hintergrund gerade gezeigt werden. */
    private JSONObject heldEintrag() {
        if (ansicht == Ansicht.DETAIL && detailEintrag != null) return detailEintrag;
        if (ansicht == Ansicht.SUCHE) return inTreffern && trefferFokus < treffer.size() ? treffer.get(trefferFokus) : null;
        return fokusEintrag();
    }

    // Hintergrund: aktuelles und vorheriges Bild fuer die Ueberblendung, dazu eine verwischte Kopie fuers Glas
    private Bitmap bildAlt, bildNeu, logo, unscharf;
    private int farbeAlt, farbeNeu;
    private long ueberblendStart;
    private String bildUrl, logoUrl;
    private final Map<String, String> titelbilder = new HashMap<>(); // Ordner-Id -> Bild-URL
    private final Set<String> titelbildLaedt = new HashSet<>();

    private final Runnable hintergrundLaden = () -> {
        JSONObject e = heldEintrag();
        if (e == null) return;
        String t = typ(e);
        if ("BoxSet".equals(t)) {
            Integer f = FARBEN.get(e.optString("Name"));
            setzeHintergrund(null, f != null ? f : 0xFF444444);
            return;
        }
        final String url = hintergrundUrl(e);
        if (url == null) {
            setzeHintergrund(null, 0xFF1D2027);
            return;
        }
        bildUrl = url;
        bilder.laden(url, (u, bm) -> {
            if (u.equals(bildUrl) && bm != null) setzeHintergrund(bm, 0);
        });
    };

    private String hintergrundUrl(JSONObject e) {
        String t = typ(e), id = e.optString("Id");
        switch (t) {
            case "Episode": {
                JSONArray pt = e.optJSONArray("ParentBackdropImageTags");
                String pid = e.optString("ParentBackdropItemId", null);
                if (pt != null && pt.length() > 0 && pid != null) return Jellyfin.hintergrund(pid, pt.optString(0));
                String pr = Jellyfin.tag(e, "Primary");
                return pr != null ? Jellyfin.quer(id, pr, 1280) : null;
            }
            case "Photo":
            case "Video":
                return Jellyfin.quer(id, Jellyfin.tag(e, "Primary"), 1280);
            case "Folder":
            case "PhotoAlbum":
            case "CollectionFolder": {
                String pr = Jellyfin.tag(e, "Primary");
                if (pr != null) return Jellyfin.quer(id, pr, 1280);
                String tb = titelbilder.get(id);
                if (tb != null && !tb.isEmpty()) return tb.replace("fillWidth=" + QW * 2, "fillWidth=1280");
                return null;
            }
            default: {
                String tag = Jellyfin.hintergrundTag(e);
                if (tag != null) return Jellyfin.hintergrund(id, tag);
                String pr = Jellyfin.tag(e, "Primary");
                return pr != null ? Jellyfin.poster(id, pr, 1080) : null;
            }
        }
    }

    private String logoUrlFuer(JSONObject e) {
        if (e == null) return null;
        String lt = Jellyfin.tag(e, "Logo");
        if (lt != null) return Jellyfin.logo(e.optString("Id"), lt);
        String pid = e.optString("ParentLogoItemId", null), ptag = e.optString("ParentLogoImageTag", null);
        if (pid != null && ptag != null && !ptag.isEmpty()) return Jellyfin.logo(pid, ptag);
        return null;
    }

    private void setzeHintergrund(Bitmap b, int farbe) {
        if (b == bildNeu && farbe == farbeNeu) return;
        bildAlt = bildNeu;
        farbeAlt = farbeNeu;
        bildNeu = b;
        farbeNeu = farbe;
        ueberblendStart = SystemClock.uptimeMillis();
        final Bitmap quelle = b;
        if (quelle != null) {
            netz.execute(() -> {
                Bitmap u;
                try {
                    u = Glas.verwischen(quelle);
                } catch (Throwable t) {
                    u = null;
                }
                final Bitmap fertig = u;
                h.post(() -> {
                    if (bildNeu == quelle) {
                        unscharf = fertig;
                        buehne.invalidate();
                    }
                });
            });
        } else {
            unscharf = null;
        }
        buehne.invalidate();
    }

    private void fokusGeaendert() {
        h.removeCallbacks(hintergrundLaden);
        JSONObject e = heldEintrag();
        String lurl = logoUrlFuer(e);
        logoUrl = lurl;
        logo = lurl != null ? bilder.sofort(lurl) : null;
        if (lurl != null && logo == null) {
            bilder.laden(lurl, (u, bm) -> {
                if (u.equals(logoUrl)) {
                    logo = bm;
                    buehne.invalidate();
                }
            });
        }
        h.postDelayed(hintergrundLaden, 220);
        buehne.invalidate();
    }

    /** Bild einer Karte: Poster oder Querformat; Ordner ohne eigenes Bild bekommen das erste Foto. */
    String kartenUrl(JSONObject e, int form) {
        if (e == null) return null;
        String id = e.optString("Id"), t = typ(e);
        if (form == QUER) {
            if ("Folder".equals(t) || "PhotoAlbum".equals(t)) {
                String pr = Jellyfin.tag(e, "Primary");
                if (pr != null) return Jellyfin.quer(id, pr, QW * 2);
                String tb = titelbilder.get(id);
                if (tb == null) {
                    titelbildSuchen(id);
                    tb = titelbilder.get(id); // kann jetzt schon aus dem Speicher da sein
                }
                return tb == null || tb.isEmpty() ? null : tb;
            }
            String pr = Jellyfin.tag(e, "Primary");
            if (pr != null || "Photo".equals(t) || "Video".equals(t)) return Jellyfin.quer(id, pr, QW * 2);
            String tag = Jellyfin.hintergrundTag(e);
            return tag != null ? Jellyfin.hintergrund(id, tag) : null;
        }
        String pr = Jellyfin.tag(e, "Primary");
        if (pr != null) return Jellyfin.poster(id, pr, 330);
        if ("Episode".equals(t) || "Season".equals(t)) {
            String sid = e.optString("SeriesId", null), stag = e.optString("SeriesPrimaryImageTag", null);
            if (sid != null && stag != null) return Jellyfin.poster(sid, stag, 330);
        }
        return null;
    }

    private void titelbildSuchen(final String ordner) {
        if (titelbildLaedt.contains(ordner)) return;
        titelbildLaedt.add(ordner);
        // schon einmal gefunden: sofort aus dem Speicher (Fotos ändern sich in alten Ordnern praktisch nie)
        String gemerkt = titelSpeicher.getString(ordner, null);
        if (gemerkt != null && !gemerkt.isEmpty()) {
            titelbilder.put(ordner, gemerkt);
            return;
        }
        bildSuche.execute(() -> {
            String url = "";
            try {
                JSONObject f = jf.erstesFoto(ordner);
                if (f != null) url = Jellyfin.quer(f.optString("Id"), Jellyfin.tag(f, "Primary"), QW * 2);
            } catch (Exception ignored) {
            }
            final String fertig = url;
            if (!fertig.isEmpty()) titelSpeicher.edit().putString(ordner, fertig).apply();
            h.post(() -> {
                titelbilder.put(ordner, fertig);
                if (fertig.isEmpty()) h.postDelayed(() -> {
                    titelbilder.remove(ordner);
                    titelbildLaedt.remove(ordner);
                    buehne.invalidate();
                }, 90_000);
                buehne.invalidate();
                JSONObject e = heldEintrag();
                if (e != null && ordner.equals(e.optString("Id"))) fokusGeaendert();
            });
        });
    }

    // ------------------------------------------------------------------ Tasten

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(ev);
        int k = ev.getKeyCode();
        boolean zurueck = k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE;
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean pfeil = k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean medien = k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;

        // Tastatur (Handy-App, echte Tastatur) tippt direkt in die Suche
        if (ansicht == Ansicht.SUCHE && !leiste) {
            if (k == KeyEvent.KEYCODE_DEL) {
                loeschen();
                return true;
            }
            int u = ev.getUnicodeChar();
            if (u > 0 && !ok && !pfeil && (Character.isLetterOrDigit(u) || u == ' ' || u == '-')) {
                tippen(String.valueOf((char) u).toLowerCase(Locale.GERMAN));
                return true;
            }
        }
        if (!(zurueck || ok || pfeil || medien)) return super.dispatchKeyEvent(ev);

        if (leiste) return leisteTaste(k, ok, zurueck);
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
            case SUCHE:
                return sucheTaste(k, ok, zurueck);
            case FOTO:
                return fotoTaste(k, ok, zurueck);
            case LISTE:
            default:
                return listeTaste(k, ok, zurueck, ev.getRepeatCount());
        }
    }

    private void leisteAuf() {
        leiste = true;
        // ausgewählt ist, wo man gerade ist (Suche, Filme, Serien oder Fotos)
        leistePos = ansicht == Ansicht.SUCHE ? 0 : bereich.ordinal() + 1;
        buehne.leisteFokusY = -1; // Auswahl-Pille springt direkt dorthin statt von der alten Stelle zu gleiten
        buehne.invalidate();
    }

    private boolean leisteTaste(int k, boolean ok, boolean zurueck) {
        if (zurueck || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            leiste = false;
        } else if (k == KeyEvent.KEYCODE_DPAD_UP) {
            leistePos = Math.max(0, leistePos - 1);
        } else if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
            leistePos = Math.min(LEISTE.length - 1, leistePos + 1);
        } else if (ok) {
            leiste = false;
            if (leistePos == 0) sucheOeffnen();
            else bereichOeffnen(Bereich.values()[leistePos - 1]);
        }
        buehne.invalidate();
        return true;
    }

    private boolean listeTaste(int k, boolean ok, boolean zurueck, int wiederholung) {
        Reihe r = aktuelle();
        if (zurueck) {
            if (!stapel.isEmpty()) {
                seite = stapel.pop();
                fokusGeaendert();
            } else if (seite.akt > 0 || (r != null && r.fokus > 0)) {
                if (r != null) r.fokus = 0;
                seite.akt = 0;
                Reihe erste = aktuelle();
                if (erste != null) erste.fokus = 0;
                fokusGeaendert();
            } else if (bereich != Bereich.FILME) {
                bereichOeffnen(Bereich.FILME);
            } else {
                finish();
            }
            return true;
        }
        if (r == null) {
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) leisteAuf();
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (r.fokus > 0) {
                    r.fokus -= wiederholung > 6 ? Math.min(r.fokus, 3) : 1;
                    fokusGeaendert();
                } else if (wiederholung == 0) {
                    leisteAuf();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (r.fokus < r.eintraege.size() - 1) {
                    r.fokus = Math.min(r.eintraege.size() - 1, r.fokus + (wiederholung > 6 ? 3 : 1));
                    fokusGeaendert();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (seite.akt > 0) {
                    seite.akt--;
                    nachladen();
                    fokusGeaendert();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (seite.akt < seite.reihen.size() - 1) {
                    seite.akt++;
                    nachladen();
                    fokusGeaendert();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: {
                JSONObject f = fokusEintrag();
                if (abspielbar(f)) abspielen(f, true);
                return true;
            }
            default:
                if (ok) oeffnen(fokusEintrag(), r, Ansicht.LISTE);
                return true;
        }
    }

    /** OK auf einem Element – je nach Art. */
    private void oeffnen(JSONObject e, Reihe r, Ansicht von) {
        if (e == null) return;
        String t = typ(e);
        switch (t) {
            case "BoxSet":
                kategorieOeffnen(e);
                break;
            case "Series":
                serieOeffnen(e);
                break;
            case "Folder":
            case "PhotoAlbum":
            case "CollectionFolder":
                ordnerOeffnen(e);
                break;
            case "Photo":
                fotoOeffnen(e);
                break;
            case "Video":
                abspielen(e, true);
                break;
            default:
                detailEintrag = e;
                detailZurueck = von;
                ansicht = Ansicht.DETAIL;
                detailKnopf = 0;
                fokusGeaendert();
        }
    }

    private boolean detailTaste(int k, boolean ok, boolean zurueck) {
        JSONObject f = detailEintrag;
        int knoepfe = f != null && Jellyfin.position(f) > 0 ? 2 : 1;
        if (zurueck) {
            ansicht = detailZurueck;
            detailEintrag = null;
            fokusGeaendert();
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

    private void neueSeite(Seite s) {
        stapel.push(seite);
        seite = s;
        ansicht = Ansicht.LISTE;
        fokusGeaendert();
    }

    private void kategorieOeffnen(JSONObject s) {
        final String id = s.optString("Id"), name = s.optString("Name");
        final Seite neu = new Seite(name);
        neu.kopf = s;
        neu.reihen.add(new Reihe(name, null, POSTER));
        neueSeite(neu);
        netz.execute(() -> {
            try {
                JSONArray a = jf.inhalt(id);
                h.post(() -> {
                    neu.reihen.clear();
                    for (int i = 0; i < a.length(); i += 8) {
                        Reihe r = new Reihe(i == 0 ? name : "", null, POSTER);
                        for (int k = i; k < Math.min(a.length(), i + 8); k++) r.eintraege.add(a.optJSONObject(k));
                        r.geladen = true;
                        neu.reihen.add(r);
                    }
                    if (neu.reihen.isEmpty()) neu.leer = "Keine Filme";
                    if (seite == neu) {
                        nachladen();
                        fokusGeaendert();
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void serieOeffnen(JSONObject serie) {
        final String id = serie.optString("Id");
        final Seite neu = new Seite(serie.optString("Name"));
        neu.kopf = serie;
        neu.reihen.add(new Reihe("Staffeln", null, QUER));
        neueSeite(neu);
        netz.execute(() -> {
            try {
                JSONArray st = jf.staffeln(id);
                h.post(() -> {
                    neu.reihen.clear();
                    for (int i = 0; i < st.length(); i++) {
                        JSONObject s = st.optJSONObject(i);
                        neu.reihen.add(new Reihe(s.optString("Name"), "staffel:" + id + ":" + s.optString("Id"), QUER));
                    }
                    if (neu.reihen.isEmpty()) neu.leer = "Keine Folgen";
                    if (seite == neu) {
                        nachladen();
                        fokusGeaendert();
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void ordnerOeffnen(JSONObject ordner) {
        final String id = ordner.optString("Id");
        final Seite neu = new Seite(ordner.optString("Name"));
        neu.kopf = ordner;
        neu.reihen.add(new Reihe(ordner.optString("Name"), null, QUER));
        neueSeite(neu);
        netz.execute(() -> {
            try {
                JSONArray a = jf.ordner(id);
                h.post(() -> {
                    neu.reihen.clear();
                    List<JSONObject> unter = new ArrayList<>(), bilderListe = new ArrayList<>();
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o == null) continue;
                        if (o.optBoolean("IsFolder", false)) unter.add(o);
                        else bilderListe.add(o);
                    }
                    if (!unter.isEmpty()) neu.reihen.add(reiheAus("Ordner", unter, QUER));
                    int pro = 12;
                    for (int i = 0; i < bilderListe.size(); i += pro) {
                        Reihe r = new Reihe(i == 0 ? anzahlText(bilderListe) : "", null, QUER);
                        r.eintraege.addAll(bilderListe.subList(i, Math.min(bilderListe.size(), i + pro)));
                        r.geladen = true;
                        neu.reihen.add(r);
                    }
                    if (neu.reihen.isEmpty()) neu.leer = "Leer";
                    if (seite == neu) {
                        nachladen();
                        fokusGeaendert();
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }

    static String anzahlText(List<JSONObject> l) {
        int f = 0, v = 0;
        for (JSONObject o : l) {
            if ("Video".equals(typ(o))) v++;
            else f++;
        }
        StringBuilder sb = new StringBuilder();
        if (f > 0) sb.append(f).append(f == 1 ? " Foto" : " Fotos");
        if (v > 0) sb.append(sb.length() > 0 ? " · " : "").append(v).append(v == 1 ? " Video" : " Videos");
        return sb.toString();
    }

    private void abspielen(JSONObject f, boolean fortsetzen) {
        String id = f.optString("Id");
        String titel = f.optString("Name");
        if ("Episode".equals(typ(f))) titel = f.optString("SeriesName", titel) + " · " + titel;
        ausPlayer = true;
        PlayerActivity.starten(this, id, titel, fortsetzen ? Jellyfin.position(f) : 0, logoUrlFuer(f));
    }

    // ------------------------------------------------------------------ Suche

    private void sucheOeffnen() {
        if (ansicht != Ansicht.SUCHE) sucheZurueck = ansicht == Ansicht.DETAIL ? Ansicht.LISTE : ansicht;
        ansicht = Ansicht.SUCHE;
        inTreffern = false;
        tasteZeile = 1;
        tasteSpalte = 0;
        if (suchIndex == null && !suchIndexLaedt) {
            suchIndexLaedt = true;
            netz.execute(() -> {
                try {
                    JSONArray a = jf.suchIndex();
                    List<JSONObject> l = new ArrayList<>();
                    for (int i = 0; i < a.length(); i++) l.add(a.optJSONObject(i));
                    h.post(() -> {
                        suchIndex = l;
                        suchenJetzt();
                    });
                } catch (Exception e) {
                    h.post(() -> suchIndexLaedt = false);
                }
            });
        }
        suchenJetzt();
        fokusGeaendert();
    }

    private void tippen(String s) {
        if (suchText.length() < 40) suchText.append(s);
        suchenJetzt();
    }

    private void loeschen() {
        if (suchText.length() > 0) suchText.setLength(suchText.length() - 1);
        suchenJetzt();
    }

    private void suchenJetzt() {
        treffer.clear();
        if (suchIndex != null) {
            String q = suchText.toString().trim();
            if (q.isEmpty()) {
                // Vorschlaege: zuletzt hinzugefuegt
                List<JSONObject> l = new ArrayList<>(suchIndex);
                l.sort((a, b) -> b.optString("DateCreated").compareTo(a.optString("DateCreated")));
                treffer.addAll(l.subList(0, Math.min(15, l.size())));
            } else {
                treffer.addAll(Suche.suchen(q, suchIndex,
                        e -> new String[]{e.optString("Name"), e.optString("OriginalTitle", null)}, 30));
            }
        }
        trefferFokus = Math.min(trefferFokus, Math.max(0, treffer.size() - 1));
        if (treffer.isEmpty()) inTreffern = false;
        for (int i = 0; i < Math.min(10, treffer.size()); i++)
            bilder.laden(kartenUrl(treffer.get(i), POSTER), (u, b) -> buehne.invalidate());
        fokusGeaendert();
    }

    /** Taste der Bildschirmtastatur: Zeile 0 = Leerzeichen/Loeschen, danach das Buchstabenfeld. */
    private boolean sucheTaste(int k, boolean ok, boolean zurueck) {
        if (zurueck) {
            ansicht = sucheZurueck;
            fokusGeaendert();
            return true;
        }
        if (inTreffern) {
            int sp = trefferFokus % SUCH_SPALTEN;
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (sp == 0) {
                        inTreffern = false;
                        tasteSpalte = 5;
                    } else trefferFokus--;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (sp < SUCH_SPALTEN - 1 && trefferFokus < treffer.size() - 1) trefferFokus++;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (trefferFokus >= SUCH_SPALTEN) trefferFokus -= SUCH_SPALTEN;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (trefferFokus + SUCH_SPALTEN < treffer.size()) trefferFokus += SUCH_SPALTEN;
                    else if (trefferFokus / SUCH_SPALTEN < (treffer.size() - 1) / SUCH_SPALTEN) trefferFokus = treffer.size() - 1;
                    break;
                default:
                    if (ok || k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                        if (trefferFokus < treffer.size()) {
                            JSONObject e = treffer.get(trefferFokus);
                            if (!ok && abspielbar(e)) abspielen(e, true);
                            else oeffnen(e, null, Ansicht.SUCHE);
                        }
                    }
            }
            fokusGeaendert();
            return true;
        }
        int zeilen = TASTEN.length + 1;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (tasteZeile == 0) {
                    if (tasteSpalte >= 3) tasteSpalte = 0;
                    else leisteAuf();
                } else if (tasteSpalte > 0) tasteSpalte--;
                else leisteAuf();
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (tasteZeile == 0 && tasteSpalte < 3) tasteSpalte = 3;
                else if (tasteZeile > 0 && tasteSpalte < 5) tasteSpalte++;
                else if (!treffer.isEmpty()) {
                    inTreffern = true;
                    int zeile = Math.max(0, tasteZeile - 1) / 3;
                    trefferFokus = Math.min(treffer.size() - 1, zeile * SUCH_SPALTEN);
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (tasteZeile > 0) {
                    tasteZeile--;
                    if (tasteZeile == 0) tasteSpalte = tasteSpalte >= 3 ? 3 : 0;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (tasteZeile < zeilen - 1) {
                    if (tasteZeile == 0) tasteSpalte = tasteSpalte >= 3 ? 3 : 0;
                    tasteZeile++;
                }
                break;
            default:
                if (ok) {
                    if (tasteZeile == 0) {
                        if (tasteSpalte < 3) tippen(" ");
                        else loeschen();
                    } else {
                        tippen(TASTEN[tasteZeile - 1][tasteSpalte]);
                    }
                }
        }
        buehne.invalidate();
        return true;
    }

    // ------------------------------------------------------------------ Fotos im Vollbild

    private Bitmap fotoBild, fotoVorher;
    private long fotoWechsel;
    private String fotoUrl;

    private void fotoOeffnen(JSONObject e) {
        fotos.clear();
        fotoReihe = aktuelle();
        for (Reihe r : seite.reihen)
            for (JSONObject o : r.eintraege)
                if ("Photo".equals(typ(o)) || "Video".equals(typ(o))) fotos.add(o);
        fotoIdx = 0;
        for (int i = 0; i < fotos.size(); i++) if (fotos.get(i).optString("Id").equals(e.optString("Id"))) fotoIdx = i;
        ansicht = Ansicht.FOTO;
        diashow = false;
        fotoInfo = true;
        h.postDelayed(() -> {
            fotoInfo = false;
            buehne.invalidate();
        }, 2500);
        fotoLaden();
    }

    private void fotoLaden() {
        if (fotos.isEmpty()) return;
        JSONObject e = fotos.get(fotoIdx);
        final String url = Jellyfin.foto(e.optString("Id"), Jellyfin.tag(e, "Primary"));
        fotoUrl = url;
        Bitmap b = bilder.sofort(url);
        if (b != null) fotoZeigen(b);
        else bilder.laden(url, (u, bm) -> {
            if (u.equals(fotoUrl) && bm != null) fotoZeigen(bm);
        });
        // Nachbarn vorladen
        for (int d : new int[]{1, -1}) {
            int n = fotoIdx + d;
            if (n >= 0 && n < fotos.size()) {
                JSONObject o = fotos.get(n);
                bilder.laden(Jellyfin.foto(o.optString("Id"), Jellyfin.tag(o, "Primary")), null);
            }
        }
    }

    private void fotoZeigen(Bitmap b) {
        if (b == fotoBild) return;
        fotoVorher = fotoBild;
        fotoBild = b;
        fotoWechsel = SystemClock.uptimeMillis();
        buehne.invalidate();
    }

    private final Runnable diaWeiter = new Runnable() {
        @Override
        public void run() {
            if (ansicht != Ansicht.FOTO || !diashow) return;
            if (fotoIdx < fotos.size() - 1) fotoIdx++;
            else fotoIdx = 0;
            fotoLaden();
            h.postDelayed(this, 5000);
        }
    };

    private boolean fotoTaste(int k, boolean ok, boolean zurueck) {
        if (zurueck) {
            diashow = false;
            h.removeCallbacks(diaWeiter);
            // Fokus in der Liste auf das zuletzt gezeigte Bild setzen
            if (!fotos.isEmpty()) {
                String id = fotos.get(fotoIdx).optString("Id");
                for (int i = 0; i < seite.reihen.size(); i++) {
                    Reihe r = seite.reihen.get(i);
                    for (int j = 0; j < r.eintraege.size(); j++)
                        if (r.eintraege.get(j).optString("Id").equals(id)) {
                            seite.akt = i;
                            r.fokus = j;
                        }
                }
            }
            fotoBild = null;
            fotoVorher = null;
            ansicht = Ansicht.LISTE;
            fokusGeaendert();
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (fotoIdx > 0) {
                    fotoIdx--;
                    fotoLaden();
                }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (fotoIdx < fotos.size() - 1) {
                    fotoIdx++;
                    fotoLaden();
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                fotoInfo = !fotoInfo;
                break;
            default:
                if (fotos.isEmpty()) break;
                JSONObject e = fotos.get(fotoIdx);
                if ("Video".equals(typ(e))) {
                    abspielen(e, false);
                } else if (ok || k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                    diashow = !diashow;
                    h.removeCallbacks(diaWeiter);
                    if (diashow) h.postDelayed(diaWeiter, 5000);
                    fotoInfo = true;
                    h.postDelayed(() -> {
                        fotoInfo = false;
                        buehne.invalidate();
                    }, 1800);
                }
        }
        buehne.invalidate();
        return true;
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

    static String datum(JSONObject e) {
        String d = e.optString("PremiereDate", "");
        if (d.isEmpty()) d = e.optString("DateCreated", "");
        if (d.length() < 10) return null;
        String[] t = d.substring(0, 10).split("-");
        if (t.length < 3) return null;
        String[] monate = {"Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"};
        try {
            int m = Integer.parseInt(t[1]);
            return Integer.parseInt(t[2]) + ". " + monate[Math.max(0, Math.min(11, m - 1))] + " " + t[0];
        } catch (Exception ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Zeichnen

    private final class Buehne extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint symbolStift = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint titel, meta, text, reiheText, knopf, gross, klein, karte, leisteText, taste;
        private final RectF r = new RectF();
        private final Matrix m = new Matrix();
        private long letzt = SystemClock.uptimeMillis();
        private final Map<Reihe, Float> scrolls = new HashMap<>();
        private float fokusZoom = 1f, detailAnim, leisteAnim, leisteFokusY = -1, sucheScroll;
        private JSONObject letzterFokus;
        private final Path[] symbole = new Path[4];

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
            karte = stift(c, 26, 650, 0xFFFFFFFF);
            leisteText = stift(c, 38, 650, 0xFFFFFFFF);
            taste = stift(c, 36, 600, 0xFFFFFFFF);
            symbole[0] = Symbole.SUCHE;
            symbole[1] = symbolFilm();
            symbole[2] = symbolSerie();
            symbole[3] = symbolFoto();
        }

        private TextPaint stift(Context c, float g, int w, int farbe) {
            TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            t.setTypeface(Schriften.gewicht(c, w));
            t.setTextSize(g);
            t.setColor(farbe);
            return t;
        }

        private Path symbolFilm() {
            Path s = new Path();
            s.addRoundRect(new RectF(3, 5, 21, 19), 3, 3, Path.Direction.CW);
            s.moveTo(10, 9);
            s.lineTo(15, 12);
            s.lineTo(10, 15);
            s.close();
            return s;
        }

        private Path symbolSerie() {
            Path s = new Path();
            s.addRoundRect(new RectF(3, 7, 21, 18), 2.5f, 2.5f, Path.Direction.CW);
            s.moveTo(8.5f, 3);
            s.lineTo(12, 7);
            s.lineTo(15.5f, 3);
            s.moveTo(8, 21);
            s.lineTo(16, 21);
            return s;
        }

        private Path symbolFoto() {
            Path s = new Path();
            s.addRoundRect(new RectF(3, 5, 21, 19), 3, 3, Path.Direction.CW);
            s.moveTo(3.5f, 17);
            s.lineTo(9, 11.5f);
            s.lineTo(13, 15.5f);
            s.lineTo(15.5f, 13);
            s.lineTo(20.5f, 17.5f);
            s.addCircle(16.2f, 9.2f, 1.6f, Path.Direction.CW);
            return s;
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
                case FOTO:
                    weiter = fotoZeichnen(c);
                    break;
                default:
                    weiter = listeZeichnen(c, dt);
            }

            // Seitenleiste liegt ueber allem
            float ziel = leiste ? 1f : 0f;
            leisteAnim = naeher(leisteAnim, ziel, dt, 55);
            if (leisteAnim != ziel) weiter = true;
            if (leisteAnim > 0.002f) weiter |= leisteZeichnen(c, dt);

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
            glas(c, 510, 300, 1410, 780, 44, 0x2EFFFFFF);
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

        /** Einfaches Glas (ohne Unschaerfe) */
        private void glas(Canvas c, float l, float t, float rr, float b, float rad, int toenung) {
            r.set(l, t, rr, b);
            p.setStyle(Paint.Style.FILL);
            p.setColor(toenung);
            c.drawRoundRect(r, rad, rad, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
            p.setColor(0x4DFFFFFF);
            c.drawRoundRect(r, rad, rad, p);
            p.setStyle(Paint.Style.FILL);
        }

        /** Liquid Glass: verwischter Hintergrund, Toenung, Lichtkante oben, feiner Rand. */
        private void echtesGlas(Canvas c, RectF flaeche, float rad, float alpha) {
            int a = Math.round(255 * alpha);
            Bitmap u = unscharf;
            // Schatten
            p.setColor(0xFF000000);
            p.setAlpha(a);
            p.setShadowLayer(Ani.schatten(80), 0, 30, Ani.farbe(0x000000, 0.45f * alpha));
            c.drawRoundRect(flaeche, rad, rad, p);
            p.clearShadowLayer();
            if (u != null) {
                BitmapShader sh = new BitmapShader(u, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Matrix mm = new Matrix();
                mm.setScale(1920f / u.getWidth(), 1080f / u.getHeight());
                sh.setLocalMatrix(mm);
                p.setShader(sh);
                p.setAlpha(a);
                c.drawRoundRect(flaeche, rad, rad, p);
                p.setShader(null);
            } else {
                p.setColor(0xFF1C1F26);
                p.setAlpha(a);
                c.drawRoundRect(flaeche, rad, rad, p);
            }
            // dunkle Toenung fuer Lesbarkeit, darueber etwas Weiss
            p.setColor(0x59000000);
            p.setAlpha(Math.round(0x59 * alpha));
            c.drawRoundRect(flaeche, rad, rad, p);
            p.setColor(0x1AFFFFFF);
            p.setAlpha(Math.round(0x1A * alpha));
            c.drawRoundRect(flaeche, rad, rad, p);
            // Lichtkante oben
            p.setShader(new LinearGradient(0, flaeche.top, 0, flaeche.top + 220,
                    Ani.farbe(0xFFFFFF, 0.16f * alpha), 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(flaeche, rad, rad, p);
            p.setShader(null);
            // Rand
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.6f);
            p.setColor(Ani.farbe(0xFFFFFF, 0.32f * alpha));
            c.drawRoundRect(flaeche, rad, rad, p);
            p.setStyle(Paint.Style.FILL);
            p.setAlpha(255);
        }

        // -------------------------------------------------- Seitenleiste

        private boolean leisteZeichnen(Canvas c, float dt) {
            boolean weiter = false;
            float a = Ani.AUS.getInterpolation(leisteAnim);
            // Inhalt abdunkeln
            p.setColor(0xFF000000);
            p.setAlpha(Math.round(110 * a));
            c.drawRect(0, 0, 1920, 1080, p);
            p.setAlpha(255);

            float x = -440 + 480 * a; // von links hereinfahren
            RectF f = new RectF(x, 40, x + 420, 1040);
            echtesGlas(c, f, 48, Math.min(1f, a * 1.4f));

            // Ueberschrift
            klein.setAlpha(Math.round(200 * a));
            c.drawText("Lukas Kino", f.left + 48, 140, klein);
            klein.setAlpha(0xB3);

            float y0 = 260, schritt = 112;
            float zielY = y0 + leistePos * schritt;
            leisteFokusY = leisteFokusY < 0 ? zielY : naeher(leisteFokusY, zielY, dt, 45);
            if (leisteFokusY != zielY) weiter = true;
            // Auswahl-Pille gleitet
            r.set(f.left + 22, leisteFokusY - 46, f.right - 22, leisteFokusY + 46);
            p.setColor(0xFFFFFFFF);
            p.setAlpha(Math.round(242 * a));
            p.setShadowLayer(Ani.schatten(24), 0, 8, Ani.farbe(0x000000, 0.25f * a));
            c.drawRoundRect(r, 46, 46, p);
            p.clearShadowLayer();

            for (int i = 0; i < LEISTE.length; i++) {
                float y = y0 + i * schritt;
                float naehe = Math.max(0f, 1f - Math.abs(leisteFokusY - y) / schritt);
                int farbe = mischen(0xFFFFFFFF, 0xFF15171C, naehe);
                Symbole.malen(c, symbole[i], f.left + 52, y - 20, 40, 2f, Ani.farbe(farbe & 0xFFFFFF, a), symbolStift);
                symbolStift.setStyle(Paint.Style.FILL);
                leisteText.setColor(farbe);
                leisteText.setAlpha(Math.round(255 * a));
                c.drawText(LEISTE[i], f.left + 114, y + 13, leisteText);
                // aktiver Bereich: kleiner gelber Punkt
                boolean aktiv = (i == 0 && ansicht == Ansicht.SUCHE)
                        || (i > 0 && ansicht != Ansicht.SUCHE && bereich == Bereich.values()[i - 1]);
                if (aktiv) {
                    p.setColor(GELB);
                    p.setAlpha(Math.round(255 * a));
                    c.drawCircle(f.right - 58, y, 7, p);
                }
            }
            leisteText.setColor(0xFFFFFFFF);
            p.setAlpha(255);
            return weiter;
        }

        // -------------------------------------------------- Liste mit Hintergrund, Infos und Reihen

        private boolean listeZeichnen(Canvas c, float dt) {
            boolean weiter = false;
            float blend = Math.min(1f, (SystemClock.uptimeMillis() - ueberblendStart) / 450f);
            blend = Ani.EASE.getInterpolation(blend);
            if (blend < 1f) weiter = true;
            if (blend < 1f) bildMalen(c, bildAlt, farbeAlt, 1f);
            bildMalen(c, bildNeu, farbeNeu, blend);

            boolean detail = ansicht == Ansicht.DETAIL;
            boolean suche = ansicht == Ansicht.SUCHE || (detail && detailZurueck == Ansicht.SUCHE && false);
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

            if (suche) return sucheZeichnen(c, dt) | weiter;

            JSONObject fokus = fokusEintrag();
            if (fokus != letzterFokus) {
                letzterFokus = fokus;
                fokusZoom = 1f;
            }
            fokusZoom = naeher(fokusZoom, 1.1f, dt, 60);
            if (fokusZoom != 1.1f) weiter = true;

            // Reihen (fahren im Detail nach unten weg)
            seite.anim = naeher(seite.anim, seite.akt, dt, 85);
            if (seite.anim != seite.akt) weiter = true;
            float wegY = detailAnim * 520;
            float reihenAlpha = 1f - detailAnim;
            if (reihenAlpha > 0.01f && seite.leer == null) {
                float basis = versatz(seite, seite.anim);
                for (int i = 0; i < seite.reihen.size(); i++) {
                    Reihe rh = seite.reihen.get(i);
                    float y = REIHE_Y + versatz(seite, i) - basis + wegY;
                    if (y < -PH - 100 || y > 1180) continue;
                    float a = reihenAlpha;
                    if (i < seite.akt) a *= Math.max(0f, Math.min(1f, (y - REIHE_Y + 250) / 250f));
                    if (i > seite.akt) a *= 0.6f;
                    if (a <= 0.01f) continue;
                    weiter |= reiheZeichnen(c, rh, y, i == seite.akt, a, dt);
                }
            }

            JSONObject e = heldEintrag();
            if (seite.leer != null && !detail) {
                gross.setTextSize(96);
                c.drawText(seite.leer, LINKS - 4, 420, gross);
                gross.setTextSize(130);
                klein.setAlpha(200);
                c.drawText(seite.titel, LINKS, 480, klein);
                klein.setAlpha(0xB3);
            } else if (e != null) {
                String t = typ(e);
                if ("BoxSet".equals(t)) kategorieInfo(c, e);
                else if ("Folder".equals(t) || "PhotoAlbum".equals(t) || "CollectionFolder".equals(t)) ordnerInfo(c, e);
                else if ("Photo".equals(t) || "Video".equals(t)) fotoInfo(c, e);
                else filmInfo(c, e, detail);
            }
            return weiter;
        }

        /** Senkrechter Versatz einer (auch gebrochenen) Reihennummer. */
        private float versatz(Seite s, float i) {
            float v = 0;
            int ganz = (int) Math.floor(i);
            for (int k = 0; k < Math.min(ganz, s.reihen.size()); k++) v += s.reihen.get(k).abstand();
            if (ganz < s.reihen.size() && i > ganz) v += (i - ganz) * s.reihen.get(ganz).abstand();
            return v;
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
            int w = rh.form == QUER ? QW : PW, hh = rh.form == QUER ? QH : PH;
            float schritt = w + ABSTAND;
            if (!rh.titel.isEmpty()) {
                reiheText.setAlpha(Math.round((aktiv ? 230 : 170) * alpha));
                c.drawText(rh.titel, LINKS, y - 24, reiheText);
            }
            float soll = rh.fokus;
            Float ist = scrolls.get(rh);
            float sc = ist == null ? soll : naeher(ist, soll, dt, 80);
            scrolls.put(rh, sc);
            if (sc != soll) weiter = true;
            if (!rh.geladen && rh.eintraege.isEmpty()) {
                for (int k = 0; k < 8; k++) {
                    r.set(LINKS + k * schritt, y, LINKS + k * schritt + w, y + hh);
                    p.setColor(0x14FFFFFF);
                    p.setAlpha(Math.round(0x14 * alpha));
                    c.drawRoundRect(r, 18, 18, p);
                }
                return weiter;
            }
            int erster = Math.max(0, (int) Math.floor(sc) - 1);
            int letzter = Math.min(rh.eintraege.size() - 1, (int) Math.ceil(sc) + (int) (1900 / schritt) + 1);
            for (int k = erster; k <= letzter; k++) {
                if (aktiv && k == rh.fokus) continue;
                float px = LINKS + (k - sc) * schritt;
                float links = Math.max(0f, Math.min(1f, (px - LINKS + schritt * 0.5f) / (schritt * 0.5f)));
                int a2 = Math.round(al * (0.25f + 0.75f * links));
                karteZeichnen(c, rh.eintraege.get(k), rh.form, px, y, 1f, a2, false);
            }
            if (aktiv && rh.fokus >= erster && rh.fokus <= letzter) {
                karteZeichnen(c, rh.eintraege.get(rh.fokus), rh.form, LINKS + (rh.fokus - sc) * schritt, y,
                        fokusZoom, al, true);
            }
            return weiter;
        }

        private void karteZeichnen(Canvas c, JSONObject e, int form, float x, float y, float zoom, int al, boolean fokus) {
            int w0 = form == QUER ? QW : PW, h0 = form == QUER ? QH : PH;
            if (x > 1920 || x + w0 < -40) return;
            float cx = x + w0 / 2f, cy = y + h0 / 2f;
            float w = w0 * zoom, hh = h0 * zoom;
            r.set(cx - w / 2, cy - hh / 2, cx + w / 2, cy + hh / 2);
            float rad = 18 * zoom;
            if (fokus) {
                p.setColor(0xFF000000);
                p.setAlpha(al);
                p.setShadowLayer(Ani.schatten(50), 0, 22, Ani.farbe(0x000000, 0.6f * al / 255f));
                c.drawRoundRect(r, rad, rad, p);
                p.clearShadowLayer();
            }
            String url = kartenUrl(e, form);
            Bitmap b = url != null ? bilder.sofort(url) : null;
            if (b == null && url != null && !bilder.fehlgeschlagen(url)) bilder.laden(url, (u, bm) -> invalidate());
            String t = typ(e);
            boolean ordner = "Folder".equals(t) || "PhotoAlbum".equals(t) || "CollectionFolder".equals(t);
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
                p.setColor(ordner ? 0x33FFFFFF : 0x26FFFFFF);
                p.setAlpha(Math.round((ordner ? 0x33 : 0x26) * al / 255f));
                c.drawRoundRect(r, rad, rad, p);
                if (!ordner && !"Photo".equals(t) && !"Video".equals(t)) {
                    klein.setAlpha(al);
                    StaticLayout sl = umbruch(e.optString("Name"), klein, (int) (r.width() - 32), 4);
                    c.save();
                    c.translate(r.left + 16, r.top + 20);
                    sl.draw(c);
                    c.restore();
                    klein.setAlpha(0xB3);
                }
            }
            // Beschriftung im Bild: Ordner (Name) und Folgen (Nummer und Titel), unten auf dunklem Verlauf
            String unten = null;
            if (ordner) unten = e.optString("Name");
            else if ("Episode".equals(t)) {
                int n = e.optInt("IndexNumber", 0);
                unten = (n > 0 ? n + ". " : "") + e.optString("Name");
            }
            if (unten != null) {
                c.save();
                Path clip = new Path();
                clip.addRoundRect(r, rad, rad, Path.Direction.CW);
                c.clipPath(clip);
                p.setShader(new LinearGradient(0, r.bottom - 110 * zoom, 0, r.bottom, 0x00000000, 0xD9000000, Shader.TileMode.CLAMP));
                p.setAlpha(al);
                c.drawRect(r.left, r.bottom - 110 * zoom, r.right, r.bottom, p);
                p.setShader(null);
                c.restore();
                karte.setTextSize(26 * zoom);
                karte.setAlpha(al);
                c.drawText(kuerzen(unten, karte, r.width() - 40 * zoom), r.left + 20 * zoom, r.bottom - 22 * zoom, karte);
            }
            // Video-Symbol bei Heimvideos
            if ("Video".equals(t)) {
                float px = r.right - 34 * zoom, py = r.top + 34 * zoom;
                p.setColor(0xB3000000);
                p.setAlpha(Math.round(0xB3 * al / 255f));
                c.drawCircle(px, py, 20 * zoom, p);
                Path d = new Path();
                d.moveTo(px - 6 * zoom, py - 9 * zoom);
                d.lineTo(px + 9 * zoom, py);
                d.lineTo(px - 6 * zoom, py + 9 * zoom);
                d.close();
                p.setColor(0xFFFFFFFF);
                p.setAlpha(al);
                c.drawPath(d, p);
            }
            // Fortschritt angefangener Filme/Folgen
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
            if (Jellyfin.gesehen(e) && !ordner) {
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

        private String kuerzen(String t, TextPaint tp, float max) {
            if (tp.measureText(t) <= max) return t;
            while (t.length() > 2 && tp.measureText(t + "…") > max) t = t.substring(0, t.length() - 1);
            return t + "…";
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
            boolean folge = "Episode".equals(typ(e));
            if (folge) {
                // Serienname klein darueber
                String s = e.optString("SeriesName", "");
                int st = e.optInt("ParentIndexNumber", 0), fo = e.optInt("IndexNumber", 0);
                String kopf = s + (st > 0 ? "  ·  Staffel " + st : "") + (fo > 0 ? "  ·  Folge " + fo : "");
                meta.setColor(GELB);
                c.drawText(kopf, LINKS, y + 10, meta);
                meta.setColor(0xE6FFFFFF);
                y += 40;
            }
            if (logo != null && !folge) {
                float maxW = 640, maxH = 200;
                float sk = Math.min(maxW / logo.getWidth(), maxH / logo.getHeight());
                float w = logo.getWidth() * sk, hh = logo.getHeight() * sk;
                r.set(LINKS, y + (maxH - hh), LINKS + w, y + maxH);
                Logo.malen(c, logo, r, p, 255);
                y += maxH + 46;
            } else {
                titel.setTextSize(folge ? 68 : 88);
                titel.setShadowLayer(Ani.schatten(24), 0, 3, 0x80000000);
                StaticLayout sl = umbruch(e.optString("Name"), titel, 1000, 2);
                float hoehe = folge ? 150 : 200;
                c.save();
                c.translate(LINKS, y + hoehe - sl.getHeight());
                sl.draw(c);
                c.restore();
                titel.clearShadowLayer();
                titel.setTextSize(88);
                y += hoehe + 46;
            }

            float x = LINKS;
            List<String> teile = new ArrayList<>();
            int jahr = e.optInt("ProductionYear", 0);
            if ("Series".equals(typ(e))) {
                String ende = e.optString("EndDate", "");
                if (jahr > 0) teile.add(jahr + (ende.length() >= 4 ? "–" + ende.substring(0, 4) : ""));
                int n = e.optInt("ChildCount", 0);
                if (n > 0) teile.add(n == 1 ? "1 Staffel" : n + " Staffeln");
            } else {
                if (jahr > 0 && !folge) teile.add(String.valueOf(jahr));
                String d = dauer(e.optLong("RunTimeTicks", 0));
                if (d != null) teile.add(d);
            }
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

            long pos = Jellyfin.position(e), ges = e.optLong("RunTimeTicks", 0);
            if (pos > 0 && ges > pos && !Jellyfin.gesehen(e)) {
                float bw = 220;
                RectF bar = new RectF(LINKS, y - 14, LINKS + bw, y - 7);
                p.setColor(0x4DFFFFFF);
                c.drawRoundRect(bar, 4, 4, p);
                bar.right = LINKS + bw * Jellyfin.fortschritt(e);
                p.setColor(GELB);
                c.drawRoundRect(bar, 4, 4, p);
                c.drawText("noch " + dauer(ges - pos), LINKS + bw + 20, y, meta);
                y += 50;
            }

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

            if (detailAnim > 0.01f && abspielbar(e)) knoepfe(c, e, Math.max(y + 40, 780), detailAnim);
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
                p.setColor(farbe);
                p.setAlpha(Math.round(255 * a));
                Path d = new Path();
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
            gross.setShadowLayer(Ani.schatten(30), 0, 4, 0x66000000);
            c.drawText(e.optString("Name"), LINKS - 6, 330, gross);
            gross.clearShadowLayer();
            int n = e.optInt("ChildCount", 0);
            if (n > 0) c.drawText(n == 1 ? "1 Film" : n + " Filme", LINKS, 400, meta);
        }

        private void ordnerInfo(Canvas c, JSONObject e) {
            String name = e.optString("Name").trim();
            gross.setTextSize(name.length() > 16 ? 84 : 120);
            gross.setShadowLayer(Ani.schatten(30), 0, 4, 0x80000000);
            StaticLayout sl = umbruch(name, gross, 1300, 2);
            c.save();
            c.translate(LINKS - 4, 400 - sl.getHeight());
            sl.draw(c);
            c.restore();
            gross.clearShadowLayer();
            gross.setTextSize(130);
            int n = e.optInt("ChildCount", 0);
            if (n > 0) c.drawText(n == 1 ? "1 Element" : n + " Elemente", LINKS, 460, meta);
        }

        private void fotoInfo(Canvas c, JSONObject e) {
            String d = datum(e);
            String kopf = seite.kopf != null ? seite.kopf.optString("Name").trim() : seite.titel;
            gross.setTextSize(84);
            gross.setShadowLayer(Ani.schatten(30), 0, 4, 0x80000000);
            StaticLayout sl = umbruch(kopf, gross, 1300, 2);
            c.save();
            c.translate(LINKS - 4, 400 - sl.getHeight());
            sl.draw(c);
            c.restore();
            gross.clearShadowLayer();
            gross.setTextSize(130);
            if (d != null) c.drawText(d, LINKS, 460, meta);
        }

        // -------------------------------------------------- Suche

        private boolean sucheZeichnen(Canvas c, float dt) {
            boolean weiter = false;
            // Suchfeld
            RectF feld = new RectF(LINKS, 96, LINKS + 662, 184);
            echtesGlas(c, feld, 44, 1f);
            Symbole.malen(c, Symbole.SUCHE, feld.left + 30, feld.top + 26, 36, 2.2f, 0xCCFFFFFF, symbolStift);
            symbolStift.setStyle(Paint.Style.FILL);
            String t = suchText.toString();
            taste.setTextSize(40);
            if (t.isEmpty()) {
                taste.setColor(0x80FFFFFF);
                c.drawText("Filme und Serien", feld.left + 86, feld.top + 58, taste);
            } else {
                taste.setColor(0xFFFFFFFF);
                String sichtbar = t;
                while (taste.measureText(sichtbar) > 520 && sichtbar.length() > 1) sichtbar = sichtbar.substring(1);
                c.drawText(sichtbar, feld.left + 86, feld.top + 58, taste);
                // blinkender Strich
                if ((SystemClock.uptimeMillis() / 530) % 2 == 0) {
                    float cx = feld.left + 90 + taste.measureText(sichtbar);
                    p.setColor(GELB);
                    c.drawRect(cx, feld.top + 22, cx + 3, feld.bottom - 22, p);
                }
                weiter = true;
            }
            taste.setTextSize(36);
            taste.setColor(0xFFFFFFFF);

            // Tastatur
            float kx = LINKS, ky = 222, kw = 100, kh = 84, gap = 12;
            boolean tastFokus = !inTreffern;
            // Zeile 0: Leerzeichen und Loeschen
            for (int i = 0; i < 2; i++) {
                float x = kx + i * 3 * (kw + gap);
                r.set(x, ky, x + 3 * kw + 2 * gap, ky + kh);
                boolean f = tastFokus && tasteZeile == 0 && (i == 0 ? tasteSpalte < 3 : tasteSpalte >= 3);
                tasteMalen(c, r, i == 0 ? "Leerzeichen" : "Löschen", f, 30);
            }
            for (int z = 0; z < TASTEN.length; z++) {
                for (int s = 0; s < 6; s++) {
                    float x = kx + s * (kw + gap), y = ky + (z + 1) * (kh + gap);
                    r.set(x, y, x + kw, y + kh);
                    boolean f = tastFokus && tasteZeile == z + 1 && tasteSpalte == s;
                    tasteMalen(c, r, TASTEN[z][s].toUpperCase(Locale.GERMAN).replace("SS", "ß"), f, 36);
                }
            }

            // Treffer rechts
            float gx = 850, gy = 300, tw = 170, th = 255, tg = 24;
            String kopf = suchIndex == null ? "Wird geladen …"
                    : t.trim().isEmpty() ? "Neu hinzugefügt" : treffer.isEmpty() ? "Nichts gefunden" : "Treffer";
            reiheText.setAlpha(230);
            c.drawText(kopf, gx, 150, reiheText);
            JSONObject held = inTreffern && trefferFokus < treffer.size() ? treffer.get(trefferFokus) : null;
            if (held != null) {
                String info = held.optString("Name");
                int j = held.optInt("ProductionYear", 0);
                if (j > 0) info += "  ·  " + j;
                if ("Series".equals(typ(held))) info += "  ·  Serie";
                meta.setColor(0xFFFFFFFF);
                c.drawText(kuerzen(info, meta, 960), gx, 200, meta);
                meta.setColor(0xE6FFFFFF);
            }
            int zeileFokus = trefferFokus / SUCH_SPALTEN;
            float sollScroll = Math.max(0, zeileFokus - 1);
            sucheScroll = naeher(sucheScroll, sollScroll, dt, 80);
            if (sucheScroll != sollScroll) weiter = true;
            if (held != letzterFokus) {
                letzterFokus = held;
                fokusZoom = 1f;
            }
            fokusZoom = naeher(fokusZoom, 1.08f, dt, 60);
            if (fokusZoom != 1.08f) weiter = true;
            c.save();
            c.clipRect(gx - 40, 240, 1920, 1080);
            int fokusIdx = -1;
            for (int i = 0; i < treffer.size(); i++) {
                int zz = i / SUCH_SPALTEN, ss = i % SUCH_SPALTEN;
                float y = gy + (zz - sucheScroll) * (th + tg + 16);
                if (y > 1080 || y + th < 200) continue;
                if (inTreffern && i == trefferFokus) {
                    fokusIdx = i;
                    continue;
                }
                kleinePosterkarte(c, treffer.get(i), gx + ss * (tw + tg), y, tw, th, 1f, inTreffern ? 255 : 200, false);
            }
            if (fokusIdx >= 0) {
                int zz = fokusIdx / SUCH_SPALTEN, ss = fokusIdx % SUCH_SPALTEN;
                float y = gy + (zz - sucheScroll) * (th + tg + 16);
                kleinePosterkarte(c, treffer.get(fokusIdx), gx + ss * (tw + tg), y, tw, th, fokusZoom, 255, true);
            }
            c.restore();
            return weiter;
        }

        private void tasteMalen(Canvas c, RectF rr, String text, boolean fokus, float groesse) {
            if (fokus) {
                p.setColor(0xFFFFFFFF);
                p.setShadowLayer(Ani.schatten(20), 0, 6, 0x40000000);
                c.drawRoundRect(rr, 20, 20, p);
                p.clearShadowLayer();
            } else {
                p.setColor(0x1FFFFFFF);
                c.drawRoundRect(rr, 20, 20, p);
            }
            taste.setTextSize(groesse);
            taste.setColor(fokus ? 0xFF111317 : 0xFFFFFFFF);
            taste.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = taste.getFontMetrics();
            c.drawText(text, rr.centerX(), rr.centerY() - (fm.ascent + fm.descent) / 2f, taste);
            taste.setTextAlign(Paint.Align.LEFT);
            taste.setColor(0xFFFFFFFF);
        }

        private void kleinePosterkarte(Canvas c, JSONObject e, float x, float y, float w0, float h0, float zoom, int al, boolean fokus) {
            float cx = x + w0 / 2f, cy = y + h0 / 2f, w = w0 * zoom, hh = h0 * zoom;
            r.set(cx - w / 2, cy - hh / 2, cx + w / 2, cy + hh / 2);
            float rad = 16 * zoom;
            if (fokus) {
                p.setColor(0xFF000000);
                p.setShadowLayer(Ani.schatten(44), 0, 18, 0x99000000);
                c.drawRoundRect(r, rad, rad, p);
                p.clearShadowLayer();
            }
            String url = kartenUrl(e, POSTER);
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
                p.setColor(0x26FFFFFF);
                c.drawRoundRect(r, rad, rad, p);
                klein.setAlpha(al);
                StaticLayout sl = umbruch(e.optString("Name"), klein, (int) (r.width() - 28), 4);
                c.save();
                c.translate(r.left + 14, r.top + 18);
                sl.draw(c);
                c.restore();
                klein.setAlpha(0xB3);
            }
            p.setAlpha(255);
        }

        // -------------------------------------------------- Foto-Vollbild

        private boolean fotoZeichnen(Canvas c) {
            boolean weiter = false;
            c.drawColor(0xFF000000);
            float t = Math.min(1f, (SystemClock.uptimeMillis() - fotoWechsel) / 380f);
            if (t < 1f) weiter = true;
            if (t < 1f && fotoVorher != null) fotoMalen(c, fotoVorher, 1f - t);
            if (fotoBild != null) fotoMalen(c, fotoBild, t);
            else {
                klein.setTextAlign(Paint.Align.CENTER);
                c.drawText("…", 960, 540, klein);
                klein.setTextAlign(Paint.Align.LEFT);
            }
            if (!fotos.isEmpty()) {
                JSONObject e = fotos.get(fotoIdx);
                if ("Video".equals(typ(e))) {
                    p.setColor(0x99000000);
                    c.drawCircle(960, 540, 72, p);
                    Path d = new Path();
                    d.moveTo(945, 510);
                    d.lineTo(990, 540);
                    d.lineTo(945, 570);
                    d.close();
                    p.setColor(0xFFFFFFFF);
                    c.drawPath(d, p);
                }
                if (fotoInfo) {
                    // Glas-Pille unten mit Datum, Nummer und Diashow-Zustand
                    String d = datum(e);
                    String txt = (d != null ? d + "   ·   " : "") + (fotoIdx + 1) + " / " + fotos.size()
                            + (diashow ? "   ·   Diashow" : "");
                    knopf.setTextSize(30);
                    float w = knopf.measureText(txt) + 80;
                    RectF pille = new RectF(960 - w / 2, 960, 960 + w / 2, 1032);
                    p.setColor(0xB31A1C22);
                    c.drawRoundRect(pille, 36, 36, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.5f);
                    p.setColor(0x40FFFFFF);
                    c.drawRoundRect(pille, 36, 36, p);
                    p.setStyle(Paint.Style.FILL);
                    knopf.setTextAlign(Paint.Align.CENTER);
                    c.drawText(txt, 960, 1007, knopf);
                    knopf.setTextAlign(Paint.Align.LEFT);
                }
            }
            return weiter;
        }

        private void fotoMalen(Canvas c, Bitmap b, float alpha) {
            float sk = Math.min(1920f / b.getWidth(), 1080f / b.getHeight());
            float w = b.getWidth() * sk, hh = b.getHeight() * sk;
            r.set((1920 - w) / 2f, (1080 - hh) / 2f, (1920 + w) / 2f, (1080 + hh) / 2f);
            p.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(b, null, r, p);
            p.setAlpha(255);
        }
    }
}
