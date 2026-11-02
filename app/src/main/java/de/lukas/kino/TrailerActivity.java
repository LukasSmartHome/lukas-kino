package de.lukas.kino;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

/**
 * Trailer im Vollbild in der App: YouTube-IFrame-Player in einer WebView, die nur für die Dauer des Trailers lebt
 * (danach wird sie zerstört, damit der Fernseher den Speicher zurückbekommt).
 * Bedienung wie im Film-Player: OK = Pause/Weiter, links/rechts = 10 Sekunden, Zurück = raus.
 * Lässt sich der Trailer nicht einbetten, öffnet er sich in der YouTube-App.
 */
public class TrailerActivity extends Activity {

    static final String EXTRA_YT = "yt";
    static final String EXTRA_TITEL = "titel";

    private WebView web;
    private String yt;

    static void starten(Context c, String yt, String titel) {
        Intent i = new Intent(c, TrailerActivity.class);
        i.putExtra(EXTRA_YT, yt);
        i.putExtra(EXTRA_TITEL, titel);
        c.startActivity(i);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        yt = getIntent().getStringExtra(EXTRA_YT);
        String titel = getIntent().getStringExtra(EXTRA_TITEL);
        if (yt == null || !yt.matches("[A-Za-z0-9_-]{11}")) {
            finish();
            return;
        }
        FrameLayout wurzel = new FrameLayout(this);
        wurzel.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false); // Größe macht die Seite selbst (passen()), sonst doppelt gezoomt
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bruecke(), "LukasKino");
        wurzel.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(wurzel);
        verstecken();
        // Basis-Adresse = Paketname: YouTube verlangt bei eingebetteten Playern eine Herkunft (Referer)
        web.loadDataWithBaseURL("https://de.lukas.kino/", seite(yt, titel == null ? "" : titel), "text/html", "utf-8", null);
    }

    private void verstecken() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                js("umschalten()");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                js("spielen()");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                js("pausieren()");
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                js("spulen(-10)");
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                js("spulen(10)");
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                js("zeigen(3000)");
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
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
        js("pausieren()");
        if (isFinishing()) aufraeumen();
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

    /** Vom Trailer-JavaScript aufgerufen. */
    public final class Bruecke {
        @JavascriptInterface
        public void ende() {
            runOnUiThread(TrailerActivity.this::finish);
        }

        @JavascriptInterface
        public void fehler(String code) {
            runOnUiThread(() -> {
                youtubeApp();
                finish();
            });
        }
    }

    /** Ausweg: Trailer in der YouTube-App öffnen. */
    private void youtubeApp() {
        Uri u = Uri.parse("https://www.youtube.com/watch?v=" + yt);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u).setPackage("com.google.android.youtube.tv"));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception ignored) {
                // kein YouTube da
            }
        }
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Die kleine Trailer-Seite: Player plus Leiste im Stil des Film-Players (gelber Fortschritt). */
    private static String seite(String yt, String titel) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1, user-scalable=no'>"
                + "<style>"
                + "html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden;color:#fff;"
                + "font-family:sans-serif;-webkit-font-smoothing:antialiased}"
                + "#w{position:absolute;left:0;top:0;width:1920px;height:1080px;overflow:hidden;transform-origin:0 0}"
                + "#p,#p iframe{position:absolute;left:0;top:0;width:1920px;height:1080px;border:0;pointer-events:none}"
                + "#l{position:absolute;left:0;top:0;width:1920px;height:1080px;opacity:0;transition:opacity .3s}"
                + "#l.an{opacity:1}"
                + "#o{position:absolute;left:0;top:0;width:1920px;height:260px;background:linear-gradient(#000a,#0000)}"
                + "#u{position:absolute;left:0;top:640px;width:1920px;height:440px;background:linear-gradient(#0000,#000d)}"
                + "#m{position:absolute;left:110px;top:52px;font-size:26px;font-weight:700;color:#F2B705;letter-spacing:.04em}"
                + "#t{position:absolute;left:110px;top:92px;width:1600px;font-size:44px;font-weight:800;white-space:nowrap;"
                + "overflow:hidden;text-overflow:ellipsis;text-shadow:0 2px 12px #0009}"
                + "#b{position:absolute;left:110px;top:946px;width:1700px;height:8px;border-radius:4px;background:#fff4}"
                + "#f{position:absolute;left:0;top:0;height:8px;border-radius:4px;background:#F2B705}"
                + "#k{position:absolute;top:-6px;width:20px;height:20px;margin-left:-10px;border-radius:50%;background:#fff}"
                + "#z,#r{position:absolute;top:980px;font-size:30px;color:#fffe}#z{left:110px}#r{right:110px}"
                + "#s{position:absolute;left:888px;top:468px;width:144px;height:144px;border-radius:50%;background:#0009;display:none}"
                + "#s:before,#s:after{content:'';position:absolute;top:37px;width:18px;height:70px;border-radius:5px;background:#fff}"
                + "#s:before{left:42px}#s:after{left:84px}"
                + "#n{position:absolute;left:0;top:640px;width:1920px;text-align:center;font-size:36px;font-weight:800}"
                + "</style></head><body>"
                + "<div id='w'><div id='p'><div id='y'></div></div>"
                + "<div id='l' class='an'><div id='o'></div><div id='u'></div><div id='m'>TRAILER</div>"
                + "<div id='t'>" + html(titel) + "</div><div id='b'><div id='f'></div><div id='k'></div></div>"
                + "<div id='z'></div><div id='r'></div><div id='s'></div><div id='n'>Trailer wird geladen …</div></div></div>"
                + "<script>"
                + "var P=null,bis=0,ziel=-1;"
                + "function $(i){return document.getElementById(i)}"
                + "function passen(){var W=window.innerWidth,H=window.innerHeight;if(!W||!H)return;var k=Math.min(W/1920,H/1080),w=$('w');"
                + "w.style.transform='scale('+k+')';w.style.left=Math.round((W-1920*k)/2)+'px';w.style.top=Math.round((H-1080*k)/2)+'px'}"
                + "window.addEventListener('resize',passen);passen();"
                + "function zeit(s){s=Math.max(0,Math.floor(s||0));var m=Math.floor(s/60),x=s%60;return m+':'+(x<10?'0':'')+x}"
                + "function zeigen(ms){bis=ms<=0?1e15:Date.now()+ms;$('l').className='an'}"
                + "function st(){try{return P&&P.getPlayerState?P.getPlayerState():-1}catch(e){return -1}}"
                + "function umschalten(){if(!P)return;if(st()==1)P.pauseVideo();else P.playVideo();zeigen(3000)}"
                + "function spielen(){if(P)P.playVideo()}"
                + "function pausieren(){try{if(P)P.pauseVideo()}catch(e){}}"
                + "function spulen(d){if(!P||!P.getDuration)return;var g=P.getDuration()||0;"
                + "ziel=Math.max(0,Math.min(g-1,(ziel>=0?ziel:P.getCurrentTime())+d));P.seekTo(ziel,true);"
                + "setTimeout(function(){ziel=-1},800);zeigen(3000)}"
                + "setInterval(function(){passen();var g=0,p=0;try{if(P&&P.getDuration){g=P.getDuration()||0;p=ziel>=0?ziel:P.getCurrentTime()||0}}catch(e){}"
                + "var a=g>0?Math.min(1,p/g):0;$('f').style.width=(a*1700)+'px';$('k').style.left=(a*1700)+'px';"
                + "$('z').textContent=g>0?zeit(p):'';$('r').textContent=g>0?'\\u2212'+zeit(g-p):'';"
                + "$('s').style.display=st()==2?'block':'none';"
                + "if(Date.now()>bis&&st()==1&&!$('n').textContent)$('l').className=''},250);"
                + "setTimeout(function(){if(!P){$('n').textContent='Öffne YouTube …';LukasKino.fehler('laden')}},15000);"
                + "function onYouTubeIframeAPIReady(){P=new YT.Player('y',{width:1920,height:1080,videoId:'" + yt + "',"
                + "playerVars:{autoplay:1,controls:0,disablekb:1,fs:0,rel:0,iv_load_policy:3,modestbranding:1,playsinline:1,hl:'de',"
                + "cc_load_policy:0,origin:'https://de.lukas.kino'},events:{"
                + "onReady:function(e){e.target.playVideo()},"
                + "onStateChange:function(e){if(e.data==0)LukasKino.ende();else if(e.data==1){$('n').textContent=''}"
                + "else if(e.data==2)zeigen(0)},"
                + "onError:function(e){$('n').textContent='Öffne YouTube …';LukasKino.fehler(String(e.data))}}})}"
                + "</script><script src='https://www.youtube.com/iframe_api'></script>"
                + "</body></html>";
    }
}
