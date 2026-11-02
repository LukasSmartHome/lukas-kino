package de.lukas.kino;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Was dieses Profil schon gesehen hat (ab 80 % der Laufzeit), getrennt nach Profil: TCL = Lukas, LG = Eltern bzw. Jonas.
 * Quelle: Home Assistant (sensor.kino_gesehen, Paket kino_gesehen.yaml) über bewertungen.json → gesehen → Profil.
 * Gesehene Filme verschwinden aus Neu, den Kategorien, Alle Filme und Empfohlen und stehen in der Kategorie
 * „Gesehen“ ganz rechts. Weiterschauen, Merkliste und Suche zeigen weiter alles.
 */
final class Gesehen {

    static final float GRENZE = 0.8f;                       // ab 80 % gilt ein Film als gesehen
    private static final long LOKAL_MS = 15 * 60_000L;      // eigene Meldung gilt, bis HA sie bestätigt

    private static volatile List<String> ids = new ArrayList<>();   // Jellyfin-IDs, zuletzt gesehen zuerst
    private static volatile Set<String> menge = new HashSet<>();
    private static final Map<String, Long> lokal = new HashMap<>(); // gerade eben gesehen (Jellyfin-ID -> gilt bis)
    private static volatile String sig = "";

    private Gesehen() {
    }

    /** Aus den HA-Daten übernehmen (nach Familie.laden()). Blockiert. Liefert true, wenn sich etwas geändert hat. */
    static boolean ausHa(Jellyfin jf) throws Exception {
        JSONObject roh = Familie.roh();
        if (roh == null) return false;
        FilmIndex.laden(jf, false);
        JSONObject alle = roh.optJSONObject("gesehen");
        JSONObject mein = alle != null ? alle.optJSONObject(Merkliste.WER) : null;
        List<String[]> l = new ArrayList<>(); // {Jellyfin-ID, Zeit}
        Iterator<String> it = mein != null ? mein.keys() : null;
        while (it != null && it.hasNext()) {
            String id = it.next();
            JSONObject o = mein.optJSONObject(id);
            String jfId = Merkliste.jfFuerHa(id);
            if ((jfId == null || jfId.isEmpty()) && o != null) jfId = FilmIndex.fuerTitel(o.optString("t"));
            if (jfId == null || jfId.isEmpty()) continue;
            l.add(new String[]{jfId, o != null ? o.optString("z") : ""});
        }
        Collections.sort(l, (a, b) -> b[1].compareTo(a[1]));
        List<String> neu = new ArrayList<>();
        for (String[] x : l) if (!neu.contains(x[0])) neu.add(x[0]);
        synchronized (lokal) {
            long jetzt = System.currentTimeMillis();
            Iterator<Map.Entry<String, Long>> li = lokal.entrySet().iterator();
            while (li.hasNext()) {
                Map.Entry<String, Long> e = li.next();
                if (neu.contains(e.getKey()) || e.getValue() < jetzt) li.remove();
                else neu.add(0, e.getKey());
            }
        }
        String s = String.join(",", neu);
        if (s.equals(sig)) return false;
        ids = neu;
        menge = new HashSet<>(neu);
        sig = s;
        return true;
    }

    /** Hat dieses Profil den Film gesehen? Nur für Filme (Folgen und Fotos nie). */
    static boolean hat(JSONObject e) {
        if (e == null || !"Movie".equals(e.optString("Type"))) return false;
        return menge.contains(e.optString("Id"));
    }

    static int anzahl() {
        return ids.size();
    }

    /** Jellyfin-IDs, zuletzt gesehen zuerst. */
    static List<String> liste() {
        return new ArrayList<>(ids);
    }

    /** Gerade eben ab 80 % geschaut: sofort als gesehen führen (bis HA es bestätigt). */
    static void lokalDazu(JSONObject film) {
        String id = film.optString("Id");
        synchronized (lokal) {
            lokal.put(id, System.currentTimeMillis() + LOKAL_MS);
        }
        if (menge.contains(id)) return;
        List<String> l = new ArrayList<>(ids);
        l.add(0, id);
        ids = l;
        menge = new HashSet<>(l);
        sig = String.join(",", l);
    }

    /** An Home Assistant melden (Automation 84 → Ereignis kino_gesehen). Blockiert – nur im Hintergrund. */
    static void senden(JSONObject film, boolean an) throws Exception {
        String titel = film.optString("Name") + (film.optInt("ProductionYear", 0) > 0 ? " (" + film.optInt("ProductionYear") + ")" : "");
        Merkliste.webhookSenden("art=gesehen&wer=" + Jellyfin.enc(Merkliste.WER) + "&id=" + Jellyfin.enc(Merkliste.haIdFuer(film))
                + "&titel=" + Jellyfin.enc(titel) + "&an=" + an);
    }
}
