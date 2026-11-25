package com.termux.x11;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceControl;

import java.lang.reflect.Method;

/**
 * The pointer over Android apps on the external screen, drawn by the injector (the shell user) as a layer of its own
 * on that display's layer stack, above everything, with no window: the injector places the pointer and moves this
 * layer in the same step, one transaction to SurfaceFlinger. As a window XDesk moved, every frame of mouse movement
 * was also a WindowManager relayout in system_server (2026-11-19: system_server about a third of a core more while
 * the mouse moved over an app). A window's child layer cannot do it: it is cropped to its window's frame.
 *
 * Needs ACCESS_SURFACE_FLINGER (the shell has it) and the hidden Transaction.setLayerStack; when either is missing
 * {@link #set} says no and XDesk keeps its window. The layer goes with this process, so a crash leaves nothing behind.
 */
final class PointerLayer {
    private static final String TAG = "XDeskInjector";
    private final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
    private SurfaceControl layer;
    private Surface surface;
    private int stack = -1, width, height, offsetX, offsetY, x, y;
    private boolean visible, failed;

    /**
     * The pointer's picture (null: the one it has), where its point is inside the picture, whether it shows, and
     * where the point is now, on this display. False: no layer here (XDesk draws its window instead).
     */
    synchronized boolean set(int displayId, Bitmap image, int offX, int offY, boolean show, int atX, int atY) {
        if (failed) return false;
        try {
            int s = layerStack(displayId);
            if (s < 0) return false;
            if (layer != null && (s != stack || image != null && (image.getWidth() != width || image.getHeight() != height)))
                drop();
            if (layer == null) {
                if (image == null) return false;
                layer = new SurfaceControl.Builder().setName("XDesk pointer")
                        .setBufferSize(image.getWidth(), image.getHeight()).setFormat(PixelFormat.TRANSLUCENT)
                        .setOpaque(false).build();
                Method setStack = SurfaceControl.Transaction.class.getMethod("setLayerStack", SurfaceControl.class, int.class);
                setStack.invoke(t, layer, s);
                surface = new Surface(layer);
                passTouches(layer);
                stack = s;
                width = image.getWidth();
                height = image.getHeight();
                Log.i(TAG, "pointer layer on layer stack " + s + ", " + width + "x" + height);
            }
            if (image != null) draw(image);
            offsetX = offX;
            offsetY = offY;
            visible = show;
            x = atX;
            y = atY;
            t.setLayer(layer, Integer.MAX_VALUE - 1).setPosition(layer, x - offsetX, y - offsetY)
                    .setVisibility(layer, visible).apply();
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "pointer layer unavailable", e);
            failed = true;
            drop();
            return false;
        }
    }

    /** The injector placed the pointer: the layer follows, whether it shows or not. */
    synchronized void moveTo(int atX, int atY) {
        x = atX;
        y = atY;
        if (layer == null || !visible) return;
        try {
            t.setPosition(layer, x - offsetX, y - offsetY).apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "pointer layer move", e);
        }
    }

    /** The session moved to another display: the layer is made again there at the next set. */
    synchronized void display(int displayId) {
        if (layer == null) return;
        try {
            if (layerStack(displayId) != stack) drop();
        } catch (Exception e) {
            drop();
        }
    }

    synchronized void show(boolean show) {
        visible = show;
        if (layer != null) t.setPosition(layer, x - offsetX, y - offsetY).setVisibility(layer, show).apply();
    }

    synchronized void release() {
        drop();
    }

    private void draw(Bitmap image) {
        Canvas c = surface.lockCanvas(null);
        try {
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            c.drawBitmap(image, 0, 0, null);
        } finally {
            surface.unlockCanvasAndPost(c);
        }
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
        stack = -1;
        visible = false;
    }

    /**
     * Touches must go through the pointer to what is under it. A layer without a window is still an occluder to the
     * input system: owned by the shell's uid, at full opacity, every tap at the pointer was dropped as an untrusted
     * touch (2026-11-19, beta.4: "Untrusted touch due to occlusion by /2000/[BBQ] XDesk pointer"). A trusted overlay
     * (the shell may make one) never occludes; when the system refuses that, the layer goes to the most opacity an
     * untrusted overlay may have and still let touches pass (0.8, the window's own as Android set it).
     */
    private void passTouches(SurfaceControl sc) {
        try {
            SurfaceControl.Transaction.class.getMethod("setTrustedOverlay", SurfaceControl.class, boolean.class)
                    .invoke(t, sc, true);
            t.apply();
            Log.i(TAG, "pointer layer is a trusted overlay");
            return;
        } catch (Throwable ignored) { }
        try {
            // Android 15 and later: the state is an int (TrustedOverlay.ENABLED is 2)
            SurfaceControl.Transaction.class.getMethod("setTrustedOverlay", SurfaceControl.class, int.class)
                    .invoke(t, sc, 2);
            t.apply();
            Log.i(TAG, "pointer layer is a trusted overlay");
            return;
        } catch (Throwable ignored) { }
        t.setAlpha(sc, 0.8f).apply();
        Log.i(TAG, "pointer layer: not trusted, at 0.8 opacity so touches pass");
    }

    /** The display's layer stack, from the display manager's hidden DisplayInfo; -1 when the display is gone. */
    private static int layerStack(int displayId) throws Exception {
        Class<?> global = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object dm = global.getMethod("getInstance").invoke(null);
        Object info = global.getMethod("getDisplayInfo", int.class).invoke(dm, displayId);
        if (info == null) return -1;
        return info.getClass().getField("layerStack").getInt(info);
    }
}
