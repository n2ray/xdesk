package com.termux.x11.mac;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The Macs XDesk has paired with: where to reach each (host:port, as typed) and the pin of its key. A pin is a
 * hash of the Mac's public key, not a secret; XDesk's own key stays in the Android key store.
 */
public final class MacStore {
    private static final String PREFS = "xdesk_macs", KEY = "macs";

    public static final class Mac {
        public final String id;
        public final String name;
        public final String host;
        public final int port;
        final byte[] pin;
        /** Resize the streamed Mac window to the screen (DISPLAY.md §1.3, off by default). */
        public final boolean fit;
        /** The keyboard is a Mac keyboard: no Ctrl↔⌘ swap (INPUT.md §5.5). */
        public final boolean macKeyboard;
        /**
         * A physical keyboard on the phone types through the phone's input method (text, composed on the phone)
         * instead of as keys the Mac's own input method composes (INPUT.md §5.2, off by default).
         */
        public final boolean phoneIme;

        Mac(String id, String name, String host, int port, byte[] pin, boolean fit, boolean macKeyboard, boolean phoneIme) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.port = port;
            this.pin = pin;
            this.fit = fit;
            this.macKeyboard = macKeyboard;
            this.phoneIme = phoneIme;
        }

        public String address() {
            return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("name", name).put("host", host).put("port", port)
                    .put("pin", Base64.encodeToString(pin, Base64.NO_WRAP)).put("fit", fit).put("macKeyboard", macKeyboard)
                    .put("phoneIme", phoneIme);
        }

        static Mac of(JSONObject o) throws JSONException {
            return new Mac(o.getString("id"), o.optString("name", ""), o.getString("host"), o.getInt("port"),
                    Base64.decode(o.getString("pin"), Base64.NO_WRAP), o.optBoolean("fit", false), o.optBoolean("macKeyboard", false),
                    o.optBoolean("phoneIme", false));
        }

        Mac with(String name, boolean fit, boolean macKeyboard, boolean phoneIme) {
            return new Mac(id, name, host, port, pin, fit, macKeyboard, phoneIme);
        }
    }

    private MacStore() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized List<Mac> all(Context c) {
        ArrayList<Mac> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Mac.of(a.getJSONObject(i)));
        } catch (JSONException e) {
            android.util.Log.w("XDeskMac", "saved Macs unreadable", e);
        }
        return out;
    }

    public static Mac find(Context c, String id) {
        for (Mac m : all(c)) if (m.id.equals(id)) return m;
        return null;
    }

    private static synchronized void save(Context c, List<Mac> macs) {
        JSONArray a = new JSONArray();
        try {
            for (Mac m : macs) a.put(m.json());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        prefs(c).edit().putString(KEY, a.toString()).apply();
    }

    /** Pairing with a Mac already saved (same id) replaces it: its address or key may have changed. */
    static synchronized Mac add(Context c, SillTls.Paired p, String host, int port) {
        List<Mac> macs = all(c);
        Mac old = null;
        for (int i = macs.size() - 1; i >= 0; i--) if (macs.get(i).id.equals(p.macId)) old = macs.remove(i);
        String name = p.name.isEmpty() ? (old != null ? old.name : "Mac") : p.name;
        Mac m = new Mac(p.macId, name, host, port, p.pin, old != null && old.fit, old != null && old.macKeyboard,
                old != null && old.phoneIme);
        macs.add(m);
        save(c, macs);
        return m;
    }

    public static synchronized void remove(Context c, String id) {
        List<Mac> macs = all(c);
        for (int i = macs.size() - 1; i >= 0; i--) if (macs.get(i).id.equals(id)) macs.remove(i);
        save(c, macs);
        prefs(c).edit().remove(STREAM + id).apply();
    }

    public static synchronized void setOptions(Context c, String id, boolean fit, boolean macKeyboard, boolean phoneIme) {
        List<Mac> macs = all(c);
        for (int i = 0; i < macs.size(); i++)
            if (macs.get(i).id.equals(id)) macs.set(i, macs.get(i).with(macs.get(i).name, fit, macKeyboard, phoneIme));
        save(c, macs);
    }

    /** The Mac's name from its window list, when it changed. */
    static synchronized void rename(Context c, String id, String name) {
        List<Mac> macs = all(c);
        boolean changed = false;
        for (int i = 0; i < macs.size(); i++) {
            Mac m = macs.get(i);
            if (m.id.equals(id) && !name.isEmpty() && !name.equals(m.name)) {
                macs.set(i, m.with(name, m.fit, m.macKeyboard, m.phoneIme));
                changed = true;
            }
        }
        if (changed) save(c, macs);
    }

    // --- picture and sound (MacStreamSettings) ---

    private static final String STREAM = "stream_";

    /**
     * What this XDesk keeps per Mac for its stream. Only `fpsCap`, `sound` and `muted` are this device's own
     * settings; the Mac's (quality, resolution, frame rate limit, ...) are the Mac's to keep, and `host` is just the
     * last state it sent, so the settings page has something to show while the Mac is not connected. Kept beside
     * the list of Macs, not in it: the Mac's state comes in from the connection's thread at any time.
     */
    public static final class Stream {
        /** The frame rate this device asks for at most (its viewport's fps): 0 for the screen's own, else 30 or 60. */
        public final int fpsCap;
        /** Play the Mac's sound here (the hello lists a codec). On by default (Sall's "experimentalAudio" is off): the Mac's own Send Audio still decides. */
        public final boolean sound;
        /** Muted here (Sall's soundMuted): sound still streams, this device says nothing; off by default. */
        public final boolean muted;
        /** Lower the quality by itself while the Mac's link report stays behind (AutoQuality); on by default. */
        public final boolean autoQuality;
        /** HostSettings.settingsJson of the last session, "" before the first. */
        public final String host;

        Stream(int fpsCap, boolean sound, boolean muted, boolean autoQuality, String host) {
            this.fpsCap = fpsCap;
            this.sound = sound;
            this.muted = muted;
            this.autoQuality = autoQuality;
            this.host = host;
        }
    }

    /** The key of Stream.sound. Not "sound": that was saved as off with every settings change while off was the default, so it
     *  says nothing about a choice; the switch is on for everyone from here, and a choice is kept under this key. */
    private static final String SOUND = "playSound";

    public static Stream stream(Context c, String id) {
        try {
            JSONObject o = new JSONObject(prefs(c).getString(STREAM + id, "{}"));
            return new Stream(o.optInt("fpsCap", 0), o.optBoolean(SOUND, true), o.optBoolean("muted", false),
                    o.optBoolean("autoQuality", true), o.optString("host", ""));
        } catch (JSONException e) {
            return new Stream(0, true, false, true, "");
        }
    }

    private static void saveStream(Context c, String id, Stream s) {
        try {
            prefs(c).edit().putString(STREAM + id, new JSONObject().put("fpsCap", s.fpsCap).put(SOUND, s.sound)
                    .put("muted", s.muted).put("autoQuality", s.autoQuality).put("host", s.host).toString()).apply();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static synchronized void setStreamOptions(Context c, String id, int fpsCap, boolean sound, boolean muted) {
        saveStream(c, id, new Stream(fpsCap, sound, muted, stream(c, id).autoQuality, stream(c, id).host));
    }

    /** The auto quality switch (AutoQuality): this device's own, so it only changes what XDesk does. */
    public static synchronized void setAutoQuality(Context c, String id, boolean autoQuality) {
        Stream now = stream(c, id);
        saveStream(c, id, new Stream(now.fpsCap, now.sound, now.muted, autoQuality, now.host));
    }

    /** The Mac's settings as it last sent them; written only when they changed. */
    static synchronized void setHostSettings(Context c, String id, String settingsJson) {
        Stream now = stream(c, id);
        if (!now.host.equals(settingsJson))
            saveStream(c, id, new Stream(now.fpsCap, now.sound, now.muted, now.autoQuality, settingsJson));
    }
}
