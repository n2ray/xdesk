package com.termux.x11;

import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * The shell-user process for people without Shizuku: ShellActivation runs this through app_process over wireless
 * debugging. It stays after a session ends and after XDesk dies, because starting it again may need Wi-Fi. Between
 * sessions it holds nothing: every grab, sound route and density belongs to an InjectorService, which ends with its session.
 *
 * This process has more rights than any app, so two doors are closed:
 * - The Binder only reaches XDesk. It goes out in a broadcast limited to XDesk's package, and XDesk's receiver only
 *   takes broadcasts from a sender holding BINDER_PERMISSION, which no third party app can get. Nobody else can feed XDesk
 *   a fake one either.
 * - Whoever got the Binder anyway can do nothing: this class and InjectorService refuse every transaction whose
 *   caller is not XDesk's uid, in onTransact, which all IPC goes through.
 *
 * A running XDesk asks for the Binder by connecting to a loopback port. Any app can do that, and all it causes is the
 * same broadcast to XDesk, so the socket reads nothing and carries nothing. A refused connection also tells XDesk straight
 * away that no server is running. The kernel picks the port and the broadcast says which, so no app can take it first
 * and answer in the server's place.
 */
public final class ShellServer extends IShellServer.Stub {
    static final String ACTION_BINDER = "com.termux.x11.ShellServer.BINDER";
    static final String EXTRA_BINDER = "binder", EXTRA_PORT = "port";
    /** Held by the shell user and not grantable to apps: signature|installer. Shizuku's provider relies on the same one. */
    static final String BINDER_PERMISSION = "android.permission.INTERACT_ACROSS_USERS_FULL";
    static final String NICE_NAME = "xdesk_shell";

    private static final String TAG = "XDeskShell";
    private static final String WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS";
    private static final long APK_CHECK_MS = 5000, MIN_RESEND_MS = 500, PM_TIMEOUT_MS = 10000;
    /** A new server cannot be knocked at before XDesk knows its port, so it sends its Binder a few times by itself. */
    private static final long[] FIRST_SENDS_MS = {2000, 5000, 10000};
    private static final int KNOCK_BACKLOG = 4;

    private final int appUid, version;
    private final Intent binderIntent = new Intent(ACTION_BINDER);
    private InjectorService session;
    /** The wireless display's own session, which must survive app sessions starting and ending. */
    private InjectorService wirelessSession;
    private long lastSent;

    /** Arguments: XDesk's uid and its install version. They come from XDesk over its own adb connection. */
    public static void main(String[] args) {
        if (args.length != 2) {
            Log.e(TAG, "expected uid and version");
            System.exit(2);
        }
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        matchShizukuEnvironment();
        CmdEntryPoint.ctx = CmdEntryPoint.createContext();
        ShellServer server = new ShellServer(Integer.parseInt(args[0]), Integer.parseInt(args[1]));
        server.exitWhenApkGoes(System.getenv("CLASSPATH"));
        server.listenForKnocks();
        new Thread(ShellServer::grantSecureSettings, "xdesk-grant").start();
        server.sendBinder();
        Handler handler = new Handler(Looper.getMainLooper());
        for (long ms : FIRST_SENDS_MS) handler.postDelayed(server::sendBinder, ms);
        Looper.loop();
    }

    /** InjectorService was developed inside Shizuku's user service, which has an attached system ActivityThread. */
    private static void matchShizukuEnvironment() {
        try {
            Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "no system ActivityThread", e);
        }
    }

    private ShellServer(int appUid, int version) {
        this.appUid = appUid;
        this.version = version;
        Bundle bundle = new Bundle();
        bundle.putBinder(EXTRA_BINDER, this);
        binderIntent.putExtra(EXTRA_BINDER, bundle);
        binderIntent.setPackage(BuildConfig.APPLICATION_ID);
        // FLAG_RECEIVER_FROM_SHELL as in CmdEntryPoint. Foreground: while XDesk waits in the background, during pairing in
        // Settings, HyperOS held a normal broadcast back for 20 s
        binderIntent.setFlags(0x00400000 | Intent.FLAG_RECEIVER_FOREGROUND);
    }

    @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (Binder.getCallingUid() != appUid) throw new SecurityException("not XDesk");
        return super.onTransact(code, data, reply, flags);
    }

    @Override public int version() {
        return version;
    }

    @Override public synchronized IInjector newSession() {
        if (session != null) session.shutdown();
        session = new InjectorService(appUid);
        return session;
    }

    @Override public synchronized IInjector newWirelessSession() {
        if (wirelessSession != null) wirelessSession.shutdown();
        wirelessSession = new InjectorService(appUid);
        return wirelessSession;
    }

    /** Gives back what the session holds first: the sound route and the density outlive this process. */
    @Override public void exit() {
        synchronized (this) {
            if (session != null) session.shutdown();
            if (wirelessSession != null) wirelessSession.shutdown();
        }
        System.exit(0);
    }

    /** Its last fallback throws. XDesk knocks again, so that must not end this process. */
    private void sendBinder() {
        try {
            CmdEntryPoint.sendBroadcast(binderIntent);
        } catch (RuntimeException e) {
            Log.w(TAG, "broadcast failed", e);
        }
    }

    /** On a port the kernel picks, before the first broadcast, which carries it. */
    private void listenForKnocks() {
        final ServerSocket socket;
        try {
            socket = new ServerSocket(0, KNOCK_BACKLOG, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            // XDesk will not find this process after a restart, so it starts a new one and that one kills this one
            Log.w(TAG, "no knock port", e);
            return;
        }
        binderIntent.putExtra(EXTRA_PORT, socket.getLocalPort());
        new Thread(() -> {
            try {
                while (true) {
                    socket.accept().close();
                    long now = SystemClock.uptimeMillis();
                    if (now - lastSent < MIN_RESEND_MS) continue;
                    lastSent = now;
                    sendBinder();
                }
            } catch (IOException e) {
                Log.w(TAG, "knock port closed", e);
            }
        }, "xdesk-knock").start();
    }

    /**
     * An update or uninstall removes the file this process runs from. XDesk would refuse this version anyway, this is for
     * when XDesk is not opened again. Only watched if it can be seen at all from here.
     */
    private void exitWhenApkGoes(String path) {
        if (path == null || !new File(path).exists()) {
            Log.w(TAG, "cannot watch " + path);
            return;
        }
        final File apk = new File(path);
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!apk.exists()) {
                    Log.i(TAG, "the APK is gone, exiting");
                    System.exit(0);
                }
                handler.postDelayed(this, APK_CHECK_MS);
            }
        }, APK_CHECK_MS);
    }

    /** Lets XDesk turn wireless debugging on after a reboot. Some systems refuse this, and XDesk then says what to do. */
    private static void grantSecureSettings() {
        String[] r = InjectorService.exec(PM_TIMEOUT_MS, "/system/bin/pm", "grant", BuildConfig.APPLICATION_ID, WRITE_SECURE_SETTINGS);
        if (r[0] != null) Log.w(TAG, "not granted: " + r[0]);
    }
}
