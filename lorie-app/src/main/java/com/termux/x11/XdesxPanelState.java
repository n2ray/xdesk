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
    int mouseSpeed,padSpeed,limit,opened,returnSequence;
    boolean reverse,remember;
    String layoutKey="default";
    int language, appearance;
    int scale = 100, render = 100;
    private boolean closed;

    XdesxPanelState(Context context) {
        this.context = context;
        dir = new File(context.getFilesDir(), "xd/run");
        dir.mkdirs();
        prefs = context.getSharedPreferences("xdesx-panel", Context.MODE_PRIVATE);
        language=prefs.getInt("language",0);
        appearance=prefs.getInt("appearance",0);
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
        events.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        context.registerReceiver(receiver, events);
        network.registerDefaultNetworkCallback(callback, main);
        context.getContentResolver().registerContentObserver(android.provider.Settings.Secure.getUriFor(
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD), false, imeObserver);
        context.getContentResolver().registerContentObserver(android.provider.Settings.Secure.getUriFor(
                android.provider.Settings.Secure.ENABLED_INPUT_METHODS), false, imeObserver);
        refreshIme();
    }
    private String imeName = "";
    private final android.database.ContentObserver imeObserver = new android.database.ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { refreshIme(); publish(); }
    };
    private void refreshIme() {
        String id = android.provider.Settings.Secure.getString(context.getContentResolver(),
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
        imeName = "";
        android.view.inputmethod.InputMethodManager manager =
                context.getSystemService(android.view.inputmethod.InputMethodManager.class);
        StringBuilder rows = new StringBuilder();
        for (android.view.inputmethod.InputMethodInfo info : manager.getEnabledInputMethodList()) {
            String label = info.loadLabel(context.getPackageManager()).toString().replaceAll("[\\t\\r\\n]", " ");
            String key = imeKey(info.getId());
            boolean selected = info.getId().equals(id);
            if (selected) imeName = label;
            rows.append(key).append('\t').append(selected ? "1" : "0").append('\t').append(label).append('\n');
        }
        try {
            Path tmp = new File(dir,"ime-list.tmp").toPath();
            Files.write(tmp,rows.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp,new File(dir,"ime-list").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { Log.w("XDesX","input method list",e); }
    }

    static String imeKey(String id) {
        return java.util.UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }
    void screen(Display d) {
        profile = ScreenProfiles.wiredKey(d);
        layoutKey=java.util.UUID.nameUUIDFromBytes(profile.getBytes(StandardCharsets.UTF_8)).toString();
        scale = prefs.getInt(profile + "/scale", 133);
        render = prefs.getInt(profile + "/render", 100);
        publish();
    }
    void saveOptions() {
        prefs.edit().putInt("language",language).putInt("appearance",appearance).putInt("mouse",mouseSpeed).putInt("pad",padSpeed).putInt("limit",limit)
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
    Locale locale() { return language==1 ? Locale.ENGLISH : language==2 ? Locale.SIMPLIFIED_CHINESE : language==3 ? Locale.TRADITIONAL_CHINESE : android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0); }
    boolean isDark() { return appearance==2 || (appearance==0 && (context.getResources().getConfiguration().uiMode & 48)==32); }

    void publish() {
        if (closed) return;
        Date now = new Date();
        Locale locale = locale();
        boolean traditional = locale.getScript().equals("Hant") || java.util.Arrays.asList("TW","HK","MO").contains(locale.getCountry());
        int effectiveLanguage = locale.getLanguage().equals("zh") ? (traditional?3:2) : 1;
        boolean dark = isDark();
        String pattern = DateFormat.getBestDateTimePattern(locale,"MMMdEEE");
        String date = new java.text.SimpleDateFormat(pattern,locale).format(now);
        String tooltip=connection()+" · "+battery;
        if(effectiveLanguage==1)tooltip=tooltip.replace("移动网络","Mobile network").replace("以太网","Ethernet").replace("网络","Network").replace("离线","Offline").replace("未验证","Unverified").replace("充电","Charging").replace("电量未知","Battery unavailable").replace("电量","Battery");
        if(effectiveLanguage==3)tooltip=tooltip.replace("移动网络","行動網路").replace("以太网","乙太網路").replace("网络","網路").replace("离线","離線").replace("未验证","未驗證").replace("电","電");
        String state = scale+"\n"+render+"\n"+connection()+"     "+battery+"     "+date+"  "+
            DateFormat.getTimeFormat(context).format(now)+"\n"+mouseSpeed+" "+padSpeed+" "+limit+" "+(reverse?1:0)+" "+(remember?1:0)+" "+opened+" "+returnSequence+" "+language+" "+effectiveLanguage+" "+appearance+" "+(dark?1:0)+"\n"+layoutKey+"\n"+tooltip+"\n"+imeName;
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
        context.getContentResolver().unregisterContentObserver(imeObserver);
        context.unregisterReceiver(receiver);
        network.unregisterNetworkCallback(callback);
    }
}
