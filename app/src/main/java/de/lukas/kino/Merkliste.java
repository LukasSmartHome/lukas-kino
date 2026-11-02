package de.lukas.kino;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Merkliste = dieselbe wie im Home-Assistant-Profil (sensor.plex_merkliste). Am TCL ist das Lukas' Liste.
 * Lesen: über bewertungen.json (HA-Paket kino_export.yaml, kommt mit Familie.laden()).
 * Schreiben: Webhook an Automation 84 – die Adresse steht nur auf dem Pi (kino_geheim.json), nicht im Code.
 * Plex-IDs werden über Titel und Jahr zugeordnet (plexfilme.json), Filme nur in Jellyfin heißen in HA „jf:&lt;ID&gt;“.
 */
final class Merkliste {

    static final String WER = "Lukas";                 // HA-Profil dieses Geräts
    static final String[] LISTEN = {"Lukas"};
    private static final String HA = "http://192.168.178.44:8123";
    private static final long LOKAL_MS = 120_000;      // eigene Änderung gilt, bis HA sie bestätigt

    private final List<String> ids = new ArrayList<>();             // Jellyfin-IDs, zuletzt gemerkt zuerst
    private final Map<String, JSONObject> items = new HashMap<>();
    private final Map<String, List<String>> haIds = new HashMap<>(); // Jellyfin-ID -> IDs in HA
    private final Map<String, long[]> lokal = new HashMap<>();       // Jellyfin-ID -> {an (1/0), bis}
    // Katalog (plexfilme.json, seit 02.11.2026 aus Jellyfin: { filme: { HA-ID: {t, j, jf} } }) und Webhook – für alle gleich
    private static Map<String, String> plex;                         // Name|Jahr -> HA-ID (alte Plex-ID oder jf:<ID>)
    private static Map<String, String> jfZuHa = new HashMap<>();     // Jellyfin-ID -> HA-ID
    private static Map<String, String> haZuJf = new HashMap<>();     // HA-ID -> Jellyfin-ID (für „Gesehen“)
    private static long plexZeit;
    private static String webhook;

    /** HA-Daten übernehmen (nach Familie.laden()). Blockiert. Liefert true, wenn sich die Liste geändert hat. */
    boolean ausHa(Jellyfin jf) throws Exception {
        JSONObject j = Familie.roh();
        if (j == null) return false;
        FilmIndex.laden(jf, false);
        JSONObject listen = j.optJSONObject("merkliste"), titel = j.optJSONObject("merktitel"), bew = j.optJSONObject("bewertungen");
        List<String> reihenfolge = new ArrayList<>();
        Map<String, List<String>> ha = new HashMap<>();
        for (String w : LISTEN) {
            JSONArray a = listen != null ? listen.optJSONArray(w) : null;
            for (int i = 0; a != null && i < a.length(); i++) {
                String id = a.optString(i);
                String jfId;
                if (id.startsWith("jf:")) jfId = id.substring(3);
                else {
                    jfId = FilmIndex.fuerTitel(titel != null ? titel.optString(id, null) : null);
                    JSONObject b = bew != null ? bew.optJSONObject(id) : null;
                    if (jfId == null && b != null) jfId = FilmIndex.fuerTitel(b.optString("t"));
                }
                if (jfId == null || jfId.isEmpty()) continue;
                if (!reihenfolge.contains(jfId)) reihenfolge.add(jfId);
                List<String> l = ha.get(jfId);
                if (l == null) ha.put(jfId, l = new ArrayList<>());
                if (!l.contains(id)) l.add(id);
            }
        }
        List<String> fehlen = new ArrayList<>();
        synchronized (this) {
            long jetzt = System.currentTimeMillis();
            Iterator<Map.Entry<String, long[]>> it = lokal.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, long[]> e = it.next();
                boolean an = e.getValue()[0] == 1, drin = reihenfolge.contains(e.getKey());
                if (e.getValue()[1] < jetzt || an == drin) {
                    it.remove();
                    continue;
                }
                if (an) reihenfolge.add(0, e.getKey());
                else reihenfolge.remove(e.getKey());
            }
            for (String id : reihenfolge) if (!items.containsKey(id)) fehlen.add(id);
        }
        Map<String, JSONObject> neu = new HashMap<>();
        for (int i = 0; i < fehlen.size(); i += 60) {
            try {
                JSONArray a = jf.nachIds(fehlen.subList(i, Math.min(fehlen.size(), i + 60)));
                for (int k = 0; k < a.length(); k++) {
                    JSONObject o = a.optJSONObject(k);
                    if (o != null) neu.put(o.optString("Id"), o);
                }
            } catch (Exception ignored) {
                // dann fehlen sie eben
            }
        }
        synchronized (this) {
            items.putAll(neu);
            List<String> l = new ArrayList<>();
            for (String id : reihenfolge) if (items.containsKey(id)) l.add(id);
            haIds.clear();
            haIds.putAll(ha);
            if (l.equals(ids)) return false;
            ids.clear();
            ids.addAll(l);
            return true;
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

    /** Sofort umschalten (Anzeige). Liefert true, wenn der Film jetzt auf der Liste steht. Danach senden() im Hintergrund. */
    synchronized boolean umschalten(JSONObject film) {
        String id = film.optString("Id");
        boolean an = !ids.remove(id);
        if (an) {
            ids.add(0, id);
            items.put(id, film);
        }
        lokal.put(id, new long[]{an ? 1 : 0, System.currentTimeMillis() + LOKAL_MS});
        return an;
    }

    /** Senden fehlgeschlagen: eigene Änderung vergessen, beim nächsten Laden gilt wieder HA. */
    synchronized void vergessen(String id) {
        lokal.remove(id);
    }

    /** Neueste Fassung eines Films übernehmen (z. B. nach dem Anschauen). */
    synchronized void ersetzen(JSONObject film) {
        String id = film.optString("Id");
        if (items.containsKey(id)) items.put(id, film);
    }

    /** Änderung an Home Assistant schicken. Blockiert – nur im Hintergrund aufrufen. */
    void senden(JSONObject film, boolean an) throws Exception {
        plexLaden();
        String jfId = film.optString("Id");
        String titel = film.optString("Name") + (film.optInt("ProductionYear", 0) > 0 ? " (" + film.optInt("ProductionYear") + ")" : "");
        String plexId = plexIdFuer(film);
        List<String> ziele = new ArrayList<>();
        if (an) ziele.add(plexId != null ? plexId : "jf:" + jfId);
        else {
            synchronized (this) {
                List<String> h = haIds.get(jfId);
                if (h != null) ziele.addAll(h);
            }
            if (plexId != null && !ziele.contains(plexId)) ziele.add(plexId);
            if (!ziele.contains("jf:" + jfId)) ziele.add("jf:" + jfId);
        }
        for (String z : ziele) {
            webhookSenden("wer=" + Jellyfin.enc(WER) + "&id=" + Jellyfin.enc(z) + "&titel=" + Jellyfin.enc(titel) + "&an=" + an);
        }
    }

    /** Formular an den Webhook von Automation 84 schicken (Merkliste, Wiedergabe). Blockiert. */
    static void webhookSenden(String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(webhook()).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        c.disconnect();
        if (code >= 400) throw new Exception("Webhook " + code);
    }

    /** ID dieses Films in Home Assistant (wie im Dashboard): alte Plex-ID, sonst jf:<Jellyfin-ID>. Blockiert. */
    static String haIdFuer(JSONObject film) {
        plexLaden();
        String id = plexIdFuer(film);
        return id != null ? id : "jf:" + film.optString("Id");
    }

    /** Jellyfin-ID zu einer HA-ID (alte Plex-ID oder jf:<ID>) oder null. Blockiert. */
    static String jfFuerHa(String haId) {
        if (haId == null) return null;
        if (haId.startsWith("jf:")) return haId.substring(3);
        plexLaden();
        synchronized (Merkliste.class) {
            return haZuJf.get(haId);
        }
    }

    private static synchronized String webhook() throws Exception {
        if (webhook != null) return webhook;
        JSONObject j = new JSONObject(Jellyfin.holen(Familie.BASIS + "kino_geheim.json?t=" + System.currentTimeMillis()));
        String id = j.optString("merkWebhook", "");
        if (id.isEmpty()) throw new Exception("kein Webhook");
        webhook = HA + "/api/webhook/" + Jellyfin.enc(id);
        return webhook;
    }

    private static void plexLaden() {
        synchronized (Merkliste.class) {
            if (plex != null && System.currentTimeMillis() - plexZeit < 1_800_000) return;
        }
        Map<String, String> p = new HashMap<>(), zuHa = new HashMap<>(), zuJf = new HashMap<>();
        try {
            JSONObject f = new JSONObject(Jellyfin.holen(Familie.BASIS + "plexfilme.json?t=" + System.currentTimeMillis())).optJSONObject("filme");
            Iterator<String> it = f != null ? f.keys() : null;
            while (it != null && it.hasNext()) {
                String id = it.next();
                JSONObject o = f.optJSONObject(id);
                if (o != null && !o.optString("t").isEmpty())
                    p.put(Familie.schluessel(o.optString("t"), o.has("j") ? String.valueOf(o.optInt("j")) : ""), id);
                if (o != null && !o.optString("jf").isEmpty()) {
                    zuHa.put(o.optString("jf"), id);
                    zuJf.put(id, o.optString("jf"));
                }
            }
        } catch (Exception ignored) {
            // ohne Katalog: dann jf:<ID>
        }
        JSONObject roh = Familie.roh();
        JSONObject[] quellen = {roh != null ? roh.optJSONObject("bewertungen") : null, roh != null ? roh.optJSONObject("merktitel") : null};
        for (int q = 0; q < 2; q++) {
            JSONObject o = quellen[q];
            Iterator<String> it = o != null ? o.keys() : null;
            while (it != null && it.hasNext()) {
                String id = it.next();
                if (id.startsWith("jf:")) continue;
                String t = q == 0 ? (o.optJSONObject(id) != null ? o.optJSONObject(id).optString("t") : "") : o.optString(id);
                String[] pt = Familie.plexTitel(t);
                String k = Familie.schluessel(pt[0], pt[1]);
                if (!p.containsKey(k)) p.put(k, id);
            }
        }
        synchronized (Merkliste.class) {
            plex = p;
            jfZuHa = zuHa;
            haZuJf = zuJf;
            plexZeit = System.currentTimeMillis();
        }
    }

    private static synchronized String plexIdFuer(JSONObject e) {
        String direkt = jfZuHa.get(e.optString("Id"));
        if (direkt != null) return direkt;
        if (plex == null) return null;
        int j = e.optInt("ProductionYear", 0);
        String[] namen = {e.optString("Name"), e.optString("OriginalTitle", "")};
        for (String n : namen) {
            if (n.isEmpty()) continue;
            String id = plex.get(Familie.schluessel(n, j > 0 ? String.valueOf(j) : ""));
            if (id == null && j > 0) id = plex.get(Familie.schluessel(n, String.valueOf(j - 1)));
            if (id == null && j > 0) id = plex.get(Familie.schluessel(n, String.valueOf(j + 1)));
            if (id != null) return id;
        }
        return null;
    }
}
