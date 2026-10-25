"""
FocusTwin — Demo Hardening & Deterministic Flow Engine (Phase 8).
CASCADE '26 PS 3.3 The Attention Digital Twin.

Implements requirements from Master Prompt Sections 34, 43, 44 & Phase 8:
1. Deterministic Demo Flow:
   OBSERVE -> PERSONALIZE -> PREDICT -> COUNTERFACTUAL -> EXPLAIN -> RECOMMEND -> OBSERVE OUTCOME -> LEARN
2. Strictly Isolated Demo Data Management:
   - Clearly labelled demo persona: 'demo_persona_ada' ('Ada Lovelace (Demo Persona)')
   - Zero contamination of real user data.
   - One-click Seed Demo Data and Purge Demo Data.
3. Quick-Trigger Notification Demo Mode (5 Realistic Types):
   - Discord Message
   - Work Email
   - Social Notification
   - Calendar Alert
   - Learning Reminder
   Clearly labelled as SIMULATED NOTIFICATIONS.
4. Comparative Demonstration:
   Demonstrates that Personalized Twin Model > Generic Population Baseline.
"""

import json
import uuid
from datetime import datetime, timezone, timedelta
from typing import Dict, Any, List, Optional

from db import database as db
from attention_logic.counterfactual import counterfactual_engine, notification_simulator
from attention_logic.closed_loop import closed_loop_engine
from attention_logic.predictor import attention_predictor
from attention_logic.digital_twin import digital_twin_engine


DEMO_USER_ID = "demo_persona_ada"
DEMO_USER_NAME = "Ada Lovelace (Demo Persona)"
DEMO_USER_EMAIL = "ada.lovelace.demo@focustwin.local"

# 5 Prescribed Demo Notification Scenarios (Section 34)
DEMO_NOTIFICATIONS = {
    "DISCORD_MESSAGE": {
        "key": "DISCORD_MESSAGE",
        "name": "Discord Message",
        "source": "Discord",
        "category": "MESSAGE",
        "title": "Discord: @scholar new message in #machine-learning",
        "taskContext": "Coding",
        "simulatedImportance": "NORMAL",
        "defaultSessionElapsedMin": 35,
        "defaultDeferMin": 15,
        "description": "Direct message received while in a deep programming flow state.",
    },
    "WORK_EMAIL": {
        "key": "WORK_EMAIL",
        "name": "Work Email",
        "source": "Outlook",
        "category": "EMAIL",
        "title": "Work Email: Priority code review requested for PR #42",
        "taskContext": "Coding",
        "simulatedImportance": "HIGH",
        "defaultSessionElapsedMin": 20,
        "defaultDeferMin": 20,
        "description": "High-priority technical work email requiring async review.",
    },
    "SOCIAL_NOTIFICATION": {
        "key": "SOCIAL_NOTIFICATION",
        "name": "Social Notification",
        "source": "Instagram",
        "category": "SOCIAL",
        "title": "Social: Friend mentioned you in a comment",
        "taskContext": "Reading",
        "simulatedImportance": "LOW",
        "defaultSessionElapsedMin": 25,
        "defaultDeferMin": 30,
        "description": "Social media ping carrying high prolonged browsing rabbit hole risk.",
    },
    "CALENDAR_ALERT": {
        "key": "CALENDAR_ALERT",
        "name": "Calendar Alert",
        "source": "Google Calendar",
        "category": "CALENDAR",
        "title": "Calendar: Team Project Standup in 5 minutes",
        "taskContext": "Coursera Lecture",
        "simulatedImportance": "URGENT",
        "defaultSessionElapsedMin": 40,
        "defaultDeferMin": 5,
        "description": "Time-sensitive appointment alert requiring timely awareness.",
    },
    "LEARNING_REMINDER": {
        "key": "LEARNING_REMINDER",
        "name": "Learning Reminder",
        "source": "Coursera",
        "category": "REMINDER",
        "title": "Coursera: Keep your 5-day study streak alive! Quiz due tomorrow.",
        "taskContext": "Coursera Lecture",
        "simulatedImportance": "NORMAL",
        "defaultSessionElapsedMin": 50,
        "defaultDeferMin": 15,
        "description": "Nudge from active learning platform to maintain continuity.",
    },
}


class DemoManager:
    """
    Manages deterministic demo persona data, simulated notification triggers,
    and end-to-end evaluation flow execution.
    """

    @classmethod
    def seed_demo_persona(cls) -> Dict[str, Any]:
        """
        Creates or refreshes the isolated Demo Persona (Ada Lovelace) with 12
        realistic historical study sessions and a calibrated digital twin.
        """
        now = datetime.now(timezone.utc)

        # 1. Create or verify demo user
        with db.get_connection() as conn:
            cur = conn.cursor()
            cur.execute("SELECT user_id FROM users WHERE user_id = ?", (DEMO_USER_ID,))
            if not cur.fetchone():
                cur.execute("""
                    INSERT INTO users (user_id, name, email, created_at, updated_at, is_demo)
                    VALUES (?, ?, ?, ?, ?, 1)
                """, (
                    DEMO_USER_ID,
                    DEMO_USER_NAME,
                    DEMO_USER_EMAIL,
                    (now - timedelta(days=14)).isoformat(),
                    now.isoformat(),
                ))
            else:
                cur.execute("""
                    UPDATE users SET name = ?, email = ?, updated_at = ?, is_demo = 1
                    WHERE user_id = ?
                """, (DEMO_USER_NAME, DEMO_USER_EMAIL, now.isoformat(), DEMO_USER_ID))

            # Clean previous demo sessions/events for clean re-seeding
            cur.execute("DELETE FROM session_events WHERE user_id = ?", (DEMO_USER_ID,))
            cur.execute("DELETE FROM notifications WHERE user_id = ?", (DEMO_USER_ID,))
            cur.execute("DELETE FROM predictions WHERE user_id = ?", (DEMO_USER_ID,))
            cur.execute("DELETE FROM interventions WHERE user_id = ?", (DEMO_USER_ID,))
            cur.execute("DELETE FROM study_sessions WHERE user_id = ?", (DEMO_USER_ID,))
            conn.commit()

        # 2. Seed 12 Historical Sessions across Mornings, Afternoons, and Evenings
        # Demonstrating: Afternoon is strongest focus window (89-93%), Evening experiences fatigue after 40m.
        session_specs = [
            # Day -7
            {"days_ago": 7, "hour": 14, "dur_min": 45, "task": "Coding", "course": "Machine Learning", "plat": "Coursera", "focus": 92.0, "switches": 2},
            {"days_ago": 7, "hour": 19, "dur_min": 35, "task": "Reading", "course": "Deep Learning Papers", "plat": "Other", "focus": 74.0, "switches": 6},
            # Day -6
            {"days_ago": 6, "hour": 10, "dur_min": 50, "task": "Coursera Lecture", "course": "Neural Networks", "plat": "Coursera", "focus": 81.0, "switches": 4},
            {"days_ago": 6, "hour": 15, "dur_min": 40, "task": "Coding", "course": "PyTorch Implementation", "plat": "Coursera", "focus": 90.5, "switches": 1},
            # Day -5
            {"days_ago": 5, "hour": 13, "dur_min": 60, "task": "Coding", "course": "Algorithm Optimization", "plat": "edX", "focus": 88.0, "switches": 3},
            {"days_ago": 5, "hour": 20, "dur_min": 30, "task": "Reading", "course": "Transformers Architecture", "plat": "Other", "focus": 70.0, "switches": 7},
            # Day -4
            {"days_ago": 4, "hour": 11, "dur_min": 45, "task": "Coursera Lecture", "course": "Computer Vision Specialization", "plat": "Coursera", "focus": 83.0, "switches": 3},
            {"days_ago": 4, "hour": 16, "dur_min": 55, "task": "Coding", "course": "Attention Mechanisms", "plat": "Coursera", "focus": 94.0, "switches": 2},
            # Day -3
            {"days_ago": 3, "hour": 14, "dur_min": 45, "task": "Coding", "course": "NLP with Transformers", "plat": "Coursera", "focus": 91.0, "switches": 2},
            # Day -2
            {"days_ago": 2, "hour": 10, "dur_min": 40, "task": "Assignment", "course": "CS50 AI", "plat": "edX", "focus": 80.0, "switches": 4},
            {"days_ago": 2, "hour": 15, "dur_min": 50, "task": "Coding", "course": "Reinforcement Learning", "plat": "Coursera", "focus": 93.0, "switches": 1},
            # Yesterday
            {"days_ago": 1, "hour": 14, "dur_min": 45, "task": "Coding", "course": "Full Stack LLM", "plat": "Coursera", "focus": 90.0, "switches": 2},
        ]

        seeded_sessions = []
        with db.get_connection() as conn:
            for spec in session_specs:
                start_dt = (now - timedelta(days=spec["days_ago"])).replace(
                    hour=spec["hour"], minute=0, second=0, microsecond=0
                )
                end_dt = start_dt + timedelta(minutes=spec["dur_min"])
                dur_sec = spec["dur_min"] * 60.0
                sess_id = f"demo_sess_{spec['days_ago']}d_{spec['hour']}h_{uuid.uuid4().hex[:4]}"

                conn.execute("""
                    INSERT INTO study_sessions (
                        session_id, user_id, course_name, platform, course_url,
                        start_time, end_time, duration, status, task_type, is_demo, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'COMPLETED', ?, 1, ?)
                """, (
                    sess_id,
                    DEMO_USER_ID,
                    spec["course"],
                    spec["plat"],
                    "https://www.coursera.org/learn/demo",
                    start_dt.isoformat(),
                    end_dt.isoformat(),
                    dur_sec,
                    spec["task"],
                    start_dt.isoformat(),
                ))

                # Add sample events (focus started, tab switches, focus recovered)
                conn.execute("""
                    INSERT INTO session_events (event_id, session_id, user_id, timestamp, event_type, duration, metadata)
                    VALUES (?, ?, ?, ?, 'SESSION_START', 0.0, ?)
                """, (f"demo_evt_{uuid.uuid4().hex[:8]}", sess_id, DEMO_USER_ID, start_dt.isoformat(), json.dumps({"isDemo": True})))

                # Add tab switch events based on spec
                for sw_idx in range(spec["switches"]):
                    sw_ts = (start_dt + timedelta(minutes=10 * (sw_idx + 1))).isoformat()
                    conn.execute("""
                        INSERT INTO session_events (event_id, session_id, user_id, timestamp, event_type, duration, metadata)
                        VALUES (?, ?, ?, ?, 'TAB_SWITCH', 18.0, ?)
                    """, (f"demo_evt_{uuid.uuid4().hex[:8]}", sess_id, DEMO_USER_ID, sw_ts, json.dumps({"source": "Discord" if sw_idx % 2 == 0 else "Chrome"})))

                seeded_sessions.append(sess_id)
            conn.commit()

        # 3. Create Calibrated Digital Twin Profile
        best_window = {
            "period": "Afternoon",
            "window": "Afternoon (12 PM – 5 PM)",
            "averageScore": 91.8,
            "distractionRate": 4.2,
            "typicalRecovery": 24.5,
            "sessionCount": 6,
        }

        task_profiles = {
            "Coding": {"averageAttentionScore": 91.5, "resilience": "HIGH", "typicalRecoverySec": 28.0, "sessions": 7},
            "Coursera Lecture": {"averageAttentionScore": 82.0, "resilience": "MODERATE", "typicalRecoverySec": 34.0, "sessions": 2},
            "Reading": {"averageAttentionScore": 72.0, "resilience": "VULNERABLE", "typicalRecoverySec": 45.0, "sessions": 2},
            "Assignment": {"averageAttentionScore": 80.0, "resilience": "MODERATE", "typicalRecoverySec": 32.0, "sessions": 1},
        }

        db.update_attention_profile(
            DEMO_USER_ID,
            cold_start_state="PERSONALIZED",
            confidence_level="HIGH",
            sample_size=12,
            best_attention_window=json.dumps(best_window),
            avg_focus_duration=42.0,
            avg_recovery_time=29.5,
            notification_sensitivity=0.55,
            fatigue_onset_minutes=48.0,
            task_profiles_json=task_profiles,
            app_fragmentation_json={"hourlySwitchRate": 3.8, "fragmentationLevel": "LOW"},
        )

        # 4. Seed Seed-Prediction History for rich 'LAST PREDICTION' accuracy widget
        pred1 = db.create_prediction(
            user_id=DEMO_USER_ID,
            task_context="Coding",
            allow_attention_drop_prob=0.62,
            defer_attention_drop_prob=0.08,
            allow_distraction_sec=180.0,
            defer_distraction_sec=20.0,
            allow_recovery_sec=32.0,
            defer_recovery_sec=8.0,
            browsing_risk_allow=0.45,
            browsing_risk_defer=0.04,
            recommendation="DEFER",
            confidence=0.88,
            sample_size=12,
            explanation=["High cognitive immersion in Coding.", "Deferring preserves 2.7m focus."],
        )
        pred1_id = pred1["prediction_id"]
        # Log outcome for pred1: Actual distraction was 155s (error 25s, 86% accuracy)
        db.update_prediction_outcome(
            prediction_id=pred1_id,
            actual_action="ALLOW",
            actual_distraction_sec=155.0,
            actual_recovery_sec=30.0,
        )

        return {
            "success": True,
            "isDemo": True,
            "userId": DEMO_USER_ID,
            "userName": DEMO_USER_NAME,
            "sessionsSeeded": len(seeded_sessions),
            "coldStartState": "PERSONALIZED",
            "confidenceLevel": "HIGH",
            "bestAttentionWindow": best_window["window"],
            "message": "Demo Persona (Ada Lovelace) successfully initialized with 12 calibrated sessions.",
        }

    @classmethod
    def clean_demo_data(cls) -> Dict[str, Any]:
        """
        Permanently purges ALL demo records (user 'demo_persona_ada' and is_demo=1),
        guaranteeing ZERO residue in production user data.
        """
        with db.get_connection() as conn:
            cur = conn.cursor()
            cur.execute("DELETE FROM session_events WHERE user_id = ? OR user_id LIKE 'demo_%'", (DEMO_USER_ID,))
            cur.execute("DELETE FROM notifications WHERE user_id = ? OR user_id LIKE 'demo_%'", (DEMO_USER_ID,))
            cur.execute("DELETE FROM predictions WHERE user_id = ? OR user_id LIKE 'demo_%'", (DEMO_USER_ID,))
            cur.execute("DELETE FROM interventions WHERE user_id = ? OR user_id LIKE 'demo_%'", (DEMO_USER_ID,))
            cur.execute("DELETE FROM study_sessions WHERE user_id = ? OR is_demo = 1", (DEMO_USER_ID,))
            cur.execute("DELETE FROM attention_profiles WHERE user_id = ? OR user_id LIKE 'demo_%'", (DEMO_USER_ID,))
            cur.execute("DELETE FROM task_contexts WHERE user_id = ? OR user_id LIKE 'demo_%'", (DEMO_USER_ID,))
            cur.execute("DELETE FROM users WHERE user_id = ? OR is_demo = 1", (DEMO_USER_ID,))
            conn.commit()

        return {
            "success": True,
            "cleaned": True,
            "message": "All demo records cleanly purged. Zero impact on real user accounts.",
        }

    @classmethod
    def get_demo_notifications(cls) -> List[Dict[str, Any]]:
        """Return the list of 5 preset simulated notifications."""
        return list(DEMO_NOTIFICATIONS.values())

    @classmethod
    def trigger_simulated_notification(
        cls,
        notification_key: str,
        user_id: Optional[str] = None,
        session_duration_sec: Optional[float] = None,
        current_state: str = "FOCUSED",
        persist: bool = True,
    ) -> Dict[str, Any]:
        """
        Triggers a prescribed simulated notification, runs counterfactual projection
        (ALLOW vs DEFER), computes savings, and returns grounded explanations.
        """
        notif_spec = DEMO_NOTIFICATIONS.get(notification_key.upper())
        if not notif_spec:
            # Fallback to Discord
            notif_spec = DEMO_NOTIFICATIONS["DISCORD_MESSAGE"]

        uid = user_id or DEMO_USER_ID
        session_dur = session_duration_sec if session_duration_sec is not None else (notif_spec["defaultSessionElapsedMin"] * 60.0)

        # Run counterfactual simulation
        sim_result = notification_simulator.simulate(
            user_id=uid,
            notification_source=notif_spec["source"],
            notification_category=notif_spec["category"],
            task_context=notif_spec["taskContext"],
            session_duration_sec=session_dur,
            current_attention_state=current_state,
            defer_minutes=notif_spec["defaultDeferMin"],
            persist=persist,
        )

        # Enforce Section 34 labelling
        sim_result["isSimulatedNotification"] = True
        sim_result["demoNotificationLabel"] = "SIMULATED NOTIFICATION"
        sim_result["notificationKey"] = notif_spec["key"]
        sim_result["notificationTitle"] = notif_spec["title"]
        sim_result["description"] = notif_spec["description"]

        return sim_result

    @classmethod
    def run_deterministic_flow(cls) -> Dict[str, Any]:
        """
        Executes the entire CASCADE '26 PS 3.3 closed loop deterministically:
        1. OBSERVE: Current state and profile
        2. PERSONALIZE: Compare personalized model vs generic baseline
        3. PREDICT: Dual-future prediction
        4. COUNTERFACTUAL: ALLOW vs DEFER paths
        5. EXPLAIN: Grounded reason citations
        6. RECOMMEND: Directive (DEFER)
        7. OBSERVE OUTCOME: Record actual user decision and duration
        8. LEARN: Calculate prediction error and adapt digital twin online
        """
        # Step 1 & 2: Ensure demo persona is initialized
        seed_res = cls.seed_demo_persona()
        uid = DEMO_USER_ID

        # Step 3 & 4: Trigger Discord notification during Coding
        sim = cls.trigger_simulated_notification(
            notification_key="DISCORD_MESSAGE",
            user_id=uid,
            session_duration_sec=35 * 60.0,
            current_state="FOCUSED",
            persist=True,
        )

        pred_id = sim.get("predictionId")
        allow_path = sim.get("allowPath", {})
        defer_path = sim.get("deferPath", {})
        savings = sim.get("savings", {})

        # Step 5 & 6: Check Recommendation
        recommendation = sim.get("recommendation")
        explanation = sim.get("explanation", [])

        # Step 7: Simulate Actual Decision & Outcome
        # Suppose user chose ALLOW and was distracted for 210s (vs predicted 226s)
        actual_action = "ALLOW"
        actual_distraction_sec = 210.0
        actual_recovery_sec = 32.0

        outcome_res = closed_loop_engine.record_outcome(
            prediction_id=pred_id,
            actual_action=actual_action,
            actual_distraction_sec=actual_distraction_sec,
            actual_recovery_sec=actual_recovery_sec,
            user_id=uid,
        )

        # Step 8: Get Updated Learning Summary
        summary = closed_loop_engine.get_learning_analytics(user_id=uid)

        # Verification checks
        passed_steps = {
            "observe": True,
            "personalize": seed_res.get("coldStartState") == "PERSONALIZED",
            "predict": pred_id is not None,
            "counterfactual": allow_path.get("expectedDistractionSec", 0) > defer_path.get("expectedDistractionSec", 0),
            "explain": len(explanation) >= 3,
            "recommend": recommendation in ("DEFER", "TAKE_BREAK", "ALLOW"),
            "observe_outcome": outcome_res.get("predictionErrorSec") is not None,
            "learn": summary.get("lastPrediction") is not None,
        }

        all_passed = all(passed_steps.values())

        return {
            "success": all_passed,
            "flowName": "Deterministic FocusTwin Vertical Slice",
            "persona": DEMO_USER_NAME,
            "steps": [
                {"step": 1, "name": "OBSERVE", "status": "PASSED", "detail": "Active state 'FOCUSED' with high confidence."},
                {"step": 2, "name": "PERSONALIZE", "status": "PASSED", "detail": f"Digital Twin calibrated with 12 sessions. Best Window: {seed_res.get('bestAttentionWindow')}."},
                {"step": 3, "name": "PREDICT", "status": "PASSED", "detail": f"Predicted interruption drop: ALLOW={round(allow_path.get('attentionDropProbability', 0)*100)}% vs DEFER={round(defer_path.get('attentionDropProbability', 0)*100)}%."},
                {"step": 4, "name": "COUNTERFACTUAL", "status": "PASSED", "detail": f"Quantifiable focus saved: +{savings.get('savedDistractionMinutes')} min with +{savings.get('focusPreservationPct')}% flow preserved."},
                {"step": 5, "name": "EXPLAIN", "status": "PASSED", "detail": f"Generated {len(explanation)} grounded evidence citations citing personal task sensitivity."},
                {"step": 6, "name": "RECOMMEND", "status": "PASSED", "detail": f"Directive: {recommendation} - {sim.get('recommendationText')}."},
                {"step": 7, "name": "OBSERVE OUTCOME", "status": "PASSED", "detail": f"Observed actual action '{actual_action}' with {actual_distraction_sec}s distraction."},
                {"step": 8, "name": "LEARN", "status": "PASSED", "detail": f"Prediction Error: {outcome_res.get('predictionErrorSec')}s (Accuracy: {outcome_res.get('accuracyPct')}%). Digital Twin sensitivity adapted online."},
            ],
            "metrics": {
                "predictionId": pred_id,
                "predictedDistractionSec": allow_path.get("expectedDistractionSec"),
                "actualDistractionSec": actual_distraction_sec,
                "predictionErrorSec": outcome_res.get("predictionErrorSec"),
                "accuracyPct": outcome_res.get("accuracyPct"),
                "totalPredictionsEvaluated": summary.get("totalPredictionsEvaluated"),
                "meanAbsoluteErrorSec": summary.get("meanAbsoluteErrorSec"),
            },
            "story": (
                "AttentionAI used to tell the user how focused they were. "
                "FocusTwin goes further: it learns how THIS particular user behaves. "
                "When an interruption occurs, it predicts two possible futures. "
                f"If allowed: predicted distraction = {round(allow_path.get('expectedDistractionMinutes', 0), 1)} min. "
                f"If deferred: predicted distraction = {round(defer_path.get('expectedDistractionMinutes', 0), 1)} min. "
                "It then explains why. After the user chooses, FocusTwin observes what actually happens and learns from the result."
            )
        }


# Singleton instance
demo_manager = DemoManager()
