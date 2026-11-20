package dev.wolklaw.soot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spark Link's connection to the paired PC. Finds it (mDNS, or the address the owner typed, or the
 * last address that worked), keeps one pinned mutual-TLS connection open, reconnects after a drop,
 * and carries the v1 features: links, clipboard, files, notifications, battery, ring.
 *
 * With BACKGROUND CONNECTION on it runs as a foreground service with a lasting notification; with
 * it off it runs only while Soot is on screen.
 */
public final class LinkService extends Service {
    private static final String TAG = "SparkLink";
    static final String ACT_STOP = "stop", ACT_ACCEPT = "accept", ACT_REJECT = "reject", ACT_STOP_RING = "stop-ring",
            ACT_SEND = "send", ACT_RECHECK = "recheck";
    private static final String CH_STATUS = "link-status", CH_EVENTS = "link-events", CH_RING = "link-ring";
    private static final int N_STATUS = 1, N_RING = 2;
    private static final int[] BACKOFF = { 1, 2, 5, 10, 30 };

    // ---- what the screens read ----

    enum State { OFF, SEARCHING, CONNECTING, CONNECTED, UNREACHABLE, FORGOTTEN }

    interface Listener { void onLinkChanged(); }

    static volatile State state = State.OFF;
    static volatile String detail = "";
    static volatile Set<String> pcFeatures = new HashSet<>();
    static Listener listener;
    private static LinkService running;
    private static int screensShown;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean stopping;
    private volatile LinkConnection conn;
    private Thread worker;
    private final Object wake = new Object();
    private int failures, forgottenInARow;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private boolean resolving;
    private String foundHost;
    private int foundPort;
    private ConnectivityManager.NetworkCallback netCallback;
    private BroadcastReceiver batteryReceiver;
    private int batteryLevel = -1;
    private boolean batteryCharging;
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;
    private String lastClipFromPc;
    private MediaPlayer ringer;
    private int ringOldVolume = -1;
    private long lastHeard;

    // ---- starting and stopping ----

    /** Called by Soot's screens as they appear and go away. Starts the link when there is a paired PC. */
    static void screenShown(Context c) {
        screensShown++;
        main.removeCallbacksAndMessages("stop");
        start(c);
    }

    static void screenHidden(Context c) {
        screensShown = Math.max(0, screensShown - 1);
        if (screensShown == 0 && !LinkState.background(c))
            main.postAtTime(() -> { if (screensShown == 0 && !LinkState.background(c)) stop(c); }, "stop", android.os.SystemClock.uptimeMillis() + 20000);
    }

    static boolean visible() { return screensShown > 0; }

    static void start(Context c) {
        if (!LinkState.paired(c)) return;
        Intent i = new Intent(c, LinkService.class);
        try {
            if (LinkState.background(c)) c.startForegroundService(i);
            else if (visible()) c.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "could not start: " + e);       // not allowed from the background right now; the next screen start retries
        }
    }

    static void stop(Context c) { c.stopService(new Intent(c, LinkService.class)); }

    /** After a switch changes: restart in the right mode and tell the PC the new feature list. */
    static void settingsChanged(Context c) {
        LinkService s = running;
        if (s != null && LinkState.background(c) != s.foreground) { stop(c); main.postDelayed(() -> start(c), 300); return; }
        if (s == null) { start(c); return; }
        s.sendHello();
        s.updateClipListener();
    }

    private boolean foreground;

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        channels();
        foreground = LinkState.background(this);
        if (foreground) startForeground(N_STATUS, statusNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        setState(State.SEARCHING, "");
        startDiscovery();
        watchNetwork();
        watchBattery();
        updateClipListener();
        worker = new Thread(this::run, "spark-link");
        worker.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACT_STOP: LinkState.setBackground(this, false); stopSelf(); break;
                case ACT_ACCEPT: answerOffer(intent.getIntExtra("offer", -1), true); break;
                case ACT_REJECT: answerOffer(intent.getIntExtra("offer", -1), false); break;
                case ACT_STOP_RING: stopRing(); break;
                case ACT_SEND: queueFiles(intent); break;
                case ACT_RECHECK: kick(); break;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        running = null;
        LinkConnection c = conn;
        if (c != null) c.close();
        if (worker != null) worker.interrupt();
        stopDiscovery();
        if (netCallback != null) try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback); } catch (Exception ignored) { }
        if (batteryReceiver != null) unregisterReceiver(batteryReceiver);
        if (clipListener != null) getSystemService(ClipboardManager.class).removePrimaryClipChangedListener(clipListener);
        stopRing();
        for (Incoming in : incoming.values()) in.discard(this);
        incoming.clear();
        setState(State.OFF, "");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void setState(State s, String d) {
        state = s;
        detail = d == null ? "" : d;
        if (foreground && !stopping) getSystemService(NotificationManager.class).notify(N_STATUS, statusNotification());
        main.post(() -> { if (listener != null) listener.onLinkChanged(); });
    }

    /** Wakes the connection loop now instead of at the end of its backoff (the network changed, an address was typed). */
    static void recheck() { LinkService s = running; if (s != null) s.kick(); }

    private void kick() { failures = 0; synchronized (wake) { wake.notifyAll(); } }

    // ---- the connection loop ----

    private void run() {
        while (!stopping) {
            if (!LinkState.paired(this)) { main.post(this::stopSelf); return; }
            String[] where = address();
            if (where == null) {
                setState(State.SEARCHING, "");
                pause(10);
                continue;
            }
            setState(State.CONNECTING, where[0]);
            try {
                session(where[0], Integer.parseInt(where[1]));
            } catch (LinkConnection.Forgotten f) {
                if (++forgottenInARow >= 2) {
                    setState(State.FORGOTTEN, "");
                    Log.i(TAG, "the PC refused this phone's certificate twice: " + f.getMessage());
                    pause(30);
                    continue;
                }
            } catch (Exception e) {
                if (stopping) return;
                Log.i(TAG, "link to " + where[0] + " ended: " + e);
                forgottenInARow = 0;
                if (where[0].equals(foundHost)) foundHost = null;       // look again
            }
            conn = null;
            pcFeatures = new HashSet<>();
            if (stopping) return;
            if (state != State.FORGOTTEN) setState(failures >= 2 ? State.UNREACHABLE : State.SEARCHING, "");
            pause(BACKOFF[Math.min(failures, BACKOFF.length - 1)]);
            failures++;
        }
    }

    private void pause(int seconds) {
        synchronized (wake) { try { wake.wait(seconds * 1000L); } catch (InterruptedException ignored) { } }
    }

    /** Where to try: the typed address, then what mDNS found, then the last address that worked. */
    private String[] address() {
        String typed = LinkState.manualAddress(this);
        if (!typed.isEmpty()) {
            String[] hp = splitAddress(typed);
            if (hp != null) return hp;
        }
        if (foundHost != null) return new String[] { foundHost, String.valueOf(foundPort) };
        String h = LinkState.host(this);
        return h.isEmpty() ? null : new String[] { h, String.valueOf(LinkState.port(this)) };
    }

    /** "192.168.1.23:47100" or "192.168.1.23" to host and port; null when it isn't an address. */
    static String[] splitAddress(String s) {
        s = s.trim().replaceFirst("^[a-z]+://", "").replaceAll("/.*$", "");
        if (s.isEmpty()) return null;
        String host = s, port = String.valueOf(LinkState.PORT);
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) { host = s.substring(0, colon); port = s.substring(colon + 1); }
        try { int p = Integer.parseInt(port); if (p < 1 || p > 65535) return null; } catch (NumberFormatException e) { return null; }
        return host.matches("[A-Za-z0-9._\\-\\[\\]:]+") ? new String[] { host, port } : null;
    }

    private void session(String host, int port) throws Exception {
        LinkConnection c = LinkConnection.open(this, host, port, LinkState.pcFp(this), true);
        conn = c;
        if (stopping) { c.close(); return; }
        try {
            sendHello(c, true);
            c.setReadTimeout(10000);
            JSONObject first = c.readMessage();         // an unknown certificate is refused here, as a TLS alert
            if (!"hello".equals(first.optString("type"))) throw new IOException("the PC did not answer hello");
            forgottenInARow = 0;
            failures = 0;
            if (!host.equals(LinkState.manualAddress(this))) LinkState.saveAddress(this, host, port);
            handle(c, first);
            c.setReadTimeout(5000);
            lastHeard = System.currentTimeMillis();
            long lastPing = System.currentTimeMillis();
            while (!stopping) {
                JSONObject m;
                try {
                    m = c.readMessage();
                } catch (java.net.SocketTimeoutException quiet) {
                    m = null;
                }
                long now = System.currentTimeMillis();
                if (m != null) { lastHeard = now; handle(c, m); }
                if (now - lastHeard > 75000) throw new IOException("nothing from the PC for 75 s");
                if (now - lastPing >= 30000) { lastPing = now; c.send(msg("ping")); }
            }
        } finally {
            c.close();
            failIncoming();
        }
    }

    private static JSONObject msg(String type) {
        JSONObject o = new JSONObject();
        try { o.put("type", type); } catch (Exception ignored) { }
        return o;
    }

    private JSONObject hello(boolean first) throws Exception {
        JSONObject h = msg("hello");
        h.put("v", 1);
        if (first) h.put("id", 0);
        JSONObject d = new JSONObject();
        d.put("id", LinkState.deviceId(this));
        d.put("name", LinkState.deviceName(this));
        d.put("kind", "phone");
        h.put("device", d);
        h.put("features", new JSONArray(LinkState.features(this)));
        return h;
    }

    private void sendHello(LinkConnection c, boolean first) throws Exception { c.send(hello(first)); }

    /** Sends the feature list again, after a switch changed. */
    private void sendHello() {
        LinkConnection c = conn;
        if (c == null || state != State.CONNECTED) return;
        new Thread(() -> { try { sendHello(c, false); } catch (Exception ignored) { } }).start();
        if (both("battery")) sendBattery(true);
    }

    /** True when the phone has this feature on and the PC listed it in its last hello. */
    private boolean both(String feature) { return pcFeatures.contains(feature) && LinkState.features(this).contains(feature); }

    static boolean connected() { return running != null && state == State.CONNECTED; }
    static boolean can(Context c, String feature) { LinkService s = running; return s != null && state == State.CONNECTED && s.both(feature); }

    private void send(JSONObject m) {
        LinkConnection c = conn;
        if (c == null) return;
        try { c.send(m); } catch (IOException e) { Log.i(TAG, "send failed: " + e); c.close(); }
    }

    private void reply(JSONObject to, String type, Object... kv) {
        JSONObject r = msg(type);
        try {
            r.put("re", to.optInt("id"));
            for (int i = 0; i + 1 < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        } catch (Exception ignored) { }
        send(r);
    }

    // ---- what the PC sends ----

    private void handle(LinkConnection c, JSONObject m) throws IOException {
        String type = m.optString("type");
        switch (type) {
            case "hello": {
                JSONArray f = m.optJSONArray("features");
                Set<String> got = new HashSet<>();
                for (int i = 0; f != null && i < f.length(); i++) got.add(f.optString(i));
                pcFeatures = got;
                JSONObject d = m.optJSONObject("device");
                if (d != null && !d.optString("name").isEmpty() && !d.optString("name").equals(LinkState.pcName(this)))
                    LinkState.prefs(this).edit().putString("pc-name", clean(d.optString("name"), 64)).apply();
                boolean wasConnected = state == State.CONNECTED;
                setState(State.CONNECTED, c.host);
                if (both("battery")) sendBattery(true);
                if (!wasConnected) sendQueued();
                break;
            }
            case "ping": reply(m, "pong"); break;
            case "pong": break;
            case "url.open": {
                if (!both("url")) { reply(m, "error", "reason", "feature-off"); break; }
                String url = m.optString("url");
                if (url.length() > 8192 || !url.matches("(?i)^https?://\\S+$")) { reply(m, "error", "reason", "bad-message"); break; }
                main.post(() -> openUrl(url));
                break;
            }
            case "clipboard.set": {
                if (!both("clipboard")) { reply(m, "error", "reason", "feature-off"); break; }
                String text = m.optString("text");
                main.post(() -> setClipboard(text));
                break;
            }
            case "ring":
                if (LinkState.on(this, "ring")) main.post(this::ring);
                else reply(m, "error", "reason", "feature-off");
                break;
            case "unpair":
                Log.i(TAG, "the PC unpaired this phone");
                String gone = LinkState.pcName(this);
                LinkState.forgetPc(this);
                notifyEvent("UNPAIRED", gone + " removed this phone. Pair again from Soot's Link to PC screen.", null);
                stopping = true;
                main.post(this::stopSelf);
                throw new IOException("unpaired by the PC");
            case "file.offer": onOffer(m); break;
            case "file.chunk": onChunk(c, m); break;
            case "file.cancel": onCancel(m.optInt("file", -1)); break;
            case "file.accept": case "file.reject": case "file.done": onOutgoingReply(type, m); break;
            case "error": Log.i(TAG, "the PC refused message " + m.optInt("re") + ": " + m.optString("reason")); break;
            default: break;       // unknown types are ignored, so either side can add messages later
        }
    }

    private static String clean(String s, int max) {
        s = s.replaceAll("\\p{Cntrl}", "").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    // ---- url ----

    private void openUrl(String url) {
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url), this, BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (visible()) { startActivity(view); return; }         // Soot is on screen: open it straight away
        // Android doesn't let a background service open screens, so the link waits in a notification
        PendingIntent open = PendingIntent.getActivity(this, url.hashCode(), view, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        notifyEvent("LINK FROM " + LinkState.pcName(this).toUpperCase(Locale.ROOT), url, open);
    }

    /** Sends a link to the PC. False when the PC isn't connected or one side has links off. */
    static boolean sendUrl(Context c, String url) {
        LinkService s = running;
        if (s == null || !s.both("url") || url == null || !url.matches("(?i)^https?://\\S+$") || url.length() > 8192) return false;
        JSONObject m = msg("url.open");
        try { m.put("url", url); } catch (Exception e) { return false; }
        new Thread(() -> s.send(m)).start();
        return true;
    }

    // ---- clipboard ----

    private void setClipboard(String text) {
        lastClipFromPc = text;
        ClipData clip = ClipData.newPlainText("From " + LinkState.pcName(this), text);
        getSystemService(ClipboardManager.class).setPrimaryClip(clip);
    }

    /** Text copied in Soot goes to the PC on its own (Android lets an app read the clipboard only while it is on screen). */
    private void updateClipListener() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (clipListener != null) { cm.removePrimaryClipChangedListener(clipListener); clipListener = null; }
        if (!LinkState.on(this, "clipboard")) return;
        clipListener = () -> {
            try {
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return;
                CharSequence t = clip.getItemAt(0).getText();
                if (t != null) sendClipboard(this, t.toString());
            } catch (Exception ignored) { }       // not readable from the background
        };
        cm.addPrimaryClipChangedListener(clipListener);
    }

    /** Sends clipboard text to the PC. False when the PC isn't connected or one side has the clipboard off. */
    static boolean sendClipboard(Context c, String text) {
        LinkService s = running;
        if (s == null || !s.both("clipboard") || text == null || text.isEmpty()) return false;
        if (text.equals(s.lastClipFromPc)) return true;          // never send back what just came from the PC
        if (text.getBytes(StandardCharsets.UTF_8).length > LinkConnection.MAX_JSON - 1024) return false;
        JSONObject m = msg("clipboard.set");
        try { m.put("text", text); } catch (Exception e) { return false; }
        new Thread(() -> s.send(m)).start();
        return true;
    }

    // ---- battery ----

    private void watchBattery() {
        batteryReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                int pct = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
                boolean charging = plugged != 0;
                if (pct == batteryLevel && charging == batteryCharging) return;
                batteryLevel = pct;
                batteryCharging = charging;
                sendBattery(false);
            }
        };
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private void sendBattery(boolean always) {
        if (batteryLevel < 0 || !both("battery")) return;
        JSONObject m = msg("battery");
        try { m.put("level", batteryLevel); m.put("charging", batteryCharging); } catch (Exception ignored) { }
        new Thread(() -> send(m)).start();
    }

    // ---- notifications to the PC ----

    static void notificationPosted(String app, String title, String text, String key) {
        LinkService s = running;
        if (s == null || !s.both("notify")) return;
        JSONObject m = msg("notify.posted");
        try {
            m.put("app", clean(app, 128));
            m.put("title", clean(title, 512));
            m.put("text", text.length() > 4000 ? text.substring(0, 4000) : text);
            m.put("key", key.length() > 256 ? key.substring(0, 256) : key);
        } catch (Exception ignored) { }
        new Thread(() -> s.send(m)).start();
    }

    static void notificationRemoved(String key) {
        LinkService s = running;
        if (s == null || !s.both("notify")) return;
        JSONObject m = msg("notify.removed");
        try { m.put("key", key.length() > 256 ? key.substring(0, 256) : key); } catch (Exception ignored) { }
        new Thread(() -> s.send(m)).start();
    }

    // ---- find my phone ----

    private void ring() {
        if (ringer != null) return;
        AudioManager am = getSystemService(AudioManager.class);
        ringOldVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
        try { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0); } catch (Exception ignored) { }
        try {
            Uri tone = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
            if (tone == null) tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringer = new MediaPlayer();
            ringer.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            ringer.setDataSource(this, tone);
            ringer.setLooping(true);
            ringer.prepare();
            ringer.start();
        } catch (Exception e) {
            Log.w(TAG, "could not play the ring: " + e);
            if (ringer != null) { ringer.release(); ringer = null; }
        }
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, LinkService.class).setAction(ACT_STOP_RING), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent full = PendingIntent.getActivity(this, 3, new Intent(this, LinkActivity.class).putExtra("ring", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH_RING)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("FIND MY PHONE")
                .setContentText(LinkState.pcName(this) + " is ringing this phone. Tap to stop.")
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(stop)
                .setDeleteIntent(stop)
                .setFullScreenIntent(full, true)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "STOP", stop).build())
                .build();
        getSystemService(NotificationManager.class).notify(N_RING, n);
        main.postAtTime(this::stopRing, "ring", android.os.SystemClock.uptimeMillis() + 10 * 60 * 1000);      // never longer than 10 minutes
    }

    static void stopRinging() { LinkService s = running; if (s != null) s.stopRing(); }

    private void stopRing() {
        main.removeCallbacksAndMessages("ring");
        if (ringer != null) { try { ringer.stop(); } catch (Exception ignored) { } ringer.release(); ringer = null; }
        if (ringOldVolume >= 0) {
            try { getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_ALARM, ringOldVolume, 0); } catch (Exception ignored) { }
            ringOldVolume = -1;
        }
        getSystemService(NotificationManager.class).cancel(N_RING);
    }

    // ---- files from the PC ----

    private static final long MAX_FILE = 16L << 30;

    /** A file the PC is sending, written into Downloads as it arrives. */
    private static final class Incoming {
        int id;
        String name, sha;
        long size, got;
        int nextSeq;
        Uri uri;
        OutputStream out;
        MessageDigest md;
        boolean accepted;

        void discard(Context c) {
            try { if (out != null) out.close(); } catch (IOException ignored) { }
            if (uri != null) try { c.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
            c.getSystemService(NotificationManager.class).cancel(1000 + id);
        }
    }

    private final Map<Integer, Incoming> incoming = new ConcurrentHashMap<>();

    /** Removes path parts, control characters and leading dots from a name the PC sent. */
    static String safeName(String name) {
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").replaceFirst("^[.\\s]+", "").trim();
        if (name.length() > 200) name = name.substring(name.length() - 200);
        return name.isEmpty() ? "file" : name;
    }

    private void onOffer(JSONObject m) {
        int id = m.optInt("id", -1);
        String sha = m.optString("sha256").toLowerCase(Locale.ROOT);
        long size = m.optLong("size", -1);
        if (!both("files")) { reply(m, "file.reject", "reason", "off"); return; }
        if (size < 0 || size > MAX_FILE || !sha.matches("[0-9a-f]{64}") || m.optString("name").isEmpty()) { reply(m, "error", "reason", "bad-message"); return; }
        if (incoming.size() >= 4) { reply(m, "file.reject", "reason", "busy"); return; }
        if (new File(getFilesDir(), ".").getUsableSpace() < size + (64L << 20)) { reply(m, "file.reject", "reason", "space"); return; }
        Incoming in = new Incoming();
        in.id = id;
        in.name = safeName(m.optString("name"));
        in.sha = sha;
        in.size = size;
        incoming.put(id, in);
        if (LinkState.alwaysAccept(this)) { answerOffer(id, true); return; }
        PendingIntent yes = PendingIntent.getService(this, 2000 + id, new Intent(this, LinkService.class).setAction(ACT_ACCEPT).putExtra("offer", id), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent no = PendingIntent.getService(this, 3000 + id, new Intent(this, LinkService.class).setAction(ACT_REJECT).putExtra("offer", id), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_EVENTS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("FILE FROM " + LinkState.pcName(this).toUpperCase(Locale.ROOT))
                .setContentText(in.name + "  ·  " + sizeText(size))
                .setDeleteIntent(no)
                .setTimeoutAfter(120000)
                .addAction(new Notification.Action.Builder(null, "SAVE TO DOWNLOADS", yes).build())
                .addAction(new Notification.Action.Builder(null, "DECLINE", no).build())
                .build();
        getSystemService(NotificationManager.class).notify(1000 + id, n);
        main.postDelayed(() -> { Incoming x = incoming.get(id); if (x != null && !x.accepted) { incoming.remove(id); x.discard(this); replyAsync(id, "file.reject", "timeout"); } }, 120000);
    }

    private void replyAsync(int re, String type, String reason) {
        JSONObject r = msg(type);
        try { r.put("re", re); if (reason != null) r.put("reason", reason); } catch (Exception ignored) { }
        new Thread(() -> send(r)).start();
    }

    private void answerOffer(int id, boolean yes) {
        Incoming in = incoming.get(id);
        if (in == null || in.accepted) return;
        getSystemService(NotificationManager.class).cancel(1000 + id);
        if (!yes) { incoming.remove(id); replyAsync(id, "file.reject", "declined"); return; }
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, in.name);       // Android adds " (1)" itself when the name is taken
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            in.uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (in.uri == null) throw new IOException("Downloads refused the file");
            in.out = getContentResolver().openOutputStream(in.uri);
            in.md = MessageDigest.getInstance("SHA-256");
            in.accepted = true;
            progress(in);
            replyAsync(id, "file.accept", null);
        } catch (Exception e) {
            Log.w(TAG, "could not start saving: " + e);
            incoming.remove(id);
            in.discard(this);
            replyAsync(id, "file.reject", "space");
        }
    }

    private void onChunk(LinkConnection c, JSONObject m) throws IOException {
        byte[] data = c.readFrame(LinkConnection.MAX_CHUNK);       // the binary frame always follows, whatever we do with it
        int id = m.optInt("file", -1);
        Incoming in = incoming.get(id);
        if (in == null || !in.accepted) return;                   // cancelled or declined meanwhile
        try {
            if (m.optInt("seq", -1) != in.nextSeq++ || in.got + data.length > in.size) throw new IOException("chunks out of order or too long");
            in.out.write(data);
            in.md.update(data);
            in.got += data.length;
            if (in.got == in.size || in.got % (4 << 20) < data.length) progress(in);
            if (!m.optBoolean("last")) return;
            in.out.close();
            in.out = null;
            StringBuilder hex = new StringBuilder();
            for (byte b : in.md.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
            boolean ok = in.got == in.size && hex.toString().equals(in.sha);
            incoming.remove(id);
            if (ok) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(in.uri, v, null, null);
                saved(in);
            } else {
                in.discard(this);
                notifyEvent("FILE NOT SAVED", in.name + " arrived damaged and was deleted.", null);
            }
            replyAsync(id, "file.done", null, "ok", ok);
        } catch (IOException e) {
            incoming.remove(id);
            in.discard(this);
            JSONObject cancel = msg("file.cancel");
            try { cancel.put("file", id); } catch (Exception ignored) { }
            send(cancel);
            notifyEvent("FILE NOT SAVED", in.name + ": " + e.getMessage(), null);
        }
    }

    private void replyAsync(int re, String type, String reason, String key, Object value) {
        JSONObject r = msg(type);
        try { r.put("re", re); r.put(key, value); } catch (Exception ignored) { }
        send(r);
    }

    private void onCancel(int id) {
        Incoming in = incoming.remove(id);
        if (in != null) { in.discard(this); notifyEvent("TRANSFER CANCELLED", in.name, null); }
        Outgoing out = outgoing.get(id);
        if (out != null) out.cancelled = true;
    }

    private void failIncoming() {
        for (Incoming in : incoming.values()) {
            if (in.accepted) notifyEvent("FILE NOT SAVED", in.name + ": the connection to the PC dropped.", null);
            in.discard(this);
        }
        incoming.clear();
    }

    private void progress(Incoming in) {
        int pct = in.size == 0 ? 100 : (int) (in.got * 100 / in.size);
        Notification n = new Notification.Builder(this, CH_EVENTS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("RECEIVING " + in.name)
                .setContentText(sizeText(in.got) + " of " + sizeText(in.size))
                .setProgress(100, pct, false)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
        getSystemService(NotificationManager.class).notify(1000 + in.id, n);
    }

    private void saved(Incoming in) {
        getSystemService(NotificationManager.class).cancel(1000 + in.id);
        Intent view = new Intent(Intent.ACTION_VIEW).setData(in.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent open = PendingIntent.getActivity(this, 4000 + in.id, view, PendingIntent.FLAG_IMMUTABLE);
        notifyEvent("SAVED TO DOWNLOADS", in.name + "  ·  " + sizeText(in.size), open);
    }

    static String sizeText(long b) {
        if (b < 1024) return b + " B";
        if (b < 1 << 20) return String.format(Locale.ROOT, "%.0f KB", b / 1024.0);
        if (b < 1L << 30) return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0);
    }

    // ---- files to the PC ----

    /** A file waiting in Soot's cache to go to the PC. Copied there first so the sending app's permission can lapse. */
    private static final class Outgoing {
        File file;
        String name, sha;
        long size;
        int id = -1;
        volatile String answer;
        volatile boolean cancelled;
    }

    private final ArrayDeque<Outgoing> queue = new ArrayDeque<>();
    private final Map<Integer, Outgoing> outgoing = new ConcurrentHashMap<>();
    private Thread sender;

    /** Hands files to the link: content URIs in the intent's ClipData, read with the permission that came with it. */
    static void sendFiles(Context c, ClipData uris) {
        Intent i = new Intent(c, LinkService.class).setAction(ACT_SEND).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setClipData(uris);
        if (LinkState.background(c)) c.startForegroundService(i); else c.startService(i);
    }

    private void queueFiles(Intent intent) {
        ClipData clip = intent.getClipData();
        if (clip == null) return;
        Uri[] uris = new Uri[clip.getItemCount()];
        for (int i = 0; i < uris.length; i++) uris[i] = clip.getItemAt(i).getUri();
        new Thread(() -> {
            File box = new File(getCacheDir(), "link-out");
            box.mkdirs();
            for (Uri u : uris) {
                if (u == null) continue;
                Outgoing o = new Outgoing();
                try {
                    o.name = safeName(displayName(u));
                    o.file = File.createTempFile("out", ".bin", box);
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    try (InputStream in = getContentResolver().openInputStream(u); OutputStream out = new FileOutputStream(o.file)) {
                        if (in == null) throw new IOException("the app that shared it closed it");
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); md.update(buf, 0, n); o.size += n; }
                    }
                    StringBuilder hex = new StringBuilder();
                    for (byte b : md.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
                    o.sha = hex.toString();
                    synchronized (queue) { queue.add(o); }
                } catch (Exception e) {
                    if (o.file != null) o.file.delete();
                    notifyEvent("NOT SENT", (o.name == null ? "a file" : o.name) + ": " + e.getMessage(), null);
                }
            }
            sendQueued();
        }).start();
    }

    private String displayName(Uri u) {
        if (ContentResolver.SCHEME_CONTENT.equals(u.getScheme())) {
            try (Cursor c = getContentResolver().query(u, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
            } catch (Exception ignored) { }
        }
        String last = u.getLastPathSegment();
        return last == null ? "file" : last;
    }

    /** Sends what is waiting, one file at a time, when connected and both sides have files on. */
    private synchronized void sendQueued() {
        if (sender != null && sender.isAlive()) return;
        sender = new Thread(() -> {
            while (!stopping) {
                Outgoing o;
                synchronized (queue) { o = queue.peek(); }
                if (o == null) return;
                if (!both("files")) {
                    if (state == State.CONNECTED) {
                        synchronized (queue) { queue.poll(); }
                        o.file.delete();
                        notifyEvent("NOT SENT", o.name + ": files are switched off on " + (LinkState.on(this, "files") ? LinkState.pcName(this) : "this phone") + ".", null);
                        continue;
                    }
                    notifyEvent("WAITING FOR THE PC", o.name + " goes as soon as " + LinkState.pcName(this) + " is connected.", null);
                    return;                     // tried again at the next hello
                }
                boolean done = sendOne(o);
                if (done || o.answer != null) { synchronized (queue) { queue.poll(); } o.file.delete(); }
                else return;                    // the link dropped: keep it for the next connection
            }
        }, "spark-link-send");
        sender.start();
    }

    /** True when the PC has answered for good (saved, declined, failed); false when the link dropped first. */
    private boolean sendOne(Outgoing o) {
        LinkConnection c = conn;
        if (c == null) return false;
        o.answer = null;
        o.cancelled = false;
        int nid = 5000 + Math.abs(o.name.hashCode() % 1000);
        try {
            JSONObject offer = msg("file.offer");
            offer.put("name", o.name);
            offer.put("size", o.size);
            offer.put("sha256", o.sha);
            o.id = c.send(offer);
            outgoing.put(o.id, o);
            progressOut(nid, o, 0, "WAITING FOR " + LinkState.pcName(this).toUpperCase(Locale.ROOT));
            long until = System.currentTimeMillis() + 130000;
            while (o.answer == null && conn == c && System.currentTimeMillis() < until) Thread.sleep(200);
            if (o.answer == null) { getSystemService(NotificationManager.class).cancel(nid); return conn != c ? false : finish(nid, o, "NOT SENT", "the PC did not answer."); }
            if (o.answer.startsWith("file.reject")) return finish(nid, o, "NOT SENT", o.name + ": " + rejectText(o.answer.substring(o.answer.indexOf(':') + 1)));
            o.answer = null;
            byte[] buf = new byte[LinkConnection.MAX_CHUNK];
            int seq = 0;
            long sent = 0;
            try (FileInputStream in = new FileInputStream(o.file)) {
                do {
                    int n = 0, r;
                    while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
                    sent += n;
                    JSONObject ch = msg("file.chunk");
                    ch.put("file", o.id);
                    ch.put("seq", seq++);
                    ch.put("last", sent >= o.size);
                    if (o.cancelled) return finish(nid, o, "TRANSFER CANCELLED", o.name);
                    c.sendChunk(ch, buf, n);
                    if (seq % 64 == 0) progressOut(nid, o, sent, "SENDING");
                } while (sent < o.size);
            }
            until = System.currentTimeMillis() + 120000;
            while (o.answer == null && conn == c && System.currentTimeMillis() < until) Thread.sleep(200);
            if (o.answer == null) { getSystemService(NotificationManager.class).cancel(nid); return false; }
            return finish(nid, o, o.answer.equals("file.done:ok") ? "SENT TO " + LinkState.pcName(this).toUpperCase(Locale.ROOT) : "NOT SENT",
                    o.answer.equals("file.done:ok") ? o.name + "  ·  " + sizeText(o.size) : o.name + " arrived damaged on the PC.");
        } catch (Exception e) {
            getSystemService(NotificationManager.class).cancel(nid);
            return false;
        } finally {
            if (o.id >= 0) outgoing.remove(o.id);
        }
    }

    private boolean finish(int nid, Outgoing o, String title, String text) {
        getSystemService(NotificationManager.class).cancel(nid);
        notifyEvent(title, text, null);
        o.answer = o.answer == null ? "done" : o.answer;
        return true;
    }

    private static String rejectText(String reason) {
        switch (reason) {
            case "declined": return "declined on the PC.";
            case "off": return "files are switched off on the PC.";
            case "space": return "not enough space on the PC.";
            case "timeout": return "nobody answered on the PC.";
            case "busy": return "the PC is busy with other files.";
            default: return "the PC refused it.";
        }
    }

    private void onOutgoingReply(String type, JSONObject m) {
        Outgoing o = outgoing.get(m.optInt("re", -1));
        if (o == null) return;
        if (type.equals("file.reject")) o.answer = "file.reject:" + m.optString("reason");
        else if (type.equals("file.accept")) o.answer = "file.accept";
        else o.answer = m.optBoolean("ok") ? "file.done:ok" : "file.done:bad";
    }

    private void progressOut(int nid, Outgoing o, long sent, String what) {
        Notification.Builder b = new Notification.Builder(this, CH_EVENTS)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(what + "  ·  " + o.name)
                .setContentText(sizeText(sent) + " of " + sizeText(o.size))
                .setOnlyAlertOnce(true)
                .setOngoing(true);
        if (sent > 0) b.setProgress(100, o.size == 0 ? 100 : (int) (sent * 100 / o.size), false);
        else b.setProgress(0, 0, true);
        getSystemService(NotificationManager.class).notify(nid, b.build());
    }

    // ---- pairing (called by the Link screen, off the main thread) ----

    /** What the PC answered to a pairing request. */
    static final class PairResult {
        boolean ok;
        String reason = "", pcName = "";
    }

    /**
     * Pairs with the PC in the invite: TLS pinned to the invite's fingerprint, no client certificate, then
     * the pair message with this phone's certificate. Waits up to 120 s for the owner to confirm on the PC.
     */
    static PairResult pair(Context c, LinkState.Invite inv) throws Exception {
        PairResult res = new PairResult();
        String cert = Base64.encodeToString(LinkKeys.certificate(c).getEncoded(), Base64.NO_WRAP);
        try (LinkConnection conn = LinkConnection.open(c, inv.host, inv.port, inv.fp, false)) {
            JSONObject m = msg("pair");
            m.put("id", 1);
            m.put("v", 1);
            m.put("code", inv.code);
            JSONObject d = new JSONObject();
            d.put("id", LinkState.deviceId(c));
            d.put("name", LinkState.deviceName(c));
            d.put("kind", "phone");
            m.put("device", d);
            m.put("cert", cert);
            conn.send(m);
            conn.setReadTimeout(125000);
            JSONObject r = conn.readMessage();
            if ("pair.ok".equals(r.optString("type"))) {
                JSONObject pc = r.optJSONObject("device");
                String id = pc == null ? (inv.id == null ? "" : inv.id) : pc.optString("id", inv.id);
                String name = pc == null ? inv.name : pc.optString("name", inv.name);
                res.pcName = clean(name == null || name.isEmpty() ? "PC" : name, 64);
                LinkState.savePc(c, id, res.pcName, inv.fp, inv.host, inv.port);
                LinkState.prefs(c).edit().remove("manual").apply();
                res.ok = true;
            } else {
                res.reason = r.optString("reason", "refused");
            }
        }
        return res;
    }

    /** Unpairs: tells the PC if it is connected, then forgets it. */
    static void unpair(Context c) {
        LinkService s = running;
        LinkConnection conn = s == null ? null : s.conn;
        new Thread(() -> {
            if (conn != null) { try { conn.send(msg("unpair")); } catch (Exception ignored) { } conn.close(); }
        }).start();
        LinkState.forgetPc(c);
        stop(c);
    }

    // ---- finding the PC on the network ----

    private void startDiscovery() {
        nsd = getSystemService(NsdManager.class);
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { Log.i(TAG, "mDNS search did not start: " + e); }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceFound(NsdServiceInfo info) { synchronized (toResolve) { toResolve.add(info); } resolveNext(); }
            @Override public void onServiceLost(NsdServiceInfo info) { }
        };
        try { nsd.discoverServices("_sparklink._tcp", NsdManager.PROTOCOL_DNS_SD, discovery); } catch (Exception e) { discovery = null; }
    }

    private void stopDiscovery() {
        if (nsd != null && discovery != null) try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) { }
        discovery = null;
    }

    /** NsdManager resolves one service at a time, so found services wait their turn. */
    @SuppressWarnings("deprecation")
    private void resolveNext() {
        NsdServiceInfo next;
        synchronized (toResolve) {
            if (resolving || toResolve.isEmpty()) return;
            next = toResolve.poll();
            resolving = true;
        }
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo info, int e) { done(); }
                @Override public void onServiceResolved(NsdServiceInfo info) {
                    byte[] id = info.getAttributes().get("id");
                    InetAddress h = info.getHost();
                    if (id != null && h != null && new String(id, StandardCharsets.UTF_8).equals(LinkState.pcId(LinkService.this))) {
                        String host = h.getHostAddress();
                        boolean changed = !host.equals(foundHost) || info.getPort() != foundPort;
                        foundHost = host;
                        foundPort = info.getPort();
                        if (changed && state != State.CONNECTED) kick();
                    }
                    done();
                }
                private void done() { synchronized (toResolve) { resolving = false; } resolveNext(); }
            });
        } catch (Exception e) {
            synchronized (toResolve) { resolving = false; }
        }
    }

    /** A new network (back on Wi-Fi, say): look for the PC again now instead of waiting out the backoff. */
    private void watchNetwork() {
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                if (state == State.CONNECTED) return;
                main.post(() -> { stopDiscovery(); if (!stopping) startDiscovery(); });
                kick();
            }
        };
        try { getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(netCallback); } catch (Exception e) { netCallback = null; }
    }

    // ---- notifications on the phone ----

    private void channels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel status = new NotificationChannel(CH_STATUS, "Link to PC: connection", NotificationManager.IMPORTANCE_LOW);
        status.setShowBadge(false);
        nm.createNotificationChannel(status);
        nm.createNotificationChannel(new NotificationChannel(CH_EVENTS, "Link to PC: links and files", NotificationManager.IMPORTANCE_DEFAULT));
        NotificationChannel ring = new NotificationChannel(CH_RING, "Link to PC: find my phone", NotificationManager.IMPORTANCE_HIGH);
        ring.setSound(null, null);           // the sound is played on the alarm stream, at full volume
        ring.setBypassDnd(true);
        nm.createNotificationChannel(ring);
    }

    private Notification statusNotification() {
        String pc = LinkState.pcName(this);
        String text;
        switch (state) {
            case CONNECTED: text = "Connected to " + pc; break;
            case CONNECTING: text = "Connecting to " + pc + "..."; break;
            case FORGOTTEN: text = pc + " no longer knows this phone. Pair again."; break;
            case UNREACHABLE: text = pc + " is not reachable. Trying again."; break;
            default: text = "Looking for " + pc + " on this network"; break;
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, LinkActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent clip = PendingIntent.getActivity(this, 5, new Intent(this, ClipSendActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent off = PendingIntent.getService(this, 6, new Intent(this, LinkService.class).setAction(ACT_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CH_STATUS)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Link to PC")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false);
        if (state == State.CONNECTED && pcFeatures.contains("clipboard") && LinkState.on(this, "clipboard"))
            b.addAction(new Notification.Action.Builder(null, "SEND CLIPBOARD", clip).build());
        b.addAction(new Notification.Action.Builder(null, "TURN OFF", off).build());
        return b.build();
    }

    private int eventId = 100;

    private void notifyEvent(String title, String text, PendingIntent tap) {
        Notification.Builder b = new Notification.Builder(this, CH_EVENTS)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true);
        if (tap != null) b.setContentIntent(tap);
        getSystemService(NotificationManager.class).notify(eventId++ % 400 + 100, b.build());
    }
}
