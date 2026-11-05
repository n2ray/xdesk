package com.termux.x11;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * A translucent dot on the external display for each finger in 触控 mode, because the user looks at the big screen.
 *
 * This window covers the display, so touches do pass through its frame, unlike the cursor, which keeps the point outside.
 * Android lets touches pass through another app's overlay when the window's alpha is at most 0.8. So WINDOW_ALPHA must
 * stay below that, or touches on the display get dropped. The window exists only while fingers are down.
 */
final class TouchDots {
    private static final String TAG = "XDesk";
    private static final float WINDOW_ALPHA = 0.7f;
    /** Dot size as a part of the display's height, in pixels and not dp, like the cursor. */
    private static final float RADIUS_OF_HEIGHT = 0.02f;

    private final Context context;
    private WindowManager windows;
    private DotsView view;
    private WindowManager.LayoutParams params;
    private boolean shown;
    private int width, height;

    TouchDots(Context c) {
        context = c;
    }

    void attach(Display display) {
        remove();
        Context dc = context.createDisplayContext(display);
        windows = dc.getSystemService(WindowManager.class);
        Point real = new Point();
        display.getRealSize(real);
        width = real.x;
        height = real.y;
        view = new DotsView(dc, real.y * RADIUS_OF_HEIGHT);
        params = new WindowManager.LayoutParams(real.x, real.y, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.alpha = WINDOW_ALPHA;
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        params.setTitle("XDesk fingers");
    }

    /** Only when the size changed: a display change can come at any time, also while fingers are down. */
    void displayChanged(Display display) {
        Point real = new Point();
        display.getRealSize(real);
        if (view != null && (real.x != width || real.y != height)) attach(display);
    }

    /** Pixels on the display. An empty array removes the dots. */
    void show(float[] x, float[] y) {
        if (view == null) return;
        if (x.length == 0) {
            hide();
            return;
        }
        view.set(x, y);
        if (shown) return;
        try {
            windows.addView(view, params);
            shown = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot show finger dots", e);
        }
    }

    void hide() {
        if (!shown) return;
        shown = false;
        try {
            windows.removeViewImmediate(view);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot hide finger dots", e);
        }
    }

    void remove() {
        hide();
        view = null;
    }

    private static final class DotsView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;
        private float[] x = new float[0], y = new float[0];

        DotsView(Context c, float radius) {
            super(c);
            this.radius = radius;
            fill.setColor(Color.WHITE);
            edge.setColor(Color.BLACK);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, radius / 8));
        }

        void set(float[] newX, float[] newY) {
            x = newX;
            y = newY;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            for (int i = 0; i < x.length; i++) {
                c.drawCircle(x[i], y[i], radius, fill);
                c.drawCircle(x[i], y[i], radius, edge);
            }
        }
    }
}
