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
    private static final long LOCKOUT_MS = 60000;

    private static WebServer instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile Listener listener;
    private volatile ServerSocket server;
    private final Object pairingLock = new Object();
    private final Map<String, Long> lockedOut = Collections.synchronizedMap(new HashMap<>());
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
        if (on) start();
        else stop();
    }

    boolean isRunning() {
        return server != null;
    }

    /** Starts listening if enabled; nothing when already running. */
    synchronized void start() {
        if (server != null || !isEnabled()) return;
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
        while (server == s) {
            final Socket c;
            try {
                c = s.accept();
            } catch (IOException e) {
                break;
            }
            new Thread(() -> serve(c), "xdesk-web-client").start();
        }
    }

    private void serve(Socket c) {
        boolean handedOver = false;
        try {
            c.setSoTimeout(REQUEST_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(c.getInputStream(), 16384);
            OutputStream out = new BufferedOutputStream(c.getOutputStream(), 65536);
            // Keep-alive: browsers fetch the page, script and icon over one connection
            while (true) {
                Map<String, String> h = new HashMap<>();
                String[] request = readRequest(in, h);
                if (request == null) return;
                String path = request[1];
                int q = path.indexOf('?');
                if (q >= 0) path = path.substring(0, q);
                if (!"GET".equals(request[0])) {
                    respond(out, 405, "text/plain", "method".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if ("/ws".equals(path) && "websocket".equalsIgnoreCase(h.get("upgrade"))) {
                    handedOver = webSocket(c, in, out, h);
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
                if ("close".equalsIgnoreCase(h.get("connection"))) return;
            }
        } catch (SocketTimeoutException ignored) {
        } catch (IOException e) {
            Log.i(TAG, "client: " + e);
        } finally {
            if (!handedOver) closeQuietly(c);
        }
    }

    /** Method and path, headers lower-cased into h; null at end of stream. */
    private static String[] readRequest(InputStream in, Map<String, String> h) throws IOException {
        String line = readLine(in);
        if (line == null) return null;
        String[] parts = line.split(" ");
        if (parts.length < 3) throw new IOException("bad request line");
        int total = line.length();
        while (true) {
            String l = readLine(in);
            if (l == null) throw new IOException("headers cut off");
            if (l.isEmpty()) break;
            total += l.length();
            if (total > HEADER_MAX) throw new IOException("headers too long");
            int colon = l.indexOf(':');
            if (colon > 0) h.put(l.substring(0, colon).trim().toLowerCase(Locale.ROOT), l.substring(colon + 1).trim());
        }
        return parts;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        while (true) {
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
    private boolean webSocket(Socket c, InputStream in, OutputStream out, Map<String, String> h) throws IOException {
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
        String ip = c.getInetAddress().getHostAddress();
        try {
            JSONObject hello = new JSONObject(ws.read().text());
            String id = hello.optString("id", "");
            String name = hello.optString("name", "").trim();
            if (name.isEmpty() || name.length() > 60) name = "Browser";
            String label = name + " (" + ip + ")";
            int w = clampEven(hello.optInt("w", 1280)), hh = clampEven(hello.optInt("h", 720));
            int[] size = fit(w, hh);
            WirelessDisplay wd = WirelessDisplay.get(context);
            if (listener == null) {
                ws.sendText(status("closed"));
                ws.close();
                return false;
            }
            if (wd.isActive()) {
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
                // Someone has to walk to the phone: wait longer than for a request
                c.setSoTimeout(PAIRING_MS);
                token = pair(ws, ip, label);
                c.setSoTimeout(REQUEST_TIMEOUT_MS);
                if (token == null) {
                    ws.close();
                    return false;
                }
                prefs.edit().putString("token_" + id, token).putString("name_" + id, name).apply();
            }
            JSONObject ok = new JSONObject();
            ok.put("status", "ok");
            ok.put("token", token != null ? token : "");
            ok.put("phone", phoneName());
            ok.put("w", size[0]);
            ok.put("h", size[1]);
            ws.sendText(ok.toString());
            synchronized (wd) {
                if (wd.isActive()) {
                    ws.sendText(status("busy"));
                    ws.close();
                    return false;
                }
                wd.startWeb(c, ws, context.getString(com.termux.x11.app.R.string.xd_web_receiver_name, label), size[0], size[1]);
            }
            return true;
        } catch (JSONException e) {
            ws.close();
            return false;
        }
    }

    /** Shows a code on the phone and waits for the browser to send it. Returns a new token, or null. */
    private String pair(WebSocket ws, String ip, final String label) throws IOException, JSONException {
        Long until = lockedOut.get(ip);
        if (until != null && until > System.currentTimeMillis()) {
            ws.sendText(status("refused"));
            return null;
        }
        synchronized (pairingLock) {
            SecureRandom r = new SecureRandom();
            final String code = String.format(Locale.ROOT, "%04d", r.nextInt(10000));
            ui.post(() -> {
                Listener l = listener;
                if (l != null) l.onWebCode(label, code);
            });
            try {
                ws.sendText(status("pair"));
                for (int tries = 0; tries < MAX_TRIES; tries++) {
                    WebSocket.Message m;
                    try {
                        m = ws.read();
                    } catch (SocketTimeoutException e) {
                        return null;
                    }
                    if (m.type != WebSocket.TEXT) {
                        tries--;
                        continue;
                    }
                    String typed = new JSONObject(m.text()).optString("code", "");
                    if (code.equals(typed)) {
                        byte[] t = new byte[32];
                        r.nextBytes(t);
                        StringBuilder s = new StringBuilder();
                        for (byte x : t) s.append(String.format("%02x", x));
                        return s.toString();
                    }
                    if (tries < MAX_TRIES - 1) ws.sendText(status("wrong"));
                }
                lockedOut.put(ip, System.currentTimeMillis() + LOCKOUT_MS);
                ws.sendText(status("refused"));
                return null;
            } finally {
                ui.post(() -> {
                    Listener l = listener;
                    if (l != null) l.onWebCodeDone();
                });
            }
        }
    }

    private static String status(String s) {
        return "{\"status\":\"" + s + "\"}";
    }

    private static int clampEven(int v) {
        return Math.max(320, Math.min(7680, v)) & ~1;
    }

    /** At most 1920 on the long side and 1088 on the short one, as the receiver asks, aspect kept, even. */
    private static int[] fit(int w, int h) {
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
