package com.termux.x11;

/** Reads the text of `dumpsys window windows`. Its format is not an API, so both answers are best effort. */
final class WindowDump {
    private WindowDump() { }

    private interface BlockTest {
        boolean matches(String header, String flagsAndType);
    }

    /** Returns the header of the first window on this display that passes, or null. */
    private static String find(String dump, int display, BlockTest test) {
        String header = null;
        boolean onDisplay = false;
        StringBuilder attrs = new StringBuilder();
        String want = "mDisplayId=" + display + " ";
        for (String line : (dump + "\n  Window #end").split("\n")) {
            String t = line.trim();
            if (t.startsWith("Window #")) {
                if (header != null && onDisplay && test.matches(header, attrs.toString())) return header;
                header = t;
                onDisplay = false;
                attrs.setLength(0);
            } else if (t.contains(want)) {
                onDisplay = true;
            } else if (t.contains("ty=") || t.contains("fl=")) {
                attrs.append(t).append(' ');
            }
        }
        return null;
    }

    static String findPresentation(String dump, int display) {
        return find(dump, display, (header, attrs) -> attrs.contains("ty=PRESENTATION") || attrs.contains("ty=PRIVATE_PRESENTATION"));
    }

    /** Where the input method's own area begins on this display, -1 while it is not showing. */
    static int imeTop(String dump, int display) {
        String want = "mDisplayId=" + display + " ";
        boolean ime = false, onDisplay = false, visible = false, ready = false;
        int top = -1;
        for (String line : (dump + "\n  Window #end").split("\n")) {
            String t = line.trim();
            if (t.startsWith("Window #")) {
                if (ime && onDisplay && visible && ready) return top;
                ime = t.contains(" InputMethod}");
                onDisplay = visible = ready = false;
                top = -1;
            } else if (ime) {
                if (t.contains(want)) onDisplay = true;
                if (t.startsWith("mViewVisibility=0x0 ")) visible = true;
                if (t.contains("isReadyForDisplay()=true")) ready = true;
                int region = t.indexOf("touchable region=SkRegion((");
                if (region >= 0) top = secondNumber(t.substring(region + 27));
            }
        }
        return -1;
    }

    private static int secondNumber(String list) {
        String[] parts = list.split("[,)]");
        try {
            return parts.length > 1 ? Integer.parseInt(parts[1].trim()) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Whether a window of this package on this display has the KEEP_SCREEN_ON flag. */
    static boolean hasKeepScreenOn(String dump, final String pkg, int display) {
        return find(dump, display, (header, attrs) -> header.contains(" " + pkg + "/") && attrs.contains("KEEP_SCREEN_ON")) != null;
    }
}
