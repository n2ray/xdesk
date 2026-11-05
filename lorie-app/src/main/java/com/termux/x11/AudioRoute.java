package com.termux.x11;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 用户选的声音输出，进程单例。Termux 桌面的声音由 AudioPlayer 用 setPreferredDevice 指定，不需要 Shizuku；
 * 安卓应用和系统其他声音要由注入服务设系统路由（AudioRouter），会话结束时清除。
 */
final class AudioRoute {
    interface Listener {
        void onOutputsChanged();
        void onAudioMessage(String message);
    }

    static final class Output {
        final int type;
        final String address, label, shortLabel, key;

        Output(AudioDeviceInfo d) {
            type = d.getType();
            address = d.getAddress();
            key = type + "|" + address;
            shortLabel = shortName(type);
            CharSequence product = d.getProductName();
            boolean named = product != null && product.length() > 0 && type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
            label = longName(type) + (named ? "：" + product : "");
        }
    }

    /** 系统默认：不指定任何设备。 */
    static final String KEY_SYSTEM = "";
    private static final String PREFS = "audio_route", PREF_KEY = "device";
    /** 老版本的设置：0 手机，1 外接，2 系统。 */
    private static final int OLD_PHONE = 0, OLD_EXTERNAL = 1;

    private static AudioRoute instance;

    static synchronized AudioRoute get(Context c) {
        if (instance == null) instance = new AudioRoute(c.getApplicationContext());
        return instance;
    }

    private final AudioManager audio;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Listener listener;
    private IInjector injector;
    /** 没选过时桌面按老设置走，系统路由不动。 */
    private int oldChoice = OLD_PHONE;
    private String appliedKey;
    private boolean wasMissing;

    private AudioRoute(Context c) {
        audio = c.getSystemService(AudioManager.class);
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        audio.registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { devicesChanged(); }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { devicesChanged(); }
        }, ui);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setOldChoice(int old) {
        oldChoice = old;
    }

    private void devicesChanged() {
        boolean missing = isMissing();
        if (missing && !wasMissing && listener != null) listener.onAudioMessage("选的声音输出断开了，暂时走系统默认");
        wasMissing = missing;
        syncSystem();
        if (listener != null) listener.onOutputsChanged();
    }

    private static boolean usable(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
            case AudioDeviceInfo.TYPE_TELEPHONY:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_REMOTE_SUBMIX:
            case AudioDeviceInfo.TYPE_FM:
            case AudioDeviceInfo.TYPE_IP:
            case AudioDeviceInfo.TYPE_BUS:
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE:
                return false;
            default:
                return true;
        }
    }

    private static String shortName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "手机";
            case AudioDeviceInfo.TYPE_HDMI:
            case AudioDeviceInfo.TYPE_HDMI_ARC:
            case AudioDeviceInfo.TYPE_HDMI_EARC: return "外接";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_HEARING_AID: return "蓝牙";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "耳机";
            default: return "其他";
        }
    }

    private static String longName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "手机扬声器";
            case AudioDeviceInfo.TYPE_HDMI:
            case AudioDeviceInfo.TYPE_HDMI_ARC:
            case AudioDeviceInfo.TYPE_HDMI_EARC: return "外接屏（HDMI）";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_HEARING_AID: return "蓝牙";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "有线耳机";
            default: return "其他输出（类型 " + type + "）";
        }
    }

    /** 现在能选的设备，同一个设备只出现一次。 */
    List<Output> outputs() {
        ArrayList<Output> out = new ArrayList<>();
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (!d.isSink() || !usable(d.getType())) continue;
            Output o = new Output(d);
            boolean seen = false;
            for (Output e : out) seen |= e.key.equals(o.key);
            if (!seen) out.add(o);
        }
        return out;
    }

    /** 用户明确选过的；null 是没选过。 */
    private String chosenKey() {
        return prefs.getString(PREF_KEY, null);
    }

    /** 对话框里该勾哪项。 */
    String selectedKey() {
        String k = chosenKey();
        if (k != null) return k;
        if (oldChoice == OLD_PHONE) return AudioDeviceInfo.TYPE_BUILTIN_SPEAKER + "|";
        if (oldChoice == OLD_EXTERNAL) return AudioDeviceInfo.TYPE_HDMI + "|";
        return KEY_SYSTEM;
    }

    private static int typeOf(String key) {
        return Integer.parseInt(key.substring(0, key.indexOf('|')));
    }

    /** 选的设备，地址变了就按类型找；不在了返回 null。 */
    private <T> T match(String key, List<T> items, Matcher<T> m) {
        T sameType = null;
        for (T d : items) {
            if (m.key(d).equals(key)) return d;
            if (sameType == null && m.type(d) == typeOf(key)) sameType = d;
        }
        return sameType;
    }

    private interface Matcher<T> {
        String key(T item);
        int type(T item);
    }

    private static final Matcher<Output> OUTPUTS = new Matcher<Output>() {
        @Override public String key(Output o) { return o.key; }
        @Override public int type(Output o) { return o.type; }
    };

    private Output selectedOutput() {
        String k = selectedKey();
        return k.isEmpty() ? null : match(k, outputs(), OUTPUTS);
    }

    /** 选了设备但它现在不在。 */
    boolean isMissing() {
        return !selectedKey().isEmpty() && selectedOutput() == null;
    }

    String shortLabel() {
        Output o = selectedOutput();
        return o != null ? o.shortLabel : "系统";
    }

    /** 给 AudioPlayer：null 表示走系统默认。 */
    AudioDeviceInfo playerDevice() {
        Output o = selectedOutput();
        if (o == null) return null;
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == o.type && d.getAddress().equals(o.address)) return d;
        }
        return null;
    }

    void select(String key) {
        prefs.edit().putString(PREF_KEY, key).apply();
        wasMissing = isMissing();
        syncSystem();
        if (listener != null) listener.onOutputsChanged();
    }

    /** 注入服务可用了，能设系统路由。 */
    void attachInjector(IInjector i) {
        injector = i;
        appliedKey = null;
        syncSystem();
    }

    /** 注入服务没了或要结束。清除由服务自己做，这样它崩溃或被杀时也有机会清。 */
    void detachInjector() {
        injector = null;
        appliedKey = null;
    }

    boolean coversSystem() {
        return injector != null;
    }

    /** 只有用户明确选过才动系统路由；没选过的人不会发现自己的蓝牙耳机被改掉。 */
    private void syncSystem() {
        final IInjector i = injector;
        final String chosen = chosenKey();
        if (i == null || chosen == null) return;
        final Output o = chosen.isEmpty() ? null : selectedOutput();
        final String want = o != null ? o.key : KEY_SYSTEM;
        if (want.equals(appliedKey)) return;
        appliedKey = want;
        worker.execute(() -> {
            String error;
            try {
                error = i.setAudioOutput(o != null ? o.type : -1, o != null ? o.address : "");
            } catch (RemoteException e) {
                error = "注入服务断开了";
            }
            if (error == null) return;
            final String message = "无法切换系统声音：" + error;
            ui.post(() -> {
                if (listener != null) listener.onAudioMessage(message);
            });
        });
    }
}
