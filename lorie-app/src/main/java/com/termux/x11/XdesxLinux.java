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
    private final Context context;
    private final BuiltinLinux linux;
    private final BuiltinApps apps;
    private final File desktop;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String,String> keys = new HashMap<>();
    private final Consumer<String> error;
    private final BuiltinLinux.Listener listener = this::changed;
    private volatile boolean closed;
    private boolean listed;
    private Process session, video;
    private final AudioRoute audioRoute;
    private AudioPlayer audio;

    XdesxLinux(Context context, Consumer<String> error) throws IOException {
        this.context=context.getApplicationContext();
        this.error = error;
        audioRoute = AudioRoute.get(context.getApplicationContext());
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
            BuiltinLinux.State state = linux.state();
            boolean installing = state == BuiltinLinux.State.INSTALLING || state == BuiltinLinux.State.STOPPING;
            String status = installed ? context.getString(com.termux.x11.app.R.string.xu_installed) : context.getString(com.termux.x11.app.R.string.xu_not_installed);
            if (installing && linux.progress() != null) status = linux.progress();
            else if (!installed && linux.error() != null) status = linux.error();
            boolean cancelled = linux.downloadCancelled();
            if (cancelled) status = installing ? context.getString(com.termux.x11.app.R.string.xu_canceling_download) : context.getString(com.termux.x11.app.R.string.xu_download_paused_you_can_resume_it_at_any_time);
            String phase = linux.canCancelDownload() ? "busy-download" : installing ? "busy"
                    : installed ? "ready" : cancelled ? "paused" : "available";
            publish("ubuntu-state", phase + "\n" + status);
            if (installed && !installing && !listed) refreshApps();
            if (!installed) {
                keys.clear();
                listed = false;
                publish("ubuntu-apps", "");
            }
        });
    }

    private void refreshApps() throws IOException {
        String locale = apps.locale();
        String theme = apps.iconTheme();
        List<String> rows = new ArrayList<>();
        keys.clear();
        for (Map.Entry<String,File> file : apps.files().entrySet()) {
            BuiltinApps.Entry e = BuiltinApps.read(file.getKey(), file.getValue());
            if (e == null || !apps.shown(e)) continue;
            String key = BuiltinApps.SCOPE + "/" + e.id;
            String id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
            keys.put(id, key);
            File icon = null;
            try { icon = panelIcon(apps.menuIcon(e.get("Icon"), theme)); }
            catch (IOException | com.caverock.androidsvg.SVGParseException ex) { Log.w("XDesX","Linux icon: "+e.id,ex); }
            rows.add(BuiltinApps.name(e, locale).replaceAll("[\\t\\r\\n]", " ") + "\t" + id + "\t"
                    + (icon == null ? "" : icon.getPath()) + "\n");
        }
        java.util.Collections.sort(rows);
        publish("ubuntu-apps", TextUtils.join("", rows));
        listed = true;
    }

    private File panelIcon(File icon) throws IOException, com.caverock.androidsvg.SVGParseException {
        if (icon == null || !icon.getName().endsWith(".svg")) return icon;
        byte[] svg = Files.readAllBytes(icon.toPath());
        File dir = new File(context.getCacheDir(), "linux-menu-icons");
        Files.createDirectories(dir.toPath());
        File png = new File(dir, UUID.nameUUIDFromBytes(svg) + ".png");
        if (png.isFile()) return png;
        com.caverock.androidsvg.SVG doc = com.caverock.androidsvg.SVG.getFromInputStream(
                new java.io.ByteArrayInputStream(svg));
        doc.setDocumentWidth("100%");
        doc.setDocumentHeight("100%");
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(128,128,android.graphics.Bitmap.Config.ARGB_8888);
        File tmp = File.createTempFile("icon-", ".png", dir);
        try {
            doc.renderToCanvas(new android.graphics.Canvas(bitmap));
            try (java.io.OutputStream out = Files.newOutputStream(tmp.toPath())) {
                if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)) throw new IOException("PNG encoding failed");
            }
            Files.move(tmp.toPath(),png.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            return png;
        } finally {
            bitmap.recycle();
            Files.deleteIfExists(tmp.toPath());
        }
    }

    void action(String verb) {
        submit(() -> {
            if (verb.equals("cancel")) { linux.cancelDownload(); return; }
            if (verb.equals("install")) {
                if (!linux.installed()) linux.install();
                return;
            }
            if (!linux.installed()) throw new IOException(context.getString(com.termux.x11.app.R.string.xu_install_ubuntu_in_settings_first));
            if (verb.equals("reset") || verb.equals("remove")) {
                if (linux.state() == BuiltinLinux.State.INSTALLING || linux.state() == BuiltinLinux.State.STOPPING) return;
                stopGuestForMaintenance();
                keys.clear();
                publish("ubuntu-apps","");
                listed=false;
                if (verb.equals("reset")) linux.reset(true); else linux.removeAll();
                return;
            }
            if (verb.equals("refresh")) { refreshApps(); return; }
            boolean fullscreen = verb.startsWith("full-");
            String key = keys.get(fullscreen ? verb.substring(5) : verb);
            if (key == null) throw new IOException(context.getString(com.termux.x11.app.R.string.xu_refresh_the_linux_application_list));
            long start=android.os.SystemClock.elapsedRealtime();
            boolean cold=startSession();
            long ready=android.os.SystemClock.elapsedRealtime();
            String startupId = fullscreen ? "xdesx-"+UUID.randomUUID() : "";
            String result = apps.launch(key, startupId, TextUtils.join("\n", linux.xdesxCommand(desktop, "xdesk-launch")));
            Log.i("XDesX","Linux app launch guest="+(cold?"cold":"warm")+" prepareMs="+(ready-start)
                    +" launchToPidMs="+(android.os.SystemClock.elapsedRealtime()-ready));
            if (result.startsWith("!")) throw new IOException(result.substring(1));
            if (fullscreen) {
                BuiltinApps.Entry entry=apps.entry(key);
                String wmClass=entry==null?"":entry.get("StartupWMClass");
                if (wmClass.isEmpty() && entry!=null) wmClass=entry.id.replaceFirst("\\.desktop$","");
                ProcessBuilder helper=new ProcessBuilder(new File(desktop,"bin/xdesx-window").getPath(),
                        "--linux-fullscreen",startupId,result,wmClass).directory(desktop);
                helper.environment().put("DISPLAY",":19");
                helper.environment().put("TMPDIR",new File(desktop,"tmp").getPath());
                helper.environment().put("LD_LIBRARY_PATH",new File(desktop,"lib").getPath());
                helper.environment().remove("LD_PRELOAD");
                Process matcher=helper.start();
                new Thread(() -> {
                    try {
                        if (matcher.waitFor()!=0 && !closed)
                            error.accept(context.getString(com.termux.x11.app.R.string.xu_linux_fullscreen_no_window));
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                },"xdesx-linux-fullscreen").start();
            }
        });
    }

    private boolean startSession() throws IOException, InterruptedException {
        synchronized (this) {
            if (closed) throw new InterruptedException();
            if (session != null && session.isAlive()) return false;
        }
        linux.prepareXdesx(desktop);
        launchSession();
        return true;
    }

    private synchronized void launchSession() throws IOException {
        if (closed) throw new IOException(context.getString(com.termux.x11.app.R.string.xu_the_desktop_session_has_ended));
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
            stopAudio();
            audio = new AudioPlayer(new File(tmp,"xdesk-audio"),audioRoute);
        } catch (IOException e) {
            if (video != null) video.destroy();
            throw e;
        }
    }

    private synchronized void stopGuestForMaintenance() throws IOException, InterruptedException {
        if(session!=null && session.isAlive()){
            session.getOutputStream().close();
            if(!session.waitFor(5,java.util.concurrent.TimeUnit.SECONDS))
                throw new IOException(context.getString(com.termux.x11.app.R.string.xu_guest_busy));
        }
        session=null;
        stopAudio();
        if(video!=null){video.destroy();video=null;}
    }

    private synchronized void stopAudio() {
        if (audio != null) {
            audio.close();
            audio = null;
        }
    }

    @Override public synchronized void close() {
        stopAudio();
        closed = true;
        linux.removeListener(listener);
        worker.shutdownNow();
        if (session != null) try { session.getOutputStream().close(); }
        catch (IOException ignored) { session.destroy(); }
        if (video != null) video.destroy();
    }
}
