package com.example.attentionapp;

public class ExternalAppUsage {
    private final String packageName;
    private final String appName;
    private long durationMillis;
    private int openCount;

    public ExternalAppUsage(String packageName, String appName, long durationMillis, int openCount) {
        this.packageName = packageName;
        this.appName = appName;
        this.durationMillis = durationMillis;
        this.openCount = openCount;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getAppName() {
        return appName;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public void addDuration(long millis) {
        this.durationMillis += millis;
    }

    public int getOpenCount() {
        return openCount;
    }

    public void incrementOpenCount() {
        this.openCount++;
    }

    public String getFormattedDuration() {
        long sec = durationMillis / 1000;
        long min = sec / 60;
        long remainingSec = sec % 60;
        if (min == 0) {
            return remainingSec + "s";
        }
        return min + "m " + remainingSec + "s";
    }
}
