package de.lukas.kino;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Weiches oranges Leuchten vom Bildschirmrand und darüber frei schwebende Glas-Blasen wie in
 * iMessage: rechts orange, was Lukas sagt (live mitgeschrieben), links Glas, was Claude antwortet.
 * Kein Kasten, kein Textfeld. Hört Claude zu, tippt die eigene Blase drei Punkte; denkt Claude,
 * tippt seine Blase drei Punkte. Neue Blasen wachsen aus der Ecke mit dem Zipfel heraus,
 * eine Punkte-Blase verwandelt sich weich in die Text-Blase. Ältere Blasen blenden oben aus.
 * Wird vom StartseitenWaechter als Bedienungshilfen-Overlay angezeigt, liegt also über allem.
 */
final class SprachRahmen extends View {

    static final int HOERT = 1, DENKT = 2, ANTWORT = 3, FEHLER = 4;

    private static final int ORANGE = 0xFFFF8A00;
    private static final int GOLD = 0xFFFFB347;
    private static final int GLAS_OBEN = 0xC73C3C44, GLAS_UNTEN = 0xDE1E1E23;
    private static final int DU_OBEN = 0xF5FFA42E, DU_UNTEN = 0xF5FF8400;
    private static final long ERSCHEINEN_MS = 420;

    private int zustand = HOERT;
    private String status = "";
    private List<String[]> verlauf = new ArrayList<>();
    private final long start = SystemClock.uptimeMillis();

    /** Erscheinzeit und aktuell gezeigte Größe je Blase (Schlüssel = Verlaufseintrag bzw. Tipp-Blase). */
    private final IdentityHashMap<Object, Long> erschienen = new IdentityHashMap<>();
    private final IdentityHashMap<Object, float[]> groesse = new IdentityHashMap<>();
    private final Object tippDu = new Object(), tippClaude = new Object();
    private final HashMap<String, StaticLayout> layoutCache = new HashMap<>();

    private final Paint schein = new Paint();
    private final Paint fuell = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glanz = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint schimmer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint punkt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint duStift, claudeStift, hinweisStift;

    private static final class Blase {
        final Object schluessel;
        final boolean du;
        final StaticLayout text; // null = drei Punkte
        float y0, y1;

        Blase(Object s, boolean du, StaticLayout t) {
            schluessel = s;
            this.du = du;
            text = t;
        }
    }

    SprachRahmen(Context c) {
        super(c);
        glanz.setStyle(Paint.Style.STROKE);
        punkt.setColor(0xFFFFFFFF);
        duStift = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        duStift.setColor(0xFFFFFFFF);
        duStift.setTypeface(Schriften.gewicht(c, 500));
        claudeStift = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        claudeStift.setColor(0xFFFFFFFF);
        claudeStift.setTypeface(Schriften.gewicht(c, 450));
        hinweisStift = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        hinweisStift.setTypeface(Schriften.gewicht(c, 500));
        hinweisStift.setTextAlign(Paint.Align.CENTER);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** verlauf: Einträge {"du"|"claude", text}, „…“ = noch kein Text; null = unverändert. */
    void setze(int zustand, String status, List<String[]> verlauf) {
        this.zustand = zustand;
        this.status = status == null ? "" : status;
        if (verlauf != null) this.verlauf = verlauf;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float d = h / 1080f;
        long jetzt = SystemClock.uptimeMillis();
        float t = (jetzt - start) / 1000f;
        float ein = Math.min(1f, (jetzt - start) / 400f);

        float puls;
        if (zustand == HOERT) puls = 0.78f + 0.22f * (float) Math.sin(t * 4.5);
        else if (zustand == DENKT) puls = 0.72f + 0.10f * (float) Math.sin(t * 2.6);
        else if (zustand == FEHLER) puls = 0.5f;
        else puls = 0.62f + 0.06f * (float) Math.sin(t * 1.6);

        abdunkeln(c, w, h, ein);
        leuchten(c, w, h, d, puls * ein);
        blasen(c, w, h, d, jetzt, t);
        postInvalidateOnAnimation();
    }

    /** Bild leicht abdunkeln, unten (wo die Blasen schweben) kräftiger – mehr Kontrast für die Blasen. */
    private void abdunkeln(Canvas c, int w, int h, float ein) {
        c.drawColor(((int) (0x3D * ein)) << 24);
        int a = (int) (0x99 * ein);
        float oben = h * 0.22f;
        schein.setShader(new LinearGradient(0, h, 0, oben, new int[]{a << 24, (a / 3) << 24, 0},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, oben, w, h, schein);
        schein.setShader(null);
    }

    /** Nur weiches Leuchten: Verlauf von jedem Rand nach innen, in den Ecken zusätzlich ein runder Schein. */
    private void leuchten(Canvas c, int w, int h, float d, float staerke) {
        float breite = 110f * d;
        int a = (int) (150 * staerke);
        int voll = (a << 24) | (ORANGE & 0x00FFFFFF);
        int halb = ((a / 3) << 24) | (ORANGE & 0x00FFFFFF);
        int klar = ORANGE & 0x00FFFFFF;
        float[] stops = {0f, 0.35f, 1f};
        int[] farben = {voll, halb, klar};
        schein.setShader(new LinearGradient(0, 0, 0, breite, farben, stops, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, breite, schein);
        schein.setShader(new LinearGradient(0, h, 0, h - breite, farben, stops, Shader.TileMode.CLAMP));
        c.drawRect(0, h - breite, w, h, schein);
        schein.setShader(new LinearGradient(0, 0, breite, 0, farben, stops, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, breite, h, schein);
        schein.setShader(new LinearGradient(w, 0, w - breite, 0, farben, stops, Shader.TileMode.CLAMP));
        c.drawRect(w - breite, 0, w, h, schein);

        float rad = 260f * d;
        int ecke = ((int) (110 * staerke) << 24) | (GOLD & 0x00FFFFFF);
        float[][] ecken = {{0, 0}, {w, 0}, {0, h}, {w, h}};
        for (float[] e : ecken) {
            schein.setShader(new RadialGradient(e[0], e[1], rad, ecke, GOLD & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(e[0], e[1], rad, schein);
        }
        schein.setShader(null);
    }

    // ------------------------------------------------------------------ Blasen

    private void blasen(Canvas c, int w, int h, float d, long jetzt, float t) {
        float spalte = Math.min(w * 0.52f, 1000 * d);
        float links = (w - spalte) / 2f, rechts = links + spalte;
        float maxBreite = spalte * 0.78f;
        float padX = 26 * d, padY = 15 * d, minHoehe = 60 * d;
        duStift.setTextSize(30 * d);
        claudeStift.setTextSize(32 * d);
        hinweisStift.setTextSize(22 * d);

        // 1. Liste der Blasen (inkl. Tipp-Blasen mit drei Punkten)
        List<Blase> liste = new ArrayList<>();
        for (String[] e : verlauf) {
            boolean du = "du".equals(e[0]);
            String text = e[1] == null ? "" : e[1];
            if (text.isEmpty() || "…".equals(text)) {
                liste.add(new Blase(e, du, null));
                continue;
            }
            if (du && text.length() > 160) text = "…" + text.substring(text.length() - 160);
            liste.add(new Blase(e, du, layout(text, du, (int) (maxBreite - 2 * padX))));
        }
        Blase letzte = liste.isEmpty() ? null : liste.get(liste.size() - 1);
        if (zustand == HOERT && (letzte == null || !letzte.du)) liste.add(new Blase(tippDu, true, null));
        if (zustand == DENKT && letzte != null && letzte.du && letzte.text != null) {
            liste.add(new Blase(tippClaude, false, null));
        }

        // 2. Erscheinzeiten; eine Punkte-Blase geht nahtlos in die erste echte Blase über.
        boolean tippDuDa = false, tippClaudeDa = false;
        for (Blase b : liste) {
            if (b.schluessel == tippDu) tippDuDa = true;
            if (b.schluessel == tippClaude) tippClaudeDa = true;
        }
        for (Blase b : liste) {
            if (erschienen.containsKey(b.schluessel)) continue;
            Object tipp = b.du ? tippDu : tippClaude;
            boolean tippWeg = b.du ? !tippDuDa : !tippClaudeDa;
            if (b.schluessel != tipp && tippWeg && erschienen.containsKey(tipp)) {
                erschienen.put(b.schluessel, erschienen.remove(tipp));
                float[] g = groesse.remove(tipp);
                if (g != null) groesse.put(b.schluessel, g);
            } else {
                erschienen.put(b.schluessel, jetzt);
            }
        }
        if (!tippDuDa) {
            erschienen.remove(tippDu);
            groesse.remove(tippDu);
        }
        if (!tippClaudeDa) {
            erschienen.remove(tippClaude);
            groesse.remove(tippClaude);
        }

        // 3. Größen weich nachführen
        for (Blase b : liste) {
            float zielB, zielH;
            if (b.text == null) {
                zielB = 104 * d;
                zielH = minHoehe;
            } else {
                zielB = Math.min(maxBreite, maxZeile(b.text) + 2 * padX);
                zielH = Math.max(minHoehe, b.text.getHeight() + 2 * padY);
            }
            float[] g = groesse.get(b.schluessel);
            if (g == null) {
                groesse.put(b.schluessel, new float[]{zielB, zielH});
            } else {
                g[0] += (zielB - g[0]) * 0.2f;
                g[1] += (zielH - g[1]) * 0.2f;
                if (Math.abs(zielB - g[0]) < 0.5f) g[0] = zielB;
                if (Math.abs(zielH - g[1]) < 0.5f) g[1] = zielH;
            }
        }

        // 4. Hinweis unter den Blasen (nur wenn es etwas zu sagen gibt)
        float unten = h - 76 * d;
        if (!status.isEmpty()) {
            hinweisStift.setColor(zustand == FEHLER ? 0xFFFF8A7A : 0x99FFFFFF);
            hinweisStift.setShadowLayer(10 * d, 0, 2 * d, 0xB3000000);
            c.drawText(status, w / 2f, unten, hinweisStift);
            unten -= 44 * d;
        }

        // 5. Von unten nach oben stapeln, oben ausblenden
        float oben = h * 0.30f, blende = 150 * d;
        for (int i = liste.size() - 1; i >= 0; i--) {
            Blase b = liste.get(i);
            float[] g = groesse.get(b.schluessel);
            b.y1 = unten;
            b.y0 = unten - g[1];
            if (b.y1 < oben) break;
            boolean schwanz = i == liste.size() - 1 || liste.get(i + 1).du != b.du;
            float a = Math.max(0f, Math.min(1f, (b.y0 - oben) / blende));
            float x0 = b.du ? rechts - g[0] : links;
            einzeln(c, b, new RectF(x0, b.y0, x0 + g[0], b.y1), schwanz, a, d, padX, padY, jetzt, t);
            unten = b.y0 - (i > 0 && liste.get(i - 1).du == b.du ? 8 * d : 22 * d);
        }
    }

    private void einzeln(Canvas c, Blase b, RectF r, boolean schwanz, float sichtbar, float d,
                         float padX, float padY, long jetzt, float t) {
        Long seit = erschienen.get(b.schluessel);
        float p = seit == null ? 1f : Math.min(1f, (jetzt - seit) / (float) ERSCHEINEN_MS);
        float skala = 0.55f + 0.45f * zurueckFedern(p);
        float alpha = Math.min(1f, p * 1.8f) * sichtbar;
        if (alpha <= 0.01f) return;
        float hoch = (1 - (float) (1 - Math.pow(1 - p, 3))) * 26 * d;

        Path form = form(r, Math.min(28 * d, r.height() / 2f), b.du, schwanz, d);
        int c0 = c.save();
        c.translate(0, hoch);
        c.scale(skala, skala, b.du ? r.right : r.left, r.bottom);
        RectF ebene = new RectF(r.left - 50 * d, r.top - 50 * d, r.right + 50 * d, r.bottom + 60 * d);
        if (alpha < 0.99f) c.saveLayerAlpha(ebene, (int) (255 * alpha));

        // Glas: Verlauf, weicher Schatten, Lichtkante oben, leichter Schimmer in der oberen Hälfte
        fuell.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                b.du ? DU_OBEN : GLAS_OBEN, b.du ? DU_UNTEN : GLAS_UNTEN, Shader.TileMode.CLAMP));
        fuell.setShadowLayer(24 * d, 0, 7 * d, 0x66000000);
        c.drawPath(form, fuell);
        fuell.clearShadowLayer();

        c.save();
        c.clipPath(form);
        schimmer.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.55f,
                b.du ? 0x33FFFFFF : 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(r.left - 20 * d, r.top, r.right + 20 * d, r.top + r.height() * 0.55f, schimmer);
        if (b.text == null) {
            punkte(c, r, d, t);
        } else {
            c.translate(r.left + padX, r.top + padY);
            b.text.draw(c);
        }
        c.restore();

        glanz.setStrokeWidth(1.6f * d);
        glanz.setShader(new LinearGradient(0, r.top, 0, r.top + Math.min(r.height(), 44 * d),
                b.du ? 0x80FFFFFF : 0x59FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawPath(form, glanz);

        c.restoreToCount(c0);
    }

    /** Drei Punkte, die nacheinander aufleuchten – wie „schreibt gerade“ in iMessage. */
    private void punkte(Canvas c, RectF r, float d, float t) {
        float abstand = 20 * d;
        float mx = r.centerX(), my = r.centerY();
        for (int i = 0; i < 3; i++) {
            float welle = (float) (0.5 + 0.5 * Math.sin(2 * Math.PI * (t / 1.25 - i * 0.16)));
            punkt.setAlpha((int) (255 * (0.35f + 0.65f * welle)));
            c.drawCircle(mx + (i - 1) * abstand, my - 2 * d * welle, (6 + 1.2f * welle) * d, punkt);
        }
    }

    /** Abgerundete Blase, beim letzten Beitrag einer Seite mit Zipfel unten außen (wie iMessage). */
    private static Path form(RectF r, float rad, boolean rechts, boolean schwanz, float d) {
        Path p = new Path();
        p.addRoundRect(r, rad, rad, Path.Direction.CW);
        if (!schwanz) return p;
        float b = r.bottom;
        float s = rechts ? 1 : -1;
        float x = rechts ? r.right : r.left;
        Path z = new Path();
        z.moveTo(x - s * 1 * d, b - 26 * d);
        z.cubicTo(x - s * 1 * d, b - 9 * d, x + s * 4 * d, b - 2 * d, x + s * 11 * d, b);
        z.cubicTo(x + s * 2 * d, b + 1.5f * d, x - s * 8 * d, b + 0.5f * d, x - s * 17 * d, b - 4 * d);
        z.close();
        p.op(z, Path.Op.UNION);
        return p;
    }

    private static float zurueckFedern(float p) {
        float c1 = 1.4f, c3 = c1 + 1;
        float q = p - 1;
        return 1 + c3 * q * q * q + c1 * q * q;
    }

    private static float maxZeile(StaticLayout l) {
        float m = 0;
        for (int i = 0; i < l.getLineCount(); i++) m = Math.max(m, l.getLineWidth(i));
        return m;
    }

    private StaticLayout layout(String text, boolean du, int breite) {
        TextPaint stift = du ? duStift : claudeStift;
        String k = (du ? "d" : "c") + breite + "|" + stift.getTextSize() + "|" + text;
        StaticLayout l = layoutCache.get(k);
        if (l != null) return l;
        if (layoutCache.size() > 60) layoutCache.clear();
        l = StaticLayout.Builder.obtain(text, 0, text.length(), stift, Math.max(10, breite))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, 1.12f)
                .setMaxLines(du ? 4 : 9)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
        layoutCache.put(k, l);
        return l;
    }
}
