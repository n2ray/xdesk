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

import com.termux.x11.app.R;

/**
 * A connected Mac's screen (macOS Screen Sharing), full screen on the display it was started on: the Mac's H.264,
 * decoded by the phone's hardware into this SurfaceView. It only shows the picture: the connection (and XDesk's
 * input to it, ScreenShareInput) belongs to ScreenShareSession and outlives this screen, so taking the Mac off the
 * screen to use a Linux or Android app and coming back keeps the Mac connected. Ends itself when the connection
 * ends.
 */
public final class ScreenShareActivity extends Activity implements SurfaceHolder.Callback, ScreenShareSession.PictureListener {
    public static final String EXTRA_HOST = "host";

    private static volatile ScreenShareActivity showing;
    private volatile boolean resumed;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private String hostId;
    private SurfaceView view;
    private com.termux.x11.ScreenShareInput desktopInput;
    private final io.github.n2ray.xdesk.input.MouseButtons buttons=new io.github.n2ray.xdesk.input.MouseButtons();
    private final int[] pictureOrigin=new int[2];

    // X gives coordinates in this virtual display. Use the same Mac input session as HDMI.
    public static boolean windowPointer(int display, int op, int x, int y) {
        ScreenShareActivity a = showing;
        if (a == null || !a.acceptsWindowPointer(display)) return false;
        a.ui.post(() -> {
            if (!a.acceptsWindowPointer(display) || a.view.getWidth() < 1 || a.view.getHeight() < 1) return;
            if (a.phonePointer) {
                a.phonePointer = false;
                if (a.arrow != null) a.arrow.setVisibility(View.GONE);
            }
            a.view.getLocationOnScreen(a.pictureOrigin);
            float fx = (float)(x - a.pictureOrigin[0]) / a.view.getWidth();
            float fy = (float)(y - a.pictureOrigin[1]) / a.view.getHeight();
            int b = op & 15;
            boolean inside = fx >= 0 && fx <= 1 && fy >= 0 && fy <= 1;
            if (!inside && op < 32 && a.windowButtons == 0) return;
            a.desktopInput.moveToFraction(fx, fy);
            if (op == 6) return;
            boolean down = op < 32;
            if (b <= 3) {
                if (down) a.windowButtons |= 1 << b; else a.windowButtons &= ~(1 << b);
                a.desktopInput.button(b, down);
            } else if (down) {
                a.desktopInput.scrollFine(b == 4 ? -1 : b == 5 ? 1 : 0,
                        b == 6 ? -1 : b == 7 ? 1 : 0, io.github.n2ray.xdesk.input.Input.WHEEL);
            }
        });
        return true;
    }
    private int windowButtons;
    private boolean acceptsWindowPointer(int display) {
        return resumed && showing == this && desktopInput != null && getDisplay() != null
                && getDisplay().getDisplayId() == display;
    }
    @Override protected void onResume() { super.onResume(); resumed = true; }
    @Override protected void onPause() {
        resumed = false;
        windowButtons = 0;
        phonePointer = false;
        if (desktopInput != null && arrow != null) arrow.setVisibility(View.GONE);
        if (desktopInput != null) desktopInput.releaseAll();
        super.onPause();
    }

    public interface PadControls {
        boolean handle(int x, int y, int op, int button, boolean down);
    }
    private boolean phonePointer;
    private final float[] padPosition = new float[2];

    /** The phone controller and the viewer share the main thread and the authenticated input session. */
    public static boolean fullscreenPad(int display, int op, float x, float y, PadControls controls) {
        ScreenShareActivity a = showing;
        if (a == null || !a.acceptsWindowPointer(display) || a.view.getWidth() < 1) return false;
        a.phonePointer = true;
        if (op == 1) a.desktopInput.move(Math.round(x), Math.round(y));
        if (!a.desktopInput.pointerPlace(a.padPosition)) return false;
        a.view.getLocationOnScreen(a.pictureOrigin);
        int px = a.pictureOrigin[0] + Math.round(a.padPosition[0] * (a.view.getWidth() - 1));
        int py = a.pictureOrigin[1] + Math.round(a.padPosition[1] * (a.view.getHeight() - 1));
        if (controls != null && controls.handle(px, py, op, (int)x, y != 0)) return true;
        if (op == 2) a.desktopInput.button((int)x, y != 0);
        if (op == 3) a.desktopInput.scrollFine(y,x,io.github.n2ray.xdesk.input.Input.WHEEL);
        return true;
    }

    private boolean desktopPointer(android.view.MotionEvent e) {
        if (desktopInput==null || view==null || view.getWidth()==0 || view.getHeight()==0) return false;
        if (phonePointer) {
            phonePointer = false;
            if (arrow != null) arrow.setVisibility(View.GONE);
        }
        view.getLocationOnScreen(pictureOrigin);
        float x=(e.getRawX()-pictureOrigin[0])/view.getWidth(), y=(e.getRawY()-pictureOrigin[1])/view.getHeight();
        int action=e.getActionMasked();
        boolean inside=x>=0 && x<=1 && y>=0 && y<=1;
        if (!inside && !buttons.holding() && action!=android.view.MotionEvent.ACTION_UP
                && action!=android.view.MotionEvent.ACTION_CANCEL) return true;
        desktopInput.moveToFraction(x,y);
        if (e.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
            buttons.event(action,e.getActionButton(),e.getButtonState(),desktopInput::button);
            if (action==android.view.MotionEvent.ACTION_SCROLL)
                desktopInput.scrollFine(-e.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL),
                    e.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL),0);
        } else if (action==android.view.MotionEvent.ACTION_DOWN) desktopInput.button(1,true);
        else if (action==android.view.MotionEvent.ACTION_UP || action==android.view.MotionEvent.ACTION_CANCEL) desktopInput.button(1,false);
        return true;
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        return desktopPointer(e) || super.dispatchTouchEvent(e);
    }
    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        return (e.isFromSource(android.view.InputDevice.SOURCE_MOUSE) && desktopPointer(e)) || super.dispatchGenericMotionEvent(e);
    }
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (desktopInput!=null && e.getScanCode()>0 && (e.getAction()==0 || e.getAction()==1)) {
            desktopInput.key(e.getScanCode(),e.getAction()==0); return true;
        }
        return super.dispatchKeyEvent(e);
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus && desktopInput!=null) { windowButtons=0; buttons.clear(); desktopInput.releaseAll(); }
    }
    /** The pointer: Screen Sharing's fast mode does not put the Mac's cursor in the picture, so it is drawn here. */
    private ArrowView arrow;
    private int pointerX = -1, pointerY = -1;
    private ScreenShareSession session;
    private boolean sampling;

    public static boolean isShowing(String hostId) {
        ScreenShareActivity a = showing;
        return a != null && hostId.equals(a.hostId) && !a.isFinishing();
    }

    /** Where XDesk's pointer is on this Mac's screen, in its pixels (ScreenShareInput). Any thread. */
    public static void pointerAt(String hostId, int x, int y) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(() -> pointerAt(hostId, x, y));
            return;
        }
        ScreenShareActivity a = showing;
        if (a == null || !hostId.equals(a.hostId) || a.isFinishing()) {
            logArrow("no screen for " + hostId + " (showing " + (a != null ? a.hostId : "none") + ")");
            return;
        }
        a.pointerX = x;
        a.pointerY = y;
        a.placeArrow();
    }

    private static long arrowLoggedAt;

    /** At most once every 2 s: the pointer is drawn or why not. */
    private static void logArrow(String what) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - arrowLoggedAt < 2000) return;
        arrowLoggedAt = now;
        Log.i(ScreenShareSession.TAG, "pointer " + what);
    }

    @Override public void cursorChanged(CursorUpdates.Shape shape) {
        if (arrow == null) return;
        if(desktopInput!=null && view!=null) {
            view.setPointerIcon(shape==null?null:android.view.PointerIcon.create(shape.bitmap,Math.max(0,Math.min(shape.hotX,shape.bitmap.getWidth()-1)),Math.max(0,Math.min(shape.hotY,shape.bitmap.getHeight()-1))));
        }
        arrow.shape = shape;
        arrow.invalidate();
        placeArrow();
    }

    private void placeArrow() {
        if (desktopInput!=null && !phonePointer) return;
        if (arrow == null || session == null || pointerX < 0 || view.getWidth() < 2) {
            logArrow("not drawn: arrow " + (arrow != null) + " view " + view.getWidth() + " at " + pointerX);
            return;
        }
        logArrow("at " + pointerX + "," + pointerY + " in " + view.getWidth() + "x" + view.getHeight() + "+" + view.getLeft() + "," + view.getTop());
        arrow.setVisibility(View.VISIBLE);
        // Placed by layout, not translation: the window's transparent region (the SurfaceView's hole, which also
        // made the system leave the whole window out of the composition) is gathered again only after a layout
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) arrow.getLayoutParams();
        CursorUpdates.Shape shape = arrow.shape;
        float sx = (float) view.getWidth() / session.width;
        float sy = (float) view.getHeight() / session.height;
        int w = shape == null ? arrow.w() : Math.max(1, Math.round(shape.bitmap.getWidth() * sx));
        int h = shape == null ? arrow.h() : Math.max(1, Math.round(shape.bitmap.getHeight() * sy));
        int left = Math.round(view.getLeft() + (float) pointerX * view.getWidth() / session.width - (shape == null ? arrow.tip : shape.hotX * sx));
        int top = Math.round(view.getTop() + (float) pointerY * view.getHeight() / session.height - (shape == null ? arrow.tip : shape.hotY * sy));
        if (lp.leftMargin != left || lp.topMargin != top || lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            lp.leftMargin = left;
            lp.topMargin = top;
            arrow.setLayoutParams(lp);
        }
    }

    /**
     * The same arrow as a Sill Mac's until it sends its own (MacActivity.defaultArrow): black, white edge, the Mac's
     * size (u: picture pixels per Mac pixel), its tip at (tip, tip).
     */
    private static final class ArrowView extends View {
        final int tip;
        CursorUpdates.Shape shape;
        private final android.graphics.Rect bounds = new android.graphics.Rect();
        private final android.graphics.Path path = new android.graphics.Path();
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float u;

        ArrowView(android.content.Context c, float u) {
            super(c);
            this.u = u;
            tip = Math.round(1.5f * u);
            path.moveTo(1.5f * u, 1.5f * u);
            path.lineTo(1.5f * u, 19f * u);
            path.lineTo(5.8f * u, 15f * u);
            path.lineTo(8.8f * u, 22f * u);
            path.lineTo(11.6f * u, 20.8f * u);
            path.lineTo(8.7f * u, 14f * u);
            path.lineTo(14.5f * u, 14f * u);
            path.close();
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            paint.setStrokeWidth(2f * u);
        }

        int w() { return Math.round(17 * u); }
        int h() { return Math.round(25 * u); }

        /**
         * The SurfaceView marks its whole area transparent for the composition; a view without a background gives
         * none of it back, so the window seemed empty and was left out: no arrow. Its own square is not transparent.
         */
        @Override public boolean gatherTransparentRegion(android.graphics.Region region) {
            if (region != null && getVisibility() == VISIBLE) {
                int[] at = new int[2];
                getLocationInWindow(at);
                region.op(at[0], at[1], at[0] + getWidth(), at[1] + getHeight(), android.graphics.Region.Op.DIFFERENCE);
            }
            return true;
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            if (shape != null) {
                bounds.set(0, 0, getWidth(), getHeight());
                c.drawBitmap(shape.bitmap, null, bounds, null);
                return;
            }
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setColor(Color.WHITE);
            c.drawPath(path, paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(Color.BLACK);
            c.drawPath(path, paint);
        }
    }

    public static void finishFor(String hostId) {
        ScreenShareActivity a = showing;
        if (a != null && hostId.equals(a.hostId)) a.finish();
    }

    /** 正在连接, until the first picture. */
    private com.termux.x11.ConnectingCard connecting;
    private int stageShown;
    private final Runnable showStage = new Runnable() {
        @Override public void run() {
            ScreenShareSession s = session;
            com.termux.x11.ConnectingCard c = connecting;
            if (s == null || c == null) return;
            int now = s.stage;
            if (now != stageShown) {
                stageShown = now;
                int line = now == ScreenShareSession.STAGE_LOGIN ? R.string.xd_ss_stage_login
                    : now == ScreenShareSession.STAGE_MEDIA ? R.string.xd_ss_stage_media
                    : now == ScreenShareSession.STAGE_PICTURE ? R.string.xd_ss_stage_picture : R.string.xd_ss_stage_connect;
                c.step(getString(line));
            }
            ui.postDelayed(this, 250);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        hostId = getIntent().getStringExtra(EXTRA_HOST);
        ScreenShareSession s = ScreenShareSession.current();
        if (hostId == null || s == null || !s.host.id.equals(hostId) || !s.alive()) {
            finish();
            return;
        }
        session = s;
        if (getIntent().getBooleanExtra("xdesx_input",false)) desktopInput=new com.termux.x11.ScreenShareInput(hostId);
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
        // The picture as large as it fits, in its own proportions on the panel: the window's size (B) may have another
        // shape than the output mode (P) when Android squeezes a display whose EDID millimetres are wrong (a 2560x1440
        // AOC given 2560x1508) and PanelFit is not on; the whole window is then mapped onto the whole mode, so the
        // picture is drawn that much narrower here and comes out right there (nx2android's external_aspect, owner
        // 22 November). With PanelFit the window is the panel's size and the factor is 1: never corrected twice.
        frame.addOnLayoutChangeListener((v, l, t, r, bottom, ol, ot, or, ob) -> {
            int bw = r - l, bh = bottom - t;
            if (bw < 2 || bh < 2) return;
            int[] p = ScreenShareSizing.output(getDisplay());
            double factor = p != null ? ((double) p[0] / p[1]) / ((double) bw / bh) : 1;
            if (!(factor >= 0.25 && factor <= 4)) factor = 1;
            double cw = width / factor;
            double k = Math.min(bw / cw, (double) bh / height);
            int w = (int) Math.floor(cw * k), hh = (int) Math.floor(height * k);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, hh, Gravity.CENTER);
            if (view.getWidth() != w || view.getHeight() != hh) {
                Log.i(ScreenShareSession.TAG, "PICTURE in " + bw + "x" + bh + " (mode " + (p != null ? p[0] + "x" + p[1] : "?")
                    + ", factor " + String.format(java.util.Locale.ROOT, "%.4f", factor) + "): " + w + "x" + hh
                    + " at " + (bw - w) / 2 + "," + (bh - hh) / 2);
                view.post(() -> view.setLayoutParams(lp));
            }
        });
        view.getHolder().setFixedSize(width, height);
        view.getHolder().addCallback(this);
        // The Mac's own pointer size: its desktop is drawn this many screen pixels per Mac point (twice as many
        // with 大字's HiDPI, a third more with 适度放大), the picture as large as it fits
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float u = Math.max(1f, Math.min((float) dm.widthPixels / s.sizing.desktopW, (float) dm.heightPixels / s.sizing.desktopH));
        arrow = new ArrowView(this, u);
        arrow.setVisibility(View.INVISIBLE);
        frame.addView(arrow, new FrameLayout.LayoutParams(arrow.w(), arrow.h(), Gravity.TOP | Gravity.START));
        view.addOnLayoutChangeListener((v, l, t, r, bottom, ol, ot, or, ob) -> placeArrow());
        // Black until the first picture (about ten seconds after the password): say what is going on
        if (!s.hadPicture()) {
            connecting = new com.termux.x11.ConnectingCard(this, getDisplay(), getString(R.string.xd_ss_connecting, s.host.label()));
            frame.addView(connecting, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            stageShown = -1;
            showStage.run();
        }
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
        ui.removeCallbacks(showStage);
        sampling = false;
        if (session != null) session.detach();
        if (showing == this) showing = null;
        super.onDestroy();
    }

    @Override public void firstFrame(long ms) {
        if (connecting != null) {
            ui.removeCallbacks(showStage);
            ((android.view.ViewGroup) connecting.getParent()).removeView(connecting);
            connecting = null;
        }
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
