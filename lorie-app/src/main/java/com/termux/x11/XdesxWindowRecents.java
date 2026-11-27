package com.termux.x11;

import android.app.ActivityManager;
import android.app.TaskInfo;
import android.content.ComponentName;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;

/** Task IDs are recorded only after observing them on the private display of this launch. */
final class XdesxWindowRecents {
    private static final String TAG = "XDesX";
    private final HashMap<Integer, Window> windows = new HashMap<>();

    private static final class Window {
        final String pkg;
        final int user;
        final HashSet<Integer> existing = new HashSet<>();
        final HashSet<Integer> owned = new HashSet<>();
        Window(String pkg, int user) { this.pkg = pkg; this.user = user; }

        boolean matches(TaskInfo info) throws ReflectiveOperationException {
            ComponentName base = info.baseIntent == null ? null : info.baseIntent.getComponent();
            return base != null && pkg.equals(base.getPackageName())
                    && info.getClass().getField("userId").getInt(info) == user;
        }
    }

    synchronized void begin(int display, String pkg, String userSpec) {
        try {
            int user = "current".equals(userSpec)
                    ? (int) ActivityManager.class.getMethod("getCurrentUser").invoke(null) : Integer.parseInt(userSpec);
            Object atm = TaskMover.taskManager();
            Window w = new Window(pkg, user);
            for (Object item : TaskMover.recentTasks(atm, user)) w.existing.add(((TaskInfo) item).taskId);
            for (Object item : (java.util.List<?>) atm.getClass().getMethod("getAllRootTaskInfos").invoke(atm)) {
                w.existing.add(((TaskInfo) item).taskId);
                int[] children = (int[]) item.getClass().getField("childTaskIds").get(item);
                if (children != null) for (int id : children) w.existing.add(id);
            }
            windows.put(display, w);
        } catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG, "Cannot track window recents", e); }
    }

    synchronized void observe(TaskInfo task) {
        try {
            Window w = windows.get(task.getClass().getField("displayId").getInt(task));
            if (w != null && !w.existing.contains(task.taskId) && w.matches(task)) w.owned.add(task.taskId);
        } catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG, "Cannot identify window task", e); }
    }

    synchronized void close(int display) {
        Window w = windows.remove(display);
        if (w == null) return;
        try {
            Object atm = TaskMover.taskManager();
            Method remove = atm.getClass().getMethod("removeTask", int.class);
            for (Object item : TaskMover.recentTasks(atm, w.user)) {
                TaskInfo task = (TaskInfo) item;
                if (w.existing.contains(task.taskId) || !w.matches(task)) continue;
                int location = task.getClass().getField("displayId").getInt(task);
                // An OEM may detach an empty task before sending its removal event.
                boolean stale = w.owned.contains(task.taskId) && !task.isRunning && task.numActivities == 0;
                if (location == display || stale) {
                    Log.i(TAG, "Closing desktop recent task " + task.taskId + " display=" + display
                            + " removed=" + remove.invoke(atm, task.taskId));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG, "Cannot close window recents", e); }
    }
}
