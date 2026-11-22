package io.github.n2ray.xdesk.probe;

import android.content.Context;
import android.system.Os;
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

    static void run(Context app, String mode) throws Exception {
        if (!Arrays.asList("sibling", "external", "browser").contains(mode))
            throw new IllegalArgumentException("unknown Chrome experiment");
        Path lib = new File(app.getApplicationInfo().nativeLibraryDir).toPath();
        Path work = Files.createTempDirectory(app.getCacheDir().toPath(), "chrome158-");
        try {
            Path opt = Files.createDirectory(work.resolve("opt"));
            Path tmp = Files.createDirectory(work.resolve("tmp"));
            Path shm = Files.createDirectory(work.resolve("shm"));
            Files.createDirectory(opt.resolve("locales"));
            for (String row : Files.readAllLines(lib.resolve("libchrome158_index.so"))) {
                String[] entry = row.split(" ");
                Path object = lib.resolve(entry[0]);
                if (entry[1].equals("chrome-sandbox")) {
                    // As in the deb post-install, set the real mode on the test
                    // app's private copy. Preserve the file's actual owner.
                    Path helper = opt.resolve(mode.equals("external") ? "helper" : entry[1]);
                    Files.copy(object, helper);
                    Os.chmod(helper.toString(), 04755);
                } else {
                    Files.createSymbolicLink(opt.resolve(entry[1]), object);
                }
            }
            Files.createSymbolicLink(opt.resolve("entry.py"), lib.resolve("libchrome158_entry.so"));
            Files.createSymbolicLink(opt.resolve("browser-load.so"), lib.resolve("libchrome158_observer.so"));
            File log = work.resolve("output").toFile();
            ProcessBuilder cmd = new ProcessBuilder(lib.resolve("libburrow.so").toString(), "-r", lib.toString(),
                    "-b", opt + ":/opt", "-b", tmp + ":/tmp", "-b", "/proc", "-b", "/dev", "-b", shm + ":/dev/shm",
                    "-w", "/", "--", "/usr/bin/python3", "-I", "-S", "-B", "/opt/entry.py", mode);
            Map<String, String> env = cmd.environment();
            env.clear();
            env.put("LC_ALL", "C");
            env.put("PATH", "/usr/bin:/bin");
            env.put("HOME", "/tmp");
            env.put("TMPDIR", "/tmp");
            env.put("LD_LIBRARY_PATH", "/opt");
            Process child = cmd.redirectErrorStream(true).redirectOutput(log).start();
            boolean ended = child.waitFor(40, TimeUnit.SECONDS);
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
}
