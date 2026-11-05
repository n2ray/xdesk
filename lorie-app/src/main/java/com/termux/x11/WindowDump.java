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

    /** Whether a window of this package on this display has the KEEP_SCREEN_ON flag. */
    static boolean hasKeepScreenOn(String dump, final String pkg, int display) {
        return find(dump, display, (header, attrs) -> header.contains(" " + pkg + "/") && attrs.contains("KEEP_SCREEN_ON")) != null;
    }
}
