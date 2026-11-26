package com.termux.x11;

import android.view.InputWindowHandle;
import android.view.WindowManager;
import android.window.WindowInfosListener;
import java.util.function.Consumer;

/** Observe window flags only. No screen capture and no polling while the pointer is idle. */
final class FullscreenSecureWatch extends WindowInfosListener {
    private final int display;
    private final String componentPrefix;
    private final Consumer<Boolean> changed;
    private Boolean last;

    FullscreenSecureWatch(int display, String pkg, Consumer<Boolean> changed) {
        this.display = display;
        componentPrefix = " " + pkg + "/";
        this.changed = changed;
    }
    synchronized void start() {
        onWindowInfosChanged(register().first, null);
    }
    @Override public synchronized void onWindowInfosChanged(InputWindowHandle[] windows, DisplayInfo[] ignored) {
        boolean secure = false;
        for (InputWindowHandle window : windows) {
            // InputConfig.NOT_VISIBLE = 2. The base Chrome window carries the WindowManager flag.
            if (window.displayId == display && (window.inputConfig & 2) == 0 && window.name != null
                    && window.name.contains(componentPrefix)) {
                secure = (window.layoutParamsFlags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
                break;
            }
        }
        if (last == null || last != secure) {
            last = secure;
            changed.accept(secure);
        }
    }
}
