package com.example.attentionapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class FocusScoreLineChartView extends View {

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scoreTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<SessionRecord> records = new ArrayList<>();

    public FocusScoreLineChartView(Context context) {
        super(context);
        init();
    }

    public FocusScoreLineChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint.setColor(Color.parseColor("#334155"));
        gridPaint.setStrokeWidth(1.5f);
        gridPaint.setStyle(Paint.Style.STROKE);

        textPaint.setColor(Color.parseColor("#94A3B8"));
        textPaint.setTextSize(24f);

        scoreTextPaint.setColor(Color.parseColor("#38BDF8"));
        scoreTextPaint.setTextSize(26f);
        scoreTextPaint.setFakeBoldText(true);

        linePaint.setColor(Color.parseColor("#38BDF8"));
        linePaint.setStrokeWidth(6f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);

        pointOuterPaint.setColor(Color.parseColor("#38BDF8"));
        pointOuterPaint.setStyle(Paint.Style.FILL);

        pointInnerPaint.setColor(Color.parseColor("#0F172A"));
        pointInnerPaint.setStyle(Paint.Style.FILL);
    }

    public void setRecords(List<SessionRecord> records) {
        this.records.clear();
        if (records != null) {
            this.records.addAll(records);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        float paddingLeft = 90f;
        float paddingRight = 40f;
        float paddingTop = 50f;
        float paddingBottom = 75f;

        float chartW = width - paddingLeft - paddingRight;
        float chartH = height - paddingTop - paddingBottom;

        // Draw horizontal grid lines at 0%, 25%, 50%, 75%, 100%
        int[] levels = {0, 25, 50, 75, 100};
        for (int lvl : levels) {
            float y = paddingTop + chartH - (chartH * lvl / 100f);
            canvas.drawLine(paddingLeft, y, width - paddingRight, y, gridPaint);
            canvas.drawText(lvl + "%", 15f, y + 8f, textPaint);
        }

        if (records.isEmpty()) {
            String empty = "No session data available";
            float tw = textPaint.measureText(empty);
            canvas.drawText(empty, paddingLeft + (chartW - tw) / 2f, paddingTop + chartH / 2f, textPaint);
            return;
        }

        int count = records.size();
        float[] ptsX = new float[count];
        float[] ptsY = new float[count];

        for (int i = 0; i < count; i++) {
            ptsX[i] = (count == 1) ? (paddingLeft + chartW / 2f) : (paddingLeft + (chartW * i / (count - 1)));
            int score = Math.max(0, Math.min(100, records.get(i).getFocusScore()));
            ptsY[i] = paddingTop + chartH - (chartH * score / 100f);
        }

        // Draw Gradient Fill Path
        Path fillPath = new Path();
        fillPath.moveTo(ptsX[0], paddingTop + chartH);
        fillPath.lineTo(ptsX[0], ptsY[0]);
        for (int i = 1; i < count; i++) {
            fillPath.lineTo(ptsX[i], ptsY[i]);
        }
        fillPath.lineTo(ptsX[count - 1], paddingTop + chartH);
        fillPath.close();

        fillPaint.setShader(new LinearGradient(
                0, paddingTop, 0, paddingTop + chartH,
                Color.parseColor("#4D38BDF8"), Color.TRANSPARENT,
                Shader.TileMode.CLAMP
        ));
        canvas.drawPath(fillPath, fillPaint);

        // Draw Line
        Path linePath = new Path();
        linePath.moveTo(ptsX[0], ptsY[0]);
        for (int i = 1; i < count; i++) {
            linePath.lineTo(ptsX[i], ptsY[i]);
        }
        canvas.drawPath(linePath, linePaint);

        // Draw Data Points, Score Percentages & X-Axis Timestamps
        for (int i = 0; i < count; i++) {
            canvas.drawCircle(ptsX[i], ptsY[i], 12f, pointOuterPaint);
            canvas.drawCircle(ptsX[i], ptsY[i], 6f, pointInnerPaint);

            // Draw score percentage label above each point
            String scoreStr = records.get(i).getFocusScore() + "%";
            float stw = scoreTextPaint.measureText(scoreStr);
            float scoreY = (ptsY[i] - 18f < paddingTop + 10f) ? (ptsY[i] + 32f) : (ptsY[i] - 18f);
            canvas.drawText(scoreStr, ptsX[i] - stw / 2f, scoreY, scoreTextPaint);

            // Draw timestamp / session label below X-axis
            String ts;
            if (count <= 6) {
                ts = "S" + (i + 1) + " " + records.get(i).getFormattedShortTime();
            } else if (i == 0 || i == count - 1 || i == count / 2) {
                ts = records.get(i).getFormattedChartTimestamp();
            } else {
                continue;
            }
            float tw = textPaint.measureText(ts);
            float tx = Math.max(paddingLeft, Math.min(width - paddingRight - tw, ptsX[i] - tw / 2f));
            canvas.drawText(ts, tx, height - 15f, textPaint);
        }
    }
}
