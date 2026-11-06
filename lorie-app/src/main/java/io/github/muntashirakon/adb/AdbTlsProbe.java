package io.github.muntashirakon.adb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * XDesk's check of a port before libadb connects to it. It lives in libadb's package for AdbProtocol, which is not
 * public. libadb accepts any server certificate and signs an old style AUTH token for a peer that never asks for TLS,
 * so a fake adbd on this device could get a signature or see the command XDesk sends.
 *
 * adbd for wireless debugging always answers CNXN with STLS. This sends CNXN, refuses a peer that asks for AUTH instead,
 * and reads the server's certificate in the TLS handshake. The handshake is then broken off before XDesk's own
 * certificate goes out, so the peer learns nothing about XDesk and adbd sees only a failed connection.
 */
public final class AdbTlsProbe {
    private AdbTlsProbe() { }

    /** Thrown on purpose once the server's certificate is known. */
    private static final class GotIt extends CertificateException {
        GotIt() { super("certificate read"); }
    }

    /**
     * @param api the API level, as given to libadb
     * @return the SHA-256 of the server's certificate, in hex
     * @throws IOException when the peer is not adbd over TLS
     */
    public static String serverIdentity(String host, int port, int api, int timeoutMs) throws IOException {
        final X509Certificate[] seen = new X509Certificate[1];
        X509TrustManager capture = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("not a server");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain != null && chain.length > 0) seen[0] = chain[0];
                throw new GotIt();
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(host, port), timeoutMs);
            plain.setSoTimeout(timeoutMs);
            OutputStream out = plain.getOutputStream();
            InputStream in = plain.getInputStream();
            out.write(AdbProtocol.generateConnect(api));
            out.flush();
            AdbProtocol.Message reply = AdbProtocol.Message.parse(in, AdbProtocol.getProtocolVersion(api), AdbProtocol.getMaxData(api));
            if (reply.command == AdbProtocol.A_AUTH) throw new IOException("asks for an old style AUTH instead of TLS");
            if (reply.command != AdbProtocol.A_STLS) throw new IOException(String.format("answers 0x%x instead of STLS", reply.command));
            out.write(AdbProtocol.generateStls());
            out.flush();
            SSLContext tls = SSLContext.getInstance("TLSv1.3");
            tls.init(null, new TrustManager[]{capture}, new SecureRandom());
            try (SSLSocket s = (SSLSocket) tls.getSocketFactory().createSocket(plain, host, port, false)) {
                s.startHandshake();
            } catch (IOException expected) {
                // GotIt ends the handshake, wrapped in an SSLException
            }
            if (seen[0] == null) throw new IOException("no server certificate");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seen[0].getEncoded());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }
}
