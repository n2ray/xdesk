package com.termux.x11.mac;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceView;

/**
 * The Mac's pointer as a layer of its own over the picture (a child of the picture SurfaceView's layer): a move is
 * a position in one transaction, nothing is drawn again. As a view in the activity's window every move redrew that
 * whole 2560×1600 window and SurfaceFlinger composed it with the GPU (2026-11-19: about 33 window frames a second
 * while dragging, CLIENT composition, the video layer losing frames). Main thread only.
 */
final class CursorLayer {
    private static final String TAG = "XDeskMac";
    private final SurfaceControl parent;
    private final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private SurfaceControl layer;
    private Surface surface;
    private int width, height;
    private boolean visible;

    private CursorLayer(SurfaceControl parent) {
        this.parent = parent;
    }

    /** A layer over this SurfaceView's picture, or null when it has none (yet) or the system refuses one. */
    static CursorLayer attach(SurfaceView picture) {
        try {
            SurfaceControl p = picture.getSurfaceControl();
            if (p == null || !p.isValid()) return null;
            return new CursorLayer(p);
        } catch (RuntimeException e) {
            Log.w(TAG, "pointer layer unavailable", e);
            return null;
        }
    }

    /** The shape, drawn once into a layer of this size (a new layer when the size changes). False: it failed. */
    boolean shape(Bitmap b, int w, int h) {
        try {
            if (layer == null || w != width || h != height) {
                drop();
                layer = new SurfaceControl.Builder().setName("XDesk Mac pointer").setParent(parent)
                        .setBufferSize(Math.max(1, w), Math.max(1, h)).setFormat(PixelFormat.TRANSLUCENT)
                        .setOpaque(false).build();
                surface = new Surface(layer);
                width = w;
                height = h;
                t.setLayer(layer, Integer.MAX_VALUE).setVisibility(layer, visible).apply();
            }
            Canvas c = surface.lockHardwareCanvas();
            try {
                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                c.drawBitmap(b, null, new Rect(0, 0, Math.max(1, w), Math.max(1, h)), paint);
            } finally {
                surface.unlockCanvasAndPost(c);
            }
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "pointer layer failed", e);
            drop();
            return false;
        }
    }

    boolean ready() {
        return layer != null;
    }

    /** Where its top left goes, in the picture's pixels. */
    void move(float x, float y) {
        if (layer == null) return;
        t.setPosition(layer, x, y).apply();
    }

    void show(boolean on) {
        visible = on;
        if (layer == null) return;
        t.setVisibility(layer, on).apply();
    }

    private void drop() {
        if (surface != null) surface.release();
        surface = null;
        if (layer != null) {
            try {
                if (layer.isValid()) t.reparent(layer, null).apply();
            } catch (RuntimeException ignored) { }
            layer.release();
        }
        layer = null;
        width = height = 0;
    }

    /** The picture's surface goes: so does the layer. */
    void release() {
        drop();
    }
}
