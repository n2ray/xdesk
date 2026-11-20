package io.github.n2ray.xdesk.receiver;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * XDesk Receiver: shows a display streamed from XDesk on a phone. The server, mDNS registration and decoder live
 * while the activity is started and its surface exists; going to the background stops them all.
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback, Receiver.Ui {
    static final String TAG = "xdesk-receiver";
    static final String SERVICE_TYPE = "_xdesk-display._tcp";

    private enum State { IDLE, PAIRING, CONNECTING, STREAMING, ERROR }

    private Handler ui;
    private Store store;
    private Receiver receiver;
    private FrameLayout root;
    private SurfaceView view;
    private View overlay;
    private LinearLayout idleBox, pairBox, messageBox;
    private TextView idleTitle, idleHint, idleAddress, idleBody, footer;
    private TextView pairTitle, pairHint, pairBody;
    private Chrome.Code pairCode;
    private TextView messageTitle, messageBody;

    private boolean started, running, portBusy;
    private SurfaceHolder holder;
    private State state = State.IDLE;
    private String phone, pairingCode, error;
    private boolean pairingRetry;
    private int videoWidth, videoHeight;
    /** The Mac's cursor over a passed-through Mac picture (PROTOCOL.md CURSOR_SHAPE): fractions of the picture */
    private android.widget.ImageView cursor;
    private float cursorW, cursorH, cursorHotX, cursorHotY, cursorX, cursorY;

    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    private WifiManager.MulticastLock multicast;
    private DisplayManager displays;

    // This device working the phone: touch, mouse and keyboard, the input method, the toolbar and the clipboard
    private static final long BAR_OPEN_MS = 6000;
    private Control control;
    private ImeView ime;
    private TextView modeTool;
    private Chrome.Icon modeIcon;
    private TextView overviewTool, switcherTool;
    /** What the phone said in FEATURES: bit 0 it handles the Overview and Switcher buttons (ANDROID 4 and 5). */
    private int features;
    /** From the phone: the Android app in front takes touches. */
    private boolean phoneWantsTouch;
    /** Chosen in the toolbar: true touchpad, false direct touch, null as the phone says. Until the app changes. */
    private Boolean padOverride;
    /** The phone said it cannot switch the app in front (TOUCH_MODE): the handle switches only here until the app changes. */
    private boolean touchModeLocal;
    /** A Mac is passed through: the cursor is drawn here, and this device's own pointer would be a wrong second one. */
    private boolean passthrough;
    /** The last INPUT_MODE from the phone (0 Android apps, 1 desktop, 2 an app that takes touches, 3 desktop whose
     *  cursor is in the picture, FEATURES bit 3), -1 until the first. */
    private int phoneInputMode = -1;
    /** FEATURES bit 3: the phone says whether the picture contains a cursor (INPUT_MODE 3), hide this device's own. */
    private static final int FEATURE_PICTURE_CURSOR = 8;
    /** applyPointerIcon hid this device's pointer. */
    private boolean pointerHidden;
    private LinearLayout bar, tools;
    private ClipboardManager clipboard;
    private String clipFromPhone;
    private final Runnable closeBar = () -> openBar(false);
    private final ClipboardManager.OnPrimaryClipChangedListener clipChanged = this::sendClipboard;

    private final Runnable refreshIdle = new Runnable() {
        @Override
        public void run() {
            // Addresses change when Wi-Fi reconnects
            if (state == State.IDLE) render();
            ui.postDelayed(this, 5000);
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int id) {
        }

        @Override
        public void onDisplayRemoved(int id) {
        }

        @Override
        public void onDisplayChanged(int id) {
            // A TV box switching HDMI modes changes the size without a configuration change
            if (id == Display.DEFAULT_DISPLAY) updateScreen();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Handler(Looper.getMainLooper());
        store = new Store(this);
        receiver = new Receiver(store, this);
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        displays = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicast = wifi.createMulticastLock("xdesk-receiver");
            multicast.setReferenceCounted(false);
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            // Use the whole screen on phones with a cutout; the picture is letterboxed anyway
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        // The input method may cover the picture's bottom; panning it would move what the touches land on
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        receiver.downloads = new Downloads(this, where -> ui.post(() ->
                Toast.makeText(this, getString(R.string.file_saved, where), Toast.LENGTH_LONG).show()));

        root = new FrameLayout(this) {
            /**
             * Nothing of this window is reported as transparent (as XDesk's MacActivity). A SurfaceView has its
             * window tell the compositor that the picture's rectangle is see-through, minus the views over it where
             * they were at the last layout, and the compositor leaves that region of the window out. The cursor moves
             * by translation, with no layout: anywhere but where the last layout found it (the last shape change, or
             * its last showing) it would be in that region and not drawn. The hole itself is still cleared in the
             * window's pixels, so the picture shows through as before.
             */
            @Override public boolean gatherTransparentRegion(android.graphics.Region region) {
                if (region != null) {
                    int[] at = new int[2];
                    getLocationInWindow(at);
                    region.op(at[0], at[1], at[0] + getWidth(), at[1] + getHeight(), android.graphics.Region.Op.DIFFERENCE);
                }
                return true;
            }
        };
        root.setBackgroundColor(Color.BLACK);
        view = new SurfaceView(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        cursor = new android.widget.ImageView(this);
        cursor.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        cursor.setVisibility(View.GONE);
        root.addView(cursor, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.START));
        control = new Control(receiver, view, ui);
        ime = new ImeView(this, control);
        root.addView(ime, new FrameLayout.LayoutParams(1, 1));
        root.setOnTouchListener((v, e) -> controlling() && control.onTouch(e));
        root.setOnHoverListener((v, e) -> controlling() && control.onMouse(e));
        root.setOnGenericMotionListener((v, e) -> controlling() && e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
                && control.onMouse(e));
        root.addView(bar = buildBar(), new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        root.addView(overlay = buildOverlay(), new FrameLayout.LayoutParams(-1, -1));
        // Rotation keeps the activity: fit the picture to the new size
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) fitVideo();
        });
        setContentView(root);
        view.getHolder().addCallback(this);
        render();
    }

    private View buildOverlay() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(Chrome.radial());
        // Phones: long-press anywhere. TV remotes: Menu, or a long press on OK (see onKeyLongPress)
        frame.setOnLongClickListener(v -> {
            showOptions();
            return true;
        });

        // Waiting: the mark, the name, the hint and the address, as RecvIdle.dc.html has them
        idleBox = column();
        FrameLayout mark = new FrameLayout(this);
        mark.addView(new Chrome.Rings(this), new FrameLayout.LayoutParams(dp(200), dp(200), Gravity.CENTER));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.xdesk_ic_fg);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setBackground(Chrome.rounded(this, 0xFF11151C, 24));
        mark.addView(logo, new FrameLayout.LayoutParams(dp(104), dp(104), Gravity.CENTER));
        idleBox.addView(mark, new LinearLayout.LayoutParams(dp(200), dp(200)));
        idleTitle = titleLabel(34);
        idleHint = label(20, Chrome.HINT);
        idleAddress = label(16, Chrome.DIM);
        idleAddress.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        idleBody = label(18, Chrome.HINT);
        add(idleBox, idleTitle, 18);
        add(idleBox, idleHint, 18);
        add(idleBox, idleAddress, 18);
        add(idleBox, idleBody, 12);
        frame.addView(idleBox, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        // Pairing: the name, four digit boxes and the hint, with a footer of its own (RecvPairing.dc.html)
        pairBox = column();
        pairTitle = titleLabel(30);
        pairCode = new Chrome.Code(this);
        pairHint = label(20, Chrome.HINT);
        pairBody = label(16, Chrome.WARN);
        pairBox.addView(pairTitle);
        LinearLayout.LayoutParams digits = new LinearLayout.LayoutParams(-1, dp(140));
        digits.topMargin = dp(28);
        pairBox.addView(pairCode, digits);
        add(pairBox, pairHint, 28);
        add(pairBox, pairBody, 12);
        frame.addView(pairBox, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        // Connecting and errors: a line or two, as before
        messageBox = column();
        messageTitle = titleLabel(30);
        messageBody = label(20, Chrome.HINT);
        messageBox.addView(messageTitle);
        add(messageBox, messageBody, 12);
        frame.addView(messageBox, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        footer = label(13, Chrome.FAINT);
        footer.setPadding(dp(24), dp(24), dp(24), dp(26));
        frame.addView(footer, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return frame;
    }

    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        box.setPadding(pad, pad, pad, pad);
        return box;
    }

    private TextView label(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.25f);
        t.setIncludeFontPadding(false);
        t.setPadding(0, 0, 0, 0);
        return t;
    }

    private TextView titleLabel(float sp) {
        TextView t = label(sp, Chrome.TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private void add(LinearLayout box, View child, int gapDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(gapDp);
        box.addView(child, lp);
    }

    /** The handle on the right edge: Keyboard, the input mode, Back, Home, Launchpad, Switcher, Disconnect. */
    private LinearLayout buildBar() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setGravity(Gravity.CENTER_HORIZONTAL);
        tools.setBackground(Chrome.shell(this, 20));
        tools.setPadding(dp(6), dp(6), dp(6), dp(6));
        tools.addView(tool(R.string.bar_keyboard, Chrome.KEYBOARD, Chrome.TEXT, v -> showKeyboard()));
        // Shows the mode now, as the phone's button does. Over an Android app it is the phone's one choice for
        // that app (FEATURES bit 6): asked of the phone, which tells both sides; elsewhere only this device switches
        modeIcon = new Chrome.Icon(this, Chrome.TABLET, Chrome.TEXT);
        modeTool = tool(R.string.bar_touchpad, modeIcon, Chrome.TEXT, v -> switchPadMode());
        tools.addView(modeTool);
        tools.addView(separator());
        tools.addView(tool(R.string.bar_back, Chrome.BACK, Chrome.TEXT, v -> control.android(Control.ANDROID_BACK)));
        tools.addView(tool(R.string.bar_home, Chrome.HOME, Chrome.TEXT, v -> control.android(Control.ANDROID_HOME)));
        // Only for a phone that said FEATURES bit 0: the switchboard and one step of its app switcher
        overviewTool = tool(R.string.bar_overview, Chrome.LAUNCHPAD, Chrome.TEXT,
                v -> control.android(Control.ANDROID_OVERVIEW));
        tools.addView(overviewTool);
        switcherTool = tool(R.string.bar_switcher, Chrome.SWITCHER, Chrome.TEXT,
                v -> control.android(Control.ANDROID_SWITCHER));
        tools.addView(switcherTool);
        tools.addView(separator());
        tools.addView(tool(R.string.bar_disconnect, Chrome.DISCONNECT, Chrome.STOP, v -> {
            openBar(false);
            receiver.disconnect();
        }));
        applyFeatures();
        tools.setVisibility(View.GONE);
        b.addView(tools);
        FrameLayout handle = new FrameLayout(this);
        View pill = new View(this);
        pill.setBackground(Chrome.rounded(this, Chrome.ACCENT, 4));
        handle.addView(pill, new FrameLayout.LayoutParams(dp(7), dp(64), Gravity.CENTER_VERTICAL | Gravity.END));
        handle.setPadding(dp(10), dp(16), dp(8), dp(16));
        handle.setOnClickListener(v -> openBar(tools.getVisibility() != View.VISIBLE));
        b.addView(handle);
        b.setVisibility(View.GONE);
        return b;
    }

    private View separator() {
        View v = new View(this);
        v.setBackgroundColor(0x1AFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        lp.setMargins(dp(10), dp(2), dp(10), dp(2));
        v.setLayoutParams(lp);
        return v;
    }

    private TextView tool(int label, int icon, int color, View.OnClickListener click) {
        return tool(label, new Chrome.Icon(this, icon, color), color, click);
    }

    private TextView tool(int label, Chrome.Icon icon, int color, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setCompoundDrawables(null, icon, null, null);
        t.setCompoundDrawablePadding(dp(4));
        t.setMinWidth(dp(76));
        t.setMinHeight(dp(58));
        t.setPadding(dp(2), dp(4), dp(2), dp(4));
        t.setOnClickListener(v -> {
            click.onClick(v);
            ui.removeCallbacks(closeBar);
            ui.postDelayed(closeBar, BAR_OPEN_MS);
        });
        return t;
    }

    private void openBar(boolean open) {
        ui.removeCallbacks(closeBar);
        tools.setVisibility(open ? View.VISIBLE : View.GONE);
        bar.setAlpha(open ? 1f : 0.9f);
        if (open) ui.postDelayed(closeBar, BAR_OPEN_MS);
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        ime.requestFocus();
        if (imm != null) imm.showSoftInput(ime, InputMethodManager.SHOW_FORCED);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(ime.getWindowToken(), 0);
    }

    /** Streaming, and this device said it works the phone. */
    private boolean controlling() {
        return state == State.STREAMING && receiver.control;
    }

    /** A key on a real keyboard: it goes to the phone by its Linux key code. */
    private static boolean fromKeyboard(KeyEvent e) {
        if (e.getScanCode() <= 0) return false;
        InputDevice d = e.getDevice();
        return d != null && !d.isVirtual() && d.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    /** Keys this device keeps: its own volume, power and brightness. */
    private static boolean ownKey(int k) {
        return k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE
                || k == KeyEvent.KEYCODE_POWER || k == KeyEvent.KEYCODE_SLEEP || k == KeyEvent.KEYCODE_WAKEUP
                || k == KeyEvent.KEYCODE_BRIGHTNESS_UP || k == KeyEvent.KEYCODE_BRIGHTNESS_DOWN;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (controlling() && e.getAction() != KeyEvent.ACTION_MULTIPLE) {
            int k = e.getKeyCode();
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            // A mouse's right button comes as Back too; it went to the phone as a right click already
            if (k == KeyEvent.KEYCODE_BACK && e.isFromSource(InputDevice.SOURCE_MOUSE)) return true;
            if (fromKeyboard(e) && !ownKey(k)) {
                // The phone repeats held keys itself
                if (!down || e.getRepeatCount() == 0) control.key(e.getScanCode(), down);
                return true;
            }
            if (k == KeyEvent.KEYCODE_BACK) {
                if (!down && !e.isCanceled()) control.android(Control.ANDROID_BACK);
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** This device's clipboard to the phone, unless it came from there. */
    private void sendClipboard() {
        if (!controlling() || clipboard == null) return;
        try {
            ClipData c = clipboard.getPrimaryClip();
            if (c == null || c.getItemCount() == 0) return;
            CharSequence t = c.getItemAt(0).coerceToText(this);
            if (t == null || t.length() == 0) return;
            String text = t.toString();
            if (text.equals(clipFromPhone)) return;
            byte[] b = text.getBytes(StandardCharsets.UTF_8);
            if (b.length <= 256 * 1024) receiver.send(Session.CLIPBOARD, b);
        } catch (RuntimeException e) {
            Log.i(TAG, "clipboard: " + e);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        displays.registerDisplayListener(displayListener, ui);
        if (clipboard != null) clipboard.addPrimaryClipChangedListener(clipChanged);
        ui.post(refreshIdle);
        update();
    }

    @Override
    protected void onStop() {
        started = false;
        update();
        displays.unregisterDisplayListener(displayListener);
        if (clipboard != null) clipboard.removePrimaryClipChangedListener(clipChanged);
        ui.removeCallbacks(refreshIdle);
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        // Nothing stays held on the phone while a dialog or the notification shade has the input
        if (!focus) control.release();
        if (focus) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        updateScreen();
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        holder = h;
        update();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        // The decoder draws on this surface: stop it before returning
        holder = null;
        update();
    }

    /** Runs the receiver exactly while the activity is started and has a surface to draw on */
    private void update() {
        boolean want = started && holder != null;
        if (want == running) return;
        running = want;
        if (want) {
            updateScreen();
            receiver.control = store.control();
            if (multicast != null) multicast.acquire();
            receiver.start(holder.getSurface());
            register();
        } else {
            unregister();
            receiver.stop();
            if (multicast != null) multicast.release();
            state = State.IDLE;
            render();
        }
    }

    private void updateScreen() {
        receiver.setScreen(Screen.of(getWindowManager().getDefaultDisplay()));
    }

    private void register() {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceName(store.name()));
        info.setServiceType(SERVICE_TYPE);
        info.setPort(Receiver.PORT);
        info.setAttribute("id", store.id());
        info.setAttribute("v", "1");
        final NsdManager.RegistrationListener l = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo s) {
                Log.i(TAG, "registered as " + s.getServiceName());
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo s, int err) {
                Log.w(TAG, "mDNS registration failed: " + err);
                // The system's mDNS daemon may not be up yet on old devices: try again while we run
                ui.post(() -> {
                    if (registration != this) return;
                    registration = null;
                    ui.postDelayed(() -> {
                        if (running && registration == null) register();
                    }, 5000);
                });
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo s) {
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo s, int err) {
                Log.w(TAG, "mDNS unregistration failed: " + err);
            }
        };
        registration = l;
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l);
        } catch (RuntimeException e) {
            Log.w(TAG, "mDNS: " + e);
            registration = null;
        }
    }

    private void unregister() {
        NsdManager.RegistrationListener l = registration;
        registration = null;
        if (l == null) return;
        try {
            nsd.unregisterService(l);
        } catch (IllegalArgumentException e) {
            // Its registration had failed already
        }
    }

    /** An mDNS instance name is one DNS label: at most 63 bytes */
    private static String serviceName(String name) {
        while (name.getBytes(StandardCharsets.UTF_8).length > 63) name = name.substring(0, name.length() - 1);
        return name;
    }

    private void render() {
        overlay.setVisibility(state == State.STREAMING ? View.GONE : View.VISIBLE);
        boolean working = controlling();
        if (working != (bar.getVisibility() == View.VISIBLE)) {
            bar.setVisibility(working ? View.VISIBLE : View.GONE);
            openBar(false);
            if (!working) {
                control.release();
                hideKeyboard();
            }
        }
        applyPointerIcon();
        idleBox.setVisibility(state == State.IDLE ? View.VISIBLE : View.GONE);
        pairBox.setVisibility(state == State.PAIRING ? View.VISIBLE : View.GONE);
        messageBox.setVisibility(state == State.CONNECTING || state == State.ERROR ? View.VISIBLE : View.GONE);
        footer.setVisibility(state == State.IDLE || state == State.PAIRING ? View.VISIBLE : View.GONE);
        switch (state) {
            case IDLE: {
                idleTitle.setText(store.name());
                idleHint.setText(R.string.idle_hint);
                List<String> addrs = Screen.addresses();
                if (portBusy) {
                    idleAddress.setText("");
                    idleAddress.setVisibility(View.GONE);
                    idleBody.setText(getString(R.string.idle_port_busy, Receiver.PORT));
                    idleBody.setVisibility(View.VISIBLE);
                } else if (addrs.isEmpty()) {
                    idleAddress.setText("");
                    idleAddress.setVisibility(View.GONE);
                    idleBody.setText(R.string.idle_no_network);
                    idleBody.setVisibility(View.VISIBLE);
                } else {
                    StringBuilder a = new StringBuilder();
                    for (int i = 0; i < Math.min(3, addrs.size()); i++) {
                        if (i > 0) a.append("   ");
                        a.append(addrs.get(i));
                    }
                    idleAddress.setText(getString(R.string.idle_address, a));
                    idleAddress.setVisibility(View.VISIBLE);
                    idleBody.setText("");
                    idleBody.setVisibility(View.GONE);
                }
                footer.setText(getString(R.string.idle_options) + " · "
                        + getString(R.string.app_name) + " " + version());
                break;
            }
            case PAIRING:
                pairTitle.setText(getString(R.string.pairing_title, phone));
                pairCode.setCode(pairingCode);
                pairHint.setText(R.string.pairing_hint);
                if (pairingRetry) {
                    pairBody.setText(R.string.pairing_wrong);
                    pairBody.setVisibility(View.VISIBLE);
                } else {
                    pairBody.setText("");
                    pairBody.setVisibility(View.GONE);
                }
                footer.setText(R.string.pairing_remember);
                break;
            case CONNECTING:
                messageTitle.setText(getString(R.string.connecting, phone));
                messageBody.setText("");
                messageBody.setVisibility(View.GONE);
                break;
            case ERROR:
                messageTitle.setText(phone);
                messageBody.setText(error);
                messageBody.setVisibility(View.VISIBLE);
                break;
            default:
                break;
        }
    }

    /**
     * This device's pointer is hidden while the picture has a cursor of its own: Android content (INPUT_MODE 0 or 2,
     * the phone draws its arrow in the picture), before the first INPUT_MODE says what is showing, a passed-through
     * Mac (this device draws the cursor itself), and with FEATURES bit 3 a desktop whose cursor is in the picture
     * (INPUT_MODE 3: X driven by relative moves, a Mac re-encoded, the switchboard, the Dock). Otherwise the system
     * pointer shows: for X content placed absolutely the phone hides the X cursor, and a tablet's mouse must still
     * show one (PROTOCOL.md, DISPLAY.md §8).
     */
    private void applyPointerIcon() {
        if (Build.VERSION.SDK_INT < 24) return;
        boolean pictureCursor = (features & FEATURE_PICTURE_CURSOR) != 0 && phoneInputMode == 3;
        boolean hide = controlling() && (passthrough || phoneInputMode < 0 || phoneInputMode == 0
                || phoneInputMode == 2 || pictureCursor);
        if (hide == pointerHidden) return;
        pointerHidden = hide;
        PointerIcon icon = hide ? PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL) : null;
        root.setPointerIcon(icon);
        view.setPointerIcon(icon);
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void fitVideo() {
        int sw = root.getWidth(), sh = root.getHeight();
        FrameLayout.LayoutParams lp;
        if (videoWidth <= 0 || videoHeight <= 0 || sw <= 0 || sh <= 0) {
            lp = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        } else {
            float scale = Math.min((float) sw / videoWidth, (float) sh / videoHeight);
            lp = new FrameLayout.LayoutParams(Math.round(videoWidth * scale), Math.round(videoHeight * scale),
                    Gravity.CENTER);
        }
        view.setLayoutParams(lp);
        placeCursor();
    }

    /** The cursor on the picture as fitVideo lays it out: centred, letterboxed. */
    private void placeCursor() {
        if (cursor.getDrawable() == null) return;
        int sw = root.getWidth(), sh = root.getHeight();
        if (videoWidth <= 0 || videoHeight <= 0 || sw <= 0 || sh <= 0) return;
        float scale = Math.min((float) sw / videoWidth, (float) sh / videoHeight);
        float pw = videoWidth * scale, ph = videoHeight * scale;
        int w = Math.max(1, Math.round(cursorW * pw)), h = Math.max(1, Math.round(cursorH * pw));
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) cursor.getLayoutParams();
        if (p.width != w || p.height != h) {
            p.width = w;
            p.height = h;
            cursor.setLayoutParams(p);
        }
        cursor.setTranslationX((sw - pw) / 2 + cursorX * pw - cursorHotX * pw);
        cursor.setTranslationY((sh - ph) / 2 + cursorY * ph - cursorHotY * pw);
    }

    @Override
    public void cursorShape(android.graphics.Bitmap shape, float width, float height, float hotX, float hotY) {
        ui.post(() -> {
            cursor.setImageBitmap(shape);
            cursorW = width;
            cursorH = height;
            cursorHotX = hotX;
            cursorHotY = hotY;
            placeCursor();
        });
    }

    @Override
    public void cursorPosition(float x, float y, boolean shown) {
        ui.post(() -> {
            cursorX = x;
            cursorY = y;
            cursor.setVisibility(shown && cursor.getDrawable() != null ? View.VISIBLE : View.GONE);
            placeCursor();
        });
    }

    @Override
    public void passthrough(boolean on) {
        ui.post(() -> {
            if (passthrough == on) return;
            passthrough = on;
            applyPointerIcon();
        });
    }

    @Override
    public void settings(float pointerSpeed, float scrollSpeed, boolean naturalScroll) {
        ui.post(() -> {
            control.setPointerSpeed(pointerSpeed);
            control.setScrollSpeed(scrollSpeed);
            control.setNaturalScroll(naturalScroll);
        });
    }

    // Receiver.Ui, called on network threads

    @Override
    public void idle() {
        ui.post(() -> {
            state = State.IDLE;
            videoWidth = videoHeight = 0;
            features = 0;
            control.setFeatures(0);
            applyFeatures();
            passthrough = false;
            // The next session starts hidden until its first INPUT_MODE, as always
            phoneInputMode = -1;
            // The next phone may never send SETTINGS: back to the defaults of INPUT.md §12
            control.resetSettings();
            cursor.setVisibility(View.GONE);
            cursor.setImageDrawable(null);
            fitVideo();
            render();
        });
    }

    @Override
    public void portBusy(boolean busy) {
        ui.post(() -> {
            portBusy = busy;
            render();
        });
    }

    @Override
    public void pairing(String phone, String code, boolean retry) {
        ui.post(() -> {
            state = State.PAIRING;
            this.phone = phone;
            pairingCode = code;
            pairingRetry = retry;
            render();
        });
    }

    @Override
    public void connecting(String phone) {
        ui.post(() -> {
            state = State.CONNECTING;
            this.phone = phone;
            render();
        });
    }

    @Override
    public void streaming() {
        ui.post(() -> {
            state = State.STREAMING;
            control.setImeTop(0xFFFF);
            padOverride = null;
            phoneWantsTouch = false;
            touchModeLocal = false;
            applyPadMode();
            render();
        });
    }

    @Override
    public void videoSize(int width, int height) {
        ui.post(() -> {
            videoWidth = width;
            videoHeight = height;
            fitVideo();
        });
    }

    @Override
    public void imeArea(int top) {
        ui.post(() -> control.setImeTop(top));
    }

    @Override
    public void inputMode(int mode) {
        ui.post(() -> {
            boolean touch = mode == 2;
            if (touch != phoneWantsTouch) {
                padOverride = null;  // another app, or its mode changed on the phone
                touchModeLocal = false;
            }
            phoneWantsTouch = touch;
            phoneInputMode = mode;
            applyPadMode();
            applyPointerIcon();
        });
    }

    @Override
    public void features(int bits) {
        ui.post(() -> {
            features = bits;
            control.setFeatures(bits);
            applyFeatures();
            // FEATURES bit 3 decides whether INPUT_MODE 3 means the picture has a cursor
            applyPointerIcon();
        });
    }

    /** The handle's 像电脑 / 像平板. */
    private void switchPadMode() {
        boolean linked = (features & Control.FEATURE_TOUCH_MODE) != 0 && !touchModeLocal
                && (phoneInputMode == 0 || phoneInputMode == 2);
        if (linked) {
            control.touchMode(control.touchpad());
            return;
        }
        padOverride = !control.touchpad();
        applyPadMode();
    }

    @Override
    public void touchModeRefused() {
        ui.post(() -> {
            // The app in front cannot switch on the phone (RustDesk has its own mouse): switch here, as before
            touchModeLocal = true;
            padOverride = !control.touchpad();
            applyPadMode();
        });
    }

    private void applyPadMode() {
        boolean pad = padOverride != null ? padOverride : !phoneWantsTouch;
        // 像平板 on the phone: direct touch passes a finger; otherwise direct touch is the mouse-only kind (INPUT.md §3.2)
        control.setFingers(phoneWantsTouch);
        control.setTouchpad(pad);
        modeTool.setText(pad ? R.string.bar_touchpad : R.string.bar_direct);
        modeIcon.setIcon(pad ? Chrome.COMPUTER : Chrome.TABLET);
        modeTool.setCompoundDrawables(null, modeIcon, null, null);
    }

    /** Overview and Switcher in the handle only for a phone that said FEATURES bit 0. */
    private void applyFeatures() {
        int show = (features & 1) != 0 ? View.VISIBLE : View.GONE;
        if (overviewTool != null) overviewTool.setVisibility(show);
        if (switcherTool != null) switcherTool.setVisibility(show);
    }

    @Override
    public void clipboard(String text) {
        ui.post(() -> {
            if (clipboard == null) return;
            clipFromPhone = text;
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText("XDesk", text));
            } catch (RuntimeException e) {
                Log.i(TAG, "clipboard: " + e);
            }
        });
    }

    @Override
    public void cannotDecode(String phone, int width, int height) {
        ui.post(() -> {
            state = State.ERROR;
            this.phone = phone;
            error = getString(R.string.cannot_decode, phone, width, height);
            render();
        });
    }

    // Options: rename, forget paired phones

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            showOptions();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showOptions();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /** RecvMenu.dc.html: the receiver's options over the dimmed waiting screen. */
    private void showOptions() {
        // Only on the idle screen: during a stream the picture is all there is
        if (state != State.IDLE || isFinishing()) return;
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Chrome.shell(this, 22));
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));

        TextView head = label(14, Chrome.DIM);
        head.setGravity(Gravity.START);
        head.setPadding(dp(20), dp(8), dp(20), dp(6));
        head.setText(R.string.options_title);
        panel.addView(head, new LinearLayout.LayoutParams(-1, -2));

        final View renameRow = optionRow(Chrome.RENAME, getString(R.string.menu_rename), Chrome.TEXT, null, v -> {
            dialog.dismiss();
            rename();
        });
        panel.addView(renameRow, new LinearLayout.LayoutParams(-1, -2));

        View controlRow = optionRow(Chrome.COMPUTER, getString(R.string.menu_control_on), Chrome.TEXT,
                new Chrome.Toggle(this, store.control()), v -> {
                    store.setControl(!store.control());
                    receiver.control = store.control();
                    Toast.makeText(this, store.control() ? R.string.control_on : R.string.control_off,
                            Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                });
        panel.addView(controlRow, new LinearLayout.LayoutParams(-1, -2));

        View forgetRow = optionRow(Chrome.FORGET, getString(R.string.menu_forget), Chrome.DANGER, null, v -> {
            store.forgetPhones();
            Toast.makeText(this, R.string.forgotten, Toast.LENGTH_LONG).show();
            dialog.dismiss();
        });
        panel.addView(forgetRow, new LinearLayout.LayoutParams(-1, -2));

        TextView foot = label(13, Chrome.FAINT);
        foot.setGravity(Gravity.START);
        foot.setPadding(dp(20), dp(10), dp(20), dp(4));
        foot.setText(R.string.options_remote);
        panel.addView(foot, new LinearLayout.LayoutParams(-1, -2));

        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.5f;
            w.setAttributes(lp);
            w.setLayout(Math.min(dp(480), getResources().getDisplayMetrics().widthPixels - dp(32)), -2);
        }
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        renameRow.post(renameRow::requestFocus);
    }

    /** One row of the options: an icon, its words, and on the right the switch the screen's mockup draws. */
    private View optionRow(int icon, CharSequence label, int color, View trailing, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(58));
        row.setPadding(dp(20), 0, dp(20), 0);
        row.setBackground(Chrome.row(this));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setOnClickListener(click);
        ImageView mark = new ImageView(this);
        mark.setImageDrawable(new Chrome.Icon(this, icon, color));
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(20), dp(20));
        markParams.rightMargin = dp(14);
        row.addView(mark, markParams);
        TextView words = new TextView(this);
        words.setText(label);
        words.setTextSize(18);
        words.setTextColor(color);
        words.setSingleLine(true);
        words.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        if (trailing != null) {
            LinearLayout.LayoutParams trailingParams = new LinearLayout.LayoutParams(dp(46), dp(28));
            trailingParams.leftMargin = dp(12);
            row.addView(trailing, trailingParams);
        }
        return row;
    }

    private void rename() {
        EditText edit = new EditText(this);
        edit.setSingleLine();
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        edit.setText(store.name());
        edit.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(R.string.menu_rename)
                .setView(edit)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String name = edit.getText().toString().trim();
                    if (TextUtils.isEmpty(name)) return;
                    store.setName(name);
                    // Phones browsing see the new name
                    if (running) {
                        unregister();
                        register();
                    }
                    render();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
