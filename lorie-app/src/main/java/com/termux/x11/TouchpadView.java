package com.termux.x11;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import com.termux.x11.app.R;
import io.github.n2ray.xdesk.input.GestureOutput;
import io.github.n2ray.xdesk.input.Input;
import io.github.n2ray.xdesk.input.Touches;
import io.github.n2ray.xdesk.input.Touchpad;
import io.github.n2ray.xdesk.input.Tuning;

/** 触摸板（INPUT.md §3.1）。手势都在 input-core 的 Touchpad 里，这里只是界面、震动和把 MotionEvent 交过去。 */
final class TouchpadView extends View {
    static final float TAP_SLOP_DP = Tuning.SLOP_DP;
    static final float BASE_GAIN = Tuning.BASE_GAIN;
    static final float ACCEL_PER_DP = Tuning.ACCEL_PER_DP;
    static final float MAX_GAIN = Tuning.MAX_GAIN;

    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String[] hints;
    private final float density;
    private final Tuning tuning;
    private final Touchpad pad;
    private final Touches touches = new Touches();
    private boolean used;

    TouchpadView(Context c, final GestureOutput output) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        tuning = MotionTouches.tuning(this);
        // Vibrations are the view's, the rest goes on as it is
        pad = new Touchpad(tuning, MotionTouches.scheduler(this), new GestureOutput() {
            @Override public void move(int dx, int dy) { output.move(dx, dy); }
            @Override public void button(int b, boolean down) { output.button(b, down); }
            @Override public void click(int b) { output.click(b); }
            @Override public void scroll(int phase, float dx, float dy) { output.scroll(phase, dx, dy); }
            @Override public void action(int a, int arg) { output.action(a, arg); }
            @Override public void feedback(int kind) { haptic(TouchpadView.this, kind); }
        });
        setBackground(new PadBackground(c, 16));
        hint.setColor(Style.TEXT_DIM);
        hint.setTextSize(15 * density);
        hint.setTextAlign(Paint.Align.CENTER);
        hints = new String[]{c.getString(R.string.xd_pad_hint_move), c.getString(R.string.xd_pad_hint_drag), c.getString(R.string.xd_pad_hint_two)};
    }

    /** INPUT.md §13: a light tick for clicks, the long press as before, a firmer one for three- and four-finger gestures. */
    static void haptic(View v, int kind) {
        int h = kind == Input.FEEDBACK_LONG_PRESS ? HapticFeedbackConstants.LONG_PRESS
            : kind == Input.FEEDBACK_GESTURE ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.CLOCK_TICK;
        v.performHapticFeedback(h);
    }

    @Override protected void onDraw(Canvas c) {
        if (used) return;
        float x = getWidth() / 2f, y = getHeight() / 2f;
        Style.fitText(hint, 15 * density, getWidth() * 0.92f, hints);
        c.drawText(hints[0], x, y - 26 * density, hint);
        c.drawText(hints[1], x, y, hint);
        c.drawText(hints[2], x, y + 26 * density, hint);
    }

    /** 双指滚动的速度：1 是默认。手指走的距离不变，每一格变短。 */
    void setScrollSpeed(float s) {
        tuning.setScrollSpeed(s);
    }

    /** 1 = 默认灵敏度。 */
    void setSpeed(float s) {
        tuning.pointerSpeed = s;
    }

    /** 自然滚动（内容跟着手指走），所有设备共用一个设置。 */
    void setNaturalScroll(boolean on) {
        tuning.naturalScroll = on;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!used && e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            used = true;
            invalidate();
        }
        pad.onTouch(MotionTouches.fill(touches, e));
        return true;
    }

    /** 手势做不完了（INPUT.md §2 cancel）：按着的都松开。 */
    void cancel() {
        pad.cancel();
    }

    @Override protected void onDetachedFromWindow() {
        pad.cancel();
        super.onDetachedFromWindow();
    }
}
