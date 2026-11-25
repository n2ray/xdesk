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
    private final String token = UUID.randomUUID().toString().replace("-","");
    private volatile ParcelFileDescriptor menu;
    private final Set<String> allowed = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String,XdesxWindow> windows = new HashMap<>();
    private DisplayManager displays;
    private int external = -1;
    private IInjector injector;
    private Boolean shizuku;
    private boolean connecting;
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            injector = IInjector.Stub.asInterface(service);
            connecting = false;
        }
        public void onServiceDisconnected(ComponentName name) { injector = null; }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        setContentView(new TouchpadView(this, new GestureOutput() {
            public void move(int x,int y) { input.move(x,y); }
            public void button(int b,boolean down) { input.button(b,down); }
            public void scroll(int phase,float x,float y) { input.scrollFine(y,x,phase); }
        }));
        displays = getSystemService(DisplayManager.class);
        displays.registerDisplayListener(this,null);
        AppSession.get(this).activateIfPaired();
        openInjector();
        place();
        new Thread(this::installSession,"xdesx-start").start();
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
    }
    public void onDisplayAdded(int id) { place(); }
    public void onDisplayChanged(int id) { }
    public void onDisplayRemoved(int id) { if (external == id) finish(); }
    private void error(String s) {
        Log.e("XDesX",s);
        runOnUiThread(() -> Toast.makeText(this,s,Toast.LENGTH_LONG).show());
    }
    private static void file(StringBuilder cmd,String name,byte[] bytes) {
        cmd.append("echo ").append(Base64.encodeToString(bytes,Base64.NO_WRAP))
           .append(" | base64 -d > ").append(name).append("\n");
    }
    private void installSession() {
        try {
            JSONArray catalog = new JSONArray();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> entries = getPackageManager().queryIntentActivities(launcher,0);
            entries.sort(new ResolveInfo.DisplayNameComparator(getPackageManager()));
            for (ResolveInfo r : entries) {
                String id = new ComponentName(r.activityInfo.packageName,r.activityInfo.name).flattenToString();
                if (getPackageName().equals(r.activityInfo.packageName) || !allowed.add(id)) continue;
                catalog.put(new JSONArray().put(id).put(r.loadLabel(getPackageManager()).toString()));
            }
            StringBuilder cmd = new StringBuilder("set -eu\numask 077\nmkdir -p ~/.local/share/xdesx\ncd ~/.local/share/xdesx\n");
            for (String name : new String[]{"session.sh","setup.py","window.py","client.c","broker.py"}) {
                try (InputStream in = getAssets().open("xdesx/"+name); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[4096]; int n;
                    while ((n=in.read(buf))!=-1) bytes.write(buf,0,n);
                    file(cmd,name,bytes.toByteArray());
                }
            }
            file(cmd,"catalog.json",catalog.toString().getBytes("UTF-8"));
            file(cmd,"session.token",token.getBytes("UTF-8"));
            cmd.append("python setup.py\nclang -O2 client.c -o client.tmp -lX11 -lX11-xcb -lxcb -lxcb-dri3\nmv client.tmp client\n")
               .append("exec setsid bash session.sh >session.log 2>&1");
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                new Thread(this::menuLoop,"xdesx-menu").start();
                if (!TermuxCommand.run(this,cmd.toString(),new TermuxCommand.Callback() {
                    public void onOutput(String s) { }
                    public void onFailure(String s) { error(s); }
                })) error("Termux RUN_COMMAND failed");
            });
        } catch (Exception e) { error(e.toString()); }
    }
    private void menuLoop() {
        long end = SystemClock.elapsedRealtime()+60000;
        while (!isDestroyed() && SystemClock.elapsedRealtime()<end) {
            ICmdEntryInterface server=MainActivity.verifiedServer;
            if (server!=null) {
                try { menu=server.xdesxSocket(token); } catch(Exception ignored) {}
                if (menu!=null) { runOnUiThread(this::openInjector); break; }
            }
            SystemClock.sleep(250);
        }
        if (menu==null) { if (!isDestroyed()) error("XDesX menu connection timed out"); return; }
        try (BufferedReader lines=new BufferedReader(new InputStreamReader(
                new ParcelFileDescriptor.AutoCloseInputStream(menu),"UTF-8"))) {
            String line;
            while ((line=lines.readLine())!=null) {
                JSONArray request=new JSONArray(line);
                String key=request.getString(0), app=request.getString(1);
                runOnUiThread(() -> openWindow(key,app));
            }
        } catch(Exception e) { if (!isDestroyed()) error(e.toString()); }
    }
    private void openWindow(String key,String app) {
        if (isDestroyed()) return;
        if (key==null || !key.matches("[0-9a-f]{32}") || app==null || !allowed.contains(app) || windows.containsKey(key)) return;
        openInjector();
        if (injector==null || MainActivity.verifiedServer==null) { error("X server or shell input service unavailable"); return; }
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
        if (menu!=null) try { android.system.Os.shutdown(menu.getFileDescriptor(),android.system.OsConstants.SHUT_RDWR); }
            catch(Exception ignored) {}

        for (XdesxWindow w:windows.values()) w.stop();
        if (shizuku!=null) AppSession.get(this).closeWirelessInjector(connection,shizuku);
        TermuxCommand.run(this,"cd ~/.local/share/xdesx && test \"$(cat session.token)\" = '"+token+
            "' && read -r p < session.pid && kill -TERM -- -\"$p\"",new TermuxCommand.Callback(){
            public void onOutput(String s) {} public void onFailure(String s) { Log.w("XDesX",s); }
        });
        super.onDestroy();
    }
}
