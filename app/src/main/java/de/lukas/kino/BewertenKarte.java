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
 * Das Bewerten-Fenster (Wunsch Lukas 03.11.2026): nach dem Film (ab 80 %) und über „Bewerten“ in den Details.
 * <ul>
 * <li>Ein Feld ohne Bewertung zeigt „–“. Erst ↑ oder ↓ holt die 5, dann zählt das Feld.</li>
 * <li>„Abgeben“ (OK) schickt nur Felder, die geändert wurden – ändert Papa nur seine Note, bleibt Mama unberührt.</li>
 * <li>Neben jedem Zähler ein kleines Feld „Keine Bewertung“ (links neben Papa bzw. dem eigenen Zähler, rechts neben Mama):
 *     OK darauf nimmt die eigene Bewertung heraus. Lukas und Jonas: geht sofort raus und schließt.</li>
 * <li>Jede Note hat ihren eigenen Look: 1 Gewitter mit Riss, ab 7 grün, 8 funkelt, 9 kräftig grün mit Konfetti,
 *     10 golden mit Konfetti, Luftschlangen und Lichtschein.</li>
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
    private static final float TW = 380, TH = 430, XW = 150, XH = 230, LUECKE = 26, MITTE = 40, H = 660;

    final JSONObject film;
    final String[] namen;
    final int[] alt;          // alte Note (0 = keine)
    final int[] wert;         // gezeigte Note (0 = keine)
    final boolean[] geaendert;
    private final long[] wechsel;
    private int fokus;        // Slot: Eltern 0 = X Papa, 1 = Papa, 2 = Mama, 3 = X Mama; sonst 0 = X, 1 = Zähler
    private long danke;
    private boolean nurEntfernt;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint zahl, wort, name, titel, frage, klein, xText;
    private final Path pfad = new Path();
    private final RectF r = new RectF(), kachel = new RectF();

    BewertenKarte(Context c, JSONObject film, String[] namen, int[] alt) {
        this.film = film;
        this.namen = namen;
        this.alt = alt;
        wert = alt.clone();
        geaendert = new boolean[namen.length];
        wechsel = new long[namen.length];
        fokus = 1;
        zahl = stift(c, 150, 800);
        wort = stift(c, 30, 650);
        name = stift(c, 34, 650);
        titel = stift(c, 28, 600);
        frage = stift(c, 42, 650);
        klein = stift(c, 26, 500);
        xText = stift(c, 24, 600);
    }

    private static TextPaint stift(Context c, float g, int w) {
        TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        t.setTypeface(Schriften.gewicht(c, w));
        t.setTextSize(g);
        t.setColor(0xFFFFFFFF);
        t.setTextAlign(Paint.Align.CENTER);
        return t;
    }

    boolean eltern() {
        return namen.length > 1;
    }

    private int slots() {
        return eltern() ? 4 : 2;
    }

    private boolean istX(int slot) {
        return slot == 0 || (eltern() && slot == 3);
    }

    private int person(int slot) {
        if (!eltern()) return 0;
        return slot <= 1 ? 0 : 1;
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
        for (int i = 0; i < namen.length; i++) {
            if (!geaendert[i] || wert[i] == alt[i]) continue;
            if (wert[i] == 0 && alt[i] == 0) continue;
            l.add(new int[]{i, wert[i]});
        }
        return l;
    }

    boolean mitZehn() {
        for (int[] a : aenderungen()) if (a[1] == 10) return true;
        return false;
    }

    /** Tasten: WEITER (nur neu zeichnen), ABGEBEN oder SCHLIESSEN. */
    int taste(int k, boolean ok, boolean zurueck, int wdh) {
        if (danke > 0) return (ok || zurueck) ? SCHLIESSEN : WEITER;
        if (zurueck) return SCHLIESSEN;
        int pi = person(fokus);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (istX(fokus)) {
                    fokus = eltern() ? (fokus == 0 ? 1 : 2) : 1;
                    return WEITER;
                }
                int vorher = wert[pi];
                if (wert[pi] == 0) wert[pi] = 5;
                else if (k == KeyEvent.KEYCODE_DPAD_UP) wert[pi] = Math.min(10, wert[pi] + 1);
                else wert[pi] = Math.max(1, wert[pi] - 1);
                if (wert[pi] != vorher) {
                    geaendert[pi] = true;
                    wechsel[pi] = SystemClock.uptimeMillis();
                }
                return WEITER;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (wdh == 0) fokus = Math.max(0, fokus - 1);
                return WEITER;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (wdh == 0) fokus = Math.min(slots() - 1, fokus + 1);
                return WEITER;
            default:
                if (!ok || wdh > 0) return WEITER;
                if (istX(fokus)) {
                    wert[pi] = 0;
                    geaendert[pi] = true;
                    wechsel[pi] = SystemClock.uptimeMillis();
                    if (!eltern()) {
                        nurEntfernt = true;
                        return aenderungen().isEmpty() ? SCHLIESSEN : ABGEBEN;
                    }
                    fokus = fokus == 0 ? 1 : 2;   // zurück auf den Zähler dieser Person
                    return WEITER;
                }
                nurEntfernt = true;
                for (int[] a : aenderungen()) if (a[1] > 0) nurEntfernt = false;
                return ABGEBEN;
        }
    }

    /** Rahmen der Glas-Karte (für echtesGlas in FilmeActivity). */
    RectF rahmen(float a) {
        float breite = eltern() ? 2 * XW + 2 * TW + 2 * LUECKE + MITTE + 88 : 2 * XW + TW + 2 * LUECKE + 88;
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
        c.drawText(kuerzen(Bewerten.frage(), frage, k.width() - 80), 960, k.top + 128, frage);

        float oben = k.top + 170;
        boolean weiter = false;
        if (eltern()) {
            float x = 960 - (2 * XW + 2 * TW + 2 * LUECKE + MITTE) / 2;
            xFeld(c, x, oben, 0, 0, a);
            x += XW + LUECKE;
            weiter |= zaehler(c, x, oben, 0, 1, a, t, jetzt);
            x += TW + MITTE;
            weiter |= zaehler(c, x, oben, 1, 2, a, t, jetzt);
            x += TW + LUECKE;
            xFeld(c, x, oben, 1, 3, a);
        } else {
            float x = 960 - TW / 2;
            xFeld(c, x - LUECKE - XW, oben, 0, 0, a);
            weiter |= zaehler(c, x, oben, 0, 1, a, t, jetzt);
        }

        String hilfe;
        if (istX(fokus)) {
            hilfe = eltern() ? "OK  keine Bewertung für " + (fokus == 0 ? "Papa" : "Mama") + "      ← →  wechseln      Zurück  später"
                    : "OK  keine Bewertung      →  zurück zur Note      Zurück  später";
        } else {
            hilfe = eltern() ? "↑ ↓  Note      ← →  Papa / Mama / keine      OK  abgeben      Zurück  später"
                    : "↑ ↓  Note      ←  keine Bewertung      OK  abgeben      Zurück  später";
        }
        klein.setAlpha(Math.round(0xC8 * a));
        c.drawText(hilfe, 960, k.bottom + 50, klein);
        return weiter;
    }

    private void xFeld(Canvas c, float x, float oben, int pi, int slot, float a) {
        boolean aktiv = fokus == slot;
        boolean entfernt = geaendert[pi] && wert[pi] == 0 && alt[pi] > 0;
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

    private boolean zaehler(Canvas c, float x, float oben, int pi, int slot, float a, float t, long jetzt) {
        boolean aktiv = fokus == slot || (!eltern() && fokus == 1);
        int note = wert[pi];
        float ia = eltern() && !aktiv ? 0.8f : 1f;
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
        float seit = (jetzt - wechsel[pi]) / 1000f;
        if (note == 10 && wechsel[pi] > 0 && seit < 1.4f) explosion(c, cx, oben + 160, seit, fx, pi);
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
        } else if (!aktiv || !eltern()) {
            p.setStrokeWidth(2);
            farbe(RAND[note], a * ia);
            c.drawRoundRect(kachel, 38, 38, p);
        }
        if (aktiv && eltern()) {
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
        if (wechsel[pi] > 0 && seit < 0.28f) {
            pop = 1f + 0.25f * (float) Math.sin(Math.PI * seit / 0.28f);
            weiter = true;
        }
        if (note == 1 && wechsel[pi] > 0 && seit < 0.6f) wackeln = (float) Math.sin(seit * 55) * 10 * (1 - seit / 0.6f);
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
        boolean entfernt = geaendert[pi] && note == 0 && alt[pi] > 0;
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
        Integer fn = Familie.FARBEN.get(namen[pi]);
        name.setColor(fn != null ? fn : 0xFFFFFFFF);
        name.setAlpha(Math.round(255 * a * ia));
        c.drawText(Bewerten.schild(namen[pi]), cx, oben + 402, name);
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
        String text = nurEntfernt ? "Bewertung entfernt" : Bewerten.danke();
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
            teile[i] = (eltern() ? Bewerten.schild(namen[e[0]]) + ":  " : "") + (e[1] == 0 ? "entfernt" : e[1] + " von 10");
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
