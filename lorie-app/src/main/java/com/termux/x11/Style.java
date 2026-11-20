package com.termux.x11;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
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
    static final int LINE = 0xFF242931;
    static final int RIPPLE = 0x33FFFFFF;
    static final int TEXT = 0xFFE8ECF1;
    static final int TEXT_DIM = 0xFF8D97A3;
    /** The second line on the shell and the setup pages: brighter than TEXT_DIM, dimmer than TEXT (xd.css' #C9D0D8). */
    static final int TEXT_SOFT = 0xFFC9D0D8;
    static final int FAINT = 0xFF5C6470;
    /** A raised row or tile on the shell: white at 5–6%. */
    static final int SHELL_ROW = 0x0DFFFFFF, SHELL_TILE = 0x0FFFFFFF;
    static final int ACCENT = 0xFF3D7EF5;
    static final int ACCENT_2 = 0xFF7FABFA;
    static final int ACCENT_SOFT = 0x293D7EF5;
    static final int OK = 0xFF3FCF7F;
    static final int WARN = 0xFFF0883E;
    static final int DANGER = 0xFFE5534B;
    /** The external shell and its dialogs: see xd.css. */
    static final int SHELL = 0xE011141A, SHELL_LINE = 0x14FFFFFF, SCRIM = 0x9E050608;
    /** Device marks: a Mac and a Linux program. */
    static final int MAC = 0xFF8DB8FF, LINUX = 0xFF3FCF7F;
    /**
     * 可以再来一次的停止：断开 Mac、停止 Linux 桌面、停止内置服务、停止投屏。黄色字，不加底色（用户 2026-11-19：
     * 停止和断开用黄字，只有移除才加底色）。比权限卡的橙色 WARN 更亮更黄，两者一眼分得开；黄底按钮上用深色字。
     */
    static final int STOP = 0xFFFFDD55;
    /** 黄色按钮上的字：深色，同权限卡琥珀按钮的字。 */
    static final int ON_STOP = 0xFF1A1405;
    /** 移除、忘记、结束这一行的淡红底：一眼看出是按钮，又不像实心红块那样抢眼。 */
    static final int DANGER_TINT = 0x29E5534B;

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

    /**
     * The shell every external panel shares (xd.css .shell): SHELL with a SHELL_LINE hairline, rounded by radiusDp. Takes
     * the caller's density (Sizing.shell on the external screen) so the corners follow b·u and not the display's
     * Android density override.
     */
    static GradientDrawable shell(float density, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(SHELL);
        g.setCornerRadius(radiusDp * density);
        g.setStroke(Math.max(1, Math.round(density)), SHELL_LINE);
        return g;
    }

    /** The same with a corner radius per corner (top-left, top-right, bottom-right, bottom-left), for an edge panel. */
    static GradientDrawable shell(float density, float tl, float tr, float br, float bl) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(SHELL);
        float[] r = {tl * density, tl * density, tr * density, tr * density, br * density, br * density, bl * density, bl * density};
        g.setCornerRadii(r);
        g.setStroke(Math.max(1, Math.round(density)), SHELL_LINE);
        return g;
    }

    static Drawable ripple(Context c, int color, float radiusDp) {
        GradientDrawable shape = rounded(c, color, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, rounded(c, Color.WHITE, radiusDp));
    }

    /**
     * The no-display hint as the mockup draws it: a blue-tinted card with a screen icon in a soft ring and white text,
     * the whole card a button (it opens the receiver picker), not a line of orange words.
     */
    static void hintCard(TextView t) {
        Context c = t.getContext();
        t.setBackground(ripple(c, 0xFF15203A, 14));
        t.setTextColor(TEXT);
        t.setTextSize(13);
        t.setLineSpacing(0, 1.15f);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0x248DB8FF);
        Drawable icon = c.getResources().getDrawable(com.termux.x11.app.R.drawable.xd_ic_display, c.getTheme()).mutate();
        icon.setTint(MAC);
        android.graphics.drawable.LayerDrawable both = new android.graphics.drawable.LayerDrawable(new Drawable[]{ring, icon});
        int inset = dp(c, 9);
        both.setLayerInset(1, inset, inset, inset, inset);
        both.setBounds(0, 0, dp(c, 40), dp(c, 40));
        t.setCompoundDrawablesRelative(both, null, null, null);
        t.setCompoundDrawablePadding(dp(c, 12));
        t.setClickable(true);
    }

    /**
     * A button that can be on: the on state is an accent-soft fill with a 1dp accent border, the off state its normal
     * colour. The state is shown by the look alone, never by "on"/"off" in the label.
     */
    static Drawable toggle(Context c, boolean on, int offColor, float radiusDp) {
        GradientDrawable shape = rounded(c, on ? ACCENT_SOFT : offColor, radiusDp);
        if (on) shape.setStroke(dp(c, 1), ACCENT);
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
        return letterIcon(c, label, c.getResources().getDisplayMetrics().density);
    }

    /** The same with a caller's factor (Sizing.shell on the external screen), so the corners do not follow the display's override. */
    static Drawable letterIcon(Context c, String label, float density) {
        final String letter = label == null || label.isEmpty() ? "?" : label.substring(0, label.offsetByCodePoints(0, 1)).toUpperCase();
        return wordIcon(c, letter, density);
    }

    /** letterIcon's tile, but the whole of a short label ("+12") instead of its first letter. */
    static Drawable wordIcon(Context c, String label, float density) {
        final String word = label == null || label.isEmpty() ? "?" : label;
        final float radius = 10 * density;
        return new Drawable() {
            private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
            { tile.setColor(SURFACE_RAISED); text.setColor(TEXT); text.setTextAlign(Paint.Align.CENTER); }
            @Override public void draw(android.graphics.Canvas canvas) {
                android.graphics.Rect b = getBounds();
                canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, radius, radius, tile);
                // "+12" is the longest there is: a little smaller than a single letter's half of the height
                text.setTextSize(b.height() * (word.length() > 2 ? 0.42f : 0.5f));
                Paint.FontMetrics m = text.getFontMetrics();
                canvas.drawText(word, b.exactCenterX(), b.exactCenterY() - (m.ascent + m.descent) / 2, text);
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

    /**
     * 正在外接屏上的那一项：图标外面一圈，不是整块底色（Components.dc.html：2.5 dp 蓝色描边，离图标 3 dp）。
     * 不亮时是全透明的同一形状，布局不跟着变。
     */
    static GradientDrawable iconRing(Context c, float iconDp, boolean on) {
        return iconRing(c.getResources().getDisplayMetrics().density, iconDp, on);
    }

    /** The same with a caller's factor (the shell's b·u on the external screen), so the ring follows it. */
    static GradientDrawable iconRing(float density, float iconDp, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius((iconDp * 0.22f + 3) * density);
        if (on) g.setStroke(Math.max(1, Math.round(2.5f * density)), ACCENT);
        return g;
    }

    /** 常用操作：一个图标，下面可以有一行小字，比如当前的值。不抢焦点。 */
    static final class IconButton extends LinearLayout {
        private final ImageView icon;
        private final TextView text;
        private final int color;
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** PermExpired: a red dot on the top-right corner, for a required permission that is off. */
        private boolean badge;

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
            icon = new ImageView(c);
            icon.setImageResource(iconId);
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
            setActive(false);
        }

        void setLabel(String label) {
            text.setText(label);
            text.setVisibility(label == null || label.isEmpty() ? GONE : VISIBLE);
        }

        void setIcon(int iconId) {
            icon.setImageResource(iconId);
        }

        /** On, such as the keys taken by the display: accent-soft with a border, off its usual surface. */
        void setActive(boolean on) {
            setBackground(toggle(getContext(), on, color, RADIUS));
            icon.setColorFilter(on ? ACCENT_2 : TEXT);
            text.setTextColor(on ? ACCENT_2 : TEXT);
        }

        void onTap(final Runnable action) {
            setOnClickListener(v -> {
                tick(v);
                action.run();
            });
        }

        /** A small DANGER dot in the top-right corner (PermExpired.dc.html): a required permission is off. */
        void setBadge(boolean on) {
            if (badge == on) return;
            badge = on;
            invalidate();
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (!badge) return;
            float d = dp(getContext(), 4.5f), ring = dp(getContext(), 2), margin = dp(getContext(), 6);
            float cx = getWidth() - margin - d, cy = margin + d;
            badgePaint.setStyle(Paint.Style.FILL);
            badgePaint.setColor(color);  // the button's own surface: the dot reads as cut out of it
            canvas.drawCircle(cx, cy, d + ring, badgePaint);
            badgePaint.setColor(DANGER);
            canvas.drawCircle(cx, cy, d, badgePaint);
        }
    }

    /**
     * The long hint where it fits on one line, the short one where it does not: a single-line field cuts its hint at
     * the edge, which left "（可用拼音首字母" without its closing bracket in narrow search fields.
     */
    static void fitHint(android.widget.EditText field, int longHint, int shortHint) {
        final String full = field.getContext().getString(longHint), brief = field.getContext().getString(shortHint);
        field.setHint(full);
        field.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int room = (r - l) - field.getCompoundPaddingLeft() - field.getCompoundPaddingRight();
            if (room <= 0) return;
            String want = field.getPaint().measureText(full) <= room ? full : brief;
            if (!want.contentEquals(field.getHint() != null ? field.getHint() : "")) field.post(() -> field.setHint(want));
        });
    }
}
