package com.termux.x11;

import static android.system.Os.getuid;

import android.annotation.SuppressLint;
import android.app.IActivityManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Keep;

import java.io.IOException;

import dalvik.annotation.optimization.CriticalNative;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;

@Keep @SuppressLint({"StaticFieldLeak", "UnsafeDynamicallyLoadedCode"})
public class CmdEntryPoint extends ICmdEntryInterface.Stub {
    public static final String ACTION_START = "com.termux.x11.CmdEntryPoint.ACTION_START";
    public static final String EXTRA_DOCUMENT_TAG = "document_tag";
    static final Handler handler;
    public static Context ctx;
    /**
     * Delays for the broadcasts that say the X server is here when nothing knocks on its port: immediate, then a
     * few with backoff. The server then waits to be knocked on, which is how the app asks for it (see
     * LorieView.requestConnection); broadcasting for its whole life woke XDesk every second with no window open.
     */
    private static final long[] ANNOUNCE_DELAY_MS = {1000, 3000, 10000};
    private final Intent intent = createIntent();
    private boolean broadcastPending;
    private int announcesLeft;
    /** XDesk's uid, the only process allowed to call this Binder. */
    private static int appUid = -2;

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        initEntryPoint();
        android.util.Log.i("CmdEntryPoint", "commit " + BuildConfig.COMMIT);
        handler.post(() -> new CmdEntryPoint(args));
        Looper.loop();
    }

    CmdEntryPoint(String[] args) {
        if (!start(args))
            System.exit(1);
    }

    // Called from native once OsVendorInit() resolves the "-tag" argument, if any.
    void setDocumentTag(String tag) {
        if (tag != null)
            intent.putExtra(EXTRA_DOCUMENT_TAG, tag);
    }

    @SuppressLint({"WrongConstant", "PrivateApi"})
    private Intent createIntent() {
        // We should not care about multiple instances, it should be called only by `Termux:X11` app
        // which is single instance...
        Bundle bundle = new Bundle();
        bundle.putBinder(null, this);

        Intent intent = new Intent(ACTION_START);
        intent.putExtra(null, bundle);
        intent.setPackage(BuildConfig.APPLICATION_ID);

        if (getuid() == 0 || getuid() == 2000)
            intent.setFlags(0x00400000 /* FLAG_RECEIVER_FROM_SHELL */);

        return intent;
    }

    private void sendBroadcast() {
        // Called from native (the X server thread) on every knock on the port; coalesce
        // bursts into a single broadcast fired 250ms later.
        synchronized (this) {
            if (broadcastPending)
                return;
            broadcastPending = true;
        }
        handler.postDelayed(() -> {
            synchronized (this) { broadcastPending = false; }
            sendBroadcast(intent);
        }, 250);
    }

    static void sendBroadcast(Intent intent) {
        try {
            ctx.sendBroadcast(intent);
        } catch (Exception e) {
            if (e instanceof NullPointerException && ctx == null)
                Log.i("Broadcast", "Context is null, falling back to manual broadcasting");
            else
                Log.e("Broadcast", "Falling back to manual broadcasting, failed to broadcast intent through Context:", e);

            String packageName;
            try {
                packageName = android.app.ActivityThread.getPackageManager().getPackagesForUid(getuid())[0];
            } catch (RemoteException ex) {
                throw new RuntimeException(ex);
            }
            IActivityManager am;
            try {
                //noinspection JavaReflectionMemberAccess
                am = (IActivityManager) android.app.ActivityManager.class
                        .getMethod("getService")
                        .invoke(null);
            } catch (Exception e2) {
                try {
                    am = (IActivityManager) Class.forName("android.app.ActivityManagerNative")
                            .getMethod("getDefault")
                            .invoke(null);
                } catch (Exception e3) {
                    throw new RuntimeException(e3);
                }
            }

            assert am != null;
            IIntentSender sender = am.getIntentSender(1, packageName, null, null, 0, new Intent[] { intent },
                    null, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT, null, 0);
            try {
                //noinspection JavaReflectionMemberAccess
                IIntentSender.class
                        .getMethod("send", int.class, Intent.class, String.class, IBinder.class, IIntentReceiver.class, String.class, Bundle.class)
                        .invoke(sender, 0, intent, null, null, new IIntentReceiver.Stub() {
                            @Override public void performReceive(Intent i, int r, String d, Bundle e, boolean o, boolean s, int a) {}
                        }, null, null);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    // In some cases Android Activity part can not connect opened port.
    // In this case opened port works like a lock file.
    private void sendBroadcastDelayed() {
        synchronized (this) { announcesLeft = ANNOUNCE_DELAY_MS.length; }
        announce();
    }

    private void announce() {
        int next;
        synchronized (this) {
            if (announcesLeft <= 0)
                return;
            next = ANNOUNCE_DELAY_MS.length - announcesLeft;
            announcesLeft--;
        }
        if (connected())
            return;
        sendBroadcast(intent);
        if (next < ANNOUNCE_DELAY_MS.length)
            handler.postDelayed(this::announce, ANNOUNCE_DELAY_MS[next]);
    }

    /** The only process that may use this Binder is XDesk itself (or this process); -1 when the package cannot be asked. */
    private static int appUid() {
        if (appUid == -2) {
            try {
                appUid = ctx.getPackageManager().getPackageUid(BuildConfig.APPLICATION_ID, 0);
            } catch (Exception e) {
                // Not known here: as before this check, every caller is let through (a desktop that cannot connect
                // is worse than the check it would have given)
                Log.e("CmdEntryPoint", "Cannot tell XDesk's uid, not checking Binder callers", e);
                appUid = -1;
            }
        }
        return appUid;
    }

    @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        int uid = appUid();
        if (uid != -1 && Binder.getCallingUid() != uid && Binder.getCallingUid() != android.os.Process.myUid())
            throw new SecurityException("not XDesk");
        return super.onTransact(code, data, reply, flags);
    }

    private static LocalSocket openXdesx(String id) throws IOException {
        if (Binder.getCallingUid() != appUid() || id == null || !id.matches("[a-f0-9]{32}"))
            throw new SecurityException("invalid XDesX window");
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress("/data/data/com.termux/files/usr/tmp/xdesx-" + id,
                    LocalSocketAddress.Namespace.FILESYSTEM));
            return socket;
        } catch (IOException e) { socket.close(); throw e; }
    }

    @Override public ParcelFileDescriptor xdesxSocket(String id) {
        try (LocalSocket socket = openXdesx(id)) {
            return ParcelFileDescriptor.dup(socket.getFileDescriptor());
        } catch (IOException e) { throw new IllegalStateException(e.toString()); }
    }

    private static native int sendXdesxBuffer(android.hardware.HardwareBuffer buffer, int fd);
    private static final class FrameLibrary {
        static final boolean loaded = NativeLibs.load(CmdEntryPoint.class.getClassLoader(), "xdesk_android_frame");
    }
    @Override public void xdesxFrame(String id, android.hardware.HardwareBuffer buffer) {
        try (android.hardware.HardwareBuffer frame = buffer; LocalSocket socket = openXdesx(id);
             ParcelFileDescriptor fd = ParcelFileDescriptor.dup(socket.getFileDescriptor())) {
            socket.setSoTimeout(3000);
            if (!FrameLibrary.loaded) throw new IOException("missing AHardwareBuffer sender");
            java.io.DataOutputStream out = new java.io.DataOutputStream(socket.getOutputStream());
            out.writeLong(((long) frame.getWidth() << 32) | frame.getHeight());
            if (sendXdesxBuffer(frame, fd.getFd()) != 0 || socket.getInputStream().read() != 1)
                throw new IOException("X client did not import the Android frame");
        } catch (IOException e) { throw new IllegalStateException(e.toString()); }
    }

    /** @noinspection DataFlowIssue*/
    @SuppressLint("DiscouragedPrivateApi")
    public static Context createContext() {
        Context context;
        PrintStream err = System.err;
        try {
            java.lang.reflect.Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            // Hiding harmless framework errors, like this:
            // java.io.FileNotFoundException: /data/system/theme_config/theme_compatibility.xml: open failed: ENOENT (No such file or directory)
            System.setErr(new PrintStream(new OutputStream() { public void write(int arg0) {} }));
            if (System.getenv("OLD_CONTEXT") != null) {
                context = android.app.ActivityThread.systemMain().getSystemContext();
            } else {
                context = ((android.app.ActivityThread) Class.
                        forName("sun.misc.Unsafe").
                        getMethod("allocateInstance", Class.class).
                        invoke(unsafe, android.app.ActivityThread.class))
                        .getSystemContext();
            }
        } catch (Exception e) {
            Log.e("Context", "Failed to instantiate context:", e);
            context = null;
        } finally {
            System.setErr(err);
        }
        return context;
    }

    public native boolean start(String[] args);
    public native ParcelFileDescriptor getXConnection();
    public native ParcelFileDescriptor getLogcatOutput();
    public native void reportFatalError(String message);

    /** Connects to PulseAudio's socket in Termux's private directory, which the app itself cannot reach. */
    @Override public ParcelFileDescriptor getAudioConnection() {
        String path = System.getenv("XDESK_AUDIO_SOCKET");
        if (path == null) return null;
        try (LocalSocket s = new LocalSocket()) {
            s.connect(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
            return ParcelFileDescriptor.dup(s.getFileDescriptor());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
    // XDesk: Linux programs as apps, see XWindows. Binder threads.
    @Override public ParcelFileDescriptor getAppCatalog() { return XWindows.catalog(); }
    @Override public String launchApp(String key, String startupId) { return XWindows.launch(key, startupId); }
    @Override public boolean isDescendant(int pid, int ancestor) { return XWindows.isDescendant(pid, ancestor); }
    @Override public void setWindowListener(IXWindowListener listener) { XWindows.setListener(listener); }
    @Override public void windowCommand(int window, int command) { XWindows.command(window, command); }
    @Override public void setScale(int dpi) { XWindows.scale(dpi); }
    @Override public String serverBuild() { return buildId(); }
    @Override public String launchStatus(int pid) { return XWindows.launchStatus(pid); }

    /**
     * This build's code: the app compares the X server's with its own (XServerBuild). An X server started before XDesk
     * was updated keeps running the old APK's code, which does not work with the new app (a black desktop).
     */
    public static String buildId() { return BuildConfig.VERSION_NAME + " " + BuildConfig.COMMIT; }

    @CriticalNative private static native boolean connected();

    static {
        try {
            if (Looper.getMainLooper() == null)
                Looper.prepareMainLooper();
        } catch (Exception e) {
            Log.e("CmdEntryPoint", "Something went wrong when preparing MainLooper", e);
        }
        handler = new Handler();
    }

    private static void initEntryPoint() {
        ctx = createContext();

        ClassLoader loader = CmdEntryPoint.class.getClassLoader();
        try {
            NativeLibs.load(loader, "Xlorie");
        } catch (Exception | UnsatisfiedLinkError e) {
            Log.e("CmdEntryPoint", "Failed to dlopen libXlorie", e);
            System.err.println("Failed to load native library. Did you install the right apk? Try the universal one.");
            System.exit(134);
        }
    }
}
