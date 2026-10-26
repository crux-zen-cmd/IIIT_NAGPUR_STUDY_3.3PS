package com.example.attentionapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

public class FocusByTimeBarChartView extends View {

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelTopPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barSlotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final String[] timeBuckets = {"Morning", "Afternoon", "Evening", "Night", "Late Night"};
    private final Map<String, Integer> scoreMap = new LinkedHashMap<>();

    public FocusByTimeBarChartView(Context context) {
        super(context);
        init();
    }

    public FocusByTimeBarChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint.setColor(Color.parseColor("#1E293B"));
        gridPaint.setStrokeWidth(1.5f);
        gridPaint.setStyle(Paint.Style.STROKE);

        textPaint.setColor(Color.parseColor("#94A3B8"));
        textPaint.setTextSize(22f);

        labelTopPaint.setColor(Color.parseColor("#F8FAFC"));
        labelTopPaint.setTextSize(24f);
        labelTopPaint.setFakeBoldText(true);

        barSlotPaint.setColor(Color.parseColor("#1E293B"));
        barSlotPaint.setStrokeWidth(2f);
        barSlotPaint.setStyle(Paint.Style.STROKE);

        for (String bucket : timeBuckets) {
            scoreMap.put(bucket, 0);
        }
    }

    public void setScoreMap(Map<String, Integer> map) {
        if (map != null) {
            scoreMap.putAll(map);
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
        float paddingTop = 45f;
        float paddingBottom = 60f;

        float chartW = width - paddingLeft - paddingRight;
        float chartH = height - paddingTop - paddingBottom;

        // Draw horizontal grid lines at 0%, 25%, 50%, 75%, 100%
        int[] levels = {0, 25, 50, 75, 100};
        for (int lvl : levels) {
            float y = paddingTop + chartH - (chartH * lvl / 100f);
            canvas.drawLine(paddingLeft, y, width - paddingRight, y, gridPaint);
            canvas.drawText(lvl + "%", 15f, y + 8f, textPaint);
        }

        float colWidth = chartW / 5f;
        float barWidth = colWidth * 0.50f;

        for (int i = 0; i < 5; i++) {
            String bucket = timeBuckets[i];
            int score = scoreMap.containsKey(bucket) ? scoreMap.get(bucket) : 0;

            float centerX = paddingLeft + (colWidth * i) + (colWidth / 2f);
            float left = centerX - (barWidth / 2f);
            float right = centerX + (barWidth / 2f);

            if (score > 0) {
                float barH = chartH * (score / 100f);
                float barTop = paddingTop + chartH - barH;

                // Color based on focus score
                if (score >= 70) {
                    barPaint.setColor(Color.parseColor("#F59E0B")); // Amber / Gold
                } else if (score >= 50) {
                    barPaint.setColor(Color.parseColor("#FB923C")); // Orange
                } else {
                    barPaint.setColor(Color.parseColor("#EF4444")); // Red
                }

                RectF barRect = new RectF(left, barTop, right, paddingTop + chartH);
                canvas.drawRoundRect(barRect, 8f, 8f, barPaint);

                // Top percentage label (e.g. 79%)
                String topTxt = score + "%";
                float tw = labelTopPaint.measureText(topTxt);
                canvas.drawText(topTxt, centerX - (tw / 2f), barTop - 8f, labelTopPaint);
            } else {
                // Subtle slot outline
                canvas.drawLine(left, paddingTop + chartH - 4f, right, paddingTop + chartH - 4f, barSlotPaint);
            }

            // X-Axis Bucket Label
            String label = bucket;
            if (label.equals("Late Night")) label = "Late";
            float ltw = textPaint.measureText(label);
            canvas.drawText(label, centerX - (ltw / 2f), height - 15f, textPaint);
        }
    }
}
