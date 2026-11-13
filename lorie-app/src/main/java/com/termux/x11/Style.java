package com.termux.x11;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 外观集中在这里：近黑的底，稍亮的面，一个蓝色强调色，状态色只用在小圆点上。边框和文字按钮尽量少，常用操作用图标。
 * 手指点的东西至少 TARGET 高，不看也能点到。界面全部用代码构建。
 */
final class Style {
    static final int BACKGROUND = 0xFF0B0D10;
    static final int SURFACE = 0xFF15181D;
    static final int SURFACE_RAISED = 0xFF21252C;
    static final int RIPPLE = 0x33FFFFFF;
    static final int TEXT = 0xFFE8ECF1;
    static final int TEXT_DIM = 0xFF8D97A3;
    static final int ACCENT = 0xFF3D7EF5;
    static final int OK = 0xFF3FCF7F;
    static final int WARN = 0xFFF2B33D;
    static final int DANGER = 0xFFE5534B;

    /** 间距，单位 dp。 */
    static final int GAP = 6, EDGE = 8;
    /** 手指目标的最小边长，单位 dp。 */
    static final int TARGET = 48;
    /** 字号，单位 sp。 */
    static final float TEXT_TITLE = 20, TEXT_BODY = 15, TEXT_SMALL = 12, TEXT_TINY = 10;
    /** 圆角，单位 dp。 */
    static final float RADIUS = 12;

    private Style() { }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static Drawable ripple(Context c, int color, float radiusDp) {
        GradientDrawable shape = rounded(c, color, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, rounded(c, Color.WHITE, radiusDp));
    }

    /** 不抢焦点的按钮：键盘焦点要一直留在 KeyInputView 上。 */
    static TextView button(Context c, String label, int color, float textSp, float radiusDp) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setTextSize(textSp);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(c, color, radiusDp));
        b.setClickable(true);
        b.setFocusable(false);
        b.setHapticFeedbackEnabled(true);
        return b;
    }

    /** Hints drawn on a canvas: smaller than size when the longest line would be wider than width. Text depends on the language. */
    static void fitText(Paint p, float size, float width, String... lines) {
        p.setTextSize(size);
        float widest = 0;
        for (String l : lines) widest = Math.max(widest, p.measureText(l));
        if (width > 0 && widest > width) p.setTextSize(size * width / widest);
    }

    static void tick(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    static TextView label(Context c, String text, int color, float sp) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sp);
        return t;
    }

    /** 小圆点：状态色只出现在这里。 */
    /** An app without an icon (a Linux program, a Mac app the Mac sent none for): its first letter on a tile. */
    static Drawable letterIcon(Context c, String label) {
        final String letter = label == null || label.isEmpty() ? "?" : label.substring(0, label.offsetByCodePoints(0, 1)).toUpperCase();
        final float radius = dp(c, 10);
        return new Drawable() {
            private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
            { tile.setColor(SURFACE_RAISED); text.setColor(TEXT); text.setTextAlign(Paint.Align.CENTER); }
            @Override public void draw(android.graphics.Canvas canvas) {
                android.graphics.Rect b = getBounds();
                canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, radius, radius, tile);
                text.setTextSize(b.height() * 0.5f);
                Paint.FontMetrics m = text.getFontMetrics();
                canvas.drawText(letter, b.exactCenterX(), b.exactCenterY() - (m.ascent + m.descent) / 2, text);
            }
            @Override public void setAlpha(int a) { tile.setAlpha(a); text.setAlpha(a); }
            @Override public void setColorFilter(android.graphics.ColorFilter f) { text.setColorFilter(f); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    static GradientDrawable dot(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /** 常用操作：一个图标，下面可以有一行小字，比如当前的值。不抢焦点。 */
    static final class IconButton extends LinearLayout {
        private final ImageView icon;
        private final TextView text;
        private final int color;

        IconButton(Context c, int iconId, String label, int color) {
            super(c);
            this.color = color;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setClickable(true);
            setFocusable(false);
            setHapticFeedbackEnabled(true);
            setMinimumHeight(dp(c, TARGET));
            setMinimumWidth(dp(c, TARGET));
            setBackground(ripple(c, color, RADIUS));
            icon = new ImageView(c);
            icon.setImageResource(iconId);
            icon.setColorFilter(TEXT);
            int size = dp(c, 22);
            addView(icon, new LayoutParams(size, size));
            text = new TextView(c);
            text.setTextColor(TEXT);
            text.setTextSize(TEXT_TINY);
            text.setSingleLine(true);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setGravity(Gravity.CENTER);
            addView(text, new LayoutParams(-2, -2));
            setLabel(label);
            setContentDescription(label);
        }

        void setLabel(String label) {
            text.setText(label);
            text.setVisibility(label == null || label.isEmpty() ? GONE : VISIBLE);
        }

        void setIcon(int iconId) {
            icon.setImageResource(iconId);
        }

        /** 强调色表示开着，比如接管了键鼠。 */
        void setActive(boolean on) {
            setBackground(ripple(getContext(), on ? ACCENT : color, RADIUS));
        }

        void onTap(final Runnable action) {
            setOnClickListener(v -> {
                tick(v);
                action.run();
            });
        }
    }
}
