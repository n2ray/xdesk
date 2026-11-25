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
    private boolean game;
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
        addAction(dc, scale, "返回窗口", exit);
        if (play != null) addAction(dc, scale, "游戏直通", play);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xfff7f7f8);
        background.setCornerRadius(height / 4f);
        bar.setBackground(background);
    }

    private void addAction(Context dc, int scale, String label, Runnable action) {
        Button button = new Button(dc);
        button.setText(label);
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 15 * scale / 100f);
        button.setTextColor(0xff19191b);
        button.setGravity(Gravity.CENTER);
        button.setPadding(height / 2, 0, height / 2, 0);
        button.setBackgroundColor(0x00000000);
        button.setOnClickListener(v -> action.run());
        bar.addView(button, -2, -1);
    }

    synchronized void gameMode(boolean active) {
        game = active;
        zone = -1;
        ui.removeCallbacks(show);
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
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(-2, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.setFitInsetsTypes(0);
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

    private synchronized void hide() {
        if (!attached) return;
        bar.animate().cancel();
        windows.removeViewImmediate(bar);
        attached = false;
    }

    synchronized void close() {
        closed = true;
        ui.removeCallbacks(show);
        ui.removeCallbacks(hide);
        hide();
    }
}
