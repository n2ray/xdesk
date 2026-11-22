package com.termux.x11;

import com.termux.x11.app.R;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;

/**
 * XDesk's built-in Linux: a Debian 13 + XFCE image run with Burrow (lib/libburrow.so, a seccomp + SIGSYS
 * container runtime) in the app's own files, without Termux. The app starts its own X server (CmdEntryPoint through
 * app_process, as the xdesk command does in Termux) with its socket in the container's /tmp, then the session.
 *
 * Layout under files/linux: rootfs (the system, replaced by a reset), home (the container's /root, kept by a reset
 * unless everything is removed), cache (the downloaded image, so a reset needs no network), *.log.
 */
final class BuiltinLinux {
    private static final String TAG = "BuiltinLinux";
    static final String IMAGE = "xdesk-debian13-arm64.tar.gz";
    /** Where the image comes from when it is not on the phone yet. Not published yet: the test image is copied in. */
    static final String IMAGE_URL = "https://github.com/n2ray/xdesk/releases/download/linux-debian13-r1/" + IMAGE;
    private static final String X_NAME = "xdesk-builtin-x";
    private static final String DISPLAY = ":1";
    private static final String PREFS = "builtin_linux", PREF_GPU = "gpu";
    private static final int X_WAIT_MS = 20000;

    enum State { NOT_INSTALLED, INSTALLING, STOPPED, STARTING, RUNNING, STOPPING }

    interface Listener { void changed(); }

    private static BuiltinLinux instance;

    private final Context c;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    final File base, rootfs, home, cache;
    private volatile State state;
    private volatile String progress, error;
    private volatile boolean busy;
    /** Opens the desktop on the external screen once it runs (XDeskActivity sets it while it lives). */
    static volatile Runnable attachHook;

    static synchronized BuiltinLinux get(Context c) {
        if (instance == null) instance = new BuiltinLinux(c.getApplicationContext());
        return instance;
    }

    private BuiltinLinux(Context c) {
        this.c = c;
        base = new File(c.getFilesDir(), "linux");
        rootfs = new File(base, "rootfs");
        home = new File(base, "home");
        cache = new File(base, "cache");
        state = installed() ? (running() ? State.RUNNING : State.STOPPED) : State.NOT_INSTALLED;
    }

    static final String DEBUG_ACTION = "io.github.n2ray.xdesk.BUILTIN_LINUX";
    private static boolean debugRegistered;

    /**
     * For testing over adb: am broadcast -a io.github.n2ray.xdesk.BUILTIN_LINUX --es cmd install|start|stop|reset|state.
     * Only the shell and the system hold the permission the receiver asks for.
     */
    static synchronized void registerDebug(Context ctx) {
        if (debugRegistered) return;
        debugRegistered = true;
        final BuiltinLinux bl = get(ctx);
        android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context x, Intent i) {
                String cmd = i.getStringExtra("cmd");
                if ("install".equals(cmd)) bl.install();
                else if ("start".equals(cmd)) bl.start();
                else if ("stop".equals(cmd)) bl.stop();
                else if ("reset".equals(cmd)) bl.reset(true);
                else if ("gpu-on".equals(cmd)) bl.setGpu(true);
                else if ("gpu-off".equals(cmd)) bl.setGpu(false);
                Log.i(TAG, "debug " + cmd + ": " + bl.state() + " " + bl.progress() + " " + bl.error());
            }
        };
        try {
            bl.c.registerReceiver(r, new android.content.IntentFilter(DEBUG_ACTION), "android.permission.INTERACT_ACROSS_USERS_FULL",
                    null, Context.RECEIVER_EXPORTED);
        } catch (RuntimeException e) {
            Log.w(TAG, "debug receiver", e);
        }
    }

    void addListener(Listener l) { listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }
    private void changed() { main.post(() -> { for (Listener l : listeners) l.changed(); }); }

    State state() {
        // A desktop that ended by itself (logged out, crashed) or was killed with the app
        if (!busy) {
            State now = installed() ? (running() ? State.RUNNING : State.STOPPED) : State.NOT_INSTALLED;
            if (now != state) state = now;
        }
        return state;
    }
    String progress() { return progress; }
    String error() { return error; }
    boolean installed() { return new File(rootfs, ".xdesk-ok").exists(); }

    boolean gpu() {
        return c.getSharedPreferences(PREFS, 0).getBoolean(PREF_GPU, new File("/dev/kgsl-3d0").exists());
    }
    void setGpu(boolean on) { c.getSharedPreferences(PREFS, 0).edit().putBoolean(PREF_GPU, on).apply(); }

    /** The image already on the phone: a copy put in the app's external files (tests), or the downloaded one. */
    private File localImage() {
        File ext = c.getExternalFilesDir(null);
        if (ext != null && new File(ext, IMAGE).isFile()) return new File(ext, IMAGE);
        File cached = new File(cache, IMAGE);
        return cached.isFile() ? cached : null;
    }
    boolean imageCached() { return localImage() != null; }

    private void set(State s, String p) {
        state = s;
        progress = p;
        Log.i(TAG, s + (p != null ? " " + p : ""));
        changed();
    }

    private boolean begin() {
        synchronized (this) {
            if (busy) return false;
            busy = true;
        }
        error = null;
        return true;
    }

    private void end(State s) {
        busy = false;
        set(s, null);
    }

    private void fail(String why, Throwable t) {
        Log.e(TAG, why, t);
        error = why + (t != null && t.getMessage() != null ? ": " + t.getMessage() : "");
        appendLog("ERROR " + error);
    }

    // ---- Install, reset, remove ----

    void install() {
        if (!begin()) return;
        set(State.INSTALLING, c.getString(R.string.xd_bl_preparing));
        new Thread(() -> {
            boolean ok = false;
            try {
                ok = installNow();
            } catch (Throwable t) {
                fail(c.getString(R.string.xd_bl_install_failed), t);
            }
            end(ok ? State.STOPPED : State.NOT_INSTALLED);
        }, "builtin-linux-install").start();
    }

    private boolean installNow() throws IOException {
        File image = localImage();
        if (image == null) image = download();
        if (image == null) return false;
        File tmp = new File(base, "rootfs.new");
        rm(tmp);
        if (!tmp.mkdirs()) throw new IOException("mkdir " + tmp);
        extract(image, tmp);
        prepare(tmp);
        rm(rootfs);
        if (!tmp.renameTo(rootfs)) throw new IOException("rename " + tmp);
        if (!home.isDirectory()) {
            home.mkdirs();
            // The container's own /root (.bashrc, .profile) is where a new home starts from
            run(null, "/system/bin/cp", "-a", new File(rootfs, "root").getPath() + "/.", home.getPath());
        }
        new FileOutputStream(new File(rootfs, ".xdesk-ok")).close();
        return true;
    }

    private File download() throws IOException {
        cache.mkdirs();
        File part = new File(cache, IMAGE + ".part"), done = new File(cache, IMAGE);
        String sum = null;
        try {
            sum = new String(fetch(IMAGE_URL + ".sha256"), StandardCharsets.US_ASCII).trim().split("\\s+")[0];
        } catch (IOException e) {
            Log.w(TAG, "no checksum", e);
        }
        long have = part.length();
        HttpURLConnection conn = (HttpURLConnection) new URL(IMAGE_URL).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");
        int code = conn.getResponseCode();
        if (code != 200 && code != 206) throw new IOException("HTTP " + code);
        if (code == 200) have = 0;
        long total = conn.getContentLengthLong();
        if (total > 0) total += have;
        try (InputStream in = conn.getInputStream(); RandomAccessFile out = new RandomAccessFile(part, "rw")) {
            out.seek(have);
            byte[] buf = new byte[1 << 16];
            long got = have, last = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                got += n;
                long now = System.currentTimeMillis();
                if (now - last > 500) {
                    last = now;
                    set(State.INSTALLING, total > 0
                        ? c.getString(R.string.xd_bl_downloading_pct, got * 100 / total, mb(got), mb(total))
                        : c.getString(R.string.xd_bl_downloading, mb(got)));
                }
            }
        }
        if (sum != null) {
            set(State.INSTALLING, c.getString(R.string.xd_bl_checking));
            if (!sum.equalsIgnoreCase(sha256(part))) {
                part.delete();
                throw new IOException(c.getString(R.string.xd_bl_bad_checksum));
            }
        }
        if (!part.renameTo(done)) throw new IOException("rename " + part);
        return done;
    }

    private static byte[] fetch(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
        try (InputStream in = conn.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String mb(long bytes) { return String.valueOf(bytes / (1024 * 1024)); }

    /** What a new system gets before its first start. */
    private void prepare(File root) throws IOException {
        new File(root, "tmp").mkdirs();
        Os_chmod(new File(root, "tmp"), 01777);
    }

    /** Resets the system to the image (no download when the image is kept); the home stays unless {@code keepHome} is false. */
    void reset(final boolean keepHome) {
        if (!begin()) return;
        set(State.INSTALLING, c.getString(R.string.xd_bl_resetting));
        new Thread(() -> {
            boolean ok = false;
            try {
                stopNow();
                rm(rootfs);
                if (!keepHome) rm(home);
                ok = installNow();
            } catch (Throwable t) {
                fail(c.getString(R.string.xd_bl_install_failed), t);
            }
            end(ok ? State.STOPPED : State.NOT_INSTALLED);
        }, "builtin-linux-reset").start();
    }

    /** Removes everything: system, home, the kept image. */
    void removeAll() {
        if (!begin()) return;
        set(State.STOPPING, c.getString(R.string.xd_bl_removing));
        new Thread(() -> {
            try {
                stopNow();
                rm(base);
                File ext = c.getExternalFilesDir(null);
                if (ext != null) new File(ext, IMAGE).delete();
            } catch (Throwable t) {
                fail(c.getString(R.string.xd_bl_remove_failed), t);
            }
            end(State.NOT_INSTALLED);
        }, "builtin-linux-remove").start();
    }

    /** Space used under files/linux, in MB, or -1 (a du over the whole tree: call off the main thread). */
    long usageMb() {
        String out = runOut("/system/bin/du", "-sk", base.getPath());
        try {
            return Long.parseLong(out.trim().split("\\s+")[0]) / 1024;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ---- Start and stop ----

    void start() {
        if (!installed() || !begin()) return;
        set(State.STARTING, c.getString(R.string.xd_bl_starting_x));
        final boolean gpu = gpu();
        new Thread(() -> {
            boolean ok = false;
            try {
                ok = startNow(gpu);
            } catch (Throwable t) {
                fail(c.getString(R.string.xd_bl_start_failed), t);
            }
            if (!ok) {
                stopNow();
                end(State.STOPPED);
                return;
            }
            end(State.RUNNING);
            Runnable attach = attachHook;
            if (attach != null) main.post(attach);
        }, "builtin-linux-start").start();
    }

    private boolean startNow(boolean gpu) throws IOException, InterruptedException {
        try {
            c.startForegroundService(new Intent(c, LinuxService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "no foreground service", e);
        }
        stopNow();
        File tmp = new File(rootfs, "tmp");
        tmp.mkdirs();
        File socket = new File(tmp, ".X11-unix/X1");
        socket.delete();
        new File(tmp, ".X1-lock").delete();
        writeResolvConf();

        ApplicationInfo ai = c.getApplicationInfo();
        ProcessBuilder x = new ProcessBuilder("/system/bin/app_process", "-Xnoimage-dex2oat", "/",
                "--nice-name=" + X_NAME, "com.termux.x11.CmdEntryPoint", DISPLAY, "-ac", "-nolisten", "tcp",
                "-nolisten", "local");
        x.environment().remove("LD_PRELOAD");
        x.environment().put("CLASSPATH", ai.sourceDir);
        x.environment().put("TMPDIR", tmp.getPath());
        x.environment().put("XKB_CONFIG_ROOT", new File(rootfs, "usr/share/X11/xkb").getPath());
        x.environment().put("XDESK_AUDIO_SOCKET", new File(tmp, "xdesk-audio").getPath());
        x.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(new File(logDir(), "x.log")));
        appendLog("starting X");
        Process xp = x.start();
        long deadline = System.currentTimeMillis() + X_WAIT_MS;
        while (!socket.exists()) {
            if (!xp.isAlive()) {
                fail(c.getString(R.string.xd_bl_x_failed, xp.exitValue()), null);
                return false;
            }
            if (System.currentTimeMillis() > deadline) {
                fail(c.getString(R.string.xd_bl_x_timeout), null);
                return false;
            }
            Thread.sleep(200);
        }

        set(State.STARTING, c.getString(R.string.xd_bl_starting_desktop));
        writeSessionScript();
        String lang = Locale.getDefault().getLanguage().equals("zh") ? "zh_CN.UTF-8" : "en_US.UTF-8";
        List<String> cmd = new ArrayList<>();
        cmd.add(burrow());
        cmd.add("-r"); cmd.add(rootfs.getPath());
        cmd.add("-b"); cmd.add("/dev");
        cmd.add("-b"); cmd.add("/proc");
        cmd.add("-b"); cmd.add("/sys");
        cmd.add("-b"); cmd.add(home.getPath() + ":/root");
        cmd.add("-w"); cmd.add("/root");
        cmd.add("--");
        cmd.add("/usr/bin/env"); cmd.add("-i");
        cmd.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        cmd.add("HOME=/root"); cmd.add("USER=root"); cmd.add("LOGNAME=root"); cmd.add("SHELL=/bin/bash");
        cmd.add("TERM=xterm-256color");
        cmd.add("LANG=" + lang); cmd.add("LANGUAGE=" + lang.substring(0, 5) + ":en");
        cmd.add("DISPLAY=" + DISPLAY);
        cmd.add("XDESK_GPU=" + (gpu ? "1" : "0"));
        cmd.add("/bin/sh"); cmd.add("/usr/local/bin/xdesk-session");
        ProcessBuilder s = new ProcessBuilder(cmd);
        s.environment().remove("LD_PRELOAD");
        s.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(new File(logDir(), "session.log")));
        appendLog("starting the session (gpu " + gpu + ")");
        Process sp = s.start();
        Thread.sleep(1500);
        if (!sp.isAlive()) {
            fail(c.getString(R.string.xd_bl_session_failed, sp.exitValue()), null);
            return false;
        }
        return true;
    }

    /** The session script comes with the app (assets/linux), so changing it needs no new image. */
    private void writeSessionScript() throws IOException {
        File f = new File(rootfs, "usr/local/bin/xdesk-session");
        f.getParentFile().mkdirs();
        f.delete();
        try (InputStream in = c.getAssets().open("linux/xdesk-session"); OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
        Os_chmod(f, 0755);
    }

    private String burrow() { return c.getApplicationInfo().nativeLibraryDir + "/libburrow.so"; }

    /** The phone's DNS servers for the container (Android has no resolv.conf of its own). */
    private void writeResolvConf() {
        StringBuilder sb = new StringBuilder();
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n != null ? cm.getLinkProperties(n) : null;
            if (lp != null)
                for (InetAddress a : lp.getDnsServers()) sb.append("nameserver ").append(a.getHostAddress()).append('\n');
        } catch (RuntimeException e) {
            Log.w(TAG, "dns", e);
        }
        if (sb.length() == 0) sb.append("nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        File f = new File(rootfs, "etc/resolv.conf");
        f.delete();
        try (FileWriter w = new FileWriter(f)) {
            w.write(sb.toString());
        } catch (IOException e) {
            Log.w(TAG, "resolv.conf", e);
        }
    }

    void stop() {
        if (!begin()) return;
        set(State.STOPPING, null);
        new Thread(() -> {
            stopNow();
            c.stopService(new Intent(c, LinuxService.class));
            end(installed() ? State.STOPPED : State.NOT_INSTALLED);
        }, "builtin-linux-stop").start();
    }

    /** Ends the session and the X server: every process of ours running Burrow or named as the X server. */
    private void stopNow() {
        List<Integer> pids = ours();
        if (pids.isEmpty()) return;
        appendLog("stopping " + pids.size() + " processes");
        for (int p : pids) kill(p, OsConstants.SIGTERM);
        for (int i = 0; i < 20 && !ours().isEmpty(); i++) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        for (int p : ours()) kill(p, OsConstants.SIGKILL);
    }

    boolean running() {
        for (int p : ours()) if (isX(p)) return true;
        return false;
    }

    private static void kill(int pid, int sig) {
        try { Os.kill(pid, sig); } catch (ErrnoException ignored) { }
    }

    /** Our uid's processes that are the built-in Linux: Burrow runs every program in the container as itself. */
    private List<Integer> ours() {
        List<Integer> out = new ArrayList<>();
        String burrow = burrow();
        int me = android.os.Process.myPid();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return out;
        for (File p : procs) {
            int pid;
            try { pid = Integer.parseInt(p.getName()); } catch (NumberFormatException e) { continue; }
            if (pid == me) continue;
            String exe;
            try { exe = Os.readlink("/proc/" + pid + "/exe"); } catch (ErrnoException e) { exe = ""; }
            if (exe.equals(burrow) || exe.endsWith("/libburrow.so") || isX(pid)) out.add(pid);
        }
        return out;
    }

    private static boolean isX(int pid) {
        try (FileInputStream in = new FileInputStream("/proc/" + pid + "/cmdline")) {
            byte[] b = new byte[64];
            int n = in.read(b);
            return n > 0 && new String(b, 0, n, StandardCharsets.UTF_8).startsWith(X_NAME);
        } catch (IOException e) {
            return false;
        }
    }

    // ---- Logs ----

    /** The logs, in the app's external files (Android/data/.../files/logs) so they can be read and shared. */
    File logDir() {
        File d = c.getExternalFilesDir("logs");
        if (d == null) d = base;
        d.mkdirs();
        return d;
    }

    private void appendLog(String line) {
        try (FileWriter w = new FileWriter(new File(logDir(), "xdesk.log"), true)) {
            w.write(new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new java.util.Date()) + " " + line + "\n");
        } catch (IOException ignored) { }
    }

    /** The last lines of the app's, the X server's and the session's logs, for the log page. */
    String logTail() {
        StringBuilder sb = new StringBuilder();
        for (String name : new String[]{"xdesk.log", "x.log", "session.log"}) {
            sb.append("== ").append(name).append('\n');
            sb.append(tail(new File(logDir(), name), 60)).append('\n');
        }
        return sb.toString();
    }

    private static String tail(File f, int lines) {
        ArrayList<String> all = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String l; (l = r.readLine()) != null; ) {
                all.add(l);
                if (all.size() > lines) all.remove(0);
            }
        } catch (IOException e) {
            return "";
        }
        return String.join("\n", all);
    }

    // ---- Files ----

    /** Removes a tree without following its symlinks (the container's are full of absolute ones). */
    private void rm(File f) {
        if (!f.exists() && !isLink(f)) return;
        // Read-only directories (a package may leave some) would stop rm: open them first
        run(null, "/system/bin/chmod", "-R", "u+rwX", f.getPath());
        run(null, "/system/bin/rm", "-rf", f.getPath());
    }

    private static boolean isLink(File f) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(f.getPath()).st_mode);
        } catch (ErrnoException e) {
            return false;
        }
    }

    private static int run(File dir, String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start();
            drain(p.getInputStream());
            return p.waitFor();
        } catch (IOException | InterruptedException e) {
            return -1;
        }
    }

    private static String runOut(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
            p.waitFor();
            return out;
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    private static void drain(InputStream in) throws IOException {
        byte[] buf = new byte[4096];
        while (in.read(buf) > 0) { }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void Os_chmod(File f, int mode) {
        try { Os.chmod(f.getPath(), mode); } catch (ErrnoException ignored) { }
    }

    /**
     * Unpacks a .tar.gz (ustar, GNU long names, pax paths) into {@code dest}: directories, files and symlinks with
     * their permission bits. Owners are not kept (all files are the app's; Burrow shows them as root's), hard links
     * become copies, devices and fifos are skipped (the container binds the real /dev).
     */
    private void extract(File image, File dest) throws IOException {
        long size = image.length();
        final long[] read = {0};
        InputStream raw = new BufferedInputStream(new FileInputStream(image), 1 << 16) {
            @Override public synchronized int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) read[0] += n;
                return n;
            }
        };
        String destPath = dest.getCanonicalPath();
        List<String[]> dirModes = new ArrayList<>();
        try (InputStream in = new BufferedInputStream(new GZIPInputStream(raw, 1 << 16), 1 << 16)) {
            byte[] h = new byte[512];
            String longName = null, longLink = null, paxPath = null, paxLink = null;
            long entries = 0, lastShown = 0;
            while (true) {
                if (!readFully(in, h)) break;
                if (allZero(h)) break;
                String name = str(h, 0, 100);
                int mode = (int) octal(h, 100, 8);
                long len = octal(h, 124, 12);
                char type = (char) h[156];
                String link = str(h, 157, 100);
                String prefix = str(h, 345, 155);
                if (!prefix.isEmpty() && "ustar".equals(str(h, 257, 5))) name = prefix + "/" + name;
                if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                    byte[] data = new byte[(int) len];
                    if (!readFully(in, data)) throw new IOException("truncated");
                    skip(in, pad(len));
                    if (type == 'L') longName = str(data, 0, data.length);
                    else if (type == 'K') longLink = str(data, 0, data.length);
                    else if (type == 'x') {
                        String[] pl = pax(data);
                        if (pl[0] != null) paxPath = pl[0];
                        if (pl[1] != null) paxLink = pl[1];
                    }
                    continue;
                }
                if (longName != null) name = longName;
                if (paxPath != null) name = paxPath;
                if (longLink != null) link = longLink;
                if (paxLink != null) link = paxLink;
                longName = longLink = paxPath = paxLink = null;

                while (name.startsWith("./")) name = name.substring(2);
                while (name.startsWith("/")) name = name.substring(1);
                if (name.isEmpty() || name.equals(".")) { skip(in, len + pad(len)); continue; }
                File out = new File(dest, name);
                String outPath = out.getPath();
                if (!outPath.startsWith(destPath + "/") || name.contains("/../") || name.startsWith("../"))
                    throw new IOException("bad path " + name);
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();

                switch (type) {
                    case '5':
                        out.mkdirs();
                        dirModes.add(new String[]{outPath, Integer.toString(mode)});
                        skip(in, len + pad(len));
                        break;
                    case '2':
                        out.delete();
                        try { Os.symlink(link, outPath); } catch (ErrnoException e) { throw new IOException("symlink " + name, e); }
                        skip(in, len + pad(len));
                        break;
                    case '1': {
                        File target = new File(dest, link.startsWith("./") ? link.substring(2) : link);
                        out.delete();
                        copy(target, out);
                        Os_chmod(out, mode & 0777);
                        skip(in, len + pad(len));
                        break;
                    }
                    case '0': case '\0': case '7': {
                        out.delete();
                        try (OutputStream o = new FileOutputStream(out)) {
                            byte[] buf = new byte[1 << 16];
                            long left = len;
                            while (left > 0) {
                                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                                if (n < 0) throw new IOException("truncated " + name);
                                o.write(buf, 0, n);
                                left -= n;
                            }
                        }
                        Os_chmod(out, (mode & 0777) | 0600);
                        skip(in, pad(len));
                        break;
                    }
                    default:
                        // Character and block devices, fifos: the container binds the real /dev
                        skip(in, len + pad(len));
                }
                entries++;
                long now = System.currentTimeMillis();
                if (now - lastShown > 300) {
                    lastShown = now;
                    set(State.INSTALLING, c.getString(R.string.xd_bl_unpacking, size > 0 ? read[0] * 100 / size : 0, entries));
                }
            }
        }
        // Directories last: a read-only one would have stopped its own files. The owner can always write and enter
        for (int i = dirModes.size() - 1; i >= 0; i--)
            try { Os.chmod(dirModes.get(i)[0], (Integer.parseInt(dirModes.get(i)[1]) & 07777) | 0700); } catch (ErrnoException ignored) { }
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream i = new FileInputStream(from); OutputStream o = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = i.read(buf)) > 0; ) o.write(buf, 0, n);
        }
    }

    private static String[] pax(byte[] data) {
        String[] out = new String[2];
        int i = 0;
        while (i < data.length) {
            int sp = i;
            while (sp < data.length && data[sp] != ' ') sp++;
            if (sp >= data.length) break;
            int len;
            try { len = Integer.parseInt(new String(data, i, sp - i, StandardCharsets.US_ASCII)); } catch (NumberFormatException e) { break; }
            if (len <= 0 || i + len > data.length) break;
            String rec = new String(data, sp + 1, i + len - sp - 2, StandardCharsets.UTF_8);
            int eq = rec.indexOf('=');
            if (eq > 0) {
                String k = rec.substring(0, eq), v = rec.substring(eq + 1);
                if (k.equals("path")) out[0] = v;
                else if (k.equals("linkpath")) out[1] = v;
            }
            i += len;
        }
        return out;
    }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) {
                if (off == 0) return false;
                throw new IOException("truncated");
            }
            off += n;
        }
        return true;
    }

    private static void skip(InputStream in, long n) throws IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new IOException("truncated");
            n -= r;
        }
    }

    private static long pad(long len) { return (512 - (len % 512)) % 512; }

    private static boolean allZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && end < b.length && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int len) {
        // GNU base-256 for big numbers
        if ((b[off] & 0x80) != 0) {
            long v = 0;
            for (int i = off + 1; i < off + len; i++) v = (v << 8) | (b[i] & 0xff);
            return v;
        }
        long v = 0;
        int i = off, end = off + len;
        while (i < end && b[i] == ' ') i++;
        for (; i < end && b[i] >= '0' && b[i] <= '7'; i++) v = v * 8 + (b[i] - '0');
        return v;
    }
}
