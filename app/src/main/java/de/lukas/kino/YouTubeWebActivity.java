package de.lukas.kino;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * YouTube als Webseite (youtube.com/tv, die Fernseh-Oberfläche von YouTube) direkt in Lukas Kino –
 * Wunsch Lukas 03.11.2026: erst mal ausprobieren, wie es aussieht; später kann der Eintrag auf die App umgestellt werden.
 *
 * youtube.com/tv zeigt die Fernseh-Oberfläche nur Fernsehern und Konsolen, deshalb meldet sich die WebView mit einer
 * Fernseher-Kennung. Am 03.11.2026 vom Pi aus geprüft: Tizen, webOS, PS4 und Cobalt bekommen alle die TV-Seite.
 * Leitet YouTube trotzdem woandershin um, ist die nächste Kennung dran; geht keine, öffnet sich die YouTube-App.
 *
 * Tasten: Pfeile und OK gehen an die Seite. Zurück = Escape (in der YouTube-Oberfläche „zurück“),
 * Zurück gedrückt halten = raus aus YouTube. Beendet YouTube sich selbst (Beenden-Dialog), geht es auch raus.
 */
public class YouTubeWebActivity extends Activity {

    private static final String TAG = "LukasKino";
    static final String START = "https://www.youtube.com/tv";
    static final String APP = "com.google.android.youtube.tv";

    static final String[] KENNUNGEN = {
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) 76.0.3809.146/6.0 TV Safari/537.36",
            "Mozilla/5.0 (Web0S; Linux/SmartTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.128 Safari/537.36 WebAppManager",
            "Mozilla/5.0 (PS4; Leanback Shell) Gecko/20100101 Firefox/65.0 LeanbackShell/01.00.01.75 Sony PS4/ (PS4, , no, CH)",
            "Mozilla/5.0 (Linux; Android 12) Cobalt/22.2.3-gold (PS4)",
    };

    private WebView web;
    private TextView laden;
    private SharedPreferences prefs;
    private int kennung;
    private long zurueckSeit;
    private boolean raus, geladen;

    static void starten(Context c) {
        c.startActivity(new Intent(c, YouTubeWebActivity.class));
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("lukaskino", MODE_PRIVATE);
        kennung = Math.max(0, Math.min(KENNUNGEN.length - 1, prefs.getInt("yt_kennung", 0)));

        FrameLayout wurzel = new FrameLayout(this);
        wurzel.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setUserAgentString(KENNUNGEN[kennung]);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Bruecke(), "LukasKino");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView w) {
                raus();
            }
        });
        web.setWebViewClient(new Client());
        wurzel.addView(web, new FrameLayout.LayoutParams(-1, -1));

        // Solange die Seite lädt: nur ein ruhiges „YouTube“ in der Mitte
        laden = new TextView(this);
        laden.setText("YouTube");
        laden.setTextColor(0x99FFFFFF);
        laden.setTextSize(28);
        laden.setTypeface(Schriften.gewicht(this, 650));
        laden.setGravity(Gravity.CENTER);
        laden.setBackgroundColor(Color.BLACK);
        wurzel.addView(laden, new FrameLayout.LayoutParams(-1, -1));

        setContentView(wurzel);
        verstecken();
        web.requestFocus();
        Log.i(TAG, "YouTube Web startet mit Kennung " + kennung);
        web.loadUrl(START);
    }

    private void verstecken() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private final class Client extends WebViewClient {
        @Override
        public void onPageStarted(WebView v, String url, Bitmap icon) {
            if (keineTvSeite(url)) naechsteKennung(url);
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            // Der Beenden-Dialog der TV-Oberfläche schließt das Fenster – das führt zurück ins Kino.
            v.evaluateJavascript("try{window.close=function(){LukasKino.schliessen()}}catch(e){}", null);
            if (!geladen && !keineTvSeite(url)) {
                geladen = true;
                laden.animate().alpha(0f).setDuration(350).withEndAction(() -> laden.setVisibility(View.GONE)).start();
                prefs.edit().putInt("yt_kennung", kennung).apply();
                Log.i(TAG, "YouTube Web geladen (Kennung " + kennung + "): " + url);
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            // Ohne das würde ein abgestürzter Seiten-Prozess die ganze Startseite mitreißen.
            Log.w(TAG, "YouTube Web: Seiten-Prozess weg, zurück ins Kino");
            weg();
            finish();
            return true;
        }
    }

    /** Hat YouTube uns von der Fernseh-Oberfläche weg auf die normale Seite geschickt? */
    private static boolean keineTvSeite(String url) {
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost() == null ? "" : u.getHost();
            if (!(host.equals("youtube.com") || host.equals("www.youtube.com") || host.equals("m.youtube.com"))) return false;
            String pfad = u.getPath() == null ? "" : u.getPath();
            return !pfad.startsWith("/tv");
        } catch (Exception e) {
            return false;
        }
    }

    private void naechsteKennung(String url) {
        if (geladen) return;
        Log.w(TAG, "YouTube Web: Kennung " + kennung + " abgelehnt (" + url + ")");
        if (kennung + 1 < KENNUNGEN.length) {
            kennung++;
            web.stopLoading();
            web.getSettings().setUserAgentString(KENNUNGEN[kennung]);
            web.post(() -> web.loadUrl(START));
        } else {
            // Keine Kennung klappt mehr: dann eben die echte App
            prefs.edit().putInt("yt_kennung", 0).apply();
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(APP);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(i);
                } catch (Exception ignored) {
                }
            }
            raus();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK) {
            long jetzt = SystemClock.uptimeMillis();
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) zurueckSeit = jetzt;
                else if (!raus && jetzt - zurueckSeit > 700) raus(); // gehalten = raus aus YouTube
            } else if (e.getAction() == KeyEvent.ACTION_UP && !raus && !e.isCanceled()) {
                if (!geladen) raus();
                else taste(KeyEvent.KEYCODE_ESCAPE); // in der Seite eine Ebene zurück
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void taste(int code) {
        if (web == null) return;
        long t = SystemClock.uptimeMillis();
        web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    private void raus() {
        if (raus) return;
        raus = true;
        finish();
    }

    private final class Bruecke {
        @JavascriptInterface
        public void schliessen() {
            runOnUiThread(YouTubeWebActivity.this::raus);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        verstecken();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause(); // Video und Seite ruhen, solange YouTube nicht vorne ist
        CookieManager.getInstance().flush(); // Anmeldung bleibt erhalten
    }

    private void weg() {
        if (web == null) return;
        try {
            ((FrameLayout) web.getParent()).removeView(web);
        } catch (Exception ignored) {
        }
        web.destroy();
        web = null;
    }

    @Override
    protected void onDestroy() {
        weg(); // WebView ganz freigeben, damit der Fernseher den Speicher zurückbekommt
        super.onDestroy();
    }
}
