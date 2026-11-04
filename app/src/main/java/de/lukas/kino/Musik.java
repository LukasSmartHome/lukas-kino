package de.lukas.kino;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Pattern;

/**
 * Musik im Kino (04.11.2026, Wunsch Lukas): Genres in wenige Gruppen, Karaoke-Verzeichnis vom Pi, Cover.
 * Gleiche Gruppen wie musik.js (LG-Web und Player) – Änderungen immer in beiden Dateien.
 */
final class Musik {

    /** Kleiner Webserver auf dem Pi (App „Lukas Kino Web“, Ordner /share/kino): Player-Seite und Karaoke-Texte. */
    static final String WEB = "http://192.168.178.44:8098";

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    static final Object[][] GRUPPEN = {
            {"Weihnachten", Pattern.compile("weihnacht|christmas|holiday|xmas", F), 0xFFB3262E},
            {"Film und Soundtrack", Pattern.compile("soundtrack|filmmusik|score|musical", F), 0xFF1592C4},
            {"Deutsch und Schlager", Pattern.compile("schlager|volksmusik|deutschpop|deutsch|liedermacher|neue deutsche", F), 0xFF2E8B3F},
            {"Hip-Hop und R&B", Pattern.compile("hip-hop|hip hop|rap|r&b|rnb|soul|urban", F), 0xFFF26B3A},
            {"Latin und Reggae", Pattern.compile("latin|reggae|spanisch|reggaeton|salsa|dancehall", F), 0xFFE9A23B},
            {"Dance und Elektro", Pattern.compile("dance|electronic|elektro|house|techno|edm|trance|disco", F), 0xFF6A3DE8},
            {"Country und Folk", Pattern.compile("country|folk|bluegrass", F), 0xFF9C7442},
            {"Klassik und Chill", Pattern.compile("classical|clasic|klassik|new age|ambient|chill|lounge|jazz", F), 0xFF2F6E73},
            {"Rock", Pattern.compile("rock|alternative|indie|metal|punk|blues|grunge", F), 0xFFB83A3A},
            {"Pop", Pattern.compile("pop|top 40|top 100|singer|songwriter|chanson|vari[eé]t[eé]|charts", F), 0xFFE8559A},
    };
    static final String SONSTIGES = "Sonstiges";

    private Musik() {
    }

    static String gruppe(JSONObject e) {
        JSONArray g = e.optJSONArray("Genres");
        if (g == null || g.length() == 0) return SONSTIGES;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.length(); i++) sb.append(g.optString(i)).append(" | ");
        String s = sb.toString();
        for (Object[] gr : GRUPPEN) if (((Pattern) gr[1]).matcher(s).find()) return (String) gr[0];
        return SONSTIGES;
    }

    static int farbe(String gruppe) {
        for (Object[] gr : GRUPPEN) if (gr[0].equals(gruppe)) return (Integer) gr[2];
        return 0xFF3E4A5E;
    }

    static String interpret(JSONObject e) {
        JSONArray a = e.optJSONArray("Artists");
        if (a != null && a.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length(); i++) sb.append(i > 0 ? ", " : "").append(a.optString(i));
            return sb.toString();
        }
        return e.optString("AlbumArtist", "");
    }

    static String dateiname(JSONObject e) {
        String p = e.optString("Path", "");
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    /** Cover: eigenes Bild des Liedes oder das des Albums. */
    static String cover(JSONObject e, int breite) {
        String pr = Jellyfin.tag(e, "Primary");
        if (pr != null) return Jellyfin.quer(e.optString("Id"), pr, breite);
        String al = e.optString("AlbumId", null), at = e.optString("AlbumPrimaryImageTag", null);
        if (al != null && at != null && !al.isEmpty()) return Jellyfin.quer(al, at, breite);
        return null;
    }

    /** /karaoke/index.json vom Pi: Dateiname -> {status, lrc, wort}. Leer, wenn nicht erreichbar. */
    static JSONObject karaokeIndex() {
        try {
            HttpURLConnection k = (HttpURLConnection) new URL(WEB + "/karaoke/index.json?t=" + System.currentTimeMillis()).openConnection();
            k.setConnectTimeout(4000);
            k.setReadTimeout(8000);
            if (k.getResponseCode() != 200) return new JSONObject();
            try (InputStream in = k.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] puffer = new byte[16384];
                int n;
                while ((n = in.read(puffer)) > 0) b.write(puffer, 0, n);
                return new JSONObject(b.toString("UTF-8"));
            }
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static boolean hatKaraoke(JSONObject index, JSONObject e) {
        JSONObject k = index.optJSONObject(dateiname(e));
        return k != null && (k.has("lrc") || k.has("wort"));
    }
}
