// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.x11.app.R;
import com.termux.x11.screenshare.ScreenShareLoginActivity;
import com.termux.x11.screenshare.ScreenSharePasswords;
import com.termux.x11.screenshare.ScreenShareStore;

import java.util.Arrays;

/**
 * The Mac password for a Screen Sharing connection, in XDesk's own look: on the phone a centred sheet (SettingsUi), and
 * when the Mac was opened on the external screen a card there (owner, 22 November: the box where one is looking). The
 * external card takes the phone's input like the Launchpad does (it is the input target while it is up): text from the
 * phone's keyboard, a physical keyboard's keys, the touchpad's pointer for its buttons. The phone's input connection is a
 * password field meanwhile, so no keyboard learns or suggests it. The password is kept as chars only, shown as dots,
 * cleared once handed over; the window is kept out of screenshots.
 */
public final class MacLogin {
    private static final String TAG = "XDeskScreenShare";

    private MacLogin() { }

    // --- the phone's sheet (ScreenShareLoginActivity) ---

    public interface Typed {
        /** The password typed, and whether to keep it. Cleared by whoever takes it. */
        void typed(char[] password, boolean remember);
        void cancelled();
    }

    /** The sheet on the phone: title, who logs in, the field, 记住密码 (when the phone can protect it), Cancel / Connect. */
    public static Dialog phone(Activity a, ScreenShareStore.Host host, Typed out) {
        SettingsUi ui = new SettingsUi(a);
        final boolean[] answered = new boolean[1];
        final boolean[] remember = new boolean[1];
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText secret = new EditText(a);
        secret.setHint(R.string.xd_ss_password);
        secret.setSingleLine();
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setSaveEnabled(false);
        secret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        secret.setTextColor(Style.TEXT);
        secret.setHintTextColor(Style.FAINT);
        secret.setTextSize(Style.TEXT_BODY);
        secret.setBackground(Style.rounded(a, Style.SURFACE_RAISED, Style.RADIUS));
        secret.setPadding(Style.dp(a, 14), 0, Style.dp(a, 14), 0);
        secret.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, Style.dp(a, Style.TARGET));
        fp.topMargin = Style.dp(a, 6);
        box.addView(secret, fp);
        final TextView keep = Style.button(a, a.getString(R.string.xd_ss_remember_short), Style.SURFACE_RAISED,
            Style.TEXT_SMALL + 2, Style.RADIUS);
        keep.setPadding(Style.dp(a, 14), 0, Style.dp(a, 14), 0);
        if (ScreenSharePasswords.canSave(a)) {
            keep.setBackground(Style.toggle(a, false, Style.SURFACE_RAISED, Style.RADIUS));
            keep.setOnClickListener(v -> {
                remember[0] = !remember[0];
                keep.setBackground(Style.toggle(a, remember[0], Style.SURFACE_RAISED, Style.RADIUS));
            });
            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-2, Style.dp(a, Style.TARGET - 8));
            kp.topMargin = Style.dp(a, 12);
            box.addView(keep, kp);
            TextView note = Style.label(a, a.getString(R.string.xd_ss_remember_note), Style.TEXT_DIM, Style.TEXT_SMALL);
            note.setPadding(Style.dp(a, 2), Style.dp(a, 6), 0, 0);
            box.addView(note);
        }
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.END);
        final Dialog[] d = new Dialog[1];
        TextView cancel = ui.pill(a.getString(R.string.xd_cancel), Style.SURFACE_RAISED);
        cancel.setOnClickListener(v -> d[0].dismiss());
        TextView go = ui.pill(a.getString(R.string.xd_ss_connect), Style.ACCENT);
        Runnable submit = () -> {
            if (secret.length() == 0) return;
            char[] pass = new char[secret.length()];
            secret.getText().getChars(0, pass.length, pass, 0);
            secret.setText("");
            answered[0] = true;
            d[0].dismiss();
            out.typed(pass, remember[0]);
        };
        go.setOnClickListener(v -> submit.run());
        secret.setOnEditorActionListener((v, action, e) -> {
            submit.run();
            return true;
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, Style.dp(a, Style.TARGET - 4));
        bp.leftMargin = Style.dp(a, 8);
        row.addView(cancel, bp);
        row.addView(go, new LinearLayout.LayoutParams(bp));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = Style.dp(a, 16);
        box.addView(row, rp);
        d[0] = ui.sheet(a.getString(R.string.xd_ss_login_title, host.label()),
            a.getString(R.string.xd_ss_login_who, host.account, host.host), box);
        d[0].getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        d[0].getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d[0].setOnDismissListener(x -> {
            if (!answered[0]) out.cancelled();
        });
        secret.requestFocus();
        return d[0];
    }

    // --- the external screen's card ---

    /** XDeskActivity: the card takes the input while it is up, and gives it back. */
    interface Host {
        /** The card is up: it is the input target now, the phone's connection a password field. */
        void loginTaken();
        /** It went: the input goes back where it was. */
        void loginReleased();
        /** For the fingerprint prompt when the password is to be kept: XDesk's window on the phone. */
        Activity phoneActivity();
        /**
         * An Android app is what the external screen shows: its keys and mouse are the injector's (or Android's own),
         * read past XDesk's window. The card then takes the display's focus and Android's pointer itself.
         */
        boolean overAndroidApp();
    }

    static volatile Host host;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static Card card;

    static boolean isShown() {
        return card != null;
    }

    /**
     * The card on that display, for a Mac whose password is not kept. False when it can't be shown there (no
     * XDesk window, no overlay permission): the phone asks instead.
     */
    static boolean ask(Context context, ScreenShareStore.Host mac, int displayId) {
        Host h = host;
        if (h == null || h.phoneActivity() == null || !android.provider.Settings.canDrawOverlays(context)) return false;
        Display d = context.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(displayId);
        if (d == null) return false;
        close(false);
        Card c;
        try {
            c = new Card(context.createDisplayContext(d), d, mac, h.overAndroidApp());
            c.wm.addView(c.root, c.params());
            if (c.own) c.root.requestFocus();
        } catch (RuntimeException e) {
            Log.w(TAG, "can't show the password card", e);
            return false;
        }
        card = c;
        Log.i(TAG, "password asked on display " + displayId);
        h.loginTaken();
        return true;
    }

    /** The card goes: given up (Esc, Cancel, the screen went) or after Connect. */
    static void close(boolean cancelled) {
        Card c = card;
        if (c == null) return;
        card = null;
        Arrays.fill(c.pass, (char) 0);
        c.length = 0;
        try {
            c.wm.removeView(c.root);
        } catch (RuntimeException ignored) { }
        Host h = host;
        if (h != null) h.loginReleased();
        if (cancelled) {
            ScreenShareLoginActivity.Outcome out = ScreenShareLoginActivity.outcome;
            if (out != null) out.cancelled(c.mac.id);
        }
    }

    /** The external screen went (or XDesk's window): nothing to type into. */
    static void displayGone(int displayId) {
        if (card != null && card.displayId == displayId) close(true);
    }

    /**
     * A physical keyboard's key while the card is up (XDeskActivity.physicalKey, before anything else): text in the
     * phone's layout, Backspace, Enter connects, Esc cancels. Every key is taken: none reaches what is under it.
     */
    static boolean key(KeyEvent e) {
        Card c = card;
        if (c == null) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DEL: c.backspace(); return true;
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: c.submit(); return true;
            case KeyEvent.KEYCODE_ESCAPE: close(true); return true;
            case KeyEvent.KEYCODE_TAB: c.toggleKeep(); return true;
        }
        int u = e.getUnicodeChar(e.getMetaState());
        if (u > 0 && !Character.isISOControl(u)) c.type(new String(Character.toChars(u)));
        return true;
    }

    private interface CardAction { void run(Card c); }

    private static void onUi(CardAction a) {
        Runnable r = () -> {
            Card c = card;
            if (c != null) a.run(c);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else UI.post(r);
    }

    /** The input target while the card is up: the phone's soft keyboard, its touchpad, a mouse. */
    static final InputSink INPUT = new InputSink() {
        @Override public void move(int dx, int dy) { onUi(c -> c.place(c.px + dx, c.py + dy)); }
        @Override public void moveToFraction(float x, float y) { onUi(c -> c.place(x * c.width, y * c.height)); }
        @Override public void button(int button, boolean down) { if (!down) onUi(c -> c.click(button)); }
        @Override public void click(int button) { onUi(c -> c.click(button)); }
        @Override public void scroll(int dy, int dx) { }
        @Override public void key(int scan, boolean down) { if (down) onUi(c -> c.scan(scan)); }
        @Override public void tapKey(int scan) { onUi(c -> c.scan(scan)); }
        @Override public void text(String s) { onUi(c -> c.type(s)); }
        @Override public void androidButton(int b) { if (b == 1) onUi(c -> close(true)); }
    };

    private static final class Card {
        final Context c;
        final WindowManager wm;
        final ScreenShareStore.Host mac;
        final int displayId, width, height;
        final float density;
        final FrameLayout root;
        final TextView field, keep, go, cancel;
        final View pointer;
        final boolean canSave;
        char[] pass = new char[64];
        int length;
        boolean remember;
        float px, py;

        /** Keys and Android's pointer come to the card's own window (Host.overAndroidApp), not through INPUT. */
        final boolean own;

        Card(Context c, Display d, ScreenShareStore.Host mac, boolean own) {
            this.c = c;
            this.own = own;
            this.mac = mac;
            displayId = d.getDisplayId();
            wm = c.getSystemService(WindowManager.class);
            android.graphics.Point size = new android.graphics.Point();
            d.getRealSize(size);
            width = size.x;
            height = size.y;
            density = Sizing.shell(c, d).density;
            canSave = ScreenSharePasswords.canSave(c);
            root = new FrameLayout(c);
            root.setBackgroundColor(Style.SCRIM);
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(Style.shell(density, 20));
            box.setPadding(dp(24), dp(22), dp(24), dp(18));
            TextView title = text(c.getString(R.string.xd_ss_login_title, mac.label()), Style.TEXT, 18);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            box.addView(title);
            TextView who = text(c.getString(R.string.xd_ss_login_who, mac.account, mac.host), Style.TEXT_DIM, 13);
            who.setPadding(0, dp(4), 0, 0);
            box.addView(who);
            field = text("", Style.TEXT, 16);
            field.setGravity(Gravity.CENTER_VERTICAL);
            field.setPadding(dp(14), 0, dp(14), 0);
            field.setSingleLine(true);
            field.setEllipsize(android.text.TextUtils.TruncateAt.START);
            field.setHintTextColor(Style.FAINT);
            field.setHint(R.string.xd_ss_password);
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, dp(44));
            fp.topMargin = dp(16);
            box.addView(field, fp);
            keep = pill(c.getString(R.string.xd_ss_remember_short), Style.SURFACE_RAISED);
            if (canSave) {
                LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-2, dp(34));
                kp.topMargin = dp(12);
                box.addView(keep, kp);
                TextView note = text(c.getString(R.string.xd_ss_remember_note), Style.TEXT_DIM, 12);
                note.setPadding(dp(2), dp(6), 0, 0);
                box.addView(note);
            }
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView hint = text(c.getString(canSave ? R.string.xd_ss_login_keys_keep : R.string.xd_ss_login_keys),
                Style.FAINT, 12);
            row.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
            cancel = pill(c.getString(R.string.xd_cancel), Style.SURFACE_RAISED);
            go = pill(c.getString(R.string.xd_ss_connect), Style.ACCENT);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(36));
            bp.leftMargin = dp(8);
            row.addView(cancel, bp);
            row.addView(go, new LinearLayout.LayoutParams(bp));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.topMargin = dp(18);
            box.addView(row, rp);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(Math.min(dp(460), width - dp(32)), -2, Gravity.CENTER);
            root.addView(box, cp);
            pointer = CursorOverlay.arrow(c, density);
            root.addView(pointer);
            if (own) takeAndroidInput();
            show();
            px = width / 2f;
            py = height * 0.72f;
            place(px, py);
        }

        WindowManager.LayoutParams params() {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                (own ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) | WindowManager.LayoutParams.FLAG_SECURE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTitle("XDesk Mac password");
            return lp;
        }

        int dp(float v) {
            return Math.round(v * density);
        }

        /**
         * Over an Android app (owner, 23 November: the pointer that moved clicked nothing, the keys went to the app):
         * the injector's taps and Android's mouse come here as touches and hovers, a keyboard's keys as this focused
         * window's. The card's own arrow goes: Android's is the one that moves.
         */
        void takeAndroidInput() {
            pointer.setVisibility(View.GONE);
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setOnKeyListener((v, code, e) -> MacLogin.key(e));
            root.setOnHoverListener((v, e) -> {
                place(e.getX(), e.getY());
                return true;
            });
            root.setOnTouchListener((v, e) -> {
                place(e.getX(), e.getY());
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) click(1);
                return true;
            });
        }

        TextView text(String s, int color, float size) {
            TextView t = new TextView(c);
            t.setText(s);
            t.setTextColor(color);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * density);
            return t;
        }

        TextView pill(String s, int color) {
            TextView b = text(s, Style.TEXT, 14);
            b.setGravity(Gravity.CENTER);
            b.setSingleLine(true);
            b.setPadding(dp(16), 0, dp(16), 0);
            b.setBackground(back(color, false));
            return b;
        }

        android.graphics.drawable.GradientDrawable back(int color, boolean on) {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(on ? Style.ACCENT_SOFT : color);
            g.setCornerRadius(dp(Style.RADIUS));
            if (on) g.setStroke(Math.max(1, dp(1)), Style.ACCENT);
            return g;
        }

        /** The dots and a caret; the field's own frame lights up while something is typed. */
        void show() {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < length; i++) s.append('•');
            field.setText(length > 0 ? s.append('▏').toString() : "");
            android.graphics.drawable.GradientDrawable f = back(Style.SURFACE_RAISED, false);
            f.setStroke(Math.max(1, dp(1)), Style.ACCENT);
            field.setBackground(f);
            go.setAlpha(length > 0 ? 1f : 0.5f);
        }

        void type(String s) {
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (Character.isISOControl(ch)) continue;
                if (length == pass.length) {
                    char[] more = Arrays.copyOf(pass, pass.length * 2);
                    Arrays.fill(pass, (char) 0);
                    pass = more;
                }
                pass[length++] = ch;
            }
            show();
        }

        void backspace() {
            if (length == 0) return;
            pass[--length] = 0;
            show();
        }

        void scan(int scan) {
            if (scan == 14) backspace();
            else if (scan == 28 || scan == 96) submit();
            else if (scan == 1) close(true);
            else if (scan == 15) toggleKeep();
        }

        void toggleKeep() {
            if (!canSave) return;
            remember = !remember;
            keep.setBackground(back(Style.SURFACE_RAISED, remember));
        }

        void submit() {
            if (length == 0) return;
            char[] typed = Arrays.copyOf(pass, length);
            boolean keepIt = remember;
            ScreenShareStore.Host m = mac;
            // Gone first, so the input is back on the content before the connection shows it
            card = null;
            Arrays.fill(pass, (char) 0);
            length = 0;
            try {
                wm.removeView(root);
            } catch (RuntimeException ignored) { }
            Host h = host;
            if (h != null) h.loginReleased();
            Activity a = h != null ? h.phoneActivity() : null;
            ScreenShareLoginActivity.Outcome out = ScreenShareLoginActivity.outcome;
            if (!keepIt || a == null) {
                if (out != null) out.password(m.id, typed);
                else Arrays.fill(typed, (char) 0);
                return;
            }
            // The fingerprint is asked on the phone; the connection goes on either way (as on the phone's sheet)
            ScreenSharePasswords.save(a, m.id, a.getString(R.string.xd_ss_save_title, m.label()), typed, (unused, error) -> {
                if (error != null) android.widget.Toast.makeText(a, R.string.xd_ss_not_saved, android.widget.Toast.LENGTH_LONG).show();
                ScreenShareLoginActivity.Outcome o = ScreenShareLoginActivity.outcome;
                if (o != null) o.password(m.id, typed);
                else Arrays.fill(typed, (char) 0);
            });
        }

        void place(float x, float y) {
            px = Math.max(0, Math.min(width - 1, x));
            py = Math.max(0, Math.min(height - 1, y));
            pointer.setTranslationX(px - CursorOverlay.arrowTipX(pointer));
            pointer.setTranslationY(py);
        }

        void click(int button) {
            if (button != 1) return;
            if (over(go)) submit();
            else if (over(cancel)) close(true);
            else if (canSave && over(keep)) toggleKeep();
        }

        private final Rect hit = new Rect();
        private final int[] at = new int[2];

        boolean over(View v) {
            if (!v.isShown()) return false;
            v.getLocationInWindow(at);
            hit.set(at[0], at[1], at[0] + v.getWidth(), at[1] + v.getHeight());
            hit.inset(-dp(4), -dp(4));
            return hit.contains(Math.round(px), Math.round(py));
        }
    }
}
