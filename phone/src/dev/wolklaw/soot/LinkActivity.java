package dev.wolklaw.soot;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.List;

/**
 * Link to PC: pairs Soot with Spark Link on a SparkOS PC (QR code, pasted link or typed address),
 * then shows the connection, the switches for each feature, and what can be sent from here.
 */
public final class LinkActivity extends Activity implements LinkService.Listener {
    private static final int SCAN = 1, PICK_FILES = 2;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout body;
    private TextView eyebrow, status;
    private GlitchText title;
    private boolean pairing;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
            v.setPadding(dp(18), bars.top + dp(14), dp(18), bars.bottom + dp(10));
            return insets;
        });
        eyebrow = Ui.label(this, "", Ui.MUTED);
        root.addView(eyebrow);
        title = new GlitchText(this, 40);
        title.setText("LINK");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = -dp(8);
        root.addView(title, tp);
        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        status = Ui.text(this, "", 11, Ui.MUTED, false);
        status.setPadding(0, dp(8), 0, 0);
        root.addView(status);
        setContentView(root);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    /** A sparklink:// link opened from another app, or the ring notification. */
    private void handle(Intent i) {
        if (i == null) return;
        if (i.getBooleanExtra("ring", false)) { setShowWhenLocked(true); setTurnScreenOn(true); showRinging(); return; }
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            LinkState.Invite inv = LinkState.parse(i.getData().toString());
            i.setData(null);
            if (inv == null) say("THAT IS NOT A SPARK LINK PAIRING LINK", true);
            else confirmInvite(inv);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        LinkService.listener = this;
        LinkService.screenShown(this);
    }

    @Override
    protected void onStop() {
        if (LinkService.listener == this) LinkService.listener = null;
        LinkService.screenHidden(this);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!pairing && !getIntent().getBooleanExtra("ring", false)) render();
    }

    @Override
    public void onLinkChanged() { if (!pairing) render(); }

    private void render() {
        if (LinkState.paired(this)) showPaired();
        else showUnpaired();
    }

    // ---- building blocks ----

    private void say(String s, boolean bad) {
        status.setText(s);
        status.setTextColor(bad ? Ui.PINK : Ui.MUTED);
        if (bad) title.burst();        // an error must glitch, not just turn pink
        ui.removeCallbacksAndMessages("say");
        if (!s.isEmpty()) ui.postAtTime(() -> status.setText(""), "say", android.os.SystemClock.uptimeMillis() + (bad ? 10000 : 6000));
    }

    private TextView button(String s, boolean primary, View.OnClickListener on) {
        TextView b = Ui.text(this, s, 12, primary ? Ui.ON_ACCENT : Ui.TEXT, true);
        b.setLetterSpacing(0.08f);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(13), dp(14), dp(13));
        b.setBackground(Ui.box(this, primary ? Ui.ACCENT : Ui.PANEL, primary ? Ui.ACCENT : Ui.LINE2));
        b.setOnClickListener(v -> { title.burst(); on.onClick(v); });
        return b;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Ui.DIM);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(14);
        e.setTypeface(android.graphics.Typeface.MONOSPACE);
        e.setSingleLine(true);
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        e.setBackground(Ui.box(this, Ui.BG, Ui.LINE2));
        return e;
    }

    private TextView note(String s, int color) { return Ui.text(this, s, 12, color, false); }

    /** A row with a caps name and a value on the right; tapping it does on. */
    private TextView row(String name, String value, int color, Runnable on) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(13), dp(14), dp(13), dp(14));
        r.setBackground(Ui.box(this, Ui.PANEL2, Ui.LINE));
        TextView n = Ui.label(this, name, on == null ? Ui.MUTED : Ui.TEXT);
        n.setTextSize(12);
        r.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = Ui.label(this, value, color);
        r.addView(v);
        if (on != null) r.setOnClickListener(x -> { title.burst(); on.run(); });
        body.addView(r, Ui.fill(this, 6));
        return v;
    }

    private void toggle(String name, String feature, String why) {
        boolean on = LinkState.on(this, feature);
        row(name, on ? "ON" : "OFF", on ? Ui.ACCENT : Ui.MUTED, () -> {
            LinkState.set(this, feature, !on);
            LinkService.settingsChanged(this);
            render();
        });
        if (why != null) body.addView(note(why, Ui.DIM), Ui.fill(this, 4));
    }

    // ---- not paired ----

    private void showUnpaired() {
        eyebrow.setText("● NOT PAIRED  ·  LINK TO PC");
        eyebrow.setTextColor(Ui.MUTED);
        body.removeAllViews();
        body.addView(Ui.text(this, "Link Soot to Spark Link on your SparkOS PC: send tabs, links and files both ways, share the clipboard, see this phone's notifications on the PC, and ring the phone from the PC.", 13, Ui.TEXT, false), Ui.fill(this, 8));
        body.addView(note("On the PC open Spark Link and choose Pair a phone. Both must be on the same network. Nothing goes through the internet.", Ui.MUTED), Ui.fill(this, 10));
        body.addView(button("SCAN THE CODE", true, v -> startActivityForResult(new Intent(this, ScanActivity.class), SCAN)), Ui.fill(this, 18));

        body.addView(Ui.label(this, "OR PASTE THE PAIRING LINK", Ui.MUTED), Ui.fill(this, 24));
        EditText link = field("sparklink://pair?...");
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        body.addView(link, Ui.fill(this, 6));
        body.addView(button("PAIR WITH THIS LINK", false, v -> {
            LinkState.Invite inv = LinkState.parse(link.getText().toString());
            if (inv == null) say("THAT IS NOT A SPARK LINK PAIRING LINK", true);
            else pair(inv);
        }), Ui.fill(this, 8));

        body.addView(Ui.label(this, "OR TYPE WHAT THE PC SHOWS", Ui.MUTED), Ui.fill(this, 24));
        EditText addr = field("192.168.1.23:47100");
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        body.addView(addr, Ui.fill(this, 6));
        EditText code = field("6-digit code");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        body.addView(code, Ui.fill(this, 6));
        body.addView(button("CONNECT", false, v -> {
            String[] hp = LinkService.splitAddress(addr.getText().toString());
            String c = code.getText().toString().trim();
            if (hp == null) { say("TYPE THE ADDRESS THE PC SHOWS, LIKE 192.168.1.23:47100", true); return; }
            if (!c.matches("\\d{6}")) { say("THE CODE IS 6 DIGITS", true); return; }
            peek(hp[0], Integer.parseInt(hp[1]), c);
        }), Ui.fill(this, 8));
        body.addView(note("Typing works without the camera. Soot then shows the PC's key so you can check it against the PC screen before anything is sent.", Ui.DIM), Ui.fill(this, 8));
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SCAN && result == RESULT_OK && data != null) {
            LinkState.Invite inv = LinkState.parse(data.getStringExtra("link"));
            if (inv != null) pair(inv);
        } else if (request == PICK_FILES && result == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip == null && data.getData() != null) clip = ClipData.newRawUri("files", data.getData());
            if (clip == null) return;
            LinkService.sendFiles(this, clip);
            say(clip.getItemCount() == 1 ? "SENDING THE FILE..." : "SENDING " + clip.getItemCount() + " FILES...", false);
        }
    }

    /** A link opened from another app or a web page: the owner says yes first, it is never automatic. */
    private void confirmInvite(LinkState.Invite inv) {
        pairing = true;
        eyebrow.setText("● PAIRING  ·  LINK TO PC");
        body.removeAllViews();
        String who = inv.name == null || inv.name.isEmpty() ? "a PC" : inv.name;
        body.addView(Ui.text(this, "Pair Soot with " + who + " at " + inv.host + "?", 15, Ui.TEXT, true), Ui.fill(this, 8));
        body.addView(note("Only say yes if you just opened Pair a phone in Spark Link on your own PC.", Ui.MUTED), Ui.fill(this, 10));
        body.addView(button("PAIR", true, v -> pair(inv)), Ui.fill(this, 18));
        body.addView(button("CANCEL", false, v -> { pairing = false; render(); }), Ui.fill(this, 8));
    }

    /** Typed address: read the PC's certificate first and have the owner compare its key with the PC screen. */
    private void peek(String host, int port, String code) {
        say("CONNECTING TO " + host + "...", false);
        new Thread(() -> {
            try {
                String fp = LinkKeys.peek(host, port);
                ui.post(() -> {
                    pairing = true;
                    eyebrow.setText("● CHECK THE KEY  ·  PAIRING");
                    body.removeAllViews();
                    body.addView(Ui.label(this, "KEY", Ui.MUTED), Ui.fill(this, 8));
                    TextView key = Ui.text(this, LinkKeys.shortKey(fp), 30, Ui.ACCENT, true);
                    key.setLetterSpacing(0.06f);
                    body.addView(key, Ui.fill(this, 4));
                    body.addView(Ui.text(this, "Spark Link on the PC shows a key under the code. Pair only if it is exactly this one.", 13, Ui.TEXT, false), Ui.fill(this, 12));
                    body.addView(note("If it differs, another machine on this network is answering in the PC's place. Cancel.", Ui.MUTED), Ui.fill(this, 8));
                    LinkState.Invite inv = new LinkState.Invite();
                    inv.host = host;
                    inv.port = port;
                    inv.fp = fp;
                    inv.code = code;
                    body.addView(button("THE KEYS MATCH", true, v -> pair(inv)), Ui.fill(this, 18));
                    body.addView(button("CANCEL", false, v -> { pairing = false; render(); say("", false); }), Ui.fill(this, 8));
                    say("", false);
                });
            } catch (Exception e) {
                ui.post(() -> say("COULD NOT REACH " + host + ": " + e.getMessage(), true));
            }
        }).start();
    }

    private void pair(LinkState.Invite inv) {
        pairing = true;
        eyebrow.setText("● WAITING FOR THE PC  ·  PAIRING");
        body.removeAllViews();
        body.addView(Ui.text(this, "Accept on the PC", 18, Ui.TEXT, true), Ui.fill(this, 8));
        body.addView(note("Spark Link on " + (inv.name == null || inv.name.isEmpty() ? "the PC" : inv.name) + " asks: Pair with " + LinkState.deviceName(this) + "? Choose Pair there. This waits two minutes.", Ui.MUTED), Ui.fill(this, 10));
        say("CONNECTING TO " + inv.host + "...", false);
        new Thread(() -> {
            String bad;
            try {
                LinkService.PairResult r = LinkService.pair(this, inv);
                if (r.ok) {
                    ui.post(() -> {
                        pairing = false;
                        LinkService.start(this);
                        askNotificationPermission();
                        render();
                        say("PAIRED WITH " + r.pcName.toUpperCase(Locale.ROOT), false);
                        title.burst();
                    });
                    return;
                }
                switch (r.reason) {
                    case "code": bad = "THE PC SAYS THE CODE IS WRONG"; break;
                    case "declined": bad = "PAIRING WAS DECLINED ON THE PC"; break;
                    case "timeout": bad = "NOBODY CONFIRMED ON THE PC IN TIME"; break;
                    case "not-pairing": bad = "THE PC IS NOT PAIRING NOW, OR THE CODE EXPIRED. OPEN PAIR A PHONE AGAIN."; break;
                    case "busy": bad = "THE PC IS PAIRING ANOTHER PHONE. TRY AGAIN IN A MOMENT."; break;
                    default: bad = "THE PC REFUSED: " + r.reason.toUpperCase(Locale.ROOT); break;
                }
            } catch (java.net.SocketTimeoutException e) {
                bad = "NOBODY CONFIRMED ON THE PC IN TIME";
            } catch (javax.net.ssl.SSLException e) {
                bad = "THAT IS NOT THE PC IN THE CODE: " + e.getMessage();
            } catch (Exception e) {
                bad = "COULD NOT REACH THE PC: " + e.getMessage();
            }
            String said = bad;
            ui.post(() -> { pairing = false; render(); say(said, true); });
        }).start();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 3);
    }

    // ---- paired ----

    private void showPaired() {
        String pc = LinkState.pcName(this);
        LinkService.State s = LinkService.state;
        boolean up = s == LinkService.State.CONNECTED;
        String word;
        switch (s) {
            case CONNECTED: word = "CONNECTED"; break;
            case CONNECTING: word = "CONNECTING"; break;
            case FORGOTTEN: word = "NOT RECOGNISED"; break;
            case UNREACHABLE: word = "NOT REACHABLE"; break;
            case SEARCHING: word = "LOOKING"; break;
            default: word = LinkState.background(this) ? "OFF" : "ONLY WHILE SOOT IS OPEN"; break;
        }
        eyebrow.setText("● " + word + "  ·  LINK TO PC");
        eyebrow.setTextColor(up ? Ui.ACCENT : s == LinkService.State.FORGOTTEN || s == LinkService.State.UNREACHABLE ? Ui.PINK : Ui.MUTED);
        body.removeAllViews();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(12), dp(13), dp(12));
        card.setBackground(Ui.box(this, Ui.PANEL2, up ? Ui.ACCENT : Ui.LINE));
        card.addView(Ui.text(this, pc, 16, Ui.TEXT, true));
        String where = up ? LinkService.detail : s == LinkService.State.CONNECTING ? "trying " + LinkService.detail
                : s == LinkService.State.FORGOTTEN ? "The PC no longer knows this phone. It was unpaired there, or Spark Link was reset."
                : s == LinkService.State.UNREACHABLE ? "Can't reach the PC. Is it on, with Spark Link running? Away from home, Tailscale must be on on both."
                    + (LinkService.lastTries.isEmpty() ? "" : " Tried " + LinkService.lastTries + ".")
                : s == LinkService.State.OFF ? "Not connected" : "Looking on this network...";
        card.addView(note(where, s == LinkService.State.FORGOTTEN ? Ui.PINK : Ui.MUTED));
        body.addView(card, Ui.fill(this, 8));

        if (s == LinkService.State.FORGOTTEN) {
            body.addView(button("PAIR AGAIN", true, v -> { LinkService.unpair(this); render(); }), Ui.fill(this, 10));
        }

        if (up) {
            body.addView(Ui.label(this, "SEND", Ui.MUTED), Ui.fill(this, 20));
            LinearLayout two = new LinearLayout(this);
            TextView files = button("FILES", true, v -> {
                if (!LinkService.can(this, "files")) { say("FILES ARE SWITCHED OFF ON THE PC OR HERE", true); return; }
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(pick, PICK_FILES);
            });
            TextView clip = button("CLIPBOARD", false, v -> sendClipboard());
            LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            a.rightMargin = dp(6);
            two.addView(files, a);
            two.addView(clip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            body.addView(two, Ui.fill(this, 6));
            body.addView(note("To send a tab, use SEND TAB TO PC in Soot's menu. From other apps, use Share, then Send to PC.", Ui.DIM), Ui.fill(this, 6));
        }

        body.addView(Ui.label(this, "ON THIS PHONE", Ui.MUTED), Ui.fill(this, 20));
        toggle("LINKS", "url", null);
        toggle("SHARED CLIPBOARD", "clipboard", "Off by default. When on, text copied in Soot goes to the PC, and text copied on the PC arrives here. For other apps, use SEND CLIPBOARD in the Link notification: Android lets apps read the clipboard only while on screen.");
        toggle("FILES", "files", null);
        boolean always = LinkState.alwaysAccept(this);
        row("ALWAYS ACCEPT FILES FROM " + pc.toUpperCase(Locale.ROOT), always ? "ON" : "OFF", always ? Ui.ACCENT : Ui.MUTED, () -> { LinkState.setAlwaysAccept(this, !always); render(); });
        boolean access = LinkNotifications.allowed(this);
        if (LinkState.on(this, "notify") && !access) {
            row("NOTIFICATIONS", "NEEDS ACCESS", Ui.PINK, () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
            body.addView(note("Allow Soot under Notification access, then come back. Tap again here after to switch it off.", Ui.DIM), Ui.fill(this, 4));
            TextView off = Ui.label(this, "SWITCH NOTIFICATIONS OFF", Ui.MUTED);
            off.setPadding(0, dp(8), 0, dp(4));
            off.setOnClickListener(v -> { LinkState.set(this, "notify", false); LinkService.settingsChanged(this); render(); });
            body.addView(off);
        } else {
            toggle("NOTIFICATIONS", "notify", "Shows this phone's notifications on the PC, read-only. Off by default; needs Android's notification access for Soot.");
        }
        toggle("BATTERY", "battery", null);
        toggle("FIND MY PHONE", "ring", "The PC can ring this phone at full volume, even on silent, until you tap the notification.");
        boolean bgOn = LinkState.background(this);
        row("BACKGROUND CONNECTION", bgOn ? "ON" : "OFF", bgOn ? Ui.ACCENT : Ui.MUTED, () -> {
            LinkState.setBackground(this, !bgOn);
            LinkService.settingsChanged(this);
            render();
        });
        body.addView(note(bgOn ? "Soot stays linked with a lasting notification, so files, links and ringing reach the phone any time."
                : "Soot links to the PC only while it is open on screen. Nothing runs in the background.", Ui.DIM), Ui.fill(this, 4));

        body.addView(Ui.label(this, "PC ADDRESS", Ui.MUTED), Ui.fill(this, 20));
        body.addView(note("Soot finds the PC by itself. If your network blocks that, type the address Spark Link shows.", Ui.DIM), Ui.fill(this, 4));
        List<String> tail = LinkState.tailnet(this);
        body.addView(note(tail.isEmpty()
                ? "Away from home: put the PC and this phone on Tailscale. Soot learns the PC's Tailscale address the next time they connect, or type it below (the 100.x address Spark Link shows)."
                : "Away from home, Soot reaches the PC over Tailscale at " + String.join(", ", tail) + ". Keep Tailscale on on this phone.", Ui.DIM), Ui.fill(this, 4));
        EditText addr = field("found automatically");
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addr.setText(LinkState.manualAddress(this).isEmpty() ? LinkState.typedTailnet(this) : LinkState.manualAddress(this));
        body.addView(addr, Ui.fill(this, 6));
        body.addView(button("SAVE ADDRESS", false, v -> {
            String t = addr.getText().toString().trim();
            String[] hp = t.isEmpty() ? null : LinkService.splitAddress(t);
            if (!t.isEmpty() && hp == null) { say("TYPE AN ADDRESS LIKE 192.168.1.23:47100", true); return; }
            if (hp != null && LinkState.isTailnet(hp[0])) {
                // a Tailscale address: tried after the home network, so it doesn't slow the link at home
                LinkState.prefs(this).edit().putString("tailnet-typed", hp[0]).remove("manual").apply();
            } else {
                LinkState.prefs(this).edit().putString("manual", t).remove("tailnet-typed").apply();
            }
            LinkService.recheck();
            render();
            say(t.isEmpty() ? "FINDING THE PC AUTOMATICALLY" : hp != null && LinkState.isTailnet(hp[0]) ? "TAILSCALE ADDRESS SAVED" : "ADDRESS SAVED", false);
        }), Ui.fill(this, 6));

        TextView un = Ui.label(this, "UNPAIR " + pc.toUpperCase(Locale.ROOT), Ui.PINK);
        un.setTextSize(11);
        un.setPadding(0, dp(22), 0, dp(8));
        un.setOnClickListener(v -> {
            Ui.Sheet sheet = new Ui.Sheet(this, "UNPAIR?");
            sheet.note("Soot forgets " + pc + ", and tells it to forget this phone if it is connected. Pairing again needs a new code from the PC.");
            sheet.button("UNPAIR", true, () -> { LinkService.unpair(this); render(); say("UNPAIRED", false); });
            sheet.button("CANCEL", false, () -> { });
            sheet.show();
        });
        body.addView(un);
        body.addView(note("Everything stays on your network: Soot connects straight to the PC, encrypted, and trusts only the PC it paired with.", Ui.DIM), Ui.fill(this, 4));
    }

    private void sendClipboard() {
        String text = null;
        try {
            ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).coerceToText(this) != null) text = clip.getItemAt(0).coerceToText(this).toString();
        } catch (Exception ignored) { }
        if (text == null || text.isEmpty()) { say("THE CLIPBOARD IS EMPTY", true); return; }
        if (LinkService.sendClipboard(this, text)) say("CLIPBOARD SENT", false);
        else say("THE SHARED CLIPBOARD IS OFF ON THE PC OR HERE", true);
    }

    // ---- find my phone ----

    private void showRinging() {
        eyebrow.setText("● RINGING  ·  FIND MY PHONE");
        eyebrow.setTextColor(Ui.PINK);
        body.removeAllViews();
        body.addView(Ui.text(this, LinkState.pcName(this) + " is ringing this phone.", 15, Ui.TEXT, true), Ui.fill(this, 8));
        TextView stop = button("STOP RINGING", true, v -> {
            LinkService.stopRinging();
            getIntent().removeExtra("ring");
            setShowWhenLocked(false);
            setTurnScreenOn(false);
            render();
        });
        stop.setPadding(dp(14), dp(30), dp(14), dp(30));
        body.addView(stop, Ui.fill(this, 22));
    }
}
