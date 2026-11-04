package de.lukas.kino;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import org.json.JSONObject;

/**
 * Musik-Player und Karaoke (04.11.2026, Wunsch Lukas). Zeigt musik.html vom Pi (gleiche Seite wie am LG) in einer
 * WebView, die nur lebt, solange Musik läuft. Zugangsdaten gibt die App über die Brücke (nicht in der Adresse).
 * Tasten: OK Pause, links/rechts Lied, hoch Karaoke an/aus, runter Zufall, Zurück raus.
 */
public class MusikActivity extends Activity {

    static final String EXTRA_QUELLE = "quelle", EXTRA_START = "start", EXTRA_KARAOKE = "karaoke", EXTRA_ZUFALL = "zufall";

    private WebView web;
    private volatile boolean bereit;

    static void starten(Context c, String quelle, String start, boolean karaoke, boolean zufall) {
        Intent i = new Intent(c, MusikActivity.class);
        i.putExtra(EXTRA_QUELLE, quelle);
        i.putExtra(EXTRA_START, start);
        i.putExtra(EXTRA_KARAOKE, karaoke);
        i.putExtra(EXTRA_ZUFALL, zufall);
        c.startActivity(i);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout wurzel = new FrameLayout(this);
        wurzel.setBackgroundColor(0xFF07080B);
        web = new WebView(this);
        web.setBackgroundColor(0xFF07080B);
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bruecke(), "LukasKino");
        wurzel.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(wurzel);
        verstecken();
        web.loadUrl(Musik.WEB + "/web/musik.html?t=" + System.currentTimeMillis());
    }

    private void verstecken() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void taste(String n) {
        if (web != null) web.evaluateJavascript("window.LukasMusik&&LukasMusik.taste('" + n + "')", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() != KeyEvent.ACTION_DOWN) {
            return k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE
                    ? super.dispatchKeyEvent(e) : true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                taste("ok");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                taste("playpause");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                taste("play");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                taste("pause");
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (e.getRepeatCount() == 0) taste("links");
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (e.getRepeatCount() == 0) taste("rechts");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                taste("links");
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                taste("rechts");
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                taste("rueck");
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                taste("vor");
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                taste("hoch");
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                taste("runter");
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                // Seite noch nicht da (Pi weg?) -> sofort raus, sonst entscheidet die Seite (Karaoke -> Player -> raus)
                if (!bereit) finish();
                else taste("zurueck");
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                finish();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isFinishing()) aufraeumen();
        else taste("pause");
    }

    @Override
    protected void onDestroy() {
        aufraeumen();
        super.onDestroy();
    }

    private void aufraeumen() {
        if (web == null) return;
        WebView w = web;
        web = null;
        w.loadUrl("about:blank");
        w.removeAllViews();
        w.destroy();
    }

    /** Von musik.html aufgerufen. */
    public final class Bruecke {
        @JavascriptInterface
        public String daten() {
            JSONObject o = new JSONObject();
            try {
                Jellyfin jf = Jellyfin.get(MusikActivity.this);
                Intent i = getIntent();
                o.put("server", Jellyfin.SERVER);
                o.put("token", jf.token);
                o.put("user", jf.benutzer);
                o.put("geraet", Jellyfin.GERAET_ID);
                o.put("geraetName", Jellyfin.GERAET);
                o.put("quelle", i.getStringExtra(EXTRA_QUELLE) != null ? i.getStringExtra(EXTRA_QUELLE) : "alle");
                if (i.getStringExtra(EXTRA_START) != null) o.put("start", i.getStringExtra(EXTRA_START));
                o.put("karaoke", i.getBooleanExtra(EXTRA_KARAOKE, false) ? "1" : "0");
                o.put("zufall", i.getBooleanExtra(EXTRA_ZUFALL, false) ? "1" : "0");
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        @JavascriptInterface
        public void bereit() {
            bereit = true;
        }

        @JavascriptInterface
        public void ende() {
            runOnUiThread(MusikActivity.this::finish);
        }
    }
}
