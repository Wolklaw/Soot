package dev.wolklaw.soot;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The phone's copy of the vault. The PC sends it encrypted with a sync key agreed at pairing; it is
 * stored exactly as received, and the sync key itself is locked with a key in the Android Keystore.
 */
final class Vault {
    static final class Login { String origin, user, pass; String host() { return origin.replaceFirst("^https?://(www\\.)?", ""); } }
    static final class Code { String name, secret; }
    static final class Note { String title, body; }
    static final class Data { long ts; final List<Login> logins = new ArrayList<>(); final List<Note> notes = new ArrayList<>(); final List<Code> codes = new ArrayList<>(); }

    /** Thrown when the PC has not confirmed the pairing code yet, or its vault is locked. */
    static final class Refused extends Exception { final int status; Refused(int s, String m) { super(m); status = s; } }

    private static final String ALIAS = "soot-master";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("soot", Context.MODE_PRIVATE); }
    static boolean paired(Context c) { return prefs(c).contains("key"); }
    static String address(Context c) { return prefs(c).getString("address", ""); }
    static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }
    static byte[] unb64(String s) { return Base64.decode(s, Base64.NO_WRAP); }

    // ---- Android Keystore: the key that locks everything on this phone ----

    private static SecretKey master(Context c) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);
        KeyguardManager km = c.getSystemService(KeyguardManager.class);
        if (km != null && km.isDeviceSecure()) {
            // usable only within 5 minutes of the owner unlocking with fingerprint, face, PIN or pattern
            b.setUserAuthenticationRequired(true)
             .setUserAuthenticationParameters(300, KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(b.build());
        return kg.generateKey();
    }

    /** True when the phone has a screen lock, so the vault key is tied to it. */
    static boolean deviceSecure(Context c) {
        KeyguardManager km = c.getSystemService(KeyguardManager.class);
        return km != null && km.isDeviceSecure();
    }

    private static String seal(Context c, byte[] plain) throws Exception {
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, master(c));
        return b64(ci.getIV()) + "." + b64(ci.doFinal(plain));
    }

    private static byte[] open(Context c, String sealed) throws Exception {
        String[] p = sealed.split("\\.");
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.DECRYPT_MODE, master(c), new GCMParameterSpec(128, unb64(p[0])));
        return ci.doFinal(unb64(p[1]));
    }

    // ---- talking to Soot on the PC ----

    private static String http(String method, String url, String body) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(4000);
        con.setReadTimeout(8000);
        con.setRequestMethod(method);
        if (body != null) {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            con.setDoOutput(true);
            con.setFixedLengthStreamingMode(data.length);
            con.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = con.getOutputStream()) { os.write(data); }
        }
        int status = con.getResponseCode();
        InputStream in = status >= 400 ? con.getErrorStream() : con.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); in.close(); }
        String text = out.toString("UTF-8");
        if (status != 200) {
            String msg = "the PC answered " + status;
            try { msg = new JSONObject(text).optString("error", msg); } catch (Exception ignored) { }
            throw new Refused(status, msg);
        }
        return text;
    }

    private static byte[] sha256(byte[] a, String label) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(a);
        md.update(label.getBytes(StandardCharsets.UTF_8));
        return md.digest();
    }

    /** Agrees a key with the PC. Returns the six-digit code both screens must show. */
    static String pair(Context c, String address) throws Exception {
        address = address.trim().replaceFirst("^https?://", "").replaceAll("/+$", "");
        if (!address.contains(":")) address += ":47600";
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        JSONObject req = new JSONObject();
        req.put("name", Build.MANUFACTURER + " " + Build.MODEL);
        req.put("pub", b64(kp.getPublic().getEncoded()));
        JSONObject res = new JSONObject(http("POST", "http://" + address + "/pair", req.toString()));
        PublicKey theirs = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(unb64(res.getString("pub"))));
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(kp.getPrivate());
        ka.doPhase(theirs, true);
        byte[] shared = ka.generateSecret();
        byte[] key = sha256(shared, "soot-sync-key");
        byte[] s = sha256(shared, "soot-sync-sas");
        long n = ((s[0] & 0xFFL) << 24 | (s[1] & 0xFFL) << 16 | (s[2] & 0xFFL) << 8 | (s[3] & 0xFFL)) % 1000000L;
        prefs(c).edit().putString("address", address).putString("id", res.getString("id")).putString("key", seal(c, key)).apply();
        return String.format("%06d", n);
    }

    static void unpair(Context c) {
        prefs(c).edit().clear().apply();
        new File(c.getFilesDir(), "vault.bin").delete();
        try { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); ks.deleteEntry(ALIAS); } catch (Exception ignored) { }
    }

    /** Fetches the vault from the PC and keeps the encrypted copy. */
    static Data sync(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        String text = http("GET", "http://" + p.getString("address", "") + "/vault?id=" + p.getString("id", ""), null);
        Data d = decode(c, text);           // only keep it if it opens
        try (FileOutputStream fos = new FileOutputStream(new File(c.getFilesDir(), "vault.bin"))) { fos.write(text.getBytes(StandardCharsets.UTF_8)); }
        p.edit().putLong("synced", System.currentTimeMillis()).apply();
        return d;
    }

    /** The copy kept on the phone, or null if it has never synced. */
    static Data load(Context c) throws Exception {
        File f = new File(c.getFilesDir(), "vault.bin");
        if (!f.exists()) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(f)) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); }
        return decode(c, out.toString("UTF-8"));
    }

    private static Data decode(Context c, String text) throws Exception {
        JSONObject o = new JSONObject(text);
        byte[] key = open(c, prefs(c).getString("key", ""));
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, unb64(o.getString("iv"))));
        JSONObject v = new JSONObject(new String(ci.doFinal(unb64(o.getString("data"))), StandardCharsets.UTF_8));
        Data d = new Data();
        d.ts = v.optLong("ts");
        JSONArray a = v.optJSONArray("logins");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i);
            Login l = new Login(); l.origin = x.optString("o"); l.user = x.optString("u"); l.pass = x.optString("p");
            d.logins.add(l);
        }
        a = v.optJSONArray("notes");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i);
            Note n = new Note(); n.title = x.optString("title"); n.body = x.optString("body");
            d.notes.add(n);
        }
        a = v.optJSONArray("codes");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i);
            Code k = new Code(); k.name = x.optString("name"); k.secret = x.optString("secret");
            d.codes.add(k);
        }
        return d;
    }

    // ---- six-digit sign-in codes (RFC 6238) ----

    static String totp(String secret, long nowMs) {
        try {
            String clean = secret.toUpperCase().replaceAll("[^A-Z2-7]", "");
            ByteArrayOutputStream key = new ByteArrayOutputStream();
            int bits = 0, value = 0;
            for (char ch : clean.toCharArray()) {
                value = (value << 5) | "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(ch);
                bits += 5;
                if (bits >= 8) { key.write((value >>> (bits - 8)) & 0xFF); bits -= 8; }
            }
            if (key.size() == 0) return "------";
            long counter = nowMs / 30000L;
            byte[] msg = new byte[8];
            for (int i = 7; i >= 0; i--) { msg[i] = (byte) (counter & 0xFF); counter >>= 8; }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.toByteArray(), "HmacSHA1"));
            byte[] h = mac.doFinal(msg);
            int o = h[h.length - 1] & 0x0F;
            int num = ((h[o] & 0x7F) << 24) | ((h[o + 1] & 0xFF) << 16) | ((h[o + 2] & 0xFF) << 8) | (h[o + 3] & 0xFF);
            return String.format("%06d", num % 1000000);
        } catch (Exception e) {
            return "------";
        }
    }
}
