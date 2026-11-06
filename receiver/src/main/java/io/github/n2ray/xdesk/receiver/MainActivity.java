package io.github.n2ray.xdesk.receiver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
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

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new SurfaceView(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
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
        ui.post(refreshIdle);
        update();
    }

    @Override
    protected void onStop() {
        started = false;
        update();
        displays.unregisterDisplayListener(displayListener);
        ui.removeCallbacks(refreshIdle);
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
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
                .setItems(new CharSequence[] { getString(R.string.menu_rename), getString(R.string.menu_forget) },
                        (d, which) -> {
                            if (which == 0) rename();
                            else {
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
