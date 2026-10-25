"""
FocusTwin — Attention State Engine (Phase 2).
Combines multi-modal inputs:
- Eye Aspect Ratio (EAR) & Blink Rate
- 3D Head Pose (Yaw, Pitch, Roll)
- Gaze Direction & Off-center ratio
- Tab Switching & Page Visibility
- Application Switching & App Categories
- Session Duration & Task Context

Produces 5 Discrete Attention States:
1. FOCUSED       - Aligned gaze, healthy EAR, active on-task presence
2. DRIFTING      - Mild gaze deviation, intermittent glances, early attention waver
3. DISTRACTED    - Context switch, off-task app/tab, severe head turn or face absent
4. RECOVERING    - Transitory post-interruption stabilization period
5. FATIGUE_RISK  - Prolonged session fatigue pattern or severe drowsiness
"""

import time
from collections import deque
from typing import Dict, Any, Tuple, Optional


class AttentionStateEngine:
    # State constants
    STATE_FOCUSED      = "FOCUSED"
    STATE_DRIFTING      = "DRIFTING"
    STATE_DISTRACTED    = "DISTRACTED"
    STATE_RECOVERING    = "RECOVERING"
    STATE_FATIGUE_RISK  = "FATIGUE_RISK"

    ALL_STATES = [
        STATE_FOCUSED,
        STATE_DRIFTING,
        STATE_DISTRACTED,
        STATE_RECOVERING,
        STATE_FATIGUE_RISK,
    ]

    def __init__(self, history_len: int = 30, recovery_window_sec: float = 8.0):
        self.history_len = history_len
        self.state_history = deque(maxlen=history_len)
        self.current_state = self.STATE_FOCUSED
        self.previous_state = self.STATE_FOCUSED
        self.confidence = 1.0

        # Timing & Durations
        self.session_start = time.time()
        self.last_tick_time = time.time()
        self.state_durations = {s: 0.0 for s in self.ALL_STATES}

        # Context & Edge signals
        self.is_tab_active = True
        self.is_window_focused = True
        self.current_app = "VS Code"
        self.current_app_classification = "PRODUCTIVE"
        self.current_task = "Coding"
        self.fatigue_onset_sec = 50.0 * 60.0  # 50 minutes default

        # Recovery tracking
        self.in_recovery = False
        self.recovery_start_time = 0.0
        self.recovery_window_sec = recovery_window_sec
        self.consecutive_focused_frames = 0

        # Interruption & Drift counters
        self.interruption_count = 0
        self.drift_count = 0
        self.fatigue_event_emitted = False

    def notify_tab_switch(self, is_active_tab: bool, app_name: Optional[str] = None, classification: Optional[str] = None) -> None:
        """Called when browser extension or Page Visibility reports tab/app change."""
        self.is_tab_active = bool(is_active_tab)
        if app_name:
            self.current_app = app_name
        if classification:
            self.current_app_classification = classification
        elif not is_active_tab:
            self.current_app_classification = "POTENTIALLY_DISTRACTING"
        else:
            self.current_app_classification = "PRODUCTIVE"

        if not self.is_tab_active:
            self.interruption_count += 1
            self.in_recovery = False
            self.consecutive_focused_frames = 0
        else:
            # User returned to active study tab -> trigger RECOVERING state
            self.in_recovery = True
            self.recovery_start_time = time.time()
            self.consecutive_focused_frames = 0

    def notify_window_focus(self, is_focused: bool) -> None:
        """Called when browser window gains or loses focus."""
        self.is_window_focused = bool(is_focused)
        if not is_focused:
            self.in_recovery = False
        else:
            self.in_recovery = True
            self.recovery_start_time = time.time()
            self.consecutive_focused_frames = 0

    def evaluate(
        self,
        ear_stats: Optional[Dict[str, Any]] = None,
        gaze_stats: Optional[Dict[str, Any]] = None,
        pose_stats: Optional[Dict[str, Any]] = None,
        face_detected: bool = True,
        override_state: Optional[str] = None,
        session_duration_sec: Optional[float] = None,
    ) -> Tuple[str, float, Dict[str, Any], Optional[str]]:
        """
        Evaluate and return:
        (current_state, confidence, signals_dict, optional_emitted_event_type)
        """
        now = time.time()
        dt = max(0.0, now - self.last_tick_time)
        self.last_tick_time = now

        ear_stats = ear_stats or {}
        gaze_stats = gaze_stats or {}
        pose_stats = pose_stats or {}

        # ── 1. Signal Extraction ──────────────────────────────────────
        ear = float(ear_stats.get("ear", 0.3))
        blink_rate = int(ear_stats.get("blink_rate_per_min", 15))
        is_drowsy_closure = bool(ear_stats.get("is_drowsy_by_closure", False))
        gaze_away = bool(gaze_stats.get("looking_away", False))
        head_away = bool(pose_stats.get("looking_away", False))
        yaw = abs(float(pose_stats.get("yaw", 0.0)))
        pitch = abs(float(pose_stats.get("pitch", 0.0)))

        session_dur = float(session_duration_sec if session_duration_sec is not None else (now - self.session_start))

        # ── 2. Determine Raw State ────────────────────────────────────
        raw_state = self.STATE_FOCUSED
        reason = "aligned_gaze"
        emitted_event = None

        if override_state and override_state in self.ALL_STATES:
            raw_state = override_state
            reason = "manual_simulation_override"

        # Check Distracted triggers first (tab away, app away, face gone, extreme head pose)
        elif not self.is_tab_active or not self.is_window_focused or self.current_app_classification == "POTENTIALLY_DISTRACTING":
            raw_state = self.STATE_DISTRACTED
            reason = "off_task_tab_or_app"
            self.in_recovery = False

        elif not face_detected or yaw > 28.0 or pitch > 30.0:
            raw_state = self.STATE_DISTRACTED
            reason = "no_face_or_severe_head_away"
            self.in_recovery = False

        # Check Fatigue Risk triggers (sustained eye closure, high blink rate, or prolonged duration fatigue)
        elif is_drowsy_closure or blink_rate >= 32 or (session_dur >= self.fatigue_onset_sec and ear < 0.20):
            raw_state = self.STATE_FATIGUE_RISK
            reason = "drowsy_closure_or_prolonged_fatigue"
            self.in_recovery = False

        # Check Recovery window (user recently returned, stabilizing attention)
        elif self.in_recovery:
            elapsed_recovery = now - self.recovery_start_time
            if not gaze_away and not head_away and face_detected:
                self.consecutive_focused_frames += 1

            if elapsed_recovery >= self.recovery_window_sec or self.consecutive_focused_frames >= 20:
                self.in_recovery = False
                raw_state = self.STATE_FOCUSED
                reason = "stabilization_complete"
            else:
                raw_state = self.STATE_RECOVERING
                reason = "stabilizing_after_interruption"

        # Check Drifting (mild gaze off-center, yaw between 15° and 25°, slight eye closure)
        elif gaze_away or (15.0 < yaw <= 28.0) or (0.18 <= ear < 0.21):
            raw_state = self.STATE_DRIFTING
            reason = "mild_gaze_or_head_drift"

        else:
            raw_state = self.STATE_FOCUSED
            reason = "active_focused_learning"

        # ── 3. Majority Voting & Smoothing ────────────────────────────
        self.state_history.append(raw_state)
        from collections import Counter
        counts = Counter(self.state_history)
        smoothed_state = counts.most_common(1)[0][0]
        self.confidence = round(float(counts[smoothed_state] / len(self.state_history)), 2)

        # ── 4. State Transition & Event Emission ───────────────────────
        if smoothed_state != self.current_state:
            old_state = self.current_state
            self.previous_state = old_state
            self.current_state = smoothed_state

            if smoothed_state == self.STATE_DRIFTING:
                self.drift_count += 1
                emitted_event = "ATTENTION_DRIFT"
            elif smoothed_state == self.STATE_DISTRACTED:
                emitted_event = "ATTENTION_DROP"
            elif smoothed_state == self.STATE_FATIGUE_RISK and not self.fatigue_event_emitted:
                self.fatigue_event_emitted = True
                emitted_event = "FATIGUE_PATTERN_DETECTED"
            elif smoothed_state == self.STATE_FOCUSED:
                if old_state == self.STATE_RECOVERING:
                    emitted_event = "ATTENTION_RECOVERED"
                else:
                    emitted_event = "ATTENTION_FOCUSED"
        elif smoothed_state != self.STATE_FATIGUE_RISK:
            self.fatigue_event_emitted = False

        # Accumulate durations
        self.state_durations[self.current_state] += dt

        signals = {
            "state": self.current_state,
            "rawState": raw_state,
            "reason": reason,
            "confidence": self.confidence,
            "ear": round(ear, 3),
            "blinkRate": blink_rate,
            "yaw": round(yaw, 1),
            "pitch": round(pitch, 1),
            "gazeAway": gaze_away,
            "isTabActive": self.is_tab_active,
            "isWindowFocused": self.is_window_focused,
            "currentApp": self.current_app,
            "appClassification": self.current_app_classification,
            "inRecovery": self.in_recovery,
            "consecutiveFocusedFrames": self.consecutive_focused_frames,
            "interruptionCount": self.interruption_count,
            "driftCount": self.drift_count,
            "sessionDurationSec": round(session_dur, 1),
            "legacyState": self.map_to_legacy_state(self.current_state),
        }

        return self.current_state, self.confidence, signals, emitted_event

    @staticmethod
    def map_to_legacy_state(state: str) -> str:
        """Map 5-state attention to backwards-compatible 3-state output."""
        if state == AttentionStateEngine.STATE_FATIGUE_RISK:
            return "Drowsy"
        elif state in (AttentionStateEngine.STATE_DISTRACTED, AttentionStateEngine.STATE_DRIFTING):
            return "Distracted"
        return "Focused"

    def get_summary(self) -> Dict[str, Any]:
        """Return state durations and distribution percentages."""
        total = sum(self.state_durations.values())
        if total <= 0:
            return {s: {"seconds": 0.0, "pct": 0.0} for s in self.ALL_STATES}
        return {
            s: {
                "seconds": round(self.state_durations[s], 1),
                "pct": round((self.state_durations[s] / total) * 100.0, 1),
            }
            for s in self.ALL_STATES
        }

    def reset(self) -> None:
        """Reset state tracking for new session."""
        self.state_history.clear()
        self.current_state = self.STATE_FOCUSED
        self.previous_state = self.STATE_FOCUSED
        self.confidence = 1.0
        self.session_start = time.time()
        self.last_tick_time = time.time()
        self.state_durations = {s: 0.0 for s in self.ALL_STATES}
        self.in_recovery = False
        self.recovery_start_time = 0.0
        self.consecutive_focused_frames = 0
        self.interruption_count = 0
        self.drift_count = 0
        self.fatigue_event_emitted = False
