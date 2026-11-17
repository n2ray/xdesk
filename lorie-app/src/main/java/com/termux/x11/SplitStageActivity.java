package com.termux.x11;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.termux.x11.app.R;
import rikka.shizuku.Shizuku;

/**
 * DISPLAY.md §15, the split-screen prototype: two Android apps side by side on the external display, each on its own
 * virtual display (made by the injector, like the car's) drawn into one of this activity's SurfaceViews. Throwaway
 * quality: it exists to answer whether this runs smoothly and whether the mouse works each side separately.
 *
 * <p>It does NOT start the injector's evdev reader (no configure): the phone's captured mouse and keys come from
 * XDeskActivity as an InputSink, like X and Mac content. XDeskActivity routes them here while {@link #isActive()}.
 */
public final class SplitStageActivity extends Activity {
    private static final String TAG = "XDeskSplit";
    static final String EXTRA_APPS = "apps", EXTRA_LABELS = "labels";
    private static final int LEFT = 0, RIGHT = 1;
    /** The captured mouse's gain, as the injector's for a physical mouse (InjectorService): relative to 2560 px. */
    private static final float MOUSE_GAIN = 2f, MOUSE_REFERENCE_WIDTH = 2560f;
    /** Two Esc presses this close close the stage (a back button is enough for a prototype, Ctrl+Alt+M gives the input back). */
    private static final long ESC_ESC_MS = 700;
    private static final long READOUT_MS = 500, FAIL_DELAY_MS = 3000, BIND_TIMEOUT_MS = 15000;

    // --- The one stage, seen by XDeskActivity (all on the main thread) ---

    private static volatile SplitStageActivity active;
    /** Set once by XDeskActivity; runs on the main thread when the stage is created or destroyed. */
    private static volatile Runnable observer;

    static boolean isActive() {
        return active != null;
    }

    static void observe(Runnable r) {
        observer = r;
    }

    private static void changed() {
        Runnable r = observer;
        if (r != null) r.run();
    }

    /** Where XDeskActivity's Router sends the captured mouse and the phone's keys while the stage is up. */
    static final InputSink input = new InputSink() {
        @Override public void move(int dx, int dy) {
            SplitStageActivity a = active;
            if (a != null) a.onMouseMove(dx, dy);
        }
        @Override public void button(int button, boolean down) {
            SplitStageActivity a = active;
            if (a != null) a.onMouseButton(button, down);
        }
        @Override public void click(int button) {
            SplitStageActivity a = active;
            if (a != null) {
                a.onMouseButton(button, true);
                a.onMouseButton(button, false);
            }
        }
        @Override public void scroll(int dy, int dx) {
            SplitStageActivity a = active;
            if (a != null) a.onMouseScroll(dy, dx);
        }
        @Override public void key(int scan, boolean down) {
            SplitStageActivity a = active;
            if (a != null) a.onKey(scan, down);
        }
        @Override public void tapKey(int scan) {
            SplitStageActivity a = active;
            if (a != null) a.onTapKey(scan);
        }
        @Override public void text(String s) {
            SplitStageActivity a = active;
            if (a != null) a.onText(s);
        }
        @Override public boolean pointerPlace(float[] out) {
            SplitStageActivity a = active;
            if (a == null || a.stageW <= 0) return false;
            out[0] = a.arrowX / a.stageW;
            out[1] = a.arrowY / a.stageH;
            return true;
        }
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final int[] displayId = {Display.INVALID_DISPLAY, Display.INVALID_DISPLAY};
    private final int[] sideW = new int[2], sideH = new int[2];
    private final SurfaceView[] surface = new SurfaceView[2];
    private final String[] components = new String[2], labels = new String[2];

    private IInjector injector;
    private Shizuku.UserServiceArgs args;
    private boolean viaShell, bound, finished, shown;
    private int stageDisplayId, stageW, stageH, halfW, halfH, divider, densityDpi;
    private float arrowX, arrowY;
    private int activeSide = LEFT;
    private View arrow;
    private FrameLayout.LayoutParams arrowLp;
    private TextView readout;
    private String errorText;
    private long lastEscDown, lastTick;
    private int frames, lastFps;

    // --- Lifecycle ---

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // The system moves this window to the phone when the external display goes: there it has no purpose
        Display d = getDisplay();
        if (d == null || d.getDisplayId() == Display.DEFAULT_DISPLAY) {
            Log.i(TAG, "on the phone, closing");
            finishAndRemoveTask();
            return;
        }
        String[] apps = getIntent().getStringArrayExtra(EXTRA_APPS);
        String[] names = getIntent().getStringArrayExtra(EXTRA_LABELS);
        if (apps == null || apps.length != 2) {
            Log.w(TAG, "no two apps to show, closing");
            finishAndRemoveTask();
            return;
        }
        components[0] = apps[0];
        components[1] = apps[1];
        if (names != null && names.length == 2) {
            labels[0] = names[0];
            labels[1] = names[1];
        }
        stageDisplayId = d.getDisplayId();
        getSystemService(android.hardware.display.DisplayManager.class).registerDisplayListener(displayGone, null);
        android.graphics.Point real = new android.graphics.Point();
        d.getRealSize(real);
        stageW = real.x;
        stageH = real.y;
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        densityDpi = m.densityDpi;
        // The prototype uses the external screen's density, no b·u (DISPLAY.md §15)
        divider = Style.dp(this, 4);
        halfW = Math.max(1, (stageW - divider) / 2);
        halfH = stageH;
        sideW[LEFT] = sideW[RIGHT] = halfW;
        sideH[LEFT] = sideH[RIGHT] = halfH;
        buildViews();
        Log.i(TAG, "stage on display " + d.getDisplayId() + ", " + stageW + "x" + stageH + " " + densityDpi + " dpi, halves " + halfW + "x" + halfH
            + ", divider " + divider + ", apps " + components[0] + " / " + components[1]);
        active = this;
        changed();
        startReadout();
        bindInjector();
    }

    private void buildViews() {
        getWindow().setDecorFitsSystemWindows(false);
        LinearLayout halves = new LinearLayout(this);
        halves.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 2; i++) {
            surface[i] = new SurfaceView(this);
            final int side = i;
            surface[i].getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder h) {
                    Log.i(TAG, "surface " + side + " created " + h.getSurfaceFrame().width() + "x" + h.getSurfaceFrame().height());
                    attachSide(side, h);
                }
                @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int ht) { }
                @Override public void surfaceDestroyed(SurfaceHolder h) {
                    Log.i(TAG, "surface " + side + " destroyed");
                    detachSide(side);
                }
            });
            halves.addView(surface[i], new LinearLayout.LayoutParams(0, -1, 1));
        }
        View dividerView = new View(this);
        dividerView.setBackgroundColor(Style.SURFACE_RAISED);
        halves.addView(dividerView, new LinearLayout.LayoutParams(divider, -1));
        FrameLayout root = new FrameLayout(this);
        root.addView(halves, new FrameLayout.LayoutParams(-1, -1));
        readout = Style.label(this, "", Style.TEXT, Style.TEXT_SMALL + 1);
        readout.setBackground(Style.rounded(this, Style.SURFACE, Style.RADIUS));
        readout.setPadding(Style.dp(this, 10), Style.dp(this, 4), Style.dp(this, 10), Style.dp(this, 4));
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, -2);
        rp.gravity = Gravity.TOP | Gravity.END;
        rp.topMargin = rp.rightMargin = Style.dp(this, 12);
        root.addView(readout, rp);
        // The arrow inside the window, sized like the overlay cursor (CursorOverlay's window works for Android apps,
        // this stage draws its own pointer)
        arrow = CursorOverlay.arrow(this, Sizing.shell(this, getDisplay()).density);
        android.view.ViewGroup.LayoutParams p = arrow.getLayoutParams();
        arrowLp = new FrameLayout.LayoutParams(p.width, p.height);
        arrowX = halfW / 2f;
        arrowY = stageH / 2f;
        placeArrow();
        root.addView(arrow, arrowLp);
        setContentView(root);
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
    }

    /** The tip of the arrow is at the view's left margin plus arrowTipX, so the margins place the tip at the pointer. */
    private void placeArrow() {
        if (arrowLp == null) return;
        arrowLp.leftMargin = Math.round(arrowX) - CursorOverlay.arrowTipX(arrow);
        arrowLp.topMargin = Math.round(arrowY);
        arrow.setLayoutParams(arrowLp);
    }

    /**
     * When the external display is removed the system moves this window to the phone, where it has no purpose. There
     * is no Activity callback for that move, so watch the display itself.
     */
    private final android.hardware.display.DisplayManager.DisplayListener displayGone =
        new android.hardware.display.DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) { }
            @Override public void onDisplayChanged(int displayId) { }
            @Override public void onDisplayRemoved(int displayId) {
                if (displayId == stageDisplayId) {
                    Log.i(TAG, "display removed, closing");
                    finishAndRemoveTask();
                }
            }
        };

    @Override public void onBackPressed() {
        Log.i(TAG, "Back, closing");
        finishAndRemoveTask();
    }

    @Override protected void onDestroy() {
        finished = true;
        shown = false;
        ui.removeCallbacksAndMessages(null);
        getSystemService(android.hardware.display.DisplayManager.class).unregisterDisplayListener(displayGone);
        IInjector i = injector;
        injector = null;
        // The displays end with the session and their apps go to the phone; shutdown blocks, so off this thread
        if (i != null) {
            new Thread(() -> {
                try {
                    i.shutdown();
                } catch (RemoteException e) {
                    Log.w(TAG, "shutdown", e);
                }
            }, "xdesk-split-stop").start();
        }
        if (args != null && bound) {
            try {
                Shizuku.unbindUserService(args, connection, true);
            } catch (RuntimeException e) {
                Log.w(TAG, "unbind", e);
            }
        }
        if (active == this) {
            active = null;
            changed();
        }
        Log.i(TAG, "stage closed");
        super.onDestroy();
    }

    // --- The injector (its own session, so the stage cannot break app sessions) ---

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            ui.removeCallbacks(bindTimeout);
            bound = true;
            if (binder == null || !binder.pingBinder()) return;
            if (finished) {
                // The stage ended while the injector was starting: end the session too
                try {
                    IInjector.Stub.asInterface(binder).shutdown();
                } catch (RemoteException ignored) { }
                return;
            }
            final IInjector i = IInjector.Stub.asInterface(binder);
            injector = i;
            Log.i(TAG, "injector connected" + (viaShell ? " (shell)" : " (shizuku)"));
            new Thread(() -> {
                try {
                    // attach links the injector to this process: if XDesk dies, the displays are released
                    i.attach(listener, getPackageName());
                    i.asBinder().linkToDeath(injectorDead, 0);
                } catch (RemoteException e) {
                    Log.w(TAG, "cannot attach", e);
                }
            }, "xdesk-split-attach").start();
            makeDisplays();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            ui.post(SplitStageActivity.this::injectorLost);
        }
    };

    /** Nothing the stage does needs these; only the messages are logged. */
    private final IInjectorListener.Stub listener = new IInjectorListener.Stub() {
        @Override public void onCursor(int x, int y) { }
        @Override public void onForwarding(boolean on, int deviceCount) { }
        @Override public void onMessage(String message) {
            Log.w(TAG, "injector: " + message);
        }
        @Override public void onAppInSession(boolean inSession) { }
        @Override public void onButtonUp() { }
        @Override public void onTopApp(String packageName) { }
        @Override public void onFingers(float[] x, float[] y) { }
        @Override public void onEdgePush(int edge, int dx, int dy, boolean held, int x, int y, boolean left) { }
        @Override public void onGameMode(boolean on) { }  // the stage never asks for game passthrough
        @Override public void onClipboard(String text) { }  // the app session's injector watches it (ShareHub)
    };

    private final IBinder.DeathRecipient injectorDead = () -> ui.post(this::injectorLost);

    private final Runnable bindTimeout = () -> fail(getString(R.string.xd_split_no_injector));

    private void bindInjector() {
        AppSession apps = AppSession.get(this);
        viaShell = apps.shizukuState() != AppSession.SHIZUKU_READY;
        Log.i(TAG, "injector through " + (viaShell ? "the shell server" : "Shizuku"));
        if (viaShell) {
            if (!apps.shell().isRunning()) {
                fail(getString(R.string.xd_split_no_injector));
                return;
            }
            apps.shell().openSplitSession(connection);
        } else {
            // Its own tag and process: Shizuku must not hand back the app session's service
            args = new Shizuku.UserServiceArgs(new ComponentName(getPackageName(), InjectorService.class.getName()))
                .daemon(false)
                .processNameSuffix("split")
                .tag("split")
                .debuggable(false)
                .version(ShellActivation.installVersion(this));
            Shizuku.bindUserService(args, connection);
        }
        ui.postDelayed(bindTimeout, BIND_TIMEOUT_MS);
    }

    private void injectorLost() {
        injector = null;
        if (finished) return;
        Log.w(TAG, "injector died");
        fail(getString(R.string.xd_split_no_injector));
    }

    /** A fatal problem: say it, then close. */
    private void fail(String message) {
        showError(message);
        ui.postDelayed(() -> {
            if (!finished) finishAndRemoveTask();
        }, FAIL_DELAY_MS);
    }

    /** The readout shows this instead of the fps while it is set (a start failure keeps the stage open). */
    private void showError(String message) {
        Log.e(TAG, message);
        ui.post(() -> errorText = message);
    }

    // --- The two displays ---

    /** Each side's display, once the injector and the side's surface are both there. */
    private void makeDisplays() {
        for (int side = 0; side < 2; side++) makeDisplay(side, surface[side].getHolder());
    }

    /** Creating a display is a blocking call of the injector's, so it runs off the main thread. */
    private void makeDisplay(final int side, SurfaceHolder h) {
        if (displayId[side] != Display.INVALID_DISPLAY) return;
        final IInjector i = injector;
        if (i == null) return;
        final Surface surf = h.getSurface();
        if (surf == null || !surf.isValid()) return;
        // The surface may still be smaller than the window was at onCreate: its real frame is the truth
        int w = h.getSurfaceFrame().width(), ht = h.getSurfaceFrame().height();
        if (w > 0 && ht > 0) {
            sideW[side] = w;
            sideH[side] = ht;
        }
        final int width = sideW[side], height = sideH[side], dpi = densityDpi;
        new Thread(() -> {
            try {
                final int id = i.createVirtualDisplay("XDeskSplit", width, height, dpi, surf, 0);
                ui.post(() -> {
                    displayId[side] = id;
                    Log.i(TAG, "display " + id + " made for side " + side + ", " + width + "x" + height + " " + dpi + " dpi");
                    startOn(side);
                });
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "no display for side " + side, e);
            }
        }, "xdesk-split-display").start();
    }

    /** The surface came back with the same size: the display and its apps stay, only the surface changes. */
    private void attachSide(int side, SurfaceHolder h) {
        IInjector i = injector;
        if (i == null) return;
        // The first surface may have come and gone before the injector: only now can its display be made
        if (displayId[side] == Display.INVALID_DISPLAY) {
            makeDisplay(side, h);
            return;
        }
        try {
            i.setVirtualDisplaySurface(displayId[side], h.getSurface());
            Log.i(TAG, "surface attached to display " + displayId[side] + ", side " + side);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "surface not attached, side " + side, e);
        }
    }

    /** Null detaches: the display and its apps stay, as with the screen off. */
    private void detachSide(int side) {
        IInjector i = injector;
        if (i == null || displayId[side] == Display.INVALID_DISPLAY) return;
        try {
            i.setVirtualDisplaySurface(displayId[side], null);
            Log.i(TAG, "surface detached from display " + displayId[side] + ", side " + side);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "surface not detached, side " + side, e);
        }
    }

    /** am start can take a while: off the main thread. */
    private void startOn(final int side) {
        final IInjector i = injector;
        final int id = displayId[side];
        final String component = components[side], label = labels[side] != null ? labels[side] : components[side];
        if (i == null) return;
        new Thread(() -> {
            String error;
            try {
                error = i.startApp(component, id);
            } catch (RemoteException e) {
                error = e.toString();
            }
            Log.i(TAG, "start " + component + " on display " + id + ": " + (error == null ? "ok" : error));
            if (error != null) showError(getString(R.string.xd_split_start_failed, label, error));
        }, "xdesk-split-start").start();
    }

    // --- Input: the captured mouse moves the arrow; the side under it is active (DISPLAY.md §2.2) ---

    private void onMouseMove(float dx, float dy) {
        float gain = MOUSE_GAIN * stageW / MOUSE_REFERENCE_WIDTH;
        arrowX = Math.max(0, Math.min(stageW - 1, arrowX + dx * gain));
        arrowY = Math.max(0, Math.min(stageH - 1, arrowY + dy * gain));
        placeArrow();
        switchTo(sideAt(arrowX));
        injectMove();
    }

    /** The divider belongs to the nearer half, so the side follows the arrow without a dead strip. */
    private int sideAt(float x) {
        return x < halfW + divider / 2f ? LEFT : RIGHT;
    }

    private void switchTo(int side) {
        if (side == activeSide) return;
        activeSide = side;
        Log.d(TAG, "active side " + (side == LEFT ? "left" : "right") + ", display " + displayId[side]);
        IInjector i = injector;
        if (i == null || displayId[side] == Display.INVALID_DISPLAY) return;
        try {
            // setDisplay lets go of everything held on the old display before anything reaches the new one
            i.focusDisplay(displayId[side]);
            i.setDisplay(displayId[side], sideW[side], sideH[side]);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "side switch failed", e);
        }
    }

    private void injectMove() {
        IInjector i = injector;
        if (i == null || displayId[activeSide] == Display.INVALID_DISPLAY) return;
        try {
            i.moveToFraction(localX(), localY());
        } catch (RemoteException e) {
            injectorLost();
        }
    }

    /** The arrow's place in the active half, 0 to 1: what moveToFraction wants. */
    private float localX() {
        float x = arrowX - (activeSide == LEFT ? 0 : halfW + divider);
        return Math.max(0, Math.min(1f, x / Math.max(1, sideW[activeSide] - 1)));
    }

    private float localY() {
        return Math.max(0, Math.min(1f, arrowY / Math.max(1, sideH[activeSide] - 1)));
    }

    private void onMouseButton(int button, boolean down) {
        IInjector i = injector;
        if (i == null || displayId[activeSide] == Display.INVALID_DISPLAY) return;
        try {
            i.button(button, down);
        } catch (RemoteException e) {
            injectorLost();
        }
    }

    private void onMouseScroll(float dy, float dx) {
        IInjector i = injector;
        if (i == null || displayId[activeSide] == Display.INVALID_DISPLAY) return;
        try {
            i.scroll((int) dy, (int) dx);
        } catch (RemoteException e) {
            injectorLost();
        }
    }

    private void onKey(int scan, boolean down) {
        if (down && scan == KeyInputView.SCAN_ESC) {
            long now = SystemClock.uptimeMillis();
            if (now - lastEscDown < ESC_ESC_MS) {
                Log.i(TAG, "Esc Esc, closing");
                finishAndRemoveTask();
                return;
            }
            lastEscDown = now;
        } else if (down && scan != KeyInputView.SCAN_ESC) {
            lastEscDown = 0;
        }
        IInjector i = injector;
        if (i == null || displayId[activeSide] == Display.INVALID_DISPLAY) return;
        try {
            i.key(scan, down);
        } catch (RemoteException e) {
            injectorLost();
        }
    }

    private void onTapKey(int scan) {
        IInjector i = injector;
        if (i == null) return;
        try {
            i.tapKey(scan);
        } catch (RemoteException e) {
            injectorLost();
        }
    }

    private void onText(String s) {
        IInjector i = injector;
        if (i == null) return;
        try {
            i.text(s);
        } catch (RemoteException e) {
            injectorLost();
        }
    }

    // --- The readout: which side is active and the fps (DISPLAY.md §15 asks for the latter) ---

    private void startReadout() {
        shown = true;
        lastTick = SystemClock.uptimeMillis();
        Choreographer.getInstance().postFrameCallback(frame);
        ui.postDelayed(readoutTick, READOUT_MS);
    }

    /**
     * Counts the stage window's frames, one number for both sides: a virtual display's own frames are not visible to
     * this process, so per-side fps would need dumpsys SurfaceFlinger --latency (a later experiment). The number here
     * still catches the stage process falling behind the display.
     */
    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long t) {
            if (!shown) return;
            frames++;
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private final Runnable readoutTick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            long elapsed = Math.max(1, now - lastTick);
            lastFps = Math.round(frames * 1000f / elapsed);
            frames = 0;
            lastTick = now;
            if (errorText != null) readout.setText(errorText);
            else {
                String fps = getString(R.string.xd_split_readout, lastFps, lastFps);
                readout.setText((activeSide == LEFT ? "◀ " : "") + fps + (activeSide == RIGHT ? " ▶" : ""));
            }
            ui.postDelayed(this, READOUT_MS);
        }
    };
}
