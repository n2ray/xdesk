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
    private XdesxLinux linux;
    private XdesxPanelState panel;
    private final Handler settingsHandler=new Handler(Looper.getMainLooper());
    private int savedRender;
    private final Runnable revertRender=() -> {
        if (savedRender==0) return;
        panel.render=savedRender;
        savedRender=0;
        applyDisplay();
        panel.publish();
    };
    private KeyInputView mouse;
    private float restX,restY;
    private volatile XdesxFullscreenBar toolbar;
    private XdesxWindow fullWindow;
    private boolean switching, gamePassthrough, leavingGameTouch;
    private void setGame(boolean on) {
        if (injector != null) try { injector.setGameMode(on); }
        catch (RemoteException e) { error(e.toString()); }
    }
    private final IInjectorListener fullscreenEvents = new WirelessDisplay.NoEvents() {
        @Override public void onGameMode(boolean on) {
            runOnUiThread(() -> {
                gamePassthrough = on && fullWindow != null;
                if (toolbar != null) toolbar.gameMode(gamePassthrough);
                if (gamePassthrough) Toast.makeText(XdesxActivity.this,
                    "游戏直通 · Ctrl+Alt+G 或轻触手机退出", Toast.LENGTH_SHORT).show();
            });
        }
        @Override public void onCursor(int x, int y) {
            XdesxFullscreenBar bar = toolbar;
            if (bar != null) bar.cursor(y);
        }
    };

    private void closeToolbar() {
        XdesxFullscreenBar bar = toolbar;
        toolbar = null;
        gamePassthrough = false;
        if (bar != null) bar.close();
    }
    private void toggleWindow(XdesxWindow w) {
        Display d=displays.getDisplay(external);
        if (w==null || d==null || switching) return;
        if (fullWindow == null && !android.provider.Settings.canDrawOverlays(this)) {
            error("请先允许 XDesk 显示在其他应用上层，以便在外屏退出全屏");
            return;
        }
        IInjector service = injector;
        if (service == null) return;
        switching=true;
        android.graphics.Point size=new android.graphics.Point();
        d.getRealSize(size);
        View arrow=CursorOverlay.arrow(this,panel.scale/100f);
        int side=arrow.getLayoutParams().width;
        arrow.layout(0,0,side,side);
        android.graphics.Bitmap cursor=android.graphics.Bitmap.createBitmap(side,side,android.graphics.Bitmap.Config.ARGB_8888);
        arrow.draw(new android.graphics.Canvas(cursor));
        final int target=external, hotspot=CursorOverlay.arrowTipX(arrow);
        new Thread(() -> {
            boolean ok=false;
            try { service.setMouseSpeed(panel.mouseSpeed / 100f); ok=w.toggle(target,cursor,hotspot,size.x,size.y); }
            catch(RemoteException e) { Log.w("XDesX","toggle",e); }
            boolean moved=ok;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                switching=false;
                if (moved) fullWindow=w.fullscreen() && windows.containsValue(w)?w:null;
                else error(w.label()+" 任务切换失败");
                if (moved) {
                    closeToolbar();
                    if (fullWindow != null) {
                        Runnable exit = () -> { if (fullWindow == w) toggleWindow(w); };
                        toolbar = new XdesxFullscreenBar(this, d, panel.scale, exit, w.mac() ? null : () -> setGame(true), () -> {
                            error("外屏工具条无法显示，正在返回窗口");
                            exit.run();
                        });
                    }
                }
                if (fullWindow == null) capture();
                else if (mouse.hasPointerCapture()) mouse.releasePointerCapture();
            });
        },"xdesx-fullscreen").start();
    }
    private boolean nativeInput(int op,float x,float y) {
        if (switching || gamePassthrough) return true;
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
                next.attach(fullscreenEvents,getPackageName());
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
            public void move(int x,int y) { int speed=panel==null?100:panel.padSpeed; movePointer(x*speed/100f,y*speed/100f); }
            public void button(int b,boolean down) { if (!nativeInput(2,b,down?1:0)) input.button(b,down); }
            public void scroll(int phase,float x,float y) { float sign=panel!=null && panel.reverse?-1:1; if (!nativeInput(3,x*sign,y*sign)) input.scrollFine(y*sign,x*sign,phase); }
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
                float gain=panel==null?1f:panel.mouseSpeed/100f;
                movePointer(dx*gain,dy*gain);
            }
            public void mouseButton(int b,boolean down) { if (!nativeInput(2,b,down?1:0)) input.button(b,down); }
            public void mouseScroll(float v,float h) { float k=panel!=null && panel.reverse?-1:1; if (!nativeInput(3,h*k,-v*k)) input.scrollFine(-v*k,h*k,0); }
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
        panel=new XdesxPanelState(this);
        place();
        try { linux = new XdesxLinux(this, this::error); }
        catch (IOException e) { error(e.toString()); }
        runtime=new XdesxRuntime(this);
        new Thread(() -> {
            try { runtime.run((key,app) -> runOnUiThread(() -> openWindow(key,app,SystemClock.elapsedRealtime()+25000))); }
            catch(Exception e) { if(!isDestroyed()) error(e.toString()); }
        },"xdesx-start").start();
    }
    private void movePointer(float dx,float dy) {
        if (nativeInput(1,dx,dy)) return;
        restX+=dx; restY+=dy;
        int x=(int)restX,y=(int)restY;
        restX-=x; restY-=y;
        if(x!=0 || y!=0) input.move(x,y);
    }
    private void capture() {
        if(mouse!=null && fullWindow == null && !switching && external>=0 && !isDestroyed() && hasWindowFocus()) {
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
        panel.screen(d);
        applyDisplay();
        Prefs prefs=((LorieApp)getApplication()).getPrefs(createDisplayContext(d));
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
    private void applyDisplay() {
        Display d=displays.getDisplay(external);
        if (d==null) return;
        android.graphics.Point px=new android.graphics.Point();
        d.getRealSize(px);
        Prefs prefs=((LorieApp)getApplication()).getPrefs(createDisplayContext(d));
        int w=Math.max(1,Math.round(px.x*panel.render/100f)), h=Math.max(1,Math.round(px.y*panel.render/100f));
        prefs.displayResolutionMode.put("custom");
        prefs.displayResolutionCustom.put(w+"x"+h);
        prefs.displayFilteringMode.put("bilinear");
        int dpi=Math.round(96*panel.scale/100f);
        prefs.putInt("xdeskDpi",dpi);
        DesktopScale.applyDpi(dpi);
        Log.i("XDesX","panel scale="+panel.scale+" render="+w+"x"+h);
    }
    private void panelAction(String action) {
        if (action.startsWith("linux-")) {
            if (linux != null) linux.action(action.substring(6));
            return;
        }

        if (action.startsWith("full-")) {
            if (fullWindow==null) toggleWindow(windows.get(action.substring(5)));
            return;
        }
        if (action.equals("recover")) {
            settingsHandler.removeCallbacks(revertRender);
            savedRender=0; panel.render=100; panel.scale=100; panel.save(); applyDisplay();
            for(XdesxWindow w:windows.values()) w.density(160);
            return;
        }
        String[] parts=action.split("-",2);
        int value=Integer.parseInt(parts[1]);
        switch(parts[0]) {
            case "mouse": panel.mouseSpeed=value; panel.saveOptions(); return;
            case "pad": panel.padSpeed=value; panel.saveOptions(); return;
            case "limit": panel.limit=value; panel.saveOptions(); return;
            case "reverse": panel.reverse=value==1; panel.saveOptions(); return;
            case "remember": panel.remember=value==1; panel.saveOptions(); return;
        }
        if (parts[0].equals("confirm")) {
            if (savedRender!=0 && panel.render==value) {
                settingsHandler.removeCallbacks(revertRender);
                savedRender=0;
                panel.save();
            }
        } else if (parts[0].equals("scale")) {
            if (savedRender!=0) revertRender.run();
            panel.scale=value;
            panel.save();
            applyDisplay();
            for (XdesxWindow w:windows.values()) w.density(Math.round(160*value/100f));
        } else if (parts[0].equals("render") && panel.render!=value) {
            if (savedRender==0) savedRender=panel.render;
            panel.render=value;
            applyDisplay();
            panel.publish();
            settingsHandler.removeCallbacks(revertRender);
            settingsHandler.postDelayed(revertRender,15000);
        }
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
        if ("action".equals(key)) { panelAction(app); return; }
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
        if (windows.size()>=panel.limit) { error("已达 "+panel.limit+" 个窗口上限，请先关闭一个，或在全局设置中调整"); return; }
        XdesxWindow w = new XdesxWindow(getApplicationContext(),MainActivity.verifiedServer,injector,key,app,
            () -> runOnUiThread(() -> { if(fullWindow==null) toggleWindow(windows.get(key)); }),
            () -> runOnUiThread(() -> {
                if (windows.remove(key)==fullWindow) { fullWindow=null; closeToolbar(); }
                panel.opened=windows.size(); panel.publish();
            }));
        w.density(Math.round(160*panel.scale/100f));
        windows.put(key,w);
        panel.opened=windows.size(); panel.publish();
        w.start();
    }
    private boolean routeUncapturedMouse(MotionEvent e) {
        if (mouse==null || external<0 || !e.isFromSource(InputDevice.SOURCE_MOUSE)) return false;
        // A newly connected mouse can hit the phone during the display focus transition.
        capture();
        return mouse.mouseBeforeCapture(e);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN && gamePassthrough) {
                leavingGameTouch = true;
                setGame(false);
            }
            if (leavingGameTouch) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL)
                    leavingGameTouch = false;
                return true;
            }
        }
        if (gamePassthrough) return super.dispatchTouchEvent(e);
        return routeUncapturedMouse(e) || super.dispatchTouchEvent(e);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        return (!gamePassthrough && routeUncapturedMouse(e)) || super.dispatchGenericMotionEvent(e);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getScanCode()>0 && e.getKeyCode()!=KeyEvent.KEYCODE_BACK) {
            boolean down=e.getAction()==KeyEvent.ACTION_DOWN;
            if (!nativeInput(4,e.getScanCode(),down?1:0)) input.key(e.getScanCode(),down);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
    @Override public void onBackPressed() {
        if (fullWindow!=null) { toggleWindow(fullWindow); return; }
        new android.app.AlertDialog.Builder(this).setItems(new String[]{"返回 X 窗口","恢复外屏原生显示","结束桌面","取消"},
            (dialog,item) -> { if(item==0) { panel.returnSequence++; panel.publish(); } if(item==1) panelAction("recover"); if(item==2) finish(); }).show();
    }
    @Override protected void onDestroy() {
        settingsHandler.removeCallbacks(revertRender);
        closeToolbar();
        if(panel!=null) panel.close();
        displays.unregisterDisplayListener(this);
        if (linux != null) linux.close();
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
