/*
 * Derived from Sall (https://github.com/n2ray/sall, SillConnection.java), the Android client of Sill
 * (https://github.com/Saffsanity/sill). Copyright 2026 Noah Saffer. Licensed under the Apache License, Version 2.0.
 * Modified for XDesk.
 */
package com.termux.x11.mac;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * One TLS 1.3 connection to a Sill host. The Mac proves who it is by its key, pinned after pairing (the SHA-256
 * of its public key); XDesk proves who it is by its own key in the Android key store, which the Mac learned at
 * pairing. "sill/1" is a session, "sill-pair/1" the one-message pairing exchange.
 */
final class SillTls implements Closeable {
    static final String SESSION = "sill/1", PAIRING = "sill-pair/1";
    private static final String TAG = "XDeskMac";
    private static final String ALIAS = "xdesk-sill-identity";

    private final SSLSocket socket;
    final DataInputStream in;
    private final OutputStream out;
    private final byte[] own;
    private final byte[] peer;

    private SillTls(SSLSocket socket, byte[] own, byte[] peer) throws IOException {
        this.socket = socket;
        this.own = own;
        this.peer = peer;
        in = SillWire.input(socket.getInputStream());
        out = socket.getOutputStream();
    }

    /** pin null: accept any Mac key (pairing only), and report it by peer(). */
    static SillTls connect(String host, int port, String alpn, final byte[] pin, int connectMs, int readMs) throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        if (!keys.containsAlias(ALIAS)) {
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256).build());
            g.generateKeyPair();
        }
        byte[] own = MessageDigest.getInstance("SHA-256").digest(keys.getCertificate(ALIAS).getPublicKey().getEncoded());
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, null);
        final byte[][] observed = new byte[1][];
        X509TrustManager trust = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Unexpected client certificate");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0 || !(chain[0].getPublicKey() instanceof ECPublicKey)
                        || ((ECPublicKey) chain[0].getPublicKey()).getParams().getCurve().getField().getFieldSize() != 256)
                    throw new CertificateException("Invalid Sill identity");
                try {
                    byte[] fp = MessageDigest.getInstance("SHA-256").digest(chain[0].getPublicKey().getEncoded());
                    if (pin != null && !MessageDigest.isEqual(pin, fp)) throw new CertificateException("Mac identity changed");
                    observed[0] = fp;
                } catch (java.security.GeneralSecurityException e) {
                    throw new CertificateException(e);
                }
            }
        };
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(km.getKeyManagers(), new TrustManager[] {trust}, new SecureRandom());
        Socket tcp = new Socket();
        try {
            tcp.connect(new InetSocketAddress(host, port), connectMs);
            tcp.setTcpNoDelay(true);
            tcp.setSoTimeout(readMs);
            SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(tcp, host, port, true);
            tls.setSoTimeout(readMs);
            SSLParameters p = tls.getSSLParameters();
            p.setProtocols(new String[] {"TLSv1.3"});
            p.setApplicationProtocols(new String[] {alpn});
            tls.setSSLParameters(p);
            tls.startHandshake();
            if (!alpn.equals(tls.getApplicationProtocol()) || observed[0] == null) throw new IOException("Sill TLS negotiation failed");
            return new SillTls(tls, own, observed[0]);
        } catch (Exception e) {
            tcp.close();
            throw e;
        }
    }

    /** What pairing gives: the Mac's pin, its id and name. */
    static final class Paired {
        final byte[] pin;
        final String macId, name;

        Paired(byte[] pin, String macId, String name) {
            this.pin = pin;
            this.macId = macId;
            this.name = name;
        }
    }

    /** On a "sill-pair/1" connection: prove we know the 12-digit code the Mac shows, and check the Mac knows it too. */
    Paired pair(String code, String deviceName, String model) throws Exception {
        byte[] key = PairingProof.codeKey(code, peer);
        try {
            byte[] proof = PairingProof.proof(key, peer, own, true);
            String encoded = Base64.encodeToString(proof, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            JSONObject request = new JSONObject().put("v", 1).put("method", "code").put("proof", encoded)
                    .put("name", deviceName).put("model", model);
            send(SillWire.PAIR_REQUEST, request.toString().getBytes(StandardCharsets.UTF_8));
            SillWire.Message m = new SillWire.Message();
            SillWire.read(in, m, 4096);
            if (m.kind != SillWire.PAIR_RESULT || m.length == 0) throw new IOException("Invalid pairing response");
            JSONObject result = new JSONObject(m.text());
            if (!result.optBoolean("ok")) throw new PairRefused(result.optString("reason", "unknown"));
            byte[] answer = Base64.decode(result.getString("proof"), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            if (!PairingProof.verifyMac(PairingProof.proof(key, peer, own, false), answer))
                throw new IOException("Mac pairing proof failed");
            String id = result.optString("macID", "");
            return new Paired(peer.clone(), id.isEmpty() ? macId(peer) : id, result.optString("name", ""));
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    static final class PairRefused extends IOException {
        final String reason;

        PairRefused(String reason) {
            super("Pairing refused: " + reason);
            this.reason = reason;
        }
    }

    /** Sill's MacID: the first 10 bytes of the fingerprint in Crockford base 32 (16 characters). */
    static String macId(byte[] fp) {
        String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        StringBuilder s = new StringBuilder();
        int acc = 0, bits = 0;
        for (int i = 0; i < 10; i++) {
            acc = (acc << 8) | (fp[i] & 255);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                s.append(alphabet.charAt((acc >>> bits) & 31));
            }
        }
        return s.toString();
    }

    byte[] peer() { return peer.clone(); }

    /** Writes come from one sender thread, but pings and the reader's replies may cross it. */
    synchronized void send(int kind, byte[] data) throws IOException {
        SillWire.write(out, kind, data);
    }

    void readTimeout(int ms) throws IOException {
        socket.setSoTimeout(ms);
    }

    /** Closing TLS writes a close_notify: never on the UI thread (NetworkOnMainThreadException crashed XDesk on 断开). */
    @Override public void close() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            new Thread(this::closeNow, "sill-close").start();
            return;
        }
        closeNow();
    }

    private void closeNow() {
        try {
            socket.close();
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "close", e);
        }
    }
}
