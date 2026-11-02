package de.lukas.kino;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Weiches oranges Leuchten vom Bildschirmrand (ohne harte Linie, in den Ecken etwas stärker)
 * und darunter eine Glas-Karte mit dem Gesprächsverlauf: was Lukas gesagt hat (rechts) und
 * was Claude antwortet (links), dazu eine Zustandszeile. Wird vom StartseitenWaechter als
 * Bedienungshilfen-Overlay angezeigt, liegt also auch über laufenden Filmen.
 */
final class SprachRahmen extends View {

    static final int HOERT = 1, DENKT = 2, ANTWORT = 3, FEHLER = 4;

    private static final int ORANGE = 0xFFFF8A00;
    private static final int GOLD = 0xFFFFB347;

    private int zustand = HOERT;
    private String status = "";
    private List<String[]> verlauf = new ArrayList<>();
    private final long start = SystemClock.uptimeMillis();
    private final long einblendStart = SystemClock.uptimeMillis();

    private final Paint schein = new Paint();
    private final Paint karte = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint kartenRand = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blase = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint punkt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint statusStift;
    private final TextPaint duStift;
    private final TextPaint claudeStift;
    private final RectF r = new RectF();

    SprachRahmen(Context c) {
        super(c);
        karte.setColor(0xD9121216);
        kartenRand.setStyle(Paint.Style.STROKE);
        kartenRand.setStrokeWidth(2f);
        kartenRand.setColor(0x33FFFFFF);
        blase.setColor(0x26FFFFFF);
        punkt.setColor(ORANGE);
        statusStift = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        statusStift.setColor(GOLD);
        statusStift.setTypeface(Schriften.gewicht(c, 600));
        duStift = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        duStift.setColor(0xD9FFFFFF);
        duStift.setTypeface(Schriften.gewicht(c, 450));
        claudeStift = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        claudeStift.setColor(Color.WHITE);
        claudeStift.setTypeface(Schriften.gewicht(c, 500));
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** verlauf: Einträge {"du"|"claude", text}; null = unverändert. */
    void setze(int zustand, String status, List<String[]> verlauf) {
        this.zustand = zustand;
        this.status = status == null ? "" : status;
        if (verlauf != null) this.verlauf = verlauf;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float d = h / 1080f;
        long jetzt = SystemClock.uptimeMillis();
        float t = (jetzt - start) / 1000f;
        float ein = Math.min(1f, (jetzt - einblendStart) / 400f);

        float puls;
        if (zustand == HOERT) puls = 0.78f + 0.22f * (float) Math.sin(t * 4.5);
        else if (zustand == DENKT) puls = 0.72f + 0.10f * (float) Math.sin(t * 2.6);
        else if (zustand == FEHLER) puls = 0.5f;
        else puls = 0.62f + 0.06f * (float) Math.sin(t * 1.6);
        float staerke = puls * ein;

        leuchten(c, w, h, d, staerke);
        karteMalen(c, w, h, d, t, ein);
        postInvalidateOnAnimation();
    }

    /** Nur weiches Leuchten: Verlauf von jedem Rand nach innen, in den Ecken zusätzlich ein runder Schein. */
    private void leuchten(Canvas c, int w, int h, float d, float staerke) {
        float breite = 110f * d;
        int a = (int) (150 * staerke);
        int voll = (a << 24) | (ORANGE & 0x00FFFFFF);
        int halb = ((a / 3) << 24) | (ORANGE & 0x00FFFFFF);
        int klar = ORANGE & 0x00FFFFFF;
        float[] stops = {0f, 0.35f, 1f};
        int[] farben = {voll, halb, klar};
        schein.setShader(new LinearGradient(0, 0, 0, breite, farben, stops, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, breite, schein);
        schein.setShader(new LinearGradient(0, h, 0, h - breite, farben, stops, Shader.TileMode.CLAMP));
        c.drawRect(0, h - breite, w, h, schein);
        schein.setShader(new LinearGradient(0, 0, breite, 0, farben, stops, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, breite, h, schein);
        schein.setShader(new LinearGradient(w, 0, w - breite, 0, farben, stops, Shader.TileMode.CLAMP));
        c.drawRect(w - breite, 0, w, h, schein);

        float rad = 260f * d;
        int ecke = ((int) (110 * staerke) << 24) | (GOLD & 0x00FFFFFF);
        float[][] ecken = {{0, 0}, {w, 0}, {0, h}, {w, h}};
        for (float[] e : ecken) {
            schein.setShader(new RadialGradient(e[0], e[1], rad, ecke, GOLD & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(e[0], e[1], rad, schein);
        }
        schein.setShader(null);
    }

    private void karteMalen(Canvas c, int w, int h, float d, float t, float ein) {
        float kartenBreite = Math.min(w * 0.6f, 1150 * d);
        float innen = 32 * d;
        int textBreite = (int) (kartenBreite - 2 * innen);
        int blasenBreite = (int) (textBreite * 0.82f);
        statusStift.setTextSize(26 * d);
        duStift.setTextSize(28 * d);
        claudeStift.setTextSize(34 * d);

        // Von unten nach oben auffüllen, bis die Karte etwa 60 % der Höhe erreicht.
        float maxHoehe = h * 0.6f;
        List<StaticLayout> layouts = new ArrayList<>();
        List<Boolean> vonDir = new ArrayList<>();
        float inhalt = 0;
        for (int i = verlauf.size() - 1; i >= 0; i--) {
            String[] e = verlauf.get(i);
            boolean du = "du".equals(e[0]);
            String text = e[1];
            // Beim Mitschreiben zählen die neuesten Wörter: lange Sätze vorne kürzen.
            if (du && text.length() > 160) text = "…" + text.substring(text.length() - 160);
            StaticLayout l = layout(text, du ? duStift : claudeStift, du ? blasenBreite - (int) (32 * d) : textBreite, du ? 3 : 8);
            float hoehe = l.getHeight() + (du ? 24 * d : 0) + 18 * d;
            if (inhalt + hoehe > maxHoehe - 120 * d && !layouts.isEmpty()) break;
            inhalt += hoehe;
            layouts.add(0, l);
            vonDir.add(0, du);
        }
        float hoehe = innen + 36 * d + (layouts.isEmpty() ? 0 : 14 * d + inhalt) + innen - 8 * d;

        float links = (w - kartenBreite) / 2f;
        float unten = h - 64 * d + (1 - ein) * 40 * d;
        r.set(links, unten - hoehe, links + kartenBreite, unten);
        c.drawRoundRect(r, 30 * d, 30 * d, karte);
        c.drawRoundRect(r, 30 * d, 30 * d, kartenRand);

        float y = r.top + innen;
        for (int i = 0; i < layouts.size(); i++) {
            StaticLayout l = layouts.get(i);
            boolean du = vonDir.get(i);
            if (du) {
                float bw = Math.min(blasenBreite, maxZeile(l) + 32 * d);
                float bx = r.right - innen - bw;
                RectF b = new RectF(bx, y, r.right - innen, y + l.getHeight() + 24 * d);
                c.drawRoundRect(b, 20 * d, 20 * d, blase);
                c.save();
                c.translate(bx + 16 * d, y + 12 * d);
                l.draw(c);
                c.restore();
                y += l.getHeight() + 24 * d + 18 * d;
            } else {
                c.save();
                c.translate(r.left + innen, y);
                l.draw(c);
                c.restore();
                y += l.getHeight() + 18 * d;
            }
        }
        if (!layouts.isEmpty()) y += 14 * d - 18 * d;

        // Zustandszeile mit drei Punkten.
        for (int i = 0; i < 3; i++) {
            float phase = (float) Math.sin(t * 6 - i * 0.7);
            boolean lebt = zustand == HOERT || zustand == DENKT;
            float rad = (lebt ? 4 + 3 * Math.max(0, phase) : 4) * d;
            punkt.setAlpha(zustand == ANTWORT ? 120 : 255);
            c.drawCircle(r.left + innen + 7 * d + i * 18 * d, y + 16 * d, rad, punkt);
        }
        c.drawText(status, r.left + innen + 66 * d, y + 25 * d, statusStift);
    }

    private static float maxZeile(StaticLayout l) {
        float m = 0;
        for (int i = 0; i < l.getLineCount(); i++) m = Math.max(m, l.getLineWidth(i));
        return m;
    }

    private static StaticLayout layout(String text, TextPaint stift, int breite, int zeilen) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), stift, Math.max(10, breite))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, 1.12f)
                .setMaxLines(zeilen)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }
}
