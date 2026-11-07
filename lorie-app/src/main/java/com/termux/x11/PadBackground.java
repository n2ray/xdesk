package com.termux.x11;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The touch area's surface: a soft shadow under it and a faint blue glow along its edge, so its border is clear on the
 * near black background without a hard line. Drawn inside the view's bounds, so the surface sits MARGIN in from them;
 * touches still reach the whole view.
 */
final class PadBackground extends Drawable {
    private static final float MARGIN = 7, SHADOW = 6, GLOW = 4;
    private static final int TOP = 0xFF1B1F26, EDGE_TOP = 0x803D7EF5, EDGE_BOTTOM = 0x283D7EF5;

    private final float density, radius;
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG),
            fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF(), shadowRect = new RectF();

    PadBackground(Context c, float radiusDp) {
        density = c.getResources().getDisplayMetrics().density;
        radius = radiusDp * density;
        shadow.setColor(0xB0000000);
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW * density, BlurMaskFilter.Blur.NORMAL));
        glow.setColor(0x403D7EF5);
        glow.setMaskFilter(new BlurMaskFilter(GLOW * density, BlurMaskFilter.Blur.OUTER));
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1, density));
    }

    @Override protected void onBoundsChange(Rect b) {
        float m = MARGIN * density;
        rect.set(b.left + m, b.top + m, b.right - m, b.bottom - m);
        shadowRect.set(rect);
        shadowRect.offset(0, 2 * density);
        fill.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, TOP, Style.SURFACE, Shader.TileMode.CLAMP));
        edge.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, EDGE_TOP, EDGE_BOTTOM, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas c) {
        c.drawRoundRect(shadowRect, radius, radius, shadow);
        c.drawRoundRect(rect, radius, radius, glow);
        c.drawRoundRect(rect, radius, radius, fill);
        float h = edge.getStrokeWidth() / 2;
        c.drawRoundRect(rect.left + h, rect.top + h, rect.right - h, rect.bottom - h, radius - h, radius - h, edge);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
