package com.termux.x11;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
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
    private static final float SIZE_DP = 24;
    /**
     * Android refuses touches that pass through another app's overlay, even a non-touchable one, when the point is
     * inside its frame. So the window starts just past the point the pointer really is at.
     */
    private static final int HOTSPOT_OFFSET = 1;

    private final Context context;
    private WindowManager windows;
    private View view;
    private WindowManager.LayoutParams params;
    private boolean shown, framePending;
    private int x, y;

    CursorOverlay(Context c) {
        context = c;
    }

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long time) {
            framePending = false;
            if (!shown) return;
            params.x = x + HOTSPOT_OFFSET;
            params.y = y + HOTSPOT_OFFSET;
            windows.updateViewLayout(view, params);
        }
    };

    /** Attach to a display. The cursor stays hidden until the first moveTo. */
    void attach(Display display) {
        remove();
        Context dc = context.createDisplayContext(display);
        windows = dc.getSystemService(WindowManager.class);
        int size = Math.round(SIZE_DP * dc.getResources().getDisplayMetrics().density);
        view = new ArrowView(dc);
        params = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        params.setTitle("XDesk cursor");
    }

    void moveTo(int newX, int newY) {
        if (view == null) return;
        x = newX;
        y = newY;
        if (!shown) {
            params.x = x + HOTSPOT_OFFSET;
            params.y = y + HOTSPOT_OFFSET;
            try {
                windows.addView(view, params);
                shown = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "cannot show cursor", e);
            }
            return;
        }
        // Movement can arrive faster than the screen refreshes, one window update per frame is enough
        if (!framePending) {
            framePending = true;
            Choreographer.getInstance().postFrameCallback(frame);
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

    private static final class ArrowView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();

        ArrowView(Context c) {
            super(c);
            fill.setColor(Color.WHITE);
            edge.setColor(Color.BLACK);
            edge.setStyle(Paint.Style.STROKE);
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            float s = w / 24f;
            edge.setStrokeWidth(1.2f * s);
            arrow.reset();
            arrow.moveTo(1 * s, 1 * s);
            arrow.lineTo(1 * s, 19 * s);
            arrow.lineTo(5.5f * s, 15 * s);
            arrow.lineTo(9 * s, 22 * s);
            arrow.lineTo(12 * s, 20.5f * s);
            arrow.lineTo(8.5f * s, 14 * s);
            arrow.lineTo(14 * s, 14 * s);
            arrow.close();
        }

        @Override protected void onDraw(Canvas c) {
            c.drawPath(arrow, fill);
            c.drawPath(arrow, edge);
        }
    }
}
