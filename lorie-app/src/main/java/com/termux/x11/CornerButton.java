package com.termux.x11;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * The small corner button of the external screen's top corners (NOTIFY.md §4.1, MACMENU.md §3): the notification
 * bell at the top-right, the Mac menu's ≡ at the top-left. One component, extracted from the bell so the menu
 * button is the same thing: the 8 dp inset from both edges (the outermost edge pixels stay the corner push's),
 * the fade while nothing points near it and the come-back within NEAR_DP, the hover and click handling, the
 * optional unread badge (the bell's only), the icon drawn as vector lines on a SIZE_DP grid. A plain overlay
 * window like the Dock's edge; the pointer works it over X and a Mac through the owner's driven pointer (Notify,
 * MacMenus), over Android apps by Android's own hover. Sizes from Sizing.shell, never the display's density.
 */
final class CornerButton {
    /** The bar of the Mac menu is as high (MacMenuBar: 24 dp of row and 2 + 2 of padding), so the ≡ sits level with it. */
    static final float SIZE_DP = 28;
    /** The plate under the glyph: the notification panel's dark surface, slightly see-through, and its edge. */
    private static final int PLATE = 0xE61B1E24, PLATE_EDGE = 0x33FFFFFF;
    /** The pointer within this much of the button keeps it lit (NOTIFY.md §4.1). */
    static final float NEAR_DP = 100;
    /** How faint it fades to, of the shown alpha. */
    private static final float FADE_ALPHA = 0.15f;
    private static final long FADE_MS = 3000, FADE_ANIM_MS = 200;

    static final int LEFT = 0, RIGHT = 1;

    /** The glyph, drawn as vector lines on a SIZE_DP grid scaled to the view (the bell, the ≡). */
    interface Icon {
        void draw(Canvas c, float w, float h, float s, Paint paint);
    }

    interface Listener {
        void tapped();
    }

    private final float density;
    private final View view;
    private final WindowManager.LayoutParams params;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int unread;
    private boolean faded;
    private long fadeAt;
    /** Whether the pointer is near (the hover, the driven pointer, a placed one): it does not fade then. */
    private boolean near;
    /** The Mac menu bar is open and runs on from the button: the bar draws the plate, the button only its glyph. */
    private boolean joined;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable fadeTick = this::fadeTick;

    /** @param corner LEFT or RIGHT: the corner of the screen the button sits in */
    CornerButton(Context dc, float density, int corner, Icon icon, String name, Listener listener) {
        this.density = density;
        int size = dp(SIZE_DP);
        view = new View(dc) {
            @Override protected void onDraw(Canvas c) {
                render(c, icon);
            }
        };
        view.setContentDescription(name);
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
        params.gravity = Gravity.TOP | (corner == LEFT ? Gravity.START : Gravity.END);
        // In from both edges: the outermost edge pixels stay the corner push's (NOTIFY.md §4.1), the button does
        // not catch a pointer heading for the corner
        params.x = params.y = dp(8);
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        params.windowAnimations = 0;
        params.setTitle(name);
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

    /** The button's place on the screen, in pixels, for the driven pointer and the fade's nearness. */
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

    void setJoined(boolean j) {
        if (j == joined) return;
        joined = j;
        view.invalidate();
    }

    private void lit() {
        if (!faded) return;
        faded = false;
        view.animate().alpha(1f).setDuration(FADE_ANIM_MS).start();
    }

    /**
     * The pointer moved on the screen (its fraction of the width and height). Within NEAR_DP the button stays (or
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

    private void render(Canvas c, Icon icon) {
        int w = view.getWidth(), h = view.getHeight();
        if (w == 0) return;
        float s = Math.min(w, h) / SIZE_DP;  // the glyph on a grid of SIZE_DP
        // A dark plate under the glyph (the notification panel's surface, a hairline edge): white lines alone were lost
        // over a light picture (owner, 2026-11-15). The bell and the ≡ share it
        float r = dp(6), edge = Math.max(1f, dp(0.5f));
        if (!joined) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(PLATE);
            c.drawRoundRect(0, 0, w, h, r, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(edge);
            paint.setColor(PLATE_EDGE);
            c.drawRoundRect(edge / 2f, edge / 2f, w - edge / 2f, h - edge / 2f, r, r, paint);
        }
        paint.setColor(Style.TEXT);
        paint.setStyle(Paint.Style.FILL);
        // The glyph a little smaller, centred, so the plate shows round it
        c.save();
        c.scale(0.7f, 0.7f, w / 2f, h / 2f);
        icon.draw(c, w, h, s, paint);
        c.restore();
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
