package com.example.attentionapp;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * XpManager — Handles XP accumulation, leveling, and titles.
 * XP formula: 10 XP per focused minute + bonus for high focus score.
 * Stored in SharedPreferences under "attentionai_xp_<userId>".
 */
public class XpManager {

    public static final String[] LEVEL_TITLES = {
        "Novice",        // 0
        "Apprentice",    // 1
        "Scholar",       // 2
        "Adept",         // 3
        "Sage",          // 4
        "Master",        // 5
        "Archmage",      // 6
        "Grandmaster"    // 7
    };

    // XP thresholds for each level (cumulative)
    private static final int[] LEVEL_XP_THRESHOLDS = {
        0,      // Novice      → 0–499
        500,    // Apprentice  → 500–1499
        1500,   // Scholar     → 1500–2999
        3000,   // Adept       → 3000–4999
        5000,   // Sage        → 5000–7999
        8000,   // Master      → 8000–11999
        12000,  // Archmage    → 12000–19999
        20000   // Grandmaster → 20000+
    };

    private static final String PREF_FILE_PREFIX = "attentionai_xp_";
    private static final String KEY_TOTAL_XP = "total_xp";

    private final SharedPreferences prefs;

    public XpManager(Context context, String userId) {
        prefs = context.getSharedPreferences(PREF_FILE_PREFIX + userId, Context.MODE_PRIVATE);
    }

    /** Calculate XP earned from a session. */
    public static int calculateSessionXp(int durationSeconds, int focusScore) {
        int focusMinutes = durationSeconds / 60;
        int baseXp = focusMinutes * 10;
        // Bonus: up to +50% XP for perfect focus
        double bonus = (focusScore / 100.0) * 0.5;
        return (int) Math.round(baseXp * (1.0 + bonus));
    }

    /** Award XP from a completed session and return the earned amount. */
    public int awardSessionXp(int durationSeconds, int focusScore) {
        int xp = calculateSessionXp(durationSeconds, focusScore);
        addXp(xp);
        return xp;
    }

    public void addXp(int amount) {
        long current = getTotalXp();
        prefs.edit().putLong(KEY_TOTAL_XP, current + amount).apply();
    }

    public long getTotalXp() {
        return prefs.getLong(KEY_TOTAL_XP, 0);
    }

    /** Returns 0-based level index (0 = Novice, 7 = Grandmaster). */
    public int getLevel() {
        long xp = getTotalXp();
        int level = 0;
        for (int i = LEVEL_XP_THRESHOLDS.length - 1; i >= 0; i--) {
            if (xp >= LEVEL_XP_THRESHOLDS[i]) {
                level = i;
                break;
            }
        }
        return level;
    }

    public String getLevelTitle() {
        return LEVEL_TITLES[getLevel()];
    }

    /** XP within the current level band (for progress bar). */
    public int getXpInCurrentLevel() {
        long xp = getTotalXp();
        int level = getLevel();
        return (int)(xp - LEVEL_XP_THRESHOLDS[level]);
    }

    /** Total XP needed to reach the NEXT level from start of current level. */
    public int getXpNeededForNextLevel() {
        int level = getLevel();
        if (level >= LEVEL_TITLES.length - 1) return 1; // Grandmaster cap
        return LEVEL_XP_THRESHOLDS[level + 1] - LEVEL_XP_THRESHOLDS[level];
    }

    /** Progress (0.0–1.0) within current level band. */
    public float getLevelProgress() {
        int needed = getXpNeededForNextLevel();
        if (needed <= 0) return 1.0f;
        return Math.min(1.0f, (float) getXpInCurrentLevel() / needed);
    }

    /** Level emoji badge. */
    public static String getLevelEmoji(int level) {
        String[] emojis = {"🌱", "📚", "⭐", "💎", "🔮", "⚡", "🌌", "👑"};
        return emojis[Math.min(level, emojis.length - 1)];
    }
}
