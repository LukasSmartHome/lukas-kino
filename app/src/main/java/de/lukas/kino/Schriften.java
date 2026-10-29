package de.lukas.kino;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;

/** Inter Tight (wie bisher in der Web-Oberfläche), als variable Schrift in den Assets. */
final class Schriften {
    private static final Map<Integer, Typeface> CACHE = new HashMap<>();

    private Schriften() {
    }

    static Typeface gewicht(Context c, int wght) {
        Typeface t = CACHE.get(wght);
        if (t != null) return t;
        try {
            t = new Typeface.Builder(c.getAssets(), "fonts/InterTight.ttf")
                    .setFontVariationSettings("'wght' " + wght)
                    .build();
        } catch (Exception e) {
            t = null;
        }
        if (t == null) t = Typeface.create(Typeface.SANS_SERIF, wght >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        CACHE.put(wght, t);
        return t;
    }

    static Paint stift(Context c, float groesse, int wght, int farbe) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(gewicht(c, wght));
        p.setTextSize(groesse);
        p.setColor(farbe);
        return p;
    }

    /** Grundlinie für eine CSS-Zeile mit der Höhe zeilenhoehe, die bei oben beginnt. */
    static float grundlinie(Paint p, float oben, float zeilenhoehe) {
        Paint.FontMetrics fm = p.getFontMetrics();
        float inhalt = fm.descent - fm.ascent;
        return oben + (zeilenhoehe - inhalt) / 2f - fm.ascent;
    }

    /** CSS line-height: normal. */
    static float normaleZeile(Paint p) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return fm.descent - fm.ascent + fm.leading;
    }
}
