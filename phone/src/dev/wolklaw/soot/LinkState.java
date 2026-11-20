package dev.wolklaw.soot;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What Spark Link keeps on the phone: its own id, the paired PC (id, name, certificate fingerprint, last address) and the switches. */
final class LinkState {
    static final int PORT = 47100;
    static final String[] FEATURES = { "url", "clipboard", "files", "notify", "battery", "ring" };

    private LinkState() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("spark-link", Context.MODE_PRIVATE); }

    /** This phone's Spark Link id: 32 random hex digits, made once. */
    static synchronized String deviceId(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("device-id", null);
        if (id == null) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            StringBuilder s = new StringBuilder();
            for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
            id = s.toString();
            p.edit().putString("device-id", id).apply();
        }
        return id;
    }

    /** The name the PC shows for this phone: the one set in Android's settings, or the model. */
    static String deviceName(Context c) {
        String n = null;
        try { n = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME); } catch (Exception ignored) { }
        if (n == null || n.trim().isEmpty()) n = Build.MODEL;
        n = n.replaceAll("\\p{Cntrl}", "").trim();
        return n.length() > 64 ? n.substring(0, 64) : n;
    }

    static boolean paired(Context c) { return prefs(c).contains("pc-fp"); }
    static String pcFp(Context c) { return prefs(c).getString("pc-fp", ""); }
    static String pcId(Context c) { return prefs(c).getString("pc-id", ""); }
    static String pcName(Context c) { return prefs(c).getString("pc-name", "PC"); }
    static String host(Context c) { return prefs(c).getString("host", ""); }
    static int port(Context c) { return prefs(c).getInt("port", PORT); }
    /** An address the owner typed because mDNS is blocked on their network. Used before discovery. */
    static String manualAddress(Context c) { return prefs(c).getString("manual", ""); }

    static void savePc(Context c, String id, String name, String fp, String host, int port) {
        prefs(c).edit().putString("pc-id", id).putString("pc-name", name == null || name.isEmpty() ? "PC" : name)
                .putString("pc-fp", fp).putString("host", host).putInt("port", port).apply();
    }

    static void saveAddress(Context c, String host, int port) { prefs(c).edit().putString("host", host).putInt("port", port).apply(); }

    static void forgetPc(Context c) {
        prefs(c).edit().remove("pc-id").remove("pc-name").remove("pc-fp").remove("host").remove("port").remove("manual").remove("always-accept").apply();
    }

    // ---- switches ----

    /** Each feature can be switched off on the phone. The shared clipboard and notifications start off. */
    static boolean on(Context c, String feature) {
        boolean dflt = !feature.equals("clipboard") && !feature.equals("notify");
        return prefs(c).getBoolean("f-" + feature, dflt);
    }

    static void set(Context c, String feature, boolean on) { prefs(c).edit().putBoolean("f-" + feature, on).apply(); }

    static List<String> features(Context c) {
        List<String> out = new ArrayList<>();
        for (String f : FEATURES) if (on(c, f) && (!f.equals("notify") || LinkNotifications.allowed(c))) out.add(f);
        return out;
    }

    static boolean alwaysAccept(Context c) { return prefs(c).getBoolean("always-accept", false); }
    static void setAlwaysAccept(Context c, boolean on) { prefs(c).edit().putBoolean("always-accept", on).apply(); }

    /** Off: Soot links to the PC only while it is open on screen. On: a background connection with a lasting notification. */
    static boolean background(Context c) { return prefs(c).getBoolean("background", true); }
    static void setBackground(Context c, boolean on) { prefs(c).edit().putBoolean("background", on).apply(); }

    // ---- the pairing link ----

    /** What a pairing QR code or link says. */
    static final class Invite {
        String host, id, fp, code, name;
        int port = PORT;
    }

    /** Reads sparklink://pair?v=1&host=..&port=..&id=..&fp=..&code=..[&name=..]. Null when it is not one. */
    static Invite parse(String link) {
        if (link == null) return null;
        Uri u;
        try { u = Uri.parse(link.trim()); } catch (Exception e) { return null; }
        if (!"sparklink".equalsIgnoreCase(u.getScheme()) || !"pair".equalsIgnoreCase(u.getHost())) return null;
        Invite i = new Invite();
        i.host = u.getQueryParameter("host");
        i.id = u.getQueryParameter("id");
        i.fp = u.getQueryParameter("fp");
        i.code = u.getQueryParameter("code");
        i.name = u.getQueryParameter("name");
        try { if (u.getQueryParameter("port") != null) i.port = Integer.parseInt(u.getQueryParameter("port")); } catch (NumberFormatException e) { return null; }
        if (!"1".equals(u.getQueryParameter("v"))) return null;
        if (i.host == null || i.host.isEmpty() || i.code == null || !i.code.matches("\\d{6}") || i.fp == null || !i.fp.toLowerCase(Locale.ROOT).matches("[0-9a-f]{64}")) return null;
        if (i.port < 1 || i.port > 65535) return null;
        i.fp = i.fp.toLowerCase(Locale.ROOT);
        return i;
    }
}
