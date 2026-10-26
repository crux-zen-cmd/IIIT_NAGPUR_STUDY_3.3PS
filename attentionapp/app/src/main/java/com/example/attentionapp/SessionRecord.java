package com.example.attentionapp;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SessionRecord {
    private final String sessionId;
    private final String userId;
    private final String topic;
    private final long startTime;
    private final long endTime;
    private final int durationSeconds;
    private final int focusScore;
    private final int tabSwitches;
    private final String timeOfDay; // Morning, Afternoon, Evening, Night, Late Night
    private final int notificationsReceived;
    private final int notificationsIgnored;
    private final String externalAppsJson;
    private final double coinsEarned;

    public SessionRecord(String sessionId, String userId, String topic, long startTime, long endTime,
                         int durationSeconds, int focusScore, int tabSwitches, String timeOfDay,
                         int notificationsReceived, int notificationsIgnored, String externalAppsJson,
                         double coinsEarned) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.topic = topic;
        this.startTime = startTime;
        this.endTime = endTime;
        this.durationSeconds = durationSeconds;
        this.focusScore = focusScore;
        this.tabSwitches = tabSwitches;
        this.timeOfDay = timeOfDay;
        this.notificationsReceived = notificationsReceived;
        this.notificationsIgnored = notificationsIgnored;
        this.externalAppsJson = externalAppsJson != null ? externalAppsJson : "{}";
        this.coinsEarned = coinsEarned;
    }

    public SessionRecord(String sessionId, String userId, String topic, long startTime, long endTime,
                         int durationSeconds, int focusScore, int tabSwitches, String timeOfDay,
                         int notificationsReceived, int notificationsIgnored, String externalAppsJson) {
        this(sessionId, userId, topic, startTime, endTime, durationSeconds, focusScore, tabSwitches,
             timeOfDay, notificationsReceived, notificationsIgnored, externalAppsJson,
             SessionDatabaseHelper.calculateCoinsEarned(durationSeconds, focusScore));
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public String getTopic() {
        return topic;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public int getFocusScore() {
        return focusScore;
    }

    public int getTabSwitches() {
        return tabSwitches;
    }

    public String getTimeOfDay() {
        return timeOfDay;
    }

    public int getNotificationsReceived() {
        return notificationsReceived;
    }

    public int getNotificationsIgnored() {
        return notificationsIgnored;
    }

    public String getExternalAppsJson() {
        return externalAppsJson;
    }

    public String getFormattedDateTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd · hh:mm a", Locale.getDefault());
        return sdf.format(new Date(startTime));
    }

    public String getFormattedChartTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault());
        return sdf.format(new Date(startTime));
    }

    public String getFormattedShortTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a", Locale.getDefault());
        return sdf.format(new Date(startTime));
    }

    public String getFormattedDuration() {
        int min = durationSeconds / 60;
        int sec = durationSeconds % 60;
        if (min == 0) return sec + "s";
        if (sec == 0) return min + "m";
        return min + "m " + sec + "s";
    }

    public int getFocusedPercent() {
        return Math.max(0, Math.min(100, focusScore));
    }

    public int getDistractedPercent() {
        return Math.max(0, Math.min(100 - getFocusedPercent(), tabSwitches * 10));
    }

    public int getLostPercent() {
        return Math.max(0, 100 - getFocusedPercent() - getDistractedPercent());
    }

    public double getCoinsEarned() {
        return coinsEarned;
    }

    public String getFormattedCoins() {
        return String.format(Locale.US, "%.2f", coinsEarned);
    }
}
