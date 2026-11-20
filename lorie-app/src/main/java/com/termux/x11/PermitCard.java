package com.termux.x11;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.termux.x11.app.R;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The one look of "go and turn this permission on" (owner, 2026-11-18), on the phone and on the external screen alike,
 * as PermInline/PermSheet draw it: an amber-tinted card with an amber title, "Turn on «X»", one line why, one line
 * where in the phone's settings, and the way to open it — the quiet blue link on a page, the blue primary button when
 * asked at a moment. Red stays for errors and lost connections, grey for plain notes. A card whose permission is there
 * when XDesk is back turns green, says so, and goes.
 */
public final class PermitCard {
    /** Where the button's page opens: here (the phone's own screen), on the phone (seen from the external screen). */
    public static final int HERE = 0, ON_PHONE = 1;
    /** The amber card's tint and border, as PermInline/PermSheet draw them (rgba(240,136,62,.10 / .30)). */
    private static final int TINT = 0x1AF0883E, BORDER = 0x4DF0883E;
    private static final long DONE_MS = 2000;
    /** Cards waiting for their permission, held while their view is attached (the card object has no other owner). */
    private static final List<PermitCard> live = new ArrayList<>();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    public final LinearLayout view;
    private final BooleanSupplier granted;
    private final Context c;
    private final float density;
    private final GradientDrawable back;
    private final TextView title;
    private final LinearLayout rest;
    private boolean done;
    private Runnable afterDone;

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
        view.setOrientation(LinearLayout.VERTICAL);
        // PermInline/PermSheet's card: an amber-tinted box with a hairline, an amber title, no bar and no icon
        back = new GradientDrawable();
        back.setColor(TINT);
        back.setCornerRadius(dp(16));
        back.setStroke(dp(1), BORDER);
        view.setBackground(back);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));

        title = label(Style.WARN, 14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setText(c.getString(R.string.xd_permit_title, what));
        view.addView(title);
        rest = new LinearLayout(c);
        rest.setOrientation(LinearLayout.VERTICAL);
        if (why != null) {
            TextView w = label(Style.TEXT_SOFT, 12);
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
            // A card asking at a moment (PermSheet) has the one primary button; the card on a page is PermInline,
            // where the action is a quiet blue link
            if (action != null) {
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(36));
                bp.topMargin = dp(10);
                rest.addView(button(word, open), bp);
            } else {
                link(word + " ›", open);
            }
        }
        view.addView(rest);
        if (granted != null) {
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {
                    if (!done && !live.contains(PermitCard.this)) live.add(PermitCard.this);
                }

                @Override public void onViewDetachedFromWindow(View v) {
                    live.remove(PermitCard.this);
                }
            });
        }
    }

    /** A title of its own instead of "Turn on «X»" (a switch to turn off and on again). */
    PermitCard retitle(String text) {
        title.setText(text);
        return this;
    }

    /** Runs once the green "turned on" has been shown (the page around it is then built again, the flow goes on). */
    public PermitCard onDone(Runnable r) {
        afterDone = r;
        return this;
    }

    public boolean isDone() {
        return done;
    }

    /** A second, quieter way under the button, and the card's own action (PermInline's blue link). */
    PermitCard link(String word, Runnable onTap) {
        TextView l = label(Style.ACCENT_2, 14);
        l.setText(word);
        l.setPadding(0, dp(6), 0, dp(2));
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

    /** The blue primary button (PermDetail/PermOnboard/PermSheet): tall enough for a finger. */
    TextView button(String word, Runnable onTap) {
        TextView b = new TextView(c);
        b.setText(word);
        b.setTextColor(Style.TEXT);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14 * density);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setMinWidth(dp(96));
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Style.ACCENT);
        fill.setCornerRadius(dp(12));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(12));
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), fill, mask));
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
        for (PermitCard p : new ArrayList<>(live)) {
            if (p.done) continue;
            boolean now;
            try {
                now = p.granted.getAsBoolean();
            } catch (RuntimeException e) {
                now = false;
            }
            if (now) p.markDone();
        }
    }

    /** Shows the green "turned on" now, whatever the supplier (the host knows the permission came). */
    public void markDone() {
        if (done) return;
        live.remove(this);
        done = true;
        back.setColor(0x1F3FCF7F);
        back.setStroke(dp(1), 0x4D3FCF7F);
        title.setTextColor(Style.OK);
        title.setText(R.string.xd_permit_done);
        rest.setVisibility(View.GONE);
        ui.postDelayed(() -> {
            view.setVisibility(View.GONE);
            if (afterDone != null) afterDone.run();
        }, DONE_MS);
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

    /** Options that open a system page on the phone's own screen, also when asked from the external screen. */
    static android.os.Bundle onPhone() {
        android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
        o.setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY);
        return o.toBundle();
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
