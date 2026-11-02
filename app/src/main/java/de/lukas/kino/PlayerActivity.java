package de.lukas.kino;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Eigener Film-Player von Lukas Kino: spielt die Originaldatei direkt (Direct Play) vom Jellyfin-Server.
 * Bedienung: OK = Pause/Weiter, links/rechts = spulen (gedrueckt halten = schneller), hoch = Ton und Untertitel,
 * Zurueck = Menue zu bzw. beenden. Fortschritt geht alle 10 s an Jellyfin.
 */
public class PlayerActivity extends Activity {

    static final String EXTRA_ID = "id";
    static final String EXTRA_TITEL = "titel";
    static final String EXTRA_START = "start_ticks";
    static final String EXTRA_LOGO = "logo";

    private static final long TICKS_PRO_MS = 10_000L;

    private Jellyfin jf;
    private ExoPlayer player;
    private PlayerView ansicht;
    private Leiste leiste;
    private String id, quelle, titel, logoUrl;
    private final String sitzung = UUID.randomUUID().toString().replace("-", "");
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean gemeldet;

    static void starten(Context c, String id, String titel, long startTicks, String logoUrl) {
        Intent i = new Intent(c, PlayerActivity.class);
        i.putExtra(EXTRA_ID, id);
        i.putExtra(EXTRA_TITEL, titel);
        i.putExtra(EXTRA_START, startTicks);
        i.putExtra(EXTRA_LOGO, logoUrl);
        c.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        jf = Jellyfin.get(this);
        id = getIntent().getStringExtra(EXTRA_ID);
        quelle = id;
        titel = getIntent().getStringExtra(EXTRA_TITEL);
        logoUrl = getIntent().getStringExtra(EXTRA_LOGO);
        long startTicks = getIntent().getLongExtra(EXTRA_START, 0);

        FrameLayout wurzel = new FrameLayout(this);
        wurzel.setBackgroundColor(0xFF000000);
        ansicht = new PlayerView(this);
        ansicht.setUseController(false);
        ansicht.setKeepContentOnPlayerReset(true);
        ansicht.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        ansicht.setShutterBackgroundColor(0xFF000000);
        if (ansicht.getSubtitleView() != null) {
            ansicht.getSubtitleView().setStyle(new CaptionStyleCompat(0xFFFFFFFF, 0x00000000, 0x00000000,
                    CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, 0xCC000000, Typeface.create(Typeface.DEFAULT, Typeface.BOLD)));
            ansicht.getSubtitleView().setFractionalTextSize(0.055f);
            ansicht.getSubtitleView().setBottomPaddingFraction(0.07f);
        }
        wurzel.addView(ansicht, new FrameLayout.LayoutParams(-1, -1));
        leiste = new Leiste(this);
        wurzel.addView(leiste, new FrameLayout.LayoutParams(-1, -1));
        setContentView(wurzel);
        verstecken();

        DefaultRenderersFactory renderer = new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(Collections.singletonMap("Authorization", jf.authHeader()));
        player = new ExoPlayer.Builder(this, renderer)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build();
        TrackSelectionParameters p = player.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguages("de", "deu", "ger")
                .setPreferredTextLanguages("de", "deu", "ger")
                .setSelectUndeterminedTextLanguage(false)
                .build();
        player.setTrackSelectionParameters(p);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && !gemeldet) {
                    gemeldet = true;
                    melde(0);
                }
                if (state == Player.STATE_ENDED) {
                    melde(2);
                    finish();
                }
                leiste.invalidate();
            }

            @Override
            public void onIsPlayingChanged(boolean spielt) {
                leiste.pauseSymbol(!spielt);
                if (!spielt) leiste.zeigen(0);
                else leiste.zeigen(2500);
                if (gemeldet) melde(1);
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                leiste.fehler("Abspielen ging nicht: " + e.getErrorCodeName());
            }
        });
        ansicht.setPlayer(player);
        player.setMediaItem(new MediaItem.Builder().setUri(jf.streamUrl(id, quelle)).setMediaId(id).build(),
                startTicks / TICKS_PRO_MS);
        player.prepare();
        player.setPlayWhenReady(true);
        leiste.zeigen(4000);
        h.postDelayed(fortschritt, 10_000);
    }

    private final Runnable fortschritt = new Runnable() {
        @Override
        public void run() {
            if (player != null && gemeldet) melde(1);
            h.postDelayed(this, 10_000);
        }
    };

    /** 0 = Start, 1 = Fortschritt, 2 = Stopp */
    private void melde(int art) {
        if (player == null) return;
        final long ticks = player.getCurrentPosition() * TICKS_PRO_MS;
        final boolean pause = !player.isPlaying();
        new Thread(() -> {
            if (art == 0) jf.meldeStart(id, quelle, ticks, sitzung);
            else if (art == 1) jf.meldeFortschritt(id, quelle, ticks, pause, sitzung);
            else jf.meldeStopp(id, quelle, ticks, sitzung);
        }).start();
    }

    private void verstecken() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) {
            melde(2);
            player.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        verstecken();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ Tasten

    private long spulZiel = -1;
    private int spulSchritte;
    private long letzteSpulTaste;
    private final Runnable spulAusfuehren = () -> {
        if (player != null && spulZiel >= 0) {
            player.seekTo(spulZiel);
            melde(1);
        }
        spulZiel = -1;
        spulSchritte = 0;
        leiste.invalidate();
    };

    private void spulen(int richtung) {
        if (player == null) return;
        long jetzt = SystemClock.uptimeMillis();
        if (jetzt - letzteSpulTaste > 700) spulSchritte = 0;
        letzteSpulTaste = jetzt;
        spulSchritte++;
        long schritt = spulSchritte < 4 ? 10_000 : spulSchritte < 10 ? 30_000 : spulSchritte < 20 ? 60_000 : 180_000;
        long basis = spulZiel >= 0 ? spulZiel : player.getCurrentPosition();
        long dauer = Math.max(0, player.getDuration());
        spulZiel = Math.max(0, Math.min(dauer > 0 ? dauer - 2000 : Long.MAX_VALUE, basis + richtung * schritt));
        leiste.zeigen(3000);
        h.removeCallbacks(spulAusfuehren);
        h.postDelayed(spulAusfuehren, 650);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        if (leiste.menueOffen()) {
            if (leiste.menueTaste(k)) return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (spulZiel >= 0) {
                    h.removeCallbacks(spulAusfuehren);
                    spulAusfuehren.run();
                    return true;
                }
                if (player.isPlaying()) player.pause();
                else player.play();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                player.play();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                player.pause();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                spulen(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                spulen(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_CAPTIONS:
                leiste.menueAuf();
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (spulZiel >= 0) {
                    h.removeCallbacks(spulAusfuehren);
                    spulZiel = -1;
                    spulSchritte = 0;
                    leiste.invalidate();
                    return true;
                }
                melde(2);
                finish();
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                melde(2);
                finish();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    // ------------------------------------------------------------------ Bedienleiste (eigene Zeichnung)

    private static final int GELB = 0xFFF2B705;

    private final class Leiste extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path pfad = new Path();
        private float sichtbar = 0f, ziel = 0f;
        private long bisWann = 0;
        private boolean pause;
        private String meldung;
        private Bitmap logo;
        // Menue Ton/Untertitel
        private boolean menue;
        private int spalte; // 0 Ton, 1 Untertitel
        private int zeile;
        private final List<Eintrag> ton = new ArrayList<>(), unter = new ArrayList<>();
        private final Typeface schrift, schriftFett;

        Leiste(Context c) {
            super(c);
            schrift = Schriften.gewicht(c, 500);
            schriftFett = Schriften.gewicht(c, 800);
            if (logoUrl != null) {
                Bilder.get(jf).laden(logoUrl, (u, b) -> {
                    logo = b;
                    invalidate();
                });
            }
        }

        void zeigen(long ms) {
            ziel = 1f;
            bisWann = ms <= 0 ? Long.MAX_VALUE : SystemClock.uptimeMillis() + ms;
            invalidate();
        }

        void pauseSymbol(boolean p) {
            pause = p;
            invalidate();
        }

        void fehler(String m) {
            meldung = m;
            zeigen(0);
        }

        boolean menueOffen() {
            return menue;
        }

        void menueAuf() {
            ton.clear();
            unter.clear();
            unter.add(new Eintrag("Aus", null, -1, player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || !textAktiv()));
            for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
                for (int i = 0; i < g.length; i++) {
                    if (!g.isTrackSupported(i)) continue;
                    Format f = g.getTrackFormat(i);
                    if (g.getType() == C.TRACK_TYPE_AUDIO) ton.add(new Eintrag(name(f, true), g, i, g.isTrackSelected(i)));
                    else if (g.getType() == C.TRACK_TYPE_TEXT) unter.add(new Eintrag(name(f, false), g, i, g.isTrackSelected(i)));
                }
            }
            if (unter.size() > 1 && textAktiv()) unter.get(0).aktiv = false;
            menue = true;
            spalte = 0;
            zeile = 0;
            for (int i = 0; i < ton.size(); i++) if (ton.get(i).aktiv) zeile = i;
            zeigen(0);
        }

        private boolean textAktiv() {
            for (Tracks.Group g : player.getCurrentTracks().getGroups())
                if (g.getType() == C.TRACK_TYPE_TEXT && g.isSelected()) return true;
            return false;
        }

        boolean menueTaste(int k) {
            List<Eintrag> liste = spalte == 0 ? ton : unter;
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    zeile = Math.max(0, zeile - 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    zeile = Math.min(Math.max(0, liste.size() - 1), zeile + 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    spalte = 1 - spalte;
                    List<Eintrag> l2 = spalte == 0 ? ton : unter;
                    zeile = 0;
                    for (int i = 0; i < l2.size(); i++) if (l2.get(i).aktiv) zeile = i;
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (zeile < liste.size()) waehlen(spalte, liste.get(zeile));
                    menue = false;
                    zeigen(2500);
                    break;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    menue = false;
                    zeigen(1500);
                    break;
                default:
                    return false;
            }
            invalidate();
            return true;
        }

        private void waehlen(int sp, Eintrag e) {
            TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
            if (sp == 1 && e.gruppe == null) {
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
            } else if (e.gruppe != null) {
                int typ = sp == 0 ? C.TRACK_TYPE_AUDIO : C.TRACK_TYPE_TEXT;
                b.setTrackTypeDisabled(typ, false);
                b.setOverrideForType(new TrackSelectionOverride(e.gruppe.getMediaTrackGroup(), e.index));
            }
            player.setTrackSelectionParameters(b.build());
        }

        private String name(Format f, boolean audio) {
            String sprache = f.language != null ? new Locale(f.language).getDisplayLanguage(Locale.GERMAN) : "";
            if (sprache.isEmpty() || "und".equals(f.language)) sprache = audio ? "Tonspur" : "Untertitel";
            StringBuilder s = new StringBuilder(sprache);
            if (f.label != null && !f.label.isEmpty() && !f.label.equalsIgnoreCase(sprache)) s.append(" · ").append(f.label);
            if (audio) {
                String codec = f.sampleMimeType == null ? "" : f.sampleMimeType;
                if (codec.contains("eac3")) s.append(" · Dolby Digital+");
                else if (codec.contains("ac3")) s.append(" · Dolby Digital");
                else if (codec.contains("true-hd")) s.append(" · Dolby TrueHD");
                else if (codec.contains("dts")) s.append(" · DTS");
                if (f.channelCount >= 6) s.append(" ").append(f.channelCount == 8 ? "7.1" : "5.1");
            } else if ((f.selectionFlags & C.SELECTION_FLAG_FORCED) != 0) {
                s.append(" · erzwungen");
            }
            return s.toString();
        }

        @Override
        protected void onDraw(Canvas c) {
            long jetzt = SystemClock.uptimeMillis();
            boolean spielt = player != null && player.isPlaying();
            if (jetzt > bisWann && !menue && spulZiel < 0 && spielt && meldung == null) ziel = 0f;
            sichtbar += (ziel - sichtbar) * 0.18f;
            if (Math.abs(ziel - sichtbar) < 0.01f) sichtbar = ziel;

            float s = getWidth() / 1920f;
            c.save();
            c.scale(s, s);
            if (sichtbar > 0.001f && player != null) zeichnen(c, sichtbar);
            c.restore();

            if (sichtbar != ziel || spulZiel >= 0) postInvalidateOnAnimation();
            else if (ziel > 0) postInvalidateDelayed(250); // Zeit weiterzaehlen und spaeter ausblenden
        }

        private void zeichnen(Canvas c, float a) {
            int al = (int) (255 * a);
            // Verlauf unten und oben
            p.setShader(new LinearGradient(0, 640, 0, 1080, 0x00000000, 0xD9000000, Shader.TileMode.CLAMP));
            p.setAlpha(al);
            c.drawRect(0, 640, 1920, 1080, p);
            p.setShader(new LinearGradient(0, 0, 0, 260, 0xA6000000, 0x00000000, Shader.TileMode.CLAMP));
            p.setAlpha(al);
            c.drawRect(0, 0, 1920, 260, p);
            p.setShader(null);

            // Titel oben links (Logo, sonst Schrift)
            if (logo != null) {
                float hoehe = 110, breite = Math.min(520, hoehe * logo.getWidth() / logo.getHeight());
                hoehe = breite * logo.getHeight() / logo.getWidth();
                r.set(110, 70, 110 + breite, 70 + hoehe);
                p.setAlpha(al);
                c.drawBitmap(logo, null, r, p);
            } else if (titel != null) {
                text.setTypeface(schriftFett);
                text.setTextSize(54);
                text.setColor(0xFFFFFFFF);
                text.setAlpha(al);
                text.setShadowLayer(12, 0, 2, 0x99000000);
                c.drawText(titel, 110, 130, text);
                text.clearShadowLayer();
            }

            long dauer = Math.max(1, player.getDuration());
            long pos = spulZiel >= 0 ? spulZiel : player.getCurrentPosition();
            long puffer = player.getBufferedPosition();
            float x0 = 110, x1 = 1810, y = 950;
            // Leiste
            p.setColor(0x40FFFFFF);
            p.setAlpha((int) (0x40 * a));
            r.set(x0, y - 4, x1, y + 4);
            c.drawRoundRect(r, 4, 4, p);
            p.setColor(0x66FFFFFF);
            p.setAlpha((int) (0x66 * a));
            r.set(x0, y - 4, x0 + (x1 - x0) * Math.min(1f, puffer / (float) dauer), y + 4);
            c.drawRoundRect(r, 4, 4, p);
            float xp = x0 + (x1 - x0) * Math.min(1f, pos / (float) dauer);
            p.setColor(GELB);
            p.setAlpha(al);
            r.set(x0, y - 4, xp, y + 4);
            c.drawRoundRect(r, 4, 4, p);
            // Knopf
            p.setColor(0xFFFFFFFF);
            p.setAlpha(al);
            c.drawCircle(xp, y, spulZiel >= 0 ? 14 : 10, p);

            // Zeiten
            text.setTypeface(schrift);
            text.setTextSize(30);
            text.setColor(0xFFFFFFFF);
            text.setAlpha((int) (230 * a));
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(zeit(pos), x0, y + 58, text);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText("−" + zeit(Math.max(0, dauer - pos)), x1, y + 58, text);
            text.setTextAlign(Paint.Align.LEFT);

            // Spulblase
            if (spulZiel >= 0) {
                String t = zeit(spulZiel);
                text.setTypeface(schriftFett);
                text.setTextSize(40);
                float w = text.measureText(t) + 56;
                float bx = Math.max(x0, Math.min(x1 - w, xp - w / 2));
                r.set(bx, y - 104, bx + w, y - 34);
                p.setColor(0xCC1A1C22);
                p.setAlpha((int) (0xCC * a));
                c.drawRoundRect(r, 35, 35, p);
                text.setColor(0xFFFFFFFF);
                text.setAlpha(al);
                c.drawText(t, bx + 28, y - 55, text);
            }

            // Pause-Symbol in der Mitte
            if (pause && !menue && spulZiel < 0) {
                p.setColor(0x99000000);
                p.setAlpha((int) (0x99 * a));
                c.drawCircle(960, 540, 72, p);
                p.setColor(0xFFFFFFFF);
                p.setAlpha(al);
                r.set(930, 505, 948, 575);
                c.drawRoundRect(r, 5, 5, p);
                r.set(972, 505, 990, 575);
                c.drawRoundRect(r, 5, 5, p);
            }

            if (meldung != null) {
                text.setTypeface(schriftFett);
                text.setTextSize(36);
                text.setColor(0xFFFFFFFF);
                text.setAlpha(al);
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText(meldung, 960, 540, text);
                text.setTextAlign(Paint.Align.LEFT);
            }

            if (menue) menueZeichnen(c, a);
        }

        private void menueZeichnen(Canvas c, float a) {
            float bx = 1120, by = 120, bw = 700, zh = 64;
            int n = Math.max(ton.size(), unter.size());
            float bh = 150 + Math.min(n, 9) * zh;
            r.set(bx, by, bx + bw, by + bh);
            p.setColor(0xE61A1C22);
            p.setAlpha((int) (0xE6 * a));
            c.drawRoundRect(r, 36, 36, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(0x33FFFFFF);
            p.setAlpha((int) (0x33 * a));
            c.drawRoundRect(r, 36, 36, p);
            p.setStyle(Paint.Style.FILL);

            String[] kopf = {"Ton", "Untertitel"};
            for (int sp = 0; sp < 2; sp++) {
                float x = bx + 40 + sp * 330;
                text.setTypeface(schriftFett);
                text.setTextSize(30);
                text.setColor(sp == spalte ? 0xFFFFFFFF : 0x99FFFFFF);
                text.setAlpha((int) ((sp == spalte ? 255 : 150) * a));
                c.drawText(kopf[sp], x, by + 75, text);
                List<Eintrag> l = sp == 0 ? ton : unter;
                int start = (sp == spalte) ? Math.max(0, Math.min(zeile - 4, l.size() - 9)) : 0;
                for (int i = start; i < Math.min(l.size(), start + 9); i++) {
                    Eintrag e = l.get(i);
                    float yy = by + 135 + (i - start) * zh;
                    if (sp == spalte && i == zeile) {
                        r.set(x - 18, yy - 42, x + 300, yy + 16);
                        p.setColor(0xFFFFFFFF);
                        p.setAlpha((int) (235 * a));
                        c.drawRoundRect(r, 18, 18, p);
                    }
                    text.setTypeface(e.aktiv ? schriftFett : schrift);
                    text.setTextSize(26);
                    boolean fokus = sp == spalte && i == zeile;
                    text.setColor(fokus ? 0xFF111111 : 0xFFFFFFFF);
                    text.setAlpha((int) ((fokus ? 255 : 220) * a));
                    String t = (e.aktiv ? "✓ " : "   ") + e.name;
                    c.drawText(kuerzen(t, 290), x, yy, text);
                }
            }
        }

        private String kuerzen(String t, float max) {
            if (text.measureText(t) <= max) return t;
            while (t.length() > 3 && text.measureText(t + "…") > max) t = t.substring(0, t.length() - 1);
            return t + "…";
        }
    }

    private static final class Eintrag {
        final String name;
        final Tracks.Group gruppe;
        final int index;
        boolean aktiv;

        Eintrag(String name, Tracks.Group gruppe, int index, boolean aktiv) {
            this.name = name;
            this.gruppe = gruppe;
            this.index = index;
            this.aktiv = aktiv;
        }
    }

    static String zeit(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sek = s % 60;
        return h > 0 ? String.format(Locale.GERMAN, "%d:%02d:%02d", h, m, sek) : String.format(Locale.GERMAN, "%d:%02d", m, sek);
    }
}
