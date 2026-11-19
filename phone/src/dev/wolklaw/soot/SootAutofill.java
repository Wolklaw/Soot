package dev.wolklaw.soot;

import android.app.PendingIntent;
import android.app.assist.AssistStructure;
import android.app.assist.AssistStructure.ViewNode;
import android.content.Intent;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.Dataset;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;
import android.text.InputType;
import android.util.Pair;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.List;

/** Offers saved logins in other apps and in browsers, matched by website or app name. */
public final class SootAutofill extends AutofillService {

    private static final class Found {
        AutofillId user, pass, lastText;
        String domain;
    }

    private static String attr(ViewStructure.HtmlInfo h, String name) {
        if (h == null || h.getAttributes() == null) return "";
        for (Pair<String, String> p : h.getAttributes()) if (name.equalsIgnoreCase(p.first)) return p.second == null ? "" : p.second.toLowerCase();
        return "";
    }

    private static boolean has(String[] hints, String want) {
        if (hints == null) return false;
        for (String h : hints) if (h != null && h.toLowerCase().contains(want)) return true;
        return false;
    }

    private static void walk(ViewNode n, Found f) {
        if (n.getWebDomain() != null && !n.getWebDomain().isEmpty()) f.domain = n.getWebDomain();
        AutofillId id = n.getAutofillId();
        if (id != null && n.getAutofillType() == View.AUTOFILL_TYPE_TEXT) {
            String[] hints = n.getAutofillHints();
            ViewStructure.HtmlInfo html = n.getHtmlInfo();
            String htmlType = attr(html, "type");
            String names = (n.getIdEntry() + " " + attr(html, "name") + " " + attr(html, "id") + " " + n.getHint()).toLowerCase();
            int variation = n.getInputType() & InputType.TYPE_MASK_VARIATION;
            boolean textClass = (n.getInputType() & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT;
            boolean isPass = has(hints, "password") || htmlType.equals("password")
                    || (textClass && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
            boolean isUser = has(hints, "username") || has(hints, "email") || htmlType.equals("email")
                    || names.matches(".*(user|email|e-mail|login|account).*")
                    || (textClass && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS));
            if (isPass) { if (f.pass == null) f.pass = id; }
            else if (isUser) { if (f.user == null) f.user = id; }
            else if (f.pass == null) f.lastText = id;       // the text box just before a password box is usually the username
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChildAt(i), f);
    }

    static boolean matches(Vault.Login l, String domain, String pkg) {
        String host = l.host().toLowerCase();
        int slash = host.indexOf('/');
        if (slash > 0) host = host.substring(0, slash);
        if (domain != null) {
            String d = domain.toLowerCase().replaceFirst("^www\\.", "");
            return host.equals(d) || host.endsWith("." + d) || d.endsWith("." + host);
        }
        for (String part : pkg.toLowerCase().split("\\.")) {
            if (part.length() < 4 || part.equals("android") || part.equals("mobile") || part.equals("client") || part.equals("apps")) continue;
            if (host.contains(part)) return true;
        }
        return false;
    }

    private RemoteViews row(String text) {
        RemoteViews v = new RemoteViews(getPackageName(), android.R.layout.simple_list_item_1);
        v.setTextViewText(android.R.id.text1, text);
        return v;
    }

    @Override
    public void onFillRequest(FillRequest request, CancellationSignal cancel, FillCallback callback) {
        try {
            List<FillContext> contexts = request.getFillContexts();
            AssistStructure s = contexts.get(contexts.size() - 1).getStructure();
            Found f = new Found();
            for (int i = 0; i < s.getWindowNodeCount(); i++) walk(s.getWindowNodeAt(i).getRootViewNode(), f);
            if (f.user == null) f.user = f.lastText;
            String pkg = s.getActivityComponent().getPackageName();
            if ((f.pass == null && f.user == null) || pkg.equals(getPackageName()) || !Vault.paired(this)) { callback.onSuccess(null); return; }

            List<AutofillId> ids = new ArrayList<>();
            if (f.user != null) ids.add(f.user);
            if (f.pass != null) ids.add(f.pass);
            Vault.Data data;
            try {
                data = Vault.load(this);
            } catch (Exception locked) {
                // the phone has not been unlocked recently enough: send the owner to Soot to unlock, then they tap the field again
                Intent i = new Intent(this, VaultActivity.class).putExtra("unlock", true);
                PendingIntent pi = PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);
                callback.onSuccess(new FillResponse.Builder()
                        .setAuthentication(ids.toArray(new AutofillId[0]), pi.getIntentSender(), row("Unlock Soot to fill"))
                        .build());
                return;
            }
            if (data == null) { callback.onSuccess(null); return; }
            FillResponse.Builder response = new FillResponse.Builder();
            int count = 0;
            for (Vault.Login l : data.logins) {
                if (!matches(l, f.domain, pkg)) continue;
                Dataset.Builder ds = new Dataset.Builder();
                RemoteViews label = row((l.user.isEmpty() ? "(no username)" : l.user) + "  ·  " + l.host());
                if (f.user != null) ds.setValue(f.user, AutofillValue.forText(l.user), label);
                if (f.pass != null) ds.setValue(f.pass, AutofillValue.forText(l.pass), label);
                response.addDataset(ds.build());
                if (++count >= 6) break;
            }
            callback.onSuccess(count > 0 ? response.build() : null);
        } catch (Exception e) {
            callback.onSuccess(null);
        }
    }

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        callback.onSuccess();      // the phone copy is read-only: new logins are saved in Soot on the PC
    }
}
