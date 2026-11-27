// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.content.Context;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** Magnify the phone's view of the desktop without resizing its virtual display. */
final class PhoneDesktopViewport extends FrameLayout {
    interface Touch { void send(int action, float x, float y); }
    final SurfaceView screen;
    private final Touch touch;
    private final int slop;
    private float zoom=1, left, top, centerX, centerY, span, downX, downY;
    private boolean navigating, pressed, pending;

    PhoneDesktopViewport(Context context, Touch touch) {
        super(context);
        this.touch=touch;
        slop=ViewConfiguration.get(context).getScaledTouchSlop();
        screen=new SurfaceView(context);
        screen.setPivotX(0);
        screen.setPivotY(0);
        addView(screen,new LayoutParams(-1,-1));
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) { return true; }

    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH) {
        super.onSizeChanged(w,h,oldW,oldH);
        reset();
    }

    void reset() {
        cancelPress();
        navigating=false;
        transform(1,0,0);
    }

    private void transform(float scale,float x,float y) {
        zoom=scale;
        left=Math.max(getWidth()*(1-scale),Math.min(0,x));
        top=Math.max(getHeight()*(1-scale),Math.min(0,y));
        screen.setScaleX(scale);
        screen.setScaleY(scale);
        screen.setTranslationX(left);
        screen.setTranslationY(top);
    }

    private void send(int action,float x,float y) {
        touch.send(action,(x-left)/zoom,(y-top)/zoom);
    }

    private final Runnable beginPress=this::beginPress;
    private void beginPress() {
        removeCallbacks(beginPress);
        if (!pending) return;
        send(MotionEvent.ACTION_DOWN,downX,downY);
        pressed=true;
        pending=false;
    }

    @Override protected void onDetachedFromWindow() {
        reset();
        super.onDetachedFromWindow();
    }

    private void cancelPress() {
        removeCallbacks(beginPress);
        if (pressed) send(MotionEvent.ACTION_CANCEL,downX,downY);
        pending=pressed=false;
    }

    private void trackPair(MotionEvent e, boolean update) {
        float x=(e.getX(0)+e.getX(1))/2, y=(e.getY(0)+e.getY(1))/2;
        float distance=(float)Math.hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1));
        if (update && span>1 && distance>1) {
            float next=Math.max(1,Math.min(2.5f,zoom*distance/span));
            transform(next,x-(centerX-left)*next/zoom,y-(centerY-top)*next/zoom);
        }
        centerX=x;
        centerY=y;
        span=distance;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action=e.getActionMasked();
        float x=e.getX(), y=e.getY();
        if (action==MotionEvent.ACTION_DOWN) {
            cancelPress();
            downX=x;
            downY=y;
            pending=true;
            postDelayed(beginPress,ViewConfiguration.getTapTimeout());
            navigating=false;
        } else if (action==MotionEvent.ACTION_POINTER_DOWN) {
            cancelPress();
            navigating=true;
            if (e.getPointerCount()==2) trackPair(e,false);
            else span=0;
        } else if (navigating) {
            if (action==MotionEvent.ACTION_MOVE && e.getPointerCount()==2) trackPair(e,true);
            if (action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) navigating=false;
        } else if (action==MotionEvent.ACTION_CANCEL) {
            cancelPress();
        } else if (action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_UP) {
            if (pending && (action==MotionEvent.ACTION_UP || Math.hypot(x-downX,y-downY)>slop)) {
                beginPress();
            }
            if (pressed) send(action,x,y);
            if (action==MotionEvent.ACTION_UP) pressed=false;
        }
        return true;
    }
}
