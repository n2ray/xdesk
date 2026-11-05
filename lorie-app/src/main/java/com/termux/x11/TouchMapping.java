package com.termux.x11;

import android.graphics.RectF;

/**
 * How the phone's touch area stands for the external display in 触控 mode. Absolute: the area, letterboxed to the
 * display's shape, is the whole display. To try another mapping, this is the only piece to change.
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
