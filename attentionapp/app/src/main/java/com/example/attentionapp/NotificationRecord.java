package com.example.attentionapp;

public class NotificationRecord {
    private final String packageName;
    private final String appName;
    private final String title;
    private final long timestamp;
    private boolean ignored; // True if user stayed focused on study tab

    public NotificationRecord(String packageName, String appName, String title, long timestamp, boolean ignored) {
        this.packageName = packageName;
        this.appName = appName;
        this.title = title;
        this.timestamp = timestamp;
        this.ignored = ignored;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getAppName() {
        return appName;
    }

    public String getTitle() {
        return title;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isIgnored() {
        return ignored;
    }

    public void setIgnored(boolean ignored) {
        this.ignored = ignored;
    }
}
