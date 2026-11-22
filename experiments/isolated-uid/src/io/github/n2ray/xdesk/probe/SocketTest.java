package io.github.n2ray.xdesk.probe;

import android.app.Activity;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;
import java.io.File;
import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

final class SocketTest {
    private static final String TAG = "XDeskFdProbe";

    private static String field(String report, String key) {
        for (String line : report.split("\n")) {
            if (line.startsWith(key + "=")) return line.substring(key.length() + 1);
        }
        throw new IllegalStateException("Missing " + key);
    }

    private static void check(boolean result, String what) {
        if (!result) throw new IllegalStateException(what);
    }

    private static String identity(String report) {
        String measured = field(report, "android_pid_uid");
        String[] parts = measured.split(":");
        check(parts.length == 2 && parts[0].equals(field(report, "guest_pid"))
                && parts[1].equals(field(report, "launcher_uid")), "guest identity mismatch");
        check(field(report, "socket_exchange").equals("ok") && field(report, "socket_exit").equals("0")
                && field(report, "guest_gone").equals("ESRCH")
                && field(report, "supervisor_wait_again").equals("ECHILD"), "exchange or reap failed");
        return measured;
    }

    static void run(Activity app, IProbe isolated, UUID releaseId) throws Exception {
        File marker = File.createTempFile("socket-grant-", ".txt", app.getFilesDir());
        try {
            String token = UUID.randomUUID().toString() + "\n";
            Files.write(marker.toPath(), token.getBytes(StandardCharsets.US_ASCII));
            String denied = releaseId == null ? marker.getCanonicalPath() :
                    app.getPackageManager().getApplicationInfo("io.github.n2ray.xdesk.x11", 0).dataDir
                            + "/files/linux/rootfs/tmp/fd-probe-" + releaseId;
            FileDescriptor local = new FileDescriptor(), remote = new FileDescriptor();
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET | OsConstants.SOCK_CLOEXEC,
                    0, local, remote);
            try {
                for (FileDescriptor end : new FileDescriptor[]{local, remote})
                    Os.setsockoptInt(end, OsConstants.SOL_SOCKET, OsConstants.SO_PASSCRED, 1);
                try (ParcelFileDescriptor worker = ParcelFileDescriptor.dup(remote);
                     ParcelFileDescriptor owner = ParcelFileDescriptor.dup(local)) {
                    FutureTask<String> task = new FutureTask<>(() -> isolated.runSocket(worker, denied));
                    new Thread(task, "isolated-socket").start();
                    String a = ProbeService.runBurrow(app.getApplicationInfo().nativeLibraryDir,
                            owner.getFd(), marker.getCanonicalPath(), false, true, 1);
                    String b = task.get(25, TimeUnit.SECONDS);
                    Log.i(TAG, "socket ordinary\n" + a);
                    Log.i(TAG, "socket isolated target=" + (releaseId == null ? "local" : releaseId) + "\n" + b);
                    String ordinary = identity(a), restricted = identity(b);
                    check(field(a, "launcher_uid").equals(String.valueOf(Process.myUid()))
                            && !field(b, "launcher_uid").equals(field(a, "launcher_uid")), "UID separation");
                    check(field(a, "sender_pid_uid").equals(restricted)
                            && field(b, "sender_pid_uid").equals(ordinary), "incorrect kernel sender");
                    StructStat st = Os.stat(marker.getCanonicalPath());
                    check(field(b, "received_file").equals(st.st_dev + ":" + st.st_ino)
                            && field(b, "received_mode").equals("O_RDONLY write=EBADF"), "fd mismatch");
                    String error = field(b, "denied_path_errno");
                    check(error.equals("2") || error.equals("13"), "private path accessible");
                    Log.i(TAG, "PASS Unix socket creator=" + Process.myPid() + ":" + Process.myUid()
                            + " ordinary=" + ordinary + " isolated=" + restricted);
                }
            } finally {
                Os.close(local);
                Os.close(remote);
            }
        } finally {
            Files.deleteIfExists(marker.toPath());
        }
    }
}
