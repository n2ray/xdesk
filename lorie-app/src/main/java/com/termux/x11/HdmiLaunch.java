package com.termux.x11;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** The identities a single explicit launch created, including an app's launcher trampoline. */
final class HdmiLaunch {
    private final Object atm;
    final int display, user;
    final String pkg;
    private final IBinder cookie=new Binder();
    private final String identifier="xdesx-"+UUID.randomUUID();
    private final HashSet<Integer> previous=new HashSet<>(), owned=new HashSet<>();
    private long started;
    private HdmiLaunch(Object atm,int display,int user,String pkg) {
        this.atm=atm;
        this.display=display;
        this.user=user;
        this.pkg=pkg;
    }

    static HdmiLaunch start(String component,int display) {
        int at=component.lastIndexOf('@');
        ComponentName target=ComponentName.unflattenFromString(at<0?component:component.substring(0,at));
        if (target==null) return null;
        HdmiLaunch launch=null;
        try {
            int user=at<0?(int)ActivityManager.class.getMethod("getCurrentUser").invoke(null):Integer.parseInt(component.substring(at+1));
            launch=new HdmiLaunch(TaskMover.taskManager(),display,user,target.getPackageName());
            if (launch.snapshot() && !allowsMultiple(target,user)) return null;
            launch.startActivity(target);
            long deadline=SystemClock.uptimeMillis()+3000;
            do {
                if (launch.activeTask()>=0) return launch;
                SystemClock.sleep(50);
            } while (SystemClock.uptimeMillis()<deadline);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.w("XDesX","direct HDMI launch",e);
        }
        if (launch!=null) launch.closeExcept(-1);
        return null;
    }

    private static int number(Object task,String field) throws ReflectiveOperationException {
        return task.getClass().getField(field).getInt(task);
    }

    private boolean snapshot() throws ReflectiveOperationException {
        for (Object t:TaskMover.recentTasks(atm,user)) previous.add(number(t,"taskId"));
        boolean live=false;
        for (Object t:(List<?>)atm.getClass().getMethod("getAllRootTaskInfos").invoke(atm)) {
            previous.add(number(t,"taskId"));
            int[] children=(int[])t.getClass().getField("childTaskIds").get(t);
            if (children!=null) for (int id:children) previous.add(id);
            if (number(t,"userId")==user && TaskMover.hasBasePackage(t,pkg)) live=true;
        }
        return live;
    }

    private static boolean allowsMultiple(ComponentName target,int user) throws ReflectiveOperationException {
        Object pm=Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
        ActivityInfo info;
        try {
            info=(ActivityInfo)pm.getClass().getMethod("getActivityInfo",ComponentName.class,long.class,int.class).invoke(pm,target,0L,user);
        } catch(NoSuchMethodException old) {
            info=(ActivityInfo)pm.getClass().getMethod("getActivityInfo",ComponentName.class,int.class,int.class).invoke(pm,target,0,user);
        }
        return info!=null && (info.launchMode==ActivityInfo.LAUNCH_MULTIPLE || info.launchMode==ActivityInfo.LAUNCH_SINGLE_TOP);
    }

    private void startActivity(ComponentName target) throws ReflectiveOperationException {
        Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(target).setIdentifier(identifier)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK |
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        ActivityOptions options=ActivityOptions.makeBasic().setLaunchDisplayId(display);
        ActivityOptions.class.getMethod("setLaunchCookie",IBinder.class).invoke(options,cookie);
        ActivityOptions.class.getMethod("setLaunchWindowingMode",int.class).invoke(options,1);
        Method start=atm.getClass().getMethod("startActivityAsUser",Class.forName("android.app.IApplicationThread"),String.class,String.class,
                Intent.class,String.class,IBinder.class,String.class,int.class,int.class,Class.forName("android.app.ProfilerInfo"),
                android.os.Bundle.class,int.class);
        started=SystemClock.uptimeMillis();
        int result=(int)start.invoke(atm,null,"com.android.shell",null,intent,null,null,null,0,0,null,options.toBundle(),user);
        if (result<0) throw new IllegalStateException("activity start returned "+result);
    }

    /** Ranked by the real foreground activity. A self-relaunch may create a second root task. */
    synchronized int activeTask() {
        int active=-1;
        try {
            List<?> tasks=(List<?>)atm.getClass().getMethod("getAllRootTaskInfosOnDisplay",int.class).invoke(atm,display);
            int background=-1;
            for(Object t:tasks) {
                int id=number(t,"taskId");
                if (previous.contains(id) || number(t,"userId")!=user || !TaskMover.hasBasePackage(t,pkg)) continue;
                Intent base=(Intent)t.getClass().getField("baseIntent").get(t);
                List<?> cookies=(List<?>)t.getClass().getField("launchCookies").get(t);
                boolean marked=(base!=null && identifier.equals(base.getIdentifier())) || (cookies!=null && cookies.contains(cookie));
                if (marked) owned.add(id);
            }
            for(Object t:tasks) {
                int id=number(t,"taskId");
                if (previous.contains(id) || number(t,"userId")!=user || !TaskMover.hasBasePackage(t,pkg)) continue;
                if (!owned.contains(id)) {
                    if (owned.isEmpty() || SystemClock.uptimeMillis()-started>10000 || !selfLaunched(id)) continue;
                    owned.add(id);
                }
                if (t.getClass().getField("topActivity").get(t)==null) continue;
                if (background<0) background=id;
                if (active<0 && TaskMover.visible(t)) active=id;
            }
            return active>=0?active:background;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w("XDesX","identify HDMI task",e);
            return -1;
        }
    }

    private boolean selfLaunched(int task) {
        String[] dump=InjectorService.exec(1500,"/system/bin/dumpsys","activity","recents");
        if (dump[0]!=null) return false;
        boolean match=false;
        for(String line:dump[1].split("\n")) {
            String t=line.trim();
            if(t.startsWith("* Recent #") || t.startsWith("Recent #")) {
                match=t.matches(".*#"+task+"(?: |}).*");
            } else if (match && t.matches(".*\\bmCallingPackage="+java.util.regex.Pattern.quote(pkg)+"(?:\\s.*|$)")) return true;
        }
        return false;
    }

    synchronized void closeExcept(int keep) {
        for(int id:owned) if(id!=keep) TaskMover.closeOwnedTask(id,user,pkg);
    }
}
