package de.lukas.kino;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Sanfte Startseite: Die Google-TV-Startseite bleibt installiert. Dieser Dienst
 *  1. fängt die Home-Taste ab und öffnet direkt Lukas Kino (Google blitzt dann nicht auf),
 *  2. holt Lukas Kino nach vorn, falls die Google-Startseite trotzdem aufgeht
 *     (z. B. eine App beendet sich selbst),
 *  3. holt Lukas Kino einmal nach dem Hochfahren des Fernsehers,
 *  4. leitet die Mikro-Taste an Claude weiter (Push-to-Talk, Google kommt darüber nie mehr)
 *     und zeigt den orangen Sprach-Rahmen als Overlay über allem an.
 * Fire TV (Cube, seit 03.11.2026): Amazon lässt sich die Home-Taste nicht wegnehmen und die
 * Amazon-Startseite nicht abschalten (geschütztes Paket), und Tasten kommen hier gar nicht an.
 * Darum erkennt der Wächter die Amazon-Startseite, sobald sie aufgeht, und bittet Home Assistant
 * (Webhook startseiteWebhook aus kino_geheim.json, Automation 90), Lukas Kino per ADB sofort nach
 * vorn zu holen – ADB darf das gleich, eine App erst 5 s nach Home (Android-Sperre). Parallel
 * versucht er es selbst (Rückfall, falls HA nicht erreichbar ist).
 * Es wird kein Systempaket angefasst. Abschalten: Dienst in den Bedienungshilfen aus
 * oder per ADB aus enabled_accessibility_services entfernen.
 */
public class StartseitenWaechter extends AccessibilityService {

    /** Extra im Intent: Lukas Kino soll an den Anfang springen (wie Home). */
    public static final String EXTRA_HOME = "de.lukas.kino.HOME";

    private static final String TAG = "LukasKino";
    private static final String GOOGLE_STARTSEITE = "com.google.android.apps.tv.launcherx";
    private static final String GOOGLE_HOME_ACTIVITY = "com.google.android.apps.tv.launcherx.home.HomeActivity";
    /** Fire TV: Amazon-Startseite (com.amazon.tv.launcher/.ui.HomeActivity_vNext). Einstellungen liegen im
     *  selben Paket (SettingsActivity) und bleiben unangetastet – darum alles außer Klassen mit „Settings“. */
    private static final String AMAZON_STARTSEITE = "com.amazon.tv.launcher";
    private static final String HA = "http://192.168.178.44:8123";
    /** So lange nach dem Hochfahren gilt das Verbinden des Dienstes als „Fernseher ist gerade gestartet“. */
    private static final long BOOT_FENSTER_MS = 5 * 60 * 1000;

    private long letzterStart = 0;
    private long letzteHaBitte = 0;
    private static String haWebhook;

    private static StartseitenWaechter aktiv;
    private static final Handler HAUPT = new Handler(Looper.getMainLooper());
    private SprachRahmen rahmen;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        aktiv = this;
        long seitStart = SystemClock.elapsedRealtime();
        Log.i(TAG, "Waechter verbunden, " + (seitStart / 1000) + " s nach dem Hochfahren");
        if (seitStart < BOOT_FENSTER_MS) holen("hochgefahren");
        Sprache.starten(this);
        if (istFireTv()) {
            // Webhook schon jetzt holen, damit die erste Home-Taste nicht auf das Laden wartet.
            new Thread(() -> {
                try {
                    startseitenWebhook();
                } catch (Exception e) {
                    Log.w(TAG, "Waechter: Startseiten-Webhook fehlt noch", e);
                }
            }, "ha-webhook").start();
        }
    }

    private boolean istFireTv() {
        try {
            return getPackageManager().hasSystemFeature("amazon.hardware.fire_tv");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onDestroy() {
        rahmenEntfernen();
        if (aktiv == this) aktiv = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- Sprach-Rahmen (Overlay)

    /** Zeigt bzw. aktualisiert den orangen Rahmen mit Gesprächsverlauf ({"du"|"claude", text}). */
    static void rahmen(int zustand, String status, java.util.List<String[]> verlauf) {
        HAUPT.post(() -> {
            StartseitenWaechter w = aktiv;
            if (w != null) w.rahmenSetzen(zustand, status, verlauf);
        });
    }

    static void rahmenWeg() {
        HAUPT.post(() -> {
            StartseitenWaechter w = aktiv;
            if (w != null) w.rahmenEntfernen();
        });
    }

    private void rahmenSetzen(int zustand, String status, java.util.List<String[]> verlauf) {
        if (rahmen == null) {
            try {
                rahmen = new SprachRahmen(this);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(rahmen, lp);
            } catch (Exception e) {
                Log.w(TAG, "Sprach-Rahmen kann nicht angezeigt werden", e);
                rahmen = null;
                return;
            }
        }
        rahmen.setze(zustand, status, verlauf);
    }

    private void rahmenEntfernen() {
        if (rahmen == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(rahmen);
        } catch (Exception ignored) {
        }
        rahmen = null;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e == null) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            Log.i(TAG, "Waechter Taste " + KeyEvent.keyCodeToString(e.getKeyCode())
                    + " scan " + e.getScanCode() + " geraet " + e.getDeviceId());
        }
        // Mikro-Taste: Claude per Push-to-Talk. Immer schlucken, damit Google nicht aufgeht.
        if (Sprache.istSprachTaste(e.getKeyCode())) {
            Sprache s = Sprache.instanz;
            if (s == null) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    boolean gestartet = Sprache.starten(this);
                    rahmen(SprachRahmen.FEHLER, Sprache.mikrofonErlaubt(this)
                            ? (gestartet ? "Claude startet gerade – gleich nochmal drücken" : "Claude konnte nicht starten")
                            : "Mikrofon ist für Lukas Kino nicht freigegeben", new java.util.ArrayList<>());
                    HAUPT.postDelayed(StartseitenWaechter::rahmenWeg, 3500);
                }
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) s.tasteRunter();
            else if (e.getAction() == KeyEvent.ACTION_UP) s.tasteHoch();
            return true;
        }
        // Zurück schließt den Sprach-Rahmen, solange er zu sehen ist.
        if (rahmen != null && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                Sprache s = Sprache.instanz;
                if (s != null) s.abbrechen();
                else rahmenEntfernen();
            }
            return true;
        }
        // Einstellungstaste (die TCL-Fernbedienung sendet KEYCODE_NOTIFICATION, das öffnete das
        // Google-Seitenmenü – das gibt es nicht mehr), also die Einstellungen-App öffnen.
        if (istEinstellungstaste(e.getKeyCode())) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) MainActivity.einstellungenOeffnen(this);
            return true;
        }
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        // Ganze Home-Taste schlucken, damit die Google-Startseite gar nicht erst aufgeht.
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) {
            if (!holen("home-taste")) {
                // Notfalls normales Home, damit die Taste nie tot ist.
                performGlobalAction(GLOBAL_ACTION_HOME);
            }
        }
        return true;
    }

    static boolean istEinstellungstaste(int k) {
        return k == KeyEvent.KEYCODE_SETTINGS || k == KeyEvent.KEYCODE_NOTIFICATION;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence paket = e.getPackageName();
        CharSequence klasse = e.getClassName();
        if (paket == null || klasse == null) return;
        // Nur die eigentliche Startseite ersetzen, keine Einstellungs- oder Suchfenster.
        boolean google = GOOGLE_STARTSEITE.contentEquals(paket) && GOOGLE_HOME_ACTIVITY.contentEquals(klasse);
        boolean amazonPaket = AMAZON_STARTSEITE.contentEquals(paket);
        if (amazonPaket) Log.i(TAG, "Waechter Fenster " + paket + " / " + klasse);
        // Amazon: alles aus dem Startseiten-Paket außer den Einstellungen (SettingsActivity und Co.)
        boolean amazon = amazonPaket && !klasse.toString().contains("Settings");
        if (!google && !amazon) return;
        if (amazon) haBitten();
        holen(amazon ? "amazon-startseite" : "google-startseite");
    }

    /** Fire TV: Home Assistant holt Lukas Kino per ADB sofort nach vorn (Automation 90). */
    private void haBitten() {
        long jetzt = SystemClock.elapsedRealtime();
        if (jetzt - letzteHaBitte < 1500) return;
        letzteHaBitte = jetzt;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(startseitenWebhook()).openConnection();
                c.setConnectTimeout(3000);
                c.setReadTimeout(5000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream o = c.getOutputStream()) {
                    o.write(("geraet=" + Jellyfin.enc(Build.MODEL)).getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                c.disconnect();
                Log.i(TAG, "Waechter bittet HA, Lukas Kino zu holen (" + code + ")");
            } catch (Exception e) {
                Log.w(TAG, "Waechter: HA nicht erreichbar", e);
            }
        }, "ha-startseite").start();
    }

    private static synchronized String startseitenWebhook() throws Exception {
        if (haWebhook != null) return haWebhook;
        JSONObject j = new JSONObject(Jellyfin.holen(Familie.BASIS + "kino_geheim.json?t=" + System.currentTimeMillis()));
        String id = j.optString("startseiteWebhook", "");
        if (id.isEmpty()) throw new Exception("kein Startseiten-Webhook");
        haWebhook = HA + "/api/webhook/" + Jellyfin.enc(id);
        return haWebhook;
    }

    private boolean holen(String grund) {
        long jetzt = SystemClock.elapsedRealtime();
        if (jetzt - letzterStart < 700) return true;
        letzterStart = jetzt;
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            i.putExtra(EXTRA_HOME, true);
            startActivity(i);
            Log.i(TAG, "Waechter holt Lukas Kino (" + grund + ")");
            return true;
        } catch (Exception ex) {
            Log.w(TAG, "Waechter konnte Lukas Kino nicht starten (" + grund + ")", ex);
            return false;
        }
    }

    @Override
    public void onInterrupt() {
    }
}
