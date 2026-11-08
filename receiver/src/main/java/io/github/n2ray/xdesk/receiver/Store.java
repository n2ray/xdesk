package io.github.n2ray.xdesk.receiver;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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

    /** The token paired with this phone, or null. */
    synchronized String token(String phoneId) {
        return prefs.getString(TOKEN + phoneId, null);
    }

    /** See PROTOCOL.md, Handshake. label says who proves, so one side's answer is no use as the other's. */
    static String proof(String token, String label, String phoneId, String receiverId, String phoneNonce, String receiverNonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] b = mac.doFinal((label + "\n" + phoneId + "\n" + receiverId + "\n" + phoneNonce + "\n" + receiverNonce)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder(b.length * 2);
            for (byte x : b) s.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
            return s.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
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
