package com.termux.x11;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import java.util.Locale;
import java.util.WeakHashMap;

/** Apply desktop language inside our process. A framework locale relaunch tears down the X session. */
public final class XdesxApp extends LorieApp implements Application.ActivityLifecycleCallbacks {
    private final WeakHashMap<Activity,Boolean> screens=new WeakHashMap<>();

    @Override public void onCreate() {
        super.onCreate();
        refreshLanguage();
        registerActivityLifecycleCallbacks(this);
    }
    private void localize(Context screen) {
        int choice=getSharedPreferences("xdesx-panel",MODE_PRIVATE).getInt("language",0);
        Locale locale=choice==1?Locale.ENGLISH:choice==2?Locale.SIMPLIFIED_CHINESE:
            choice==3?Locale.TRADITIONAL_CHINESE:Resources.getSystem().getConfiguration().getLocales().get(0);
        Resources res=screen.getResources();
        Configuration config=new Configuration(res.getConfiguration());
        if(locale.equals(config.getLocales().get(0)))return;
        config.setLocale(locale);
        res.updateConfiguration(config,res.getDisplayMetrics());
    }
    void refreshLanguage() {
        localize(this);
        for(Activity screen:screens.keySet())localize(screen);
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        refreshLanguage();
    }
    @Override public void onActivityPreCreated(Activity screen,Bundle state) {
        screens.put(screen,true);
        localize(screen);
    }
    @Override public void onActivityDestroyed(Activity screen){screens.remove(screen);}
    @Override public void onActivityCreated(Activity screen,Bundle state){}
    private Activity visibleDesktop;
    private final java.util.Set<Activity> externalLogins=new java.util.HashSet<>();
    private Runnable desktopChanged;
    void observeDesktop(Runnable listener) {desktopChanged=listener;}
    boolean desktopVisible(int display) {
        return externalLogins.isEmpty() && visibleDesktop!=null && visibleDesktop.getDisplay()!=null &&
            visibleDesktop.getDisplay().getDisplayId()==display;
    }
    @Override public void onActivityStarted(Activity screen){
        if(screen instanceof com.termux.x11.screenshare.ScreenShareLoginActivity &&
                screen.getIntent().hasExtra(com.termux.x11.screenshare.ScreenShareLoginActivity.EXTRA_DISPLAY)) {
            externalLogins.add(screen);
            if(desktopChanged!=null)desktopChanged.run();
        }
        if(screen instanceof MainActivity && screen.getIntent().getBooleanExtra("xdesx_quiet",false)){
            visibleDesktop=screen;
            if(desktopChanged!=null)desktopChanged.run();
        }
    }
    @Override public void onActivityResumed(Activity screen){}
    @Override public void onActivityPaused(Activity screen){}
    @Override public void onActivityStopped(Activity screen){
        if(externalLogins.remove(screen)){
            if(desktopChanged!=null)desktopChanged.run();
        }
        if(visibleDesktop==screen){
            visibleDesktop=null;
            if(desktopChanged!=null)desktopChanged.run();
        }
    }
    @Override public void onActivitySaveInstanceState(Activity screen,Bundle state){}
}
