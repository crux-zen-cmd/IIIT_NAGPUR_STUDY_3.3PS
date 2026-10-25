"""
FocusTwin — Counterfactual Engine & Notification Simulator (Phase 5).
PS 3.3 The Attention Digital Twin (CASCADE '26).

Core Capabilities:
1. Counterfactual Dual-Path Simulation:
   - Path A (ALLOW): Predicted outcome if notification interrupts immediately.
     * attention_drop_probability
     * distraction_probability
     * expected_distraction_sec / expected_distraction_minutes
     * expected_recovery_sec
     * prolonged_browsing_probability
     * estimated_score_impact
   - Path B (DEFER): Predicted outcome if notification is deferred (e.g., 15m or until break).
     * Preserved flow metrics
     * Minimal residual glance impact (5-12s)
     * Swift re-engagement (< 10s)
     * Suppressed browsing overrun risk (< 5%)
2. Quantifiable Savings & Benefits:
   - savedDistractionMinutes (ALLOW - DEFER)
   - focusPreservationPct (DropProb ALLOW - DropProb DEFER)
   - savedRecoverySec (Recovery ALLOW - Recovery DEFER)
   - browsingRiskReductionPct
3. Grounded Recommendation Engine:
   - ALLOW, DEFER, TAKE_BREAK, ASK_USER
   - Grounded rationales citing digital twin profile, app classification, and fatigue onset.
4. Interactive Notification Simulator:
   - Single-shot simulation with SQLite persistence
   - Batch simulation across diverse scenarios & task contexts
   - Rich preset library for interactive demonstrations
"""

import json
from datetime import datetime, timezone
from typing import Dict, Any, List, Optional

from db import database as db
from attention_logic.digital_twin import digital_twin_engine
from attention_logic.predictor import attention_predictor, ExplanationEngine


class CounterfactualEngine:
    """
    Simulates and evaluates two alternate futures:
    Path A: ALLOW the interruption now.
    Path B: DEFER the interruption for X minutes (or until the next natural break).
    """

    @classmethod
    def evaluate(
        cls,
        user_id: str,
        task_context: str = "Coding",
        notification_category: str = "MESSAGE",
        notification_source: str = "Discord",
        session_duration_sec: float = 0.0,
        current_attention_state: str = "FOCUSED",
        defer_minutes: int = 15,
        session_id: Optional[str] = None,
        notification_id: Optional[str] = None,
        persist: bool = False,
    ) -> Dict[str, Any]:
        """
        Execute full counterfactual simulation between ALLOW and DEFER pathways.
        """
        cat_upper = notification_category.upper()
        session_min = max(0.0, float(session_duration_sec) / 60.0)

        # 1. Fetch digital twin profile and app classification
        twin_profile = digital_twin_engine.compute_digital_twin_profile(user_id, persist=False)
        sample_size = int(twin_profile.get("sampleSize") or 0)
        baselines = twin_profile.get("baselines", {})
        task_profiles = twin_profile.get("taskProfiles", {})
        task_data = task_profiles.get(task_context, {})
        app_classification = db.classify_app(notification_source, user_id=user_id)

        fatigue_onset_min = float(baselines.get("fatigueOnsetMinutes") or 45.0)

        # 2. Compute Path A (ALLOW) using personalized predictor
        allow_prediction = attention_predictor.predict(
            user_id=user_id,
            task_context=task_context,
            notification_category=cat_upper,
            notification_source=notification_source,
            session_duration_sec=session_duration_sec,
            current_attention_state=current_attention_state,
            session_id=session_id,
            notification_id=notification_id,
            persist=False,
        )

        pers_allow = allow_prediction.get("personalizedPrediction", {})
        allow_drop_prob = float(pers_allow.get("attentionDropProbability", 0.65))
        allow_distract_prob = float(pers_allow.get("distractionProbability", 0.60))
        allow_dist_sec = float(pers_allow.get("expectedDistractionSec", 180.0))
        allow_rec_sec = float(pers_allow.get("expectedRecoverySec", 60.0))
        allow_overrun_prob = float(pers_allow.get("prolongedBrowsingProbability", 0.35))

        # Focus score penalty impact estimation (-points on 0-100 scale)
        allow_score_impact = round(min(30.0, (allow_dist_sec / 30.0) + (allow_drop_prob * 10.0)), 1)

        # 3. Compute Path B (DEFER)
        # When deferred, attention drop is confined to ambient awareness (0.05 - 0.15)
        if current_attention_state == "FATIGUE_RISK":
            defer_drop_prob = 0.16
            defer_distract_prob = 0.12
            defer_dist_sec = 12.0
            defer_rec_sec = 12.0
            defer_overrun_prob = 0.04
        elif current_attention_state == "DRIFTING":
            defer_drop_prob = 0.12
            defer_distract_prob = 0.08
            defer_dist_sec = 8.0
            defer_rec_sec = 9.0
            defer_overrun_prob = 0.03
        else:
            defer_drop_prob = 0.07
            defer_distract_prob = 0.05
            defer_dist_sec = 5.0
            defer_rec_sec = 6.0
            defer_overrun_prob = 0.02

        defer_score_impact = round(min(5.0, (defer_dist_sec / 30.0) + (defer_drop_prob * 5.0)), 1)

        # 4. Quantifiable Savings & Benefits
        saved_dist_sec = round(max(0.0, allow_dist_sec - defer_dist_sec), 1)
        saved_dist_min = round(saved_dist_sec / 60.0, 2)
        saved_rec_sec = round(max(0.0, allow_rec_sec - defer_rec_sec), 1)
        focus_preservation_pct = round(max(0.0, (allow_drop_prob - defer_drop_prob) * 100.0), 1)
        browsing_reduction_pct = round(max(0.0, (allow_overrun_prob - defer_overrun_prob) * 100.0), 1)
        score_preserved = round(max(0.0, allow_score_impact - defer_score_impact), 1)

        # 5. Recommendation Logic
        # Transparent rule set based on cognitive fatigue, app classification, and drop probability
        suggested_action = "DEFER"
        suggested_defer_min = defer_minutes

        if session_min >= (fatigue_onset_min + 5.0) and current_attention_state in ("FATIGUE_RISK", "DRIFTING"):
            recommendation = "TAKE_BREAK"
            suggested_action = "DEFER_AND_BREAK"
            recommendation_text = (
                f"TAKE COGNITIVE BREAK (5-10m): Session has reached {round(session_min, 1)} minutes, "
                f"exceeding your fatigue onset threshold ({fatigue_onset_min}m). "
                f"Defer notification and recharge before continuing."
            )
        elif cat_upper == "URGENT":
            recommendation = "ALLOW"
            suggested_action = "ALLOW"
            recommendation_text = "ALLOW: Notification flagged as high-priority/urgent despite switching cost."
        elif app_classification == "PRODUCTIVE" and allow_drop_prob < 0.40 and allow_dist_sec < 45.0:
            recommendation = "ALLOW"
            suggested_action = "ALLOW"
            recommendation_text = "ALLOW: Low cognitive switching cost and productive alignment with current work."
        elif allow_drop_prob >= 0.50 or saved_dist_sec >= 60.0 or app_classification == "POTENTIALLY_DISTRACTING":
            recommendation = "DEFER"
            suggested_action = "DEFER"
            recommendation_text = (
                f"DEFER FOR {defer_minutes} MINUTES: Preserves {round(saved_dist_sec / 60.0, 1)}m of focused study "
                f"and prevents an estimated {round(focus_preservation_pct)}% attention drop."
            )
        else:
            recommendation = "ASK_USER"
            suggested_action = "PROMPT"
            recommendation_text = (
                "ASK USER: Moderate context-switching trade-off. Show brief preview without switching focus."
            )

        # 6. Build Grounded Explanations
        base_explanations = allow_prediction.get("explanation", [])
        counterfactual_explanations = []

        counterfactual_explanations.append(
            f"ALLOW Path: Expected {round(allow_dist_sec / 60.0, 1)}m distraction with {allow_rec_sec}s recovery time "
            f"and ~{allow_score_impact} point focus score impact."
        )
        counterfactual_explanations.append(
            f"DEFER Path: Preserves ~{saved_dist_min}m of deep focus and avoids a {focus_preservation_pct}% attention drop."
        )

        if app_classification == "POTENTIALLY_DISTRACTING":
            counterfactual_explanations.append(
                f"Interruption source '{notification_source}' carries a {round(allow_overrun_prob * 100)}% risk "
                "of triggering prolonged off-task browsing (vs only "
                f"{round(defer_overrun_prob * 100)}% if deferred)."
            )

        if session_min >= fatigue_onset_min:
            counterfactual_explanations.append(
                f"Session duration ({round(session_min, 1)}m) is past your fatigue inflection point ({fatigue_onset_min}m), "
                "making attention significantly more fragile to external disruption."
            )

        combined_explanations = counterfactual_explanations + [e for e in base_explanations if e not in counterfactual_explanations]

        # 7. Formulate Paths & Structured Response
        allow_path = {
            "attentionDropProbability": allow_drop_prob,
            "distractionProbability": allow_distract_prob,
            "expectedDistractionSec": allow_dist_sec,
            "expectedDistractionMinutes": round(allow_dist_sec / 60.0, 2),
            "expectedRecoverySec": allow_rec_sec,
            "prolongedBrowsingProbability": allow_overrun_prob,
            "estimatedScoreImpact": -allow_score_impact,
        }

        defer_path = {
            "attentionDropProbability": defer_drop_prob,
            "distractionProbability": defer_distract_prob,
            "expectedDistractionSec": defer_dist_sec,
            "expectedDistractionMinutes": round(defer_dist_sec / 60.0, 2),
            "expectedRecoverySec": defer_rec_sec,
            "prolongedBrowsingProbability": defer_overrun_prob,
            "estimatedScoreImpact": -defer_score_impact,
            "deferMinutes": defer_minutes,
        }

        savings = {
            "savedDistractionSec": saved_dist_sec,
            "savedDistractionMinutes": saved_dist_min,
            "focusPreservationPct": focus_preservation_pct,
            "savedRecoverySec": saved_rec_sec,
            "browsingRiskReductionPct": browsing_reduction_pct,
            "attentionScorePreserved": score_preserved,
        }

        confidence = allow_prediction.get("confidence", 0.75)
        confidence_level = allow_prediction.get("confidenceLevel", "MEDIUM")
        confidence_status = allow_prediction.get("confidenceStatus", "")

        result = {
            "userId": user_id,
            "sessionId": session_id,
            "taskContext": task_context,
            "notificationCategory": cat_upper,
            "notificationSource": notification_source,
            "appClassification": app_classification,
            "sessionDurationMinutes": round(session_min, 1),
            "currentAttentionState": current_attention_state,
            "deferMinutes": defer_minutes,
            # Dual Paths
            "allowPath": allow_path,
            "deferPath": defer_path,
            "savings": savings,
            # Recommendation
            "recommendation": recommendation,
            "recommendationText": recommendation_text,
            "suggestedAction": suggested_action,
            "actionDetails": {
                "action": suggested_action,
                "deferMinutes": suggested_defer_min,
                "suggestedBreakMinutes": 5 if recommendation == "TAKE_BREAK" else 0,
            },
            # Explainability & Trust
            "confidence": confidence,
            "confidenceLevel": confidence_level,
            "confidenceStatus": confidence_status,
            "sampleSize": sample_size,
            "isPersonalized": allow_prediction.get("isPersonalized", False),
            "explanation": combined_explanations,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            # Snake case aliases for compatibility
            "allow_path": allow_path,
            "defer_path": defer_path,
            "saved_distraction_sec": saved_dist_sec,
            "saved_distraction_minutes": saved_dist_min,
            "focus_preservation_pct": focus_preservation_pct,
            "saved_recovery_sec": saved_rec_sec,
        }

        # 8. Persist to SQLite if requested
        if persist:
            pred_row = db.create_prediction(
                user_id=user_id,
                task_context=task_context,
                allow_attention_drop_prob=allow_drop_prob,
                defer_attention_drop_prob=defer_drop_prob,
                allow_distraction_sec=allow_dist_sec,
                defer_distraction_sec=defer_dist_sec,
                allow_recovery_sec=allow_rec_sec,
                defer_recovery_sec=defer_rec_sec,
                browsing_risk_allow=allow_overrun_prob,
                browsing_risk_defer=defer_overrun_prob,
                recommendation=recommendation,
                confidence=confidence,
                sample_size=sample_size,
                explanation=combined_explanations,
                notification_id=notification_id,
                session_id=session_id,
            )
            result["predictionId"] = pred_row["prediction_id"]
            result["prediction_id"] = pred_row["prediction_id"]

            # Also create intervention record if an action is recommended
            if recommendation in ("DEFER", "TAKE_BREAK"):
                inv_type = "BREAK_RECOMMENDATION" if recommendation == "TAKE_BREAK" else "DEFER_NOTIFICATION"
                try:
                    inv = db.create_intervention(
                        user_id=user_id,
                        type=inv_type,
                        reason=recommendation_text,
                        session_id=session_id,
                    )
                    result["interventionId"] = inv["intervention_id"]
                    result["intervention_id"] = inv["intervention_id"]
                except Exception:
                    pass

        return result


class NotificationSimulator:
    """
    Simulation platform for interactive what-if scenarios and batch evaluations.
    """

    PRESET_SCENARIOS = [
        {
            "id": "scenario_discord_coding",
            "name": "Discord Message during Deep Coding",
            "description": "Incoming community ping while immersed in algorithmic programming.",
            "source": "Discord",
            "category": "MESSAGE",
            "taskContext": "Coding",
            "sessionDurationSec": 2100,  # 35 minutes
            "currentAttentionState": "FOCUSED",
            "deferMinutes": 15,
        },
        {
            "id": "scenario_slack_writing",
            "name": "Slack Project Alert during Paper Writing",
            "description": "Workplace notification during structured document synthesis.",
            "source": "Slack",
            "category": "MESSAGE",
            "taskContext": "Writing",
            "sessionDurationSec": 900,  # 15 minutes
            "currentAttentionState": "FOCUSED",
            "deferMinutes": 15,
        },
        {
            "id": "scenario_youtube_reading",
            "name": "YouTube Recommendation during Academic Reading",
            "description": "Algorithmic feed temptation during dense technical reading.",
            "source": "YouTube",
            "category": "SOCIAL",
            "taskContext": "Reading",
            "sessionDurationSec": 3000,  # 50 minutes (past fatigue onset)
            "currentAttentionState": "DRIFTING",
            "deferMinutes": 20,
        },
        {
            "id": "scenario_github_coding",
            "name": "GitHub CI/CD Alert during Coding",
            "description": "Automated workflow notification from development tooling.",
            "source": "GitHub",
            "category": "SYSTEM",
            "taskContext": "Coding",
            "sessionDurationSec": 1200,  # 20 minutes
            "currentAttentionState": "FOCUSED",
            "deferMinutes": 10,
        },
        {
            "id": "scenario_fatigued_social",
            "name": "Social Alert at Late-Session Fatigue",
            "description": "Interactive alert after nearly an hour of continuous study.",
            "source": "Instagram",
            "category": "SOCIAL",
            "taskContext": "General Study",
            "sessionDurationSec": 3300,  # 55 minutes
            "currentAttentionState": "FATIGUE_RISK",
            "deferMinutes": 15,
        },
    ]

    @classmethod
    def get_preset_scenarios(cls) -> List[Dict[str, Any]]:
        """Return available preset simulation scenarios."""
        return cls.PRESET_SCENARIOS

    @classmethod
    def simulate(
        cls,
        user_id: str,
        task_context: str = "Coding",
        notification_category: str = "MESSAGE",
        notification_source: str = "Discord",
        session_duration_sec: float = 0.0,
        current_attention_state: str = "FOCUSED",
        defer_minutes: int = 15,
        session_id: Optional[str] = None,
        notification_id: Optional[str] = None,
        persist: bool = False,
    ) -> Dict[str, Any]:
        """Run single counterfactual simulation."""
        return CounterfactualEngine.evaluate(
            user_id=user_id,
            task_context=task_context,
            notification_category=notification_category,
            notification_source=notification_source,
            session_duration_sec=session_duration_sec,
            current_attention_state=current_attention_state,
            defer_minutes=defer_minutes,
            session_id=session_id,
            notification_id=notification_id,
            persist=persist,
        )

    @classmethod
    def batch_simulate(
        cls,
        user_id: str,
        scenarios: Optional[List[Dict[str, Any]]] = None,
        persist: bool = False,
    ) -> Dict[str, Any]:
        """
        Run multiple simulations across scenarios and aggregate focus time savings.
        """
        targets = scenarios if (scenarios and len(scenarios) > 0) else cls.PRESET_SCENARIOS

        results = []
        total_saved_sec = 0.0
        total_preservation_sum = 0.0
        defer_count = 0
        allow_count = 0
        break_count = 0

        for sc in targets:
            sim = cls.simulate(
                user_id=user_id,
                task_context=sc.get("taskContext", "Coding"),
                notification_category=sc.get("category") or sc.get("notificationCategory") or "MESSAGE",
                notification_source=sc.get("source") or sc.get("notificationSource") or "System",
                session_duration_sec=float(sc.get("sessionDurationSec", 0.0)),
                current_attention_state=sc.get("currentAttentionState", "FOCUSED"),
                defer_minutes=int(sc.get("deferMinutes", 15)),
                session_id=sc.get("sessionId"),
                persist=persist,
            )
            sim["scenarioId"] = sc.get("id")
            sim["scenarioName"] = sc.get("name")
            results.append(sim)

            savings = sim.get("savings", {})
            total_saved_sec += float(savings.get("savedDistractionSec", 0.0))
            total_preservation_sum += float(savings.get("focusPreservationPct", 0.0))

            rec = sim.get("recommendation", "")
            if rec == "DEFER":
                defer_count += 1
            elif rec == "ALLOW":
                allow_count += 1
            elif rec == "TAKE_BREAK":
                break_count += 1

        n = max(1, len(results))
        avg_preservation = round(total_preservation_sum / n, 1)
        total_saved_min = round(total_saved_sec / 60.0, 2)

        return {
            "userId": user_id,
            "totalSimulatedScenarios": len(results),
            "totalSavedDistractionSec": round(total_saved_sec, 1),
            "totalSavedDistractionMinutes": total_saved_min,
            "averageFocusPreservationPct": avg_preservation,
            "recommendationBreakdown": {
                "deferCount": defer_count,
                "allowCount": allow_count,
                "takeBreakCount": break_count,
            },
            "scenarios": results,
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }


# Global instances
counterfactual_engine = CounterfactualEngine()
notification_simulator = NotificationSimulator()
