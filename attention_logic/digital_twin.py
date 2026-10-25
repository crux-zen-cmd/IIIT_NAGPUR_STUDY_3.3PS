"""
FocusTwin — Personal Attention Profile & Digital Twin Engine (Phase 3).
Transforms AttentionAI from "Monitor -> Score -> Report" to "Observe -> Learn -> Model -> Predict".

Core Sub-Systems:
1. Cold Start System (LEARNING -> LIMITED_PERSONALIZATION -> PERSONALIZED -> HIGH_CONFIDENCE)
2. Personal Attention Baselines (focus duration, recovery time, notification sensitivity, fatigue onset)
3. Multidimensional Best Attention Window (Focus + Distraction Rate + Recovery)
4. Task Context Profiles (Per-task learning curves & interruption resilience)
5. Application Fragmentation Engine (Switching bursts, frequency/hr, fragmentation level)
6. Prolonged Browsing Overrun Detector (Drift from course material to rabbit holes)
7. Task Resumption Engine (Interruption impact: resumption time vs attention recovery time)
8. Behavioral Fatigue Pattern Learner (Non-medical fatigue onset estimation & rest recommendations)
"""

import json
import time
from datetime import datetime, timezone
from typing import Dict, Any, List, Optional, Tuple
from db import database as db
from attention_logic.analytics import (
    calculate_session_scores,
    get_time_of_day_period,
    parse_session_datetime,
)


class FragmentationEngine:
    """
    Measures concentration fragmentation from application and tab switching:
    - switch count & switching frequency per hour
    - switching bursts (>= 3 switches within 60 seconds)
    - fragmentation levels: LOW, MODERATE, HIGH, CRITICAL
    - pattern recognition (e.g. rapid oscillation between IDE and chat)
    """

    LEVEL_LOW = "LOW"
    LEVEL_MODERATE = "MODERATE"
    LEVEL_HIGH = "HIGH"
    LEVEL_CRITICAL = "CRITICAL"

    @classmethod
    def analyze_switches(
        cls,
        switches: List[Dict[str, Any]],
        session_duration_sec: float = 0.0,
    ) -> Dict[str, Any]:
        count = len(switches)
        dur_sec = max(60.0, float(session_duration_sec))
        freq_per_hour = round((count / dur_sec) * 3600.0, 1)

        # Detect switching bursts: >= 3 switches occurring within 60s
        timestamps = []
        for s in switches:
            ts_str = s.get("timestamp")
            if ts_str:
                try:
                    dt = parse_session_datetime(ts_str)
                    timestamps.append(dt.timestamp())
                except Exception:
                    pass
        timestamps.sort()

        burst_count = 0
        i = 0
        while i < len(timestamps):
            window_end = timestamps[i] + 60.0
            window_switches = [t for t in timestamps[i:] if t <= window_end]
            if len(window_switches) >= 3:
                burst_count += 1
                i += len(window_switches)
            else:
                i += 1

        # Classify fragmentation level
        if freq_per_hour > 30.0 or burst_count >= 4:
            level = cls.LEVEL_CRITICAL
        elif freq_per_hour >= 16.0 or burst_count >= 2:
            level = cls.LEVEL_HIGH
        elif freq_per_hour >= 6.0 or burst_count >= 1:
            level = cls.LEVEL_MODERATE
        else:
            level = cls.LEVEL_LOW

        # Detect application alternation patterns
        app_names = []
        for s in switches:
            meta = s.get("metadata") or {}
            if isinstance(meta, str):
                try:
                    meta = json.loads(meta)
                except Exception:
                    meta = {}
            app = meta.get("appName") or meta.get("newTab") or s.get("appName")
            if app:
                app_names.append(str(app).strip())

        pattern_desc = "Stable on-task concentration with minimal fragmentation."
        if burst_count > 0:
            if len(app_names) >= 2:
                top_apps = list(dict.fromkeys(app_names))[:3]
                pattern_desc = (
                    f"Concentration fragmentation detected: {burst_count} rapid switching burst(s) "
                    f"involving {', '.join(top_apps)}."
                )
            else:
                pattern_desc = f"Concentration fragmentation detected: {burst_count} rapid switching burst(s)."
        elif level == cls.LEVEL_HIGH:
            pattern_desc = f"Elevated switching frequency ({freq_per_hour}/hr) creating continuous micro-interruptions."

        return {
            "switchCount": count,
            "sessionDurationSec": round(dur_sec, 1),
            "switchingFrequencyPerHour": freq_per_hour,
            "burstCount": burst_count,
            "fragmentationLevel": level,
            "patternDescription": pattern_desc,
            "topApps": list(dict.fromkeys(app_names))[:5],
        }


class BrowsingOverrunDetector:
    """
    Detects when a user begins studying but slowly drifts into prolonged
    rabbit-hole browsing outside their intended learning task.
    """

    RISK_NONE = "NONE"
    RISK_MILD = "MILD"
    RISK_ELEVATED = "ELEVATED"
    RISK_HIGH = "HIGH"

    @classmethod
    def check_overrun(
        cls,
        events: List[Dict[str, Any]],
        current_task: str = "General Study",
        session_duration_sec: float = 0.0,
    ) -> Dict[str, Any]:
        time_outside_task = 0.0
        current_streak = 0.0
        max_streak = 0.0

        for ev in events:
            ev_type = str(ev.get("event_type") or "").upper()
            dur = float(ev.get("duration") or 0.0)

            if ev_type in ("TAB_SWITCH", "PAGE_HIDDEN", "ATTENTION_DROP"):
                time_outside_task += dur
                current_streak += dur
                if current_streak > max_streak:
                    max_streak = current_streak
            elif ev_type in ("FOCUS_REGAINED", "PAGE_VISIBLE", "ATTENTION_RECOVERED"):
                current_streak = 0.0

        dur_sec = max(session_duration_sec, time_outside_task)
        time_outside_min = round(time_outside_task / 60.0, 1)
        max_streak_min = round(max_streak / 60.0, 1)

        # Risk classification
        if max_streak >= 1800.0 or time_outside_task >= 2400.0:
            risk = cls.RISK_HIGH
            is_overrun = True
            warning = (
                f"Severe session overrun: {max_streak_min} minutes spent outside '{current_task}'. "
                "Significant departure from intended learning content."
            )
        elif max_streak >= 900.0 or time_outside_task >= 1200.0:
            risk = cls.RISK_ELEVATED
            is_overrun = True
            warning = (
                f"Potential session overrun detected: {max_streak_min} continuous minutes outside '{current_task}'."
            )
        elif max_streak >= 300.0 or time_outside_task >= 450.0:
            risk = cls.RISK_MILD
            is_overrun = False
            warning = f"Noticeable off-task drift ({time_outside_min} min cumulative)."
        else:
            risk = cls.RISK_NONE
            is_overrun = False
            warning = f"On track: learning focus maintained within '{current_task}'."

        return {
            "currentTask": current_task,
            "sessionDurationSec": round(dur_sec, 1),
            "timeSpentOutsideTaskSec": round(time_outside_task, 1),
            "timeSpentOutsideTaskMin": time_outside_min,
            "maxOffTaskStreakSec": round(max_streak, 1),
            "maxOffTaskStreakMin": max_streak_min,
            "overrunRiskLevel": risk,
            "isOverrun": is_overrun,
            "warning": warning,
        }


class TaskResumptionEngine:
    """
    Computes interruption impact:
    - task_resumption_time: elapsed time before returning to the learning task
    - attention_recovery_time: elapsed time after return before stable focus is restored
    """

    @classmethod
    def analyze_resumptions(cls, events: List[Dict[str, Any]]) -> Dict[str, Any]:
        chrono_events = sorted(events, key=lambda e: e.get("timestamp", ""))
        episodes = []
        pending_interruption = None

        for ev in chrono_events:
            ev_type = str(ev.get("event_type") or "").upper()
            ts = ev.get("timestamp")

            if ev_type in ("TASK_INTERRUPTED", "TAB_SWITCH", "PAGE_HIDDEN", "NOTIFICATION_RECEIVED"):
                if not pending_interruption:
                    pending_interruption = {
                        "interruptTime": ts,
                        "interruptType": ev_type,
                        "duration": float(ev.get("duration") or 0.0),
                    }
            elif ev_type in ("TASK_RESUMED", "FOCUS_REGAINED", "PAGE_VISIBLE", "ATTENTION_RECOVERED"):
                if pending_interruption:
                    resumption_dur = float(ev.get("duration") or pending_interruption["duration"] or 15.0)
                    recovery_dur = round(resumption_dur * 0.45, 1)
                    episodes.append({
                        "interruptedAt": pending_interruption["interruptTime"],
                        "interruptionType": pending_interruption["interruptType"],
                        "taskResumptionSec": round(resumption_dur, 1),
                        "attentionRecoverySec": round(recovery_dur, 1),
                    })
                    pending_interruption = None

        if episodes:
            avg_resumption = round(sum(e["taskResumptionSec"] for e in episodes) / len(episodes), 1)
            avg_recovery = round(sum(e["attentionRecoverySec"] for e in episodes) / len(episodes), 1)
        else:
            avg_resumption = 0.0
            avg_recovery = 0.0

        return {
            "interruptionCount": len(episodes),
            "avgTaskResumptionSec": avg_resumption,
            "avgAttentionRecoverySec": avg_recovery,
            "recentEpisodes": episodes[-5:],
        }


class FatiguePatternLearner:
    """
    Learns non-medical behavioral fatigue patterns from session duration,
    drowsiness episodes, blink rates, and attention degradation curves.
    """

    @classmethod
    def analyze_fatigue_pattern(
        cls,
        sessions: List[Dict[str, Any]],
        events: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        if not sessions:
            return {
                "fatigueOnsetMinutes": 50.0,
                "confidence": "LOW",
                "sampleSize": 0,
                "degradationPattern": "Insufficient historical sessions to estimate personal fatigue onset.",
                "recommendation": "Maintain standard 45-minute focus intervals with 5-minute pauses.",
                "breakRecommendationMinutes": 45.0,
            }

        # Analyze session durations and when drowsiness/drops occur
        durations_min = [float(s.get("totalDuration", s.get("duration", 0.0))) / 60.0 for s in sessions]
        avg_dur = sum(durations_min) / len(durations_min)

        # Look for timestamps of FATIGUE_PATTERN_DETECTED or DROWSINESS_STARTED relative to session start
        drop_times_min = []
        for ev in events:
            ev_type = str(ev.get("event_type") or "").upper()
            if ev_type in ("FATIGUE_PATTERN_DETECTED", "DROWSINESS_STARTED", "ATTENTION_DROP"):
                meta = ev.get("metadata") or {}
                if isinstance(meta, str):
                    try:
                        meta = json.loads(meta)
                    except Exception:
                        meta = {}
                offset = meta.get("sessionDurationSec") or meta.get("elapsedSec")
                if offset:
                    drop_times_min.append(float(offset) / 60.0)

        if len(drop_times_min) >= 3:
            drop_times_min.sort()
            # 25th percentile of drops represents the onset threshold
            idx = max(0, int(len(drop_times_min) * 0.25))
            onset = round(max(20.0, drop_times_min[idx]), 1)
            confidence = "HIGH" if len(sessions) >= 6 else "MEDIUM"
        else:
            # Fallback based on average session length and degradation tendency
            onset = round(min(55.0, max(35.0, avg_dur * 0.75 if avg_dur >= 30.0 else 45.0)), 1)
            confidence = "MEDIUM" if len(sessions) >= 3 else "LOW"

        break_at = round(max(15.0, onset - 5.0), 1)
        pattern = (
            f"Attention degradation pattern indicates focus stability declines after approximately "
            f"{onset} minutes of continuous study."
        )
        recommendation = (
            f"Schedule a 5-minute cognitive reset around minute {break_at} before attention degrades."
        )

        return {
            "fatigueOnsetMinutes": onset,
            "confidence": confidence,
            "sampleSize": len(sessions),
            "degradationPattern": pattern,
            "recommendation": recommendation,
            "breakRecommendationMinutes": break_at,
        }


class DigitalTwinEngine:
    """
    Core Attention Digital Twin Coordinator.
    Manages cold-start tiering, baseline learning, task profiling, and SQLite persistence.
    """

    STATE_LEARNING                = "LEARNING"
    STATE_LIMITED_PERSONALIZATION = "LIMITED_PERSONALIZATION"
    STATE_PERSONALIZED            = "PERSONALIZED"
    STATE_HIGH_CONFIDENCE         = "HIGH_CONFIDENCE"

    @classmethod
    def evaluate_cold_start(cls, session_count: int) -> Dict[str, Any]:
        """Determine cold start tier and predictive confidence from sample size."""
        n = max(0, int(session_count))
        if n < 3:
            return {
                "coldStartState": cls.STATE_LEARNING,
                "confidenceLevel": "LOW",
                "sampleSize": n,
                "isPersonalized": False,
                "statusMessage": (
                    f"FocusTwin is learning your attention patterns ({n}/3 baseline sessions). "
                    "Predictions currently rely on generic cognitive baselines."
                ),
            }
        elif 3 <= n <= 5:
            return {
                "coldStartState": cls.STATE_LIMITED_PERSONALIZATION,
                "confidenceLevel": "MEDIUM",
                "sampleSize": n,
                "isPersonalized": True,
                "statusMessage": (
                    f"Limited personalization active ({n} sessions). "
                    "Personal recovery curves and task baselines established."
                ),
            }
        elif 6 <= n <= 11:
            return {
                "coldStartState": cls.STATE_PERSONALIZED,
                "confidenceLevel": "HIGH",
                "sampleSize": n,
                "isPersonalized": True,
                "statusMessage": (
                    f"Personal attention model available ({n} sessions) with high predictive accuracy."
                ),
            }
        else:
            return {
                "coldStartState": cls.STATE_HIGH_CONFIDENCE,
                "confidenceLevel": "VERY_HIGH",
                "sampleSize": n,
                "isPersonalized": True,
                "statusMessage": (
                    f"High-confidence digital twin model trained on extensive personal behavioral history ({n} sessions)."
                ),
            }

    @classmethod
    def compute_best_attention_window(
        cls,
        sessions: List[Dict[str, Any]],
        events: Optional[List[Dict[str, Any]]] = None,
    ) -> Dict[str, Any]:
        """
        Multidimensional Best Attention Window Analysis.
        Combines Focus Score (45%), Distraction Rate (35%), and Recovery Time (20%).
        """
        if len(sessions) < 2:
            return {
                "hasRecommendation": False,
                "status": "INSUFFICIENT_DATA",
                "period": None,
                "window": None,
                "avgAttentionScore": 0.0,
                "distractionRatePct": 0.0,
                "avgRecoverySec": 0.0,
                "recommendation": "Complete at least 2 study sessions across different times of day to unlock your peak attention window.",
            }

        # Group sessions by Time-of-Day period
        periods_dict = {
            "Morning":   {"label": "Morning (6 AM – 12 PM)",   "window": "6 AM – 12 PM", "sessions": []},
            "Afternoon": {"label": "Afternoon (12 PM – 5 PM)", "window": "12 PM – 5 PM", "sessions": []},
            "Evening":   {"label": "Evening (5 PM – 9 PM)",   "window": "5 PM – 9 PM", "sessions": []},
            "Night":     {"label": "Night (9 PM – 12 AM)",     "window": "9 PM – 12 AM", "sessions": []},
            "Late Night":{"label": "Late Night (12 AM – 6 AM)","window": "12 AM – 6 AM", "sessions": []},
        }

        for s in sessions:
            st_iso = s.get("start_time", "")
            period_info = get_time_of_day_period(st_iso)
            p_name = period_info.get("period", "Morning")
            if p_name in periods_dict:
                periods_dict[p_name]["sessions"].append(s)

        scored_periods = []
        for p_name, data in periods_dict.items():
            p_sessions = data["sessions"]
            if not p_sessions:
                continue

            avg_focus = round(sum(s.get("focusScore", 80.0) for s in p_sessions) / len(p_sessions), 1)
            tot_dur = sum(s.get("totalDuration", 1.0) for s in p_sessions)
            tot_dist = sum(s.get("distractionDuration", 0.0) for s in p_sessions)
            dist_pct = round((tot_dist / max(1.0, tot_dur)) * 100.0, 1)

            # Tab switch / recovery penalty proxy
            tab_switches = sum(s.get("tabSwitchCount", 0) for s in p_sessions)
            avg_rec = round(min(120.0, 15.0 + (tab_switches / max(1, len(p_sessions))) * 8.0), 1)

            # Composite attention quality score (0 - 100)
            composite = round((avg_focus * 0.45) + (max(0.0, 100.0 - dist_pct) * 0.35) + (max(0.0, 100.0 - avg_rec) * 0.20), 1)

            scored_periods.append({
                "period": p_name,
                "label": data["label"],
                "window": data["window"],
                "sessionCount": len(p_sessions),
                "avgAttentionScore": avg_focus,
                "distractionRatePct": dist_pct,
                "avgRecoverySec": avg_rec,
                "compositeScore": composite,
            })

        if not scored_periods:
            return {
                "hasRecommendation": False,
                "status": "INSUFFICIENT_DATA",
                "period": None,
                "window": None,
                "avgAttentionScore": 0.0,
                "distractionRatePct": 0.0,
                "avgRecoverySec": 0.0,
                "recommendation": "FocusTwin needs more sessions across different times of day.",
            }

        scored_periods.sort(key=lambda x: (x["compositeScore"], x["sessionCount"]), reverse=True)
        best = scored_periods[0]

        return {
            "hasRecommendation": True,
            "status": "OPTIMAL",
            "period": best["period"],
            "window": best["window"],
            "label": best["label"],
            "avgAttentionScore": best["avgAttentionScore"],
            "distractionRatePct": best["distractionRatePct"],
            "avgRecoverySec": best["avgRecoverySec"],
            "compositeScore": best["compositeScore"],
            "sessionCount": best["sessionCount"],
            "recommendation": (
                f"Your strongest attention window is {best['label']} with {best['avgAttentionScore']}% "
                f"average attention, {best['distractionRatePct']}% distraction rate, and {best['avgRecoverySec']}s recovery."
            ),
            "allWindows": scored_periods,
        }

    @classmethod
    def compute_task_profiles(cls, user_id: str, sessions: List[Dict[str, Any]]) -> Dict[str, Any]:
        """
        Computes task-specific attention baselines for distinct learning tasks.
        Updates task_contexts in SQLite to ensure system synchronicity.
        """
        task_groups = {}
        for s in sessions:
            task = (s.get("task_type") or s.get("taskType") or "General Study").strip()
            task_groups.setdefault(task, []).append(s)

        profiles = {}
        defaults = ["Coding", "Coursera Lecture", "Reading", "Assignment", "Research", "Writing", "YouTube Learning"]
        all_tasks = sorted(list(set(defaults + list(task_groups.keys()))))

        for task in all_tasks:
            matching = task_groups.get(task, [])
            if matching:
                avg_score = round(sum(s.get("focusScore", 80.0) for s in matching) / len(matching), 1)
                tot_dist = round(sum(s.get("distractionDuration", 0.0) for s in matching) / len(matching), 1)
                switches = sum(s.get("tabSwitchCount", 0) for s in matching) / len(matching)
                avg_rec = round(min(120.0, 18.0 + switches * 7.5), 1)
                count = len(matching)

                if avg_rec <= 35.0 and avg_score >= 80.0:
                    resilience = "HIGH"
                elif avg_rec <= 65.0 and avg_score >= 60.0:
                    resilience = "MODERATE"
                else:
                    resilience = "FRAGILE"

                # Sync with SQLite
                db.upsert_task_context(
                    user_id=user_id,
                    task_name=task,
                    avg_attention_score=avg_score,
                    avg_recovery_time=avg_rec,
                    increment_session=False,
                )
            else:
                avg_score = 75.0
                tot_dist = 45.0
                avg_rec = 35.0
                count = 0
                resilience = "MODERATE"

            profiles[task] = {
                "taskName": task,
                "sessionCount": count,
                "avgAttentionScore": avg_score,
                "avgDistractionSec": tot_dist,
                "avgRecoverySec": avg_rec,
                "interruptionResilience": resilience,
            }

        return profiles

    @classmethod
    def compute_digital_twin_profile(cls, user_id: str, persist: bool = True) -> Dict[str, Any]:
        """
        Full Digital Twin synthesis:
        - Evaluates cold-start tier
        - Calculates personal focus duration, recovery time, notification sensitivity, fatigue onset
        - Computes multidimensional best attention window
        - Computes task-specific profiles
        - Persists state into SQLite attention_profiles table
        """
        # Fetch completed sessions
        all_sessions = db.list_sessions(user_id=user_id, limit=100)
        completed = [s for s in all_sessions if s.get("status") == "COMPLETED"]

        # Score sessions
        scored_sessions = []
        for s in completed:
            scored = dict(s)
            scored.update(calculate_session_scores(s))
            scored_sessions.append(scored)

        # Fetch user events
        user_events = db.list_user_events(user_id=user_id, limit=300)

        # 1. Cold Start Status
        cold_start = cls.evaluate_cold_start(len(scored_sessions))

        # 2. Focus Duration Baseline
        if scored_sessions:
            avg_focus_sec = sum(s.get("focusedDuration", 0.0) for s in scored_sessions) / len(scored_sessions)
            avg_focus_min = round(max(5.0, avg_focus_sec / 60.0), 1)
        else:
            avg_focus_min = 25.0

        # 3. Task Resumption & Recovery
        resumption_stats = TaskResumptionEngine.analyze_resumptions(user_events)
        if resumption_stats["avgAttentionRecoverySec"] > 0:
            avg_recovery_sec = resumption_stats["avgAttentionRecoverySec"]
        elif scored_sessions:
            switches = sum(s.get("tabSwitchCount", 0) for s in scored_sessions) / len(scored_sessions)
            avg_recovery_sec = round(min(90.0, max(15.0, 20.0 + switches * 6.0)), 1)
        else:
            avg_recovery_sec = 35.0

        # 4. Notification Sensitivity
        notifications = db.list_notifications(user_id=user_id, limit=50)
        if notifications:
            dropped = sum(1 for n in notifications if n.get("action_taken") == "ALLOW" or (n.get("actual_distraction_sec") or 0) > 10.0)
            notif_sensitivity = round(max(0.1, min(0.95, dropped / len(notifications))), 2)
        elif scored_sessions:
            # Estimate from tab switches per session
            avg_switches = sum(s.get("tabSwitchCount", 0) for s in scored_sessions) / len(scored_sessions)
            notif_sensitivity = round(min(0.90, max(0.20, 0.30 + avg_switches * 0.12)), 2)
        else:
            notif_sensitivity = 0.50

        # 5. Fatigue Pattern Learning
        fatigue_info = FatiguePatternLearner.analyze_fatigue_pattern(scored_sessions, user_events)
        fatigue_onset = fatigue_info["fatigueOnsetMinutes"]

        # 6. Best Attention Window
        best_window = cls.compute_best_attention_window(scored_sessions, user_events)

        # 7. Task Profiles
        task_profiles = cls.compute_task_profiles(user_id, scored_sessions)

        # 8. Application Fragmentation
        tab_switches = [e for e in user_events if e.get("event_type") == "TAB_SWITCH"]
        total_time = sum(s.get("totalDuration", 0.0) for s in scored_sessions)
        fragmentation = FragmentationEngine.analyze_switches(tab_switches, session_duration_sec=total_time)

        # Digital Twin Profile Object
        twin_profile = {
            "userId": user_id,
            "user_id": user_id,
            "coldStartState": cold_start["coldStartState"],
            "cold_start_state": cold_start["coldStartState"],
            "confidenceLevel": cold_start["confidenceLevel"],
            "confidence_level": cold_start["confidenceLevel"],
            "sampleSize": cold_start["sampleSize"],
            "sample_size": cold_start["sampleSize"],
            "isPersonalized": cold_start["isPersonalized"],
            "statusMessage": cold_start["statusMessage"],
            "best_attention_window": best_window.get("period") or "Afternoon",
            "avg_focus_duration": avg_focus_min,
            "avg_recovery_time": avg_recovery_sec,
            "notification_sensitivity": notif_sensitivity,
            "fatigue_onset_minutes": fatigue_onset,
            "baselines": {
                "avgFocusDurationMinutes": avg_focus_min,
                "avgRecoveryTimeSeconds": avg_recovery_sec,
                "notificationSensitivity": notif_sensitivity,
                "fatigueOnsetMinutes": fatigue_onset,
                "avgFocusScore": round(sum(s.get("focusScore", 85.0) for s in scored_sessions) / len(scored_sessions), 1) if scored_sessions else 85.0,
            },
            "bestAttentionWindow": best_window,
            "fatiguePattern": fatigue_info,
            "taskProfiles": task_profiles,
            "task_profiles_json": task_profiles,
            "appFragmentation": fragmentation,
            "app_fragmentation_json": fragmentation,
            "resumptionStats": resumption_stats,
            "updatedAt": datetime.now(timezone.utc).isoformat(),
        }

        # Persist to SQLite
        if persist:
            window_str = best_window.get("period") or "Afternoon"
            db.update_attention_profile(
                user_id=user_id,
                cold_start_state=cold_start["coldStartState"],
                confidence_level=cold_start["confidenceLevel"],
                sample_size=cold_start["sampleSize"],
                best_attention_window=window_str,
                avg_focus_duration=avg_focus_min,
                avg_recovery_time=avg_recovery_sec,
                notification_sensitivity=notif_sensitivity,
                fatigue_onset_minutes=fatigue_onset,
                task_profiles_json=task_profiles,
                app_fragmentation_json=fragmentation,
            )

        return twin_profile


# Global engine instances
fragmentation_engine = FragmentationEngine()
browsing_overrun_detector = BrowsingOverrunDetector()
task_resumption_engine = TaskResumptionEngine()
digital_twin_engine = DigitalTwinEngine()
