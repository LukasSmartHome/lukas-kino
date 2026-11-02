package de.lukas.kino;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Verbindung zu Home Assistant für Wiedergabe und „Film vom Dashboard starten“ (seit 02.11.2026, Plex → Jellyfin).
 *
 * Wiedergabe: Start, Stand (höchstens jede Minute, auch pausiert) und Stopp gehen über den Webhook von Automation 84
 * als Ereignis kino_wiedergabe an HA (sensor.kino_jetzt) – für den Verlauf im Dashboard und das Abspann-Licht (39).
 * Filme mit derselben ID wie im Dashboard (alte Plex-ID, sonst jf:&lt;ID&gt;).
 *
 * Auftrag: Skript 01g (script.kino_film_abspielen) legt in HA einen Auftrag ab, kino_export schreibt ihn nach
 * /share/kino/auftrag.json: { auftraege: { Lukas: {id, titel, ts} } }. Solange Lukas Kino vorne ist, schaut die App
 * alle 4 Sekunden nach und spielt einen neuen Auftrag genau einmal ab (gemerkt über ts).
 */
final class KinoHa {

    private static final ExecutorService netz = Executors.newSingleThreadExecutor();

    private KinoHa() {
    }

    // ------------------------------------------------------------------ Wiedergabe

    /** art: start | stand | stopp. Läuft im Hintergrund, Fehler sind egal. */
    static void wiedergabe(JSONObject e, String art, long posMs, long dauerMs, boolean pause) {
        if (e == null) return;
        netz.execute(() -> {
            try {
                boolean folge = "Episode".equals(e.optString("Type"));
                String titel = e.optString("Name");
                if (folge) {
                    titel = e.optString("SeriesName") + " – " + String.format(Locale.ROOT, "S%02dE%02d",
                            e.optInt("ParentIndexNumber", 0), e.optInt("IndexNumber", 0));
                } else if (e.optInt("ProductionYear", 0) > 0) {
                    titel += " (" + e.optInt("ProductionYear") + ")";
                }
                String id = folge ? "jf:" + e.optString("Id") : Merkliste.haIdFuer(e);
                String bildId = folge && !e.optString("SeriesId").isEmpty() ? e.optString("SeriesId") : e.optString("Id");
                long dauer = dauerMs > 0 ? dauerMs / 1000 : e.optLong("RunTimeTicks", 0) / 10_000_000L;
                String status = "stopp".equals(art) ? "stopped" : pause ? "paused" : "playing";
                Merkliste.webhookSenden("art=" + art + "&wer=" + Jellyfin.enc(Merkliste.WER) + "&id=" + Jellyfin.enc(id)
                        + "&titel=" + Jellyfin.enc(titel) + "&typ=" + (folge ? "episode" : "movie")
                        + "&serie=" + Jellyfin.enc(folge ? e.optString("SeriesName") : "")
                        + "&pos=" + Math.max(0, posMs / 1000) + "&dauer=" + dauer + "&status=" + status
                        + "&bild=" + Jellyfin.enc(Jellyfin.poster(bildId, null, 450)));
            } catch (Exception ignored) {
                // HA nicht erreichbar – nicht schlimm
            }
        });
    }

    // ------------------------------------------------------------------ Auftrag vom Dashboard

    /** Was mit einem gefundenen Auftrag passieren soll (läuft auf dem Haupt-Thread). */
    interface Ziel {
        void abspielen(JSONObject film, boolean fortsetzen);
    }

    /** Schaut alle 4 Sekunden nach, solange die Activity vorne ist: an() in onResume, aus() in onPause. */
    static final class Waechter {
        private final Activity a;
        private final Ziel ziel;
        private final Handler h = new Handler(Looper.getMainLooper());
        private final SharedPreferences prefs;
        private boolean laeuft, erster;
        private volatile boolean unterwegs;

        Waechter(Activity a, Ziel ziel) {
            this.a = a;
            this.ziel = ziel;
            prefs = a.getSharedPreferences("lukaskino", Context.MODE_PRIVATE);
        }

        void an() {
            if (laeuft) return;
            laeuft = true;
            erster = true;
            h.removeCallbacks(schritt);
            h.postDelayed(schritt, 500);
        }

        void aus() {
            laeuft = false;
            h.removeCallbacks(schritt);
        }

        private final Runnable schritt = new Runnable() {
            @Override
            public void run() {
                if (!laeuft) return;
                h.postDelayed(this, 4000);
                if (unterwegs) return;
                unterwegs = true;
                final boolean warErster = erster;
                erster = false;
                netz.execute(() -> {
                    try {
                        pruefen(warErster);
                    } catch (Exception ignored) {
                        // kein Auftrag oder Pi nicht erreichbar
                    } finally {
                        unterwegs = false;
                    }
                });
            }
        };

        private void pruefen(boolean warErster) throws Exception {
            Jellyfin jf = Jellyfin.get(a);
            if (!jf.angemeldet()) return;
            JSONObject j = new JSONObject(Jellyfin.holen(Familie.BASIS + "auftrag.json?t=" + System.currentTimeMillis()));
            JSONObject alle = j.optJSONObject("auftraege");
            JSONObject x = alle != null ? alle.optJSONObject(Merkliste.WER) : null;
            if (x == null || x.optString("id").isEmpty() || x.optDouble("ts", 0) <= 0) return;
            String kennung = x.optString("ts") + "|" + x.optString("id");
            synchronized (KinoHa.class) {
                if (kennung.equals(prefs.getString("auftrag", null))) return;
                prefs.edit().putString("auftrag", kennung).apply();
            }
            // Beim ersten Blick nach dem Nach-vorne-Kommen nur, wenn er frisch ist (sonst alt, z. B. vom Vortag)
            if (warErster && Math.abs(System.currentTimeMillis() / 1000.0 - x.optDouble("ts")) > 150) return;
            String id = x.optString("id");
            if (id.startsWith("jf:")) id = id.substring(3);
            JSONObject film = jf.film(id);
            if (film == null || film.optString("Id").isEmpty()) return;
            long pos = Jellyfin.position(film), lauf = film.optLong("RunTimeTicks", 0);
            boolean fortsetzen = pos > 0 && (lauf <= 0 || pos < lauf * 0.95);
            a.runOnUiThread(() -> {
                if (laeuft && !a.isFinishing()) ziel.abspielen(film, fortsetzen);
            });
        }
    }
}
