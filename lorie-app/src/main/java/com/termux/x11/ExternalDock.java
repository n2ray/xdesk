package com.termux.x11;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * The dock at the bottom of the external display over Android apps: the current and the previous app, then the
 * favourites. A strip a few pixels high along the bottom edge notices the pointer resting there; the dock then slides
 * up and goes again when the pointer leaves it. It never takes the keyboard focus.
 */
final class ExternalDock {
    private static final String TAG = "XDesk";
    private static final long DWELL_MS = 250, LEAVE_MS = 450, SLIDE_MS = 180;
    private static final float EDGE_DP = 3, ICON_DP = 54, ITEM_GAP_DP = 8, PAD_DP = 10, ROOM_BELOW_DP = 10, RADIUS_DP = 24;
    private static final float HOVER_ZOOM = 1.16f, WIDTH_USED = 0.9f;
    private static final long ZOOM_MS = 110;
    private static final int PANEL = 0xEB1B1E24, EDGE = 0x33FFFFFF, BUBBLE = 0xF02A2E36, DOT = 0xFFFFFFFF;

    private final Context context;
    private final Runnable shown;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int displayId = Display.INVALID_DISPLAY;
    private Context dc;
    private WindowManager windows;
    private View strip;
    private Panel panel;
    private boolean closing;

    private final Runnable open = this::open;
    private final Runnable close = this::close;

    /** @param shown after the dock appears, for what must stay above it */
    ExternalDock(Context c, Runnable shown) {
        context = c.getApplicationContext();
        this.shown = shown;
    }

    private int dp(float v) {
        return Style.dp(dc, v);
    }

    /** Null while there are no Android apps on an external display. */
    void setDisplay(Display d) {
        int id = d != null ? d.getDisplayId() : Display.INVALID_DISPLAY;
        if (id == displayId) return;
        detach();
        displayId = id;
        if (d == null) return;
        dc = context.createDisplayContext(d);
        windows = dc.getSystemService(WindowManager.class);
        strip = new View(dc);
        strip.setOnHoverListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_EXIT) {
                ui.removeCallbacks(open);
                if (panel != null) later(close, LEAVE_MS);
            } else if (panel == null) {
                if (!ui.hasCallbacks(open)) ui.postDelayed(open, DWELL_MS);
            } else {
                ui.removeCallbacks(close);
            }
            return true;
        });
        WindowManager.LayoutParams p = params(WindowManager.LayoutParams.MATCH_PARENT, dp(EDGE_DP), "XDesk dock edge");
        try {
            windows.addView(strip, p);
        } catch (RuntimeException e) {
            Log.w(TAG, "no dock edge", e);
            strip = null;
        }
    }

    private void later(Runnable r, long ms) {
        ui.removeCallbacks(r);
        ui.postDelayed(r, ms);
    }

    private WindowManager.LayoutParams params(int w, int h, String title) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setFitInsetsTypes(0);
        p.windowAnimations = 0;
        p.setTitle(title);
        return p;
    }

    private void open() {
        ExternalLauncher.Host h = ExternalLauncher.host();
        if (h == null || panel != null || windows == null) return;
        Panel fresh = new Panel(h);
        if (fresh.empty()) return;
        try {
            windows.addView(fresh, params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, "XDesk dock"));
        } catch (RuntimeException e) {
            Log.w(TAG, "no dock", e);
            return;
        }
        panel = fresh;
        closing = false;
        fresh.slideIn();
        shown.run();
    }

    private void close() {
        final Panel p = panel;
        if (p == null || closing) return;
        closing = true;
        p.slideOut(() -> {
            remove(p);
            if (panel == p) panel = null;
            closing = false;
        });
    }

    private void remove(View v) {
        try {
            if (windows != null) windows.removeViewImmediate(v);
        } catch (RuntimeException ignored) { }
    }

    private void detach() {
        ui.removeCallbacksAndMessages(null);
        if (panel != null) remove(panel);
        panel = null;
        closing = false;
        if (strip != null) remove(strip);
        strip = null;
        windows = null;
    }

    /** Space for the name above it, the rounded bar, and room below that still counts as being on the dock. */
    private final class Panel extends LinearLayout {
        private final LinearLayout bar;
        private final TextView name;
        private int items;

        Panel(ExternalLauncher.Host h) {
            super(dc);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setClipChildren(false);
            setClipToPadding(false);
            setPadding(dp(16), dp(4), dp(16), dp(ROOM_BELOW_DP));
            name = Style.label(dc, "", Style.TEXT, 13);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setMaxWidth(dp(220));
            name.setPadding(dp(10), dp(4), dp(10), dp(4));
            GradientDrawable bubble = new GradientDrawable();
            bubble.setColor(BUBBLE);
            bubble.setCornerRadius(dp(8));
            name.setBackground(bubble);
            name.setAlpha(0f);
            LayoutParams nameAt = new LayoutParams(-2, -2);
            nameAt.bottomMargin = dp(8);
            addView(name, nameAt);
            bar = new LinearLayout(dc);
            bar.setGravity(Gravity.BOTTOM);
            bar.setClipChildren(false);
            bar.setClipToPadding(false);
            bar.setPadding(dp(PAD_DP), dp(PAD_DP), dp(PAD_DP), dp(4));
            GradientDrawable back = new GradientDrawable();
            back.setColor(PANEL);
            back.setCornerRadius(dp(RADIUS_DP));
            back.setStroke(Math.max(1, dp(0.5f)), EDGE);
            bar.setBackground(back);
            addView(bar, new LayoutParams(-2, -2));
            fill(h);
        }

        boolean empty() {
            return items == 0;
        }

        private void fill(ExternalLauncher.Host h) {
            int room = (int) (dc.getResources().getDisplayMetrics().widthPixels * WIDTH_USED - dp(2 * PAD_DP + 32));
            int each = dp(ICON_DP + ITEM_GAP_DP);
            int most = Math.max(1, room / each);
            String current = h.current(), previous = h.previous();
            boolean left = false;
            if (current != null && ExternalLauncher.exists(dc, current) && items < most) {
                bar.addView(item(current, true));
                left = true;
            }
            if (previous != null && !previous.equals(current) && ExternalLauncher.exists(dc, previous) && items < most) {
                bar.addView(item(previous, false));
                left = true;
            }
            List<String> favorites = h.favorites();
            boolean line = false;
            for (String key : favorites) {
                if (items >= most || !ExternalLauncher.exists(dc, key)) continue;
                if (left && !line) {
                    line = true;
                    View divider = new View(dc);
                    divider.setBackgroundColor(EDGE);
                    LayoutParams at = new LayoutParams(Math.max(1, dp(1)), dp(ICON_DP * 0.72f));
                    at.leftMargin = at.rightMargin = dp(ITEM_GAP_DP / 2 + 2);
                    at.bottomMargin = dp(12);
                    bar.addView(divider, at);
                }
                bar.addView(item(key, false));
            }
        }

        private View item(final String key, boolean running) {
            items++;
            LinearLayout box = new LinearLayout(dc);
            box.setOrientation(VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setClickable(true);
            box.setFocusable(false);
            box.setClipChildren(false);
            final View icon = ExternalLauncher.icon(dc, key, dp(ICON_DP));
            box.addView(icon, new LayoutParams(dp(ICON_DP), dp(ICON_DP)));
            icon.setPivotX(dp(ICON_DP) / 2f);
            icon.setPivotY(dp(ICON_DP));
            View dot = new View(dc);
            GradientDrawable round = new GradientDrawable();
            round.setShape(GradientDrawable.OVAL);
            round.setColor(DOT);
            dot.setBackground(round);
            dot.setVisibility(running ? VISIBLE : INVISIBLE);
            LayoutParams dotAt = new LayoutParams(dp(4), dp(4));
            dotAt.topMargin = dp(4);
            dotAt.bottomMargin = dp(2);
            box.addView(dot, dotAt);
            final String label = ExternalLauncher.label(dc, key);
            box.setContentDescription(label);
            box.setOnHoverListener((v, e) -> {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_HOVER_ENTER) {
                    icon.animate().scaleX(HOVER_ZOOM).scaleY(HOVER_ZOOM).setDuration(ZOOM_MS).start();
                    showName(label, v);
                } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
                    icon.animate().scaleX(1f).scaleY(1f).setDuration(ZOOM_MS).start();
                    name.animate().alpha(0f).setDuration(ZOOM_MS).start();
                }
                return false;
            });
            box.setOnClickListener(v -> {
                ExternalLauncher.open(dc, key);
                close();
            });
            LayoutParams at = new LayoutParams(-2, -2);
            at.leftMargin = at.rightMargin = dp(ITEM_GAP_DP / 2);
            box.setLayoutParams(at);
            return box;
        }

        private void showName(String label, View over) {
            name.setText(label);
            name.post(() -> {
                float centre = bar.getLeft() + over.getLeft() + over.getWidth() / 2f;
                float half = name.getWidth() / 2f;
                float clamped = Math.max(half, Math.min(getWidth() - half, centre));
                name.setTranslationX(clamped - getWidth() / 2f);
                name.animate().alpha(1f).setDuration(ZOOM_MS).start();
            });
        }

        // The children's own enter and exit are handled below this: here is only whether the pointer is on the dock at all
        @Override public boolean dispatchHoverEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_EXIT) later(close, LEAVE_MS);
            else ui.removeCallbacks(close);
            return super.dispatchHoverEvent(e);
        }

        void slideIn() {
            bar.setTranslationY(dp(ICON_DP + 30));
            setAlpha(0f);
            animate().alpha(1f).setDuration(SLIDE_MS).start();
            bar.animate().translationY(0f).setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator()).start();
        }

        void slideOut(Runnable done) {
            animate().alpha(0f).setDuration(SLIDE_MS).withEndAction(done).start();
            bar.animate().translationY(dp(ICON_DP + 30)).setDuration(SLIDE_MS).start();
        }
    }
}
