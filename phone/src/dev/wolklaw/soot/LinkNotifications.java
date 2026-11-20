package dev.wolklaw.soot;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Passes this phone's notifications to the PC, read-only. Android runs it only after the owner grants
 * Soot notification access, and it sends nothing unless NOTIFICATIONS is on and the PC is connected.
 * Ongoing notifications (music, downloads, Soot's own) and group summaries are left out.
 */
public final class LinkNotifications extends NotificationListenerService {

    static boolean allowed(Context c) {
        String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(c, LinkNotifications.class).flattenToString());
    }

    private boolean skip(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        return sbn.getPackageName().equals(getPackageName()) || sbn.isOngoing()
                || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0
                || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (skip(sbn) || !LinkState.on(this, "notify")) return;
        Bundle x = sbn.getNotification().extras;
        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (text == null) text = x.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null && text == null) return;
        LinkService.notificationPosted(label(sbn.getPackageName()), title == null ? "" : title.toString(), text == null ? "" : text.toString(), sbn.getKey());
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (skip(sbn) || !LinkState.on(this, "notify")) return;
        LinkService.notificationRemoved(sbn.getKey());
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
