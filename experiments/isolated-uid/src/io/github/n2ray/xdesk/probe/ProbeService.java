package io.github.n2ray.xdesk.probe;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.FileDescriptor;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class ProbeService extends Service {
    static { System.loadLibrary("fdprobe"); }

    static native String runBurrow(String appLibDir, int markerFd, String ownerPath, boolean dynamic, boolean rootfs, int socketRole);
    static native String runHandshake(String dir, int endpoint, int registration, String expected);
    private static native int runChromeZygote(String dir, int requestFd, int owner);
    private final IProbe.Stub stub = new IProbe.Stub() {
        @Override public int serviceUid() { return Process.myUid(); }

        @Override public int launchChromeZygote(ParcelFileDescriptor request) {
            try (ParcelFileDescriptor fd = request) {
                if (!Process.isIsolated() || !GuestBuild.ROOTFS) return 125;
                int rc = runChromeZygote(getApplicationInfo().nativeLibraryDir, fd.getFd(),
                        android.os.Binder.getCallingUid());
                android.util.Log.i("XDeskChrome158", "isolated launch rc=" + rc);
                return rc < 0 ? 125 : rc;
            } catch (java.io.IOException e) { return 125; }
        }

        @Override public String checkSelfExe() {
            if (!GuestBuild.ROOTFS) return "ERROR requires rootfs";
            String dir = getApplicationInfo().nativeLibraryDir;
            ProcessBuilder cmd = new ProcessBuilder(dir + "/libburrow.so", "-r", dir, "-b", "/proc",
                    "-b", dir + "/libself_exe_check.so:/self-exe.py",
                    "-w", "/", "--", "/usr/bin/python3", "-I", "-B", "-S", "/self-exe.py");
            cmd.environment().clear();
            java.lang.Process child = null;
            try {
                child = cmd.redirectErrorStream(true).start();
                if (!child.waitFor(10, TimeUnit.SECONDS)) return "ERROR self-exe timeout";
                StringBuilder result = new StringBuilder("self-exe service_uid=").append(Process.myUid());
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                    String line;
                    while ((line = lines.readLine()) != null) result.append('\n').append(line);
                }
                return result.append("\nguest_exit=").append(child.exitValue()).toString();
            } catch (Exception e) { return "ERROR self-exe " + e; }
            finally { if (child != null && child.isAlive()) child.destroy(); }
        }

        @Override public String handshake(ParcelFileDescriptor endpoint, ParcelFileDescriptor registration, String protocol) {
            try (ParcelFileDescriptor channel = endpoint; ParcelFileDescriptor roster = registration) {
                if (protocol != null && !"mojo:".equals(protocol) && !"fork:".equals(protocol))
                    throw new IllegalArgumentException("unknown probe protocol");
                return runHandshake(getApplicationInfo().nativeLibraryDir, channel.getFd(), roster.getFd(), protocol);
            } catch (Exception e) { return "ERROR " + e; }
        }

        @Override public String runSocket(ParcelFileDescriptor endpoint, String deniedPath) {
            try (ParcelFileDescriptor fd = endpoint) {
                return runBurrow(getApplicationInfo().nativeLibraryDir, fd.getFd(), deniedPath, false, true, 2);
            } catch (Exception e) { return "ERROR " + e; }
        }
        @Override public String run(ParcelFileDescriptor marker, String ownerPath) {
            try (ParcelFileDescriptor fd = marker) {
                return observe(getApplicationInfo().nativeLibraryDir, fd, ownerPath);
            } catch (Exception e) { return "ERROR " + e; }
        }
    };

    static String observe(String libDir, ParcelFileDescriptor marker, String ownerPath) throws Exception {
        int pathErrno = 0;
        try {
            FileDescriptor direct = Os.open(ownerPath, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
            Os.close(direct);
        } catch (ErrnoException e) { pathErrno = e.errno; }
        String domain = new String(Files.readAllBytes(Paths.get("/proc/self/attr/current")))
                .replace("\u0000", "").trim();
        int mode = Os.fcntlInt(marker.getFileDescriptor(), OsConstants.F_GETFL, 0) & OsConstants.O_ACCMODE;
        StructStat st = Os.fstat(marker.getFileDescriptor());
        return "uid=" + Process.myUid() + "\ndomain=" + domain + "\npath_errno=" + pathErrno +
                "\nfd_mode=" + mode + "\nfile=" + st.st_dev + ":" + st.st_ino + "\n" +
                runBurrow(libDir, marker.getFd(), ownerPath, GuestBuild.DYNAMIC, GuestBuild.ROOTFS, 0);
    }

    static final String CHROME_LAUNCH = "io.github.n2ray.xdesk.isolationprobe.CHROME_LAUNCH";

    @Override public IBinder onBind(Intent intent) {
        if (CHROME_LAUNCH.equals(intent.getAction())) {
            // This is the actual Android service context, before Burrow virtualizes IDs.
            try {
                StringBuilder status = new StringBuilder("backend_pid=").append(Process.myPid());
                for (String line : Files.readAllLines(Paths.get("/proc/self/status"))) {
                    if (line.startsWith("CapPrm:") || line.startsWith("CapEff:")
                            || line.startsWith("CapBnd:") || line.startsWith("NoNewPrivs:"))
                        status.append(' ').append(line.trim());
                }
                android.util.Log.i("XDeskChrome158", status.toString());
            } catch (java.io.IOException e) {
                android.util.Log.e("XDeskChrome158", "Cannot read service capabilities", e);
            }
        }
        return stub;
    }
}
