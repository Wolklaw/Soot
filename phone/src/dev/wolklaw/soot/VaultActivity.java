package dev.wolklaw.soot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.autofill.AutofillManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;

/** Soot for Android: pair with the PC, keep an encrypted copy of the vault, show and copy logins, codes and notes. */
public final class VaultActivity extends Activity {
    // house palette (terminal variant)
    static final int BG = 0xFF04090B, PANEL = 0xFF0A1517, PANEL2 = 0xFF0E1F22, LINE = 0xFF143A3D, LINE2 = 0xFF1F6A66,
            TEXT = 0xFFD4F1EC, MUTED = 0xFF6F9F98, DIM = 0xFF3B6460, ACCENT = 0xFF3DE8C8, ON_ACCENT = 0xFF02130F, PINK = 0xFFFF4D8D;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root, body;
    private GlitchText title;
    private TextView eyebrow, status;
    private Vault.Data data;
    private String tab = "logins", query = "";
    private boolean waitingForPc;

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    // ---- small building blocks ----

    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(3));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 10.5f, MUTED, true);
        t.setLetterSpacing(0.08f);
        return t;
    }

    private TextView button(String s, boolean primary, View.OnClickListener on) {
        TextView b = text(s, 12, primary ? ON_ACCENT : TEXT, true);
        b.setLetterSpacing(0.08f);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(13), dp(14), dp(13));
        b.setBackground(box(primary ? ACCENT : PANEL, primary ? ACCENT : LINE2));
        b.setOnClickListener(v -> { title.burst(); on.onClick(v); });
        return b;
    }

    private LinearLayout.LayoutParams fill(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        return p;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(DIM);
        e.setTextColor(TEXT);
        e.setTextSize(14);
        e.setTypeface(Typeface.MONOSPACE);
        e.setSingleLine(true);
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        e.setBackground(box(BG, LINE2));
        return e;
    }

    private void say(String s, boolean bad) {
        status.setText(s);
        status.setTextColor(bad ? PINK : MUTED);
        if (bad) title.burst();        // an error must glitch, not just turn pink
    }

    private void copy(String what, String value) {
        ClipData clip = ClipData.newPlainText("Soot", value);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        say(what + " COPIED", false);
        title.burst();
    }

    // ---- screens ----

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (BuildConfig.SECURE) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);      // keeps the vault out of screenshots and the recents list
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(18), dp(18), dp(18), dp(12));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
            v.setPadding(dp(18), bars.top + dp(14), dp(18), bars.bottom + dp(10));
            return insets;
        });
        eyebrow = label("");
        root.addView(eyebrow);
        title = new GlitchText(this, 40);
        title.setText("SOOT");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = -dp(8);
        root.addView(title, tp);
        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        status = text("", 11, MUTED, false);
        status.setPadding(0, dp(8), 0, 0);
        root.addView(status);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Vault.paired(this)) { showPairing(); return; }
        open(true);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private void showPairing() {
        eyebrow.setText("● NOT PAIRED  ·  VAULT");
        body.removeAllViews();
        body.addView(text("Pair this phone with Soot on your PC to carry your vault with you.", 13, TEXT, false), fill(8));
        body.addView(text("On the PC: open VAULT, then PHONE, and turn on phone sync. Type the address it shows here. At home use the Wi-Fi address; away from home install Tailscale on both devices and use the 100.x address.", 12, MUTED, false), fill(10));
        body.addView(label("PC ADDRESS"), fill(22));
        EditText addr = field("192.168.1.20:47600");
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addr.setText(Vault.address(this));
        body.addView(addr, fill(6));
        body.addView(button("PAIR", true, v -> {
            say("CONNECTING...", false);
            final String a = addr.getText().toString();
            new Thread(() -> {
                try {
                    String code = Vault.pair(this, a);
                    ui.post(() -> showCode(code));
                } catch (Exception e) {
                    ui.post(() -> say("COULD NOT REACH THE PC: " + e.getMessage(), true));
                }
            }).start();
        }), fill(12));
        if (!Vault.deviceSecure(this))
            body.addView(text("This phone has no screen lock. Set a PIN or fingerprint first: the vault's key is tied to it, and without one anyone holding the phone can open the vault.", 12, PINK, false), fill(22));
    }

    private void showCode(String code) {
        eyebrow.setText("● CHECK THE CODE  ·  PAIRING");
        body.removeAllViews();
        body.addView(label("CODE"), fill(8));
        TextView big = text(code.substring(0, 3) + " " + code.substring(3), 44, ACCENT, true);
        body.addView(big, fill(4));
        body.addView(text("Your PC shows a code on the PHONE page of the vault. If it is the same as this one, press THE CODES MATCH on the PC.", 13, TEXT, false), fill(14));
        body.addView(text("If the codes differ, someone on your network is in the middle. Press REJECT on the PC and cancel here.", 12, MUTED, false), fill(10));
        body.addView(button("CANCEL", false, v -> { waitingForPc = false; Vault.unpair(this); showPairing(); say("", false); }), fill(22));
        say("WAITING FOR THE PC...", false);
        waitingForPc = true;
        pollUntilConfirmed();
    }

    private void pollUntilConfirmed() {
        if (!waitingForPc) return;
        new Thread(() -> {
            try {
                Vault.Data d = Vault.sync(this);
                ui.post(() -> { waitingForPc = false; data = d; showVault(); say("PAIRED AND SYNCED", false); title.burst(); });
            } catch (Vault.Refused r) {
                if (r.status == 403 || r.status == 423) ui.postDelayed(this::pollUntilConfirmed, 2000);
                else ui.post(() -> { waitingForPc = false; Vault.unpair(this); showPairing(); say("THE PC REJECTED THE PAIRING", true); });
            } catch (Exception e) {
                ui.postDelayed(this::pollUntilConfirmed, 2500);
            }
        }).start();
    }

    /** Opens the copy on the phone (asking for fingerprint or PIN if needed), then refreshes from the PC. */
    private void open(boolean thenSync) {
        try {
            data = Vault.load(this);
        } catch (Exception locked) {
            unlock(thenSync);
            return;
        }
        if (getIntent().getBooleanExtra("unlock", false)) { getIntent().removeExtra("unlock"); setResult(RESULT_OK); finish(); return; }
        if (data == null && !waitingForPc) { waitingForPc = true; say("WAITING FOR THE PC...", false); pollUntilConfirmed(); }
        showVault();
        if (thenSync && data != null) sync(false);
    }

    private void unlock(boolean thenSync) {
        eyebrow.setText("● LOCKED  ·  VAULT");
        body.removeAllViews();
        body.addView(button("UNLOCK", true, v -> unlock(thenSync)), fill(12));
        BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle("Unlock Soot")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();
        prompt.authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) { open(thenSync); }
            @Override public void onAuthenticationError(int code, CharSequence msg) { say("LOCKED: " + msg, true); }
        });
    }

    private void sync(boolean loud) {
        if (loud) say("SYNCING...", false);
        new Thread(() -> {
            try {
                Vault.Data d = Vault.sync(this);
                ui.post(() -> { data = d; showVault(); say("SYNCED " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()), false); if (loud) title.burst(); });
            } catch (Vault.Refused r) {
                ui.post(() -> say(r.status == 423 ? "THE VAULT IS LOCKED ON THE PC" : r.status == 404 ? "THE PC NO LONGER KNOWS THIS PHONE: UNPAIR AND PAIR AGAIN" : "PC: " + r.getMessage(), loud));
            } catch (Exception e) {
                ui.post(() -> { if (loud) say("PC NOT REACHABLE. SHOWING THE COPY ON THIS PHONE.", true); else say(lastSynced(), false); });
            }
        }).start();
    }

    private String lastSynced() {
        long t = Vault.prefs(this).getLong("synced", 0);
        return t == 0 ? "NOT SYNCED YET" : "LAST SYNCED " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(t));
    }

    private void showVault() {
        int n = data == null ? 0 : data.logins.size();
        eyebrow.setText("● UNLOCKED  ·  " + n + " LOGINS");
        body.removeAllViews();
        LinearLayout tabs = new LinearLayout(this);
        for (String t : new String[] { "logins", "codes", "notes" }) {
            TextView b = text(t.toUpperCase(), 11, t.equals(tab) ? TEXT : MUTED, true);
            b.setLetterSpacing(0.08f);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(10), dp(11), dp(10), dp(11));
            b.setBackground(box(t.equals(tab) ? PANEL : BG, t.equals(tab) ? ACCENT : LINE));
            b.setOnClickListener(v -> { tab = t; title.burst(); showVault(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            p.rightMargin = dp(6);
            tabs.addView(b, p);
        }
        body.addView(tabs, fill(8));
        ui.removeCallbacksAndMessages("codes");
        if (data == null) { body.addView(text("Nothing here yet. Waiting for the first sync.", 13, MUTED, false), fill(18)); addFooter(); return; }

        if (tab.equals("logins")) {
            EditText search = field("Search logins");
            search.setText(query);
            body.addView(search, fill(10));
            final LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            body.addView(list, fill(10));
            search.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                public void onTextChanged(CharSequence s, int a, int b, int c) { }
                public void afterTextChanged(Editable s) { query = s.toString(); fillLogins(list); }
            });
            fillLogins(list);
        } else if (tab.equals("codes")) {
            if (data.codes.isEmpty()) body.addView(text("No sign-in codes. Add them in Soot on the PC.", 13, MUTED, false), fill(18));
            for (Vault.Code c : data.codes) {
                LinearLayout row = card();
                row.addView(text(c.name, 14, TEXT, true));
                TextView code = text("------", 28, ACCENT, true);
                code.setLetterSpacing(0.12f);
                row.addView(code);
                TextView left = text("", 11, MUTED, false);
                row.addView(left);
                Runnable tick = new Runnable() {
                    public void run() {
                        code.setText(Vault.totp(c.secret, System.currentTimeMillis()));
                        left.setText("new code in " + (30 - (System.currentTimeMillis() / 1000) % 30) + " s  ·  tap to copy");
                        ui.postAtTime(this, "codes", android.os.SystemClock.uptimeMillis() + 1000);
                    }
                };
                tick.run();
                row.setOnClickListener(v -> copy("CODE", code.getText().toString()));
                body.addView(row, fill(8));
            }
        } else {
            if (data.notes.isEmpty()) body.addView(text("No notes. Add them in Soot on the PC.", 13, MUTED, false), fill(18));
            for (Vault.Note note : data.notes) {
                LinearLayout row = card();
                row.addView(text(note.title, 14, TEXT, true));
                row.addView(text(note.body, 13, MUTED, false));
                row.setOnClickListener(v -> copy("NOTE", note.body));
                body.addView(row, fill(8));
            }
        }
        addFooter();
    }

    private LinearLayout card() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(13), dp(11), dp(13), dp(11));
        row.setBackground(box(PANEL2, LINE));
        return row;
    }

    private void fillLogins(LinearLayout list) {
        list.removeAllViews();
        String q = query.trim().toLowerCase();
        int shown = 0;
        for (Vault.Login l : data.logins) {
            if (!q.isEmpty() && !l.host().toLowerCase().contains(q) && !l.user.toLowerCase().contains(q)) continue;
            LinearLayout row = card();
            row.addView(text(l.host(), 14, TEXT, true));
            row.addView(text(l.user.isEmpty() ? "(no username)" : l.user, 12, MUTED, false));
            row.setOnClickListener(v -> showLogin(l));
            LinearLayout.LayoutParams p = fill(shown == 0 ? 0 : 6);
            list.addView(row, p);
            shown++;
        }
        if (shown == 0) list.addView(text(data.logins.isEmpty() ? "No logins in the vault yet." : "Nothing matches that search.", 13, MUTED, false));
    }

    private void showLogin(Vault.Login l) {
        title.burst();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(l.host())
                .setItems(new String[] { "Copy username", "Copy password", "Show password" }, (d, which) -> {
                    if (which == 0) copy("USERNAME", l.user);
                    else if (which == 1) copy("PASSWORD", l.pass);
                    else new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(l.user).setMessage(l.pass).setPositiveButton("Close", null).show();
                })
                .show();
    }

    private void addFooter() {
        body.addView(button("SYNC NOW", true, v -> sync(true)), fill(22));
        AutofillManager am = getSystemService(AutofillManager.class);
        boolean on = am != null && am.hasEnabledAutofillServices();
        body.addView(button(on ? "AUTOFILL IS ON" : "TURN ON AUTOFILL", false, v -> {
            try { startActivity(new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:" + getPackageName()))); }
            catch (Exception e) { say("OPEN SETTINGS, PASSWORDS AND AUTOFILL, AND CHOOSE SOOT", true); }
        }), fill(8));
        body.addView(text("With autofill on, Soot offers your logins in apps and browsers. New logins are saved in Soot on the PC and arrive here at the next sync.", 11, DIM, false), fill(10));
        TextView un = text("UNPAIR THIS PHONE", 11, PINK, true);
        un.setLetterSpacing(0.08f);
        un.setPadding(0, dp(18), 0, dp(8));
        un.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Unpair?").setMessage("This removes the vault copy from this phone.")
                .setPositiveButton("Unpair", (d, w) -> { Vault.unpair(this); data = null; showPairing(); say("", false); })
                .setNegativeButton("Cancel", null).show());
        body.addView(un);
        if (status.getText().length() == 0) say(lastSynced(), false);
    }
}
