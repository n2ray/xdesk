package com.termux.x11;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 可启动的安卓应用，进程单例。读应用名和图标都很慢，手机屏又是主要操作界面，所以：应用名存文件，打开就有；
 * 后台再读一遍更新；图标画成位图放内存，看得到的先读，其余的低优先级预读。
 */
final class AppCatalog {
    static final class App {
        final String label, packageName, activityName;

        App(String label, String packageName, String activityName) {
            this.label = label;
            this.packageName = packageName;
            this.activityName = activityName;
        }

        String component() {
            return packageName + "/" + activityName;
        }

        /** 不分大小写，按名字和包名。 */
        boolean matches(String lowerCaseQuery) {
            return label.toLowerCase().contains(lowerCaseQuery) || packageName.toLowerCase().contains(lowerCaseQuery);
        }
    }

    private static final String TAG = "XDesk";
    private static final String CACHE_FILE = "apps.txt", FIELD = "\t";
    private static final float ICON_DP = 40;
    private static final int ICON_CACHE_BYTES = 16 * 1024 * 1024;

    private static AppCatalog instance;

    static synchronized AppCatalog get(Context c) {
        if (instance == null) instance = new AppCatalog(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final PackageManager pm;
    private final File cacheFile;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    private final ExecutorService visibleIcons = Executors.newSingleThreadExecutor();
    private final ExecutorService prewarm = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> icons = new LruCache<String, Bitmap>(ICON_CACHE_BYTES) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private final int iconSize;
    private List<App> apps = new ArrayList<>();

    private AppCatalog(Context c) {
        context = c;
        pm = c.getPackageManager();
        cacheFile = new File(c.getCacheDir(), CACHE_FILE);
        iconSize = Style.dp(c, ICON_DP);
        apps = readCache();
    }

    /** 现在知道的应用，可能是上次存的。主线程用。 */
    List<App> apps() {
        return apps;
    }

    App find(String component) {
        for (App a : apps) if (a.component().equals(component)) return a;
        return null;
    }

    /** 这个包的第一个可启动入口，没有是 null。 */
    App findPackage(String pkg) {
        for (App a : apps) if (a.packageName.equals(pkg)) return a;
        return null;
    }

    /** 后台重读，读完回到主线程调用 done。 */
    void refresh(final Runnable done) {
        scanner.execute(() -> {
            final List<App> fresh = scan();
            writeCache(fresh);
            ui.post(() -> {
                apps = fresh;
                done.run();
            });
            prewarm.execute(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST);
                for (App a : fresh) {
                    if (icons.size() > ICON_CACHE_BYTES * 9 / 10) break;  // 再读只会挤掉刚读的
                    load(a);
                }
            });
        });
    }

    private List<App> scan() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        ArrayList<App> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            if (context.getPackageName().equals(ri.activityInfo.packageName)) continue;
            out.add(new App(String.valueOf(ri.loadLabel(pm)), ri.activityInfo.packageName, ri.activityInfo.name));
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> collator.compare(a.label, b.label));
        return out;
    }

    private List<App> readCache() {
        ArrayList<App> out = new ArrayList<>();
        try {
            if (!cacheFile.exists()) return out;
            for (String line : Files.readAllLines(cacheFile.toPath(), StandardCharsets.UTF_8)) {
                String[] f = line.split(FIELD);
                if (f.length == 3) out.add(new App(f[0], f[1], f[2]));
            }
        } catch (Exception e) {
            Log.w(TAG, "app cache", e);
        }
        return out;
    }

    private void writeCache(List<App> list) {
        StringBuilder s = new StringBuilder();
        for (App a : list) s.append(a.label.replace('\t', ' ').replace('\n', ' ')).append(FIELD).append(a.packageName).append(FIELD).append(a.activityName).append('\n');
        try {
            Files.write(cacheFile.toPath(), s.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "app cache", e);
        }
    }

    /** 自适应图标每次画都不便宜，画成固定大小的位图。 */
    private Bitmap load(App a) {
        String key = a.component();
        Bitmap b = icons.get(key);
        if (b != null) return b;
        try {
            Drawable d = pm.getActivityIcon(new ComponentName(a.packageName, a.activityName));
            b = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, iconSize, iconSize);
            d.draw(c);
            icons.put(key, b);
            return b;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    void showIcon(final App a, final ImageView into) {
        final String key = a.component();
        Bitmap cached = icons.get(key);
        into.setTag(key);
        into.setImageBitmap(cached);
        if (cached != null) return;
        visibleIcons.execute(() -> {
            final Bitmap b = load(a);
            ui.post(() -> {
                if (key.equals(into.getTag())) into.setImageBitmap(b);
            });
        });
    }
}
