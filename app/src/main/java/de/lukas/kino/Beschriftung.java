package de.lukas.kino;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Große Uhr links oben und der Bildname mit Ortssymbol rechts oben, beide mit weichem Textschatten. */
final class Beschriftung extends View {
    static final int RAND = 60;

    private String text = "";
    private final Paint schrift;
    private final Paint sym = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean rechts;
    private final float zeile;

    /** rechts=false: Uhr (176 px, fett, Ziffern gleich breit). rechts=true: Ort (48 px mit Symbol). */
    Beschriftung(Context c, boolean rechts) {
        super(c);
        this.rechts = rechts;
        if (rechts) {
            schrift = Schriften.stift(c, 48, 600, 0xFFFFFFFF);
            schrift.setLetterSpacing(-0.5f / 48f);
            schrift.setShadowLayer(Ani.schatten(30), 0, 4, Ani.farbe(0x000000, 0.4f));
            zeile = Schriften.normaleZeile(schrift);
        } else {
            schrift = Schriften.stift(c, 176, 700, 0xFFFFFFFF);
            schrift.setLetterSpacing(-5f / 176f);
            schrift.setFontFeatureSettings("'tnum'");
            schrift.setShadowLayer(Ani.schatten(44), 0, 6, Ani.farbe(0x000000, 0.38f));
            zeile = 176f; // line-height: 1
        }
    }

    void text(String t) {
        String neu = t == null ? "" : t;
        if (neu.equals(text)) return;
        text = neu;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float oben = RAND;
        float grund = Schriften.grundlinie(schrift, oben, zeile);
        if (!rechts) {
            c.drawText(text, RAND, grund, schrift);
            return;
        }
        float tw = schrift.measureText(text);
        float rechtsKante = getWidth() - RAND;
        c.drawText(text, rechtsKante - tw, grund, schrift);
        float sx = rechtsKante - tw - 14 - 38;
        Symbole.malen(c, Symbole.ORT, sx, oben + (zeile - 38) / 2f, 38, 2f, 0xFFFFFFFF, sym);
    }
}
