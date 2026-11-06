package io.github.n2ray.xdesk.receiver;

import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The TCP server: accepts phones, lets one at a time stream, and holds the pairing code. */
final class Receiver {
    static final int PORT = 47140;
    static final int CODE_OK = 0, CODE_WRONG = 1, CODE_REFUSED = 2;
    private static final int MAX_WRONG = 3;

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
                    Session s = new Session(this, c);
                    synchronized (this) {
                        if (!alive(my)) {
                            closeQuietly(c);
                            break;
                        }
                        sessions.add(s);
                    }
                    s.start();
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
     * Gives the receiver to this session after its HELLO. False (BUSY) while another phone holds it. The same phone
     * connecting again takes over at once: it reconnects after a network change long before its old connection
     * would time out.
     */
    boolean claim(Session s) {
        Session old;
        synchronized (this) {
            if (!running) return false;
            old = active;
            if (old != null && !old.phoneId.equals(s.phoneId)) return false;
            active = s;
        }
        if (old != null) {
            Log.i(MainActivity.TAG, "same phone again, closing its old connection");
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

    synchronized int checkCode(String typed) {
        if (code != null && code.equals(typed.trim())) {
            code = null;
            return CODE_OK;
        }
        if (++wrong >= MAX_WRONG) {
            code = null;
            return CODE_REFUSED;
        }
        return CODE_WRONG;
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
