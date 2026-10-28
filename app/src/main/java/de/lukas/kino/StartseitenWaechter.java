package de.lukas.kino;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sanfte Startseite: Die Google-TV-Startseite bleibt installiert. Sobald sie aufgeht
 * (Home-Taste, Fernseher an, App beendet), holt dieser Dienst sofort Lukas Kino nach vorn.
 * Es wird kein Systempaket angefasst. Abschalten: Dienst in den Bedienungshilfen aus
 * oder per ADB aus enabled_accessibility_services entfernen.
 */
public class StartseitenWaechter extends AccessibilityService {

    private static final String GOOGLE_STARTSEITE = "com.google.android.apps.tv.launcherx";
    private static final String GOOGLE_HOME_ACTIVITY = "com.google.android.apps.tv.launcherx.home.HomeActivity";

    private long letzterStart = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence paket = e.getPackageName();
        CharSequence klasse = e.getClassName();
        if (paket == null || klasse == null) return;
        if (!GOOGLE_STARTSEITE.contentEquals(paket)) return;
        // Nur die eigentliche Startseite ersetzen, keine Einstellungs- oder Suchfenster.
        if (!GOOGLE_HOME_ACTIVITY.contentEquals(klasse)) return;

        long jetzt = SystemClock.elapsedRealtime();
        if (jetzt - letzterStart < 700) return;
        letzterStart = jetzt;

        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }
}
