package com.termux.x11;

import android.content.*;
import android.net.*;
import android.os.*;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Display;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Date;
import java.util.Locale;

/** Android owns the display profile and status. The Bionic panel watches one private file. */
final class XdesxPanelState {
    private final Context context;
    private final ConnectivityManager network;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final File dir;
    private String profile = "";
    private String battery = "电量未知", previous = "";
    int mouseSpeed,padSpeed,limit,opened;
    boolean reverse,remember;
    String layoutKey="default";
    int scale = 100, render = 100;
    private boolean closed;

    XdesxPanelState(Context context) {
        this.context = context;
        dir = new File(context.getFilesDir(), "xd/run");
        dir.mkdirs();
        prefs = context.getSharedPreferences("xdesx-panel", Context.MODE_PRIVATE);
        mouseSpeed=prefs.getInt("mouse",100);
        padSpeed=prefs.getInt("pad",100);
        limit=prefs.getInt("limit",3);
        reverse=prefs.getBoolean("reverse",false);
        remember=prefs.getBoolean("remember",true);
        network = context.getSystemService(ConnectivityManager.class);
        IntentFilter events = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        events.addAction(Intent.ACTION_TIME_TICK);
        events.addAction(Intent.ACTION_TIME_CHANGED);
        events.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        events.addAction(Intent.ACTION_LOCALE_CHANGED);
        context.registerReceiver(receiver, events);
        network.registerDefaultNetworkCallback(callback, main);
    }
    void screen(Display d) {
        profile = ScreenProfiles.wiredKey(d);
        layoutKey=java.util.UUID.nameUUIDFromBytes(profile.getBytes(StandardCharsets.UTF_8)).toString();
        scale = prefs.getInt(profile + "/scale", 100);
        render = prefs.getInt(profile + "/render", 100);
        publish();
    }
    void saveOptions() {
        prefs.edit().putInt("mouse",mouseSpeed).putInt("pad",padSpeed).putInt("limit",limit)
            .putBoolean("reverse",reverse).putBoolean("remember",remember).apply();
        publish();
    }
    void save() {
        prefs.edit().putInt(profile + "/scale", scale).putInt(profile + "/render", render).apply();
        publish();
    }
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(i.getAction())) {
                int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
                int max = i.getIntExtra(BatteryManager.EXTRA_SCALE,100);
                boolean charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED,0) != 0;
                battery = level < 0 || max <= 0 ? "电量未知" :
                    (charging ? "充电 " : "电量 ") + Math.round(100f*level/max) + "%";
            }
            publish();
        }
    };
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { publish(); }
        @Override public void onLost(Network n) { publish(); }
    };
    private String connection() {
        NetworkCapabilities n = network.getNetworkCapabilities(network.getActiveNetwork());
        if (n == null) return "离线";
        String name = n.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" :
            n.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "以太网" :
            n.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "移动网络" :
            n.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN" : "网络";
        return n.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? name : name+" · 未验证";
    }
    void publish() {
        if (closed) return;
        Date now = new Date();
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(),"MMMdEEE");
        String date = DateFormat.format(pattern, now).toString();
        String state = scale+"\n"+render+"\n"+connection()+"     "+battery+"     "+date+"  "+
            DateFormat.getTimeFormat(context).format(now)+"\n"+mouseSpeed+" "+padSpeed+" "+limit+" "+(reverse?1:0)+" "+(remember?1:0)+" "+opened+"\n"+layoutKey;
        if (state.equals(previous)) return;
        Path tmp = new File(dir,"panel-state.tmp").toPath();
        try {
            Files.write(tmp,state.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp,new File(dir,"panel-state").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            previous = state;
        } catch(IOException e) { Log.w("XDesX","panel status",e); }
    }
    void close() {
        closed = true;
        context.unregisterReceiver(receiver);
        network.unregisterNetworkCallback(callback);
    }
}
