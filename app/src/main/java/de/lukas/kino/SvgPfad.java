package de.lukas.kino;

import android.graphics.Path;

/** Winziger SVG-Pfad-Leser (M, L, H, V, C, Q, Z – groß und klein), reicht für das Standardbild. */
final class SvgPfad {
    private SvgPfad() {
    }

    static Path lesen(String d) {
        Path p = new Path();
        int i = 0, n = d.length();
        char befehl = 'M';
        float x = 0, y = 0, sx = 0, sy = 0;
        float[] z = new float[6];
        while (i < n) {
            char ch = d.charAt(i);
            if (Character.isWhitespace(ch) || ch == ',') {
                i++;
                continue;
            }
            if (Character.isLetter(ch)) {
                befehl = ch;
                i++;
                if (ch == 'Z' || ch == 'z') {
                    p.close();
                    x = sx;
                    y = sy;
                }
                continue;
            }
            int anzahl;
            switch (Character.toUpperCase(befehl)) {
                case 'H':
                case 'V':
                    anzahl = 1;
                    break;
                case 'C':
                    anzahl = 6;
                    break;
                case 'Q':
                    anzahl = 4;
                    break;
                default:
                    anzahl = 2;
            }
            for (int k = 0; k < anzahl; k++) {
                while (i < n && (Character.isWhitespace(d.charAt(i)) || d.charAt(i) == ',')) i++;
                int start = i;
                if (i < n && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
                while (i < n && (Character.isDigit(d.charAt(i)) || d.charAt(i) == '.')) i++;
                z[k] = Float.parseFloat(d.substring(start, i));
            }
            boolean rel = Character.isLowerCase(befehl);
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(befehl)) {
                case 'M':
                    x = ox + z[0];
                    y = oy + z[1];
                    p.moveTo(x, y);
                    sx = x;
                    sy = y;
                    befehl = rel ? 'l' : 'L';
                    break;
                case 'L':
                    x = ox + z[0];
                    y = oy + z[1];
                    p.lineTo(x, y);
                    break;
                case 'H':
                    x = (rel ? x : 0) + z[0];
                    p.lineTo(x, y);
                    break;
                case 'V':
                    y = (rel ? y : 0) + z[0];
                    p.lineTo(x, y);
                    break;
                case 'C':
                    p.cubicTo(ox + z[0], oy + z[1], ox + z[2], oy + z[3], ox + z[4], oy + z[5]);
                    x = ox + z[4];
                    y = oy + z[5];
                    break;
                case 'Q':
                    p.quadTo(ox + z[0], oy + z[1], ox + z[2], oy + z[3]);
                    x = ox + z[2];
                    y = oy + z[3];
                    break;
                default:
                    break;
            }
        }
        return p;
    }
}
