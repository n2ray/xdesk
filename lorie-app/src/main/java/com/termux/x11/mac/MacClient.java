/*
 * The session flow (hello, window list, source pick, launch, viewport, cursor, pointer, clipboard, goodbye,
 * keyframe requests) follows Sall (https://github.com/n2ray/sall, MainActivity.java), the Android client of Sill
 * (https://github.com/Saffsanity/sill, Sources/StreamProtocol). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk.
 */
package com.termux.x11.mac;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

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
    private static final int CONNECT_MS = 8_000, HANDSHAKE_MS = 10_000;

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
        void cursor(Bitmap shape, float hotX, float hotY, float width, float height);
        void pointer(float x, float y, boolean inside);
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
    private volatile State state = State.OFFLINE;
    private volatile String failure;
    private volatile boolean needsPairing;

    // Connection
    private final Object lock = new Object();
    private Thread runner;
    private volatile SillTls session;
    private volatile boolean wanted;
    private long lastUse = SystemClock.uptimeMillis();
    private volatile long lastPong;

    // The screen
    private final Object surfaceLock = new Object();
    private volatile Surface surface;
    private volatile View view;
    private final MacVideo video;
    private volatile int surfaceW, surfaceH;
    private volatile float density = 1;
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
    private volatile int inputsSent;
    private volatile boolean launchFailed;

    // Output queue: byte[] messages, float[] pointer moves (the last one is updated in place)
    private final ArrayDeque<Object> out = new ArrayDeque<>();
    private ClipboardManager.OnPrimaryClipChangedListener clipWatch;
    private String lastClipFromMac;

    public MacClient(Context c, MacStore.Mac mac) {
        context = c.getApplicationContext();
        this.mac = mac;
        video = new MacVideo((w, h) -> ui.post(() -> {
            View v = view;
            if (v != null) v.videoSize(w, h);
        }));
    }

    public MacStore.Mac mac() { return mac; }
    public void updateMac(MacStore.Mac m) {
        boolean refit = m.fit != mac.fit;
        mac = m;
        if (refit && surface != null) sendViewport();
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
    public float pointerX() { return pointerX; }
    public float pointerY() { return pointerY; }
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
            if (needsPairing) return; // unpaired or another key: only pairing again helps
            runner = new Thread(this::run, "mac-" + mac.id);
            runner.start();
        }
    }

    /** Wait until connected, up to ms. Off the UI thread. */
    public boolean awaitConnected(long ms) {
        touch();
        long until = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < until) {
            if (state == State.CONNECTED && !windowsPending) return true;
            if (state == State.FAILED) return false;
            SystemClock.sleep(50);
        }
        return state == State.CONNECTED;
    }

    private volatile boolean windowsPending = true;

    /** Close the connection and stop reconnecting (the Mac was removed, or XDesk is closing). */
    public void shutdown() {
        wanted = false;
        SillTls s = session;
        if (s != null) s.close();
        synchronized (lock) {
            if (runner != null) runner.interrupt();
        }
        stopClipboard();
    }

    private void setState(State s, String why) {
        state = s;
        failure = why;
        changed();
        statusToView();
    }

    private void run() {
        int retries = 0;
        while (wanted) {
            long started = SystemClock.uptimeMillis();
            try {
                setState(State.CONNECTING, null);
                session(mac);
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
                setState(State.OFFLINE, e.getMessage());
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
            }
            long delay = Math.min(30_000, 1000L << Math.min(retries++, 5));
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                if (!wanted) break;
            }
        }
        synchronized (lock) {
            if (runner == Thread.currentThread()) runner = null;
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
        SillTls s = SillTls.connect(m.host, m.port, SillTls.SESSION, m.pin, CONNECT_MS, HANDSHAKE_MS);
        session = s;
        windowsPending = true;
        inputsSent = 0;
        JSONObject hello = new JSONObject().put("protocol", 1).put("appVersion", appVersion()).put("device", Build.MODEL)
                .put("build", "xdesk");
        s.send(SillWire.HELLO, hello.toString().getBytes(StandardCharsets.UTF_8));
        s.readTimeout(0);
        lastPong = SystemClock.uptimeMillis();
        Thread sender = new Thread(() -> sendLoop(s), "mac-out");
        sender.start();
        Thread pinger = new Thread(() -> pingLoop(s), "mac-ping");
        pinger.start();
        SillWire.Message msg = new SillWire.Message();
        try {
            while (wanted && session == s) {
                SillWire.read(s.in, msg, SillWire.MAX_FRAME);
                handle(s, msg);
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
        windowsPending = true;
        activeWindow = -1;
        activeDesktop = false;
        stopClipboard();
    }

    private void pingLoop(SillTls s) {
        try {
            while (session == s) {
                boolean streaming = surface != null;
                Thread.sleep(streaming ? 1000 : 4000);
                if (SystemClock.uptimeMillis() - lastPong > PONG_DEADLINE_MS) {
                    Log.i(TAG, "no pong: the connection is gone");
                    s.close();
                    return;
                }
                s.send(SillWire.PING, ByteBuffer.allocate(8).putDouble(System.nanoTime() / 1e9).array());
                checkLaunch();
                synchronized (lock) {
                    if (surface == null && SystemClock.uptimeMillis() - lastUse > IDLE_MS) {
                        wanted = false;
                        s.close();
                        return;
                    }
                }
            }
        } catch (InterruptedException | IOException e) {
            s.close();
        }
    }

    private void sendLoop(SillTls s) {
        try {
            while (session == s) {
                Object item;
                synchronized (out) {
                    while (out.isEmpty() && session == s) out.wait();
                    if (session != s) return;
                    item = out.poll();
                }
                if (item instanceof float[]) {
                    float[] p = (float[]) item;
                    s.send(SillWire.INPUT, pointerJson("move", p[0], p[1]));
                    inputsSent++;
                } else {
                    byte[] b = (byte[]) item;
                    int kind = b[0] & 255;
                    byte[] payload = new byte[b.length - 1];
                    System.arraycopy(b, 1, payload, 0, payload.length);
                    s.send(kind, payload);
                    if (kind == SillWire.INPUT) inputsSent++;
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
            out.add(b);
            out.notifyAll();
        }
    }

    private void enqueue(int kind, JSONObject o) {
        enqueue(kind, o.toString().getBytes(StandardCharsets.UTF_8));
    }

    // --- incoming ---

    private void handle(SillTls s, SillWire.Message m) throws Exception {
        switch (m.kind) {
        case SillWire.FRAME: {
            Surface target = surface;
            if (locked() || target == null) return;
            if (!video.frame(m.payload, m.length, m.keyframe, m.timestamp, target)) askKeyframe(s);
            return;
        }
        case SillWire.PARAMETER_SETS:
            video.parameters(m.payload, m.length, surface);
            return;
        case SillWire.TICK:
            video.drain();
            return;
        case SillWire.PONG:
            lastPong = SystemClock.uptimeMillis();
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
        case SillWire.MAC_POINTER: {
            JSONObject p = new JSONObject(m.text());
            // Built before the host read our latest input: ours is newer
            if (p.optInt("seen", 0) < inputsSent) return;
            final boolean inside = p.optBoolean("inside", false);
            final double x = p.optDouble("x", Double.NaN), y = p.optDouble("y", Double.NaN);
            if (inside && !Double.isNaN(x) && !Double.isNaN(y)) {
                pointerX = (float) x;
                pointerY = (float) y;
            }
            ui.post(() -> {
                View v = view;
                if (v != null) v.pointer(pointerX, pointerY, inside);
            });
            return;
        }
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
            // Thumbnails (3), host settings (16), Mac info (18), menus, sound: not used
        }
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
        JSONObject win = active != null ? active.optJSONObject("window") : null;
        activeWindow = win != null ? win.optLong("_0", -1) : -1;
        boolean first = windowsPending;
        windowsPending = false;
        // The app launched from here got its first window: the host picked it; it is what this screen shows now
        String launch = pendingLaunch;
        if (launch != null && activeWindow >= 0) {
            for (Window w : next) {
                if (w.id == activeWindow && launch.equals(w.bundleId)) {
                    pendingLaunch = null;
                    want = activeWindow;
                }
            }
        }
        if (first) {
            setState(State.CONNECTED, null);
            if (surface != null && want != NONE) {
                selectSource(want);
                sendViewport();
            }
        }
        if (clipNew && surface != null) startClipboard();
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
        ui.post(() -> {
            View v = view;
            if (v != null) v.cursor(bm, hx, hy, w, h);
        });
    }

    // --- the screen ---

    /** A screen shows this Mac (MacActivity). One at a time: a new one replaces the old. */
    public void attach(View v) {
        view = v;
        touch();
        statusToView();
    }

    public void detach(View v) {
        if (view == v) view = null;
    }

    /** The SurfaceView's surface and its size in pixels; null when it is gone. */
    public void setSurface(Surface s, int width, int height, float density, int fps) {
        boolean fresh, resized;
        synchronized (surfaceLock) {
            Surface old = surface;
            fresh = s != old;
            resized = width != surfaceW || height != surfaceH || fps != this.fps;
            surface = s;
            surfaceW = width;
            surfaceH = height;
            this.density = density;
            this.fps = fps;
            if (s == null || fresh) video.release();
        }
        synchronized (lock) {
            lastUse = SystemClock.uptimeMillis();
        }
        if (s != null) {
            touch();
            if (state == State.CONNECTED && fresh) {
                // While a launch waits for its window there is nothing to pick yet
                if (want != NONE) selectSource(want);
                sendViewport();
                askKeyframeSoon();
                if (clipboardGeneration >= 1) startClipboard();
            } else if (state == State.CONNECTED && resized) {
                sendViewport();
            }
        } else {
            stopClipboard();
        }
    }

    private void askKeyframeSoon() {
        SillTls s = session;
        if (s == null) return;
        lastKeyframeAsk = 0;
        enqueue(SillWire.KEYFRAME_REQUEST, new byte[0]);
    }

    /** Where the picture is on the screen (letterboxed), and the screen, in pixels: input maps through it. */
    public void setFrameRect(int x, int y, int w, int h, int sw, int sh) {
        frameX = x;
        frameY = y;
        frameW = w;
        frameH = h;
        screenW = sw;
        screenH = sh;
    }

    /** x, y, width, height of the picture, then the screen's width and height; zeros before it is laid out. */
    public int[] frameRect() {
        return new int[] {frameX, frameY, frameW, frameH, screenW, screenH};
    }

    /** Stream this window (id), DESKTOP, or nothing (NONE): the screen showing this Mac took something else. */
    public void show(long source) {
        want = source;
        pendingLaunch = null;
        launchFailed = false;
        if (state == State.CONNECTED && (surface != null || source == NONE)) selectSource(source);
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

    private void selectSource(long source) {
        try {
            JSONObject o = new JSONObject();
            if (source == DESKTOP) o.put("desktop", new JSONObject());
            else if (source >= 0) o.put("window", new JSONObject().put("_0", source));
            else o.put("none", new JSONObject());
            enqueue(SillWire.SELECT_SOURCE, o);
        } catch (JSONException ignored) { }
    }

    /** The screen's size in its own dp; a scale only with "fit to screen" (the host restores the window when it ends). */
    public void sendViewport() {
        if (surfaceW <= 0 || surfaceH <= 0) return;
        try {
            JSONObject v = new JSONObject().put("width", Math.max(1, Math.min(16384, surfaceW / density)))
                    .put("height", Math.max(1, Math.min(16384, surfaceH / density))).put("fps", fps);
            if (mac.fit) v.put("scale", 1.0);
            enqueue(SillWire.VIEWPORT, v);
        } catch (JSONException ignored) { }
    }

    public void windowCommand(long id, String action) {
        try {
            enqueue(SillWire.WINDOW_COMMAND, new JSONObject().put("id", id).put("action", action));
        } catch (JSONException ignored) { }
    }

    /** What the screen's line says now. */
    private void statusToView() {
        final String line;
        boolean taken = false;
        if (state == State.FAILED) {
            line = failure != null ? failure : "";
        } else if (state != State.CONNECTED) {
            line = context.getString(com.termux.x11.app.R.string.xd_mac_connecting, name());
        } else if (locked && offConsole && !offConsoleView) {
            line = context.getString(com.termux.x11.app.R.string.xd_mac_other_user);
        } else if (locked && !offConsoleView) {
            line = context.getString(com.termux.x11.app.R.string.xd_mac_locked, name());
        } else if (pendingLaunch != null) {
            line = context.getString(com.termux.x11.app.R.string.xd_mac_opening);
        } else if (launchFailed) {
            line = context.getString(com.termux.x11.app.R.string.xd_mac_launch_failed);
        } else if (want >= 0 && activeWindow != want || want == DESKTOP && !activeDesktop) {
            boolean gone = want >= 0 && !hasWindow(want);
            line = context.getString(gone ? com.termux.x11.app.R.string.xd_mac_window_closed : com.termux.x11.app.R.string.xd_mac_taken);
            taken = !gone;
        } else {
            line = null;
        }
        final boolean t = taken;
        ui.post(() -> {
            View v = view;
            if (v != null) v.status(line, t);
        });
    }

    private boolean hasWindow(long id) {
        for (Window w : windows) if (w.id == id) return true;
        return false;
    }

    /** "Taken over, tap to get it back" (DISPLAY.md §1.3). */
    public void takeBack() {
        if (want != NONE) selectSource(want);
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

    /** Where the pointer is, in fractions of the frame; only the latest of a run of moves is sent. */
    public void pointerTo(float x, float y) {
        pointerX = Math.max(0, Math.min(1, x));
        pointerY = Math.max(0, Math.min(1, y));
        if (session == null) return;
        synchronized (out) {
            Object last = out.peekLast();
            if (last instanceof float[]) {
                ((float[]) last)[0] = pointerX;
                ((float[]) last)[1] = pointerY;
            } else {
                out.add(new float[] {pointerX, pointerY});
                out.notifyAll();
            }
        }
        ui.post(() -> {
            View v = view;
            if (v != null) v.pointer(pointerX, pointerY, true);
        });
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

    private void startClipboard() {
        if (!clipboardOn()) return;
        ui.post(() -> {
            if (clipWatch != null || surface == null) return;
            final ClipboardManager cm = context.getSystemService(ClipboardManager.class);
            enqueue(SillWire.CLIPBOARD, new byte[] {'{', '}'});
            clipWatch = () -> {
                try {
                    ClipData d = cm.getPrimaryClip();
                    if (d == null || d.getItemCount() == 0) return;
                    CharSequence t = d.getItemAt(0).getText();
                    if (t == null || t.length() == 0 || t.toString().equals(lastClipFromMac)) return;
                    if (t.length() > 256 * 1024) return;
                    enqueue(SillWire.CLIPBOARD, new JSONObject().put("text", t.toString()));
                } catch (RuntimeException | JSONException e) {
                    Log.d(TAG, "clipboard", e);
                }
            };
            cm.addPrimaryClipChangedListener(clipWatch);
        });
    }

    private void stopClipboard() {
        ui.post(() -> {
            if (clipWatch == null) return;
            context.getSystemService(ClipboardManager.class).removePrimaryClipChangedListener(clipWatch);
            clipWatch = null;
            try {
                enqueue(SillWire.CLIPBOARD, new JSONObject().put("stop", true));
            } catch (JSONException ignored) { }
        });
    }

    private void clipboardFromMac(final String text) {
        if (text.isEmpty() || !clipboardOn()) return;
        ui.post(() -> {
            if (clipWatch == null) return;
            lastClipFromMac = text;
            try {
                context.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Mac", text));
            } catch (RuntimeException e) {
                Log.d(TAG, "clipboard", e);
            }
        });
    }
}
