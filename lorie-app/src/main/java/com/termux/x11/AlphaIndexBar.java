package com.termux.x11;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import java.util.Set;

/**
 * The launcher's A–Z quick scroll bar (DEVICES.md §3): letters A–Z and '#' down the right edge of the list. Touching
 * or dragging along it calls back with the letter under the finger; letters with no entry are dim but still pick
 * (the list goes to the next one that has entries). UI thread.
 */
final class AlphaIndexBar extends View {
    interface Listener {
        void onLetter(char letter);
    }

    static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubble = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Listener listener;
    private Set<Character> present;
    private int touched = -1;

    AlphaIndexBar(Context c) {
        super(c);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(Style.dp(c, 10));
        bubble.setColor(Style.ACCENT);
        setContentDescription("A–Z");
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** The letters that have entries now. */
    void setPresent(Set<Character> letters) {
        present = letters;
        invalidate();
    }

    private float step() {
        return (getHeight() - getPaddingTop() - getPaddingBottom()) / (float) LETTERS.length();
    }

    @Override protected void onDraw(Canvas canvas) {
        float step = step();
        if (step <= 0) return;
        paint.setTextSize(Math.min(Style.dp(getContext(), 11), step * 0.8f));
        float x = getWidth() / 2f;
        Paint.FontMetrics fm = paint.getFontMetrics();
        for (int i = 0; i < LETTERS.length(); i++) {
            char l = LETTERS.charAt(i);
            float cy = getPaddingTop() + step * (i + 0.5f);
            if (i == touched) {
                canvas.drawCircle(x, cy, Math.min(getWidth() / 2f, step * 0.7f), bubble);
                paint.setColor(Style.TEXT);
            } else {
                paint.setColor(present == null || present.contains(l) ? Style.TEXT : Style.TEXT_DIM & 0x66FFFFFF);
            }
            canvas.drawText(String.valueOf(l), x, cy - (fm.ascent + fm.descent) / 2f, paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE: {
                float step = step();
                int i = step > 0 ? (int) ((e.getY() - getPaddingTop()) / step) : -1;
                i = Math.max(0, Math.min(LETTERS.length() - 1, i));
                if (i != touched) {
                    touched = i;
                    invalidate();
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) Style.tick(this);
                    if (listener != null) listener.onLetter(LETTERS.charAt(i));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touched = -1;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }
}
