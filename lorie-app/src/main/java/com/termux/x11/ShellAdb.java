package com.termux.x11;

import android.content.Context;
import android.os.Build;
import androidx.annotation.NonNull;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.android.AdbMdns;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * XDesk's identity towards adbd. Paired once, this key can open a shell whenever wireless debugging is on, so it stays in
 * the app's private no-backup directory. Revoking it is "revoke authorizations" in the developer options.
 */
final class ShellAdb extends AbsAdbConnectionManager {
    /** What shows in the list of paired devices. */
    private static final String DEVICE_NAME = "XDesk";
    private static final int KEY_BITS = 2048;
    private static final long TIMEOUT_MS = 5000;
    /** The protocol version libadb speaks follows this. */
    static final int API = Build.VERSION.SDK_INT;

    private final PrivateKey privateKey;
    private final Certificate certificate;

    ShellAdb(Context context) throws GeneralSecurityException, IOException {
        setApi(API);
        setTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        setThrowOnUnauthorised(true);
        File keyFile = new File(context.getNoBackupFilesDir(), "adb_key"), certFile = new File(context.getNoBackupFilesDir(), "adb_cert");
        if (!keyFile.exists() || !certFile.exists()) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_BITS);
            KeyPair keys = generator.generateKeyPair();
            write(certFile, AdbCertificate.selfSigned(keys));
            write(keyFile, keys.getPrivate().getEncoded());
        }
        privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
        certificate = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Files.readAllBytes(certFile.toPath())));
    }

    private static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    @NonNull @Override protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @NonNull @Override protected Certificate getCertificate() {
        return certificate;
    }

    @NonNull @Override protected String getDeviceName() {
        return DEVICE_NAME;
    }

    /**
     * Looks up one of adbd's services on this device. AdbMdns only accepts a result with this device's address and a port
     * that really is in use.
     *
     * @return the port, or 0 when not found in time
     */
    static int findPort(Context context, String serviceType, long timeoutMs) throws InterruptedException {
        final AtomicInteger found = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        AdbMdns mdns = new AdbMdns(context, serviceType, (host, port) -> {
            if (port > 0) {
                found.set(port);
                latch.countDown();
            }
        });
        mdns.start();
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            mdns.stop();
        }
        return found.get();
    }
}
