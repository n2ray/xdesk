package io.github.n2ray.xdesk.probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.system.Os;
import android.system.StructStat;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.UUID;

public final class ProbeActivity extends Activity {
    private static final String TAG = "XDeskFdProbe";
    private static boolean hasLine(String report, String line) {
        return ("\n" + report + "\n").contains("\n" + line + "\n");
    }
    private static boolean imagesMapped(String report) {
        if (!GuestBuild.DYNAMIC) return true;
        if (GuestBuild.ROOTFS && !(hasLine(report, "rootfs_image=ubuntu-26.04-r1 2026-11-22")
                && hasLine(report, "rootfs_version=26.04") && hasLine(report, "image_write=DENIED")
                && hasLine(report, "image_create=DENIED") && hasLine(report, "xdesk_entries=image")
                && hasLine(report, "cwd=/etc/xdesk relative_image=ubuntu-26.04-r1 2026-11-22")
                && hasLine(report, "relative_link=../usr/lib/os-release")
                && hasLine(report, "absolute_link=/usr/share/zoneinfo/Etc/UTC")
                && hasLine(report, "bin_entries=671") && hasLine(report, "exec_cwd=/usr/bin"))) return false;
        String[] lines = report.split("\n");
        for (String name : new String[]{"libubuntu_dash.so", "libubuntu_ld.so", "libubuntu_libc.so"}) {
            String prefix = "installed=" + name + " ";
            boolean mapped = false;
            for (String line : lines) {
                if (!line.startsWith(prefix)) continue;
                String mapping = "exec_map=" + line.substring(prefix.length()) + " ";
                for (String candidate : lines) {
                    if (candidate.startsWith(mapping)) mapped = true;
                }
            }
            if (!mapped) return false;
        }
        return true;
    }
    private static boolean isolatedResult(String report, String token, String identity) {
        // Android app-data isolation may hide the directory altogether.
        boolean denied = hasLine(report, "path_errno=2") || hasLine(report, "path_errno=13");
        String diagnostic = report.toLowerCase(Locale.ROOT);
        boolean guestDenied = diagnostic.contains("/owner-marker: no such file")
                || diagnostic.contains("/owner-marker: permission denied");
        return imagesMapped(report) && denied && hasLine(report, "file=" + identity) && hasLine(report, "via_fd=" + token)
                && guestDenied && hasLine(report, "fd_mode=0") && hasLine(report, "via_path=DENIED")
                && hasLine(report, "guest_exit=0") && report.contains("domain=u:r:isolated_app:")
                && report.contains("\nu:r:isolated_app:") && !hasLine(report, "uid=" + android.os.Process.myUid());
    }
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            new Thread(() -> {
                try {
                    if (GuestBuild.ROOTFS && getIntent().getBooleanExtra("renderer_fork", false)) {
                        HandshakeTest.runFork(ProbeActivity.this, IProbe.Stub.asInterface(binder));
                        return;
                    }
                    if (GuestBuild.ROOTFS && getIntent().getBooleanExtra("zygote_handshake", false)) {
                        HandshakeTest.run(ProbeActivity.this, IProbe.Stub.asInterface(binder));
                        return;
                    }
                    runTest(IProbe.Stub.asInterface(binder));
                    String source = getIntent().getStringExtra("release_marker");
                    if (GuestBuild.ROOTFS) {
                        UUID id = source == null ? null : UUID.fromString(source);
                        SocketTest.run(ProbeActivity.this, IProbe.Stub.asInterface(binder), id);
                    }
                    if (source != null) runReleaseTest(IProbe.Stub.asInterface(binder), UUID.fromString(source));
                } catch (Exception e) {
                    Log.e(TAG, "FAIL", e);
                } finally {
                    runOnUiThread(() -> { unbindService(this); finish(); });
                }
            }, "test-owner").start();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "Service disconnected");
        }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!bindService(new Intent(this, ProbeService.class), connection, Context.BIND_AUTO_CREATE)) {
            Log.e(TAG, "FAIL: bindService");
            finish();
        }
    }

    private void runTest(IProbe isolated) throws Exception {
        String token = UUID.randomUUID().toString();
        File marker = File.createTempFile("owner-marker-", ".txt", getFilesDir());
        try {
            Files.write(marker.toPath(), (token + "\n").getBytes(StandardCharsets.US_ASCII));
            String path = marker.getCanonicalPath();
            String control, restricted;
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(marker, ParcelFileDescriptor.MODE_READ_ONLY)) {
                control = ProbeService.observe(getApplicationInfo().nativeLibraryDir, fd, path);
            }
            // A fresh file description starts at offset zero for the second run.
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(marker, ParcelFileDescriptor.MODE_READ_ONLY)) {
                restricted = isolated.run(fd, path);
            }
            StructStat st = Os.stat(path);
            String identity = st.st_dev + ":" + st.st_ino;
            boolean ok = imagesMapped(control) && hasLine(control, "path_errno=0") && hasLine(control, "via_path=" + token)
                    && hasLine(control, "via_fd=" + token) && hasLine(control, "guest_exit=0")
                    && hasLine(control, "file=" + identity) && isolatedResult(restricted, token, identity)
                    && new String(Files.readAllBytes(marker.toPath()), StandardCharsets.US_ASCII).equals(token + "\n");
            Log.i(TAG, "owner control\n" + control.replace(token, "<matched marker>"));
            Log.i(TAG, "isolated service\n" + restricted.replace(token, "<matched marker>"));
            if (!ok) throw new IllegalStateException("local marker control failed");
            Log.i(TAG, "PASS local marker");
        } finally {
            Files.deleteIfExists(marker.toPath());
        }
    }

    /* The release package keeps this separate synthetic file open during the
     * check. The only granted fd is for the experimental host's own marker. */
    private void runReleaseTest(IProbe isolated, UUID id) throws Exception {
        String pkg = "io.github.n2ray.xdesk.x11";
        android.content.pm.ApplicationInfo owner = getPackageManager().getApplicationInfo(pkg, 0);
        String path = owner.dataDir + "/files/linux/rootfs/tmp/fd-probe-" + id;
        String token = UUID.randomUUID().toString();
        File input = File.createTempFile("granted-", ".txt", getFilesDir());
        try {
            Files.write(input.toPath(), (token + "\n").getBytes(StandardCharsets.US_ASCII));
            StructStat st = Os.stat(input.getPath());
            String report;
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)) {
                report = isolated.run(fd, path);
            }
            Log.i(TAG, "release path check " + id + ", owner uid=" + owner.uid
                    + "\n" + report.replace(token, "<matched marker>"));
            if (!isolatedResult(report, token, st.st_dev + ":" + st.st_ino))
                throw new IllegalStateException("release private path test failed");
            Log.i(TAG, "PASS release private path " + id);
        } finally {
            Files.deleteIfExists(input.toPath());
        }
    }
}
