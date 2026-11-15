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
        // The dome
        bell.reset();
        bell.moveTo(w * 0.18f, h * 0.52f);
        bell.cubicTo(w * 0.05f, h * 0.35f, w * 0.22f, h * 0.12f, w * 0.5f, h * 0.12f);
        bell.cubicTo(w * 0.78f, h * 0.12f, w * 0.95f, h * 0.35f, w * 0.82f, h * 0.52f);
        c.drawPath(bell, paint);
        // The clapper and the rim
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, 1.6f * s));
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(w * 0.18f, h * 0.52f, w * 0.82f, h * 0.52f, paint);
        c.drawCircle(w * 0.5f, h * 0.68f, Math.max(1f, 2.2f * s), paint);
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
