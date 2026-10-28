package de.lukas.kino;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lukas Kino – eigene Startseite für den TCL-Fernseher.
 * Die Oberfläche ist eine Webseite (assets/index.html), diese Klasse liefert ihr
 * die installierten Apps, deren Bilder, die Hintergründe und die Tasten der Fernbedienung.
 */
public class MainActivity extends Activity {

    private static final String HOST = "lukas.kino";

    private WebView web;
    private boolean langGedrueckt = false;
    private final Map<String, byte[]> bildCache = new HashMap<>();
    private BroadcastReceiver bildschirmAn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vollbild();

        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        // Die Seite ist fest 1920 breit; die WebView zoomt sie genau auf Bildschirmbreite.
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i("LukasKino", m.message());
                return true;
            }
        });
        web.addJavascriptInterface(new Bruecke(), "LK");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u == null || !HOST.equals(u.getHost())) return null;
                return liefern(u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }
        });
        setContentView(web);
        web.loadUrl("https://" + HOST + "/index.html");

        bildschirmAn = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                js("window.lk&&lk.einschalten()");
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bildschirmAn, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(bildschirmAn, f);
        }
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
        js("window.lk&&lk.wiederda()");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        js("window.lk&&lk.wiederda()");
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(bildschirmAn);
        } catch (Exception ignored) {
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private void js(final String code) {
        if (web == null) return;
        web.post(() -> web.evaluateJavascript(code, null));
    }

    // ---------- Tasten der Fernbedienung ----------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        String richtung = null;
        if (k == KeyEvent.KEYCODE_DPAD_UP) richtung = "hoch";
        else if (k == KeyEvent.KEYCODE_DPAD_DOWN) richtung = "runter";
        else if (k == KeyEvent.KEYCODE_DPAD_LEFT) richtung = "links";
        else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) richtung = "rechts";

        if (richtung != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) js("window.lk&&lk.taste('" + richtung + "')");
            return true;
        }

        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    langGedrueckt = false;
                } else if (!langGedrueckt) {
                    langGedrueckt = true;
                    js("window.lk&&lk.halten()");
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                if (!langGedrueckt) js("window.lk&&lk.ok()");
                langGedrueckt = false;
            }
            return true;
        }

        if (k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) js("window.lk&&lk.zurueck()");
            return true;
        }

        return super.dispatchKeyEvent(e);
    }

    // ---------- Dateien für die Oberfläche ----------

    private File hintergrundOrdner() {
        File basis = getExternalFilesDir(null);
        if (basis == null) basis = getFilesDir();
        File d = new File(basis, "hintergruende");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private WebResourceResponse liefern(Uri u) {
        String pfad = u.getPath() == null ? "/" : u.getPath();
        try {
            if (pfad.equals("/") || pfad.equals("/index.html")) {
                return antwort("text/html", getAssets().open("index.html"), "no-cache");
            }
            if (pfad.startsWith("/banner/") || pfad.startsWith("/icon/")) {
                boolean banner = pfad.startsWith("/banner/");
                String pkg = Uri.decode(pfad.substring(banner ? 8 : 6));
                byte[] b = bild(pkg, banner);
                if (b != null) return antwort("image/png", new ByteArrayInputStream(b), "max-age=86400");
            }
            if (pfad.startsWith("/bg/")) {
                String name = new File(Uri.decode(pfad.substring(4))).getName();
                File f = new File(hintergrundOrdner(), name);
                if (f.isFile()) return antwort(mime(name), new FileInputStream(f), "max-age=86400");
            }
        } catch (Exception ignored) {
        }
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    private WebResourceResponse antwort(String mime, InputStream in, String cache) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", cache);
        h.put("Access-Control-Allow-Origin", "*");
        return new WebResourceResponse(mime, mime.startsWith("text") ? "utf-8" : null, 200, "OK", h, in);
    }

    private static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private byte[] bild(String pkg, boolean banner) {
        String key = (banner ? "b:" : "i:") + pkg;
        synchronized (bildCache) {
            if (bildCache.containsKey(key)) return bildCache.get(key);
        }
        PackageManager pm = getPackageManager();
        Drawable d = null;
        try {
            if (banner) {
                Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (i != null && i.getComponent() != null) {
                    d = pm.getActivityBanner(i.getComponent());
                }
                if (d == null) d = pm.getApplicationBanner(pkg);
            } else {
                d = pm.getApplicationIcon(pkg);
            }
        } catch (Exception ignored) {
        }
        byte[] b = d == null ? null : png(d, banner ? 640 : 256, banner ? 360 : 256);
        synchronized (bildCache) {
            bildCache.put(key, b);
        }
        return b;
    }

    private static byte[] png(Drawable d, int w, int h) {
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 100, o);
        bm.recycle();
        return o.toByteArray();
    }

    // ---------- Brücke für die Oberfläche (window.LK) ----------

    public class Bruecke {

        @JavascriptInterface
        public String apps() {
            PackageManager pm = getPackageManager();
            JSONArray arr = new JSONArray();
            Set<String> gesehen = new HashSet<>();
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<ResolveInfo> liste = pm.queryIntentActivities(i, 0);
            for (ResolveInfo ri : liste) {
                String pkg = ri.activityInfo.packageName;
                if (gesehen.contains(pkg) || pkg.equals(getPackageName())) continue;
                gesehen.add(pkg);
                try {
                    boolean hatBanner = false;
                    try {
                        hatBanner = pm.getActivityBanner(new ComponentName(pkg, ri.activityInfo.name)) != null
                                || pm.getApplicationBanner(pkg) != null;
                    } catch (Exception ignored) {
                    }
                    JSONObject o = new JSONObject();
                    o.put("pkg", pkg);
                    o.put("name", String.valueOf(ri.loadLabel(pm)));
                    o.put("banner", hatBanner);
                    arr.put(o);
                } catch (Exception ignored) {
                }
            }
            return arr.toString();
        }

        @JavascriptInterface
        public boolean starten(final String pkg) {
            final PackageManager pm = getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            final Intent start = i;
            start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            runOnUiThread(() -> {
                try {
                    startActivity(start);
                } catch (Exception ignored) {
                }
            });
            return true;
        }

        @JavascriptInterface
        public String eingaenge() {
            JSONArray arr = new JSONArray();
            try {
                TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
                for (TvInputInfo t : tim.getTvInputList()) {
                    JSONObject o = new JSONObject();
                    o.put("id", t.getId());
                    o.put("name", String.valueOf(t.loadLabel(MainActivity.this)));
                    o.put("typ", t.getType());
                    o.put("eltern", t.getParentId() == null ? "" : t.getParentId());
                    arr.put(o);
                }
            } catch (Exception ignored) {
            }
            return arr.toString();
        }

        @JavascriptInterface
        public boolean hdmi(int nummer) {
            try {
                TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
                if (tim == null) return false;
                List<TvInputInfo> hdmi = new ArrayList<>();
                for (TvInputInfo t : tim.getTvInputList()) {
                    if (t.getType() == TvInputInfo.TYPE_HDMI && t.getParentId() == null) hdmi.add(t);
                }
                TvInputInfo treffer = null;
                String nr = String.valueOf(nummer);
                for (TvInputInfo t : hdmi) {
                    String name = String.valueOf(t.loadLabel(MainActivity.this)).replace(" ", "");
                    if (name.toUpperCase(Locale.ROOT).endsWith("HDMI" + nr) || name.endsWith(nr)) {
                        treffer = t;
                        break;
                    }
                }
                if (treffer == null && hdmi.size() >= nummer) treffer = hdmi.get(nummer - 1);
                if (treffer == null) return false;
                final Uri uri = TvContract.buildChannelUriForPassthroughInput(treffer.getId());
                runOnUiThread(() -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, uri);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception ignored) {
                    }
                });
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void suche() {
            runOnUiThread(() -> {
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
            });
        }

        @JavascriptInterface
        public String hintergruende() {
            JSONArray arr = new JSONArray();
            File[] dateien = hintergrundOrdner().listFiles();
            if (dateien != null) {
                Arrays.sort(dateien);
                for (File f : dateien) {
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) {
                        arr.put(f.getName());
                    }
                }
            }
            return arr.toString();
        }

        @JavascriptInterface
        public boolean istStartseite() {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_HOME);
                ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
                return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void beenden() {
            runOnUiThread(MainActivity.this::finish);
        }

        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "?";
            }
        }
    }
}
