package com.termux.x11;

import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.termux.x11.app.R;

/** 第一次激活注入服务：怎么打开无线调试，还有一个输入配对码的地方。通知里输不了时全靠这里。 */
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

        text(ui.section(content, c.getString(R.string.xd_act_steps_title)), c.getString(R.string.xd_act_steps));
        TextView open = Style.button(c, c.getString(R.string.xd_act_open_wireless), Style.ACCENT, Style.TEXT_BODY, Style.RADIUS);
        open.setOnClickListener(v -> actions.openPairingSettings());
        content.addView(open, buttonParams());

        LinearLayout card = ui.section(content, c.getString(R.string.xd_act_code_title));
        codeField = field(c.getString(R.string.xd_act_code_hint));
        card.addView(codeField, fieldParams());
        portNote = text(card, "");
        portField = field(c.getString(R.string.xd_act_port_hint));
        card.addView(portField, fieldParams());
        pairButton = Style.button(c, c.getString(R.string.xd_act_pair), Style.ACCENT, Style.TEXT_BODY, Style.RADIUS);
        pairButton.setOnClickListener(v -> pair());
        card.addView(pairButton, fieldParams());
        status = text(card, "");

        text(ui.section(content, c.getString(R.string.xd_act_later_title)), c.getString(R.string.xd_act_later));

        if (offerShizuku) {
            TextView shizuku = Style.button(c, c.getString(R.string.xd_act_use_shizuku), Style.SURFACE_RAISED, Style.TEXT_BODY, Style.RADIUS);
            shizuku.setOnClickListener(v -> actions.useShizuku());
            content.addView(shizuku, buttonParams());
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

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(Style.TARGET));
        p.topMargin = dp(12);
        return p;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(Style.TARGET));
        p.setMargins(dp(8), dp(8), dp(8), dp(4));
        return p;
    }

    private TextView text(LinearLayout parent, String s) {
        TextView t = Style.label(c, s, Style.TEXT, Style.TEXT_BODY - 1);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setLineSpacing(dp(2), 1f);
        parent.addView(t);
        return t;
    }

    private EditText field(String hint) {
        EditText f = new EditText(c);
        f.setInputType(InputType.TYPE_CLASS_NUMBER);
        f.setSingleLine(true);
        f.setTextColor(Style.TEXT);
        f.setHintTextColor(Style.TEXT_DIM);
        f.setTextSize(Style.TEXT_BODY);
        f.setGravity(Gravity.CENTER);
        f.setHint(hint);
        f.setPadding(0, 0, 0, 0);
        f.setBackground(Style.rounded(c, Style.SURFACE_RAISED, Style.RADIUS));
        return f;
    }

    private void pair() {
        String code = codeField.getText().toString().trim();
        int port = shell.pairingPort();
        if (port <= 0) {
            try {
                port = Integer.parseInt(portField.getText().toString().trim());
            } catch (NumberFormatException e) {
                status.setText(R.string.xd_act_need_port);
                return;
            }
        }
        if (code.length() != CODE_LENGTH) {
            status.setText(R.string.xd_act_code_length);
            return;
        }
        status.setText(R.string.xd_act_pairing);
        shell.pair(port, code, error -> status.setText(error));
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
        portNote.setText(found ? R.string.xd_act_port_found : R.string.xd_act_port_missing);
        portField.setVisibility(found ? View.GONE : View.VISIBLE);
        pairButton.setEnabled(!shell.isPairing());
        pairButton.setAlpha(shell.isPairing() ? 0.5f : 1f);
        if (shell.pairingLost() && !shell.isPairing() && status.length() == 0) status.setText(R.string.xd_act_pairing_lost);
    }
}
