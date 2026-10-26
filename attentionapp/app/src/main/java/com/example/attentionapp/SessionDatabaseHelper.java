package com.example.attentionapp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SessionDatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "attention_user_sessions.db";
    private static final int DATABASE_VERSION = 2;

    public static final String TABLE_SESSIONS = "sessions";
    public static final String COL_SESSION_ID = "session_id";
    public static final String COL_USER_ID = "user_id";
    public static final String COL_TOPIC = "topic";
    public static final String COL_START_TIME = "start_time";
    public static final String COL_END_TIME = "end_time";
    public static final String COL_DURATION_SEC = "duration_seconds";
    public static final String COL_FOCUS_SCORE = "focus_score";
    public static final String COL_TAB_SWITCHES = "tab_switches";
    public static final String COL_TIME_OF_DAY = "time_of_day";
    public static final String COL_NOTIFS_RECV = "notifications_received";
    public static final String COL_NOTIFS_IGNORED = "notifications_ignored";
    public static final String COL_EXTERNAL_APPS = "external_apps_json";
    public static final String COL_COINS_EARNED = "coins_earned";

    private static SessionDatabaseHelper instance;

    public static synchronized SessionDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new SessionDatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    private SessionDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_SESSIONS + " (" +
                COL_SESSION_ID + " TEXT PRIMARY KEY, " +
                COL_USER_ID + " TEXT NOT NULL, " +
                COL_TOPIC + " TEXT NOT NULL, " +
                COL_START_TIME + " INTEGER NOT NULL, " +
                COL_END_TIME + " INTEGER NOT NULL, " +
                COL_DURATION_SEC + " INTEGER NOT NULL, " +
                COL_FOCUS_SCORE + " INTEGER NOT NULL, " +
                COL_TAB_SWITCHES + " INTEGER NOT NULL, " +
                COL_TIME_OF_DAY + " TEXT NOT NULL, " +
                COL_NOTIFS_RECV + " INTEGER NOT NULL, " +
                COL_NOTIFS_IGNORED + " INTEGER NOT NULL, " +
                COL_EXTERNAL_APPS + " TEXT, " +
                COL_COINS_EARNED + " REAL NOT NULL DEFAULT 0.0" +
                ");";
        db.execSQL(createTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_SESSIONS + " ADD COLUMN " + COL_COINS_EARNED + " REAL NOT NULL DEFAULT 0.0;");
                // Backfill any existing sessions: 0.5 coins per 2 minutes scaled by focus
                db.execSQL("UPDATE " + TABLE_SESSIONS + " SET " + COL_COINS_EARNED +
                        " = ROUND(((" + COL_DURATION_SEC + " / 120.0) * 0.5) * (" + COL_FOCUS_SCORE + " / 100.0), 2) " +
                        " WHERE " + COL_COINS_EARNED + " = 0.0;");
            } catch (Exception ignored) {}
        }
    }

    public synchronized void insertSession(SessionRecord record) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_SESSION_ID, record.getSessionId());
        cv.put(COL_USER_ID, record.getUserId());
        cv.put(COL_TOPIC, record.getTopic());
        cv.put(COL_START_TIME, record.getStartTime());
        cv.put(COL_END_TIME, record.getEndTime());
        cv.put(COL_DURATION_SEC, record.getDurationSeconds());
        cv.put(COL_FOCUS_SCORE, record.getFocusScore());
        cv.put(COL_TAB_SWITCHES, record.getTabSwitches());
        cv.put(COL_TIME_OF_DAY, record.getTimeOfDay());
        cv.put(COL_NOTIFS_RECV, record.getNotificationsReceived());
        cv.put(COL_NOTIFS_IGNORED, record.getNotificationsIgnored());
        cv.put(COL_EXTERNAL_APPS, record.getExternalAppsJson());
        cv.put(COL_COINS_EARNED, record.getCoinsEarned());

        db.insertWithOnConflict(TABLE_SESSIONS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized List<SessionRecord> getSessionsForUser(String userId) {
        List<SessionRecord> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(
                TABLE_SESSIONS,
                null,
                COL_USER_ID + " = ?",
                new String[]{userId},
                null,
                null,
                COL_START_TIME + " DESC"
        );

        if (cursor != null) {
            while (cursor.moveToNext()) {
                list.add(cursorToSessionRecord(cursor));
            }
            cursor.close();
        }
        return list;
    }

    public synchronized List<SessionRecord> getScoreTimelineForUser(String userId, int limit) {
        List<SessionRecord> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(
                TABLE_SESSIONS,
                null,
                COL_USER_ID + " = ?",
                new String[]{userId},
                null,
                null,
                COL_START_TIME + " ASC",
                String.valueOf(limit)
        );

        if (cursor != null) {
            while (cursor.moveToNext()) {
                list.add(cursorToSessionRecord(cursor));
            }
            cursor.close();
        }
        return list;
    }

    public synchronized int getTodayTotalSeconds(String userId) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long todayStart = cal.getTimeInMillis();

        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT SUM(" + COL_DURATION_SEC + ") FROM " + TABLE_SESSIONS +
                        " WHERE " + COL_USER_ID + " = ? AND " + COL_START_TIME + " >= ?",
                new String[]{userId, String.valueOf(todayStart)}
        );

        int total = 0;
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                total = cursor.getInt(0);
            }
            cursor.close();
        }
        return total;
    }

    public synchronized int getTodaySessionCount(String userId) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long todayStart = cal.getTimeInMillis();

        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_SESSIONS +
                        " WHERE " + COL_USER_ID + " = ? AND " + COL_START_TIME + " >= ?",
                new String[]{userId, String.valueOf(todayStart)}
        );

        int count = 0;
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                count = cursor.getInt(0);
            }
            cursor.close();
        }
        return count;
    }

    /**
     * Computes average focus score per time of day bucket (Morning, Afternoon, Evening, Night, Late Night)
     */
    public synchronized Map<String, Integer> getAverageScoreByTimeOfDay(String userId) {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("Morning", 0);
        result.put("Afternoon", 0);
        result.put("Evening", 0);
        result.put("Night", 0);
        result.put("Late Night", 0);

        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT " + COL_TIME_OF_DAY + ", AVG(" + COL_FOCUS_SCORE + ") FROM " + TABLE_SESSIONS +
                        " WHERE " + COL_USER_ID + " = ? GROUP BY " + COL_TIME_OF_DAY,
                new String[]{userId}
        );

        if (cursor != null) {
            while (cursor.moveToNext()) {
                String tod = cursor.getString(0);
                int avg = (int) Math.round(cursor.getDouble(1));
                if (result.containsKey(tod)) {
                    result.put(tod, avg);
                }
            }
            cursor.close();
        }
        return result;
    }

    /**
     * Determines the optimal study window for the user based on historical average focus score and chronotype.
     */
    public synchronized String getBestStudyTime(String userId, String chronotype) {
        Map<String, Integer> map = getAverageScoreByTimeOfDay(userId);
        String bestTime = null;
        int maxScore = 0;

        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (entry.getValue() > maxScore) {
                maxScore = entry.getValue();
                bestTime = entry.getKey();
            }
        }

        if (maxScore == 0) {
            if ("NIGHT".equalsIgnoreCase(chronotype)) {
                return "Night (🌙 Night Owl Focus)";
            } else {
                return "Morning (☀️ Early Bird Focus)";
            }
        }
        return bestTime + " (" + maxScore + "% Avg Focus)";
    }

    public synchronized String getBestStudyTime(String userId) {
        return getBestStudyTime(userId, "DAY");
    }

    /**
     * Computes study duration (in minutes) for each day of the week (Mon to Sun) over the last 7 days.
     */
    public synchronized double[] getDurationByDayOfWeek(String userId) {
        double[] durations = new double[7]; // 0: Mon, 1: Tue, ..., 6: Sun

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -6);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        long sevenDaysAgo = cal.getTimeInMillis();

        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT " + COL_START_TIME + ", " + COL_DURATION_SEC + " FROM " + TABLE_SESSIONS +
                        " WHERE " + COL_USER_ID + " = ? AND " + COL_START_TIME + " >= ?",
                new String[]{userId, String.valueOf(sevenDaysAgo)}
        );

        Calendar sessionCal = Calendar.getInstance();
        if (cursor != null) {
            while (cursor.moveToNext()) {
                long start = cursor.getLong(0);
                int sec = cursor.getInt(1);
                sessionCal.setTimeInMillis(start);

                int dayOfWeek = sessionCal.get(Calendar.DAY_OF_WEEK);
                // Convert Calendar.SUNDAY (1) ... SATURDAY (7) to index 0: Mon ... 6: Sun
                int index;
                if (dayOfWeek == Calendar.SUNDAY) {
                    index = 6;
                } else {
                    index = dayOfWeek - Calendar.MONDAY;
                }

                if (index >= 0 && index < 7) {
                    durations[index] += (sec / 60.0);
                }
            }
            cursor.close();
        }
        return durations;
    }

    /**
     * Set of unique calendar dates (yyyy-MM-dd) on which the user completed sessions.
     */
    public synchronized java.util.Set<String> getUniqueSessionDates(String userId) {
        java.util.Set<String> dates = new java.util.HashSet<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(
                TABLE_SESSIONS,
                new String[]{COL_START_TIME},
                COL_USER_ID + " = ?",
                new String[]{userId},
                null,
                null,
                null
        );

        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
        if (cursor != null) {
            while (cursor.moveToNext()) {
                long start = cursor.getLong(0);
                dates.add(sdf.format(new java.util.Date(start)));
            }
            cursor.close();
        }
        return dates;
    }

    /**
     * Calculates the real consecutive day study streak.
     * Counts consecutive days backwards from today (or yesterday if today hasn't had a session yet).
     */
    public synchronized int getCurrentStreak(String userId) {
        java.util.Set<String> dates = getUniqueSessionDates(userId);
        if (dates.isEmpty()) return 0;

        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
        Calendar cal = Calendar.getInstance();

        String todayStr = sdf.format(cal.getTime());
        boolean hasToday = dates.contains(todayStr);

        cal.add(Calendar.DAY_OF_YEAR, -1);
        String yesterdayStr = sdf.format(cal.getTime());
        boolean hasYesterday = dates.contains(yesterdayStr);

        if (!hasToday && !hasYesterday) {
            return 0; // Streak broken
        }

        int streak = 0;
        Calendar checkCal = Calendar.getInstance();
        if (!hasToday) {
            checkCal.add(Calendar.DAY_OF_YEAR, -1);
        }

        while (true) {
            String dStr = sdf.format(checkCal.getTime());
            if (dates.contains(dStr)) {
                streak++;
                checkCal.add(Calendar.DAY_OF_YEAR, -1);
            } else {
                break;
            }
        }
        return streak;
    }

    public synchronized int getTotalSessionsCount(String userId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_SESSIONS + " WHERE " + COL_USER_ID + " = ?",
                new String[]{userId}
        );
        int count = 0;
        if (cursor != null) {
            if (cursor.moveToFirst()) count = cursor.getInt(0);
            cursor.close();
        }
        return count;
    }

    public synchronized int getDistinctActiveDaysCount(String userId) {
        return getUniqueSessionDates(userId).size();
    }

    public synchronized double getAverageSessionsPerDay(String userId) {
        int totalSessions = getTotalSessionsCount(userId);
        int activeDays = getDistinctActiveDaysCount(userId);
        if (activeDays == 0) return 0.0;
        return (double) totalSessions / activeDays;
    }

    public synchronized int getAverageDurationSecondsPerSession(String userId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT AVG(" + COL_DURATION_SEC + ") FROM " + TABLE_SESSIONS + " WHERE " + COL_USER_ID + " = ?",
                new String[]{userId}
        );
        int avg = 0;
        if (cursor != null) {
            if (cursor.moveToFirst()) avg = (int) Math.round(cursor.getDouble(0));
            cursor.close();
        }
        return avg;
    }

    public synchronized int getTotalDurationSecondsAllTime(String userId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT SUM(" + COL_DURATION_SEC + ") FROM " + TABLE_SESSIONS + " WHERE " + COL_USER_ID + " = ?",
                new String[]{userId}
        );
        int total = 0;
        if (cursor != null) {
            if (cursor.moveToFirst()) total = cursor.getInt(0);
            cursor.close();
        }
        return total;
    }

    /**
     * Coin System Formula:
     * Base: 0.5 coins every 2 minutes (i.e. 0.25 coins per minute).
     * Scaled by Focus Score: Focus / 100.0.
     */
    public static double calculateCoinsEarned(int durationSeconds, int focusScore) {
        return calculateCoinsEarned(durationSeconds, focusScore, 1.0);
    }

    public static double calculateCoinsEarned(int durationSeconds, int focusScore, double multiplier) {
        if (durationSeconds <= 0) return 0.0;
        double baseCoins = (durationSeconds / 120.0) * 0.5;
        double focusMultiplier = Math.max(0.0, Math.min(100.0, focusScore)) / 100.0;
        double earned = baseCoins * focusMultiplier * Math.max(1.0, multiplier);
        return Math.round(earned * 100.0) / 100.0;
    }

    public synchronized double getTotalCoins(String userId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT SUM(" + COL_COINS_EARNED + ") FROM " + TABLE_SESSIONS + " WHERE " + COL_USER_ID + " = ?",
                new String[]{userId}
        );
        double total = 0.0;
        if (cursor != null) {
            if (cursor.moveToFirst()) total = cursor.getDouble(0);
            cursor.close();
        }
        return Math.round(total * 100.0) / 100.0;
    }

    public synchronized double getTodayCoins(String userId) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long startOfDay = cal.getTimeInMillis();

        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT SUM(" + COL_COINS_EARNED + ") FROM " + TABLE_SESSIONS +
                        " WHERE " + COL_USER_ID + " = ? AND " + COL_START_TIME + " >= ?",
                new String[]{userId, String.valueOf(startOfDay)}
        );
        double total = 0.0;
        if (cursor != null) {
            if (cursor.moveToFirst()) total = cursor.getDouble(0);
            cursor.close();
        }
        return Math.round(total * 100.0) / 100.0;
    }

    public static class DailyStudyStat {
        public final String date; // YYYY-MM-DD
        public final int totalMinutes;
        public final int sessionCount;

        public DailyStudyStat(String date, int totalMinutes, int sessionCount) {
            this.date = date;
            this.totalMinutes = totalMinutes;
            this.sessionCount = sessionCount;
        }
    }

    public synchronized Map<String, DailyStudyStat> getDailyStudyStats(String userId, int daysBack) {
        Map<String, DailyStudyStat> map = new HashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT strftime('%Y-%m-%d', datetime(" + COL_START_TIME + " / 1000, 'unixepoch', 'localtime')) AS day, " +
                        "SUM(" + COL_DURATION_SEC + ") AS total_sec, " +
                        "COUNT(*) AS cnt " +
                        "FROM " + TABLE_SESSIONS + " " +
                        "WHERE " + COL_USER_ID + " = ? " +
                        "GROUP BY day",
                new String[]{userId}
        );
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String day = cursor.getString(0);
                int sec = cursor.getInt(1);
                int cnt = cursor.getInt(2);
                if (day != null) {
                    map.put(day, new DailyStudyStat(day, Math.max(1, sec / 60), cnt));
                }
            }
            cursor.close();
        }
        return map;
    }

    public synchronized int getMaxStreak(String userId) {
        Map<String, DailyStudyStat> map = getDailyStudyStats(userId, 365);
        if (map.isEmpty()) return 0;

        List<String> dates = new ArrayList<>(map.keySet());
        java.util.Collections.sort(dates);

        int maxStreak = 0;
        int currentCount = 0;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

        Calendar prevCal = null;
        for (String dateStr : dates) {
            try {
                Date d = sdf.parse(dateStr);
                Calendar curCal = Calendar.getInstance();
                curCal.setTime(d);

                if (prevCal == null) {
                    currentCount = 1;
                } else {
                    long diff = (curCal.getTimeInMillis() - prevCal.getTimeInMillis()) / (24L * 60L * 60L * 1000L);
                    if (diff == 1) {
                        currentCount++;
                    } else if (diff > 1) {
                        currentCount = 1;
                    }
                }
                prevCal = curCal;
                if (currentCount > maxStreak) {
                    maxStreak = currentCount;
                }
            } catch (Exception ignored) {}
        }
        return maxStreak;
    }

    public synchronized int getTotalActiveDays(String userId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(DISTINCT strftime('%Y-%m-%d', datetime(" + COL_START_TIME + " / 1000, 'unixepoch', 'localtime'))) " +
                        "FROM " + TABLE_SESSIONS + " WHERE " + COL_USER_ID + " = ?",
                new String[]{userId}
        );
        int count = 0;
        if (cursor != null) {
            if (cursor.moveToFirst()) count = cursor.getInt(0);
            cursor.close();
        }
        return count;
    }

    public synchronized int getYesterdayTotalSeconds(String userId) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long start = cal.getTimeInMillis();

        cal.add(Calendar.DAY_OF_YEAR, 1);
        long end = cal.getTimeInMillis();

        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT SUM(" + COL_DURATION_SEC + ") FROM " + TABLE_SESSIONS +
                        " WHERE " + COL_USER_ID + " = ? AND " + COL_START_TIME + " >= ? AND " + COL_START_TIME + " < ?",
                new String[]{userId, String.valueOf(start), String.valueOf(end)}
        );
        int total = 0;
        if (cursor != null) {
            if (cursor.moveToFirst()) total = cursor.getInt(0);
            cursor.close();
        }
        return total;
    }

    public synchronized int getYesterdaySessionCount(String userId) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long start = cal.getTimeInMillis();

        cal.add(Calendar.DAY_OF_YEAR, 1);
        long end = cal.getTimeInMillis();

        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_SESSIONS +
                        " WHERE " + COL_USER_ID + " = ? AND " + COL_START_TIME + " >= ? AND " + COL_START_TIME + " < ?",
                new String[]{userId, String.valueOf(start), String.valueOf(end)}
        );
        int count = 0;
        if (cursor != null) {
            if (cursor.moveToFirst()) count = cursor.getInt(0);
            cursor.close();
        }
        return count;
    }

    public synchronized int getYesterdayAverageFocusScore(String userId) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long start = cal.getTimeInMillis();

        cal.add(Calendar.DAY_OF_YEAR, 1);
        long end = cal.getTimeInMillis();

        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT AVG(" + COL_FOCUS_SCORE + ") FROM " + TABLE_SESSIONS +
                        " WHERE " + COL_USER_ID + " = ? AND " + COL_START_TIME + " >= ? AND " + COL_START_TIME + " < ?",
                new String[]{userId, String.valueOf(start), String.valueOf(end)}
        );
        int avg = 0;
        if (cursor != null) {
            if (cursor.moveToFirst()) avg = (int) Math.round(cursor.getDouble(0));
            cursor.close();
        }
        return avg;
    }

    public synchronized double getYesterdayCoins(String userId) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long start = cal.getTimeInMillis();

        cal.add(Calendar.DAY_OF_YEAR, 1);
        long end = cal.getTimeInMillis();

        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT SUM(" + COL_COINS_EARNED + ") FROM " + TABLE_SESSIONS +
                        " WHERE " + COL_USER_ID + " = ? AND " + COL_START_TIME + " >= ? AND " + COL_START_TIME + " < ?",
                new String[]{userId, String.valueOf(start), String.valueOf(end)}
        );
        double coins = 0.0;
        if (cursor != null) {
            if (cursor.moveToFirst()) coins = cursor.getDouble(0);
            cursor.close();
        }
        return Math.round(coins * 100.0) / 100.0;
    }

    public synchronized void seedYesterdayOnlySessions(String userId) {
        SQLiteDatabase db = getWritableDatabase();
        // Clear all previous sessions to strictly satisfy "only add yesterday data"
        db.delete(TABLE_SESSIONS, null, null);

        java.util.Set<String> userSet = new java.util.LinkedHashSet<>();
        if (userId != null && !userId.trim().isEmpty()) {
            userSet.add(userId.trim());
        }
        userSet.add("student_01");
        userSet.add("guest_7370");

        for (String uid : userSet) {
            // S1: 1hr (60m = 3600s), 76% focus
            Calendar c1 = Calendar.getInstance();
            c1.add(Calendar.DAY_OF_YEAR, -1);
            c1.set(Calendar.HOUR_OF_DAY, 9);
            c1.set(Calendar.MINUTE, 30);
            c1.set(Calendar.SECOND, 0);
            long s1Start = c1.getTimeInMillis();
            long s1End = s1Start + 3600 * 1000L;
            insertSession(new SessionRecord("sess_yest_01_" + uid, uid, "Algorithms & Problem Solving",
                    s1Start, s1End, 3600, 76, 2, "Morning", 3, 3, "{}", calculateCoinsEarned(3600, 76)));

            // S2: 20 min (1200s), 100% focus
            Calendar c2 = Calendar.getInstance();
            c2.add(Calendar.DAY_OF_YEAR, -1);
            c2.set(Calendar.HOUR_OF_DAY, 11);
            c2.set(Calendar.MINUTE, 15);
            c2.set(Calendar.SECOND, 0);
            long s2Start = c2.getTimeInMillis();
            long s2End = s2Start + 1200 * 1000L;
            insertSession(new SessionRecord("sess_yest_02_" + uid, uid, "Mathematics & Linear Algebra",
                    s2Start, s2End, 1200, 100, 0, "Morning", 1, 1, "{}", calculateCoinsEarned(1200, 100)));

            // S3: 37 min (2220s), 89% focus
            Calendar c3 = Calendar.getInstance();
            c3.add(Calendar.DAY_OF_YEAR, -1);
            c3.set(Calendar.HOUR_OF_DAY, 14);
            c3.set(Calendar.MINUTE, 30);
            c3.set(Calendar.SECOND, 0);
            long s3Start = c3.getTimeInMillis();
            long s3End = s3Start + 2220 * 1000L;
            insertSession(new SessionRecord("sess_yest_03_" + uid, uid, "Operating Systems & Kernels",
                    s3Start, s3End, 2220, 89, 1, "Afternoon", 2, 2, "{}", calculateCoinsEarned(2220, 89)));

            // S4: 10 min (600s), 60% focus
            Calendar c4 = Calendar.getInstance();
            c4.add(Calendar.DAY_OF_YEAR, -1);
            c4.set(Calendar.HOUR_OF_DAY, 17);
            c4.set(Calendar.MINUTE, 45);
            c4.set(Calendar.SECOND, 0);
            long s4Start = c4.getTimeInMillis();
            long s4End = s4Start + 600 * 1000L;
            insertSession(new SessionRecord("sess_yest_04_" + uid, uid, "System Architecture",
                    s4Start, s4End, 600, 60, 3, "Evening", 4, 2, "{}", calculateCoinsEarned(600, 60)));

            // S5: 30 min (1800s), 79% focus
            Calendar c5 = Calendar.getInstance();
            c5.add(Calendar.DAY_OF_YEAR, -1);
            c5.set(Calendar.HOUR_OF_DAY, 20);
            c5.set(Calendar.MINUTE, 30);
            c5.set(Calendar.SECOND, 0);
            long s5Start = c5.getTimeInMillis();
            long s5End = s5Start + 1800 * 1000L;
            insertSession(new SessionRecord("sess_yest_05_" + uid, uid, "Computer Networks & Protocols",
                    s5Start, s5End, 1800, 79, 2, "Night", 3, 3, "{}", calculateCoinsEarned(1800, 79)));
        }
    }

    private SessionRecord cursorToSessionRecord(Cursor cursor) {
        int durationSec = cursor.getInt(cursor.getColumnIndexOrThrow(COL_DURATION_SEC));
        int focusScore = cursor.getInt(cursor.getColumnIndexOrThrow(COL_FOCUS_SCORE));

        int coinsColIdx = cursor.getColumnIndex(COL_COINS_EARNED);
        double coins = (coinsColIdx != -1) ? cursor.getDouble(coinsColIdx) : 0.0;
        if (coins == 0.0 && durationSec > 0) {
            coins = calculateCoinsEarned(durationSec, focusScore);
        }

        return new SessionRecord(
                cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_USER_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_TOPIC)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_START_TIME)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_END_TIME)),
                durationSec,
                focusScore,
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_TAB_SWITCHES)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_TIME_OF_DAY)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_NOTIFS_RECV)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_NOTIFS_IGNORED)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_EXTERNAL_APPS)),
                coins
        );
    }
}
