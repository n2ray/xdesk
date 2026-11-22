// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Region;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * "Connecting to <Mac>…" on the screen a content is opening on, until its first picture (owner, 22 November: after
 * the password the shared desktop was black for about ten seconds). The external panels' shell look, sized from
 * Sizing.shell, with a spinner and the step it is at. Over a SurfaceView it gives its own square back to the
 * composition (gatherTransparentRegion), or the window is taken for empty and not shown.
 */
public final class ConnectingCard extends LinearLayout {
    private final TextView step;

    public ConnectingCard(Context c, Display d, String title) {
        super(c);
        Sizing.Shell s = Sizing.shell(c, d);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackground(Style.shell(s.density, 18));
        setPadding(s.dp(22), s.dp(18), s.dp(26), s.dp(18));
        ProgressBar spin = new ProgressBar(c);
        spin.setIndeterminate(true);
        spin.setIndeterminateTintList(ColorStateList.valueOf(Style.ACCENT_2));
        addView(spin, new LayoutParams(s.dp(28), s.dp(28)));
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(VERTICAL);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(Style.TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, s.text(17));
        text.addView(t);
        step = new TextView(c);
        step.setTextColor(Style.TEXT_DIM);
        step.setTextSize(TypedValue.COMPLEX_UNIT_PX, s.text(13));
        step.setPadding(0, s.dp(3), 0, 0);
        text.addView(step);
        LayoutParams tp = new LayoutParams(-2, -2);
        tp.leftMargin = s.dp(16);
        addView(text, tp);
    }

    /** The step it is at, one short line; null hides the line. */
    public void step(String line) {
        step.setText(line);
        step.setVisibility(line != null ? VISIBLE : GONE);
    }

    @Override public boolean gatherTransparentRegion(Region region) {
        if (region != null && getVisibility() == VISIBLE) {
            int[] at = new int[2];
            getLocationInWindow(at);
            region.op(at[0], at[1], at[0] + getWidth(), at[1] + getHeight(), Region.Op.DIFFERENCE);
        }
        return true;
    }
}
