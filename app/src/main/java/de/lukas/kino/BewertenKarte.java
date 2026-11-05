package de.lukas.kino;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Das Bewerten-Fenster (Wunsch Lukas 03.11.2026): nach dem Film (ab 80 %) und über den Stern in den Details.
 * <ul>
 * <li>Ein Feld ohne Bewertung zeigt „–“. Erst ↑ oder ↓ holt die 5, dann zählt das Feld.</li>
 * <li>„Abgeben“ (OK) schickt nur Felder, die geändert wurden – ändert Papa nur seine Note, bleibt Mama unberührt.</li>
 * <li>Neben jedem Zähler ein kleines Feld „Keine Bewertung“ (links neben Papa bzw. dem eigenen Zähler, rechts neben Mama):
 *     OK darauf nimmt die eigene Bewertung heraus. Lukas und Jonas: geht sofort raus und schließt.</li>
 * <li>Jede Note hat ihren eigenen Look: 1 Gewitter mit Riss, ab 7 grün, 8 funkelt, 9 kräftig grün mit Konfetti,
 *     10 golden mit Konfetti, Luftschlangen und Lichtschein.</li>
 * <li>Seit 05.11.2026: nach dem Film ganz rechts „Noch wer dabei?“ – dort lassen sich Mitschauer dazunehmen (einzeln oder
 *     „Alle“). Jeder bekommt seinen Zähler mit seiner alten Note; viele Zähler werden kleiner gezeichnet.</li>
 * </ul>
 * Zeichnet nur den Inhalt; das Glas dahinter malt FilmeActivity (echtesGlas mit {@link #rahmen(float)}).
 */
final class BewertenKarte {

    static final int WEITER = 0, ABGEBEN = 1, SCHLIESSEN = 2;

    static final String[] WORT = {"Noch keine Note", "Katastrophe", "Schlecht", "Schwach", "Mäh", "Geht so",
            "Ganz okay", "Gut", "Sehr gut", "Super!", "Meisterwerk!"};
    /** Farbe der Zahl und der Punkte je Note (0 = noch keine). */
    static final int[] ZAHL = {0xFFFFFFFF, 0xFFE0322F, 0xFFEF4444, 0xFFF26B3A, 0xFFF59E0B, 0xFFF2C94C,
            0xFFB5D334, 0xFF6BD48A, 0xFF22C55E, 0xFF39E072, 0xFFFFD54A};
    private static final int[] KACHEL_OBEN = {0x1AFFFFFF, 0xFF3A1212, 0xCC3A1517, 0x553A1A12, 0x2EF59E0B, 0x26F2C94C,
            0x26B5D334, 0x3322C55E, 0x5522C55E, 0xD8116B3C, 0xFF7A5A00};
    private static final int[] KACHEL_UNTEN = {0x0DFFFFFF, 0xFF120707, 0xCC170A0B, 0x22000000, 0x0AF59E0B, 0x0AF2C94C,
            0x0AB5D334, 0x0D22C55E, 0x1422C55E, 0xD80A2E1C, 0xFF261B00};
    private static final int[] RAND = {0x33FFFFFF, 0xFF7F1D1D, 0xFF9B2C2C, 0x66F26B3A, 0x66F59E0B, 0x66F2C94C,
            0x66B5D334, 0x886BD48A, 0xAA22C55E, 0xFF4ADE80, 0xFFFFD54A};
    private static final int[] BUNT = {0xFFF2B705, 0xFFE8559A, 0xFF22C55E, 0xFF3E9BF0, 0xFFFF6B35, 0xFFA855F7, 0xFFFFFFFF, 0xFF2DD4BF};
    private static final int[] GRUEN = {0xFF4ADE80, 0xFF22C55E, 0xFFBBF7D0, 0xFFF2B705, 0xFFFFFFFF};

    // Maße (Bühne 1920x1080)
    private static final float TW = 380, TH = 430, XW = 150, XH = 230, LUECKE = 26, MITTE = 40, H = 660, MAX = 1840, RAND_B = 88;
    private static final int X = 0, Z = 1, PLUS = 2;

    final JSONObject film;
    final List<String> namen = new ArrayList<>();
    final List<Integer> alt = new ArrayList<>();      // alte Note (0 = keine)
    final List<Integer> wert = new ArrayList<>();     // gezeigte Note (0 = keine)
    final List<Boolean> geaendert = new ArrayList<>();
    private final List<Long> wechsel = new ArrayList<>();
    final int eigene;          // so viele gehören zu diesem Fernseher (Eltern 2, sonst 1); dahinter die Mitschauer
    private final boolean nachFilm;
    private int fokus;        // Index in slots(): Eltern 0 = X Papa, 1 = Papa, 2 = Mama, 3 = X Mama; sonst 0 = X, 1 = Zähler
    private boolean waehlen;  // Auswahl „Wer hat noch mitgeschaut?“ offen
    private int wFokus;
    private long danke;
    private boolean nurEntfernt;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint zahl, wort, name, titel, frage, klein, xText, chip;
    private final Path pfad = new Path();
    private final RectF r = new RectF(), kachel = new RectF();

    /** nachFilm = true: Fenster nach dem Film (mit „Noch wer dabei?“), sonst über den Stern in den Details. */
    BewertenKarte(Context c, JSONObject film, String[] namen, int[] alt, boolean nachFilm) {
        this.film = film;
        for (int i = 0; i < namen.length; i++) {
            this.namen.add(namen[i]);
            this.alt.add(alt[i]);
            wert.add(alt[i]);
            geaendert.add(false);
            wechsel.add(0L);
        }
        eigene = namen.length;
        this.nachFilm = nachFilm;
        fokus = 1;
        zahl = stift(c, 150, 800);
        wort = stift(c, 30, 650);
        name = stift(c, 34, 650);
        titel = stift(c, 28, 600);
        frage = stift(c, 42, 650);
        klein = stift(c, 26, 500);
        xText = stift(c, 24, 600);
        chip = stift(c, 30, 650);
    }

    private static TextPaint stift(Context c, float g, int w) {
        TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        t.setTypeface(Schriften.gewicht(c, w));
        t.setTextSize(g);
        t.setColor(0xFFFFFFFF);
        t.setTextAlign(Paint.Align.CENTER);
        return t;
    }

    /** Mehr als ein Zähler (Eltern oder Mitschauer dabei). */
    boolean mehr() {
        return namen.size() > 1;
    }

    /** Wer noch dazukommen kann. */
    List<String> kandidaten() {
        List<String> l = new ArrayList<>();
        for (String n : Bewerten.ALLE) if (!namen.contains(n)) l.add(n);
        return l;
    }

    /** Auswahl: die Kandidaten, bei mehreren dazu „Alle“ (null). */
    private List<String> wahl() {
        List<String> l = kandidaten();
        if (l.size() > 1) l.add(null);
        return l;
    }

    /** Mitschauer (nach den eigenen). */
    List<String> dazu() {
        return new ArrayList<>(namen.subList(eigene, namen.size()));
    }

    /** Felder der Reihe: {Art, Person}. Art X = Keine Bewertung, Z = Zähler, PLUS = Noch wer dabei? */
    private List<int[]> slots() {
        List<int[]> l = new ArrayList<>();
        l.add(new int[]{X, 0});
        l.add(new int[]{Z, 0});
        if (eigene > 1) {
            l.add(new int[]{Z, 1});
            l.add(new int[]{X, 1});
        }
        for (int i = eigene; i < namen.size(); i++) l.add(new int[]{Z, i});
        if (nachFilm && !kandidaten().isEmpty()) l.add(new int[]{PLUS, -1});
        return l;
    }

    private int[] slot() {
        List<int[]> l = slots();
        return l.get(Math.max(0, Math.min(fokus, l.size() - 1)));
    }

    private int zSlot(int pi) {
        List<int[]> l = slots();
        for (int i = 0; i < l.size(); i++) if (l.get(i)[0] == Z && l.get(i)[1] == pi) return i;
        return 1;
    }

    private static float breite(int art) {
        return art == Z ? TW : XW;
    }

    /** Breite der Reihe ohne Verkleinerung (allein ohne Plus mit leerem Platz rechts, damit der Zähler mittig bleibt). */
    private float reiheBreite(List<int[]> l) {
        float b = 0;
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) b += l.get(i - 1)[0] == Z && l.get(i)[0] == Z ? MITTE : LUECKE;
            b += breite(l.get(i)[0]);
        }
        if (l.size() == 2) b += LUECKE + XW;
        return b;
    }

    boolean imDanke() {
        return danke > 0;
    }

    void danke() {
        danke = SystemClock.uptimeMillis();
    }

    /** Was abgeschickt wird: {Person, Note}, Note 0 = Bewertung herausnehmen. Nur geänderte Felder. */
    List<int[]> aenderungen() {
        List<int[]> l = new ArrayList<>();
        for (int i = 0; i < namen.size(); i++) {
            if (!geaendert.get(i) || wert.get(i).equals(alt.get(i))) continue;
            if (wert.get(i) == 0 && alt.get(i) == 0) continue;
            l.add(new int[]{i, wert.get(i)});
        }
        return l;
    }

    boolean mitZehn() {
        for (int[] a : aenderungen()) if (a[1] == 10) return true;
        return false;
    }

    /** Mitschauer dazunehmen: eigener Zähler mit seiner alten Note (aus der Familie), Fokus auf den ersten neuen. */
    private void hinzu(List<String> neu) {
        Familie.Info info = Familie.fuer(film);
        int erster = -1;
        for (String n : neu) {
            if (namen.contains(n)) continue;
            Integer w = info != null ? info.noten.get(n) : null;
            int a = w != null && w >= 1 && w <= 10 ? w : 0;
            namen.add(n);
            alt.add(a);
            wert.add(a);
            geaendert.add(false);
            wechsel.add(0L);
            if (erster < 0) erster = namen.size() - 1;
        }
        if (erster >= 0) fokus = zSlot(erster);
    }

    private void setzeWert(int pi, int note) {
        if (wert.get(pi) == note) return;
        wert.set(pi, note);
        geaendert.set(pi, true);
        wechsel.set(pi, SystemClock.uptimeMillis());
    }

    /** Tasten: WEITER (nur neu zeichnen), ABGEBEN oder SCHLIESSEN. */
    int taste(int k, boolean ok, boolean zurueck, int wdh) {
        if (danke > 0) return (ok || zurueck) ? SCHLIESSEN : WEITER;
        if (waehlen) {
            List<String> w = wahl();
            if (zurueck) waehlen = false;
            else if (k == KeyEvent.KEYCODE_DPAD_LEFT && wdh == 0) wFokus = Math.max(0, wFokus - 1);
            else if (k == KeyEvent.KEYCODE_DPAD_RIGHT && wdh == 0) wFokus = Math.min(w.size() - 1, wFokus + 1);
            else if (ok && wdh == 0 && !w.isEmpty()) {
                String x = w.get(Math.min(wFokus, w.size() - 1));
                waehlen = false;
                List<String> neu = new ArrayList<>();
                if (x == null) neu.addAll(kandidaten());
                else neu.add(x);
                hinzu(neu);
            }
            return WEITER;
        }
        if (zurueck) return SCHLIESSEN;
        int[] s = slot();
        int pi = s[1];
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (s[0] == X) {
                    fokus = zSlot(pi);
                    return WEITER;
                }
                if (s[0] == PLUS) return WEITER;
                int vorher = wert.get(pi);
                if (vorher == 0) setzeWert(pi, 5);
                else if (k == KeyEvent.KEYCODE_DPAD_UP) setzeWert(pi, Math.min(10, vorher + 1));
                else setzeWert(pi, Math.max(1, vorher - 1));
                return WEITER;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (wdh == 0) fokus = Math.max(0, fokus - 1);
                return WEITER;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (wdh == 0) fokus = Math.min(slots().size() - 1, fokus + 1);
                return WEITER;
            default:
                if (!ok || wdh > 0) return WEITER;
                if (s[0] == PLUS) {
                    waehlen = true;
                    wFokus = 0;
                    return WEITER;
                }
                if (s[0] == X) {
                    wert.set(pi, 0);
                    geaendert.set(pi, true);
                    wechsel.set(pi, SystemClock.uptimeMillis());
                    if (!mehr()) {
                        nurEntfernt = true;
                        return aenderungen().isEmpty() ? SCHLIESSEN : ABGEBEN;
                    }
                    fokus = zSlot(pi);   // zurück auf den Zähler dieser Person
                    return WEITER;
                }
                nurEntfernt = true;
                for (int[] a : aenderungen()) if (a[1] > 0) nurEntfernt = false;
                return ABGEBEN;
        }
    }

    /** Rahmen der Glas-Karte (für echtesGlas in FilmeActivity). */
    RectF rahmen(float a) {
        float breite = Math.min(MAX, reiheBreite(slots()) + RAND_B);
        if (danke > 0) breite = Math.min(MAX, Math.max(breite, 240 + aenderungen().size() * 330));
        float oben = 190 + (1 - a) * 40;
        return new RectF(960 - breite / 2, oben, 960 + breite / 2, oben + H);
    }

    // ------------------------------------------------------------------ Zeichnen

    private static float h(int k) {
        // fester Zufall je Teilchen (immer gleich, ohne Zustand)
        int x = k * 374761393 + 668265263;
        x = (x ^ (x >>> 13)) * 1274126177;
        x ^= x >>> 16;
        return (x & 0xFFFFFF) / (float) 0x1000000;
    }

    private void farbe(int argb, float alpha) {
        p.setColor(argb);
        p.setAlpha(Math.round(((argb >>> 24) & 255) * Math.max(0f, Math.min(1f, alpha))));
    }

    /** Zeichnet den Inhalt; true = weiter animieren. */
    boolean zeichnen(Canvas c, float a) {
        long jetzt = SystemClock.uptimeMillis();
        float t = (jetzt % 1_000_000L) / 1000f;
        RectF k = rahmen(a);
        String filmName = film != null ? film.optString("Name") : "";
        if (danke > 0) return dankeZeichnen(c, k, a, jetzt, filmName);

        titel.setAlpha(Math.round(0xB3 * a));
        c.drawText(kuerzen(filmName, titel, k.width() - 120), 960, k.top + 70, titel);
        frage.setAlpha(Math.round(255 * a));
        c.drawText(kuerzen(Bewerten.frage(namen.size() > eigene), frage, k.width() - 80), 960, k.top + 128, frage);

        float oben = k.top + 170;
        boolean weiter = false;
        List<int[]> l = slots();
        fokus = Math.min(fokus, l.size() - 1);
        float rb = reiheBreite(l);
        float skala = rb + RAND_B > MAX ? (MAX - RAND_B) / rb : 1f;
        c.save();
        if (skala < 1f) c.scale(skala, skala, 960, oben);
        float x = 960 - rb / 2;
        for (int i = 0; i < l.size(); i++) {
            int[] s = l.get(i);
            if (i > 0) x += l.get(i - 1)[0] == Z && s[0] == Z ? MITTE : LUECKE;
            if (s[0] == X) xFeld(c, x, oben, s[1], i, a);
            else if (s[0] == PLUS) plusFeld(c, x, oben, i, a);
            else weiter |= zaehler(c, x, oben, s[1], i, a, t, jetzt);
            x += breite(s[0]);
        }
        c.restore();
        if (waehlen) wahlZeichnen(c, k, a);

        int[] s = slot();
        boolean elternAllein = eigene == 2 && namen.size() == 2;
        boolean plus = nachFilm && !kandidaten().isEmpty();
        String hilfe;
        if (waehlen) {
            hilfe = "OK  dazunehmen      ← →  wählen      Zurück  abbrechen";
        } else if (s[0] == PLUS) {
            hilfe = "OK  noch jemand hat mitgeschaut      ←  zurück      Zurück  später";
        } else if (s[0] == X) {
            hilfe = mehr() ? "OK  keine Bewertung für " + Bewerten.kurz(namen.get(s[1])) + "      ← →  wechseln      Zurück  später"
                    : "OK  keine Bewertung      →  zurück zur Note      Zurück  später";
        } else if (elternAllein && !plus) {
            hilfe = "↑ ↓  Note      ← →  Papa / Mama / keine      OK  abgeben      Zurück  später";
        } else if (mehr()) {
            hilfe = "↑ ↓  Note      ← →  wechseln      OK  abgeben      Zurück  später";
        } else {
            hilfe = "↑ ↓  Note      ←  keine Bewertung" + (plus ? "      →  noch wer dabei?" : "") + "      OK  abgeben      Zurück  später";
        }
        klein.setAlpha(Math.round(0xC8 * a));
        c.drawText(hilfe, 960, k.bottom + 50, klein);
        return weiter;
    }

    private void xFeld(Canvas c, float x, float oben, int pi, int slot, float a) {
        boolean aktiv = fokus == slot && !waehlen;
        boolean entfernt = geaendert.get(pi) && wert.get(pi) == 0 && alt.get(pi) > 0;
        float y = oben + (TH - XH) / 2;
        r.set(x, y, x + XW, y + XH);
        farbe(entfernt ? 0x40E5484D : (aktiv ? 0x2EFFFFFF : 0x12FFFFFF), a);
        c.drawRoundRect(r, 30, 30, p);
        if (aktiv) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3);
            farbe(0xDDFFFFFF, a);
            c.drawRoundRect(r, 30, 30, p);
            p.setStyle(Paint.Style.FILL);
        }
        // Symbol: Kreis mit Schrägstrich
        float cx = x + XW / 2, cy = y + 78;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(5);
        p.setStrokeCap(Paint.Cap.ROUND);
        farbe(entfernt ? 0xFFFCA5A5 : 0xFFFFFFFF, a * (aktiv ? 0.95f : 0.55f));
        c.drawCircle(cx, cy, 28, p);
        c.drawLine(cx - 19, cy + 19, cx + 19, cy - 19, p);
        p.setStyle(Paint.Style.FILL);
        xText.setColor(entfernt ? 0xFFFCA5A5 : 0xFFFFFFFF);
        xText.setAlpha(Math.round(255 * a * (aktiv ? 1f : 0.6f)));
        c.drawText(entfernt ? "Wird" : "Keine", cx, y + 150, xText);
        c.drawText(entfernt ? "entfernt" : "Bewertung", cx, y + 180, xText);
    }

    /** Feld „Noch wer dabei?“ (nur nach dem Film): Kreis mit Plus. */
    private void plusFeld(Canvas c, float x, float oben, int slot, float a) {
        boolean aktiv = fokus == slot && !waehlen;
        float y = oben + (TH - XH) / 2;
        r.set(x, y, x + XW, y + XH);
        farbe(aktiv ? 0x2EFFFFFF : 0x12FFFFFF, a);
        c.drawRoundRect(r, 30, 30, p);
        if (aktiv) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3);
            farbe(0xDDFFFFFF, a);
            c.drawRoundRect(r, 30, 30, p);
        }
        float cx = x + XW / 2, cy = y + 78;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(5);
        p.setStrokeCap(Paint.Cap.ROUND);
        farbe(0xFFFFFFFF, a * (aktiv ? 0.95f : 0.55f));
        c.drawCircle(cx, cy, 28, p);
        c.drawLine(cx - 13, cy, cx + 13, cy, p);
        c.drawLine(cx, cy - 13, cx, cy + 13, p);
        p.setStyle(Paint.Style.FILL);
        xText.setColor(0xFFFFFFFF);
        xText.setAlpha(Math.round(255 * a * (aktiv ? 1f : 0.6f)));
        c.drawText("Noch wer", cx, y + 150, xText);
        c.drawText("dabei?", cx, y + 180, xText);
    }

    /** Auswahl „Wer hat noch mitgeschaut?“ über der Reihe: Namen als Pillen in ihrer Farbe, bei mehreren „Alle“. */
    private void wahlZeichnen(Canvas c, RectF k, float a) {
        float oben = k.top + 160;
        r.set(k.left + 30, oben, k.right - 30, oben + 450);
        farbe(0xFB0C0E14, a);
        c.drawRoundRect(r, 40, 40, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        farbe(0x24FFFFFF, a);
        c.drawRoundRect(r, 40, 40, p);
        p.setStyle(Paint.Style.FILL);
        frage.setAlpha(Math.round(255 * a));
        frage.setTextSize(40);
        c.drawText("Wer hat noch mitgeschaut?", 960, oben + 120, frage);
        frage.setTextSize(42);

        List<String> w = wahl();
        wFokus = Math.min(wFokus, Math.max(0, w.size() - 1));
        float ph = 88, gap = 14, maxB = r.width() - 30;
        // Pillen messen und auf Zeilen verteilen
        float[] bw = new float[w.size()];
        List<List<Integer>> zeilen = new ArrayList<>();
        List<Integer> zeile = new ArrayList<>();
        float zb = 0;
        for (int i = 0; i < w.size(); i++) {
            String txt = w.get(i) == null ? "Alle" : Bewerten.kurz(w.get(i));
            bw[i] = chip.measureText(txt) + 86;
            if (!zeile.isEmpty() && zb + gap + bw[i] > maxB) {
                zeilen.add(zeile);
                zeile = new ArrayList<>();
                zb = 0;
            }
            zb += (zeile.isEmpty() ? 0 : gap) + bw[i];
            zeile.add(i);
        }
        if (!zeile.isEmpty()) zeilen.add(zeile);
        float y = oben + 190 + (zeilen.size() == 1 ? 44 : 0);
        for (List<Integer> z : zeilen) {
            float summe = 0;
            for (int i : z) summe += bw[i];
            summe += gap * (z.size() - 1);
            float x = 960 - summe / 2;
            for (int i : z) {
                String n = w.get(i);
                boolean f = i == wFokus;
                kachel.set(x, y, x + bw[i], y + ph);
                if (f) {
                    farbe(0xFFFFFFFF, a);
                    c.drawRoundRect(kachel, ph / 2, ph / 2, p);
                } else {
                    farbe(0x1AFFFFFF, a);
                    c.drawRoundRect(kachel, ph / 2, ph / 2, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2);
                    farbe(0x2EFFFFFF, a);
                    c.drawRoundRect(kachel, ph / 2, ph / 2, p);
                    p.setStyle(Paint.Style.FILL);
                }
                // Farbpunkt der Person (Alle: vier kleine Punkte)
                float px = x + 36, py = y + ph / 2;
                if (n == null) {
                    int[] f4 = {0xFFF2B705, 0xFFE8559A, 0xFF1FA463, 0xFF2F6FEB};
                    for (int q = 0; q < 4; q++) {
                        farbe(f4[q], a);
                        c.drawCircle(px - 6 + (q % 2) * 12, py - 6 + (q / 2) * 12, 5, p);
                    }
                } else {
                    Integer fc = Familie.FARBEN.get(n);
                    farbe(fc != null ? fc : 0xFFFFFFFF, a);
                    c.drawCircle(px, py, 11, p);
                }
                chip.setColor(f ? 0xFF111317 : 0xFFFFFFFF);
                chip.setAlpha(Math.round(255 * a));
                chip.setTextAlign(Paint.Align.LEFT);
                c.drawText(n == null ? "Alle" : Bewerten.kurz(n), x + 58, y + ph / 2 + 11, chip);
                chip.setTextAlign(Paint.Align.CENTER);
                x += bw[i] + gap;
            }
            y += ph + gap;
        }
    }

    private boolean zaehler(Canvas c, float x, float oben, int pi, int slot, float a, float t, long jetzt) {
        boolean aktiv = fokus == slot && !waehlen;
        int note = wert.get(pi);
        float ia = mehr() && !aktiv ? 0.8f : 1f;
        float cx = x + TW / 2;
        kachel.set(x, oben, x + TW, oben + TH);
        boolean weiter = note == 1 || note == 2 || note >= 8;

        // Kachel
        p.setShader(new LinearGradient(0, oben, 0, oben + TH, KACHEL_OBEN[note], KACHEL_UNTEN[note], Shader.TileMode.CLAMP));
        p.setAlpha(Math.round(255 * a * ia));
        c.drawRoundRect(kachel, 38, 38, p);
        p.setShader(null);

        c.save();
        pfad.reset();
        pfad.addRoundRect(kachel, 38, 38, Path.Direction.CW);
        c.clipPath(pfad);
        float fx = a * ia;
        if (note == 10) {
            schein(c, t, fx);
            konfetti(c, t, 60, BUNT, 1f, fx, pi);
            luftschlangen(c, t, fx);
        } else if (note == 9) {
            konfetti(c, t, 16, GRUEN, 0.7f, fx, pi);
            funkeln(c, cx, oben, t, 8, 0xFFFFFFFF, fx);
        } else if (note == 8) {
            funkeln(c, cx, oben, t, 4, 0xFFD1FAE5, fx);
        } else if (note == 1) {
            riss(c, fx);
            regen(c, cx, oben, t, 22, fx);
            wolke(c, cx, oben + 82, 1f, 0xFF4B5059, fx);
            blitz(c, cx, oben, t, fx);
        } else if (note == 2) {
            regen(c, cx + 70, oben, t, 6, fx * 0.7f);
            wolke(c, cx + 70, oben + 82, 0.7f, 0xFF6B7280, fx);
        }
        // Platzen beim Wechsel auf 10
        float seit = (jetzt - wechsel.get(pi)) / 1000f;
        if (note == 10 && wechsel.get(pi) > 0 && seit < 1.4f) explosion(c, cx, oben + 160, seit, fx, pi);
        c.restore();

        // Rand (Fokus weiß, sonst in der Farbe der Note)
        p.setStyle(Paint.Style.STROKE);
        if (note >= 9) {
            float puls = 0.6f + 0.4f * (float) Math.sin(t * 3.2);
            p.setStrokeWidth(4);
            p.setShadowLayer(note == 10 ? 26 * puls : 18, 0, 0, RAND[note]);
            farbe(RAND[note], a * ia);
            c.drawRoundRect(kachel, 38, 38, p);
            p.clearShadowLayer();
        } else if (!aktiv || !mehr()) {
            p.setStrokeWidth(2);
            farbe(RAND[note], a * ia);
            c.drawRoundRect(kachel, 38, 38, p);
        }
        if (aktiv && mehr()) {
            p.setStrokeWidth(3);
            farbe(0xE6FFFFFF, a);
            r.set(kachel.left - 6, kachel.top - 6, kachel.right + 6, kachel.bottom + 6);
            c.drawRoundRect(r, 44, 44, p);
        }
        p.setStyle(Paint.Style.FILL);

        // Pfeile
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(7);
        p.setStrokeCap(Paint.Cap.ROUND);
        farbe(0xFFFFFFFF, a * ia * (note < 10 ? 0.9f : 0.25f));
        c.drawLine(cx - 26, oben + 62, cx, oben + 38, p);
        c.drawLine(cx, oben + 38, cx + 26, oben + 62, p);
        farbe(0xFFFFFFFF, a * ia * (note != 1 ? 0.9f : 0.25f));
        c.drawLine(cx - 26, oben + 290, cx, oben + 314, p);
        c.drawLine(cx, oben + 314, cx + 26, oben + 290, p);
        p.setStyle(Paint.Style.FILL);

        // Zahl (Pop beim Wechsel, Wackeln bei der 1)
        float basis = oben + 222;
        float pop = 1f, wackeln = 0f;
        if (wechsel.get(pi) > 0 && seit < 0.28f) {
            pop = 1f + 0.25f * (float) Math.sin(Math.PI * seit / 0.28f);
            weiter = true;
        }
        if (note == 1 && wechsel.get(pi) > 0 && seit < 0.6f) wackeln = (float) Math.sin(seit * 55) * 10 * (1 - seit / 0.6f);
        c.save();
        c.translate(wackeln, 0);
        c.scale(pop, pop, cx, basis - 55);
        String z = note == 0 ? "–" : String.valueOf(note);
        if (note == 10) {
            zahl.setShader(new LinearGradient(0, basis - 112, 0, basis, new int[]{0xFFFFF6C2, 0xFFFFD54A, 0xFFE09A00},
                    null, Shader.TileMode.CLAMP));
            zahl.setShadowLayer(22 + 10 * (float) Math.sin(t * 3.2), 0, 0, 0xCCF2B705);
        } else if (note == 9 || note == 8) {
            zahl.setShadowLayer(note == 9 ? 22 : 12, 0, 0, note == 9 ? 0xCC22C55E : 0x8822C55E);
        } else if (note == 1) {
            zahl.setShadowLayer(14, 0, 4, 0xCC000000);
        }
        zahl.setColor(note == 0 ? 0x80FFFFFF : ZAHL[note]);
        zahl.setAlpha(Math.round((note == 0 ? 128 : 255) * a * ia));
        c.drawText(z, cx, basis, zahl);
        zahl.setShader(null);
        zahl.clearShadowLayer();
        c.restore();

        // Wort
        boolean entfernt = geaendert.get(pi) && note == 0 && alt.get(pi) > 0;
        wort.setColor(note == 0 ? (entfernt ? 0xFFFCA5A5 : 0xB3FFFFFF) : (note >= 9 || note <= 2 ? ZAHL[note] : 0xE6FFFFFF));
        wort.setAlpha(Math.round(255 * a * ia * (note == 0 ? 0.8f : 1f)));
        c.drawText(entfernt ? "Wird entfernt" : WORT[note], cx, oben + 268, wort);

        // zehn Punkte
        for (int q = 1; q <= 10; q++) {
            float px = cx - 135 + (q - 1) * 30;
            float py = oben + 352;
            boolean an = q <= note;
            int f = note == 10 ? BUNT[(q - 1) % BUNT.length] : ZAHL[note];
            if (note == 10 && an) py += (float) Math.sin(t * 5 + q * 0.7) * 3;
            farbe(an ? f : 0xFFFFFFFF, a * ia * (an ? 1f : 0.18f));
            c.drawCircle(px, py, q == note ? 8 : 6, p);
        }

        // wer
        Integer fn = Familie.FARBEN.get(namen.get(pi));
        name.setColor(fn != null ? fn : 0xFFFFFFFF);
        name.setAlpha(Math.round(255 * a * ia));
        c.drawText(Bewerten.schild(namen.get(pi)), cx, oben + 402, name);
        return weiter;
    }

    // ------------------------------------------------------------------ Effekte

    private void konfetti(Canvas c, float t, int anzahl, int[] farben, float tempo, float a, int saat) {
        float h0 = kachel.height() + 40;
        for (int k = 0; k < anzahl; k++) {
            int s = k * 3 + saat * 997;
            float x = kachel.left + h(s + 1) * kachel.width() + (float) Math.sin(t * 1.7 + k) * 14;
            float v = (70 + 110 * h(s + 2)) * tempo;
            float y = kachel.top - 20 + ((t * v + h(s + 4) * h0) % h0);
            float rot = t * (90 + 180 * h(s + 3)) + k * 37;
            float w = 10 + 6 * h(s + 3), hh = 6;
            c.save();
            c.rotate(rot, x, y);
            farbe(farben[k % farben.length], a * 0.95f);
            r.set(x - w / 2, y - hh / 2, x + w / 2, y + hh / 2);
            c.drawRoundRect(r, 2, 2, p);
            c.restore();
        }
    }

    private void luftschlangen(Canvas c, float t, float a) {
        float[] stellen = {0.08f, 0.28f, 0.5f, 0.72f, 0.92f};
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(6);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < stellen.length; i++) {
            float x0 = kachel.left + stellen[i] * kachel.width();
            float laenge = 110 + 90 * h(i * 11 + 5);
            pfad.reset();
            pfad.moveTo(x0, kachel.top - 4);
            for (float s = 6; s <= laenge; s += 6) {
                float amp = 16 * (s / laenge) + 4;
                float px = x0 + amp * (float) Math.sin(s / 11f + t * 3f + i * 1.3f);
                pfad.lineTo(px, kachel.top + s);
            }
            farbe(BUNT[(i * 3) % BUNT.length], a * 0.9f);
            c.drawPath(pfad, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void schein(Canvas c, float t, float a) {
        float u = (t % 2.8f) / 2.8f * 1.8f - 0.4f;
        float x = kachel.left + u * kachel.width();
        p.setShader(new LinearGradient(x - 90, kachel.top, x + 90, kachel.bottom,
                new int[]{0x00FFFFFF, 0x55FFF3C4, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        p.setAlpha(Math.round(255 * a));
        c.drawRect(kachel, p);
        p.setShader(null);
    }

    private void funkeln(Canvas c, float cx, float oben, float t, int anzahl, int farbe, float a) {
        for (int k = 0; k < anzahl; k++) {
            float x = cx + (h(k * 7 + 3) - 0.5f) * kachel.width() * 0.86f;
            float y = oben + 50 + h(k * 7 + 4) * (kachel.height() - 120);
            float puls = 0.5f + 0.5f * (float) Math.sin(t * 2.6f + k * 1.9f);
            float s = (8 + 10 * h(k * 7 + 5)) * (0.4f + 0.6f * puls);
            pfad.reset();
            pfad.moveTo(x, y - s);
            pfad.lineTo(x + s * 0.25f, y - s * 0.25f);
            pfad.lineTo(x + s, y);
            pfad.lineTo(x + s * 0.25f, y + s * 0.25f);
            pfad.lineTo(x, y + s);
            pfad.lineTo(x - s * 0.25f, y + s * 0.25f);
            pfad.lineTo(x - s, y);
            pfad.lineTo(x - s * 0.25f, y - s * 0.25f);
            pfad.close();
            farbe(farbe, a * (0.35f + 0.65f * puls));
            c.drawPath(pfad, p);
        }
    }

    private void wolke(Canvas c, float cx, float cy, float g, int farbe, float a) {
        farbe(farbe, a);
        c.drawCircle(cx - 44 * g, cy + 4 * g, 26 * g, p);
        c.drawCircle(cx - 8 * g, cy - 12 * g, 34 * g, p);
        c.drawCircle(cx + 34 * g, cy + 2 * g, 28 * g, p);
        r.set(cx - 70 * g, cy, cx + 62 * g, cy + 28 * g);
        c.drawRoundRect(r, 14 * g, 14 * g, p);
        farbe(0x22FFFFFF, a);
        c.drawCircle(cx - 14 * g, cy - 22 * g, 14 * g, p);
    }

    private void regen(Canvas c, float cx, float oben, float t, int anzahl, float a) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        p.setStrokeCap(Paint.Cap.ROUND);
        float start = oben + 112, weg = 230;
        for (int k = 0; k < anzahl; k++) {
            float x = cx + (h(k * 5 + 1) - 0.5f) * (anzahl > 8 ? 150 : 110);
            float d = (t * (420 + 160 * h(k * 5 + 2)) + h(k * 5 + 3) * weg) % weg;
            float y = start + d;
            farbe(0xFF93A4C3, a * 0.75f * Math.min(1f, (weg - d) / (weg * 0.25f)));
            c.drawLine(x, y, x - 4, y + 18, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void blitz(Canvas c, float cx, float oben, float t, float a) {
        float ph = t % 3.4f;
        if (ph < 0.12f) {
            farbe(0xFFFFFFFF, a * 0.16f * (1 - ph / 0.12f));
            c.drawRect(kachel, p);
        }
        if (ph < 0.2f) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(5);
            p.setStrokeJoin(Paint.Join.ROUND);
            pfad.reset();
            pfad.moveTo(cx + 18, oben + 106);
            pfad.lineTo(cx + 2, oben + 140);
            pfad.lineTo(cx + 16, oben + 142);
            pfad.lineTo(cx - 6, oben + 182);
            farbe(0xFFFDE68A, a * (1 - ph / 0.2f));
            c.drawPath(pfad, p);
            p.setStyle(Paint.Style.FILL);
        }
    }

    private void riss(Canvas c, float a) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f);
        p.setStrokeJoin(Paint.Join.MITER);
        float L = kachel.left, T = kachel.top, W = kachel.width(), Hh = kachel.height();
        pfad.reset();
        pfad.moveTo(L + W * 0.07f, T);
        pfad.lineTo(L + W * 0.15f, T + Hh * 0.13f);
        pfad.lineTo(L + W * 0.1f, T + Hh * 0.22f);
        pfad.lineTo(L + W * 0.19f, T + Hh * 0.35f);
        pfad.moveTo(L + W * 0.15f, T + Hh * 0.13f);
        pfad.lineTo(L + W * 0.24f, T + Hh * 0.17f);
        pfad.moveTo(L + W * 0.95f, T + Hh);
        pfad.lineTo(L + W * 0.86f, T + Hh * 0.87f);
        pfad.lineTo(L + W * 0.91f, T + Hh * 0.77f);
        pfad.lineTo(L + W * 0.81f, T + Hh * 0.66f);
        farbe(0xFFFFFFFF, a * 0.28f);
        c.drawPath(pfad, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void explosion(Canvas c, float cx, float cy, float tau, float a, int saat) {
        for (int k = 0; k < 40; k++) {
            int s = k * 13 + saat * 101;
            double w = h(s) * Math.PI * 2;
            float v = 300 + 520 * h(s + 7);
            float x = cx + (float) Math.cos(w) * v * tau;
            float y = cy + (float) Math.sin(w) * v * tau + 450 * tau * tau;
            farbe(BUNT[k % BUNT.length], a * Math.max(0f, 1 - tau / 1.4f));
            c.drawCircle(x, y, 5 + 4 * h(s + 3), p);
        }
    }

    private boolean dankeZeichnen(Canvas c, RectF k, float a, long jetzt, String filmName) {
        float t0 = Math.min(1f, (jetzt - danke) / 350f);
        float t = (jetzt % 1_000_000L) / 1000f;
        boolean zehn = mitZehn();
        if (zehn) {
            kachel.set(k);
            c.save();
            pfad.reset();
            pfad.addRoundRect(k, 52, 52, Path.Direction.CW);
            c.clipPath(pfad);
            konfetti(c, t, 70, BUNT, 1.1f, a, 3);
            c.restore();
        }
        float cy = k.top + 230;
        farbe(nurEntfernt ? 0xFF6B7280 : 0xFF22C55E, a);
        c.drawCircle(960, cy, 70 * (0.6f + 0.4f * Ani.AUS.getInterpolation(t0)), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(12);
        p.setStrokeCap(Paint.Cap.ROUND);
        farbe(0xFFFFFFFF, a);
        c.drawLine(930, cy + 2, 952, cy + 26, p);
        c.drawLine(952, cy + 26, 994, cy - 24, p);
        p.setStyle(Paint.Style.FILL);
        frage.setColor(0xFFFFFFFF);
        frage.setTextSize(46);
        frage.setAlpha(Math.round(255 * a));
        String text = nurEntfernt ? "Bewertung entfernt" : Bewerten.danke(mehr());
        c.drawText(text, 960, cy + 160, frage);
        frage.setTextSize(42);
        // wer was: nur die geänderten Felder, in der Farbe der Note
        List<int[]> l = aenderungen();
        float x = 960;
        float[] breiten = new float[l.size()];
        float summe = 0;
        String[] teile = new String[l.size()];
        for (int i = 0; i < l.size(); i++) {
            int[] e = l.get(i);
            teile[i] = (mehr() ? Bewerten.schild(namen.get(e[0])) + ":  " : "") + (e[1] == 0 ? "entfernt" : e[1] + " von 10");
            breiten[i] = wort.measureText(teile[i]);
            summe += breiten[i] + (i > 0 ? 60 : 0);
        }
        x = 960 - summe / 2;
        for (int i = 0; i < l.size(); i++) {
            int n = l.get(i)[1];
            wort.setColor(n == 0 ? 0xFFFCA5A5 : ZAHL[n]);
            wort.setAlpha(Math.round(255 * a));
            wort.setTextAlign(Paint.Align.LEFT);
            c.drawText(teile[i], x, cy + 225, wort);
            x += breiten[i] + 60;
        }
        wort.setTextAlign(Paint.Align.CENTER);
        titel.setAlpha(Math.round(0xB3 * a));
        c.drawText(kuerzen(filmName, titel, k.width() - 120), 960, cy + 280, titel);
        if (namen.size() > eigene) {
            List<String> kurz = new ArrayList<>();
            for (String n : dazu()) kurz.add(Bewerten.kurz(n));
            klein.setAlpha(Math.round(0xB3 * a));
            c.drawText("Auch als gesehen eingetragen: " + String.join(", ", kurz), 960, cy + 334, klein);
        }
        return t0 < 1f || zehn;
    }

    private static String kuerzen(String s, Paint p, float max) {
        if (s == null) return "";
        if (p.measureText(s) <= max) return s;
        String t = s;
        while (t.length() > 1 && p.measureText(t + "…") > max) t = t.substring(0, t.length() - 1);
        return t + "…";
    }
}
