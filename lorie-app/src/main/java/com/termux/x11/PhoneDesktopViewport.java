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
    interface Scroll {
        boolean begin(float x,float y);
        void move(float dx,float dy);
    }
    interface DesktopActions {
        boolean available();
        void contextClick(float x,float y);
        void cycle(int direction);
        void finishCycle(boolean cancel);
    }
    private final DesktopActions desktop;
    private final Scroll scroll;
    private final float cycleStep;
    private long previousTap=-1, firstDown;
    private float tapX,tapY,pairX,pairY,pairSpan;
    private boolean pairTap,three,cycleOpen,blocked;
    private final float[] tripleX=new float[3],tripleY=new float[3];
    private final int[] tripleIds=new int[3];
    private int cycleIndex;

    private boolean scrolling;
    private float lastX,lastY,queuedX,queuedY;
    private boolean framePending;
    private final Runnable scrollFrame=this::flushScroll;
    private void flushScroll() {
        if (framePending) removeCallbacks(scrollFrame);
        framePending=false;
        if (queuedX!=0 || queuedY!=0) scroll.move(queuedX,queuedY);
        queuedX=queuedY=0;
    }
    private void queueScroll(float x,float y) {
        queuedX+=(x-lastX)/zoom;
        queuedY+=(y-lastY)/zoom;
        lastX=x;
        lastY=y;
        if (!framePending) {
            framePending=true;
            postOnAnimation(scrollFrame);
        }
    }
    final SurfaceView screen;
    private final Touch touch;
    private final int slop;
    private float zoom=1, left, top, centerX, centerY, span, downX, downY;
    private boolean navigating, pressed, pending;

    PhoneDesktopViewport(Context context, Touch touch, Scroll scroll, DesktopActions desktop) {
        super(context);
        this.touch=touch;
        this.desktop=desktop;
        cycleStep=56*getResources().getDisplayMetrics().density;
        this.scroll=scroll;
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
        cancelGestures();
        transform(1,0,0);
    }

    void cancelGestures() {
        cancelPress();
        if (cycleOpen) desktop.finishCycle(true);
        previousTap=-1;
        navigating=three=cycleOpen=pairTap=false;
        blocked=true;
    }

    private void moveThree(MotionEvent e) {
        if (e.getPointerCount()!=3 || blocked) return;
        float total=0,min=Float.MAX_VALUE,max=-Float.MAX_VALUE;
        for (int i=0;i<3;i++) {
            int index=e.findPointerIndex(tripleIds[i]);
            if(index<0) return;
            float dx=e.getX(index)-tripleX[i],dy=e.getY(index)-tripleY[i];
            if(Math.abs(dy)>cycleStep && Math.abs(dy)>Math.abs(dx)) return;
            total+=dx;min=Math.min(min,dx);max=Math.max(max,dx);
        }
        if(max-min>cycleStep || !desktop.available()) return;
        int next=(int)(-total/(3*cycleStep));
        while(cycleIndex!=next) {
            int direction=Integer.signum(next-cycleIndex);
            desktop.cycle(direction);
            cycleOpen=true;
            cycleIndex+=direction;
        }
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
        previousTap=-1;
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
        if (framePending) removeCallbacks(scrollFrame);
        framePending=false;
        queuedX=queuedY=0;
        pending=pressed=scrolling=false;
    }

    private void trackPair(MotionEvent e, boolean update) {
        float x=(e.getX(0)+e.getX(1))/2, y=(e.getY(0)+e.getY(1))/2;
        float distance=(float)Math.hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1));
        if (pairTap && (Math.hypot(x-pairX,y-pairY)>slop || Math.abs(distance-pairSpan)>slop)) pairTap=false;
        if (update && !pairTap && span>1 && distance>1) {
            float next=Math.max(1,Math.min(2.5f,zoom*distance/span));
            transform(next,x-(centerX-left)*next/zoom,y-(centerY-top)*next/zoom);
        }
        if (update && pairTap) return;
        centerX=x;
        centerY=y;
        span=distance;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action=e.getActionMasked();
        float x=e.getX(), y=e.getY();
        if (action==MotionEvent.ACTION_DOWN) {
            if (cycleOpen) cancelGestures();
            cancelPress();
            blocked=three=pairTap=false; firstDown=e.getEventTime();
            lastX=downX=x;
            lastY=downY=y;
            pending=true;
            postDelayed(beginPress,ViewConfiguration.getLongPressTimeout());
            navigating=false;
        } else if (action==MotionEvent.ACTION_POINTER_DOWN) {
            if (pressed || scrolling || (navigating && !pairTap) || e.getPointerCount()>3) blocked=true;
            previousTap=-1;
            cancelPress();
            navigating=true;
            if (e.getPointerCount()==2 && !three) {
                trackPair(e,false);
                pairX=centerX; pairY=centerY; pairSpan=span; pairTap=!blocked;
            } else if (e.getPointerCount()==3 && !three) {
                three=true;pairTap=false;cycleIndex=0;
                for(int i=0;i<3;i++) {
                    tripleIds[i]=e.getPointerId(i);tripleX[i]=e.getX(i);tripleY[i]=e.getY(i);
                }
            }
        } else if (navigating) {
            if (!blocked && (action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_POINTER_UP)) {
                if (three) moveThree(e);
                else if (e.getPointerCount()==2) trackPair(e,true);
                else if (pairTap && Math.hypot(x-lastX,y-lastY)>slop) pairTap=false;
            }
            if (action==MotionEvent.ACTION_POINTER_UP && e.getPointerCount()==2) {
                int other=1-e.getActionIndex();lastX=e.getX(other);lastY=e.getY(other);
            }
            if (action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
                if(cycleOpen) desktop.finishCycle(blocked || action==MotionEvent.ACTION_CANCEL || cycleIndex==0);
                if(action==MotionEvent.ACTION_UP && pairTap && !blocked && desktop.available() &&
                        Math.hypot(x-lastX,y-lastY)<=slop && e.getEventTime()-firstDown<=ViewConfiguration.getDoubleTapTimeout())
                    desktop.contextClick((pairX-left)/zoom,(pairY-top)/zoom);
                navigating=three=cycleOpen=pairTap=false;
                if (zoom<1.02f) transform(1,0,0);
            }
        } else if (action==MotionEvent.ACTION_CANCEL) {
            cancelGestures();
        } else if (action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_UP) {
            if (pending && Math.hypot(x-downX,y-downY)>slop) {
                if (scroll.begin((downX-left)/zoom,(downY-top)/zoom)) {
                    removeCallbacks(beginPress);
                    pending=false;
                    scrolling=true;previousTap=-1;
                } else {
                    beginPress();
                }
            }
            if (scrolling) {
                queueScroll(x,y);
                if (action==MotionEvent.ACTION_UP) {
                    flushScroll();
                    scrolling=false;
                }
            }
            if (action==MotionEvent.ACTION_UP && pending && desktop.available()) {
                boolean repeat=previousTap>=0 && firstDown-previousTap<=ViewConfiguration.getDoubleTapTimeout()
                        && Math.hypot(downX-tapX,downY-tapY)<=slop;
                if (!repeat) {tapX=downX;tapY=downY;}
                removeCallbacks(beginPress);
                pending=false;
                send(MotionEvent.ACTION_DOWN,tapX,tapY);
                send(MotionEvent.ACTION_UP,tapX,tapY);
                previousTap=repeat?-1:e.getEventTime();
            } else if (action==MotionEvent.ACTION_UP && pending) beginPress();
            if (pressed) send(action,x,y);
            if (action==MotionEvent.ACTION_UP) pressed=false;
        }
        return true;
    }
}
