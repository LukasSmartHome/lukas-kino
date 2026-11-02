package de.lukas.kino;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Laedt Poster und Hintergruende vom Jellyfin-Server.
 * Sparsam, weil der TCL nur 2,4 GB Arbeitsspeicher hat: RGB_565, kleiner Zwischenspeicher, zwei Threads.
 */
final class Bilder {

    interface Fertig {
        void bild(String url, Bitmap b);
    }

    private static Bilder instanz;

    private final LruCache<String, Bitmap> cache;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler haupt = new Handler(Looper.getMainLooper());
    private final Map<String, Set<Fertig>> wartend = new HashMap<>();
    private final Set<String> fehler = new HashSet<>();
    private Jellyfin jf;

    private Bilder() {
        int max = 40 * 1024 * 1024; // 40 MB fuer Bilder
        cache = new LruCache<String, Bitmap>(max) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    static synchronized Bilder get(Jellyfin jf) {
        if (instanz == null) instanz = new Bilder();
        instanz.jf = jf;
        return instanz;
    }

    Bitmap sofort(String url) {
        return url == null ? null : cache.get(url);
    }

    boolean fehlgeschlagen(String url) {
        synchronized (wartend) {
            return fehler.contains(url);
        }
    }

    /** Laedt im Hintergrund; ruft fertig auf dem Hauptthread auf (auch null bei Fehler). */
    void laden(final String url, final Fertig fertig) {
        if (url == null) return;
        Bitmap b = cache.get(url);
        if (b != null) {
            if (fertig != null) fertig.bild(url, b);
            return;
        }
        synchronized (wartend) {
            if (fehler.contains(url)) {
                if (fertig != null) fertig.bild(url, null);
                return;
            }
            Set<Fertig> s = wartend.get(url);
            if (s != null) {
                if (fertig != null) s.add(fertig);
                return;
            }
            s = new HashSet<>();
            if (fertig != null) s.add(fertig);
            wartend.put(url, s);
        }
        pool.execute(() -> {
            Bitmap bild = holen(url);
            if (bild != null) cache.put(url, bild);
            haupt.post(() -> {
                Set<Fertig> s;
                synchronized (wartend) {
                    s = wartend.remove(url);
                    if (bild == null) fehler.add(url);
                }
                if (s != null) for (Fertig f : s) f.bild(url, bild);
            });
        });
    }

    void leeren() {
        cache.evictAll();
    }

    private Bitmap holen(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(15000);
            if (jf != null) c.setRequestProperty("Authorization", jf.authHeader());
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            byte[] daten = out.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            // Logos brauchen Transparenz, alles andere spart mit RGB_565 die Haelfte
            o.inPreferredConfig = url.contains("/Images/Logo") ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(daten, 0, daten.length, o);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
