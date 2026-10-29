package de.lukas.kino;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Einmaliger Umzug: Die alte Web-Oberfläche hat die Kachel-Reihenfolge im localStorage der WebView
 * gespeichert. Dieser Dienst läuft in einem eigenen Prozess, liest den Wert einmal aus, schreibt ihn
 * in eine Datei und beendet sich samt Prozess – die Startseite selbst braucht keine WebView mehr.
 */
public class UmzugDienst extends Service {

    static final String DATEI = "reihenfolge_umzug.json";
    static final String FERTIG = "de.lukas.kino.UMZUG_FERTIG";

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean erledigt = false;
    private WebView web;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        h.post(this::lesen);
        h.postDelayed(() -> fertig(""), 15000);
        return START_NOT_STICKY;
    }

    private void lesen() {
        try {
            web = new WebView(this);
            web.getSettings().setJavaScriptEnabled(true);
            web.getSettings().setDomStorageEnabled(true);
            web.addJavascriptInterface(new Antwort(), "LK");
            web.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                    Uri u = r.getUrl();
                    if (u == null || !"lukas.kino".equals(u.getHost())) return null;
                    String html = "<!doctype html><script>var r='';try{r=localStorage.getItem('lk.reihenfolge')||''}catch(e){}LK.fertig(r)</script>";
                    return new WebResourceResponse("text/html", "utf-8",
                            new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
                }
            });
            web.loadUrl("https://lukas.kino/umzug.html");
        } catch (Throwable t) {
            Log.w("LukasKino", "Umzug: WebView ging nicht", t);
            fertig("");
        }
    }

    public class Antwort {
        @JavascriptInterface
        public void fertig(String wert) {
            h.post(() -> UmzugDienst.this.fertig(wert));
        }
    }

    private void fertig(String wert) {
        if (erledigt) return;
        erledigt = true;
        try (FileOutputStream o = new FileOutputStream(new File(getFilesDir(), DATEI))) {
            o.write((wert == null ? "" : wert).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w("LukasKino", "Umzug: Datei ging nicht", e);
        }
        Log.i("LukasKino", "Umzug Reihenfolge: " + (wert == null || wert.isEmpty() ? "(keine)" : wert.length() + " Zeichen"));
        sendBroadcast(new Intent(FERTIG).setPackage(getPackageName()));
        try {
            if (web != null) web.destroy();
        } catch (Exception ignored) {
        }
        stopSelf();
        // Eigener Prozess (:umzug) – danach komplett weg, damit kein Browser im Speicher bleibt.
        h.postDelayed(() -> Process.killProcess(Process.myPid()), 500);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
