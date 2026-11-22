// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11.screenshare;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * A connected Mac's screen (macOS Screen Sharing), full screen on the display it was started on: the Mac's H.264,
 * decoded by the phone's hardware into this SurfaceView. It only shows the picture: the connection (and XDesk's
 * input to it, ScreenShareInput) belongs to ScreenShareSession and outlives this screen, so taking the Mac off the
 * screen to use a Linux or Android app and coming back keeps the Mac connected. Ends itself when the connection
 * ends.
 */
public final class ScreenShareActivity extends Activity implements SurfaceHolder.Callback, ScreenShareSession.PictureListener {
    public static final String EXTRA_HOST = "host";

    private static ScreenShareActivity showing;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private String hostId;
    private SurfaceView view;
    private ScreenShareSession session;
    private boolean sampling;

    public static boolean isShowing(String hostId) {
        ScreenShareActivity a = showing;
        return a != null && hostId.equals(a.hostId) && !a.isFinishing();
    }

    public static void finishFor(String hostId) {
        ScreenShareActivity a = showing;
        if (a != null && hostId.equals(a.hostId)) a.finish();
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        hostId = getIntent().getStringExtra(EXTRA_HOST);
        ScreenShareSession s = ScreenShareSession.current();
        if (hostId == null || s == null || !s.host.id.equals(hostId) || !s.alive()) {
            finish();
            return;
        }
        session = s;
        if (showing != null && showing != this) showing.finish();
        showing = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        final int width = s.width, height = s.height;
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        view = new SurfaceView(this);
        frame.addView(view, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        // The picture as large as it fits, in its own proportions
        frame.addOnLayoutChangeListener((v, l, t, r, bottom, ol, ot, or, ob) -> {
            int w = Math.min(r - l, (bottom - t) * width / height);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, w * height / width, Gravity.CENTER);
            if (view.getWidth() != w || view.getHeight() != lp.height) view.post(() -> view.setLayoutParams(lp));
        });
        view.getHolder().setFixedSize(width, height);
        view.getHolder().addCallback(this);
        setContentView(frame);
        Log.i(ScreenShareSession.TAG, "SCREEN display=" + getDisplay().getDisplayId() + " picture=" + width + "x" + height);
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        if (session != null && session.alive()) session.attach(h.getSurface(), this);
        else finish();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        sampling = false;
        if (session != null) session.detach();
    }

    @Override protected void onDestroy() {
        sampling = false;
        if (session != null) session.detach();
        if (showing == this) showing = null;
        super.onDestroy();
    }

    @Override public void firstFrame(long ms) {
        final ScreenShareSession s = session;
        if (s == null || !s.host.measure || sampling) return;
        // Test only: the time bar read off the picture, and the numbers of the 35 seconds from here
        s.resetStats();
        sampling = s.clockCalibrated;
        if (sampling) sample();
        ui.postDelayed(() -> { if (s == session) s.summary(); }, 35000);
    }

    /** Test only: reads the Mac's time bar (Marker.swift) off the picture, against the calibrated clock. */
    private void sample() {
        final ScreenShareSession s = session;
        if (!sampling || s == null || view.getWidth() < 1) return;
        final Bitmap bm = Bitmap.createBitmap(256, 144, Bitmap.Config.ARGB_8888);
        PixelCopy.request(view, bm, result -> {
            long done = System.nanoTime();
            if (sampling && result == PixelCopy.SUCCESS) {
                long bits = 0;
                for (int i = 0; i < 48; i++) bits = (bits << 1) | ((bm.getPixel(34 + 4 * i, 72) & 255) > 128 ? 1 : 0);
                long tick = (bits >> 8) & 0xffffffffL;
                if ((bits >>> 40) == 0xd3 && ((tick ^ (tick >> 8) ^ (tick >> 16) ^ (tick >> 24)) % 256) == (bits & 255)) {
                    long age = ((done - s.offsetNs) / 1000000 - tick) & 0xffffffffL;
                    if (age < 10000) s.addAge(age);
                }
            }
            bm.recycle();
            if (sampling) ui.postDelayed(this::sample, 100);
        }, ui);
    }
}
