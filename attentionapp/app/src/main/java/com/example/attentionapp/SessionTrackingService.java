package com.example.attentionapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class SessionTrackingService extends Service {
    public static final String CHANNEL_ID = "session_tracker_service_channel";
    public static final int NOTIFICATION_ID = 2001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable backgroundMonitorRunnable;

    public static void start(Context context) {
        Intent intent = new Intent(context, SessionTrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, SessionTrackingService.class);
        context.stopService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createServiceNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildForegroundNotification();
        startForeground(NOTIFICATION_ID, notification);

        startBackgroundPolling();
        return START_STICKY;
    }

    private String lastTrackedPackage = null;
    private int pollTickCount = 0;

    private void startBackgroundPolling() {
        if (backgroundMonitorRunnable != null) {
            handler.removeCallbacks(backgroundMonitorRunnable);
        }

        backgroundMonitorRunnable = new Runnable() {
            @Override
            public void run() {
                SessionManager sm = SessionManager.getInstance();
                if (sm.isSessionActive()) {
                    Context context = getApplicationContext();
                    String currentFg = SessionManager.getForegroundAppPackage(context);

                    if (currentFg != null) {
                        if (currentFg.equals(getPackageName())) {
                            // Returned to study app
                            if (lastTrackedPackage != null && !lastTrackedPackage.equals(getPackageName())) {
                                sm.restoreFocus(context);
                            }
                            lastTrackedPackage = currentFg;
                        } else {
                            // Switched to an external app or home screen
                            if (!currentFg.equals(lastTrackedPackage)) {
                                lastTrackedPackage = currentFg;
                                sm.recordAppSwitch(context, currentFg);
                            } else if (sm.isExternalAppDistracted()) {
                                sm.recordExternalApp(context, currentFg, 1000);
                                sm.recalculateFocusScore();
                            }
                        }
                    } else {
                        // Phone locked, screen off, or no foreground app
                        if (sm.isExternalAppDistracted()) {
                            sm.restoreFocus(context);
                        }
                        lastTrackedPackage = null;
                    }

                    pollTickCount++;
                    if (pollTickCount % 5 == 0) {
                        sm.refreshExternalAppsFromUsageStats(context);
                        NotificationManager nm = getSystemService(NotificationManager.class);
                        if (nm != null) {
                            nm.notify(NOTIFICATION_ID, buildForegroundNotification());
                        }
                    }

                    handler.postDelayed(this, 1000); // 1s continuous detection
                } else {
                    stopSelf();
                }
            }
        };
        handler.postDelayed(backgroundMonitorRunnable, 1000);
    }

    private Notification buildForegroundNotification() {
        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        SessionManager sm = SessionManager.getInstance();
        String content = "Switches: " + sm.getTabSwitchCount() +
                " | Score: " + sm.getFocusScore() + "%" +
                " | Notifs: " + sm.getNotificationsReceivedCount() +
                " (Ignored: " + sm.getNotificationsIgnoredCount() + ")";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🎓 Study Session Active (" + sm.getFormattedSessionDuration() + ")")
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createServiceNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Study Session Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Maintains background study session attention and tab switch monitoring");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (backgroundMonitorRunnable != null) {
            handler.removeCallbacks(backgroundMonitorRunnable);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
