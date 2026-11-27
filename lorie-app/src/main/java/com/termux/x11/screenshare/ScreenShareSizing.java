// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11.screenshare;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.view.Display;
import android.view.Surface;

/**
 * 桌面缩放 for a Mac through Screen Sharing (owner, 22 November): from the external screen's real output and the
 * user's choice, the three sizes of one session, every one in the screen's own proportions —
 * <ul>
 * <li>the screen: the pixels the picture is shown on (its current mode, turned as the display is);</li>
 * <li>the desktop: the Mac's logical size in points, its work area;</li>
 * <li>the video: the Mac's frame buffer in pixels, which is what H.264 carries (twice the desktop for HiDPI).</li>
 * </ul>
 * A limit (the Mac's virtual display, the H.264 level, the phone's decoder) scales width and height by one factor,
 * then both are made even (HiDPI keeps its exact 2:1). Pure arithmetic apart from the decoder's word, so the same
 * numbers show in the settings before a connection and are asked for at it.
 */
public final class ScreenShareSizing {
    /** 原生大小 (the behaviour before), 适度放大 (about 1.33×), 大字 (2×, HiDPI first). */
    public static final int NATIVE = 0, MODERATE = 1, LARGE = 2;
    static final float[] FACTOR = {1f, 4f / 3f, 2f};

    /** The Mac's virtual display (the native client's own kModes top out at 3840×2160), either way round. */
    static final int MAC_LONG = 3840, MAC_SHORT = 2160;
    /** H.264 level 5.1/5.2: at most 36864 macroblocks a frame (4096×2304). */
    static final int MAX_MACROBLOCKS = 36864;

    public final int tier;
    /** The screen's output, turned as shown. 0 when not known (no such display). */
    public final int screenW, screenH;
    /** The Mac's desktop, in points. */
    public final int desktopW, desktopH;
    /** The frame buffer and the H.264 picture, in pixels. */
    public final int videoW, videoH;
    /** True HiDPI: the video is exactly twice the desktop. */
    public final boolean hidpi;
    /** A limit made it smaller than the tier asked (by one factor for width and height). */
    public final boolean limited;

    private ScreenShareSizing(int tier, int sw, int sh, int dw, int dh, int vw, int vh, boolean hidpi, boolean limited) {
        this.tier = tier;
        screenW = sw;
        screenH = sh;
        desktopW = dw;
        desktopH = dh;
        videoW = vw;
        videoH = vh;
        this.hidpi = hidpi;
        this.limited = limited;
    }

    /** The video is smaller than the screen: the phone scales it up (not HiDPI, a lower resolution). */
    public boolean upscaled() {
        return !hidpi && screenW > 0 && videoW < screenW * 0.98;
    }

    /** The output a display is driven at now, turned as it is shown (a portrait monitor gives a tall size). */
    public static int[] output(Display d) {
        if (d == null) return null;
        Display.Mode m = d.getMode();
        int w = m.getPhysicalWidth(), h = m.getPhysicalHeight();
        int r = d.getRotation();
        if (r == Surface.ROTATION_90 || r == Surface.ROTATION_270) {
            int t = w;
            w = h;
            h = t;
        }
        return w > 0 && h > 0 ? new int[] {w, h} : null;
    }

    /** The old fixed 1920×1080 baseline (a test setting): no screen, no scaling. */
    public static ScreenShareSizing fixed1080() {
        return new ScreenShareSizing(NATIVE, 0, 0, 1920, 1080, 1920, 1080, false, false);
    }

    /** For a screen of screenW×screenH (0×0: not known, a 1920×1080 one is assumed). */
    public static ScreenShareSizing of(int tier, int screenW, int screenH) {
        if (tier < NATIVE || tier > LARGE) tier = NATIVE;
        return scaled(tier,screenW,screenH,FACTOR[tier]);
    }

    public static ScreenShareSizing window(int preference,int w,int h,int desktopPercent) {
        double factor=preference==0?desktopPercent/100.0:preference==1?4.0/3:preference==2?2:1;
        return scaled(preference,w,h,Math.max(1,Math.min(2,factor)));
    }

    private static ScreenShareSizing scaled(int tier,int screenW,int screenH,double factor) {
        int sw = screenW > 0 && screenH > 0 ? screenW : 1920, sh = screenW > 0 && screenH > 0 ? screenH : 1080;
        boolean hidpi = factor >= 2;
        // What the tier asks: the desktop the screen's size over the factor; the frame buffer twice it for HiDPI
        double desk = 1.0 / factor;
        double video = hidpi ? 2 * desk : desk;   // as a share of the screen: 1, 0.75, 1
        double vw = sw * video, vh = sh * video;
        // The one factor every limit agrees to
        double k = 1;
        double lng = Math.max(vw, vh), shrt = Math.min(vw, vh);
        k = Math.min(k, MAC_LONG / lng);
        k = Math.min(k, MAC_SHORT / shrt);
        k = Math.min(k, Math.sqrt(MAX_MACROBLOCKS * 256.0 / (vw * vh)));
        int dw, dh, pw, ph;
        for (int tries = 0; ; tries++) {
            if (hidpi) {
                dw = evenDown(vw * k / 2);
                dh = evenDown(vh * k / 2);
                pw = 2 * dw;
                ph = 2 * dh;
            } else {
                pw = evenNear(vw * k);
                ph = evenNear(vh * k);
                dw = pw;
                dh = ph;
            }
            if (tries >= 40 || decodes(pw, ph)) break;
            k *= 0.95;  // the phone cannot decode it: a little smaller, same proportions
        }
        boolean limited = k < 0.999;
        return new ScreenShareSizing(tier, screenW, screenH, dw, dh, pw, ph, hidpi, limited);
    }

    /** Even, never more than v (HiDPI's half: twice it stays within the screen and the limits). */
    private static int evenDown(double v) {
        return Math.max(2, 2 * (int) Math.floor(v / 2 + 1e-6));
    }

    /** The nearest even (2560 / 1.33 is 1920, not 1918); a limit's exact value is even already. */
    private static int evenNear(double v) {
        return Math.max(2, 2 * (int) Math.round(v / 2));
    }

    private static volatile MediaCodecInfo.VideoCapabilities decoder;

    /** The phone's hardware H.264 decoder takes this size (true when it can't be asked). */
    static boolean decodes(int w, int h) {
        MediaCodecInfo.VideoCapabilities v = decoder;
        if (v == null) {
            try {
                for (MediaCodecInfo i : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                    if (i.isEncoder() || !i.isHardwareAccelerated()) continue;
                    for (String t : i.getSupportedTypes()) {
                        if (!t.equalsIgnoreCase("video/avc")) continue;
                        v = i.getCapabilitiesForType(t).getVideoCapabilities();
                        break;
                    }
                    if (v != null) break;
                }
            } catch (RuntimeException ignored) { }
            if (v == null) return true;
            decoder = v;
        }
        return v.isSizeSupported(w, h);
    }

    @Override public String toString() {
        return "tier " + tier + " screen " + screenW + "x" + screenH + " desktop " + desktopW + "x" + desktopH
            + " video " + videoW + "x" + videoH + (hidpi ? " hidpi" : "") + (limited ? " limited" : "");
    }
}
