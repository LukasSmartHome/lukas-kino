package de.lukas.kino;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.Log;

/**
 * Selbst gezeichnete Bilder für die zwei Kacheln ohne App-Banner (seit 16.11.2026):
 * „Filme – Lukas Kino“ und der HDMI-Eingang der PlayStation.
 * Sie werden einmal in ein Bild gezeichnet (doppelte Größe) und laufen danach wie jedes App-Banner –
 * kein Glas mehr, das bei jeder Bewegung neu gezeichnet werden muss.
 * Nur eigene Formen und die Schrift der Oberfläche, keine Marken-Logos.
 * Maße wie die Kachel: 300x170.
 */
final class KachelKunst {
    private static final int F = 2;

    private KachelKunst() {
    }

    /** Bild für die Kachel a, oder null, wenn etwas schiefgeht (dann zeichnet die Kachel das alte Glas). */
    static Bitmap malen(Context ctx, MainActivity.App a) {
        try {
            Bitmap b = Bitmap.createBitmap(Kachel.W * F, Kachel.H * F, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(F, F);
            if (a.pkg.equals("@filme")) filme(ctx, c);
            else eingang(ctx, c, a.name, a.unter == null ? "HDMI" : a.unter);
            return b;
        } catch (Throwable t) {
            Log.w(MainActivity.TAG, "Kachel-Bild ging nicht: " + a.pkg, t);
            return null;
        }
    }

    /** Filme: warmes Schwarz, goldener Lichtkegel wie von einem Projektor, Filmstreifen-Lochung, gelbe Abspiel-Scheibe. */
    private static void filme(Context ctx, Canvas c) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setStyle(Paint.Style.FILL);

        p.setShader(new LinearGradient(0, 0, 300, 170,
                new int[]{0xFF221A0B, 0xFF0F0C07, 0xFF060504}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 300, 170, p);

        // goldener Schein hinter der Scheibe
        p.setShader(new RadialGradient(66, 85, 170,
                new int[]{Ani.farbe(0xF2B705, 0.42f), Ani.farbe(0xF2B705, 0.13f), Ani.farbe(0xF2B705, 0f)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 300, 170, p);

        // Lichtkegel: von der Scheibe nach rechts auffächernd
        p.setShader(new LinearGradient(66, 0, 300, 0,
                Ani.farbe(0xFFD65A, 0.20f), Ani.farbe(0xFFD65A, 0f), Shader.TileMode.CLAMP));
        Path kegel = new Path();
        kegel.moveTo(66, 85);
        kegel.lineTo(300, -30);
        kegel.lineTo(300, 200);
        kegel.close();
        c.drawPath(kegel, p);
        p.setShader(null);

        // Filmstreifen: Lochung oben und unten
        p.setColor(Ani.farbe(0xFFFFFF, 0.11f));
        for (int x = 13; x < 300; x += 25) {
            c.drawRoundRect(new RectF(x, 9, x + 13, 17), 2.5f, 2.5f, p);
            c.drawRoundRect(new RectF(x, 153, x + 13, 161), 2.5f, 2.5f, p);
        }

        // gelbe Scheibe (Lukas' Lieblingsfarbe) mit feinem Lichtrand oben
        p.setShader(new LinearGradient(0, 51, 0, 119, 0xFFFFD84D, 0xFFF2B705, Shader.TileMode.CLAMP));
        c.drawCircle(66, 85, 34, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(Ani.farbe(0xFFFFFF, 0.55f));
        c.drawArc(new RectF(32.5f, 51.5f, 99.5f, 118.5f), 207f, 126f, false, p);

        // Abspiel-Dreieck mit runden Ecken
        p.setColor(0xFF17110A);
        p.setStyle(Paint.Style.FILL_AND_STROKE);
        p.setStrokeWidth(5f);
        p.setStrokeJoin(Paint.Join.ROUND);
        Path d = new Path();
        d.moveTo(58, 71);
        d.lineTo(80, 85);
        d.lineTo(58, 99);
        d.close();
        c.drawPath(d, p);

        Paint gross = Schriften.stift(ctx, 47, 760, 0xFFFFFFFF);
        gross.setLetterSpacing(-1f / 47f);
        c.drawText("Filme", 116.5f, 94, gross);
        Paint klein = Schriften.stift(ctx, 14.5f, 700, 0xFFF2B705);
        klein.setLetterSpacing(2.6f / 14.5f);
        c.drawText("LUKAS KINO", 116.7f, 119, klein);
    }

    /**
     * HDMI-Eingang (PlayStation): tiefes Blau, heller Schein, die Nummer des Eingangs groß im Hintergrund,
     * oben links ein weißes Schild mit Stecker und „HDMI 2“ – daran erkennt man sofort, dass es ein Eingang ist.
     */
    private static void eingang(Context ctx, Canvas c, String name, String schild) {
        String nr = schild.replaceAll("\\D", "");
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setStyle(Paint.Style.FILL);

        p.setShader(new LinearGradient(0, 170, 300, 0,
                new int[]{0xFF071446, 0xFF1238B8, 0xFF2F6DFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 300, 170, p);
        p.setShader(new RadialGradient(262, 10, 190,
                Ani.farbe(0xA0CDFF, 0.50f), Ani.farbe(0xA0CDFF, 0f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 300, 170, p);
        p.setShader(null);

        if (!nr.isEmpty()) {
            Paint ziffer = Schriften.stift(ctx, 210, 800, Ani.farbe(0xFFFFFF, 0.15f));
            c.drawText(nr, 186, 176, ziffer);
        }

        // Schild: Stecker-Umriss und Beschriftung
        Paint st = Schriften.stift(ctx, 17, 760, 0xFF0B1E63);
        st.setLetterSpacing(0.4f / 17f);
        float breite = 43 + st.measureText(schild) + 14;
        p.setColor(Ani.farbe(0xFFFFFF, 0.96f));
        c.drawRoundRect(new RectF(20, 19, 20 + breite, 55), 18, 18, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.2f);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFF0B1E63);
        Path stecker = new Path();
        stecker.moveTo(33, 31);
        stecker.lineTo(55, 31);
        stecker.lineTo(55, 38);
        stecker.lineTo(51, 43);
        stecker.lineTo(37, 43);
        stecker.lineTo(33, 38);
        stecker.close();
        c.drawPath(stecker, p);
        c.drawLine(39, 36.5f, 49, 36.5f, p);
        c.drawText(schild, 63, 43, st);

        Paint n = Schriften.stift(ctx, 35, 760, 0xFFFFFFFF);
        n.setLetterSpacing(-0.6f / 35f);
        n.setShadowLayer(Ani.schatten(12), 0, 2, Ani.farbe(0x000A32, 0.45f));
        c.drawText(name, 21, 136, n);
    }
}
