package com.termux.x11;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** 触摸板：单指移动，轻点左键，双指轻点右键，三指轻点中键，双指滑动滚动；长按或轻点后再按住可拖动。 */
final class TouchpadView extends View {
    interface Output {
        void move(int dx, int dy);
        void button(int button, boolean down);
        void click(int button);
        void scroll(int dy, int dx);
    }

    private static final long TAP_MS = 220;
    private static final long LONG_PRESS_MS = 350;
    private static final long TAP_DRAG_WINDOW_MS = 250;
    private static final float TAP_SLOP_DP = 8;
    private static final float SCROLL_STEP_DP = 28;
    private static final float BASE_GAIN = 1.3f;
    private static final float ACCEL_PER_DP = 0.09f;
    private static final float MAX_GAIN = 5f;

    private final Output output;
    private final float density;
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lastX, lastY, restX, restY, travel, scrollX, scrollY;
    private long downTime;
    private int maxPointers;
    private boolean dragging;
    private boolean used;
    private float speed = 1f;
    private long lastTapUp;
    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (travel < TAP_SLOP_DP && maxPointers == 1) startDrag();
        }
    };

    TouchpadView(Context c, Output output) {
        super(c);
        this.output = output;
        density = c.getResources().getDisplayMetrics().density;
        setBackground(Style.rounded(c, Style.SURFACE, 16));
        hint.setColor(Style.TEXT_DIM);
        hint.setTextSize(15 * density);
        hint.setTextAlign(Paint.Align.CENTER);
    }

    @Override protected void onDraw(Canvas c) {
        if (used) return;
        float x = getWidth() / 2f, y = getHeight() / 2f;
        c.drawText("单指移动 · 轻点左键", x, y - 26 * density, hint);
        c.drawText("长按或轻点后按住：拖动", x, y, hint);
        c.drawText("双指轻点右键 · 双指滑动滚动", x, y + 26 * density, hint);
    }

    /** 1 = 默认灵敏度。 */
    void setSpeed(float s) {
        speed = s;
    }

    private void startDrag() {
        dragging = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        output.button(1, true);
    }

    private void stopDrag() {
        if (dragging) output.button(1, false);
        dragging = false;
    }

    private static float avgX(MotionEvent e) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) s += e.getX(i);
        return s / e.getPointerCount();
    }

    private static float avgY(MotionEvent e) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i);
        return s / e.getPointerCount();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            if (!used) {
                used = true;
                invalidate();
            }
            downTime = e.getEventTime();
            maxPointers = 1;
            travel = scrollX = scrollY = restX = restY = 0;
            lastX = e.getX();
            lastY = e.getY();
            if (e.getEventTime() - lastTapUp < TAP_DRAG_WINDOW_MS) startDrag();
            else postDelayed(longPress, LONG_PRESS_MS);
            break;
        case MotionEvent.ACTION_POINTER_DOWN:
            removeCallbacks(longPress);
            stopDrag();
            maxPointers = Math.max(maxPointers, e.getPointerCount());
            lastX = avgX(e);
            lastY = avgY(e);
            break;
        case MotionEvent.ACTION_POINTER_UP:
            // 手指数变了，平均点会跳；下一次 MOVE 重新取基准
            lastX = Float.NaN;
            break;
        case MotionEvent.ACTION_MOVE:
            onMove(e);
            break;
        case MotionEvent.ACTION_UP:
            removeCallbacks(longPress);
            if (dragging) {
                stopDrag();
            } else if (e.getEventTime() - downTime < TAP_MS && travel < TAP_SLOP_DP) {
                output.click(maxPointers == 1 ? 1 : maxPointers == 2 ? 3 : 2);
                lastTapUp = maxPointers == 1 ? e.getEventTime() : 0;
            }
            break;
        case MotionEvent.ACTION_CANCEL:
            removeCallbacks(longPress);
            stopDrag();
            break;
        default:
            break;
        }
        return true;
    }

    private void onMove(MotionEvent e) {
        float x = avgX(e), y = avgY(e);
        if (Float.isNaN(lastX)) {
            lastX = x;
            lastY = y;
            return;
        }
        float dx = (x - lastX) / density, dy = (y - lastY) / density;
        lastX = x;
        lastY = y;
        travel += Math.abs(dx) + Math.abs(dy);
        if (travel >= TAP_SLOP_DP) removeCallbacks(longPress);
        if (maxPointers == 1) {
            moveCursor(dx, dy);
        } else if (e.getPointerCount() == 2) {
            scrollBy(dx, dy);
        }
    }

    private void moveCursor(float dx, float dy) {
        float pace = (float) Math.hypot(dx, dy);
        float gain = this.speed * Math.min(MAX_GAIN, BASE_GAIN + ACCEL_PER_DP * pace);
        restX += dx * gain;
        restY += dy * gain;
        int ix = (int) restX, iy = (int) restY;
        if (ix != 0 || iy != 0) {
            restX -= ix;
            restY -= iy;
            output.move(ix, iy);
        }
    }

    private void scrollBy(float dx, float dy) {
        // 自然滚动：手指向上推，内容向上走，也就是向下滚
        scrollY -= dy;
        scrollX -= dx;
        int ty = (int) (scrollY / SCROLL_STEP_DP), tx = (int) (scrollX / SCROLL_STEP_DP);
        if (ty != 0 || tx != 0) {
            scrollY -= ty * SCROLL_STEP_DP;
            scrollX -= tx * SCROLL_STEP_DP;
            output.scroll(ty, tx);
        }
    }
}
