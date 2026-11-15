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
    private static final int PANEL = 0xF01B1E24, EDGE = 0x33FFFFFF;
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
    private final TextView app, time, title, text;
    private final LinearLayout actions;
    private final FrameLayout replyBox;
    private final EditText replyField;
    private final Rect rect = new Rect();
    private final List<NotifyCenter.Entry> entries = new ArrayList<>();
    private Notification.Action[] acts;
    private Notification.Action replyAction;
    private boolean replyOpen;
    private boolean closing;

    private final Runnable leave = this::leave;

    NotifyBanner(Context dc, float density, Host host, WindowManager windows, int top) {
        this.dc = dc;
        this.density = density;
        this.host = host;
        this.windows = windows;

        root = new LinearLayout(dc);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(PANEL);
        surface.setCornerRadius(dp(14));
        surface.setStroke(Math.max(1, dp(0.5f)), EDGE);
        root.setBackground(surface);
        root.setPadding(dp(12), dp(10), dp(10), dp(10));
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

        LinearLayout head = new LinearLayout(dc);
        head.setGravity(Gravity.CENTER_VERTICAL);
        icon = new ImageView(dc);
        int iconSize = dp(34);
        head.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        LinearLayout names = new LinearLayout(dc);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(8), 0, 0, 0);
        app = label(Style.TEXT_DIM, 11, true);
        app.setSingleLine(true);
        app.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(app, new LinearLayout.LayoutParams(0, -2, 1));
        title = label(Style.TEXT, 14, false);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        time = label(Style.TEXT_DIM, 11, true);
        head.addView(time);
        View close = smallButton("×", () -> host.bannerClose(NotifyBanner.this));
        head.addView(close);
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        text = label(Style.TEXT_DIM, 12, false);
        text.setMaxLines(2);
        text.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams textAt = new LinearLayout.LayoutParams(-1, -2);
        textAt.topMargin = dp(4);
        root.addView(text, textAt);

        actions = new LinearLayout(dc);
        actions.setGravity(Gravity.END);
        actions.setVisibility(View.GONE);
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));

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
        root.addView(replyBox, replyAt);

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
        b.setPadding(dp(8), dp(2), dp(8), dp(2));
        GradientDrawable back = new GradientDrawable();
        back.setColor(0x22FFFFFF);
        back.setCornerRadius(dp(8));
        b.setBackground(back);
        b.setOnClickListener(v -> onTap.run());
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
        fill(appIcon, label, label, message, when, false);
    }

    private void fill(Drawable appIcon, String appLabel, String titleText, String bodyText, long when, boolean nameOnly) {
        icon.setImageDrawable(appIcon);
        app.setText(appLabel);
        time.setText(android.text.format.DateFormat.format("HH:mm", when));
        if (nameOnly) {
            title.setText(appLabel);
            text.setText(dc.getString(R.string.xd_notify_new_message));
        } else {
            title.setText(titleText);
            text.setText(bodyText);
        }
        fillActions();
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
        ui.removeCallbacks(leave);
        ui.postDelayed(leave, on ? STAY_MS : LEAVE_MS);
    }

    private void leave() {
        if (closing) return;
        host.bannerGone(this);
    }

    void slideOut() {
        if (closing) return;
        closing = true;
        ui.removeCallbacks(leave);
        root.animate().translationX(dp(WIDTH_DP)).alpha(0f).setDuration(SLIDE_MS).withEndAction(this::remove).start();
    }

    void remove() {
        closing = true;
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

    static void sendAction(Context c, Notification.Action action, String replyText) {
        Intent intent = new Intent();
        if (replyText != null) {
            android.os.Bundle results = new android.os.Bundle();
            for (RemoteInput ri : action.getRemoteInputs()) results.putString(ri.getResultKey(), replyText);
            RemoteInput.addResultsToIntent(action.getRemoteInputs(), intent, results);
        }
        try {
            action.actionIntent.send(c, 0, intent);
        } catch (PendingIntent.CanceledException | RuntimeException e) {
            android.util.Log.w("XDeskNotify", "action send: " + e);
        }
    }
}
