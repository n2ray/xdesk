package com.termux.x11;

import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.x11.app.R;

/**
 * The “Use Shizuku” page (Shizuku.dc.html): the three steps — install, start, allow — with their state, the ways out
 * of the cases that stop it (not installed, too old, denied, stopped after a restart), and the way back to wireless
 * debugging. The page is redrawn from AppSession's state each time it is opened or the state changes; it only draws
 * and calls the actions XDesk already had.
 */
final class ShizukuPage {
    interface Actions {
        /** Not installed: Shizuku's site. */
        void download();
        /** Start it: Shizuku's own app. */
        void openShizuku();
        /** Ask Shizuku for the permission XDesk needs. */
        void requestPermission();
        /** Give up on Shizuku and go back to the built-in wireless-debugging service. */
        void useWireless();
        /** The user left the page with Back. */
        void cancelled();
    }

    private final Context c;
    private final SettingsUi ui;
    private final AppSession apps;
    private final Actions actions;
    private final Dialog dialog;
    private final LinearLayout content;
    private boolean closing;

    ShizukuPage(Context context, SettingsUi ui, AppSession apps, Actions actions) {
        c = context;
        this.ui = ui;
        this.apps = apps;
        this.actions = actions;
        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        fill();
        dialog = ui.page(c.getString(R.string.xd_act_use_shizuku), content);
        dialog.setOnDismissListener(d -> {
            if (!closing) actions.cancelled();
        });
    }

    private int dp(float v) {
        return Style.dp(c, v);
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    /** Closed by XDesk, not by the user: nothing to cancel. */
    void close() {
        closing = true;
        dialog.dismiss();
    }

    /** The state changed (the user started Shizuku, granted it): the page follows. */
    void refresh() {
        fill();
    }

    private void fill() {
        content.removeAllViews();
        int state = apps.shizukuState();
        boolean installed = state != AppSession.SHIZUKU_NOT_INSTALLED;
        boolean running = state == AppSession.SHIZUKU_READY || state == AppSession.SHIZUKU_NO_PERMISSION;
        boolean ready = state == AppSession.SHIZUKU_READY;
        boolean denied = state == AppSession.SHIZUKU_NO_PERMISSION && apps.permissionDeniedForGood();

        TextView intro = Style.label(c, c.getString(R.string.xd_shizuku_intro), Style.TEXT_SOFT, 13);
        intro.setPadding(dp(6), 0, dp(6), dp(6));
        intro.setLineSpacing(dp(2), 1f);
        content.addView(intro);

        // 1. Install: done with a green check and the version, else its download
        step(installed, false, installed ? "✓" : "1",
            c.getString(R.string.xd_shizuku_step_install),
            installed ? c.getString(R.string.xd_shizuku_installed, version()) : c.getString(R.string.xd_shizuku_not_installed),
            installed ? Style.OK : Style.TEXT_DIM,
            installed ? null : c.getString(R.string.xd_shizuku_download),
            installed ? null : actions::download);

        // 2. Start: the current step while it is not running; its own app opens it
        boolean startCurrent = installed && !running;
        step(running, startCurrent, running ? "✓" : "2",
            c.getString(R.string.xd_shizuku_step_start),
            c.getString(R.string.xd_shizuku_start_note),
            running ? Style.OK : Style.TEXT_DIM,
            running || !installed ? null : c.getString(R.string.xd_open_shizuku),
            running || !installed ? null : actions::openShizuku);

        // 3. Allow: done once granted; while it is not, the card asks Shizuku (click or its button)
        boolean allowCurrent = running && !ready;
        // Refused for good: Shizuku asks no more, so the way is its own app management (the row below)
        String allowAction = allowCurrent && !denied ? c.getString(R.string.xd_shizuku_ask_action) : null;
        LinearLayout allow = step(ready, allowCurrent, ready ? "✓" : "3",
            c.getString(R.string.xd_shizuku_step_allow),
            c.getString(R.string.xd_shizuku_allow_note),
            ready ? Style.OK : Style.TEXT_DIM,
            allowAction, allowCurrent ? actions::requestPermission : null);
        if (allowCurrent && !denied) {
            allow.setOnClickListener(v -> {
                Style.tick(v);
                actions.requestPermission();
            });
        }

        // The cases that stop it, each a row with what to do (Shizuku.dc.html's 其他情况)
        boolean tooOld = state == AppSession.SHIZUKU_TOO_OLD;
        boolean stopped = state == AppSession.SHIZUKU_NOT_RUNNING;
        if (!installed || tooOld || denied || stopped) {
            TextView head = Style.label(c, c.getString(R.string.xd_shizuku_other), Style.TEXT_DIM, 12);
            head.setPadding(dp(6), dp(16), dp(6), dp(6));
            content.addView(head);
            LinearLayout card = ui.card(content);
            if (!installed)
                row(card, "1", R.string.xd_shizuku_missing_title, R.string.xd_shizuku_not_installed,
                    R.string.xd_shizuku_download, actions::download);
            if (tooOld)
                row(card, "!", R.string.xd_shizuku_old_row, R.string.xd_shizuku_old_row_note,
                    R.string.xd_shizuku_download, actions::download);
            if (denied)
                row(card, "!", R.string.xd_shizuku_denied_row, R.string.xd_shizuku_denied_row_note,
                    R.string.xd_shizuku_open, actions::openShizuku);
            if (stopped)
                row(card, "!", R.string.xd_shizuku_restart_row, R.string.xd_shizuku_restart_row_note, 0, null);
        }

        // Back to XDesk's own service, centered at the bottom (btnq)
        TextView back = Style.button(c, c.getString(R.string.xd_shizuku_back), 0x00000000, 14, 12);
        back.setTextColor(Style.ACCENT_2);
        back.setPadding(dp(6), 0, dp(6), 0);
        back.setOnClickListener(v -> {
            Style.tick(v);
            actions.useWireless();
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(44));
        bp.gravity = Gravity.CENTER_HORIZONTAL;
        bp.topMargin = dp(16);
        content.addView(back, bp);
    }

    /** One numbered step card: a 28 dp marker, its title and state line, and a button when there is something to do. */
    private LinearLayout step(boolean done, boolean current, String mark, String title, String note, int noteColor,
                              String action, Runnable onAction) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = Style.rounded(c, Style.SURFACE, 16);
        if (current) bg.setStroke(dp(1.5f), Style.ACCENT);
        card.setBackground(bg);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.TOP);
        TextView marker = Style.label(c, mark, done ? Style.BACKGROUND : current ? 0xFFFFFFFF : Style.TEXT_DIM, 13);
        marker.setTypeface(Typeface.DEFAULT_BOLD);
        marker.setGravity(Gravity.CENTER);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(done ? Style.OK : current ? Style.ACCENT : Style.SURFACE_RAISED);
        marker.setBackground(disc);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(28), dp(28));
        mp.rightMargin = dp(14);
        row.addView(marker, mp);
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView t = Style.label(c, title, Style.TEXT, 15);
        words.addView(t);
        TextView n = Style.label(c, note, noteColor, 12);
        n.setPadding(0, dp(2), 0, 0);
        n.setLineSpacing(dp(2), 1f);
        words.addView(n);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        if (action != null && onAction != null) {
            TextView b = Style.button(c, action, Style.ACCENT, 14, 12);
            b.setTextColor(android.graphics.Color.WHITE);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setPadding(dp(20), 0, dp(20), 0);
            b.setOnClickListener(v -> {
                Style.tick(v);
                onAction.run();
            });
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, dp(44));
            ap.leftMargin = dp(42);
            ap.topMargin = dp(10);
            card.addView(b, ap);
        }
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(10);
        content.addView(card, cp);
        return card;
    }

    /** A row of “其他情况”: a 24 dp mark, the case, and a button when there is one. */
    private void row(LinearLayout card, String mark, int title, int note, int action, Runnable onAction) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView marker = Style.label(c, mark, Style.WARN, 12);
        marker.setTypeface(Typeface.DEFAULT_BOLD);
        marker.setGravity(Gravity.CENTER);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(0x29F0883E);
        marker.setBackground(disc);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(24), dp(24));
        mp.rightMargin = dp(12);
        row.addView(marker, mp);
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(Style.label(c, c.getString(title), Style.TEXT, 15));
        TextView n = Style.label(c, c.getString(note), Style.TEXT_DIM, 12);
        n.setPadding(0, dp(2), 0, 0);
        words.addView(n);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != 0 && onAction != null) {
            TextView b = Style.button(c, c.getString(action), Style.SURFACE_RAISED, 12, 10);
            b.setPadding(dp(12), 0, dp(12), 0);
            b.setOnClickListener(v -> {
                Style.tick(v);
                onAction.run();
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(32));
            bp.leftMargin = dp(10);
            row.addView(b, bp);
        }
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Shizuku's installed version for the first step ("13.6.0"), "?" when the system will not say. */
    private String version() {
        try {
            String v = c.getPackageManager().getPackageInfo(AppSession.SHIZUKU_PACKAGE, 0).versionName;
            return v != null ? v : "?";
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return "?";
        }
    }
}
