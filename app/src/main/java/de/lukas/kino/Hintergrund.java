package de.lukas.kino;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Großes Hintergrundbild (wie background-size: cover) mit weicher Überblendung, sonst Sonnenuntergang. */
final class Hintergrund extends View {

    private Bitmap aktuell, vorher;
    private boolean vorherStandard = true;
    private float anteil = 1f;
    private ValueAnimator blende;
    private final Paint bildStift = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint stift = new Paint(Paint.ANTI_ALIAS_FLAG);

    Hintergrund(Context c) {
        super(c);
    }

    /** Neues Bild zeigen (null = Sonnenuntergang), Überblendung 900 ms. */
    void zeigen(Bitmap neu) {
        if (blende != null) blende.cancel();
        // Keine Bitmaps von Hand freigeben – der Grafik-Thread könnte sie noch zeichnen; das macht der GC.
        vorher = aktuell;
        vorherStandard = aktuell == null;
        aktuell = neu;
        anteil = 0f;
        blende = ValueAnimator.ofFloat(0f, 1f);
        blende.setDuration(900);
        blende.setInterpolator(Ani.EASE);
        blende.addUpdateListener(a -> {
            anteil = (float) a.getAnimatedValue();
            invalidate();
        });
        blende.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                vorher = null;
                anteil = 1f;
                invalidate();
            }
        });
        blende.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (anteil < 1f) {
            if (vorher != null && !vorher.isRecycled()) bild(c, vorher, 255);
            else if (vorherStandard) sonnenuntergang(c, stift);
        }
        int alpha = Math.round(255 * anteil);
        if (aktuell != null && !aktuell.isRecycled()) {
            bild(c, aktuell, alpha);
        } else {
            if (alpha < 255) c.saveLayerAlpha(0, 0, 1920, 1080, alpha);
            sonnenuntergang(c, stift);
            if (alpha < 255) c.restore();
        }
    }

    private void bild(Canvas c, Bitmap b, int alpha) {
        bildStift.setAlpha(alpha);
        c.drawBitmap(b, abdecken(b.getWidth(), b.getHeight(), 1920, 1080), bildStift);
    }

    /** background-size: cover; background-position: center. */
    static Matrix abdecken(int bw, int bh, float zw, float zh) {
        float s = Math.max(zw / bw, zh / bh);
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate((zw - bw * s) / 2f, (zh - bh * s) / 2f);
        return m;
    }

    // ---------- Standardbild: der gezeichnete Sonnenuntergang aus der Web-Version ----------

    private static Path[] pfade;

    private static Path pf(int i, String d) {
        if (pfade[i] == null) pfade[i] = SvgPfad.lesen(d);
        return pfade[i];
    }

    static void sonnenuntergang(Canvas c, Paint p) {
        if (pfade == null) pfade = new Path[10];
        p.reset();
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.FILL);

        p.setShader(new LinearGradient(0, 0, 0, 806,
                new int[]{0xFF120D2E, 0xFF392262, 0xFFA1436C, 0xFFEE8952, 0xFFFFCD6C, 0xFFFFDC7E},
                new float[]{0f, 0.34f, 0.6f, 0.8f, 0.95f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1920, 806, p);

        p.setShader(new RadialGradient(1230, 790, 560,
                new int[]{Ani.farbe(0xFFD27A, 0.6f), Ani.farbe(0xFF9C55, 0.18f), Ani.farbe(0xFF8A4C, 0f)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(1230, 790, 560, p);
        p.setShader(null);

        oval(c, p, 320, 290, 400, 14, 0x7D4A8C, 0.4f);
        oval(c, p, 880, 520, 380, 10, 0xF39B7B, 0.38f);
        oval(c, p, 1520, 600, 320, 12, 0xFFC48A, 0.5f);
        oval(c, p, 1720, 470, 260, 9, 0xE88BA3, 0.3f);
        oval(c, p, 1050, 640, 250, 7, 0xFFD8A0, 0.45f);

        p.setShader(new RadialGradient(1230, 792, 104,
                new int[]{0xFFFFFBE8, 0xFFFFE38F, Ani.farbe(0xFFB54A, 0f)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(1230, 792, 104, p);
        p.setShader(null);
        p.setColor(0xFFFFF3CF);
        c.drawCircle(1230, 792, 70, p);

        flaeche(c, p, pf(0, "M800 802 C880 786 970 782 1040 790 L1064 802 Z"), 0x7A3C5D, 0.75f);
        flaeche(c, p, pf(1, "M1380 802 C1460 772 1560 750 1650 744 C1740 738 1830 754 1920 742 L1920 802 Z"), 0x673254, 0.92f);

        p.setShader(new LinearGradient(0, 800, 0, 1080,
                new int[]{0xFFEC8D55, 0xFFA14E63, 0xFF4A2348, 0xFF170C26},
                new float[]{0f, 0.22f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 800, 1920, 1080, p);
        p.setShader(null);

        oval(c, p, 1230, 812, 160, 5, 0xFFF1C4, 0.85f);
        oval(c, p, 1230, 834, 124, 4, 0xFFE7A8, 0.65f);
        oval(c, p, 1230, 858, 98, 4, 0xFFDC96, 0.5f);
        oval(c, p, 1230, 886, 74, 3, 0xFFD28A, 0.4f);
        oval(c, p, 1230, 918, 54, 3, 0xFFC985, 0.3f);
        oval(c, p, 1230, 956, 38, 3, 0xFFC080, 0.22f);
        oval(c, p, 1230, 1000, 26, 2, 0xFFB87A, 0.15f);
        oval(c, p, 1500, 846, 180, 2, 0xFFD3A0, 0.25f);
        oval(c, p, 960, 872, 220, 2, 0xFFC7A0, 0.18f);
        oval(c, p, 1660, 930, 240, 2, 0xF3A58F, 0.14f);

        flaeche(c, p, pf(2, "M1556 802 L1568 744 L1586 728 L1602 742 L1612 802 Z"), 0x3A1B39, 1f);
        flaeche(c, p, pf(3, "M1650 802 L1660 772 L1676 762 L1688 802 Z"), 0x3A1B39, 1f);

        p.setShader(new LinearGradient(0, 596, 1100, 1080, 0xFF2C1632, 0xFF0C0711, Shader.TileMode.CLAMP));
        c.drawPath(pf(4, "M0 1080 L0 612 C120 600 260 596 380 604 C500 612 600 624 690 640 C740 650 770 668 790 700 C812 735 822 770 846 800 C870 832 905 862 940 902 C990 962 1040 1022 1100 1080 Z"), p);
        p.setShader(null);

        strich(c, p, pf(5, "M0 612 C120 600 260 596 380 604 C500 612 600 624 690 640 C740 650 770 668 790 700"), 0xF3A56C, 0.55f);
        strich(c, p, pf(6, "M702 652 C716 700 728 760 758 822 M612 640 C622 700 640 780 676 860 M500 624 C506 700 520 800 548 900"), 0x46223F, 0.6f);
        strich(c, p, pf(7, "M120 603 l6 -16 M128 603 l-3 -14 M260 597 l5 -15 M268 598 l-4 -12 M430 607 l6 -15 M438 608 l-2 -13 M590 622 l5 -14"), 0x2C1632, 1f);
        flaeche(c, p, pf(8, "M1920 1080 L1920 902 C1860 907 1800 927 1760 962 C1720 997 1700 1042 1690 1080 Z"), 0x0C0711, 1f);
        strich(c, p, pf(9, "M1010 420 q12 -10 24 0 q12 -10 24 0 M1094 378 q8 -7 16 0 q8 -7 16 0 M968 458 q7 -6 14 0 q7 -6 14 0"), 0x2A1638, 1f);
    }

    private static void oval(Canvas c, Paint p, float cx, float cy, float rx, float ry, int rgb, float a) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ani.farbe(rgb, a));
        c.drawOval(new RectF(cx - rx, cy - ry, cx + rx, cy + ry), p);
    }

    private static void flaeche(Canvas c, Paint p, Path pfad, int rgb, float a) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ani.farbe(rgb, a));
        c.drawPath(pfad, p);
    }

    private static void strich(Canvas c, Paint p, Path pfad, int rgb, float a) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(Ani.farbe(rgb, a));
        c.drawPath(pfad, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** Sonnenuntergang als kleines Bild (für das Milchglas). */
    static Bitmap standardKlein() {
        Bitmap b = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(0.25f, 0.25f);
        sonnenuntergang(c, new Paint());
        return b;
    }
}
