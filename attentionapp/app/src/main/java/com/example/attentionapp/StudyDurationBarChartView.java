package com.example.attentionapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Locale;

public class StudyDurationBarChartView extends View {

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelTopPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final String[] days = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private double[] durationsMinutes = new double[7];

    public StudyDurationBarChartView(Context context) {
        super(context);
        init();
    }

    public StudyDurationBarChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint.setColor(Color.parseColor("#1E293B"));
        gridPaint.setStrokeWidth(1.5f);
        gridPaint.setStyle(Paint.Style.STROKE);

        textPaint.setColor(Color.parseColor("#94A3B8"));
        textPaint.setTextSize(24f);

        labelTopPaint.setColor(Color.parseColor("#F8FAFC"));
        labelTopPaint.setTextSize(24f);
        labelTopPaint.setFakeBoldText(true);

        barBgPaint.setColor(Color.parseColor("#1E293B"));
        barBgPaint.setStyle(Paint.Style.FILL);

        barActivePaint.setColor(Color.parseColor("#A78BFA"));
        barActivePaint.setStyle(Paint.Style.FILL);
    }

    public void setDurationsMinutes(double[] durations) {
        if (durations != null && durations.length == 7) {
            this.durationsMinutes = durations;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        float paddingLeft = 80f;
        float paddingRight = 30f;
        float paddingTop = 55f;
        float paddingBottom = 60f;

        float chartW = width - paddingLeft - paddingRight;
        float chartH = height - paddingTop - paddingBottom;

        // Determine max duration for Y-scale with breathing room for top label
        double maxVal = 60.0; // minimum scale of 60 mins
        for (double d : durationsMinutes) {
            if (d > maxVal) maxVal = d;
        }
        maxVal = Math.ceil((maxVal * 1.2) / 20.0) * 20.0;

        // Draw 5 grid lines
        for (int i = 0; i <= 4; i++) {
            float y = paddingTop + chartH - (chartH * i / 4f);
            canvas.drawLine(paddingLeft, y, width - paddingRight, y, gridPaint);
            int labelVal = (int) Math.round((maxVal * i / 4.0));
            canvas.drawText(labelVal + "m", 15f, y + 8f, textPaint);
        }

        float colWidth = chartW / 7f;
        float barWidth = colWidth * 0.55f;

        for (int i = 0; i < 7; i++) {
            float centerX = paddingLeft + (colWidth * i) + (colWidth / 2f);
            float left = centerX - (barWidth / 2f);
            float right = centerX + (barWidth / 2f);

            // Draw background slot
            RectF bgRect = new RectF(left, paddingTop, right, paddingTop + chartH);
            canvas.drawRoundRect(bgRect, 8f, 8f, barBgPaint);

            // Draw active bar
            double val = durationsMinutes[i];
            if (val > 0) {
                float barH = (float) ((val / maxVal) * chartH);
                float barTop = paddingTop + chartH - barH;
                RectF activeRect = new RectF(left, barTop, right, paddingTop + chartH);
                canvas.drawRoundRect(activeRect, 8f, 8f, barActivePaint);

                // Top label text (e.g. 157m)
                String topTxt = ((val == Math.floor(val)) ? (int) val : String.format(Locale.getDefault(), "%.1f", val)) + "m";
                float tw = labelTopPaint.measureText(topTxt);
                canvas.drawText(topTxt, centerX - (tw / 2f), Math.max(35f, barTop - 10f), labelTopPaint);
            }

            // Draw Day of week label
            String day = days[i];
            float dtw = textPaint.measureText(day);
            canvas.drawText(day, centerX - (dtw / 2f), height - 15f, textPaint);
        }
    }
}
