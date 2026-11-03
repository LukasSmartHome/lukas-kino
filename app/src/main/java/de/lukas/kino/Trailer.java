package de.lukas.kino;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trailer zu einem Film: zuerst die geprüfte Liste vom Pi (trailer.json), sonst KinoCheck auf Deutsch (per TMDb-ID, ohne Schlüssel, 1000 Abfragen am Tag),
 * sonst die Trailer-Links, die Jellyfin von TMDb hat. Ergebnisse bleiben am Gerät gemerkt
 * (gefundene für immer, „keiner gefunden“ 3 Tage).
 */
final class Trailer {

    private static final Pattern YT = Pattern.compile("(?:v=|youtu\\.be/|embed/|shorts/)([A-Za-z0-9_-]{11})");
    private static final long DREI_TAGE = 3L * 24 * 3600 * 1000;

    private Trailer() {
    }

    static String ytId(String url) {
        Matcher m = YT.matcher(url == null ? "" : url);
        return m.find() ? m.group(1) : null;
    }

    private static boolean deutsch(String name) {
        String n = name == null ? "" : name.toLowerCase();
        return n.contains("german") || n.contains("deutsch") || n.contains("offiziell");
    }

    // Liste vom Pi (werkzeuge/kino_pflege.py trailer → /share/kino/trailer.json, über „Lukas Kino Web“ auf Port 8098):
    // dort ist jeder Film schon geprüft (gibt es den Trailer, darf man ihn einbetten, deutsch zuerst, ohne Untertitel).
    private static JSONObject liste;
    private static long listeZeit;

    private static synchronized JSONObject liste() {
        if (liste == null || System.currentTimeMillis() - listeZeit > 10 * 60 * 1000L) {
            try {
                JSONObject j = new JSONObject(Jellyfin.holen(Familie.BASIS + "trailer.json?t=" + System.currentTimeMillis()));
                JSONObject f = j.optJSONObject("filme");
                if (f != null) {
                    liste = f;
                    listeZeit = System.currentTimeMillis();
                }
            } catch (Exception ignored) {
                // Pi nicht erreichbar – dann wie bisher selbst suchen
            }
        }
        return liste;
    }

    /** {youtubeId, titel} oder null. Blockiert – nur im Hintergrund aufrufen. */
    static String[] suchen(Context c, JSONObject film) {
        JSONObject l = liste();
        JSONObject eintrag = l != null ? l.optJSONObject(film.optString("Id")) : null;
        if (eintrag != null) {
            String yt = eintrag.optString("yt", "");
            return yt.matches("[A-Za-z0-9_-]{11}") ? new String[]{yt, eintrag.optString("t", "")} : null;
        }
        SharedPreferences sp = c.getSharedPreferences("lukaskino_trailer", Context.MODE_PRIVATE);
        JSONObject ids = film.optJSONObject("ProviderIds");
        String tmdb = ids != null ? ids.optString("Tmdb", ids.optString("tmdb", "")) : "";
        String key = !tmdb.isEmpty() ? "m" + tmdb : "j" + film.optString("Id");
        try {
            String gemerkt = sp.getString(key, null);
            if (gemerkt != null) {
                JSONObject g = new JSONObject(gemerkt);
                String yt = g.optString("yt", "");
                if (!yt.isEmpty()) return new String[]{yt, g.optString("t", "")};
                if (System.currentTimeMillis() - g.optLong("z", 0) < DREI_TAGE) return null;
            }
        } catch (Exception ignored) {
        }
        String[] tr = null;
        if (!tmdb.isEmpty()) {
            try {
                JSONObject j = new JSONObject(Jellyfin.holen("https://api.kinocheck.de/movies?tmdb_id=" + Jellyfin.enc(tmdb)
                        + "&language=de&categories=Trailer"));
                JSONObject v = j.optJSONObject("trailer");
                if (v == null || v.optString("youtube_video_id", "").isEmpty()) {
                    v = null;
                    JSONArray vs = j.optJSONArray("videos");
                    for (int i = 0; vs != null && i < vs.length() && v == null; i++) {
                        JSONObject x = vs.optJSONObject(i);
                        if (x == null || x.optString("youtube_video_id", "").isEmpty() || !"de".equals(x.optString("language"))) continue;
                        JSONArray kat = x.optJSONArray("categories");
                        for (int k = 0; kat != null && k < kat.length(); k++) if ("Trailer".equals(kat.optString(k))) v = x;
                    }
                }
                if (v != null) tr = new String[]{v.optString("youtube_video_id"), v.optString("title", "")};
            } catch (Exception ignored) {
                // KinoCheck nicht erreichbar – dann Jellyfin
            }
        }
        if (tr == null) {
            JSONArray rt = film.optJSONArray("RemoteTrailers");
            String[] best = null;
            for (int i = 0; rt != null && i < rt.length(); i++) {
                JSONObject t = rt.optJSONObject(i);
                String yt = t != null ? ytId(t.optString("Url")) : null;
                if (yt == null) continue;
                String name = t.optString("Name", "");
                if (best == null || (deutsch(name) && !deutsch(best[1]))) best = new String[]{yt, name};
            }
            tr = best;
        }
        try {
            JSONObject g = new JSONObject().put("z", System.currentTimeMillis())
                    .put("yt", tr != null ? tr[0] : "").put("t", tr != null ? tr[1] : "");
            sp.edit().putString(key, g.toString()).apply();
        } catch (Exception ignored) {
        }
        return tr;
    }
}
