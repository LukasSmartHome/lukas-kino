package de.lukas.kino;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;

import java.util.Map;
import java.util.WeakHashMap;

/** Filmlogos zeichnen: dunkle Logos (z. B. schwarze Schrift) werden weiß, damit sie auf dem dunklen Bild lesbar sind. */
final class Logo {
    private static final Map<Bitmap, Boolean> DUNKEL = new WeakHashMap<>();
    private static final PorterDuffColorFilter WEISS = new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);

    private Logo() {
    }

    static boolean dunkel(Bitmap b) {
        Boolean d = DUNKEL.get(b);
        if (d != null) return d;
        long summe = 0, n = 0;
        int sx = Math.max(1, b.getWidth() / 48), sy = Math.max(1, b.getHeight() / 24);
        for (int y = 0; y < b.getHeight(); y += sy) {
            for (int x = 0; x < b.getWidth(); x += sx) {
                int px = b.getPixel(x, y);
                int a = px >>> 24;
                if (a < 128) continue;
                int r = (px >> 16) & 255, g = (px >> 8) & 255, bl = px & 255;
                summe += (r * 299 + g * 587 + bl * 114) / 1000;
                n++;
            }
        }
        d = n > 0 && summe / n < 95;
        DUNKEL.put(b, d);
        return d;
    }

    static void malen(Canvas c, Bitmap b, RectF ziel, Paint p, int alpha) {
        boolean dunkel = dunkel(b);
        p.setColorFilter(dunkel ? WEISS : null);
        p.setAlpha(alpha);
        c.drawBitmap(b, null, ziel, p);
        p.setColorFilter(null);
        p.setAlpha(255);
    }
}
