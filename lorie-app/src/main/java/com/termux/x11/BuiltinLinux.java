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
 * XDesk's built-in Linux: an Ubuntu 26.04 LTS + XFCE image run with Burrow (lib/libburrow.so, a seccomp + SIGSYS
 * container runtime) in the app's own files, without Termux. The app starts its own X server (CmdEntryPoint through
 * app_process, as the xdesk command does in Termux) with its socket in the container's /tmp, then the session.
 *
 * Layout under files/linux: rootfs (the system, replaced by a reset), home (the user xdesk's /home/xdesk, kept by a reset
 * unless everything is removed), cache (the downloaded image, so a reset needs no network), *.log.
 */
final class BuiltinLinux {
    private static final String TAG = "BuiltinLinux";
    static final String IMAGE = "xdesx-ubuntu-apps-arm64.tar.gz";
    /**
     * Where the image comes from when it is not on the phone yet: n2ray/xdesk-apt's releases (Actions → Image there).
     * A new installation starts from it; from then on the system follows xdesk-core (xdesk-update), not new images.
     */
    static final String IMAGE_URL = "https://github.com/n2ray/xdesk/releases/download/xdesx-ubuntu-apps-r1/" + IMAGE;
    private static final String X_NAME = "xdesk-builtin-x";
    private static final String DISPLAY = ":1";
    /** termux-va's MediaCodec service, packaged as a "library" so it is installed executable. */
    private static final String VIDEO_HELPER = "libtermux_va.so";
    private static final String PREFS = "builtin_linux", PREF_GPU = "gpu";
    /** SSH access (docs/design-ssh-access.md in n2ray/xdesk-linux): on or off, also from the network, the public keys. */
    private static final String PREF_SSH = "ssh", PREF_SSH_LAN = "ssh_lan", PREF_SSH_KEYS = "ssh_keys";
    static final int SSH_PORT = 8222;
    private static final int X_WAIT_MS = 20000;
    /** xdesk-update: how long a start waits for the system's XDesk package (it starts the desktop either way) */
    private static final long UPDATE_WAIT_MS = 5 * 60 * 1000;

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
    private volatile boolean downloading, downloadCancelled;
    private HttpURLConnection downloadConnection;

    boolean canCancelDownload() { return downloading && !downloadCancelled; }
    boolean downloadCancelled() { return downloadCancelled; }

    void cancelDownload() {
        HttpURLConnection connection;
        synchronized (this) {
            if (!canCancelDownload()) return;
            downloadCancelled = true;
            connection = downloadConnection;
        }
        changed();
        if (connection != null) connection.disconnect();
    }

    private synchronized HttpURLConnection openDownloadConnection(String url) throws IOException {
        if (downloadCancelled) throw new IOException("下载已取消");
        return downloadConnection = (HttpURLConnection) new URL(url).openConnection();
    }

    /** Opens the desktop on the external screen once it runs (XDeskActivity sets it while it lives). */
    static volatile Runnable attachHook;

    static synchronized BuiltinLinux get(Context c) {
        if (instance == null) instance = new BuiltinLinux(c.getApplicationContext());
        return instance;
    }

    private BuiltinLinux(Context c) {
        this.c = c;
        // The real path: /data/user/0 is a link to /data/data, and the kernel's getcwd gives the real one, which Burrow
        // must find among its bindings (with the link, every getcwd in the container failed)
        File files = c.getFilesDir();
        try { files = files.getCanonicalFile(); } catch (IOException ignored) { }
        base = new File(files, "linux");
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
                else if ("start".equals(cmd)) bl.start(i.getBooleanExtra("measure", false), false);
                else if ("start-apps".equals(cmd)) bl.start(false, true);
                else if ("desktop".equals(cmd)) bl.showDesktop();
                else if ("stop".equals(cmd)) bl.stop();
                else if ("reset".equals(cmd)) bl.reset(true);
                else if ("gpu-on".equals(cmd)) bl.setGpu(true);
                else if ("gpu-off".equals(cmd)) bl.setGpu(false);
                else if ("bench".equals(cmd)) bl.bench();
                else if ("run".equals(cmd)) bl.runDebugScript(i.getBooleanExtra("stats", false));
                // Screen Sharing's codec test (HEVC fallback): avc, hevc-config-fail, hevc-no-output; none for normal
                else if ("ss-codec".equals(cmd)) {
                    String m = i.getStringExtra("mode");
                    com.termux.x11.screenshare.ScreenShareSession.testCodec = m == null || m.equals("none") ? null : m;
                    Log.i(TAG, "screen sharing codec test: " + com.termux.x11.screenshare.ScreenShareSession.testCodec);
                }
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
        downloadCancelled = false;
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
            // The system's /etc/skel (.bashrc, .profile) is where the user xdesk's new home starts from
            File skel = new File(rootfs, "etc/skel");
            run(null, "/system/bin/cp", "-a", (skel.isDirectory() ? skel : new File(rootfs, "root")).getPath() + "/.", home.getPath());
        }
        new FileOutputStream(new File(rootfs, ".xdesk-ok")).close();
        return true;
    }

    private File download() throws IOException {
        cache.mkdirs();
        File part = new File(cache, IMAGE + ".part"), done = new File(cache, IMAGE);
        synchronized (this) {
            downloadCancelled = false;
            downloading = true;
        }
        changed();
        String sum;
        try {
            sum = new String(fetch(IMAGE_URL + ".sha256"), StandardCharsets.US_ASCII).trim().split("\\s+")[0];
            if (!sum.matches("[a-fA-F0-9]{64}")) throw new IOException("Ubuntu 镜像校验信息无效");
            transferImage(part);
        } catch (IOException e) {
            if (downloadCancelled) return null;
            throw e;
        } finally {
            synchronized (this) {
                downloading = false;
                downloadConnection = null;
                changed();
            }
        }
        if (downloadCancelled) return null;
        set(State.INSTALLING, c.getString(R.string.xd_bl_checking));
        if (!sum.equalsIgnoreCase(sha256(part))) {
            part.delete();
            throw new IOException(c.getString(R.string.xd_bl_bad_checksum));
        }
        if (!part.renameTo(done)) throw new IOException("rename " + part);
        return done;
    }

    private void transferImage(File part) throws IOException {
        long have = part.length();
        HttpURLConnection conn = openDownloadConnection(IMAGE_URL);
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");
        try {
            int code = conn.getResponseCode();
            if (code == 416 && have > 0) return; // A completed partial file is verified by the caller.
            if (downloadCancelled) return;
            if (code != 200 && code != 206) throw new IOException("HTTP " + code);
            if (code == 200) have = 0;
            long total = conn.getContentLengthLong();
            if (total > 0) total += have;
            try (InputStream in = conn.getInputStream(); RandomAccessFile out = new RandomAccessFile(part, "rw")) {
                out.setLength(have);
                out.seek(have);
                byte[] buf = new byte[1 << 16];
                long got = have, last = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (downloadCancelled) return;
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
        } finally { conn.disconnect(); }
    }

    private byte[] fetch(String url) throws IOException {
        HttpURLConnection conn = openDownloadConnection(url);
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        try {
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally { conn.disconnect(); }
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
        userStopAt = android.os.SystemClock.elapsedRealtime();
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
        userStopAt = android.os.SystemClock.elapsedRealtime();
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

    /** The whole desktop (XFCE with its panel). */
    void start() { start(false, false); }

    /** "Apps only": X, the window manager and the settings daemon, for programs opened from XDesk's launcher. */
    void startApps() { start(false, true); }

    private void start(boolean measurePresent, boolean appsOnly) {
        if (!installed() || !begin()) return;
        desktopWanted = !appsOnly;
        desktopCheckedAt = 0;
        set(State.STARTING, c.getString(R.string.xd_bl_starting_x));
        final boolean gpu = gpu();
        new Thread(() -> {
            boolean ok = false;
            try {
                ok = startNow(gpu, measurePresent, appsOnly);
            } catch (Throwable t) {
                fail(c.getString(R.string.xd_bl_start_failed), t);
            }
            if (!ok) {
                stopNow();
                end(State.STOPPED);
                return;
            }
            end(State.RUNNING);
            desktopCheckedAt = 0;
            // The whole desktop was asked for while "apps only" started
            if (appsOnly && desktopWanted) showDesktop();
            // SSH access on: it comes with the desktop (kept if it was already running alone)
            startSsh();
            Runnable attach = attachHook;
            if (attach != null) main.post(attach);
        }, "builtin-linux-start").start();
    }

    private boolean startNow(boolean gpu, boolean measurePresent, boolean appsOnly) throws IOException, InterruptedException {
        try {
            c.startForegroundService(new Intent(c, LinuxService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "no foreground service", e);
        }
        stopNow(true);
        File tmp = new File(rootfs, "tmp");
        tmp.mkdirs();
        File socket = new File(tmp, ".X11-unix/X1");
        socket.delete();
        new File(tmp, ".X1-lock").delete();
        // The last session's launch pipe: xdesk-launch waits for this session's own (xdesk-session)
        new File(tmp, "xdesk-launch/req").delete();
        writeResolvConf();
        updateSystem();
        // Android's /dev has no shm: Chromium (and glibc's shm_open) need one, emptied at each start
        File shm = shm();
        rm(shm);
        shm.mkdirs();
        Os_chmod(shm, 01777);

        ApplicationInfo ai = c.getApplicationInfo();
        ProcessBuilder x = new ProcessBuilder("/system/bin/app_process", "-Xnoimage-dex2oat", "/",
                "--nice-name=" + X_NAME, "com.termux.x11.CmdEntryPoint", DISPLAY, "-ac", "-nolisten", "tcp",
                "-nolisten", "local");
        x.environment().remove("LD_PRELOAD");
        x.environment().put("CLASSPATH", ai.sourceDir);
        x.environment().put("TMPDIR", tmp.getPath());
        x.environment().put("XKB_CONFIG_ROOT", new File(rootfs, "usr/share/X11/xkb").getPath());
        x.environment().put("XDESK_AUDIO_SOCKET", new File(tmp, "xdesk-audio").getPath());
        // Its programs for XDesk's launcher (XWindows, BuiltinApps): read from the system's files, opened in it
        writeAsset("xdesk-launch");
        x.environment().put("XDESK_BUILTIN_ROOT", rootfs.getPath());
        x.environment().put("XDESK_BUILTIN_HOME", home.getPath());
        x.environment().put("XDESK_BUILTIN_LAUNCH", android.text.TextUtils.join("\n", containerCmd("/usr/local/bin/xdesk-launch")));
        x.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(new File(logDir(), "x.log")));
        appendLog("starting X");
        if (measurePresent) x.environment().put("TERMUX_X11_MISURA", "1");
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

        startVideoHelper(tmp);
        set(State.STARTING, c.getString(R.string.xd_bl_starting_desktop));
        writeAsset("xdesk-session");
        // The desktop runs as xdesk (xdesk-asuser says how; sudo comes with xdesk-core)
        writeAsset("xdesk-asuser");
        ProcessBuilder s = new ProcessBuilder(containerCmd("/usr/local/bin/xdesk-session", "XDESK_APPS_ONLY=" + (appsOnly ? 1 : 0)));
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

    /**
     * Hardware video decoding for the container: termux-va's MediaCodec service (built from lfdevs/termux-va into
     * lib/libtermux_va.so), run as the app so it may use the phone's decoders. It listens on the socket Mesa's
     * termuxva VA-API driver looks for by default, /tmp/termux-va/termux-va.sock; stopNow ends it with the rest.
     */
    Process startVideoHelper(File tmp) {
        File helper = new File(c.getApplicationInfo().nativeLibraryDir, VIDEO_HELPER);
        if (!helper.canExecute()) return null;
        File dir = new File(tmp, "termux-va");
        dir.mkdirs();
        new File(dir, "termux-va.sock").delete();
        try {
            ProcessBuilder b = new ProcessBuilder(helper.getPath(), "--sock", dir.getPath(), "-t");
            b.environment().remove("LD_PRELOAD");
            b.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(new File(logDir(), "video.log")));
            Process service = b.start();
            appendLog("video decoding service started");
            return service;
        } catch (IOException e) {
            Log.w(TAG, "video helper", e);
            return null;
        }
    }

    /** The command line that runs a shell script inside the container, with the session's environment. */
    private List<String> containerCmd(String script, String... env) {
        return containerCmd(null, script, env);
    }

    List<String> xdesxCommand(File desktop, String script) {
        return containerCmd(desktop, "/usr/local/bin/" + script, "XDESX_SHARED_X=1");
    }

    void prepareXdesx(File desktop) throws IOException, InterruptedException {
        if (!installed()) throw new IOException("请先安装 Ubuntu 核心");
        File tmp = new File(desktop, "linux-tmp");
        new File(tmp, ".X11-unix").mkdirs();
        Os_chmod(tmp, 01777);
        shm().mkdirs();
        Os_chmod(shm(), 01777);
        writeResolvConf();
        updateSystem(false);
        writeAsset("xdesk-asuser");
        writeAsset("xdesk-session");
        writeAsset("xdesk-launch");
        writeAsset("xdesx-immodules.cache");
        File module=new File(rootfs,"usr/local/lib/xdesx/im-vte-anchor.so");
        module.getParentFile().mkdirs();
        java.nio.file.Files.copy(new File(desktop,"guest/im-vte-anchor.so").toPath(),module.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private List<String> containerCmd(File desktop, String script, String... env) {
        List<String> cmd = new ArrayList<>();
        cmd.add(burrow());
        cmd.add("-r"); cmd.add(rootfs.getPath());
        cmd.add("-b"); cmd.add("/dev");
        cmd.add("-b"); cmd.add(shm().getPath() + ":/dev/shm");
        cmd.add("-b"); cmd.add("/proc");
        cmd.add("-b"); cmd.add("/sys");
        // The home XDesk keeps (a reset leaves it) is the user xdesk's; root's own /root is the system's
        new File(rootfs, "home/xdesk").mkdirs();  // the binding's place, in a system from before too
        cmd.add("-b"); cmd.add(home.getPath() + ":/home/xdesk");
        File shared = XdesxFiles.sharedRoot(c);
        if (shared != null) {
            new File(rootfs, "mnt/xdesx").mkdirs();
            cmd.add("-b"); cmd.add(shared.getPath() + ":/mnt/xdesx");
            try {
                android.system.Os.symlink("/mnt/xdesx", new File(home, "xdesx").getPath());
            } catch (android.system.ErrnoException e) {
                if (e.errno != android.system.OsConstants.EEXIST) Log.w(TAG, "Home shortcut", e);
            }
        }
        if (desktop != null) {
            // APT stages absolute links to guest archives here before calling dpkg --recursive.
            cmd.add("-B"); cmd.add(new File(desktop, "linux-tmp").getPath() + ":/tmp");
            cmd.add("-b"); cmd.add(new File(desktop, "tmp/.X11-unix").getPath() + ":/tmp/.X11-unix");
        }
        cmd.add("-w"); cmd.add("/root");
        cmd.add("--");
        cmd.add("/usr/bin/env"); cmd.add("-i");
        cmd.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        cmd.add("HOME=/root"); cmd.add("USER=root"); cmd.add("LOGNAME=root"); cmd.add("SHELL=/bin/bash");
        cmd.add("TERM=xterm-256color");
        // English; the system's own locale (update-locale, /etc/default/locale) wins in xdesk-session
        cmd.add("LANG=en_US.UTF-8");
        cmd.add("DISPLAY=" + (desktop == null ? DISPLAY : ":19"));
        cmd.add("XDESK_GPU=" + (gpu() ? "1" : "0"));
        for (String e : env) cmd.add(e);
        cmd.add("/bin/sh"); cmd.add(script);
        return cmd;
    }

    /**
     * Debug only (the shell's broadcast): runs the bundled benchmark inside the running desktop's container, the
     * same one run against native Termux, and writes logs/bench.log.
     */
    void bench() {
        if (state() != State.RUNNING) { Log.w(TAG, "bench: desktop not running"); return; }
        new Thread(() -> {
            try {
                writeAsset("xdesk-bench");
                ProcessBuilder b = new ProcessBuilder(containerCmd("/usr/local/bin/xdesk-bench"));
                b.environment().remove("LD_PRELOAD");
                b.redirectErrorStream(true).redirectOutput(new File(logDir(), "bench.log"));
                Log.i(TAG, "bench done " + b.start().waitFor());
            } catch (Exception e) {
                Log.w(TAG, "bench", e);
            }
        }, "builtin-linux-bench").start();
    }

    /**
     * Debug only (the shell's broadcast): runs debug.sh from the app's external files (where the shell can put it)
     * inside the container with the session's environment, output in logs/run.log.
     */
    void runDebugScript(boolean countSyscalls) {
        File src = new File(c.getExternalFilesDir(null), "debug.sh");
        if (!src.isFile()) { Log.w(TAG, "run: no " + src); return; }
        new Thread(() -> {
            try {
                File dst = new File(rootfs, "tmp/xdesk-debug.sh");
                dst.getParentFile().mkdirs();
                try (InputStream in = new java.io.FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                }
                List<String> cmd = containerCmd("/tmp/xdesk-debug.sh");
                if (countSyscalls) {
                    // Burrow aggregates this run and its child processes in a shared counter file.
                    File stats = File.createTempFile("burrow-", ".stats", logDir());
                    cmd.add(1, "-s");
                    cmd.add(2, stats.getAbsolutePath());
                }
                ProcessBuilder b = new ProcessBuilder(cmd);
                b.environment().remove("LD_PRELOAD");
                b.redirectErrorStream(true).redirectOutput(new File(logDir(), "run.log"));
                Log.i(TAG, "run done " + b.start().waitFor());
            } catch (Exception e) {
                Log.w(TAG, "run", e);
            }
        }, "builtin-linux-run").start();
    }

    /**
     * XDesk's side of the system (the package xdesk-core) at the version this app needs: xdesk-update checks, and
     * installs from XDesk's repository when it is older. Quick when nothing is to do; while it installs, the status
     * says so. Whatever happens the desktop starts (a system without the package runs as before).
     */
    private void updateSystem() throws IOException, InterruptedException {
        updateSystem(true);
    }

    /** @param shows whether the state says it is updating (a desktop's start); SSH alone changes no state */
    private void updateSystem(boolean shows) throws IOException, InterruptedException {
        writeAsset("xdesk-update");
        File marker = new File(rootfs, "tmp/xdesk-updating");
        marker.delete();
        ProcessBuilder u = new ProcessBuilder(containerCmd("/usr/local/bin/xdesk-update"));
        u.environment().remove("LD_PRELOAD");
        u.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(new File(logDir(), "update.log")));
        Process p = u.start();
        long deadline = System.currentTimeMillis() + UPDATE_WAIT_MS;
        boolean shown = false;
        try {
            while (!p.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (shows && !shown && marker.exists()) {
                    shown = true;
                    set(State.STARTING, c.getString(R.string.xd_bl_updating));
                }
                if (System.currentTimeMillis() > deadline) {
                    appendLog("xdesk-update: still running after " + UPDATE_WAIT_MS / 1000 + " s, starting without it");
                    p.destroy();
                    break;
                }
            }
        } catch (InterruptedException interrupted) {
            p.destroy();
            throw interrupted;
        }
        marker.delete();
        if (shown) set(State.STARTING, c.getString(R.string.xd_bl_starting_x));
        if (!p.isAlive() && p.exitValue() != 0) appendLog("xdesk-update: exit " + p.exitValue());
    }

    /** Scripts come with the app (assets/linux), so changing them needs no new image. */
    private void writeAsset(String name) throws IOException {
        File f = new File(rootfs, "usr/local/bin/" + name);
        f.getParentFile().mkdirs();
        f.delete();
        try (InputStream in = c.getAssets().open("linux/" + name); OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
        Os_chmod(f, 0755);
    }

    private File shm() { return new File(base, "shm"); }

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

    /**
     * When the user last stopped, reset or removed the system: its X then ends on purpose. XDesk heard only Termux's
     * "xdesk stop" (DesktopSession), so every stop of the built-in Linux read as a crash (24 November).
     */
    private volatile long userStopAt;

    /** The built-in Linux's X ending now is the user's doing: they stopped, reset or removed it a moment ago. */
    boolean stoppedByUserRecently() {
        return userStopAt != 0 && android.os.SystemClock.elapsedRealtime() - userStopAt < 20000;
    }

    void stop() {
        userStopAt = android.os.SystemClock.elapsedRealtime();
        if (!begin()) return;
        set(State.STOPPING, null);
        new Thread(() -> {
            // With SSH access on, stopping the desktop leaves SSH running: the way in without a screen (owner,
            // 23 November). Its own switch stops it; reset and remove stop everything
            boolean keepSsh = sshEnabled() && sshRunning();
            stopNow(keepSsh);
            if (!keepSsh) c.stopService(new Intent(c, LinuxService.class));
            end(installed() ? State.STOPPED : State.NOT_INSTALLED);
        }, "builtin-linux-stop").start();
    }

    /** Ends the session and the X server: every process of ours running Burrow or named as the X server. */
    private void stopNow() {
        stopNow(false);
    }

    /**
     * As stopNow(); keepSsh leaves the SSH server and its sessions running (the desktop restarting, not the system
     * stopping).
     */
    private void stopNow(boolean keepSsh) {
        List<Integer> pids = ours(keepSsh);
        if (pids.isEmpty()) return;
        appendLog("stopping " + pids.size() + " processes" + (keepSsh ? " (SSH kept)" : ""));
        for (int p : pids) kill(p, OsConstants.SIGTERM);
        for (int i = 0; i < 20 && !ours(keepSsh).isEmpty(); i++) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        for (int p : ours(keepSsh)) kill(p, OsConstants.SIGKILL);
    }

    private List<Integer> ours(boolean keepSsh) {
        List<Integer> out = ours();
        if (keepSsh) out.removeIf(BuiltinLinux::isSsh);
        return out;
    }

    // ---- SSH access (the one way in to manage the system; n2ray/xdesk-linux docs/design-ssh-access.md) ----

    boolean sshEnabled() { return c.getSharedPreferences(PREFS, 0).getBoolean(PREF_SSH, false); }
    boolean sshLan() { return c.getSharedPreferences(PREFS, 0).getBoolean(PREF_SSH_LAN, false); }

    /** On: the server starts now (alone, or next to the desktop). Off: it stops, with its sessions. */
    void setSsh(boolean on) {
        if (on) {
            c.getSharedPreferences(PREFS, 0).edit().putBoolean(PREF_SSH, true).apply();
            startSsh();
        } else {
            // Off once its processes are gone, not before (owner, 24 November): the page says it is stopping meanwhile
            stopSsh(() -> c.getSharedPreferences(PREFS, 0).edit().putBoolean(PREF_SSH, false).apply());
        }
    }

    /** SSH between states: starting (until sshd runs) or stopping (until its processes are gone); else IDLE. */
    static final int SSH_IDLE = 0, SSH_STARTING = 1, SSH_STOPPING = 2;
    private volatile int sshPhase = SSH_IDLE;

    int sshPhase() { return sshPhase; }

    private void setSshPhase(int p) {
        sshPhase = p;
        changed();
    }

    /** sshd itself is up (not only Burrow's process that starts it). */
    private boolean sshdUp() {
        for (int p : ours()) if (cmdline(p).startsWith("sshd")) return true;
        return false;
    }

    /** Also from the local network and Tailscale (else 127.0.0.1 only): a running server restarts with it. */
    void setSshLan(boolean on) {
        c.getSharedPreferences(PREFS, 0).edit().putBoolean(PREF_SSH_LAN, on).apply();
        if (sshRunning()) restartSsh();
    }

    /** The public keys allowed in, one per line as in authorized_keys. */
    List<String> sshKeys() {
        List<String> out = new ArrayList<>();
        for (String k : c.getSharedPreferences(PREFS, 0).getString(PREF_SSH_KEYS, "").split("\n"))
            if (!k.trim().isEmpty()) out.add(k.trim());
        return out;
    }

    /** Adds a public key (one line, "type base64 [comment]"); false when it is not one. */
    boolean addSshKey(String key) {
        String k = key.trim().replaceAll("\\s+", " ");
        if (!k.matches("(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)|sk-ssh-ed25519@openssh\\.com|sk-ecdsa-sha2-nistp256@openssh\\.com) [A-Za-z0-9+/=]{20,}( .*)?"))
            return false;
        List<String> keys = sshKeys();
        if (!keys.contains(k)) keys.add(k);
        saveSshKeys(keys);
        return true;
    }

    void removeSshKey(String key) {
        List<String> keys = sshKeys();
        keys.remove(key);
        saveSshKeys(keys);
    }

    private void saveSshKeys(List<String> keys) {
        c.getSharedPreferences(PREFS, 0).edit().putString(PREF_SSH_KEYS, String.join("\n", keys)).apply();
        // A running server reads the file at each login: the change counts at once
        try {
            writeSshKeys();
        } catch (IOException e) {
            Log.w(TAG, "ssh keys", e);
        }
        changed();
    }

    private void writeSshKeys() throws IOException {
        if (!installed()) return;
        File dir = new File(rootfs, "etc/xdesk/ssh");
        dir.mkdirs();
        File f = new File(dir, "authorized_keys");
        try (FileWriter w = new FileWriter(f)) {
            for (String k : sshKeys()) w.write(k + "\n");
        }
        Os_chmod(f, 0600);
    }

    boolean sshRunning() {
        for (int p : ours()) if (isSsh(p)) return true;
        return false;
    }

    /** Starts the SSH server if it is on and not running: with the desktop, or alone (the system in the background). */
    void startSsh() {
        if (!installed() || !sshEnabled() || sshRunning() || sshPhase != SSH_IDLE) return;
        setSshPhase(SSH_STARTING);
        new Thread(() -> {
            try {
                try {
                    c.startForegroundService(new Intent(c, LinuxService.class));
                } catch (RuntimeException e) {
                    Log.w(TAG, "no foreground service", e);
                }
                writeResolvConf();
                // Without a desktop (no screen, SSH alone) the system is kept current all the same: xdesk-core at the
                // version this app needs (quick when it is; after a desktop start it already is)
                try {
                    updateSystem(false);
                } catch (IOException | InterruptedException e) {
                    Log.w(TAG, "update before SSH", e);
                }
                writeSshKeys();
                writeAsset("xdesk-sshd");
                ProcessBuilder b = new ProcessBuilder(containerCmd("/usr/local/bin/xdesk-sshd",
                    "XDESK_SSH_PORT=" + SSH_PORT, "XDESK_SSH_LAN=" + (sshLan() ? "1" : "0")));
                b.environment().remove("LD_PRELOAD");
                b.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(new File(logDir(), "ssh.log")));
                Process sshd = b.start();
                // Starting until sshd itself runs (or its script gave up: no openssh-server, a bad configuration)
                for (int i = 0; i < 100 && !sshdUp() && sshd.isAlive(); i++) Thread.sleep(100);
                appendLog(sshdUp() ? "SSH started (port " + SSH_PORT + (sshLan() ? ", network" : ", this phone only") + ")"
                    : "SSH did not start (ssh.log says why)");
            } catch (IOException | InterruptedException e) {
                fail(c.getString(R.string.xd_bl_ssh_failed), e);
            } finally {
                setSshPhase(SSH_IDLE);
            }
        }, "builtin-linux-ssh").start();
    }

    /** Stops the SSH server and its sessions; the desktop stays. Without a desktop the app stops keeping it alive. */
    void stopSsh() {
        stopSsh(null);
    }

    /**
     * Stopping until every SSH process is gone: TERM, up to 5 s, then KILL and up to 2 s more. @param then runs once
     * they are (on this thread), before the page is told.
     */
    private void stopSsh(final Runnable then) {
        if (sshPhase == SSH_STOPPING) return;
        setSshPhase(SSH_STOPPING);
        new Thread(() -> {
            try {
                boolean any = endSsh();
                if (any) appendLog("SSH stopped");
                if (!running()) c.stopService(new Intent(c, LinuxService.class));
                if (then != null) then.run();
            } finally {
                setSshPhase(SSH_IDLE);
            }
        }, "builtin-linux-ssh-stop").start();
    }

    /** Ends the SSH server and its sessions, waiting for them; true when there were any. */
    private boolean endSsh() {
        List<Integer> pids = new ArrayList<>();
        for (int p : ours()) if (isSsh(p)) pids.add(p);
        if (pids.isEmpty()) return false;
        for (int p : pids) kill(p, OsConstants.SIGTERM);
        for (int i = 0; i < 50 && sshRunning(); i++) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        for (int p : ours()) if (isSsh(p)) kill(p, OsConstants.SIGKILL);
        for (int i = 0; i < 20 && sshRunning(); i++) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        return true;
    }

    /** Running SSH again with the keys and settings of now (the page's Restart, the network switch). */
    void restartSsh() {
        if (sshPhase != SSH_IDLE) return;
        setSshPhase(SSH_STOPPING);
        new Thread(() -> {
            endSsh();
            appendLog("SSH restarting");
            sshPhase = SSH_IDLE;
            startSsh();
        }, "builtin-linux-ssh-restart").start();
    }

    /**
     * The SSH server or one of its sessions: its launcher (Burrow running xdesk-sshd), sshd itself ("sshd ..." or
     * "sshd: ..." as its title), or anything started under one of them.
     */
    private static boolean isSsh(int pid) {
        for (int i = 0, p = pid; i < 12 && p > 1; i++) {
            String cmd = cmdline(p);
            if (cmd.contains("xdesk-sshd") || cmd.startsWith("sshd") || cmd.contains("/sshd ") || cmd.endsWith("/sshd"))
                return true;
            p = parent(p);
        }
        return false;
    }

    /**
     * The whole command line. Burrow's own process carries its binds first and the guest's program last: SSH's was
     * 603 bytes with "xdesk-sshd" at 592, so the first 512 never named it, and every desktop start or stop took the
     * SSH server down with "SSH kept" in the log (owner, 24 November).
     */
    private static String cmdline(int pid) {
        try (FileInputStream in = new FileInputStream("/proc/" + pid + "/cmdline")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1024);
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) > 0 && out.size() < 65536; ) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8).replace('\0', ' ').trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static int parent(int pid) {
        try (FileInputStream in = new FileInputStream("/proc/" + pid + "/stat")) {
            byte[] b = new byte[512];
            int n = in.read(b);
            String s = new String(b, 0, Math.max(0, n), StandardCharsets.US_ASCII);
            String[] f = s.substring(s.lastIndexOf(')') + 2).split(" ");
            return Integer.parseInt(f[1]);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
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
            if (exe.equals(burrow) || exe.endsWith("/libburrow.so") || exe.endsWith("/" + VIDEO_HELPER) || isX(pid)) out.add(pid);
        }
        return out;
    }

    private volatile boolean desktopWanted;
    private volatile long desktopCheckedAt;
    private volatile boolean desktopSeen;

    /** Its whole desktop runs (the panel is there), not only "apps only". Looked up at most every 2 s (the lists ask often). */
    boolean wholeDesktop() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - desktopCheckedAt < 2000) return desktopSeen;
        boolean seen = false;
        if (state() == State.RUNNING) for (int p : ours()) if (cmdline(p).contains("xfce4-panel")) { seen = true; break; }
        desktopSeen = seen;
        desktopCheckedAt = now;
        return seen;
    }

    /**
     * The whole desktop over a running "apps only" session: the panel and desktop added, the open programs stay.
     * While it starts, once it runs.
     */
    void showDesktop() {
        State st = state();
        if (st == State.STARTING) {
            desktopWanted = true;
            return;
        }
        if (st != State.RUNNING) return;
        new Thread(() -> {
            desktopCheckedAt = 0;
            if (wholeDesktop()) return;
            try {
                writeAsset("xdesk-desktop");
                ProcessBuilder b = new ProcessBuilder(containerCmd("/usr/local/bin/xdesk-desktop"));
                b.environment().remove("LD_PRELOAD");
                b.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(new File(logDir(), "session.log")));
                appendLog("adding the whole desktop");
                b.start();
                Thread.sleep(1500);
            } catch (IOException | InterruptedException e) {
                Log.w(TAG, "desktop", e);
            }
            desktopCheckedAt = 0;
            changed();
        }, "builtin-linux-desktop").start();
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
        String destPath = dest.getAbsolutePath();
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
                while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                if (name.isEmpty() || name.equals(".")) { skip(in, len + pad(len)); continue; }
                File out = new File(dest, name);
                String outPath = out.getPath();
                if (!outPath.startsWith(destPath + "/") || ("/" + name + "/").contains("/../"))
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
