"""
FocusTwin — Closed-Loop Learning Engine (Phase 6).
PS 3.3 The Attention Digital Twin (CASCADE '26).

Implements the Complete Adaptive Twin Loop:
  Observation / Interruption
       ↓
  Prediction (Allow vs Defer)
       ↓
  Intervention / Recommendation
       ↓
  Actual Outcome Observed
       ↓
  Prediction Error Calculation
       ↓
  Digital Twin Model Update & Adaptation

Core Capabilities:
1. Outcome Recording:
   - Stores user's actual action (ALLOW, DEFER, DISMISSED) and measured durations.
   - Computes prediction error (|actual - predicted|) and accuracy percentage.
2. Online Adaptive Calibration:
   - Updates personal interruption sensitivity based on signed prediction errors.
   - Updates recovery time baselines using exponential moving averages.
   - Synchronizes adjustments with SQLite attention_profiles.
3. Closed-Loop Performance Analytics:
   - MAE (Mean Absolute Error), RMSE, and accuracy trends.
   - "LAST PREDICTION" dashboard telemetry widget support.
"""

import math
from datetime import datetime, timezone
from typing import Dict, Any, List, Optional

from db import database as db
from attention_logic.digital_twin import digital_twin_engine


class ClosedLoopEngine:
    """
    Closes the loop between prediction and reality:
    Prediction -> Intervention -> Actual Outcome -> Prediction Error -> Model Update.
    """

    @classmethod
    def record_outcome(
        cls,
        prediction_id: str,
        actual_action: str = "ALLOW",
        actual_distraction_sec: float = 0.0,
        actual_recovery_sec: Optional[float] = None,
        actual_attention_drop: Optional[float] = None,
        user_id: Optional[str] = None,
    ) -> Optional[Dict[str, Any]]:
        """
        Record actual outcome, compute error against prediction, and adapt digital twin model.
        """
        pred = db.get_prediction(prediction_id)
        if not pred:
            return None

        user_id = user_id or pred.get("user_id")
        action_upper = actual_action.upper().strip()
        actual_dist_sec = max(0.0, float(actual_distraction_sec))
        actual_rec_sec = float(actual_recovery_sec) if actual_recovery_sec is not None else None

        # 1. Retrieve Predicted Values for the Action Taken
        if action_upper == "ALLOW":
            predicted_dist_sec = float(pred.get("allow_distraction_sec", 180.0))
            predicted_rec_sec = float(pred.get("allow_recovery_sec", 60.0))
            predicted_drop_prob = float(pred.get("allow_attention_drop_prob", 0.70))
        else:
            # DEFER or DISMISSED
            predicted_dist_sec = float(pred.get("defer_distraction_sec", 15.0))
            predicted_rec_sec = float(pred.get("defer_recovery_sec", 10.0))
            predicted_drop_prob = float(pred.get("defer_attention_drop_prob", 0.10))

        # 2. Compute Prediction Errors
        distraction_error_sec = round(abs(actual_dist_sec - predicted_dist_sec), 2)
        signed_error_sec = round(actual_dist_sec - predicted_dist_sec, 2)

        if actual_rec_sec is not None:
            recovery_error_sec = round(abs(actual_rec_sec - predicted_rec_sec), 2)
        else:
            recovery_error_sec = None

        # Accuracy Score (0% to 100%)
        # Normalizes error relative to expected magnitude (minimum scale 30s)
        scale = max(30.0, predicted_dist_sec)
        error_ratio = distraction_error_sec / scale
        accuracy_pct = round(max(0.0, min(100.0, (1.0 - error_ratio) * 100.0)), 1)

        # 3. Update SQLite Prediction Record
        updated_pred = db.update_prediction_outcome(
            prediction_id=prediction_id,
            actual_action=action_upper,
            actual_distraction_sec=actual_dist_sec,
            actual_recovery_sec=actual_rec_sec,
        )

        # Sync linked notification if present
        notif_id = pred.get("notification_id")
        if notif_id:
            try:
                db.update_notification_outcome(
                    notification_id=notif_id,
                    action_taken=action_upper,
                    actual_distraction_sec=actual_dist_sec,
                    actual_recovery_sec=actual_rec_sec,
                )
            except Exception:
                pass

        # 4. Adaptive Learning & Model Calibration
        # Fetch current personal digital twin stored profile
        stored_profile = db.get_or_create_attention_profile(user_id)
        prev_sensitivity = float(stored_profile.get("notification_sensitivity") or 0.50)
        prev_recovery = float(stored_profile.get("avg_recovery_time") or 35.0)

        # Adapt notification sensitivity:
        # If user was distracted significantly longer than predicted, increase sensitivity factor
        # If user resisted distraction much better than predicted, decrease sensitivity factor
        learning_rate = 0.04
        if signed_error_sec > 20.0:
            sensitivity_delta = min(0.08, (signed_error_sec / 120.0) * learning_rate)
            new_sensitivity = round(min(0.95, prev_sensitivity + sensitivity_delta), 3)
            adaptation_rationale = (
                f"Distraction exceeded prediction by {abs(signed_error_sec)}s: "
                f"increased notification sensitivity from {prev_sensitivity} to {new_sensitivity}."
            )
        elif signed_error_sec < -20.0:
            sensitivity_delta = min(0.06, (abs(signed_error_sec) / 120.0) * learning_rate)
            new_sensitivity = round(max(0.15, prev_sensitivity - sensitivity_delta), 3)
            adaptation_rationale = (
                f"User resisted distraction {abs(signed_error_sec)}s faster than predicted: "
                f"lowered notification sensitivity from {prev_sensitivity} to {new_sensitivity}."
            )
        else:
            new_sensitivity = prev_sensitivity
            adaptation_rationale = (
                f"Prediction was highly accurate (error: {distraction_error_sec}s); baseline sensitivity retained at {prev_sensitivity}."
            )

        # Calibrate recovery time baseline via Exponential Smoothing if actual recovery was observed
        if actual_rec_sec is not None and actual_rec_sec > 0:
            new_recovery = round(0.80 * prev_recovery + 0.20 * actual_rec_sec, 1)
            recovery_delta = round(new_recovery - prev_recovery, 1)
        else:
            new_recovery = prev_recovery
            recovery_delta = 0.0

        # Persist adapted baselines directly to SQLite attention_profiles table
        try:
            db.update_attention_profile(
                user_id,
                notification_sensitivity=new_sensitivity,
                avg_recovery_time=new_recovery,
            )
        except Exception:
            pass

        # 5. Build Comprehensive Closed-Loop Result
        result = {
            "predictionId": prediction_id,
            "userId": user_id,
            "taskContext": pred.get("task_context", "General Study"),
            "actualAction": action_upper,
            "predictedDistractionSec": predicted_dist_sec,
            "predictedDistractionMinutes": round(predicted_dist_sec / 60.0, 2),
            "actualDistractionSec": actual_dist_sec,
            "actualDistractionMinutes": round(actual_dist_sec / 60.0, 2),
            "predictedRecoverySec": predicted_rec_sec,
            "actualRecoverySec": actual_rec_sec,
            "predictionErrorSec": distraction_error_sec,
            "signedErrorSec": signed_error_sec,
            "recoveryErrorSec": recovery_error_sec,
            "accuracyPct": accuracy_pct,
            # Adaptation summary
            "modelAdapted": new_sensitivity != prev_sensitivity or new_recovery != prev_recovery,
            "adaptation": {
                "previousSensitivity": prev_sensitivity,
                "updatedSensitivity": new_sensitivity,
                "newSensitivity": new_sensitivity,
                "sensitivityDelta": round(new_sensitivity - prev_sensitivity, 3),
                "previousRecoveryBaseline": prev_recovery,
                "updatedRecoveryBaseline": new_recovery,
                "recoveryDelta": recovery_delta,
                "rationale": adaptation_rationale,
            },
            # Formatted display for dashboard telemetry
            "display": {
                "title": "LAST PREDICTION",
                "predictedDistraction": f"{round(predicted_dist_sec / 60.0, 1)} min",
                "actualDistraction": f"{round(actual_dist_sec / 60.0, 1)} min",
                "predictionError": f"{int(round(distraction_error_sec))} sec",
                "accuracy": f"{accuracy_pct}%",
            },
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }

        return result

    @classmethod
    def get_learning_analytics(cls, user_id: str) -> Dict[str, Any]:
        """
        Aggregate closed-loop learning statistics and accuracy history for a user.
        """
        all_preds = db.list_predictions(user_id, limit=100)
        evaluated = [p for p in all_preds if p.get("actual_action") is not None]

        if not evaluated:
            return {
                "userId": user_id,
                "totalPredictionsEvaluated": 0,
                "meanAbsoluteErrorSec": 0.0,
                "rootMeanSquareErrorSec": 0.0,
                "averageAccuracyPct": 100.0,
                "lastPrediction": None,
                "recentEvaluations": [],
                "learningStatus": "LEARNING",
                "statusMessage": "No closed-loop evaluations recorded yet. Run simulations and record actual outcomes to train the twin.",
            }

        total_err = 0.0
        total_sq_err = 0.0
        accuracies = []
        recent = []

        for p in evaluated:
            err = float(p.get("prediction_error_sec") or 0.0)
            act_dist = float(p.get("actual_distraction_sec") or 0.0)
            action = p.get("actual_action", "ALLOW")
            pred_dist = float(p.get("allow_distraction_sec") if action == "ALLOW" else p.get("defer_distraction_sec"))

            total_err += err
            total_sq_err += (err ** 2)

            scale = max(30.0, pred_dist)
            acc = max(0.0, min(100.0, (1.0 - (err / scale)) * 100.0))
            accuracies.append(acc)

            recent.append({
                "predictionId": p.get("prediction_id"),
                "taskContext": p.get("task_context"),
                "actualAction": action,
                "predictedDistractionSec": pred_dist,
                "predictedDistractionMin": round(pred_dist / 60.0, 2),
                "actualDistractionSec": act_dist,
                "actualDistractionMin": round(act_dist / 60.0, 2),
                "predictionErrorSec": err,
                "accuracyPct": round(acc, 1),
                "createdAt": p.get("created_at"),
            })

        n = len(evaluated)
        mae = round(total_err / n, 2)
        rmse = round(math.sqrt(total_sq_err / n), 2)
        avg_acc = round(sum(accuracies) / n, 1)

        # Most recent evaluated prediction for the dashboard widget
        latest = evaluated[0]
        latest_act = latest.get("actual_action", "ALLOW")
        latest_act_dist = float(latest.get("actual_distraction_sec") or 0.0)
        latest_pred_dist = float(latest.get("allow_distraction_sec") if latest_act == "ALLOW" else latest.get("defer_distraction_sec"))
        latest_err = float(latest.get("prediction_error_sec") or 0.0)
        latest_scale = max(30.0, latest_pred_dist)
        latest_acc = round(max(0.0, min(100.0, (1.0 - (latest_err / latest_scale)) * 100.0)), 1)

        last_prediction_widget = {
            "predictionId": latest.get("prediction_id"),
            "taskContext": latest.get("task_context"),
            "actualAction": latest_act,
            "predictedDistraction": f"{round(latest_pred_dist / 60.0, 1)} min",
            "actualDistraction": f"{round(latest_act_dist / 60.0, 1)} min",
            "predictionError": f"{int(round(latest_err))} sec",
            "accuracyPct": latest_acc,
            "createdAt": latest.get("created_at"),
        }

        return {
            "userId": user_id,
            "totalPredictionsEvaluated": n,
            "meanAbsoluteErrorSec": mae,
            "rootMeanSquareErrorSec": rmse,
            "averageAccuracyPct": avg_acc,
            "lastPrediction": last_prediction_widget,
            "recentEvaluations": recent[:10],
            "learningStatus": "ACTIVE_LEARNING" if n >= 3 else "CALIBRATING",
            "statusMessage": f"Twin has evaluated {n} live interruption outcomes with average MAE of {mae}s and {avg_acc}% accuracy.",
        }


# Global instance
closed_loop_engine = ClosedLoopEngine()
