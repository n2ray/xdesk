package com.termux.x11;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.Display;
import android.view.WindowInsets;
import com.termux.x11.app.R;
import java.lang.ref.WeakReference;

/**
 * XDesk's own window at the bottom of the external display, needing no Shizuku. Apps and the Termux window open on top
 * of it, and when an app exits this is what gets uncovered, rather than mirroring. 结束 XDesk removes it.
 *
 * It is not an app to move to the phone and does not count as an app in front: both follow from it being XDesk's own
 * package (see TaskMover). It is an Activity, not a Presentation, so the Presentation warning does not see it either.
 */
public class DashboardActivity extends Activity {
    private static WeakReference<DashboardActivity> instance = new WeakReference<>(null);

    static boolean isAlive() {
        DashboardActivity a = instance.get();
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    static void closeIfAlive() {
        DashboardActivity a = instance.get();
        if (a != null && !a.isFinishing()) a.finishAndRemoveTask();
    }

    private DashboardView view;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // On the phone it has no purpose. The system moves it there when the display goes away
        Display d = getDisplay();
        if (d == null || d.getDisplayId() == Display.DEFAULT_DISPLAY) {
            finishAndRemoveTask();
            return;
        }
        instance = new WeakReference<>(this);
        getWindow().setDecorFitsSystemWindows(false);
        view = new DashboardView(this, getString(R.string.xd_dash_choose), true);
        setContentView(view);
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
    }

    @Override protected void onStart() {
        super.onStart();
        if (view != null) view.start();
    }

    /** Covered by an app or a desktop: nothing to update. */
    @Override protected void onStop() {
        if (view != null) view.stop();
        super.onStop();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
    }

    /** Input can reach this window. Back must not close it, only 结束 XDesk does. */
    @Override public void onBackPressed() { }
}
