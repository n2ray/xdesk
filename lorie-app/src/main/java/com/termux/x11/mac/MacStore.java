package com.termux.x11.mac;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * The Macs XDesk has paired with: where to reach each (the pairing address, the addresses its signed info last
 * listed, the one that last worked) and the pin of its key. A pin is a hash of the Mac's public key, not a secret;
 * XDesk's own key stays in the Android key store.
 */
public final class MacStore {
    private static final String PREFS = "xdesk_macs", KEY = "macs";

    /** Sall's SavedMacs: a VPN route a newer list no longer has is kept this long (90 days), and this many. */
    static final double REMEMBER_FOR = 90 * 24 * 3600;
    static final int REMEMBER_CAP = 4;

    /** One address the Mac advertised (kind "vpn", "lan" or "internet"), or one a session connected at (kind null). */
    public static final class Address {
        static final String VPN = "vpn", LAN = "lan", INTERNET = "internet";

        public final String host;
        public final int port;
        public final String kind;

        Address(String host, int port, String kind) {
            this.host = host;
            this.port = port;
            this.kind = kind;
        }

        static boolean validKind(String kind) {
            return VPN.equals(kind) || LAN.equals(kind) || INTERNET.equals(kind);
        }

        /** Host and port, case-insensitive, as two lists of addresses are deduplicated. */
        String key() {
            return host.toLowerCase(Locale.ROOT) + "|" + port;
        }

        boolean same(Address o) {
            return o != null && port == o.port && host.equalsIgnoreCase(o.host)
                    && (kind == null ? o.kind == null : kind.equals(o.kind));
        }

        JSONObject json() throws JSONException {
            JSONObject o = new JSONObject().put("host", host).put("port", port);
            if (kind != null) o.put("kind", kind);
            return o;
        }

        static Address of(JSONObject o, boolean withKind) {
            if (o == null) return null;
            Object host = o.opt("host"), port = o.opt("port");
            if (!(host instanceof String) || ((String) host).isEmpty() || ((String) host).length() > 253) return null;
            if (!(port instanceof Number)) return null;
            double p = ((Number) port).doubleValue();
            if (!Double.isFinite(p) || p != (int) p || p < 1 || p > 65535) return null;
            String kind = null;
            if (withKind) {
                Object k = o.opt("kind");
                if (!(k instanceof String) || !validKind((String) k)) return null;
                kind = (String) k;
            }
            return new Address((String) host, (int) p, kind);
        }
    }

    /** An address a newer list no longer has, and when (seconds since the epoch) it was last listed. */
    public static final class Remembered {
        public final Address address;
        public final double listedAt;

        Remembered(Address address, double listedAt) {
            this.address = address;
            this.listedAt = listedAt;
        }

        JSONObject json() throws JSONException {
            return address.json().put("listedAt", listedAt);
        }
    }

    public static final class Mac {
        public final String id;
        public final String name;
        public final String host;
        public final int port;
        final byte[] pin;
        /**
         * Sill's recognition key (32 bytes, sent once in the pairing answer): what the home door's Bonjour TXT
         * tag is checked against, to find this Mac on the local network. Null for a Mac paired by a Sill that did
         * not send one (before home pairing); such a Mac is reached at its saved address as before.
         */
        final byte[] recognition;
        /** Resize the streamed Mac window to the screen (DISPLAY.md §1.3, off by default). */
        public final boolean fit;
        /** The keyboard is a Mac keyboard: no Ctrl↔⌘ swap (INPUT.md §5.5). */
        public final boolean macKeyboard;
        /**
         * A physical keyboard on the phone types through the phone's input method (text, composed on the phone)
         * instead of as keys the Mac's own input method composes (INPUT.md §5.2, off by default).
         */
        public final boolean phoneIme;
        /**
         * What the Mac's last signed address list (SillWire.MAC_INFO) said: where it can be reached, in its own
         * order. Empty before the first list and for a saved Mac from before XDesk read one.
         */
        final List<Address> advertised;
        /** When the Mac issued that list, in seconds since the epoch; 0 when none was taken. */
        final double infoIssuedAt;
        /** The address a session last connected at (the home door is not one), or null before one did. */
        final Address lastWorked;
        /**
         * VPN addresses a newer list no longer has, newest first. A Mac whose VPN address changed (Tailscale gave
         * it a new one, the laptop moved) is still reachable at the old one for a while (Sall's SavedMacs).
         */
        final List<Remembered> remembered;

        Mac(String id, String name, String host, int port, byte[] pin, byte[] recognition, boolean fit,
            boolean macKeyboard, boolean phoneIme, List<Address> advertised, double infoIssuedAt,
            Address lastWorked, List<Remembered> remembered) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.port = port;
            this.pin = pin;
            this.recognition = recognition;
            this.fit = fit;
            this.macKeyboard = macKeyboard;
            this.phoneIme = phoneIme;
            this.advertised = Collections.unmodifiableList(new ArrayList<>(advertised));
            this.infoIssuedAt = infoIssuedAt;
            this.lastWorked = lastWorked;
            this.remembered = Collections.unmodifiableList(new ArrayList<>(remembered));
        }

        public String address() {
            return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        }

        JSONObject json() throws JSONException {
            JSONArray advertisedJson = new JSONArray();
            for (Address a : advertised) advertisedJson.put(a.json());
            JSONArray rememberedJson = new JSONArray();
            for (Remembered r : remembered) rememberedJson.put(r.json());
            return new JSONObject().put("id", id).put("name", name).put("host", host).put("port", port)
                    .put("pin", Base64.encodeToString(pin, Base64.NO_WRAP))
                    .put("recognition", recognition == null ? ""
                            : Base64.encodeToString(recognition, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING))
                    .put("fit", fit).put("macKeyboard", macKeyboard)
                    .put("phoneIme", phoneIme)
                    .put("advertised", advertisedJson).put("infoIssuedAt", infoIssuedAt)
                    .put("lastWorked", lastWorked == null ? JSONObject.NULL : lastWorked.json())
                    .put("remembered", rememberedJson);
        }

        static Mac of(JSONObject o) throws JSONException {
            byte[] recognition = null;
            String encoded = o.optString("recognition", "");
            if (!encoded.isEmpty()) {
                try {
                    byte[] decoded = Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                    if (decoded.length == 32) recognition = decoded;
                } catch (IllegalArgumentException ignored) {
                    // A damaged hint is no hint: the Mac is still reachable at its saved address
                }
            }
            double issued = o.optDouble("infoIssuedAt", 0);
            if (!Double.isFinite(issued) || issued < 0) issued = 0;
            return new Mac(o.getString("id"), o.optString("name", ""), o.getString("host"), o.getInt("port"),
                    Base64.decode(o.getString("pin"), Base64.NO_WRAP), recognition, o.optBoolean("fit", false),
                    o.optBoolean("macKeyboard", false), o.optBoolean("phoneIme", false),
                    addresses(o.optJSONArray("advertised")), issued,
                    Address.of(o.optJSONObject("lastWorked"), false), remembered(o.optJSONArray("remembered")));
        }

        Mac with(String name, boolean fit, boolean macKeyboard, boolean phoneIme) {
            return new Mac(id, name, host, port, pin, recognition, fit, macKeyboard, phoneIme, advertised,
                    infoIssuedAt, lastWorked, remembered);
        }

        Mac withInfo(List<Address> advertised, double infoIssuedAt, List<Remembered> remembered) {
            return new Mac(id, name, host, port, pin, recognition, fit, macKeyboard, phoneIme, advertised,
                    infoIssuedAt, lastWorked, remembered);
        }

        Mac withLastWorked(Address lastWorked) {
            return new Mac(id, name, host, port, pin, recognition, fit, macKeyboard, phoneIme, advertised,
                    infoIssuedAt, lastWorked, remembered);
        }
    }

    /** The advertised list, in its saved order; malformed entries (an older or damaged record) are skipped. */
    private static List<Address> addresses(JSONArray a) {
        ArrayList<Address> out = new ArrayList<>();
        if (a == null) return Collections.emptyList();
        for (int i = 0; i < a.length(); i++) {
            Address address = Address.of(a.optJSONObject(i), true);
            if (address != null && !has(out, address)) out.add(address);
        }
        return Collections.unmodifiableList(out);
    }

    private static List<Remembered> remembered(JSONArray a) {
        ArrayList<Remembered> out = new ArrayList<>();
        if (a == null) return Collections.emptyList();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            Address address = Address.of(o, true);
            Object listedAt = o != null ? o.opt("listedAt") : null;
            if (address == null || !(listedAt instanceof Number)) continue;
            double at = ((Number) listedAt).doubleValue();
            if (Double.isFinite(at) && at >= 0) out.add(new Remembered(address, at));
        }
        return Collections.unmodifiableList(out);
    }

    private static boolean has(List<Address> addresses, Address address) {
        for (Address a : addresses) if (a.key().equals(address.key())) return true;
        return false;
    }

    private MacStore() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** A saved Mac whose home door local discovery can recognise (it has a recognition key). */
    public static synchronized boolean anyFindableAtHome(Context c) {
        for (Mac m : all(c)) if (m.recognition != null) return true;
        return false;
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
        // A pairing answer from a Sill that has no recognition key leaves the one already saved alone
        byte[] recognition = p.recognitionKey != null ? p.recognitionKey : old != null ? old.recognition : null;
        // What the old key signed is the old key's to say: a Mac paired again with a new key starts with no routes.
        boolean samePin = old != null && MessageDigest.isEqual(old.pin, p.pin);
        Mac m = new Mac(p.macId, name, host, port, p.pin, recognition, old != null && old.fit,
                old != null && old.macKeyboard, old != null && old.phoneIme,
                samePin ? old.advertised : Collections.<Address>emptyList(),
                samePin ? old.infoIssuedAt : 0, samePin ? old.lastWorked : null,
                samePin ? old.remembered : Collections.<Remembered>emptyList());
        macs.add(m);
        save(c, macs);
        return m;
    }

    /**
     * The Mac's signed address list (kind 18), once it is newer than the one saved; false when it is not this
     * Mac's (the pin differs) or is not newer (no rollback). A VPN address the new list dropped is remembered for
     * a while, as Sall's SavedMacs does, so a changing VPN address does not lose the Mac.
     */
    static synchronized boolean updateInfo(Context c, String id, byte[] pin, double issuedAt,
                                           List<Address> advertised) {
        if (pin == null || pin.length != 32 || advertised == null
                || !Double.isFinite(issuedAt) || issuedAt <= 0) return false;
        List<Mac> macs = all(c);
        for (int i = 0; i < macs.size(); i++) {
            Mac m = macs.get(i);
            if (!m.id.equals(id) || !MessageDigest.isEqual(m.pin, pin)) continue;
            if (issuedAt <= m.infoIssuedAt) return false;
            ArrayList<Address> next = new ArrayList<>();
            for (Address a : advertised) if (a != null && a.kind != null) next.add(a);
            double now = System.currentTimeMillis() / 1000.0;
            List<Remembered> remembered = remembering(m.advertised, m.remembered, next, m.infoIssuedAt, now);
            macs.set(i, m.withInfo(next, issuedAt, remembered));
            save(c, macs);
            return true;
        }
        return false;
    }

    /** The address a session connected at, the next dial's first try; saved only when it changed. */
    static synchronized void setLastWorked(Context c, String id, String host, int port) {
        if (host == null || host.isEmpty() || port < 1 || port > 65535) return;
        List<Mac> macs = all(c);
        for (int i = 0; i < macs.size(); i++) {
            Mac m = macs.get(i);
            if (!m.id.equals(id)) continue;
            Address address = new Address(host, port, null);
            if (address.same(m.lastWorked)) return;
            macs.set(i, m.withLastWorked(address));
            save(c, macs);
            return;
        }
    }

    /** Sall's SavedMacs.remembering: the VPN routes a newer list dropped, newest first, at most REMEMBER_CAP. */
    private static List<Remembered> remembering(List<Address> oldAdvertised, List<Remembered> oldRemembered,
                                                List<Address> current, double oldIssuedAt, double now) {
        ArrayList<Remembered> offered = new ArrayList<>();
        for (Address a : oldAdvertised) offered.add(new Remembered(a, oldIssuedAt));
        offered.addAll(oldRemembered);
        LinkedHashMap<String, Remembered> newest = new LinkedHashMap<>();
        for (Remembered r : offered) {
            if (!Address.VPN.equals(r.address.kind)) continue;
            // An address was last listed when the list that had it was issued, never later than now.
            double at = Math.min(now, r.listedAt);
            if (now - at > REMEMBER_FOR || has(current, r.address)) continue;
            Remembered seen = newest.get(r.address.key());
            if (seen == null || seen.listedAt < at) newest.put(r.address.key(), new Remembered(r.address, at));
        }
        ArrayList<Remembered> out = new ArrayList<>(newest.values());
        out.sort((a, b) -> Double.compare(b.listedAt, a.listedAt));
        if (out.size() > REMEMBER_CAP) out = new ArrayList<>(out.subList(0, REMEMBER_CAP));
        return Collections.unmodifiableList(out);
    }

    public static synchronized void remove(Context c, String id) {
        List<Mac> macs = all(c);
        for (int i = macs.size() - 1; i >= 0; i--) if (macs.get(i).id.equals(id)) macs.remove(i);
        save(c, macs);
        prefs(c).edit().remove(STREAM + id).remove(SMOOTH + id).apply();
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

    /**
     * Smooth playback (MacVideo pacing): pictures shown evenly by the Mac's capture times, a little later when the
     * link is uneven, instead of each at once as it arrives. This device's own switch, on by default.
     */
    public static boolean smooth(Context c, String id) {
        return prefs(c).getBoolean(SMOOTH + id, true);
    }

    public static void setSmooth(Context c, String id, boolean on) {
        prefs(c).edit().putBoolean(SMOOTH + id, on).apply();
    }

    private static final String SMOOTH = "smooth.";

    /** The Mac's settings as it last sent them; written only when they changed. */
    static synchronized void setHostSettings(Context c, String id, String settingsJson) {
        Stream now = stream(c, id);
        if (!now.host.equals(settingsJson))
            saveStream(c, id, new Stream(now.fpsCap, now.sound, now.muted, now.autoQuality, settingsJson));
    }
}
