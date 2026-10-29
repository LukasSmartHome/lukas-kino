package de.lukas.kino;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Eine App-Kachel (300x170, Ecken 26) mit Schatten, Banner oder Glas, Verschiebe-Pfeilen
 * und dem „Deinstallieren“-Knopf darüber. Die View ist größer als die Kachel, damit Schatten
 * und Knopf Platz haben.
 */
final class Kachel extends View {

    static final int RAND_X = 60, RAND_OBEN = 110, RAND_UNTEN = 90;
    static final int W = 300, H = 170, RADIUS = 26;
    static final int VIEW_W = W + 2 * RAND_X, VIEW_H = RAND_OBEN + H + RAND_UNTEN;

    final MainActivity.App app;
    Bitmap banner, symbol;
    private boolean geladen = false;

    private boolean fokus, gehoben, menueOffen, gewaehlt, inApps, sichtbar;
    private float fokusAnteil = 0f;
    private ValueAnimator fokusAni;
    private ObjectAnimator alphaAni;
    private boolean ersterZustand = true;

    private final RectF kachel = new RectF(RAND_X, RAND_OBEN, RAND_X + W, RAND_OBEN + H);
    private final Path kachelPfad = new Path();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint schattenStift = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint name, unter, menueText;

    Kachel(Context c, MainActivity.App app) {
        super(c);
        this.app = app;
        kachelPfad.addRoundRect(kachel, RADIUS, RADIUS, Path.Direction.CW);
        setPivotX(RAND_X + W / 2f);
        setPivotY(RAND_OBEN + H / 2f);
        name = Schriften.stift(c, 30, 650, 0xFFFFFFFF);
        name.setLetterSpacing(-0.3f / 30f);
        unter = Schriften.stift(c, 20, 500, Ani.farbe(0xFFFFFF, 0.85f));
        menueText = Schriften.stift(c, 24, 600, 0xFFFFFFFF);
        if (istGlas()) Glas.NUTZER.add(this);
    }

    boolean istGlas() {
        return app.spiel || !app.banner || geladen && banner == null;
    }

    void entfernen() {
        Glas.NUTZER.remove(this);
    }

    void bilderDa(Bitmap b, Bitmap s) {
        banner = b;
        symbol = s;
        geladen = true;
        if (istGlas()) {
            if (!Glas.NUTZER.contains(this)) Glas.NUTZER.add(this);
        } else {
            Glas.NUTZER.remove(this);
        }
        invalidate();
    }

    /**
     * Zustand setzen und weich hinbewegen (wie die CSS-Übergänge).
     * sichtbar: Phase apps oder suche. inApps: nur in Phase apps gibt es Vergrößern/Anheben.
     */
    void zustand(boolean sichtbar, boolean inApps, boolean fokus, boolean gehoben, boolean menueOffen,
                 boolean gewaehlt, long verzoegerung, boolean sofort) {
        boolean anders = ersterZustand || sichtbar != this.sichtbar || inApps != this.inApps
                || fokus != this.fokus || gehoben != this.gehoben;
        boolean nurAnzeige = menueOffen != this.menueOffen || gewaehlt != this.gewaehlt;
        this.sichtbar = sichtbar;
        this.inApps = inApps;
        this.fokus = fokus;
        this.gehoben = gehoben;
        this.menueOffen = menueOffen;
        this.gewaehlt = gewaehlt;
        setTranslationZ(fokus ? 2f : 0f);

        float zielY, zielS, zielA = sichtbar ? 1f : 0f;
        if (!sichtbar) {
            zielY = 320f;
            zielS = 1f;
        } else if (inApps && gehoben) {
            zielY = -16f;
            zielS = 1.12f;
        } else if (inApps && fokus) {
            zielY = -8f;
            zielS = 1.1f;
        } else {
            zielY = 0f;
            zielS = 1f;
        }
        float zielF = sichtbar && inApps && fokus ? 1f : 0f;

        if (sofort || ersterZustand) {
            ersterZustand = false;
            animate().cancel();
            if (alphaAni != null) alphaAni.cancel();
            if (fokusAni != null) fokusAni.cancel();
            setTranslationY(zielY);
            setScaleX(zielS);
            setScaleY(zielS);
            setAlpha(zielA);
            fokusAnteil = zielF;
            invalidate();
            return;
        }
        if (anders) {
            animate().translationY(zielY).scaleX(zielS).scaleY(zielS)
                    .setDuration(420).setInterpolator(Ani.AUS).setStartDelay(verzoegerung)
                    .setUpdateListener(istGlas() ? a -> invalidate() : null)
                    .start();
            if (getAlpha() != zielA) {
                if (alphaAni != null) alphaAni.cancel();
                alphaAni = ObjectAnimator.ofFloat(this, "alpha", getAlpha(), zielA);
                alphaAni.setDuration(380);
                alphaAni.setInterpolator(Ani.EASE);
                alphaAni.setStartDelay(verzoegerung);
                alphaAni.start();
            }
            if (fokusAnteil != zielF) {
                if (fokusAni != null) fokusAni.cancel();
                fokusAni = ValueAnimator.ofFloat(fokusAnteil, zielF);
                fokusAni.setDuration(200);
                fokusAni.setInterpolator(Ani.EASE);
                fokusAni.addUpdateListener(a -> {
                    fokusAnteil = (float) a.getAnimatedValue();
                    invalidate();
                });
                fokusAni.start();
            }
        }
        if (nurAnzeige) invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float f = fokusAnteil;

        // Schatten außen (wie box-shadow: nur außerhalb der Kachel sichtbar)
        float dy = Ani.mix(10f, 26f, f), blur = Ani.mix(28f, 60f, f), a = Ani.mix(0.28f, 0.5f, f);
        c.save();
        c.clipOutPath(kachelPfad);
        schattenStift.setStyle(Paint.Style.FILL);
        schattenStift.setColor(0xFF000000);
        schattenStift.setShadowLayer(Ani.schatten(blur), 0, dy, Ani.farbe(0x000000, a));
        c.drawRoundRect(kachel, RADIUS, RADIUS, schattenStift);
        c.restore();

        if (!istGlas() && banner != null) {
            BitmapShader s = new BitmapShader(banner, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = Hintergrund.abdecken(banner.getWidth(), banner.getHeight(), W, H);
            m.postTranslate(kachel.left, kachel.top);
            s.setLocalMatrix(m);
            p.setShader(s);
            p.setFilterBitmap(true);
            p.setStyle(Paint.Style.FILL);
            p.clearShadowLayer();
            c.drawRoundRect(kachel, RADIUS, RADIUS, p);
            p.setShader(null);
        } else if (istGlas()) {
            glasMalen(c, f);
        }

        if (gehoben && !menueOffen && inApps) pfeileMalen(c);
        if (menueOffen && inApps) menueMalen(c);
    }

    private void glasMalen(Canvas c, float f) {
        Glas.malen(c, this, kachel, RADIUS, Ani.farbe(0xFFFFFF, Ani.mix(0.13f, 0.3f, f)), Ani.farbe(0xFFFFFF, 0.3f), p);
        float x = kachel.left + 22, y = kachel.top + 20;
        if (app.spiel) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ani.farbe(0x426EFF, 0.85f));
            c.drawRoundRect(new RectF(x, y, x + 58, y + 58), 17, 17, p);
            p.setColor(Ani.farbe(0xFFFFFF, 0.35f));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            c.drawLine(x + 17, y + 0.5f, x + 41, y + 0.5f, p);
            p.setStyle(Paint.Style.FILL);
            Symbole.malen(c, Symbole.CONTROLLER, x + 13, y + 13, 32, 2f, 0xFFFFFFFF, p);
        } else if (symbol != null) {
            BitmapShader s = new BitmapShader(symbol, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(60f / symbol.getWidth(), 60f / symbol.getHeight());
            m.postTranslate(x, y);
            s.setLocalMatrix(m);
            p.setShader(s);
            p.setFilterBitmap(true);
            c.drawRoundRect(new RectF(x, y, x + 60, y + 60), 16, 16, p);
            p.setShader(null);
        }
        // Text unten links: Name (30 px) und darunter klein „HDMI 2“
        float zeileName = Schriften.normaleZeile(name);
        float zeileUnter = app.unter != null ? Schriften.normaleZeile(unter) : 0;
        float block = zeileName + (app.unter != null ? 2 + zeileUnter : 0);
        float oben = kachel.bottom - 20 - block;
        name.setShadowLayer(Ani.schatten(12), 0, 2, Ani.farbe(0x000000, 0.3f));
        c.drawText(app.name, x, Schriften.grundlinie(name, oben, zeileName), name);
        if (app.unter != null) {
            c.drawText(app.unter, x, Schriften.grundlinie(unter, oben + zeileName + 2, zeileUnter), unter);
        }
    }

    private void pfeileMalen(Canvas c) {
        float w = 10 + 18 + 4 + 18 + 10, h = 6 + 18 + 6;
        float x = kachel.right - 14 - w, y = kachel.top + 14;
        p.setStyle(Paint.Style.FILL);
        p.clearShadowLayer();
        p.setShader(null);
        p.setColor(Ani.farbe(0xFFFFFF, 0.92f));
        c.drawRoundRect(new RectF(x, y, x + w, y + h), h / 2, h / 2, p);
        Symbole.malen(c, Symbole.LINKS, x + 10, y + 6, 18, 2.6f, 0xFF1A1426, p);
        Symbole.malen(c, Symbole.RECHTS, x + 10 + 18 + 4, y + 6, 18, 2.6f, 0xFF1A1426, p);
    }

    private void menueMalen(Canvas c) {
        String text = "Deinstallieren";
        menueText.setColor(gewaehlt ? 0xFF141218 : 0xFFFFFFFF);
        float tw = menueText.measureText(text);
        float zeile = Math.max(26f, Schriften.normaleZeile(menueText));
        float w = 26 + 26 + 12 + tw + 26, h = 14 + zeile + 14;
        float cx = kachel.centerX();
        RectF r = new RectF(cx - w / 2, kachel.top - 26 - h, cx + w / 2, kachel.top - 26);
        float rad = h / 2;

        Path pille = new Path();
        pille.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.save();
        c.clipOutPath(pille);
        schattenStift.setColor(0xFF000000);
        if (gewaehlt) {
            schattenStift.setShadowLayer(Ani.schatten(40), 0, 16, Ani.farbe(0x000000, 0.45f));
        } else {
            schattenStift.setShadowLayer(Ani.schatten(30), 0, 12, Ani.farbe(0x000000, 0.35f));
        }
        c.drawRoundRect(r, rad, rad, schattenStift);
        c.restore();

        if (gewaehlt) {
            p.setShader(null);
            p.clearShadowLayer();
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ani.farbe(0xFFFFFF, 0.95f));
            RectF ring = new RectF(r.left - 4, r.top - 4, r.right + 4, r.bottom + 4);
            c.drawRoundRect(ring, rad + 4, rad + 4, p);
            p.setColor(0xFFFFFFFF);
            c.drawRoundRect(r, rad, rad, p);
        } else {
            Glas.malen(c, this, r, rad, Ani.farbe(0xFFFFFF, 0.18f), Ani.farbe(0xFFFFFF, 0.35f), p);
        }
        int farbe = gewaehlt ? 0xFF141218 : 0xFFFFFFFF;
        Symbole.malen(c, Symbole.MUELL, r.left + 26, r.centerY() - 13, 26, 2.2f, farbe, p);
        c.drawText(text, r.left + 26 + 26 + 12, Schriften.grundlinie(menueText, r.centerY() - zeile / 2, zeile), menueText);
    }
}
