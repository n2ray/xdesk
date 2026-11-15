package com.termux.x11;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.View;
import android.view.WindowManager;

/**
 * NOTIFY.md §4.1: the small bell at the external screen's top-right corner, the CornerButton component with the
 * bell's glyph (the Mac menu's ≡ is the second instance, MACMENU.md §3). Clicking it opens and closes the
 * notification centre; the number of unread notifications is drawn in its badge.
 */
final class NotifyBell {
    static final float SIZE_DP = CornerButton.SIZE_DP, NEAR_DP = CornerButton.NEAR_DP;

    interface Listener {
        void tapped();
    }

    private final CornerButton button;
    private final Path bell = new Path();

    NotifyBell(Context dc, float density, Listener listener) {
        button = new CornerButton(dc, density, CornerButton.RIGHT, this::draw, "XDesk bell", listener::tapped);
    }

    private void draw(Canvas c, float w, float h, float s, Paint paint) {
        // An outline in the ≡'s stroke (owner, 2026-11-15: the filled dome read as a mushroom once on a plate): the
        // knob, the body flaring to the rim, the rim, and the clapper's arc below it
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, 1.6f * s));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        bell.reset();
        bell.moveTo(w * 0.20f, h * 0.70f);
        bell.cubicTo(w * 0.29f, h * 0.62f, w * 0.29f, h * 0.54f, w * 0.29f, h * 0.44f);
        bell.cubicTo(w * 0.29f, h * 0.29f, w * 0.38f, h * 0.20f, w * 0.5f, h * 0.20f);
        bell.cubicTo(w * 0.62f, h * 0.20f, w * 0.71f, h * 0.29f, w * 0.71f, h * 0.44f);
        bell.cubicTo(w * 0.71f, h * 0.54f, w * 0.71f, h * 0.62f, w * 0.80f, h * 0.70f);
        bell.close();
        c.drawPath(bell, paint);
        c.drawLine(w * 0.5f, h * 0.13f, w * 0.5f, h * 0.20f, paint);
        c.drawArc(w * 0.42f, h * 0.70f, w * 0.58f, h * 0.84f, 0, 180, false, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    View view() {
        return button.view();
    }

    WindowManager.LayoutParams params() {
        return button.params();
    }

    /** The bell's place on the screen, in pixels, for the driven pointer and the fade's nearness. */
    void bounds(Rect out) {
        button.bounds(out);
    }

    void setUnread(int n) {
        button.setUnread(n);
    }

    void pointerAt(float fx, float fy, int width, int height) {
        button.pointerAt(fx, fy, width, height);
    }
}
