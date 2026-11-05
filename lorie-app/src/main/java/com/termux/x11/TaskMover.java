package com.termux.x11;

import android.content.ComponentName;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Moves tasks from the external display back to the phone without closing them. Runs in the shell-user process. All of
 * this is hidden API or command output with no promise of stability, so each step has a fallback and failure comes back
 * as text: the phone must still end the session normally.
 */
final class TaskMover {
    private static final String TAG = "XDeskInjector";
    private static final int PHONE_DISPLAY = 0, ACTIVITY_TYPE_STANDARD = 1;
    private static final long AM_TIMEOUT_MS = 5000;

    private TaskMover() { }

    private static Object taskManager() throws ReflectiveOperationException {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "activity_task");
        return Class.forName("android.app.IActivityTaskManager$Stub").getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
    }

    /** Skip XDesk's own window and things that are not normal apps, such as a launcher on that display. */
    private static boolean movable(Object info, String ownPackage) {
        try {
            for (String name : new String[]{"baseActivity", "topActivity"}) {
                Field f = info.getClass().getField(name);
                ComponentName c = (ComponentName) f.get(info);
                if (c != null && c.getPackageName().equals(ownPackage)) return false;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.i(TAG, "task component unknown: " + e);
        }
        try {
            Method m = info.getClass().getMethod("getActivityType");
            return (Integer) m.invoke(info) == ACTIVITY_TYPE_STANDARD;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.i(TAG, "task type unknown: " + e);
            return true;
        }
    }

    private static List<Integer> listFromSystem(Object atm, int display, String ownPackage) throws ReflectiveOperationException {
        List<?> infos = (List<?>) atm.getClass().getMethod("getAllRootTaskInfosOnDisplay", int.class).invoke(atm, display);
        ArrayList<Integer> ids = new ArrayList<>();
        for (Object info : infos) {
            if (movable(info, ownPackage)) ids.add(info.getClass().getField("taskId").getInt(info));
        }
        return ids;
    }

    /**
     * Reads `am stack list`, whose blocks start with `RootTask id=N ... displayId=D` and hold the type and components.
     * Returns the ids in the order listed.
     */
    static List<Integer> parseStackList(String text, int display, String ownPackage) {
        ArrayList<Integer> ids = new ArrayList<>();
        int id = -1;
        boolean wanted = false;
        for (String line : (text + "\nRootTask id=-1 displayId=-1").split("\n")) {
            String t = line.trim();
            if (t.startsWith("RootTask ") || t.startsWith("Stack ")) {
                if (wanted) ids.add(id);
                id = -1;
                wanted = false;
                for (String word : t.split("\\s+")) {
                    if (word.startsWith("id=")) id = parseInt(word.substring(3));
                    else if (word.startsWith("displayId=")) wanted = parseInt(word.substring(10)) == display;
                }
                if (id < 0) wanted = false;
            } else if (t.contains(ownPackage + "/") || t.contains("mActivityType=home") || t.contains("mActivityType=recents") || t.contains("mActivityType=assistant")) {
                wanted = false;
            }
        }
        return ids;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Removes the app's tasks like swiping them away from recent tasks. Only when that is not available it force stops
     * the app, which is harsher but also closes it.
     */
    static String closeApp(String pkg) {
        try {
            Object atm = taskManager();
            List<?> infos = (List<?>) atm.getClass().getMethod("getAllRootTaskInfos").invoke(atm);
            Method remove = atm.getClass().getMethod("removeTask", int.class);
            for (Object info : infos) {
                if (belongsTo(info, pkg)) remove.invoke(atm, info.getClass().getField("taskId").getInt(info));
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.i(TAG, "removeTask failed, force stopping: " + e);
        }
        String[] r = InjectorService.exec(AM_TIMEOUT_MS, "/system/bin/am", "force-stop", pkg);
        return r[0];
    }

    private static boolean belongsTo(Object info, String pkg) throws ReflectiveOperationException {
        for (String name : new String[]{"baseActivity", "topActivity"}) {
            ComponentName c = (ComponentName) info.getClass().getField(name).get(info);
            if (c != null && c.getPackageName().equals(pkg)) return true;
        }
        return false;
    }

    /** Returns null when everything on the display went to the phone, otherwise what went wrong. */
    static String moveAllToPhone(int display, String ownPackage) {
        Object atm = null;
        List<Integer> ids;
        try {
            atm = taskManager();
            ids = listFromSystem(atm, display, ownPackage);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.i(TAG, "no task list from the system, trying am: " + e);
            String[] r = InjectorService.exec(AM_TIMEOUT_MS, "/system/bin/am", "stack", "list");
            if (r[0] != null) return "无法列出外接屏上的任务：" + r[0];
            ids = parseStackList(r[1], display, ownPackage);
        }
        // Listed top first. Move the bottom one first, so what was in front stays in front on the phone
        Collections.reverse(ids);
        String error = null;
        for (int id : ids) {
            String e = move(atm, id);
            if (e != null) error = e;
        }
        Log.i(TAG, "moved " + ids + " to the phone: " + (error == null ? "ok" : error));
        return error;
    }

    private static String move(Object atm, int id) {
        if (atm != null) {
            try {
                atm.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class).invoke(atm, id, PHONE_DISPLAY);
                return null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                Log.i(TAG, "moveRootTaskToDisplay failed, trying am: " + e);
            }
        }
        String[] r = InjectorService.exec(AM_TIMEOUT_MS, "/system/bin/am", "display", "move-stack", String.valueOf(id), String.valueOf(PHONE_DISPLAY));
        if (r[0] != null) return r[0];
        return r[1].contains("Error") || r[1].contains("Exception") ? r[1].trim() : null;
    }
}
