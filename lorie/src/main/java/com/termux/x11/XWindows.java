package com.termux.x11;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

/**
 * The X server's side of Linux programs as apps (DISPLAY.md §1.2, §1.5), in the X server process, which runs as
 * Termux and can read its files and processes, unlike the app. Reached through ICmdEntryInterface (CmdEntryPoint).
 * The windows come from xwindows.c, inside the X server: no X client, nothing runs while nobody listens.
 */
@Keep
final class XWindows {
    private static final String TAG = "XDeskWindows";
    static final int ACTIVATE = 1, FULLSCREEN_ON = 2, FULLSCREEN_OFF = 3, CLOSE = 4;
    private static final String PREFIX = env("PREFIX", "/data/data/com.termux/files/usr");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._+@-]{1,100}(/[A-Za-z0-9._+@-]{1,200})?");
    private static final Pattern STARTUP_ID = Pattern.compile("[A-Za-z0-9._+@-]{0,200}");

    private static IXWindowListener listener;
    private static IBinder.DeathRecipient death;

    private XWindows() { }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v != null && !v.isEmpty() ? v : fallback;
    }

    /** The xdesk that started this X server (it passes itself), else the installed one. */
    private static String[] xdesk(String... args) {
        String cli = env("XDESK_CLI", PREFIX + "/bin/xdesk");
        String[] argv = new String[args.length + 2];
        argv[0] = PREFIX + "/bin/bash";
        argv[1] = cli;
        System.arraycopy(args, 0, argv, 2, args.length);
        return argv;
    }

    static synchronized void setListener(IXWindowListener l) {
        if (listener != null && death != null) {
            try { listener.asBinder().unlinkToDeath(death, 0); } catch (RuntimeException ignored) { }
        }
        listener = l;
        death = null;
        if (l != null) {
            final IXWindowListener mine = l;
            death = () -> {
                synchronized (XWindows.class) {
                    if (listener != mine) return;
                    listener = null;
                }
                nativeSetWanted(false);
            };
            try {
                l.asBinder().linkToDeath(death, 0);
            } catch (RemoteException e) {
                listener = null;
                death = null;
            }
        }
        nativeSetWanted(listener != null);
    }

    private static synchronized IXWindowListener listener() {
        return listener;
    }

    // From xwindows.c, on the X server thread. Oneway calls: they never wait for the app.
    @Keep static void onWindows(byte[] snapshot) {
        IXWindowListener l = listener();
        if (l == null) return;
        try { l.windows(snapshot); } catch (RemoteException | RuntimeException e) { Log.w(TAG, "windows: " + e); }
    }

    @Keep static void onIcon(int window, int width, int height, int[] argb) {
        IXWindowListener l = listener();
        if (l == null) return;
        try { l.icon(window, width, height, argb); } catch (RemoteException | RuntimeException e) { Log.w(TAG, "icon: " + e); }
    }

    static void command(int window, int command) {
        if (command >= ACTIVATE && command <= CLOSE) nativeCommand(window, command);
    }

    static ParcelFileDescriptor catalog() {
        String path = null;
        try {
            Process p = new ProcessBuilder(xdesk("apps", "--path")).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) if (line.startsWith("/")) path = line.trim();
            }
            p.waitFor();
            if (path == null) return null;
            return ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException | InterruptedException | RuntimeException e) {
            Log.w(TAG, "catalog", e);
            return null;
        }
    }

    /** "pid" or "!reason". */
    static String launch(String key, String startupId) {
        if (key == null || !KEY.matcher(key).matches()) return "!bad key";
        if (startupId == null || !STARTUP_ID.matcher(startupId).matches()) startupId = "";
        try {
            String[] argv = startupId.isEmpty() ? xdesk("launch", "--", key) : xdesk("launch", "--startup-id", startupId, "--", key);
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            pb.environment().remove("CLASSPATH");
            Process p = pb.start();
            String last = "";
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                // xdesk prints "pid N" and then writes only to its log, so this ends without waiting for the program
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.startsWith("pid ")) return line.substring(4).trim();
                    if (!line.trim().isEmpty()) last = line.trim();
                }
            }
            return "!" + (last.isEmpty() ? "xdesk launch failed" : last);
        } catch (IOException | RuntimeException e) {
            return "!" + e.getMessage();
        }
    }

    /**
     * xdesk scale: XFCE follows the X DPI. xdesk waits for the session itself in the background, so this only starts
     * it; a oneway call must not hold up the ones after it on this binder.
     */
    static void scale(int dpi) {
        if (dpi < 30 || dpi > 1000) return;
        new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(xdesk("scale", String.valueOf(dpi))).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(new File("/dev/null")));
                pb.environment().remove("CLASSPATH");
                pb.start().waitFor();
            } catch (IOException | InterruptedException | RuntimeException e) {
                Log.w(TAG, "scale", e);
            }
        }, "xdesk-scale").start();
    }

    static boolean isDescendant(int pid, int ancestor) {
        for (int i = 0; i < 64 && pid > 1; i++) {
            if (pid == ancestor) return true;
            pid = parent(pid);
        }
        return pid == ancestor && pid > 0;
    }

    private static int parent(int pid) {
        try {
            String stat = new String(Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath()), StandardCharsets.UTF_8);
            // pid (comm) state ppid ...; comm may contain spaces and parentheses
            String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return Integer.parseInt(f[1]);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static native void nativeSetWanted(boolean on);
    private static native void nativeCommand(int window, int command);
}
