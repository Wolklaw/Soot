package dev.wolklaw.soot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.Autocomplete;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.StorageController;

import java.util.ArrayList;
import java.util.List;

/** Soot for Android: the browser. Address bar, tabs, private tabs, the vault one tap away. */
public final class BrowserActivity extends Activity implements Tab.Host, Engine.Listener, GeckoSession.PromptDelegate {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root, bottom;
    private Ui.GlitchBox bar;
    private GeckoView view;
    private EditText address;
    private TextView dot, go, status, back, forward, vault, tabs, privateTag;
    private View progress;
    private FrameLayout progressTrack;
    private ScrollView home;
    private LinearLayout homeBody;
    private boolean fullScreen, vaultLocked;

    private int dp(float v) { return Ui.dp(this, v); }

    // ---- building the screen ----

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Engine.listener = this;
        Tab.host = this;
        Engine.get(this);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(0, fullScreen ? 0 : bars.top, 0, fullScreen ? 0 : bars.bottom);
            return insets;
        });

        // top bar: state dot, address, reload or stop
        bar = new Ui.GlitchBox(this);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(8), dp(8));
        dot = Ui.text(this, "●", 12, Ui.DIM, true);
        dot.setPadding(0, 0, dp(8), 0);
        row.addView(dot);
        privateTag = Ui.label(this, "PRIVATE", Ui.PINK);
        privateTag.setPadding(0, 0, dp(8), 0);
        row.addView(privateTag);
        address = new EditText(this);
        address.setHint("Search or type an address");
        address.setHintTextColor(Ui.DIM);
        address.setTextColor(Ui.TEXT);
        address.setTextSize(14);
        address.setTypeface(android.graphics.Typeface.MONOSPACE);
        address.setSingleLine(true);
        address.setSelectAllOnFocus(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        address.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        address.setPadding(dp(11), dp(10), dp(11), dp(10));
        address.setBackground(Ui.box(this, Ui.BG, Ui.LINE2));
        address.setContentDescription("Address");
        address.setOnEditorActionListener((v, action, event) -> { submit(); return true; });
        address.setOnFocusChangeListener((v, has) -> { if (!has) refresh(); else ui.post(address::selectAll); });
        row.addView(address, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        go = barButton("RELOAD");
        go.setOnClickListener(v -> {
            Tab t = Tab.current;
            if (address.hasFocus()) submit();
            else if (t.loading) t.session.stop();
            else if (!t.home) t.session.reload();
        });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.leftMargin = dp(6);
        row.addView(go, gp);
        bar.addView(row);
        root.addView(bar);

        progressTrack = new FrameLayout(this);
        progressTrack.setBackgroundColor(Ui.LINE);
        progress = new View(this);
        progress.setBackgroundColor(Ui.ACCENT);
        progress.setPivotX(0);
        progressTrack.addView(progress);
        root.addView(progressTrack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        // the page, or Soot's start screen over it
        FrameLayout content = new FrameLayout(this);
        view = new GeckoView(this);
        view.coverUntilFirstPaint(Ui.BG);
        view.setAutofillEnabled(false);          // logins come from the vault through the engine, not through Android autofill
        content.addView(view);
        home = new ScrollView(this);
        home.setBackgroundColor(Ui.BG);
        home.setFillViewport(true);
        homeBody = new LinearLayout(this);
        homeBody.setOrientation(LinearLayout.VERTICAL);
        homeBody.setPadding(dp(18), dp(22), dp(18), dp(18));
        home.addView(homeBody);
        content.addView(home);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        status = Ui.text(this, "", 11, Ui.MUTED, true);
        status.setLetterSpacing(0.06f);
        status.setPadding(dp(12), dp(7), dp(12), dp(7));
        status.setBackgroundColor(Ui.PANEL);
        status.setVisibility(View.GONE);
        root.addView(status);

        // bottom bar
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        bottom = new LinearLayout(this);
        bottom.setBackgroundColor(Ui.PANEL);
        back = bottomButton("BACK", v -> { if (Tab.current.canBack) Tab.current.session.goBack(); });
        forward = bottomButton("FWD", v -> { if (Tab.current.canForward) Tab.current.session.goForward(); });
        vault = bottomButton("VAULT", v -> { if (vaultLocked) unlockVault(); else startActivity(new Intent(this, VaultActivity.class)); });
        tabs = bottomButton("TABS 1", v -> showTabs());
        bottomButton("MENU", v -> showMenu());
        root.addView(bottom);
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);

        if (Tab.all.isEmpty()) Tab.current = Tab.open(this, false, null);
        show(Tab.current);
        if (saved == null) open(getIntent());
    }

    private TextView barButton(String s) {
        TextView b = Ui.label(this, s, Ui.TEXT);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(12), dp(10), dp(12));
        b.setBackground(Ui.box(this, Ui.PANEL, Ui.LINE2));
        return b;
    }

    private TextView bottomButton(String s, View.OnClickListener on) {
        TextView b = Ui.label(this, s, Ui.TEXT);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(15), 0, dp(15));
        b.setOnClickListener(on);
        bottom.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return b;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        open(intent);
    }

    /** A link sent by another app opens in a new tab. */
    private void open(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        String scheme = intent.getData().getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return;
        show(Tab.open(this, false, intent.getData().toString()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Tab.current != null) Tab.current.session.setActive(true);
        refresh();
        if (Tab.current != null && Tab.current.home) fillHome(Tab.current);      // the vault may have been paired meanwhile
    }

    @Override
    protected void onStop() {
        if (Tab.current != null) Tab.current.session.setActive(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        view.releaseSession();
        if (Tab.host == this) Tab.host = null;
        if (Engine.listener == this) Engine.listener = null;
        super.onDestroy();
    }

    // ---- tabs ----

    private void show(Tab t) {
        if (Tab.current != null && Tab.current != t) Tab.current.session.setActive(false);
        Tab.current = t;
        view.releaseSession();
        view.setSession(t.session);
        t.session.setPromptDelegate(this);
        t.session.setActive(true);
        address.clearFocus();
        bar.burst();
        refresh();
    }

    private void closeTab(Tab t) {
        int at = Tab.all.indexOf(t);
        if (at < 0) return;
        boolean wasCurrent = t == Tab.current;
        if (wasCurrent) { view.releaseSession(); Tab.current = null; }
        Tab.close(t);
        if (Tab.all.isEmpty()) { show(Tab.open(this, false, null)); return; }
        if (wasCurrent) show(Tab.all.get(Math.min(at, Tab.all.size() - 1)));
        else refresh();
    }

    private void showTabs() {
        Ui.Sheet sheet = new Ui.Sheet(this, "TABS");
        for (Tab t : new ArrayList<>(Tab.all)) {
            LinearLayout rowView = new LinearLayout(this);
            rowView.setGravity(Gravity.CENTER_VERTICAL);
            rowView.setBackground(Ui.box(this, Ui.PANEL2, t == Tab.current ? Ui.ACCENT : Ui.LINE));
            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            words.setPadding(dp(13), dp(11), dp(8), dp(11));
            TextView name = Ui.text(this, t.home ? (t.priv ? "Private tab" : "New tab") : t.title.isEmpty() ? t.host() : t.title, 14, Ui.TEXT, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(name);
            TextView where = Ui.label(this, (t.priv ? "PRIVATE  ·  " : "") + (t.home ? "START" : t.host().toUpperCase()), t.priv ? Ui.PINK : Ui.MUTED);
            where.setSingleLine(true);
            where.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(where);
            words.setOnClickListener(v -> { sheet.dialog.dismiss(); show(t); });
            rowView.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView close = Ui.label(this, "CLOSE", Ui.PINK);
            close.setPadding(dp(12), dp(18), dp(13), dp(18));
            close.setOnClickListener(v -> { sheet.dialog.dismiss(); closeTab(t); showTabs(); });
            rowView.addView(close);
            sheet.body.addView(rowView, Ui.fill(this, 6));
        }
        sheet.button("NEW TAB", true, () -> newTab(false));
        sheet.button("NEW PRIVATE TAB", false, () -> newTab(true));
        sheet.show();
    }

    private void newTab(boolean priv) {
        show(Tab.open(this, priv, null));
        address.requestFocus();
        getSystemService(InputMethodManager.class).showSoftInput(address, 0);
    }

    // ---- the bars ----

    /** Turns what was typed into an address, or into a search. Nothing is sent anywhere before this. */
    static String toAddress(String typed) {
        String s = typed.trim();
        if (s.isEmpty()) return null;
        if (s.matches("(?i)^(https?|view-source|file)://.*") || s.matches("(?i)^(about|data|view-source):.*")) return s;
        boolean oneWord = !s.contains(" ");
        if (oneWord && s.matches("(?i)^(localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$")) return "http://" + s;
        if (oneWord && s.matches("^[^/?#]+\\.[^/?#.]{2,}([/?#:].*)?$")) return "https://" + s;
        return "https://www.google.com/search?q=" + Uri.encode(s);       // the desktop's default search engine
    }

    private void submit() {
        String to = toAddress(address.getText().toString());
        hideKeyboard();
        if (to == null) return;
        Tab.current.load(to);
    }

    private void hideKeyboard() {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(address.getWindowToken(), 0);
        address.clearFocus();
        view.requestFocus();
    }

    private void refresh() {
        Tab t = Tab.current;
        if (t == null || address == null) return;
        if (!address.hasFocus()) address.setText(t.home ? "" : t.url);
        dot.setTextColor(t.home ? Ui.DIM : t.secure ? Ui.ACCENT : Ui.PINK);
        privateTag.setVisibility(t.priv ? View.VISIBLE : View.GONE);
        address.setBackground(Ui.box(this, Ui.BG, t.priv ? Ui.PINK : Ui.LINE2));
        go.setText(address.hasFocus() ? "GO" : t.loading ? "STOP" : "RELOAD");
        go.setTextColor(t.home && !address.hasFocus() ? Ui.DIM : Ui.TEXT);
        progress.setBackgroundColor(t.priv ? Ui.PINK : Ui.ACCENT);
        progress.setVisibility(t.loading ? View.VISIBLE : View.INVISIBLE);
        progress.setScaleX(Math.max(0.05f, t.progress / 100f));
        back.setTextColor(t.canBack ? Ui.TEXT : Ui.DIM);
        forward.setTextColor(t.canForward ? Ui.TEXT : Ui.DIM);
        vault.setText(vaultLocked ? "UNLOCK" : "VAULT");
        vault.setTextColor(vaultLocked ? Ui.PINK : Ui.TEXT);
        tabs.setText("TABS " + Tab.all.size());
        if (t.home && home.getVisibility() != View.VISIBLE) fillHome(t);
        home.setVisibility(t.home ? View.VISIBLE : View.GONE);
        // private tabs stay out of screenshots and the recents list
        if (BuildConfig.SECURE && t.priv) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private String blockerWord() {
        switch (Engine.blocker) {
            case Engine.BLOCKER_ON: return "ON";
            case Engine.BLOCKER_FAILED: return "NOT INSTALLED";
            case Engine.BLOCKER_INSTALLING: return "INSTALLING";
            default: return "STARTING";
        }
    }

    /** The start screen of an empty tab: the name, and the state of what protects you. */
    private void fillHome(Tab t) {
        homeBody.removeAllViews();
        boolean ok = Engine.blocker == Engine.BLOCKER_ON;
        homeBody.addView(Ui.label(this, t.priv ? "● PRIVATE TAB" : "● " + (ok ? "ONLINE" : blockerWord()) + "  ·  BROWSER", t.priv ? Ui.PINK : Ui.MUTED));
        GlitchText title = new GlitchText(this, 44);
        title.setText(t.priv ? "PRIVATE" : "SOOT");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = -dp(8);
        homeBody.addView(title, tp);
        homeBody.addView(Ui.text(this, t.priv
                ? "Nothing from this tab is kept once it is closed: no history, cookies or cache. Sites and your network can still see what you load."
                : "Type an address or a search in the bar above.", 13, Ui.TEXT, false), Ui.fill(this, 8));
        homeBody.addView(Ui.label(this, "PROTECTION", Ui.MUTED), Ui.fill(this, 26));
        homeRow("UBLOCK ORIGIN", blockerWord() + (ok && !Engine.blockerVersion.isEmpty() ? "  " + Engine.blockerVersion : ""), ok ? Ui.ACCENT : Ui.PINK,
                Engine.blocker == Engine.BLOCKER_FAILED ? Engine::retryBlocker : null);
        homeRow("TRACKING PROTECTION", "STRICT", Ui.ACCENT, null);
        homeRow("TELEMETRY", "OFF", Ui.ACCENT, null);
        boolean paired = Vault.paired(this);
        homeRow("VAULT", paired ? "PAIRED" : "NOT PAIRED", paired ? Ui.ACCENT : Ui.MUTED, () -> startActivity(new Intent(this, VaultActivity.class)));
        if (Engine.blocker == Engine.BLOCKER_FAILED)
            homeBody.addView(Ui.text(this, "uBlock Origin could not be downloaded from addons.mozilla.org (" + Engine.blockerError
                    + "). Pages load without it until it is installed. Tap its row to try again.", 12, Ui.PINK, false), Ui.fill(this, 12));
    }

    private void homeRow(String name, String value, int color, Runnable on) {
        LinearLayout rowView = new LinearLayout(this);
        rowView.setGravity(Gravity.CENTER_VERTICAL);
        rowView.setPadding(dp(13), dp(13), dp(13), dp(13));
        rowView.setBackground(Ui.box(this, Ui.PANEL2, Ui.LINE));
        rowView.addView(Ui.label(this, name, Ui.TEXT), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        rowView.addView(Ui.label(this, value, color));
        if (on != null) rowView.setOnClickListener(v -> { bar.burst(); on.run(); });
        homeBody.addView(rowView, Ui.fill(this, 6));
    }

    @Override
    public void say(String message, boolean bad) {
        status.setText(message);
        status.setTextColor(bad ? Ui.PINK : Ui.MUTED);
        status.setVisibility(message.isEmpty() || fullScreen ? View.GONE : View.VISIBLE);
        if (bad) bar.burst();           // an error must glitch, not just turn pink
        ui.removeCallbacksAndMessages("say");
        ui.postAtTime(() -> status.setVisibility(View.GONE), "say", android.os.SystemClock.uptimeMillis() + 5000);
    }

    private void showMenu() {
        Ui.Sheet sheet = new Ui.Sheet(this, "SOOT");
        boolean paired = Vault.paired(this), glitch = getSharedPreferences("soot-ui", MODE_PRIVATE).getBoolean("glitch", true);
        sheet.row("NEW TAB", null, 0, () -> newTab(false));
        sheet.row("NEW PRIVATE TAB", null, 0, () -> newTab(true));
        sheet.row("VAULT", paired ? "PAIRED" : "NOT PAIRED", paired ? Ui.ACCENT : Ui.MUTED, () -> startActivity(new Intent(this, VaultActivity.class)));
        sheet.row("UBLOCK ORIGIN", Engine.blocker == Engine.BLOCKER_FAILED ? "NOT INSTALLED  ·  TRY AGAIN" : blockerWord() + (Engine.blockerVersion.isEmpty() ? "" : "  " + Engine.blockerVersion),
                Engine.blocker == Engine.BLOCKER_ON ? Ui.ACCENT : Ui.PINK, Engine.blocker == Engine.BLOCKER_FAILED ? Engine::retryBlocker : null);
        sheet.row("TRACKING PROTECTION", "STRICT", Ui.ACCENT, null);
        sheet.row("GLITCH EFFECTS", glitch ? "ON" : "OFF", glitch ? Ui.ACCENT : Ui.MUTED, () -> {
            Ui.setGlitch(this, !glitch);
            say("GLITCH EFFECTS " + (glitch ? "OFF" : "ON"), false);
            bar.burst();
        });
        sheet.row("CLEAR BROWSING DATA", null, 0, this::confirmClear);
        sheet.row("SOOT " + BuildConfig.VERSION_NAME, "ENGINE: GECKO " + BuildConfig.ENGINE, Ui.MUTED, null);
        sheet.show();
    }

    private void confirmClear() {
        Ui.Sheet sheet = new Ui.Sheet(this, "CLEAR?");
        sheet.note("Closes every tab and removes cookies, cache, site data and site permissions. The vault is not touched.");
        sheet.button("CLEAR EVERYTHING", true, () -> {
            for (Tab t : new ArrayList<>(Tab.all)) { if (t == Tab.current) view.releaseSession(); Tab.close(t); }
            Tab.current = null;
            Engine.get(this).getStorageController().clearData(StorageController.ClearFlags.ALL)
                    .accept(done -> say("BROWSING DATA CLEARED", false), e -> say("COULD NOT CLEAR EVERYTHING", true));
            show(Tab.open(this, false, null));
        });
        sheet.button("CANCEL", false, () -> { });
        sheet.show();
    }

    // ---- back ----

    private void goBack() {
        Tab t = Tab.current;
        if (fullScreen) t.session.exitFullScreen();
        else if (address.hasFocus()) hideKeyboard();
        else if (t.canBack) t.session.goBack();
        else if (Tab.all.size() > 1) closeTab(t);
        else moveTaskToBack(true);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { goBack(); }

    // ---- what tabs and the engine tell the screen ----

    @Override public void onTabChanged(Tab tab) { if (tab == Tab.current) refresh(); else if (tabs != null) tabs.setText("TABS " + Tab.all.size()); }
    @Override public void onTabOpened(Tab tab) { show(tab); }
    @Override public void onTabClosed(Tab tab) { closeTab(tab); }
    @Override public void onTabError(Tab tab, String message) { if (tab == Tab.current) say("NOT LOADED", true); }

    @Override
    public void onFullScreen(Tab tab, boolean on) {
        fullScreen = on;
        int show = on ? View.GONE : View.VISIBLE;
        bar.setVisibility(show);
        progressTrack.setVisibility(show);
        bottom.setVisibility(show);
        if (on) status.setVisibility(View.GONE);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            if (on) { c.hide(WindowInsets.Type.systemBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
            else c.show(WindowInsets.Type.systemBars());
        }
        root.requestApplyInsets();
    }

    @Override
    public boolean openOutside(String uri) {
        try {
            Intent i = uri.startsWith("intent:") ? Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.setComponent(null);
            i.setSelector(null);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onBlockerChanged() {
        if (Engine.blocker == Engine.BLOCKER_INSTALLING) say("INSTALLING UBLOCK ORIGIN...", false);
        else if (Engine.blocker == Engine.BLOCKER_ON) say("UBLOCK ORIGIN IS ON", false);
        else if (Engine.blocker == Engine.BLOCKER_FAILED) say("UBLOCK ORIGIN COULD NOT BE INSTALLED: " + Engine.blockerError, true);
        if (Tab.current != null && Tab.current.home) fillHome(Tab.current);
    }

    @Override
    public void onVaultLocked() {
        if (vaultLocked) return;
        vaultLocked = true;
        say("THE VAULT IS LOCKED. PRESS UNLOCK TO FILL LOGINS.", false);
        refresh();
    }

    private void unlockVault() {
        BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle("Unlock Soot")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();
        prompt.authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                vaultLocked = false;
                refresh();
                say("VAULT UNLOCKED. TAP THE LOGIN FIELD AGAIN.", false);
                bar.burst();
            }
            @Override public void onAuthenticationError(int code, CharSequence msg) { say("LOCKED: " + msg, true); }
        });
    }

    // ---- prompts from pages, in the house style ----

    /** Opens a sheet for a page prompt. Closing it any other way than through one of its buttons dismisses the prompt. */
    private Ui.Sheet promptSheet(String heading, BasePrompt prompt, GeckoResult<PromptResponse> result) {
        Ui.Sheet sheet = new Ui.Sheet(this, heading);
        sheet.dialog.setOnDismissListener(d -> { if (!prompt.isComplete()) result.complete(prompt.dismiss()); });
        return sheet;
    }

    @Override
    public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession s, AlertPrompt prompt) {
        GeckoResult<PromptResponse> result = new GeckoResult<>();
        Ui.Sheet sheet = promptSheet("PAGE SAYS", prompt, result);
        sheet.note(prompt.message == null ? "" : prompt.message);
        sheet.button("OK", true, () -> result.complete(prompt.dismiss()));
        sheet.show();
        return result;
    }

    @Override
    public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession s, ButtonPrompt prompt) {
        GeckoResult<PromptResponse> result = new GeckoResult<>();
        Ui.Sheet sheet = promptSheet("PAGE ASKS", prompt, result);
        sheet.note(prompt.message == null ? "" : prompt.message);
        sheet.button("OK", true, () -> result.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE)));
        sheet.button("CANCEL", false, () -> result.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE)));
        sheet.show();
        return result;
    }

    private EditText sheetField(Ui.Sheet sheet, String hint, String value, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Ui.DIM);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(14);
        e.setTypeface(android.graphics.Typeface.MONOSPACE);
        e.setSingleLine(true);
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (value != null) e.setText(value);
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        e.setBackground(Ui.box(this, Ui.BG, Ui.LINE2));
        sheet.body.addView(e, Ui.fill(this, 10));
        return e;
    }

    @Override
    public GeckoResult<PromptResponse> onTextPrompt(GeckoSession s, TextPrompt prompt) {
        GeckoResult<PromptResponse> result = new GeckoResult<>();
        Ui.Sheet sheet = promptSheet("PAGE ASKS", prompt, result);
        sheet.note(prompt.message == null ? "" : prompt.message);
        EditText field = sheetField(sheet, "", prompt.defaultValue, false);
        sheet.button("OK", true, () -> result.complete(prompt.confirm(field.getText().toString())));
        sheet.button("CANCEL", false, () -> result.complete(prompt.dismiss()));
        sheet.show();
        return result;
    }

    @Override
    public GeckoResult<PromptResponse> onAuthPrompt(GeckoSession s, AuthPrompt prompt) {
        GeckoResult<PromptResponse> result = new GeckoResult<>();
        Ui.Sheet sheet = promptSheet("SIGN IN", prompt, result);
        sheet.note(prompt.message == null ? "This site asks for a name and password." : prompt.message);
        boolean onlyPassword = (prompt.authOptions.flags & AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) != 0;
        EditText user = onlyPassword ? null : sheetField(sheet, "Name", prompt.authOptions.username, false);
        EditText pass = sheetField(sheet, "Password", null, true);
        sheet.button("SIGN IN", true, () -> result.complete(onlyPassword
                ? prompt.confirm(pass.getText().toString())
                : prompt.confirm(user.getText().toString(), pass.getText().toString())));
        sheet.button("CANCEL", false, () -> result.complete(prompt.dismiss()));
        sheet.show();
        return result;
    }

    /** Drop-down lists and pickers on pages. */
    @Override
    public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession s, ChoicePrompt prompt) {
        GeckoResult<PromptResponse> result = new GeckoResult<>();
        Ui.Sheet sheet = promptSheet("CHOOSE", prompt, result);
        if (prompt.message != null && !prompt.message.isEmpty()) sheet.note(prompt.message);
        boolean many = prompt.type == ChoicePrompt.Type.MULTIPLE;
        List<ChoicePrompt.Choice> picked = new ArrayList<>();
        addChoices(sheet, prompt, prompt.choices, many, picked, result);
        if (many) sheet.button("DONE", true, () -> result.complete(prompt.confirm(picked.toArray(new ChoicePrompt.Choice[0]))));
        sheet.show();
        return result;
    }

    private void addChoices(Ui.Sheet sheet, ChoicePrompt prompt, ChoicePrompt.Choice[] choices, boolean many,
                            List<ChoicePrompt.Choice> picked, GeckoResult<PromptResponse> result) {
        for (ChoicePrompt.Choice c : choices) {
            if (c.separator) continue;
            if (c.items != null) {
                sheet.body.addView(Ui.label(this, c.label.toUpperCase(), Ui.MUTED), Ui.fill(this, 12));
                addChoices(sheet, prompt, c.items, many, picked, result);
                continue;
            }
            if (c.selected && many) picked.add(c);
            TextView rowView = Ui.text(this, c.label, 14, c.disabled ? Ui.DIM : Ui.TEXT, c.selected);
            rowView.setPadding(dp(13), dp(13), dp(13), dp(13));
            rowView.setBackground(Ui.box(this, Ui.PANEL2, c.selected ? Ui.ACCENT : Ui.LINE));
            if (!c.disabled) rowView.setOnClickListener(v -> {
                if (!many) { result.complete(prompt.confirm(c)); sheet.dialog.dismiss(); return; }
                boolean on = !picked.remove(c);
                if (on) picked.add(c);
                rowView.setBackground(Ui.box(this, Ui.PANEL2, on ? Ui.ACCENT : Ui.LINE));
            });
            sheet.body.addView(rowView, Ui.fill(this, 6));
        }
    }

    @Override
    public GeckoResult<PromptResponse> onPopupPrompt(GeckoSession s, PopupPrompt prompt) {
        say("POP-UP BLOCKED", false);
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY));
    }

    @Override
    public GeckoResult<PromptResponse> onBeforeUnloadPrompt(GeckoSession s, BeforeUnloadPrompt prompt) {
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW));
    }

    @Override
    public GeckoResult<PromptResponse> onRepostConfirmPrompt(GeckoSession s, RepostConfirmPrompt prompt) {
        GeckoResult<PromptResponse> result = new GeckoResult<>();
        Ui.Sheet sheet = promptSheet("SEND AGAIN?", prompt, result);
        sheet.note("Loading this page again sends the form again.");
        sheet.button("SEND AGAIN", true, () -> result.complete(prompt.confirm(AllowOrDeny.ALLOW)));
        sheet.button("CANCEL", false, () -> result.complete(prompt.confirm(AllowOrDeny.DENY)));
        sheet.show();
        return result;
    }

    /** The engine found logins for this site in the vault: the owner picks one, the engine fills it. */
    @Override
    public GeckoResult<PromptResponse> onLoginSelect(GeckoSession s, AutocompleteRequest<Autocomplete.LoginSelectOption> request) {
        GeckoResult<PromptResponse> result = new GeckoResult<>();
        Ui.Sheet sheet = promptSheet("FILL FROM VAULT", request, result);
        for (Autocomplete.LoginSelectOption option : request.options) {
            Autocomplete.LoginEntry login = option.value;
            String where = login.origin.replaceFirst("^https?://(www\\.)?", "");
            sheet.row(login.username.isEmpty() ? "(no username)" : login.username, where, Ui.MUTED, () -> {
                result.complete(request.confirm(option));
                say("FILLED FROM THE VAULT", false);
                bar.burst();
            });
        }
        sheet.button("NOT NOW", false, () -> result.complete(request.dismiss()));
        sheet.show();
        return result;
    }
}
