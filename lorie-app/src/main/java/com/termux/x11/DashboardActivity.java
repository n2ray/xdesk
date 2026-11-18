package com.termux.x11;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.widget.FrameLayout;
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
    /** PermitCard while XDesk may not draw over other apps (owner, 2026-11-18); gone once it may. */
    private android.view.View permit;

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
        FrameLayout root = new FrameLayout(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
    }

    @Override protected void onStart() {
        super.onStart();
        // The switchboard is drawn over this as an overlay window. Without the permission for that there is only this
        // window: it says what is missing, and that the phone's list works meanwhile
        if (view != null) view.setHint(getString(R.string.xd_dash_choose));
        if (view != null) showPermit(!Settings.canDrawOverlays(this));
        if (view != null) view.start();
    }

    /** The card over the dashboard: its button opens the system's page on the phone, where the switch is. */
    private void showPermit(boolean show) {
        if (!show) {
            if (permit != null) ((FrameLayout) permit.getParent()).removeView(permit);
            permit = null;
            return;
        }
        if (permit != null) return;
        float density = getResources().getDisplayMetrics().density;
        permit = new PermitCard(this, density, getString(R.string.xd_permit_overlay), getString(R.string.xd_permit_overlay_dash_why),
            getString(R.string.xd_permit_overlay_path), PermitCard.ON_PHONE, null, this::openOverlaySettingsOnPhone, null).view;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(Math.min(420 * density,
            getResources().getDisplayMetrics().widthPixels * 0.8f)), -2, Gravity.CENTER);
        ((FrameLayout) view.getParent()).addView(permit, lp);
    }

    private void openOverlaySettingsOnPhone() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
            startActivity(i, o.toBundle());
        } catch (RuntimeException e) {
            Log.w("XDeskDash", "no overlay settings", e);
        }
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
