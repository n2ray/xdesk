// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11.screenshare;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.Executor;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * A Mac password kept only if the user asked for it, and only encrypted: an AES-GCM key per Mac made in the
 * Android Keystore (in the phone's secure hardware, never readable by the app), usable for one operation after the
 * user proves it is them with a strong biometric or the phone's own lock-screen credential. A new fingerprint or
 * face enrolled, or the lock screen removed, makes the key unusable: the saved password is then forgotten. What
 * the app stores is the ciphertext and its IV.
 */
public final class ScreenSharePasswords {
    private static final String TAG = ScreenShareSession.TAG, PREFS = "screenshare_secrets", KEYSTORE = "AndroidKeyStore";
    private static final int AUTH = KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL;

    public interface Result {
        /** The password (the caller clears it), or null with a reason (cancelled, failed, key gone). */
        void done(char[] password, String error);
    }

    private ScreenSharePasswords() { }

    private static String alias(String hostId) {
        return "xdesk-screenshare-" + hostId;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Saving is offered only with a secure lock screen (else nothing could protect the key). */
    public static boolean canSave(Context c) {
        KeyguardManager k = c.getSystemService(KeyguardManager.class);
        if (k == null || !k.isDeviceSecure()) return false;
        BiometricManager b = c.getSystemService(BiometricManager.class);
        return b == null || b.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG
            | BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public static boolean has(Context c, String hostId) {
        return prefs(c).contains(hostId + ".data");
    }

    public static void forget(Context c, String hostId) {
        prefs(c).edit().remove(hostId + ".data").remove(hostId + ".iv").apply();
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            ks.deleteEntry(alias(hostId));
        } catch (Exception e) {
            Log.w(TAG, "forget key", e);
        }
    }

    private static SecretKey newKey(String hostId) throws Exception {
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        g.init(new KeyGenParameterSpec.Builder(alias(hostId), KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, AUTH)
            .setInvalidatedByBiometricEnrollment(true)
            .build());
        return g.generateKey();
    }

    private static SecretKey key(String hostId) throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        return (SecretKey) ks.getKey(alias(hostId), null);
    }

    /** Asks for the user's fingerprint or lock screen, then encrypts and stores the password (a copy of it). */
    public static void save(Context activity, String hostId, String title, char[] password, Result result) {
        final char[] copy = Arrays.copyOf(password, password.length);
        try {
            forget(activity, hostId);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, newKey(hostId));
            prompt(activity, title, cipher, result, c -> {
                byte[] plain = bytes(copy);
                Arrays.fill(copy, (char) 0);
                try {
                    byte[] data = c.doFinal(plain);
                    prefs(activity).edit().putString(hostId + ".data", Base64.encodeToString(data, Base64.NO_WRAP))
                        .putString(hostId + ".iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP)).apply();
                    result.done(null, null);
                } finally {
                    Arrays.fill(plain, (byte) 0);
                }
            }, () -> Arrays.fill(copy, (char) 0));
        } catch (Exception e) {
            Arrays.fill(copy, (char) 0);
            Log.w(TAG, "save password", e);
            result.done(null, e.toString());
        }
    }

    /** Asks for the user's fingerprint or lock screen, then hands the saved password over. */
    public static void load(Context activity, String hostId, String title, Result result) {
        SharedPreferences p = prefs(activity);
        String data = p.getString(hostId + ".data", null), iv = p.getString(hostId + ".iv", null);
        if (data == null || iv == null) {
            result.done(null, "none");
            return;
        }
        try {
            SecretKey k = key(hostId);
            if (k == null) throw new KeyPermanentlyInvalidatedException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            prompt(activity, title, cipher, result, c -> {
                byte[] plain = c.doFinal(Base64.decode(data, Base64.NO_WRAP));
                try {
                    result.done(chars(plain), null);
                } finally {
                    Arrays.fill(plain, (byte) 0);
                }
            }, null);
        } catch (KeyPermanentlyInvalidatedException e) {
            // A new fingerprint, or the lock screen changed: the key is gone, and with it the password
            forget(activity, hostId);
            result.done(null, "invalidated");
        } catch (Exception e) {
            Log.w(TAG, "load password", e);
            result.done(null, e.toString());
        }
    }

    private interface Use { void run(Cipher c) throws Exception; }

    private static void prompt(Context activity, String title, Cipher cipher, Result result, Use use, Runnable cancelled) {
        Executor main = activity.getMainExecutor();
        BiometricPrompt bp = new BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setAllowedAuthenticators(AUTH)
            .build();
        bp.authenticate(new BiometricPrompt.CryptoObject(cipher), new CancellationSignal(), main,
            new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                    try {
                        use.run(r.getCryptoObject().getCipher());
                    } catch (Exception e) {
                        Log.w(TAG, "password", e);
                        result.done(null, e.toString());
                    }
                }
                @Override public void onAuthenticationError(int code, CharSequence msg) {
                    if (cancelled != null) cancelled.run();
                    result.done(null, msg != null ? msg.toString() : "cancelled");
                }
            });
    }

    private static byte[] bytes(char[] c) {
        ByteBuffer b = StandardCharsets.UTF_8.encode(CharBuffer.wrap(c));
        byte[] out = Arrays.copyOfRange(b.array(), b.position(), b.limit());
        Arrays.fill(b.array(), (byte) 0);
        return out;
    }

    private static char[] chars(byte[] b) {
        CharBuffer c = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(b));
        char[] out = Arrays.copyOfRange(c.array(), c.position(), c.limit());
        Arrays.fill(c.array(), (char) 0);
        return out;
    }
}
