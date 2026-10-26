package com.example.attentionapp;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * AchievementManager — Tracks and unlocks achievements based on study behavior.
 * Achievements are stored as booleans in SharedPreferences.
 */
public class AchievementManager {

    public static class Achievement {
        public final String id;
        public final String emoji;
        public final String title;
        public final String description;
        public boolean unlocked;

        public Achievement(String id, String emoji, String title, String description) {
            this.id = id;
            this.emoji = emoji;
            this.title = title;
            this.description = description;
            this.unlocked = false;
        }
    }

    private static final String PREF_FILE_PREFIX = "attentionai_achievements_";
    private final SharedPreferences prefs;

    public AchievementManager(Context context, String userId) {
        prefs = context.getSharedPreferences(PREF_FILE_PREFIX + userId, Context.MODE_PRIVATE);
    }

    /** Returns ALL achievements with current unlock status loaded. */
    public List<Achievement> getAllAchievements() {
        List<Achievement> list = new ArrayList<>();

        list.add(make("first_steps",       "🚀", "First Steps",
            "Complete your very first study session"));
        list.add(make("streak_warrior",    "🔥", "Streak Warrior",
            "Maintain a 7-day study streak"));
        list.add(make("iron_focus",        "⚡", "Iron Focus",
            "Score 100% focus in a 30+ minute session"));
        list.add(make("comeback_kid",      "💪", "The Comeback Kid",
            "Return and study after a 3+ day break"));
        list.add(make("night_owl",         "🦉", "Night Owl Elite",
            "Complete 5 sessions after 10 PM"));
        list.add(make("marathon_scholar",  "🏃", "Marathon Scholar",
            "Complete a single session longer than 2 hours"));
        list.add(make("coin_collector",    "🪙", "Coin Collector",
            "Earn 50 or more coins lifetime"));
        list.add(make("distraction_slayer","🛡️", "Distraction Slayer",
            "Complete 10 sessions with zero app switches"));
        list.add(make("century_club",      "💯", "Century Club",
            "Accumulate 100 total study sessions"));
        list.add(make("deep_work",         "🧘", "Deep Work",
            "Study for 4+ hours in a single day"));
        list.add(make("xp_1000",           "⭐", "Rising Scholar",
            "Earn 1000 total XP"));
        list.add(make("xp_5000",           "🌟", "Focus Master",
            "Earn 5000 total XP"));

        return list;
    }

    private Achievement make(String id, String emoji, String title, String desc) {
        Achievement a = new Achievement(id, emoji, title, desc);
        a.unlocked = prefs.getBoolean("ach_" + id, false);
        return a;
    }

    public boolean isUnlocked(String id) {
        return prefs.getBoolean("ach_" + id, false);
    }

    /**
     * Attempt to unlock an achievement. Returns true if newly unlocked (triggers toast/badge).
     */
    public boolean tryUnlock(String id) {
        if (prefs.getBoolean("ach_" + id, false)) return false; // already unlocked
        prefs.edit().putBoolean("ach_" + id, true).apply();
        return true;
    }

    public int getUnlockedCount() {
        int count = 0;
        for (Achievement a : getAllAchievements()) {
            if (a.unlocked) count++;
        }
        return count;
    }

    /**
     * Evaluate all conditions for a just-completed session and unlock applicable achievements.
     * Returns list of newly-unlocked achievement titles (for notifications).
     */
    public List<String> evaluateSessionAchievements(
            SessionRecord session,
            int totalSessionsEver,
            int currentStreak,
            double lifetimeCoins,
            long totalStudySecondsToday,
            int zeroSwitchSessionCount,
            long totalXp) {

        List<String> newlyUnlocked = new ArrayList<>();

        // First Steps
        if (totalSessionsEver >= 1 && tryUnlock("first_steps"))
            newlyUnlocked.add("First Steps 🚀");

        // Streak Warrior — 7-day streak
        if (currentStreak >= 7 && tryUnlock("streak_warrior"))
            newlyUnlocked.add("Streak Warrior 🔥");

        // Iron Focus — 100% focus in 30+ min session
        if (session.getFocusScore() >= 100 && session.getDurationSeconds() >= 1800 && tryUnlock("iron_focus"))
            newlyUnlocked.add("Iron Focus ⚡");

        // Night Owl — session between 22:00 and 04:00
        // (evaluated externally for 5-session count)

        // Marathon Scholar — single session > 2 hours
        if (session.getDurationSeconds() >= 7200 && tryUnlock("marathon_scholar"))
            newlyUnlocked.add("Marathon Scholar 🏃");

        // Coin Collector — 50+ lifetime coins
        if (lifetimeCoins >= 50.0 && tryUnlock("coin_collector"))
            newlyUnlocked.add("Coin Collector 🪙");

        // Distraction Slayer — 10 sessions with 0 switches
        if (session.getTabSwitches() == 0) {
            int count = prefs.getInt("zero_switch_count", 0) + 1;
            prefs.edit().putInt("zero_switch_count", count).apply();
            if (count >= 10 && tryUnlock("distraction_slayer"))
                newlyUnlocked.add("Distraction Slayer 🛡️");
        }

        // Century Club — 100 sessions
        if (totalSessionsEver >= 100 && tryUnlock("century_club"))
            newlyUnlocked.add("Century Club 💯");

        // Deep Work — 4+ hours today
        if (totalStudySecondsToday >= 14400 && tryUnlock("deep_work"))
            newlyUnlocked.add("Deep Work 🧘");

        // XP milestones
        if (totalXp >= 1000 && tryUnlock("xp_1000"))
            newlyUnlocked.add("Rising Scholar ⭐");
        if (totalXp >= 5000 && tryUnlock("xp_5000"))
            newlyUnlocked.add("Focus Master 🌟");

        return newlyUnlocked;
    }

    /** Increment night session count and check for Night Owl achievement. */
    public boolean recordNightSession() {
        int count = prefs.getInt("night_sessions", 0) + 1;
        prefs.edit().putInt("night_sessions", count).apply();
        if (count >= 5) return tryUnlock("night_owl");
        return false;
    }

    /** Call after a break of 3+ days, to evaluate comeback. */
    public boolean recordComeback() {
        return tryUnlock("comeback_kid");
    }
}
