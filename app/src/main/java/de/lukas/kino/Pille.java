package de.lukas.kino;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Glas-Pille mit Symbol und Text: das Suchfeld und die (seltene) Fehlermeldung oben. */
final class Pille extends View {
    static final int RAND = 70;

    private String text = "";
    private final Paint schrift;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint schatten = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path symbol;
    private final float symbolGroesse, symbolStrich, abstand, innenX, innenY, festeBreite, festeHoehe, radius;
    private final int toenung, rand;
    private final boolean ring, mittig;
    private final float schattenBlur, schattenY, schattenA;

    Pille(Context c, float schriftGroesse, int gewicht, Path symbol, float symbolGroesse, float symbolStrich,
          float abstand, float innenX, float innenY, float festeBreite, float festeHoehe, float radius,
          int toenung, int rand, boolean ring, float schattenBlur, float schattenY, float schattenA, boolean mittig) {
        super(c);
        schrift = Schriften.stift(c, schriftGroesse, gewicht, 0xFFFFFFFF);
        this.symbol = symbol;
        this.symbolGroesse = symbolGroesse;
        this.symbolStrich = symbolStrich;
        this.abstand = abstand;
        this.innenX = innenX;
        this.innenY = innenY;
        this.festeBreite = festeBreite;
        this.festeHoehe = festeHoehe;
        this.radius = radius;
        this.toenung = toenung;
        this.rand = rand;
        this.ring = ring;
        this.schattenBlur = schattenBlur;
        this.schattenY = schattenY;
        this.schattenA = schattenA;
        this.mittig = mittig;
        Glas.NUTZER.add(this);
    }

    void text(String t) {
        text = t == null ? "" : t;
        invalidate();
    }

    private RectF flaeche() {
        float zeile = Schriften.normaleZeile(schrift);
        float inhalt = (symbol != null ? symbolGroesse + abstand : 0) + schrift.measureText(text);
        float w = festeBreite > 0 ? festeBreite : innenX * 2 + inhalt;
        float h = festeHoehe > 0 ? festeHoehe : innenY * 2 + zeile;
        float x = mittig ? (getWidth() - w) / 2f : RAND;
        return new RectF(x, RAND, x + w, RAND + h);
    }

    @Override
    protected void onDraw(Canvas c) {
        RectF r = flaeche();
        float rad = Math.min(radius, r.height() / 2f);
        Path form = new Path();
        form.addRoundRect(r, rad, rad, Path.Direction.CW);
        if (schattenA > 0) {
            c.save();
            c.clipOutPath(form);
            schatten.setColor(0xFF000000);
            schatten.setShadowLayer(Ani.schatten(schattenBlur), 0, schattenY, Ani.farbe(0x000000, schattenA));
            c.drawRoundRect(r, rad, rad, schatten);
            c.restore();
        }
        if (ring) {
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4f);
            p.setColor(Ani.farbe(0xFFFFFF, 0.95f));
            RectF rr = new RectF(r.left - 2, r.top - 2, r.right + 2, r.bottom + 2);
            c.drawRoundRect(rr, rad + 2, rad + 2, p);
            p.setStyle(Paint.Style.FILL);
        }
        Glas.malen(c, this, r, rad, toenung, rand, p);

        float x = r.left + innenX;
        float zeile = Schriften.normaleZeile(schrift);
        if (symbol != null) {
            Symbole.malen(c, symbol, x, r.centerY() - symbolGroesse / 2f, symbolGroesse, symbolStrich, 0xFFFFFFFF, p);
            x += symbolGroesse + abstand;
        }
        c.drawText(text, x, Schriften.grundlinie(schrift, r.centerY() - zeile / 2f, zeile), schrift);
    }
}
