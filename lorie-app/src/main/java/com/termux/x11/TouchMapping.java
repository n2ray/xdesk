package com.termux.x11;

import android.graphics.RectF;

/**
 * Absolute mapping from a view to the external display. Not in use: 触控 mode takes positions from the cursor, because the
 * user watches the big screen. Kept for a possible live preview of the display on the phone, where absolute makes sense.
 */
final class TouchMapping {
    private TouchMapping() { }

    /** The largest centred box in a view of this size with the display's aspect ratio. */
    static RectF fit(float viewW, float viewH, float displayW, float displayH) {
        float scale = Math.min(viewW / displayW, viewH / displayH);
        float w = displayW * scale, h = displayH * scale;
        float left = (viewW - w) / 2, top = (viewH - h) / 2;
        return new RectF(left, top, left + w, top + h);
    }

    /** A view position as 0 to 1 across the display. A finger outside the box counts as on its edge. */
    static float toDisplay(float position, float boxStart, float boxLength) {
        return Math.max(0f, Math.min(1f, (position - boxStart) / boxLength));
    }
}
