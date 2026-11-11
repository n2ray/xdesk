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
import android.os.UserHandle;
import android.os.UserManager;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;
import com.termux.x11.app.R;
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
        /** The user it is installed in: one of XDesk's own profiles (a dual app, a work profile), or OWN_USER. */
        final int user;

        App(String label, String packageName, String activityName) {
            this(label, packageName, activityName, OWN_USER);
        }

        App(String label, String packageName, String activityName, int user) {
            this.label = label;
            this.packageName = packageName;
            this.activityName = activityName;
            this.user = user;
        }

        /** Its key everywhere, docks and recent lists included: "package/activity", with "@<user>" for another profile. */
        String component() {
            return user == OWN_USER ? flat() : flat() + "@" + user;
        }

        /** Without the user, as ComponentName.flattenToString. */
        String flat() {
            return packageName + "/" + activityName;
        }

        /** For the injector's closeApp: only this user's tasks. */
        String packageInUser() {
            return packageName + "@" + (user == OWN_USER ? Process.myUserHandle().hashCode() : user);
        }

        boolean inOtherProfile() {
            return user != OWN_USER;
        }

        UserHandle userHandle() {
            return user == OWN_USER ? Process.myUserHandle() : UserHandle.getUserHandleForUid(user * PER_USER_RANGE);
        }

        /** 不分大小写，按名字和包名。 */
        boolean matches(String lowerCaseQuery) {
            return label.toLowerCase().contains(lowerCaseQuery) || packageName.toLowerCase().contains(lowerCaseQuery);
        }
    }

    static final int OWN_USER = -1;
    /** UserHandle.PER_USER_RANGE, hidden. */
    private static final int PER_USER_RANGE = 100000;
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
        addOtherProfiles(out);
        final Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> collator.compare(a.label, b.label));
        return out;
    }

    /**
     * Apps in XDesk's other profiles: HyperOS's dual apps are a clone profile (user 999), and a work profile is one too.
     * Shown as "label (dual)", next to the original. The injector starts them with am start --user, XDesk itself through LauncherApps.
     */
    private void addOtherProfiles(List<App> out) {
        try {
            UserManager um = context.getSystemService(UserManager.class);
            LauncherApps la = context.getSystemService(LauncherApps.class);
            UserHandle me = Process.myUserHandle();
            for (UserHandle u : um.getUserProfiles()) {
                if (u.equals(me)) continue;
                int id = u.hashCode();
                for (LauncherActivityInfo info : la.getActivityList(null, u)) {
                    ComponentName cn = info.getComponentName();
                    if (context.getPackageName().equals(cn.getPackageName())) continue;
                    String label = context.getString(R.string.xd_app_other_profile, String.valueOf(info.getLabel()));
                    out.add(new App(label, cn.getPackageName(), cn.getClassName(), id));
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "other profiles", e);
        }
    }

    private List<App> readCache() {
        ArrayList<App> out = new ArrayList<>();
        try {
            if (!cacheFile.exists()) return out;
            for (String line : Files.readAllLines(cacheFile.toPath(), StandardCharsets.UTF_8)) {
                String[] f = line.split(FIELD);
                if (f.length == 3) out.add(new App(f[0], f[1], f[2]));
                else if (f.length == 4) out.add(new App(f[0], f[1], f[2], Integer.parseInt(f[3])));
            }
        } catch (Exception e) {
            Log.w(TAG, "app cache", e);
        }
        return out;
    }

    private void writeCache(List<App> list) {
        StringBuilder s = new StringBuilder();
        for (App a : list) {
            s.append(a.label.replace('\t', ' ').replace('\n', ' ')).append(FIELD).append(a.packageName).append(FIELD).append(a.activityName);
            if (a.inOtherProfile()) s.append(FIELD).append(a.user);
            s.append('\n');
        }
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
            Drawable d = a.inOtherProfile() ? otherProfileIcon(a) : pm.getActivityIcon(new ComponentName(a.packageName, a.activityName));
            if (d == null) return null;
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

    /** With the profile's badge, as the launcher shows it. */
    private Drawable otherProfileIcon(App a) {
        LauncherApps la = context.getSystemService(LauncherApps.class);
        for (LauncherActivityInfo info : la.getActivityList(a.packageName, a.userHandle())) {
            if (info.getComponentName().getClassName().equals(a.activityName)) return info.getBadgedIcon(0);
        }
        return null;
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
