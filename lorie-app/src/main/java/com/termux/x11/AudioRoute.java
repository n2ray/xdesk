package com.termux.x11;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import com.termux.x11.app.R;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 用户选的声音输出，进程单例。Shizuku 在运行时，所有声音（XDesk 自己的桌面声音也算）都靠注入服务设的系统路由
 * （AudioRouter），会话结束时清除。没有 Shizuku 时只有 AudioPlayer 的 setPreferredDevice，这只是尽力而为：
 * 实测它没法在接着 HDMI 时把声音拉回手机扬声器。
 */
final class AudioRoute {
    interface Listener {
        void onOutputsChanged();
        void onAudioMessage(String message);
    }

    static final class Output {
        final int type;
        final String address, label, shortLabel, key;

        Output(Context c, AudioDeviceInfo d) {
            type = d.getType();
            address = d.getAddress();
            key = type + "|" + address;
            shortLabel = c.getString(shortName(type));
            CharSequence product = d.getProductName();
            boolean named = product != null && product.length() > 0 && type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
            label = named ? c.getString(R.string.xd_audio_named, longName(c, type), product) : longName(c, type);
        }
    }

    /** 系统默认：不指定任何设备。 */
    static final String KEY_SYSTEM = "";
    private static final String PREFS = "audio_route", PREF_KEY = "device";
    /** 老版本的设置：0 手机，1 外接，2 系统。 */
    private static final int OLD_PHONE = 0, OLD_EXTERNAL = 1;
    private static final long PENDING_TIMEOUT_MS = 15000;

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
    private String appliedKey;
    private boolean wasMissing;
    /** 注入服务正在启动。这期间不对音轨指定设备，否则会白试几次再报“系统不听”。 */
    private boolean pending;
    private final Runnable pendingOver = () -> pending = false;
    /** 注入服务设路由失败过：这个系统版本用不了，退回 setPreferredDevice。 */
    private boolean systemFailed;
    /** 用的是车机：声音走系统默认的媒体输出，Android Auto 把它送到车里。存的选择不变，回到外接屏时再按它来。 */
    /** On the car or a wireless display: sound goes where that display is, the chosen output does not apply. */
    private boolean car, wireless;

    private final Context context;

    private AudioRoute(Context c) {
        context = c;
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

    /** 老版本明确选过的，算作这个设置的值。 */
    void migrateOldChoice(int old) {
        if (prefs.contains(PREF_KEY)) return;
        String key = old == OLD_PHONE ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER + "|" : old == OLD_EXTERNAL ? AudioDeviceInfo.TYPE_HDMI + "|" : KEY_SYSTEM;
        prefs.edit().putString(PREF_KEY, key).apply();
    }

    void setPending(boolean p) {
        pending = p;
        ui.removeCallbacks(pendingOver);
        if (p) ui.postDelayed(pendingOver, PENDING_TIMEOUT_MS);
    }

    private void devicesChanged() {
        boolean missing = isMissing();
        if (missing && !wasMissing && listener != null) listener.onAudioMessage(context.getString(R.string.xd_audio_missing));
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

    private static int shortName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return R.string.xd_audio_short_phone;
            case AudioDeviceInfo.TYPE_HDMI:
            case AudioDeviceInfo.TYPE_HDMI_ARC:
            case AudioDeviceInfo.TYPE_HDMI_EARC: return R.string.xd_audio_short_hdmi;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_HEARING_AID: return R.string.xd_audio_short_bluetooth;
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
            case AudioDeviceInfo.TYPE_USB_HEADSET: return R.string.xd_audio_short_usb;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return R.string.xd_audio_short_wired;
            default: return R.string.xd_audio_short_other;
        }
    }

    private static String longName(Context c, int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return c.getString(R.string.xd_audio_phone);
            case AudioDeviceInfo.TYPE_HDMI:
            case AudioDeviceInfo.TYPE_HDMI_ARC:
            case AudioDeviceInfo.TYPE_HDMI_EARC: return c.getString(R.string.xd_audio_hdmi);
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_HEARING_AID: return c.getString(R.string.xd_audio_bluetooth);
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
            case AudioDeviceInfo.TYPE_USB_HEADSET: return c.getString(R.string.xd_audio_usb);
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return c.getString(R.string.xd_audio_wired);
            default: return c.getString(R.string.xd_audio_other, type);
        }
    }

    /** 现在能选的设备，同一个设备只出现一次。 */
    List<Output> outputs() {
        ArrayList<Output> out = new ArrayList<>();
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (!d.isSink() || !usable(d.getType())) continue;
            Output o = new Output(context, d);
            boolean seen = false;
            for (Output e : out) seen |= e.key.equals(o.key);
            if (!seen) out.add(o);
        }
        return out;
    }

    /** 所有模式共用这一个设置。没选过就是系统默认。 */
    String selectedKey() {
        return prefs.getString(PREF_KEY, KEY_SYSTEM);
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

    /** 在车机或无线显示上不按选择来，用系统默认。 */
    private Output activeOutput() {
        return isRemote() ? null : selectedOutput();
    }

    void setCar(boolean c) {
        setRemote(c, wireless);
    }

    /** The wireless display takes the whole system output, see WirelessDisplay. */
    void setWireless(boolean w) {
        setRemote(car, w);
    }

    private void setRemote(boolean c, boolean w) {
        if (c == car && w == wireless) return;
        car = c;
        wireless = w;
        wasMissing = isMissing();
        appliedKey = null;
        syncSystem();
        if (listener != null) listener.onOutputsChanged();
    }

    boolean isRemote() {
        return car || wireless;
    }

    boolean isWireless() {
        return wireless;
    }

    /** 选了设备但它现在不在。 */
    boolean isMissing() {
        return !isRemote() && !selectedKey().isEmpty() && selectedOutput() == null;
    }

    String shortLabel() {
        if (wireless) return context.getString(R.string.xd_audio_short_wireless);
        if (car) return context.getString(R.string.xd_audio_short_car);
        Output o = selectedOutput();
        return o != null ? o.shortLabel : context.getString(R.string.xd_audio_short_system);
    }

    /** 给 AudioPlayer：null 表示走系统默认。 */
    AudioDeviceInfo playerDevice() {
        Output o = activeOutput();
        if (o == null) return null;
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == o.type && d.getAddress().equals(o.address)) return d;
        }
        return null;
    }

    void select(String key) {
        prefs.edit().putString(PREF_KEY, key).apply();
        wasMissing = isMissing();
        appliedKey = null;  // 再选一次也要真的设一次，比如清掉别的进程留下的
        syncSystem();
        if (listener != null) listener.onOutputsChanged();
    }

    /** 注入服务可用了，能设系统路由。 */
    void attachInjector(IInjector i) {
        injector = i;
        appliedKey = null;
        systemFailed = false;
        setPending(false);
        syncSystem();
    }

    /** 注入服务没了或要结束。清除由服务自己做，这样它崩溃或被杀时也有机会清。 */
    void detachInjector() {
        injector = null;
        appliedKey = null;
        setPending(false);
    }

    /** 系统路由在管声音，不用再对单个音轨指定设备。 */
    boolean coversSystem() {
        return (injector != null || pending) && !systemFailed;
    }

    /** AudioPlayer 试了几次系统都不理。从播放线程调用。 */
    void reportNotHonoured() {
        ui.post(() -> {
            if (listener != null) listener.onAudioMessage(context.getString(R.string.xd_audio_not_honoured));
        });
    }

    /**
     * 不分模式：注入服务在就照设置来，切换桌面和应用时它一直在，设置也就一直有效。选了系统默认或选的设备不在了就清除，
     * 这也能清掉以前的进程留下的。会话结束时注入服务退出前自己清（InjectorService.shutdown），下次会话再设。
     */
    private void syncSystem() {
        final IInjector i = injector;
        if (i == null) return;
        // 车机上清掉以前设的路由，媒体声音才会走 Android Auto
        final Output o = activeOutput();
        final String want = o != null ? o.key : KEY_SYSTEM;
        if (want.equals(appliedKey)) return;
        appliedKey = want;
        worker.execute(() -> {
            String error;
            try {
                error = i.setAudioOutput(o != null ? o.type : -1, o != null ? o.address : "");
            } catch (RemoteException e) {
                error = context.getString(R.string.xd_injector_gone);
            }
            final boolean failed = error != null;
            final String message = context.getString(R.string.xd_audio_cannot_switch, ShellText.localize(context, error));
            ui.post(() -> {
                systemFailed = failed;
                if (failed && listener != null) listener.onAudioMessage(message);
            });
        });
    }
}
