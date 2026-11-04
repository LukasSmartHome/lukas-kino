package de.lukas.kino;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Start-Intro „LWM – Lukas Werner Media“ (Wunsch Lukas 04.11.2026): läuft beim Öffnen des Kino-Bereichs
 * im Vollbild über der Oberfläche, die dahinter schon lädt. Am Ende blendet es weich aus.
 * Jede Taste (außer Lautstärke) überspringt. Video: res/raw/lwm_intro.mp4 (1080p60, 4,8 s, mit Ton).
 * Schwarzer Rahmen um die TextureView, damit vor dem ersten Bild nichts von der Oberfläche durchblitzt.
 */
final class Intro extends FrameLayout implements TextureView.SurfaceTextureListener {
    private static final String TAG = "LukasKino";
    private MediaPlayer mp;
    private Surface surface;
    private boolean weg;

    private Intro(Activity a) {
        super(a);
        setBackgroundColor(Color.BLACK);
        TextureView tv = new TextureView(a);
        tv.setSurfaceTextureListener(this);
        addView(tv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /** Legt das Intro über die Activity. Gibt null zurück, wenn es nicht geht (dann einfach ohne Intro). */
    static Intro zeigen(Activity a) {
        try {
            Intro i = new Intro(a);
            a.addContentView(i, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return i;
        } catch (Exception e) {
            Log.w(TAG, "Intro ging nicht", e);
            return null;
        }
    }

    boolean laeuft() {
        return !weg;
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        if (weg) return;
        try {
            AssetFileDescriptor fd = getResources().openRawResourceFd(R.raw.lwm_intro);
            mp = new MediaPlayer();
            mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            surface = new Surface(st);
            mp.setSurface(surface);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnCompletionListener(m -> ausblenden());
            mp.setOnErrorListener((m, a, b) -> {
                Log.w(TAG, "Intro Fehler " + a + "/" + b);
                ausblenden();
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "Intro startet nicht", e);
            ausblenden();
        }
    }

    /** Weiches Ende (Video fertig oder Taste gedrückt). */
    void ausblenden() {
        if (weg) return;
        weg = true;
        animate().alpha(0f).setDuration(350).withEndAction(this::entfernen).start();
    }

    /** Sofort weg (z. B. Activity pausiert). */
    void entfernen() {
        weg = true;
        animate().cancel();
        if (mp != null) {
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        if (mp != null) {
            try {
                mp.setSurface(null);
            } catch (Exception ignored) {
            }
        }
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }
}
