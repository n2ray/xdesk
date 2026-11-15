package com.termux.x11;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.Display;

import java.util.HashMap;
import java.util.Map;

/**
 * How big things are on a screen, DISPLAY.md §4.1. Two numbers, and one ratio:
 * <ul>
 *   <li><b>b</b>, the screen's fact: effective ppi ÷ 96 × a distance factor for its kind (monitor 1, TV 1.6, projector 2,
 *       a tablet or phone held in the hand 0.6);</li>
 *   <li><b>u</b>, the user's choice: small 0.85, medium 1, large 1.15, extra large 1.3;</li>
 *   <li><b>R/N</b>, the pixels the content renders ÷ the screen's own pixels (the X desktop at 1920 on a 2560 screen).</li>
 * </ul>
 * From them: X DPI = 96·b·u·R/N, Android density = 160·b·u·R/N, and XFCE's panel, icons and cursor in the same ratio.
 * Pure arithmetic apart from reading a Display; nothing here runs per frame.
 */
public final class Sizing {
    static final int KIND_UNSET = 0, MONITOR = 1, TV = 2, PROJECTOR = 3, HANDHELD = 4;
    /** u, index 1 (medium) by default. */
    static final float[] SIZES = {0.85f, 1f, 1.15f, 1.3f};
    static final int SIZE_DEFAULT = 1;
    /** §4.1: a screen whose EDID gives no believable size. */
    static final float NO_PPI_B = 1.1f;
    private static final float MIN_PPI = 40, MAX_PPI = 300;

    private Sizing() { }

    static float distance(int kind) {
        switch (kind) {
            case TV: return 1.6f;
            case PROJECTOR: return 2f;
            case HANDHELD: return 0.6f;
            default: return 1f;
        }
    }

    static float u(int size) {
        return size >= 0 && size < SIZES.length ? SIZES[size] : SIZES[SIZE_DEFAULT];
    }

    /** The screen's ppi from its physical size (EDID for an external one), or NaN when it is missing or unbelievable. */
    static float ppi(Display d) {
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        return ppi(m.xdpi, m.ydpi);
    }

    static float ppi(float xdpi, float ydpi) {
        if (!(xdpi >= MIN_PPI && xdpi <= MAX_PPI && ydpi >= MIN_PPI && ydpi <= MAX_PPI)) return Float.NaN;
        return (xdpi + ydpi) / 2;
    }

    /** b of a cable-connected screen of that kind (unset counts as a monitor). */
    static float wiredB(Display d, int kind) {
        float ppi = ppi(d);
        return (Float.isNaN(ppi) ? NO_PPI_B : ppi / 96f) * distance(kind);
    }

    /**
     * b of a screen that tells its own Android-style density (160 = 1×): a receiver, a browser (160 × device pixel
     * ratio), the car. That density already allows for how far away it is seen, as its own system chose it for its
     * natural kind (a tablet's density is meant for the hand); a kind the user picks scales it from there.
     */
    static float densityB(int density, int kind, int naturalKind) {
        float b = density > 0 ? density / 160f : 1f;
        if (kind == KIND_UNSET || kind == naturalKind) return b;
        return b * distance(kind) / distance(naturalKind);
    }

    /** X DPI = 96·b·u·R/N. */
    static int xDpi(float b, float u, float rn) {
        return clamp(Math.round(96f * b * u * rn), 48, 480);
    }

    /** Android density = 160·b·u·R/N. */
    static int androidDensity(float b, float u, float rn) {
        return clamp(Math.round(160f * b * u * rn), 72, 800);
    }

    /** The X DPI for the desktop on display d, rendered w pixels wide on a screen native pixels wide. */
    static int desktopDpi(Context c, Display d, int w, int nativeWidth) {
        ScreenProfiles.Profile p = ScreenProfiles.get(c).of(c, d);
        float rn = nativeWidth > 0 && w > 0 ? (float) w / nativeWidth : 1f;
        return xDpi(b(c, d, p), u(p.size), rn);
    }

    /** b of display d, which XDesk uses: wired, the car or the wireless display. */
    static float b(Context c, Display d, ScreenProfiles.Profile p) {
        if (TargetDisplay.isCar(d)) {
            // Android Auto gives the car's DPI as the display's density
            DisplayMetrics m = new DisplayMetrics();
            d.getRealMetrics(m);
            return densityB(m.densityDpi, KIND_UNSET, MONITOR);
        }
        if (d.getDisplayId() == WirelessDisplay.displayId()) return WirelessDisplay.get(c).screenB(p.kind);
        return wiredB(d, p.kind);
    }

    /**
     * The shell's and the cursor's own factor on one screen (§4.1: b·u): what dp and sp count at, independent of the
     * Android density override in force on the display and of the system's font scale. R/N is not in it: the shell
     * does not follow the content's rendered pixels.
     */
    public static final class Shell {
        /** Pixels per dp: b·u. */
        public final float density;
        /** Pixels per sp: the same b·u, with no font scale (§4.1 sizes the shell by b·u alone). */
        public final float text;

        Shell(float f) {
            density = f;
            text = f;
        }

        public int dp(float v) {
            return Math.round(v * density);
        }

        public int text(float v) {
            return Math.round(v * text);
        }
    }

    private static final class Cached {
        final int generation;
        final long stamp;
        final Shell shell;

        Cached(int generation, long stamp, Shell shell) {
            this.generation = generation;
            this.stamp = stamp;
            this.shell = shell;
        }
    }

    /** Per display that had a shell recently. Main thread. */
    private static final Map<Integer, Cached> shells = new HashMap<>();

    /**
     * The shell's and cursor's factor on display d (b·u from its profile), cached per display. Recomputed when a
     * screen's profile changes (kind or u: ScreenProfiles.generation) or what the wireless screen's b comes from
     * changes (WirelessDisplay.shellStamp). What it deliberately does not follow: the density override Android apps
     * put on the display, and the font scale. Main thread.
     */
    public static Shell shell(Context c, Display d) {
        if (d == null) return new Shell(1f);
        ScreenProfiles profiles = ScreenProfiles.get(c);
        int generation = profiles.generation();
        long stamp = WirelessDisplay.get(c).shellStamp();
        Cached hit = shells.get(d.getDisplayId());
        if (hit != null && hit.generation == generation && hit.stamp == stamp) return hit.shell;
        ScreenProfiles.Profile p = profiles.of(c, d);
        float f = b(c, d, p) * u(p.size);
        if (!(f > 0f)) f = 1f;  // a factor of 0 or NaN would draw nothing
        // Display ids of virtual displays never repeat, but don't grow without bound either
        if (shells.size() > 8) shells.clear();
        Cached fresh = new Cached(generation, stamp, new Shell(f));
        shells.put(d.getDisplayId(), fresh);
        return fresh.shell;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
