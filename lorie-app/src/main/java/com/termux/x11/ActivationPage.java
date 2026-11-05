package com.termux.x11;

import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

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

        text(ui.section(content, "怎么做"), "安卓应用的键鼠转发要靠一个 shell 权限的小服务。不用 root，也不用装别的 App。配对只要做一次。\n\n"
            + "1. 连上 Wi-Fi。激活时必须连着，手机自己的热点可能不算。激活之后 Wi-Fi 断了也没关系。\n\n"
            + "2. 点下面的按钮进入开发者选项，打开“无线调试”，再点“使用配对码配对设备”。没有开发者选项的话，先在“关于手机”里连点版本号打开它。\n\n"
            + "3. 配对框里有 6 位配对码。XDesk 的通知里可以直接输入。\n\n"
            + "4. 通知里输不了的话（HyperOS 上有人遇到过），在下面输入。配对框要一直开着，切回这里时它关了的话，用分屏或小窗同时打开设置和 XDesk。");
        TextView open = Style.button(c, "打开无线调试设置", Style.ACCENT, Style.TEXT_BODY, Style.RADIUS);
        open.setOnClickListener(v -> actions.openPairingSettings());
        content.addView(open, buttonParams());

        LinearLayout card = ui.section(content, "配对码");
        codeField = field("6 位配对码");
        card.addView(codeField, fieldParams());
        portNote = text(card, "");
        portField = field("配对框里冒号后面的端口");
        card.addView(portField, fieldParams());
        pairButton = Style.button(c, "配对", Style.ACCENT, Style.TEXT_BODY, Style.RADIUS);
        pairButton.setOnClickListener(v -> pair());
        card.addView(pairButton, fieldParams());
        status = text(card, "");

        text(ui.section(content, "以后"), "打开 XDesk 时，只要无线调试开着，它会自己启动服务。\n\n"
            + "重启手机后要重新激活：系统允许的话，XDesk 会在连着 Wi-Fi 时自己打开无线调试，否则会告诉你怎么做。\n\n"
            + "运行期间不要关 USB 调试或开发者选项。");

        if (offerShizuku) {
            TextView shizuku = Style.button(c, "改用 Shizuku", Style.SURFACE_RAISED, Style.TEXT_BODY, Style.RADIUS);
            shizuku.setOnClickListener(v -> actions.useShizuku());
            content.addView(shizuku, buttonParams());
        }

        dialog = ui.page("激活注入服务", content);
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
                status.setText("还不知道配对端口，填配对框里冒号后面的数字。");
                return;
            }
        }
        if (code.length() != CODE_LENGTH) {
            status.setText("配对码是 6 位数字。");
            return;
        }
        status.setText("正在配对…");
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
        portNote.setText(found ? "已经找到配对端口。" : "还没有找到配对端口。配对框开着的话，也可以自己填。");
        portField.setVisibility(found ? View.GONE : View.VISIBLE);
        pairButton.setEnabled(!shell.isPairing());
        pairButton.setAlpha(shell.isPairing() ? 0.5f : 1f);
        if (shell.pairingLost() && !shell.isPairing() && status.length() == 0) status.setText("系统里的配对记录没有了，需要重新配对。");
    }
}
