// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A notice on the phone as a card that drops in from the top, instead of a line fixed on the launcher (owner,
 * 24 November): "Desktop ended", "No external screen", what the input service lacks. It goes by itself after a few
 * seconds (longer when a tap does something), when swiped up or aside, or with its close button. A tap on the card
 * does what the line did (the no-screen notice opens the receiver picker). One at a time: a new one replaces it.
 * Main thread only.
 */
final class PhoneNotice {
    private static final long PLAIN_MS = 6000, ACTION_MS = 10000, SLIDE_MS = 180;

    private final Activity a;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout card;
    private final Runnable timeout = this::hide;

    PhoneNotice(Activity a) {
        this.a = a;
    }

    void show(String text, final Runnable action) {
        removeNow();
        ViewGroup root = a.findViewById(android.R.id.content);
        if (root == null || text == null) return;
        int pad = Style.dp(a, 14);
        final LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(pad, pad, Style.dp(a, 4), pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Style.SURFACE_RAISED);
        bg.setCornerRadius(Style.dp(a, 16));
        bg.setStroke(Style.dp(a, 1), Style.SHELL_LINE);
        c.setBackground(bg);
        c.setElevation(Style.dp(a, 8));
        TextView t = Style.label(a, text, Style.TEXT, 14);
        c.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = Style.label(a, "✕", Style.TEXT_DIM, 16);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(a.getString(android.R.string.cancel));
        close.setOnClickListener(v -> hide());
        int target = Style.dp(a, Style.TARGET);
        c.addView(close, new LinearLayout.LayoutParams(target, target));
        swipeToClose(c, action);

        int side = Style.dp(a, 12);
        int top = side;
        WindowInsets in = root.getRootWindowInsets();
        if (in != null) top += in.getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        lp.setMargins(side, top, side, 0);
        root.addView(c, lp);
        card = c;
        c.setTranslationY(-Style.dp(a, 120));
        c.setAlpha(0f);
        c.animate().translationY(0).alpha(1f).setDuration(SLIDE_MS).start();
        ui.postDelayed(timeout, action != null ? ACTION_MS : PLAIN_MS);
    }

    /** It slides away (the timer, the close button, a new state that ends what it said). */
    void hide() {
        ui.removeCallbacks(timeout);
        final View c = card;
        if (c == null) return;
        card = null;
        c.animate().translationY(-c.getHeight() - Style.dp(a, 24)).alpha(0f).setDuration(SLIDE_MS)
            .withEndAction(() -> detach(c)).start();
    }

    private void removeNow() {
        ui.removeCallbacks(timeout);
        if (card != null) {
            card.animate().cancel();
            detach(card);
            card = null;
        }
    }

    private static void detach(View c) {
        if (c.getParent() instanceof ViewGroup) ((ViewGroup) c.getParent()).removeView(c);
    }

    /** Up or aside past a third of its size closes it; less springs back; a tap runs the action. */
    private void swipeToClose(final LinearLayout c, final Runnable action) {
        final int slop = ViewConfiguration.get(a).getScaledTouchSlop();
        final float[] start = new float[2];
        final boolean[] moved = {false};
        c.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = e.getRawX();
                    start[1] = e.getRawY();
                    moved[0] = false;
                    ui.removeCallbacks(timeout);  // held: it waits
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - start[0], dy = Math.min(0, e.getRawY() - start[1]);
                    if (!moved[0] && Math.hypot(dx, dy) > slop) moved[0] = true;
                    if (moved[0]) {
                        boolean aside = Math.abs(dx) > -dy;
                        v.setTranslationX(aside ? dx : 0);
                        v.setTranslationY(aside ? 0 : dy);
                        v.setAlpha(1f - Math.min(0.8f, Math.max(Math.abs(v.getTranslationX()) / v.getWidth(),
                            -v.getTranslationY() / v.getHeight())));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean gone = Math.abs(v.getTranslationX()) > v.getWidth() / 3f
                        || -v.getTranslationY() > v.getHeight() / 3f;
                    if (gone) {
                        if (Math.abs(v.getTranslationX()) > 0) {
                            ui.removeCallbacks(timeout);
                            card = null;
                            v.animate().translationX(Math.signum(v.getTranslationX()) * v.getWidth() * 1.2f).alpha(0f)
                                .setDuration(SLIDE_MS).withEndAction(() -> detach(v)).start();
                        } else {
                            hide();
                        }
                        return true;
                    }
                    v.animate().translationX(0).translationY(0).alpha(1f).setDuration(SLIDE_MS).start();
                    if (!moved[0] && e.getActionMasked() == MotionEvent.ACTION_UP && action != null) {
                        hide();
                        action.run();
                    } else {
                        ui.postDelayed(timeout, action != null ? ACTION_MS : PLAIN_MS);
                    }
                    return true;
                }
                default:
                    return false;
            }
        });
    }
}
