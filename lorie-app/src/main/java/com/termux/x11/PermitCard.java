package com.termux.x11;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.termux.x11.app.R;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The one look of "go and turn this permission on" (owner, 2026-11-18), on the phone and on the external screen alike:
 * amber (Style.WARN, which only this uses for a call to act) with a bar on the left and a key, "Turn on «X»", one line
 * why, one line where in the phone's settings, and an amber button that says where the page will open. Red stays for
 * errors and lost connections, grey for plain notes. A card whose permission is there when XDesk is back turns green,
 * says so, and goes.
 */
public final class PermitCard {
    /** Where the button's page opens: here (the phone's own screen), on the phone (seen from the external screen). */
    public static final int HERE = 0, ON_PHONE = 1;
    private static final int INK = 0xFF1A1405;  // the button's text, dark on amber
    private static final long DONE_MS = 2000;
    private static final List<WeakReference<PermitCard>> live = new ArrayList<>();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    public final LinearLayout view;
    private final BooleanSupplier granted;
    private final Context c;
    private final float density;
    private final GradientDrawable bar;
    private final ImageView icon;
    private final TextView title;
    private final LinearLayout rest;
    private boolean done;

    /**
     * @param density pixels per dp on the screen it shows on (the external screen's panel has its own)
     * @param what the permission's name as the system's settings call it
     * @param why one line: what does not work without it
     * @param path where it is in the phone's settings, or null
     * @param action the button's word, null for the usual one (by {@code where}); no button when {@code open} is null
     * @param granted whether it is there now, or null: the card then stays until its page is built again
     */
    public PermitCard(Context c, float density, String what, String why, String path, int where, String action, Runnable open,
               BooleanSupplier granted) {
        this.c = c;
        this.density = density;
        this.granted = granted;
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable back = new GradientDrawable();
        back.setColor(0x1FF2B33D);
        back.setCornerRadius(dp(12));
        bar = new GradientDrawable();
        bar.setColor(Style.WARN);
        bar.setCornerRadius(dp(2));
        LayerDrawable layers = new LayerDrawable(new Drawable[]{back, bar});
        layers.setLayerGravity(1, Gravity.START | Gravity.FILL_VERTICAL);
        layers.setLayerWidth(1, dp(4));
        layers.setLayerInset(1, dp(4), dp(10), 0, dp(10));
        view.setBackground(layers);
        view.setPadding(dp(16), dp(12), dp(12), dp(12));

        icon = new ImageView(c);
        icon.setImageDrawable(new KeyIcon(Style.WARN));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(22), dp(22));
        ip.rightMargin = dp(10);
        ip.topMargin = dp(1);
        view.addView(icon, ip);

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        title = label(Style.TEXT, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setText(c.getString(R.string.xd_permit_title, what));
        text.addView(title);
        rest = new LinearLayout(c);
        rest.setOrientation(LinearLayout.VERTICAL);
        if (why != null) {
            TextView w = label(Style.TEXT, 13);
            w.setText(why);
            w.setPadding(0, dp(4), 0, 0);
            rest.addView(w);
        }
        if (path != null) {
            TextView p = label(Style.TEXT_DIM, 12);
            p.setText(path);
            p.setPadding(0, dp(3), 0, 0);
            rest.addView(p);
        }
        if (open != null) {
            String word = action != null ? action
                : c.getString(where == ON_PHONE ? R.string.xd_permit_open_phone : R.string.xd_permit_open);
            TextView b = button(word, open);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(36));
            bp.topMargin = dp(10);
            rest.addView(b, bp);
        }
        text.addView(rest);
        view.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        if (granted != null) live.add(new WeakReference<>(this));
    }

    /** A second, quieter way under the button (the details page when the system's switch is grey). */
    PermitCard link(String word, Runnable onTap) {
        TextView l = label(Style.WARN, 13);
        l.setText(word);
        l.setPadding(0, dp(8), 0, dp(4));
        l.setOnClickListener(v -> {
            Style.tick(v);
            onTap.run();
        });
        rest.addView(l);
        return this;
    }

    /** Added to {@code parent} with the usual gap above. */
    PermitCard addTo(LinearLayout parent) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(4);
        parent.addView(view, lp);
        return this;
    }

    /** The amber button, also for the full-screen setup page: dark text, tall enough for a finger. */
    TextView button(String word, Runnable onTap) {
        TextView b = new TextView(c);
        b.setText(word);
        b.setTextColor(INK);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14 * density);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setMinWidth(dp(96));
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Style.WARN);
        fill.setCornerRadius(dp(18));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(18));
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33000000), fill, mask));
        b.setClickable(true);
        b.setFocusable(false);
        b.setOnClickListener(v -> {
            Style.tick(v);
            onTap.run();
        });
        return b;
    }

    /** XDesk is back in front (or the panel opened): cards whose permission is now there say so and go. */
    static void recheck() {
        for (Iterator<WeakReference<PermitCard>> i = live.iterator(); i.hasNext(); ) {
            PermitCard p = i.next().get();
            if (p == null || p.done) {
                i.remove();
                continue;
            }
            boolean now;
            try {
                now = p.granted.getAsBoolean();
            } catch (RuntimeException e) {
                now = false;
            }
            if (now) {
                i.remove();
                p.markDone();
            }
        }
    }

    private void markDone() {
        done = true;
        bar.setColor(Style.OK);
        ((GradientDrawable) ((LayerDrawable) view.getBackground()).getDrawable(0)).setColor(0x1F3FCF7F);
        icon.setImageDrawable(new CheckIcon(Style.OK));
        title.setText(R.string.xd_permit_done);
        rest.setVisibility(View.GONE);
        ui.postDelayed(() -> view.setVisibility(View.GONE), DONE_MS);
    }

    private TextView label(int color, float sizeDp) {
        TextView t = new TextView(c);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeDp * density);
        t.setLineSpacing(2 * density, 1f);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** A key: a ring and a bit with two teeth, in one colour. */
    static final class KeyIcon extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        KeyIcon(int color) {
            p.setColor(color);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float x = b.left + (b.width() - s) / 2f, y = b.top + (b.height() - s) / 2f;
            float stroke = s * 0.12f;
            p.setStrokeWidth(stroke);
            p.setStyle(Paint.Style.STROKE);
            float r = s * 0.2f, cx = x + s * 0.3f, cy = y + s * 0.5f;
            canvas.drawCircle(cx, cy, r, p);
            float from = cx + r, to = x + s * 0.92f;
            canvas.drawLine(from, cy, to, cy, p);
            canvas.drawLine(to, cy, to, cy + s * 0.2f, p);
            canvas.drawLine(x + s * 0.72f, cy, x + s * 0.72f, cy + s * 0.15f, p);
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** A tick, for a card whose permission came. */
    static final class CheckIcon extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        CheckIcon(int color) {
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float x = b.left + (b.width() - s) / 2f, y = b.top + (b.height() - s) / 2f;
            p.setStrokeWidth(s * 0.13f);
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x + s * 0.18f, y + s * 0.52f);
            path.lineTo(x + s * 0.42f, y + s * 0.76f);
            path.lineTo(x + s * 0.84f, y + s * 0.28f);
            canvas.drawPath(path, p);
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
