package com.termux.x11;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * XDesk's own pointer on the external display. Android's pointer stays on the phone (Pointer Display ID 0), even with
 * desktop mode forced on. Only used for Android apps: a Termux desktop draws its own cursor.
 */
final class CursorOverlay {
    private static final String TAG = "XDesk";
    /**
     * Cursor size as a part of the display's height in pixels: 32 pixels on a 1508 high display. Not in dp: it should not
     * shrink when the interface size setting makes everything else smaller, and it should depend on nothing that a
     * density change can disturb. A cursor about 85 pixels high was seen after a density change.
     */
    private static final float SIZE_OF_HEIGHT = 32f / 1508f;
    private static final int MIN_SIZE = 16;
    /** The faint cursor, next to a remote machine's own cursor: this part of the cursor's size wide, this opaque (0 to 1). */
    private static final float FAINT_DOT_OF_SIZE = 0.25f, FAINT_OPACITY = 0.25f;
    /** Room around the arrow for its outline and shadow, as a part of the cursor's size. */
    private static final float MARGIN_OF_SIZE = 0.15f;
    /**
     * Android refuses touches that pass through another app's overlay when the point is inside its frame, unless the
     * window's opacity is at most 0.8. For the 键鼠 arrow the window starts one row below the point the pointer really is
     * at, and the tip is drawn in its top row. One axis is enough to keep the point out of the frame, so horizontally the
     * tip is exact. The 触控 arrowhead has a rounded tip with an outline above it, which that would cut off, so its window
     * holds the point. The window was seen at alpha 0.8 on the phone, so touches should pass, as they do through the finger
     * dots. Windows of one app add up, so AppSession hides the cursor while the dots are showing.
     */
    private static final int HOTSPOT_OFFSET_Y = 1;

    private final Context context;
    private WindowManager windows;
    private ArrowView view;
    private WindowManager.LayoutParams params;
    private boolean shown, framePending, moved, faint, touchStyle;
    private int x, y, margin, attachedWidth, attachedHeight, attachedDensity;

    CursorOverlay(Context c) {
        context = c;
    }

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long time) {
            framePending = false;
            if (!shown || !moved) return;
            moved = false;
            place();
            windows.updateViewLayout(view, params);
        }
    };

    private void place() {
        params.x = x - margin;
        params.y = touchStyle ? y - margin : y + HOTSPOT_OFFSET_Y;
    }

    private static int densityOf(Display display) {
        DisplayMetrics m = new DisplayMetrics();
        display.getRealMetrics(m);
        return m.densityDpi;
    }

    /** Build it again from a fresh context if the display's size or density changed. It stays where it was. */
    void displayChanged(Display display) {
        Point real = new Point();
        display.getRealSize(real);
        if (view == null || (real.x == attachedWidth && real.y == attachedHeight && densityOf(display) == attachedDensity)) return;
        boolean wasShown = shown;
        int atX = x, atY = y;
        attach(display);
        if (wasShown) moveTo(atX, atY);
    }

    /** Attach to a display. The cursor stays hidden until the first moveTo. */
    void attach(Display display) {
        remove();
        Context dc = context.createDisplayContext(display);
        windows = dc.getSystemService(WindowManager.class);
        Point real = new Point();
        display.getRealSize(real);
        attachedWidth = real.x;
        attachedHeight = real.y;
        attachedDensity = densityOf(display);
        int size = Math.max(MIN_SIZE, Math.round(real.y * SIZE_OF_HEIGHT));
        margin = Math.round(size * MARGIN_OF_SIZE);
        int window = size + 2 * margin;
        Log.i(TAG, "cursor " + size + " px on " + real.x + "x" + real.y + " at " + attachedDensity + " dpi");
        view = new ArrowView(dc, size, margin);
        view.setFaint(faint);
        view.setTouchStyle(touchStyle);
        params = new WindowManager.LayoutParams(window, window, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        params.setTitle("XDesk cursor");
    }

    /** The cursor shows the input mode: a rounded arrowhead in 触控, the arrow with a tail in 键鼠. Takes effect at once. */
    void setTouchStyle(boolean on) {
        if (touchStyle == on) return;
        touchStyle = on;
        if (view == null) return;
        view.setTouchStyle(on);
        if (shown) {
            place();
            windows.updateViewLayout(view, params);
        }
    }

    /** See CursorPolicy. */
    void setFaint(boolean f) {
        faint = f;
        if (view != null) view.setFaint(f);
    }

    void moveTo(int newX, int newY) {
        if (view == null) return;
        x = newX;
        y = newY;
        if (!shown) {
            place();
            try {
                windows.addView(view, params);
                shown = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "cannot show cursor", e);
            }
            return;
        }
        // The first move in a frame goes to the window at once: waiting for the next frame added a frame of lag that
        // felt like drag. Further moves in that frame wait for it, one window update per frame is enough
        if (!framePending) {
            framePending = true;
            place();
            windows.updateViewLayout(view, params);
            Choreographer.getInstance().postFrameCallback(frame);
        } else {
            moved = true;
        }
    }

    void hide() {
        if (!shown) return;
        shown = false;
        try {
            windows.removeViewImmediate(view);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot hide cursor", e);
        }
    }

    void remove() {
        hide();
        view = null;
    }

    /**
     * Our own drawings, so they work on any phone: in 键鼠 a slim black arrow with a tail, a thin white outline and a soft
     * shadow; in 触控 a rounded black arrowhead without a tail, with a thick white outline and a soft shadow. All sizes come
     * from the cursor size passed in, nothing from density.
     */
    private static final class ArrowView extends View {
        /** The arrow on a grid of 24 by 24, with the tip at 0,0. */
        private static final float[][] SHAPE = {
            {0, 0}, {0, 17.5f}, {4, 13.8f}, {6.6f, 19.8f}, {9.2f, 18.7f}, {6.6f, 12.8f}, {12.3f, 12.8f}};
        private static final float GRID = 24, OUTLINE_WIDTH = 1.8f, SHADOW_RADIUS = 1.4f, SHADOW_Y = 0.7f;
        private static final int SHADOW_COLOR = 0x66000000;
        /**
         * The 触控 arrowhead on the same grid: a fat triangle with a shallow notch on its lower right side. Its corners
         * before rounding, and how far along each edge each corner is cut. The outline shows one unit outside the black.
         */
        private static final float[][] TOUCH_SHAPE = {{3.9f, 1.9f}, {3.9f, 22f}, {10.4f, 15f}, {20f, 13.9f}};
        private static final float[] TOUCH_CUTS = {3f, 2.6f, 1f, 3f};
        /** The visible tip of the black after rounding: where a click lands. */
        private static final float TOUCH_TIP_X = 4.5f, TOUCH_TIP_Y = 3.1f, TOUCH_OUTLINE_WIDTH = 2f;

        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG), outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG), dotEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private final float size, tipX;
        private final Path touchArrow = new Path();
        private final Paint touchOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean faint, touchStyle;

        ArrowView(Context c, int size, int margin) {
            super(c);
            this.size = size;
            tipX = margin;
            float s = size / GRID;
            // Shadows need this on every Android version, and the window moves without redrawing anyway
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            body.setColor(Color.BLACK);
            outline.setColor(Color.WHITE);
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeJoin(Paint.Join.ROUND);
            // Half of it shows outside the arrow: at least one pixel, or it fades on small displays
            outline.setStrokeWidth(Math.max(2f, OUTLINE_WIDTH * s));
            outline.setShadowLayer(SHADOW_RADIUS * s, 0, SHADOW_Y * s, SHADOW_COLOR);
            int alpha = Math.round(FAINT_OPACITY * 255);
            dot.setColor(Color.WHITE);
            dot.setAlpha(alpha);
            dotEdge.setColor(Color.BLACK);
            dotEdge.setAlpha(alpha);
            dotEdge.setStyle(Paint.Style.STROKE);
            dotEdge.setStrokeWidth(s);
            arrow.moveTo(SHAPE[0][0] * s + tipX, SHAPE[0][1] * s);
            for (int i = 1; i < SHAPE.length; i++) arrow.lineTo(SHAPE[i][0] * s + tipX, SHAPE[i][1] * s);
            arrow.close();
            touchOutline.setColor(Color.WHITE);
            touchOutline.setStyle(Paint.Style.STROKE);
            touchOutline.setStrokeJoin(Paint.Join.ROUND);
            touchOutline.setStrokeWidth(Math.max(2f, TOUCH_OUTLINE_WIDTH * s));
            touchOutline.setShadowLayer(SHADOW_RADIUS * s, 0, SHADOW_Y * s, SHADOW_COLOR);
            buildTouchArrow(s, margin - TOUCH_TIP_X * s, margin - TOUCH_TIP_Y * s);
        }

        /** A polygon whose corners are curves: each starts and ends its cut away from the corner. */
        private void buildTouchArrow(float s, float dx, float dy) {
            int n = TOUCH_SHAPE.length;
            for (int i = 0; i < n; i++) {
                float[] corner = TOUCH_SHAPE[i], before = TOUCH_SHAPE[(i + n - 1) % n], after = TOUCH_SHAPE[(i + 1) % n];
                float[] in = along(corner, before, TOUCH_CUTS[i]), out = along(corner, after, TOUCH_CUTS[i]);
                if (i == 0) touchArrow.moveTo(in[0] * s + dx, in[1] * s + dy);
                else touchArrow.lineTo(in[0] * s + dx, in[1] * s + dy);
                touchArrow.quadTo(corner[0] * s + dx, corner[1] * s + dy, out[0] * s + dx, out[1] * s + dy);
            }
            touchArrow.close();
        }

        private static float[] along(float[] from, float[] to, float distance) {
            float ex = to[0] - from[0], ey = to[1] - from[1];
            float length = (float) Math.hypot(ex, ey);
            return new float[]{from[0] + ex / length * distance, from[1] + ey / length * distance};
        }

        void setTouchStyle(boolean on) {
            if (touchStyle == on) return;
            touchStyle = on;
            invalidate();
        }

        void setFaint(boolean f) {
            if (faint == f) return;
            faint = f;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            if (faint) {
                float r = size * FAINT_DOT_OF_SIZE / 2;
                c.drawCircle(tipX + r, r, r, dot);
                c.drawCircle(tipX + r, r, r, dotEdge);
                return;
            }
            if (touchStyle) {
                c.drawPath(touchArrow, touchOutline);
                c.drawPath(touchArrow, body);
                return;
            }
            c.drawPath(arrow, outline);
            c.drawPath(arrow, body);
        }
    }
}
