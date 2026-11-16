package de.lukas.kino;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * Ein bisschen Leben in den Genre-Szenen (Wunsch Lukas 16.11.2026): über dem Hintergrundbild einer Kategorie
 * bewegt sich etwas Passendes – Funken bei Action, Konfetti bei Komödien, Fledermäuse bei Horror, Regen bei
 * Thriller, Herzen bei Liebesfilmen, Glühwürmchen bei Kindheit …
 *
 * Bewusst sparsam: wenige kleine Teile, nur oben rechts (dort steht das Motiv), alles wird aus der Uhrzeit
 * berechnet – kein Speicher, keine Objekte pro Bild. Die Bühne zeichnet dafür nur ca. 25-mal pro Sekunde neu.
 *
 * Koordinaten: Bühne 1920 x 1080. Das Motiv im Bild liegt bei x = 1160 + 0,75 * Kern-x, y = -120 + 0,75 * Kern-y
 * (siehe werkzeuge/genre_kunst/genre_kunst.py), der Horizont also bei y = 480.
 */
final class GenreEffekt {

    private static final float X0 = 1000, X1 = 1930, HORIZONT = 480;
    private static final Path pfad = new Path();
    private static final int[] BUNT = {0xE3261B, 0x1592C4, 0x2E8B3F, 0xFFFFFF, 0xE8508D, 0x6A3DE8};

    private GenreEffekt() {
    }

    /** Gibt es für diese Kategorie etwas Bewegtes? */
    static boolean kennt(String genre) {
        return art(genre) != 0;
    }

    private static int art(String g) {
        if (g == null) return 0;
        switch (g) {
            case "Action": return 1;
            case "Komödien": return 2;
            case "Horror": return 3;
            case "Thriller": return 4;
            case "Science-Fiction": return 5;
            case "Krimi": return 6;
            case "Drama": return 7;
            case "Abenteuer": return 8;
            case "Fantasy": return 9;
            case "Mystery": return 10;
            case "Liebesfilme": return 11;
            case "Kindheit":
            case "Familie & Animation":
            case "Familie und Animation": return 12;
            case "Kriegsfilme": return 13;
            case "Historienfilme": return 14;
            case "Western": return 15;
            default: return 0;
        }
    }

    /**
     * So weit rutscht die Szene in der geöffneten Kategorie nach oben (Bühnen-Pixel).
     * gross = erste Zeile: das obere Drittel gehört dem Bild, fast das ganze Motiv ist zu sehen.
     * Sonst (weiter unten in der Liste) ist oben nur ein schmaler Streifen frei – darin steht der wichtigste Teil.
     */
    static float wandHub(String genre, boolean gross) {
        switch (art(genre)) {
            case 1: return gross ? 90 : 200;    // Action: Hubschrauber, Feuerball
            case 2: return gross ? 90 : 180;    // Komödien: Gesicht der Maske
            case 3: return gross ? 60 : 180;    // Horror: Spukhaus vor dem Mond
            case 4: return gross ? 60 : 150;    // Thriller: Klinge vor der roten Scheibe
            case 5: return gross ? 60 : 170;    // Science-Fiction: Rakete und Ringplanet
            case 6: return gross ? 140 : 200;   // Krimi: Laterne, Hut und Mantel
            case 7: return gross ? 150 : 290;   // Drama: Gestalt im Licht
            case 8: return gross ? 110 : 250;   // Abenteuer: Pyramide und Palmen
            case 9: return gross ? 30 : 200;    // Fantasy: Drache, Schloss auf der Insel
            case 10: return gross ? 130 : 280;  // Mystery: leuchtendes Tor
            case 11: return gross ? 90 : 255;   // Liebesfilme: Paar vor der Sonne
            case 12: return gross ? 40 : 170;   // Kindheit: Baum mit Schaukel
            case 13: return gross ? 140 : 320;  // Kriegsfilme: Panzer und Soldaten
            case 14: return gross ? 110 : 260;  // Historienfilme: Tempel und Arena
            case 15: return gross ? 130 : 280;  // Western: Felsen, Kakteen, Sonne
            default: return gross ? 100 : 250;
        }
    }

    /** Fester Zufallswert 0..1 für Teilchen i, Eigenschaft k. */
    private static float z(int i, int k) {
        int h = i * 374761393 + k * 668265263 + 1013904223;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / 16777216f;
    }

    /** Lauf 0..1 eines Teilchens mit eigener Dauer (Sekunden) und eigenem Versatz. */
    private static float lauf(double sek, int i, float dauerMin, float dauerMax) {
        float dauer = dauerMin + (dauerMax - dauerMin) * z(i, 7);
        double t = sek / dauer + z(i, 8);
        return (float) (t - Math.floor(t));
    }

    private static int farbe(int rgb, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    private static float sin(double x) {
        return (float) Math.sin(x);
    }

    /**
     * Zeichnet die Teilchen der Kategorie. ms = Uhr, alpha = Deckkraft (0..1), dy = senkrechter Versatz des Bildes
     * (in der Poster-Wand rutscht es nach oben), unten = bis zu welcher Höhe gezeichnet wird.
     */
    static void malen(Canvas c, Paint p, String genre, long ms, float alpha, float dy, float unten) {
        int a = art(genre);
        if (a == 0 || alpha <= 0.01f) return;
        double s = ms / 1000.0;
        c.save();
        c.clipRect(0, 0, 1920, unten);
        c.translate(0, dy);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        switch (a) {
            case 1: { // Action: Funken steigen aus der Explosion
                for (int i = 0; i < 26; i++) {
                    float t = lauf(s, i, 3f, 6.5f);
                    float x = 1610 + (z(i, 1) - 0.5f) * 520 + sin(t * 7 + i) * 26 * t;
                    float y = 400 - t * (300 + 160 * z(i, 2));
                    p.setColor(farbe(z(i, 3) > 0.5f ? 0xFFD25A : 0xFF8A1E, alpha * sin(Math.PI * t) * 0.95f));
                    c.drawCircle(x, y, 1.8f + 2.6f * z(i, 4), p);
                }
                break;
            }
            case 2: { // Komödien: Konfetti fällt
                for (int i = 0; i < 26; i++) {
                    float t = lauf(s, i, 5f, 9f);
                    float x = X0 + z(i, 1) * (X1 - X0) + sin(t * 9 + i) * 22;
                    float y = -20 + t * 560;
                    c.save();
                    c.rotate(t * 720 * (z(i, 2) > 0.5f ? 1 : -1) + i * 40, x, y);
                    p.setColor(farbe(BUNT[i % BUNT.length], alpha * Math.min(1f, (1f - t) * 4f)));
                    c.drawRect(x - 7, y - 4, x + 7, y + 4, p);
                    c.restore();
                }
                break;
            }
            case 3: { // Horror: Fledermäuse ziehen vorbei
                // Flügel als feste Fläche (Vorderkante Schulter → Spitze, gezackte Hinterkante): beim Schlagen hebt und senkt
                // sich nur die Spitze, die Fläche verschwindet nie (vorher klappte die Form in sich zusammen – Meldung Lukas).
                p.setColor(farbe(0x0B0312, alpha));
                for (int i = 0; i < 11; i++) {
                    float t = lauf(s, i, 8f, 15f);
                    float x = X1 + 60 - t * (X1 - X0 + 320);
                    float y = 50 + z(i, 1) * 300 + sin(t * 12 + i) * 18;
                    float g = 8 + 8 * z(i, 2);
                    float schlag = sin(s * (7 + 3 * z(i, 3)) + i * 1.3);  // -1 (Flügel unten) … 1 (Flügel oben)
                    float spitze = -g * (0.15f + 0.75f * schlag);         // Höhe der Flügelspitze
                    pfad.reset();
                    pfad.moveTo(x, y - g * 0.35f);                                  // Kopf
                    pfad.lineTo(x + g * 0.22f, y - g * 0.75f);                      // Ohr rechts
                    pfad.lineTo(x + g * 0.38f, y - g * 0.3f);
                    pfad.lineTo(x + g * 1.3f, y - g * 0.25f + spitze * 0.75f);      // Vorderkante
                    pfad.lineTo(x + g * 2.3f, y + spitze);                          // Flügelspitze
                    pfad.quadTo(x + g * 1.95f, y + g * 0.25f + spitze * 0.7f, x + g * 1.55f, y + g * 0.55f + spitze * 0.5f);
                    pfad.quadTo(x + g * 1.2f, y + g * 0.2f + spitze * 0.35f, x + g * 0.85f, y + g * 0.6f + spitze * 0.25f);
                    pfad.quadTo(x + g * 0.55f, y + g * 0.3f, x + g * 0.3f, y + g * 0.55f);
                    pfad.lineTo(x, y + g * 0.85f);                                  // Körper unten
                    pfad.lineTo(x - g * 0.3f, y + g * 0.55f);
                    pfad.quadTo(x - g * 0.55f, y + g * 0.3f, x - g * 0.85f, y + g * 0.6f + spitze * 0.25f);
                    pfad.quadTo(x - g * 1.2f, y + g * 0.2f + spitze * 0.35f, x - g * 1.55f, y + g * 0.55f + spitze * 0.5f);
                    pfad.quadTo(x - g * 1.95f, y + g * 0.25f + spitze * 0.7f, x - g * 2.3f, y + spitze);
                    pfad.lineTo(x - g * 1.3f, y - g * 0.25f + spitze * 0.75f);
                    pfad.lineTo(x - g * 0.38f, y - g * 0.3f);
                    pfad.lineTo(x - g * 0.22f, y - g * 0.75f);                      // Ohr links
                    pfad.close();
                    c.drawPath(pfad, p);
                }
                break;
            }
            case 4: { // Thriller: Regen
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.6f);
                for (int i = 0; i < 64; i++) {
                    float t = lauf(s, i, 0.8f, 1.5f);
                    float x = 960 + z(i, 1) * 1000 - t * 46;
                    float y = -40 + t * 600;
                    p.setColor(farbe(0xC0CAD8, alpha * (0.14f + 0.2f * z(i, 2))));
                    c.drawLine(x, y, x - 11, y + 38, p);
                }
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case 5: { // Science-Fiction: Sterne funkeln, ab und zu eine Sternschnuppe
                for (int i = 0; i < 44; i++) {
                    float x = 900 + z(i, 1) * 1020, y = z(i, 2) * 300;
                    float f = 0.25f + 0.75f * Math.abs(sin(s * (0.6 + 1.2 * z(i, 3)) + i));
                    p.setColor(farbe(0xE6FBFF, alpha * f));
                    c.drawCircle(x, y, 1.2f + 1.6f * z(i, 4), p);
                }
                double zyk = s / 7.0;
                int nr = (int) Math.floor(zyk);
                float t = (float) (zyk - nr) * 7f / 0.9f;                 // 0,9 Sekunden Flug alle 7 Sekunden
                if (t < 1f) {
                    float x = 1100 + z(nr, 5) * 600 + t * 300, y = 20 + z(nr, 6) * 120 + t * 130;
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2.4f);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setColor(farbe(0xFFFFFF, alpha * sin(Math.PI * t)));
                    c.drawLine(x, y, x - 70, y - 30, p);
                    p.setStyle(Paint.Style.FILL);
                }
                break;
            }
            case 6:   // Krimi: Staub im Licht der Laterne
            case 7: { // Drama: Staub im Scheinwerfer
                float mx = a == 6 ? 1445 : 1557, breite = a == 6 ? 150 : 110;
                for (int i = 0; i < 24; i++) {
                    float t = lauf(s, i, 7f, 13f);
                    float yy = (a == 6 ? 190 : 0) + z(i, 2) * (a == 6 ? 290 : 470);
                    float weit = breite * (0.35f + (yy / 480f) * 0.9f);
                    float x = mx + (z(i, 1) - 0.5f) * 2 * weit + sin(t * 6.3 + i) * 14;
                    float y = yy + (a == 6 ? -1 : 1) * (t - 0.5f) * 46;
                    p.setColor(farbe(a == 6 ? 0xFFF3C8 : 0xFFF1DC, alpha * sin(Math.PI * t) * 0.8f));
                    c.drawCircle(x, y, 1.4f + 1.8f * z(i, 3), p);
                }
                break;
            }
            case 8: { // Abenteuer: Blätter segeln herunter
                for (int i = 0; i < 16; i++) {
                    float t = lauf(s, i, 7f, 12f);
                    float x = X0 + z(i, 1) * (X1 - X0) + sin(t * 8 + i) * 46;
                    float y = -20 + t * 540;
                    c.save();
                    c.rotate(sin(t * 10 + i) * 50 + i * 30, x, y);
                    p.setColor(farbe(z(i, 2) > 0.5f ? 0x0B3A22 : 0x1B6A38, alpha * Math.min(1f, (1f - t) * 5f)));
                    pfad.reset();
                    pfad.moveTo(x - 10, y);
                    pfad.quadTo(x, y - 7, x + 10, y);
                    pfad.quadTo(x, y + 7, x - 10, y);
                    c.drawPath(pfad, p);
                    c.restore();
                }
                break;
            }
            case 9: { // Fantasy: Funkelsterne gehen auf und zu
                for (int i = 0; i < 22; i++) {
                    float t = lauf(s, i, 2.2f, 4.5f);
                    int runde = (int) Math.floor(s / (2.2f + 2.3f * z(i, 7)) + z(i, 8));
                    float x = 980 + z(i + runde * 31, 1) * 930, y = 10 + z(i + runde * 31, 2) * 400;
                    float g = (5 + 9 * z(i, 3)) * sin(Math.PI * t);
                    p.setColor(farbe(z(i, 4) > 0.6f ? 0xFFD98A : 0xFFFFFF, alpha * sin(Math.PI * t)));
                    pfad.reset();
                    pfad.moveTo(x, y - g);
                    pfad.quadTo(x, y, x + g, y);
                    pfad.quadTo(x, y, x, y + g);
                    pfad.quadTo(x, y, x - g, y);
                    pfad.quadTo(x, y, x, y - g);
                    c.drawPath(pfad, p);
                }
                break;
            }
            case 10: { // Mystery: Irrlichter schweben
                for (int i = 0; i < 18; i++) {
                    float t = lauf(s, i, 8f, 14f);
                    float x = X0 + z(i, 1) * (X1 - X0) + sin(s * 0.5 + i * 1.7) * 30;
                    float y = HORIZONT - 20 - t * (200 + 160 * z(i, 2));
                    float f = sin(Math.PI * t) * (0.5f + 0.5f * Math.abs(sin(s * 1.6 + i)));
                    float rr = 2f + 2.4f * z(i, 3);
                    p.setColor(farbe(0xD8FFF6, alpha * f * 0.25f));
                    c.drawCircle(x, y, rr * 3f, p);
                    p.setColor(farbe(0xEFFFFA, alpha * f));
                    c.drawCircle(x, y, rr, p);
                }
                break;
            }
            case 11: { // Liebesfilme: Herzen steigen auf
                for (int i = 0; i < 13; i++) {
                    float t = lauf(s, i, 7f, 12f);
                    float x = X0 + 40 + z(i, 1) * (X1 - X0 - 80) + sin(t * 7 + i) * 26;
                    float y = HORIZONT - t * 520;
                    float g = 7 + 9 * z(i, 2);
                    p.setColor(farbe(z(i, 3) > 0.4f ? 0xFFFFFF : 0xFFC2D6, alpha * sin(Math.PI * t) * 0.9f));
                    pfad.reset();
                    pfad.moveTo(x, y + g * 0.9f);
                    pfad.cubicTo(x - g * 1.5f, y - g * 0.2f, x - g * 0.6f, y - g * 1.25f, x, y - g * 0.2f);
                    pfad.cubicTo(x + g * 0.6f, y - g * 1.25f, x + g * 1.5f, y - g * 0.2f, x, y + g * 0.9f);
                    c.drawPath(pfad, p);
                }
                break;
            }
            case 12: { // Kindheit: Glühwürmchen schweben am Sommerabend
                for (int i = 0; i < 20; i++) {
                    float t = lauf(s, i, 7f, 13f);
                    float x = X0 + z(i, 1) * (X1 - X0) + sin(s * 0.45 + i * 1.9) * 34;
                    float y = 250 + z(i, 2) * 230 + sin(s * 0.6 + i * 0.7) * 22 - t * 30;
                    float f = sin(Math.PI * t) * (0.35f + 0.65f * Math.abs(sin(s * 1.3 + i * 2.1)));
                    float rr = 1.8f + 1.8f * z(i, 3);
                    p.setColor(farbe(0xFFE9A0, alpha * f * 0.28f));
                    c.drawCircle(x, y, rr * 3.4f, p);
                    p.setColor(farbe(0xFFF6CC, alpha * f));
                    c.drawCircle(x, y, rr, p);
                }
                break;
            }
            case 13:   // Kriegsfilme: Asche und Glut treiben
            case 14: { // Historienfilme: goldener Staub treibt
                for (int i = 0; i < 28; i++) {
                    float t = lauf(s, i, 6f, 11f);
                    float x = X1 + 20 - t * (X1 - X0 + 60) * (0.5f + 0.5f * z(i, 1));
                    float y = z(i, 2) * HORIZONT + (a == 13 ? -1 : 1) * t * 60 + sin(t * 9 + i) * 12;
                    int f = a == 14 ? 0xFFE9B0 : (z(i, 3) > 0.75f ? 0xFF9A3C : 0xC9C7A8);
                    p.setColor(farbe(f, alpha * sin(Math.PI * t) * (a == 14 ? 0.75f : 0.6f)));
                    c.drawCircle(x, y, 1.4f + 1.8f * z(i, 4), p);
                }
                break;
            }
            case 15: { // Western: Sand weht, ein Steppenläufer rollt durchs Bild
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                for (int i = 0; i < 26; i++) {
                    float t = lauf(s, i, 2.4f, 5f);
                    float x = X0 - 60 + t * (X1 - X0 + 120);
                    float y = HORIZONT - 190 + z(i, 1) * 200 + sin(t * 6 + i) * 8;
                    p.setStrokeWidth(1.4f + 1.2f * z(i, 2));
                    p.setColor(farbe(0xFFE0A0, alpha * sin(Math.PI * t) * 0.45f));
                    c.drawLine(x, y, x + 26 + 30 * z(i, 3), y - 2, p);
                }
                double zyk = s / 11.0;
                float t = (float) (zyk - Math.floor(zyk));
                float x = X0 - 40 + t * (X1 - X0 + 80);
                float y = HORIZONT - 22 - Math.abs(sin(t * 34)) * 16;
                p.setStrokeWidth(2.2f);
                p.setColor(farbe(0x230908, alpha));
                c.save();
                c.rotate(t * 2400, x, y);
                c.drawCircle(x, y, 16, p);
                c.drawCircle(x + 3, y - 2, 9, p);
                c.drawLine(x - 15, y, x + 15, y, p);
                c.drawLine(x, y - 15, x, y + 15, p);
                c.drawLine(x - 10, y - 10, x + 10, y + 10, p);
                c.restore();
                p.setStyle(Paint.Style.FILL);
                break;
            }
            default:
                break;
        }
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setColor(0xFFFFFFFF);
        p.setAlpha(255);
        c.restore();
    }
}
