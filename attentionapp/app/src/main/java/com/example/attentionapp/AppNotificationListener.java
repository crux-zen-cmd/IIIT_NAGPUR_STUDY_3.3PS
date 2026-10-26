package com.example.attentionapp;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class AppNotificationListener extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !SessionManager.getInstance().isSessionActive()) return;

        // Skip ongoing / non-clearable / progress notifications
        if (sbn.isOngoing()) return;

        String packageName = sbn.getPackageName();
        if (packageName == null || packageName.equals(getPackageName())) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        String title = "";
        if (extras != null) {
            CharSequence cs = extras.getCharSequence(Notification.EXTRA_TITLE);
            if (cs != null) title = cs.toString();
        }

        SessionManager.getInstance().recordNotification(this, packageName, title);
    }

    public static boolean isNotificationAccessGranted(Context context) {
        try {
            ComponentName cn = new ComponentName(context, AppNotificationListener.class);
            String flat = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(cn.flattenToString());
        } catch (Exception e) {
            return false;
        }
    }
}
