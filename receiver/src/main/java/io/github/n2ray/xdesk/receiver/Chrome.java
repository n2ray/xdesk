package io.github.n2ray.xdesk.receiver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;

/**
 * The colours and the few drawn things of the receiver's screens, from .spec/app (xd.css, RecvIdle,
 * RecvPairing, RecvHandle, RecvMenu): no images but the app's own vector logo.
 */
final class Chrome {
    static final int TEXT = 0xFFE8ECF1;
    static final int HINT = 0xFFC9D0D8;
    static final int DIM = 0xFF8D97A3;
    static final int FAINT = 0xFF5C6470;
    static final int ACCENT = 0xFF3D7EF5;
    static final int WARN = 0xFFF0883E;
    static final int DANGER = 0xFFE5534B;
    static final int STOP = 0xFFFFDD55;
    /** The shell of the bar and the options: #11141A at 88%, a white line at 8% (xd.css). */
    static final int SHELL = 0xE011141A;
    static final int SHELL_LINE = 0x14FFFFFF;
    /** The options row under the D-pad: the accent at 22% with a 2 dp accent edge. */
    static final int FOCUS_FILL = 0x383D7EF5;

    static final int KEYBOARD = 1, TABLET = 2, COMPUTER = 3, BACK = 4, HOME = 5, LAUNCHPAD = 6, SWITCHER = 7,
            DISCONNECT = 8, RENAME = 9, FORGET = 10;

    private Chrome() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable shell(Context c, float radiusDp) {
        GradientDrawable d = rounded(c, SHELL, radiusDp);
        d.setStroke(Math.max(1, dp(c, 1)), SHELL_LINE);
        return d;
    }

    /** A row of the options: clear, the accent while the D-pad is on it, a little lighter under a finger. */
    static Drawable row(Context c) {
        StateListDrawable d = new StateListDrawable();
        GradientDrawable focus = rounded(c, FOCUS_FILL, 14);
        focus.setStroke(Math.max(1, dp(c, 2)), ACCENT);
        d.addState(new int[] { android.R.attr.state_focused }, focus);
        d.addState(new int[] { android.R.attr.state_pressed }, rounded(c, 0x1FFFFFFF, 14));
        d.addState(new int[0], rounded(c, 0x00000000, 14));
        return d;
    }

    /** The waiting screen's background: a dark blue glow a little above the middle, fading to near black. */
    static Drawable radial() {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                int w = b.width(), h = b.height();
                if (w <= 0 || h <= 0) return;
                paint.setShader(new RadialGradient(b.exactCenterX(), b.top + h * 0.40f,
                        Math.max(w, h) * 0.85f, 0xFF121826, 0xFF07080B, Shader.TileMode.CLAMP));
                canvas.drawRect(b, paint);
            }

            @Override
            public void setAlpha(int alpha) {
            }

            @Override
            public void setColorFilter(ColorFilter filter) {
            }

            @Override
            public int getOpacity() {
                return PixelFormat.OPAQUE;
            }
        };
    }

    /** The two blue rings behind the logo: 200 dp across, a second at inset 28. */
    static final class Rings extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();

        Rings(Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float stroke = Math.max(1f, 1.5f * getResources().getDisplayMetrics().density);
            paint.setStrokeWidth(stroke);
            float half = stroke / 2f;
            paint.setColor(0x593D7EF5);  // the accent at 35%
            oval.set(half, half, getWidth() - half, getHeight() - half);
            canvas.drawOval(oval, paint);
            float inset = dp(getContext(), 28) + half;
            paint.setColor(0x8C3D7EF5);  // at 55%
            oval.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawOval(oval, paint);
        }
    }

    /** The pairing code: four rounded boxes, as RecvPairing.dc.html draws them. */
    static final class Code extends View {
        private static final int BOXES = 4;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private String code = "";

        Code(Context c) {
            super(c);
            fill.setColor(0xFF15181D);
            line.setStyle(Paint.Style.STROKE);
            line.setColor(0x0FFFFFFF);
            text.setColor(TEXT);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        void setCode(String value) {
            code = value == null ? "" : value;
            setContentDescription(code);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float gap = 16 * density;
            float radius = 20 * density;
            float boxWidth = Math.min(110 * density, (getWidth() - gap * (BOXES - 1)) / BOXES);
            float boxHeight = Math.min(140 * density, getHeight());
            if (boxWidth <= 0 || boxHeight <= 0) return;
            float total = boxWidth * BOXES + gap * (BOXES - 1);
            float left = (getWidth() - total) / 2f;
            float top = (getHeight() - boxHeight) / 2f;
            line.setStrokeWidth(Math.max(1f, 1.5f * density));
            text.setTextSize(Math.min(84 * getResources().getDisplayMetrics().scaledDensity, boxHeight * 0.62f));
            Paint.FontMetrics metrics = text.getFontMetrics();
            float baseline = top + boxHeight / 2f - (metrics.ascent + metrics.descent) / 2f;
            for (int i = 0; i < BOXES; i++) {
                float x = left + i * (boxWidth + gap);
                box.set(x, top, x + boxWidth, top + boxHeight);
                canvas.drawRoundRect(box, radius, radius, fill);
                canvas.drawRoundRect(box, radius, radius, line);
                if (i < code.length()) {
                    canvas.drawText(String.valueOf(code.charAt(i)), x + boxWidth / 2f, baseline, text);
                }
            }
        }
    }

    /** The on/off switch of the options (xd.css .sw): a blue track when on, a grey one when off. */
    static final class Toggle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean on;

        Toggle(Context c, boolean on) {
            super(c);
            this.on = on;
            // The row's words say what it is; the switch is its state, as xd.css draws it
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setOn(boolean value) {
            on = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float width = getWidth(), height = getHeight(), radius = height / 2f;
            paint.setColor(on ? ACCENT : 0xFF3A414C);
            canvas.drawRoundRect(new RectF(0, 0, width, height), radius, radius, paint);
            float knob = height / 2f - 3 * density;
            float cx = on ? width - 3 * density - knob : 3 * density + knob;
            paint.setColor(on ? 0xFFFFFFFF : 0xFFC9D0D8);
            canvas.drawCircle(cx, height / 2f, knob, paint);
        }
    }

    /** A line icon of the mockups, on the 24 grid with a 1.8 stroke. */
    static final class Icon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;
        private int icon, color;

        Icon(Context c, int icon, int color) {
            this.icon = icon;
            this.color = color;
            size = dp(c, 22);
        }

        void setIcon(int value) {
            icon = value;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float side = Math.min(b.width(), b.height());
            canvas.save();
            canvas.translate(b.exactCenterX() - side / 2f, b.exactCenterY() - side / 2f);
            canvas.scale(side / 24f, side / 24f);
            drawIcon(canvas, icon, color);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            color = (color & 0x00FFFFFF) | (alpha << 24);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }
    }

    /** Paths from Icons.dc.html and the receiver's mockups, all in the 24 grid. */
    private static void drawIcon(Canvas c, int icon, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.8f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        switch (icon) {
            case KEYBOARD:
                c.drawRoundRect(new RectF(2, 7, 22, 18), 2, 2, p);
                dot(c, p, 6, 11);
                dot(c, p, 10, 11);
                dot(c, p, 14, 11);
                dot(c, p, 18, 11);
                c.drawLine(7, 15, 17, 15, p);
                break;
            case TABLET:
                c.drawRoundRect(new RectF(6, 3, 18, 21), 2, 2, p);
                c.drawLine(11, 18, 13, 18, p);
                break;
            case COMPUTER:
                c.drawRoundRect(new RectF(7, 8, 17, 21), 5, 5, p);
                c.drawLine(12, 8, 12, 13, p);
                c.drawLine(7, 13, 17, 13, p);
                Path cable = new Path();
                cable.moveTo(12, 8);
                cable.cubicTo(12, 5, 16, 6, 16, 3);
                c.drawPath(cable, p);
                break;
            case BACK: {
                Path path = new Path();
                path.moveTo(15, 5);
                path.lineTo(8, 12);
                path.lineTo(15, 19);
                c.drawPath(path, p);
                break;
            }
            case HOME: {
                Path path = new Path();
                path.moveTo(4, 11);
                path.lineTo(12, 4);
                path.lineTo(20, 11);
                path.lineTo(20, 20);
                path.lineTo(4, 20);
                path.close();
                c.drawPath(path, p);
                break;
            }
            case LAUNCHPAD:
                c.drawRoundRect(new RectF(4, 4, 10, 10), 1.5f, 1.5f, p);
                c.drawRoundRect(new RectF(14, 4, 20, 10), 1.5f, 1.5f, p);
                c.drawRoundRect(new RectF(4, 14, 10, 20), 1.5f, 1.5f, p);
                c.drawRoundRect(new RectF(14, 14, 20, 20), 1.5f, 1.5f, p);
                break;
            case SWITCHER:
                c.drawRoundRect(new RectF(3, 6, 15, 18), 2, 2, p);
                c.drawLine(19, 8, 19, 16, p);
                c.drawLine(22, 10, 22, 14, p);
                break;
            case DISCONNECT: {
                Path path = new Path();
                path.moveTo(9, 15);
                path.lineTo(7, 17);
                path.arcTo(new RectF(1, 11, 8, 18), 45, 180);
                path.lineTo(4, 10);
                path.moveTo(15, 9);
                path.lineTo(17, 7);
                path.arcTo(new RectF(16, 6, 23, 13), 225, 180);
                path.lineTo(20, 14);
                path.moveTo(8, 8);
                path.lineTo(4, 4);
                path.moveTo(20, 20);
                path.lineTo(16, 16);
                c.drawPath(path, p);
                break;
            }
            case RENAME: {
                Path path = new Path();
                path.moveTo(4, 20);
                path.lineTo(8, 20);
                path.lineTo(19, 9);
                path.lineTo(15, 5);
                path.lineTo(4, 16);
                path.close();
                path.moveTo(13, 7);
                path.lineTo(17, 11);
                c.drawPath(path, p);
                break;
            }
            case FORGET: {
                c.drawLine(4, 7, 20, 7, p);
                Path lid = new Path();
                lid.moveTo(9, 7);
                lid.lineTo(9, 4);
                lid.lineTo(15, 4);
                lid.lineTo(15, 7);
                c.drawPath(lid, p);
                Path bin = new Path();
                bin.moveTo(6, 7);
                bin.lineTo(7, 20);
                bin.lineTo(17, 20);
                bin.lineTo(18, 7);
                c.drawPath(bin, p);
                break;
            }
            default:
                break;
        }
    }

    private static void dot(Canvas c, Paint p, float x, float y) {
        c.drawLine(x, y, x + 0.01f, y, p);
    }
}
