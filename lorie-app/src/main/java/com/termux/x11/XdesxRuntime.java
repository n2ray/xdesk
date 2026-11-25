package com.termux.x11;

import android.content.Context;
import android.content.pm.ResolveInfo;
import android.content.Intent;
import android.content.ComponentName;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;
import android.system.Os;
import android.text.TextUtils;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;

/** The packaged ELF files execute from nativeLibraryDir. Only resources and symlinks live in files/. */
final class XdesxRuntime {
    interface Menu { void selected(String key,String component); }
    final Context context;
    final File prefix;
    final String socketName="xdesx-menu-"+UUID.randomUUID();
    private final List<String> apps=new ArrayList<>();
    private volatile LocalServerSocket socket;
    private java.lang.Process session;
    private boolean closed;
    XdesxRuntime(Context c) { context=c.getApplicationContext(); prefix=new File(c.getFilesDir(),"xd"); }
    private File path(String name) { return new File(prefix,name); }
    private void text(String name,String value) throws IOException {
        File f=path(name); f.getParentFile().mkdirs();
        Files.write(f.toPath(),value.getBytes("UTF-8"));
    }
    private void unpack() throws Exception {
        prefix.mkdirs();
        long stamp=context.getPackageManager().getPackageInfo(context.getPackageName(),0).lastUpdateTime;
        File done=path("installed-"+stamp);
        if (done.exists()) return;
        try (ZipInputStream z=new ZipInputStream(context.getAssets().open("xdesx/runtime.zip"))) {
            ZipEntry e;
            while ((e=z.getNextEntry())!=null) {
                File dst=path(e.getName());
                if (!dst.getCanonicalPath().startsWith(prefix.getCanonicalPath()+"/")) throw new IOException("invalid desktop resource");
                dst.getParentFile().mkdirs();
                Files.copy(z,dst.toPath(),StandardCopyOption.REPLACE_EXISTING);
            }
        }
        JSONObject links=new JSONObject(new String(Files.readAllBytes(path("links.json").toPath()),"UTF-8"));
        for (Iterator<String> it=links.keys();it.hasNext();) {
            String name=it.next();
            File dst=path(name);
            dst.getParentFile().mkdirs();
            Files.deleteIfExists(dst.toPath());
            File elf=new File(context.getApplicationInfo().nativeLibraryDir,links.getString(name));
            if (!elf.isFile()) throw new IOException("missing native desktop binary "+elf);
            Os.symlink(elf.getPath(),dst.getPath());
        }
        try (InputStream in=context.getAssets().open("xdesx/session.sh")) {
            Files.copy(in,path("session.sh").toPath(),StandardCopyOption.REPLACE_EXISTING);
        }
        done.createNewFile();
    }
    private String launcherIcon(ResolveInfo entry, ComponentName component) throws IOException {
        android.content.pm.PackageManager pm=context.getPackageManager();
        String pkg=component.getPackageName();
        try {
            long version=pm.getPackageInfo(pkg,0).lastUpdateTime;
            String name=UUID.nameUUIDFromBytes(component.flattenToString().getBytes("UTF-8")).toString();
            File target=path("cache/icons/"+name+"-"+version+".png");
            if (!target.isFile()) {
                target.getParentFile().mkdirs();
                android.graphics.drawable.Drawable icon=entry.loadIcon(pm);
                if (icon==null) return "applications-other";
                android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(64,64,android.graphics.Bitmap.Config.ARGB_8888);
                icon.setBounds(0,0,64,64);
                icon.draw(new android.graphics.Canvas(bitmap));
                File tmp=File.createTempFile("icon",".png",target.getParentFile());
                try {
                    try (OutputStream out=new FileOutputStream(tmp)) {
                        if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)) return "applications-other";
                    }
                    Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
                } finally { bitmap.recycle(); tmp.delete(); }
            }
            return target.getAbsolutePath();
        } catch (android.content.pm.PackageManager.NameNotFoundException | IOException | RuntimeException e) {
            return "applications-other";
        }
    }
    private void configure() throws Exception {
        for(String dir:new String[]{"tmp","run","data/applications","cache","config"}) path(dir).mkdirs();
        String dir=prefix.getPath();
        StringBuilder menu=new StringBuilder("<Menu><Name>Android</Name><AppDir>"+dir+"/data/applications</AppDir><Include>");
        List<ResolveInfo> entries=context.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0);
        entries.sort(new ResolveInfo.DisplayNameComparator(context.getPackageManager()));
        for(ResolveInfo r:entries) {
            if(r.activityInfo.packageName.equals(context.getPackageName())) continue;
            ComponentName activity=new ComponentName(r.activityInfo.packageName,r.activityInfo.name);
            String component=activity.flattenToString();
            int i=apps.size(); apps.add(component);
            String name="android-"+i+".desktop";
            String label=r.loadLabel(context.getPackageManager()).toString().replace('\n',' ').replace('\r',' ');
            text("titles/"+i,label);
            text("data/applications/"+name,"[Desktop Entry]\nType=Application\nName="+label.replace("\\","\\\\")+
                "\nPath="+dir+"\nExec="+dir+"/bin/xdesx-window "+i+" "+dir+"/titles/"+i+"\nIcon="+launcherIcon(r,activity)+"\n");
            menu.append("<Filename>").append(name).append("</Filename>");
        }
        int mac=apps.size();
        apps.add(new ComponentName(context,XdesxMacActivity.class).flattenToString());
        text("titles/"+mac,"Mac 屏幕共享");
        text("data/applications/xdesx-mac.desktop","[Desktop Entry]\nType=Application\nName=连接 Mac\nPath="+dir+
            "\nExec="+dir+"/bin/xdesx-window "+mac+" "+dir+"/titles/"+mac+"\n");
        menu.append("<Filename>xdesx-mac.desktop</Filename>");
        text("config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml",
            "<channel name='xfwm4' version='1.0'><property name='general' type='empty'><property name='box_resize' type='bool' value='true'/></property></channel>");
        text("config/gtk-3.0/settings.ini","[Settings]\ngtk-menu-images=true\n");
        text("android.menu",menu.append("</Include></Menu>").toString());
        try(InputStream in=context.getAssets().open("xdesx/panel.xml"); BufferedReader r=new BufferedReader(new InputStreamReader(in))) {
            StringBuilder xml=new StringBuilder(); String line; while((line=r.readLine())!=null) xml.append(line);
            text("config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml",xml.toString().replace("@MENU@",dir+"/android.menu"));
        }
        text("fonts.conf","<fontconfig><dir>/system/fonts</dir><cachedir>"+dir+"/cache/fonts</cachedir></fontconfig>");
        text("dbus.conf","<busconfig><type>session</type><listen>unix:path="+dir+"/run/bus</listen><auth>EXTERNAL</auth>"+
            "<servicedir>"+dir+"/services</servicedir><policy context=\"default\"><allow send_destination=\"*\"/><allow receive_sender=\"*\"/><allow own=\"*\"/></policy></busconfig>");
        text("services/org.xfce.Xfconf.service","[D-BUS Service]\nName=org.xfce.Xfconf\nExec="+dir+"/lib/xfce4/xfconf/xfconfd\n");
    }
    void run(Menu menu) throws Exception {
        unpack(); configure();
        String dir=prefix.getPath();
        ProcessBuilder b=new ProcessBuilder("/system/bin/sh",path("session.sh").getPath()).directory(prefix);
        Map<String,String> env=b.environment();
        env.put("PATH",dir+"/bin:/system/bin"); env.put("HOME",dir); env.put("TMPDIR",dir+"/tmp");
        env.put("LD_LIBRARY_PATH",dir+"/lib"); env.put("XDG_CONFIG_HOME",dir+"/config"); env.put("XDG_CONFIG_DIRS",dir+"/etc/xdg");
        env.put("XDG_DATA_HOME",dir+"/data"); env.put("XDG_DATA_DIRS",dir+"/share"); env.put("XDG_RUNTIME_DIR",dir+"/run");
        env.put("XDG_CACHE_HOME",dir+"/cache"); env.put("FONTCONFIG_FILE",dir+"/fonts.conf");
        env.put("XKB_CONFIG_ROOT",dir+"/share/xkeyboard-config-2"); env.put("DBUS_SESSION_BUS_ADDRESS","unix:path="+dir+"/run/bus");
        env.put("DISPLAY",":19"); env.put("GIO_USE_VFS","local"); env.put("GDK_BACKEND","x11"); env.put("NO_AT_BRIDGE","1");
        env.put("GSETTINGS_SCHEMA_DIR",dir+"/share/glib-2.0/schemas"); env.put("CLASSPATH",context.getApplicationInfo().sourceDir);
        env.put("TERMUX_X11_GLAMOR","1"); env.put("XDESX_MENU",socketName);
        File logs=context.getExternalFilesDir(null);
        b.redirectErrorStream(true).redirectOutput(logs==null?path("session.log"):new File(logs,"xdesx-session.log"));
        synchronized(this) {
            if (closed) return;
            socket=new LocalServerSocket(socketName);
            session=b.start();
        }
        try {
            while (true) {
                try(LocalSocket peer=socket.accept()) {
                    if(peer.getPeerCredentials().getUid()!=Process.myUid()) continue;
                    peer.setSoTimeout(2000);
                    String line=new BufferedReader(new InputStreamReader(peer.getInputStream(),"UTF-8")).readLine();
                    if(line==null || !line.matches("[0-9a-f]{32} [0-9]{1,6}")) continue;
                    int i=Integer.parseInt(line.substring(33));
                    if(i<apps.size()) menu.selected(line.substring(0,32),apps.get(i));
                }
            }
        } finally { close(); }
    }
    synchronized void close() {
        closed=true;
        if(socket!=null) try { socket.close(); } catch(IOException ignored) {}
        if(session!=null) try { session.getOutputStream().close(); } catch(IOException ignored) {}
    }
}
