/*
 * The session flow (hello, window list, source pick, launch, viewport, cursor, pointer, clipboard, goodbye,
 * keyframe requests) follows Sall (https://github.com/n2ray/sall, MainActivity.java), the Android client of Sill
 * (https://github.com/Saffsanity/sill, Sources/StreamProtocol). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk.
 */
package com.termux.x11.mac;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One paired Mac: its connection, what it has (windows, installed apps, icons) and the one stream it sends.
 *
 * Connects on demand (the chooser asks for its catalog, or something is opened) and keeps an idle connection
 * for the catalog; with nothing of this Mac on a screen for IDLE_MS it disconnects, since the Mac sends every
 * connected device its thumbnails and any stream another device watches. Reconnects with a backoff while it is
 * wanted. Threads: a reader (also decodes video), a sender (input, coalescing pointer moves), a pinger.
 */
public final class MacClient {
    private static final String TAG = "XDeskMac";
    public static final int DEFAULT_PORT = 7455;
    private static final long IDLE_MS = 10 * 60_000, LAUNCH_MS = 15_000, PONG_DEADLINE_MS = 12_000;
    /** While a screen shows this Mac: the least silence that ends the connection, and how long discovery may wait. */
    private static final long MIN_DEADLINE_MS = 6_000, HOME_DISCOVERY_WAIT_MS = 300;
    private static final int CONNECT_MS = 8_000, HOME_CONNECT_MS = 1_500, HANDSHAKE_MS = 10_000;
    /** Reconnect pauses: the old 1→30 s, or at most this while a screen shows the Mac (someone is watching). */
    private static final long BACKOFF_MAX_MS = 30_000, SHOWN_BACKOFF_MAX_MS = 2_000;

    // Sill's key modifier bits (CGEventFlags / UIKeyModifierFlags)
    public static final long SHIFT = 1L << 17, CONTROL = 1L << 18, OPTION = 1L << 19, COMMAND = 1L << 20;

    public static final class Window {
        public final long id;
        public final String title, appName, bundleId;

        Window(long id, String title, String appName, String bundleId) {
            this.id = id;
            this.title = title;
            this.appName = appName;
            this.bundleId = bundleId;
        }
    }

    public static final class App {
        public final String name, bundleId;

        App(String name, String bundleId) {
            this.name = name;
            this.bundleId = bundleId;
        }
    }

    /** What a screen showing this Mac hears. Called on the UI thread. */
    public interface View {
        void videoSize(int width, int height);
        /** The Mac's cursor, sizes in Mac points. blank: nothing visible in it (the Mac's app hides its cursor). */
        void cursor(Bitmap shape, float hotX, float hotY, float width, float height, boolean blank);
        /** Where the pointer is now, in fractions of the picture: moved here, or by the Mac's own mouse. */
        void pointer(float x, float y);
        /** The state line changed: connecting, locked, taken over, failed; null when the picture is all there is. */
        void status(String line, boolean takenOver);
    }

    public interface Listener {
        /** Windows, apps, icons, the name or the state changed. UI thread. */
        void changed(MacClient c);
    }

    public enum State { OFFLINE, CONNECTING, CONNECTED, FAILED }

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private MacStore.Mac mac;
    private volatile Listener listener;

    // Catalog, replaced whole (read from any thread)
    private volatile List<Window> windows = Collections.emptyList();
    private volatile List<App> apps = Collections.emptyList();
    private final Map<String, Bitmap> icons = Collections.synchronizedMap(new HashMap<String, Bitmap>());
    private volatile String macName = "";
    private volatile boolean locked, offConsole, offConsoleView;
    private volatile boolean keyRepeat;
    private volatile int clipboardGeneration;
    private volatile long activeWindow = -1;
    private volatile boolean activeDesktop;
    /**
     * The Desktop's displays (Sill 0.6.2, Switcher.swift's WindowList): how many, 2 or more, else 0 (one display, a
     * locked Mac, an older host); which one it shows or comes back to, from 1; their names, in the same order.
     */
    private volatile int desktopDisplays, desktopDisplay;
    private volatile List<String> desktopDisplayNames = Collections.emptyList();
    /** The display asked for by showDesktop(n), stepped to by kind 47 as the lists come; 0 when none. */
    private volatile int wantDisplay;
    private volatile long wantDisplayAt, lastStepAt;
    /** The display shown when the last step went: the next step waits for the list that says it moved. */
    private volatile int steppedFrom;
    private static final long DISPLAY_GIVE_UP_MS = 8000, DISPLAY_STEP_AGAIN_MS = 1500;
    private volatile State state = State.OFFLINE;
    private volatile String failure;
    private volatile boolean needsPairing;

    // Connection
    private final Object lock = new Object();
    /** Signalled when the state a caller of awaitConnected waits for may have changed. */
    private final Object stateSignal = new Object();
    private Thread runner;
    private volatile SillTls session;
    private volatile boolean wanted;
    private long lastUse = SystemClock.uptimeMillis();
    /** The last message of any kind from the Mac: pongs, and everything else that proves the link alive. */
    private volatile long lastPong;
    /** The round trips of the pings, from the session's start (Sall's diagnosticsRtt); for the settings page. */
    private volatile Rtt rtt;
    /** Runners that watch the default network (one normally; a dying one may overlap its successor). Under lock. */
    private int networkUsers;
    /** The default network changed: the next session starts at once, without the reconnect pause. */
    private volatile boolean reconnectAtOnce;
    private volatile long networkEpoch;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network defaultNetwork;
    /** A default network was seen since registering: the next one is a change, even after the old one was lost. */
    private boolean networkSeen;
    /** WiFi high-performance lock while a screen shows this Mac (and out of low latency). */
    private WifiManager.WifiLock wifiLock;

    // The screen
    private final Object surfaceLock = new Object();
    private volatile Surface surface;
    private volatile View view;
    private final MacVideo video;
    private volatile int surfaceW, surfaceH;
    /** The screen's b·u (§4.1, Sizing.shell) from the last setSurface: pixels per Mac point before R/N and the floors. */
    private volatile float density = 1;
    /** Whether the streamed window is (or the next one will be) on Sill's virtual display; null before Sill says. */
    private volatile Boolean staging;
    private volatile int frameX, frameY, frameW, frameH, screenW, screenH;
    private volatile int fps = 60;
    /** What this XDesk wants streamed while shown: a window id, DESKTOP, or NONE. */
    public static final long DESKTOP = -2, NONE = -1;
    private volatile long want = NONE;
    private volatile String pendingLaunch;
    private volatile long launchAt;
    private long lastKeyframeAsk;

    // Pointer, in fractions of the streamed frame
    private volatile float pointerX = 0.5f, pointerY = 0.5f;
    /**
     * Input messages (kind 8) queued on this connection, counted when queued, as Sall's PointerSync does: a pointer
     * report of the Mac's (kind 26) says how many the Mac had read, and one made before it read them all is older
     * than this side's pointer. Counted when sent (as before), a report could pass while moves still waited in the
     * queue, and the pointer jumped back to where the Mac had it and forward again with the next move.
     */
    private final java.util.concurrent.atomic.AtomicInteger inputsQueued = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean launchFailed;

    // The Mac's menus (kinds 24, 25 and 27): the streamed app's menu bar, for quitApp and the menu bar overlay.
    // The reader thread feeds receiveTop/receiveAnswer, the quit machine and the UI ask fetch/press;
    // MacMenuState's methods are synchronized.
    private final MacMenuState mirroredMenus = new MacMenuState();
    /** FetchMenu/PressMenuItem tokens, strictly increasing per connection, as kind 17's are (Sall's menuToken). */
    private final java.util.concurrent.atomic.AtomicLong menuToken = new java.util.concurrent.atomic.AtomicLong();
    /** This connection asked for the top level (kind 27 without an id): the host then serves fetches and presses. */
    private boolean menuSubscribed;
    /** Who hears tops and answers for the menu bar UI; all get every one, the quit machine's slot stays its own. */
    private final java.util.List<MenuListener> menuListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** What the menu bar UI hears (MacMenuBar, the phone's dialog), on the UI thread. */
    public interface MenuListener {
        /** The top level came or moved (read it afresh), and the requests the host answered. */
        void menusChanged(MacMenuState.Top top, java.util.List<MacMenuState.Done> done);
    }
    /** The app the quit machine is quitting (its stream is switching): the screen says so meanwhile. */
    private volatile String quittingApp;
    /** Sall's Accessibility is missing on the Mac: said once per process (MacMenuState.NOT_TRUSTED). */
    private static volatile boolean quitAccessibilitySaid;

    /** A pointer move waiting for the sender; while it is the queue's last, later moves only change where it goes. */
    private static final class Move {
        float x, y;
        /** When the first move it stands for was queued (uptime). */
        final long at = SystemClock.uptimeMillis();

        Move(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    // Output queue: byte[] messages, Move pointer moves (the last one is updated in place)
    private final ArrayDeque<Object> out = new ArrayDeque<>();
    private String lastClipFromMac;
    /** This Mac in the shared clipboard (ShareHub), while its clipboard is on. */
    private com.termux.x11.ShareHub.Endpoint clipEndpoint;

    public MacClient(Context c, MacStore.Mac mac) {
        context = c.getApplicationContext();
        this.mac = mac;
        video = new MacVideo(this::videoSize);
        video.setSmooth(MacStore.smooth(context, mac.id));
    }

    /**
     * The picture's size as the decoder (or a passthrough's SPS) last said it, 0 before. Kept, since a screen that
     * shows this Mac later (a new MacActivity on a stream that goes on) is not told by the decoder again in time, or
     * at all, and without a size it lays the picture over its whole screen: a 16:9 desktop stretched to 16:10.
     */
    private volatile int videoW, videoH;

    private void videoSize(final int w, final int h) {
        videoW = w;
        videoH = h;
        ui.post(() -> {
            View v = view;
            if (v != null) v.videoSize(w, h);
        });
    }

    public MacStore.Mac mac() { return mac; }
    public void updateMac(MacStore.Mac m) {
        boolean refit = m.fit != mac.fit;
        boolean repaired = !java.util.Arrays.equals(m.pin, mac.pin) || !m.host.equals(mac.host) || m.port != mac.port;
        mac = m;
        if (repaired) {
            // Paired again (a new key or address): start over with it
            needsPairing = false;
            failure = null;
            SillTls s = session;
            if (s != null) s.close();
            if (state == State.FAILED) state = State.OFFLINE;
        }
        if (refit && surface != null) sendViewport();
    }
    /** Just paired (again): whatever ended the last session no longer holds. */
    void paired() {
        needsPairing = false;
        failure = null;
        if (state == State.FAILED) state = State.OFFLINE;
        SillTls s = session;
        if (s != null) s.close();
    }

    public String name() { return macName.isEmpty() ? mac.name : macName; }
    public List<Window> windows() { return windows; }
    public List<App> apps() { return apps; }
    public Bitmap icon(String bundleId) { return icons.get(bundleId); }
    public State state() { return state; }
    public String failure() { return failure; }
    public boolean needsPairing() { return needsPairing; }
    public boolean locked() { return locked && !offConsoleView; }
    public boolean keyRepeat() { return keyRepeat; }
    public long want() { return want; }
    public long activeWindow() { return activeWindow; }
    public boolean activeDesktop() { return activeDesktop; }
    public int desktopDisplays() { return desktopDisplays; }
    public int desktopDisplay() { return desktopDisplay; }
    /** The name of display n (from 1), or "" when the Mac gave none. */
    public String desktopDisplayName(int n) {
        List<String> names = desktopDisplayNames;
        return n >= 1 && n <= names.size() ? names.get(n - 1) : "";
    }
    public float pointerX() { return pointerX; }
    public float pointerY() { return pointerY; }
    /** quitApp is running: the Mac's stream may leave the shown window before its app's save dialog opens. */
    public boolean quitting() { return quittingApp != null; }

    /**
     * Screen pixels per Mac point, DISPLAY.md §4.1: max(b·u·R/N, 1.5). How big the Mac window is asked to be on this
     * screen (sendViewport) and how big its cursor is drawn. {@code density} is the shell's b·u from MacActivity
     * (Sizing.shell), near 1 on a desk monitor, never the display's Android density: an override set for apps must
     * not resize the Mac. At least POINT_MIN_PX: at b·u = 1 a Mac window of 2560×1440
     * points would need a virtual display Sill can't make (Sill.log 2026-11-14: "not at 4096×4096 @2×") and a picture
     * of four times the screen's pixels; 1.5 is a MacBook Air's own look (1707×960 on a 2560 screen). And never so
     * small that the window's longer side passes WINDOW_MAX.
     */
    public float pointScale() {
        // R/N stays 1 here: the window's own size decides how its picture is drawn, so drawn size must not feed back
        float k = Math.max(density, POINT_MIN_PX);
        int longest = Math.max(surfaceW, surfaceH);
        return longest > 0 ? Math.max(k, longest / WINDOW_MAX) : k;
    }

    private static final float POINT_MIN_PX = 1.5f;
    /**
     * The longest panel side sent to Sill, in points. Sill makes its virtual display a square of the panel's longer
     * side ÷ 0.5 (VirtualDisplaySize.envelope) at 2× backing; the largest it ever made on the user's M4 was 2316 pt
     * wide (4632 px), and 4096 failed. A window bigger than this comes from the scale (window = panel ÷ scale).
     */
    private static final float PANEL_MAX = 1160;
    /** The longest Mac window side asked for: PANEL_MAX at the smallest scale Sill's envelope allows for (0.5). */
    private static final float WINDOW_MAX = 2 * PANEL_MAX;

    public void setListener(Listener l) { listener = l; }

    private void changed() {
        ui.post(() -> {
            Listener l = listener;
            if (l != null) l.changed(this);
        });
    }

    // --- connection ---

    /** Something wants this Mac (its catalog or a stream): connect if not connected. Never blocks. */
    public void touch() {
        synchronized (lock) {
            lastUse = SystemClock.uptimeMillis();
            wanted = true;
            if (runner != null && runner.isAlive()) return;
            if (needsPairing) {
                wanted = false;
                return; // unpaired or another key: only pairing again helps
            }
            // Each runner holds the network watch from here to its own end. Discovery is held by what shows (XDesk's
            // window, a Mac's screen), not by every connection: mDNS for the whole of an idle connection costs battery
            networkWatch(true);
            state = State.CONNECTING;
            runner = new Thread(this::run, "mac-" + mac.id);
            runner.start();
        }
    }

    /** Wait until connected, up to ms. Off the UI thread. */
    public boolean awaitConnected(long ms) {
        touch();
        // The user is waiting: no point in sitting out the rest of a reconnect pause (the network may just be back)
        synchronized (lock) {
            if (backingOff && runner != null) runner.interrupt();
        }
        long until = SystemClock.uptimeMillis() + ms;
        synchronized (stateSignal) {
            while (SystemClock.uptimeMillis() < until) {
                if (state == State.CONNECTED && !windowsPending) return true;
                if (state == State.FAILED) return false;
                long left = until - SystemClock.uptimeMillis();
                if (left <= 0) break;
                try {
                    stateSignal.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return state == State.CONNECTED;
        }
    }

    /** Wakes awaitConnected's waiters: a state or the window list changed. */
    private void stateChanged() {
        synchronized (stateSignal) {
            stateSignal.notifyAll();
        }
    }

    private volatile boolean windowsPending = true;
    /** The runner sleeps between two connection attempts (under lock: only then may it be interrupted to hurry). */
    private boolean backingOff;

    /** Close the connection and stop reconnecting (the Mac was removed, or XDesk is closing). */
    public void shutdown() {
        wanted = false;
        SillTls s = session;
        if (s != null) s.close();
        setWifiLock(false);
        synchronized (lock) {
            if (runner != null && backingOff) runner.interrupt();
            // Under the lock helloAudio takes, and wanted already false: a mute made before this is closed here,
            // one after this is never made (its receiver would otherwise outlive this MacClient)
            AudioMute m = mute;
            mute = null;
            if (m != null) m.close();
        }
        stopClipboard();
    }

    private void setState(State s, String why) {
        state = s;
        failure = why;
        stateChanged();
        changed();
        statusToView();
        ui.post(this::autoTickFollow);  // the auto quality tick follows the connection
    }

    private void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        try {
            int retries = 0;
            while (true) {
                // Under the lock touch() takes: either it sees this runner end and starts another, or this one goes on
                synchronized (lock) {
                    if (!wanted) {
                        if (runner == Thread.currentThread()) runner = null;
                        break;
                    }
                }
                long started = SystemClock.uptimeMillis();
                try {
                    setState(State.CONNECTING, null);
                    session(mac);
                    // Left without an error: shutdown() came (also while still connecting, which nothing interrupts)
                    SillTls s = session;
                    session = null;
                    if (s != null) s.close();
                    clearSession();
                    setState(State.OFFLINE, null);
                    retries = 0;
                } catch (Exception e) {
                    Log.i(TAG, mac.name + ": session ended: " + e);
                    SillTls s = session;
                    session = null;
                    if (s != null) s.close();
                    clearSession();
                    if (e instanceof Ended) {
                        Ended g = (Ended) e;
                        if (!g.reconnect) {
                            wanted = false;
                            needsPairing = g.removed;
                            setState(State.FAILED, g.text);
                            return;
                        }
                    }
                    if (identityChanged(e)) {
                        wanted = false;
                        needsPairing = true;
                        setState(State.FAILED, context.getString(com.termux.x11.app.R.string.xd_mac_identity_changed));
                        return;
                    }
                    setState(State.OFFLINE, wanted ? e.getMessage() : null);
                }
                if (SystemClock.uptimeMillis() - started > 30_000) retries = 0;
                // Nobody has shown or asked for this Mac for a while: stay disconnected until asked again
                synchronized (lock) {
                    if (surface == null && SystemClock.uptimeMillis() - lastUse > IDLE_MS) {
                        wanted = false;
                        runner = null;
                        setState(State.OFFLINE, null);
                        return;
                    }
                    if (!wanted) continue;
                    backingOff = true;
                }
                // A new default network: at once. While a screen shows the Mac, never wait long enough to be noticed.
                boolean now = reconnectAtOnce;
                reconnectAtOnce = false;
                long delay = now ? 0 : Math.min(surface != null ? SHOWN_BACKOFF_MAX_MS : BACKOFF_MAX_MS,
                        1000L << Math.min(retries++, 5));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    // Someone waits for this Mac (awaitConnected), a network came back, or shutdown(): now, from a
                    // short pause again
                    retries = 0;
                }
                synchronized (lock) {
                    backingOff = false;
                }
                Thread.interrupted();
            }
        } finally {
            // This runner's own hold: a successor that already started has its own
            networkWatch(false);
        }
    }

    /**
     * Watches the default network while this client wants a connection: the Mac's address on a mobile link is
     * not the one on Wi-Fi, and a session dialled on the old one is dead. Off: no callback is left registered.
     */
    private void networkWatch(boolean on) {
        synchronized (lock) {
            if (on) {
                networkUsers++;
                // One callback for this client; a dying runner's successor keeps it
                if (networkUsers > 1 || networkCallback != null) return;
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) return;
                ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network network) {
                        boolean changed;
                        synchronized (lock) {
                            // The first one is the network this client started on, not a change; one after a loss
                            // or in place of another is
                            changed = networkSeen && (defaultNetwork == null || !defaultNetwork.equals(network));
                            networkSeen = true;
                            defaultNetwork = network;
                        }
                        if (changed) networkChanged();
                    }

                    @Override public void onLost(Network network) {
                        boolean lost;
                        synchronized (lock) {
                            lost = network.equals(defaultNetwork);
                            if (lost) defaultNetwork = null;
                        }
                        if (lost) networkChanged();
                    }
                };
                try {
                    cm.registerDefaultNetworkCallback(cb);
                    networkCallback = cb;
                } catch (RuntimeException e) {
                    Log.w(TAG, mac.name + ": network watch", e);
                }
            } else {
                if (networkUsers == 0) return;
                networkUsers--;
                if (networkUsers > 0) return;
                ConnectivityManager.NetworkCallback cb = networkCallback;
                networkCallback = null;
                defaultNetwork = null;
                networkSeen = false;
                if (cb == null) return;
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) return;
                try {
                    cm.unregisterNetworkCallback(cb);
                } catch (RuntimeException ignored) { }
            }
        }
    }

    /** A different default network: the old session cannot survive it, so close it and redial without the pause. */
    private void networkChanged() {
        networkEpoch++;
        reconnectAtOnce = true;
        Log.i(TAG, mac.name + ": the default network changed, reconnecting");
        SillTls s = session;
        if (s != null) s.close();
        synchronized (lock) {
            if (backingOff && runner != null) runner.interrupt();
        }
    }

    /**
     * Holds the Wi-Fi radio in its low-latency mode while a screen shows this Mac (Sill's video class on the Mac's
     * side has a home for this here). Released when the surface goes, and on shutdown.
     */
    private void setWifiLock(boolean on) {
        synchronized (lock) {
            if (on == (wifiLock != null)) return;
            if (on) {
                WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                if (wm == null) return;
                try {
                    WifiManager.WifiLock lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "xdesk-mac");
                    lock.acquire();
                    wifiLock = lock;
                } catch (RuntimeException e) {
                    Log.w(TAG, mac.name + ": Wi-Fi low latency", e);
                }
            } else {
                WifiManager.WifiLock lock = wifiLock;
                wifiLock = null;
                try {
                    lock.release();
                } catch (RuntimeException ignored) { }
            }
        }
    }

    /** The Mac answered with another key than the one paired (SillTls's pin check, somewhere in the cause chain). */
    private static boolean identityChanged(Throwable e) {
        for (int i = 0; e != null && i < 8; i++, e = e.getCause())
            if (String.valueOf(e.getMessage()).contains("Mac identity changed")) return true;
        return false;
    }

    /** A goodbye (kind 22): why the Mac closes the session. */
    private static final class Ended extends IOException {
        final String text;
        final boolean reconnect, removed;

        Ended(String text, boolean reconnect, boolean removed) {
            super(text);
            this.text = text;
            this.reconnect = reconnect;
            this.removed = removed;
        }
    }

    private void session(MacStore.Mac m) throws Exception {
        long epoch = networkEpoch;
        SillTls s = null;
        // The Mac on this network, found by its Bonjour recognition tag: its home door takes the same paired key
        // as the remote one (Sill's DoorPolicy.trusts: a paired key is trusted for "sill/1" at home), with the home
        // quality and the home link's immediate keyframe recovery. A Mac paired before Sill sent a recognition key
        // has none, and is never sought: the 300 ms wait would only delay its saved address.
        MacDiscovery.Door home = m.recognition != null && m.recognition.length == 32
                ? MacDiscovery.get(context).homeDoor(m.recognition, HOME_DISCOVERY_WAIT_MS) : null;
        if (home != null) {
            try {
                // A LAN answers at once: a stale Bonjour record must not hold the saved address back for 8 s
                s = SillTls.connect(home.host, home.port, SillTls.SESSION, m.pin, HOME_CONNECT_MS, HANDSHAKE_MS);
                Log.i(TAG, m.name + ": connected at home (" + home.name + ", " + home.address() + ")");
            } catch (Exception e) {
                Log.i(TAG, m.name + ": home door " + home.address() + " did not answer, using the saved address: " + e);
            }
        }
        if (s == null) {
            s = SillTls.connect(m.host, m.port, SillTls.SESSION, m.pin, CONNECT_MS, HANDSHAKE_MS);
            Log.i(TAG, m.name + ": connected at the saved address " + m.address());
        }
        // The network changed while this session was dialled: start the next one on the new one
        if (networkEpoch != epoch) {
            s.close();
            throw new IOException("network changed");
        }
        final SillTls opened = s;
        inputsQueued.set(0);
        rtt = new Rtt();
        session = opened;
        // A change between the check above and here: the callback could not close a session it did not see yet
        if (networkEpoch != epoch) {
            opened.close();
            throw new IOException("network changed");
        }
        windowsPending = true;
        stateChanged();
        JSONObject hello = new JSONObject().put("protocol", 1).put("appVersion", appVersion()).put("device", Build.MODEL)
                .put("build", "xdesk");
        helloAudio(hello);
        opened.send(SillWire.HELLO, hello.toString().getBytes(StandardCharsets.UTF_8));
        // The pinger closes a connection without pongs, but it writes too, and a write into a dead mobile connection
        // can block it: nothing at all read for this long (pongs come every 1 to 4 s) ends the session as well
        opened.readTimeout(readTimeout());
        lastPong = SystemClock.uptimeMillis();
        Thread sender = new Thread(() -> sendLoop(opened), "mac-out");
        sender.start();
        Thread pinger = new Thread(() -> pingLoop(opened), "mac-ping");
        pinger.start();
        SillWire.Message msg = new SillWire.Message();
        try {
            while (wanted && session == opened) {
                SillWire.read(opened.in, msg, SillWire.MAX_FRAME);
                handle(opened, msg);
            }
        } finally {
            sender.interrupt();
            pinger.interrupt();
            synchronized (out) {
                out.clear();
                out.notifyAll();
            }
        }
    }

    private String appVersion() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    private void clearSession() {
        video.reset();
        videoW = videoH = 0;
        lastCursor = null;
        windowsPending = true;
        stateChanged();
        activeWindow = -1;
        activeDesktop = false;
        desktopDisplays = desktopDisplay = 0;
        desktopDisplayNames = Collections.emptyList();
        wantDisplay = 0;
        had = NONE;
        // Not connected: no window of this Mac is known to be open (the lists show them as running)
        windows = Collections.emptyList();
        locked = offConsole = offConsoleView = false;
        // The next session's first list says whether it syncs the clipboard: start it again then
        clipboardGeneration = 0;
        staging = null;
        stopClipboard();
        settingsCleared();
        closeAudio();
        // The next session's menus start afresh (a new connection, so a new subscription); anything the quit
        // machine still waits for settles now, and it ends on the next state check
        for (MacMenuState.Done d : mirroredMenus.reset()) quitAnswer(d);
        menuSubscribed = false;
    }

    private void pingLoop(SillTls s) {
        try {
            long lastSent = SystemClock.uptimeMillis(), lastDeadline = -1;
            while (session == s) {
                // A screen wants the pongs for liveness; the settings page for the round trip it shows (Sall pings
                // always). The interval is read again after each short step, so the page's 1 s cadence starts at
                // once when it opens mid-wait, not up to 4 s later
                long interval = surface != null || !settingsListeners.isEmpty() ? 1000 : 4000;
                long deadline = pongDeadline();
                if (lastDeadline != deadline) {
                    // The reader's backstop above the pinger's deadline: a write that blocks must not outlive it
                    lastDeadline = deadline;
                    int timeout = (int) Math.min(Integer.MAX_VALUE, deadline + 8_000);
                    try {
                        s.readTimeout(timeout);
                    } catch (java.io.IOException e) {
                        s.close();
                        return;
                    }
                }
                // Checked every step, not only when a ping is due: a dead link is noticed within a quarter second
                // of its deadline
                if (SystemClock.uptimeMillis() - lastPong > deadline) {
                    Log.i(TAG, "no message for " + deadline + " ms: the connection is gone");
                    s.close();
                    return;
                }
                if (SystemClock.uptimeMillis() - lastSent >= interval) {
                    s.send(SillWire.PING, ByteBuffer.allocate(8).putDouble(System.nanoTime() / 1e9).array());
                    lastSent = SystemClock.uptimeMillis();
                    checkLaunch();
                    askAgain();
                    if (statusSettling && !settling()) statusToView();
                    synchronized (lock) {
                        if (surface == null && SystemClock.uptimeMillis() - lastUse > IDLE_MS) {
                            wanted = false;
                            s.close();
                            return;
                        }
                    }
                }
                Thread.sleep(250);
            }
        } catch (InterruptedException | IOException e) {
            s.close();
        }
    }

    /**
     * How long silence may last. With a screen on this Mac the picture is what someone is watching: 6 s, or four
     * times the worst round trip of the last moment when the link is slower than that. No screen: the old 12 s,
     * since nothing waits on the picture.
     */
    private long pongDeadline() {
        if (surface == null) return PONG_DEADLINE_MS;
        Rtt r = rtt;
        Long worst = r != null ? r.worst(SystemClock.uptimeMillis()) : null;
        return Math.max(MIN_DEADLINE_MS, worst != null ? 4 * worst : 0);
    }

    /** The socket's read timeout: above the pinger's deadline, so the pinger is what normally ends a dead link. */
    private int readTimeout() {
        return (int) Math.min(Integer.MAX_VALUE, pongDeadline() + 8_000);
    }

    private void sendLoop(SillTls s) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        try {
            while (session == s) {
                Object item;
                synchronized (out) {
                    while (out.isEmpty() && session == s) out.wait();
                    if (session != s) return;
                    item = out.poll();
                }
                if (item instanceof Move) {
                    Move p = (Move) item;
                    byte[] json = pointerJson("move", p.x, p.y);
                    long start = SystemClock.uptimeMillis();
                    s.send(SillWire.INPUT, json);
                    PointerStats.sent(start - p.at, SystemClock.uptimeMillis() - start);
                } else {
                    byte[] b = (byte[]) item;
                    int kind = b[0] & 255;
                    byte[] payload = new byte[b.length - 1];
                    System.arraycopy(b, 1, payload, 0, payload.length);
                    s.send(kind, payload);
                }
            }
        } catch (InterruptedException | IOException e) {
            s.close();
        }
    }

    private void enqueue(int kind, byte[] payload) {
        if (session == null) return;
        byte[] b = new byte[payload.length + 1];
        b[0] = (byte) kind;
        System.arraycopy(payload, 0, b, 1, payload.length);
        synchronized (out) {
            if (kind == SillWire.INPUT) inputsQueued.incrementAndGet();
            out.add(b);
            out.notifyAll();
        }
    }

    private void enqueue(int kind, JSONObject o) {
        enqueue(kind, o.toString().getBytes(StandardCharsets.UTF_8));
    }

    // --- incoming ---

    private void handle(SillTls s, SillWire.Message m) throws Exception {
        // Any message at all is the Mac still there: pongs, frames, a list. Only silence is a dead link.
        lastPong = SystemClock.uptimeMillis();
        switch (m.kind) {
        case SillWire.FRAME: {
            Surface target = surface;
            // Only another user's session hides the picture; a locked screen is shown, so the desktop can be unlocked (as sall)
            if (locked && offConsole && !offConsoleView || target == null) return;
            resumePass();
            if (passFrame(s, m)) return;
            if (!video.frame(m.payload, m.length, m.keyframe, m.timestamp, target)) askKeyframe(s);
            return;
        }
        case SillWire.PARAMETER_SETS:
            video.parameters(m.payload, m.length, surface);
            lastParameters = java.util.Arrays.copyOf(m.payload, m.length);
            passParameters();
            return;
        case SillWire.TICK:
            video.drain();
            return;
        case SillWire.PONG:
            lastPong = SystemClock.uptimeMillis();
            // The pong echoes the ping's send time: one round trip sample (Sall's ConnectionDiagnostics.Rtt)
            if (m.length == 8) {
                double elapsed = System.nanoTime() / 1e9 - ByteBuffer.wrap(m.payload, 0, 8).getDouble();
                Rtt r = rtt;
                if (r != null && Double.isFinite(elapsed) && elapsed >= 0 && elapsed <= 60)
                    r.add(elapsed, SystemClock.uptimeMillis());
            }
            return;
        case SillWire.WINDOW_LIST:
            windowList(new JSONObject(m.text()));
            return;
        case SillWire.APP_LIST: {
            JSONArray a = new JSONArray(m.text());
            ArrayList<App> list = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                list.add(new App(o.optString("name"), o.optString("bundleID")));
            }
            Collections.sort(list, (x, y) -> x.name.compareToIgnoreCase(y.name));
            apps = Collections.unmodifiableList(list);
            changed();
            return;
        }
        case SillWire.APP_ICON:
            appIcon(m);
            return;
        case SillWire.CURSOR_SHAPE:
            cursorShape(m);
            return;
        case SillWire.MAC_MENU:
            macMenu(new JSONObject(m.text()));
            return;
        case SillWire.MAC_POINTER: {
            // The Mac's own mouse (or another device) moved its pointer. This side's pointer is the one the user
            // moves and looks at, so a report only ever moves it, as in Sall (PointerSync.accepts): never one built
            // before the Mac read every input queued here, and never one that says the pointer is off the stream.
            // That one used to hide the arrow, until the next move here: on a Mac with a second display, or with a
            // window on Sill's virtual display, the pointer vanished whenever the Mac's own mouse was touched
            JSONObject p = new JSONObject(m.text());
            final double x = p.optDouble("x", Double.NaN), y = p.optDouble("y", Double.NaN);
            if (!p.optBoolean("inside", false) || !(x >= 0 && x <= 1 && y >= 0 && y <= 1)) {
                reportNote(0, m.text());
                return;
            }
            synchronized (out) {
                if (p.optLong("seen", 0) < inputsQueued.get()) {
                    reportNote(1, m.text() + ", queued here " + inputsQueued.get());
                    return;
                }
                pointerX = (float) x;
                pointerY = (float) y;
            }
            reportNote(2, m.text());
            ui.post(() -> {
                View v = view;
                if (v != null) v.pointer(pointerX, pointerY);
            });
            Passthrough pt = pass;
            if (pt != null) pt.cursorPosition(pointerX, pointerY, true);
            return;
        }
        case SillWire.HOST_SETTINGS: {
            JSONObject o = new JSONObject(m.text());
            hostSettings(o);
            settingsState(o);
            return;
        }
        case SillWire.AUDIO:
            audioMessage(m);
            return;
        case SillWire.CLIPBOARD:
            clipboardFromMac(new JSONObject(m.text()).optString("text", ""));
            return;
        case SillWire.GOODBYE: {
            JSONObject g = new JSONObject(m.text());
            String reason = g.optString("reason", "");
            String text = g.optString("message", "");
            boolean removed = "removed".equals(reason);
            // Sill's GoodbyePolicy: the first build's reasons keep their own rules, a later one says by "reconnect"
            boolean again = "quit".equals(reason) || "busy".equals(reason) ? true
                    : removed || "remoteOff".equals(reason) || "internetOff".equals(reason) || "pairingRequired".equals(reason)
                    || "update".equals(reason) ? false : g.optBoolean("reconnect", false);
            if (removed) text = context.getString(com.termux.x11.app.R.string.xd_mac_unpaired);
            else if ("remoteOff".equals(reason) || "internetOff".equals(reason)) text = context.getString(com.termux.x11.app.R.string.xd_mac_remote_off);
            else if ("quit".equals(reason) && text.isEmpty()) text = context.getString(com.termux.x11.app.R.string.xd_mac_quit);
            else if (text.isEmpty()) text = reason;
            throw new Ended(text, again, removed);
        }
        default:
            // Thumbnails (3), Mac info (18): not used
        }
    }

    /**
     * Kind 24 (MacMenu.swift): the top level of the streamed app's menu bar (with `menus`), or the answer to
     * one of this connection's kind 27s or 25s (with `answering`). What the menu state settles goes to the quit
     * machine (quitAnswer) and to the menu bar UI's listeners. Reader thread.
     */
    private void macMenu(JSONObject menu) {
        MacMenuState.Received received;
        JSONArray top = menu.optJSONArray("menus");
        if (top != null) {
            received = mirroredMenus.receiveTop(optInt(menu, "version"), optString(menu, "app"),
                    optString(menu, "bundleID"), menuItems(top), menu.optBoolean("stale", false),
                    optString(menu, "note"));
        } else {
            Long answering = optLong(menu, "answering");
            if (answering == null) return;
            JSONArray items = menu.optJSONArray("items");
            received = mirroredMenus.receiveAnswer(answering, optInt(menu, "version"),
                    items == null ? null : menuItems(items), Math.max(0, menu.optInt("more", 0)),
                    optBool(menu, "pressed"), optString(menu, "note"));
        }
        for (MacMenuState.Done d : received.done) quitAnswer(d);
        if (!received.done.isEmpty() || received.topChanged) {
            final MacMenuState.Top now = mirroredMenus.top();
            final java.util.List<MacMenuState.Done> done = received.done;
            for (MenuListener l : menuListeners) ui.post(() -> l.menusChanged(now, done));
        }
    }

    /** Sall's optionalMenu*: a field is read only when it is there and of its kind (HostSettings.swift's rules). */
    private static String optString(JSONObject o, String name) {
        Object v = o.opt(name);
        return v instanceof String ? (String) v : null;
    }

    private static Integer optInt(JSONObject o, String name) {
        Object v = o.opt(name);
        if (!(v instanceof Number)) return null;
        double n = ((Number) v).doubleValue();
        return Double.isFinite(n) && n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE && n == Math.rint(n)
                ? (int) n : null;
    }

    private static Long optLong(JSONObject o, String name) {
        Object v = o.opt(name);
        if (!(v instanceof Number)) return null;
        double n = ((Number) v).doubleValue();
        return Double.isFinite(n) && n >= 0 && n <= 9007199254740991d && n == Math.rint(n)
                ? ((Number) v).longValue() : null;
    }

    private static Boolean optBool(JSONObject o, String name) {
        Object v = o.opt(name);
        return v instanceof Boolean ? (Boolean) v : null;
    }

    private static List<MacMenuState.Item> menuItems(JSONArray values) {
        ArrayList<MacMenuState.Item> items = new ArrayList<>();
        if (values == null) return items;
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            if (item == null) continue;
            Object enabled = item.opt("enabled");
            items.add(new MacMenuState.Item(optString(item, "id"), optString(item, "title"),
                    item.optBoolean("separator", false), enabled instanceof Boolean ? (Boolean) enabled : null,
                    optString(item, "mark"), optString(item, "key"), item.optBoolean("submenu", false)));
        }
        return items;
    }

    private void askKeyframe(SillTls s) {
        long now = SystemClock.uptimeMillis();
        if (now - lastKeyframeAsk < 1000) return;
        lastKeyframeAsk = now;
        try {
            s.send(SillWire.KEYFRAME_REQUEST, new byte[0]);
        } catch (IOException e) {
            s.close();
        }
    }

    private void windowList(JSONObject list) throws JSONException {
        JSONArray a = list.optJSONArray("windows");
        ArrayList<Window> next = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject w = a.getJSONObject(i);
            next.add(new Window(w.getLong("id"), w.optString("title"), w.optString("appName"), w.optString("bundleID")));
        }
        windows = Collections.unmodifiableList(next);
        String name = list.optString("macName", "");
        if (!name.isEmpty() && !name.equals(macName)) {
            macName = name;
            MacStore.rename(context, mac.id, name);
        }
        locked = list.optBoolean("locked", false);
        offConsole = list.optBoolean("offConsole", false);
        offConsoleView = list.optBoolean("offConsoleView", false);
        keyRepeat = list.optInt("keyRepeat", 0) >= 1;
        int clip = list.optInt("clipboard", 0);
        boolean clipNew = clip >= 1 && clipboardGeneration < 1;
        clipboardGeneration = clip;
        JSONObject active = list.optJSONObject("active");
        activeDesktop = active != null && active.has("desktop");
        int displays = list.optInt("desktopDisplays", 0), current = list.optInt("desktopDisplay", 0);
        if (displays < 2 || current < 1 || current > displays) displays = current = 0;
        ArrayList<String> names = new ArrayList<>();
        JSONArray n = displays > 0 ? list.optJSONArray("desktopDisplayNames") : null;
        if (n != null) for (int i = 0; i < n.length(); i++) names.add(n.optString(i, ""));
        desktopDisplays = displays;
        desktopDisplay = current;
        desktopDisplayNames = Collections.unmodifiableList(names);
        stepToDisplay();
        JSONObject win = active != null ? active.optJSONObject("window") : null;
        activeWindow = win != null ? win.optLong("_0", -1) : -1;
        long streams = activeSource();
        if (streams != NONE && streams == want) had = streams;
        boolean first = windowsPending;
        windowsPending = false;
        // The app launched from here got its first window: the host picked it; it is what this screen shows now
        String launch = pendingLaunch;
        if (launch != null && activeWindow >= 0) {
            for (Window w : next) {
                if (w.id == activeWindow && launch.equals(w.bundleId)) {
                    pendingLaunch = null;
                    want = had = activeWindow;
                }
            }
        }
        if (first) {
            setState(State.CONNECTED, null);
            if (surface != null && want != NONE) {
                switchAt = SystemClock.uptimeMillis();
                selectSource(want);
                sendViewport();
            }
        }
        if (clipNew && surface != null) startClipboard();
        stateChanged();  // awaitConnected also waits for the first list
        changed();
        statusToView();
    }

    private void appIcon(SillWire.Message m) {
        byte[] b = m.payload;
        if (m.length < 3) return;
        int n = (b[0] & 255) << 8 | b[1] & 255;
        if (m.length <= 2 + n) return;
        String id = new String(b, 2, n, StandardCharsets.UTF_8);
        int offset = 2 + n, len = m.length - offset;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, offset, len, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 2048 || bounds.outHeight > 2048) return;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / 128);
        Bitmap bm = BitmapFactory.decodeByteArray(b, offset, len, o);
        if (bm == null) return;
        if (icons.size() >= 256 && !icons.containsKey(id)) return;
        icons.put(id, bm);
        changed();
    }

    /** Reports of the Mac's pointer (kind 26) by what became of them, said once a second at most. Reader thread. */
    private final int[] reports = new int[3];
    private long reportsSaid;

    private void reportNote(int what, String last) {
        reports[what]++;
        long now = SystemClock.uptimeMillis();
        if (now - reportsSaid < 1000) return;
        reportsSaid = now;
        Log.d(TAG, "pointer reports: " + reports[2] + " moved the pointer, " + reports[1] + " older than the input queued, "
                + reports[0] + " off the picture; last (" + (what == 2 ? "moved" : what == 1 ? "old" : "off") + ") " + last);
        reports[0] = reports[1] = reports[2] = 0;
    }

    private void cursorShape(SillWire.Message m) {
        if (m.length <= 16) return;
        ByteBuffer b = ByteBuffer.wrap(m.payload, 0, 16);
        final float hx = b.getFloat(), hy = b.getFloat(), w = b.getFloat(), h = b.getFloat();
        if (!(w > 0 && h > 0 && w < 512 && h < 512)) return;
        final Bitmap bm;
        try {
            bm = BitmapFactory.decodeByteArray(m.payload, 16, m.length - 16);
        } catch (RuntimeException e) {
            return;
        }
        if (bm == null) return;
        lastCursor = java.util.Arrays.copyOf(m.payload, m.length);
        passCursor();
        final boolean blank = blank(bm);
        ui.post(() -> {
            View v = view;
            if (v != null) v.cursor(bm, hx, hy, w, h, blank);
        });
    }

    /** A kept cursor message (lastCursor) for a screen that starts showing this Mac. UI thread. */
    private static void cursorTo(View v, byte[] c) {
        if (c.length <= 16) return;
        ByteBuffer b = ByteBuffer.wrap(c, 0, 16);
        float hx = b.getFloat(), hy = b.getFloat(), w = b.getFloat(), h = b.getFloat();
        Bitmap bm;
        try {
            bm = BitmapFactory.decodeByteArray(c, 16, c.length - 16);
        } catch (RuntimeException e) {
            return;
        }
        if (bm != null) v.cursor(bm, hx, hy, w, h, blank(bm));
    }

    /**
     * Nothing to see in this cursor: the Mac's app set an empty one (a player over its video, a game). A cursor is a
     * few thousand pixels and changes a few times a second at most.
     */
    private static boolean blank(Bitmap bm) {
        int w = bm.getWidth(), h = bm.getHeight();
        if (w > 512 || h > 512) return false;
        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        for (int p : px) if ((p >>> 24) > 24) return false;
        return true;
    }

    // --- passthrough (DISPLAY.md §3.4): the screen's picture goes to a receiver that decodes HEVC itself ---

    /** Set by XDesk: who can take this Mac's video compressed for a display (the wireless receiver's). */
    public static volatile Passthrough.Provider passthroughProvider;
    private volatile Passthrough pass;
    /** The display the passthrough was opened for (the wireless receiver's), from the last setSurface. */
    private volatile int passDisplay = Display.INVALID_DISPLAY;
    /** A status line took the picture (locked, taken, the session down): the passthrough waits for frames (resumePass). */
    private volatile boolean passSuspended;
    /** The last parameter sets and cursor as Sill sent them: a passthrough that opens later starts with them. */
    private volatile byte[] lastParameters, lastCursor;
    private byte[] annexB;
    private final int[] annexLength = new int[1];
    /** The receiver's socket writes that the UI thread starts (a new or lost surface, a resize), in order. */
    private static final java.util.concurrent.ExecutorService passIo =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "mac-pass"));

    /** Runs r off the UI thread: it writes to the receiver (NetworkOnMainThreadException there). */
    private static void offUi(Runnable r) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) passIo.execute(r);
        else r.run();
    }

    /** Reader thread. True when the frame went to the passthrough (or was dropped for it). */
    private boolean passFrame(SillTls s, SillWire.Message m) {
        Passthrough p = pass;
        if (p == null) return false;
        if (!p.alive()) {
            // The receiver went away: decode here again, from a keyframe
            dropPass(p);
            return false;
        }
        byte[] b = video.annexB(m.payload, m.length, annexB, annexLength);
        if (b == null) return true;
        annexB = b;
        if (!p.frame(b, annexLength[0], (long) (m.timestamp * 1_000_000), m.keyframe)) askKeyframe(s);
        return true;
    }

    private void passParameters() {
        Passthrough p = pass;
        byte[] raw = lastParameters, config = video.config();
        if (p == null || raw == null || config == null) return;
        int[] size = MacVideo.spsSize(raw, raw.length);
        if (size == null || !p.parameters(config, size[0], size[1])) {
            Log.i(TAG, "passthrough: this stream can't go to the receiver, decoding on the phone");
            dropPass(p);
            return;
        }
        // No decoder here says the size: the screen letterboxes by the SPS's
        videoSize(size[0], size[1]);
    }

    private void passCursor() {
        Passthrough p = pass;
        byte[] c = lastCursor;
        int fw = frameW;
        if (p == null || c == null || fw <= 0 || c.length <= 16) return;
        ByteBuffer b = ByteBuffer.wrap(c, 0, 16);
        float hx = b.getFloat(), hy = b.getFloat(), w = b.getFloat(), h = b.getFloat();
        // A Mac point is pointScale pixels on the display; the picture is fw pixels wide on it
        float k = pointScale() / fw;
        p.cursorShape(c, 16, c.length - 16, w * k, h * k, hx * k, hy * k);
        p.cursorPosition(pointerX, pointerY, true);
    }

    private void dropPass(Passthrough p) {
        synchronized (surfaceLock) {
            if (pass != p) return;
            pass = null;
        }
        p.end();
        askKeyframeSoon();
        sendViewport();
    }

    /**
     * A status line is about to cover the screen (statusToView): the passthrough must go, or the receiver would sit
     * on a frozen picture and never see the line. The phone decodes and encodes again, line and all, until frames
     * come again (resumePass). Any thread; the end is off the UI thread, it writes to the receiver.
     */
    private void suspendPass() {
        Passthrough p = pass;
        if (p == null) return;
        passSuspended = true;
        synchronized (surfaceLock) {
            if (pass != p) return;
            pass = null;
        }
        offUi(p::end);
        askKeyframeSoon();
        sendViewport();
    }

    /** Frames came again and they are this side's own picture: the passthrough comes back. Reader thread. */
    private void resumePass() {
        if (pass != null) {
            passSuspended = false;
            return;
        }
        if (!passSuspended || surface == null || !oursFlowing()) return;
        Passthrough.Provider p = passthroughProvider;
        Passthrough opened = p != null ? p.open(passDisplay, this::askKeyframeSoon) : null;
        if (opened != null) {
            passSuspended = false;
            pass = opened;
            Log.i(TAG, "passthrough again, frames came back");
            offUi(() -> {
                passParameters();
                passCursor();
            });
        }
    }

    /**
     * Whether the Mac streams the picture this side wants: false while the status line covers the screen
     * (statusToView). Frames of another source still reach every device when the stream is taken over, and must not
     * bring the passthrough back while the line is up.
     */
    private boolean oursFlowing() {
        if (state != State.CONNECTED) return false;
        if (locked && (offConsole && !offConsoleView || !offConsoleView && want != DESKTOP)) return false;
        return want >= 0 ? activeWindow == want : want == DESKTOP && activeDesktop;
    }

    // --- the screen ---

    /** A screen shows this Mac (MacActivity). One at a time: a new one replaces the old. */
    public void attach(final View v) {
        view = v;
        touch();
        statusToView();
        // The stream may be running already (this Mac was on another screen a moment ago): its size and cursor now
        final int w = videoW, h = videoH;
        if (w > 0 && h > 0) ui.post(() -> {
            if (view == v) v.videoSize(w, h);
        });
        final byte[] c = lastCursor;
        if (c != null) ui.post(() -> {
            if (view == v) cursorTo(v, c);
        });
    }

    public void detach(View v) {
        if (view == v) view = null;
    }

    /** Who gave the surface (a MacActivity): only it may take it away. */
    private Object surfaceOwner;

    /**
     * The surface of the screen showing this Mac, from {@code owner}, and its size in pixels. A new owner replaces the
     * old one: the screen that showed this Mac before and is still going away (Mac, an app, the Mac again) can no
     * longer take the new one's surface (clearSurface).
     */
    public void setSurface(Object owner, Surface s, int width, int height, float density, int fps, int displayId) {
        synchronized (surfaceLock) {
            surfaceOwner = owner;
        }
        applySurface(s, width, height, density, fps, displayId);
    }

    /** The owner's surface is gone: the decoder must not render into it any more. Nothing if another owner took over. */
    public void clearSurface(Object owner) {
        synchronized (surfaceLock) {
            if (surfaceOwner != owner) return;
            surfaceOwner = null;
        }
        applySurface(null, 0, 0, 1, 60, -1);
    }

    private void applySurface(Surface s, int width, int height, float density, int fps, int displayId) {
        boolean fresh, resized;
        Passthrough ended = null;
        passDisplay = displayId;
        synchronized (surfaceLock) {
            Surface old = surface;
            fresh = s != old;
            resized = width != surfaceW || height != surfaceH || fps != this.fps;
            surface = s;
            surfaceW = width;
            surfaceH = height;
            this.density = density;
            this.fps = fps;
            if (s == null || fresh) {
                video.release();
                ended = pass;
                pass = null;
            }
        }
        // A screen showing this Mac gets the Wi-Fi radio's low-latency mode; no screen, no lock
        setWifiLock(s != null);
        if (ended != null) offUi(ended::end);
        if (s != null && fresh) {
            // A wireless receiver that decodes HEVC gets Sill's stream as it is: nothing decoded or encoded here
            Passthrough.Provider p = passthroughProvider;
            Passthrough opened = p != null ? p.open(displayId, this::askKeyframeSoon) : null;
            if (opened != null) {
                Log.i(TAG, "passthrough to the receiver of display " + displayId);
                pass = opened;
                passSuspended = false;
                // A status line already covering the screen (locked, taken) would never reach the receiver through
                // the passthrough: the picture goes back to the encoder until frames come again
                statusToView();
                offUi(() -> {
                    passParameters();
                    passCursor();
                });
            }
        }
        synchronized (lock) {
            lastUse = SystemClock.uptimeMillis();
        }
        if (s != null) {
            touch();
            if (state == State.CONNECTED && fresh) {
                // While a launch waits for its window there is nothing to pick yet
                if (want != NONE) {
                    switchAt = SystemClock.uptimeMillis();
                    selectSource(want);
                }
                sendViewport();
                askKeyframeSoon();
                if (clipboardGeneration >= 1) startClipboard();
            } else if (state == State.CONNECTED && resized) {
                sendViewport();
            }
        } else {
            stopClipboard();
        }
        // Whether sound plays at all follows the surface; only then does a pulled headphone plug mean anything
        AudioMute m = mute;
        if (m != null) m.setWatched(s != null);
    }

    private void askKeyframeSoon() {
        SillTls s = session;
        if (s == null) return;
        lastKeyframeAsk = 0;
        enqueue(SillWire.KEYFRAME_REQUEST, new byte[0]);
    }

    /** Where the picture is on the screen (letterboxed), and the screen, in pixels: input maps through it. */
    public void setFrameRect(int x, int y, int w, int h, int sw, int sh) {
        boolean wider = w != frameW;
        frameX = x;
        frameY = y;
        frameW = w;
        frameH = h;
        screenW = sw;
        screenH = sh;
        if (wider) offUi(this::passCursor);
    }

    /** x, y, width, height of the picture, then the screen's width and height; zeros before it is laid out. */
    public int[] frameRect() {
        return new int[] {frameX, frameY, frameW, frameH, screenW, screenH};
    }

    /**
     * The Desktop on display n (from 1) of the Mac's: the Desktop, then kind 47 until the list says n. Sill has no
     * pick of a display yet (kind 47's {"number": n} is kept for a later list), so it is stepped there, one step per
     * list, and given up after a while (a locked Mac refuses the steps).
     */
    public void showDesktop(int n) {
        show(DESKTOP);
        wantDisplay = n;
        wantDisplayAt = SystemClock.uptimeMillis();
        lastStepAt = 0;
        steppedFrom = 0;
        stepToDisplay();
    }

    private final Runnable stepAgain = this::stepToDisplay;

    private synchronized void stepToDisplay() {
        int n = wantDisplay;
        if (n <= 0) return;
        long now = SystemClock.uptimeMillis();
        Log.i(TAG, "display: want " + n + ", at " + desktopDisplay + " of " + desktopDisplays + ", desktop " + activeDesktop
                + ", " + state);
        if (desktopDisplays < 2 || n > desktopDisplays || desktopDisplay == n || now - wantDisplayAt > DISPLAY_GIVE_UP_MS) {
            // There (or nothing to step: one display, a locked Mac): the Desktop as it is
            if (desktopDisplays >= 2 || now - wantDisplayAt > DISPLAY_GIVE_UP_MS) wantDisplay = 0;
            return;
        }
        // Only while the Desktop streams does the Mac take the step; one at a time, the next once the list says the
        // last one moved it (or after a while, in case that list or the step was lost)
        if (state != State.CONNECTED || !activeDesktop) {
            // The Desktop not streaming yet: looked at again soon, not only when a list comes
            ui.removeCallbacks(stepAgain);
            ui.postDelayed(stepAgain, 500);
            return;
        }
        if (lastStepAt != 0 && desktopDisplay == steppedFrom && now - lastStepAt < DISPLAY_STEP_AGAIN_MS) return;
        lastStepAt = now;
        steppedFrom = desktopDisplay;
        enqueue(SillWire.NEXT_DISPLAY, new JSONObject());
        // Looked at again also without a new list: the step or its list may not come
        ui.removeCallbacks(stepAgain);
        ui.postDelayed(stepAgain, DISPLAY_STEP_AGAIN_MS + 100);
    }

    /** Stream this window (id), DESKTOP, or nothing (NONE): the screen showing this Mac took something else. */
    public void show(long source) {
        want = source;
        wantDisplay = 0;
        pendingLaunch = null;
        launchFailed = false;
        if (state == State.CONNECTED && (surface != null || source == NONE)) {
            switchAt = SystemClock.uptimeMillis();
            selectSource(source);
        }
        statusToView();
    }

    /** Open an installed app: the host starts it and picks its first window (Sill's pendingLaunch). */
    public void launch(String bundleId) {
        pendingLaunch = bundleId;
        launchFailed = false;
        launchAt = SystemClock.uptimeMillis();
        want = NONE;
        try {
            enqueue(SillWire.LAUNCH_APP, new JSONObject().put("bundleID", bundleId));
        } catch (JSONException ignored) { }
        statusToView();
    }

    private void checkLaunch() {
        if (pendingLaunch != null && SystemClock.uptimeMillis() - launchAt > LAUNCH_MS) {
            pendingLaunch = null;
            launchFailed = true;
            statusToView();
        }
    }

    /**
     * The desktop was asked for and nothing streams (the Mac could not start it: its display asleep at a locked
     * screen, a capture that stopped): ask again every DESKTOP_AGAIN_MS, as Sill's own clients do
     * (DiscoveryPolicy.autoDesktop), with a pointer move where the pointer is, which the Mac takes as someone at the
     * device and wakes its display for. Never while something else streams: that is another device's pick.
     */
    private void askAgain() {
        if (surface == null || state != State.CONNECTED || want != DESKTOP || activeDesktop || activeWindow >= 0) return;
        if (locked && offConsole && !offConsoleView) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastSelect < DESKTOP_AGAIN_MS) return;
        Log.i(TAG, mac.name + ": the desktop does not stream yet, asking again");
        synchronized (out) {
            inputsQueued.incrementAndGet();
            out.add(new Move(pointerX, pointerY));
            out.notifyAll();
        }
        selectSource(DESKTOP);
        sendViewport();
    }

    private static final long DESKTOP_AGAIN_MS = 10_000;
    private volatile long lastSelect;

    private void selectSource(long source) {
        lastSelect = SystemClock.uptimeMillis();
        // Sill restarts the capture for a select of what already streams (StreamCoordinator.select:
        // restartOf = source == active), 0.5–2 s of frozen picture for nothing. The viewport and a keyframe
        // are all a screen that arrives on a running source needs.
        if (source != NONE && state == State.CONNECTED && activeSource() == source) {
            sendViewport();
            askKeyframeSoon();
            return;
        }
        try {
            JSONObject o = new JSONObject();
            if (source == DESKTOP) o.put("desktop", new JSONObject());
            else if (source >= 0) o.put("window", new JSONObject().put("_0", source));
            else o.put("none", new JSONObject());
            enqueue(SillWire.SELECT_SOURCE, o);
        } catch (JSONException ignored) { }
    }

    /**
     * The panel and scale that make the Mac window the screen in Mac points (pointScale) while keeping Sill's virtual
     * display small: Sill sizes a window to panel ÷ scale and its display to the panel alone, so a big window is a
     * panel of at most PANEL_MAX with a scale under 1. The scale goes only where it sizes a window XDesk may size:
     * on Sill's virtual display (where no scale means 1×, a window the size of the panel), or with "fit to screen"
     * (the real window, put back when the stream ends). Elsewhere no scale leaves the Mac window alone.
     */
    public void sendViewport() {
        if (surfaceW <= 0 || surfaceH <= 0) return;
        float k = pointScale();
        double w = surfaceW / k, h = surfaceH / k, longest = Math.max(w, h);
        double s = longest > PANEL_MAX ? PANEL_MAX / longest : 1;
        try {
            JSONObject v = new JSONObject().put("width", Math.max(1, Math.min(16384, w * s)))
                    .put("height", Math.max(1, Math.min(16384, h * s))).put("fps", viewportFps());
            if (mac.fit || Boolean.TRUE.equals(staging)) v.put("scale", Math.max(0.5, Math.min(1, s)));
            enqueue(SillWire.VIEWPORT, v);
        } catch (JSONException ignored) { }
    }

    /**
     * Sill's settings state (kind 16, after the window list and whenever it changes): whether a window streams from
     * its virtual display, so sendViewport knows whether a scale sizes it there. Where the running stream is a window
     * its own onVirtualDisplay decides (a staging can fail and stream the real window); otherwise the setting.
     */
    private void hostSettings(JSONObject o) {
        JSONObject settings = o.optJSONObject("settings"), stream = o.optJSONObject("stream");
        boolean on = settings != null && settings.optBoolean("virtualDisplay", false)
                && o.optBoolean("virtualDisplayAvailable", true);
        if (activeWindow >= 0 && !activeDesktop && stream != null && stream.has("onVirtualDisplay")) {
            on = stream.optBoolean("onVirtualDisplay", on);
        }
        Boolean before = staging;
        staging = on;
        if (before == null || before != on) {
            Log.i(TAG, "virtual display " + (on ? "in use" : "not in use"));
            if (state == State.CONNECTED && surface != null) sendViewport();
        }
    }

    // --- Sill's settings (kinds 16 and 17) and sound (kind 43): MacStreamSettings is the page ---

    /** What a settings page hears, on the UI thread. */
    public interface SettingsListener {
        int ACCEPTED = 0, REFUSED = 1, TIMEOUT = 2;
        /** The Mac's state, the pending change or the connection changed. */
        void settingsChanged();
        /** The Mac answered the change sent from here (or did not within SETTINGS_ANSWER_MS). */
        void settingsAnswer(int result);
    }

    private static final long SETTINGS_ANSWER_MS = 10_000;
    /** HostSettingsChange.token: strictly increasing for the process, never reset, whichever Mac it goes to. */
    private static final java.util.concurrent.atomic.AtomicInteger settingsToken = new java.util.concurrent.atomic.AtomicInteger();
    private volatile HostSettings hostState;
    /** Who follows the settings: the stream page, the mode rows (MacModeRow) on other pages. UI thread. */
    private final java.util.List<SettingsListener> settingsListeners = new java.util.ArrayList<>();
    // The one change the Mac has not answered yet (Sall's pendingSettings*), UI thread. A quality mode is one
    // HostSettingsChange with several fields (Sill's HostSettingsChange takes them together), so one token and
    // one answer cover all of it.
    private volatile int pendingSettingsToken;
    /** The first field of the pending change, for the rows that say what waits. */
    private volatile String pendingSettingsField;
    /** Every field the pending change asked for, with its expected value, for the one answer. */
    private volatile java.util.List<AutoQuality.Change> pendingSettingsChanges;
    private volatile MacStore.Stream streamOptions;
    private volatile MacAudio audio;
    /** This device's mute (Sall's LocalAudioControl), from the first session with sound to shutdown. */
    private volatile AudioMute mute;
    /** Where XDesk's sound setting sends sound (AudioRoute, set by MacSource); null: the system's choice. */
    public static volatile java.util.function.Supplier<android.media.AudioDeviceInfo> audioDevice;

    /** The Mac's settings as this connection last heard them; null while not connected or before the Mac said. */
    public HostSettings settings() { return hostState; }
    /** The field of the change that waits for the Mac's answer, or null. */
    public String pendingSetting() { return pendingSettingsToken != 0 ? pendingSettingsField : null; }
    public void addSettingsListener(SettingsListener l) { settingsListeners.add(l); }
    public void removeSettingsListener(SettingsListener l) { settingsListeners.remove(l); }

    private java.util.List<SettingsListener> listeners() {
        synchronized (settingsListeners) {
            return new java.util.ArrayList<>(settingsListeners);
        }
    }

    private MacStore.Stream streamOptions() {
        MacStore.Stream o = streamOptions;
        if (o == null) streamOptions = o = MacStore.stream(context, mac.id);
        return o;
    }

    /** This device's own options changed (MacStore.setStreamOptions): the rate at once, the sound with a new session. */
    public void streamOptionsChanged() {
        streamOptions = MacStore.stream(context, mac.id);
        video.setSmooth(MacStore.smooth(context, mac.id));
        autoTickFollow();
        SillTls s = session;
        if (s == null || state != State.CONNECTED) return;
        if (wantsAudio() != (audio != null)) {
            // Whether this device plays sound is said in the hello: connect again (the runner does, in a second)
            s.close();
            return;
        }
        if (surface != null) sendViewport();
    }

    /**
     * The rate asked of the Mac: the screen's own, or this Mac's cap when it is lower. The Mac runs the stream at
     * the highest rate its connected devices ask for, under its own frame rate limit and never under 24
     * (StreamCoordinator.wantedFPS).
     */
    private int viewportFps() {
        int cap = streamOptions().fpsCap;
        int rate = cap > 0 ? Math.min(fps, cap) : fps;
        // Away from home the Mac caps its stream at 60 (iOS StreamClient+Viewport.wantedFPS, Sall's
        // ViewportFps): above that costs the shared stream more than the remote link can show
        HostSettings h = hostState;
        return h != null && h.thisConnectionAway ? Math.min(rate, 60) : rate;
    }

    /** Kind 16, after hostSettings(o) above took what the viewport needs. On the reader's thread. */
    private void settingsState(JSONObject o) {
        final HostSettings h = HostSettings.parse(o);
        HostSettings before = hostState;
        hostState = h;
        MacStore.setHostSettings(context, mac.id, h.settingsJson);
        // Away or home now: the rate asked for follows it, also when it changed mid-stream
        if (state == State.CONNECTED && surface != null && (before == null || before.thisConnectionAway != h.thisConnectionAway))
            sendViewport();
        if (!h.linkState.isEmpty() && (before == null || !h.linkState.equals(before.linkState)))
            Log.i(TAG, mac.name + ": link " + h.linkState + ", the Mac suggests " + h.suggestedBitrate + " bps"
                    + (h.suggestedCaptureScale != null ? " at scale " + h.suggestedCaptureScale : ""));
        ui.post(() -> {
            if (hostState != h) return;
            java.util.List<SettingsListener> ls = listeners();
            if (pendingSettingsToken != 0 && h.answering == pendingSettingsToken) {
                java.util.List<AutoQuality.Change> sent = pendingSettingsChanges;
                boolean accepted = sent != null;
                if (sent != null) for (AutoQuality.Change c : sent)
                    if (!HostSettings.accepted(h.value(c.field), c.value, h.captureScale, c.captureScale)) accepted = false;
                pendingSettingsToken = 0;
                pendingSettingsChanges = null;
                Log.i(TAG, mac.name + ": " + pendingSettingsField + (accepted ? " set" : " not taken by the Mac"));
                for (SettingsListener l : ls) l.settingsAnswer(accepted ? SettingsListener.ACCEPTED : SettingsListener.REFUSED);
                pumpChanges();  // the queue of several changes (a mode, auto quality) sends its next one
            } else if (pendingSettingsToken == 0) {
                // Not an answer to a change sent from here: the Mac's own menu or another device changed them
                quality.external(AutoQuality.modeOf(h.bitrate, h.captureScale, h.prioritizeSpeed),
                        h.bitrate != null ? HostSettings.preset(h.bitrate) : -1, SystemClock.uptimeMillis());
            }
            for (SettingsListener l : ls) l.settingsChanged();
            autoTickFollow();
        });
    }

    /**
     * One control's change (Sall's changeHostSetting): the field as an absolute value, and with a quality the
     * resolution the Mac suggested with it. Only a field the Mac reported, and one change at a time: the Mac answers
     * each with its state (`answering`), which is what the page then shows, taken or not. UI thread.
     */
    public boolean changeSetting(String field, Object value, Double captureScale) {
        HostSettings h = hostState;
        if (h == null || state != State.CONNECTED || h.value(field) == null || captureScale != null && h.captureScale == null) return false;
        if (pendingSettingsToken != 0) return false;
        final int token = settingsToken.incrementAndGet();
        try {
            JSONObject request = new JSONObject().put("token", token).put(field, value);
            if (captureScale != null) request.put(HostSettings.CAPTURE_SCALE, captureScale);
            pendingSettingsField = field;
            pendingSettingsChanges = java.util.Collections.singletonList(
                    new AutoQuality.Change(field, value, captureScale));
            pendingSettingsToken = token;
            enqueue(SillWire.CHANGE_SETTINGS, request);
        } catch (JSONException e) {
            return false;
        }
        // A manual change of the picture is what the user wants now: the auto machine follows it (a refusal
        // is settled by the real state that comes next)
        if (HostSettings.BITRATE.equals(field) || HostSettings.CAPTURE_SCALE.equals(field) || HostSettings.PRIORITIZE_SPEED.equals(field)) {
            Integer b = HostSettings.BITRATE.equals(field) ? ((Number) value).intValue() : h.bitrate;
            Double scale = HostSettings.CAPTURE_SCALE.equals(field) ? (Double) value
                    : captureScale != null ? captureScale : h.captureScale;
            Boolean speed = HostSettings.PRIORITIZE_SPEED.equals(field) ? (Boolean) value : h.prioritizeSpeed;
            quality.userChose(AutoQuality.modeOf(b, scale, speed), b != null ? HostSettings.preset(b) : -1, SystemClock.uptimeMillis());
        }
        ui.postDelayed(() -> {
            if (pendingSettingsToken != token) return;
            pendingSettingsToken = 0;
            pendingSettingsChanges = null;
            java.util.List<SettingsListener> ls = listeners();
            for (SettingsListener l : ls) {
                l.settingsAnswer(SettingsListener.TIMEOUT);
                l.settingsChanged();
            }
            pumpChanges();  // a mode queued behind this one may go now
        }, SETTINGS_ANSWER_MS);
        java.util.List<SettingsListener> ls = listeners();
        for (SettingsListener l : ls) l.settingsChanged();
        return true;
    }

    // --- a mode's changes (AutoQuality: a quick mode, the machine's steps) ---

    /** The fields that set a mode: only the ones that differ, bitrate, resolution, speed, in that order. */
    public static java.util.List<AutoQuality.Change> modeChanges(HostSettings h, int mode) {
        java.util.ArrayList<AutoQuality.Change> changes = new java.util.ArrayList<>();
        if (h.bitrate != null && h.bitrate != AutoQuality.preset(mode))
            changes.add(new AutoQuality.Change(HostSettings.BITRATE, AutoQuality.preset(mode), null));
        if (h.captureScale != null && h.captureScale != AutoQuality.scale(mode))
            changes.add(new AutoQuality.Change(HostSettings.CAPTURE_SCALE, AutoQuality.scale(mode), null));
        if (h.prioritizeSpeed != null && h.prioritizeSpeed != AutoQuality.speed(mode))
            changes.add(new AutoQuality.Change(HostSettings.PRIORITIZE_SPEED, AutoQuality.speed(mode), null));
        return changes;
    }

    /** The quick modes' machine, one for this Mac for the process. */
    private final AutoQuality quality = new AutoQuality();
    public AutoQuality quality() { return quality; }

    /** The fields of a change waiting for the pipe; sent together as one kind 17. UI thread. */
    private final java.util.ArrayDeque<AutoQuality.Change> pendingChanges = new java.util.ArrayDeque<>();
    /** The queue holds a manual pick (its result becomes the user's mode); false: the machine's own steps. */
    private boolean queueManual;
    /** A batch of changes is in the pipe: settle the machine when it drains. */
    private boolean queueActive;

    /**
     * A mode's fields, out together as one HostSettingsChange with one token (Sill's HostSettingsChange takes
     * several fields), the next batch only after the Mac's answer — the quick mode rows and the auto machine send
     * this way. A manual pick clears the machine's unsent steps. UI thread.
     * @return false when nothing went out: not connected, or a manual pick already holds the pipe
     */
    public boolean changeAll(java.util.List<AutoQuality.Change> changes, boolean manual) {
        if (manual) {
            pendingChanges.clear();
            queueManual = true;
        } else if (queueManual) {
            return false;  // the user's pick goes first; this auto step is superseded
        }
        if (changes.isEmpty()) return false;
        pendingChanges.addAll(changes);
        queueActive = true;
        pumpChanges();
        return pendingSettingsToken != 0 || !pendingChanges.isEmpty();
    }

    /**
     * Sends every field waiting as one kind 17 when the Mac has answered the last batch; settle the machine when
     * the queue drains. A field the Mac stopped reporting is left out (or the whole batch, when none is left).
     */
    private void pumpChanges() {
        if (pendingSettingsToken != 0) return;
        if (pendingChanges.isEmpty()) {
            settleChanges();
            return;
        }
        HostSettings h = hostState;
        if (h == null || state != State.CONNECTED) {
            // Gone (disconnected): drop the rest, the page's toast says why
            pendingChanges.clear();
            settleChanges();
            return;
        }
        final int token = settingsToken.incrementAndGet();
        final java.util.ArrayList<AutoQuality.Change> sent = new java.util.ArrayList<>();
        JSONObject request = new JSONObject();
        try {
            request.put("token", token);
            while (!pendingChanges.isEmpty()) {
                AutoQuality.Change c = pendingChanges.poll();
                if (h.value(c.field) == null || c.captureScale != null && h.captureScale == null) continue;
                request.put(c.field, c.value);
                if (c.captureScale != null) request.put(HostSettings.CAPTURE_SCALE, c.captureScale);
                sent.add(c);
            }
        } catch (JSONException e) {
            pendingChanges.clear();
            settleChanges();
            return;
        }
        if (sent.isEmpty()) {
            // The host lost every field the batch wanted: nothing can be asked
            settleChanges();
            return;
        }
        pendingSettingsField = sent.get(0).field;
        pendingSettingsChanges = sent;
        pendingSettingsToken = token;
        enqueue(SillWire.CHANGE_SETTINGS, request);
        ui.postDelayed(() -> {
            if (pendingSettingsToken != token) return;
            pendingSettingsToken = 0;
            pendingSettingsChanges = null;
            java.util.List<SettingsListener> ls = listeners();
            for (SettingsListener l : ls) {
                l.settingsAnswer(SettingsListener.TIMEOUT);
                l.settingsChanged();
            }
            pumpChanges();
        }, SETTINGS_ANSWER_MS);
        java.util.List<SettingsListener> ls = listeners();
        for (SettingsListener l : ls) l.settingsChanged();
    }

    /** The batch of several changes is done: the machine hears what the Mac's settings are now. */
    private void settleChanges() {
        if (!queueActive) return;
        queueActive = false;
        boolean manual = queueManual;
        queueManual = false;
        HostSettings h = hostState;
        if (h == null) return;
        int mode = AutoQuality.modeOf(h.bitrate, h.captureScale, h.prioritizeSpeed);
        int tier = h.bitrate != null ? HostSettings.preset(h.bitrate) : -1;
        if (manual) quality.userChose(mode, tier, SystemClock.uptimeMillis());
        else quality.applied(mode, tier, SystemClock.uptimeMillis());
    }

    /** The session ended: its state and whatever waited for an answer are gone with it. */
    private void settingsCleared() {
        hostState = null;
        ui.post(() -> {
            pendingSettingsToken = 0;
            pendingSettingsChanges = null;
            pendingChanges.clear();
            queueManual = false;
            queueActive = false;
            autoTickFollow();
            java.util.List<SettingsListener> ls = listeners();
            for (SettingsListener l : ls) l.settingsChanged();
        });
    }

    private boolean wantsAudio() {
        return streamOptions().sound && MacAudio.supported();
    }

    /** Whether this phone can play the Mac's sound at all (an AAC-ELD decoder). */
    public static boolean soundSupported() { return MacAudio.supported(); }

    /** The hello's `audio`: the codecs this device plays. The Mac sends kind 43 only to a device that lists one. */
    private void helloAudio(JSONObject hello) throws JSONException {
        closeAudio();
        // Whether the Mac will send sound is decided by this one hello: say which, and why not
        if (!wantsAudio()) {
            Log.i(TAG, mac.name + ": hello without sound (" + (!streamOptions().sound ? "\"Play the Mac's sound here\" is off"
                    : "no AAC-ELD decoder on this phone") + ")");
            return;
        }
        Log.i(TAG, mac.name + ": hello asks for sound (aac-eld, decoder " + MacAudio.decoderName() + ")");
        hello.put("audio", new JSONArray().put("aac-eld"));
        audio = new MacAudio(() -> {
            java.util.function.Supplier<android.media.AudioDeviceInfo> d = audioDevice;
            return d != null ? d.get() : null;
        });
        // The mute holds across sessions (MacStore.Stream.muted); the receiver is one for this Mac's lifetime.
        // Made under the lock shutdown() takes, and never after it: the abandoned audio above is closed with the
        // session (clearSession), and a mute made past a shutdown would register a receiver nobody unregisters
        synchronized (lock) {
            if (!wanted) return;
            AudioMute m = mute;
            if (m == null) {
                m = mute = new AudioMute(context, MacStore.stream(context, mac.id).muted, this::audioMuteChanged, this::audioMuteToast);
            }
            m.setPlayer(audio);
            m.setWatched(surface != null);
        }
    }

    /** Kind 43. Formats always; packets only while a screen shows this Mac (the idle connection stays silent). */
    private void audioMessage(SillWire.Message m) {
        MacAudio a = audio;
        if (a == null || m.length < 1) return;
        AudioMute mu = mute;
        if (mu != null) mu.received(m.payload);
        if (m.payload[0] == 2 && surface == null) return;
        a.receive(m.payload, m.length);
    }

    private void closeAudio() {
        MacAudio a = audio;
        audio = null;
        if (a != null) a.close();
        AudioMute m = mute;
        if (m != null) m.setPlayer(null);
    }

    // --- auto quality (AutoQuality, SALL-SYNC.md "XDesk's own additions"): the machine's tick and its notices ---

    /** Every second while connected and the switch is on: does the machine want to move? UI thread. */
    private final Runnable autoTick = new Runnable() {
        @Override public void run() {
            if (streamOptions().autoQuality && state == State.CONNECTED) autoWatch();
            ui.postDelayed(this, 1000);
        }
    };

    /** The tick follows the connection and the switch; re-armed so it runs a second after the last state. */
    private void autoTickFollow() {
        boolean on = streamOptions().autoQuality && state == State.CONNECTED;
        if (on) {
            ui.removeCallbacks(autoTick);
            ui.postDelayed(autoTick, 1000);
        } else {
            ui.removeCallbacks(autoTick);
        }
    }

    /**
     * The Mac's link report against the machine: 5 s of continuous behind lowers (the Mac's suggestion, or one
     * mode down), a long keep-up raises one step back. Nothing while a change is in the pipe: the machine moves
     * only on what the Mac really has. UI thread.
     */
    private void autoWatch() {
        HostSettings h = hostState;
        if (h == null || pendingSettingsToken != 0 || !pendingChanges.isEmpty()) return;
        HostSettings.Advice advice = h.advice(rttSlow());
        boolean behind = advice.kind == HostSettings.Advice.BEHIND || advice.kind == HostSettings.Advice.BEHIND_SUGGESTION;
        // A suggestion that is already what the Mac has is not one: the step down takes its place
        boolean suggest = advice.kind == HostSettings.Advice.BEHIND_SUGGESTION
                && (h.bitrate == null || advice.bitrate != h.bitrate
                    || advice.captureScale != null && (h.captureScale == null
                        || advice.captureScale.doubleValue() != h.captureScale.doubleValue()));
        int action = behind ? quality.lower(SystemClock.uptimeMillis(), suggest) : quality.keep(SystemClock.uptimeMillis());
        String[] names = context.getResources().getStringArray(com.termux.x11.app.R.array.xd_mac_mode_names);
        if (action == AutoQuality.SUGGEST) {
            java.util.ArrayList<AutoQuality.Change> changes = new java.util.ArrayList<>();
            changes.add(new AutoQuality.Change(HostSettings.BITRATE, advice.bitrate, advice.captureScale));
            if (changeAll(changes, false)) {
                int m = AutoQuality.modeOf(advice.bitrate,
                        advice.captureScale != null ? advice.captureScale : h.captureScale, h.prioritizeSpeed);
                flash(m >= 0 ? context.getString(com.termux.x11.app.R.string.xd_mac_auto_lowered, names[m])
                        : context.getString(com.termux.x11.app.R.string.xd_mac_auto_lowered_generic));
            }
        } else if (action >= 0) {
            if (changeAll(modeChanges(h, action), false))
                flash(context.getString(behind ? com.termux.x11.app.R.string.xd_mac_auto_lowered
                        : com.termux.x11.app.R.string.xd_mac_auto_raised, names[action]));
        }
    }

    private static final long FLASH_MS = 3_000;
    /** Put the real status line back after a flash. */
    private final Runnable hideFlash = () -> statusToView();

    /** A short line over this Mac's picture (the hotkey's answer, auto quality's notice); a toast without one. */
    public void flash(String text) {
        View v = view;
        if (v == null) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
            return;
        }
        ui.removeCallbacks(hideFlash);
        v.status(text, false);
        ui.postDelayed(hideFlash, FLASH_MS);
    }

    // --- round trip and this device's mute (Sall's ConnectionDiagnostics.Rtt, LocalAudioControl) ---

    /** The round trip of the latest pongs, in milliseconds; null before a connection or a pong in the last 1.5 s. */
    public Long rttMedian() {
        Rtt r = rtt;
        return r == null ? null : r.median(SystemClock.uptimeMillis());
    }

    /** Whether the round trip has stayed high for a while (Rtt.slow): the settings page's slow-link advice. */
    public boolean rttSlow() {
        Rtt r = rtt;
        return r != null && r.slow(SystemClock.uptimeMillis());
    }

    /** The "mute this device" switch: at once on the live sound, remembered for the next session either way. UI thread. */
    public void setSoundMuted(boolean muted) {
        // Persist even when the live policy already holds this value: the page's switch follows the store, and a
        // policy created from a stale store stays in step with what the user just picked
        MacStore.Stream own = streamOptions();
        MacStore.setStreamOptions(context, mac.id, own.fpsCap, own.sound, muted);
        AudioMute m = mute;
        if (m != null) m.setMuted(muted); // the listener fires only when the policy changed; the store is right either way
    }

    /** The mute changed (the switch, or the headphones went away): remember it and redraw the settings page. UI thread. */
    private void audioMuteChanged(boolean muted) {
        MacStore.Stream own = streamOptions();
        MacStore.setStreamOptions(context, mac.id, own.fpsCap, own.sound, muted);
        java.util.List<SettingsListener> ls = listeners();
        for (SettingsListener l : ls) l.settingsChanged();
    }

    /** The headphones went away while the Mac's sound played to them: it is muted now (Sall's headphonesOut). UI thread. */
    private void audioMuteToast() {
        Toast.makeText(context, context.getString(com.termux.x11.app.R.string.xd_mac_sound_muted_headphones), Toast.LENGTH_LONG).show();
    }

    public void windowCommand(long id, String action) {
        try {
            enqueue(SillWire.WINDOW_COMMAND, new JSONObject().put("id", id).put("action", action));
        } catch (JSONException ignored) { }
    }

    /** What the screen's line says now. */
    private void statusToView() {
        final String line;
        boolean taken = false, settling = false;
        if (state == State.FAILED) {
            line = failure != null ? failure : "";
        } else if (state != State.CONNECTED) {
            line = context.getString(com.termux.x11.app.R.string.xd_mac_connecting, name());
        } else if (locked && offConsole && !offConsoleView) {
            line = context.getString(com.termux.x11.app.R.string.xd_mac_other_user);
        } else if (locked && !offConsoleView && want != DESKTOP) {
            line = context.getString(com.termux.x11.app.R.string.xd_mac_locked, name());
        } else if (pendingLaunch != null) {
            line = context.getString(com.termux.x11.app.R.string.xd_mac_opening);
        } else if (launchFailed) {
            line = context.getString(com.termux.x11.app.R.string.xd_mac_launch_failed);
        } else if (quittingApp != null) {
            // quitApp switched the stream to the app it quits: the picture changes though this side's own
            // content is left alone, so the line says it is quitting, not that someone took the stream
            line = context.getString(com.termux.x11.app.R.string.xd_mac_quitting, quittingApp);
        } else if (want >= 0 && activeWindow != want || want == DESKTOP && !activeDesktop) {
            boolean gone = want >= 0 && !hasWindow(want);
            // The Mac streams one source, to every device, and its list does not say who picked it. So "another
            // device" only when the Mac says something else streams and that is not this side's own doing: while
            // a pick sent from here is on its way the list still names what streamed before (this screen's last
            // source, or nothing), and that is the Mac switching, not somebody taking it (settling). Nothing
            // streaming past that is the Mac not having started it: its display asleep, the capture stopped.
            // Tapping asks again either way
            boolean other = activeSource() != NONE;
            settling = !gone && settling();
            line = gone ? context.getString(com.termux.x11.app.R.string.xd_mac_window_closed)
                    : settling ? context.getString(com.termux.x11.app.R.string.xd_mac_opening)
                    : other ? context.getString(com.termux.x11.app.R.string.xd_mac_taken)
                    : context.getString(com.termux.x11.app.R.string.xd_mac_waiting_picture, name());
            taken = !gone && !settling;
        } else {
            line = null;
        }
        statusSettling = settling;
        final boolean t = taken;
        // The status line covers the screen: through the passthrough the receiver would sit on a frozen picture
        // (DISPLAY.md §3.4). Not while the Mac still streams during a switch (settling) or a launch waits for its
        // window: the picture flows on then.
        if (line != null && !settling && pendingLaunch == null) suspendPass();
        ui.post(() -> {
            View v = view;
            if (v != null) v.status(line, t);
        });
    }

    /** What the Mac's list says streams: a window's id, DESKTOP or NONE. */
    private long activeSource() {
        return activeDesktop ? DESKTOP : activeWindow >= 0 ? activeWindow : NONE;
    }

    /**
     * A pick sent from here (show, a new screen, a tap on the line) may still be on its way: for SWITCH_MS whatever
     * the list names, and for SWITCH_OWN_MS while it names what this screen itself streamed last (the Mac moves the
     * window onto its virtual display and starts the capture in a second or two, Sill.log 2026-11-14). The desktop
     * asked for again every ten seconds (askAgain) is not such a pick: nothing streams then, and the line says so.
     */
    private boolean settling() {
        long since = SystemClock.uptimeMillis() - switchAt;
        long streams = activeSource();
        return since < SWITCH_MS || streams != NONE && streams == had && streams != want && since < SWITCH_OWN_MS;
    }

    private static final long SWITCH_MS = 4_000, SWITCH_OWN_MS = 12_000;
    /** When this side last picked a source by itself (not askAgain's repeat). */
    private volatile long switchAt = -SWITCH_OWN_MS;
    /** The source the list last named while it was the one wanted here: what this screen streamed last. */
    private volatile long had = NONE;
    /** The line shown counts on settling(): the pinger looks again when that is over. */
    private volatile boolean statusSettling;

    private boolean hasWindow(long id) {
        for (Window w : windows) if (w.id == id) return true;
        return false;
    }

    /** "Taken over, tap to get it back" (DISPLAY.md §1.3). */
    public void takeBack() {
        if (want == NONE) return;
        switchAt = SystemClock.uptimeMillis();
        selectSource(want);
        sendViewport();
        statusToView();
    }

    // --- quitting an app (Sill's menu protocol, kinds 24, 25 and 27; SALL-SYNC.md "The Mac's menus") ---

    /** quitApp's answers, on the UI thread. */
    public interface QuitListener {
        /** The app's windows left the window list: it quit. */
        int OK = 0;
        /** The app did not quit (a save dialog, most likely): winId is the window to put on the screen, -1 none. */
        int SAVE = 1;
        /** It could not be quit; note says why, for the user. */
        int FAILED = 2;

        /** @param winId SAVE's window (>= 0), else -1; note: what to tell the user when the result is not OK. */
        void quitDone(int result, long winId, String note);
    }

    private static final long QUIT_READY_MS = 15_000, QUIT_GONE_MS = 5_000, QUIT_MENU_MS = 4_000;
    /** quitApp's runs, one at a time: the menu state and the answer slot are for one request in flight. */
    private final Object quitGate = new Object();
    /** The answer slot the reader thread hands the quit machine (quitAnswer, waitQuitAnswer). */
    private final Object quitLock = new Object();
    private MacMenuState.Done quitDone;

    /**
     * Quit an installed app for real ("Sill can't quit an app" — it can, through its menu protocol): make it
     * the streamed app if it is not, then press its menu's Quit item (the item with the key "⌘Q", so it is
     * found whatever the menu's language; without a ⌘Q item, a title that starts with "Quit"/"退出"/"Beenden"…),
     * and wait for the app's windows to leave the window list. Sill's AppInfo has no running flag, so an app
     * with no windows would otherwise stay on the Mac unseen and unquittable; quitting one needs no window.
     *
     * The stream switch does not take over any screen: XDesk's own content (what it shows) is left alone, and
     * the Mac's stream goes back to what streamed before afterwards. A screen showing this Mac sees the switch
     * (the picture is the Mac's one stream), with a "Quitting…" line, and then what it showed before. An app
     * that keeps windows after the press (a save dialog, most likely) is reported as SAVE with its newest
     * window, and the stream left on it. Sill without Accessibility on the Mac refuses the menus: ⌘Q then goes
     * as key input to the streamed app (the raw key path MacInput uses), and the user is told once that
     * allowing Accessibility makes quitting reliable. Off the UI thread; the listener runs on the UI thread.
     */
    public void quitApp(String bundleId, QuitListener listener) {
        new Thread(() -> {
            synchronized (quitGate) {
                quitRun(bundleId, listener);
            }
        }, "mac-quit").start();
    }

    private void quitRun(String bundleId, QuitListener listener) {
        try {
            quitBody(bundleId, listener);
        } finally {
            // Whatever ended it (an unexpected error included): the screen must not stay on "Quitting…"
            if (quittingApp != null) {
                quittingApp = null;
                statusToView();
            }
        }
    }

    private void quitBody(String bundleId, QuitListener listener) {
        final String name = appName(bundleId);
        if (!awaitConnected(10_000)) {
            done(listener, QuitListener.FAILED, -1, context.getString(com.termux.x11.app.R.string.xd_mac_cannot_connect, name));
            return;
        }
        long before = activeSource();
        long wantAt = want;
        boolean switched = false;
        if (!streamsApp(bundleId)) {
            // Not the streamed app: pick one of its windows, or launch it (the host picks its first window).
            // The Mac's stream only, never XDesk's own content: `want` and what a screen shows stay
            switched = true;
            quittingApp = name;
            statusToView();
            MacClient.Window win = newestWindow(bundleId);
            if (win != null) switchStreamTo(win.id);
            else try {
                enqueue(SillWire.LAUNCH_APP, new JSONObject().put("bundleID", bundleId));
            } catch (JSONException ignored) { }
            long until = SystemClock.uptimeMillis() + QUIT_READY_MS;
            while (!streamsApp(bundleId)) {
                if (state != State.CONNECTED || SystemClock.uptimeMillis() >= until) {
                    finishQuit(listener, QuitListener.FAILED, -1,
                            context.getString(com.termux.x11.app.R.string.xd_mac_launch_failed), before, wantAt, switched);
                    return;
                }
                SystemClock.sleep(50);
            }
        }
        menuSubscribe();
        MacMenuState.Top top = waitTop(bundleId, QUIT_READY_MS);
        if (top == null) {
            finishQuit(listener, QuitListener.FAILED, -1, quitFailed(name,
                    context.getString(com.termux.x11.app.R.string.xd_mac_quit_no_answer)), before, wantAt, switched);
            return;
        }
        // The app menu is the first top-level entry (the Apple menu is left out); press its Quit item. The menus
        // can move between a fetch and the press (an app changes them in place): the version moves, the host
        // sends the new top level by itself (we are a subscriber), and one more fetch and press follows
        MacMenuState.Refusal refusal = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            top = mirroredMenus.top();
            boolean fromTop = false;  // a refusal read off the top level itself: nothing to retry a fetch for
            if (top.version == null || !matches(top, bundleId)) {
                // The stream moved off the app while quitting (someone took it back): never press another app's Quit
                refusal = new MacMenuState.Refusal(MacMenuState.RefusalKind.CHANGED, MacMenuState.CHANGED_NOTE);
                fromTop = true;
            } else if (top.stale) {
                refusal = new MacMenuState.Refusal(MacMenuState.RefusalKind.NOT_ANSWERING,
                        top.note == null ? MacMenuState.DEFAULT_NOT_RESPONDING : top.note);
            } else {
                MacMenuState.Item menu = MacMenuState.firstMenu(top.menus);
                if (menu == null) {
                    // No menu bar at all: the top's note says why (no Accessibility: NOT_TRUSTED)
                    refusal = MacMenuState.Refusal.of(top.note);
                    if (refusal == null) refusal = new MacMenuState.Refusal(MacMenuState.RefusalKind.OTHER,
                            context.getString(com.termux.x11.app.R.string.xd_mac_quit_no_menus));
                } else {
                    long token = menuToken.incrementAndGet();
                    MacMenuState.Sent sent = mirroredMenus.fetch(menu.id, menu.title, top.version, token,
                            SystemClock.uptimeMillis());
                    if (sent.refusal != null) {
                        refusal = sent.refusal;
                    } else {
                        sendMenu(SillWire.FETCH_MENU, sent.command);
                        MacMenuState.Done d = waitQuitAnswer(token, menuWaitMs());
                        if (d == null) {
                            mirroredMenus.drop(token);
                            refusal = new MacMenuState.Refusal(MacMenuState.RefusalKind.NOT_ANSWERING,
                                    context.getString(com.termux.x11.app.R.string.xd_mac_quit_no_answer));
                        } else if (d.refusal != null) {
                            refusal = d.refusal;
                        } else {
                            MacMenuState.Item quit = MacMenuState.quitItem(d.items);
                            if (quit == null) {
                                refusal = new MacMenuState.Refusal(MacMenuState.RefusalKind.OTHER,
                                        context.getString(com.termux.x11.app.R.string.xd_mac_quit_no_item));
                            } else {
                                token = menuToken.incrementAndGet();
                                sent = mirroredMenus.press(quit, token);
                                if (sent.refusal != null) {
                                    refusal = sent.refusal;
                                } else {
                                    sendMenu(SillWire.PRESS_MENU_ITEM, sent.command);
                                    d = waitQuitAnswer(token, menuWaitMs());
                                    if (d == null) {
                                        mirroredMenus.drop(token);
                                        refusal = new MacMenuState.Refusal(MacMenuState.RefusalKind.NOT_ANSWERING,
                                                context.getString(com.termux.x11.app.R.string.xd_mac_quit_no_answer));
                                    } else {
                                        refusal = d.refusal;
                                        if (refusal == null && Boolean.FALSE.equals(d.pressed)) {
                                            // pressed false without a note: the menus changed, as in Sall
                                            refusal = new MacMenuState.Refusal(MacMenuState.RefusalKind.CHANGED,
                                                    MacMenuState.CHANGED_NOTE);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (refusal == null) break;  // pressed: wait for its windows to leave
            if (refusal.kind == MacMenuState.RefusalKind.CHANGED && attempt == 0 && !fromTop) {
                waitTopChanged(bundleId, top.version, QUIT_MENU_MS);  // the new top level comes by itself
                continue;
            }
            if (refusal.kind == MacMenuState.RefusalKind.GONE) refusal = null;  // the app is already closed: as good as quit
            break;
        }
        if (refusal != null && refusal.kind == MacMenuState.RefusalKind.NOT_TRUSTED) {
            // Sill has no Accessibility on the Mac: no menu is ever readable. ⌘Q as key input to the streamed
            // app works without it, as MacInput's shortcut() sends it; say once that allowing Accessibility
            // makes quitting reliable
            if (!quitAccessibilitySaid) {
                quitAccessibilitySaid = true;
                String access = context.getString(com.termux.x11.app.R.string.xd_mac_quit_no_access);
                ui.post(() -> Toast.makeText(context, access, Toast.LENGTH_LONG).show());
            }
            sendQuitKeys();
            refusal = null;
        }
        if (refusal != null) {
            finishQuit(listener, QuitListener.FAILED, -1, quitFailed(name, refusal.note), before, wantAt, switched);
            return;
        }
        // The Quit item was pressed (or ⌘Q sent): wait for the app's windows to leave the list
        if (waitGone(bundleId, QUIT_GONE_MS)) {
            finishQuit(listener, QuitListener.OK, -1, null, before, wantAt, switched);
            return;
        }
        // The app stays (a save dialog, most likely): its newest window is the one the user must answer on the Mac
        long win = -1;
        for (MacClient.Window w : windows) if (bundleId.equals(w.bundleId)) win = w.id;
        if (win >= 0) switchStreamTo(win);  // the dialog is what streams; the caller puts it on the screen
        finishQuit(listener, QuitListener.SAVE, win,
                context.getString(com.termux.x11.app.R.string.xd_mac_quit_save, name), before, wantAt, switched);
    }

    /** The app whose menus these are, by its bundle id (the name, when the host's bundleID field is absent). */
    private boolean matches(MacMenuState.Top top, String bundleId) {
        return bundleId.equals(top.bundleID) || top.bundleID == null && bundleId.equals(top.app);
    }

    /** A top level of this app, once it streams; null when the connection went or the time ran out. */
    private MacMenuState.Top waitTop(String bundleId, long ms) {
        long until = SystemClock.uptimeMillis() + ms;
        while (state == State.CONNECTED && SystemClock.uptimeMillis() < until) {
            MacMenuState.Top top = mirroredMenus.top();
            if (top.version != null && matches(top, bundleId)) return top;
            SystemClock.sleep(50);
        }
        return null;
    }

    /** Wait for the next top level of this app (its version moved), for the one retry after a CHANGED refusal. */
    private void waitTopChanged(String bundleId, int oldVersion, long ms) {
        long until = SystemClock.uptimeMillis() + ms;
        while (state == State.CONNECTED && SystemClock.uptimeMillis() < until) {
            MacMenuState.Top top = mirroredMenus.top();
            if (top.version != null && top.version != oldVersion && matches(top, bundleId)) return;
            SystemClock.sleep(50);
        }
    }

    private boolean waitGone(String bundleId, long ms) {
        long until = SystemClock.uptimeMillis() + ms;
        while (state == State.CONNECTED && SystemClock.uptimeMillis() < until) {
            if (newestWindow(bundleId) == null) return true;
            SystemClock.sleep(100);
        }
        return newestWindow(bundleId) == null;
    }

    /** Whether a window of this app is what streams now. */
    private boolean streamsApp(String bundleId) {
        long active = activeWindow;
        if (active < 0) return false;
        for (MacClient.Window w : windows) if (w.id == active && bundleId.equals(w.bundleId)) return true;
        return false;
    }

    /** The app's newest window (the Mac lists windows first seen first), or null when it has none. */
    private MacClient.Window newestWindow(String bundleId) {
        MacClient.Window newest = null;
        for (MacClient.Window w : windows) if (bundleId.equals(w.bundleId)) newest = w;
        return newest;
    }

    private String appName(String bundleId) {
        for (MacClient.App a : apps) if (bundleId.equals(a.bundleId)) return a.name;
        return bundleId;
    }

    /** Sall's sendMenuCommand shape: version, id, title, token — kinds 25 and 27 both. */
    private static JSONObject menuJson(MacMenuState.Command command) throws JSONException {
        return new JSONObject().put("version", command.version).put("id", command.id)
                .put("title", command.title).put("token", command.token);
    }

    /** One kind 25 or 27 of a quit step; JSONException cannot happen with these fields, and then the step times out. */
    private void sendMenu(int kind, MacMenuState.Command command) {
        try {
            enqueue(kind, menuJson(command));
        } catch (JSONException e) {
            Log.i(TAG, "cannot build the menu request", e);
        }
    }

    /** An answer wait: 4 s at least, four round trips when the link is slow (Sall's menuTimeoutMs). */
    private long menuWaitMs() {
        Long rtt = rttMedian();
        return Math.max(QUIT_MENU_MS, rtt != null ? 4 * Math.min(rtt, 4000) : 0);
    }

    /** The reader thread settled a request of the quit machine's (an answer, or a version that moved past it). */
    private void quitAnswer(MacMenuState.Done d) {
        synchronized (quitLock) {
            quitDone = d;
            quitLock.notifyAll();
        }
    }

    /** The answer to the request with this token, or null when the time ran out or the connection went. */
    private MacMenuState.Done waitQuitAnswer(long token, long ms) {
        long until = SystemClock.uptimeMillis() + ms;
        synchronized (quitLock) {
            while (state == State.CONNECTED && SystemClock.uptimeMillis() < until) {
                if (quitDone != null) {
                    MacMenuState.Done d = quitDone;
                    quitDone = null;
                    if (d.token == token) return d;
                    continue;  // an earlier request's late answer: dropped, the expected one may still come
                }
                try {
                    quitLock.wait(Math.max(1, until - SystemClock.uptimeMillis()));
                } catch (InterruptedException e) {
                    return null;
                }
            }
            return null;
        }
    }

    /** Pick the Mac's source without touching XDesk's own content: `want` and what a screen shows stay. */
    private void switchStreamTo(long source) {
        switchAt = SystemClock.uptimeMillis();
        selectSource(source);
    }

    /** The app was quit or the quit failed: put back what streamed before, when it can still stream. */
    private void restoreAfterQuit(long before, long wantAt) {
        // The user moved on while the quit ran (chose another content, took this Mac off the screen):
        // their pick stands, nothing is put back over it
        if (want != wantAt) return;
        if (before == NONE) return;
        if (before == DESKTOP || hasWindow(before)) switchStreamTo(before);
        // A window that closed meanwhile: nothing to put back; a screen of this Mac says so (statusToView)
    }

    private void finishQuit(QuitListener listener, int result, long winId, String note, long before, long wantAt,
                            boolean switched) {
        // SAVE leaves the stream on the app's dialog window: the caller puts it on the screen
        if (switched && result != QuitListener.SAVE) restoreAfterQuit(before, wantAt);
        quittingApp = null;
        statusToView();
        done(listener, result, winId, note);
    }

    private void done(QuitListener listener, int result, long winId, String note) {
        ui.post(() -> listener.quitDone(result, winId, note));
    }

    private String quitFailed(String name, String why) {
        return context.getString(com.termux.x11.app.R.string.xd_mac_quit_failed, name, why);
    }

    /** ⌘Q as MacInput's shortcut() sends it (its raw key path): GUI down, Q down, Q up, GUI up. */
    private void sendQuitKeys() {
        key(HidKeys.LEFT_GUI, true, COMMAND);
        key(HidKeys.forChar('q'), true, COMMAND);
        key(HidKeys.forChar('q'), false, COMMAND);
        key(HidKeys.LEFT_GUI, false, 0);
    }

    // --- the menu bar UI (MacMenuBar, the phone's dialog): the same state the quit machine drives ---

    public void addMenuListener(MenuListener l) {
        menuListeners.add(l);
    }

    public void removeMenuListener(MenuListener l) {
        menuListeners.remove(l);
    }

    /** The top level as last heard; version null before the first one. */
    public MacMenuState.Top menuTop() {
        return mirroredMenus.top();
    }

    /** Ask for the top level once per connection (the subscription): the host then serves fetches and presses,
     *  and pushes each new top level. Asked again when the answer never came: a second subscription is served
     *  the same, so no harm. */
    public void menuSubscribe() {
        if (!menuSubscribed || mirroredMenus.top().version == null) {
            menuSubscribed = true;
            enqueue(SillWire.FETCH_MENU, new JSONObject());
        }
    }

    /** Ask for one menu's items (kind 27), by the id and the title shown in that version; the answer comes
     *  through the menu listeners. The refusal when nothing was sent (stale, another version). */
    public MacMenuState.Sent menuFetch(String id, String title, int version) {
        long token = menuToken.incrementAndGet();
        MacMenuState.Sent sent = mirroredMenus.fetch(id, title, version, token, SystemClock.uptimeMillis());
        if (sent.refusal == null) sendMenu(SillWire.FETCH_MENU, sent.command);
        return sent;
    }

    /** Choose the item a menu UI shows as this row (kind 25); the answer comes through the menu listeners. */
    public MacMenuState.Sent menuPress(MacMenuState.Row row) {
        long token = menuToken.incrementAndGet();
        MacMenuState.Sent sent = mirroredMenus.press(row, token);
        if (sent.refusal == null) sendMenu(SillWire.PRESS_MENU_ITEM, sent.command);
        return sent;
    }

    // --- input (any thread) ---

    private static byte[] pointerJson(String action, float x, float y) {
        try {
            return new JSONObject().put("pointer", new JSONObject().put("_0", action).put("x", x).put("y", y))
                    .toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean pointerPosted = new java.util.concurrent.atomic.AtomicBoolean();
    private final Runnable showPointer = () -> {
        pointerPosted.set(false);
        View v = view;
        if (v != null) v.pointer(pointerX, pointerY);
    };

    /** Where the pointer is, in fractions of the frame; only the latest of a run of moves is sent. */
    public void pointerTo(float x, float y) {
        pointerX = Math.max(0, Math.min(1, x));
        pointerY = Math.max(0, Math.min(1, y));
        if (session == null) return;
        synchronized (out) {
            Object last = out.peekLast();
            if (last instanceof Move) {
                ((Move) last).x = pointerX;
                ((Move) last).y = pointerY;
            } else {
                inputsQueued.incrementAndGet();
                out.add(new Move(pointerX, pointerY));
                out.notifyAll();
            }
        }
        // One for a run of moves: the mouse reports faster than the screen draws
        if (pointerPosted.compareAndSet(false, true)) ui.post(showPointer);
    }

    /** leftDown, leftUp, rightDown, rightUp at the pointer. */
    public void pointerButton(String action) {
        enqueue(SillWire.INPUT, pointerJson(action, pointerX, pointerY));
    }

    /** dx, dy: fractions of the frame the content moves; positive dy moves it down (natural). */
    public void scroll(float dx, float dy) {
        try {
            enqueue(SillWire.INPUT, new JSONObject().put("scroll", new JSONObject().put("x", pointerX).put("y", pointerY)
                    .put("dx", dx).put("dy", dy)));
        } catch (JSONException ignored) { }
    }

    /** began, ended, momentumBegan, momentumEnded around scroll deltas: a trackpad-like gesture (Sill's scrollGesture). */
    public void scrollGesture(String phase) {
        try {
            enqueue(SillWire.INPUT, new JSONObject().put("scrollGesture", new JSONObject().put("_0", phase)
                    .put("x", pointerX).put("y", pointerY)));
        } catch (JSONException ignored) { }
    }

    public void key(int hidUsage, boolean down, long modifiers) {
        try {
            enqueue(SillWire.INPUT, new JSONObject().put("key", new JSONObject().put("hidUsage", hidUsage).put("down", down)
                    .put("modifiers", modifiers)));
        } catch (JSONException ignored) { }
    }

    /** Typed text; "\n", "\t" and "\b" arrive as their keys. */
    public void text(String s) {
        try {
            enqueue(SillWire.INPUT, new JSONObject().put("text", new JSONObject().put("_0", s)));
        } catch (JSONException ignored) { }
    }

    // --- clipboard (INPUT.md §8: one clipboard; XDesk's clipboard_sync switch) ---

    private boolean clipboardOn() {
        return context.getSharedPreferences("wireless", Context.MODE_PRIVATE).getBoolean("clipboard_sync", true);
    }

    /**
     * Sill's clipboard on: this Mac takes part in the shared clipboard (ShareHub), which tells it of every copy on the
     * phone, the Linux desktop or another device, and gives it what is there now.
     */
    private void startClipboard() {
        if (!clipboardOn()) return;
        ui.post(() -> {
            if (clipEndpoint != null || surface == null) return;
            enqueue(SillWire.CLIPBOARD, new byte[] {'{', '}'});
            // The hub never gives a device back what came from it; a text this Mac sent earlier and got again from
            // another device is a new copy there, and goes (audit: it was dropped, and the Mac kept an older one)
            clipEndpoint = text -> {
                if (session == null || state != State.CONNECTED) return false;
                try {
                    byte[] json = new JSONObject().put("text", text).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    // Escaped control characters can take a 256K text past what Sill's writer sends: not shared
                    if (json.length >= SillWire.MAX_OUTGOING) return false;
                    enqueue(SillWire.CLIPBOARD, json);
                    return true;
                } catch (JSONException e) {
                    return false;
                }
            };
            com.termux.x11.ShareHub.get(context).join(clipEndpoint, "mac:" + mac.id);
        });
    }

    private void stopClipboard() {
        ui.post(() -> {
            if (clipEndpoint == null) return;
            com.termux.x11.ShareHub.get(context).leave(clipEndpoint);
            clipEndpoint = null;
            try {
                enqueue(SillWire.CLIPBOARD, new JSONObject().put("stop", true));
            } catch (JSONException ignored) { }
        });
    }

    /** The Mac copied: to the phone and every other device. */
    private void clipboardFromMac(final String text) {
        if (text.isEmpty() || !clipboardOn()) return;
        ui.post(() -> {
            if (clipEndpoint == null) return;
            lastClipFromMac = text;
            com.termux.x11.ShareHub.get(context).put(text, clipEndpoint);
        });
    }
}
