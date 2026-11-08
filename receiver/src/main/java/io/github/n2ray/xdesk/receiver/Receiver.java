package io.github.n2ray.xdesk.receiver;

import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The TCP server: accepts phones, lets one at a time stream, and holds the pairing code. */
final class Receiver {
    static final int PORT = 47140;
    static final int CODE_OK = 0, CODE_WRONG = 1, CODE_REFUSED = 2;
    private static final int MAX_WRONG = 3;
    // Connections that have not proved themselves, all and from one address. More are closed at once
    private static final int MAX_UNPAIRED = 16, MAX_UNPAIRED_PER_ADDRESS = 4;
    // Failed pairings: per address before a lockout, for everyone within a window, and in all until one succeeds.
    // After the last one pairing stays closed until the app is opened again
    private static final int ADDRESS_FAILURES = MAX_WRONG, ALL_FAILURES = 10, TOTAL_FAILURES = 30;
    private static final long LOCKOUT_MS = 60_000, ALL_WINDOW_MS = 600_000, ALL_LOCKOUT_MS = 600_000;

    /** Called from network threads; only for the session that holds the receiver */
    interface Ui {
        void idle();

        void portBusy(boolean busy);

        void pairing(String phone, String code, boolean retry);

        void connecting(String phone);

        void streaming();

        void videoSize(int width, int height);

        void cannotDecode(String phone, int width, int height);
    }

    final Store store;
    final Ui ui;
    private volatile Screen screen;
    private Surface surface;
    private boolean running;
    // Each start() makes a new generation; threads of an older one see it and stop
    private int gen;
    private ServerSocket server;
    // The session that holds the receiver: pairing or streaming
    private Session active;
    private final Set<Session> sessions = new HashSet<>();
    private String code;
    private int wrong;
    private int unpaired, totalFailures;
    private boolean pairingClosed;
    private long allLockedUntil;
    private final Map<String, Integer> unpairedPerAddress = new HashMap<>();
    private final Map<String, Integer> addressFailures = new HashMap<>();
    private final Map<String, Long> lockedUntil = new HashMap<>();
    private final ArrayDeque<Long> allFailures = new ArrayDeque<>();

    Receiver(Store store, Ui ui) {
        this.store = store;
        this.ui = ui;
    }

    Screen screen() {
        return screen;
    }

    void setScreen(Screen s) {
        Screen old = screen;
        screen = s;
        if (old == null || s.equals(old)) return;
        Session a;
        synchronized (this) {
            a = active;
        }
        if (a != null) a.sendSize(s);
    }

    synchronized Surface surface() {
        return surface;
    }

    synchronized boolean isActive(Session s) {
        return active == s;
    }

    void start(Surface surface) {
        final int my;
        synchronized (this) {
            if (running) return;
            running = true;
            this.surface = surface;
            my = ++gen;
            pairingClosed = false;
            totalFailures = 0;
        }
        new Thread(() -> serve(my), "server").start();
    }

    /**
     * Closes everything and waits for the sessions to release their decoders: the caller is about to lose the
     * surface they draw on. Tells a streaming phone BYE first.
     */
    void stop() {
        final ServerSocket ss;
        final List<Session> all;
        final Session current;
        synchronized (this) {
            if (!running) return;
            running = false;
            gen++;
            ss = server;
            server = null;
            all = new ArrayList<>(sessions);
            current = active;
        }
        // Sockets must not be touched on the main thread
        Thread t = new Thread(() -> {
            closeQuietly(ss);
            for (Session s : all) s.kill(s == current);
            for (Session s : all) {
                try {
                    s.join(1500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "stop");
        t.start();
        try {
            t.join(2500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized boolean alive(int my) {
        return running && gen == my;
    }

    private void serve(int my) {
        while (alive(my)) {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                try {
                    ss.bind(new InetSocketAddress(PORT));
                } catch (IOException e) {
                    Log.w(MainActivity.TAG, "bind: " + e);
                    ui.portBusy(true);
                    sleep(2000);
                    continue;
                }
                synchronized (this) {
                    if (!alive(my)) break;
                    server = ss;
                }
                ui.portBusy(false);
                while (true) {
                    Socket c = ss.accept();
                    String ip = c.getInetAddress().getHostAddress();
                    if (!enterUnpaired(ip)) {
                        closeQuietly(c);
                        continue;
                    }
                    Session s = new Session(this, c, ip);
                    synchronized (this) {
                        if (!alive(my)) {
                            leaveUnpaired(ip);
                            closeQuietly(c);
                            break;
                        }
                        sessions.add(s);
                    }
                    try {
                        s.start();
                    } catch (OutOfMemoryError e) {
                        // No thread left: this connection goes, the one streaming stays
                        Log.w(MainActivity.TAG, "no thread for a connection");
                        synchronized (this) {
                            sessions.remove(s);
                        }
                        leaveUnpaired(ip);
                        closeQuietly(c);
                    }
                }
            } catch (IOException e) {
                if (alive(my)) {
                    Log.w(MainActivity.TAG, "server: " + e);
                    sleep(1000);
                }
            } finally {
                closeQuietly(ss);
            }
        }
    }

    /**
     * Gives the receiver to this session after its HELLO. False (BUSY) while another phone holds it. A phone that has
     * proved itself takes over from its own old connection at once (it reconnects after a network change long before
     * that would time out) and from a connection still pairing. Nothing unproved replaces anything.
     */
    boolean claim(Session s) {
        Session old;
        synchronized (this) {
            if (!running) return false;
            old = active;
            if (old != null && !(s.authenticated && (old.phoneId.equals(s.phoneId) || !old.authenticated))) return false;
            active = s;
        }
        if (old != null) {
            Log.i(MainActivity.TAG, "a paired phone takes over, closing the old connection");
            old.kill(false);
            try {
                old.join(2000);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return true;
    }

    void ended(Session s) {
        synchronized (this) {
            sessions.remove(s);
            if (active != s) return;
            active = null;
        }
        ui.idle();
    }

    /** The code stays the same until it is entered right or wrong 3 times, across connections */
    synchronized String pairingCode() {
        if (code == null) {
            code = String.format(Locale.ROOT, "%04d", Store.RANDOM.nextInt(10000));
            wrong = 0;
        }
        return code;
    }

    synchronized int checkCode(String typed, String ip) {
        if (code != null && code.equals(typed.trim())) {
            code = null;
            addressFailures.remove(ip);
            totalFailures = 0;
            return CODE_OK;
        }
        boolean locked = failed(ip);
        if (locked || ++wrong >= MAX_WRONG) {
            code = null;
            return CODE_REFUSED;
        }
        return CODE_WRONG;
    }

    /** Whether this address may not try a code now. Phones with a valid token do not ask. */
    synchronized boolean lockedOut(String ip) {
        long now = SystemClock.elapsedRealtime();
        if (pairingClosed || allLockedUntil > now) return true;
        Long until = lockedUntil.get(ip);
        if (until == null) return false;
        if (until > now) return true;
        lockedUntil.remove(ip);
        return false;
    }

    /** A wrong code, or a pairing given up without one. Returns whether that locks this address out. */
    synchronized boolean failed(String ip) {
        long now = SystemClock.elapsedRealtime();
        Integer n = addressFailures.get(ip);
        int mine = n == null ? 1 : n + 1;
        if (mine >= ADDRESS_FAILURES) {
            addressFailures.remove(ip);
            lockedUntil.put(ip, now + LOCKOUT_MS);
        } else {
            addressFailures.put(ip, mine);
        }
        allFailures.addLast(now);
        while (!allFailures.isEmpty() && now - allFailures.peekFirst() > ALL_WINDOW_MS) allFailures.removeFirst();
        if (allFailures.size() >= ALL_FAILURES && allLockedUntil <= now) {
            allFailures.clear();
            allLockedUntil = now + ALL_LOCKOUT_MS;
        }
        if (++totalFailures >= TOTAL_FAILURES && !pairingClosed) {
            Log.w(MainActivity.TAG, "too many failed pairings, closed until the app is opened again");
            pairingClosed = true;
        }
        return lockedOut(ip);
    }

    private synchronized boolean enterUnpaired(String ip) {
        Integer n = unpairedPerAddress.get(ip);
        int mine = n == null ? 0 : n;
        if (unpaired >= MAX_UNPAIRED || mine >= MAX_UNPAIRED_PER_ADDRESS) return false;
        unpaired++;
        unpairedPerAddress.put(ip, mine + 1);
        return true;
    }

    synchronized void leaveUnpaired(String ip) {
        unpaired = Math.max(0, unpaired - 1);
        Integer n = unpairedPerAddress.get(ip);
        if (n == null || n <= 1) unpairedPerAddress.remove(ip);
        else unpairedPerAddress.put(ip, n - 1);
    }

    static void closeQuietly(ServerSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException e) {
            // Closing anyway
        }
    }

    static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException e) {
            // Closing anyway
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
