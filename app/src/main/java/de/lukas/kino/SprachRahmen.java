package de.lukas.kino;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

/**
 * Der orange Leuchtrahmen über dem ganzen Bild (wie „Claude am PC“) und darunter eine
 * Glas-Karte mit Zustand, erkanntem Satz und Antwort. Wird vom StartseitenWaechter als
 * Bedienungshilfen-Overlay angezeigt, liegt also auch über laufenden Filmen und nimmt
 * keine Tasten und keinen Fokus weg.
 */
final class SprachRahmen extends View {

    static final int HOERT = 1, DENKT = 2, ANTWORT = 3, FEHLER = 4;

    private static final int ORANGE = 0xFFFF8A00;

    private int zustand = HOERT;
    private String status = "";
    private String erkannt = "";
    private String antwort = "";
    private final long start = SystemClock.uptimeMillis();
    private long einblendStart = SystemClock.uptimeMillis();

    private final Paint linie = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint schein = new Paint();
    private final Paint karte = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint kartenRand = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint punkt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint statusStift;
    private final TextPaint erkanntStift;
    private final TextPaint antwortStift;
    private final RectF r = new RectF();

    SprachRahmen(Context c) {
        super(c);
        linie.setStyle(Paint.Style.STROKE);
        linie.setColor(ORANGE);
        karte.setColor(0xD9141418);
        kartenRand.setStyle(Paint.Style.STROKE);
        kartenRand.setStrokeWidth(2f);
        kartenRand.setColor(0x40FFFFFF);
        punkt.setColor(ORANGE);
        statusStift = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        statusStift.setColor(ORANGE);
        statusStift.setTypeface(Schriften.gewicht(c, 600));
        erkanntStift = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        erkanntStift.setColor(0xB3FFFFFF);
        erkanntStift.setTypeface(Schriften.gewicht(c, 400));
        antwortStift = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        antwortStift.setColor(Color.WHITE);
        antwortStift.setTypeface(Schriften.gewicht(c, 500));
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    void setze(int zustand, String status, String erkannt, String antwort) {
        if (this.zustand != zustand && zustand == HOERT) einblendStart = SystemClock.uptimeMillis();
        this.zustand = zustand;
        this.status = status == null ? "" : status;
        if (erkannt != null) this.erkannt = erkannt;
        if (antwort != null) this.antwort = antwort;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float dichte = h / 1080f;
        long jetzt = SystemClock.uptimeMillis();
        float t = (jetzt - start) / 1000f;
        float ein = Math.min(1f, (jetzt - einblendStart) / 350f);

        // Stärke des Leuchtens je Zustand: zuhören pulsiert, denken ruhig, antworten atmet leicht.
        float puls;
        if (zustand == HOERT) puls = 0.75f + 0.25f * (float) Math.sin(t * 5.0);
        else if (zustand == ANTWORT) puls = 0.8f + 0.12f * (float) Math.sin(t * 2.2);
        else if (zustand == FEHLER) puls = 0.55f;
        else puls = 0.7f;
        float staerke = puls * ein;

        // Weicher Schein von jedem Rand nach innen.
        float breite = 90f * dichte * (0.8f + 0.2f * puls);
        int a = (int) (170 * staerke);
        int farbe = (a << 24) | (ORANGE & 0x00FFFFFF);
        int klar = ORANGE & 0x00FFFFFF;
        schein.setShader(new LinearGradient(0, 0, 0, breite, farbe, klar, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, breite, schein);
        schein.setShader(new LinearGradient(0, h, 0, h - breite, farbe, klar, Shader.TileMode.CLAMP));
        c.drawRect(0, h - breite, w, h, schein);
        schein.setShader(new LinearGradient(0, 0, breite, 0, farbe, klar, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, breite, h, schein);
        schein.setShader(new LinearGradient(w, 0, w - breite, 0, farbe, klar, Shader.TileMode.CLAMP));
        c.drawRect(w - breite, 0, w, h, schein);

        // Klare Linie am Rand.
        float dicke = 7f * dichte;
        linie.setStrokeWidth(dicke);
        linie.setAlpha((int) (255 * Math.min(1f, 0.6f + 0.4f * staerke)));
        r.set(dicke / 2, dicke / 2, w - dicke / 2, h - dicke / 2);
        c.drawRoundRect(r, 28 * dichte, 28 * dichte, linie);

        karteMalen(c, w, h, dichte, t, ein);
        postInvalidateOnAnimation();
    }

    private void karteMalen(Canvas c, int w, int h, float dichte, float t, float ein) {
        float kartenBreite = Math.min(w * 0.62f, 1180 * dichte);
        float innen = 36 * dichte;
        int textBreite = (int) (kartenBreite - 2 * innen);
        statusStift.setTextSize(30 * dichte);
        erkanntStift.setTextSize(28 * dichte);
        antwortStift.setTextSize(38 * dichte);

        StaticLayout lErk = erkannt.isEmpty() ? null : layout("„" + erkannt + "“", erkanntStift, textBreite, 3);
        StaticLayout lAnt = antwort.isEmpty() ? null : layout(antwort, antwortStift, textBreite, 6);
        float hoehe = innen + 40 * dichte;
        if (lErk != null) hoehe += lErk.getHeight() + 14 * dichte;
        if (lAnt != null) hoehe += lAnt.getHeight() + 6 * dichte;
        hoehe += innen - 10 * dichte;

        float links = (w - kartenBreite) / 2f;
        float unten = h - 70 * dichte + (1 - ein) * 40 * dichte;
        r.set(links, unten - hoehe, links + kartenBreite, unten);
        int alt = karte.getAlpha();
        karte.setAlpha((int) (alt * ein));
        c.drawRoundRect(r, 30 * dichte, 30 * dichte, karte);
        karte.setAlpha(alt);
        c.drawRoundRect(r, 30 * dichte, 30 * dichte, kartenRand);

        float y = r.top + innen;
        // Kleine Punkte als Lebenszeichen neben dem Zustand.
        for (int i = 0; i < 3; i++) {
            float phase = (float) Math.sin(t * 6 - i * 0.7);
            float rad = (zustand == HOERT || zustand == DENKT) ? (5 + 3 * Math.max(0, phase)) * dichte : 5 * dichte;
            c.drawCircle(r.left + innen + 8 * dichte + i * 20 * dichte, y + 16 * dichte, rad, punkt);
        }
        c.drawText(status, r.left + innen + 72 * dichte, y + 27 * dichte, statusStift);
        y += 40 * dichte;
        if (lErk != null) {
            y += 14 * dichte;
            c.save();
            c.translate(r.left + innen, y);
            lErk.draw(c);
            c.restore();
            y += lErk.getHeight();
        }
        if (lAnt != null) {
            y += 6 * dichte;
            c.save();
            c.translate(r.left + innen, y);
            lAnt.draw(c);
            c.restore();
        }
    }

    private static StaticLayout layout(String text, TextPaint stift, int breite, int zeilen) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), stift, Math.max(10, breite))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, 1.12f)
                .setMaxLines(zeilen)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build();
    }
}
