package com.termux.x11;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import com.termux.x11.app.R;
import io.github.n2ray.xdesk.input.CursorTouch;
import io.github.n2ray.xdesk.input.GestureOutput;
import io.github.n2ray.xdesk.input.Input;
import io.github.n2ray.xdesk.input.Touches;
import io.github.n2ray.xdesk.input.Tuning;
import java.util.Collections;

/**
 * 触控模式（像平板）的触控区。用户看的是大屏幕，所以位置来自光标，不是手指在手机上的位置。手势在 input-core 的
 * CursorTouch 里：一根手指移动光标；轻点是光标处的一次点按；按住不动是长按，接着滑动是拖动；两根以上手指是真正的多指，
 * 围绕光标摆放；三指、四指一起落下是 XDesk 的手势（INPUT.md §10 第 5 条）。
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
        /** 三指、四指手势的动作，见 io.github.n2ray.xdesk.input.Input。 */
        void action(int action, int arg);
    }

    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String hintMove, hintFingers;
    private final float density;
    private final Tuning tuning;
    private final CursorTouch touch;
    private final Touches touches = new Touches();
    /** The injector takes arrays of the frame's own length: one set per finger count, made once. */
    private final int[][] frameIds = new int[6][];
    private final float[][] frameX = new float[6][], frameY = new float[6][];

    DirectTouchView(Context c, final Sink sink) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        tuning = MotionTouches.tuning(this);
        for (int n = 0; n < 6; n++) {
            frameIds[n] = new int[n];
            frameX[n] = new float[n];
            frameY[n] = new float[n];
        }
        touch = new CursorTouch(tuning, MotionTouches.scheduler(this), new GestureOutput() {
            @Override public void move(int dx, int dy) { sink.moveCursor(dx, dy); }
            @Override public void click(int b) { if (b == Input.LEFT) sink.tap(); }
            @Override public void button(int b, boolean down) { if (b == Input.LEFT) sink.press(down); }
            @Override public void action(int a, int arg) { sink.action(a, arg); }
            @Override public void feedback(int kind) { TouchpadView.haptic(DirectTouchView.this, kind); }
            @Override public void frame(int action, int[] ids, float[] ox, float[] oy, int count) {
                int n = Math.min(count, 5);
                int[] i = frameIds[n];
                float[] x = frameX[n], y = frameY[n];
                System.arraycopy(ids, 0, i, 0, n);
                System.arraycopy(ox, 0, x, 0, n);
                System.arraycopy(oy, 0, y, 0, n);
                sink.frame(action, i, x, y);
            }
        });
        setBackground(new PadBackground(c, Style.RADIUS));
        hintPaint.setColor(Style.TEXT_DIM);
        hintPaint.setTextSize(14 * density);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintMove = c.getString(R.string.xd_touch_hint_move);
        hintFingers = c.getString(R.string.xd_touch_hint_fingers);
    }

    void setSpeed(float s) {
        tuning.pointerSpeed = s;
    }

    /** 多指之间的距离放大到外接屏上，捏合才有感觉。同一个比例，形状不变。 */
    void setDisplaySize(float w, float h) {
        touch.setDisplaySize(w, h);
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        touch.setViewSize(w, h);
        // 别让系统把屏幕边上的滑动当成返回手势。底部的手势条系统不让排除
        setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, w, h)));
    }

    @Override protected void onDraw(Canvas c) {
        float x = getWidth() / 2f, y = getHeight() / 2f;
        Style.fitText(hintPaint, 14 * density, getWidth() * 0.92f, hintMove, hintFingers);
        c.drawText(hintMove, x, y - 13 * density, hintPaint);
        c.drawText(hintFingers, x, y + 13 * density, hintPaint);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        touch.onTouch(MotionTouches.fill(touches, e));
        return true;
    }

    /** 手势做不完了：转屏、换模式、窗口没了。 */
    void cancel() {
        touch.cancel();
    }

    @Override protected void onDetachedFromWindow() {
        cancel();
        super.onDetachedFromWindow();
    }
}
