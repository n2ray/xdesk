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

        Mac(String id, String name, String host, int port, byte[] pin, boolean fit, boolean macKeyboard) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.port = port;
            this.pin = pin;
            this.fit = fit;
            this.macKeyboard = macKeyboard;
        }

        public String address() {
            return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("name", name).put("host", host).put("port", port)
                    .put("pin", Base64.encodeToString(pin, Base64.NO_WRAP)).put("fit", fit).put("macKeyboard", macKeyboard);
        }

        static Mac of(JSONObject o) throws JSONException {
            return new Mac(o.getString("id"), o.optString("name", ""), o.getString("host"), o.getInt("port"),
                    Base64.decode(o.getString("pin"), Base64.NO_WRAP), o.optBoolean("fit", false), o.optBoolean("macKeyboard", false));
        }

        Mac with(String name, boolean fit, boolean macKeyboard) {
            return new Mac(id, name, host, port, pin, fit, macKeyboard);
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
        Mac m = new Mac(p.macId, name, host, port, p.pin, old != null && old.fit, old != null && old.macKeyboard);
        macs.add(m);
        save(c, macs);
        return m;
    }

    public static synchronized void remove(Context c, String id) {
        List<Mac> macs = all(c);
        for (int i = macs.size() - 1; i >= 0; i--) if (macs.get(i).id.equals(id)) macs.remove(i);
        save(c, macs);
    }

    public static synchronized void setOptions(Context c, String id, boolean fit, boolean macKeyboard) {
        List<Mac> macs = all(c);
        for (int i = 0; i < macs.size(); i++) if (macs.get(i).id.equals(id)) macs.set(i, macs.get(i).with(macs.get(i).name, fit, macKeyboard));
        save(c, macs);
    }

    /** The Mac's name from its window list, when it changed. */
    static synchronized void rename(Context c, String id, String name) {
        List<Mac> macs = all(c);
        boolean changed = false;
        for (int i = 0; i < macs.size(); i++) {
            Mac m = macs.get(i);
            if (m.id.equals(id) && !name.isEmpty() && !name.equals(m.name)) {
                macs.set(i, m.with(name, m.fit, m.macKeyboard));
                changed = true;
            }
        }
        if (changed) save(c, macs);
    }
}
