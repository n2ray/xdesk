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
    private XdesxMacProfiles macProfiles;
    private XdesxNotifications notifications;
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
    private boolean switching, gamePassthrough, leavingGameTouch, hideImeOnReturn;
    private void setGame(boolean on) {
        if (injector != null) try { injector.setGameMode(on); }
        catch (RemoteException e) { error(e.toString()); }
    }
    private final IInjectorListener fullscreenEvents = new IInjectorListener.Stub() {
        public void onForwarding(boolean on,int count) {}
        public void onMessage(String message) {}
        public void onAppInSession(boolean inSession) {}
        public void onButtonUp() {}
        public void onTopApp(String pkg) {}
        public void onFingers(float[] x,float[] y) {}
        public void onEdgePush(int edge,int dx,int dy,boolean held,int x,int y,boolean left) {}
        public void onClipboard(String text) {}
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
            new android.app.AlertDialog.Builder(this).setMessage("请允许 xdesx 在外屏显示返回窗口工具条。").setNegativeButton("取消",null).setPositiveButton("打开权限设置",(a,b)->startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:"+getPackageName())))).show();
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
                if (fullWindow == null) {
                    hideImeOnReturn = moved;
                    capture();
                }
                else if (mouse.hasPointerCapture()) mouse.releasePointerCapture();
            });
        },"xdesx-fullscreen").start();
    }
    private boolean desktopNative;
    private void routeDesktop() {
        if(injector==null || external<=0 || desktopNative) return;
        try {
            desktopNative=injector.routeXdesxDesktop(external);
            MainActivity.xdesxHardwareCursor(desktopNative);
            if(desktopNative && mouse.hasPointerCapture()) mouse.releasePointerCapture();
        } catch(RemoteException e){error(e.toString());}
    }
    private boolean padInput(int op, float x, float y) {
        if (!switching && fullWindow != null && fullWindow.mac() &&
                com.termux.x11.screenshare.ScreenShareActivity.fullscreenPad(external,op,x,y,
                        toolbar == null ? null : toolbar::padEvent)) return true;
        return nativeInput(op,x,y);
    }

    private boolean nativeInput(int op,float x,float y) {
        if (switching || gamePassthrough) return true;
        if(desktopNative && op>=1 && op<=3 && injector!=null) {
            try {injector.desktopMouse(op,x,y);} catch(RemoteException e){error(e.toString());}
            return true;
        }
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
                injector=next; routeDesktop();
            } catch(RemoteException e) { error(e.toString()); }
        }
        public void onServiceDisconnected(ComponentName name) { injector = null; desktopNative=false;MainActivity.xdesxHardwareCursor(false);capture(); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        android.widget.FrameLayout layout=new android.widget.FrameLayout(this);
        layout.addView(new TouchpadView(this, new GestureOutput() {
            public void move(int x,int y) {
                int speed=panel==null?100:panel.padSpeed;
                float dx=x*speed/100f, dy=y*speed/100f;
                if (!padInput(1,dx,dy)) moveXPointer(dx,dy);
            }
            public void button(int b,boolean down) { if (!padInput(2,b,down?1:0)) input.button(b,down); }
            public void scroll(int phase,float x,float y) { float sign=panel!=null && panel.reverse?-1:1; if (!padInput(3,x*sign,y*sign)) input.scrollFine(y*sign,x*sign,phase); }
        }, () -> desktopNative && injector != null && fullWindow == null));
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
        android.widget.Button controls=new android.widget.Button(this);
        controls.setText("桌面控制");controls.setAlpha(.72f);
        layout.addView(controls,new android.widget.FrameLayout.LayoutParams(-2,-2,android.view.Gravity.BOTTOM|android.view.Gravity.END));
        controls.setOnClickListener(v->onBackPressed());
        setContentView(layout);
        displays = getSystemService(DisplayManager.class);
        displays.registerDisplayListener(this,null);
        AppSession.get(this).activateIfPaired();
        openInjector();
        if (!AppSession.get(this).injectorReady()) mouse.postDelayed(()->{
            if (!isFinishing() && !isDestroyed() && !AppSession.get(this).injectorReady()) setup();
        },1500);
        try {macProfiles=new XdesxMacProfiles(this);} catch(IOException e){error(e.toString());}
        panel=new XdesxPanelState(this);
        place();
        try { linux = new XdesxLinux(this, this::error); }
        catch (IOException e) { error(e.toString()); }
        notifications=new XdesxNotifications(this);
        runtime=new XdesxRuntime(this,notifications);
        new Thread(() -> {
            try { runtime.run((key,app) -> runOnUiThread(() -> openWindow(key,app,SystemClock.elapsedRealtime()+25000))); }
            catch(Exception e) { if(!isDestroyed()) error(e.toString()); }
        },"xdesx-start").start();
    }
    private void movePointer(float dx,float dy) {
        if (!nativeInput(1,dx,dy)) moveXPointer(dx,dy);
    }
    private void moveXPointer(float dx,float dy) {
        restX+=dx; restY+=dy;
        int x=(int)restX,y=(int)restY;
        restX-=x; restY-=y;
        if(x!=0 || y!=0) input.move(x,y);
    }
    private void capture() {
        if(mouse!=null && fullWindow == null && !switching && external>=0 && !isDestroyed() && hasWindowFocus()) {
            mouse.requestFocus();
            if (hideImeOnReturn) {
                hideImeOnReturn = false;
                getWindow().getInsetsController().hide(WindowInsets.Type.ime());
            }
            if(!desktopNative && !mouse.hasPointerCapture()) mouse.requestPointerCapture();
        }
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if(focused) capture(); else if(mouse!=null) mouse.releaseMouse();
    }
    private ActivationPage activation;
    private final Runnable checkSetup=new Runnable() {
        public void run() {
            if(activation==null || isDestroyed() || isFinishing()) return;
            if(AppSession.get(XdesxActivity.this).injectorReady()) {
                activation.close();activation=null;openInjector();return;
            }
            activation.refresh();
            settingsHandler.postDelayed(this,1000);
        }
    };
    private void setup() {
        if(activation!=null) return;
        AppSession session=AppSession.get(this);
        if(session.injectorReady()){openInjector();error("桌面控制已授权");return;}
        activation=new ActivationPage(this,new SettingsUi(this),session.shell(),session.shizukuInstalled(),new ActivationPage.Actions(){
            public void openPairingSettings(){
                beginWirelessPairing();
            }
            public void useShizuku(){
                if(session.shizukuState()==AppSession.SHIZUKU_NO_PERMISSION) session.requestPermission();
                else error("请启动 Shizuku，然后回到 xdesx");
            }
            public void cancelled(){activation=null;settingsHandler.removeCallbacks(checkSetup);}
        });
        settingsHandler.post(checkSetup);
    }
    private static final int REQUEST_PAIRING_NOTIFICATIONS = 731;
    private static final int REQUEST_PAIRING_SETTINGS = 732;

    private void beginWirelessPairing() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQUEST_PAIRING_NOTIFICATIONS);
            return;
        }
        openWirelessSettingsForPairing();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == REQUEST_PAIRING_NOTIFICATIONS && grants.length > 0) openWirelessSettingsForPairing();
    }

    private void openWirelessSettingsForPairing() {
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        android.app.NotificationChannel channel = manager.getNotificationChannel(PairingService.CHANNEL);
        boolean blockedChannel = channel != null && channel.getImportance() == android.app.NotificationManager.IMPORTANCE_NONE;
        if (!manager.areNotificationsEnabled() || blockedChannel) {
            boolean appEnabled = manager.areNotificationsEnabled();
            Intent settings = new Intent(appEnabled ? android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                    : android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
            if (appEnabled) settings.putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, PairingService.CHANNEL);
            new android.app.AlertDialog.Builder(this).setTitle("开启配对通知")
                    .setMessage("允许 xdesx 发送配对通知后，就能留在系统配对码页面，下拉通知栏输入六位码。")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("打开通知设置", (dialog, which) -> startActivityForResult(settings, REQUEST_PAIRING_SETTINGS)).show();
            return;
        }
        try {
            startForegroundService(PairingService.startIntent(this));
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .putExtra(":settings:fragment_args_key", "toggle_adb_wireless"));
        } catch (RuntimeException e) {
            error("无法开启无线配对：" + e.getLocalizedMessage());
        }
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
        routeDesktop();
        Context app=getApplicationContext();
        MainActivity.displayAllowed=id -> TargetDisplay.allowsDesktop(app,id);
        panel.screen(d);
        applyDisplay();
        Prefs prefs=((LorieApp)getApplication()).getPrefs(createDisplayContext(d));
        prefs.pointerCapture.put(false);
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
    private void chooseWallpaper() {
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        ActivityOptions options=ActivityOptions.makeBasic();
        options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        try { startActivityForResult(pick,730,options.toBundle()); }
        catch(RuntimeException unavailable) { error("没有可用的图片选择器"); }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request == REQUEST_PAIRING_SETTINGS) {
            android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
            android.app.NotificationChannel channel = manager.getNotificationChannel(PairingService.CHANNEL);
            if (manager.areNotificationsEnabled() && (channel == null || channel.getImportance() != android.app.NotificationManager.IMPORTANCE_NONE))
                openWirelessSettingsForPairing();
            return;
        }
        if(request!=730 || result!=RESULT_OK || data==null || data.getData()==null) return;
        android.net.Uri uri=data.getData();
        Display d=displays.getDisplay(external);
        if(d==null) return;
        android.graphics.Point size=new android.graphics.Point();d.getRealSize(size);
        new Thread(()->{
            File dir=new File(getFilesDir(),"xd");
            try {
                android.graphics.Bitmap image=android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(getContentResolver(),uri),(decoder,info,source)->{
                        int w=info.getSize().getWidth(),h=info.getSize().getHeight();
                        double ratio=Math.min(1.,Math.min(4096./Math.max(w,h),Math.max((double)size.x/w,(double)size.y/h)));
                        decoder.setTargetSize(Math.max(1,(int)(w*ratio)),Math.max(1,(int)(h*ratio)));
                        decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
                File tmp=File.createTempFile("wallpaper",".jpg",dir);
                try {
                    try(FileOutputStream out=new FileOutputStream(tmp)){
                        if(!image.compress(android.graphics.Bitmap.CompressFormat.JPEG,92,out)) throw new IOException("图片保存失败");
                    }
                    java.nio.file.Files.move(tmp.toPath(),new File(dir,"wallpaper.jpg").toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    java.nio.file.Files.write(new File(dir,"wallpaper-mode").toPath(),"fill".getBytes("UTF-8"));
                    java.nio.file.Files.write(new File(dir,"run/wallpaper-update").toPath(),new byte[]{1});
                } finally { image.recycle(); tmp.delete(); }
            } catch(IOException|RuntimeException e){error("壁纸 · "+e.getMessage());}
        },"xdesx-wallpaper").start();
    }
    private void readCursorShape() {
        File file=new File(getFilesDir(),"xd/run/cursor-image");
        if(file.length()>16+256*256*4) return;
        try(java.io.DataInputStream in=new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(file)))) {
            int w=in.readInt(),h=in.readInt(),x=in.readInt(),y=in.readInt();
            if(w<1 || h<1 || w>256 || h>256 || x<0 || y<0 || x>=w || y>=h) return;
            int[] pixels=new int[w*h];
            for(int i=0;i<pixels.length;i++){
                int argb=in.readInt(),a=argb>>>24;
                if(a>0 && a<255) {
                    int r=Math.min(255,(argb>>16&255)*255/a),g=Math.min(255,(argb>>8&255)*255/a),b=Math.min(255,(argb&255)*255/a);
                    argb=a<<24|r<<16|g<<8|b;
                }
                pixels[i]=argb;
            }
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(pixels,w,h,android.graphics.Bitmap.Config.ARGB_8888);
            MainActivity.xdesxShape(android.view.PointerIcon.create(bitmap,x,y));
        } catch(IOException | IllegalArgumentException e){Log.w("XDesX","cursor image",e);}
    }
    private void panelAction(String action) {
        if(action.startsWith("notify-")) {notifications.action(action.substring(7));return;}
        if(action.startsWith("mac-")) {
            try {if(macProfiles!=null) macProfiles.apply(action);} catch(IllegalArgumentException e){error(e.getMessage());}
            return;
        }
        if(action.equals("cursor-image")) {readCursorShape();return;}
        if(action.equals("wallpaper-pick")) {chooseWallpaper(); return;}
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
        new android.app.AlertDialog.Builder(this).setItems(new String[]{"返回 X 窗口","恢复外屏原生显示","结束桌面","连接与授权","手机键盘","取消"},
            (dialog,item) -> { if(item==0) { panel.returnSequence++; panel.publish(); } if(item==1) panelAction("recover"); if(item==2) finish(); if(item==3) setup(); if(item==4){mouse.requestFocus();getWindow().getInsetsController().show(WindowInsets.Type.ime());} }).show();
    }
    @Override protected void onResume(){super.onResume();if(notifications!=null)notifications.refresh();}
    @Override protected void onDestroy() {
        settingsHandler.removeCallbacks(revertRender);
        closeToolbar();
        if(notifications!=null) notifications.close();
        if(macProfiles!=null) macProfiles.close();
        if(panel!=null) panel.close();
        displays.unregisterDisplayListener(this);
        if (linux != null) linux.close();
        if(runtime!=null) runtime.close();
        MainActivity.xdesxHardwareCursor(false);
        if(mouse!=null) mouse.releasePointerCapture();
        for (XdesxWindow w:windows.values()) w.stop();
        if (injector!=null) {
            try { injector.shutdown(); } catch(RemoteException e) { Log.w("XDesX","shutdown",e); }
        }
        if (shizuku!=null) AppSession.get(this).closeWirelessInjector(connection,shizuku);
        settingsHandler.removeCallbacks(checkSetup);if(activation!=null) activation.close();
        super.onDestroy();
    }
}
