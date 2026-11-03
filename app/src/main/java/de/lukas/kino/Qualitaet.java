package de.lukas.kino;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * „Schlechte Qualität melden“ (Idee von Jonas, 03.11.2026): kleiner Fahnen-Knopf ganz rechts in der Detailansicht.
 * Jeder kann jeden Film melden, nur Lukas sieht die Liste (Kachel „Schlechte Qualität“ ganz rechts in den Kategorien).
 * Quelle: Home Assistant (sensor.kino_qualitaet, Paket kino_qualitaet.yaml) über bewertungen.json → qualitaet:
 * { HA-ID: { t: "Titel (Jahr)", wer: [Profile], z: Zeit } }.
 * Schreiben: Webhook an Automation 84 (art=qualitaet, an=true/false). an=false von Lukas = erledigt (Meldung ganz weg),
 * von den anderen = nur die eigene Meldung zurücknehmen.
 */
final class Qualitaet {

    private static final long LOKAL_MS = 15 * 60_000L;                    // eigene Meldung gilt, bis HA sie bestätigt

    private static volatile List<String> ids = new ArrayList<>();            // Jellyfin-IDs, zuletzt gemeldet zuerst
    private static volatile Map<String, List<String>> wer = new HashMap<>(); // Jellyfin-ID -> Profile, die gemeldet haben
    private static final Map<String, Object[]> lokal = new HashMap<>();      // Jellyfin-ID -> {Boolean an, Long bis}
    private static volatile String sig = "";

    private Qualitaet() {
    }

    /** Die Liste sieht nur Lukas. */
    static boolean nurLukas() {
        return "Lukas".equals(Merkliste.WER);
    }

    /** Aus den HA-Daten übernehmen (nach Familie.laden()). Blockiert. Liefert true, wenn sich etwas geändert hat. */
    static boolean ausHa(Jellyfin jf) throws Exception {
        JSONObject roh = Familie.roh();
        if (roh == null) return false;
        FilmIndex.laden(jf, false);
        JSONObject alle = roh.optJSONObject("qualitaet");
        List<String[]> l = new ArrayList<>(); // {Jellyfin-ID, Zeit}
        Map<String, List<String>> w = new HashMap<>();
        Iterator<String> it = alle != null ? alle.keys() : null;
        while (it != null && it.hasNext()) {
            String id = it.next();
            JSONObject o = alle.optJSONObject(id);
            if (o == null) continue;
            String jfId = Merkliste.jfFuerHa(id);
            if (jfId == null || jfId.isEmpty()) jfId = FilmIndex.fuerTitel(o.optString("t"));
            if (jfId == null || jfId.isEmpty()) continue;
            List<String> leute = new ArrayList<>();
            JSONArray a = o.optJSONArray("wer");
            for (int i = 0; a != null && i < a.length(); i++) if (!leute.contains(a.optString(i))) leute.add(a.optString(i));
            if (leute.isEmpty()) continue;
            w.put(jfId, leute);
            l.add(new String[]{jfId, o.optString("z")});
        }
        Collections.sort(l, (x, y) -> y[1].compareTo(x[1]));
        List<String> neu = new ArrayList<>();
        for (String[] x : l) if (!neu.contains(x[0])) neu.add(x[0]);
        synchronized (lokal) {
            long jetzt = System.currentTimeMillis();
            Iterator<Map.Entry<String, Object[]>> li = lokal.entrySet().iterator();
            while (li.hasNext()) {
                Map.Entry<String, Object[]> e = li.next();
                boolean an = (Boolean) e.getValue()[0];
                if (istAn(w, e.getKey()) == an || (Long) e.getValue()[1] < jetzt) li.remove();
                else anwenden(neu, w, e.getKey(), an);
            }
        }
        return setzen(neu, w);
    }

    /** Lukas: irgendwer hat gemeldet. Die anderen: dieses Profil hat gemeldet. */
    private static boolean istAn(Map<String, List<String>> w, String id) {
        List<String> l = w.get(id);
        if (l == null || l.isEmpty()) return false;
        return nurLukas() || l.contains(Merkliste.WER);
    }

    private static void anwenden(List<String> neu, Map<String, List<String>> w, String id, boolean an) {
        if (an) {
            List<String> l = new ArrayList<>(w.containsKey(id) ? w.get(id) : new ArrayList<>());
            if (!l.contains(Merkliste.WER)) l.add(Merkliste.WER);
            w.put(id, l);
            if (!neu.contains(id)) neu.add(0, id);
            return;
        }
        List<String> l = w.containsKey(id) ? new ArrayList<>(w.get(id)) : new ArrayList<>();
        if (nurLukas()) l.clear();
        else l.remove(Merkliste.WER);
        if (l.isEmpty()) {
            w.remove(id);
            neu.remove(id);
        } else {
            w.put(id, l);
        }
    }

    private static boolean setzen(List<String> neu, Map<String, List<String>> w) {
        StringBuilder sb = new StringBuilder();
        for (String id : neu) sb.append(id).append('=').append(w.get(id)).append(',');
        String s = sb.toString();
        if (s.equals(sig)) return false;
        ids = neu;
        wer = w;
        sig = s;
        return true;
    }

    /** Ist der Knopf bei diesem Film „an“ (siehe istAn)? */
    static boolean an(JSONObject e) {
        return e != null && istAn(wer, e.optString("Id"));
    }

    /** Wer diesen Film gemeldet hat (Profile). */
    static List<String> werHat(JSONObject e) {
        List<String> l = e != null ? wer.get(e.optString("Id")) : null;
        return l != null ? new ArrayList<>(l) : new ArrayList<>();
    }

    static int anzahl() {
        return ids.size();
    }

    /** Jellyfin-IDs, zuletzt gemeldet zuerst. */
    static List<String> liste() {
        return new ArrayList<>(ids);
    }

    /** Knopf gedrückt: sofort umschalten (bis HA es bestätigt). Liefert den neuen Zustand. */
    static boolean umschalten(JSONObject film) {
        String id = film.optString("Id");
        boolean an = !istAn(wer, id);
        synchronized (lokal) {
            lokal.put(id, new Object[]{an, System.currentTimeMillis() + LOKAL_MS});
        }
        List<String> neu = new ArrayList<>(ids);
        Map<String, List<String>> w = new HashMap<>(wer);
        anwenden(neu, w, id, an);
        setzen(neu, w);
        return an;
    }

    /** Senden hat nicht geklappt: eigene Änderung verwerfen, beim nächsten Laden gilt wieder HA. */
    static void vergessen(String id) {
        synchronized (lokal) {
            lokal.remove(id);
        }
        sig = "";
    }

    /** An Home Assistant melden (Automation 84 → Ereignis kino_qualitaet). Blockiert – nur im Hintergrund. */
    static void senden(JSONObject film, boolean an) throws Exception {
        String titel = film.optString("Name") + (film.optInt("ProductionYear", 0) > 0 ? " (" + film.optInt("ProductionYear") + ")" : "");
        Merkliste.webhookSenden("art=qualitaet&wer=" + Jellyfin.enc(Merkliste.WER) + "&id=" + Jellyfin.enc(Merkliste.haIdFuer(film))
                + "&titel=" + Jellyfin.enc(titel) + "&an=" + an);
    }
}
