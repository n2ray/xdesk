package com.termux.x11;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.x11.app.R;

import java.util.ArrayList;
import java.util.List;

/**
 * NOTIFY.md §3: one banner at the external screen's top-right corner, a plain overlay window like the Dock's. Up to
 * three stack below the bell; consecutive notifications of one app merge into one ("3 new messages"). It stays 5
 * seconds, the pointer resting on it keeps it (2 seconds after it left), the × closes it (the notification stays in
 * the centre). Clicking the banner opens the newest notification; its action buttons act, up to two; with a quick
 * reply the "reply" button opens a field in the banner itself (Enter sends, Esc cancels — the keys come from Notify,
 * the window never takes the keyboard).
 *
 * The low-battery banner is one too, without a notification behind it (NOTIFY.md §4): clicking it opens the centre.
 * Over Android apps Android's own pointer works it; over X and a Mac Notify's driven pointer does (its hover keeps
 * the banner, its clicks land where the views say). Sizes from Sizing.shell.
 */
final class NotifyBanner {
    static final float WIDTH_DP = 360, MARGIN_DP = 16, GAP_DP = 8;
    static final int MAX = 3;
    /** The banner's action buttons: white at 8%, 12 dp corners (ExtNotify.dc.html). */
    private static final int ACTION = 0x14FFFFFF;
    private static final long STAY_MS = 5000, LEAVE_MS = 2000, SLIDE_MS = 180;

    interface Host {
        /** The banner's body was clicked: open the newest of its notifications (NOTIFY.md §5). */
        void bannerTap(NotifyBanner b);
        /** The × : the banner goes, the notifications stay in the centre. */
        void bannerClose(NotifyBanner b);
        /** One of its actions (its button, or the reply being sent). */
        void bannerAction(NotifyBanner b, Notification.Action action, String replyText);
        /** A reply field was opened or closed: the phone's keys type into it while open (NOTIFY.md §3). */
        void replyFocus(NotifyBanner b, boolean on);
        /** The banner slid out (its own timer, the pointer's leaving): take it down. */
        void bannerGone(NotifyBanner b);
    }

    private final Host host;
    private final Context dc;
    private final float density;
    private final WindowManager windows;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinearLayout root;
    private final WindowManager.LayoutParams params;
    private final ImageView icon;
    private final LinearLayout header, column;
    private final LinearLayout.LayoutParams iconAt;
    private final TextView app, time, title, text;
    private final LinearLayout actions;
    private final FrameLayout replyBox;
    private final EditText replyField;
    /** The low-battery banner has no notification's header, title or actions (ExtNotify.dc.html's third banner). */
    private boolean noticeMode;
    private final Rect rect = new Rect();
    private final List<NotifyCenter.Entry> entries = new ArrayList<>();
    private Notification.Action[] acts;
    private Notification.Action replyAction;
    private boolean replyOpen;
    /** The pointer is resting on it: it may not leave under the pointer. */
    private boolean hovered;
    private boolean closing;

    private final Runnable leave = this::leave;

    NotifyBanner(Context dc, float density, Host host, WindowManager windows, int top) {
        this.dc = dc;
        this.density = density;
        this.host = host;
        this.windows = windows;

        // ExtNotify.dc.html: the app's icon on the left, and to its right the app and the time, the title, the body and
        // the actions, all in one column. The shell's own colours (Style.SHELL / SHELL_LINE), 16 dp corners.
        root = new LinearLayout(dc);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.TOP);
        root.setBackground(Style.shell(density, 16));
        root.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.setOnClickListener(v -> host.bannerTap(NotifyBanner.this));
        // The Android pointer's hover keeps the banner (NOTIFY.md §3); the driven pointer's does the same in Notify
        root.setOnHoverListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_EXIT) {
                hover(false);
            } else if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {
                hover(true);
            }
            return false;
        });

        icon = new ImageView(dc);
        iconAt = new LinearLayout.LayoutParams(dp(40), dp(40));
        iconAt.rightMargin = dp(12);
        root.addView(icon, iconAt);

        column = new LinearLayout(dc);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new LinearLayout.LayoutParams(0, -2, 1));

        header = new LinearLayout(dc);
        header.setGravity(Gravity.CENTER_VERTICAL);
        app = label(Style.TEXT_DIM, 12, true);
        app.setSingleLine(true);
        app.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(app, new LinearLayout.LayoutParams(-2, -2));
        time = label(Style.TEXT_DIM, 12, true);
        time.setPadding(dp(4), 0, 0, 0);
        header.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
        // The ×: only the banner goes, the notification stays in the centre (NOTIFY.md §3)
        ImageView close = new ImageView(dc);
        close.setImageResource(R.drawable.xd_ic_close);
        close.setColorFilter(Style.TEXT_DIM);
        close.setScaleType(ImageView.ScaleType.FIT_CENTER);
        close.setPadding(dp(7), dp(7), dp(7), dp(7));
        close.setContentDescription(dc.getString(R.string.xd_close));
        close.setOnClickListener(v -> {
            Style.tick(v);
            host.bannerClose(NotifyBanner.this);
        });
        header.addView(close, new LinearLayout.LayoutParams(dp(32), dp(32)));
        column.addView(header, new LinearLayout.LayoutParams(-1, -2));

        title = label(Style.TEXT, 14, false);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleAt = new LinearLayout.LayoutParams(-1, -2);
        titleAt.topMargin = dp(3);
        column.addView(title, titleAt);

        text = label(Style.TEXT_SOFT, 13, false);
        text.setMaxLines(2);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams textAt = new LinearLayout.LayoutParams(-1, -2);
        textAt.topMargin = dp(3);
        column.addView(text, textAt);

        actions = new LinearLayout(dc);
        actions.setGravity(Gravity.START);
        actions.setVisibility(View.GONE);
        LinearLayout.LayoutParams actionsAt = new LinearLayout.LayoutParams(-1, -2);
        actionsAt.topMargin = dp(6);
        column.addView(actions, actionsAt);

        replyBox = new FrameLayout(dc);
        replyBox.setVisibility(View.GONE);
        replyField = new EditText(dc);
        replyField.setSingleLine(true);
        replyField.setTextColor(Style.TEXT);
        replyField.setHintTextColor(Style.TEXT_DIM);
        replyField.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.round(13 * density));
        replyField.setFocusable(false);
        replyField.setClickable(false);
        replyField.setHint(R.string.xd_notify_reply_hint);
        replyBox.addView(replyField, new FrameLayout.LayoutParams(-1, dp(30)));
        LinearLayout.LayoutParams replyAt = new LinearLayout.LayoutParams(-1, -2);
        replyAt.topMargin = dp(6);
        column.addView(replyBox, replyAt);

        params = new WindowManager.LayoutParams(dp(WIDTH_DP), -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(MARGIN_DP);
        params.y = top;
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        params.windowAnimations = 0;
        params.setTitle("XDesk notification banner");
    }

    private TextView label(int color, float sp, boolean single) {
        TextView t = new TextView(dc);
        t.setTextColor(color);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.round(sp * density));
        t.setSingleLine(single);
        return t;
    }

    private TextView smallButton(String word, Runnable onTap) {
        TextView b = new TextView(dc);
        b.setText(word);
        b.setTextColor(Style.TEXT);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.round(12 * density));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setBackground(Style.ripple(dc, ACTION, 12));
        b.setOnClickListener(v -> onTap.run());
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, dp(30));
        at.rightMargin = dp(8);
        b.setLayoutParams(at);
        return b;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    View view() {
        return root;
    }

    WindowManager.LayoutParams params() {
        return params;
    }

    boolean closing() {
        return closing;
    }

    int height() {
        return root.getHeight();
    }

    /** The banner as the model has it: the entries that came since the last one of this app went. */
    void show(NotifyCenter.Entry e, Drawable appIcon, String appLabel, boolean nameOnly, long when) {
        entries.clear();
        entries.add(e);
        acts = e.sbn.getNotification().actions;
        fill(appIcon, appLabel, e.title.isEmpty() ? appLabel : e.title, e.text, when, nameOnly);
    }

    /** The next notification of the same app while its banner is up: it becomes one (NOTIFY.md §3). */
    void merge(NotifyCenter.Entry e, boolean nameOnly) {
        entries.add(e);
        acts = e.sbn.getNotification().actions;
        fill(icon.getDrawable(), app.getText().toString(), e.title.isEmpty() ? app.getText().toString() : e.title,
            dc.getString(R.string.xd_notify_new_messages, entries.size()), e.when, nameOnly);
    }

    /** The low-battery banner (NOTIFY.md §4): no notification behind it. */
    void showNotice(Drawable appIcon, String label, String message, long when) {
        entries.clear();
        acts = null;
        noticeMode = false;
        fill(appIcon, label, label, message, when, false);
    }

    /**
     * ExtNotify.dc.html's third banner: the phone's battery, one line in the shell, the amber battery icon at its left,
     * no app, time, title or buttons.
     */
    void showBatteryNotice(String message) {
        entries.clear();
        acts = null;
        noticeMode = true;
        icon.setImageResource(R.drawable.xd_ic_battery);
        icon.setColorFilter(Style.WARN);
        iconAt.width = iconAt.height = dp(28);
        iconAt.rightMargin = dp(12);
        icon.setLayoutParams(iconAt);
        header.setVisibility(View.GONE);
        title.setVisibility(View.GONE);
        text.setText(message);
        text.setTextColor(Style.TEXT);
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.round(14 * density));
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setMaxLines(1);
        actions.setVisibility(View.GONE);
        replyBox.setVisibility(View.GONE);
        root.setGravity(Gravity.CENTER_VERTICAL);
    }

    private void fill(Drawable appIcon, String appLabel, String titleText, String bodyText, long when, boolean nameOnly) {
        noticeMode = false;
        icon.setImageDrawable(appIcon);
        icon.setColorFilter(null);
        iconAt.width = iconAt.height = dp(40);
        icon.setLayoutParams(iconAt);
        header.setVisibility(View.VISIBLE);
        title.setVisibility(View.VISIBLE);
        text.setTextColor(Style.TEXT_SOFT);
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.round(13 * density));
        text.setTypeface(Typeface.DEFAULT);
        text.setMaxLines(2);
        root.setGravity(Gravity.TOP);
        app.setText(appLabel);
        // "微信 · 刚刚" as the mockup has it: the app, then the time it came, relative
        time.setText(" · " + whenText(dc, when));
        if (nameOnly) {
            title.setText(appLabel);
            text.setText(dc.getString(R.string.xd_notify_new_message));
        } else {
            title.setText(titleText);
            text.setText(bodyText);
        }
        fillActions();
    }

    /** "刚刚" under a minute, then the system's relative words ("2 分钟前", "2 min. ago"). */
    static CharSequence whenText(Context c, long when) {
        long age = System.currentTimeMillis() - when;
        if (age < 60_000) return c.getString(R.string.xd_notify_now);
        return android.text.format.DateUtils.getRelativeTimeSpanString(when, System.currentTimeMillis(),
            android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE);
    }

    NotifyCenter.Entry newest() {
        return entries.isEmpty() ? null : entries.get(entries.size() - 1);
    }

    List<NotifyCenter.Entry> entries() {
        return entries;
    }

    String packageName() {
        return entries.isEmpty() ? null : entries.get(0).pkg;
    }

    /** A notification cleared on the phone leaves the banner (NOTIFY.md §2). True when nothing is left of it. */
    boolean removeKey(String key) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).key.equals(key)) entries.remove(i--);
        return entries.isEmpty();
    }

    /** Up to two action buttons, and "reply" when the newest has a quick reply (NOTIFY.md §3). */
    private void fillActions() {
        actions.removeAllViews();
        replyAction = null;
        if (acts == null) {
            actions.setVisibility(View.GONE);
            return;
        }
        int shown = 0;
        for (Notification.Action a : acts) {
            if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) {
                if (replyAction == null) replyAction = a;
                continue;
            }
            if (shown < 2) {
                TextView b = smallButton(String.valueOf(a.title), () -> host.bannerAction(NotifyBanner.this, a, null));
                actions.addView(b);
                shown++;
            }
        }
        if (replyAction != null && shown < 2) {
            TextView r = smallButton(dc.getString(R.string.xd_notify_reply), this::openReply);
            actions.addView(r);
        }
        actions.setVisibility(actions.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    // --- staying and going ---

    /** The banner appeared: it goes after STAY_MS unless the pointer rests on it. */
    void shown() {
        ui.removeCallbacks(leave);
        ui.postDelayed(leave, STAY_MS);
    }

    /** The banner stays until it is closed, no timer: the unlock banner (NOTIFY.md §5). */
    void stay() {
        ui.removeCallbacks(leave);
    }

    /** The pointer is over the banner (or left it): while over it the timer stops, after it 2 seconds remain. */
    void hover(boolean on) {
        hovered = on;
        ui.removeCallbacks(leave);
        // An open reply waits for Enter or Esc wherever the pointer went: closing it would lose what was typed
        if (!on && !replyOpen) ui.postDelayed(leave, LEAVE_MS);
    }

    private void leave() {
        if (closing) return;
        host.bannerGone(this);
    }

    void slideOut() {
        if (closing) return;
        closing = true;
        // The keys must not stay with an invisible field: Notify.key would swallow every one, Esc included
        cancelReply();
        ui.removeCallbacks(leave);
        root.animate().translationX(dp(WIDTH_DP)).alpha(0f).setDuration(SLIDE_MS).withEndAction(this::remove).start();
    }

    void remove() {
        closing = true;
        cancelReply();
        ui.removeCallbacks(leave);
        try {
            windows.removeViewImmediate(root);
        } catch (RuntimeException ignored) { }
    }

    /** The banner's place on the screen, in pixels. */
    void rect(Rect out) {
        int[] at = new int[2];
        root.getLocationOnScreen(at);
        out.set(at[0], at[1], at[0] + root.getWidth(), at[1] + root.getHeight());
    }

    boolean holds(int x, int y) {
        rect(rect);
        return x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom;
    }

    /** The driven pointer's click: the button under it, or the banner's body (which opens). */
    boolean clickAt(int x, int y) {
        return clickWalk(root, x, y);
    }

    /** The bottommost clickable view that holds this place: the child buttons before the body. */
    private static boolean clickWalk(View v, int x, int y) {
        if (v.getVisibility() != View.VISIBLE) return false;
        if (v instanceof ViewGroup) {
            for (int i = ((ViewGroup) v).getChildCount() - 1; i >= 0; i--) {
                if (clickWalk(((ViewGroup) v).getChildAt(i), x, y)) return true;
            }
        }
        if (!v.isClickable()) return false;
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        if (x >= at[0] && x < at[0] + v.getWidth() && y >= at[1] && y < at[1] + v.getHeight()) {
            v.performClick();
            return true;
        }
        return false;
    }

    // --- the quick reply (NOTIFY.md §3) ---

    boolean replyOpen() {
        return replyOpen;
    }

    void openReply() {
        stay();  // typing into it must not race the 5 seconds
        replyOpen = true;
        replyBox.setVisibility(View.VISIBLE);
        replyField.setText("");
        GradientDrawable back = new GradientDrawable();
        back.setColor(0x22FFFFFF);
        back.setStroke(Math.max(1, dp(1)), Style.ACCENT);
        back.setCornerRadius(dp(8));
        replyField.setBackground(back);
        host.replyFocus(this, true);
    }

    void cancelReply() {
        if (!replyOpen) return;
        replyOpen = false;
        replyBox.setVisibility(View.GONE);
        host.replyFocus(this, false);
        // It kept the banner while it was open: back to an ordinary banner, unless the pointer is keeping it
        if (!closing && !hovered) {
            ui.removeCallbacks(leave);
            ui.postDelayed(leave, LEAVE_MS);
        }
    }

    void type(char c) {
        if (!replyOpen) return;
        String now = replyField.getText().toString();
        replyField.setText(now + c);
        replyField.setSelection(replyField.getText().length());
    }

    void backspace() {
        if (!replyOpen) return;
        String now = replyField.getText().toString();
        if (!now.isEmpty()) {
            replyField.setText(now.substring(0, now.length() - 1));
            replyField.setSelection(replyField.getText().length());
        }
    }

    /** Enter: the reply goes (RemoteInput on the action's intent), the banner goes. An empty one is not sent. */
    void sendReply() {
        if (!replyOpen || replyAction == null) return;
        String textValue = replyField.getText().toString().trim();
        if (textValue.isEmpty()) {
            cancelReply();
            return;
        }
        host.bannerAction(this, replyAction, textValue);
        cancelReply();
        host.bannerClose(this);
    }

    /** Sends a notification's action (with the reply, if any). False when the app's intent was cancelled or refused. */
    static boolean sendAction(Context c, Notification.Action action, String replyText) {
        Intent intent = new Intent();
        if (replyText != null) {
            android.os.Bundle results = new android.os.Bundle();
            for (RemoteInput ri : action.getRemoteInputs()) results.putString(ri.getResultKey(), replyText);
            RemoteInput.addResultsToIntent(action.getRemoteInputs(), intent, results);
        }
        try {
            action.actionIntent.send(c, 0, intent);
            return true;
        } catch (PendingIntent.CanceledException | RuntimeException e) {
            android.util.Log.w("XDeskNotify", "action send: " + e);
            return false;
        }
    }
}
