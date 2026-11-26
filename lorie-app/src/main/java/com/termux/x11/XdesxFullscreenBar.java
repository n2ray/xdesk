package com.termux.x11;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;

/** The external overlay is attached only while the pointer is near the top in native fullscreen. */
final class XdesxFullscreenBar {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final WindowManager windows;
    private final LinearLayout bar;
    private final android.view.View edge;
    private boolean edgeAttached, game;
    private final int height;
    private boolean attached, closed;
    private int zone = -1;
    private final Runnable failure;
    private final Runnable show = this::show;
    private final Runnable hide = this::hide;

    XdesxFullscreenBar(Context context, Display display, int scale, Runnable exit, Runnable play, Runnable failure) {
        Context dc = context.createDisplayContext(display);
        windows = dc.getSystemService(WindowManager.class);
        height = Math.round(38 * scale / 100f);
        this.failure = failure;
        bar = new LinearLayout(dc);
        addAction(dc, scale, dc.getString(com.termux.x11.app.R.string.xu_return_to_window), exit);
        if (play != null) addAction(dc, scale, dc.getString(com.termux.x11.app.R.string.xu_direct_game_input), play);
        GradientDrawable background = new GradientDrawable();
        background.setColor((dc.getResources().getConfiguration().uiMode & 48)==32 ? 0xff25262a : 0xfff7f7f8);
        background.setCornerRadius(height / 4f);
        bar.setBackground(background);
        edge = new android.view.View(dc);
        edge.setOnHoverListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_HOVER_EXIT) {
                cursor(height + 9);
            } else cursor(0);
            return true;
        });
        // Native physical pointer movements go straight to HDMI. Only the edge needs a hit target.
        WindowManager.LayoutParams trigger = params(160 * scale / 100, 2);
        trigger.setTitle("Xdesx fullscreen edge");
        // The empty View draws nothing. A zero-alpha Window is excluded from pointer hit testing.
        try { windows.addView(edge, trigger); edgeAttached = true; }
        catch (RuntimeException e) { android.util.Log.w("XDesX", "fullscreen edge unavailable", e); ui.post(failure); }

    }

    private void addAction(Context dc, int scale, String label, Runnable action) {
        Button button = new Button(dc);
        button.setText(label);
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 15 * scale / 100f);
        button.setTextColor((dc.getResources().getConfiguration().uiMode & 48)==32 ? 0xfff4f4f6 : 0xff19191b);
        button.setGravity(Gravity.CENTER);
        button.setPadding(height / 2, 0, height / 2, 0);
        button.setBackgroundColor(0x00000000);
        button.setOnClickListener(v -> action.run());
        button.setOnHoverListener((v, event) -> {
            cursor(event.getActionMasked() == android.view.MotionEvent.ACTION_HOVER_EXIT ? height + 9 : 3);
            return false;
        });
        bar.addView(button, -2, -1);
    }

    private final int[] padOrigin = new int[2];
    private boolean padPressed;

    // The Mac toolbar has one action. Its normal button still handles physical mice.
    boolean padEvent(int x, int y, int op, int button, boolean down) {
        int width = windows.getCurrentWindowMetrics().getBounds().width();
        boolean near = Math.abs(x - width / 2) <= edge.getLayoutParams().width / 2;
        cursor(near ? y : height + 9);
        if (op != 2 || button != 1) return false;
        boolean hit = false;
        if (attached) {
            bar.getLocationOnScreen(padOrigin);
            hit = x >= padOrigin[0] && x < padOrigin[0] + bar.getWidth()
                    && y >= padOrigin[1] && y < padOrigin[1] + bar.getHeight();
        }
        if (down) { padPressed = hit; return hit; }
        if (!padPressed) return false;
        padPressed = false;
        if (hit) bar.getChildAt(0).performClick();
        return true;
    }

    synchronized void gameMode(boolean active) {
        game = active;
        zone = -1;
        ui.removeCallbacks(show);
        edge.setVisibility(active ? android.view.View.GONE : android.view.View.VISIBLE);
        if (active) hide();
    }

    /** Called from Binder. Post only crossings, even with a high polling-rate mouse. */
    synchronized void cursor(int y) {
        int next = y <= 2 ? 0 : y <= height + 8 ? 1 : 2;
        if (closed || game || next == zone) return;
        zone = next;
        ui.removeCallbacks(show);
        ui.removeCallbacks(hide);
        if (next == 0) ui.postDelayed(show, 300);
        if (next == 2) ui.postDelayed(hide, 200);
    }

    private synchronized void show() {
        if (closed || attached || zone != 0) return;
        WindowManager.LayoutParams p = params(-2, height);
        p.setTitle("Xdesx fullscreen controls");
        try {
            windows.addView(bar, p);
            attached = true;
            bar.setTranslationY(-height);
            bar.animate().translationY(0).setDuration(160).start();
        } catch (RuntimeException e) {
            android.util.Log.w("XDesX", "fullscreen controls unavailable", e);
            failure.run();
        }
    }

    private WindowManager.LayoutParams params(int w, int h) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.setFitInsetsTypes(0);
        return p;
    }

    private synchronized void hide() {
        if (!attached) return;
        bar.animate().cancel();
        windows.removeViewImmediate(bar);
        attached = false;
    }

    synchronized void close() {
        closed = true;
        if (edgeAttached) { windows.removeViewImmediate(edge); edgeAttached = false; }
        ui.removeCallbacks(show);
        ui.removeCallbacks(hide);
        hide();
    }
}
