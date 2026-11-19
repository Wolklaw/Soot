package dev.wolklaw.soot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.mozilla.geckoview.Autocomplete;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The one Gecko engine of the app: Soot's privacy defaults, uBlock Origin, and the bridge that lets the
 * engine fill logins from the vault.
 */
final class Engine {
    static final String UBO_ID = "uBlock0@raymondhill.net";
    // the same signed package the desktop installs, straight from Mozilla's add-on site
    static final String UBO_XPI = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi";
    private static final String TAG = "Soot";

    /** What the browser screen needs to hear from the engine. */
    interface Listener {
        void onBlockerChanged();
        void onVaultLocked();
    }

    static final int BLOCKER_STARTING = 0, BLOCKER_INSTALLING = 1, BLOCKER_ON = 2, BLOCKER_FAILED = 3;
    static int blocker = BLOCKER_STARTING;
    static String blockerVersion = "", blockerError = "";
    static Listener listener;

    private static GeckoRuntime runtime;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final List<Runnable> waiting = new ArrayList<>();
    private static boolean settled;

    private Engine() { }

    static GeckoRuntime get(Context context) {
        if (runtime != null) return runtime;
        final Context app = context.getApplicationContext();

        ContentBlocking.Settings blocking = new ContentBlocking.Settings.Builder()
                .antiTracking(ContentBlocking.AntiTracking.STRICT)                     // trackers, fingerprinters, cryptominers, email trackers
                .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)   // every site gets its own cookie jar
                .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                .strictSocialTrackingProtection(true)
                .emailTrackerBlockingPrivateMode(true)
                .cookiePurging(true)
                .queryParameterStrippingEnabled(true)                                  // strip tracking tags from links
                .queryParameterStrippingPrivateBrowsingEnabled(true)
                .bounceTrackingProtectionMode(ContentBlocking.BounceTrackingProtectionMode.BOUNCE_TRACKING_PROTECTION_MODE_ENABLED)
                .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                .build();

        GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                .contentBlocking(blocking)
                .extensionsProcessEnabled(true)                // uBlock Origin runs in a process of its own, not in the app's main one
                .globalPrivacyControlEnabled(true)             // tell sites not to sell or share data
                .configFilePath(prefsFile(app))                // the rest of Soot's defaults, see assets/soot-prefs.yaml
                .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
                .loginAutofillEnabled(true)
                .aboutConfigEnabled(true)
                .remoteDebuggingEnabled(false)
                .consoleOutput(false)
                .build();                                      // no crash handler set: crash reports are never sent

        runtime = GeckoRuntime.create(app, settings);
        runtime.setAutocompleteStorageDelegate(new VaultLogins(app));
        startBlocker();
        return runtime;
    }

    /** Copies the defaults out of the APK: the engine reads them from a real file. */
    private static String prefsFile(Context app) {
        File f = new File(app.getFilesDir(), "soot-prefs.yaml");
        try (InputStream in = app.getAssets().open("soot-prefs.yaml"); FileOutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            Log.w(TAG, "could not write engine defaults", e);
        }
        return f.getAbsolutePath();
    }

    // ---- uBlock Origin ----

    private static void startBlocker() {
        final WebExtensionController controller = runtime.getWebExtensionController();
        controller.setPromptDelegate(new WebExtensionController.PromptDelegate() {
            @Override public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
                    WebExtension extension, String[] permissions, String[] origins, String[] dataCollectionPermissions) {
                // Soot installs exactly one add-on by itself. It runs in private tabs too and sends no technical data.
                boolean ours = UBO_ID.equals(extension.id);
                return GeckoResult.fromValue(new WebExtension.PermissionPromptResponse(ours, ours, false));
            }
        });
        controller.list().accept(list -> {
            WebExtension found = null;
            if (list != null) for (WebExtension e : list) if (UBO_ID.equals(e.id)) found = e;
            if (found != null) { blockerReady(found); controller.update(found).accept(updated -> { if (updated != null) blockerReady(updated); }, e -> { }); }
            else installBlocker(controller);
        }, e -> installBlocker(controller));
        ui.postDelayed(Engine::release, 20000);        // never hold the first page for longer than this
    }

    private static void installBlocker(WebExtensionController controller) {
        setBlocker(BLOCKER_INSTALLING, "");
        controller.install(UBO_XPI).accept(
                ext -> {
                    // private tabs were granted in the install prompt; ask again only if that did not stick (it restarts the add-on)
                    if (ext.metaData != null && ext.metaData.allowedInPrivateBrowsing) blockerReady(ext);
                    else controller.setAllowedInPrivateBrowsing(ext, true).accept(Engine::blockerReady, e -> blockerReady(ext));
                },
                e -> {
                    Log.w(TAG, "uBlock Origin install failed", e);
                    setBlocker(BLOCKER_FAILED, e == null || e.getMessage() == null ? "no connection" : e.getMessage());
                    release();
                });
    }

    private static void blockerReady(WebExtension ext) {
        boolean fresh = blocker == BLOCKER_INSTALLING;
        if (ext != null && ext.metaData != null && ext.metaData.version != null) blockerVersion = ext.metaData.version;
        setBlocker(BLOCKER_ON, "");
        // a fresh install still has to compile its filter lists: give it a moment before the first page goes out
        if (!settled) ui.postDelayed(Engine::release, fresh ? 2500 : 0);
    }

    private static void setBlocker(int state, String error) {
        blocker = state;
        blockerError = error;
        if (listener != null) listener.onBlockerChanged();
    }

    /** Tries the install again, from the menu, after a start without a connection. */
    static void retryBlocker() {
        if (runtime != null && blocker == BLOCKER_FAILED) installBlocker(runtime.getWebExtensionController());
    }

    /** Runs the action once the blocker is in place (or has failed, or 20 s have passed): the first page waits for it. */
    static void whenBlockerSettled(Runnable action) {
        if (settled) action.run(); else waiting.add(action);
    }

    private static void release() {
        settled = true;
        List<Runnable> run = new ArrayList<>(waiting);
        waiting.clear();
        for (Runnable r : run) r.run();
    }

    // ---- logins from the vault ----

    private static final class VaultLogins implements Autocomplete.StorageDelegate {
        private final Context app;
        VaultLogins(Context app) { this.app = app; }

        @Override public GeckoResult<Autocomplete.LoginEntry[]> onLoginFetch(String domain) {
            List<Autocomplete.LoginEntry> out = new ArrayList<>();
            if (Vault.paired(app)) {
                try {
                    Vault.Data data = Vault.load(app);
                    if (data != null) for (Vault.Login l : data.logins) {
                        // the engine asks by site name (example.com); a login saved for the site or one of its subdomains fits
                        String host = l.origin == null || !l.origin.startsWith("http") ? null : android.net.Uri.parse(l.origin).getHost();
                        if (host == null || !(host.equalsIgnoreCase(domain) || host.toLowerCase().endsWith("." + domain.toLowerCase()))) continue;
                        out.add(new Autocomplete.LoginEntry.Builder()
                                .guid(Integer.toHexString((l.origin + "\n" + l.user).hashCode()))
                                .origin(l.origin.replaceFirst("^(https?://[^/]+).*$", "$1"))
                                .formActionOrigin(l.origin.replaceFirst("^(https?://[^/]+).*$", "$1"))
                                .username(l.user)
                                .password(l.pass)
                                .build());
                    }
                } catch (Exception locked) {
                    // the phone was not unlocked recently enough for the vault key: say so, the owner unlocks from the bar
                    ui.post(() -> { if (listener != null) listener.onVaultLocked(); });
                }
            }
            return GeckoResult.fromValue(out.toArray(new Autocomplete.LoginEntry[0]));
        }
        // onLoginSave is left alone: the phone copy is read-only, new logins are saved in Soot on the PC
    }
}
