package com.termux.x11;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import java.util.Collections;

/**
 * 触控模式的触控区。用户看的是大屏幕，所以位置来自光标，不是手指在手机上的位置：
 * 一根手指滑动只移动光标；轻点是光标处的一次点按；轻点后 DOUBLE_MS 内再按住滑动是拖动；按住不动 HOLD_MS 是长按，
 * 接着滑动继续拖动。两根以上手指是真正的多指，围绕光标摆放，捏合、旋转、双指滚动和三指手势都原样过去。
 *
 * 以后如果手机上有外接屏的实时预览，绝对位置才有意义，那时看 TouchMapping。
 */
final class DirectTouchView extends View {
    interface Sink {
        void moveCursor(int dx, int dy);
        void tap();
        void press(boolean down);
        /** 多指的一帧。@param action 带手指序号的 MotionEvent 动作；offset 是相对手势开始时光标的外接屏像素 */
        void frame(int action, int[] ids, float[] offsetX, float[] offsetY);
    }

    private static final int MAX_FINGERS = 5;
    private static final long DOUBLE_MS = 300, HOLD_MS = 500;
    /** 和触摸板一样的手感。 */
    private static final float SLOP_DP = TouchpadView.TAP_SLOP_DP;

    private enum State { IDLE, ONE, CURSOR, DRAG, MULTI }

    private final Sink sink;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final int[] phoneId = new int[MAX_FINGERS];
    private final float[] fingerX = new float[MAX_FINGERS], fingerY = new float[MAX_FINGERS];
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private State state = State.IDLE;
    private int count;
    private float scale = 1f, speed = 1f, displayW, displayH;
    private float lastX, lastY, restX, restY, travel, centroidX, centroidY;
    private long lastTapUp;

    private final Runnable hold = new Runnable() {
        @Override public void run() {
            if (state != State.ONE) return;
            state = State.DRAG;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            sink.press(true);
        }
    };

    DirectTouchView(Context c, Sink sink) {
        super(c);
        this.sink = sink;
        density = c.getResources().getDisplayMetrics().density;
        for (int i = 0; i < MAX_FINGERS; i++) phoneId[i] = -1;
        setBackground(Style.rounded(c, Style.SURFACE, Style.RADIUS));
        hintPaint.setColor(Style.TEXT_DIM);
        hintPaint.setTextSize(14 * density);
        hintPaint.setTextAlign(Paint.Align.CENTER);
    }

    void setSpeed(float s) {
        speed = s;
    }

    void setDisplaySize(float w, float h) {
        displayW = w;
        displayH = h;
        updateScale();
    }

    /** 多指之间的距离放大到外接屏上，捏合才有感觉。同一个比例，形状不变。 */
    private void updateScale() {
        if (getWidth() > 0 && getHeight() > 0 && displayW > 0 && displayH > 0) {
            scale = Math.min(displayW / getWidth(), displayH / getHeight());
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        updateScale();
        // 别让系统把屏幕边上的滑动当成返回手势。底部的手势条系统不让排除
        setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, w, h)));
    }

    @Override protected void onDraw(Canvas c) {
        float x = getWidth() / 2f, y = getHeight() / 2f;
        c.drawText("单指移动光标 · 轻点点按", x, y - 13 * density, hintPaint);
        c.drawText("按住拖动 · 多指直接操作", x, y + 13 * density, hintPaint);
    }

    private int slotOf(int id) {
        for (int i = 0; i < MAX_FINGERS; i++) if (phoneId[i] == id) return i;
        return -1;
    }

    /** 这根手指在这一帧里的序号：按 id 排，动作要带上它。 */
    private int indexOf(int slot) {
        int index = 0;
        for (int i = 0; i < slot; i++) if (phoneId[i] >= 0) index++;
        return index;
    }

    private void updateFingers(MotionEvent e) {
        for (int p = 0; p < e.getPointerCount(); p++) {
            int slot = slotOf(e.getPointerId(p));
            if (slot < 0) continue;
            fingerX[slot] = e.getX(p);
            fingerY[slot] = e.getY(p);
        }
    }

    /**
     * 每根手指 = 光标 + (手指 − 多指开始时的中心) × 比例。中心固定在开始的那一刻，所以手指整体移动时一组一起平移，
     * 增减手指时其他手指不会跳。
     */
    private void send(int action) {
        int[] ids = new int[count];
        float[] ox = new float[count], oy = new float[count];
        int n = 0;
        for (int i = 0; i < MAX_FINGERS; i++) {
            if (phoneId[i] < 0) continue;
            ids[n] = i;
            ox[n] = (fingerX[i] - centroidX) * scale;
            oy[n] = (fingerY[i] - centroidY) * scale;
            n++;
        }
        sink.frame(action, ids, ox, oy);
    }

    private void beginMulti() {
        ui.removeCallbacks(hold);
        float sx = 0, sy = 0;
        for (int i = 0; i < MAX_FINGERS; i++) {
            if (phoneId[i] < 0) continue;
            sx += fingerX[i];
            sy += fingerY[i];
        }
        centroidX = sx / count;
        centroidY = sy / count;
        state = State.MULTI;
        // 注入那边看到还没开始，会把所有手指依次放下，也会取消正在进行的按住
        send(MotionEvent.ACTION_POINTER_DOWN);
    }

    private void moveOne(MotionEvent e) {
        float dx = (e.getX() - lastX) / density, dy = (e.getY() - lastY) / density;
        lastX = e.getX();
        lastY = e.getY();
        if (state == State.ONE) {
            travel += Math.abs(dx) + Math.abs(dy);
            if (travel < SLOP_DP) return;
            ui.removeCallbacks(hold);
            state = State.CURSOR;
        }
        float pace = (float) Math.hypot(dx, dy);
        float gain = speed * Math.min(TouchpadView.MAX_GAIN, TouchpadView.BASE_GAIN + TouchpadView.ACCEL_PER_DP * pace);
        restX += dx * gain;
        restY += dy * gain;
        int ix = (int) restX, iy = (int) restY;
        if (ix != 0 || iy != 0) {
            restX -= ix;
            restY -= iy;
            sink.moveCursor(ix, iy);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int masked = e.getActionMasked();
        int id = e.getPointerId(e.getActionIndex());
        switch (masked) {
            case MotionEvent.ACTION_DOWN: {
                phoneId[0] = id;
                count = 1;
                updateFingers(e);
                lastX = e.getX();
                lastY = e.getY();
                travel = restX = restY = 0;
                if (e.getEventTime() - lastTapUp < DOUBLE_MS) {
                    state = State.DRAG;
                    sink.press(true);
                } else {
                    state = State.ONE;
                    ui.postDelayed(hold, HOLD_MS);
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                int slot = slotOf(-1);
                if (slot < 0) break;  // 已经有最多的手指了，这根不算
                phoneId[slot] = id;
                count++;
                updateFingers(e);
                if (state == State.MULTI) send(withIndex(MotionEvent.ACTION_POINTER_DOWN, indexOf(slot)));
                else beginMulti();
                break;
            }
            case MotionEvent.ACTION_MOVE:
                updateFingers(e);
                if (state == State.MULTI) send(MotionEvent.ACTION_MOVE);
                else if (state != State.IDLE) moveOne(e);
                break;
            case MotionEvent.ACTION_POINTER_UP: {
                int slot = slotOf(id);
                if (slot < 0) break;
                updateFingers(e);
                if (state == State.MULTI) {
                    if (count > 1) {
                        send(withIndex(MotionEvent.ACTION_POINTER_UP, indexOf(slot)));
                    } else {
                        // 还按着的只剩多出来、没算进去的手指
                        send(MotionEvent.ACTION_UP);
                        state = State.IDLE;
                    }
                }
                phoneId[slot] = -1;
                count--;
                break;
            }
            case MotionEvent.ACTION_UP:
                ui.removeCallbacks(hold);
                updateFingers(e);
                if (state == State.MULTI) {
                    if (count > 0) send(MotionEvent.ACTION_UP);
                } else if (state == State.DRAG) {
                    sink.press(false);
                } else if (state == State.ONE) {
                    sink.tap();
                    lastTapUp = e.getEventTime();
                }
                clear();
                break;
            case MotionEvent.ACTION_CANCEL:
                cancel();
                break;
            default:
                break;
        }
        return true;
    }

    private static int withIndex(int action, int index) {
        return action | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    }

    private void clear() {
        for (int i = 0; i < MAX_FINGERS; i++) phoneId[i] = -1;
        count = 0;
        state = State.IDLE;
    }

    /** 手势做不完了：转屏、换模式、窗口没了。 */
    void cancel() {
        ui.removeCallbacks(hold);
        if (state == State.MULTI) send(MotionEvent.ACTION_CANCEL);
        else if (state == State.DRAG) sink.press(false);
        clear();
    }

    @Override protected void onDetachedFromWindow() {
        cancel();
        super.onDetachedFromWindow();
    }
}
