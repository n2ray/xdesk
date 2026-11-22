package com.termux.x11.screenshare;

import java.io.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.spec.*;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.PBEKeySpec;
import static com.termux.x11.screenshare.DirectSession.*;

/** Type 33 SRP profile adapted from Rootshell's MIT MacAuthenticator and AppleSRPClient. */
final class AppleLogin {
    private final DirectSession socket;

    AppleLogin(DirectSession socket) { this.socket = socket; }

    private static byte[] shortField(byte[] value) throws IOException {
        if (value.length > 65535) throw new IOException("SRP field too large");
        return cat(u16(value.length), value);
    }

    private static ByteBuffer section(byte[] packet) throws IOException {
        if (packet.length < 10) throw new IOException("Mac rejected SRP exchange");
        ByteBuffer in = ByteBuffer.wrap(packet);
        in.getInt();
        int size = word(in);
        int inner = in.getInt();
        if (size != inner + 4 || inner < 0) throw new IOException("invalid SRP section");
        return ByteBuffer.wrap(bytes(in, inner));
    }

    private void send(byte[] fields, int length) throws IOException {
        byte[] body = cat(b(1,0), str("RSA1"), u16(2), shortField(fields));
        if (body.length > length) throw new IOException("SRP frame too large");
        socket.out.write(cat(u32(length), Arrays.copyOf(body, length)));
    }

    private PublicKey publicKey() throws Exception {
        byte[] hello = cat(u16(256), str("RSA1"), u32(0));
        socket.out.write(cat(b(33), u32(hello.length), hello));
        ByteBuffer response = ByteBuffer.wrap(socket.packet());
        response.getShort();
        byte[] encoded = bytes(response, response.getInt());
        KeyFactory factory = KeyFactory.getInstance("RSA");
        try {
            return factory.generatePublic(new X509EncodedKeySpec(encoded));
        } catch (InvalidKeySpecException pkcs1) {
            // Wrap a DER RSAPublicKey in SubjectPublicKeyInfo.
            byte[] oid = b(48,13,6,9,42,134,72,134,247,13,1,1,1,5,0);
            byte[] contents = cat(oid, b(3,130),u16(encoded.length+1),b(0),encoded);
            return factory.generatePublic(new X509EncodedKeySpec(cat(b(48,130),u16(contents.length),contents)));
        }
    }

    byte[] authenticate(String username, char[] password) throws Exception {
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, publicKey());
        byte[] account = str(username);
        ByteBuffer initial = ByteBuffer.allocate(7 + account.length);
        initial.putInt(account.length).put(account);
        byte[] identity = initial.array();
        send(rsa.doFinal(cat(u32(identity.length),identity)), 650);
        ByteBuffer challenge = section(socket.packet());
        challenge.get();
        byte[] prime = bytes(challenge,word(challenge));
        BigInteger generator = num(bytes(challenge,word(challenge)));
        byte[] salt = bytes(challenge,Byte.toUnsignedInt(challenge.get()));
        BigInteger server = num(bytes(challenge,word(challenge)));
        long cost = challenge.getLong();
        byte[] options = bytes(challenge,word(challenge));
        BigInteger modulus = num(prime);
        if (!Arrays.equals(prime,socket.asset("group")) || !generator.equals(BigInteger.valueOf(5))
                || server.signum()==0 || server.compareTo(modulus)>=0 || cost<=0 || cost>1000000)
            throw new IOException("unsupported SRP parameters");

        PBEKeySpec spec = new PBEKeySpec(password,salt,(int)cost,1024);
        BigInteger exponent;
        try {
            byte[] stretched = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).getEncoded();
            try { exponent = num(sha(salt,sha(b(58),stretched))); }
            finally { Arrays.fill(stretched,(byte)0); }
        } finally { spec.clearPassword(); }
        BigInteger secret = new BigInteger(512,socket.rng).add(BigInteger.ONE);
        byte[] client = pad(generator.modPow(secret,modulus));
        byte[] peer = pad(server);
        BigInteger scramble = num(sha(client,peer));
        BigInteger multiplier = num(sha(prime,pad(generator)));
        BigInteger base = server.subtract(multiplier.multiply(generator.modPow(exponent,modulus))).mod(modulus);
        if (scramble.signum()==0 || base.signum()==0) throw new IOException("invalid SRP challenge");
        byte[] shared = sha(pad(base.modPow(secret.add(scramble.multiply(exponent)),modulus)));
        byte[] prefix = sha(prime);
        byte[] hashG = sha(pad(generator));
        for (int i=0;i<prefix.length;i++) prefix[i] ^= hashG[i];
        byte[] proof = sha(prefix,sha(),salt,client,peer,shared);
        byte[] evidence = cat(shortField(client),b(proof.length),proof,shortField(options),b(16),socket.random(16));
        send(cat(u32(evidence.length),evidence),1076);
        ByteBuffer confirmation = section(socket.packet());
        byte[] m2 = bytes(confirmation,Byte.toUnsignedInt(confirmation.get()));
        if (!MessageDigest.isEqual(m2,sha(client,proof,shared)) || socket.in.readInt()!=0)
            throw new IOException("Mac SRP proof or login result invalid");
        return Arrays.copyOf(hash("SHA-256",shared),16);
    }
}
