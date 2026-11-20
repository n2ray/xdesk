package com.termux.x11;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.termux.x11.app.R;

/**
 * 第一次激活注入服务（Activation.dc.html）：怎么做三步、配对码和端口，以及改用 Shizuku 的入口。通知里输不了时全靠这里。
 */
final class ActivationPage {
    interface Actions {
        void openPairingSettings();
        /** 改用 Shizuku。没装 Shizuku 时这个选项不出现。 */
        void useShizuku();
        /** 用户自己离开了这一页。 */
        void cancelled();
    }

    private static final int CODE_LENGTH = 6;

    private final Context c;
    private final ShellActivation shell;
    private final Actions actions;
    private final Dialog dialog;
    private final EditText codeField, portField;
    private final TextView portNote, status, pairButton;
    private boolean closing;

    ActivationPage(Context context, SettingsUi ui, ShellActivation shell, boolean offerShizuku, Actions actions) {
        c = context;
        this.shell = shell;
        this.actions = actions;
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);

        // The intro line, then the three steps in one card (Activation.dc.html)
        TextView intro = Style.label(c, c.getString(R.string.xd_act_intro), Style.TEXT_SOFT, 13);
        intro.setPadding(dp(6), 0, dp(6), dp(6));
        intro.setLineSpacing(dp(2), 1f);
        content.addView(intro);

        LinearLayout steps = ui.card(content);
        steps.setPadding(dp(10), dp(6), dp(10), dp(6));
        step(steps, "1", c.getString(R.string.xd_act_step1), null, null);
        step(steps, "2", c.getString(R.string.xd_act_step2), c.getString(R.string.xd_act_open_wireless),
            () -> actions.openPairingSettings());
        step(steps, "3", c.getString(R.string.xd_act_step3), null, null);

        LinearLayout card = ui.section(content, c.getString(R.string.xd_act_code_title));
        LinearLayout fields = new LinearLayout(c);
        fields.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.setMargins(dp(8), dp(8), dp(8), 0);
        card.addView(fields, fp);
        codeField = field(c.getString(R.string.xd_act_code_hint), true);
        codeField.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams codeAt = new LinearLayout.LayoutParams(0, dp(44), 2);
        codeAt.rightMargin = dp(8);
        fields.addView(codeField, codeAt);
        portField = field(c.getString(R.string.xd_act_port_hint), false);
        fields.addView(portField, new LinearLayout.LayoutParams(0, dp(44), 1));

        portNote = Style.label(c, "", Style.OK, 12);
        portNote.setPadding(dp(10), dp(8), dp(10), 0);
        card.addView(portNote);

        pairButton = Style.button(c, c.getString(R.string.xd_act_pair), Style.ACCENT, 14, 12);
        pairButton.setTextColor(android.graphics.Color.WHITE);
        pairButton.setTypeface(Typeface.DEFAULT_BOLD);
        pairButton.setOnClickListener(v -> pair());
        LinearLayout.LayoutParams pairAt = new LinearLayout.LayoutParams(-1, dp(44));
        pairAt.setMargins(dp(8), dp(10), dp(8), dp(8));
        card.addView(pairButton, pairAt);

        status = Style.label(c, "", Style.DANGER, 12);
        status.setPadding(dp(10), 0, dp(10), dp(8));
        card.addView(status);

        LinearLayout later = ui.section(content, c.getString(R.string.xd_act_later_title));
        TextView laterText = Style.label(c, c.getString(R.string.xd_act_later_short), Style.TEXT_SOFT, 13);
        laterText.setPadding(dp(12), dp(10), dp(12), dp(10));
        laterText.setLineSpacing(dp(2), 1f);
        later.addView(laterText);

        if (offerShizuku) {
            // A quiet link at the page's bottom (btnq): accent-2 words, no fill
            TextView shizuku = Style.button(c, c.getString(R.string.xd_act_use_shizuku) + " ›", 0x00000000, 14, 12);
            shizuku.setTextColor(Style.ACCENT_2);
            shizuku.setPadding(dp(6), 0, dp(6), 0);
            shizuku.setGravity(Gravity.START);
            shizuku.setOnClickListener(v -> {
                Style.tick(v);
                actions.useShizuku();
            });
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(44));
            sp.topMargin = dp(8);
            content.addView(shizuku, sp);
        }

        dialog = ui.page(c.getString(R.string.xd_act_title), content);
        dialog.setOnDismissListener(d -> {
            if (!closing) actions.cancelled();
        });
        refresh();
    }

    private int dp(float v) {
        return Style.dp(c, v);
    }

    /** One numbered step: the 24 dp disc and its words; step 2 has its button under them. */
    private void step(LinearLayout card, String number, String text, String action, Runnable onAction) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(4), dp(8), dp(4), dp(8));
        TextView num = Style.label(c, number, Style.ACCENT_2, 12);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        num.setGravity(Gravity.CENTER);
        num.setBackground(Style.rounded(c, Style.SURFACE_RAISED, 12));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(dp(24), dp(24));
        np.rightMargin = dp(12);
        np.topMargin = dp(1);
        row.addView(num, np);
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView t = Style.label(c, text, Style.TEXT_SOFT, 13);
        t.setLineSpacing(dp(2), 1f);
        words.addView(t);
        if (action != null) {
            TextView b = Style.button(c, action, Style.SURFACE_RAISED, 14, 12);
            b.setPadding(dp(18), 0, dp(18), 0);
            b.setOnClickListener(v -> {
                Style.tick(v);
                onAction.run();
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(44));
            bp.topMargin = dp(10);
            words.addView(b, bp);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private EditText field(String hint, boolean accent) {
        EditText f = new EditText(c);
        f.setInputType(InputType.TYPE_CLASS_NUMBER);
        f.setSingleLine(true);
        f.setTextColor(Style.TEXT);
        f.setHintTextColor(Style.TEXT_DIM);
        f.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(14));
        f.setGravity(Gravity.CENTER);
        f.setHint(hint);
        f.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable bg = Style.rounded(c, Style.BACKGROUND, 12);
        if (accent) bg.setStroke(dp(1.5f), Style.ACCENT);
        f.setBackground(bg);
        return f;
    }

    private void pair() {
        String code = codeField.getText().toString().trim();
        int port = shell.pairingPort();
        if (port <= 0) {
            try {
                port = Integer.parseInt(portField.getText().toString().trim());
            } catch (NumberFormatException e) {
                status.setTextColor(Style.DANGER);
                status.setText(R.string.xd_act_need_port);
                return;
            }
        }
        if (code.length() != CODE_LENGTH) {
            status.setTextColor(Style.DANGER);
            status.setText(R.string.xd_act_code_length);
            return;
        }
        status.setTextColor(Style.TEXT_DIM);
        status.setText(R.string.xd_act_pairing);
        shell.pair(port, code, error -> {
            status.setTextColor(Style.DANGER);
            status.setText(error);
        });
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    /** 不算用户离开：配对好了，或者换了别的方式。 */
    void close() {
        closing = true;
        dialog.dismiss();
    }

    void refresh() {
        boolean found = shell.pairingPort() > 0;
        portNote.setTextColor(found ? Style.OK : Style.TEXT_DIM);
        portNote.setText(found ? R.string.xd_act_port_found : R.string.xd_act_port_missing);
        // The mockup shows the port found, filled in beside the code; pairing still uses the system's when it knows it
        if (found) portField.setText(String.valueOf(shell.pairingPort()));
        pairButton.setEnabled(!shell.isPairing());
        pairButton.setAlpha(shell.isPairing() ? 0.5f : 1f);
        if (shell.pairingLost() && !shell.isPairing() && status.length() == 0) {
            status.setTextColor(Style.DANGER);
            status.setText(R.string.xd_act_pairing_lost);
        }
    }
}
