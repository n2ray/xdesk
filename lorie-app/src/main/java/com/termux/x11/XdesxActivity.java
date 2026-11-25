package com.termux.x11;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.util.Base64;
import android.util.Log;
import android.view.*;
import android.widget.Toast;
import io.github.n2ray.xdesk.input.GestureOutput;
import java.io.*;
import java.util.*;
import org.json.JSONArray;

/** Minimal phone controller for the native Termux energy experiment. */
public final class XdesxActivity extends Activity implements DisplayManager.DisplayListener {
    private final DesktopInput input = new DesktopInput();
    private XdesxRuntime runtime;
    private KeyInputView mouse;
    private float restX,restY;
    private android.widget.Button fullscreenButton;
    private XdesxWindow fullWindow;
    private boolean switching;

    private XdesxWindow fullscreenCandidate() {
        for (XdesxWindow w:windows.values()) if (w.mac()) return w;
        for (XdesxWindow w:windows.values()) if (w.chrome()) return w;
        return null;
    }
    private void refreshButton() {
        XdesxWindow candidate=fullWindow!=null?fullWindow:fullscreenCandidate();
        fullscreenButton.setVisibility(candidate!=null?View.VISIBLE:View.GONE);
        fullscreenButton.setEnabled(!switching);
        if (fullWindow==null && candidate!=null && candidate.mac() && windows.values().stream().anyMatch(XdesxWindow::chrome)) {
            fullscreenButton.setText("选择原生全屏窗口"); return;
        }
        if (candidate!=null) fullscreenButton.setText(fullWindow==null?candidate.label()+" 原生全屏":"返回 "+candidate.label()+" 窗口");
    }
    private void toggleFullscreen() {
        XdesxWindow w=fullWindow!=null?fullWindow:fullscreenCandidate();
        if (fullWindow==null && w!=null && w.mac()) {
            for (XdesxWindow chrome:windows.values()) if (chrome.chrome()) {
                new android.app.AlertDialog.Builder(this).setItems(new String[]{"Mac","Chrome"},
                    (dialog,index) -> toggleWindow(index==0?w:chrome)).show();
                return;
            }
        }
        toggleWindow(w);
    }
    private void toggleWindow(XdesxWindow w) {
        Display d=displays.getDisplay(external);
        if (w==null || d==null || switching) return;
        switching=true;
        refreshButton();
        android.graphics.Point size=new android.graphics.Point();
        d.getRealSize(size);
        View arrow=CursorOverlay.arrow(this,1.5f);
        int side=arrow.getLayoutParams().width;
        arrow.layout(0,0,side,side);
        android.graphics.Bitmap cursor=android.graphics.Bitmap.createBitmap(side,side,android.graphics.Bitmap.Config.ARGB_8888);
        arrow.draw(new android.graphics.Canvas(cursor));
        final int target=external, hotspot=CursorOverlay.arrowTipX(arrow);
        new Thread(() -> {
            boolean ok=false;
            try { ok=w.toggle(target,cursor,hotspot,size.x,size.y); }
            catch(RemoteException e) { Log.w("XDesX","toggle",e); }
            boolean moved=ok;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                switching=false;
                if (moved) fullWindow=w.fullscreen()?w:null;
                else error(w.label()+" 任务切换失败");
                refreshButton();
                capture();
            });
        },"xdesx-fullscreen").start();
    }
    private boolean nativeInput(int op,float x,float y) {
        if (switching) return true;
        if (fullWindow==null) return false;
        try { if (injector!=null) injector.xdesxNativeInput(op,x,y); }
        catch(RemoteException e) { error(e.toString()); }
        return true;
    }
    private final Map<String,XdesxWindow> windows = new HashMap<>();
    private DisplayManager displays;
    private int external = -1;
    private IInjector injector;
    private Boolean shizuku;
    private boolean connecting;
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            connecting = false;
            IInjector next=IInjector.Stub.asInterface(service);
            if (next==null || isDestroyed()) return;
            try {
                next.attach(WirelessDisplay.NO_EVENTS,getPackageName());
                injector=next;
            } catch(RemoteException e) { error(e.toString()); }
        }
        public void onServiceDisconnected(ComponentName name) { injector = null; }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        android.widget.FrameLayout layout=new android.widget.FrameLayout(this);
        layout.addView(new TouchpadView(this, new GestureOutput() {
            public void move(int x,int y) { if (!nativeInput(1,x,y)) input.move(x,y); }
            public void button(int b,boolean down) { if (!nativeInput(2,b,down?1:0)) input.button(b,down); }
            public void scroll(int phase,float x,float y) { if (!nativeInput(3,x,y)) input.scrollFine(y,x,phase); }
        }));
        mouse=new KeyInputView(this,new KeyInputView.Sink() {
            public void text(String s) {
                if (fullWindow==null) input.text(s);
                else try { if (injector!=null) injector.text(s); } catch(RemoteException e) { error(e.toString()); }
            }
            public void tapKey(int scan) {
                if (!nativeInput(4,scan,1)) input.tapKey(scan); else nativeInput(4,scan,0);
            }
            public void mouseMove(float dx,float dy) {
                if (nativeInput(1,dx,dy)) return;
                restX+=dx; restY+=dy; int x=(int)restX,y=(int)restY;
                restX-=x; restY-=y;
                if(x!=0 || y!=0) input.move(x,y);
            }
            public void mouseButton(int b,boolean down) { if (!nativeInput(2,b,down?1:0)) input.button(b,down); }
            public void mouseScroll(float v,float h) { if (!nativeInput(3,h,-v)) input.scrollFine(-v,h,0); }
            public void captureChanged(boolean has) { if(!has) { mouse.releaseMouse(); mouse.postDelayed(XdesxActivity.this::capture,200); } }
            public Object target() { return input; }
            public boolean latched() { return false; }
            public boolean physicalKey(KeyEvent e) { return dispatchKeyEvent(e); }
        });
        layout.addView(mouse,new android.widget.FrameLayout.LayoutParams(1,1));
        fullscreenButton=new android.widget.Button(this);
        fullscreenButton.setFocusable(false);
        fullscreenButton.setOnClickListener(v -> toggleFullscreen());
        layout.addView(fullscreenButton,new android.widget.FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END));
        refreshButton();
        setContentView(layout);
        displays = getSystemService(DisplayManager.class);
        displays.registerDisplayListener(this,null);
        AppSession.get(this).activateIfPaired();
        openInjector();
        place();
        runtime=new XdesxRuntime(this);
        new Thread(() -> {
            try { runtime.run((key,app) -> runOnUiThread(() -> openWindow(key,app,SystemClock.elapsedRealtime()+25000))); }
            catch(Exception e) { if(!isDestroyed()) error(e.toString()); }
        },"xdesx-start").start();
    }
    private void capture() {
        if(mouse!=null && external>=0 && !isDestroyed() && hasWindowFocus()) {
            mouse.requestFocus();
            if(!mouse.hasPointerCapture()) mouse.requestPointerCapture();
        }
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if(focused) capture(); else if(mouse!=null) mouse.releaseMouse();
    }
    private void openInjector() {
        if (injector!=null || connecting) return;
        shizuku=AppSession.get(this).openWirelessInjector(connection);
        connecting=shizuku!=null;
    }
    private void place() {
        Display d=TargetDisplay.real(displays);
        if (d==null || external==d.getDisplayId()) return;
        external=d.getDisplayId();
        Context app=getApplicationContext();
        MainActivity.displayAllowed=id -> TargetDisplay.allowsDesktop(app,id);
        Prefs prefs=((LorieApp)getApplication()).getPrefs(createDisplayContext(d));
        android.graphics.Point pixels=new android.graphics.Point();
        d.getRealSize(pixels);
        prefs.displayResolutionMode.put("custom");
        prefs.displayResolutionCustom.put(pixels.x+"x"+pixels.y);
        prefs.showAdditionalKbd.put(false);
        prefs.additionalKbdVisible.put(false);
        ActivityOptions opts=ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(external);
        startActivity(new Intent(this,MainActivity.class).putExtra("xdesx_quiet",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION),opts.toBundle());
        mouse.postDelayed(() -> {
            if(isDestroyed() || isFinishing() || external<0) return;
            ActivityOptions phone=ActivityOptions.makeBasic();
            phone.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
            startActivity(new Intent(this,XdesxActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),phone.toBundle());
            capture();
        },400);
    }
    public void onDisplayAdded(int id) { place(); }
    public void onDisplayChanged(int id) { }
    public void onDisplayRemoved(int id) { if (external == id) finish(); }
    private void error(String s) {
        Log.e("XDesX",s);
        runOnUiThread(() -> Toast.makeText(this,s,Toast.LENGTH_LONG).show());
    }
    private void openWindow(String key,String app,long deadline) {
        if (isDestroyed()) return;
        if (key==null || !key.matches("[0-9a-f]{32}") || app==null || windows.containsKey(key)) return;
        openInjector();
        if (injector==null || MainActivity.verifiedServer==null) {
            if (SystemClock.elapsedRealtime()<deadline) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> openWindow(key,app,deadline),250);
            } else error("X server or shell input service unavailable");
            return;
        }
        if (app.endsWith("/"+XdesxMacActivity.class.getName())) {
            for (XdesxWindow existing:windows.values()) if (existing.mac()) {
                error("Mac 共享窗口已打开"); return;
            }
        }
        XdesxWindow w = new XdesxWindow(getApplicationContext(),MainActivity.verifiedServer,injector,key,app,
            () -> runOnUiThread(() -> {
                if (windows.remove(key)==fullWindow) fullWindow=null;
                refreshButton();
            }));
        windows.put(key,w);
        w.start();
        refreshButton();
    }
    private boolean routeUncapturedMouse(MotionEvent e) {
        if (mouse==null || external<0 || !e.isFromSource(InputDevice.SOURCE_MOUSE)) return false;
        // A newly connected mouse can hit the phone during the display focus transition.
        // Only a finger on the pad should operate the native fullscreen button.
        capture();
        return mouse.mouseBeforeCapture(e);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        return routeUncapturedMouse(e) || super.dispatchTouchEvent(e);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        return routeUncapturedMouse(e) || super.dispatchGenericMotionEvent(e);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getScanCode()>0 && e.getKeyCode()!=KeyEvent.KEYCODE_BACK) {
            boolean down=e.getAction()==KeyEvent.ACTION_DOWN;
            if (!nativeInput(4,e.getScanCode(),down?1:0)) input.key(e.getScanCode(),down);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
    @Override protected void onDestroy() {
        displays.unregisterDisplayListener(this);
        if(runtime!=null) runtime.close();
        if(mouse!=null) mouse.releasePointerCapture();
        for (XdesxWindow w:windows.values()) w.stop();
        if (injector!=null) {
            try { injector.shutdown(); } catch(RemoteException e) { Log.w("XDesX","shutdown",e); }
        }
        if (shizuku!=null) AppSession.get(this).closeWirelessInjector(connection,shizuku);
        super.onDestroy();
    }
}
