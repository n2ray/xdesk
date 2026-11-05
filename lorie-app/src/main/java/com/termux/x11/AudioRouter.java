package com.termux.x11;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Prefers an output for all media on the system, for apps that XDesk does not play itself. Lives in the shell-user
 * process, which holds MODIFY_AUDIO_ROUTING. Everything is hidden API with names that changed between Android versions,
 * so each is looked up at run time and a mismatch comes back as an error message, not a crash. The preference probably
 * does not survive a reboot, and it is cleared when XDesk's session ends.
 */
final class AudioRouter {
    private static final String TAG = "XDeskInjector";
    /** The id of the media strategy on the phone this was first tested on, if the system does not say. */
    private static final int STRATEGY_MEDIA_FALLBACK = 5;
    private static final int DEVICE_ROLE_PREFERRED_FALLBACK = 1;
    private static final int ROLE_OUTPUT = 2;
    private static final int RESULT_OK = 0;

    private boolean applied;

    private static int mediaStrategy() {
        try {
            Class<?> c = Class.forName("android.media.audiopolicy.AudioProductStrategy");
            AudioAttributes media = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
            List<?> all = (List<?>) c.getMethod("getAudioProductStrategies").invoke(null);
            Method supports = c.getMethod("supportsAudioAttributes", AudioAttributes.class);
            Method id = c.getMethod("getId");
            for (Object s : all) if ((Boolean) supports.invoke(s, media)) return (Integer) id.invoke(s);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.i(TAG, "no strategy lookup: " + e);
        }
        return STRATEGY_MEDIA_FALLBACK;
    }

    private static int preferredRole(Class<?> audioSystem) {
        try {
            return audioSystem.getField("DEVICE_ROLE_PREFERRED").getInt(null);
        } catch (ReflectiveOperationException e) {
            return DEVICE_ROLE_PREFERRED_FALLBACK;
        }
    }

    /** An app sees anonymised Bluetooth addresses, so find the real device here, by type and then by address. */
    private static Object attributes(int type, String address) throws ReflectiveOperationException {
        Class<?> c = Class.forName("android.media.AudioDeviceAttributes");
        AudioDeviceInfo sameType = null;
        try {
            Method m = android.media.AudioManager.class.getMethod("getDevicesStatic", int.class);
            AudioDeviceInfo[] devices = (AudioDeviceInfo[]) m.invoke(null, android.media.AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo d : devices) {
                if (d.getType() != type) continue;
                if (d.getAddress().equals(address)) {
                    sameType = d;
                    break;
                }
                if (sameType == null) sameType = d;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.i(TAG, "no device list: " + e);
        }
        if (sameType != null) return c.getConstructor(AudioDeviceInfo.class).newInstance(sameType);
        return c.getConstructor(int.class, int.class, String.class).newInstance(ROLE_OUTPUT, type, address);
    }

    /** Returns null, or what went wrong. */
    String set(int type, String address) {
        try {
            Class<?> system = Class.forName("android.media.AudioSystem");
            List<Object> devices = new ArrayList<>();
            devices.add(attributes(type, address));
            Method m = system.getMethod("setDevicesRoleForStrategy", int.class, int.class, List.class);
            int result = (Integer) m.invoke(null, mediaStrategy(), preferredRole(system), devices);
            if (result != RESULT_OK) return "setDevicesRoleForStrategy 返回 " + result;
            applied = true;
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.w(TAG, "audio route", e);
            return "这个系统版本不支持（" + e + "）";
        }
    }

    /** Returns null, or what went wrong. Harmless when nothing was set. */
    String clear() {
        if (!applied) return null;
        applied = false;
        try {
            Class<?> system = Class.forName("android.media.AudioSystem");
            int strategy = mediaStrategy(), role = preferredRole(system);
            Integer result = null;
            // The clearing call has had different names and arguments, take what this version has
            for (String name : new String[]{"clearDevicesRoleForStrategy", "removeDevicesRoleForStrategy"}) {
                try {
                    result = (Integer) system.getMethod(name, int.class, int.class).invoke(null, strategy, role);
                    break;
                } catch (NoSuchMethodException ignored) { }
            }
            if (result == null) return "这个系统版本没有清除声音路由的方法";
            return result == RESULT_OK ? null : "清除声音路由返回 " + result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.w(TAG, "audio route clear", e);
            return String.valueOf(e);
        }
    }
}
