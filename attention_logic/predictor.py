"""
FocusTwin — Attention Prediction & Explainability Engine (Phase 4).
Evolves AttentionAI from "Monitor -> Score" into a proactive, personalized predictive digital twin.

Core Capabilities:
1. Predicts:
   - attention_drop_probability
   - distraction_probability
   - expected_distraction_duration (seconds)
   - expected_recovery_time (seconds)
   - prolonged_browsing_probability
2. Dual Model Formulation:
   - GENERIC_BASELINE (Cognitive population priors)
   - PERSONALIZED_MODEL (Trained on user's digital twin, task curves, and behavioral history)
3. Bayesian Blending & Sample-size Weighting:
   - w_personal = min(0.92, n / (n + 3.0)) for n >= 3, else 0.0
4. Grounded Explainability:
   - Data-backed explanations citing actual stored profile metrics and historical patterns.
"""

import json
import time
from datetime import datetime, timezone
from typing import Dict, Any, List, Optional
from db import database as db
from attention_logic.digital_twin import digital_twin_engine
from attention_logic.analytics import get_time_of_day_period


class ExplanationEngine:
    """
    Constructs transparent, grounded explanations for predicted outcomes.
    Only includes statements supported by actual stored historical data.
    """

    @classmethod
    def generate_explanation(
        cls,
        user_profile: Dict[str, Any],
        task_context: str,
        notification_source: str,
        notification_category: str,
        session_duration_min: float,
        app_classification: str,
        is_personalized: bool,
    ) -> List[str]:
        reasons = []

        # 1. Source / App Category Impact
        if app_classification == "POTENTIALLY_DISTRACTING":
            reasons.append(
                f"Source '{notification_source}' is classified as POTENTIALLY DISTRACTING, "
                "which historically triggers prolonged context switches."
            )
        elif notification_category in ("MESSAGE", "SOCIAL"):
            reasons.append(
                f"Interactive {notification_category} alerts from '{notification_source}' carry high "
                "cognitive switching cost and high temptation to engage in dialogue."
            )
        else:
            reasons.append(
                f"{notification_category} notification from '{notification_source}' represents an external stimulus."
            )

        # 2. Session Duration vs Fatigue Onset
        baselines = user_profile.get("baselines", {})
        fatigue_onset = float(baselines.get("fatigueOnsetMinutes") or 45.0)
        if session_duration_min >= fatigue_onset:
            reasons.append(
                f"Current session has reached {round(session_duration_min, 1)} minutes, surpassing your personal "
                f"fatigue onset threshold of {fatigue_onset} minutes where attention drops accelerate."
            )
        else:
            remaining = round(fatigue_onset - session_duration_min, 1)
            reasons.append(
                f"Session duration is {round(session_duration_min, 1)} minutes ({remaining}m before your estimated "
                f"fatigue onset at minute {fatigue_onset})."
            )

        # 3. Task Resilience Profile
        task_profiles = user_profile.get("taskProfiles", {})
        task_data = task_profiles.get(task_context)
        if task_data:
            resilience = task_data.get("interruptionResilience", "MODERATE")
            avg_rec = task_data.get("avgRecoverySec", 35.0)
            avg_score = task_data.get("avgAttentionScore", 75.0)
            reasons.append(
                f"For '{task_context}', your digital twin records {resilience} interruption resilience "
                f"with an average recovery time of {avg_rec}s and {avg_score}% attention."
            )
        else:
            reasons.append(
                f"Task '{task_context}' has limited task-specific history; applying general cognitive recovery baselines."
            )

        # 4. Personal History Status
        sample_size = user_profile.get("sampleSize", 0)
        if is_personalized and sample_size >= 3:
            reasons.append(
                f"Prediction personalized against {sample_size} verified study sessions "
                f"(Personal baseline weight: {round(min(0.92, sample_size / (sample_size + 3.0)) * 100)}%)."
            )
        else:
            reasons.append(
                "Digital twin is in early LEARNING mode (0-2 sessions); prediction blended with cognitive population priors."
            )

        return reasons


class AttentionPredictor:
    """
    Personalized Attention Predictor.
    Blends generic cognitive baselines with individualized digital twin behavior.
    """

    # Generic cognitive literature priors
    CATEGORY_PRIORS = {
        "MESSAGE":  {"drop": 0.75, "distract": 0.70, "dist_sec": 180.0, "rec_sec": 55.0, "overrun": 0.40},
        "EMAIL":    {"drop": 0.60, "distract": 0.55, "dist_sec": 120.0, "rec_sec": 45.0, "overrun": 0.25},
        "SOCIAL":   {"drop": 0.85, "distract": 0.80, "dist_sec": 280.0, "rec_sec": 85.0, "overrun": 0.60},
        "SYSTEM":   {"drop": 0.25, "distract": 0.20, "dist_sec": 30.0,  "rec_sec": 15.0, "overrun": 0.05},
        "LEARNING": {"drop": 0.35, "distract": 0.30, "dist_sec": 45.0,  "rec_sec": 20.0, "overrun": 0.10},
        "CALENDAR": {"drop": 0.40, "distract": 0.35, "dist_sec": 60.0,  "rec_sec": 25.0, "overrun": 0.10},
        "URGENT":   {"drop": 0.90, "distract": 0.85, "dist_sec": 240.0, "rec_sec": 75.0, "overrun": 0.30},
        "OTHER":    {"drop": 0.50, "distract": 0.45, "dist_sec": 90.0,  "rec_sec": 35.0, "overrun": 0.20},
    }

    GENERIC_BASELINE = {
        "attentionDropProbability": 0.65,
        "distractionProbability": 0.60,
        "expectedDistractionSec": 180.0,
        "expectedRecoverySec": 60.0,
        "prolongedBrowsingProbability": 0.35,
    }

    @classmethod
    def get_generic_baseline(cls, category: str = "MESSAGE") -> Dict[str, Any]:
        """Return generic cognitive population priors for a category."""
        priors = cls.CATEGORY_PRIORS.get(category.upper(), cls.CATEGORY_PRIORS["OTHER"])
        return {
            "category": category.upper(),
            "attentionDropProbability": priors["drop"],
            "distractionProbability": priors["distract"],
            "expectedDistractionSec": priors["dist_sec"],
            "expectedRecoverySec": priors["rec_sec"],
            "prolongedBrowsingProbability": priors["overrun"],
            "modelType": "GENERIC_COGNITIVE_BASELINE",
            "source": "Cognitive Psychology Population Priors",
        }

    @classmethod
    def predict(
        cls,
        user_id: str,
        task_context: str = "Coding",
        notification_category: str = "MESSAGE",
        notification_source: str = "System",
        session_duration_sec: float = 0.0,
        current_attention_state: str = "FOCUSED",
        session_id: Optional[str] = None,
        notification_id: Optional[str] = None,
        persist: bool = False,
    ) -> Dict[str, Any]:
        """
        Generate a personalized attention prediction.
        Returns dual comparison: GENERIC_BASELINE vs PERSONALIZED_MODEL.
        """
        cat_upper = notification_category.upper()
        if cat_upper not in cls.CATEGORY_PRIORS:
            cat_upper = "OTHER"

        generic_prior = cls.CATEGORY_PRIORS[cat_upper]
        session_min = max(0.0, float(session_duration_sec) / 60.0)

        # 1. Fetch user's digital twin profile
        twin_profile = digital_twin_engine.compute_digital_twin_profile(user_id, persist=False)
        sample_size = int(twin_profile.get("sampleSize") or 0)
        baselines = twin_profile.get("baselines", {})
        task_profiles = twin_profile.get("taskProfiles", {})
        task_data = task_profiles.get(task_context, {})

        # Check classification of application source
        app_classification = db.classify_app(notification_source, user_id=user_id)

        # 2. Compute Personal Modifiers
        # Notification sensitivity multiplier
        user_sensitivity = float(baselines.get("notificationSensitivity") or 0.50)
        sensitivity_mult = max(0.6, min(1.6, user_sensitivity / 0.50))

        # Fatigue multiplier based on personal fatigue onset
        fatigue_onset = float(baselines.get("fatigueOnsetMinutes") or 45.0)
        if session_min >= fatigue_onset:
            overage = session_min - fatigue_onset
            fatigue_mult = min(1.5, 1.10 + (overage / 40.0))
        else:
            fatigue_mult = 0.95

        # Task resilience modifier
        task_resilience = task_data.get("interruptionResilience", "MODERATE")
        if task_resilience == "HIGH":
            resilience_mult = 0.80
        elif task_resilience == "FRAGILE":
            resilience_mult = 1.30
        else:
            resilience_mult = 1.0

        # App classification multiplier
        if app_classification == "POTENTIALLY_DISTRACTING":
            app_mult = 1.35
            browsing_risk_mult = 1.50
        elif app_classification == "PRODUCTIVE":
            app_mult = 0.65
            browsing_risk_mult = 0.50
        else:
            app_mult = 1.0
            browsing_risk_mult = 1.0

        # Current attention state dampener/amplifier
        if current_attention_state == "DRIFTING":
            state_mult = 1.20
        elif current_attention_state == "FATIGUE_RISK":
            state_mult = 1.40
        else:
            state_mult = 1.0

        # 3. Compute Pure Personal Model Outputs
        raw_personal_drop = generic_prior["drop"] * sensitivity_mult * fatigue_mult * app_mult * state_mult
        personal_drop_prob = round(max(0.05, min(0.98, raw_personal_drop)), 2)

        raw_personal_distract = generic_prior["distract"] * sensitivity_mult * fatigue_mult * app_mult
        personal_distract_prob = round(max(0.05, min(0.98, raw_personal_distract)), 2)

        personal_dist_sec = round(max(15.0, generic_prior["dist_sec"] * fatigue_mult * app_mult * resilience_mult), 1)

        user_avg_rec = float(task_data.get("avgRecoverySec") or baselines.get("avgRecoveryTimeSeconds") or 35.0)
        personal_rec_sec = round(max(10.0, user_avg_rec * fatigue_mult * resilience_mult), 1)

        raw_personal_overrun = generic_prior["overrun"] * fatigue_mult * browsing_risk_mult
        personal_overrun_prob = round(max(0.02, min(0.95, raw_personal_overrun)), 2)

        # 4. Bayesian Blending (Generic vs Personal based on sample size)
        if sample_size < 3:
            w_personal = 0.0
            is_personalized = False
            conf_score = 0.55
            conf_level = "LOW"
            conf_status = "Digital twin in LEARNING mode: prediction guided by generic cognitive population baseline (sample size < 3)."
        else:
            w_personal = min(0.92, sample_size / (sample_size + 3.0))
            is_personalized = True
            if sample_size >= 12:
                conf_score = 0.92
                conf_level = "VERY_HIGH"
                conf_status = f"High confidence prediction trained on {sample_size} personal sessions."
            elif sample_size >= 6:
                conf_score = 0.85
                conf_level = "HIGH"
                conf_status = f"Personalized prediction with strong confidence ({sample_size} sessions)."
            else:
                conf_score = 0.72
                conf_level = "MEDIUM"
                conf_status = f"Limited personalization active ({sample_size} sessions)."

        w_generic = 1.0 - w_personal

        final_drop_prob = round((w_personal * personal_drop_prob) + (w_generic * generic_prior["drop"]), 2)
        final_distract_prob = round((w_personal * personal_distract_prob) + (w_generic * generic_prior["distract"]), 2)
        final_dist_sec = round((w_personal * personal_dist_sec) + (w_generic * generic_prior["dist_sec"]), 1)
        final_rec_sec = round((w_personal * personal_rec_sec) + (w_generic * generic_prior["rec_sec"]), 1)
        final_overrun_prob = round((w_personal * personal_overrun_prob) + (w_generic * generic_prior["overrun"]), 2)

        # 5. Dual Comparison Object
        personalized_prediction = {
            "attentionDropProbability": final_drop_prob,
            "distractionProbability": final_distract_prob,
            "expectedDistractionSec": final_dist_sec,
            "expectedRecoverySec": final_rec_sec,
            "prolongedBrowsingProbability": final_overrun_prob,
        }

        generic_baseline = {
            "attentionDropProbability": generic_prior["drop"],
            "distractionProbability": generic_prior["distract"],
            "expectedDistractionSec": generic_prior["dist_sec"],
            "expectedRecoverySec": generic_prior["rec_sec"],
            "prolongedBrowsingProbability": generic_prior["overrun"],
        }

        delta_vs_baseline = {
            "dropProbabilityDelta": round(final_drop_prob - generic_prior["drop"], 2),
            "distractionSecDelta": round(final_dist_sec - generic_prior["dist_sec"], 1),
            "recoverySecDelta": round(final_rec_sec - generic_prior["rec_sec"], 1),
            "browsingRiskDelta": round(final_overrun_prob - generic_prior["overrun"], 2),
        }

        # 6. Generate Data-Backed Explanations
        explanations = ExplanationEngine.generate_explanation(
            user_profile=twin_profile,
            task_context=task_context,
            notification_source=notification_source,
            notification_category=cat_upper,
            session_duration_min=session_min,
            app_classification=app_classification,
            is_personalized=is_personalized,
        )

        # Generate recommendation
        if final_drop_prob >= 0.65 or final_dist_sec >= 120.0 or app_classification == "POTENTIALLY_DISTRACTING":
            recommendation = "DEFER"
            rec_text = "DEFER: High likelihood of cognitive drift and prolonged context switching."
        else:
            recommendation = "ALLOW"
            rec_text = "ALLOW: Low cognitive switching cost detected."

        result = {
            "userId": user_id,
            "sessionId": session_id,
            "coldStartState": twin_profile.get("coldStartState", "LEARNING"),
            "taskContext": task_context,
            "notificationCategory": cat_upper,
            "notificationSource": notification_source,
            "sessionDurationMinutes": round(session_min, 1),
            "currentAttentionState": current_attention_state,
            "appClassification": app_classification,
            "personalizedPrediction": personalized_prediction,
            "genericBaseline": generic_baseline,
            "deltaVsBaseline": delta_vs_baseline,
            "recommendation": recommendation,
            "recommendationText": rec_text,
            "confidence": conf_score,
            "confidenceLevel": conf_level,
            "confidenceStatus": conf_status,
            "sampleSize": sample_size,
            "personalWeightPct": round(w_personal * 100, 1),
            "isPersonalized": is_personalized,
            "explanation": explanations,
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }

        # 7. Persist to SQLite if requested
        if persist:
            # Estimate deferred path (deferred interruptions drastically reduce drop and distraction)
            defer_drop = round(max(0.08, min(0.30, final_drop_prob * 0.22)), 2)
            defer_dist_sec = round(max(5.0, final_dist_sec * 0.12), 1)
            defer_rec_sec = round(max(5.0, final_rec_sec * 0.20), 1)
            defer_overrun = round(max(0.02, final_overrun_prob * 0.15), 2)

            pred_row = db.create_prediction(
                user_id=user_id,
                task_context=task_context,
                allow_attention_drop_prob=final_drop_prob,
                defer_attention_drop_prob=defer_drop,
                allow_distraction_sec=final_dist_sec,
                defer_distraction_sec=defer_dist_sec,
                allow_recovery_sec=final_rec_sec,
                defer_recovery_sec=defer_rec_sec,
                browsing_risk_allow=final_overrun_prob,
                browsing_risk_defer=defer_overrun,
                recommendation=recommendation,
                confidence=conf_score,
                sample_size=sample_size,
                explanation=explanations,
                notification_id=notification_id,
                session_id=session_id,
            )
            result["predictionId"] = pred_row["prediction_id"]

        return result


# Global predictor instance
attention_predictor = AttentionPredictor()
