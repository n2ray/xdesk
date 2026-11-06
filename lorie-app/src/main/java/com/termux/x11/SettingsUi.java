package com.termux.x11;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import com.termux.x11.app.R;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 设置和应用卡片共用的组件：圆角卡片分组，每行有名称、需要时的说明、右边的当前值。开关和选择都用强调色，
 * 不用系统默认的复选框、单选框和下拉框。只管样子，存什么、改什么由调用的地方决定。
 */
final class SettingsUi {
    /** 大屏或横屏时内容不拉得太宽。 */
    private static final int MAX_WIDTH_DP = 640, SHEET_MAX_WIDTH_DP = 480;
    private static final int ROW_MIN_DP = 56;

    private final Context c;

    SettingsUi(Context c) {
        this.c = c;
    }

    private int dp(float v) {
        return Style.dp(c, v);
    }

    /** 整屏的设置页：标题栏加可以滚动的内容。 */
    Dialog page(String title, View content) {
        final Dialog d = new Dialog(c, android.R.style.Theme_Material_NoActionBar);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Style.BACKGROUND);
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(4), dp(16), dp(4));
        Style.IconButton back = new Style.IconButton(c, R.drawable.xd_ic_back, "", Style.BACKGROUND);
        back.setContentDescription(c.getString(R.string.xd_back));
        back.onTap(d::dismiss);
        head.addView(back, new LinearLayout.LayoutParams(dp(Style.TARGET), dp(Style.TARGET)));
        TextView t = Style.label(c, title, Style.TEXT, Style.TEXT_TITLE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(8), 0, 0, 0);
        head.addView(t);
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        FrameLayout center = new FrameLayout(c) {
            @Override protected void onMeasure(int w, int h) {
                // 超过最大宽度时内容居中
                int max = dp(MAX_WIDTH_DP), width = MeasureSpec.getSize(w);
                int side = Math.max(dp(12), (width - max) / 2);
                setPadding(side, 0, side, dp(16));
                super.onMeasure(w, h);
            }
        };
        center.addView(content, new FrameLayout.LayoutParams(-1, -2));
        scroll.addView(center);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        d.setContentView(root);
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        d.getWindow().setStatusBarColor(Style.BACKGROUND);
        d.getWindow().setNavigationBarColor(Style.BACKGROUND);
        d.show();
        return d;
    }

    /** 浮在中间的圆角卡片，比如选一项或应用卡片。内容太长时可以滚动。 */
    Dialog sheet(String title, String note, View content) {
        Dialog d = new Dialog(c, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(12));
        if (title != null) {
            TextView t = Style.label(c, title, Style.TEXT, Style.TEXT_BODY + 2);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            box.addView(t);
        }
        if (note != null) {
            TextView n = Style.label(c, note, Style.TEXT_DIM, Style.TEXT_SMALL + 1);
            n.setPadding(0, dp(6), 0, 0);
            box.addView(n);
        }
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(8);
        box.addView(content, cp);
        ScrollView scroll = new ScrollView(c);
        scroll.setBackground(Style.rounded(c, Style.SURFACE, 16));
        scroll.addView(box);
        d.setContentView(scroll);
        d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        d.show();
        int width = Math.min(dp(SHEET_MAX_WIDTH_DP), Math.round(c.getResources().getDisplayMetrics().widthPixels * 0.92f));
        d.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        return d;
    }

    /** 从底部弹出的面板，顶部有把手。内容太长时可以滚动。 */
    Dialog bottomSheet(View header, View content) {
        Dialog d = new Dialog(c, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(10), dp(16), dp(20));
        View handle = new View(c);
        handle.setBackground(Style.rounded(c, Style.TEXT_DIM, 2));
        handle.setAlpha(0.5f);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(14);
        box.addView(handle, hp);
        box.addView(header, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(14);
        box.addView(content, cp);
        ScrollView scroll = new ScrollView(c);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Style.BACKGROUND);
        float r = dp(24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        scroll.setBackground(bg);
        scroll.addView(box);
        d.setContentView(scroll);
        d.setCanceledOnTouchOutside(true);
        d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        d.getWindow().setGravity(Gravity.BOTTOM);
        d.show();
        int width = Math.min(dp(SHEET_MAX_WIDTH_DP + 80), c.getResources().getDisplayMetrics().widthPixels);
        d.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        return d;
    }

    /** 分组标题，比 section 紧凑，放在面板里。 */
    TextView groupLabel(LinearLayout parent, String title) {
        TextView t = Style.label(c, title, Style.TEXT_DIM, Style.TEXT_SMALL);
        t.setPadding(dp(4), dp(14), 0, dp(6));
        parent.addView(t);
        return t;
    }

    /** 名称在左，紧凑的数字框在右。改了才出现确认键，有值时可以清掉。 */
    void inlineNumberRow(LinearLayout card, String label, String description, String hint, int value, final int min, final int max,
                         final IntConsumer onValid, final Runnable onReset) {
        LinearLayout row = plainRow();
        row.setMinimumHeight(dp(Style.TARGET + 8));
        row.addView(labels(label, description), new LinearLayout.LayoutParams(0, -2, 1));
        final EditText field = new EditText(c);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setSingleLine(true);
        field.setTextColor(Style.TEXT);
        field.setHintTextColor(Style.TEXT_DIM);
        field.setTextSize(Style.TEXT_BODY);
        field.setGravity(Gravity.CENTER);
        field.setHint(hint);
        field.setPadding(0, 0, 0, 0);
        field.setBackground(Style.rounded(c, Style.SURFACE_RAISED, Style.RADIUS));
        if (value > 0) field.setText(String.valueOf(value));
        final TextView action = Style.button(c, value > 0 ? "×" : "✓", value > 0 ? Style.SURFACE_RAISED : Style.ACCENT, Style.TEXT_BODY, Style.RADIUS);
        action.setVisibility(value > 0 ? View.VISIBLE : View.GONE);
        final int[] saved = {value};
        final Runnable showAction = () -> {
            String now = field.getText().toString().trim();
            boolean changed = !now.equals(saved[0] > 0 ? String.valueOf(saved[0]) : "");
            if (changed && now.length() > 0) {
                action.setText("✓");
                action.setBackground(Style.ripple(c, Style.ACCENT, Style.RADIUS));
                action.setVisibility(View.VISIBLE);
            } else if (saved[0] > 0) {
                action.setText("×");
                action.setBackground(Style.ripple(c, Style.SURFACE_RAISED, Style.RADIUS));
                action.setVisibility(View.VISIBLE);
            } else {
                action.setVisibility(View.GONE);
            }
        };
        final Runnable confirm = () -> {
            int n;
            try {
                n = Integer.parseInt(field.getText().toString().trim());
            } catch (NumberFormatException e) {
                n = -1;
            }
            if (n < min || n > max) {
                field.setTextColor(Style.DANGER);
                android.widget.Toast.makeText(c, c.getString(R.string.xd_range, min, max), android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            field.setTextColor(Style.TEXT);
            c.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(field.getWindowToken(), 0);
            field.clearFocus();
            saved[0] = n;
            showAction.run();
            onValid.accept(n);
        };
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                field.setTextColor(Style.TEXT);
                showAction.run();
            }
        });
        field.setOnEditorActionListener((v, a, event) -> {
            confirm.run();
            return true;
        });
        action.setOnClickListener(v -> {
            Style.tick(v);
            if ("✓".contentEquals(action.getText())) {
                confirm.run();
            } else {
                saved[0] = 0;
                field.setText("");
                onReset.run();
            }
        });
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(dp(68), dp(Style.TARGET - 8));
        fp.leftMargin = dp(12);
        row.addView(field, fp);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(Style.TARGET - 8), dp(Style.TARGET - 8));
        ap.leftMargin = dp(6);
        row.addView(action, ap);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** 选一项：点了就关。 */
    Dialog choice(String title, String note, String[] options, int checked, final IntConsumer onPick) {
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] d = new Dialog[1];
        for (int i = 0; i < options.length; i++) {
            final int which = i;
            LinearLayout row = pressableRow();
            TextView t = Style.label(c, options[i], i == checked ? Style.ACCENT : Style.TEXT, Style.TEXT_BODY);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            if (i == checked) {
                View dot = new View(c);
                dot.setBackground(Style.dot(Style.ACCENT));
                row.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
            }
            row.setOnClickListener(v -> {
                Style.tick(v);
                d[0].dismiss();
                onPick.accept(which);
            });
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        d[0] = sheet(title, note, list);
        return d[0];
    }

    /** 确认一件不好撤销的事。 */
    Dialog confirm(String title, String message, String action, final Runnable onConfirm) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.END);
        final Dialog[] d = new Dialog[1];
        if (onConfirm != null) {
            TextView cancel = pill(c.getString(R.string.xd_cancel), Style.SURFACE_RAISED);
            cancel.setOnClickListener(v -> d[0].dismiss());
            row.addView(cancel, pillParams());
        }
        TextView go = pill(action, onConfirm != null ? Style.DANGER : Style.ACCENT);
        go.setOnClickListener(v -> {
            d[0].dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        row.addView(go, pillParams());
        d[0] = sheet(title, message, row);
        return d[0];
    }

    private LinearLayout.LayoutParams pillParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(Style.TARGET - 4));
        p.leftMargin = dp(8);
        return p;
    }

    TextView pill(String text, int color) {
        TextView b = Style.button(c, text, color, Style.TEXT_SMALL + 2, Style.RADIUS);
        b.setPadding(dp(14), 0, dp(14), 0);
        // 英文比中文长：分段选择里宁可省略也不换行
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return b;
    }

    /** 一组：上面一行小字，下面一张圆角卡片。往返回的卡片里加行。 */
    LinearLayout section(LinearLayout parent, String title) {
        TextView t = Style.label(c, title, Style.TEXT_DIM, Style.TEXT_SMALL);
        t.setPadding(dp(4), dp(16), 0, dp(6));
        parent.addView(t);
        return card(parent);
    }

    LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Style.rounded(c, Style.SURFACE, 16));
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        parent.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private LinearLayout pressableRow() {
        LinearLayout row = plainRow();
        row.setClickable(true);
        row.setBackground(Style.ripple(c, Color.TRANSPARENT, Style.RADIUS));
        return row;
    }

    private LinearLayout plainRow() {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ROW_MIN_DP));
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        return row;
    }

    private LinearLayout labels(String label, String description) {
        return labels(label, description, Style.TEXT);
    }

    private LinearLayout labels(String label, String description, int color) {
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(Style.label(c, label, color, Style.TEXT_BODY));
        if (description != null) {
            TextView d = Style.label(c, description, Style.TEXT_DIM, Style.TEXT_SMALL);
            d.setPadding(0, dp(2), 0, 0);
            text.addView(d);
        }
        return text;
    }

    /** 名称、说明，右边是当前值，点了做事。返回值文字，以后可以改。 */
    TextView valueRow(LinearLayout card, String label, String description, String value, final Runnable onTap) {
        LinearLayout row = pressableRow();
        row.addView(labels(label, description), new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = Style.label(c, value, Style.TEXT_DIM, Style.TEXT_SMALL + 2);
        v.setPadding(dp(8), 0, 0, 0);
        row.addView(v);
        row.setOnClickListener(view -> {
            Style.tick(view);
            onTap.run();
        });
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    /** 只显示，点不了。 */
    void infoRow(LinearLayout card, String label, String description, String value) {
        LinearLayout row = plainRow();
        row.addView(labels(label, description), new LinearLayout.LayoutParams(0, -2, 1));
        if (value != null) row.addView(Style.label(c, value, Style.TEXT_DIM, Style.TEXT_SMALL + 2));
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    void switchRow(LinearLayout card, String label, String description, boolean on, final Consumer<Boolean> onChange) {
        LinearLayout row = pressableRow();
        row.addView(labels(label, description), new LinearLayout.LayoutParams(0, -2, 1));
        final Toggle toggle = new Toggle(c, on);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(44), dp(26));
        tp.leftMargin = dp(12);
        row.addView(toggle, tp);
        row.setOnClickListener(v -> {
            Style.tick(v);
            toggle.setOn(!toggle.on);
            onChange.accept(toggle.on);
        });
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** 几个选项一行，选中的是强调色。点了立刻生效。 */
    void segmentedRow(LinearLayout card, String label, String description, String[] options, int selected, final IntConsumer onPick) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));
        box.addView(labels(label, description));
        // 一条轨道里的选中块，而不是几个分开的按钮
        LinearLayout row = new LinearLayout(c);
        row.setBackground(Style.rounded(c, Style.SURFACE_RAISED, Style.RADIUS));
        row.setPadding(dp(3), dp(3), dp(3), dp(3));
        final TextView[] buttons = new TextView[options.length];
        for (int i = 0; i < options.length; i++) {
            final int which = i;
            buttons[i] = pill(options[i], i == selected ? Style.ACCENT : Style.SURFACE_RAISED);
            buttons[i].setPadding(dp(6), 0, dp(6), 0);
            buttons[i].setOnClickListener(v -> {
                Style.tick(v);
                for (int k = 0; k < buttons.length; k++) {
                    buttons[k].setBackground(Style.ripple(c, k == which ? Style.ACCENT : Style.SURFACE_RAISED, Style.RADIUS - 3));
                }
                onPick.accept(which);
            });
            buttons[i].setBackground(Style.ripple(c, i == selected ? Style.ACCENT : Style.SURFACE_RAISED, Style.RADIUS - 3));
            row.addView(buttons[i], new LinearLayout.LayoutParams(0, dp(Style.TARGET - 10), 1));
        }
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(8);
        box.addView(row, rp);
        card.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    /** 百分比滑块，右边显示值。@param percent 50 到 200 */
    void percentRow(LinearLayout card, String label, String description, int percent, final IntConsumer onChange) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(4));
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(labels(label, description), new LinearLayout.LayoutParams(0, -2, 1));
        final TextView value = Style.label(c, percent + "%", Style.TEXT_DIM, Style.TEXT_SMALL + 2);
        head.addView(value);
        box.addView(head);
        SeekBar bar = new SeekBar(c);
        bar.setMax(150);
        bar.setProgress(percent - 50);
        bar.setProgressTintList(ColorStateList.valueOf(Style.ACCENT));
        bar.setThumbTintList(ColorStateList.valueOf(Style.ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Style.SURFACE_RAISED));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                value.setText((p + 50) + "%");
                if (user) onChange.accept(p + 50);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(bar, new LinearLayout.LayoutParams(-1, dp(Style.TARGET - 8)));
        card.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    /**
     * 名称和紧凑的数字框，确认时才生效，每次改动应用都要重新布局。
     *
     * @param value 现在的值，0 表示没有
     * @param resetLabel 第二个按钮，比如恢复默认，点了清空数字框
     */
    void numberRow(LinearLayout card, String label, String description, String hint, int value, final int min, final int max,
                   final IntConsumer onValid, String resetLabel, final Runnable onReset) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));
        box.addView(labels(label, description));
        final EditText field = new EditText(c);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setSingleLine(true);
        field.setTextColor(Style.TEXT);
        field.setHintTextColor(Style.TEXT_DIM);
        field.setTextSize(Style.TEXT_BODY);
        field.setGravity(Gravity.CENTER);
        field.setHint(hint);
        field.setBackground(Style.rounded(c, Style.SURFACE_RAISED, Style.RADIUS));
        if (value > 0) field.setText(String.valueOf(value));
        final Runnable confirm = () -> {
            int dpi;
            try {
                dpi = Integer.parseInt(field.getText().toString().trim());
            } catch (NumberFormatException e) {
                dpi = -1;
            }
            if (dpi < min || dpi > max) {
                field.setTextColor(Style.DANGER);
                android.widget.Toast.makeText(c, c.getString(R.string.xd_range, min, max), android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            field.setTextColor(Style.TEXT);
            c.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(field.getWindowToken(), 0);
            field.clearFocus();
            onValid.accept(dpi);
        };
        field.setOnEditorActionListener((v, action, event) -> {
            confirm.run();
            return true;
        });
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(field, new LinearLayout.LayoutParams(dp(84), dp(Style.TARGET - 4)));
        TextView apply = pill(c.getString(R.string.xd_apply), Style.ACCENT);
        apply.setOnClickListener(v -> {
            Style.tick(v);
            confirm.run();
        });
        row.addView(apply, pillParams());
        TextView reset = pill(resetLabel, Style.SURFACE_RAISED);
        reset.setOnClickListener(v -> {
            Style.tick(v);
            field.setText("");
            field.setTextColor(Style.TEXT);
            onReset.run();
        });
        row.addView(reset, pillParams());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(8);
        box.addView(row, rp);
        card.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    /** 红色的动作行，单独一张卡片。 */
    void dangerRow(LinearLayout parent, String label, String description, final Runnable onTap) {
        LinearLayout card = card(parent);
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) card.getLayoutParams();
        p.topMargin = dp(16);
        LinearLayout row = pressableRow();
        row.addView(labels(label, description, Style.DANGER), new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> {
            Style.tick(v);
            onTap.run();
        });
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** 开关：轨道和圆点，开着是强调色。 */
    private static final class Toggle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        boolean on;

        Toggle(Context c, boolean on) {
            super(c);
            this.on = on;
        }

        void setOn(boolean o) {
            on = o;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float h = getHeight(), r = h / 2;
            track.set(0, 0, getWidth(), h);
            paint.setColor(on ? Style.ACCENT : Style.SURFACE_RAISED);
            canvas.drawRoundRect(track, r, r, paint);
            paint.setColor(on ? Color.WHITE : Style.TEXT_DIM);
            float inset = h * 0.15f;
            canvas.drawCircle(on ? getWidth() - r : r, r, r - inset, paint);
        }
    }
}
