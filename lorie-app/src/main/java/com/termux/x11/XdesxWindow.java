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
import com.termux.x11.screenshare.ScreenShareActivity;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/** A window owns one virtual display. Both ends sleep while there is no damage or input. */
final class XdesxWindow {
    private static final AtomicReference<XdesxWindow> focused = new AtomicReference<>();
    static XdesxWindow focused() { return focused.get(); }
    private volatile boolean xGrab;
    boolean ownsAndroidEditor() { return !mac() && !nativeFullscreen && !minimized && !stopped && !xGrab; }
    private void editorFocus(boolean active) {
        boolean changed=active ? focused.getAndSet(this)!=this : focused.compareAndSet(this,null);
        boolean grabbed=xGrab;
        xGrab=false;
        if (changed || grabbed) syncHost();
    }
    private void syncHost() { ((XdesxApp)context.getApplicationContext()).syncEditorHost(); }
    int displayId() { return display; }
    private final android.content.Context context;
    private final ICmdEntryInterface server;
    private final IInjector injector;
    private final String key, component, hostId;
    private final Runnable finished, requestFullscreen;
    private final HandlerThread frames = new HandlerThread("xdesx-frames");
    private volatile ParcelFileDescriptor control;
    private volatile boolean stopped;
    private volatile ImageReader reader;
    private volatile int display = -1;
    private volatile boolean nativeFullscreen, minimized;
    private int task=-1, densityTarget=-1;
    private volatile boolean hdmiFirst;
    boolean awaitingHdmi() { return hdmiFirst; }
    private boolean awaitingMap;
    private synchronized void showReturnWindow() {
        if (!awaitingMap || stopped) return;
        try {
            sendAspect(-1,0); // Reveal the previously unmapped X window.
            awaitingMap=false;
        } catch (Exception e) { Log.w("XDesX","show return window",e); stop(); }
    }
    void cancelInitialFullscreen() {
        if (!hdmiFirst) return;
        hdmiFirst=false;
        if (mac()) { showReturnWindow(); XdesxMacActivity.openOnDisplay(key,display); }
        else stop();
    }
    boolean mac() { return component.endsWith("/"+XdesxMacActivity.class.getName()); }
    String label() { return mac()?"Mac":chrome()?"Chrome":context.getString(com.termux.x11.app.R.string.xu_application); }
    boolean chrome() { return component.startsWith("com.android.chrome/"); }
    boolean fullscreen() { return nativeFullscreen; }
    synchronized boolean toggle(int external,android.graphics.Bitmap cursor,int hotspot,int width,int height) throws RemoteException {
        if (stopped || display<0) return false;
        boolean enter=!nativeFullscreen;
        if (enter && !mac()) {
            HdmiScale.enter(context,injector,external,dpi);
            densityTarget=external;
        }
        // GFN queries the display associated with its original UI process again
        // after migration when choosing the streaming profile for a new game.
        boolean syncGameMode=!hdmiFirst && component.startsWith("com.nvidia.geforcenow/") && reader!=null;
        boolean didMove=false;
        try {
            if (syncGameMode) {
                int sourceWidth=enter?width:reader.getWidth(), sourceHeight=enter?height:reader.getHeight();
                injector.resizeVirtualDisplay(display,sourceWidth,sourceHeight,dpi,reader.getSurface());
                Log.i("XDesX","GFN source mode "+display+" "+sourceWidth+"x"+sourceHeight+" enter="+enter);
            }
            boolean launch=enter && hdmiFirst && !mac();
            int moved=launch?injector.startXdesxFullscreen(display,external,component,cursor,hotspot,width,height):
                    injector.moveXdesxTask(display,external,component.split("/",2)[0],task,enter,cursor,hotspot,width,height);
            if (moved<0) return false;
            task=moved;
            nativeFullscreen=enter;
            syncHost();
            didMove=true;
            if (!enter) showReturnWindow();
            if (hdmiFirst) {
                hdmiFirst=false;
                if (mac()) XdesxMacActivity.openOnDisplay(key,external);
                else watchTask();
            }
            Log.i("XDesX","task "+task+" "+display+" <-> "+external+" fullscreen="+enter);
            return true;
        } finally {
            try {
                if (syncGameMode && !didMove)
                    injector.resizeVirtualDisplay(display,enter?reader.getWidth():width,enter?reader.getHeight():height,dpi,reader.getSurface());
            } finally {
                if (enter!=didMove) restoreDensity();
            }
        }
    }
    private synchronized void restoreDensity() {
        if (densityTarget>0) {
            HdmiScale.restore(context,injector,densityTarget);
            densityTarget=-1;
        }
    }
    XdesxWindow(android.content.Context context,ICmdEntryInterface s,IInjector i,String k,String c,Runnable fs,Runnable f) {
        this.context=context; server=s; injector=i; key=k;
        hdmiFirst=c.startsWith("hdmi:");
        awaitingMap=hdmiFirst;
        if (hdmiFirst) c=c.substring(5);
        int marker=c.indexOf('#');
        component=marker<0?c:c.substring(0,marker);
        hostId=marker<0?null:c.substring(marker+1); requestFullscreen=fs; finished=f;
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
            String pkg=component.split("/",2)[0];
            android.content.SharedPreferences ratios=context.getSharedPreferences("portrait_windows",0);
            sendAspect(mac()?0:ratios.getInt(pkg+".w",0),mac()?0:ratios.getInt(pkg+".h",0));
            while (!stopped) {
                int op=in.readInt(), x=in.readInt(), y=in.readInt();
                if (op==8 && !mac()) setMinimized(x!=0);
                if (op==7 || (op==10 && !nativeFullscreen)) requestFullscreen.run();
                if (op==1) resize(x,y);
                if (display<0) continue;
                synchronized (injector) {
                    if (op==9) {
                        editorFocus(x!=0);
                        if (mac() && x==0) ScreenShareActivity.releaseInput(display);
                        injector.xdesxWindowFocus(display,x!=0);
                        continue;
                    }
                    if (op==11) {
                        xGrab=x!=0;
                        if (focused.get()==this) syncHost();
                        continue;
                    }
                    if (nativeFullscreen) continue;
                    if (op==4 || op==5) {
                        int scan=x & 0xffff;
                        if (!mac() || !ScreenShareActivity.sendKeysym(display,scan,y,op==4)) {
                            int source=op==4 ? DesktopInput.keyboardForScan(scan) : -1;
                            injector.xdesxWindowInput(display,reader.getWidth(),reader.getHeight(),op,x,source);
                        }
                    } else if (op==6 || (op>=17 && op<=23) || (op>=33 && op<=39)) {
                        // An already-active X window may have lost Android editor focus to the
                        // phone keyboard or panel. Reassert it on a real click, never on hover.
                        if (op==17) {
                            editorFocus(true);
                            injector.xdesxWindowFocus(display,true);
                        }
                        if (!mac() || !ScreenShareActivity.windowPointer(display,op,x,y))
                            injector.xdesxWindowInput(display,reader.getWidth(),reader.getHeight(),op,x,y);
                    }
                }
            }
        } catch (EOFException ignored) {
        } catch (Exception e) { Log.e("XDesX","window "+component,e); }
        finally {
            editorFocus(false);
            stopped=true; control=null;
            restoreDensity();
            if (mac()) XdesxMacActivity.closeWindow(key);
            if (display>=0) try { injector.releaseXdesxDisplay(display); } catch(Exception ignored) {}
            frames.quitSafely();
            try { frames.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (reader!=null) reader.close();
            finished.run();
        }
    }
    private synchronized void setMinimized(boolean value) throws RemoteException {
        if (value==minimized || display<0 || reader==null || nativeFullscreen) return;
        injector.setVirtualDisplaySurface(display,value?null:reader.getSurface());
        minimized=value;
        if (value) {
            editorFocus(false);
            injector.xdesxWindowFocus(display,false);
        }
    }
    private int dpi=160;
    synchronized void density(int value) {
        dpi=value;
        if (stopped || reader==null || display<0) return;
        try { injector.resizeVirtualDisplay(display,reader.getWidth(),reader.getHeight(),dpi,minimized?null:reader.getSurface()); }
        catch (RemoteException e) { Log.w("XDesX","window density",e); }
    }
    private synchronized void resize(int w,int h) throws Exception {
        if (w<=0 || h<=0 || (long)w*h>16777216) throw new IOException("invalid window dimensions");
        ImageReader next=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3,HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        try {
            if (display<0) {
                Log.i("XDesX",(hdmiFirst?"reserved return window ":"first mapped content ")+component+" "+w+"x"+h);
                display=injector.createVirtualDisplay("XDesX "+component,w,h,dpi,next.getSurface(),0);
                if (mac()) {
                    android.app.ActivityOptions options=android.app.ActivityOptions.makeBasic();
                    options.setLaunchDisplayId(display);
                    android.content.Intent intent=new android.content.Intent(context,XdesxMacActivity.class);
                    intent.putExtra("host",hostId).putExtra("window",key).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK |
                        android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    if (hdmiFirst) {
                        XdesxMacActivity.prepareFullscreen(key,requestFullscreen);
                        intent.putExtra("hdmi_first",true);
                    }
                    context.startActivity(intent,options.toBundle());
                } else if (!hdmiFirst) {
                    String failure=injector.startApp(component,display);
                    if (failure!=null) throw new IOException(failure);
                }
                if (!hdmiFirst || mac()) watchTask();
            } else {
                injector.resizeVirtualDisplay(display,w,h,dpi,minimized?null:next.getSurface());
                Log.i("XDesX","committed resize "+w+"x"+h);
            }
        } catch(Exception e) { next.close(); throw e; }
        ImageReader old=reader;
        reader=next;
        Handler handler=new Handler(frames.getLooper());
        if (old!=null) { old.setOnImageAvailableListener(null,null); handler.post(old::close); }
        if (old == null && !mac()) handler.postDelayed(() -> probePortrait(handler, null, 0), 450);
        next.setOnImageAvailableListener(this::frame,handler);
        handler.post(() -> frame(next));
        if (old==null && hdmiFirst && !mac()) requestFullscreen.run();
    }
    private void watchTask() throws RemoteException {
        injector.watchXdesxWindow(display,new IXdesxWindowListener.Stub() {
            public void taskEnded(int fullscreenTask) {
                synchronized (XdesxWindow.this) { if (nativeFullscreen ? task==fullscreenTask : fullscreenTask<0) stop(); }
            }
        });
    }
    // Only sample during startup. A second matching layout excludes transient launch bounds.
    private synchronized void probePortrait(Handler handler, android.graphics.Rect previous, int attempt) {
        if (stopped || nativeFullscreen || control == null || reader == null) return;
        String pkg = component.split("/", 2)[0];
        int w = reader.getWidth(), h = reader.getHeight();
        android.content.SharedPreferences prefs = context.getSharedPreferences("portrait_windows", 0);
        try {
            android.graphics.Rect bounds = injector.xdesxPortraitBounds(display, pkg);
            boolean knownUnboxed = bounds != null && bounds.isEmpty();
            if (bounds != null && !(bounds.width() > 0 && bounds.width() < bounds.height()
                    && bounds.left >= 2 && w - bounds.right >= 2
                    && bounds.top == 0 && Math.abs(h - bounds.bottom) <= 1)) bounds = null;
            // A restored portrait window already fits. Reapply its previously measured hint.
            int rw = prefs.getInt(pkg + ".w", 0), rh = prefs.getInt(pkg + ".h", 0);
            boolean restored = knownUnboxed && rw > 0 && rh > 0 && Math.abs(w - (double) h * rw / rh) <= 2;
            if (bounds != null && bounds.equals(previous)) {
                rw = bounds.width();
                rh = bounds.height();
                prefs.edit().putInt(pkg + ".w", rw).putInt(pkg + ".h", rh).apply();
            } else if (!restored) {
                android.graphics.Rect sample = bounds;
                if (attempt < 7) handler.postDelayed(() -> probePortrait(handler, sample, attempt + 1), 400);
                return;
            }
            sendAspect(rw,rh);
            Log.i("XDesX", "portrait window " + pkg + " aspect " + rw + "x" + rh);
        } catch (Exception e) { if (!stopped) Log.w("XDesX", "portrait layout", e); }
    }

    private synchronized void sendAspect(int width,int height) throws android.system.ErrnoException, java.io.InterruptedIOException {
        byte[] hint=java.nio.ByteBuffer.allocate(8).putInt(width).putInt(height).array();
        int sent=0;
        while(sent<hint.length)sent+=Os.write(control.getFileDescriptor(),hint,sent,hint.length-sent);
    }

    private boolean logged;
    private void frame(ImageReader src) {
        if (stopped || src!=reader) return;
        try (Image image=src.acquireLatestImage()) {
            if (image==null || nativeFullscreen || minimized) return;
            try (HardwareBuffer buffer=image.getHardwareBuffer(); SyncFence fence=image.getFence()) {
                if (fence.isValid() && !fence.await(Duration.ofSeconds(1))) { stop(); return; }
                server.xdesxFrame(key,buffer);
                if (!logged) { logged=true; Log.i("XDesX","imported "+component+" "+buffer.getWidth()+"x"+buffer.getHeight()); }
            }
        } catch(Exception e) {
            if (!stopped && src==reader) {
                Log.e("XDesX","frame "+component,e);
                stop();
            }
        }
    }
}
