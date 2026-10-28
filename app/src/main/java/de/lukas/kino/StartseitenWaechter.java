package de.lukas.kino;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sanfte Startseite: Die Google-TV-Startseite bleibt installiert. Dieser Dienst
 *  1. fängt die Home-Taste ab und öffnet direkt Lukas Kino (Google blitzt dann nicht auf),
 *  2. holt Lukas Kino nach vorn, falls die Google-Startseite trotzdem aufgeht
 *     (z. B. eine App beendet sich selbst),
 *  3. holt Lukas Kino einmal nach dem Hochfahren des Fernsehers.
 * Es wird kein Systempaket angefasst. Abschalten: Dienst in den Bedienungshilfen aus
 * oder per ADB aus enabled_accessibility_services entfernen.
 */
public class StartseitenWaechter extends AccessibilityService {

    /** Extra im Intent: Lukas Kino soll an den Anfang springen (wie Home). */
    public static final String EXTRA_HOME = "de.lukas.kino.HOME";

    private static final String TAG = "LukasKino";
    private static final String GOOGLE_STARTSEITE = "com.google.android.apps.tv.launcherx";
    private static final String GOOGLE_HOME_ACTIVITY = "com.google.android.apps.tv.launcherx.home.HomeActivity";
    /** So lange nach dem Hochfahren gilt das Verbinden des Dienstes als „Fernseher ist gerade gestartet“. */
    private static final long BOOT_FENSTER_MS = 5 * 60 * 1000;

    private long letzterStart = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        long seitStart = SystemClock.elapsedRealtime();
        Log.i(TAG, "Waechter verbunden, " + (seitStart / 1000) + " s nach dem Hochfahren");
        if (seitStart < BOOT_FENSTER_MS) holen("hochgefahren");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e == null) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            Log.i(TAG, "Waechter Taste " + KeyEvent.keyCodeToString(e.getKeyCode()));
        }
        // Einstellungstaste: Das Google-Seitenmenü gibt es nicht mehr, also die Einstellungen-App öffnen.
        if (e.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) {
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence paket = e.getPackageName();
        CharSequence klasse = e.getClassName();
        if (paket == null || klasse == null) return;
        if (!GOOGLE_STARTSEITE.contentEquals(paket)) return;
        // Nur die eigentliche Startseite ersetzen, keine Einstellungs- oder Suchfenster.
        if (!GOOGLE_HOME_ACTIVITY.contentEquals(klasse)) return;
        holen("google-startseite");
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
