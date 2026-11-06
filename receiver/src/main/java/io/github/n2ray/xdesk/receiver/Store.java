package io.github.n2ray.xdesk.receiver;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** The receiver's id and name, and the tokens of paired phones (phone id → token). */
final class Store {
    private static final String TOKEN = "token.";
    static final SecureRandom RANDOM = new SecureRandom();

    private final Context context;
    private final SharedPreferences prefs;

    Store(Context c) {
        context = c.getApplicationContext();
        prefs = context.getSharedPreferences("receiver", Context.MODE_PRIVATE);
    }

    synchronized String id() {
        String id = prefs.getString("id", null);
        if (id == null) {
            id = randomHex(16);
            prefs.edit().putString("id", id).apply();
        }
        return id;
    }

    synchronized String name() {
        String name = prefs.getString("name", null);
        if (name != null && !name.isEmpty()) return name;
        // The name the user gave the device; Settings.Global.DEVICE_NAME is API 25, but many older builds have it
        name = Settings.Global.getString(context.getContentResolver(), "device_name");
        return name != null && !name.trim().isEmpty() ? name.trim() : Build.MODEL;
    }

    synchronized void setName(String name) {
        prefs.edit().putString("name", name.trim()).apply();
    }

    synchronized boolean tokenMatches(String phoneId, String token) {
        if (token.isEmpty()) return false;
        String stored = prefs.getString(TOKEN + phoneId, null);
        return stored != null && MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    synchronized String newToken(String phoneId) {
        String token = randomHex(32);
        // commit, not apply: the phone keeps this token from the WELCOME we are about to send
        prefs.edit().putString(TOKEN + phoneId, token).commit();
        return token;
    }

    synchronized void forgetPhones() {
        SharedPreferences.Editor e = prefs.edit();
        for (String key : prefs.getAll().keySet())
            if (key.startsWith(TOKEN)) e.remove(key);
        e.apply();
    }

    static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder s = new StringBuilder(bytes * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return s.toString();
    }
}
