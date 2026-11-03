package de.lukas.kino;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Filmlogos zeichnen, damit sie auf dem dunklen Bild lesbar sind.
 * <ul>
 * <li>Fast ganz dunkle, einfarbige Logos (z. B. schwarze Schrift) werden weiß.</li>
 * <li>Dunkle Logos mit hellen oder bunten Teilen (z. B. Masters of the Universe mit dunklen Kanten und Metall)
 *     bleiben, wie sie sind, und bekommen nur einen hellen Schein dahinter. Früher wurden auch sie ganz weiß
 *     eingefärbt und waren dann nur noch ein weißer Klecks (Meldung Lukas 03.11.2026).</li>
 * </ul>
 */
final class Logo {
    static final int NORMAL = 0, WEISS = 1, SCHEIN = 2;

    private static final Map<Bitmap, Integer> ART = new WeakHashMap<>();
    private static final Map<Bitmap, Bitmap> SCHEIN_BILD = new WeakHashMap<>();
    private static final PorterDuffColorFilter WEISS_FILTER = new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);

    private Logo() {
    }

    static int art(Bitmap b) {
        Integer d = ART.get(b);
        if (d != null) return d;
        long summe = 0, n = 0, hell = 0, bunt = 0;
        int sx = Math.max(1, b.getWidth() / 64), sy = Math.max(1, b.getHeight() / 32);
        for (int y = 0; y < b.getHeight(); y += sy) {
            for (int x = 0; x < b.getWidth(); x += sx) {
                int px = b.getPixel(x, y);
                int a = px >>> 24;
                if (a < 128) continue;
                int r = (px >> 16) & 255, g = (px >> 8) & 255, bl = px & 255;
                int l = (r * 299 + g * 587 + bl * 114) / 1000;
                int s = Math.max(r, Math.max(g, bl)) - Math.min(r, Math.min(g, bl));
                summe += l;
                if (l > 160) hell++;
                if (s > 60 && l > 50) bunt++;
                n++;
            }
        }
        int ergebnis = NORMAL;
        if (n > 0) {
            float L = summe / (float) n, H = hell / (float) n, B = bunt / (float) n;
            if (L < 95 && H < 0.10f && B < 0.15f) ergebnis = WEISS;
            else if (L < 115) ergebnis = SCHEIN;
        }
        ART.put(b, ergebnis);
        return ergebnis;
    }

    /** Weicher heller Schein in der Form des Logos (einmal je Logo gerechnet). */
    private static Bitmap schein(Bitmap b) {
        Bitmap s = SCHEIN_BILD.get(b);
        if (s != null) return s;
        try {
            Paint blur = new Paint();
            blur.setMaskFilter(new BlurMaskFilter(Math.max(4f, b.getHeight() / 18f), BlurMaskFilter.Blur.NORMAL));
            int[] versatz = new int[2];
            s = b.extractAlpha(blur, versatz);
            SCHEIN_BILD.put(b, s);
            SCHEIN_VERSATZ.put(s, versatz);
        } catch (Throwable t) {
            s = null;
        }
        return s;
    }

    private static final Map<Bitmap, int[]> SCHEIN_VERSATZ = new WeakHashMap<>();

    static void malen(Canvas c, Bitmap b, RectF ziel, Paint p, int alpha) {
        int art = art(b);
        if (art == SCHEIN) {
            Bitmap s = schein(b);
            int[] v = s != null ? SCHEIN_VERSATZ.get(s) : null;
            if (s != null && v != null) {
                float kx = ziel.width() / b.getWidth(), ky = ziel.height() / b.getHeight();
                RectF r = new RectF(ziel.left + v[0] * kx, ziel.top + v[1] * ky,
                        ziel.left + (v[0] + s.getWidth()) * kx, ziel.top + (v[1] + s.getHeight()) * ky);
                p.setColorFilter(null);
                p.setColor(0xFFFFFFFF);
                p.setAlpha(Math.round(alpha * 0.55f));
                c.drawBitmap(s, null, r, p);   // Alpha-Bitmap wird in der Farbe des Stifts gemalt
            }
        }
        p.setColorFilter(art == WEISS ? WEISS_FILTER : null);
        p.setAlpha(alpha);
        c.drawBitmap(b, null, ziel, p);
        p.setColorFilter(null);
        p.setAlpha(255);
    }
}
