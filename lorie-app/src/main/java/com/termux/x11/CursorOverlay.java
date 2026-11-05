package com.termux.x11;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
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
     * Android refuses touches that pass through another app's overlay, even a non-touchable one, when the point is
     * inside its frame. So the window starts one row below the point the pointer really is at, and the tip is drawn in
     * its top row. One axis is enough to keep the point out of the frame, so horizontally the tip is exact.
     */
    private static final int HOTSPOT_OFFSET_Y = 1;

    private final Context context;
    private WindowManager windows;
    private ArrowView view;
    private WindowManager.LayoutParams params;
    private boolean shown, framePending, faint, systemStyle;
    private int x, y, margin, attachedWidth, attachedHeight, attachedDensity;

    CursorOverlay(Context c) {
        context = c;
    }

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long time) {
            framePending = false;
            if (!shown) return;
            place();
            windows.updateViewLayout(view, params);
        }
    };

    private void place() {
        params.x = x - margin;
        params.y = y + HOTSPOT_OFFSET_Y;
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
        view = new ArrowView(dc, size, margin, SystemPointer.load());
        view.setFaint(faint);
        view.setSystemStyle(systemStyle);
        params = new WindowManager.LayoutParams(window, window, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        params.setTitle("XDesk cursor");
    }

    /** The cursor shows the input mode: the system's pointer in 触控, our own arrow in 键鼠. Takes effect at once. */
    void setSystemStyle(boolean on) {
        systemStyle = on;
        if (view != null) view.setSystemStyle(on);
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

    /**
     * The phone's own pointer, loaded at run time so nothing of it is copied into this repository. Null when this Android
     * has none under these names.
     */
    private static final class SystemPointer {
        /** Used when the hot spot cannot be read: the tip is near the top left, inset from the corner. */
        private static final float FALLBACK_HOT = 0.25f;
        final Drawable drawable;
        /** Hot spot as a part of the drawable's size. */
        final float hotX, hotY;

        private SystemPointer(Drawable d, float x, float y) {
            drawable = d;
            hotX = x;
            hotY = y;
        }

        static SystemPointer load() {
            Resources res = Resources.getSystem();
            for (String name : new String[]{"pointer_arrow_vector", "pointer_arrow"}) {
                try {
                    int id = res.getIdentifier(name, "drawable", "android");
                    if (id == 0) continue;
                    Drawable d = res.getDrawable(id, null).mutate();
                    float widthDp = d.getIntrinsicWidth() / res.getDisplayMetrics().density;
                    float[] hot = readHotSpot(res, widthDp);
                    Log.i(TAG, "cursor from " + name + ", hot spot " + hot[0] + "," + hot[1]);
                    return new SystemPointer(d, hot[0], hot[1]);
                } catch (RuntimeException e) {
                    Log.i(TAG, "no system pointer " + name + ": " + e);
                }
            }
            return null;
        }

        /** From the `pointer-icon` resource, whose hotSpotX and hotSpotY are dimensions. */
        private static float[] readHotSpot(Resources res, float widthDp) {
            float[] hot = {FALLBACK_HOT, FALLBACK_HOT};
            int id = res.getIdentifier("pointer_arrow_icon", "drawable", "android");
            if (id == 0 || widthDp <= 0) return hot;
            try (XmlResourceParser p = res.getXml(id)) {
                int event;
                while ((event = p.next()) != XmlResourceParser.END_DOCUMENT) {
                    if (event != XmlResourceParser.START_TAG) continue;
                    for (int i = 0; i < p.getAttributeCount(); i++) {
                        String attr = p.getAttributeName(i);
                        float dp = parseDp(p.getAttributeValue(i));
                        if (dp < 0) continue;
                        if ("hotSpotX".equals(attr)) hot[0] = dp / widthDp;
                        else if ("hotSpotY".equals(attr)) hot[1] = dp / widthDp;
                    }
                    break;
                }
            } catch (Exception e) {
                Log.i(TAG, "no pointer hot spot: " + e);
            }
            return hot;
        }

        /** Text such as 6.5dip. Below 0 when it is something else. */
        private static float parseDp(String value) {
            if (value == null || !value.endsWith("dip")) return -1;
            try {
                return Float.parseFloat(value.substring(0, value.length() - 3));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    /**
     * Our own slim black arrow with a white outline and a soft shadow, with the tip at the hot spot, or the system's
     * pointer in 触控 mode, with its own hot spot. All sizes come from the cursor size passed in, nothing from density.
     */
    private static final class ArrowView extends View {
        /** The arrow on a grid of 24 by 24, with the tip at 0,0. */
        private static final float[][] SHAPE = {
            {0, 0}, {0, 17.5f}, {4, 13.8f}, {6.6f, 19.8f}, {9.2f, 18.7f}, {6.6f, 12.8f}, {12.3f, 12.8f}};
        private static final float GRID = 24, OUTLINE_WIDTH = 1.8f, SHADOW_RADIUS = 1.4f, SHADOW_Y = 0.7f;
        private static final int SHADOW_COLOR = 0x66000000;

        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG), outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG), dotEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private final float size, tipX;
        private final SystemPointer pointer;
        private boolean faint, systemStyle;

        ArrowView(Context c, int size, int margin, SystemPointer pointer) {
            super(c);
            this.pointer = pointer;
            if (pointer != null) {
                // Its hot spot goes on the window's top row, so what little is drawn above the tip is cut off
                int left = Math.round(margin - pointer.hotX * size), top = Math.round(-pointer.hotY * size);
                pointer.drawable.setBounds(left, top, left + size, top + size);
            }
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
        }

        void setSystemStyle(boolean on) {
            if (on && pointer == null && !systemStyle) Log.w(TAG, "no system pointer, using our own arrow");
            if (systemStyle == on) return;
            systemStyle = on;
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
            if (systemStyle && pointer != null) {
                pointer.drawable.draw(c);
                return;
            }
            c.drawPath(arrow, outline);
            c.drawPath(arrow, body);
        }
    }
}
