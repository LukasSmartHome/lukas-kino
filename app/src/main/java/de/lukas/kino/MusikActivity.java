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

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Der ganze Musik-Bereich mit Karaoke (seit 04.11.2026, Wunsch Lukas). Zeigt musik.html vom Pi (gleiche Seite und
 * gleiches musik.js wie am LG) in einer WebView: Startseite mit Karaoke-Modus, Gelikten Songs und Genres, Künstler,
 * Suche, Player und Karaoke. Links hat die Seite eine eigene Leiste im Aussehen der App; wählt man dort etwas anderes,
 * endet die Musik und FilmeActivity öffnet das Gewählte (nachMusik). Zugangsdaten gibt die App über die Brücke.
 */
public class MusikActivity extends Activity {

    /** Kleiner Webserver auf dem Pi (App „Lukas Kino Web“, Ordner /share/kino): Musik-Seite und Karaoke-Texte. */
    static final String WEB = "http://192.168.178.44:8098";

    private WebView web;
    private volatile boolean bereit;

    static void starten(Context c) {
        FilmeActivity.nachMusik = -1;
        c.startActivity(new Intent(c, MusikActivity.class));
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
        // Zwischenspeicher an (seit 04.11.2026 abends): vorher wurden Schrift, Cover und Künstlerbilder bei jedem Öffnen
        // neu geladen – darum dauerte Musik so lange. musik.html und musik.js kommen trotzdem immer frisch (?t= in der Adresse).
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bruecke(), "LukasKino");
        wurzel.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(wurzel);
        verstecken();
        web.loadUrl(WEB + "/web/musik.html?t=" + System.currentTimeMillis());
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
                taste("links");
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                taste("rechts");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                taste("rueck");
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                taste("vor");
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
                // Seite noch nicht da (Pi weg?) -> sofort raus, sonst entscheidet die Seite (Player -> Stöbern -> Leiste)
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
                o.put("server", Jellyfin.SERVER);
                o.put("token", jf.token);
                o.put("user", jf.benutzer);
                o.put("geraet", Jellyfin.GERAET_ID);
                o.put("geraetName", Jellyfin.GERAET);
                JSONArray l = new JSONArray();
                for (String n : FilmeActivity.LEISTE) l.put(n);
                o.put("leiste", l);
                o.put("apps", FilmeActivity.APPS);
                o.put("musikPos", FilmeActivity.MUSIK_POS);
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

        /** In der Leiste der Musik-Seite etwas anderes gewählt (Musik ist schon aus). */
        @JavascriptInterface
        public void verlassen(int pos) {
            FilmeActivity.nachMusik = pos;
            runOnUiThread(MusikActivity.this::finish);
        }
    }
}
