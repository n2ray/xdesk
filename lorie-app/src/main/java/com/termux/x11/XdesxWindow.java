package com.termux.x11;

import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.hardware.SyncFence;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.view.MotionEvent;
import java.io.*;
import java.time.Duration;

/** A window owns one virtual display. Both ends sleep while there is no damage or input. */
final class XdesxWindow {
    private final android.content.Context context;
    private final ICmdEntryInterface server;
    private final IInjector injector;
    private final String key, component;
    private final Runnable finished;
    private final HandlerThread frames = new HandlerThread("xdesx-frames");
    private volatile ParcelFileDescriptor control;
    private volatile boolean stopped;
    private ImageReader reader;
    private volatile int display = -1;
    private volatile boolean nativeFullscreen;
    private int task=-1;
    boolean mac() { return component.endsWith("/"+XdesxMacActivity.class.getName()); }
    String label() { return mac()?"Mac":"Chrome"; }
    boolean chrome() { return component.startsWith("com.android.chrome/"); }
    boolean fullscreen() { return nativeFullscreen; }
    synchronized boolean toggle(int external,android.graphics.Bitmap cursor,int hotspot,int width,int height) throws RemoteException {
        if (stopped || display<0) return false;
        boolean enter=!nativeFullscreen;
        int moved=injector.moveXdesxTask(display,external,component.split("/",2)[0],task,enter,cursor,hotspot,width,height);
        if (moved<0) return false;
        task=moved;
        nativeFullscreen=enter;
        Log.i("XDesX","task "+task+" "+display+" <-> "+external+" fullscreen="+enter);
        return true;
    }
    XdesxWindow(android.content.Context context,ICmdEntryInterface s,IInjector i,String k,String c,Runnable f) {
        this.context=context; server=s; injector=i; key=k; component=c; finished=f;
    }
    void start() {
        frames.start();
        new Thread(this::run,"xdesx-window").start();
    }
    void stop() {
        stopped=true;
        ParcelFileDescriptor fd=control;
        if (fd!=null) try { Os.shutdown(fd.getFileDescriptor(),OsConstants.SHUT_RDWR); } catch(Exception ignored) {}
    }
    private void run() {
        try (ParcelFileDescriptor fd=server.xdesxSocket(key);
             DataInputStream in=new DataInputStream(new ParcelFileDescriptor.AutoCloseInputStream(fd))) {
            control=fd;
            while (!stopped) {
                int op=in.readInt(), x=in.readInt(), y=in.readInt();
                if (op==1) resize(x,y);
                if (display<0 || nativeFullscreen) continue;
                synchronized (injector) {
                    if (op==4 || op==5) {
                        injector.setDisplay(display,reader.getWidth(),reader.getHeight());
                        injector.key(x,op==4);
                    } else if (op==6 || (op>=17 && op<=23) || (op>=33 && op<=39)) {
                        injector.xdesxPointer(display,reader.getWidth(),reader.getHeight(),op,x,y);
                    }
                }
            }
        } catch (EOFException ignored) {
        } catch (Exception e) { Log.e("XDesX","window "+component,e); }
        finally {
            stopped=true; control=null;
            if (mac()) XdesxMacActivity.closeWindow(key);
            if (display>=0) try { injector.releaseXdesxDisplay(display); } catch(Exception ignored) {}
            frames.quitSafely();
            try { frames.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (reader!=null) reader.close();
            finished.run();
        }
    }
    private void resize(int w,int h) throws Exception {
        if (w<=0 || h<=0 || (long)w*h>16777216) throw new IOException("invalid window dimensions");
        ImageReader next=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3,HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        try {
            if (display<0) {
                display=injector.createVirtualDisplay("XDesX "+component,w,h,160,next.getSurface(),0);
                if (mac()) {
                    android.app.ActivityOptions options=android.app.ActivityOptions.makeBasic();
                    options.setLaunchDisplayId(display);
                    android.content.Intent intent=new android.content.Intent(context,XdesxMacActivity.class);
                    intent.putExtra("window",key).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK |
                        android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    context.startActivity(intent,options.toBundle());
                } else {
                    String failure=injector.startApp(component,display);
                    if (failure!=null) throw new IOException(failure);
                }
            } else {
                injector.resizeVirtualDisplay(display,w,h,160,next.getSurface());
                Log.i("XDesX","committed resize "+w+"x"+h);
            }
        } catch(Exception e) { next.close(); throw e; }
        ImageReader old=reader;
        reader=next;
        Handler handler=new Handler(frames.getLooper());
        if (old!=null) { old.setOnImageAvailableListener(null,null); handler.post(old::close); }
        next.setOnImageAvailableListener(this::frame,handler);
        handler.post(() -> frame(next));
    }
    private boolean logged;
    private void frame(ImageReader src) {
        if (stopped) return;
        try (Image image=src.acquireLatestImage()) {
            if (image==null || nativeFullscreen) return;
            try (HardwareBuffer buffer=image.getHardwareBuffer(); SyncFence fence=image.getFence()) {
                if (fence.isValid() && !fence.await(Duration.ofSeconds(1))) { stop(); return; }
                server.xdesxFrame(key,buffer);
                if (!logged) { logged=true; Log.i("XDesX","imported "+component+" "+buffer.getWidth()+"x"+buffer.getHeight()); }
            }
        } catch(Exception e) { if (!stopped) Log.e("XDesX","frame "+component,e); stop(); }
    }
}
