package io.github.n2ray.xdesk.receiver;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Range;
import android.view.Display;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What the receiver asks the phone for: its screen, capped by the decoder. */
final class Screen {
    static final String AVC = "video/avc", HEVC = "video/hevc";
    static final int MAX_LONG_SIDE = 1920;

    final int width, height, density, fps;
    final boolean hevc;

    private Screen(int width, int height, int density, int fps, boolean hevc) {
        this.width = width;
        this.height = height;
        this.density = density;
        this.fps = fps;
        this.hevc = hevc;
    }

    static Screen of(Display d) {
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        int[] size = fit(m.widthPixels, m.heightPixels, caps(AVC));
        MediaCodecInfo.VideoCapabilities h265 = caps(HEVC);
        // Offer H.265 only when its decoder takes the same size, the phone does not ask again per codec
        boolean hevc = h265 != null && h265.isSizeSupported(size[0], size[1]);
        int fps = Math.max(1, Math.round(d.getRefreshRate()));
        // The density for the picture asked for, which may be smaller than the screen: the phone sizes things by it
        int density = Math.max(1, Math.round((float) m.densityDpi * size[0] / Math.max(1, m.widthPixels)));
        return new Screen(size[0], size[1], density, fps, hevc);
    }

    /**
     * The real size scaled down, aspect kept, until the long side is at most 1920 and the decoder takes it.
     * Rounded down to the decoder's alignment, which is at least 2, so both are even.
     */
    static int[] fit(int w, int h, MediaCodecInfo.VideoCapabilities caps) {
        w = Math.max(w, 2);
        h = Math.max(h, 2);
        double scale = Math.min(1.0, (double) MAX_LONG_SIDE / Math.max(w, h));
        int wa = 2, ha = 2;
        if (caps != null) {
            wa = lcm(2, caps.getWidthAlignment());
            ha = lcm(2, caps.getHeightAlignment());
            // Widths and heights are capped separately; a decoder that does 1920x1080 may not do 1080x1920
            Range<Integer> ws = caps.getSupportedWidths(), hs = caps.getSupportedHeights();
            scale = Math.min(scale, Math.min((double) ws.getUpper() / w, (double) hs.getUpper() / h));
        }
        for (int i = 0; i < 40; i++, scale *= 0.95) {
            int sw = Math.max(wa, (int) (w * scale) / wa * wa);
            int sh = Math.max(ha, (int) (h * scale) / ha * ha);
            if (caps == null || caps.isSizeSupported(sw, sh)) return new int[] { sw, sh };
        }
        Log.w(MainActivity.TAG, "no decoder size found for " + w + "x" + h);
        return new int[] { Math.max(2, (int) (w * scale) & ~1), Math.max(2, (int) (h * scale) & ~1) };
    }

    private static final Object lock = new Object();
    private static boolean scanned;
    private static MediaCodecInfo.VideoCapabilities avc, hevcCaps;

    /** The decoder createDecoderByType would pick: the first one in the list for the type */
    static MediaCodecInfo.VideoCapabilities caps(String mime) {
        synchronized (lock) {
            if (!scanned) {
                scanned = true;
                try {
                    for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                        if (info.isEncoder()) continue;
                        for (String type : info.getSupportedTypes()) {
                            if (avc == null && type.equalsIgnoreCase(AVC))
                                avc = info.getCapabilitiesForType(type).getVideoCapabilities();
                            else if (hevcCaps == null && type.equalsIgnoreCase(HEVC))
                                hevcCaps = info.getCapabilitiesForType(type).getVideoCapabilities();
                        }
                    }
                } catch (RuntimeException e) {
                    Log.w(MainActivity.TAG, "codec list: " + e);
                }
            }
            return AVC.equals(mime) ? avc : hevcCaps;
        }
    }

    private static int lcm(int a, int b) {
        int x = a, y = Math.max(1, b);
        while (y != 0) {
            int t = x % y;
            x = y;
            y = t;
        }
        return a / x * Math.max(1, b);
    }

    /** IPv4 addresses to show on the idle screen, Wi-Fi and Ethernet first */
    static List<String> addresses() {
        List<String> first = new ArrayList<>(), rest = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                String n = nif.getName();
                boolean lan = n.startsWith("wlan") || n.startsWith("eth");
                for (InetAddress a : Collections.list(nif.getInetAddresses()))
                    if (a instanceof Inet4Address) (lan ? first : rest).add(a.getHostAddress());
            }
        } catch (Exception e) {
            // No interfaces: shown as no network
        }
        first.addAll(rest);
        return first;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Screen)) return false;
        Screen s = (Screen) o;
        return s.width == width && s.height == height && s.density == density;
    }

    @Override
    public int hashCode() {
        return (width * 31 + height) * 31 + density;
    }
}
