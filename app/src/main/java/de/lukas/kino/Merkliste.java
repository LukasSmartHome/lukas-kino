package de.lukas.kino;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Merkliste dieses Geräts. Gespeichert in Jellyfin (DisplayPreferences des Benutzers, je Gerät eine eigene Liste):
 * übersteht App-Updates, und die LG-Fernseher haben jeweils ihre eigene Liste.
 */
final class Merkliste {

    static final String DP_ID = "lukaskino-merkliste";
    static final String CLIENT = "lukaskino-tcl";

    private final List<String> ids = new ArrayList<>();
    private final Map<String, JSONObject> items = new HashMap<>();
    private JSONObject dto;

    /** Blockiert – nur im Hintergrund aufrufen. */
    void laden(Jellyfin jf) throws Exception {
        JSONObject d = jf.einstellungen(DP_ID, CLIENT);
        JSONObject prefs = d.optJSONObject("CustomPrefs");
        String roh = prefs != null ? prefs.optString("merkliste", "") : "";
        List<String> neu = new ArrayList<>();
        for (String s : roh.split(",")) if (!s.trim().isEmpty()) neu.add(s.trim());
        Map<String, JSONObject> m = new HashMap<>();
        if (!neu.isEmpty()) {
            JSONArray a = jf.nachIds(neu);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) m.put(o.optString("Id"), o);
            }
        }
        synchronized (this) {
            dto = d;
            ids.clear();
            for (String id : neu) if (m.containsKey(id)) ids.add(id);
            items.clear();
            items.putAll(m);
        }
    }

    synchronized boolean hat(String id) {
        return ids.contains(id);
    }

    synchronized List<JSONObject> liste() {
        List<JSONObject> l = new ArrayList<>();
        for (String id : ids) {
            JSONObject o = items.get(id);
            if (o != null) l.add(o);
        }
        return l;
    }

    /** Hinzufügen bzw. entfernen. Liefert true, wenn der Film jetzt auf der Liste steht. */
    synchronized boolean umschalten(JSONObject film) {
        String id = film.optString("Id");
        if (ids.remove(id)) return false;
        ids.add(0, id);
        items.put(id, film);
        return true;
    }

    /** Neueste Fassung eines Films übernehmen (z. B. nach dem Anschauen). */
    synchronized void ersetzen(JSONObject film) {
        String id = film.optString("Id");
        if (items.containsKey(id)) items.put(id, film);
    }

    /** Blockiert – nur im Hintergrund aufrufen. */
    void speichern(Jellyfin jf) throws Exception {
        JSONObject d;
        synchronized (this) {
            d = dto != null ? new JSONObject(dto.toString()) : new JSONObject()
                    .put("Id", DP_ID).put("SortBy", "SortName").put("SortOrder", "Ascending")
                    .put("RememberIndexing", false).put("RememberSorting", false).put("ScrollDirection", "Horizontal")
                    .put("ShowBackdrop", true).put("ShowSidebar", false).put("PrimaryImageHeight", 250).put("PrimaryImageWidth", 250);
            JSONObject prefs = d.optJSONObject("CustomPrefs");
            if (prefs == null) prefs = new JSONObject();
            prefs.put("merkliste", String.join(",", ids));
            d.put("CustomPrefs", prefs);
            d.put("Client", CLIENT);
            dto = d;
        }
        jf.einstellungenSpeichern(DP_ID, CLIENT, d);
    }
}
