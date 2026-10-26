package com.example.attentionapp;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class AttentionNotificationManager {
    public static final String CHANNEL_ID = "attention_tracker_channel";
    public static final int NOTIFICATION_ID = 1001;
    public static final String EXTRA_RETURN_TO_STUDY = "extra_return_to_study";

    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Attention Alerts";
            String description = "Notifies when student leaves study tab or loses focus";
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            channel.enableLights(true);
            channel.setLightColor(Color.RED);
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 300, 150, 300});

            NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    public static void showTabSwitchNotification(Context context, int switchCount, int currentScore, String fromTab, String toTab) {
        createNotificationChannel(context);

        // Intent to return directly to MainActivity and focus the study tab
        Intent returnIntent = new Intent(context, MainActivity.class);
        returnIntent.setAction("ACTION_RETURN_TO_STUDY");
        returnIntent.putExtra(EXTRA_RETURN_TO_STUDY, true);
        returnIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent returnPendingIntent = PendingIntent.getActivity(
                context,
                0,
                returnIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String title = "⚠️ Attention Lost: Tab Switch Detected!";
        String content = "Switched to: " + toTab + " | Total Switches: " + switchCount + " | Focus Score: " + currentScore + "%";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        "You navigated away from your primary study tab.\n\n" +
                        "• Destination: " + toTab + "\n" +
                        "• Total Tab Switches: " + switchCount + "\n" +
                        "• Attention Score: " + currentScore + "%\n\n" +
                        "Tap below to immediately restore focus."
                ))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(returnPendingIntent)
                .addAction(android.R.drawable.ic_menu_revert, "Return to Study Tab", returnPendingIntent);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);

        // Check permission on Android 13+ (TIRAMISU)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return; // User has not granted notification permission yet
            }
        }

        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException ignored) {
        }
    }

    public static void showAppSwitchNotification(Context context, String appName, int switchCount, int currentScore) {
        createNotificationChannel(context);

        Intent returnIntent = new Intent(context, MainActivity.class);
        returnIntent.setAction("ACTION_RETURN_TO_STUDY");
        returnIntent.putExtra(EXTRA_RETURN_TO_STUDY, true);
        returnIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent returnPendingIntent = PendingIntent.getActivity(
                context,
                0,
                returnIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String title = "⚠️ Focus Lost: Switched to " + appName + "!";
        String content = "You opened " + appName + ". Switches: " + switchCount + " | Attention Score: " + currentScore + "%";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        "🚨 DISTRACTION DETECTED!\n\n" +
                        "• App Opened: " + appName + "\n" +
                        "• Switches Today: " + switchCount + "\n" +
                        "• Attention Score: " + currentScore + "%\n\n" +
                        "Tap below to restore your focus in AttentionAI."
                ))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(returnPendingIntent)
                .addAction(android.R.drawable.ic_menu_revert, "Return to Study", returnPendingIntent);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException ignored) {}
    }

    public static void cancelNotification(Context context) {
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        notificationManager.cancel(NOTIFICATION_ID);
    }
}
