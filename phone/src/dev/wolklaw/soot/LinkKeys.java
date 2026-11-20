package dev.wolklaw.soot;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.math.BigInteger;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.security.auth.x500.X500Principal;

/**
 * Spark Link's identity on this phone: an ECDSA P-256 key made once in the Android Keystore (it never
 * leaves it) and the self-signed certificate the Keystore makes for it. The PC is trusted only by the
 * SHA-256 fingerprint of its certificate, taken from the pairing QR code.
 */
final class LinkKeys {
    private static final String ALIAS = "spark-link";

    private LinkKeys() { }

    private static KeyStore store() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return ks;
    }

    /** This phone's certificate, made on first use. Its common name is the phone's Spark Link id. */
    static synchronized X509Certificate certificate(Context c) throws Exception {
        KeyStore ks = store();
        if (!ks.containsAlias(ALIAS)) {
            Calendar from = new GregorianCalendar(2026, Calendar.JANUARY, 1), to = new GregorianCalendar(2099, Calendar.DECEMBER, 31);
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1"))
                    // NONE is what the TLS stack asks for: it hashes the handshake itself and has the Keystore sign the digest
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(new X500Principal("CN=" + LinkState.deviceId(c)))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotBefore(from.getTime())
                    .setCertificateNotAfter(to.getTime())
                    .build());
            g.generateKeyPair();
        }
        return (X509Certificate) ks.getCertificate(ALIAS);
    }

    /** Forgets this phone's key: a new one is made at the next pairing. */
    static synchronized void forget() {
        try { store().deleteEntry(ALIAS); } catch (Exception ignored) { }
    }

    static String fingerprint(byte[] der) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(der);
            StringBuilder s = new StringBuilder();
            for (byte b : h) s.append(String.format(Locale.ROOT, "%02x", b));
            return s.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * TLS 1.3 to the PC, trusting only the certificate whose fingerprint is pcFp. With presentOwn the
     * phone shows its own certificate (every connection after pairing); without it (the pairing
     * connection) it shows none, and sends the certificate inside the pair message instead.
     */
    static SSLContext context(Context c, String pcFp, boolean presentOwn) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        KeyManager[] km = presentOwn ? new KeyManager[] { new OwnKey(certificate(c), (PrivateKey) store().getKey(ALIAS, null)) } : null;
        ctx.init(km, new TrustManager[] { new Pinned(pcFp) }, null);
        return ctx;
    }

    private static final String PEEK = "peek";

    /**
     * For pairing from a typed address, when there is no QR code to carry the PC's fingerprint: makes a
     * TLS connection that only reads the PC's certificate, then closes it. Nothing is sent. The owner
     * compares the start of this fingerprint with the key the PC window shows before any code is sent.
     */
    static String peek(String host, int port) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        Pinned trust = new Pinned(PEEK);
        ctx.init(null, new TrustManager[] { trust }, null);
        try (javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) ctx.getSocketFactory().createSocket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 5000);
            s.setSoTimeout(8000);
            s.setEnabledProtocols(new String[] { "TLSv1.3" });
            s.startHandshake();
        }
        if (trust.seen == null) throw new CertificateException("the PC sent no certificate");
        return trust.seen;
    }

    /** The first 16 hex digits of a fingerprint, upper case in groups of four, as the PC window shows them. */
    static String shortKey(String fp) {
        String s = fp.substring(0, 16).toUpperCase(Locale.ROOT);
        return s.substring(0, 4) + " " + s.substring(4, 8) + " " + s.substring(8, 12) + " " + s.substring(12, 16);
    }

    /** Hands the Keystore key and its certificate to the TLS stack. */
    private static final class OwnKey extends X509ExtendedKeyManager {
        private final X509Certificate cert;
        private final PrivateKey key;
        OwnKey(X509Certificate cert, PrivateKey key) { this.cert = cert; this.key = key; }
        @Override public String chooseClientAlias(String[] types, Principal[] issuers, Socket s) { return ALIAS; }
        @Override public String chooseEngineClientAlias(String[] types, Principal[] issuers, SSLEngine e) { return ALIAS; }
        @Override public String[] getClientAliases(String type, Principal[] issuers) { return new String[] { ALIAS }; }
        @Override public X509Certificate[] getCertificateChain(String alias) { return new X509Certificate[] { cert }; }
        @Override public PrivateKey getPrivateKey(String alias) { return key; }
        @Override public String[] getServerAliases(String type, Principal[] issuers) { return null; }
        @Override public String chooseServerAlias(String type, Principal[] issuers, Socket s) { return null; }
    }

    /** Accepts exactly one certificate, by fingerprint. No certificate authorities, no host names. */
    private static final class Pinned extends X509ExtendedTrustManager {
        private final String fp;
        String seen;
        Pinned(String fp) { this.fp = fp == null ? "" : fp.toLowerCase(Locale.ROOT); }

        private void check(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("the PC sent no certificate");
            String got = fingerprint(chain[0].getEncoded());
            seen = got;
            if (fp.equals(PEEK)) return;
            if (fp.isEmpty() || !MessageDigest.isEqual(got.getBytes(), fp.getBytes()))
                throw new CertificateException("this is not the paired PC (certificate " + got.substring(0, 12) + "...)");
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException { check(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String auth, Socket s) throws CertificateException { check(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String auth, SSLEngine e) throws CertificateException { check(chain); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException("the phone is never a server"); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String auth, Socket s) throws CertificateException { throw new CertificateException("the phone is never a server"); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String auth, SSLEngine e) throws CertificateException { throw new CertificateException("the phone is never a server"); }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
