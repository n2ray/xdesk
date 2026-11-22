package io.github.n2ray.xdesk.probe;

import android.app.Activity;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;
import android.util.Log;
import java.io.FileDescriptor;
import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HandshakeTest {
    static { System.loadLibrary("fdprobe"); }
    private static final String TAG = "XDeskFdProbe";
    private static native long registration(int fd, byte stage);

    private static void require(boolean ok, String what) {
        if (!ok) throw new IllegalStateException(what);
    }

    private static final class Pair implements AutoCloseable {
        final FileDescriptor reader = new FileDescriptor(), writer = new FileDescriptor();

        Pair() throws Exception {
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET | OsConstants.SOCK_CLOEXEC,
                    0, reader, writer);
            try {
                Os.setsockoptInt(reader, OsConstants.SOL_SOCKET, OsConstants.SO_PASSCRED, 1);
                Os.setsockoptTimeval(reader, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                        StructTimeval.fromMillis(7000));
            } catch (Exception e) {
                close();
                throw e;
            }
        }
        @Override public void close() throws Exception {
            try { Os.close(reader); } finally { Os.close(writer); }
        }
    }

    private static int registeredPid(int fd, char stage, int uid) {
        long record = registration(fd, (byte)stage);
        require(record > 0 && (int)record == uid, "unregistered process at " + stage);
        return (int)(record >>> 32);
    }

    private static void trial(Activity app, IProbe isolated, int uid, String scenario) throws Exception {
        try (Pair control = new Pair(); Pair roster = new Pair();
             ParcelFileDescriptor host = ParcelFileDescriptor.dup(control.reader);
             ParcelFileDescriptor child = ParcelFileDescriptor.dup(control.writer);
             ParcelFileDescriptor admin = ParcelFileDescriptor.dup(roster.reader);
             ParcelFileDescriptor worker = ParcelFileDescriptor.dup(roster.writer)) {
            boolean mojo = scenario.equals("mojo_invitation");
            boolean fork = mojo || scenario.equals("renderer_fork");
            String protocol = mojo ? "mojo:" : fork ? "fork:" : null;
            FutureTask<String> sender = new FutureTask<>(() -> isolated.handshake(child, worker, protocol));
            new Thread(sender, "isolated-boot").start();
            int boot = registeredPid(admin.getFd(), 'B', uid);
            int hello = registeredPid(admin.getFd(), 'H', uid);
            require(boot != hello, "expected separate boot and hello processes");

            // Only the test expectation changes. Both datagrams retain their original kernel credentials.
            int expectedBoot = scenario.equals("boot_pid") ? hello : boot;
            int expectedHello = scenario.equals("hello_pid") ? boot : hello;
            int expectedUid = scenario.equals("boot_uid") ? Process.myUid() : uid;
            String policy = expectedBoot + ":" + expectedHello + ":" + expectedUid;
            String received;
            try {
                received = ProbeService.runHandshake(app.getApplicationInfo().nativeLibraryDir,
                        host.getFd(), -1, fork ? protocol + policy : policy);
            } finally {
                Os.write(roster.reader, new byte[]{'D'}, 0, 1);
            }
            String sent = sender.get(18, TimeUnit.SECONDS);
            Log.i(TAG, "handshake case=" + scenario + " registered=" + boot + ":" + uid + ","
                    + hello + ":" + uid + " expected=" + policy + "\nreceiver\n" + received + "\nsender\n" + sent);
            java.util.List<String> rx = Arrays.asList(received.split("\n"));
            java.util.List<String> tx = Arrays.asList(sent.split("\n"));
            boolean accept = fork || scenario.equals("match");
            require(rx.contains("boot_observed=" + boot + ":" + uid), "bad boot credentials");
            require(rx.contains("hello_observed=" + hello + ":" + uid) == !scenario.startsWith("boot_"),
                    "incorrect hello progression");
            require(rx.contains("decision=" + (accept ? "accept" : "reject_" + scenario))
                    && rx.contains("socket_exit=" + (accept ? 0 : 23)), "wrong decision");
            require(tx.contains("boot_pid=" + boot + " hello_pid=" + hello)
                    && tx.contains("android_pid_uid=" + boot + ":" + uid) && tx.contains("socket_exit=0"),
                    "sender registration/exit mismatch");
            require(rx.contains("launcher_uid=" + Process.myUid()) && rx.contains("guest_gone=ESRCH")
                    && tx.contains("guest_gone=ESRCH") && rx.contains("supervisor_wait_again=ECHILD")
                    && tx.contains("supervisor_wait_again=ECHILD"), "launcher identity or reap failed");
            if (fork) {
                int pid = registeredPid(admin.getFd(), 'C', uid);
                require(pid != boot && pid != hello && rx.contains("oracle_pid_uid=" + pid + ":" + uid
                        + " fork_reply_pid=" + pid + (mojo ? " channel_key=2" : " reap_requested=" + pid)), "browser/Android PID mismatch");
                require(tx.contains("renderer_pid=" + pid + " parent=" + hello + " global_pid=" + pid)
                        && tx.contains("fork_pid=" + pid + " wait_pid=" + pid + " exit=0 wait_again=ECHILD"),
                        "zygote/renderer PID or reap mismatch");
                if (mojo) {
                    Matcher broker = exchange(rx, "broker");
                    Matcher childReport = exchange(tx, "child");
                    require(rx.contains("android_pid_uid=" + broker.group(1) + ":" + Process.myUid())
                            && childReport.group(1).equals(Integer.toString(pid))
                            && broker.group(2).equals(childReport.group(3))
                            && broker.group(3).equals(childReport.group(2))
                            && !broker.group(2).equals(childReport.group(2)), "Mojo endpoint PID or nonce mismatch");
                    require(rx.contains("mojo_invitation=sent attachment=0")
                            && tx.contains("mojo_invitation=accepted attachment=0")
                            && rx.contains("mojo_reap_requested=" + pid), "Mojo invitation or Reap missing");
                    Log.i(TAG, "PASS Mojo invitation android_registered=" + pid + ":" + uid);
                    return;
                }
                String marker = null;
                for (String line : rx) if (line.matches("marker=[0-9a-f]{32}")) marker = line;
                require(marker != null && tx.contains(marker) && tx.contains("fd_key=73 mode=O_RDONLY write=EBADF"),
                        "renderer did not receive browser's read-only marker");
                Log.i(TAG, "PASS renderer fork android_registered=" + pid + ":" + uid);
            }
        }
    }

    private static Matcher exchange(java.util.List<String> lines, String role) {
        Pattern pattern = Pattern.compile("mojo_" + role + "_pid=([0-9]+) sent=([0-9A-Fa-f]{32})"
                + " received=([0-9A-Fa-f]{32}) echo=ok");
        for (String line : lines) {
            Matcher match = pattern.matcher(line);
            if (match.matches()) return match;
        }
        throw new IllegalStateException("missing verified exchange from " + role);
    }

    static void runFork(Activity app, IProbe isolated, boolean mojo) throws Exception {
        int uid = isolated.serviceUid();
        require(uid != Process.myUid(), "missing isolated UID");
        if (mojo) require(new java.io.File(app.getApplicationInfo().nativeLibraryDir, "libmojo_guest.so").isFile(),
                "build the rootfs APK with MOJO_GUEST_ELF");
        trial(app, isolated, uid, mojo ? "mojo_invitation" : "renderer_fork");
    }

    static void run(Activity app, IProbe isolated) throws Exception {
        int uid = isolated.serviceUid();
        require(uid != Process.myUid(), "missing isolated UID");
        for (String scenario : new String[]{"match", "boot_uid", "boot_pid", "hello_pid"})
            trial(app, isolated, uid, scenario);
        Log.i(TAG, "PASS zygote handshake match=1 rejected=3 uid=" + uid);
    }
}
