package com.termux.x11;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.TextView;

/** 颜色和控件样式。界面全部用代码构建，集中在这里调。 */
final class Style {
    static final int BACKGROUND = 0xFF0E1116;
    static final int SURFACE = 0xFF1A1F26;
    static final int SURFACE_RAISED = 0xFF262C35;
    static final int RIPPLE = 0x33FFFFFF;
    static final int TEXT = 0xFFE8ECF1;
    static final int TEXT_DIM = 0xFF8D97A3;
    static final int ACCENT = 0xFF4C8DFF;
    static final int OK = 0xFF3FCF7F;
    static final int WARN = 0xFFF2B33D;
    static final int DANGER = 0xFFE5534B;

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

    static void tick(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }
}
