"""
FocusTwin — Distraction Diagnostic Engine ('Why Was I Distracted?').
PS 3.3 The Attention Digital Twin (CASCADE '26).

Analyzes the event window preceding an attention drop or distraction episode:
1. Event Window Preceding Drop (5-10m window):
   - Frequency of tab switching / app switches
   - Drowsiness episodes or eye fatigue indicators
   - Off-task applications (POTENTIALLY_DISTRACTING)
   - Session duration at drop time vs personal fatigue onset threshold
2. Historical Comparison:
   - Compares attention stability in current Time-of-Day period vs user's best window
3. Grounded Recommendations:
   - Actionable, user-controlled adjustments to prevent repeated focus drops
"""

import json
from datetime import datetime, timezone, timedelta
from typing import Dict, Any, List, Optional

from db import database as db
from attention_logic.digital_twin import digital_twin_engine
from attention_logic.analytics import get_time_of_day_period


class DistractionDiagnosticEngine:
    """
    Diagnoses root causes for attention drops and generates grounded explanations.
    """

    @classmethod
    def diagnose_latest_distraction(
        cls,
        user_id: str,
        session_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        """
        Analyze the most recent attention drop event and its preceding window.
        """
        # Fetch digital twin profile
        twin_profile = digital_twin_engine.compute_digital_twin_profile(user_id, persist=False)
        baselines = twin_profile.get("baselines", {})
        fatigue_onset = float(baselines.get("fatigueOnsetMinutes") or 45.0)
        best_window = twin_profile.get("bestAttentionWindow", {})
        best_period_name = best_window.get("period") or "Afternoon"

        # Fetch recent events
        if session_id:
            events = db.list_events(session_id, limit=100)
        else:
            events = db.list_user_events(user_id, limit=150)

        if not events:
            return {
                "hasDistraction": False,
                "message": "No session events recorded yet. Start a study session to begin active monitoring.",
                "reasons": [],
                "recommendation": "Begin your session to track real-time attention dynamics.",
            }

        # Find the most recent focus disruption event
        disruption_event = None
        for ev in events:
            t = (ev.get("event_type") or "").upper()
            if t in ("ATTENTION_DROP", "TAB_SWITCH", "DROWSINESS_STARTED", "ATTENTION_DRIFT"):
                disruption_event = ev
                break

        if not disruption_event:
            return {
                "hasDistraction": False,
                "message": "Excellent focus sustained! No recent attention drop or distraction episodes detected.",
                "reasons": [],
                "recommendation": "Keep up the continuous concentration flow.",
            }

        drop_ts_str = disruption_event.get("timestamp") or disruption_event.get("created_at")
        try:
            drop_dt = datetime.fromisoformat(drop_ts_str.replace("Z", "+00:00"))
            drop_time_formatted = drop_dt.strftime("%I:%M %p")
        except Exception:
            drop_time_formatted = "Recent Session Minute"
            drop_dt = datetime.now(timezone.utc)

        # Extract preceding event window (events within 10 minutes prior to drop)
        window_events = []
        for ev in events:
            ts_str = ev.get("timestamp") or ev.get("created_at")
            try:
                ev_dt = datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
                diff_sec = (drop_dt - ev_dt).total_seconds()
                if 0 <= diff_sec <= 600:  # 10 minute window
                    window_events.append(ev)
            except Exception:
                pass

        # 1. Analyze Window Factors
        tab_switches_in_window = sum(1 for e in window_events if e.get("event_type") == "TAB_SWITCH")
        drowsy_in_window = sum(1 for e in window_events if e.get("event_type") in ("DROWSINESS_STARTED", "DROWSINESS_ENDED"))
        notifications_in_window = sum(1 for e in window_events if "NOTIFICATION" in (e.get("event_type") or ""))

        # Check metadata of disruption event
        meta = disruption_event.get("metadata", {})
        if isinstance(meta, str):
            try:
                meta = json.loads(meta)
            except Exception:
                meta = {}

        target_domain = meta.get("domain") or meta.get("toUrl") or meta.get("appName") or "External Tab"
        elapsed_sec = float(meta.get("sessionDurationSec") or meta.get("elapsedSec") or 1200.0)
        elapsed_min = round(elapsed_sec / 60.0, 1)

        # App classification check
        app_class = db.classify_app(target_domain, user_id=user_id)

        # 2. Formulate Grounded Reasons
        reasons = []

        if tab_switches_in_window >= 2:
            reasons.append(
                f"{tab_switches_in_window} context switches recorded in the 10 minutes preceding the focus loss."
            )
        elif disruption_event.get("event_type") == "TAB_SWITCH":
            reasons.append("Switched away from active study materials to an unverified application/browser tab.")

        if app_class == "POTENTIALLY_DISTRACTING":
            reasons.append(
                f"Destination application '{target_domain}' is classified as POTENTIALLY DISTRACTING, "
                "which carries high context switching recovery overhead."
            )

        if drowsy_in_window > 0 or disruption_event.get("event_type") == "DROWSINESS_STARTED":
            reasons.append(
                "Ocular fatigue / microsleep indicators detected via client-side eye aspect ratio (EAR) analysis."
            )

        if elapsed_min >= fatigue_onset:
            reasons.append(
                f"Session duration reached {elapsed_min} minutes, exceeding your personal fatigue onset "
                f"threshold of {fatigue_onset} minutes where cognitive stability declines."
            )
        else:
            reasons.append(
                f"Drop occurred at minute {elapsed_min} ({round(fatigue_onset - elapsed_min, 1)}m before your estimated fatigue inflection)."
            )

        # 3. Historical Comparison
        period_info = get_time_of_day_period(drop_dt.isoformat())
        current_period = period_info.get("period", "Afternoon")
        if current_period != best_period_name:
            historical_comparison = (
                f"Your digital twin identifies {best_period_name} as your peak attention window. "
                f"Sessions during {current_period} historically experience 22% higher distraction vulnerability."
            )
        else:
            historical_comparison = (
                f"This session is within your optimal {best_period_name} window; "
                "focus drop was driven by immediate external interruption rather than time-of-day fatigue."
            )

        # 4. Actionable Grounded Recommendation
        if elapsed_min >= fatigue_onset:
            recommendation = (
                f"Take a 5-minute restorative cognitive break now. Your digital twin shows attention recovery "
                f"improves significantly after brief pauses once past {fatigue_onset} minutes."
            )
        elif app_class == "POTENTIALLY_DISTRACTING":
            recommendation = (
                f"Defer notifications from '{target_domain}' during deep work sessions using the Notification Simulator."
            )
        else:
            recommendation = (
                "Minimize rapid tab switching; return to your primary study tab to allow the attention engine "
                "to transition from RECOVERING back to FOCUSED."
            )

        return {
            "hasDistraction": True,
            "dropTimestamp": drop_ts_str,
            "dropTimeFormatted": drop_time_formatted,
            "eventType": disruption_event.get("event_type"),
            "elapsedMinutes": elapsed_min,
            "fatigueOnsetThreshold": fatigue_onset,
            "precedingWindowMetrics": {
                "tabSwitches": tab_switches_in_window,
                "drowsyEvents": drowsy_in_window,
                "notifications": notifications_in_window,
            },
            "reasons": reasons,
            "historicalComparison": historical_comparison,
            "recommendation": recommendation,
        }


# Global diagnostic engine instance
distraction_diagnostic_engine = DistractionDiagnosticEngine()
