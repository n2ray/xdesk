package com.termux.x11;

import com.termux.x11.app.R;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Settings › Devices › Add device › Built-in Linux, and the built-in Linux's own page: install, start and stop,
 * the GPU switch, reset (keeping the home or not) and the logs.
 */
final class BuiltinLinuxPage {
    private final Context c;
    private final SettingsUi s;
    private final BuiltinLinux bl;
    private LinearLayout content;
    /** Measured off the main thread when the page opens and after each change. */
    private volatile long usageMb = -1;

    private BuiltinLinuxPage(Context c) {
        this.c = c;
        s = new SettingsUi(c);
        bl = BuiltinLinux.get(c);
    }

    static Dialog open(Context c) {
        return new BuiltinLinuxPage(c).show();
    }

    private Dialog show() {
        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        final BuiltinLinux.Listener l = this::fill;
        bl.addListener(l);
        fill();
        measure();
        Dialog d = s.page(c.getString(R.string.xd_bl_title), content);
        d.setOnDismissListener(x -> bl.removeListener(l));
        return d;
    }

    private void measure() {
        new Thread(() -> {
            usageMb = bl.usageMb();
            content.post(this::fill);
        }, "builtin-linux-du").start();
    }

    private void fill() {
        content.removeAllViews();
        BuiltinLinux.State st = bl.state();
        LinearLayout state = s.card(content);
        boolean on = st == BuiltinLinux.State.RUNNING;
        String note = bl.progress();
        if (note == null) note = bl.error();
        s.dotRow(state, on, c.getString(label(st)), note);
        switch (st) {
            case NOT_INSTALLED:
                s.accentRow(state, c.getString(R.string.xd_bl_install),
                    c.getString(bl.imageCached() ? R.string.xd_bl_install_local_note : R.string.xd_bl_install_note), bl::install);
                break;
            case STOPPED:
                s.accentRow(state, c.getString(R.string.xd_bl_start), c.getString(R.string.xd_bl_start_note), bl::start);
                break;
            case RUNNING:
                s.accentRow(state, c.getString(R.string.xd_bl_open), c.getString(R.string.xd_bl_open_note), () -> {
                    Runnable attach = BuiltinLinux.attachHook;
                    if (attach != null) attach.run();
                });
                s.stopRowIn(state, c.getString(R.string.xd_dev_stop), c.getString(R.string.xd_bl_stop_note), bl::stop);
                break;
            default:
                break;
        }
        if (st == BuiltinLinux.State.NOT_INSTALLED) {
            LinearLayout about = s.section(content, c.getString(R.string.xd_bl_about));
            s.infoRow(about, c.getString(R.string.xd_bl_about_what), c.getString(R.string.xd_bl_about_what_note), null);
            s.infoRow(about, c.getString(R.string.xd_bl_about_termux), c.getString(R.string.xd_bl_about_termux_note), null);
            return;
        }
        boolean idle = st == BuiltinLinux.State.STOPPED || st == BuiltinLinux.State.RUNNING;

        LinearLayout gfx = s.section(content, c.getString(R.string.xd_bl_graphics));
        s.switchRow(gfx, c.getString(R.string.xd_bl_gpu), c.getString(R.string.xd_bl_gpu_note), bl.gpu(), v -> {
            bl.setGpu(v);
            if (bl.state() == BuiltinLinux.State.RUNNING)
                android.widget.Toast.makeText(c, R.string.xd_bl_gpu_restart, android.widget.Toast.LENGTH_LONG).show();
        });

        LinearLayout keep = s.section(content, c.getString(R.string.xd_bl_upkeep));
        s.infoRow(keep, c.getString(R.string.xd_bl_space), null,
            usageMb < 0 ? "…" : c.getString(R.string.xd_bl_space_mb, usageMb));
        s.linkRow(keep, c.getString(R.string.xd_bl_log), c.getString(R.string.xd_bl_log_note), "", this::showLog);
        if (idle) {
            s.linkRow(keep, c.getString(R.string.xd_bl_reset), c.getString(R.string.xd_bl_reset_note), "", () ->
                s.confirm(c.getString(R.string.xd_bl_reset_confirm_title), c.getString(R.string.xd_bl_reset_confirm),
                    c.getString(R.string.xd_bl_reset_action), SettingsUi.LEVEL_STOP, () -> { bl.reset(true); measureLater(); }));
            s.dangerRowIn(keep, c.getString(R.string.xd_bl_remove), c.getString(R.string.xd_bl_remove_note), () ->
                s.confirm(c.getString(R.string.xd_bl_remove_confirm_title), c.getString(R.string.xd_bl_remove_confirm),
                    c.getString(R.string.xd_bl_remove_action), SettingsUi.LEVEL_DANGER, () -> { bl.removeAll(); measureLater(); }));
        }
    }

    private void measureLater() {
        content.postDelayed(this::measure, 3000);
    }

    private static int label(BuiltinLinux.State st) {
        switch (st) {
            case NOT_INSTALLED: return R.string.xd_bl_state_not_installed;
            case INSTALLING: return R.string.xd_bl_state_installing;
            case STARTING: return R.string.xd_dev_state_starting;
            case RUNNING: return R.string.xd_dev_state_running;
            case STOPPING: return R.string.xd_bl_state_stopping;
            default: return R.string.xd_dev_state_not_running;
        }
    }

    private void showLog() {
        TextView t = Style.label(c, bl.logTail(), Style.TEXT, 11);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        int p = Style.dp(c, 12);
        t.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(c);
        sv.addView(t);
        s.page(c.getString(R.string.xd_bl_log), sv);
        sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
