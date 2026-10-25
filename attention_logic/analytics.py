"""
Productivity Analytics & Focus Score Engine (Phase 6).
Analyzes completed study sessions, calculates explainable Focus Scores,
groups by Time-of-Day, and determines the user's optimal study periods.
"""
from datetime import datetime, timezone, timedelta
from typing import Dict, Any, List, Optional
from db import database as db


def parse_session_datetime(dt_str: str) -> datetime:
    """Parse ISO formatted timestamp safely with timezone awareness."""
    try:
        if dt_str.endswith("Z"):
            dt_str = dt_str[:-1] + "+00:00"
        return datetime.fromisoformat(dt_str)
    except Exception:
        return datetime.now(timezone.utc)


def get_time_of_day_period(start_time_iso: str) -> Dict[str, str]:
    """
    Classify session into Time of Day period based on local hour:
    - Morning: 6 AM – 12 PM (06:00 – 11:59)
    - Afternoon: 12 PM – 5 PM (12:00 – 16:59)
    - Evening: 5 PM – 9 PM (17:00 – 20:59)
    - Night: 9 PM – 12 AM (21:00 – 23:59)
    - Late Night: 12 AM – 6 AM (00:00 – 05:59)
    """
    dt = parse_session_datetime(start_time_iso)
    # Using local hour
    hour = dt.astimezone().hour if dt.tzinfo else dt.hour

    if 6 <= hour < 12:
        return {
            "period": "Morning",
            "window": "6 AM – 12 PM",
            "label": "Morning (6 AM – 12 PM)",
        }
    elif 12 <= hour < 17:
        return {
            "period": "Afternoon",
            "window": "12 PM – 5 PM",
            "label": "Afternoon (12 PM – 5 PM)",
        }
    elif 17 <= hour < 21:
        return {
            "period": "Evening",
            "window": "5 PM – 9 PM",
            "label": "Evening (5 PM – 9 PM)",
        }
    elif 21 <= hour < 24:
        return {
            "period": "Night",
            "window": "9 PM – 12 AM",
            "label": "Night (9 PM – 12 AM)",
        }
    else:
        return {
            "period": "Late Night",
            "window": "12 AM – 6 AM",
            "label": "Late Night (12 AM – 6 AM)",
        }


def calculate_session_scores(session: Dict[str, Any]) -> Dict[str, Any]:
    """
    Transparent Focus Scoring Algorithm (Phase 6):
    
    Metrics:
    - totalDuration (T): Duration in seconds
    - distractionDuration (D): totalDistractionDuration + totalFocusLostDuration
    - drowsinessDuration (S): totalDrowsinessDuration
    - focusedDuration (F): max(0, T - D - S)
    - tabSwitchCount: Context switch count
    
    Formula:
      Focus Score = 100 - distraction_penalty - drowsiness_penalty - tab_switch_penalty
      where:
        P_d = (D / T) * 100 (distraction percentage)
        P_s = (S / T) * 100 (drowsiness percentage)
        distraction_penalty = P_d * 0.60 (up to 60 pt penalty)
        drowsiness_penalty  = P_s * 0.80 (up to 80 pt penalty)
        tab_switch_penalty  = min(15.0, tabSwitchCount * 1.5) (up to 15 pt penalty)
    """
    raw_dur = float(session.get("duration") or 0.0)
    total_duration = max(0.0, raw_dur)

    tab_dur = float(session.get("totalDistractionDuration") or session.get("total_distraction_duration") or 0.0)
    cv_lost_dur = float(session.get("totalFocusLostDuration") or session.get("total_focus_lost_duration") or 0.0)
    drowsy_dur = float(session.get("totalDrowsinessDuration") or session.get("total_drowsiness_duration") or 0.0)
    tab_switches = int(session.get("tabSwitchCount") or session.get("tab_switch_count") or 0)
    drowsy_events = int(session.get("drowsinessEventCount") or session.get("drowsiness_event_count") or 0)

    # Distraction is combined tab leave + computer vision head/gaze away
    distraction_duration = round(min(total_duration, tab_dur + cv_lost_dur), 2)
    drowsiness_duration = round(min(total_duration, drowsy_dur), 2)

    # Focused time is remainder
    focused_duration = round(max(0.0, total_duration - distraction_duration - drowsiness_duration), 2)

    # Calculate proportions
    if total_duration > 0:
        p_distract = min(100.0, (distraction_duration / total_duration) * 100.0)
        p_drowsy = min(100.0, (drowsiness_duration / total_duration) * 100.0)
        p_focused = max(0.0, 100.0 - p_distract - p_drowsy)
    else:
        p_distract = 0.0
        p_drowsy = 0.0
        p_focused = 100.0

    # Penalties
    distraction_penalty = p_distract * 0.60
    drowsiness_penalty = p_drowsy * 0.80
    tab_switch_penalty = min(15.0, tab_switches * 1.5)

    # Focus Score: 0 - 100
    focus_score = round(max(0.0, min(100.0, 100.0 - distraction_penalty - drowsiness_penalty - tab_switch_penalty)), 1)
    distraction_score = round(min(100.0, p_distract + (tab_switches * 1.5)), 1)
    drowsiness_score = round(min(100.0, p_drowsy + (drowsy_events * 3.0)), 1)

    time_info = get_time_of_day_period(session.get("start_time", ""))

    return {
        "totalDuration": total_duration,
        "total_duration": total_duration,
        "focusedDuration": focused_duration,
        "focused_duration": focused_duration,
        "distractionDuration": distraction_duration,
        "distraction_duration": distraction_duration,
        "drowsinessDuration": drowsiness_duration,
        "drowsiness_duration": drowsiness_duration,
        "tabSwitchCount": tab_switches,
        "tab_switch_count": tab_switches,
        "focusScore": focus_score,
        "focus_score": focus_score,
        "distractionScore": distraction_score,
        "distraction_score": distraction_score,
        "drowsinessScore": drowsiness_score,
        "drowsiness_score": drowsiness_score,
        "focusedPercentage": round(p_focused, 1),
        "distractionPercentage": round(p_distract, 1),
        "drowsinessPercentage": round(p_drowsy, 1),
        "distractionPenalty": round(distraction_penalty, 2),
        "drowsinessPenalty": round(drowsiness_penalty, 2),
        "tabSwitchPenalty": round(tab_switch_penalty, 2),
        "timeOfDay": time_info["period"],
        "timeOfDayWindow": time_info["window"],
        "timeOfDayLabel": time_info["label"],
    }


def get_user_analytics_summary(user_id: Optional[str] = None) -> Dict[str, Any]:
    """Calculate aggregate analytics summary, Today's metrics, and Best Study Period."""
    all_sessions = db.list_sessions(user_id=user_id, limit=200)
    completed_sessions = [s for s in all_sessions if s.get("status") == "COMPLETED"]

    # Enrich all completed sessions with Phase 6 scores
    scored_sessions = []
    for s in completed_sessions:
        scored = dict(s)
        scored.update(calculate_session_scores(s))
        scored_sessions.append(scored)

    now = datetime.now(timezone.utc)
    today_date = now.astimezone().date()

    # 1. Today's Metrics
    today_sessions = [
        s for s in scored_sessions
        if parse_session_datetime(s.get("start_time", "")).astimezone().date() == today_date
    ]

    today_total_dur = sum(s["totalDuration"] for s in today_sessions)
    today_focused_dur = sum(s["focusedDuration"] for s in today_sessions)
    today_distract_dur = sum(s["distractionDuration"] for s in today_sessions)
    today_drowsy_dur = sum(s["drowsinessDuration"] for s in today_sessions)
    today_tab_switches = sum(s["tabSwitchCount"] for s in today_sessions)
    today_focus_score = (
        round(sum(s["focusScore"] for s in today_sessions) / len(today_sessions), 1)
        if today_sessions else 100.0
    )

    # 2. Time of Day Analysis
    period_definitions = [
        ("Morning", "Morning (6 AM – 12 PM)", "6 AM – 12 PM"),
        ("Afternoon", "Afternoon (12 PM – 5 PM)", "12 PM – 5 PM"),
        ("Evening", "Evening (5 PM – 9 PM)", "5 PM – 9 PM"),
        ("Night", "Night (9 PM – 12 AM)", "9 PM – 12 AM"),
        ("Late Night", "Late Night (12 AM – 6 AM)", "12 AM – 6 AM"),
    ]

    periods_data = []
    for p_id, p_label, p_win in period_definitions:
        matching = [s for s in scored_sessions if s.get("timeOfDay") == p_id]
        if matching:
            avg_fs = round(sum(s["focusScore"] for s in matching) / len(matching), 1)
            tot_dur = round(sum(s["totalDuration"] for s in matching), 1)
            avg_dist = round(sum(s["distractionScore"] for s in matching) / len(matching), 1)
            avg_drow = round(sum(s["drowsinessScore"] for s in matching) / len(matching), 1)
        else:
            avg_fs = 0.0
            tot_dur = 0.0
            avg_dist = 0.0
            avg_drow = 0.0

        periods_data.append({
            "period": p_id,
            "label": p_label,
            "window": p_win,
            "sessionCount": len(matching),
            "totalDuration": tot_dur,
            "avgFocusScore": avg_fs,
            "avgDistractionScore": avg_dist,
            "avgDrowsinessScore": avg_drow,
        })

    # 3. Best Study Time Recommendation
    # Rule: Require at least 2 sessions to recommend
    active_periods = [p for p in periods_data if p["sessionCount"] >= 1]
    total_completed = len(scored_sessions)

    if total_completed < 2 or not active_periods:
        best_study_time = {
            "hasRecommendation": False,
            "status": "INSUFFICIENT_DATA",
            "message": "Complete at least 2 study sessions across different times of day to unlock your peak productivity recommendation.",
            "bestPeriod": None,
            "bestWindow": None,
            "avgFocusScore": 0.0,
        }
    else:
        # Choose period with highest avgFocusScore (weighted by session count if ties)
        sorted_periods = sorted(
            active_periods,
            key=lambda p: (p["avgFocusScore"], p["sessionCount"], p["totalDuration"]),
            reverse=True,
        )
        best = sorted_periods[0]
        best_study_time = {
            "hasRecommendation": True,
            "status": "OPTIMAL",
            "bestPeriod": best["period"],
            "bestWindow": best["window"],
            "avgFocusScore": best["avgFocusScore"],
            "message": f"Your strongest study period is {best['label']} with an average Focus Score of {best['avgFocusScore']}%.",
        }

    # 4. Overall Totals
    overall_dur = round(sum(s["totalDuration"] for s in scored_sessions), 1)
    overall_focused = round(sum(s["focusedDuration"] for s in scored_sessions), 1)
    overall_avg_score = (
        round(sum(s["focusScore"] for s in scored_sessions) / len(scored_sessions), 1)
        if scored_sessions else 100.0
    )

    return {
        "ok": True,
        "userId": user_id,
        "today": {
            "totalStudyTime": round(today_total_dur, 1),
            "focusedTime": round(today_focused_dur, 1),
            "distractionTime": round(today_distract_dur, 1),
            "drowsinessDuration": round(today_drowsy_dur, 1),
            "tabSwitches": today_tab_switches,
            "focusScore": today_focus_score,
            "sessionCount": len(today_sessions),
            "totalCompletedSessions": len(today_sessions),
        },
        "bestStudyTime": best_study_time,
        "periods": periods_data,
        "timeOfDayPeriods": periods_data,
        "overall": {
            "totalSessions": total_completed,
            "totalDuration": overall_dur,
            "focusedDuration": overall_focused,
            "avgFocusScore": overall_avg_score,
        },
    }


def get_charts_data(user_id: Optional[str] = None) -> Dict[str, Any]:
    """Prepare data series for the 5 dashboard visualizations."""
    all_sessions = db.list_sessions(user_id=user_id, limit=50)
    completed_sessions = [s for s in all_sessions if s.get("status") == "COMPLETED"]

    scored_sessions = []
    for s in completed_sessions:
        scored = dict(s)
        scored.update(calculate_session_scores(s))
        scored_sessions.append(scored)

    # Sort chronological for charts
    scored_sessions_chrono = sorted(scored_sessions, key=lambda s: s.get("start_time", ""))

    # ── 1. Focus Score Over Time ──────────────────────────────
    # Last 15 sessions chronological
    focus_trend = []
    for s in scored_sessions_chrono[-15:]:
        dt = parse_session_datetime(s.get("start_time", ""))
        time_lbl = dt.astimezone().strftime("%b %d, %H:%M")
        focus_trend.append({
            "sessionId": s["session_id"],
            "date": time_lbl,
            "courseName": s.get("course_name", "Study"),
            "focusScore": s["focusScore"],
            "distractionScore": s["distractionScore"],
            "drowsinessScore": s["drowsinessScore"],
        })

    # ── 2. Study Duration by Day (Last 7 Days) ────────────────
    now = datetime.now(timezone.utc).astimezone()
    daily_durations = []
    for i in range(6, -1, -1):
        day_date = (now - timedelta(days=i)).date()
        day_lbl = day_date.strftime("%a (%b %d)")
        # Sum minutes for this day
        matching = [
            s for s in scored_sessions
            if parse_session_datetime(s.get("start_time", "")).astimezone().date() == day_date
        ]
        total_mins = round(sum(s["totalDuration"] for s in matching) / 60.0, 1)
        focused_mins = round(sum(s["focusedDuration"] for s in matching) / 60.0, 1)
        daily_durations.append({
            "day": day_lbl,
            "totalMinutes": total_mins,
            "focusedMinutes": focused_mins,
            "sessionCount": len(matching),
        })

    # ── 3. Focus by Time of Day ───────────────────────────────
    summary = get_user_analytics_summary(user_id=user_id)
    time_of_day_series = [
        {
            "period": p["period"],
            "label": p["label"],
            "avgFocusScore": p["avgFocusScore"],
            "sessionCount": p["sessionCount"],
            "totalMinutes": round(p["totalDuration"] / 60.0, 1),
        }
        for p in summary.get("periods", [])
    ]

    # ── 4. Distraction vs Focus Breakdown ─────────────────────
    # Aggregated breakdown
    total_focused_sec = sum(s["focusedDuration"] for s in scored_sessions)
    total_distract_sec = sum(s["distractionDuration"] for s in scored_sessions)
    total_drowsy_sec = sum(s["drowsinessDuration"] for s in scored_sessions)
    all_time = total_focused_sec + total_distract_sec + total_drowsy_sec

    if all_time > 0:
        breakdown = {
            "focusedPct": round((total_focused_sec / all_time) * 100.0, 1),
            "distractedPct": round((total_distract_sec / all_time) * 100.0, 1),
            "drowsyPct": round((total_drowsy_sec / all_time) * 100.0, 1),
            "focusedSeconds": round(total_focused_sec, 1),
            "distractedSeconds": round(total_distract_sec, 1),
            "drowsySeconds": round(total_drowsy_sec, 1),
        }
    else:
        breakdown = {
            "focusedPct": 100.0,
            "distractedPct": 0.0,
            "drowsyPct": 0.0,
            "focusedSeconds": 0.0,
            "distractedSeconds": 0.0,
            "drowsySeconds": 0.0,
        }

    # ── 5. Drowsiness Trend ───────────────────────────────────
    drowsiness_trend = []
    for s in scored_sessions_chrono[-15:]:
        dt = parse_session_datetime(s.get("start_time", ""))
        drowsiness_trend.append({
            "sessionId": s["session_id"],
            "date": dt.astimezone().strftime("%b %d, %H:%M"),
            "drowsinessDuration": s["drowsinessDuration"],
            "drowsinessCount": s.get("drowsinessEventCount", 0),
            "drowsinessScore": s["drowsinessScore"],
        })

    return {
        "ok": True,
        "userId": user_id,
        "charts": {
            "focusScoreOverTime": focus_trend,
            "studyDurationByDay": daily_durations,
            "focusByTimeOfDay": time_of_day_series,
            "distractionVsFocus": breakdown,
            "drowsinessTrend": drowsiness_trend,
        },
    }
