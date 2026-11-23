package io.archud.link;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The phone's identity for the TV remote protocol: one self-signed RSA certificate, made on first use and kept in
 * this app's private storage. Android has no certificate builder, so the few DER bytes are written by hand.
 */
final class AtvCert {
    static final char[] PASS = "arclink".toCharArray();
    static final String ALIAS = "atv";
    private static final String FILE = "atv_client.p12";

    private static final byte[] SHA256_RSA = {0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D,
            0x01, 0x01, 0x0B};
    private static final byte[] NULL = {0x05, 0x00};
    private static final byte[] OID_CN = {0x06, 0x03, 0x55, 0x04, 0x03};

    private AtvCert() {
    }

    static synchronized KeyStore keyStore(Context c) throws GeneralSecurityException, IOException {
        File f = new File(c.getFilesDir(), FILE);
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                ks.load(in, PASS);
                if (ks.getKey(ALIAS, PASS) != null) {
                    return ks;
                }
            } catch (IOException | GeneralSecurityException e) {
                // unreadable file: make a new identity (the TV will need pairing again)
            }
            f.delete();
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(selfSigned(kp)));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(ALIAS, kp.getPrivate(), PASS, new Certificate[] {cert});
        try (FileOutputStream out = new FileOutputStream(f)) {
            ks.store(out, PASS);
        }
        return ks;
    }

    private static byte[] selfSigned(KeyPair kp) throws GeneralSecurityException {
        byte[] sigAlg = tlv(0x30, cat(SHA256_RSA, NULL));
        byte[] name = tlv(0x30, tlv(0x31, tlv(0x30, cat(OID_CN, tlv(0x0C, "atvremote".getBytes(StandardCharsets.UTF_8))))));
        long now = System.currentTimeMillis();
        byte[] validity = tlv(0x30, cat(utc(now - 86400000L), utc(now + 3650L * 86400000L)));
        byte[] serial = new byte[8];
        new SecureRandom().nextBytes(serial);
        serial[0] = (byte) ((serial[0] & 0x3F) | 0x40);
        byte[] version = {(byte) 0xA0, 0x03, 0x02, 0x01, 0x02};
        byte[] tbs = tlv(0x30, cat(version, tlv(0x02, serial), sigAlg, name, validity, name,
                kp.getPublic().getEncoded()));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        return tlv(0x30, cat(tbs, sigAlg, tlv(0x03, bits)));
    }

    private static byte[] utc(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(new Date(millis)).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int n = content.length;
        if (n < 128) {
            out.write(n);
        } else if (n < 256) {
            out.write(0x81);
            out.write(n);
        } else {
            out.write(0x82);
            out.write(n >> 8);
            out.write(n & 0xFF);
        }
        out.write(content, 0, n);
        return out.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }
}
