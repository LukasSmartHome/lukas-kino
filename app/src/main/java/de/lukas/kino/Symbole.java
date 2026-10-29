package de.lukas.kino;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Die kleinen Strich-Symbole der Oberfläche (24er-Raster wie die alten SVGs). */
final class Symbole {
    private Symbole() {
    }

    static final Path ORT = new Path();
    static final Path SUCHE = new Path();
    static final Path MUELL = new Path();
    static final Path LINKS = new Path();
    static final Path RECHTS = new Path();
    static final Path CONTROLLER = new Path();

    static {
        ORT.moveTo(12f, 21f);
        ORT.cubicTo(12f, 21f, 5.5f, 15.4f, 5.5f, 10f);
        ORT.arcTo(new RectF(5.5f, 3.5f, 18.5f, 16.5f), 180f, 180f, false);
        ORT.cubicTo(18.5f, 15.4f, 12f, 21f, 12f, 21f);
        ORT.close();
        ORT.addCircle(12f, 10f, 2.4f, Path.Direction.CW);

        SUCHE.addCircle(11f, 11f, 6.5f, Path.Direction.CW);
        SUCHE.moveTo(20f, 20f);
        SUCHE.lineTo(15.8f, 15.8f);

        MUELL.moveTo(4f, 7f);
        MUELL.lineTo(20f, 7f);
        MUELL.moveTo(10f, 11f);
        MUELL.lineTo(10f, 17f);
        MUELL.moveTo(14f, 11f);
        MUELL.lineTo(14f, 17f);
        MUELL.moveTo(6f, 7f);
        MUELL.lineTo(7f, 19f);
        MUELL.quadTo(7.2f, 21f, 9f, 21f);
        MUELL.lineTo(15f, 21f);
        MUELL.quadTo(16.8f, 21f, 17f, 19f);
        MUELL.lineTo(18f, 7f);
        MUELL.moveTo(9f, 7f);
        MUELL.lineTo(9f, 4f);
        MUELL.lineTo(15f, 4f);
        MUELL.lineTo(15f, 7f);

        LINKS.moveTo(15f, 6f);
        LINKS.lineTo(9f, 12f);
        LINKS.lineTo(15f, 18f);
        RECHTS.moveTo(9f, 6f);
        RECHTS.lineTo(15f, 12f);
        RECHTS.lineTo(9f, 18f);

        CONTROLLER.moveTo(7f, 9f);
        CONTROLLER.lineTo(17f, 9f);
        CONTROLLER.arcTo(new RectF(13f, 9f, 21f, 17f), -90f, 103f, false);
        CONTROLLER.lineTo(20.3f, 16.5f);
        CONTROLLER.arcTo(new RectF(15.77f, 13.62f, 20.37f, 18.22f), 14.6f, 122f, false);
        CONTROLLER.lineTo(14.6f, 15.6f);
        CONTROLLER.lineTo(9.4f, 15.6f);
        CONTROLLER.lineTo(7.6f, 17.5f);
        CONTROLLER.arcTo(new RectF(3.63f, 13.62f, 8.23f, 18.22f), 43.4f, 122f, false);
        CONTROLLER.lineTo(3.1f, 13.9f);
        CONTROLLER.arcTo(new RectF(3f, 9f, 11f, 17f), 167f, 103f, false);
        CONTROLLER.close();
        CONTROLLER.moveTo(8f, 11.5f);
        CONTROLLER.lineTo(8f, 14.5f);
        CONTROLLER.moveTo(6.5f, 13f);
        CONTROLLER.lineTo(9.5f, 13f);
    }

    /** Symbol in der Größe groesse (px) an x/y zeichnen, Strichstärke im 24er-Raster. */
    static void malen(Canvas c, Path p, float x, float y, float groesse, float strich, int farbe, Paint stift) {
        float s = groesse / 24f;
        c.save();
        c.translate(x, y);
        c.scale(s, s);
        stift.setStyle(Paint.Style.STROKE);
        stift.setStrokeWidth(strich);
        stift.setStrokeCap(Paint.Cap.ROUND);
        stift.setStrokeJoin(Paint.Join.ROUND);
        stift.setColor(farbe);
        stift.setShader(null);
        stift.clearShadowLayer();
        c.drawPath(p, stift);
        if (p == CONTROLLER) {
            stift.setStyle(Paint.Style.FILL);
            c.drawCircle(15.5f, 12.3f, 1.6f, stift);
            c.drawCircle(17.2f, 13.9f, 1.6f, stift);
        }
        c.restore();
    }
}
