package com.example.attentionapp;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SessionManager {
    private static SessionManager instance;

    public interface SessionUpdateListener {
        void onSessionUpdated();
        default void onAppSwitchDetected(String packageName, String appName) {}
        default void onFocusRestored(String reason) {}
    }

    private boolean isSessionActive = false;
    private long sessionStartTime = 0;
    private long sessionEndTime = 0;
    private String sessionTopic = "Self-Paced Study";

    private int tabSwitchCount = 0;
    private int focusScore = 100;

    // Time-based Distraction Tracking
    private long cumulativeDistractionTimeMillis = 0;
    private long currentDistractionStartTime = 0;

    // External App Tracking
    private final Map<String, ExternalAppUsage> externalAppMap = new HashMap<>();
    private String currentExternalPackage = null;
    private String currentExternalAppName = null;
    private boolean isExternalAppDistracted = false;

    // Notification Tracking
    private final List<NotificationRecord> notificationRecords = new ArrayList<>();
    private int notificationsReceivedCount = 0;
    private int notificationsIgnoredCount = 0;
    private int notificationsInterruptedCount = 0;

    private SessionUpdateListener updateListener;

    public static synchronized SessionManager getInstance() {
        if (instance == null) {
            instance = new SessionManager();
        }
        return instance;
    }

    private SessionManager() {}

    public void setUpdateListener(SessionUpdateListener listener) {
        this.updateListener = listener;
    }

    public synchronized void startSession(Context context, String topic) {
        this.isSessionActive = true;
        this.sessionStartTime = System.currentTimeMillis();
        this.sessionEndTime = 0;
        this.sessionTopic = (topic != null && !topic.trim().isEmpty()) ? topic.trim() : "General Study";
        this.tabSwitchCount = 0;
        this.focusScore = 100;
        this.cumulativeDistractionTimeMillis = 0;
        this.currentDistractionStartTime = 0;
        this.currentExternalPackage = null;
        this.currentExternalAppName = null;
        this.isExternalAppDistracted = false;
        this.externalAppMap.clear();
        this.notificationRecords.clear();
        this.notificationsReceivedCount = 0;
        this.notificationsIgnoredCount = 0;
        this.notificationsInterruptedCount = 0;

        // Start Foreground Service for reliable background tracking
        SessionTrackingService.start(context);

        notifyUpdate();
    }

    public synchronized void endSession(Context context) {
        if (!isSessionActive) return;
        this.sessionEndTime = System.currentTimeMillis();

        // Finalize any ongoing distraction interval
        if (this.isExternalAppDistracted && this.currentDistractionStartTime > 0) {
            long duration = Math.max(0, this.sessionEndTime - this.currentDistractionStartTime);
            this.cumulativeDistractionTimeMillis += duration;
            if (this.currentExternalPackage != null) {
                recordExternalApp(context, this.currentExternalPackage, duration);
            }
            this.isExternalAppDistracted = false;
            this.currentDistractionStartTime = 0;
        }

        recalculateFocusScore();
        this.isSessionActive = false;

        // Query usage stats to finalize external apps used during session
        refreshExternalAppsFromUsageStats(context);

        // Stop Foreground Service
        SessionTrackingService.stop(context);

        notifyUpdate();
    }

    public boolean isSessionActive() {
        return isSessionActive;
    }

    public long getSessionStartTime() {
        return sessionStartTime;
    }

    public long getSessionEndTime() {
        return sessionEndTime;
    }

    public String getSessionTopic() {
        return sessionTopic;
    }

    public int getTabSwitchCount() {
        return tabSwitchCount;
    }

    public int getFocusScore() {
        return this.focusScore;
    }

    /**
     * Returns the active focus score.
     */
    public synchronized int recalculateFocusScore() {
        return this.focusScore;
    }

    /**
     * Deducts focus percentage based on how much time was taken to come back to the app.
     * @param awaySeconds number of seconds the user remained away from the study app
     * @return the penalty points deducted
     */
    public synchronized int deductTimePenalty(int awaySeconds) {
        if (!isSessionActive) return 0;
        int basePenalty = 2; // Context switch friction
        // Time penalty: +1% per ~1.6s away (scales with time taken to return)
        int timePenalty = (int) Math.round(awaySeconds * 0.6f);
        int penalty = Math.max(2, Math.min(75, basePenalty + timePenalty));

        this.focusScore = Math.max(5, this.focusScore - penalty);
        notifyUpdate();
        return penalty;
    }

    /**
     * Slowly and steadily increases the percentage of the app when the user sustains
     * active focus without tab switching.
     * @param amount points to recover (+1 per recovery interval)
     * @return true if score increased
     */
    public synchronized boolean recoverFocusGradually(int amount) {
        if (!isSessionActive || isExternalAppDistracted) return false;
        if (this.focusScore < 100) {
            this.focusScore = Math.min(100, this.focusScore + amount);
            notifyUpdate();
            return true;
        }
        return false;
    }

    public synchronized void recordTabSwitch(int penalty) {
        if (!isSessionActive) return;
        this.tabSwitchCount++;
        notifyUpdate();
    }

    public synchronized void recordAppSwitch(Context context, String packageName) {
        if (!isSessionActive || packageName == null) return;
        if (packageName.equals(context.getPackageName())) {
            restoreFocus(context);
            return;
        }

        String appName = resolveAppName(context, packageName);

        // ONLY reduce focus score if a distracting app (social media, quick commerce, games) is open
        if (!isDistractingApp(packageName, appName)) {
            // User opened a non-distracting app, home screen, or nothing is open.
            // If they were previously in a distracting app, end that distraction period now.
            if (isExternalAppDistracted) {
                restoreFocus(context);
            }
            return; // Do NOT deduct score!
        }

        long now = System.currentTimeMillis();

        // If previously distracted on another app, accumulate that time
        if (isExternalAppDistracted && currentDistractionStartTime > 0) {
            long prevDuration = Math.max(0, now - currentDistractionStartTime);
            this.cumulativeDistractionTimeMillis += prevDuration;
            if (currentExternalPackage != null) {
                recordExternalApp(context, currentExternalPackage, prevDuration);
            }
        }

        this.currentExternalPackage = packageName;
        this.currentExternalAppName = appName;
        this.isExternalAppDistracted = true;
        this.currentDistractionStartTime = now;
        this.tabSwitchCount++;

        markLatestNotificationInterrupted();

        AttentionNotificationManager.showAppSwitchNotification(context, appName, tabSwitchCount, focusScore);

        if (updateListener != null) {
            updateListener.onAppSwitchDetected(packageName, appName);
        }
        notifyUpdate();
    }

    public synchronized int restoreFocus(Context context) {
        if (!isSessionActive) return 0;
        boolean wasDistracted = isExternalAppDistracted;
        String prevApp = currentExternalAppName;
        String prevPkg = currentExternalPackage;

        long awayDuration = 0;
        if (isExternalAppDistracted && currentDistractionStartTime > 0) {
            long now = System.currentTimeMillis();
            awayDuration = Math.max(0, now - currentDistractionStartTime);
            this.cumulativeDistractionTimeMillis += awayDuration;
            if (prevPkg != null) {
                recordExternalApp(context, prevPkg, awayDuration);
            }
        }

        int awaySeconds = (int) Math.max(1, awayDuration / 1000);

        this.currentExternalPackage = null;
        this.currentExternalAppName = null;
        this.isExternalAppDistracted = false;
        this.currentDistractionStartTime = 0;

        int penalty = 0;
        if (wasDistracted) {
            penalty = deductTimePenalty(awaySeconds);
        }

        AttentionNotificationManager.cancelNotification(context);

        if (wasDistracted && updateListener != null) {
            updateListener.onFocusRestored(prevApp != null ? "Returned from " + prevApp + " (" + awaySeconds + "s away, -" + penalty + "%)" : "Returned to Study (" + awaySeconds + "s away, -" + penalty + "%)");
        }
        notifyUpdate();
        return penalty;
    }

    public String getCurrentExternalAppName() {
        return currentExternalAppName;
    }

    public boolean isExternalAppDistracted() {
        return isExternalAppDistracted;
    }

    public long getCumulativeDistractionTimeMillis() {
        long currentEpisode = 0;
        if (isExternalAppDistracted && currentDistractionStartTime > 0) {
            currentEpisode = Math.max(0, System.currentTimeMillis() - currentDistractionStartTime);
        }
        return cumulativeDistractionTimeMillis + currentEpisode;
    }

    public synchronized void recoverScore(int amount) {
        recoverFocusGradually(amount);
    }

    public synchronized void recordExternalApp(Context context, String packageName, long durationMillis) {
        if (!isSessionActive || packageName == null || packageName.equals(context.getPackageName())) {
            return;
        }

        String appName = resolveAppName(context, packageName);
        ExternalAppUsage usage = externalAppMap.get(packageName);
        if (usage == null) {
            usage = new ExternalAppUsage(packageName, appName, durationMillis, 1);
            externalAppMap.put(packageName, usage);
        } else {
            usage.addDuration(durationMillis);
            usage.incrementOpenCount();
        }
        notifyUpdate();
    }

    public synchronized void recordNotification(Context context, String packageName, String title) {
        if (!isSessionActive) return;

        // Ignore our own notifications
        if (packageName != null && packageName.equals(context.getPackageName())) {
            return;
        }

        String appName = resolveAppName(context, packageName);
        notificationsReceivedCount++;

        // Default to ignored (resisted) until user switches away
        NotificationRecord record = new NotificationRecord(
                packageName,
                appName,
                title != null ? title : "Notification",
                System.currentTimeMillis(),
                true
        );
        notificationRecords.add(record);
        notificationsIgnoredCount++;

        notifyUpdate();
    }

    public synchronized void markLatestNotificationInterrupted() {
        if (!isSessionActive || notificationRecords.isEmpty()) return;

        long now = System.currentTimeMillis();
        // If a notification arrived in the last 20 seconds, mark it as having interrupted the user
        for (int i = notificationRecords.size() - 1; i >= 0; i--) {
            NotificationRecord rec = notificationRecords.get(i);
            if (now - rec.getTimestamp() < 20000 && rec.isIgnored()) {
                rec.setIgnored(false);
                notificationsIgnoredCount = Math.max(0, notificationsIgnoredCount - 1);
                notificationsInterruptedCount++;
                break;
            }
        }
        notifyUpdate();
    }

    public synchronized List<ExternalAppUsage> getExternalAppList(Context context) {
        refreshExternalAppsFromUsageStats(context);
        List<ExternalAppUsage> list = new ArrayList<>(externalAppMap.values());
        Collections.sort(list, (a, b) -> Long.compare(b.getDurationMillis(), a.getDurationMillis()));
        return list;
    }

    public int getNotificationsReceivedCount() {
        return notificationsReceivedCount;
    }

    public int getNotificationsIgnoredCount() {
        return notificationsIgnoredCount;
    }

    public int getNotificationsInterruptedCount() {
        return notificationsInterruptedCount;
    }

    public String getFormattedSessionDuration() {
        long end = (sessionEndTime > sessionStartTime) ? sessionEndTime : System.currentTimeMillis();
        long diffSec = Math.max(0, (end - sessionStartTime) / 1000);
        long min = diffSec / 60;
        long sec = diffSec % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec);
    }

    public boolean hasUsageStatsPermission(Context context) {
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.getPackageName()
            );
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    public void refreshExternalAppsFromUsageStats(Context context) {
        if (!hasUsageStatsPermission(context) || sessionStartTime <= 0) return;

        try {
            UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            long end = (sessionEndTime > sessionStartTime) ? sessionEndTime : System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(sessionStartTime, end);

            UsageEvents.Event event = new UsageEvents.Event();
            String currentForegroundPkg = null;
            long resumeTime = 0;

            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                String pkg = event.getPackageName();
                if (pkg == null || pkg.equals(context.getPackageName())) continue;

                // Filter out common android launchers/system UI if wanted
                if (pkg.contains("launcher") || pkg.contains("systemui")) continue;

                if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                    currentForegroundPkg = pkg;
                    resumeTime = event.getTimeStamp();
                } else if (event.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED && pkg.equals(currentForegroundPkg)) {
                    long duration = Math.max(500, event.getTimeStamp() - resumeTime);
                    recordExternalApp(context, pkg, duration);
                    currentForegroundPkg = null;
                }
            }
        } catch (Exception ignored) {}
    }

    public static String getForegroundAppPackage(Context context) {
        if (context == null) return null;
        try {
            UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;

            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 4000, now);
            if (events == null) return null;

            UsageEvents.Event event = new UsageEvents.Event();
            String lastResumedPkg = null;
            long lastResumedTime = 0;

            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (event.getTimeStamp() >= lastResumedTime) {
                        lastResumedTime = event.getTimeStamp();
                        lastResumedPkg = event.getPackageName();
                    }
                }
            }
            return lastResumedPkg;
        } catch (Exception e) {
            return null;
        }
    }

    public String resolveAppName(Context context, String packageName) {
        if (packageName == null) return "Unknown App";
        if (packageName.equals(context.getPackageName())) return "AttentionAI";
        if (packageName.contains("launcher") || packageName.contains("systemui") || packageName.contains("trebuchet")) {
            return "Home Screen";
        }

        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Exception ignored) {}

        // Fallbacks for well-known popular apps
        if (packageName.contains("youtube")) return "YouTube";
        if (packageName.contains("whatsapp")) return "WhatsApp";
        if (packageName.contains("instagram")) return "Instagram";
        if (packageName.contains("chrome")) return "Chrome";
        if (packageName.contains("facebook")) return "Facebook";
        if (packageName.contains("reddit")) return "Reddit";
        if (packageName.contains("twitter") || packageName.contains("x.android")) return "X (Twitter)";
        if (packageName.contains("netflix")) return "Netflix";
        if (packageName.contains("spotify")) return "Spotify";
        if (packageName.contains("telegram")) return "Telegram";
        if (packageName.contains("tiktok")) return "TikTok";
        if (packageName.contains("discord")) return "Discord";
        if (packageName.contains("snapchat")) return "Snapchat";

        if (packageName.contains("grofers") || packageName.contains("blinkit")) return "Blinkit";
        if (packageName.contains("swiggy")) return "Swiggy / Instamart";
        if (packageName.contains("zepto")) return "Zepto";
        if (packageName.contains("zomato")) return "Zomato";
        if (packageName.contains("flipkart")) return "Flipkart";
        if (packageName.contains("amazon.mshop")) return "Amazon";
        if (packageName.contains("myntra")) return "Myntra";
        if (packageName.contains("barcelona")) return "Threads";

        if (packageName.contains(".")) {
            String[] parts = packageName.split("\\.");
            String last = parts[parts.length - 1];
            return last.substring(0, 1).toUpperCase(Locale.getDefault()) + last.substring(1);
        }
        return packageName;
    }

    /**
     * Identifies if a foreground package is a social media, quick commerce, or entertainment distraction.
     * Returns false for Home Screen, Launcher, System UI, Lockscreen, phone utilities, or when nothing is open.
     */
    public static boolean isDistractingApp(String packageName, String appName) {
        if (packageName == null) return false;
        String pkg = packageName.toLowerCase(Locale.US);
        String name = (appName != null) ? appName.toLowerCase(Locale.US) : "";

        // Explicitly ignored: Launchers, System UI, Lockscreen, Keyguard, Android framework, AttentionApp
        if (pkg.contains("launcher") || pkg.contains("systemui") || pkg.contains("keyguard")
                || pkg.contains("lockscreen") || pkg.contains("trebuchet") || pkg.contains("nexuslauncher")
                || pkg.contains("miui.home") || pkg.contains("sec.android.app.launcher")
                || pkg.contains("com.android.settings") || pkg.contains("com.android.systemui")
                || pkg.contains("com.android.dialer") || pkg.contains("com.android.phone")
                || pkg.contains("com.google.android.inputmethod") || pkg.contains("com.android.deskclock")
                || pkg.contains("com.android.calculator2") || pkg.contains("calculator")) {
            return false;
        }

        // 1. Social Media & Messaging platforms (Instagram, Snapchat, WhatsApp, X, Facebook, etc.)
        if (pkg.contains("instagram") || name.contains("instagram")
                || pkg.contains("snapchat") || name.contains("snapchat")
                || pkg.contains("whatsapp") || name.contains("whatsapp")
                || pkg.contains("twitter") || pkg.contains(".x") || name.contains("twitter") || name.equals("x")
                || pkg.contains("facebook") || pkg.contains("katana") || pkg.contains("orca") || name.contains("facebook") || name.contains("messenger")
                || pkg.contains("telegram") || name.contains("telegram")
                || pkg.contains("reddit") || name.contains("reddit")
                || pkg.contains("tiktok") || pkg.contains("musically") || name.contains("tiktok")
                || pkg.contains("youtube") || name.contains("youtube")
                || pkg.contains("discord") || name.contains("discord")
                || pkg.contains("threads") || pkg.contains("barcelona") || name.contains("threads")
                || pkg.contains("pinterest") || name.contains("pinterest")
                || pkg.contains("linkedin") || name.contains("linkedin")) {
            return true;
        }

        // 2. Quick Commerce, Food Delivery & Shopping (Blinkit, Swiggy / Instamart, Zepto, Zomato, etc.)
        if (pkg.contains("grofers") || pkg.contains("blinkit") || name.contains("blinkit")
                || pkg.contains("swiggy") || name.contains("swiggy") || name.contains("instamart")
                || pkg.contains("zepto") || name.contains("zepto")
                || pkg.contains("zomato") || name.contains("zomato")
                || pkg.contains("dunzo") || name.contains("dunzo")
                || pkg.contains("bigbasket") || name.contains("bigbasket")
                || pkg.contains("flipkart") || name.contains("flipkart")
                || pkg.contains("amazon.mshop") || name.contains("amazon")
                || pkg.contains("myntra") || name.contains("myntra")
                || pkg.contains("meesho") || name.contains("meesho")
                || pkg.contains("ajio") || name.contains("ajio")
                || pkg.contains("nykaa") || name.contains("nykaa")) {
            return true;
        }

        // 3. Entertainment, Streaming & Games
        if (pkg.contains("netflix") || name.contains("netflix")
                || pkg.contains("primevideo") || pkg.contains("amazon.avod") || name.contains("prime video")
                || pkg.contains("hotstar") || name.contains("hotstar")
                || pkg.contains("spotify") || name.contains("spotify")
                || pkg.contains("twitch") || name.contains("twitch")
                || pkg.contains("pubg") || pkg.contains("freefire") || pkg.contains("roblox") || pkg.contains("supercell")
                || pkg.contains("game") || name.contains("game")) {
            return true;
        }

        return false;
    }

    private void notifyUpdate() {
        if (updateListener != null) {
            updateListener.onSessionUpdated();
        }
    }
}
