package com.termux.x11;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.Collections;

/**
 * 触控模式的触控区：每根手指都是外接屏上的一根手指，位置一一对应，最多 MAX_FINGERS 根。没有触摸板的轻点和相对移动。
 * 每根手指从按下到抬起用同一个 id，每一帧都带上所有手指。标出来的框就是整个外接屏，见 TouchMapping。
 */
final class DirectTouchView extends View {
    interface Sink {
        /** @param action 带手指序号的 MotionEvent 动作；x、y 是 0 到 1；抬起的手指在这一帧还在 */
        void frame(int action, int[] ids, float[] x, float[] y);
    }

    private static final int MAX_FINGERS = 5;

    private final Sink sink;
    private final int[] phoneId = new int[MAX_FINGERS];
    private final float[] x = new float[MAX_FINGERS], y = new float[MAX_FINGERS];
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG), hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius, density;
    private RectF box = new RectF();
    private float displayW = 16, displayH = 9;
    private int count;

    DirectTouchView(Context c, Sink sink) {
        super(c);
        this.sink = sink;
        density = c.getResources().getDisplayMetrics().density;
        radius = Style.RADIUS * density;
        for (int i = 0; i < MAX_FINGERS; i++) phoneId[i] = -1;
        setBackgroundColor(Style.BACKGROUND);
        boxPaint.setColor(Style.SURFACE);
        hintPaint.setColor(Style.TEXT_DIM);
        hintPaint.setTextSize(14 * density);
        hintPaint.setTextAlign(Paint.Align.CENTER);
    }

    void setDisplaySize(float w, float h) {
        if (w <= 0 || h <= 0 || (w == displayW && h == displayH)) return;
        displayW = w;
        displayH = h;
        layoutBox();
    }

    private void layoutBox() {
        if (getWidth() == 0 || getHeight() == 0) return;
        box = TouchMapping.fit(getWidth(), getHeight(), displayW, displayH);
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        layoutBox();
        // 别让系统把屏幕边上的滑动当成返回手势。底部的手势条系统不让排除
        setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, w, h)));
    }

    @Override protected void onDraw(Canvas c) {
        c.drawRoundRect(box, radius, radius, boxPaint);
        c.drawText("这里就是整个外接屏", box.centerX(), box.centerY(), hintPaint);
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

    private void send(int action) {
        int[] ids = new int[count];
        float[] fx = new float[count], fy = new float[count];
        int n = 0;
        for (int i = 0; i < MAX_FINGERS; i++) {
            if (phoneId[i] < 0) continue;
            ids[n] = i;
            fx[n] = x[i];
            fy[n] = y[i];
            n++;
        }
        sink.frame(action, ids, fx, fy);
    }

    private static int withIndex(int action, int index) {
        return action | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    }

    private void updatePositions(MotionEvent e) {
        for (int p = 0; p < e.getPointerCount(); p++) {
            int slot = slotOf(e.getPointerId(p));
            if (slot < 0) continue;
            x[slot] = TouchMapping.toDisplay(e.getX(p), box.left, box.width());
            y[slot] = TouchMapping.toDisplay(e.getY(p), box.top, box.height());
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int masked = e.getActionMasked();
        int id = e.getPointerId(e.getActionIndex());
        if (masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_POINTER_DOWN) {
            int slot = slotOf(-1);
            if (slot < 0) return true;  // 已经有最多的手指了，这根不算
            phoneId[slot] = id;
            count++;
            updatePositions(e);
            send(count == 1 ? MotionEvent.ACTION_DOWN : withIndex(MotionEvent.ACTION_POINTER_DOWN, indexOf(slot)));
        } else if (masked == MotionEvent.ACTION_MOVE) {
            if (count == 0) return true;
            updatePositions(e);
            send(MotionEvent.ACTION_MOVE);
        } else if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_POINTER_UP) {
            int slot = slotOf(id);
            if (slot < 0) return true;
            updatePositions(e);
            send(count == 1 ? MotionEvent.ACTION_UP : withIndex(MotionEvent.ACTION_POINTER_UP, indexOf(slot)));
            phoneId[slot] = -1;
            count--;
        } else if (masked == MotionEvent.ACTION_CANCEL) {
            cancel();
        }
        return true;
    }

    /** 手势做不完了：转屏、换模式、窗口没了。 */
    void cancel() {
        if (count == 0) return;
        send(MotionEvent.ACTION_CANCEL);
        for (int i = 0; i < MAX_FINGERS; i++) phoneId[i] = -1;
        count = 0;
    }

    @Override protected void onDetachedFromWindow() {
        cancel();
        super.onDetachedFromWindow();
    }
}
