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
import java.nio.file.Files;
import java.nio.file.Paths;

public final class ProbeService extends Service {
    static { System.loadLibrary("fdprobe"); }

    static native String runBurrow(String appLibDir, int markerFd, String ownerPath, boolean dynamic, boolean rootfs, int socketRole);
    static native String runHandshake(String dir, int endpoint, int registration, String expected);
    private final IProbe.Stub stub = new IProbe.Stub() {
        @Override public int serviceUid() { return Process.myUid(); }

        @Override public String handshake(ParcelFileDescriptor endpoint, ParcelFileDescriptor registration) {
            try (ParcelFileDescriptor channel = endpoint; ParcelFileDescriptor roster = registration) {
                return runHandshake(getApplicationInfo().nativeLibraryDir, channel.getFd(), roster.getFd(), null);
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

    @Override public IBinder onBind(Intent intent) { return stub; }
}
