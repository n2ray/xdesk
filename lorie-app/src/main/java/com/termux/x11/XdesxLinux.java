package com.termux.x11;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** The guest supplies application processes. The X socket, WM and panel belong to xdesx. */
final class XdesxLinux implements AutoCloseable {
    private final BuiltinLinux linux;
    private final BuiltinApps apps;
    private final File desktop;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String,String> keys = new HashMap<>();
    private final Consumer<String> error;
    private final BuiltinLinux.Listener listener = this::changed;
    private boolean closed, listed;
    private Process session, video;

    XdesxLinux(Context context, Consumer<String> error) throws IOException {
        this.error = error;
        desktop = new File(context.getFilesDir(), "xd").getCanonicalFile();
        linux = BuiltinLinux.get(context);
        apps = new BuiltinApps(linux.rootfs, linux.home);
        linux.addListener(listener);
        changed();
    }

    private interface Work { void run() throws IOException, InterruptedException; }

    private synchronized void submit(Work work) {
        if (closed) return;
        worker.execute(() -> {
            try { work.run(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (IOException | RuntimeException e) {
                Log.w("XDesX", "Ubuntu", e);
                error.accept("Ubuntu · " + e.getMessage());
            }
        });
    }

    private void publish(String file, String contents) throws IOException {
        File run = new File(desktop, "run");
        run.mkdirs();
        File tmp = new File(run, file + ".tmp");
        Files.write(tmp.toPath(), contents.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), new File(run, file).toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void changed() {
        submit(() -> {
            boolean installed = linux.installed();
            boolean installing = linux.state() == BuiltinLinux.State.INSTALLING;
            String status = installed ? "已安装" : "未安装";
            if (installing && linux.progress() != null) status = linux.progress();
            else if (!installed && linux.error() != null) status = linux.error();
            publish("ubuntu-state", (installed ? "ready" : installing ? "busy" : "available") + "\n" + status);
            if (installed && !listed) refreshApps();
            if (!installed) {
                keys.clear();
                listed = false;
                publish("ubuntu-apps", "");
            }
        });
    }

    private void refreshApps() throws IOException {
        String locale = apps.locale();
        List<String> rows = new ArrayList<>();
        keys.clear();
        for (Map.Entry<String,File> file : apps.files().entrySet()) {
            BuiltinApps.Entry e = BuiltinApps.read(file.getKey(), file.getValue());
            if (e == null || !apps.shown(e)) continue;
            String key = BuiltinApps.SCOPE + "/" + e.id;
            String id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
            keys.put(id, key);
            rows.add(BuiltinApps.name(e, locale).replaceAll("[\\t\\r\\n]", " ") + "\t" + id + "\n");
        }
        java.util.Collections.sort(rows);
        publish("ubuntu-apps", TextUtils.join("", rows));
        listed = true;
    }

    void action(String verb) {
        submit(() -> {
            if (verb.equals("install")) {
                if (!linux.installed()) linux.install();
                return;
            }
            if (!linux.installed()) throw new IOException("请先在全局设置中安装 Ubuntu 核心");
            if (verb.equals("refresh")) { refreshApps(); return; }
            String key = keys.get(verb);
            if (key == null) throw new IOException("请刷新 Linux 应用列表");
            startSession();
            String result = apps.launch(key, "", TextUtils.join("\n", linux.xdesxCommand(desktop, "xdesk-launch")));
            if (result.startsWith("!")) throw new IOException(result.substring(1));
        });
    }

    private void startSession() throws IOException, InterruptedException {
        synchronized (this) {
            if (closed) throw new InterruptedException();
            if (session != null && session.isAlive()) return;
        }
        linux.prepareXdesx(desktop);
        launchSession();
    }

    private synchronized void launchSession() throws IOException {
        if (closed) throw new IOException("桌面已关闭");
        if (session != null && session.isAlive()) return;
        if (video != null) video.destroy();
        File tmp = new File(desktop, "linux-tmp");
        Files.deleteIfExists(new File(tmp, "xdesk-launch/req").toPath());
        video = linux.startVideoHelper(tmp);
        try {
            ProcessBuilder b = new ProcessBuilder(linux.xdesxCommand(desktop, "xdesk-session"));
            b.environment().remove("LD_PRELOAD");
            b.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(new File(linux.logDir(), "xdesx-apps.log")));
            session = b.start();
        } catch (IOException e) {
            if (video != null) video.destroy();
            throw e;
        }
    }

    @Override public synchronized void close() {
        closed = true;
        linux.removeListener(listener);
        worker.shutdownNow();
        if (session != null) try { session.getOutputStream().close(); }
        catch (IOException ignored) { session.destroy(); }
        if (video != null) video.destroy();
    }
}
