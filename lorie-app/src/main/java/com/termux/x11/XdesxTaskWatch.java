package com.termux.x11;

import android.os.*;
import android.util.Log;
import java.lang.reflect.*;
import java.util.HashMap;
import java.util.List;

/** The shell observes actual task removals. Idle windows need no polling. */
final class XdesxTaskWatch {
    private final XdesxWindowRecents recents;
    XdesxTaskWatch(XdesxWindowRecents recents) { this.recents=recents; }
    private final HashMap<Integer,IXdesxWindowListener> windows=new HashMap<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private int nativeWindow = -1, nativeDisplay = -1, nativeTask = -1;

    private volatile HdmiLaunch direct;
    private long emptySince;
    synchronized void fullscreen(int window, int external, int task,HdmiLaunch launch) {
        direct=launch;
        emptySince=0;
        nativeWindow = external >= 0 ? window : -1;
        nativeDisplay = external;
        nativeTask = task;
    }

    private volatile Runnable gameFrontChanged;
    void gameFrontChanged(Runnable callback) {
        gameFrontChanged = callback;
    }

    private Object atm,listener;
    private Class<?> type;
    private final Runnable check=this::checkEmpty;

    synchronized void add(int display,IXdesxWindowListener callback) {
        windows.put(display,callback);
        if (listener!=null) return;
        try {
            atm=TaskMover.taskManager();
            type=Class.forName("android.app.ITaskStackListener");
            Class<?> stub=Class.forName("android.app.ITaskStackListener$Stub");
            Field f=stub.getDeclaredField("TRANSACTION_onTaskRemoved");
            f.setAccessible(true);
            final int removed=f.getInt(null);
            int frontCode = -1;
            try {
                Field front = stub.getDeclaredField("TRANSACTION_onTaskMovedToFront");
                front.setAccessible(true);
                frontCode = front.getInt(null);
            } catch (ReflectiveOperationException ignored) { }
            final int movedToFront = frontCode;
            Binder endpoint=new Binder() {
                @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
                    if (code==INTERFACE_TRANSACTION) return super.onTransact(code,data,reply,flags);
                    if (code == movedToFront) {
                        data.enforceInterface("android.app.ITaskStackListener");
                        android.app.ActivityManager.RunningTaskInfo info =
                                data.readTypedObject(android.app.ActivityManager.RunningTaskInfo.CREATOR);
                        if (info != null) recents.observe(info);
                        Runnable checkGame = gameFrontChanged;
                        if (checkGame != null) handler.post(checkGame);
                        HdmiLaunch launch=direct;
                        if (launch!=null && info!=null && info.topActivity!=null && launch.pkg.equals(info.topActivity.getPackageName()))
                            handler.post(() -> { if (direct==launch) launch.activeTask(); });
                    }
                    if (code==removed) {
                        data.enforceInterface("android.app.ITaskStackListener");
                        handler.removeCallbacks(check);
                        handler.postDelayed(check,200);
                    }
                    return true;
                }
            };
            Object proxy=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a) -> {
                if (m.getName().equals("asBinder")) return endpoint;
                throw new UnsupportedOperationException(m.getName());
            });
            atm.getClass().getMethod("registerTaskStackListener",type).invoke(atm,proxy);
            listener=proxy;
        } catch (ReflectiveOperationException | RuntimeException e) { Log.w("XDesX","cannot watch task removal",e); }
    }
    private synchronized void checkEmpty() {
        if (listener==null) return;
        for (int display:windows.keySet()) try {
            List<?> tasks=(List<?>)atm.getClass().getMethod("getAllRootTaskInfosOnDisplay",int.class).invoke(atm, display == nativeWindow ? nativeDisplay : display);
            if (display == nativeWindow) {
                boolean present = false;
                for (Object info : tasks) {
                    if (info.getClass().getField("taskId").getInt(info) == nativeTask) present = true;
                }
                if (direct!=null) {
                    present=direct.activeTask()>=0;
                    if (present) emptySince=0;
                    else if (emptySince==0) {
                        emptySince=SystemClock.elapsedRealtime();
                        handler.postDelayed(check,650);
                        continue;
                    }
                }
                if (!present) windows.get(display).taskEnded(nativeTask);
            } else if (tasks.isEmpty()) windows.get(display).taskEnded(-1);
        } catch (ReflectiveOperationException | RemoteException | RuntimeException e) { Log.w("XDesX","check window tasks",e); }
    }
    synchronized void remove(int display) {
        if (nativeWindow == display) fullscreen(-1, -1, -1,null);
        windows.remove(display);
        if (windows.isEmpty()) clear();
    }
    synchronized void clear() {
        windows.clear();
        handler.removeCallbacks(check);
        if (listener==null) return;
        try { atm.getClass().getMethod("unregisterTaskStackListener",type).invoke(atm,listener); }
        catch (ReflectiveOperationException | RuntimeException e) { Log.w("XDesX","unregister task listener",e); }
        listener=null;
    }
}
