package de.lukas.kino;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewParent;

import java.util.ArrayList;
import java.util.List;

/**
 * Milchglas wie CSS backdrop-filter: blur(26px) saturate(160%).
 * Statt live zu verwischen (teuer auf dem TV) gibt es eine kleine, schon verwischte Kopie
 * des Hintergrunds; jedes Glas-Element zeichnet genau den Ausschnitt, der hinter ihm liegt.
 */
final class Glas {
    static final int B = 240, H = 135;

    static volatile Bitmap unscharf;
    static View hintergrund;
    static View buehne;
    static final List<View> NUTZER = new ArrayList<>();

    private Glas() {
    }

    static void neuZeichnen() {
        for (View v : NUTZER) v.invalidate();
    }

    /** Matrix: Koordinaten in v → Koordinaten der Bühne (1920x1080). */
    static Matrix zuBuehne(View v) {
        Matrix m = new Matrix();
        View x = v;
        while (x != null && x != buehne) {
            m.postConcat(x.getMatrix());
            m.postTranslate(x.getLeft(), x.getTop());
            ViewParent p = x.getParent();
            if (!(p instanceof View)) break;
            View pv = (View) p;
            m.postTranslate(-pv.getScrollX(), -pv.getScrollY());
            x = pv;
        }
        return m;
    }

    /** Glasfläche: verwischter Hintergrund, Tönung, optional 1-px-Rand. */
    static void malen(Canvas c, View v, RectF r, float radius, int toenung, int rand, Paint p) {
        Bitmap u = unscharf;
        p.clearShadowLayer();
        p.setStyle(Paint.Style.FILL);
        if (u != null && hintergrund != null) {
            Matrix m = new Matrix();
            m.setScale(1920f / u.getWidth(), 1080f / u.getHeight());
            m.postConcat(zuBuehne(hintergrund));
            Matrix inv = new Matrix();
            if (zuBuehne(v).invert(inv)) m.postConcat(inv);
            BitmapShader s = new BitmapShader(u, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            s.setLocalMatrix(m);
            p.setShader(s);
            p.setFilterBitmap(true);
            p.setColor(0xFFFFFFFF);
            c.drawRoundRect(r, radius, radius, p);
            p.setShader(null);
        } else {
            p.setShader(null);
            p.setColor(0xFF3A2A4A);
            c.drawRoundRect(r, radius, radius, p);
        }
        p.setColor(toenung);
        c.drawRoundRect(r, radius, radius, p);
        if (rand != 0) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            p.setColor(rand);
            RectF r2 = new RectF(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f);
            c.drawRoundRect(r2, Math.max(0f, radius - 0.5f), Math.max(0f, radius - 0.5f), p);
            p.setStyle(Paint.Style.FILL);
        }
    }

    /** Aus einem Bühnenbild (beliebige Größe, schon zugeschnitten auf 16:9) die verwischte Kopie machen. */
    static Bitmap verwischen(Bitmap quelle) {
        Bitmap klein = Bitmap.createBitmap(B, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(klein);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        // Erst halbieren, dann auf Zielgröße – weniger Flimmern als in einem Schritt.
        Bitmap zwischen = quelle;
        boolean eigen = false;
        while (zwischen.getWidth() > B * 2) {
            Bitmap h = Bitmap.createScaledBitmap(zwischen, zwischen.getWidth() / 2, Math.max(1, zwischen.getHeight() / 2), true);
            if (eigen) zwischen.recycle();
            zwischen = h;
            eigen = true;
        }
        c.drawBitmap(zwischen, null, new RectF(0, 0, B, H), p);
        if (eigen) zwischen.recycle();

        int[] px = new int[B * H];
        klein.getPixels(px, 0, B, 0, 0, B, H);
        int[] tmp = new int[B * H];
        for (int i = 0; i < 3; i++) {
            kasten(px, tmp, B, H, 3, true);
            kasten(tmp, px, B, H, 3, false);
        }
        klein.setPixels(px, 0, B, 0, 0, B, H);

        Bitmap fertig = Bitmap.createBitmap(B, H, Bitmap.Config.ARGB_8888);
        Canvas c2 = new Canvas(fertig);
        Paint sat = new Paint(Paint.FILTER_BITMAP_FLAG);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.6f);
        sat.setColorFilter(new ColorMatrixColorFilter(cm));
        c2.drawBitmap(klein, 0, 0, sat);
        klein.recycle();
        return fertig;
    }

    /** Einfacher Kastenfilter (waagrecht oder senkrecht), Ränder geklemmt. */
    private static void kasten(int[] ein, int[] aus, int w, int h, int r, boolean waagrecht) {
        int n = waagrecht ? w : h, linien = waagrecht ? h : w;
        int teiler = 2 * r + 1;
        for (int l = 0; l < linien; l++) {
            int sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int px = ein[index(l, Math.max(0, Math.min(n - 1, k)), w, waagrecht)];
                sr += (px >> 16) & 0xFF;
                sg += (px >> 8) & 0xFF;
                sb += px & 0xFF;
            }
            for (int i = 0; i < n; i++) {
                aus[index(l, i, w, waagrecht)] = 0xFF000000 | ((sr / teiler) << 16) | ((sg / teiler) << 8) | (sb / teiler);
                int raus = ein[index(l, Math.max(0, i - r), w, waagrecht)];
                int rein = ein[index(l, Math.min(n - 1, i + r + 1), w, waagrecht)];
                sr += ((rein >> 16) & 0xFF) - ((raus >> 16) & 0xFF);
                sg += ((rein >> 8) & 0xFF) - ((raus >> 8) & 0xFF);
                sb += (rein & 0xFF) - (raus & 0xFF);
            }
        }
    }

    private static int index(int linie, int pos, int w, boolean waagrecht) {
        return waagrecht ? linie * w + pos : pos * w + linie;
    }
}
