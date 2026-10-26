package com.example.attentionapp;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AttentionEvent {
    private final String timestamp;
    private final String eventType;
    private final String title;
    private final String details;
    private final int scoreChange;

    public AttentionEvent(String eventType, String title, String details, int scoreChange) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        this.timestamp = sdf.format(new Date());
        this.eventType = eventType;
        this.title = title;
        this.details = details;
        this.scoreChange = scoreChange;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTitle() {
        return title;
    }

    public String getDetails() {
        return details;
    }

    public int getScoreChange() {
        return scoreChange;
    }
}
