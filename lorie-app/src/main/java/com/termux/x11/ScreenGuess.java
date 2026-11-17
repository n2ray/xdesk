package com.termux.x11;

import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

/**
 * What kind of wired screen a display is, guessed from what it tells (DISPLAY.md §4.1), and whether the guess is sure
 * enough not to ask. Its size from the EDID: 40 inches and more is a TV, less a monitor (portable 13–17 inch screens
 * stand on a desk like one). A TV feature, auto low latency mode / game content type (isMinimalPostProcessingSupported),
 * makes a big screen surely a TV and a smaller one unsure. No believable size (projectors often give none or any):
 * asked. Makers are not used: Samsung, LG and Philips make both.
 */
final class ScreenGuess {
    private static final String TAG = "XDesk";
    static final float TV_INCHES = 40, TV_FEATURE_INCHES = 27;

    final int kind;
    final boolean sure;

    private ScreenGuess(int kind, boolean sure) {
        this.kind = kind;
        this.sure = sure;
    }

    static ScreenGuess of(Display d) {
        float inches = inches(d);
        boolean tvFeature = false;
        try {
            tvFeature = d.isMinimalPostProcessingSupported();
        } catch (RuntimeException e) {
            Log.i(TAG, "screen guess: " + e);
        }
        ScreenGuess g = of(inches, tvFeature);
        Log.i(TAG, "screen guess: " + (Float.isNaN(inches) ? "no size" : String.format(java.util.Locale.ROOT, "%.1f in", inches))
            + (tvFeature ? ", TV feature" : "") + " -> kind " + g.kind + (g.sure ? "" : ", asked"));
        return g;
    }

    /** The rule, apart from reading the display. */
    static ScreenGuess of(float inches, boolean tvFeature) {
        if (Float.isNaN(inches)) return new ScreenGuess(tvFeature ? Sizing.TV : Sizing.MONITOR, false);
        if (inches >= TV_INCHES) return new ScreenGuess(Sizing.TV, tvFeature);
        if (!tvFeature) return new ScreenGuess(Sizing.MONITOR, true);
        return new ScreenGuess(inches >= TV_FEATURE_INCHES ? Sizing.TV : Sizing.MONITOR, false);
    }

    /** The diagonal from the panel's pixels and the EDID's dpi, NaN when that is missing or not believable. */
    static float inches(Display d) {
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        if (Float.isNaN(Sizing.ppi(m.xdpi, m.ydpi))) return Float.NaN;
        Display.Mode mode = d.getMode();
        float w = mode.getPhysicalWidth() / m.xdpi, h = mode.getPhysicalHeight() / m.ydpi;
        float inches = (float) Math.sqrt(w * w + h * h);
        return inches >= 5 && inches <= 300 ? inches : Float.NaN;
    }
}
