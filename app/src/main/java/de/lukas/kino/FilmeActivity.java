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
import java.util.Iterator;
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
        FARBEN.put("Gesehen", 0xFF3E4A5E);
    }

    // ------------------------------------------------------------------ Daten

    static final class Reihe {
        final String titel;
        final String quelle; // wird erst bei Bedarf geladen (samml:, @alle, staffel:, ordner:)
        final int form;
        final List<JSONObject> eintraege = new ArrayList<>();
        int fokus;
        int live;            // so viele Live-Kacheln stehen vorne (nur Weiterschauen)
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

        // Raster (Fotos wie in Plex): jede Reihe ist eine Zeile der Wand, fokus = Spalte
        boolean raster;
        int spalten, kw, kh, luecke;
        float kopfAnim = 1f;      // 1 = grosser Kopf (erste Zeile), 0 = kleine Zeile ganz oben
        String kopfInfo = "";     // Zeile unter dem Namen (Zeitraum, Anzahl)
        boolean ohneFilter;       // Merkliste und Gesehen zeigen auch Gesehenes

        Seite(String titel) {
            this.titel = titel;
        }
    }

    // Raster-Masse: Ordner 6 nebeneinander (16:9), Fotos 7 nebeneinander (4:3), wenig Abstand
    static final int ORDNER_SPALTEN = 6, ORDNER_W = 268, ORDNER_H = 151, ORDNER_LUECKE = 17;
    static final int FOTO_SPALTEN = 7, FOTO_W = 230, FOTO_H = 172, FOTO_LUECKE = 14;
    // Live-Kacheln ganz links in Weiterschauen: breiter, mit Abstand zu den normalen Postern
    static final int LIVE_W = 470, LIVE_LUECKE = 64;
    static final int RASTER_OBEN_KLEIN = 112, RASTER_OBEN_GROSS = 300;

    /** Baut eine Raster-Seite: erst die Ordner, dann Fotos und Videos, zeilenweise. */
    static void rasterFuellen(Seite s, List<JSONObject> eintraege) {
        boolean nurOrdner = true;
        for (JSONObject o : eintraege) if (!o.optBoolean("IsFolder", false)) nurOrdner = false;
        s.raster = true;
        s.spalten = nurOrdner ? ORDNER_SPALTEN : FOTO_SPALTEN;
        s.kw = nurOrdner ? ORDNER_W : FOTO_W;
        s.kh = nurOrdner ? ORDNER_H : FOTO_H;
        s.luecke = nurOrdner ? ORDNER_LUECKE : FOTO_LUECKE;
        s.reihen.clear();
        for (int i = 0; i < eintraege.size(); i += s.spalten) {
            Reihe r = new Reihe("", null, QUER);
            r.eintraege.addAll(eintraege.subList(i, Math.min(eintraege.size(), i + s.spalten)));
            r.geladen = true;
            s.reihen.add(r);
        }
        s.akt = 0;
        s.anim = 0;
    }

    /** Zeitraum der Aufnahmen („3.–12. April 2025“), aus dem Aufnahmedatum der Fotos. */
    static String zeitraum(List<JSONObject> l) {
        String von = null, bis = null;
        for (JSONObject o : l) {
            String d = o.optString("PremiereDate", "");
            if (d.length() < 10 || o.optBoolean("IsFolder", false)) continue;
            d = d.substring(0, 10);
            if (von == null || d.compareTo(von) < 0) von = d;
            if (bis == null || d.compareTo(bis) > 0) bis = d;
        }
        if (von == null) return null;
        String[] monate = {"Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"};
        try {
            String[] a = von.split("-"), b = bis.split("-");
            int ta = Integer.parseInt(a[2]), tb = Integer.parseInt(b[2]);
            String ma = monate[Integer.parseInt(a[1]) - 1], mb = monate[Integer.parseInt(b[1]) - 1];
            if (von.equals(bis)) return ta + ". " + ma + " " + a[0];
            if (a[0].equals(b[0]) && a[1].equals(b[1])) return ta + ".–" + tb + ". " + ma + " " + a[0];
            if (a[0].equals(b[0])) return ta + ". " + ma + " – " + tb + ". " + mb + " " + a[0];
            return ma + " " + a[0] + " – " + mb + " " + b[0];
        } catch (Exception e) {
            return null;
        }
    }

    static String ordnerName(JSONObject e) {
        return e.optString("Name").trim().replaceAll("\\s+", " ");
    }

    enum Bereich { FILME, SERIEN, MERKLISTE, EMPFOHLEN, FOTOS }

    enum Ansicht { LADEN, ANMELDEN, FEHLER, LISTE, DETAIL, SUCHE, FOTO }

    private Jellyfin jf;
    private Bilder bilder;
    private Buehne buehne;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService netz = Executors.newFixedThreadPool(2);
    // Titelbilder der Foto-Ordner eigen, damit sie das Öffnen von Ordnern und Reihen nicht ausbremsen
    private final ExecutorService bildSuche = Executors.newFixedThreadPool(2);
    private SharedPreferences titelSpeicher;
    private final Merkliste merkliste = new Merkliste();
    private KinoHa.Waechter auftrag;   // Film vom Dashboard starten (Skript 01g)
    private String[] detailTrailer;          // {youtubeId, titel} zum Film in der Detailansicht
    private String detailTrailerId;
    private String hinweisText;              // kurzer Hinweis unten (z. B. „Auf der Merkliste“)
    private long hinweisBis, familieZeit;

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
    private static final String[] LEISTE = {"Suche", "Filme", "Serien", "Merkliste", "Empfohlen", "Fotos"};

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

    // Live: was gerade an den Fernsehern läuft (sensor.kino_jetzt über auftrag.json), ganz links in Weiterschauen
    private final List<JSONObject> liveListe = new ArrayList<>();
    private String liveSig = "";

    // Bewerten (nach dem Film ab 80 % oder über den Knopf in den Details)
    private boolean bew;
    private BewertenKarte bewKarte;   // Zustand und Zeichnen des Fensters (seit 03.11.2026)

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
        titelSpeicher = getSharedPreferences("lukaskino_titelbilder2", MODE_PRIVATE);
        buehne = new Buehne(this);
        setContentView(buehne);
        verstecken();
        auftrag = new KinoHa.Waechter(this, this::abspielen, this::liveNeu);
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
        if (jf.angemeldet() && SystemClock.uptimeMillis() - familieZeit > 180_000) netz.execute(this::familieLaden);
        auftrag.an();
    }

    @Override
    protected void onPause() {
        super.onPause();
        auftrag.aus();
    }

    @Override
    protected void onDestroy() {
        auftrag.aus();
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
                familieLaden(); // Bewertungen, Merkliste und Empfohlen aus Home Assistant
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
        if (b == Bereich.MERKLISTE) startseiten.put(Bereich.MERKLISTE, merklisteSeite());
        if (b == Bereich.EMPFOHLEN) startseiten.put(Bereich.EMPFOHLEN, empfohlenSeite());
        if (b == Bereich.MERKLISTE || b == Bereich.EMPFOHLEN) netz.execute(this::familieLaden); // frisch aus HA
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
        if (b == Bereich.FILME) liveEinsetzen();
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
        List<JSONObject> gemerkt = merkliste.liste();
        if (!gemerkt.isEmpty()) s.reihen.add(reiheAus("Merkliste", gemerkt, POSTER));
        s.reihen.add(ohneGesehenes(reihe("Neu", neu, POSTER)));
        if (samml.length() > 0 || Gesehen.anzahl() > 0) {
            Reihe kat = reihe("Kategorien", samml, POSTER);
            if (Gesehen.anzahl() > 0) kat.eintraege.add(gesehenKachel());
            s.reihen.add(kat);
            for (int i = 0; i < samml.length(); i++) {
                JSONObject k = samml.getJSONObject(i);
                s.reihen.add(new Reihe(k.optString("Name"), "samml:" + k.optString("Id"), POSTER));
            }
        }
        s.reihen.add(new Reihe("Alle Filme", "@alle", POSTER));
        return s;
    }

    private Seite merklisteSeite() {
        Seite s = new Seite("Merkliste");
        s.ohneFilter = true;
        List<JSONObject> filme = new ArrayList<>(), serien = new ArrayList<>();
        for (JSONObject o : merkliste.liste()) {
            if ("Series".equals(typ(o))) serien.add(o);
            else filme.add(o);
        }
        for (int i = 0; i < filme.size(); i += 8)
            s.reihen.add(reiheAus(i == 0 ? "Filme" : "", filme.subList(i, Math.min(filme.size(), i + 8)), POSTER));
        for (int i = 0; i < serien.size(); i += 8)
            s.reihen.add(reiheAus(i == 0 ? "Serien" : "", serien.subList(i, Math.min(serien.size(), i + 8)), POSTER));
        if (s.reihen.isEmpty()) s.leer = "Noch nichts gemerkt";
        return s;
    }

    /** Merkliste geändert: Reihe auf der Filme-Startseite und die Merkliste-Seite nachziehen. */
    private void merklisteGeaendert() {
        List<JSONObject> l = merkliste.liste();
        Seite start = startseiten.get(Bereich.FILME);
        if (start != null) {
            int i = -1;
            for (int k = 0; k < start.reihen.size(); k++) if ("Merkliste".equals(start.reihen.get(k).titel)) i = k;
            if (!l.isEmpty() && i >= 0) {
                Reihe r = start.reihen.get(i);
                r.eintraege.clear();
                r.eintraege.addAll(l);
                r.fokus = Math.min(r.fokus, l.size() - 1);
            } else if (!l.isEmpty()) {
                int pos = !start.reihen.isEmpty() && "Weiterschauen".equals(start.reihen.get(0).titel) ? 1 : 0;
                start.reihen.add(pos, reiheAus("Merkliste", l, POSTER));
                if (start.akt >= pos && start.reihen.size() > 1) {
                    start.akt++;
                    start.anim = start.akt;
                }
            } else if (i >= 0) {
                start.reihen.remove(i);
                if (start.akt > i || start.akt >= start.reihen.size()) start.akt = Math.max(0, start.akt - 1);
                start.anim = start.akt;
            }
        }
        Seite alt = startseiten.get(Bereich.MERKLISTE);
        Seite neu = merklisteSeite();
        startseiten.put(Bereich.MERKLISTE, neu);
        if (alt != null && seite == alt) {
            neu.akt = Math.min(alt.akt, Math.max(0, neu.reihen.size() - 1));
            neu.anim = neu.akt;
            seite = neu;
        }
        buehne.invalidate();
    }

    /** Bewertungen, Merkliste und Empfohlen aus Home Assistant holen (blockiert – im Hintergrund). */
    private void familieLaden() {
        try {
            Familie.laden();
            familieZeit = SystemClock.uptimeMillis();
        } catch (Exception ignored) {
            return; // ohne Bewertungen weiter
        }
        boolean m = false, e = false, g = false;
        try {
            m = merkliste.ausHa(jf);
        } catch (Exception ignored) {
            // Merkliste bleibt, wie sie war
        }
        try {
            g = Gesehen.ausHa(jf);
        } catch (Exception ignored) {
            // Gesehen bleibt, wie es war
        }
        try {
            e = Empfohlen.berechnen(jf);
        } catch (Exception ignored) {
            // Empfohlen bleibt, wie es war
        }
        final boolean mm = m, ee = e, gg = g;
        h.post(() -> {
            if (mm) merklisteGeaendert();
            if (ee) empfohlenGeaendert();
            if (gg) gesehenGeaendert();
            buehne.invalidate();
        });
    }

    private Seite empfohlenSeite() {
        Seite s = new Seite("Empfohlen");
        for (Empfohlen.Gruppe g : Empfohlen.gruppen()) {
            List<JSONObject> l = new ArrayList<>();
            for (JSONObject o : g.eintraege) if (!Gesehen.hat(o)) l.add(o);
            for (int i = 0; i < l.size(); i += 8)
                s.reihen.add(reiheAus(i == 0 ? "Mit " + g.note + " bewertet" : "", l.subList(i, Math.min(l.size(), i + 8)), POSTER));
        }
        if (s.reihen.isEmpty())
            s.leer = Empfohlen.geladen() ? "Noch nichts mit 7 oder besser bewertet" : "Bewertungen werden geladen …";
        return s;
    }

    private void empfohlenGeaendert() {
        Seite alt = startseiten.get(Bereich.EMPFOHLEN);
        Seite neu = empfohlenSeite();
        startseiten.put(Bereich.EMPFOHLEN, neu);
        if (alt != null && seite == alt) {
            neu.akt = Math.min(alt.akt, Math.max(0, neu.reihen.size() - 1));
            neu.anim = neu.akt;
            if (neu.akt < neu.reihen.size() && alt.akt < alt.reihen.size())
                neu.reihen.get(neu.akt).fokus = Math.min(alt.reihen.get(alt.akt).fokus, neu.reihen.get(neu.akt).eintraege.size() - 1);
            seite = neu;
            fokusGeaendert();
        }
        buehne.invalidate();
    }

    // ------------------------------------------------------------------ Live (wer schaut gerade was, wo)

    /** Alle 4 s aus auftrag.json (KinoHa.Waechter). Neue Filme werden bei Jellyfin geholt, Zeiten laufend übernommen. */
    private void liveNeu(JSONObject laeuft, double serverJetzt) {
        List<String> wer = new ArrayList<>();
        Iterator<String> it = laeuft.keys();
        while (it.hasNext()) {
            String w = it.next();
            JSONObject x = laeuft.optJSONObject(w);
            String typ = x != null ? x.optString("typ", "movie") : "";
            if (x != null && ("movie".equals(typ) || "episode".equals(typ))) wer.add(w);
        }
        // das Aktuellste ganz links
        Collections.sort(wer, (a, b) -> Double.compare(laeuft.optJSONObject(b).optDouble("ts", 0), laeuft.optJSONObject(a).optDouble("ts", 0)));
        StringBuilder sb = new StringBuilder();
        for (String w : wer) sb.append(w).append('|').append(laeuft.optJSONObject(w).optString("id")).append(',');
        final String sig = sb.toString();
        if (sig.equals(liveSig)) {
            // gleiche Filme: nur Stand, Pause und Zeit übernehmen
            for (JSONObject e : liveListe) {
                JSONObject li = e.optJSONObject("_live");
                JSONObject x = li != null ? laeuft.optJSONObject(li.optString("wer")) : null;
                if (x == null) continue;
                try {
                    if (li.optDouble("jetzt", 0) != serverJetzt) {
                        li.put("jetzt", serverJetzt);
                        li.put("empfangen", SystemClock.uptimeMillis());
                    }
                    li.put("pos", x.optDouble("pos", 0));
                    li.put("dauer", x.optDouble("dauer", 0));
                    li.put("status", x.optString("status", "playing"));
                    li.put("ts", x.optDouble("ts", serverJetzt));
                } catch (Exception ignored) {
                }
            }
            buehne.invalidate();
            return;
        }
        final long empfangen = SystemClock.uptimeMillis();
        netz.execute(() -> {
            Map<String, String> jfIds = new HashMap<>();
            List<String> ids = new ArrayList<>();
            for (String w : wer) {
                String id = Merkliste.jfFuerHa(laeuft.optJSONObject(w).optString("id"));
                if (id == null || id.isEmpty()) continue;
                jfIds.put(w, id);
                if (!ids.contains(id)) ids.add(id);
            }
            Map<String, JSONObject> items = new HashMap<>();
            if (!ids.isEmpty()) {
                try {
                    JSONArray a = jf.nachIds(ids);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o != null) items.put(o.optString("Id"), o);
                    }
                } catch (Exception e) {
                    return; // beim nächsten Mal nochmal
                }
            }
            List<JSONObject> weiter = null;
            try {
                JSONArray a = jf.weiterschauen();
                weiter = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) weiter.add(a.optJSONObject(i));
            } catch (Exception ignored) {
                // dann bleibt Weiterschauen, wie es ist
            }
            final List<JSONObject> weiterFertig = weiter;
            List<JSONObject> neu = new ArrayList<>();
            for (String w : wer) {
                JSONObject f = items.get(jfIds.get(w));
                if (f == null) continue;
                try {
                    JSONObject k = new JSONObject(f.toString());
                    JSONObject x = laeuft.optJSONObject(w), li = new JSONObject();
                    li.put("wer", w);
                    li.put("pos", x.optDouble("pos", 0));
                    li.put("dauer", x.optDouble("dauer", 0));
                    li.put("status", x.optString("status", "playing"));
                    li.put("ts", x.optDouble("ts", serverJetzt));
                    li.put("jetzt", serverJetzt);
                    li.put("empfangen", empfangen);
                    k.put("_live", li);
                    neu.add(k);
                } catch (Exception ignored) {
                }
            }
            h.post(() -> {
                liveSig = sig;
                liveListe.clear();
                liveListe.addAll(neu);
                liveEinsetzen(weiterFertig);
                fokusGeaendert();
            });
        });
    }

    private void liveEinsetzen() {
        liveEinsetzen(null);
    }

    /**
     * Live-Kacheln vorne in die Reihe Weiterschauen der Filme-Startseite setzen (Reihe notfalls anlegen oder entfernen).
     * weiter = frische Weiterschauen-Liste von Jellyfin oder null (dann bleibt der normale Teil, wie er ist).
     */
    private void liveEinsetzen(List<JSONObject> weiter) {
        Seite start = startseiten.get(Bereich.FILME);
        if (start == null) return;
        Reihe w = start.reihen.isEmpty() ? null : start.reihen.get(0);
        boolean da = w != null && "Weiterschauen".equals(w.titel);
        int alt = da ? w.live : 0;
        Set<String> liveIds = new HashSet<>();
        for (JSONObject e : liveListe) liveIds.add(e.optString("Id"));
        List<JSONObject> regulaer = new ArrayList<>();
        if (weiter != null) regulaer.addAll(weiter);
        else if (da) for (JSONObject e : w.eintraege) if (!e.has("_live")) regulaer.add(e);
        regulaer.removeIf(e -> liveIds.contains(e.optString("Id")));
        List<JSONObject> alle = new ArrayList<>(liveListe);
        alle.addAll(regulaer);
        if (alle.isEmpty()) {
            if (da && start.reihen.size() > 1) {
                start.reihen.remove(0);
                if (start.akt > 0) start.akt--;
                start.anim = start.akt;
            }
            buehne.invalidate();
            return;
        }
        if (!da) {
            w = new Reihe("Weiterschauen", null, POSTER);
            w.geladen = true;
            start.reihen.add(0, w);
            if (seite == start) {
                start.akt++;
                start.anim = start.akt;
            }
        }
        w.eintraege.clear();
        w.eintraege.addAll(alle);
        w.live = liveListe.size();
        // stand man ganz vorne, bleibt man vorne (Live zuerst sehen), sonst bleibt der Fokus auf demselben Film
        w.fokus = Math.max(0, Math.min(alt == 0 && w.fokus == 0 ? 0 : w.fokus + w.live - alt, w.eintraege.size() - 1));
        buehne.invalidate();
    }

    /** „Mama und Papa · Wohnzimmer“, „Jonas · in seinem Zimmer“, „Du · dein Zimmer“. */
    static String liveWo(String wer) {
        if (wer.equals(Merkliste.WER)) return "Du  ·  dein Zimmer";
        if ("Eltern".equals(wer)) return "Mama und Papa  ·  Wohnzimmer";
        return wer + "  ·  in seinem Zimmer";
    }

    // ------------------------------------------------------------------ Gesehen (je Profil, ab 80 %)

    private static Reihe ohneGesehenes(Reihe r) {
        r.eintraege.removeIf(Gesehen::hat);
        return r;
    }

    /** Kachel „Gesehen“ ganz rechts in der Reihe Kategorien. */
    private static JSONObject gesehenKachel() {
        JSONObject o = new JSONObject();
        try {
            o.put("Type", "Gesehen");
            o.put("Id", "@gesehen");
            o.put("Name", "Gesehen");
            o.put("ChildCount", Gesehen.anzahl());
        } catch (Exception ignored) {
        }
        return o;
    }

    /** Neue Gesehen-Liste: Gesehenes aus allen offenen Reihen nehmen, Kachel nachziehen. */
    private void gesehenGeaendert() {
        List<Seite> alle = new ArrayList<>(startseiten.values());
        alle.addAll(stapel);
        if (!alle.contains(seite)) alle.add(seite);
        for (Seite s : alle) {
            if (s.ohneFilter || s.raster) continue;
            for (int i = s.reihen.size() - 1; i >= 0; i--) {
                Reihe r = s.reihen.get(i);
                if ("Weiterschauen".equals(r.titel) || "Merkliste".equals(r.titel)) continue;
                if (!r.eintraege.removeIf(Gesehen::hat)) continue;
                r.fokus = Math.min(r.fokus, Math.max(0, r.eintraege.size() - 1));
                if (r.eintraege.isEmpty() && r.geladen && s.reihen.size() > 1) {
                    s.reihen.remove(i);
                    if (s.akt > i || s.akt >= s.reihen.size()) s.akt = Math.max(0, s.akt - 1);
                    s.anim = s.akt;
                }
            }
        }
        Seite start = startseiten.get(Bereich.FILME);
        if (start != null) {
            for (Reihe r : start.reihen) {
                if (!"Kategorien".equals(r.titel)) continue;
                r.eintraege.removeIf(o -> "Gesehen".equals(typ(o)));
                if (Gesehen.anzahl() > 0) r.eintraege.add(gesehenKachel());
                r.fokus = Math.min(r.fokus, Math.max(0, r.eintraege.size() - 1));
            }
        }
        if (bereich == Bereich.EMPFOHLEN || startseiten.containsKey(Bereich.EMPFOHLEN)) empfohlenGeaendert();
        fokusGeaendert();
    }

    /** Kategorie „Gesehen“: alle Filme, die dieses Profil schon gesehen hat, zuletzt gesehen zuerst. */
    private void gesehenOeffnen(JSONObject kachel) {
        final Seite neu = new Seite("Gesehen");
        neu.kopf = kachel;
        neu.ohneFilter = true;
        neu.reihen.add(new Reihe("Gesehen", null, POSTER));
        neueSeite(neu);
        final List<String> ids = Gesehen.liste();
        netz.execute(() -> {
            Map<String, JSONObject> gefunden = new HashMap<>();
            for (int i = 0; i < ids.size(); i += 60) {
                try {
                    JSONArray a = jf.nachIds(ids.subList(i, Math.min(ids.size(), i + 60)));
                    for (int k = 0; k < a.length(); k++) {
                        JSONObject o = a.optJSONObject(k);
                        if (o != null) gefunden.put(o.optString("Id"), o);
                    }
                } catch (Exception ignored) {
                }
            }
            h.post(() -> {
                List<JSONObject> l = new ArrayList<>();
                for (String id : ids) if (gefunden.containsKey(id)) l.add(gefunden.get(id));
                neu.reihen.clear();
                for (int i = 0; i < l.size(); i += 8)
                    neu.reihen.add(reiheAus(i == 0 ? (l.size() == 1 ? "1 Film" : l.size() + " Filme") : "", l.subList(i, Math.min(l.size(), i + 8)), POSTER));
                if (neu.reihen.isEmpty()) neu.leer = "Noch nichts gesehen";
                if (seite == neu) {
                    nachladen();
                    fokusGeaendert();
                }
            });
        });
    }

    // ------------------------------------------------------------------ Bewerten

    /** Fenster „Lukas, wie hat dir der Film gefallen?“ – Eltern bekommen zwei Zähler (Papa links, Mama rechts). */
    private void bewertenOeffnen(JSONObject film) {
        if (film == null || !"Movie".equals(typ(film))) return;
        String[] namen = Bewerten.namen();
        int[] alt = new int[namen.length];
        Familie.Info info = Familie.fuer(film);
        for (int i = 0; i < namen.length; i++) {
            Integer n = info != null ? info.noten.get(namen[i]) : null;
            alt[i] = n != null && n >= 1 && n <= 10 ? n : 0;
        }
        bewKarte = new BewertenKarte(this, film, namen, alt);
        bew = true;
        leiste = false;
        buehne.invalidate();
    }

    private boolean bewertenTaste(int k, boolean ok, boolean zurueck, int wiederholung) {
        if (bewKarte == null) {
            bewSchliessen();
            return true;
        }
        int was = bewKarte.taste(k, ok, zurueck, wiederholung);
        if (was == BewertenKarte.SCHLIESSEN) bewSchliessen();
        else if (was == BewertenKarte.ABGEBEN) bewSenden();
        buehne.invalidate();
        return true;
    }

    /** Schickt nur, was geändert wurde (Note 0 = eigene Bewertung herausnehmen). */
    private void bewSenden() {
        final BewertenKarte karte = bewKarte;
        final List<int[]> l = karte.aenderungen();
        if (l.isEmpty()) {
            bewSchliessen();
            hinweis("Nichts geändert");
            return;
        }
        karte.danke();
        buehne.invalidate();
        h.postDelayed(() -> {
            if (bewKarte == karte && karte.imDanke()) bewSchliessen();
        }, karte.mitZehn() ? 3200 : 2200);
        netz.execute(() -> {
            boolean alles = true;
            for (int[] e : l) {
                try {
                    Bewerten.senden(karte.film, karte.namen[e[0]], e[1]);
                } catch (Exception ex) {
                    alles = false;
                }
            }
            if (!alles) h.post(() -> hinweis("Bewertung ließ sich nicht speichern"));
            // HA schreibt die Datei sofort neu – kurz danach abholen
            h.postDelayed(() -> netz.execute(this::familieLaden), 3000);
        });
    }

    private void bewSchliessen() {
        bew = false;
        buehne.invalidate();
    }

    private void hinweis(String text) {
        hinweisText = text;
        hinweisBis = SystemClock.uptimeMillis() + 2200;
        buehne.invalidate();
    }

    /** Knöpfe der Detailansicht: {art, text}. */
    private List<String[]> knoepfeFuer(JSONObject e) {
        List<String[]> l = new ArrayList<>();
        if (e == null) return l;
        if (abspielbar(e)) {
            long pos = Jellyfin.position(e);
            if (pos > 0) {
                l.add(new String[]{"weiter", "Fortsetzen  ·  " + PlayerActivity.zeit(pos / 10_000L)});
                l.add(new String[]{"vorne", "Von vorne"});
            } else {
                l.add(new String[]{"abspielen", "Abspielen"});
            }
        }
        if (detailTrailer != null && e.optString("Id").equals(detailTrailerId)) l.add(new String[]{"trailer", "Trailer"});
        if ("Movie".equals(typ(e))) {
            l.add(merkliste.hat(e.optString("Id")) ? new String[]{"gemerkt", "Gemerkt"} : new String[]{"merken", "Merken"});
            l.add(new String[]{"bewerten", eigeneNoten(e)});
        }
        return l;
    }

    /** Text des Knopfs „Bewerten“: mit den eigenen Noten, wenn schon bewertet (z. B. „Bewertet · 8“). */
    private static String eigeneNoten(JSONObject e) {
        Familie.Info info = Familie.fuer(e);
        StringBuilder sb = new StringBuilder();
        for (String n : Bewerten.namen()) {
            Integer w = info != null ? info.noten.get(n) : null;
            if (w != null) sb.append(sb.length() > 0 ? " / " : "").append(w);
        }
        return sb.length() > 0 ? "Bewertet  ·  " + sb : "Bewerten";
    }

    private void knopfAusfuehren(String art, JSONObject f) {
        switch (art) {
            case "weiter":
            case "abspielen":
                abspielen(f, true);
                break;
            case "vorne":
                abspielen(f, false);
                break;
            case "trailer":
                if (detailTrailer != null) TrailerActivity.starten(this, detailTrailer[0], f.optString("Name"));
                break;
            case "bewerten":
                bewertenOeffnen(f);
                break;
            case "merken":
            case "gemerkt": {
                final boolean drin = merkliste.umschalten(f);
                merklisteGeaendert();
                hinweis(drin ? "Auf der Merkliste" : "Von der Merkliste entfernt");
                netz.execute(() -> {
                    try {
                        merkliste.senden(f, drin);
                        // HA schreibt die Datei sofort neu – kurz danach abholen
                        h.postDelayed(() -> netz.execute(this::familieLaden), 2500);
                    } catch (Exception ex) {
                        merkliste.vergessen(f.optString("Id"));
                        h.post(() -> hinweis("Merkliste ließ sich nicht speichern"));
                        familieLaden();
                    }
                });
                break;
            }
            default:
        }
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
        // Wie in Plex: alle Ordner der Fotos-Bibliothek nach Namen als Kachel-Wand, lose Bilder dahinter
        List<JSONObject> ordner = new ArrayList<>(), lose = new ArrayList<>();
        for (int i = 0; i < inhalt.length(); i++) {
            JSONObject o = inhalt.getJSONObject(i);
            if (o.optBoolean("IsFolder", false)) ordner.add(o);
            else lose.add(o);
        }
        List<JSONObject> alle = new ArrayList<>(ordner);
        alle.addAll(lose);
        if (alle.isEmpty()) {
            s.leer = "Die Fotos werden gerade eingelesen";
            return s;
        }
        rasterFuellen(s, alle);
        s.kopfInfo = ordner.size() + " Ordner" + (lose.isEmpty() ? "" : " · " + anzahlText(lose));
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
        if (s.raster) {
            // Wand: sichtbare Zeilen plus ein paar darunter vorladen (nach Nähe zum Fokus)
            for (int d = 0; d <= 7; d++)
                for (int i : new int[]{s.akt + d, s.akt - d}) {
                    if (i < 0 || i >= s.reihen.size() || (i < s.akt && d > 2)) continue;
                    for (JSONObject o : s.reihen.get(i).eintraege)
                        bilder.laden(rasterUrl(o), (u, bm) -> buehne.invalidate());
                }
            return;
        }
        for (int i = Math.max(0, s.akt - 1); i < Math.min(s.reihen.size(), s.akt + 3); i++) {
            final Reihe r = s.reihen.get(i);
            if (r.geladen || r.laedt || r.quelle == null) continue;
            r.laedt = true;
            netz.execute(() -> {
                try {
                    JSONArray a = holen(r.quelle);
                    h.post(() -> {
                        for (int k = 0; k < a.length(); k++) {
                            JSONObject o = a.optJSONObject(k);
                            if (o != null && (s.ohneFilter || !Gesehen.hat(o))) r.eintraege.add(o);
                        }
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
        final JSONObject f0 = detailEintrag != null ? detailEintrag : fokusEintrag();
        // Zuletzt gespielt (auch bei Filmen vom Dashboard) und wie weit
        final String zuletzt = PlayerActivity.zuletztId;
        final float quote = zuletzt != null ? PlayerActivity.zuletztQuote : 0f;
        PlayerActivity.zuletztId = null;
        final JSONObject f = zuletzt != null && (f0 == null || !zuletzt.equals(f0.optString("Id"))) ? null : f0;
        // Weiterschauen gibt es nur auf den Startseiten von Filme und Serien (aus Merkliste oder Suche: Filme)
        final Bereich b = bereich == Bereich.SERIEN ? Bereich.SERIEN : Bereich.FILME;
        netz.execute(() -> {
            try {
                JSONObject frisch = zuletzt != null ? jf.film(zuletzt) : f != null ? jf.film(f.optString("Id")) : null;
                JSONArray weiter = b == Bereich.SERIEN ? jf.serienWeiterschauen() : jf.weiterschauen();
                h.post(() -> {
                    if (frisch != null && "Movie".equals(typ(frisch)) && quote >= Gesehen.GRENZE) {
                        // ab 80 %: bei diesem Profil gesehen, und fragen, wie er gefallen hat
                        boolean schon = Gesehen.hat(frisch);
                        Gesehen.lokalDazu(frisch);
                        if (!schon) {
                            netz.execute(() -> {
                                try {
                                    Gesehen.senden(frisch, true);
                                } catch (Exception ignored) {
                                    // Automation 44 trägt es sonst ohnehin ein
                                }
                            });
                        }
                        bewertenOeffnen(frisch);
                    }
                    if (frisch != null) {
                        ersetzen(frisch);
                        merkliste.ersetzen(frisch);
                        Empfohlen.ersetzen(frisch);
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
                    if (b == Bereich.FILME) liveEinsetzen();
                    if (frisch != null && "Movie".equals(typ(frisch)) && quote >= Gesehen.GRENZE) gesehenGeaendert();
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
                    if (id.equals(r.eintraege.get(i).optString("Id")) && !r.eintraege.get(i).has("_live")) r.eintraege.set(i, neu);
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
        // In der Foto-Wand bleibt der Hintergrund ruhig: Titelbild des Ordners (Startseite: dunkel)
        boolean wand = seite.raster && ansicht == Ansicht.LISTE;
        JSONObject e = wand ? seite.kopf : heldEintrag();
        if (e == null) {
            if (wand) setzeHintergrund(null, 0xFF1B1E25);
            return;
        }
        String t = typ(e);
        if ("BoxSet".equals(t) || "Gesehen".equals(t)) {
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

    /** Bild einer Kachel in der Wand: kleiner als in den Reihen, damit viele in den Speicher passen. */
    String rasterUrl(JSONObject e) {
        if (e == null) return null;
        String id = e.optString("Id"), pr = Jellyfin.tag(e, "Primary");
        if (e.optBoolean("IsFolder", false)) {
            if (pr != null) return Jellyfin.quer(id, pr, ORDNER_W * 2);
            String tb = titelbilder.get(id);
            if (tb == null) {
                titelbildSuchen(id);
                tb = titelbilder.get(id);
            }
            return tb == null || tb.isEmpty() ? null : tb.replace("fillWidth=" + QW * 2, "fillWidth=" + ORDNER_W * 2);
        }
        return Jellyfin.quer(id, pr, 360);
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
                if (f != null && Jellyfin.tag(f, "Primary") != null) url = Jellyfin.quer(f.optString("Id"), Jellyfin.tag(f, "Primary"), QW * 2);
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

        if (bew) return bewertenTaste(k, ok, zurueck, ev.getRepeatCount());
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
        if (seite.raster) return rasterTaste(k, ok, r, wiederholung);
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

    /** Pfeile in der Foto-Wand: frei in alle Richtungen, rechts am Zeilenende geht es in der nächsten Zeile weiter. */
    private boolean rasterTaste(int k, boolean ok, Reihe r, int wiederholung) {
        int spalte = r.fokus;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (r.fokus > 0) r.fokus--;
                else if (wiederholung == 0) leisteAuf();
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (r.fokus < r.eintraege.size() - 1) r.fokus++;
                else if (seite.akt < seite.reihen.size() - 1) {
                    seite.akt++;
                    seite.reihen.get(seite.akt).fokus = 0;
                    nachladen();
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (seite.akt > 0) {
                    seite.akt--;
                    Reihe n = seite.reihen.get(seite.akt);
                    n.fokus = Math.min(spalte, n.eintraege.size() - 1);
                    nachladen();
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (seite.akt < seite.reihen.size() - 1) {
                    seite.akt++;
                    Reihe n = seite.reihen.get(seite.akt);
                    n.fokus = Math.min(spalte, n.eintraege.size() - 1);
                    nachladen();
                }
                break;
            default:
                if (ok) oeffnen(fokusEintrag(), r, Ansicht.LISTE);
                else if (k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                    // Play auf einem Foto: gleich als Diashow ab diesem Bild
                    JSONObject f = fokusEintrag();
                    if (f != null && "Photo".equals(typ(f))) {
                        fotoOeffnen(f);
                        diashow = true;
                        h.postDelayed(diaWeiter, 5000);
                    } else if (f != null) oeffnen(f, r, Ansicht.LISTE);
                }
                return true;
        }
        fokusGeaendert();
        return true;
    }

    /** OK auf einem Element – je nach Art. */
    private void oeffnen(JSONObject e, Reihe r, Ansicht von) {
        if (e == null) return;
        String t = typ(e);
        switch (t) {
            case "BoxSet":
                kategorieOeffnen(e);
                break;
            case "Gesehen":
                gesehenOeffnen(e);
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
                detailTrailer = null;
                detailTrailerId = null;
                fokusGeaendert();
                if ("Movie".equals(t)) {
                    netz.execute(() -> {
                        final String[] tr = Trailer.suchen(this, e);
                        if (tr == null) return;
                        h.post(() -> {
                            if (detailEintrag != null && e.optString("Id").equals(detailEintrag.optString("Id"))) {
                                detailTrailer = tr;
                                detailTrailerId = e.optString("Id");
                                buehne.invalidate();
                            }
                        });
                    });
                }
        }
    }

    private boolean detailTaste(int k, boolean ok, boolean zurueck) {
        JSONObject f = detailEintrag;
        List<String[]> kn = knoepfeFuer(f);
        if (zurueck) {
            ansicht = detailZurueck;
            detailEintrag = null;
            detailTrailer = null;
            fokusGeaendert();
        } else if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
            detailKnopf = Math.max(0, detailKnopf - 1);
        } else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            detailKnopf = Math.min(Math.max(0, kn.size() - 1), detailKnopf + 1);
        } else if (ok) {
            if (f != null && detailKnopf < kn.size()) knopfAusfuehren(kn.get(detailKnopf)[0], f);
        } else if ((k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) && f != null && abspielbar(f)) {
            abspielen(f, true);
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
                JSONArray roh = jf.inhalt(id);
                h.post(() -> {
                    neu.reihen.clear();
                    List<JSONObject> a = new ArrayList<>();
                    for (int k = 0; k < roh.length(); k++) {
                        JSONObject o = roh.optJSONObject(k);
                        if (o != null && !Gesehen.hat(o)) a.add(o);
                    }
                    for (int i = 0; i < a.size(); i += 8)
                        neu.reihen.add(reiheAus(i == 0 ? name : "", a.subList(i, Math.min(a.size(), i + 8)), POSTER));
                    if (neu.reihen.isEmpty()) neu.leer = roh.length() > 0 ? "Alles schon gesehen" : "Keine Filme";
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
        final Seite neu = new Seite(ordnerName(ordner));
        neu.kopf = ordner;
        neu.raster = true; // Kopf sofort, die Wand kommt, sobald der Inhalt da ist
        neu.kopfInfo = "";
        neueSeite(neu);
        netz.execute(() -> {
            try {
                JSONArray a = jf.ordner(id);
                h.post(() -> {
                    List<JSONObject> unter = new ArrayList<>(), bilderListe = new ArrayList<>();
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o == null) continue;
                        if (o.optBoolean("IsFolder", false)) unter.add(o);
                        else bilderListe.add(o);
                    }
                    List<JSONObject> alle = new ArrayList<>(unter);
                    alle.addAll(bilderListe);
                    rasterFuellen(neu, alle);
                    StringBuilder info = new StringBuilder();
                    String zr = zeitraum(bilderListe);
                    if (zr != null) info.append(zr);
                    if (!unter.isEmpty()) info.append(info.length() > 0 ? "   ·   " : "").append(unter.size()).append(" Ordner");
                    if (!bilderListe.isEmpty()) info.append(info.length() > 0 ? "   ·   " : "").append(anzahlText(bilderListe));
                    neu.kopfInfo = info.toString();
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
        private float fokusZoom = 1f, detailAnim, leisteAnim, leisteFokusY = -1, sucheScroll, bewAnim;
        private JSONObject letzterFokus;
        private final Path[] symbole = new Path[6];

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
            symbole[3] = symbolMerk();
            symbole[4] = symbolStern();
            symbole[5] = symbolFoto();
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

        private Path symbolMerk() {
            Path s = new Path();
            s.moveTo(6.5f, 3.5f);
            s.lineTo(17.5f, 3.5f);
            s.lineTo(17.5f, 20.5f);
            s.lineTo(12f, 16.5f);
            s.lineTo(6.5f, 20.5f);
            s.close();
            return s;
        }

        private Path symbolStern() {
            Path s = new Path();
            float[] xy = {12f, 3.2f, 14.7f, 8.7f, 20.7f, 9.6f, 16.35f, 13.8f, 17.35f, 19.8f, 12f, 17f, 6.65f, 19.8f, 7.65f, 13.8f,
                    3.3f, 9.6f, 9.3f, 8.7f};
            s.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) s.lineTo(xy[i], xy[i + 1]);
            s.close();
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

            // Bewerten liegt ganz oben
            float bz = bew ? 1f : 0f;
            bewAnim = naeher(bewAnim, bz, dt, 60);
            if (bewAnim != bz) weiter = true;
            if (bewAnim > 0.002f && bewKarte != null) weiter |= bewertenZeichnen(c);

            if (hinweisText != null && SystemClock.uptimeMillis() < hinweisBis) {
                knopf.setTextSize(30);
                float hw = knopf.measureText(hinweisText) + 80;
                RectF pl = new RectF(960 - hw / 2, 948, 960 + hw / 2, 1020);
                p.setColor(0xD91A1C22);
                c.drawRoundRect(pl, 36, 36, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.5f);
                p.setColor(0x40FFFFFF);
                c.drawRoundRect(pl, 36, 36, p);
                p.setStyle(Paint.Style.FILL);
                knopf.setTextAlign(Paint.Align.CENTER);
                c.drawText(hinweisText, 960, 995, knopf);
                knopf.setTextAlign(Paint.Align.LEFT);
                weiter = true;
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
            if (seite.raster && ansicht == Ansicht.LISTE) return rasterZeichnen(c, dt) | weiter;
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
                    float kachelA = 1f;
                    if (i > seite.akt) {
                        a *= 0.6f;
                        // Reihen darunter: nur die Überschrift, keine angeschnittenen Kacheln am unteren Rand
                        // (Wunsch Lukas 02.11.2026). Erst wenn fast die ganze Kachel Platz hat, blendet sie ein.
                        float hoch = rh.form == QUER ? QH : PH;
                        kachelA = Math.max(0f, Math.min(1f, ((1080f - y) / hoch - 0.55f) / 0.35f));
                    }
                    if (a <= 0.01f) continue;
                    weiter |= reiheZeichnen(c, rh, y, i == seite.akt, a, kachelA, dt);
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
                if ("BoxSet".equals(t) || "Gesehen".equals(t)) kategorieInfo(c, e);
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

        private boolean reiheZeichnen(Canvas c, Reihe rh, float y, boolean aktiv, float alpha, float kachelA, float dt) {
            boolean weiter = false;
            int al = Math.round(255 * alpha * kachelA);
            int w = rh.form == QUER ? QW : PW, hh = rh.form == QUER ? QH : PH;
            float schritt = w + ABSTAND;
            float soll = rh.fokus;
            Float ist = scrolls.get(rh);
            float sc = ist == null ? soll : naeher(ist, soll, dt, 80);
            scrolls.put(rh, sc);
            if (sc != soll) weiter = true;
            if (rh.live > 0 && rh.form == POSTER) return liveReiheZeichnen(c, rh, y, aktiv, alpha * kachelA, sc) | weiter;
            if (!rh.titel.isEmpty()) {
                reiheText.setAlpha(Math.round((aktiv ? 230 : 170) * alpha));
                c.drawText(rh.titel, LINKS, y - 24, reiheText);
            }
            if (al <= 2) return weiter;
            if (!rh.geladen && rh.eintraege.isEmpty()) {
                for (int k = 0; k < 8; k++) {
                    r.set(LINKS + k * schritt, y, LINKS + k * schritt + w, y + hh);
                    p.setColor(0x14FFFFFF);
                    p.setAlpha(Math.round(0x14 * alpha * kachelA));
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

        // -------------------------------------------------- Bewerten

        private boolean bewertenZeichnen(Canvas c) {
            float a = Ani.AUS.getInterpolation(Math.max(0f, Math.min(1f, bewAnim)));
            p.setColor(0xFF000000);
            p.setAlpha(Math.round(150 * a));
            c.drawRect(0, 0, 1920, 1080, p);
            p.setAlpha(255);
            echtesGlas(c, bewKarte.rahmen(a), 52, a);
            boolean weiter = bewKarte.zeichnen(c, a);
            p.setAlpha(255);
            return weiter && bew;
        }

        // -------------------------------------------------- Weiterschauen mit Live-Kacheln vorne

        /** x-Position einer (auch gebrochenen) Stelle in einer Reihe mit Live-Kacheln vorne. */
        private float liveX(Reihe rh, float k) {
            int a = (int) Math.floor(k);
            float xa = livePunkt(rh, a), xb = livePunkt(rh, a + 1);
            return xa + (xb - xa) * (k - a);
        }

        private float livePunkt(Reihe rh, int k) {
            int n = rh.live;
            float lw = LIVE_W + ABSTAND, sw = PW + ABSTAND;
            return k < n ? k * lw : n * lw + LIVE_LUECKE + (k - n) * sw;
        }

        private boolean liveReiheZeichnen(Canvas c, Reihe rh, float y, boolean aktiv, float alpha, float sc) {
            int al = Math.round(255 * alpha);
            float basis = liveX(rh, sc);
            // zwei Überschriften, die mitwandern: „Läuft gerade“ über den Live-Kacheln, „Weiterschauen“ darüber
            reiheText.setAlpha(Math.round((aktiv ? 230 : 170) * alpha));
            float tx = LINKS + livePunkt(rh, 0) - basis;
            if (tx > -400) {
                p.setColor(0xFFE5484D);
                p.setAlpha(al);
                c.drawCircle(Math.max(tx, -300) + 8, y - 34, 8, p);
                c.drawText("Läuft gerade", tx + 26, y - 24, reiheText);
            }
            if (rh.eintraege.size() > rh.live)
                c.drawText("Weiterschauen", LINKS + livePunkt(rh, rh.live) - basis, y - 24, reiheText);
            int erster = Math.max(0, (int) Math.floor(sc) - 1);
            int letzter = Math.min(rh.eintraege.size() - 1, (int) Math.ceil(sc) + 10);
            for (int k = erster; k <= letzter; k++) {
                if (aktiv && k == rh.fokus) continue;
                float px = LINKS + livePunkt(rh, k) - basis;
                if (px > 1920) break;
                float bw = k < rh.live ? LIVE_W : PW;
                float links = Math.max(0f, Math.min(1f, (px - LINKS + bw * 0.5f) / (bw * 0.5f)));
                int a2 = Math.round(al * (0.25f + 0.75f * links));
                if (k < rh.live) liveKarte(c, rh.eintraege.get(k), px, y, 1f, a2, false);
                else karteZeichnen(c, rh.eintraege.get(k), POSTER, px, y, 1f, a2, false);
            }
            if (aktiv && rh.fokus >= erster && rh.fokus <= letzter) {
                float px = LINKS + livePunkt(rh, rh.fokus) - basis;
                if (rh.fokus < rh.live) liveKarte(c, rh.eintraege.get(rh.fokus), px, y, fokusZoom, al, true);
                else karteZeichnen(c, rh.eintraege.get(rh.fokus), POSTER, px, y, fokusZoom, al, true);
            }
            p.setAlpha(255);
            return false;
        }

        /** Live-Kachel: Szenenbild, LIVE, wer wo, Titel, Fortschritt in der Farbe der Person, „noch … · fertig ca. …“. */
        private void liveKarte(Canvas c, JSONObject e, float x, float y, float zoom, int al, boolean fokus) {
            JSONObject li = e.optJSONObject("_live");
            if (li == null) return;
            float cx = x + LIVE_W / 2f, cy = y + PH / 2f, w = LIVE_W * zoom, hh = PH * zoom;
            r.set(cx - w / 2, cy - hh / 2, cx + w / 2, cy + hh / 2);
            float rad = 18 * zoom;
            if (fokus) {
                p.setColor(0xFF000000);
                p.setAlpha(al);
                p.setShadowLayer(Ani.schatten(50), 0, 22, Ani.farbe(0x000000, 0.6f * al / 255f));
                c.drawRoundRect(r, rad, rad, p);
                p.clearShadowLayer();
            }
            String wer = li.optString("wer");
            Integer pf = Familie.FARBEN.get(wer);
            int farbe = pf != null ? pf : GELB;
            // Bild: Szene des Films (bei Folgen der Serie), sonst das Poster
            String url = null;
            String tag = Jellyfin.hintergrundTag(e);
            if (tag != null) url = Jellyfin.szene(e.optString("Id"), tag, LIVE_W * 2);
            else {
                JSONArray pt = e.optJSONArray("ParentBackdropImageTags");
                String pid = e.optString("ParentBackdropItemId", null);
                if (pt != null && pt.length() > 0 && pid != null) url = Jellyfin.szene(pid, pt.optString(0), LIVE_W * 2);
                else url = kartenUrl(e, POSTER);
            }
            Bitmap b = url != null ? bilder.sofort(url) : null;
            if (b == null && url != null && !bilder.fehlgeschlagen(url)) bilder.laden(url, (u, bm) -> invalidate());
            c.save();
            Path clip = new Path();
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.clipPath(clip);
            if (b != null) {
                float sk = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
                m.setScale(sk, sk);
                m.postTranslate(r.left + (r.width() - b.getWidth() * sk) / 2f, r.top + (r.height() - b.getHeight() * sk) / 2f);
                p.setAlpha(al);
                c.drawBitmap(b, m, p);
            } else {
                p.setColor(0xFF1D2027);
                p.setAlpha(al);
                c.drawRect(r, p);
            }
            // dunkler Verlauf unten für die Schrift, leichter Farbschimmer der Person
            p.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[]{0x26000000, 0x8C000000, 0xF2000000},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            p.setAlpha(al);
            c.drawRect(r, p);
            p.setShader(null);
            c.restore();
            // Rand in der Farbe der Person
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f * zoom);
            p.setColor(farbe);
            p.setAlpha(Math.round((fokus ? 255 : 170) * al / 255f));
            c.drawRoundRect(r, rad, rad, p);
            p.setStyle(Paint.Style.FILL);
            float l = r.left + 22 * zoom, breite = r.width() - 44 * zoom;
            // LIVE
            knopf.setTextSize(20 * zoom);
            float lw = knopf.measureText("LIVE") + 44 * zoom;
            RectF pill = new RectF(l, r.top + 18 * zoom, l + lw, r.top + 52 * zoom);
            p.setColor(0xFFE5484D);
            p.setAlpha(al);
            c.drawRoundRect(pill, 17 * zoom, 17 * zoom, p);
            p.setColor(0xFFFFFFFF);
            p.setAlpha(al);
            c.drawCircle(pill.left + 16 * zoom, pill.centerY(), 5 * zoom, p);
            knopf.setAlpha(al);
            c.drawText("LIVE", pill.left + 28 * zoom, pill.centerY() + 7 * zoom, knopf);
            knopf.setTextSize(30);
            knopf.setAlpha(255);
            // wer und wo
            karte.setTextSize(22 * zoom);
            karte.setColor(farbe);
            karte.setAlpha(al);
            c.drawText(kuerzen(liveWo(wer), karte, breite), l, r.bottom - 124 * zoom, karte);
            karte.setColor(0xFFFFFFFF);
            // Titel
            String titel = e.optString("Name");
            if ("Episode".equals(typ(e)))
                titel = e.optString("SeriesName") + "  ·  S" + e.optInt("ParentIndexNumber", 0) + " E" + e.optInt("IndexNumber", 0);
            karte.setTextSize(31 * zoom);
            karte.setAlpha(al);
            c.drawText(kuerzen(titel, karte, breite), l, r.bottom - 84 * zoom, karte);
            // Stand: läuft weiter, solange gespielt wird
            double dauer = li.optDouble("dauer", 0), pos = li.optDouble("pos", 0);
            boolean pause = !"playing".equals(li.optString("status"));
            if (!pause)
                pos += Math.max(0, li.optDouble("jetzt", 0) - li.optDouble("ts", 0)) + (SystemClock.uptimeMillis() - li.optLong("empfangen", SystemClock.uptimeMillis())) / 1000.0;
            if (dauer > 0) pos = Math.min(pos, dauer);
            float anteil = dauer > 0 ? (float) (pos / dauer) : 0f;
            RectF bahn = new RectF(l, r.bottom - 62 * zoom, l + breite, r.bottom - 55 * zoom);
            p.setColor(0x4DFFFFFF);
            p.setAlpha(Math.round(0x4D * al / 255f));
            c.drawRoundRect(bahn, 4 * zoom, 4 * zoom, p);
            bahn.right = l + breite * anteil;
            p.setColor(farbe);
            p.setAlpha(al);
            c.drawRoundRect(bahn, 4 * zoom, 4 * zoom, p);
            long rest = Math.max(0, Math.round((dauer - pos) / 60.0));
            String restText = rest >= 60 ? (rest / 60) + " Std. " + (rest % 60) + " Min." : rest + " Min.";
            String zeile;
            if (dauer <= 0) zeile = pause ? "pausiert" : "läuft";
            else if (pause) zeile = "pausiert  ·  noch " + restText;
            else {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.add(java.util.Calendar.SECOND, (int) Math.round(dauer - pos));
                zeile = "noch " + restText + "  ·  fertig ca. " + String.format(Locale.GERMAN, "%d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
            }
            klein.setTextSize(22 * zoom);
            klein.setAlpha(Math.round(0xE6 * al / 255f));
            c.drawText(kuerzen(zeile, klein, breite), l, r.bottom - 22 * zoom, klein);
            klein.setTextSize(26);
            klein.setAlpha(0xB3);
            karte.setTextSize(26);
            karte.setAlpha(255);
            p.setAlpha(255);
        }

        // -------------------------------------------------- Foto-Wand (wie in Plex)

        private boolean rasterZeichnen(Canvas c, float dt) {
            boolean weiter = false;
            Seite s = seite;
            // ruhiger, stark abgedunkelter Hintergrund, damit die Bilder leuchten
            p.setColor(0xC7050608);
            c.drawRect(0, 0, 1920, 1080, p);
            p.setShader(new LinearGradient(0, 0, 0, 360, 0x73000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, 1920, 360, p);
            p.setShader(null);

            JSONObject fokus = fokusEintrag();
            if (fokus != letzterFokus) {
                letzterFokus = fokus;
                fokusZoom = 1f;
            }
            fokusZoom = naeher(fokusZoom, 1.1f, dt, 60);
            if (fokusZoom != 1.1f) weiter = true;

            // grosser Kopf nur in der ersten Zeile, sonst rutscht er als kleine Zeile nach oben
            float kz = s.akt == 0 ? 1f : 0f;
            s.kopfAnim = naeher(s.kopfAnim, kz, dt, 70);
            if (s.kopfAnim != kz) weiter = true;
            s.anim = naeher(s.anim, s.akt, dt, 70);
            if (s.anim != s.akt) weiter = true;
            float k = Ani.EASE.getInterpolation(Math.max(0f, Math.min(1f, s.kopfAnim)));
            float oben = RASTER_OBEN_KLEIN + (RASTER_OBEN_GROSS - RASTER_OBEN_KLEIN) * k;

            // Kopf: Name, darunter Zeitraum und Anzahl (nur gross), rechts das Foto im Fokus
            String name = s.kopf != null ? ordnerName(s.kopf) : s.titel;
            float titelY = 80 + (190 - 80) * k;
            gross.setTextSize(44 + 40 * k);
            gross.setShadowLayer(Ani.schatten(24), 0, 3, 0x80000000);
            c.drawText(kuerzen(name, gross, 1180 + 520 * k), LINKS - 3, titelY, gross);
            gross.clearShadowLayer();
            gross.setTextSize(130);
            if (s.kopfInfo != null && !s.kopfInfo.isEmpty() && k > 0.02f) {
                meta.setAlpha(Math.round(0xE6 * k));
                c.drawText(s.kopfInfo, LINKS, titelY + 62 * k, meta);
                meta.setAlpha(0xE6);
            }
            if (fokus != null && !fokus.optBoolean("IsFolder", false)) {
                int nr = 0, gesamt = 0;
                for (int i = 0; i < s.reihen.size(); i++) {
                    Reihe rr = s.reihen.get(i);
                    for (int j = 0; j < rr.eintraege.size(); j++) {
                        if (rr.eintraege.get(j).optBoolean("IsFolder", false)) continue;
                        gesamt++;
                        if (i < s.akt || (i == s.akt && j <= rr.fokus)) nr++;
                    }
                }
                String d = datum(fokus);
                String txt = (d != null ? d + "      " : "") + nr + " / " + gesamt;
                meta.setTextAlign(Paint.Align.RIGHT);
                c.drawText(txt, 1810, 80 + (252 - 80) * k, meta);
                meta.setTextAlign(Paint.Align.LEFT);
            }

            if (s.reihen.isEmpty()) {
                if (s.leer != null) {
                    c.drawText(s.leer, LINKS, oben + 60, meta);
                } else {
                    // lädt noch: Platzhalter-Wand
                    for (int i = 0; i < 4; i++)
                        for (int j = 0; j < FOTO_SPALTEN; j++) {
                            float x = LINKS + j * (FOTO_W + FOTO_LUECKE), y = oben + i * (FOTO_H + FOTO_LUECKE);
                            r.set(x, y, x + FOTO_W, y + FOTO_H);
                            p.setColor(0x14FFFFFF);
                            c.drawRoundRect(r, 12, 12, p);
                        }
                }
                return weiter;
            }

            float step = s.kh + s.luecke;
            float gesamtH = s.reihen.size() * step - s.luecke;
            float maxScroll = Math.max(0f, gesamtH - (1080 - RASTER_OBEN_KLEIN - 40));
            // die Zeile im Fokus steht als zweite Zeile, so sieht man immer, was darüber war
            float scroll = Math.min(maxScroll, Math.max(0f, s.anim - 1f) * step);
            float zoom = 1f + (fokusZoom - 1f) * 0.7f;

            c.save();
            c.clipRect(0, oben - 28, 1920, 1080);
            float fx = -1, fy = -1;
            JSONObject fe = null;
            for (int i = 0; i < s.reihen.size(); i++) {
                float y = oben + i * step - scroll;
                if (y > 1080 || y + s.kh < oben - 28) continue;
                float a = y < oben ? Math.max(0f, 1f - (oben - y) / (s.kh * 0.8f)) : 1f;
                if (a <= 0.01f) continue;
                Reihe rh = s.reihen.get(i);
                for (int j = 0; j < rh.eintraege.size(); j++) {
                    float x = LINKS + j * (s.kw + s.luecke);
                    if (i == s.akt && j == rh.fokus) {
                        fx = x;
                        fy = y;
                        fe = rh.eintraege.get(j);
                        continue;
                    }
                    rasterKachel(c, rh.eintraege.get(j), x, y, s.kw, s.kh, 1f, Math.round(235 * a), false);
                }
            }
            if (fe != null) rasterKachel(c, fe, fx, fy, s.kw, s.kh, zoom, 255, true);
            c.restore();
            return weiter;
        }

        private void rasterKachel(Canvas c, JSONObject e, float x, float y, int w0, int h0, float zoom, int al, boolean fokus) {
            float cx = x + w0 / 2f, cy = y + h0 / 2f, w = w0 * zoom, hh = h0 * zoom;
            r.set(cx - w / 2, cy - hh / 2, cx + w / 2, cy + hh / 2);
            float rad = 12 * zoom;
            if (fokus) {
                p.setColor(0xFF000000);
                p.setShadowLayer(Ani.schatten(40), 0, 18, 0x99000000);
                c.drawRoundRect(r, rad, rad, p);
                p.clearShadowLayer();
            }
            String url = rasterUrl(e);
            Bitmap b = url != null ? bilder.sofort(url) : null;
            if (b == null && url != null && !bilder.fehlgeschlagen(url)) bilder.laden(url, (u, bm) -> invalidate());
            boolean ordner = e.optBoolean("IsFolder", false);
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
                p.setColor(ordner ? 0x2EFFFFFF : 0x1AFFFFFF);
                p.setAlpha(Math.round((ordner ? 0x2E : 0x1A) * al / 255f));
                c.drawRoundRect(r, rad, rad, p);
            }
            if (ordner) {
                // Name unten im Bild auf dunklem Verlauf, bis zu zwei Zeilen
                c.save();
                Path clip = new Path();
                clip.addRoundRect(r, rad, rad, Path.Direction.CW);
                c.clipPath(clip);
                p.setShader(new LinearGradient(0, r.bottom - hh * 0.72f, 0, r.bottom, 0x00000000, 0xE6000000, Shader.TileMode.CLAMP));
                p.setAlpha(al);
                c.drawRect(r.left, r.bottom - hh * 0.72f, r.right, r.bottom, p);
                p.setShader(null);
                c.restore();
                karte.setTextSize(23 * zoom);
                karte.setAlpha(al);
                StaticLayout sl = umbruch(ordnerName(e), karte, (int) (w - 28 * zoom), 2);
                c.save();
                c.translate(r.left + 14 * zoom, r.bottom - 12 * zoom - sl.getHeight());
                sl.draw(c);
                c.restore();
                karte.setTextSize(26);
                karte.setAlpha(255);
            }
            if ("Video".equals(typ(e))) {
                float px = r.right - 26 * zoom, py = r.top + 26 * zoom;
                p.setColor(0xB3000000);
                p.setAlpha(Math.round(0xB3 * al / 255f));
                c.drawCircle(px, py, 16 * zoom, p);
                Path d = new Path();
                d.moveTo(px - 5 * zoom, py - 7 * zoom);
                d.lineTo(px + 7 * zoom, py);
                d.lineTo(px - 5 * zoom, py + 7 * zoom);
                d.close();
                p.setColor(0xFFFFFFFF);
                p.setAlpha(al);
                c.drawPath(d, p);
            }
            if (fokus) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(4f);
                p.setColor(0xF2FFFFFF);
                c.drawRoundRect(r, rad, rad, p);
                p.setStyle(Paint.Style.FILL);
            }
            p.setAlpha(255);
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
            String t = typ(e);
            if ("Gesehen".equals(t)) {
                gesehenKarte(c, e, rad, al);
                p.setAlpha(255);
                return;
            }
            String url = kartenUrl(e, form);
            Bitmap b = url != null ? bilder.sofort(url) : null;
            if (b == null && url != null && !bilder.fehlgeschlagen(url)) bilder.laden(url, (u, bm) -> invalidate());
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
            // Fortschritt angefangener Filme/Folgen – nicht bei Kategorien, Serien oder Ordnern (Wunsch Lukas 02.11.2026)
            float f = e.optBoolean("IsFolder", false) ? 0f : Jellyfin.fortschritt(e);
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
            boolean gesehenHaken = "Movie".equals(t) ? Gesehen.hat(e) : Jellyfin.gesehen(e);
            if (gesehenHaken && !ordner) {
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

        /** Kachel „Gesehen“ in den Kategorien: ruhiges Blaugrau, Auge mit Haken, Anzahl. */
        private void gesehenKarte(Canvas c, JSONObject e, float rad, int al) {
            p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, 0xFF56637A, 0xFF242B38, Shader.TileMode.CLAMP));
            p.setAlpha(al);
            c.drawRoundRect(r, rad, rad, p);
            p.setShader(null);
            float cx = r.centerX(), cy = r.top + r.height() * 0.40f, s = r.width() / 196f;
            // Auge
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(5f * s);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xFFFFFFFF);
            p.setAlpha(al);
            Path auge = new Path();
            auge.moveTo(cx - 46 * s, cy);
            auge.quadTo(cx, cy - 44 * s, cx + 46 * s, cy);
            auge.quadTo(cx, cy + 44 * s, cx - 46 * s, cy);
            c.drawPath(auge, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, 13 * s, p);
            // gelber Haken
            p.setColor(GELB);
            p.setAlpha(al);
            c.drawCircle(cx + 40 * s, cy + 26 * s, 17 * s, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4.2f * s);
            p.setColor(0xFF1A1C22);
            p.setAlpha(al);
            c.drawLine(cx + 32 * s, cy + 26 * s, cx + 38 * s, cy + 32 * s, p);
            c.drawLine(cx + 38 * s, cy + 32 * s, cx + 49 * s, cy + 19 * s, p);
            p.setStyle(Paint.Style.FILL);
            karte.setTextSize(30 * s);
            karte.setAlpha(al);
            karte.setTextAlign(Paint.Align.CENTER);
            c.drawText("Gesehen", cx, r.top + r.height() * 0.74f, karte);
            int n = e.optInt("ChildCount", 0);
            klein.setTextSize(22 * s);
            klein.setAlpha(Math.round(0xB3 * al / 255f));
            klein.setTextAlign(Paint.Align.CENTER);
            c.drawText(n == 1 ? "1 Film" : n + " Filme", cx, r.top + r.height() * 0.85f, klein);
            karte.setTextAlign(Paint.Align.LEFT);
            karte.setTextSize(26);
            karte.setAlpha(255);
            klein.setTextAlign(Paint.Align.LEFT);
            klein.setTextSize(26);
            klein.setAlpha(0xB3);
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

            // Bei „Empfohlen“ steht schon in der Liste, wer wie bewertet hat (dafür ohne Restzeit und mit kürzerem Text)
            boolean empf = bereich == Bereich.EMPFOHLEN && !detail;
            float famA = Math.max(detailAnim, empf ? 1f : 0f);
            if (famA > 0.01f) {
                Familie.Info fam = Familie.fuer(e);
                if (fam != null) y = familieZeichnen(c, fam, y, famA);
            }

            long pos = Jellyfin.position(e), ges = e.optLong("RunTimeTicks", 0);
            if (!empf && pos > 0 && ges > pos && !Jellyfin.gesehen(e)) {
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
                StaticLayout sl = umbruch(inhalt, text, 880, detail ? 8 : empf ? 2 : 3);
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
            List<String[]> k = knoepfeFuer(e);
            float x = LINKS;
            for (int i = 0; i < k.size(); i++) {
                String art = k.get(i)[0], text = k.get(i)[1];
                boolean f = i == detailKnopf;
                float w = knopf.measureText(text) + 120;
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
                float tx = x + 42, ty = y + 42;
                Path d = new Path();
                switch (art) {
                    case "vorne":
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
                        break;
                    case "trailer":
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(3f);
                        c.drawRoundRect(new RectF(tx - 15, ty - 11, tx + 15, ty + 11), 5, 5, p);
                        p.setStyle(Paint.Style.FILL);
                        d.moveTo(tx - 4, ty - 6);
                        d.lineTo(tx + 6, ty);
                        d.lineTo(tx - 4, ty + 6);
                        d.close();
                        c.drawPath(d, p);
                        break;
                    case "merken":
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(3.6f);
                        p.setStrokeCap(Paint.Cap.ROUND);
                        c.drawLine(tx, ty - 12, tx, ty + 12, p);
                        c.drawLine(tx - 12, ty, tx + 12, ty, p);
                        p.setStyle(Paint.Style.FILL);
                        break;
                    case "gemerkt":
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(3.6f);
                        p.setStrokeCap(Paint.Cap.ROUND);
                        p.setStrokeJoin(Paint.Join.ROUND);
                        d.moveTo(tx - 12, ty + 1);
                        d.lineTo(tx - 3, ty + 9);
                        d.lineTo(tx + 12, ty - 9);
                        c.drawPath(d, p);
                        p.setStyle(Paint.Style.FILL);
                        break;
                    default:
                        d.moveTo(tx - 9, ty - 13);
                        d.lineTo(tx + 13, ty);
                        d.lineTo(tx - 9, ty + 13);
                        d.close();
                        c.drawPath(d, p);
                }
                knopf.setColor(farbe);
                knopf.setAlpha(Math.round(255 * a));
                c.drawText(text, x + 74, y + 53, knopf);
                knopf.setColor(0xFFFFFFFF);
                x += w + 24;
            }
            p.setAlpha(255);
        }

        /** Bewertungen der Familie als kleine Pillen (Name in der eigenen Farbe, Note) und „Gesehen: …“. */
        private float familieZeichnen(Canvas c, Familie.Info f, float y, float a) {
            float x = LINKS;
            int al = Math.round(255 * a);
            if (!f.namen.isEmpty()) {
                int summe = 0;
                for (String n : f.namen) summe += f.noten.get(n);
                String sch = "Ø " + String.format(Locale.GERMAN, "%.1f", summe / (float) f.namen.size());
                meta.setColor(GELB);
                meta.setAlpha(al);
                c.drawText(sch, x, y, meta);
                x += meta.measureText(sch) + 22;
                meta.setColor(0xE6FFFFFF);
                karte.setTextSize(26);
                for (String n : f.namen) {
                    String z = String.valueOf(f.noten.get(n));
                    float wn = karte.measureText(n), wz = karte.measureText(z);
                    float w = wn + wz + 10 + 36;
                    r.set(x, y - 33, x + w, y + 13);
                    p.setColor(0x1FFFFFFF);
                    p.setAlpha(Math.round(0x1F * a));
                    c.drawRoundRect(r, 23, 23, p);
                    Integer fc = Familie.FARBEN.get(n);
                    karte.setColor(fc != null ? fc : 0xFFFFFFFF);
                    karte.setAlpha(al);
                    c.drawText(n, x + 18, y, karte);
                    karte.setColor(0xFFFFFFFF);
                    karte.setAlpha(al);
                    c.drawText(z, x + 18 + wn + 10, y, karte);
                    x += w + 12;
                }
                karte.setColor(0xFFFFFFFF);
            }
            if (!f.gesehen.isEmpty()) {
                klein.setAlpha(Math.round(0xB3 * a));
                c.drawText("Gesehen: " + TextUtils.join(", ", f.gesehen), x + 6, y, klein);
                klein.setAlpha(0xB3);
            }
            p.setAlpha(255);
            return y + 58;
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
