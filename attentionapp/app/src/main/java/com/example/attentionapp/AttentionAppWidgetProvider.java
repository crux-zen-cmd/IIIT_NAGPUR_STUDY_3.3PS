package com.example.attentionapp;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

public class AttentionAppWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_START_SESSION_FROM_WIDGET = "com.example.attentionapp.ACTION_START_SESSION_FROM_WIDGET";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    public static void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        SharedPreferences prefs = context.getSharedPreferences(StatisticsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String userId = prefs.getString(StatisticsActivity.KEY_USER_ID, StatisticsActivity.DEFAULT_USER_ID);

        SessionDatabaseHelper db = SessionDatabaseHelper.getInstance(context);

        int streak = db.getCurrentStreak(userId);
        int todaySec = db.getTodayTotalSeconds(userId);
        int totalSessions = db.getTotalSessionsCount(userId);
        int avgDurationSec = db.getAverageDurationSecondsPerSession(userId);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_attention_tracker);

        views.setTextViewText(R.id.tvWidgetUserId, " • " + userId);
        views.setTextViewText(R.id.tvWidgetStreak, "🔥 " + streak + (streak == 1 ? " Day" : " Days"));

        int todayMin = todaySec / 60;
        int todayHours = todayMin / 60;
        int remMin = todayMin % 60;
        String formattedToday;
        if (todayHours > 0) {
            formattedToday = todayHours + "h " + remMin + "m";
        } else {
            formattedToday = todayMin + "m";
        }
        views.setTextViewText(R.id.tvWidgetTodayTime, formattedToday);

        int avgMin = avgDurationSec / 60;
        views.setTextViewText(R.id.tvWidgetAvgSession, avgMin + "m");
        views.setTextViewText(R.id.tvWidgetTotalSessions, String.valueOf(totalSessions));

        // 1. Click entire widget -> Open MainActivity
        Intent mainIntent = new Intent(context, MainActivity.class);
        PendingIntent mainPending = PendingIntent.getActivity(
                context, 0, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widgetContainer, mainPending);

        // 2. Click "Start Session" -> Open MainActivity with auto start prompt
        Intent startIntent = new Intent(context, MainActivity.class);
        startIntent.setAction(ACTION_START_SESSION_FROM_WIDGET);
        PendingIntent startPending = PendingIntent.getActivity(
                context, 1, startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.btnWidgetStart, startPending);

        // 3. Click "View Stats" -> Open StatisticsActivity
        Intent statsIntent = new Intent(context, StatisticsActivity.class);
        PendingIntent statsPending = PendingIntent.getActivity(
                context, 2, statsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.btnWidgetStats, statsPending);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    /**
     * Statically updates all placed home screen widgets.
     */
    public static void updateAllWidgets(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            ComponentName widgetComponent = new ComponentName(context, AttentionAppWidgetProvider.class);
            int[] widgetIds = manager.getAppWidgetIds(widgetComponent);
            if (widgetIds != null && widgetIds.length > 0) {
                for (int id : widgetIds) {
                    updateAppWidget(context, manager, id);
                }
            }
        } catch (Exception ignored) {}
    }
}
