package dev.wolklaw.soot;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.geckoview.WebResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One tab: a Gecko session and what the bars need to know about it. Tabs outlive the screen that shows them. */
final class Tab implements GeckoSession.NavigationDelegate, GeckoSession.ProgressDelegate, GeckoSession.ContentDelegate {

    /** The browser screen, while there is one. */
    interface Host {
        void onTabChanged(Tab tab);
        void onTabOpened(Tab tab);
        void onTabClosed(Tab tab);
        void onTabError(Tab tab, String message);
        void onFullScreen(Tab tab, boolean on);
        boolean openOutside(String uri);
        void say(String message, boolean bad);
    }

    static final List<Tab> all = new ArrayList<>();
    static Tab current;
    static Host host;
    private static final Handler ui = new Handler(Looper.getMainLooper());

    final GeckoSession session;
    final boolean priv;
    String url = "", title = "";
    int progress;
    boolean loading, canBack, canForward, secure;
    /** True while the tab shows Soot's own start screen instead of a page. */
    boolean home = true;

    Tab(boolean priv) {
        this.priv = priv;
        session = new GeckoSession(new GeckoSessionSettings.Builder()
                .usePrivateMode(priv)
                .useTrackingProtection(true)
                .suspendMediaWhenInactive(true)
                .build());
        session.setNavigationDelegate(this);
        session.setProgressDelegate(this);
        session.setContentDelegate(this);
    }

    /** Opens a new tab after the current one. With no address it shows the start screen. */
    static Tab open(Context c, boolean priv, String address) {
        Tab t = new Tab(priv);
        all.add(current == null ? all.size() : all.indexOf(current) + 1, t);
        t.session.open(Engine.get(c));
        if (address != null) t.load(address);
        return t;
    }

    static void close(Tab t) {
        all.remove(t);
        t.session.close();
    }

    void load(String address) {
        home = false;
        url = address;
        loading = true;
        progress = 5;
        Engine.whenBlockerSettled(() -> { if (session.isOpen()) session.loadUri(address); });   // the first page waits for the blocker
        changed();
    }

    String host() {
        String h = Uri.parse(url).getHost();
        return h == null ? url : h.replaceFirst("^www\\.", "");
    }

    private void changed() { if (host != null) host.onTabChanged(this); }

    // ---- navigation ----

    @Override public void onLocationChange(GeckoSession s, String location, List<GeckoSession.PermissionDelegate.ContentPermission> perms, Boolean hasUserGesture) {
        if (location == null || location.equals("about:blank") || location.startsWith("data:text/html;base64,PCEtLXNvb3Q")) { changed(); return; }   // blank, or Soot's own error page
        url = location;
        home = false;
        changed();
    }

    @Override public void onCanGoBack(GeckoSession s, boolean can) { canBack = can; changed(); }
    @Override public void onCanGoForward(GeckoSession s, boolean can) { canForward = can; changed(); }

    @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
        String scheme = Uri.parse(request.uri).getScheme();
        scheme = scheme == null ? "" : scheme.toLowerCase();
        switch (scheme) {
            case "http": case "https": case "about": case "data": case "blob": case "view-source": case "moz-extension": case "resource": case "javascript": case "":
                return null;        // the engine loads it
        }
        // mailto:, tel:, app links: hand over to Android, and only when the owner tapped something
        if (request.hasUserGesture && host != null && host.openOutside(request.uri)) return GeckoResult.fromValue(AllowOrDeny.DENY);
        if (host != null) host.say("NOTHING ON THIS PHONE OPENS " + scheme.toUpperCase() + " LINKS", true);
        return GeckoResult.fromValue(AllowOrDeny.DENY);
    }

    /** A link that asks for a new window opens as a new tab of the same kind. */
    @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
        Tab t = new Tab(priv);
        t.home = false;
        t.url = uri;
        all.add(all.indexOf(this) + 1, t);
        ui.post(() -> { if (host != null) host.onTabOpened(t); });      // the engine opens the session itself, after this returns
        return GeckoResult.fromValue(t.session);
    }

    @Override public GeckoResult<String> onLoadError(GeckoSession s, String uri, WebRequestError error) {
        String what;
        switch (error.category) {
            case WebRequestError.ERROR_CATEGORY_NETWORK: what = "Could not reach this site. Check the address and the connection."; break;
            case WebRequestError.ERROR_CATEGORY_SECURITY: what = "The connection to this site is not secure, so Soot stopped it."; break;
            case WebRequestError.ERROR_CATEGORY_URI: what = "That address does not look right."; break;
            case WebRequestError.ERROR_CATEGORY_SAFEBROWSING: what = "This site is reported as harmful, so Soot stopped it."; break;
            case WebRequestError.ERROR_CATEGORY_PROXY: what = "The proxy refused the connection."; break;
            case WebRequestError.ERROR_CATEGORY_CONTENT: what = "The site answered with something Soot cannot show."; break;
            default: what = "The page did not load.";
        }
        if (uri != null) url = uri;
        if (host != null) host.onTabError(this, what);
        String address = uri == null ? "" : uri;
        String html = "<!--soot--><!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>Page not loaded</title>"
                + "<style>body{background:#04090b;color:#d4f1ec;font:15px/1.5 monospace;padding:28px 20px}"
                + "h1{font-size:30px;margin:0 0 14px;text-shadow:2px 0 #ff2e88,-2px 0 #22d8ff}"
                + "small{color:#6f9f98;font-weight:bold;letter-spacing:.08em;font-size:11px}p{margin:0 0 14px}"
                + "code{color:#6f9f98;word-break:break-all}a{display:inline-block;margin-top:8px;padding:12px 16px;border-radius:3px;background:#3de8c8;color:#02130f;"
                + "font-weight:bold;letter-spacing:.08em;text-decoration:none;font-size:12px}</style>"
                + "<small>&#9679; NOT LOADED</small><h1>NO SIGNAL</h1><p>" + what + "</p><p><code>" + esc(address) + "</code></p>"
                + "<p><small>ENGINE CODE 0x" + Integer.toHexString(error.code) + "</small></p><a href=\"" + esc(address) + "\">TRY AGAIN</a>";
        return GeckoResult.fromValue("data:text/html;base64," + Base64.encodeToString(html.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
    }

    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    // ---- progress ----

    @Override public void onPageStart(GeckoSession s, String location) { loading = true; progress = 5; secure = false; changed(); }
    @Override public void onPageStop(GeckoSession s, boolean success) { loading = false; progress = 100; changed(); }
    @Override public void onProgressChange(GeckoSession s, int p) { progress = p; changed(); }
    @Override public void onSecurityChange(GeckoSession s, SecurityInformation info) { secure = info.isSecure; changed(); }

    // ---- content ----

    @Override public void onTitleChange(GeckoSession s, String t) { title = t == null ? "" : t; changed(); }
    @Override public void onFullScreen(GeckoSession s, boolean on) { if (host != null) host.onFullScreen(this, on); }
    @Override public void onCloseRequest(GeckoSession s) { if (host != null) host.onTabClosed(this); }

    @Override public void onExternalResponse(GeckoSession s, WebResponse response) {
        loading = false;
        if (host != null) host.say("DOWNLOADS ARE NOT IN THIS VERSION", true);
        changed();
    }

    @Override public void onCrash(GeckoSession s) { revive("THE PAGE CRASHED. LOADED IT AGAIN."); }
    @Override public void onKill(GeckoSession s) { revive("ANDROID CLOSED THE PAGE TO FREE MEMORY. LOADED IT AGAIN."); }

    /** The content process is gone and the session with it: open it again and reload when this is the tab on screen. */
    private void revive(String message) {
        loading = false;
        if (!all.contains(this) || host == null) return;
        ui.post(() -> {
            if (!session.isOpen()) session.open(Engine.get(null));
            if (current == this && !home) { session.loadUri(url); host.say(message, true); }
        });
    }
}
