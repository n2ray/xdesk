package com.termux.x11;

import android.graphics.Rect;

/** Null means no reliable answer. An empty rect means Android explicitly reports no bars. */
final class PortraitLayout {
    static Rect read(Object task, int width, int height) throws ReflectiveOperationException {
        Object info;
        try {
            info = task.getClass().getField("appCompatTaskInfo").get(task);
        } catch (NoSuchFieldException olderSystem) {
            info = task;
        }
        if (info == null) return null;
        Class<?> type = info.getClass();
        boolean boxed;
        try {
            boxed = (Boolean) type.getMethod("isTopActivityLetterboxed").invoke(info);
        } catch (NoSuchMethodException olderSystem) {
            try {
                boxed = type.getField("topActivityBoundsLetterboxed").getBoolean(info);
            } catch (NoSuchFieldException olderLayout) {
                // Android 14 only populates dimensions when letterbox double-tap is enabled.
                if (!type.getField("isLetterboxDoubleTapEnabled").getBoolean(info)) return null;
                boxed = true;
            }
        }
        if (!boxed) return new Rect();
        try {
            Rect bounds = (Rect) type.getField("topActivityLetterboxBounds").get(info);
            if (bounds != null && !bounds.isEmpty()) return bounds;
        } catch (NoSuchFieldException sizesOnly) {
            // Some releases expose dimensions but no origin.
        }
        int w = type.getField("topActivityLetterboxWidth").getInt(info);
        int h = type.getField("topActivityLetterboxHeight").getInt(info);
        if (w <= 0 || w >= h || width - w < 4 || h <= 0 || Math.abs(height - h) > 1) return null;
        // Only the size is used for fitting. No pixel or input coordinate is cropped.
        int left = (width - w) / 2;
        return new Rect(left, 0, left + w, h);
    }
}
