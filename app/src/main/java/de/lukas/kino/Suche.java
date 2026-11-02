package de.lukas.kino;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Unscharfe Suche wie bei Netflix: findet Titel auch mit Tippfehlern, fehlenden Buchstaben oder Umlauten
 * („matchbocks“ → Matchbox, „spiderman“ → Spider-Man, „fuer“ → für).
 * Reines Java ohne Android, damit es sich auch am PC testen lässt.
 */
final class Suche {

    static final class Treffer<T> implements Comparable<Treffer<T>> {
        final T wert;
        final int punkte;
        final String titel;

        Treffer(T wert, int punkte, String titel) {
            this.wert = wert;
            this.punkte = punkte;
            this.titel = titel;
        }

        @Override
        public int compareTo(Treffer<T> o) {
            if (punkte != o.punkte) return Integer.compare(o.punkte, punkte);
            return titel.compareToIgnoreCase(o.titel);
        }
    }

    private Suche() {
    }

    /** Kleinschreibung, Umlaute ausgeschrieben, Akzente weg, Satzzeichen weg, Leerzeichen zusammengefasst. */
    static String normal(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.GERMAN)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replace("&", " und ");
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        t = t.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return t;
    }

    /** Ohne Leerzeichen („spider man“ = „spiderman“). */
    static String kompakt(String s) {
        return s.replace(" ", "");
    }

    /** Wie viele Fehler bei dieser Suchlänge erlaubt sind. */
    static int erlaubt(int laenge) {
        if (laenge <= 3) return 0;
        if (laenge <= 5) return 1;
        if (laenge <= 8) return 2;
        return 3;
    }

    /**
     * Punkte für einen Titel (0 = kein Treffer). Höher = besser.
     * Reihenfolge: Titel beginnt so > ein Wort beginnt so > kommt vor > ähnlich (mit Fehlern).
     */
    static int punkte(String anfrage, String... titel) {
        String q = normal(anfrage);
        if (q.isEmpty()) return 0;
        String qk = kompakt(q);
        int best = 0;
        for (String roh : titel) {
            if (roh == null || roh.isEmpty()) continue;
            String t = normal(roh);
            String tk = kompakt(t);
            int p = 0;
            if (t.startsWith(q) || tk.startsWith(qk)) p = 1000 - Math.min(200, t.length() - q.length());
            else if (wortAnfang(t, q)) p = 900 - Math.min(200, t.length() - q.length());
            else if (t.contains(q) || tk.contains(qk)) p = 800 - Math.min(200, t.length() - q.length());
            else {
                int fehler = Math.min(abstandIrgendwo(qk, tk), wortAbstand(q, t));
                int grenze = erlaubt(qk.length());
                if (fehler <= grenze) p = 600 - fehler * 120 - Math.min(100, Math.abs(tk.length() - qk.length()));
            }
            best = Math.max(best, p);
        }
        return best;
    }

    private static boolean wortAnfang(String t, String q) {
        for (String w : t.split(" ")) if (w.startsWith(q)) return true;
        // mehrere Wörter der Anfrage: jedes muss ein Wort im Titel beginnen
        String[] qs = q.split(" ");
        if (qs.length < 2) return false;
        String[] ws = t.split(" ");
        for (String qw : qs) {
            boolean gef = false;
            for (String w : ws) if (w.startsWith(qw)) {
                gef = true;
                break;
            }
            if (!gef) return false;
        }
        return true;
    }

    /** Fehler, wenn die Anfrage gegen jedes einzelne Wort (oder Wortpaar) des Titels gehalten wird. */
    private static int wortAbstand(String q, String t) {
        String[] ws = t.split(" ");
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < ws.length; i++) {
            best = Math.min(best, damerau(q.replace(" ", ""), ws[i]));
            if (i + 1 < ws.length) best = Math.min(best, damerau(q.replace(" ", ""), ws[i] + ws[i + 1]));
        }
        return best;
    }

    /**
     * Kleinste Zahl an Änderungen, damit die Anfrage irgendwo im Titel vorkommt (Anfang/Ende im Titel frei).
     * Vertauschte Nachbarbuchstaben zählen als ein Fehler.
     */
    static int abstandIrgendwo(String q, String t) {
        int m = q.length(), n = t.length();
        if (m == 0) return 0;
        if (n == 0) return m;
        int[][] d = new int[m + 1][n + 1];
        for (int i = 0; i <= m; i++) d[i][0] = i;
        for (int j = 0; j <= n; j++) d[0][j] = 0;
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                int kosten = q.charAt(i - 1) == t.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + kosten);
                if (i > 1 && j > 1 && q.charAt(i - 1) == t.charAt(j - 2) && q.charAt(i - 2) == t.charAt(j - 1))
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                d[i][j] = v;
            }
        }
        int best = Integer.MAX_VALUE;
        for (int j = 0; j <= n; j++) best = Math.min(best, d[m][j]);
        return best;
    }

    /** Normaler Abstand zweier Wörter (mit Vertauschung). */
    static int damerau(String a, String b) {
        int m = a.length(), n = b.length();
        int[][] d = new int[m + 1][n + 1];
        for (int i = 0; i <= m; i++) d[i][0] = i;
        for (int j = 0; j <= n; j++) d[0][j] = j;
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                int kosten = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + kosten);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                d[i][j] = v;
            }
        }
        return d[m][n];
    }

    interface Titel<T> {
        String[] titel(T wert);
    }

    static <T> List<T> suchen(String anfrage, List<T> alle, Titel<T> titel, int max) {
        List<Treffer<T>> l = new ArrayList<>();
        for (T w : alle) {
            String[] t = titel.titel(w);
            int p = punkte(anfrage, t);
            if (p > 0) l.add(new Treffer<>(w, p, t.length > 0 && t[0] != null ? t[0] : ""));
        }
        Collections.sort(l);
        List<T> r = new ArrayList<>();
        for (int i = 0; i < Math.min(max, l.size()); i++) r.add(l.get(i).wert);
        return r;
    }
}
