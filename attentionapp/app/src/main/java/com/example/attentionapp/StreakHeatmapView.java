package com.example.attentionapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * LeetCode / GitHub style Streak & Activity Contribution Heatmap View.
 * Displays weeks as columns and days of the week as 7 rows.
 * Color scales dynamically from subtle empty to deep dark green based on study duration.
 */
public class StreakHeatmapView extends View {

    public interface OnDaySelectedListener {
        void onDaySelected(String dateStr, String displayDate, int totalMinutes, int sessionCount);
    }

    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cellStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint monthTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Color Tiers (LeetCode / GitHub Green Scale on Light Theme)
    private static final int COLOR_EMPTY = Color.parseColor("#E2E8F0");
    private static final int COLOR_EMPTY_STROKE = Color.parseColor("#CBD5E1");
    private static final int COLOR_LEVEL_1 = Color.parseColor("#9BE9A8"); // 1 - 20 min (Light green)
    private static final int COLOR_LEVEL_2 = Color.parseColor("#40C463"); // 21 - 45 min (Medium light green)
    private static final int COLOR_LEVEL_3 = Color.parseColor("#30A14E"); // 46 - 90 min (Dark green)
    private static final int COLOR_LEVEL_4 = Color.parseColor("#216E39"); // > 90 min (Deep green)

    private final RectF cellRect = new RectF();

    private int numWeeks = 18; // ~4.5 months of visible activity
    private float cellSize = 38f;
    private float cellSpacing = 8f;
    private float cellCornerRadius = 6f;
    private float labelWidth = 50f;
    private float headerHeight = 36f;

    private Map<String, SessionDatabaseHelper.DailyStudyStat> studyStatsMap = new HashMap<>();
    private final DayCellInfo[][] grid = new DayCellInfo[7][numWeeks];

    private int selectedRow = -1;
    private int selectedCol = -1;
    private OnDaySelectedListener daySelectedListener;

    public static class DayCellInfo {
        public String dateKey; // YYYY-MM-DD
        public String displayDate; // Oct 26, 2026
        public int totalMinutes;
        public int sessionCount;
        public boolean isFuture;
        public int monthNumber;
        public String monthLabel;
        public RectF bounds = new RectF();
    }

    public StreakHeatmapView(Context context) {
        super(context);
        init();
    }

    public StreakHeatmapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public StreakHeatmapView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        cellPaint.setStyle(Paint.Style.FILL);

        cellStrokePaint.setStyle(Paint.Style.STROKE);
        cellStrokePaint.setStrokeWidth(1.5f);
        cellStrokePaint.setColor(COLOR_EMPTY_STROKE);

        selectedBorderPaint.setStyle(Paint.Style.STROKE);
        selectedBorderPaint.setStrokeWidth(3f);
        selectedBorderPaint.setColor(Color.parseColor("#312E81")); // Dark Indigo highlight

        textPaint.setColor(Color.parseColor("#64748B"));
        textPaint.setTextSize(22f);
        textPaint.setTextAlign(Paint.Align.RIGHT);

        monthTextPaint.setColor(Color.parseColor("#475569"));
        monthTextPaint.setTextSize(22f);
        monthTextPaint.setTextAlign(Paint.Align.LEFT);

        rebuildCalendarGrid();
    }

    public void setOnDaySelectedListener(OnDaySelectedListener listener) {
        this.daySelectedListener = listener;
    }

    public void setStudyStats(Map<String, SessionDatabaseHelper.DailyStudyStat> stats) {
        this.studyStatsMap = (stats != null) ? stats : new HashMap<>();
        rebuildCalendarGrid();
        invalidate();
    }

    private void rebuildCalendarGrid() {
        SimpleDateFormat keyFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat displayFormat = new SimpleDateFormat("MMM d, yyyy", Locale.US);
        SimpleDateFormat monthFormat = new SimpleDateFormat("MMM", Locale.US);

        Calendar todayCal = Calendar.getInstance();
        todayCal.set(Calendar.HOUR_OF_DAY, 23);
        todayCal.set(Calendar.MINUTE, 59);
        todayCal.set(Calendar.SECOND, 59);
        todayCal.set(Calendar.MILLISECOND, 999);
        long todayMillis = todayCal.getTimeInMillis();

        // End grid on current week's Saturday
        Calendar cursor = Calendar.getInstance();
        int dayOfWeek = cursor.get(Calendar.DAY_OF_WEEK); // 1 = Sunday ... 7 = Saturday
        int daysUntilSaturday = Calendar.SATURDAY - dayOfWeek;
        cursor.add(Calendar.DAY_OF_YEAR, daysUntilSaturday);

        // Move back (numWeeks - 1) weeks to the starting Sunday
        cursor.add(Calendar.DAY_OF_YEAR, -(numWeeks * 7 - 1));

        int lastSeenMonth = -1;

        for (int col = 0; col < numWeeks; col++) {
            for (int row = 0; row < 7; row++) {
                Date d = cursor.getTime();
                String key = keyFormat.format(d);
                DayCellInfo info = new DayCellInfo();
                info.dateKey = key;
                info.displayDate = displayFormat.format(d);
                info.isFuture = d.getTime() > todayMillis;

                int m = cursor.get(Calendar.MONTH);
                if (row == 0 && m != lastSeenMonth) {
                    info.monthLabel = monthFormat.format(d);
                    lastSeenMonth = m;
                }
                info.monthNumber = m;

                if (studyStatsMap != null && studyStatsMap.containsKey(key)) {
                    SessionDatabaseHelper.DailyStudyStat stat = studyStatsMap.get(key);
                    info.totalMinutes = stat.totalMinutes;
                    info.sessionCount = stat.sessionCount;
                } else {
                    info.totalMinutes = 0;
                    info.sessionCount = 0;
                }

                grid[row][col] = info;
                cursor.add(Calendar.DAY_OF_YEAR, 1);
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float desiredWidth = labelWidth + (numWeeks * (cellSize + cellSpacing)) + 24f;
        float desiredHeight = headerHeight + (7 * (cellSize + cellSpacing)) + 20f;

        int width = resolveSize((int) desiredWidth, widthMeasureSpec);
        int height = resolveSize((int) desiredHeight, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 1. Draw Day-of-week labels on the left (M, W, F)
        String[] dayLabels = {"", "M", "", "W", "", "F", ""};
        for (int row = 0; row < 7; row++) {
            if (!dayLabels[row].isEmpty()) {
                float y = headerHeight + (row * (cellSize + cellSpacing)) + (cellSize * 0.75f);
                canvas.drawText(dayLabels[row], labelWidth - 10f, y, textPaint);
            }
        }

        // 2. Draw Month headers & Grid cells
        for (int col = 0; col < numWeeks; col++) {
            float x = labelWidth + (col * (cellSize + cellSpacing));

            // Month header label above week
            DayCellInfo headerCell = grid[0][col];
            if (headerCell != null && headerCell.monthLabel != null) {
                canvas.drawText(headerCell.monthLabel, x, headerHeight - 12f, monthTextPaint);
            }

            for (int row = 0; row < 7; row++) {
                float y = headerHeight + (row * (cellSize + cellSpacing));
                DayCellInfo info = grid[row][col];
                if (info == null) continue;

                info.bounds.set(x, y, x + cellSize, y + cellSize);

                if (info.isFuture) {
                    // Future day: subtle dash/empty
                    cellPaint.setColor(COLOR_EMPTY);
                    cellPaint.setAlpha(60);
                    canvas.drawRoundRect(info.bounds, cellCornerRadius, cellCornerRadius, cellPaint);
                    cellPaint.setAlpha(255);
                } else {
                    int cellColor = getLevelColor(info.totalMinutes);
                    cellPaint.setColor(cellColor);
                    canvas.drawRoundRect(info.bounds, cellCornerRadius, cellCornerRadius, cellPaint);

                    if (info.totalMinutes == 0) {
                        canvas.drawRoundRect(info.bounds, cellCornerRadius, cellCornerRadius, cellStrokePaint);
                    }
                }

                // Selected border highlight
                if (row == selectedRow && col == selectedCol) {
                    canvas.drawRoundRect(info.bounds, cellCornerRadius, cellCornerRadius, selectedBorderPaint);
                }
            }
        }
    }

    private int getLevelColor(int minutes) {
        if (minutes <= 0) {
            return COLOR_EMPTY;
        } else if (minutes <= 20) {
            return COLOR_LEVEL_1; // Light green (low time)
        } else if (minutes <= 45) {
            return COLOR_LEVEL_2; // Medium green
        } else if (minutes <= 90) {
            return COLOR_LEVEL_3; // Dark green
        } else {
            return COLOR_LEVEL_4; // Deep green (> 90 min)
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_UP) {
            float x = event.getX();
            float y = event.getY();

            for (int col = 0; col < numWeeks; col++) {
                for (int row = 0; row < 7; row++) {
                    DayCellInfo info = grid[row][col];
                    if (info != null && !info.isFuture && info.bounds.contains(x, y)) {
                        selectedRow = row;
                        selectedCol = col;
                        invalidate();

                        if (daySelectedListener != null) {
                            daySelectedListener.onDaySelected(
                                    info.dateKey,
                                    info.displayDate,
                                    info.totalMinutes,
                                    info.sessionCount
                            );
                        }
                        return true;
                    }
                }
            }
        }
        return super.onTouchEvent(event);
    }
}
