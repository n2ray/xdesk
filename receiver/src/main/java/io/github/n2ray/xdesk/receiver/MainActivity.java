package io.github.n2ray.xdesk.receiver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
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
    private TextView title, code, body, footer;

    private boolean started, running, portBusy;
    private SurfaceHolder holder;
    private State state = State.IDLE;
    private String phone, pairingCode, error;
    private boolean pairingRetry;
    private int videoWidth, videoHeight;

    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    private WifiManager.MulticastLock multicast;
    private DisplayManager displays;

    // This device working the phone: touch, mouse and keyboard, the input method, the toolbar and the clipboard
    private static final long BAR_OPEN_MS = 6000;
    private Control control;
    private ImeView ime;
    private TextView modeTool;
    /** From the phone: the Android app in front takes touches. */
    private boolean phoneWantsTouch;
    /** Chosen in the toolbar: true touchpad, false direct touch, null as the phone says. Until the app changes. */
    private Boolean padOverride;
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

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new SurfaceView(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
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
        frame.setBackgroundColor(Color.BLACK);
        // Phones: long-press anywhere. TV remotes: Menu, or a long press on OK (see onKeyLongPress)
        frame.setOnLongClickListener(v -> {
            showOptions();
            return true;
        });
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        box.setPadding(pad, pad, pad, pad);
        title = text(30, Color.WHITE);
        code = text(96, Color.WHITE);
        code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        code.setLetterSpacing(0.2f);
        body = text(20, 0xFFB0B8C4);
        box.addView(title);
        box.addView(code);
        box.addView(body);
        frame.addView(box, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        footer = text(14, 0xFF6B7380);
        footer.setPadding(pad, pad, pad, pad);
        frame.addView(footer, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return frame;
    }

    /** A small handle on the right edge; it opens Keyboard, Back, Home and Disconnect. */
    private LinearLayout buildBar() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setBackground(rounded(0xE61D1D1F, dp(14)));
        tools.setPadding(dp(4), dp(4), dp(4), dp(4));
        tools.addView(tool(R.string.bar_keyboard, v -> showKeyboard()));
        // Shows the mode a tap switches to
        modeTool = tool(R.string.bar_direct, v -> {
            padOverride = !control.touchpad();
            applyPadMode();
        });
        tools.addView(modeTool);
        tools.addView(tool(R.string.bar_back, v -> control.android(Control.ANDROID_BACK)));
        tools.addView(tool(R.string.bar_home, v -> control.android(Control.ANDROID_HOME)));
        tools.addView(tool(R.string.bar_disconnect, v -> {
            openBar(false);
            receiver.disconnect();
        }));
        tools.setVisibility(View.GONE);
        b.addView(tools);
        FrameLayout handle = new FrameLayout(this);
        View pill = new View(this);
        pill.setBackground(rounded(0xFF3D7EF5, dp(4)));
        pill.setElevation(dp(3));
        handle.addView(pill, new FrameLayout.LayoutParams(dp(7), dp(64), Gravity.CENTER));
        handle.setPadding(dp(10), dp(16), dp(8), dp(16));
        handle.setOnClickListener(v -> openBar(tools.getVisibility() != View.VISIBLE));
        b.addView(handle);
        b.setVisibility(View.GONE);
        return b;
    }

    private TextView tool(int label, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(72));
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setOnClickListener(v -> {
            click.onClick(v);
            ui.removeCallbacks(closeBar);
            ui.postDelayed(closeBar, BAR_OPEN_MS);
        });
        return t;
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
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

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.25f);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
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
            // The phone draws its pointer in the picture; this device's own would be a second one
            if (Build.VERSION.SDK_INT >= 24) {
                PointerIcon none = working ? PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL) : null;
                root.setPointerIcon(none);
                view.setPointerIcon(none);
            }
        }
        code.setVisibility(state == State.PAIRING ? View.VISIBLE : View.GONE);
        footer.setVisibility(state == State.IDLE ? View.VISIBLE : View.GONE);
        switch (state) {
            case IDLE: {
                title.setText(store.name());
                StringBuilder b = new StringBuilder();
                List<String> addrs = Screen.addresses();
                if (portBusy) b.append(getString(R.string.idle_port_busy, Receiver.PORT));
                else if (addrs.isEmpty()) b.append(getString(R.string.idle_no_network));
                else {
                    StringBuilder a = new StringBuilder();
                    for (int i = 0; i < Math.min(3, addrs.size()); i++) {
                        if (i > 0) a.append("   ");
                        a.append(addrs.get(i)).append(':').append(Receiver.PORT);
                    }
                    b.append(getString(R.string.idle_address, a));
                }
                b.append("\n\n").append(getString(R.string.idle_hint));
                body.setText(b);
                footer.setText(getString(R.string.app_name) + " " + version() + "  ·  "
                        + getString(R.string.idle_options));
                break;
            }
            case PAIRING:
                title.setText(getString(R.string.pairing_title, phone));
                code.setText(pairingCode);
                body.setText(pairingRetry ? getString(R.string.pairing_wrong) + "\n" + getString(R.string.pairing_hint)
                        : getString(R.string.pairing_hint));
                break;
            case CONNECTING:
                title.setText(getString(R.string.connecting, phone));
                body.setText("");
                break;
            case ERROR:
                title.setText(phone);
                body.setText(error);
                break;
            default:
                break;
        }
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
    }

    // Receiver.Ui, called on network threads

    @Override
    public void idle() {
        ui.post(() -> {
            state = State.IDLE;
            videoWidth = videoHeight = 0;
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
            if (touch != phoneWantsTouch) padOverride = null;  // another app, or its mode changed on the phone
            phoneWantsTouch = touch;
            applyPadMode();
        });
    }

    private void applyPadMode() {
        boolean pad = padOverride != null ? padOverride : !phoneWantsTouch;
        control.setTouchpad(pad);
        modeTool.setText(pad ? R.string.bar_direct : R.string.bar_touchpad);
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

    private void showOptions() {
        // Only on the idle screen: during a stream the picture is all there is
        if (state != State.IDLE || isFinishing()) return;
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(R.string.app_name)
                .setItems(new CharSequence[] { getString(R.string.menu_rename), getString(R.string.menu_forget),
                                getString(store.control() ? R.string.menu_control_off : R.string.menu_control_on) },
                        (d, which) -> {
                            if (which == 0) rename();
                            else if (which == 2) {
                                store.setControl(!store.control());
                                receiver.control = store.control();
                                Toast.makeText(this, store.control() ? R.string.control_on : R.string.control_off,
                                        Toast.LENGTH_LONG).show();
                            } else {
                                store.forgetPhones();
                                Toast.makeText(this, R.string.forgotten, Toast.LENGTH_LONG).show();
                            }
                        })
                .show();
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
