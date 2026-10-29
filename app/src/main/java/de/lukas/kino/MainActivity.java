package de.lukas.kino;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Lukas Kino – eigene Startseite für den TCL-Fernseher.
 * Ganz normale Android-Oberfläche (keine WebView mehr): Bühne 1920x1080, wird auf den Bildschirm skaliert.
 * Aussehen und Bedienung 1:1 wie die frühere Web-Version.
 */
public class MainActivity extends Activity {

    static final String TAG = "LukasKino";

    // ---------- Einstellungen der Oberfläche ----------
    private static final List<String> FAVORITEN = Arrays.asList(
            "com.google.android.youtube.tv", "com.spotify.tv.android", "com.netflix.ninja", "@hdmi2",
            "com.amazon.amazonvideo.livingroom", "com.plexapp.android", "com.google.android.youtube.tvmusic",
            "com.limelight", "com.valvesoftware.steamlink", "tunein.player");
    private static final Pattern VERSTECKT = Pattern.compile(
            "^(de\\.lukas\\.kino$|com\\.tcl\\.|com\\.google\\.android\\.apps\\.tv\\.launcherx|com\\.google\\.android\\.tungsten)");
    private static final int SCHRITT = 334, SICHTBAR = 5;
    private static final int SPUR_LINKS = 96, KACHEL_OBEN = 804; // Kachel-Oberkante auf der Bühne

    static final class App {
        final String pkg, name, unter;
        final boolean banner, spiel;

        App(String pkg, String name, boolean banner, boolean spiel, String unter) {
            this.pkg = pkg;
            this.name = name;
            this.banner = banner;
            this.spiel = spiel;
            this.unter = unter;
        }
    }

    private static final App PLAYSTATION = new App("@hdmi2", "PlayStation", false, true, "HDMI 2");

    // ---------- Zustand (wie in der Web-Version) ----------
    private final List<App> apps = new ArrayList<>();
    private final List<Kachel> kacheln = new ArrayList<>();
    private int fokus = 0, scroll = 0;
    private String phase = "", modus = "";
    private boolean menueWahl = false, an = false, langGedrueckt = false;
    private List<String> bilder = new ArrayList<>();
    private int bildIndex = -1;
    private int ladeNummer = 0;

    // ---------- Ansichten ----------
    private FrameLayout wurzel, buehne, reihe, spur;
    private Hintergrund hintergrund;
    private View scrim, schwarz;
    private Beschriftung uhr, ort;
    private Pille suche, toast;
    private ValueAnimator spurAni;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService arbeiter = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap[]> bildCache = new HashMap<>();
    private final Random zufall = new Random();
    private BroadcastReceiver bildschirmAn, umzugFertig;
    private SharedPreferences einst;
    private Runnable t1, t2, toastWeg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vollbild();
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        einst = getSharedPreferences("lukaskino", MODE_PRIVATE);
        aufbauen();

        bildschirmAn = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                einschalten();
            }
        };
        umzugFertig = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                laden();
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bildschirmAn, new IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_EXPORTED);
            registerReceiver(umzugFertig, new IntentFilter(UmzugDienst.FERTIG), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(bildschirmAn, new IntentFilter(Intent.ACTION_SCREEN_ON));
            registerReceiver(umzugFertig, new IntentFilter(UmzugDienst.FERTIG));
        }
        umzugStarten();

        uhrTick.run();
        laden();
        einschalten();
    }

    private void vollbild() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    protected void onResume() {
        super.onResume();
        vollbild();
        wiederda();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        boolean istHome = intent != null
                && (intent.getBooleanExtra(StartseitenWaechter.EXTRA_HOME, false)
                || intent.hasCategory(Intent.CATEGORY_HOME));
        if (istHome) home();
        else wiederda();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(bildschirmAn);
            unregisterReceiver(umzugFertig);
        } catch (Exception ignored) {
        }
        h.removeCallbacksAndMessages(null);
        arbeiter.shutdownNow();
        super.onDestroy();
    }

    // ---------- Aufbau der Bühne ----------

    private FrameLayout.LayoutParams lp(int w, int h, int x, int y) {
        FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(w, h);
        l.leftMargin = x;
        l.topMargin = y;
        return l;
    }

    private void aufbauen() {
        wurzel = new FrameLayout(this);
        wurzel.setBackgroundColor(Color.BLACK);
        buehne = new FrameLayout(this);
        buehne.setBackgroundColor(Color.BLACK);
        buehne.setPivotX(0);
        buehne.setPivotY(0);
        buehne.setClipChildren(true);
        wurzel.addView(buehne, new FrameLayout.LayoutParams(1920, 1080));
        wurzel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> skalieren(r - l, b - t));
        Glas.buehne = buehne;

        hintergrund = new Hintergrund(this);
        hintergrund.setPivotX(960);
        hintergrund.setPivotY(540);
        buehne.addView(hintergrund, lp(1920, 1080, 0, 0));
        Glas.hintergrund = hintergrund;
        arbeiter.execute(() -> {
            Bitmap u = Glas.verwischen(Hintergrund.standardKlein());
            h.post(() -> {
                if (bilder.isEmpty()) {
                    Glas.unscharf = u;
                    Glas.neuZeichnen();
                }
            });
        });

        scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Ani.farbe(0x08060E, 0.55f), Ani.farbe(0x08060E, 0f)}));
        buehne.addView(scrim, lp(1920, 480, 0, 600));

        uhr = new Beschriftung(this, false);
        buehne.addView(uhr, lp(1100, 176 + 2 * Beschriftung.RAND, 96 - Beschriftung.RAND, 60 - Beschriftung.RAND));
        ort = new Beschriftung(this, true);
        buehne.addView(ort, lp(1100, 80 + 2 * Beschriftung.RAND, 1920 - 96 + Beschriftung.RAND - 1100, 96 - Beschriftung.RAND));
        ort.setVisibility(View.INVISIBLE);

        reihe = new FrameLayout(this);
        reihe.setClipChildren(false);
        buehne.addView(reihe, lp(1920, 1080, 0, 0));
        spur = new FrameLayout(this);
        spur.setClipChildren(false);
        reihe.addView(spur, lp(1920, 1080, 0, 0));

        // Suchfeld: 720x88, links 96, unten 64
        suche = new Pille(this, 30, 500, Symbole.SUCHE, 34, 2.2f, 20, 34, 0, 720, 88, 44,
                Ani.farbe(0xFFFFFF, 0.24f), Ani.farbe(0xFFFFFF, 0.4f), true, 50, 20, 0.4f, false);
        suche.text("Nach Apps und Spielen suchen");
        buehne.addView(suche, lp(720 + 2 * Pille.RAND, 88 + 2 * Pille.RAND, 96 - Pille.RAND, 1080 - 64 - 88 - Pille.RAND));
        suche.setAlpha(0f);
        suche.setTranslationY(40f);

        // Meldung oben in der Mitte (nur bei Fehlern)
        toast = new Pille(this, 28, 600, null, 0, 0, 0, 34, 18, 0, 0, 999,
                Ani.farbe(0x14101E, 0.45f), Ani.farbe(0xFFFFFF, 0.3f), false, 0, 0, 0, true);
        buehne.addView(toast, lp(1920, 70 + 2 * Pille.RAND, 0, 84 - Pille.RAND));
        toast.setAlpha(0f);
        toast.setTranslationY(-12f);

        schwarz = new View(this);
        schwarz.setBackgroundColor(Color.BLACK);
        buehne.addView(schwarz, lp(1920, 1080, 0, 0));

        setContentView(wurzel);
    }

    private void skalieren(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w / 1920f, h / 1080f);
        buehne.setScaleX(s);
        buehne.setScaleY(s);
        buehne.setTranslationX((w - 1920 * s) / 2f);
        buehne.setTranslationY((h - 1080 * s) / 2f);
        Log.i(TAG, "groesse " + w + "x" + h + " skala " + s);
    }

    // ---------- Uhr ----------

    private final Runnable uhrTick = new Runnable() {
        @Override
        public void run() {
            Calendar k = Calendar.getInstance();
            uhr.text(String.format(Locale.ROOT, "%02d:%02d", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE)));
            h.postDelayed(this, 5000);
        }
    };

    // ---------- Meldung ----------

    private void zeigen(String text) {
        toast.text(text);
        toast.animate().alpha(1f).translationY(0f).setDuration(250).setInterpolator(Ani.EASE).start();
        if (toastWeg != null) h.removeCallbacks(toastWeg);
        toastWeg = () -> toast.animate().alpha(0f).translationY(-12f).setDuration(250).setInterpolator(Ani.EASE).start();
        h.postDelayed(toastWeg, 2200);
    }

    // ---------- Apps laden ----------

    private void laden() {
        PackageManager pm = getPackageManager();
        List<App> liste = new ArrayList<>();
        Set<String> gesehen = new HashSet<>();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> gefunden;
        try {
            gefunden = pm.queryIntentActivities(i, 0);
        } catch (Exception e) {
            gefunden = new ArrayList<>();
        }
        for (ResolveInfo ri : gefunden) {
            String pkg = ri.activityInfo.packageName;
            if (gesehen.contains(pkg) || VERSTECKT.matcher(pkg).find()) continue;
            gesehen.add(pkg);
            boolean hatBanner = false;
            try {
                hatBanner = pm.getActivityBanner(new ComponentName(pkg, ri.activityInfo.name)) != null
                        || pm.getApplicationBanner(pkg) != null;
            } catch (Exception ignored) {
            }
            liste.add(new App(pkg, String.valueOf(ri.loadLabel(pm)), hatBanner, false, null));
        }
        Map<String, App> nachPkg = new HashMap<>();
        for (App a : liste) nachPkg.put(a.pkg, a);
        nachPkg.put(PLAYSTATION.pkg, PLAYSTATION);

        List<String> basis = gespeicherteReihenfolge();
        if (basis.isEmpty()) basis = FAVORITEN;
        List<App> reihenfolge = new ArrayList<>();
        Set<String> drin = new HashSet<>();
        for (String p : basis) {
            App a = nachPkg.get(p);
            if (a != null && drin.add(p)) reihenfolge.add(a);
        }
        final Collator col = Collator.getInstance(Locale.GERMAN);
        List<App> rest = new ArrayList<>(liste);
        rest.sort((a, b) -> col.compare(a.name, b.name));
        for (App a : rest) if (drin.add(a.pkg)) reihenfolge.add(a);

        String neu = schluessel(reihenfolge), alt = schluessel(apps);
        apps.clear();
        apps.addAll(reihenfolge);
        if (!neu.equals(alt)) {
            zeichnen();
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < Math.min(12, apps.size()); k++) sb.append(k > 0 ? ", " : "").append(apps.get(k).name).append(apps.get(k).banner ? "*" : "");
            Log.i(TAG, "apps " + apps.size() + ": " + sb);
        }
    }

    private static String schluessel(List<App> l) {
        StringBuilder sb = new StringBuilder();
        for (App a : l) sb.append(a.pkg).append('|');
        return sb.toString();
    }

    private List<String> gespeicherteReihenfolge() {
        List<String> r = new ArrayList<>();
        String s = einst.getString("reihenfolge", null);
        if (s == null) s = umzugLesen();
        if (s == null || s.isEmpty()) return r;
        try {
            JSONArray arr = new JSONArray(s);
            for (int k = 0; k < arr.length(); k++) r.add(arr.getString(k));
        } catch (Exception ignored) {
        }
        return r;
    }

    private void speichern() {
        JSONArray arr = new JSONArray();
        for (App a : apps) arr.put(a.pkg);
        einst.edit().putString("reihenfolge", arr.toString()).apply();
    }

    // ---------- Umzug der alten Reihenfolge (einmalig) ----------

    private void umzugStarten() {
        if (einst.getBoolean("umzug_fertig", false) || einst.contains("reihenfolge")) return;
        if (new File(getFilesDir(), UmzugDienst.DATEI).exists()) return;
        try {
            startService(new Intent(this, UmzugDienst.class));
        } catch (Exception e) {
            Log.w(TAG, "Umzug ging nicht", e);
            einst.edit().putBoolean("umzug_fertig", true).apply();
        }
    }

    /** Liest das Ergebnis des Umzugs einmal ein und übernimmt es fest. */
    private String umzugLesen() {
        if (einst.getBoolean("umzug_fertig", false)) return null;
        File f = new File(getFilesDir(), UmzugDienst.DATEI);
        if (!f.exists()) return null;
        String s = "";
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b);
            s = new String(b, 0, Math.max(0, n), StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
        }
        SharedPreferences.Editor e = einst.edit().putBoolean("umzug_fertig", true);
        if (!s.isEmpty()) e.putString("reihenfolge", s);
        e.apply();
        Log.i(TAG, "Reihenfolge uebernommen: " + (s.isEmpty() ? "(keine, Standard)" : s));
        return s;
    }

    // ---------- Kacheln ----------

    private void zeichnen() {
        for (Kachel k : kacheln) k.entfernen();
        spur.removeAllViews();
        kacheln.clear();
        int nummer = ++ladeNummer;
        for (int i = 0; i < apps.size(); i++) {
            App a = apps.get(i);
            Kachel k = new Kachel(this, a);
            spur.addView(k, kachelPlatz(i));
            kacheln.add(k);
            bilderHolen(k, nummer);
        }
        if (fokus >= apps.size()) fokus = Math.max(0, apps.size() - 1);
        spur.getLayoutParams().width = SPUR_LINKS + apps.size() * SCHRITT + 400;
        spur.requestLayout();
        fokusSetzen(0, true);
    }

    private FrameLayout.LayoutParams kachelPlatz(int i) {
        return lp(Kachel.VIEW_W, Kachel.VIEW_H, SPUR_LINKS + i * SCHRITT - Kachel.RAND_X, KACHEL_OBEN - Kachel.RAND_OBEN);
    }

    private void bilderHolen(Kachel k, int nummer) {
        final App a = k.app;
        if (a.spiel) {
            k.bilderDa(null, null);
            return;
        }
        Bitmap[] fertig;
        synchronized (bildCache) {
            fertig = bildCache.get(a.pkg);
        }
        if (fertig != null) {
            k.bilderDa(fertig[0], fertig[1]);
            return;
        }
        arbeiter.execute(() -> {
            Bitmap b = a.banner ? bannerBild(a.pkg) : null;
            Bitmap s = b == null ? symbolBild(a.pkg) : null;
            synchronized (bildCache) {
                bildCache.put(a.pkg, new Bitmap[]{b, s});
            }
            h.post(() -> {
                if (nummer == ladeNummer || kacheln.contains(k)) k.bilderDa(b, s);
            });
        });
    }

    private Bitmap bannerBild(String pkg) {
        PackageManager pm = getPackageManager();
        Drawable d = null;
        try {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i != null && i.getComponent() != null) d = pm.getActivityBanner(i.getComponent());
            if (d == null) d = pm.getApplicationBanner(pkg);
        } catch (Exception ignored) {
        }
        if (d == null) return null;
        // Etwas größer als die Kachel, damit sie auch angehoben (x1,12) scharf bleibt.
        int bw = 336, bh = 190;
        int iw = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 320;
        int ih = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 180;
        float s = Math.max((float) bw / iw, (float) bh / ih);
        int dw = Math.round(iw * s), dh = Math.round(ih * s);
        Bitmap bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        d.setBounds((bw - dw) / 2, (bh - dh) / 2, (bw - dw) / 2 + dw, (bh - dh) / 2 + dh);
        d.draw(c);
        return bm;
    }

    private Bitmap symbolBild(String pkg) {
        try {
            Drawable d = getPackageManager().getApplicationIcon(pkg);
            Bitmap bm = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 64, 64);
            d.draw(c);
            return bm;
        } catch (Exception e) {
            return null;
        }
    }

    /** Wie fokusSetzen() der Web-Version; staffel = Verzögerung je Kachel beim Hereinfahren. */
    private void fokusSetzen(int staffel, boolean sofort) {
        if (fokus < scroll) scroll = fokus;
        if (fokus > scroll + SICHTBAR - 1) scroll = fokus - (SICHTBAR - 1);
        if (scroll < 0) scroll = 0;
        float ziel = -scroll * SCHRITT;
        if (sofort) {
            if (spurAni != null) spurAni.cancel();
            spur.setTranslationX(ziel);
        } else if (spur.getTranslationX() != ziel) {
            if (spurAni != null) spurAni.cancel();
            spurAni = ObjectAnimator.ofFloat(spur, "translationX", spur.getTranslationX(), ziel);
            spurAni.setDuration(380);
            spurAni.setInterpolator(Ani.AUS);
            spurAni.addUpdateListener(a -> Glas.neuZeichnen());
            spurAni.start();
        }
        boolean sichtbar = phase.equals("apps") || phase.equals("suche");
        boolean inApps = phase.equals("apps");
        for (int i = 0; i < kacheln.size(); i++) {
            boolean hier = i == fokus;
            boolean gehoben = hier && (modus.equals("gehoben") || modus.equals("menue"));
            boolean menue = hier && modus.equals("menue");
            long verz = staffel > 0 ? Math.min(Math.max(0, i - scroll), 6) * (long) staffel : 0;
            kacheln.get(i).zustand(sichtbar, inApps, hier, gehoben, menue, menue && menueWahl, verz, sofort);
        }
    }

    // ---------- Zustände ----------

    private void phaseSetzen(String p, int staffel) {
        String war = phase;
        phase = p;
        float reiheZiel = p.equals("suche") ? -118f : 0f;
        if (reihe.getTranslationY() != reiheZiel) {
            reihe.animate().translationY(reiheZiel).setDuration(450).setInterpolator(Ani.AUS)
                    .setUpdateListener(a -> Glas.neuZeichnen()).start();
        }
        boolean sucheDa = p.equals("suche");
        if (war.equals("suche") != sucheDa || (sucheDa && suche.getAlpha() < 1f)) {
            suche.animate().cancel();
            ObjectAnimator.ofFloat(suche, "alpha", suche.getAlpha(), sucheDa ? 1f : 0f).setDuration(350).start();
            suche.animate().translationY(sucheDa ? 0f : 40f).setDuration(450).setInterpolator(Ani.AUS)
                    .setUpdateListener(a -> suche.invalidate()).start();
        }
        fokusSetzen(staffel, false);
    }

    private void appsRein() {
        boolean staffeln = !(phase.equals("apps") || phase.equals("suche"));
        phaseSetzen("apps", staffeln ? 55 : 0);
    }

    private void einschalten() {
        if (t1 != null) h.removeCallbacks(t1);
        if (t2 != null) h.removeCallbacks(t2);
        an = false;
        for (View v : new View[]{hintergrund, scrim, uhr, ort, schwarz}) {
            v.animate().cancel();
        }
        hintergrund.setAlpha(0f);
        hintergrund.setScaleX(1.07f);
        hintergrund.setScaleY(1.07f);
        scrim.setAlpha(0f);
        uhr.setAlpha(0f);
        uhr.setTranslationY(-16f);
        ort.setAlpha(0f);
        ort.setTranslationY(-16f);
        schwarz.setAlpha(1f);
        phase = "";
        fokus = 0;
        scroll = 0;
        modus = "";
        menueWahl = false;
        reihe.setTranslationY(0f);
        suche.setAlpha(0f);
        suche.setTranslationY(40f);
        fokusSetzen(0, true);
        bilderLaden();
        if (!bilder.isEmpty()) bildZeigen(zufall.nextInt(bilder.size()));
        else bildZeigen(-1);
        t1 = () -> {
            an = true;
            hintergrund.animate().scaleX(1f).scaleY(1f).setDuration(2600).setInterpolator(Ani.AUS)
                    .setUpdateListener(a -> Glas.neuZeichnen()).start();
            ObjectAnimator.ofFloat(hintergrund, "alpha", 0f, 1f).setDuration(1000).start();
            scrim.animate().alpha(1f).setDuration(900).setInterpolator(Ani.EASE).start();
            reinBlenden(uhr, 250);
            reinBlenden(ort, 400);
            schwarz.animate().alpha(0f).setDuration(700).setInterpolator(Ani.EASE).start();
        };
        t2 = this::appsRein;
        h.postDelayed(t1, 400);
        h.postDelayed(t2, 1700);
    }

    private void reinBlenden(View v, long verz) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "alpha", 0f, 1f);
        a.setDuration(900);
        a.setInterpolator(Ani.EASE);
        a.setStartDelay(verz);
        a.start();
        v.animate().translationY(0f).setDuration(900).setInterpolator(Ani.AUS).setStartDelay(verz).start();
    }

    private void wiederda() {
        if (!an) return;
        laden();
        int anzahl = bilder.size();
        bilderLaden();
        if (bilder.size() != anzahl) bildZeigen(bilder.isEmpty() ? -1 : 0);
        if (!phase.equals("apps")) appsRein();
    }

    // ---------- Hintergründe ----------

    private File hintergrundOrdner() {
        File basis = getExternalFilesDir(null);
        if (basis == null) basis = getFilesDir();
        File d = new File(basis, "hintergruende");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void bilderLaden() {
        List<String> l = new ArrayList<>();
        File[] dateien = hintergrundOrdner().listFiles();
        if (dateien != null) {
            Arrays.sort(dateien);
            for (File f : dateien) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) {
                    l.add(f.getName());
                }
            }
        }
        bilder = l;
    }

    private void bildZeigen(int i) {
        if (bilder.isEmpty() || i < 0) {
            ort.setVisibility(View.INVISIBLE);
            hintergrund.zeigen(null);
            arbeiter.execute(() -> {
                Bitmap u = Glas.verwischen(Hintergrund.standardKlein());
                h.post(() -> {
                    Glas.unscharf = u;
                    Glas.neuZeichnen();
                });
            });
            return;
        }
        bildIndex = ((i % bilder.size()) + bilder.size()) % bilder.size();
        final String name = bilder.get(bildIndex);
        final File f = new File(hintergrundOrdner(), name);
        arbeiter.execute(() -> {
            Bitmap b = bildLesen(f);
            if (b == null) return;
            Bitmap u = Glas.verwischen(b);
            h.post(() -> {
                if (bildIndex < 0 || bildIndex >= bilder.size() || !name.equals(bilder.get(bildIndex))) return;
                hintergrund.zeigen(b);
                Glas.unscharf = u;
                Glas.neuZeichnen();
            });
        });
        String titel = name.replaceAll("\\.[^.]+$", "").replaceAll("_+", " ").trim();
        if (name.startsWith("_") || titel.isEmpty()) {
            ort.setVisibility(View.INVISIBLE);
        } else {
            ort.text(titel);
            ort.setVisibility(View.VISIBLE);
        }
    }

    /** Bild laden, auf ca. Bühnengröße verkleinert (spart Speicher, 4K-Bild wird 1920 breit). */
    private static Bitmap bildLesen(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= 1920 && o.outHeight / (s * 2) >= 1080) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable t) {
            Log.w(TAG, "Bild ging nicht: " + f, t);
            return null;
        }
    }

    // ---------- Bedienung ----------

    private void oeffnen(App a) {
        if (a.pkg.equals("@hdmi2")) {
            if (!hdmi(2)) zeigen("HDMI 2 nicht gefunden");
            return;
        }
        starten(a.pkg);
    }

    private void taste(String r) {
        if (phase.equals("apps") && modus.equals("menue")) {
            if (r.equals("hoch")) menueWahl = true;
            else if (r.equals("runter")) menueWahl = false;
            fokusSetzen(0, false);
            return;
        }
        if (phase.equals("apps")) {
            if (r.equals("links") || r.equals("rechts")) {
                int ziel = fokus + (r.equals("links") ? -1 : 1);
                if (ziel < 0 || ziel >= apps.size()) return;
                if (modus.equals("gehoben")) {
                    App a = apps.get(fokus);
                    apps.set(fokus, apps.get(ziel));
                    apps.set(ziel, a);
                    Kachel k1 = kacheln.get(fokus), k2 = kacheln.get(ziel);
                    kacheln.set(fokus, k2);
                    kacheln.set(ziel, k1);
                    k2.setLayoutParams(kachelPlatz(fokus));
                    k1.setLayoutParams(kachelPlatz(ziel));
                    speichern();
                    Glas.neuZeichnen();
                }
                fokus = ziel;
                fokusSetzen(0, false);
            } else if (r.equals("hoch")) {
                if (modus.isEmpty()) phaseSetzen("bild", 0);
            } else if (r.equals("runter")) {
                if (modus.isEmpty()) phaseSetzen("suche", 0);
            }
        } else if (phase.equals("bild")) {
            if (r.equals("runter")) appsRein();
            else if ((r.equals("links") || r.equals("rechts")) && bilder.size() > 1) {
                bildZeigen(bildIndex + (r.equals("links") ? -1 : 1));
            }
        } else if (phase.equals("suche")) {
            if (r.equals("hoch")) phaseSetzen("apps", 0);
        }
    }

    private void ok() {
        if (phase.equals("apps")) {
            if (modus.equals("menue")) {
                if (menueWahl && fokus < apps.size()) deinstallieren(apps.get(fokus).pkg);
                modus = menueWahl ? "" : "gehoben";
                menueWahl = false;
                fokusSetzen(0, false);
                return;
            }
            if (modus.equals("gehoben")) {
                modus = "";
                speichern();
                fokusSetzen(0, false);
                return;
            }
            if (fokus < apps.size()) oeffnen(apps.get(fokus));
        } else if (phase.equals("bild")) {
            appsRein();
        } else if (phase.equals("suche")) {
            suchen();
        }
    }

    private void halten() {
        if (!phase.equals("apps") || apps.isEmpty()) return;
        if (modus.isEmpty()) modus = "gehoben";
        else if (modus.equals("gehoben") && !apps.get(fokus).pkg.equals("@hdmi2")) {
            modus = "menue";
            menueWahl = false;
        }
        fokusSetzen(0, false);
    }

    /** Zurück zum Anfang: Kacheln da, YouTube (erste Kachel) ausgewählt, Reihe ganz links. */
    private void zumAnfang() {
        if (!modus.isEmpty()) speichern();
        modus = "";
        menueWahl = false;
        fokus = 0;
        scroll = 0;
        if (!phase.equals("apps")) appsRein();
        else fokusSetzen(0, false);
    }

    private void zurueck() {
        if (modus.equals("menue")) {
            modus = "gehoben";
            menueWahl = false;
            fokusSetzen(0, false);
            return;
        }
        if (!modus.isEmpty()) {
            modus = "";
            speichern();
            fokusSetzen(0, false);
            return;
        }
        zumAnfang();
    }

    /** Home-Taste: immer an den Anfang (auch wenn man aus einer App zurückkommt). */
    private void home() {
        if (!an) {
            einschalten();
            return;
        }
        laden();
        zumAnfang();
    }

    // ---------- Tasten der Fernbedienung ----------

    /** Öffnet die normale Einstellungen-App (Ersatz für das Google-Seitenmenü hinter der Einstellungstaste). */
    static boolean einstellungenOeffnen(Context c) {
        Intent[] wege = new Intent[]{
                new Intent(android.provider.Settings.ACTION_SETTINGS),
                c.getPackageManager().getLeanbackLaunchIntentForPackage("com.android.tv.settings"),
                c.getPackageManager().getLaunchIntentForPackage("com.android.tv.settings")
        };
        for (Intent i : wege) {
            if (i == null) continue;
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                Log.i(TAG, "Einstellungen geoeffnet: " + i);
                return true;
            } catch (Exception ex) {
                Log.w(TAG, "Einstellungen gingen nicht: " + i, ex);
            }
        }
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            Log.i(TAG, "Taste " + KeyEvent.keyCodeToString(k) + " (" + k + ", scan " + e.getScanCode() + ")");
        }
        if (StartseitenWaechter.istEinstellungstaste(k)) {
            if (e.getAction() == KeyEvent.ACTION_UP) einstellungenOeffnen(this);
            return true;
        }
        String richtung = null;
        if (k == KeyEvent.KEYCODE_DPAD_UP) richtung = "hoch";
        else if (k == KeyEvent.KEYCODE_DPAD_DOWN) richtung = "runter";
        else if (k == KeyEvent.KEYCODE_DPAD_LEFT) richtung = "links";
        else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) richtung = "rechts";
        if (richtung != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) taste(richtung);
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    langGedrueckt = false;
                } else if (!langGedrueckt) {
                    langGedrueckt = true;
                    halten();
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                if (!langGedrueckt) ok();
                langGedrueckt = false;
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) zurueck();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------- Apps starten, HDMI, Suche, Deinstallieren ----------

    private void starten(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "Start ging nicht: " + pkg, e);
        }
    }

    private void deinstallieren(String pkg) {
        if (pkg == null || pkg.startsWith("@") || pkg.equals(getPackageName())) return;
        try {
            // Android fragt selbst noch einmal nach, bevor wirklich etwas gelöscht wird.
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private boolean hdmi(int nummer) {
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) return false;
            List<TvInputInfo> liste = new ArrayList<>();
            for (TvInputInfo t : tim.getTvInputList()) {
                if (t.getType() == TvInputInfo.TYPE_HDMI && t.getParentId() == null) liste.add(t);
            }
            TvInputInfo treffer = null;
            String nr = String.valueOf(nummer);
            for (TvInputInfo t : liste) {
                String name = String.valueOf(t.loadLabel(this)).replace(" ", "");
                if (name.toUpperCase(Locale.ROOT).endsWith("HDMI" + nr) || name.endsWith(nr)) {
                    treffer = t;
                    break;
                }
            }
            if (treffer == null && liste.size() >= nummer) treffer = liste.get(nummer - 1);
            if (treffer == null) return false;
            Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(treffer.getId()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void suchen() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=&c=apps"));
            i.setPackage("com.android.vending");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            try {
                PackageManager pm = getPackageManager();
                Intent i = pm.getLeanbackLaunchIntentForPackage("com.android.vending");
                if (i == null) i = pm.getLaunchIntentForPackage("com.android.vending");
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
