// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11.screenshare;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Macs reached through macOS's own Screen Sharing (no app on the Mac): the address, the account and how big a
 * screen to ask for. The password is never kept: it is asked at each connection and only lives in memory for the
 * authentication (ScreenShareLoginActivity).
 */
public final class ScreenShareStore {
    private static final String PREFS = "screenshare", KEY = "hosts";

    public static final class Host {
        public final String id;
        public final String name, host, account;
        /** True: a 1920×1080 virtual screen (the baseline); false: the size of the screen it shows on. */
        public final boolean fixed1080;
        /** Test only: clock calibration and the time-bar sampling (needs the Mac's test tools). */
        public final boolean measure;
        /** 桌面缩放: ScreenShareSizing.NATIVE, MODERATE or LARGE, chosen per Mac. */
        public final int scale;

        public Host(String id, String name, String host, String account, boolean fixed1080, boolean measure) {
            this(id, name, host, account, fixed1080, measure, ScreenShareSizing.NATIVE);
        }

        public Host(String id, String name, String host, String account, boolean fixed1080, boolean measure, int scale) {
            this.scale = scale;
            this.id = id;
            this.name = name;
            this.host = host;
            this.account = account;
            this.fixed1080 = fixed1080;
            this.measure = measure;
        }

        public String label() {
            return name != null && !name.isEmpty() ? name : host;
        }
    }

    private ScreenShareStore() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized List<Host> all(Context c) {
        ArrayList<Host> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Host(o.getString("id"), o.optString("name"), o.getString("host"), o.optString("account"),
                    o.optBoolean("fixed1080", false), o.optBoolean("measure", false), o.optInt("scale", ScreenShareSizing.NATIVE)));
            }
        } catch (JSONException ignored) { }
        return out;
    }

    public static Host find(Context c, String id) {
        for (Host h : all(c)) if (h.id.equals(id)) return h;
        return null;
    }

    /** Adds (id null) or replaces one. Returns its id. */
    public static synchronized String save(Context c, String id, String name, String host, String account,
                                           boolean fixed1080, boolean measure, int scale) {
        if (id == null) id = UUID.randomUUID().toString().substring(0, 8);
        JSONArray out = new JSONArray();
        boolean replaced = false;
        try {
            for (Host h : all(c)) {
                Host w = h.id.equals(id) ? new Host(id, name, host, account, fixed1080, measure, scale) : h;
                if (h.id.equals(id)) replaced = true;
                out.put(json(w));
            }
            if (!replaced) out.put(json(new Host(id, name, host, account, fixed1080, measure, scale)));
        } catch (JSONException ignored) { }
        prefs(c).edit().putString(KEY, out.toString()).apply();
        return id;
    }

    public static synchronized void remove(Context c, String id) {
        prefs(c).edit().remove("result." + id).apply();
        JSONArray out = new JSONArray();
        try {
            for (Host h : all(c)) if (!h.id.equals(id)) out.put(json(h));
        } catch (JSONException ignored) { }
        prefs(c).edit().putString(KEY, out.toString()).apply();
    }

    /** What the last connection got: the tier and sizes asked, and the video the Mac really sent (VIDEO in the log). */
    public static void setResult(Context c, String id, ScreenShareSizing asked, int videoW, int videoH) {
        prefs(c).edit().putString("result." + id, asked.tier + " " + asked.desktopW + " " + asked.desktopH + " " + asked.videoW
            + " " + asked.videoH + " " + (asked.hidpi ? 1 : 0) + " " + videoW + " " + videoH).apply();
    }

    /**
     * The last connection's {tier, desktop w, h, video asked w, h, hidpi, video got w, h}, or null.
     */
    public static int[] result(Context c, String id) {
        String s = prefs(c).getString("result." + id, null);
        if (s == null) return null;
        try {
            String[] p = s.split(" ");
            int[] v = new int[p.length];
            for (int i = 0; i < p.length; i++) v[i] = Integer.parseInt(p[i]);
            return v.length == 8 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JSONObject json(Host h) throws JSONException {
        return new JSONObject().put("id", h.id).put("name", h.name).put("host", h.host).put("account", h.account)
            .put("fixed1080", h.fixed1080).put("measure", h.measure).put("scale", h.scale);
    }
}
