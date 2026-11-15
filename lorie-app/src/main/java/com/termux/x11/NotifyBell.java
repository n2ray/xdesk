package com.termux.x11;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * NOTIFY.md §4.1: the small bell at the external screen's top-right corner. Clicking it opens and closes the
 * notification centre; the number of unread notifications is drawn in its badge. It fades while nothing points near
 * it (full-screen video and the like), and comes back when the pointer comes within NEAR_DP. A plain overlay window
 * like the Dock's edge; the pointer works it over Android apps by itself, over X and a Mac through Notify's driven
 * pointer. Sizes from Sizing.shell, never the display's density.
 */
final class NotifyBell {
    static final float SIZE_DP = 20;
    /** The pointer within this much of the bell keeps it lit (NOTIFY.md §4.1). */
    static final float NEAR_DP = 100;
    /** How faint it fades to, of the shown alpha. */
    private static final float FADE_ALPHA = 0.15f;
    private static final long FADE_MS = 3000, FADE_ANIM_MS = 200;

    interface Listener {
        void tapped();
    }

    private final Context dc;
    private final Listener listener;
    private final float density;
    private final View view;
    private final WindowManager.LayoutParams params;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bell = new Path();
    private int unread;
    private boolean faded;
    private long fadeAt;
    /** Whether the pointer is near (the hover, the driven pointer, a placed one): it does not fade then. */
    private boolean near;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable fadeTick = this::fadeTick;

    NotifyBell(Context dc, float density, Listener listener) {
        this.dc = dc;
        this.density = density;
        this.listener = listener;
        int size = dp(SIZE_DP);
        view = new View(dc) {
            @Override protected void onDraw(Canvas c) {
                draw(c);
            }
        };
        view.setContentDescription("XDesk bell");
        view.setOnHoverListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) near(false);
            else near(true);
            return false;
        });
        view.setOnClickListener(v -> listener.tapped());
        params = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        // In from both edges: the outermost edge pixels stay the corner push's (NOTIFY.md §4.1), the bell does not
        // catch a pointer heading for the corner
        params.x = params.y = dp(8);
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        params.windowAnimations = 0;
        params.setTitle("XDesk bell");
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    View view() {
        return view;
    }

    WindowManager.LayoutParams params() {
        return params;
    }

    /** The bell's place on the screen, in pixels, for the driven pointer and the fade's nearness. */
    void bounds(Rect out) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        out.set(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }

    void setUnread(int n) {
        if (n == unread) return;
        unread = n;
        view.invalidate();
        lit();  // something new: say so while it is there
    }

    private void lit() {
        if (!faded) return;
        faded = false;
        view.animate().alpha(1f).setDuration(FADE_ANIM_MS).start();
    }

    /**
     * The pointer moved on the screen (its fraction of the width and height). Within NEAR_DP the bell stays (or
     * comes back); further away a 3-second quiet fades it (NOTIFY.md §4.1's simple rule).
     */
    void pointerAt(float fx, float fy, int width, int height) {
        int x = Math.round(fx * (width - 1)), y = Math.round(fy * (height - 1));
        Rect r = new Rect();
        bounds(r);
        boolean close = Math.abs(x - r.centerX()) <= dp(NEAR_DP) && Math.abs(y - r.centerY()) <= dp(NEAR_DP);
        near(close);
    }

    private void near(boolean n) {
        if (n == near) return;
        near = n;
        if (n) {
            ui.removeCallbacks(fadeTick);
            lit();
        } else if (!faded) {
            // Nothing near it: start the quiet that fades it
            fadeAt = System.currentTimeMillis() + FADE_MS;
            ui.removeCallbacks(fadeTick);
            ui.postDelayed(fadeTick, FADE_MS);
        }
    }

    private void fadeTick() {
        if (near) return;
        long now = System.currentTimeMillis();
        if (now < fadeAt) {
            ui.postDelayed(fadeTick, fadeAt - now);
            return;
        }
        faded = true;
        view.animate().alpha(FADE_ALPHA).setDuration(FADE_ANIM_MS).start();
    }

    private void draw(Canvas c) {
        int w = view.getWidth(), h = view.getHeight();
        if (w == 0) return;
        float s = Math.min(w, h) / (float) SIZE_DP;  // the glyph on a grid of SIZE_DP
        paint.setColor(Style.TEXT);
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
        // The unread badge: a round dot over the corner, the count inside when there is room
        if (unread > 0) {
            int d = Math.max(dp(10), dp(SIZE_DP * 0.45f));
            float cx = w - d / 2f, cy = d / 2f;
            badgePaint.setColor(Style.DANGER);
            c.drawCircle(cx, cy, d / 2f, badgePaint);
            if (unread < 100) {
                textPaint.setColor(0xFFFFFFFF);
                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setTextSize(d * (unread > 9 ? 0.5f : 0.62f));
                Paint.FontMetrics m = textPaint.getFontMetrics();
                c.drawText(String.valueOf(unread), cx, cy - (m.ascent + m.descent) / 2f, textPaint);
            }
        }
    }
}
