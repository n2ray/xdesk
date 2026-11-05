package com.termux.x11;

import android.content.Context;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

/**
 * Covers the external display while the phone is on another app, so what is on it stays private. It is a window above
 * the app rather than DashboardActivity in front of it: the app stays resumed and comes back at once, and a RustDesk
 * session is not disturbed. Its sound keeps playing.
 */
final class ExternalCover {
    private static final String TAG = "XDesk";

    private final Context context;
    private WindowManager windows;
    private DashboardView view;

    ExternalCover(Context c) {
        context = c.getApplicationContext();
    }

    boolean isShown() {
        return view != null;
    }

    void show(Display display) {
        if (view != null) return;
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "cannot cover the external display without the overlay permission");
            return;
        }
        Context dc = context.createDisplayContext(display);
        WindowManager wm = dc.getSystemService(WindowManager.class);
        DashboardView v = new DashboardView(dc, "回到手机上的 XDesk 继续", false);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE);
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setFitInsetsTypes(0);
        p.setTitle("XDesk cover");
        try {
            wm.addView(v, p);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot cover the external display", e);
            return;
        }
        windows = wm;
        view = v;
        v.start();
    }

    void hide() {
        if (view == null) return;
        view.stop();
        try {
            windows.removeViewImmediate(view);
        } catch (RuntimeException e) {
            Log.w(TAG, "cover already gone", e);
        }
        view = null;
        windows = null;
    }
}
