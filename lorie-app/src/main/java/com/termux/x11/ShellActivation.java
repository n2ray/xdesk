package com.termux.x11;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import com.termux.x11.app.R;
import io.github.muntashirakon.adb.AdbAuthenticationFailedException;
import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.AdbTlsProbe;
import io.github.muntashirakon.adb.android.AdbMdns;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

/**
 * Gets an injector without Shizuku: pairs with this device's own adbd once, then starts ShellServer as the shell user
 * whenever XDesk opens and none is running. Only ever writes adb_wifi_enabled, and turns it off again when it was XDesk
 * that turned it on. It leaves adb_enabled and adb_allowed_connection_time alone: changing those would affect every
 * paired device, and the user did not choose that.
 */
final class ShellActivation {
    private static final String TAG = "XDeskShell";
    private static final String PREFS = "shell_activation", PREF_PAIRED = "paired", PREF_PORT = "knock_port";
    /** adbd's certificate when it last started the server, and the boot it was in: adbd makes a new key when it starts. */
    private static final String PREF_ADBD = "adbd_cert", PREF_ADBD_BOOT = "adbd_cert_boot";
    /** XDesk turned wireless debugging on and has not turned it off again yet. Kept in case the app dies in between. */
    private static final String PREF_WIRELESS_OURS = "wireless_enabled_by_xdesk";
    private static final String SETTING_WIRELESS = "adb_wifi_enabled";
    private static final String LOOPBACK = "127.0.0.1";
    private static final String STARTED_MARKER = "xdesk_started";
    private static final String CONSCRYPT_PREFIX = "Lcom/android/org/conscrypt/";
    private static final int KNOCK_TIMEOUT_MS = 300;
    private static final int PROBE_TIMEOUT_MS = 2000;
    /** How long a running server gets to answer a knock. */
    private static final long ANSWER_WAIT_MS = 1500;
    /** app_process loading this APK without compiled code takes a while. */
    private static final long SERVER_START_WAIT_MS = 30000, KNOCK_EVERY_MS = 1000;
    private static final long SHELL_COMMAND_WAIT_MS = 8000, SHELL_POLL_MS = 50;
    private static final long SETTING_SETTLE_MS = 1500, FIND_PORT_MS = 4000, FIND_PORT_AFTER_ENABLE_MS = 8000;
    private static final int SCAN_FIRST_PORT = 30000, SCAN_LAST_PORT = 60999, SCAN_THREADS = 16, SCAN_TIMEOUT_MS = 100, SCAN_MAX_TRIES = 4;
    private static final long SCAN_WAIT_S = 20;

    private static ShellActivation instance;

    static synchronized ShellActivation get(Context c) {
        if (instance == null) instance = new ShellActivation(c.getApplicationContext());
        return instance;
    }

    /** Tied to the APK's install time, so an update never talks to an old injector. */
    static int installVersion(Context c) {
        try {
            return (int) (c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime / 1000);
        } catch (PackageManager.NameNotFoundException e) {
            return 1;
        }
    }

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final int version;
    /** Starting and pairing, one at a time. Binder calls that must not wait for them have their own threads. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object serverChanged = new Object();
    private volatile IShellServer server;
    private volatile boolean starting, pairing, pairingLost;
    /** The user stopped the server in the settings: only a request of theirs starts it again, see startByUser. */
    private volatile boolean stoppedByUser;
    private volatile String problem;
    private volatile int pairingPort;
    private Runnable listener;
    private ShellAdb adb;
    /** Told when the server dies. Main thread. */
    private ServiceConnection sessionUser;

    /**
     * Exported, because a broadcast from another uid does not reach a receiver that is not. The permission is what keeps
     * everybody but the shell user out, see ShellServer.
     */
    private final BroadcastReceiver binderReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            Bundle bundle = intent.getBundleExtra(ShellServer.EXTRA_BINDER);
            final IBinder binder = bundle != null ? bundle.getBinder(ShellServer.EXTRA_BINDER) : null;
            final int port = intent.getIntExtra(ShellServer.EXTRA_PORT, 0);
            if (binder != null) new Thread(() -> adopt(binder, port), "xdesk-shell-adopt").start();
        }
    };

    private ShellActivation(Context c) {
        context = c;
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        version = installVersion(c);
        c.registerReceiver(binderReceiver, new IntentFilter(ShellServer.ACTION_BINDER), ShellServer.BINDER_PERMISSION, null, Context.RECEIVER_EXPORTED);
    }

    void setListener(Runnable l) {
        listener = l;
    }

    boolean isPaired() { return prefs.getBoolean(PREF_PAIRED, false); }
    /** Not any more once the user stopped it, even before it is gone. */
    boolean isRunning() { return server != null && !stoppedByUser; }
    boolean isStarting() { return starting; }
    boolean isPairing() { return pairing; }
    /** adbd no longer accepted our key, which is why pairing is asked for again. */
    boolean pairingLost() { return pairingLost; }
    /** What the user has to do before it can start, or null. */
    String problem() { return problem; }
    /** From the pairing service's search, 0 when nothing is found. */
    int pairingPort() { return pairingPort; }

    void setPairingPort(int port) {
        pairingPort = port;
        changed();
    }

    void forgetPairing() {
        prefs.edit().putBoolean(PREF_PAIRED, false).apply();
        problem = null;
        changed();
    }

    /** Main thread. Starts the server if it is paired and none is running. Says what happened through the listener. */
    void start() {
        if (stoppedByUser || !isPaired() || isRunning() || starting || pairing) return;
        starting = true;
        problem = null;
        worker.execute(this::runStart);
    }

    /** Main thread. A fresh attempt: the user may have fixed what the last one complained about. */
    void retry() {
        problem = null;
        start();
    }

    /** Main thread. The user asked for it: also after they stopped the server. */
    void startByUser() {
        stoppedByUser = false;
        retry();
    }

    boolean isStoppedByUser() { return stoppedByUser; }

    /**
     * Main thread. Ends the server, and XDesk does not start it again by itself until the user asks, see startByUser. The
     * server ends its session first, so nothing stays grabbed or routed. Its death comes through lost() as any other.
     */
    void stopServer() {
        stoppedByUser = true;
        problem = null;
        final IShellServer s = server;
        if (s != null) new Thread(() -> {
            try {
                s.exit();
            } catch (RemoteException expected) {
                // exit() never answers
            }
        }, "xdesk-shell-exit").start();
        changed();
    }

    /**
     * Main thread.
     *
     * @param done gets null, or why pairing failed, on the main thread. Starting the server comes after that with its own
     *             outcome, see problem().
     */
    void pair(final int port, final String code, final Consumer<String> done) {
        if (pairing) return;
        pairing = true;
        stoppedByUser = false;
        changed();
        worker.execute(() -> {
            final String error = runPair(port, code);
            if (error == null) {
                pairingLost = false;
                prefs.edit().putBoolean(PREF_PAIRED, true).apply();
                if (!isRunning()) {
                    starting = true;
                    problem = null;
                    runStart();
                }
            }
            ui.post(() -> {
                pairing = false;
                done.accept(error);
                if (error == null) context.stopService(new Intent(context, PairingService.class));
                changed();
            });
        });
    }

    /** Main thread. A new session on the running server, answered on c. Without a server c hears nothing. */
    void openSession(final ServiceConnection c) {
        sessionUser = c;
        open(c, false);
    }

    /** The wireless display's session, next to the app session: its end and the server's death are its owner's business. */
    void openWirelessSession(final ServiceConnection c) {
        open(c, true);
    }

    private void open(final ServiceConnection c, final boolean wireless) {
        final IShellServer s = server;
        if (s == null) return;
        new Thread(() -> {
            try {
                final IBinder injector = (wireless ? s.newWirelessSession() : s.newSession()).asBinder();
                ui.post(() -> c.onServiceConnected(null, injector));
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "new session", e);
            }
        }, "xdesk-shell-session").start();
    }

    /** Main thread. The session is over, so the death of the server is nothing to report. */
    void closeSession() {
        sessionUser = null;
    }

    private void changed() {
        ui.post(() -> {
            if (listener != null) listener.run();
        });
    }

    /**
     * Any thread. Takes the server's Binder, or ends a server from an earlier install.
     *
     * @param port where the server answers knocks, 0 when it did not say. It picks the port itself, so no app can take it first
     */
    private void adopt(final IBinder binder, int port) {
        IShellServer current = server;
        if (current != null && current.asBinder() == binder) return;
        final IShellServer s = IShellServer.Stub.asInterface(binder);
        try {
            if (s.version() != version) {
                Log.i(TAG, "ending a server of another install");
                s.exit();
                return;
            }
            binder.linkToDeath(() -> lost(s), 0);
        } catch (RemoteException e) {
            return;  // an exit() always ends up here too
        }
        if (port > 0) prefs.edit().putInt(PREF_PORT, port).apply();
        server = s;
        problem = null;
        synchronized (serverChanged) {
            serverChanged.notifyAll();
        }
        changed();
    }

    private void lost(IShellServer s) {
        Log.i(TAG, "server died");
        ui.post(() -> {
            if (server != s) return;
            server = null;
            ServiceConnection c = sessionUser;
            sessionUser = null;
            if (c != null) c.onServiceDisconnected(null);
            changed();
        });
    }

    // --- Worker thread ---

    private void runStart() {
        String result;
        try {
            result = tryStart();
        } catch (InterruptedException e) {
            result = context.getString(R.string.xd_shell_interrupted);
        } finally {
            restoreWireless();
        }
        starting = false;
        if (!isRunning()) problem = result;
        changed();
    }

    /** Returns what the user should know when there is still no server, or null. */
    private String tryStart() throws InterruptedException {
        int oldPort = prefs.getInt(PREF_PORT, 0);
        if (oldPort > 0) {
            if (knock(oldPort) && awaitServer(oldPort, ANSWER_WAIT_MS)) return null;
            // No server of ours there: it ended, or something else has the port now
            prefs.edit().remove(PREF_PORT).apply();
        }

        boolean justEnabled = false;
        if (!wirelessOn()) {
            String why = turnWirelessOn();
            if (why != null) return why;
            justEnabled = true;
        }
        int port = ShellAdb.findPort(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT, justEnabled ? FIND_PORT_AFTER_ENABLE_MS : FIND_PORT_MS);
        List<Integer> ports = new ArrayList<>();
        if (port > 0) ports.add(port);
        else ports.addAll(openLoopbackPorts());
        // A scanned port can be the pairing port or another service: its refusal says nothing about our key
        final boolean found = port > 0;
        ports.remove(Integer.valueOf(oldPort));
        if (ports.isEmpty()) return context.getString(R.string.xd_shell_no_port);

        String failure = null;
        for (int candidate : ports.subList(0, Math.min(ports.size(), SCAN_MAX_TRIES))) {
            // Any app can listen on a port here or announce one over mDNS, so first make sure this is adbd asking for TLS
            String identity;
            try {
                identity = AdbTlsProbe.serverIdentity(LOOPBACK, candidate, ShellAdb.API, PROBE_TIMEOUT_MS);
            } catch (IOException e) {
                Log.i(TAG, "port " + candidate + " is not adbd over TLS: " + e.getMessage());
                failure = context.getString(R.string.xd_shell_cannot_connect);
                continue;
            }
            try {
                ShellAdb a = adb();
                a.disconnect();
                if (a.connect(LOOPBACK, candidate)) {
                    launchServer(a);
                    if (!awaitServer(0, SERVER_START_WAIT_MS)) return context.getString(R.string.xd_shell_no_answer);
                    // Only adbd can start a server that reaches XDesk, see ShellServer
                    rememberAdbd(identity);
                    return null;
                }
                failure = context.getString(R.string.xd_shell_cannot_connect);
            } catch (AdbPairingRequiredException | AdbAuthenticationFailedException e) {
                if (!found) {
                    Log.i(TAG, "port " + candidate + " refused us, trying the next", e);
                    failure = context.getString(R.string.xd_shell_cannot_connect);
                    continue;
                }
                if (!isKnownAdbd(identity)) {
                    // adbd started again with a new key, or this is not adbd at all: not enough to forget the pairing
                    Log.w(TAG, "refused by a port that is not the adbd XDesk knows from this boot", e);
                    failure = context.getString(R.string.xd_shell_refused);
                    continue;
                }
                Log.w(TAG, "adbd no longer knows this key", e);
                pairingLost = true;
                prefs.edit().putBoolean(PREF_PAIRED, false).apply();
                return null;
            } catch (java.security.GeneralSecurityException | IOException | RuntimeException e) {
                Log.w(TAG, "connect to port " + candidate, e);
                failure = context.getString(R.string.xd_shell_cannot_connect_detail, e.getMessage());
            } finally {
                disconnectQuietly();
            }
        }
        return failure;
    }

    /**
     * adbd makes a new key each time it starts, at the latest on every boot, so its certificate is only known within one
     * boot. Not known yet in this boot counts as known: the pairing would otherwise never be found lost.
     */
    private boolean isKnownAdbd(String identity) {
        if (prefs.getInt(PREF_ADBD_BOOT, -1) != bootCount()) return true;
        return identity.equals(prefs.getString(PREF_ADBD, identity));
    }

    private void rememberAdbd(String identity) {
        prefs.edit().putString(PREF_ADBD, identity).putInt(PREF_ADBD_BOOT, bootCount()).apply();
    }

    private int bootCount() {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    private ShellAdb adb() throws java.security.GeneralSecurityException, IOException {
        if (adb == null) adb = new ShellAdb(context);
        return adb;
    }

    private void disconnectQuietly() {
        try {
            if (adb != null) adb.disconnect();
        } catch (IOException e) {
            Log.i(TAG, "disconnect", e);
        }
    }

    private String runPair(int port, String code) {
        try {
            // libadb reads the TLS keying material through the platform's hidden Conscrypt class
            if (!HiddenApiBypass.addHiddenApiExemptions(CONSCRYPT_PREFIX)) Log.w(TAG, "hidden API exemption refused");
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "hidden API exemption", e);
        }
        try {
            adb().pair(LOOPBACK, port, code);
            return null;
        } catch (ConnectException e) {
            Log.w(TAG, "pair", e);
            return context.getString(R.string.xd_shell_pair_port_gone);
        } catch (Exception | LinkageError e) {
            Log.w(TAG, "pair", e);
            return context.getString(R.string.xd_shell_pair_failed, e.getMessage());
        } finally {
            disconnectQuietly();
        }
    }

    private boolean wirelessOn() {
        try {
            return Settings.Global.getInt(context.getContentResolver(), SETTING_WIRELESS, 0) == 1;
        } catch (RuntimeException e) {
            Log.i(TAG, "wireless debugging state unknown", e);
            return true;  // looking for the port will tell
        }
    }

    /**
     * Wireless debugging lets every computer paired with this phone in over the network, so it goes off again once the
     * server runs, or the start failed, when XDesk was the one that turned it on. The server does not need it any more.
     */
    private void restoreWireless() {
        if (!prefs.getBoolean(PREF_WIRELESS_OURS, false)) return;
        try {
            Settings.Global.putInt(context.getContentResolver(), SETTING_WIRELESS, 0);
            prefs.edit().remove(PREF_WIRELESS_OURS).apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot turn wireless debugging off again", e);
        }
    }

    /** Returns null when it is on now, otherwise what the user has to do. */
    private String turnWirelessOn() throws InterruptedException {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            return context.getString(R.string.xd_shell_wireless_off);
        }
        if (!wifiConnected()) {
            return context.getString(R.string.xd_shell_need_wifi);
        }
        ContentResolver cr = context.getContentResolver();
        try {
            Settings.Global.putInt(cr, SETTING_WIRELESS, 1);
            prefs.edit().putBoolean(PREF_WIRELESS_OURS, true).apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot write the setting", e);
            return context.getString(R.string.xd_shell_wireless_off);
        }
        // The system puts it back to 0 when there is no Wi-Fi network, and until the user allows a new network
        Thread.sleep(SETTING_SETTLE_MS);
        if (!wirelessOn()) {
            return context.getString(R.string.xd_shell_wireless_refused);
        }
        return null;
    }

    /** Any Wi-Fi network, not the active one: with a VPN running the active network is the VPN. */
    private boolean wifiConnected() {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
        }
        return false;
    }

    /** When service discovery finds nothing. adbd takes a port from the kernel's automatic range. */
    private List<Integer> openLoopbackPorts() throws InterruptedException {
        final List<Integer> open = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(SCAN_THREADS);
        for (int p = SCAN_FIRST_PORT; p <= SCAN_LAST_PORT; p++) {
            final int port = p;
            pool.execute(() -> {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(LOOPBACK, port), SCAN_TIMEOUT_MS);
                    synchronized (open) {
                        open.add(port);
                    }
                } catch (IOException ignored) { }
            });
        }
        pool.shutdown();
        if (!pool.awaitTermination(SCAN_WAIT_S, TimeUnit.SECONDS)) pool.shutdownNow();
        synchronized (open) {
            return new ArrayList<>(open);
        }
    }

    /**
     * Started so that it outlives this connection. adbd only ends the shell it started. The server is a child of a
     * subshell that exits, setsid gives it its own session, and with stdio on /dev/null it has no link to the stream. The marker comes only once the server runs: the stream closes
     * right after it, and with it the shell's session, which used to end a server that had not reached setsid yet. Toybox
     * has setsid and pkill. pkill without -f compares process names, so it does not hit this shell.
     */
    private void launchServer(ShellAdb a) throws IOException, InterruptedException {
        String apk = context.getApplicationInfo().sourceDir;
        String command = "pkill " + ShellServer.NICE_NAME + "; (CLASSPATH='" + apk + "' setsid app_process / --nice-name=" + ShellServer.NICE_NAME
            + " " + ShellServer.class.getName() + " " + context.getApplicationInfo().uid + " " + version
            + " </dev/null >/dev/null 2>&1 &); sleep 1; pidof " + ShellServer.NICE_NAME + " >/dev/null && echo " + STARTED_MARKER;
        AdbStream stream = a.openStream("shell:" + command);
        StringBuilder out = new StringBuilder();
        byte[] buffer = new byte[256];
        long deadline = System.currentTimeMillis() + SHELL_COMMAND_WAIT_MS;
        // Polled, because a read only returns on data or when the stream closes. Closing the stream too early would end the shell
        try {
            while (System.currentTimeMillis() < deadline && !stream.isClosed() && out.indexOf(STARTED_MARKER) < 0) {
                if (stream.available() > 0) {
                    int n = stream.read(buffer, 0, buffer.length);
                    if (n > 0) out.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                } else {
                    Thread.sleep(SHELL_POLL_MS);
                }
            }
        } catch (IOException e) {
            Log.i(TAG, "shell stream ended", e);
        }
        if (out.indexOf(STARTED_MARKER) < 0) Log.w(TAG, "shell said: " + out);
    }

    /** True when the port takes a connection. A server answers that with its broadcast. */
    private boolean knock(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(LOOPBACK, port), KNOCK_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Knocks again now and then in case a broadcast went missing. @param port 0 for a new server, which picks its own */
    private boolean awaitServer(int port, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!isRunning()) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return false;
            synchronized (serverChanged) {
                if (!isRunning()) serverChanged.wait(Math.min(left, KNOCK_EVERY_MS));
            }
            if (!isRunning() && port > 0) knock(port);
        }
        return true;
    }
}
