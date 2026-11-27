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
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.Drawable;
import android.content.res.Configuration;
import android.view.View;
import com.termux.x11.app.R;
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

    XdesxFullscreenBar(Context context, Display display, XdesxPanelState panel, Runnable exit, Runnable play,
                       Runnable quit, Runnable failure) {
        Context dc = context.createDisplayContext(display);
        windows = dc.getSystemService(WindowManager.class);
        Configuration config = new Configuration(dc.getResources().getConfiguration());
        config.setLocale(panel.locale());
        dc = dc.createConfigurationContext(config);
        int scale = panel.scale;
        boolean dark = panel.isDark();
        height = Math.round(38 * scale / 100f);
        this.failure = failure;
        bar = new LinearLayout(dc);
        int inset = Math.round(3 * scale / 100f);
        bar.setPadding(inset, inset, inset, inset);
        GradientDrawable surface = rounded(dark ? 0xff222326 : 0xfff2f3f6, height / 4f);
        surface.setStroke(1, dark ? 0xff45474c : 0xffd6d8dd);
        bar.setBackground(surface);
        int ink = dark ? 0xfff2f3f5 : 0xff20232a;
        int hover = dark ? 0xff41444b : 0xffe4e7ec;
        addAction(dc, scale, R.string.xu_exit_fullscreen, R.drawable.xu_bar_window, ink, hover, exit);
        if (play != null) addAction(dc, scale, R.string.xu_direct_game_input, R.drawable.xu_bar_game, ink, hover, play);
        addAction(dc, scale, R.string.xu_quit_app, R.drawable.xu_bar_quit, dark ? 0xffff827b : 0xffba322e, hover, quit);
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

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radius);
        return background;
    }

    private void addAction(Context dc, int scale, int label, int icon, int ink, int hover, Runnable action) {
        Button button = new Button(dc);
        button.setAllCaps(false);
        button.setText(label);
        button.setSingleLine(true);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 14 * scale / 100f);
        button.setTextColor(ink);
        button.setGravity(Gravity.CENTER);
        button.setPadding(height / 3, 0, height / 3, 0);
        Drawable symbol = dc.getDrawable(icon).mutate();
        int size = Math.round(16 * scale / 100f);
        symbol.setBounds(0, 0, size, size);
        symbol.setTint(ink);
        button.setCompoundDrawablesRelative(symbol, null, null, null);
        button.setCompoundDrawablePadding(Math.round(6 * scale / 100f));
        StateListDrawable states = new StateListDrawable();
        for (int state : new int[]{android.R.attr.state_pressed, android.R.attr.state_hovered, android.R.attr.state_focused})
            states.addState(new int[]{state}, rounded(hover, height / 6f));
        states.addState(new int[0], rounded(0, 0));
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);
        button.setBackground(states);
        button.setOnClickListener(v -> action.run());
        button.setOnHoverListener((v, event) -> {
            cursor(event.getActionMasked() == android.view.MotionEvent.ACTION_HOVER_EXIT ? height + 9 : 3);
            return false;
        });
        bar.addView(button, -2, -1);
    }

    private final int[] padOrigin = new int[2];
    private View padPressed;

    // Route the Mac touchpad click to the same control as a physical pointer.
    boolean padEvent(int x, int y, int op, int button, boolean down) {
        int width = windows.getCurrentWindowMetrics().getBounds().width();
        boolean near = Math.abs(x - width / 2) <= edge.getLayoutParams().width / 2;
        View hit = null;
        if (attached) {
            bar.getLocationOnScreen(padOrigin);
            int localX = x - padOrigin[0], localY = y - padOrigin[1];
            for (int i=0; i<bar.getChildCount(); i++) {
                View child = bar.getChildAt(i);
                if (localX >= child.getLeft() && localX < child.getRight() &&
                        localY >= child.getTop() && localY < child.getBottom()) { hit=child; break; }
            }
        }
        cursor(near || hit != null ? y : height + 9);
        if (op != 2 || button != 1) return false;
        if (down) { padPressed = hit; return hit != null; }
        View pressed = padPressed;
        padPressed = null;
        if (pressed == null) return false;
        if (hit == pressed) pressed.performClick();
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
