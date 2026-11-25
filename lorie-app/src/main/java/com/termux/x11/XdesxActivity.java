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
            public void move(int x,int y) { input.move(x,y); }
            public void button(int b,boolean down) { input.button(b,down); }
            public void scroll(int phase,float x,float y) { input.scrollFine(y,x,phase); }
        }));
        mouse=new KeyInputView(this,new KeyInputView.Sink() {
            public void text(String s) { input.text(s); }
            public void tapKey(int scan) { input.tapKey(scan); }
            public void mouseMove(float dx,float dy) {
                restX+=dx; restY+=dy; int x=(int)restX,y=(int)restY;
                restX-=x; restY-=y;
                if(x!=0 || y!=0) input.move(x,y);
            }
            public void mouseButton(int b,boolean down) { input.button(b,down); }
            public void mouseScroll(float v,float h) { input.scrollFine(-v,h,0); }
            public void captureChanged(boolean has) { if(!has) { mouse.releaseMouse(); mouse.postDelayed(XdesxActivity.this::capture,200); } }
            public Object target() { return input; }
            public boolean latched() { return false; }
            public boolean physicalKey(KeyEvent e) { return dispatchKeyEvent(e); }
        });
        layout.addView(mouse,new android.widget.FrameLayout.LayoutParams(1,1));
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
        startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),opts.toBundle());
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
        XdesxWindow w = new XdesxWindow(MainActivity.verifiedServer,injector,key,app,
            () -> runOnUiThread(() -> windows.remove(key)));
        windows.put(key,w);
        w.start();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getScanCode()>0 && e.getKeyCode()!=KeyEvent.KEYCODE_BACK) {
            input.key(e.getScanCode(),e.getAction()==KeyEvent.ACTION_DOWN); return true;
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
