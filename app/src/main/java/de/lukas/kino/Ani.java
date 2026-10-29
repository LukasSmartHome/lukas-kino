package de.lukas.kino;

import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/** Bewegungskurven wie in der alten Web-Oberfläche (CSS cubic-bezier). */
final class Ani {
    /** cubic-bezier(.2,.8,.2,1) – schnelles, weiches Ausrollen. */
    static final Interpolator AUS = new PathInterpolator(0.2f, 0.8f, 0.2f, 1f);
    /** CSS „ease“ = cubic-bezier(.25,.1,.25,1). */
    static final Interpolator EASE = new PathInterpolator(0.25f, 0.1f, 0.25f, 1f);

    private Ani() {
    }

    static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** CSS-Unschärfe (box-shadow/text-shadow, Radius) in den Radius von Paint.setShadowLayer umrechnen. */
    static float schatten(float cssBlur) {
        float sigma = cssBlur / 2f;
        return Math.max(0.5f, (sigma - 0.5f) / 0.57735f);
    }

    static int farbe(int rgb, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
