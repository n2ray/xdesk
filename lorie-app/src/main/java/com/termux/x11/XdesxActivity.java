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
    private final String token = UUID.randomUUID().toString();
    private final Set<String> allowed = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String,XdesxWindow> windows = new HashMap<>();
    private DisplayManager displays;
    private int external = -1;
    private IInjector injector;
    private Boolean shizuku;
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            injector = IInjector.Stub.asInterface(service);
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
        shizuku = AppSession.get(this).openWirelessInjector(connection);
        place();
        new Thread(this::installSession,"xdesx-start").start();
    }
    private void place() {
        for (Display d : displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (d.getName().startsWith("XDesX") || external == d.getDisplayId()) continue;
            if (external != -1) return;
            external = d.getDisplayId();
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(external);
            startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),opts.toBundle());
            return;
        }
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
            for (String name : new String[]{"session.sh","setup.py","window.py","client.c"}) {
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
                if (!isDestroyed() && !TermuxCommand.run(this,cmd.toString(),new TermuxCommand.Callback() {
                    public void onOutput(String s) { }
                    public void onFailure(String s) { error(s); }
                })) error("Termux RUN_COMMAND failed");
            });
        } catch (Exception e) { error(e.toString()); }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!token.equals(intent.getStringExtra("token"))) return;
        String key=intent.getStringExtra("window"), app=intent.getStringExtra("component");
        if (key==null || !key.matches("[0-9a-f]{32}") || app==null || !allowed.contains(app) || windows.containsKey(key)) return;
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
        for (XdesxWindow w:windows.values()) w.stop();
        if (shizuku!=null) AppSession.get(this).closeWirelessInjector(connection,shizuku);
        TermuxCommand.run(this,"cd ~/.local/share/xdesx && test \"$(cat session.token)\" = '"+token+
            "' && read -r p < session.pid && kill -TERM -- -\"$p\"",new TermuxCommand.Callback(){
            public void onOutput(String s) {} public void onFailure(String s) { Log.w("XDesX",s); }
        });
        super.onDestroy();
    }
}
