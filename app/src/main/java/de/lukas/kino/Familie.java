package de.lukas.kino;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bewertungen der Familie aus Home Assistant (sensor.plex_bewertungen) und wer welchen Film fertig geschaut hat
 * (sensor.plex_verlauf). HA schreibt beides alle 5 Minuten nach /share/kino/bewertungen.json (Paket kino_export.yaml),
 * die kleine App „Lukas Kino Web“ liefert die Datei auf Port 8098 aus.
 * Plex-Titel „Name (Jahr)“ werden über Name und Jahr den Jellyfin-Filmen zugeordnet.
 */
final class Familie {

    static final String QUELLE = "http://192.168.178.44:8098/bewertungen.json";
    private static final Pattern TITEL = Pattern.compile("^(.*)\\s\\((\\d{4})\\)\\s*$");

    static final Map<String, Integer> FARBEN = new HashMap<>();

    static {
        FARBEN.put("Lukas", 0xFFF2B705);
        FARBEN.put("Moni", 0xFFE8559A);
        FARBEN.put("Uli", 0xFF1FA463);
        FARBEN.put("Jonas", 0xFF2F6FEB);
        FARBEN.put("Eltern", 0xFFE8559A);
    }

    static final class Info {
        final List<String> namen = new ArrayList<>();     // nach Note sortiert
        final Map<String, Integer> noten = new HashMap<>();
        final List<String> gesehen = new ArrayList<>();
    }

    private static volatile Map<String, Map<String, Integer>> noten = new HashMap<>();
    private static volatile Map<String, List<String>> gesehen = new HashMap<>();
    private static volatile Map<String, String> nurName = new HashMap<>();

    private Familie() {
    }

    private static String schluessel(String name, String jahr) {
        return Suche.normal(name) + "|" + (jahr == null ? "" : jahr);
    }

    private static String[] plexTitel(String t) {
        Matcher m = TITEL.matcher(t == null ? "" : t);
        return m.matches() ? new String[]{m.group(1), m.group(2)} : new String[]{t == null ? "" : t, ""};
    }

    /** Blockiert – nur im Hintergrund aufrufen. */
    static void laden() throws Exception {
        JSONObject j = new JSONObject(Jellyfin.holen(QUELLE + "?t=" + System.currentTimeMillis()));
        Map<String, Map<String, Integer>> n = new HashMap<>();
        Map<String, List<String>> g = new HashMap<>();
        Map<String, String> nn = new HashMap<>();
        JSONObject b = j.optJSONObject("bewertungen");
        if (b != null) {
            Iterator<String> it = b.keys();
            while (it.hasNext()) {
                JSONObject f = b.optJSONObject(it.next());
                if (f == null) continue;
                String[] t = plexTitel(f.optString("t"));
                String k = schluessel(t[0], t[1]);
                Map<String, Integer> w = new HashMap<>();
                JSONObject wo = f.optJSONObject("w");
                if (wo != null) {
                    Iterator<String> wi = wo.keys();
                    while (wi.hasNext()) {
                        String name = wi.next();
                        w.put(name, wo.optInt(name));
                    }
                }
                n.put(k, w);
                String kurz = Suche.normal(t[0]);
                nn.put(kurz, nn.containsKey(kurz) ? "" : k); // "" = mehrdeutig
            }
        }
        JSONArray v = j.optJSONArray("verlauf");
        if (v != null) {
            for (int i = 0; i < v.length(); i++) {
                JSONObject e = v.optJSONObject(i);
                if (e == null) continue;
                String typ = e.optString("typ", "movie");
                if (!typ.isEmpty() && !"movie".equals(typ)) continue;
                String[] t = plexTitel(e.optString("titel"));
                String k = schluessel(t[0], t[1]);
                List<String> l = g.get(k);
                if (l == null) {
                    l = new ArrayList<>();
                    g.put(k, l);
                }
                String wer = e.optString("wer");
                if (!wer.isEmpty() && !l.contains(wer)) l.add(wer);
                String kurz = Suche.normal(t[0]);
                if (!nn.containsKey(kurz)) nn.put(kurz, k);
            }
        }
        noten = n;
        gesehen = g;
        nurName = nn;
    }

    /** Bewertungen und „gesehen von“ zu einem Film, sonst null. */
    static Info fuer(JSONObject film) {
        if (film == null || !"Movie".equals(film.optString("Type"))) return null;
        String jahr = film.optInt("ProductionYear", 0) > 0 ? String.valueOf(film.optInt("ProductionYear")) : "";
        String k = schluessel(film.optString("Name"), jahr);
        Map<String, Map<String, Integer>> n = noten;
        Map<String, List<String>> g = gesehen;
        if (!n.containsKey(k) && !g.containsKey(k)) {
            String alt = nurName.get(Suche.normal(film.optString("Name")));
            if (alt != null && !alt.isEmpty()) k = alt;
            else if (!film.optString("OriginalTitle", "").isEmpty()) k = schluessel(film.optString("OriginalTitle"), jahr);
        }
        Map<String, Integer> w = n.get(k);
        List<String> s = g.get(k);
        if ((w == null || w.isEmpty()) && (s == null || s.isEmpty())) return null;
        Info i = new Info();
        if (w != null) {
            i.noten.putAll(w);
            i.namen.addAll(w.keySet());
            Collections.sort(i.namen, (a, bb) -> Integer.compare(w.get(bb), w.get(a)));
        }
        if (s != null) i.gesehen.addAll(s);
        return i;
    }
}
