"""
Session-connected Computer Vision Tracker (Phase 5).
Monitors attention classifier state changes during an active study session and records
aggregated events (DROWSINESS_STARTED, DROWSINESS_ENDED, FOCUS_LOST, FOCUS_REGAINED)
without sending raw camera frames or saving raw video.
"""
import time
import threading
from typing import Optional, Dict, Any
from db import database as db
from .attention_state_engine import AttentionStateEngine


class SessionCVTracker:
    def __init__(self):
        self.lock = threading.Lock()
        self.active_session: Optional[Dict[str, Any]] = None
        self.engine = AttentionStateEngine()
        self.last_signals: Dict[str, Any] = {}
        self.in_drowsy = False
        self.drowsy_start_time = 0.0
        self.in_focus_lost = False
        self.focus_lost_start_time = 0.0
        self.last_sync_time = 0.0
        self.override_until = 0.0
        self.override_state = None

    def set_session(self, session: Optional[Dict[str, Any]]) -> None:
        """Start or stop session CV monitoring."""
        with self.lock:
            if self.active_session and (not session or self.active_session.get("session_id") != session.get("session_id")):
                self._flush_active_states_locked()
            self.active_session = session
            self.in_drowsy = False
            self.in_focus_lost = False
            self.drowsy_start_time = 0.0
            self.focus_lost_start_time = 0.0
            self.override_until = 0.0
            self.override_state = None
            self.engine.reset()
            self.last_signals = {}
            if session is None:
                self.last_sync_time = time.time() + 5.0

    def set_override(self, state_name: str, hold_sec: float = 3.0, **kwargs) -> None:
        """Set a temporary override for testing/demo so live webcam does not overwrite immediately."""
        with self.lock:
            self.override_until = time.time() + hold_sec
            self.override_state = state_name
        self.update_state(state_name, is_simulation=True, **kwargs)

    def clear_override(self) -> None:
        """Clear simulation override and return to live camera feed."""
        with self.lock:
            self.override_until = 0.0
            self.override_state = None

    def sync_active_session(self) -> None:
        """Periodically verify if the active session is still active in database."""
        now = time.time()
        if now - self.last_sync_time < 2.0:
            return
        self.last_sync_time = now

        try:
            with self.lock:
                if self.active_session:
                    sess = db.get_session(self.active_session["session_id"])
                    if not sess or sess.get("status") != "ACTIVE":
                        self._flush_active_states_locked()
                        self.active_session = None
        except Exception:
            pass

    def update_state(
        self,
        attn_state: str,
        confidence: float = 1.0,
        ear: float = 0.0,
        blink_rate: int = 0,
        gaze: str = "center",
        yaw: float = 0.0,
        pitch: float = 0.0,
        roll: float = 0.0,
        source: str = "computer_vision",
        is_simulation: bool = False,
    ) -> None:
        """
        Evaluate CV state transition for the current active study session.
        If no study session is active, no events are recorded.
        """
        now = time.time()
        with self.lock:
            # If camera frame arrives during an active simulation override, ignore it
            if not is_simulation and now < self.override_until:
                return

            if not self.active_session or not self.active_session.get("session_id"):
                return

            session_id = self.active_session["session_id"]
            user_id = self.active_session.get("user_id")
            now = time.time()

            # ── 1. Drowsiness Tracking ────────────────────────────────
            if attn_state == "Drowsy":
                if not self.in_drowsy:
                    self.in_drowsy = True
                    self.drowsy_start_time = now
                    try:
                        db.create_event(
                            session_id=session_id,
                            event_type="DROWSINESS_STARTED",
                            user_id=user_id,
                            duration=0.0,
                            metadata={
                                "source": source,
                                "ear": round(float(ear), 3),
                                "blinkRate": int(blink_rate),
                                "confidence": round(float(confidence), 2),
                            },
                        )
                    except Exception:
                        pass
            else:
                if self.in_drowsy:
                    drowsy_dur = max(0.0, now - self.drowsy_start_time)
                    self.in_drowsy = False
                    try:
                        db.create_event(
                            session_id=session_id,
                            event_type="DROWSINESS_ENDED",
                            user_id=user_id,
                            duration=round(drowsy_dur, 2),
                            metadata={
                                "source": source,
                                "ear": round(float(ear), 3),
                                "duration": round(drowsy_dur, 2),
                                "confidence": round(float(confidence), 2),
                                "nextState": attn_state,
                            },
                        )
                    except Exception:
                        pass

            # ── 2. Focus Loss Tracking ────────────────────────────────
            # Focus loss corresponds to distracted gaze/head pose or no face
            if attn_state in ("Distracted", "No Face"):
                if not self.in_focus_lost:
                    self.in_focus_lost = True
                    self.focus_lost_start_time = now
                    try:
                        db.create_event(
                            session_id=session_id,
                            event_type="FOCUS_LOST",
                            user_id=user_id,
                            duration=0.0,
                            metadata={
                                "source": source,
                                "reason": attn_state.lower().replace(" ", "_"),
                                "gaze": gaze,
                                "yaw": round(float(yaw), 1),
                                "pitch": round(float(pitch), 1),
                                "roll": round(float(roll), 1),
                                "confidence": round(float(confidence), 2),
                            },
                        )
                    except Exception:
                        pass
            elif attn_state == "Focused":
                if self.in_focus_lost:
                    lost_dur = max(0.0, now - self.focus_lost_start_time)
                    self.in_focus_lost = False
                    try:
                        db.create_event(
                            session_id=session_id,
                            event_type="FOCUS_REGAINED",
                            user_id=user_id,
                            duration=round(lost_dur, 2),
                            metadata={
                                "source": source,
                                "duration": round(lost_dur, 2),
                                "confidence": round(float(confidence), 2),
                            },
                        )
                    except Exception:
                        pass

            # ── 3. FocusTwin 5-State Attention Engine (Phase 2) ──────
            ear_dict = {"ear": float(ear), "blink_rate_per_min": int(blink_rate), "is_drowsy_by_closure": attn_state == "Drowsy"}
            gaze_dict = {"looking_away": gaze != "center" or attn_state == "Distracted"}
            pose_dict = {"yaw": float(yaw), "pitch": float(pitch), "roll": float(roll), "looking_away": abs(float(yaw)) > 20.0 or attn_state == "Distracted"}

            override_5 = None
            if self.override_state in AttentionStateEngine.ALL_STATES:
                override_5 = self.override_state
            elif attn_state == "Drowsy":
                override_5 = AttentionStateEngine.STATE_FATIGUE_RISK
            elif attn_state in ("Distracted", "No Face"):
                override_5 = AttentionStateEngine.STATE_DISTRACTED

            st_5, conf_5, sigs_5, emitted_evt = self.engine.evaluate(
                ear_stats=ear_dict,
                gaze_stats=gaze_dict,
                pose_stats=pose_dict,
                face_detected=attn_state != "No Face",
                override_state=override_5,
            )
            self.last_signals = sigs_5

            if emitted_evt:
                try:
                    db.create_event(
                        session_id=session_id,
                        event_type=emitted_evt,
                        user_id=user_id,
                        duration=0.0,
                        metadata={
                            "source": source,
                            "attentionState": st_5,
                            "confidence": conf_5,
                            "reason": sigs_5.get("reason"),
                            "ear": round(float(ear), 3),
                            "yaw": round(float(yaw), 1),
                        }
                    )
                except Exception:
                    pass

    def notify_tab_switch(self, is_active_tab: bool, app_name: Optional[str] = None, classification: Optional[str] = None) -> None:
        with self.lock:
            self.engine.notify_tab_switch(is_active_tab, app_name=app_name, classification=classification)

    def notify_window_focus(self, is_focused: bool) -> None:
        with self.lock:
            self.engine.notify_window_focus(is_focused)

    def _flush_active_states_locked(self) -> None:
        """Close out any running drowsiness or focus loss durations upon session termination."""
        if not self.active_session or not self.active_session.get("session_id"):
            return

        session_id = self.active_session["session_id"]
        user_id = self.active_session.get("user_id")
        now = time.time()

        if self.in_drowsy:
            drowsy_dur = max(0.0, now - self.drowsy_start_time)
            self.in_drowsy = False
            try:
                db.create_event(
                    session_id=session_id,
                    event_type="DROWSINESS_ENDED",
                    user_id=user_id,
                    duration=round(drowsy_dur, 2),
                    metadata={"source": "computer_vision", "reason": "session_ended"},
                )
            except Exception:
                pass

        if self.in_focus_lost:
            lost_dur = max(0.0, now - self.focus_lost_start_time)
            self.in_focus_lost = False
            try:
                db.create_event(
                    session_id=session_id,
                    event_type="FOCUS_REGAINED",
                    user_id=user_id,
                    duration=round(lost_dur, 2),
                    metadata={"source": "computer_vision", "reason": "session_ended"},
                )
            except Exception:
                pass

    def get_status(self) -> Dict[str, Any]:
        """Return the current CV monitoring state and 5-state Attention Digital Twin state."""
        with self.lock:
            return {
                "isMonitoring": self.active_session is not None,
                "activeSessionId": self.active_session.get("session_id") if self.active_session else None,
                "inDrowsy": self.in_drowsy,
                "inFocusLost": self.in_focus_lost,
                "attentionState": self.engine.current_state,
                "attentionSignals": self.last_signals,
            }
