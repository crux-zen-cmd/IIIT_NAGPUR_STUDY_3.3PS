package com.example.attentionapp;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Map;

public class StatisticsActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "attention_prefs";
    public static final String KEY_USER_ID = "active_user_id";
    public static final String DEFAULT_USER_ID = "student_01";

    private TextView tvStatUserId, tvTotalStudyToday, tvTodaySessionsCount, tvBestStudyTime, tvEmptyHistory;
    private TextView tvStatTotalCoins, tvStatTodayCoins;
    private TextView tvHeatmapStreakBadge, tvStatCurrentStreak, tvStatMaxStreak, tvStatActiveDays, tvHeatmapDayDetail;
    private StreakHeatmapView viewStreakHeatmap;
    private android.widget.HorizontalScrollView scrollHeatmap;
    private FocusScoreLineChartView chartScoreTimeline;
    private StudyDurationBarChartView chartDurationByDay;
    private FocusByTimeBarChartView chartFocusByTime;
    private RecyclerView rvSessionHistory;
    private SessionHistoryAdapter historyAdapter;

    private String currentUserId = DEFAULT_USER_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_statistics);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentUserId = prefs.getString(KEY_USER_ID, DEFAULT_USER_ID);

        initViews();
        loadUserStatistics();
    }

    private void initViews() {
        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        tvStatUserId = findViewById(R.id.tvStatUserId);
        tvTotalStudyToday = findViewById(R.id.tvTotalStudyToday);
        tvTodaySessionsCount = findViewById(R.id.tvTodaySessionsCount);
        tvStatTotalCoins = findViewById(R.id.tvStatTotalCoins);
        tvStatTodayCoins = findViewById(R.id.tvStatTodayCoins);
        tvBestStudyTime = findViewById(R.id.tvBestStudyTime);
        tvEmptyHistory = findViewById(R.id.tvEmptyHistory);

        tvHeatmapStreakBadge = findViewById(R.id.tvHeatmapStreakBadge);
        tvStatCurrentStreak = findViewById(R.id.tvStatCurrentStreak);
        tvStatMaxStreak = findViewById(R.id.tvStatMaxStreak);
        tvStatActiveDays = findViewById(R.id.tvStatActiveDays);
        tvHeatmapDayDetail = findViewById(R.id.tvHeatmapDayDetail);
        viewStreakHeatmap = findViewById(R.id.viewStreakHeatmap);
        scrollHeatmap = findViewById(R.id.scrollHeatmap);

        chartScoreTimeline = findViewById(R.id.chartScoreTimeline);
        chartDurationByDay = findViewById(R.id.chartDurationByDay);
        chartFocusByTime = findViewById(R.id.chartFocusByTime);
        rvSessionHistory = findViewById(R.id.rvSessionHistory);

        rvSessionHistory.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.btnSwitchUser).setOnClickListener(v -> showSwitchUserDialog());
    }

    private void loadUserStatistics() {
        tvStatUserId.setText("👤 " + currentUserId);

        SessionDatabaseHelper db = SessionDatabaseHelper.getInstance(this);

        // 1. Today's stats
        int todaySec = db.getTodayTotalSeconds(currentUserId);
        int todayCount = db.getTodaySessionCount(currentUserId);

        int min = todaySec / 60;
        int hours = min / 60;
        int remMin = min % 60;
        String formattedTime;
        if (hours > 0) {
            formattedTime = hours + "h " + remMin + "m";
        } else {
            formattedTime = min + "m";
        }
        tvTotalStudyToday.setText(formattedTime);
        tvTodaySessionsCount.setText(todayCount + " session" + (todayCount == 1 ? "" : "s") + " today");

        // 2. Coins Calculation
        double totalCoins = db.getTotalCoins(currentUserId);
        double todayCoins = db.getTodayCoins(currentUserId);
        tvStatTotalCoins.setText(String.format(java.util.Locale.US, "%.2f", totalCoins));
        tvStatTodayCoins.setText(String.format(java.util.Locale.US, "+%.2f today", todayCoins));

        // 3. Best Study Window Calculation
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String chronotype = prefs.getString("chronotype_preference", "DAY");
        String bestWindow = db.getBestStudyTime(currentUserId, chronotype);
        tvBestStudyTime.setText(bestWindow);

        // 4. LeetCode / GitHub Style Streak & Activity Heatmap
        int currentStreak = db.getCurrentStreak(currentUserId);
        int maxStreak = db.getMaxStreak(currentUserId);
        int activeDays = db.getTotalActiveDays(currentUserId);

        tvHeatmapStreakBadge.setText("🔥 " + currentStreak + (currentStreak == 1 ? " Day Streak" : " Days Streak"));
        tvStatCurrentStreak.setText(currentStreak + (currentStreak == 1 ? " Day" : " Days"));
        tvStatMaxStreak.setText(maxStreak + (maxStreak == 1 ? " Day" : " Days"));
        tvStatActiveDays.setText(activeDays + (activeDays == 1 ? " Day" : " Days"));

        Map<String, SessionDatabaseHelper.DailyStudyStat> statsMap = db.getDailyStudyStats(currentUserId, 126);
        viewStreakHeatmap.setStudyStats(statsMap);

        viewStreakHeatmap.setOnDaySelectedListener((dateStr, displayDate, totalMinutes, sessionCount) -> {
            if (totalMinutes > 0) {
                tvHeatmapDayDetail.setText("📅 " + displayDate + ": " + totalMinutes + "m studied · " + sessionCount + (sessionCount == 1 ? " session" : " sessions"));
            } else {
                tvHeatmapDayDetail.setText("📅 " + displayDate + ": No study sessions recorded");
            }
        });

        int yestSec = db.getYesterdayTotalSeconds(currentUserId);
        int yestCount = db.getYesterdaySessionCount(currentUserId);
        int yestMin = yestSec / 60;
        int yestAvgFocus = db.getYesterdayAverageFocusScore(currentUserId);
        if (tvHeatmapDayDetail != null && yestCount > 0) {
            tvHeatmapDayDetail.setText("📅 Yesterday: " + yestMin + "m studied · " + yestCount + " sessions (" + yestAvgFocus + "% avg focus)");
        }

        if (scrollHeatmap != null) {
            scrollHeatmap.post(() -> scrollHeatmap.fullScroll(android.view.View.FOCUS_RIGHT));
        }

        // 5. Score Over Time Line Chart (Image 1)
        List<SessionRecord> timelineRecords = db.getScoreTimelineForUser(currentUserId, 15);
        chartScoreTimeline.setRecords(timelineRecords);

        // 4. Study Duration by Day (Image 2)
        double[] durationsByDay = db.getDurationByDayOfWeek(currentUserId);
        chartDurationByDay.setDurationsMinutes(durationsByDay);

        // 5. Focus by Time of Day (Image 3)
        Map<String, Integer> todScores = db.getAverageScoreByTimeOfDay(currentUserId);
        chartFocusByTime.setScoreMap(todScores);

        // 6. Completed Study Sessions Table (Image 4)
        List<SessionRecord> allSessions = db.getSessionsForUser(currentUserId);
        if (allSessions.isEmpty()) {
            tvEmptyHistory.setVisibility(View.VISIBLE);
            rvSessionHistory.setVisibility(View.GONE);
        } else {
            tvEmptyHistory.setVisibility(View.GONE);
            rvSessionHistory.setVisibility(View.VISIBLE);
            historyAdapter = new SessionHistoryAdapter(allSessions);
            rvSessionHistory.setAdapter(historyAdapter);
        }
    }

    private void showSwitchUserDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Switch User Profile");
        builder.setMessage("Enter the User ID to view and record dedicated study statistics:");

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(currentUserId);
        input.setSelectAllOnFocus(true);
        builder.setView(input);

        builder.setPositiveButton("Switch", (dialog, which) -> {
            String newId = input.getText().toString().trim();
            if (!newId.isEmpty()) {
                currentUserId = newId;
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                prefs.edit().putString(KEY_USER_ID, newId).apply();
                loadUserStatistics();
                Toast.makeText(this, "Active User ID: " + currentUserId, Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }
}
