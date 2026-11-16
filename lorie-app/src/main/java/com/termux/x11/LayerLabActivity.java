package com.termux.x11;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

/**
 * P0 of the per-window layers (DISPLAY.md §1.2, "lorie 无根模式"): does Android put a desktop's windows on the
 * display's own planes when each is a SurfaceControl layer of its own, and what does that save against drawing the
 * whole screen every frame as XDesk does now? Nothing of the X server yet: the windows are drawn here.
 *
 * The scene: a desktop, n windows (one plays a moving picture like a video, one is dragged around all the time, the
 * rest stay still) and a cursor that circles. Two modes:
 *   single  the whole screen drawn into the SurfaceView every frame (the windows from cached bitmaps), as lorie does
 *   layers  the desktop drawn once into the SurfaceView; each window and the cursor a child SurfaceControl. A frame
 *           moves two layers (one transaction) and redraws only the video window's buffer
 *
 * Shell only (the activity needs DUMP):
 *   am start -n io.github.n2ray.xdesk.x11/com.termux.x11.LayerLabActivity --es mode layers --ei n 6
 * logcat -s XDeskLab: every 5 s the frames drawn and this app's CPU per frame. What composes each layer:
 *   dumpsys SurfaceFlinger, the "XDeskLab" layers' composition type (DEVICE = a plane, CLIENT = the GPU).
 */
public final class LayerLabActivity extends Activity implements SurfaceHolder.Callback, Choreographer.FrameCallback {
    private static final String TAG = "XDeskLab";
    private static final long LOG_MS = 5000;

    private SurfaceView view;
    private boolean layers, running;
    private int n, width, height, cursorSize;
    private Rect[] windows;
    private Bitmap[] pictures;
    private SurfaceControl[] controls;
    private Surface[] surfaces;
    private SurfaceControl cursor;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long frames, cpuNs, since, startedAt;
    private int dragX0, dragY0;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String mode = getIntent().getStringExtra("mode");
        layers = !"single".equals(mode);
        n = Math.max(2, Math.min(16, getIntent().getIntExtra("n", 6)));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        setContentView(view);
    }

    @Override public void surfaceCreated(SurfaceHolder h) { }

    @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
        stop();
        width = w;
        height = hh;
        build();
        running = true;
        frames = cpuNs = 0;
        since = startedAt = SystemClock.uptimeMillis();
        Log.i(TAG, "start " + (layers ? "layers" : "single") + " n=" + n + " " + w + "x" + hh);
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        stop();
    }

    @Override protected void onDestroy() {
        stop();
        super.onDestroy();
    }

    private void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        if (controls != null)
            for (int i = 0; i < controls.length; i++) {
                if (surfaces[i] != null) surfaces[i].release();
                if (controls[i] != null) {
                    t.reparent(controls[i], null);
                    controls[i].release();
                }
            }
        if (cursor != null) {
            t.reparent(cursor, null);
            cursor.release();
        }
        t.apply();
        controls = null;
        surfaces = null;
        cursor = null;
    }

    /** Window 0 is the video, window 1 the dragged one. Sizes and places are fixed by the screen, the same in both modes. */
    private void build() {
        windows = new Rect[n];
        pictures = new Bitmap[n];
        int cols = width > height ? 3 : 2, rows = (n + cols - 1) / cols;
        int cellW = width / cols, cellH = (height - height / 10) / rows;
        for (int i = 0; i < n; i++) {
            int c = i % cols, r = i / cols;
            int x = c * cellW + cellW / 12, y = height / 20 + r * cellH + cellH / 12;
            windows[i] = new Rect(x, y, x + cellW * 5 / 6, y + cellH * 5 / 6);
            pictures[i] = picture(i, windows[i].width(), windows[i].height());
        }
        dragX0 = windows[1].left;
        dragY0 = windows[1].top;
        cursorSize = Math.max(24, width / 40);
        if (!layers) return;
        Canvas c = view.getHolder().lockHardwareCanvas();
        drawDesktop(c);
        view.getHolder().unlockCanvasAndPost(c);
        controls = new SurfaceControl[n];
        surfaces = new Surface[n];
        SurfaceControl parent = view.getSurfaceControl();
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        for (int i = 0; i < n; i++) {
            Rect r = windows[i];
            controls[i] = new SurfaceControl.Builder().setName("XDeskLab window " + i).setParent(parent)
                .setBufferSize(r.width(), r.height()).setFormat(PixelFormat.OPAQUE).build();
            surfaces[i] = new Surface(controls[i]);
            Canvas wc = surfaces[i].lockHardwareCanvas();
            wc.drawBitmap(pictures[i], 0, 0, null);
            surfaces[i].unlockCanvasAndPost(wc);
            t.setPosition(controls[i], r.left, r.top).setLayer(controls[i], i + 1).setVisibility(controls[i], true);
        }
        cursor = new SurfaceControl.Builder().setName("XDeskLab cursor").setParent(parent)
            .setBufferSize(cursorSize, cursorSize).setFormat(PixelFormat.TRANSLUCENT).build();
        Surface cs = new Surface(cursor);
        Canvas cc = cs.lockHardwareCanvas();
        cc.drawColor(0, PorterDuff.Mode.CLEAR);
        drawCursor(cc, 0, 0);
        cs.unlockCanvasAndPost(cc);
        cs.release();
        t.setLayer(cursor, n + 10).setVisibility(cursor, true);
        t.apply();
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        long cpu0 = Debug.threadCpuTimeNanos();
        long ms = SystemClock.uptimeMillis() - startedAt;
        double a = ms / 1000.0;
        int dragX = dragX0 + (int) (Math.sin(a * 0.9) * width / 6), dragY = dragY0 + (int) (Math.cos(a * 0.7) * height / 12);
        int curX = width / 2 + (int) (Math.cos(a * 1.3) * width / 3), curY = height / 2 + (int) (Math.sin(a * 1.3) * height / 3);
        if (layers) {
            Rect v = windows[0];
            Canvas c = surfaces[0].lockHardwareCanvas();
            drawVideo(c, 0, 0, v.width(), v.height(), a);
            surfaces[0].unlockCanvasAndPost(c);
            new SurfaceControl.Transaction().setPosition(controls[1], dragX, dragY).setPosition(cursor, curX, curY).apply();
        } else {
            Canvas c = view.getHolder().lockHardwareCanvas();
            drawDesktop(c);
            for (int i = 0; i < n; i++) {
                Rect r = windows[i];
                if (i == 0) drawVideo(c, r.left, r.top, r.width(), r.height(), a);
                else if (i == 1) c.drawBitmap(pictures[i], dragX, dragY, null);
                else c.drawBitmap(pictures[i], r.left, r.top, null);
            }
            drawCursor(c, curX, curY);
            view.getHolder().unlockCanvasAndPost(c);
        }
        cpuNs += Debug.threadCpuTimeNanos() - cpu0;
        frames++;
        long now = SystemClock.uptimeMillis();
        if (now - since >= LOG_MS) {
            Log.i(TAG, String.format(java.util.Locale.ROOT, "%s n=%d: %.1f fps, %.2f ms CPU per frame on the UI thread",
                layers ? "layers" : "single", n, frames * 1000.0 / (now - since), cpuNs / 1e6 / Math.max(1, frames)));
            frames = cpuNs = 0;
            since = now;
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void drawDesktop(Canvas c) {
        c.drawColor(0xff20304a);
        paint.setColor(0xff2c4466);
        for (int y = 0; y < height; y += height / 12) c.drawRect(0, y, width, y + height / 24, paint);
    }

    /** A window: title bar, lines of "text", a few coloured boxes. */
    private Bitmap picture(int i, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        paint.setColor(Color.HSVToColor(new float[]{i * 47 % 360, 0.5f, 0.8f}));
        int bar = Math.max(24, h / 12);
        c.drawRect(0, 0, w, bar, paint);
        paint.setColor(0xff666666);
        for (int y = bar * 2; y < h - bar; y += bar) c.drawRect(bar / 2f, y, w * (0.5f + (y * 7 % 40) / 100f), y + bar / 3f, paint);
        return b;
    }

    private void drawVideo(Canvas c, int x, int y, int w, int h, double t) {
        int bands = 8;
        for (int k = 0; k < bands; k++) {
            paint.setColor(Color.HSVToColor(new float[]{(float) ((k * 45 + t * 90) % 360), 0.8f, 0.9f}));
            float x0 = x + (float) (((k * w / (double) bands) + t * w / 3) % w);
            c.drawRect(x0, y, Math.min(x + w, x0 + w / (float) bands), y + h, paint);
            if (x0 + w / (float) bands > x + w) c.drawRect(x, y, x + (x0 + w / (float) bands - x - w), y + h, paint);
        }
    }

    private void drawCursor(Canvas c, int x, int y) {
        paint.setColor(Color.BLACK);
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(x, y);
        p.lineTo(x, y + cursorSize * 0.8f);
        p.lineTo(x + cursorSize * 0.55f, y + cursorSize * 0.55f);
        p.close();
        c.drawPath(p, paint);
    }
}
