package de.lukas.kino;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * „Empfohlen“: Filme, die die anderen in der Familie mit 7 bis 10 bewertet haben (am TCL also Jonas, Moni und Uli).
 * Gruppiert nach der besten Note; innerhalb einer Gruppe zuerst, was man selbst noch nicht gesehen/bewertet hat.
 */
final class Empfohlen {

    static final List<String> ICH = Arrays.asList("Lukas");

    static final class Gruppe {
        final int note;
        final List<JSONObject> eintraege = new ArrayList<>();

        Gruppe(int note) {
            this.note = note;
        }
    }

    private static final class Kandidat {
        final String id;
        int best, summe, n;
        boolean selbst;

        Kandidat(String id) {
            this.id = id;
        }
    }

    private static final Map<String, JSONObject> speicher = new HashMap<>();
    private static volatile List<Gruppe> gruppen = new ArrayList<>();
    private static volatile String sig;

    private Empfohlen() {
    }

    static boolean geladen() {
        return sig != null;
    }

    static List<Gruppe> gruppen() {
        return gruppen;
    }

    /** Aus den HA-Daten neu berechnen. Blockiert. Liefert true, wenn sich etwas geändert hat. */
    static synchronized boolean berechnen(Jellyfin jf) throws Exception {
        JSONObject j = Familie.roh();
        if (j == null) return false;
        FilmIndex.laden(jf, false);
        Map<String, Kandidat> k = new HashMap<>();
        JSONObject b = j.optJSONObject("bewertungen");
        Iterator<String> it = b != null ? b.keys() : null;
        while (it != null && it.hasNext()) {
            JSONObject f = b.optJSONObject(it.next());
            JSONObject w = f != null ? f.optJSONObject("w") : null;
            if (w == null) continue;
            List<String> gut = new ArrayList<>();
            boolean selbst = false;
            Iterator<String> wi = w.keys();
            while (wi.hasNext()) {
                String n = wi.next();
                if (ICH.contains(n)) selbst = true;
                else if (w.optInt(n) >= 7) gut.add(n);
            }
            if (gut.isEmpty()) continue;
            String id = FilmIndex.fuerTitel(f.optString("t"));
            if (id == null) continue;
            Kandidat x = k.get(id);
            if (x == null) k.put(id, x = new Kandidat(id));
            for (String n : gut) {
                int v = w.optInt(n);
                x.best = Math.max(x.best, v);
                x.summe += v;
                x.n++;
            }
            if (selbst) x.selbst = true;
        }
        JSONArray v = j.optJSONArray("verlauf");
        for (int i = 0; v != null && i < v.length(); i++) {
            JSONObject e = v.optJSONObject(i);
            if (e == null || !ICH.contains(e.optString("wer"))) continue;
            String typ = e.optString("typ", "movie");
            if (!typ.isEmpty() && !"movie".equals(typ)) continue;
            String id = FilmIndex.fuerTitel(e.optString("titel"));
            if (id != null && k.containsKey(id)) k.get(id).selbst = true;
        }
        List<Kandidat> l = new ArrayList<>(k.values());
        Collections.sort(l, (a, c) -> {
            if (a.best != c.best) return c.best - a.best;
            if (a.selbst != c.selbst) return a.selbst ? 1 : -1;
            float da = a.summe / (float) a.n, dc = c.summe / (float) c.n;
            if (da != dc) return Float.compare(dc, da);
            return c.n - a.n;
        });
        StringBuilder sb = new StringBuilder();
        List<String> fehlen = new ArrayList<>();
        for (Kandidat x : l) {
            sb.append(x.id).append(':').append(x.best).append(x.selbst ? "s," : ",");
            if (!speicher.containsKey(x.id)) fehlen.add(x.id);
        }
        String neuSig = sb.toString();
        if (neuSig.equals(sig)) return false;
        for (int i = 0; i < fehlen.size(); i += 60) {
            try {
                JSONArray a = jf.nachIds(fehlen.subList(i, Math.min(fehlen.size(), i + 60)));
                for (int q = 0; q < a.length(); q++) {
                    JSONObject o = a.optJSONObject(q);
                    if (o != null) speicher.put(o.optString("Id"), o);
                }
            } catch (Exception ignored) {
                // dann fehlen sie eben
            }
        }
        List<Gruppe> g = new ArrayList<>();
        for (Kandidat x : l) {
            JSONObject e = speicher.get(x.id);
            if (e == null) continue;
            Gruppe last = g.isEmpty() ? null : g.get(g.size() - 1);
            if (last == null || last.note != x.best) g.add(last = new Gruppe(x.best));
            last.eintraege.add(e);
        }
        gruppen = g;
        sig = neuSig;
        return true;
    }

    /** Neueste Fassung eines Films übernehmen (z. B. nach dem Anschauen). */
    static synchronized void ersetzen(JSONObject film) {
        String id = film.optString("Id");
        if (speicher.containsKey(id)) speicher.put(id, film);
    }
}
