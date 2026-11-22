package io.github.n2ray.xdesk.probe;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class ChromeLaunchTest {
    private static final String TAG = "XDeskChrome158";

    private static void deleteTree(Path path) throws java.io.IOException {
        if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) deleteTree(child);
            }
        }
        Files.deleteIfExists(path);
    }

    static { System.loadLibrary("fdprobe"); }
    private static native int listenPrivate(String path);
    private static native int acceptHelper(int listener);

    static void run(Context app, String mode, IProbe isolated) throws Exception {
        if (!Arrays.asList("sibling", "external", "browser", "installed").contains(mode))
            throw new IllegalArgumentException("unknown Chrome experiment");
        Path lib = new File(app.getApplicationInfo().nativeLibraryDir).toPath();
        Path work = Files.createTempDirectory(app.getCacheDir().toPath(), "chrome158-");
        try {
            Path tmp = Files.createDirectory(work.resolve("tmp"));
            Path shm = Files.createDirectory(work.resolve("shm"));
            boolean installed = mode.equals("installed");
            Path opt = work.resolve("opt");
            if (!installed) stageBrowser(lib, opt, mode);
            File log = work.resolve("output").toFile();
            String runtime = installed ? "libburrow_chrome_fs.so" : "libburrow.so";
            ProcessBuilder cmd = new ProcessBuilder(lib.resolve(runtime).toString(), "-r", lib.toString());
            if (!installed) cmd.command().addAll(Arrays.asList("-b", opt + ":/opt"));
            cmd.command().addAll(Arrays.asList("-b", tmp + ":/tmp", "-b", "/proc", "-b", "/dev", "-b", shm + ":/dev/shm",
                    "-w", "/", "--", "/usr/bin/python3", "-I", "-S", "-B", "/opt/entry.py", mode));
            Map<String, String> env = cmd.environment();
            env.clear();
            env.put("LC_ALL", "C");
            env.put("PATH", "/usr/bin:/bin");
            env.put("HOME", "/tmp");
            env.put("TMPDIR", "/tmp");
            env.put("LD_LIBRARY_PATH", "/opt");
            cmd.redirectErrorStream(true).redirectOutput(log);
            int listener = isolated == null ? -1 : listenPrivate(tmp.resolve("zygote.sock").toString());
            if (isolated != null && listener < 0) throw new java.io.IOException("listen errno=" + -listener);
            Process child;
            boolean ended;
            try (ParcelFileDescriptor server = listener < 0 ? null : ParcelFileDescriptor.adoptFd(listener)) {
                child = cmd.start();
                try {
                    long deadline = SystemClock.elapsedRealtime() + 40000;
                    boolean dispatched = false;
                    while (server != null && !dispatched && child.isAlive()
                            && SystemClock.elapsedRealtime() < deadline) {
                        int accepted = acceptHelper(server.getFd());
                        if (accepted >= 0) {
                            dispatched = true;
                            ParcelFileDescriptor request = ParcelFileDescriptor.adoptFd(accepted);
                            new Thread(() -> {
                                try (ParcelFileDescriptor channel = request) {
                                    int rc = isolated.launchChromeZygote(channel);
                                    Os.write(channel.getFileDescriptor(), new byte[]{(byte)rc}, 0, 1);
                                } catch (Exception e) { Log.e(TAG, "sandbox request closed", e); }
                            }, "chrome-zygote-request").start();
                        } else if (accepted != -OsConstants.EAGAIN && accepted != -OsConstants.EINTR) {
                            throw new java.io.IOException("accept errno=" + -accepted);
                        } else {
                            child.waitFor(50, TimeUnit.MILLISECONDS);
                        }
                    }
                    if (server != null) Log.i(TAG, "helper_request=" + dispatched);
                    ended = child.waitFor(Math.max(0, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    child.destroy();
                    throw e;
                }
            }
            if (!ended) {
                child.destroy();
                if (!child.waitFor(3, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(); }
            }
            try (java.io.BufferedReader lines = Files.newBufferedReader(log.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = lines.readLine()) != null) Log.i(TAG, line);
            }
            Log.i(TAG, "case=" + mode + " exit=" + child.exitValue() + " timeout=" + !ended);
        } finally {
            deleteTree(work);
        }
    }

    private static void stageBrowser(Path lib, Path opt, String mode) throws java.io.IOException,
            android.system.ErrnoException {
        Files.createDirectory(opt);
        Files.createDirectory(opt.resolve("locales"));
        for (String row : Files.readAllLines(lib.resolve("libchrome158_index.so"))) {
            String[] entry = row.split(" ");
            Path object = lib.resolve(entry[0]);
            if (entry[1].equals("chrome-sandbox")) {
                Path helper = opt.resolve(mode.equals("external") ? "helper" : entry[1]);
                Files.copy(object, helper);
                Os.chmod(helper.toString(), 04755);
            } else {
                Files.createSymbolicLink(opt.resolve(entry[1]), object);
            }
        }
        Files.createSymbolicLink(opt.resolve("entry.py"), lib.resolve("libchrome158_entry.so"));
        Files.createSymbolicLink(opt.resolve("browser-load.so"), lib.resolve("libchrome158_observer.so"));
    }
}
