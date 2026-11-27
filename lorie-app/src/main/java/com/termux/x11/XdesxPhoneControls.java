// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.termux.x11.app.R;
import java.util.Locale;

/** Phone-only controls. The session owns the timeout, fullscreen transitions and IME. */
final class XdesxPhoneControls {
    interface Actions {
        boolean canStartPhoneDesktop();
        void startPhoneDesktop();
        void keyboard();
        void exitFullscreen();
        void toggleGame();
        void quitApp();
        void recover();
        void permissions();
        void endSession();
        void idleMinutes(int minutes);
        void menuDismissed();
    }

    private enum Page { MORE, TIMEOUT, END }
    private static final int WRAP = -2, MATCH = -1;
    private static final int[] MINUTES = {1, 5, 10, 15, 0};
    private final Activity host;
    private final LinearLayout bar;
    private final Actions actions;
    private Context labels;
    private Locale locale;
    private boolean dark, fullscreen, nativeApp, gameAvailable, gameActive;
    private int minutes, width, height;
    private float fontScale;
    private Dialog sheet;
    private Page page;
    private Runnable end;

    XdesxPhoneControls(Activity host, LinearLayout root, Actions actions) {
        this.host = host;
        this.actions = actions;
        bar = new LinearLayout(host);
        bar.setGravity(Gravity.CENTER);
        root.addView(bar, MATCH, WRAP);
        update(null, false, false, false, false, 5);
    }

    void update(XdesxPanelState panel, boolean fullscreen, boolean nativeApp, boolean gameAvailable, boolean gameActive, int idleMinutes) {
        Configuration config = host.getResources().getConfiguration();
        Locale l = panel == null ? config.getLocales().get(0) : panel.locale();
        boolean night = panel == null ? (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES : panel.isDark();
        boolean appearance = !l.equals(locale) || night != dark || fontScale != config.fontScale ||
                width != config.screenWidthDp || height != config.screenHeightDp;
        boolean toolbar = appearance || fullscreen != this.fullscreen || nativeApp != this.nativeApp ||
                gameAvailable != this.gameAvailable || gameActive != this.gameActive;
        boolean refreshSheet = appearance || minutes != idleMinutes;
        dark = night;
        locale = l;
        fontScale = config.fontScale;
        width = config.screenWidthDp;
        height = config.screenHeightDp;
        this.fullscreen = fullscreen;
        this.nativeApp = nativeApp;
        this.gameAvailable = gameAvailable;
        this.gameActive = gameActive;
        minutes = idleMinutes;
        if (appearance) {
            Configuration local = new Configuration(config);
            local.setLocale(l);
            labels = host.createConfigurationContext(local);
        }
        if (toolbar) renderBar();
        if (refreshSheet && showing()) renderSheet();
    }

    boolean showing() { return sheet != null && sheet.isShowing(); }

    void confirmEnd(Runnable confirmed) {
        end = confirmed;
        show(Page.END);
    }

    void close() {
        if (sheet != null) sheet.dismiss();
        if (bar.getParent() instanceof LinearLayout) ((LinearLayout)bar.getParent()).removeView(bar);
    }

    private int dp(int value) { return Math.round(host.getResources().getDisplayMetrics().density * value); }
    private int ink() { return dark ? 0xfff2f3f5 : 0xff20232a; }
    private int muted() { return dark ? 0xffb7bac4 : 0xff555c69; }
    private int surface() { return dark ? 0xff222326 : 0xfff2f3f6; }
    private int card() { return dark ? 0xff303136 : Color.WHITE; }
    private int danger() { return dark ? 0xffff928c : 0xffad302e; }
    private String text(int id) { return labels.getString(id); }
    private String interval(int n) {
        return n == 0 ? text(R.string.xu_pad_never) : labels.getString(R.string.xu_pad_minutes, n);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        return bg;
    }

    private Drawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x38ffffff : 0x22000000),
                shape(color, radius), shape(Color.WHITE, radius));
    }

    private TextView label(String value, int sp, int color, boolean medium) {
        TextView v = new TextView(host);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (medium) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    private Drawable icon(int resource, int color, int size) {
        Drawable icon = host.getDrawable(resource).mutate();
        icon.setTint(color);
        icon.setBounds(0, 0, dp(size), dp(size));
        return icon;
    }

    private void renderBar() {
        bar.removeAllViews();
        bar.setBackgroundColor(surface());
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        barAction(R.string.xu_pad_keyboard, R.drawable.xd_ic_keyboard, actions::keyboard);
        if (fullscreen) barAction(R.string.xu_exit_fullscreen, R.drawable.xu_bar_window, actions::exitFullscreen);
        if (gameAvailable) barAction(gameActive ? R.string.xu_pad_exit_game : R.string.xu_direct_game_input,
                R.drawable.xu_bar_game, actions::toggleGame);
        if (nativeApp) barAction(R.string.xu_quit_app, R.drawable.xu_bar_quit, actions::quitApp);
        barAction(R.string.xu_pad_more, R.drawable.xu_pad_more, () -> show(Page.MORE));
    }

    private void barAction(int title, int image, Runnable click) {
        int color = title == R.string.xu_quit_app ? (dark ? 0xffff827b : 0xffba322e) : ink();
        TextView button = label(text(title), nativeApp ? 12 : 13, color, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(4),dp(2),dp(4),dp(2));
        button.setCompoundDrawables(null, icon(image, color, 20), null, null);
        button.setCompoundDrawablePadding(dp(2));
        button.setBackground(ripple(surface(), 10));
        button.setFocusable(true);
        button.setOnClickListener(v -> click.run());
        bar.addView(button, new LinearLayout.LayoutParams(0, WRAP, 1));
    }

    private void show(Page requested) {
        if (host.isFinishing() || host.isDestroyed()) return;
        page = requested;
        if (showing()) { renderSheet(); return; }
        Dialog dialog = new Dialog(host, dark ? android.R.style.Theme_Material_Dialog_NoActionBar :
                android.R.style.Theme_Material_Light_Dialog_NoActionBar);
        sheet = dialog;
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(which -> {
            if (sheet == dialog) { sheet = null; page = null; end = null; actions.menuDismissed(); }
        });
        Window window = dialog.getWindow();
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.dimAmount = .4f;
        window.setAttributes(attrs);
        dialog.setOnKeyListener((d, key, event) -> {
            if (key != android.view.KeyEvent.KEYCODE_BACK || page != Page.TIMEOUT) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) show(Page.MORE);
            return true;
        });
        renderSheet();
        dialog.show();
        sizeWindow();
    }

    private void sizeWindow() {
        int available = host.getWindowManager().getCurrentWindowMetrics().getBounds().width();
        sheet.getWindow().setLayout(Math.min(dp(500), available), WRAP);
    }

    private void renderSheet() {
        ScrollView scroller = new ScrollView(host);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setBackground(shape(surface(), 20));
        scroller.setClipToOutline(true);
        LinearLayout body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16),dp(10),dp(16),dp(16));
        scroller.addView(body);
        LinearLayout header = new LinearLayout(host);
        header.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(header, MATCH, WRAP);
        int title = page == Page.END ? R.string.xu_end_the_desktop_session :
                page == Page.TIMEOUT ? R.string.xu_pad_dim : R.string.xu_desktop_controls;
        TextView heading = label(text(title), 21, ink(), true);
        heading.setAccessibilityHeading(true);
        header.addView(heading, new LinearLayout.LayoutParams(0, WRAP, 1));
        boolean back = page == Page.TIMEOUT;
        ImageButton close = new ImageButton(host);
        close.setPadding(dp(12),dp(12),dp(12),dp(12));
        close.setImageDrawable(icon(back ? R.drawable.xd_ic_back : R.drawable.xd_ic_close, muted(), 22));
        close.setContentDescription(text(back ? R.string.xd_back : R.string.xd_close));
        close.setBackground(ripple(surface(),24));
        close.setOnClickListener(v -> { if (back) show(Page.MORE); else sheet.dismiss(); });
        header.addView(close, dp(48), dp(48));
        if (page == Page.TIMEOUT) {
            for (int n : MINUTES) {
                row(body, interval(n), n == minutes ? R.drawable.xu_pad_check : 0, null, false, () -> {
                    actions.idleMinutes(n);
                    minutes = n;
                    show(Page.MORE);
                }).setSelected(n == minutes);
            }
        } else if (page == Page.END) {
            TextView detail = label(text(R.string.xu_all_desktop_windows_will_close_save_your_work_before_conti),
                    15, muted(), false);
            detail.setPadding(0,dp(4),0,dp(10));
            body.addView(detail);
            row(body,text(R.string.xu_end_session),R.drawable.xu_pad_power,null,true,() -> {
                Runnable confirmed = end;
                sheet.dismiss();
                if (confirmed != null) confirmed.run();
            });
            row(body,text(R.string.xu_cancel),0,null,false,() -> sheet.dismiss());
        } else {
            if (actions.canStartPhoneDesktop()) row(body,text(R.string.xu_phone_desktop),R.drawable.xd_ic_display,null,false,() -> run(actions::startPhoneDesktop));
            row(body,text(R.string.xu_pad_dim),R.drawable.xd_ic_moon,interval(minutes),false,() -> show(Page.TIMEOUT));
            row(body,text(R.string.xu_restore_native_display),R.drawable.xd_ic_display,null,false,() -> run(actions::recover));
            row(body,text(R.string.xu_connection_permissions),R.drawable.xd_ic_shield,null,false,() -> run(actions::permissions));
            row(body,text(R.string.xu_end_session),R.drawable.xu_pad_power,null,true,() -> run(actions::endSession));
        }
        sheet.setContentView(scroller);
        if (sheet.isShowing()) sizeWindow();
    }

    private void run(Runnable action) {
        sheet.dismiss();
        action.run();
    }

    private View row(LinearLayout parent, String title, int image, String value, boolean destructive, Runnable click) {
        int color = destructive ? danger() : ink();
        LinearLayout row = new LinearLayout(host);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14),dp(8),dp(14),dp(8));
        row.setMinimumHeight(dp(52));
        row.setBackground(ripple(card(),12));
        TextView name = label(title,16,color,false);
        name.setGravity(Gravity.CENTER_VERTICAL);
        if (image != 0) {
            name.setCompoundDrawablesRelative(icon(image,color,20),null,null,null);
            name.setCompoundDrawablePadding(dp(12));
        } else if (page == Page.TIMEOUT) name.setPaddingRelative(dp(32),0,0,0);
        row.addView(name,new LinearLayout.LayoutParams(0,WRAP,1));
        if (value != null) row.addView(label(value,14,muted(),false));
        row.setContentDescription(value == null ? title : title+", "+value);
        row.setFocusable(true);
        row.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH,WRAP);
        lp.topMargin = dp(5);
        parent.addView(row,lp);
        return row;
    }
}
