package de.lukas.kino;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Jellyfin-Filme nach Name und Jahr – damit Plex-Titel „Name (Jahr)“ aus Home Assistant (Merkliste, Bewertungen)
 * dem richtigen Jellyfin-Film zugeordnet werden. Jahr ±1 erlaubt (Plex und Jellyfin liegen manchmal ein Jahr auseinander),
 * sonst ein eindeutiger Name.
 */
final class FilmIndex {

    private static volatile Map<String, String> schluessel = new HashMap<>();
    private static volatile Map<String, String> name = new HashMap<>();   // "" = mehrdeutig
    private static volatile boolean fertig;

    private FilmIndex() {
    }

    /** Blockiert – nur im Hintergrund aufrufen. Lädt nur beim ersten Mal (oder mit neu = true). */
    static synchronized void laden(Jellyfin jf, boolean neu) throws Exception {
        if (fertig && !neu) return;
        JSONArray a = jf.filmIndex();
        Map<String, String> k = new HashMap<>(), n = new HashMap<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e == null) continue;
            String id = e.optString("Id");
            String jahr = e.optInt("ProductionYear", 0) > 0 ? String.valueOf(e.optInt("ProductionYear")) : "";
            String s = Familie.schluessel(e.optString("Name"), jahr);
            if (!k.containsKey(s)) k.put(s, id);
            String o = e.optString("OriginalTitle", "");
            if (!o.isEmpty()) {
                String so = Familie.schluessel(o, jahr);
                if (!k.containsKey(so)) k.put(so, id);
            }
            String nn = Suche.normal(e.optString("Name"));
            n.put(nn, n.containsKey(nn) ? "" : id);
        }
        schluessel = k;
        name = n;
        fertig = true;
    }

    static boolean fertig() {
        return fertig;
    }

    /** Jellyfin-ID zu „Name (Jahr)“ oder null. */
    static String fuerTitel(String plexTitel) {
        if (plexTitel == null || plexTitel.isEmpty()) return null;
        String[] t = Familie.plexTitel(plexTitel);
        Map<String, String> k = schluessel;
        String id = k.get(Familie.schluessel(t[0], t[1]));
        if (id == null && !t[1].isEmpty()) {
            try {
                int j = Integer.parseInt(t[1]);
                id = k.get(Familie.schluessel(t[0], String.valueOf(j - 1)));
                if (id == null) id = k.get(Familie.schluessel(t[0], String.valueOf(j + 1)));
            } catch (NumberFormatException ignored) {
                // kein Jahr
            }
        }
        if (id == null) {
            String n = name.get(Suche.normal(t[0]));
            if (n != null && !n.isEmpty()) id = n;
        }
        return id;
    }
}
