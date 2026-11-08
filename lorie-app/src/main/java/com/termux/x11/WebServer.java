package com.termux.x11;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The web display: any browser on the network opens http://phone:47150 and gets XDesk's display as a wireless display
 * would, with the phone's sound, and sends its mouse and keyboard back. Over Wi-Fi, the phone's hotspot or a USB cable
 * (USB tethering, NCM for iPad and Mac).
 *
 * Off by default. A browser pairs once with a 4-digit code that XDesk shows on the phone, so only someone who sees the
 * phone can connect; the browser then keeps a token. Plain HTTP: the token keeps other browsers out by accident or
 * casually, it is no protection against someone who can read the network, like the receiver's (PROTOCOL.md).
 *
 * After pairing the WebSocket carries the wireless display protocol, see WirelessDisplay.startWeb.
 */
final class WebServer {
    interface Listener {
        /** Show this code until onWebCodeDone. Main thread. */
        void onWebCode(String browserName, String code);
        void onWebCodeDone();
        void onWebMessage(String message);
    }

    private static final String TAG = "XDeskWeb";
    static final int PORT = 47150;
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int HEADER_MAX = 16384, REQUEST_TIMEOUT_MS = 15000, PAIRING_MS = 120000, MAX_TRIES = 3;
    /** A connection that has not paired: its requests, all of them, and its hello must be done by then. */
    private static final int CONNECTION_MS = 60000, HELLO_MS = 15000;
    /** Tenths of a second to wait for the session before answering busy. */
    private static final int BUSY_WAITS = 20;
    /** Before pairing, messages are a hello or a code. */
    private static final int SMALL_MESSAGE = 8192, PAIRED_MESSAGE = 1 << 20;
    /** Unpaired connections at a time, all and from one address. More are closed at once. */
    private static final int MAX_UNPAIRED = 16, MAX_UNPAIRED_PER_ADDRESS = 4;
    /** Wrong codes, dropped pairings included: per address before a lockout, and for everyone together. */
    private static final int ADDRESS_FAILURES = MAX_TRIES, ALL_FAILURES = 10;
    /** In all until a pairing succeeds, however slowly they come: pairing then stays closed until the switch is turned off and on. */
    private static final int TOTAL_FAILURES = 30;
    private static final long LOCKOUT_MS = 60000, ALL_WINDOW_MS = 600000, ALL_LOCKOUT_MS = 600000;
    private static final long ALLOWED_REFRESH_MS = 5000;

    private static WebServer instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile Listener listener;
    private volatile ServerSocket server;

    // Pairing, under pairingLock: one at a time; failures counted per address and for all, across connections
    private final Object pairingLock = new Object();
    private boolean pairing;
    private final Map<String, int[]> addressFailures = new HashMap<>();
    private final Map<String, Long> lockedUntil = new HashMap<>();
    private final java.util.ArrayDeque<Long> allFailures = new java.util.ArrayDeque<>();
    private long allLockedUntil;
    private int totalFailures;
    private boolean pairingClosed;

    // Unpaired connections, under unpairedLock
    private final Object unpairedLock = new Object();
    private int unpaired;
    private final Map<String, Integer> unpairedPerAddress = new HashMap<>();

    /** Local addresses the server answers on: those addresses() lists, and loopback. */
    private volatile java.util.Set<String> allowed = java.util.Collections.emptySet();
    private volatile long allowedAt;
    private byte[] iconPng;

    static synchronized WebServer get(Context c) {
        if (instance == null) instance = new WebServer(c.getApplicationContext());
        return instance;
    }

    private WebServer(Context c) {
        context = c;
        prefs = c.getSharedPreferences("web", Context.MODE_PRIVATE);
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isEnabled() {
        return prefs.getBoolean("enabled", false);
    }

    void setEnabled(boolean on) {
        prefs.edit().putBoolean("enabled", on).apply();
        synchronized (pairingLock) {
            pairingClosed = false;
            totalFailures = 0;
            allLockedUntil = 0;
            allFailures.clear();
        }
        if (on) start();
        else stop();
    }

    boolean isRunning() {
        return server != null;
    }

    /**
     * Starts listening if enabled; nothing when already running. Call it while XDesk is in front (opening the window,
     * turning the switch on): it also starts DesktopService in the foreground and keeps it there while the web display
     * is on, so a browser that connects while the phone is locked or XDesk is in the background can start streaming.
     * Android 12+ refuses to start a foreground service from the background (ForegroundServiceStartNotAllowedException).
     */
    synchronized void start() {
        if (!isEnabled()) return;
        keepService();
        if (server != null) return;
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PORT));
            server = s;
        } catch (IOException e) {
            Log.w(TAG, "listen", e);
            final String why = e.getMessage();
            ui.post(() -> {
                Listener l = listener;
                if (l != null) l.onWebMessage(context.getString(com.termux.x11.app.R.string.xd_web_no_port, PORT, why));
            });
            return;
        }
        final ServerSocket s = server;
        new Thread(() -> accept(s), "xdesk-web-accept").start();
    }

    synchronized void stop() {
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) { }
        }
        WirelessDisplay w = WirelessDisplay.get(context);
        if (w.isWeb() && w.isActive()) ui.post(w::stop);
        ui.post(() -> DesktopService.stopIfIdle(context));
    }

    /** A browser's stream ended: the service stays, with the web display's own notification again. */
    void sessionEnded() {
        ui.post(() -> {
            if (server != null) keepService();
        });
    }

    private void keepService() {
        try {
            context.startForegroundService(new android.content.Intent(context, DesktopService.class)
                .putExtra(DesktopService.EXTRA_TITLE, context.getString(com.termux.x11.app.R.string.xd_web_service_title)));
        } catch (RuntimeException e) {
            // In the background after all: the stream then starts only while XDesk is in front
            Log.w(TAG, "foreground service", e);
        }
    }

    int pairedCount() {
        int n = 0;
        for (String k : prefs.getAll().keySet()) if (k.startsWith("token_")) n++;
        return n;
    }

    void forgetBrowsers() {
        SharedPreferences.Editor e = prefs.edit();
        for (String k : prefs.getAll().keySet()) if (k.startsWith("token_") || k.startsWith("name_")) e.remove(k);
        e.apply();
    }

    // --- 地址：无线、热点、有线（USB 网络共享） ---

    static final int KIND_WIFI = 0, KIND_HOTSPOT = 1, KIND_USB = 2, KIND_ETHERNET = 3, KIND_OTHER = 4;

    static final class Address {
        final int kind;
        final String host;
        Address(int kind, String host) {
            this.kind = kind;
            this.host = host;
        }
        String url() {
            return "http://" + host + ":" + PORT;
        }
    }

    /** IPv4 addresses a browser can reach, Wi-Fi first. */
    static List<Address> addresses() {
        List<Address> list = new ArrayList<>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                String name = n.getName().toLowerCase(Locale.ROOT);
                // Mobile data and VPNs: nobody on them can reach the phone
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") || name.startsWith("dummy")
                    || name.startsWith("p2p") || name.startsWith("ip6") || name.startsWith("sit")) continue;
                int kind = name.startsWith("wlan") ? KIND_WIFI
                    : name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") ? KIND_HOTSPOT
                    : name.startsWith("rndis") || name.startsWith("ncm") || name.startsWith("usb") ? KIND_USB
                    : name.startsWith("eth") ? KIND_ETHERNET : KIND_OTHER;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        list.add(new Address(kind, a.getHostAddress()));
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) { }
        Collections.sort(list, (a, b) -> a.kind - b.kind);
        return list;
    }

    /**
     * iPad and Mac only speak NCM over USB, Android tethers with RNDIS unless told otherwise (Android 11+). Needs
     * WRITE_SECURE_SETTINGS, which the injector may have given XDesk. Returns false without it.
     */
    static boolean setUsbTetheringNcm(Context c) {
        try {
            return Settings.Global.putInt(c.getContentResolver(), "tether_force_usb_functions", 1);
        } catch (SecurityException e) {
            return false;
        }
    }

    static boolean usbTetheringIsNcm(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), "tether_force_usb_functions", 0) == 1;
    }

    // --- HTTP ---

    private void accept(ServerSocket s) {
        int backoff = 50;
        while (server == s) {
            final Socket c;
            try {
                c = s.accept();
                backoff = 50;
            } catch (IOException e) {
                if (server != s || s.isClosed()) break;
                // EMFILE, ECONNABORTED and the like pass: wait a little and go on
                Log.w(TAG, "accept: " + e);
                android.os.SystemClock.sleep(backoff);
                backoff = Math.min(backoff * 2, 2000);
                continue;
            } catch (RuntimeException e) {
                Log.w(TAG, "accept", e);
                break;
            }
            final String ip = c.getInetAddress().getHostAddress();
            // Only on the networks the page lists (and this phone itself): not VPNs, mobile data or IPv6
            if (!allowedLocal(c.getLocalAddress()) || !enterUnpaired(ip)) {
                closeQuietly(c);
                continue;
            }
            try {
                new Thread(() -> serve(c, ip), "xdesk-web-client").start();
            } catch (Throwable t) {
                // Out of threads or memory: drop this one, keep serving
                leaveUnpaired(ip);
                closeQuietly(c);
            }
        }
        // Ended by an error, not by stop(): give the port back and show the web display as off until it starts again
        synchronized (this) {
            if (server == s) {
                server = null;
                try {
                    s.close();
                } catch (IOException ignored) { }
            }
        }
    }

    private boolean allowedLocal(InetAddress local) {
        if (local == null) return false;
        if (local.isLoopbackAddress()) return true;
        long now = android.os.SystemClock.uptimeMillis();
        // Stale, or a new address (Wi-Fi changed, tethering turned on); not for every refused connection
        boolean known = allowed.contains(local.getHostAddress());
        if (now - allowedAt > ALLOWED_REFRESH_MS || (!known && now - allowedAt > 500)) {
            java.util.Set<String> a = new java.util.HashSet<>();
            for (Address x : addresses()) a.add(x.host);
            allowed = a;
            allowedAt = now;
        }
        return allowed.contains(local.getHostAddress());
    }

    private String appVersion() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private void webMessage(final int id) {
        ui.post(() -> {
            Listener l = listener;
            if (l != null) l.onWebMessage(context.getString(id));
        });
    }

    private boolean enterUnpaired(String ip) {
        synchronized (unpairedLock) {
            int mine = unpairedPerAddress.containsKey(ip) ? unpairedPerAddress.get(ip) : 0;
            if (unpaired >= MAX_UNPAIRED || mine >= MAX_UNPAIRED_PER_ADDRESS) return false;
            unpaired++;
            unpairedPerAddress.put(ip, mine + 1);
            return true;
        }
    }

    private void leaveUnpaired(String ip) {
        synchronized (unpairedLock) {
            unpaired = Math.max(0, unpaired - 1);
            Integer mine = unpairedPerAddress.get(ip);
            if (mine == null || mine <= 1) unpairedPerAddress.remove(ip);
            else unpairedPerAddress.put(ip, mine - 1);
        }
    }

    private void serve(Socket c, String ip) {
        boolean handedOver = false;
        // Everything before pairing ends by then, however slowly the client sends
        final long deadline = System.currentTimeMillis() + CONNECTION_MS;
        try {
            InputStream in = new BufferedInputStream(c.getInputStream(), 16384);
            OutputStream out = new BufferedOutputStream(c.getOutputStream(), 65536);
            // One request per connection: an idle keep-alive connection would count as unpaired, and after a reload those
            // of the old page used up the limit, so the page's WebSocket was closed at once for up to a minute
            c.setSoTimeout(REQUEST_TIMEOUT_MS);
            Map<String, String> h = new HashMap<>();
            String[] request = readRequest(in, h, deadline);
            if (request == null) return;
            String path = request[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            if (!"GET".equals(request[0])) {
                respond(out, 405, "text/plain", "method".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/ws".equals(path) && "websocket".equalsIgnoreCase(h.get("upgrade"))) {
                handedOver = webSocket(c, ip, in, out, h);
                return;
            }
            byte[] body;
            String type;
            switch (path) {
                case "/": body = asset("index.html"); type = "text/html; charset=utf-8"; break;
                case "/xdesk.js": body = asset("xdesk.js"); type = "text/javascript; charset=utf-8"; break;
                case "/xdesk.css": body = asset("xdesk.css"); type = "text/css; charset=utf-8"; break;
                case "/manifest.webmanifest": body = asset("manifest.webmanifest"); type = "application/manifest+json"; break;
                case "/icon.png": body = icon(); type = "image/png"; break;
                default: body = null; type = null;
            }
            if (body == null) respond(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            else respond(out, 200, type, body);
        } catch (SocketTimeoutException ignored) {
        } catch (IOException e) {
            Log.i(TAG, "client: " + e);
        } catch (RuntimeException e) {
            // Nothing a client sends may take XDesk down with it
            Log.w(TAG, "client", e);
        } finally {
            leaveUnpaired(ip);
            if (!handedOver) closeQuietly(c);
        }
    }

    /** Method and path, headers lower-cased into h; null at end of stream. */
    private static String[] readRequest(InputStream in, Map<String, String> h, long deadline) throws IOException {
        String line = readLine(in, deadline);
        if (line == null) return null;
        String[] parts = line.split(" ");
        if (parts.length < 3) throw new IOException("bad request line");
        int total = line.length();
        while (true) {
            String l = readLine(in, deadline);
            if (l == null) throw new IOException("headers cut off");
            if (l.isEmpty()) break;
            total += l.length();
            if (total > HEADER_MAX) throw new IOException("headers too long");
            int colon = l.indexOf(':');
            if (colon > 0) h.put(l.substring(0, colon).trim().toLowerCase(Locale.ROOT), l.substring(colon + 1).trim());
        }
        return parts;
    }

    private static String readLine(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        while (true) {
            if (System.currentTimeMillis() > deadline) throw new SocketTimeoutException("deadline");
            int c = in.read();
            if (c < 0) return b.size() == 0 ? null : b.toString("UTF-8");
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > HEADER_MAX) throw new IOException("line too long");
        }
        return b.toString("UTF-8");
    }

    private static void respond(OutputStream out, int status, String type, byte[] body) throws IOException {
        String reason = status == 200 ? "OK" : status == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
            + "Content-Type: " + type + "\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Cache-Control: no-cache\r\n"
            + "Connection: close\r\n"
            + "X-Content-Type-Options: nosniff\r\n"
            + "Referrer-Policy: no-referrer\r\n"
            + "\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private byte[] asset(String name) {
        AssetManager a = context.getAssets();
        try (InputStream in = a.open("web/" + name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * For "Add to Home Screen": XDesk's launcher icon with a green globe badge, so the web display does not look like
     * the app itself on a device that has both.
     */
    private synchronized byte[] icon() {
        if (iconPng != null) return iconPng;
        try {
            Drawable d = context.getPackageManager().getApplicationIcon(context.getPackageName());
            Bitmap b = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 192, 192);
            d.draw(c);
            // Badge at the lower right, inside the corner that home screens round off
            float cx = 138, cy = 138, r = 40;
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setColor(Style.BACKGROUND);
            c.drawCircle(cx, cy, r + 6, p);
            p.setColor(Style.OK);
            c.drawCircle(cx, cy, r, p);
            // The globe: outline, a meridian, the equator and two parallels
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(4.5f);
            p.setColor(0xFFFFFFFF);
            float g = r * 0.62f;
            c.drawCircle(cx, cy, g, p);
            c.drawOval(new android.graphics.RectF(cx - g * 0.45f, cy - g, cx + g * 0.45f, cy + g), p);
            c.drawLine(cx - g, cy, cx + g, cy, p);
            float py = g * 0.5f, px = (float) Math.sqrt(g * g - py * py);
            c.drawLine(cx - px, cy - py, cx + px, cy - py, p);
            c.drawLine(cx - px, cy + py, cx + px, cy + py, p);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
            iconPng = o.toByteArray();
        } catch (Exception e) {
            iconPng = new byte[0];
        }
        return iconPng;
    }

    // --- WebSocket 和配对 ---

    /** Returns true when the socket now belongs to WirelessDisplay. */
    private boolean webSocket(Socket c, String ip, InputStream in, OutputStream out, Map<String, String> h) throws IOException {
        String key = h.get("sec-websocket-key");
        if (key == null) return false;
        // Another web page in the same browser must not reach the phone: only this page's own origin
        String origin = h.get("origin"), host = h.get("host");
        if (origin != null && host != null && !origin.equals("http://" + host) && !origin.equals("https://" + host)) {
            respond(out, 403, "text/plain", "origin".getBytes(StandardCharsets.UTF_8));
            return false;
        }
        String accept;
        try {
            accept = Base64.encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)), Base64.NO_WRAP);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        WebSocket ws = new WebSocket(in, out);
        // Until paired: small messages, and the hello within its time whatever else arrives
        ws.setMaxMessage(SMALL_MESSAGE);
        ws.setDeadline(c, System.currentTimeMillis() + HELLO_MS);
        try {
            JSONObject hello = new JSONObject(ws.read().text());
            String id = hello.optString("id", "");
            String name = hello.optString("name", "").trim();
            if (name.isEmpty() || name.length() > 60) name = "Browser";
            String label = name + " (" + ip + ")";
            int w = clampEven(hello.optInt("w", 1280)), hh = clampEven(hello.optInt("h", 720));
            int[] size = fit(w, hh);
            WirelessDisplay wd = WirelessDisplay.get(context);
            // A reloaded page is here before its old connection has ended
            for (int i = 0; i < BUSY_WAITS && wd.isActive() && !wd.webResumable(id, size[0], size[1]); i++) android.os.SystemClock.sleep(100);
            if (listener == null) {
                ws.sendText(status("closed"));
                ws.close();
                return false;
            }
            if (wd.isActive() && !wd.webResumable(id, size[0], size[1])) {
                ws.sendText(status("busy").replace("}", ",\"name\":" + JSONObject.quote(wd.receiverName()) + "}"));
                ws.close();
                return false;
            }
            String token = null;
            if (!id.matches("[0-9a-f]{32}")) {
                ws.sendText(status("version"));
                ws.close();
                return false;
            }
            String known = prefs.getString("token_" + id, null);
            String offered = hello.optString("token", "");
            if (known == null || !MessageDigest.isEqual(known.getBytes(StandardCharsets.US_ASCII), offered.getBytes(StandardCharsets.US_ASCII))) {
                token = pair(ws, c, ip, label);
                if (token == null) {
                    ws.close();
                    return false;
                }
                prefs.edit().putString("token_" + id, token).putString("name_" + id, name).apply();
            }
            ws.setDeadline(null, 0);
            ws.setMaxMessage(PAIRED_MESSAGE);
            JSONObject ok = new JSONObject();
            ok.put("status", "ok");
            ok.put("token", token != null ? token : "");
            ok.put("phone", phoneName());
            ok.put("version", appVersion());
            ok.put("w", size[0]);
            ok.put("h", size[1]);
            synchronized (wd) {
                if (wd.isActive() && !wd.webResumable(id, size[0], size[1])) {
                    ws.sendText(status("busy"));
                    ws.close();
                    return false;
                }
                // Sends ok itself, before any stream message; false when it may not start (see startWeb)
                if (!wd.startWeb(c, ws, id, context.getString(com.termux.x11.app.R.string.xd_web_receiver_name, label),
                        size[0], size[1], ok.toString())) {
                    ws.sendText(status("ended").replace("}", ",\"reason\":"
                        + JSONObject.quote(context.getString(com.termux.x11.app.R.string.xd_web_no_service)) + "}"));
                    ws.close();
                    return false;
                }
            }
            return true;
        } catch (JSONException e) {
            ws.close();
            return false;
        }
    }

    /**
     * Shows a code on the phone and waits for the browser to send it. Returns a new token, or null.
     *
     * One pairing at a time: others get "pairing" at once rather than waiting. A pairing ends 2 minutes after it began,
     * whatever arrives. Every wrong code counts against the address and against everyone, across connections, and so
     * does a pairing that ends without a right code (dropped, timed out, anything but a code).
     */
    private String pair(WebSocket ws, Socket c, String ip, final String label) throws IOException {
        synchronized (pairingLock) {
            if (lockedOut(ip, System.currentTimeMillis())) {
                ws.sendText(status("refused"));
                return null;
            }
            if (pairing) {
                ws.sendText(status("pairing"));
                return null;
            }
            pairing = true;
        }
        SecureRandom r = new SecureRandom();
        final String code = String.format(Locale.ROOT, "%04d", r.nextInt(10000));
        ui.post(() -> {
            Listener l = listener;
            if (l != null) l.onWebCode(label, code);
        });
        boolean paired = false;
        int wrong = 0;
        try {
            ws.setDeadline(c, System.currentTimeMillis() + PAIRING_MS);
            ws.sendText(status("pair"));
            while (true) {
                WebSocket.Message m;
                try {
                    m = ws.read();
                } catch (IOException e) {
                    return null;
                }
                if (m.type != WebSocket.TEXT) return null;
                String typed;
                try {
                    typed = new JSONObject(m.text()).optString("code", "");
                } catch (JSONException e) {
                    return null;
                }
                if (code.equals(typed)) {
                    paired = true;
                    byte[] t = new byte[32];
                    r.nextBytes(t);
                    StringBuilder s = new StringBuilder();
                    for (byte x : t) s.append(String.format("%02x", x));
                    return s.toString();
                }
                wrong++;
                boolean locked;
                synchronized (pairingLock) {
                    locked = fail(ip);
                }
                if (locked || wrong >= MAX_TRIES) {
                    ws.sendText(status("refused"));
                    return null;
                }
                ws.sendText(status("wrong"));
            }
        } finally {
            synchronized (pairingLock) {
                pairing = false;
                if (paired) {
                    addressFailures.remove(ip);
                    totalFailures = 0;
                } else if (wrong == 0) {
                    fail(ip);
                }
            }
            ui.post(() -> {
                Listener l = listener;
                if (l != null) l.onWebCodeDone();
            });
        }
    }

    /** Under pairingLock. */
    private boolean lockedOut(String ip, long now) {
        if (pairingClosed || allLockedUntil > now) return true;
        Long until = lockedUntil.get(ip);
        if (until == null) return false;
        if (until > now) return true;
        lockedUntil.remove(ip);
        return false;
    }

    /** One failed pairing attempt from ip. Under pairingLock. Returns whether ip is now locked out. */
    private boolean fail(String ip) {
        long now = System.currentTimeMillis();
        int[] n = addressFailures.get(ip);
        if (n == null) addressFailures.put(ip, n = new int[1]);
        if (++n[0] >= ADDRESS_FAILURES) {
            addressFailures.remove(ip);
            lockedUntil.put(ip, now + LOCKOUT_MS);
        }
        // Many addresses guessing together: stop pairing for everyone a while, and say so on the phone
        allFailures.addLast(now);
        while (!allFailures.isEmpty() && now - allFailures.peekFirst() > ALL_WINDOW_MS) allFailures.removeFirst();
        if (allFailures.size() >= ALL_FAILURES && allLockedUntil <= now) {
            allFailures.clear();
            allLockedUntil = now + ALL_LOCKOUT_MS;
            webMessage(com.termux.x11.app.R.string.xd_web_pairing_paused);
        }
        // A window alone lets a slow guesser go on for ever
        if (++totalFailures >= TOTAL_FAILURES && !pairingClosed) {
            pairingClosed = true;
            webMessage(com.termux.x11.app.R.string.xd_web_pairing_closed);
        }
        return lockedOut(ip, now);
    }

    private static String status(String s) {
        return "{\"status\":\"" + s + "\"}";
    }

    private static int clampEven(int v) {
        return Math.max(320, Math.min(7680, v)) & ~1;
    }

    /** At most 1920 on the long side and 1088 on the short one, as the receiver asks, aspect kept, even. */
    static int[] fit(int w, int h) {
        float scale = Math.min(1f, Math.min(1920f / Math.max(w, h), 1088f / Math.min(w, h)));
        int sw = Math.round(w * scale) & ~1, sh = Math.round(h * scale) & ~1;
        return new int[]{Math.max(320, sw), Math.max(240, sh)};
    }

    private String phoneName() {
        String n = Settings.Global.getString(context.getContentResolver(), "device_name");
        return n != null && !n.isEmpty() ? n : android.os.Build.MODEL;
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) { }
    }
}
