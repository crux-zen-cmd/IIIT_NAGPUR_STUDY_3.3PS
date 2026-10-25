"""
Database layer for Study Session Productivity Monitoring System.
Uses SQLite with thread-safe connection management and WAL mode.
"""

import sqlite3
import os
import json
import uuid
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Optional, List, Dict, Any

DATA_DIR = Path(__file__).parent.parent / "data"
DATA_DIR.mkdir(exist_ok=True)
DB_PATH = DATA_DIR / "study_tracker.db"


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def get_connection() -> sqlite3.Connection:
    conn = sqlite3.connect(str(DB_PATH), check_same_thread=False, timeout=15.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute("PRAGMA foreign_keys=ON;")
    return conn


def init_db():
    with get_connection() as conn:
        conn.executescript("""
            CREATE TABLE IF NOT EXISTS users (
                user_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                email TEXT NOT NULL UNIQUE,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS study_sessions (
                session_id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                course_name TEXT NOT NULL,
                platform TEXT NOT NULL,
                course_url TEXT,
                start_time TEXT NOT NULL,
                end_time TEXT,
                duration REAL DEFAULT 0.0,
                status TEXT NOT NULL CHECK(status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
                created_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS session_events (
                event_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL REFERENCES study_sessions(session_id) ON DELETE CASCADE,
                user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                timestamp TEXT NOT NULL,
                event_type TEXT NOT NULL,
                duration REAL DEFAULT 0.0,
                metadata TEXT NOT NULL DEFAULT '{}'
            );

            -- ── FocusTwin Digital Twin Data Foundation (Phase 1) ───────────

            CREATE TABLE IF NOT EXISTS attention_profiles (
                user_id TEXT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
                cold_start_state TEXT NOT NULL DEFAULT 'LEARNING',
                confidence_level TEXT NOT NULL DEFAULT 'LOW',
                sample_size INTEGER DEFAULT 0,
                best_attention_window TEXT,
                avg_focus_duration REAL DEFAULT 0.0,
                avg_recovery_time REAL DEFAULT 0.0,
                notification_sensitivity REAL DEFAULT 0.5,
                fatigue_onset_minutes REAL DEFAULT 45.0,
                task_profiles_json TEXT NOT NULL DEFAULT '{}',
                app_fragmentation_json TEXT NOT NULL DEFAULT '{}',
                updated_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS task_contexts (
                context_id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                task_name TEXT NOT NULL,
                avg_attention_score REAL DEFAULT 0.0,
                avg_recovery_time REAL DEFAULT 0.0,
                total_sessions INTEGER DEFAULT 0,
                created_at TEXT NOT NULL,
                UNIQUE(user_id, task_name)
            );

            CREATE TABLE IF NOT EXISTS app_categories (
                category_id TEXT PRIMARY KEY,
                user_id TEXT REFERENCES users(user_id) ON DELETE CASCADE,
                app_name TEXT NOT NULL,
                classification TEXT NOT NULL CHECK(classification IN ('PRODUCTIVE', 'NEUTRAL', 'POTENTIALLY_DISTRACTING')),
                created_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS notifications (
                notification_id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                session_id TEXT REFERENCES study_sessions(session_id) ON DELETE SET NULL,
                timestamp TEXT NOT NULL,
                source TEXT NOT NULL,
                category TEXT NOT NULL,
                importance TEXT NOT NULL DEFAULT 'NORMAL',
                task_at_time TEXT NOT NULL,
                attention_at_time REAL DEFAULT 1.0,
                action_taken TEXT,
                actual_distraction_sec REAL,
                actual_recovery_sec REAL,
                metadata TEXT NOT NULL DEFAULT '{}',
                created_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS predictions (
                prediction_id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                session_id TEXT REFERENCES study_sessions(session_id) ON DELETE SET NULL,
                notification_id TEXT REFERENCES notifications(notification_id) ON DELETE CASCADE,
                task_context TEXT NOT NULL,
                allow_attention_drop_prob REAL NOT NULL,
                defer_attention_drop_prob REAL NOT NULL,
                allow_distraction_sec REAL NOT NULL,
                defer_distraction_sec REAL NOT NULL,
                allow_recovery_sec REAL NOT NULL,
                defer_recovery_sec REAL NOT NULL,
                browsing_risk_allow REAL NOT NULL,
                browsing_risk_defer REAL NOT NULL,
                recommendation TEXT NOT NULL,
                confidence REAL NOT NULL,
                sample_size INTEGER NOT NULL,
                explanation_json TEXT NOT NULL DEFAULT '[]',
                actual_action TEXT,
                actual_distraction_sec REAL,
                prediction_error_sec REAL,
                created_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS interventions (
                intervention_id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                session_id TEXT REFERENCES study_sessions(session_id) ON DELETE SET NULL,
                type TEXT NOT NULL,
                reason TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'RECOMMENDED' CHECK(status IN ('RECOMMENDED', 'ACCEPTED', 'REJECTED', 'EXPIRED')),
                created_at TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_sessions_user_status ON study_sessions(user_id, status);
            CREATE INDEX IF NOT EXISTS idx_sessions_start_time ON study_sessions(start_time);
            CREATE INDEX IF NOT EXISTS idx_events_session_ts ON session_events(session_id, timestamp);
            CREATE INDEX IF NOT EXISTS idx_events_user ON session_events(user_id);
            CREATE INDEX IF NOT EXISTS idx_events_type ON session_events(event_type);
            CREATE INDEX IF NOT EXISTS idx_notifications_user_ts ON notifications(user_id, timestamp);
            CREATE INDEX IF NOT EXISTS idx_predictions_user ON predictions(user_id);
            CREATE INDEX IF NOT EXISTS idx_interventions_user ON interventions(user_id);
        """)

        # Non-destructive migration: add task_type column to study_sessions if missing
        try:
            conn.execute("ALTER TABLE study_sessions ADD COLUMN task_type TEXT DEFAULT 'General Study';")
        except Exception:
            pass

        # Non-destructive migration: add actual_recovery_sec column to predictions if missing
        try:
            conn.execute("ALTER TABLE predictions ADD COLUMN actual_recovery_sec REAL;")
        except Exception:
            pass

        # Non-destructive migration: add is_demo column for Phase 8 Demo Hardening
        try:
            conn.execute("ALTER TABLE users ADD COLUMN is_demo INTEGER DEFAULT 0;")
        except Exception:
            pass

        try:
            conn.execute("ALTER TABLE study_sessions ADD COLUMN is_demo INTEGER DEFAULT 0;")
        except Exception:
            pass

        # Ensure default user exists
        cur = conn.cursor()
        cur.execute("SELECT user_id FROM users LIMIT 1")
        row = cur.fetchone()
        if not row:
            default_id = f"usr_{uuid.uuid4().hex[:8]}"
            now = _now_iso()
            cur.execute("""
                INSERT INTO users (user_id, name, email, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
            """, (default_id, "Default Scholar", "scholar@studytracker.local", now, now))
            conn.commit()
            user_id = default_id
        else:
            user_id = row["user_id"]

        # Seed default app categories if empty
        cur.execute("SELECT COUNT(*) as count FROM app_categories")
        if cur.fetchone()["count"] == 0:
            defaults = [
                ("VS Code", "PRODUCTIVE"),
                ("PyCharm", "PRODUCTIVE"),
                ("IntelliJ", "PRODUCTIVE"),
                ("GitHub", "PRODUCTIVE"),
                ("Coursera", "PRODUCTIVE"),
                ("edX", "PRODUCTIVE"),
                ("Stack Overflow", "PRODUCTIVE"),
                ("ChatGPT", "PRODUCTIVE"),
                ("Google Docs", "PRODUCTIVE"),
                ("Terminal", "PRODUCTIVE"),
                ("Google Search", "NEUTRAL"),
                ("Gmail", "NEUTRAL"),
                ("Slack", "NEUTRAL"),
                ("Calendar", "NEUTRAL"),
                ("Zoom", "NEUTRAL"),
                ("Discord", "POTENTIALLY_DISTRACTING"),
                ("Instagram", "POTENTIALLY_DISTRACTING"),
                ("Reddit", "POTENTIALLY_DISTRACTING"),
                ("Twitter", "POTENTIALLY_DISTRACTING"),
                ("YouTube Shorts", "POTENTIALLY_DISTRACTING"),
                ("Netflix", "POTENTIALLY_DISTRACTING"),
                ("Twitch", "POTENTIALLY_DISTRACTING"),
            ]
            now = _now_iso()
            for app, cat in defaults:
                cat_id = f"cat_{uuid.uuid4().hex[:8]}"
                cur.execute("""
                    INSERT INTO app_categories (category_id, user_id, app_name, classification, created_at)
                    VALUES (?, NULL, ?, ?, ?)
                """, (cat_id, app, cat, now))
            conn.commit()

        # Seed default task contexts for default user if empty
        cur.execute("SELECT COUNT(*) as count FROM task_contexts WHERE user_id = ?", (user_id,))
        if cur.fetchone()["count"] == 0:
            default_tasks = ["Coding", "Coursera Lecture", "Reading", "Assignment", "Research", "Writing", "YouTube Learning"]
            now = _now_iso()
            for t in default_tasks:
                ctx_id = f"ctx_{uuid.uuid4().hex[:8]}"
                cur.execute("""
                    INSERT INTO task_contexts (context_id, user_id, task_name, avg_attention_score, avg_recovery_time, total_sessions, created_at)
                    VALUES (?, ?, ?, 0.0, 0.0, 0, ?)
                """, (ctx_id, user_id, t, now))
            conn.commit()


# ── User Operations ──────────────────────────────────────────────────

def create_user(name: str, email: str, user_id: Optional[str] = None) -> Dict[str, Any]:
    if not user_id:
        user_id = f"usr_{uuid.uuid4().hex[:8]}"
    now = _now_iso()
    with get_connection() as conn:
        cur = conn.cursor()
        cur.execute("""
            INSERT INTO users (user_id, name, email, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
        """, (user_id, name.strip(), email.strip().lower(), now, now))
        conn.commit()
    return get_user(user_id)


def update_user(user_id: str, name: Optional[str] = None, email: Optional[str] = None) -> Optional[Dict[str, Any]]:
    existing = get_user(user_id)
    if not existing:
        return None
    new_name = name.strip() if name else existing["name"]
    new_email = email.strip().lower() if email else existing["email"]
    now = _now_iso()
    with get_connection() as conn:
        conn.execute("""
            UPDATE users
            SET name = ?, email = ?, updated_at = ?
            WHERE user_id = ?
        """, (new_name, new_email, now, user_id))
        conn.commit()
    return get_user(user_id)


def _format_user(d: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    if not d:
        return None
    res = dict(d)
    res["userId"] = res.get("user_id")
    res["createdAt"] = res.get("created_at")
    res["updatedAt"] = res.get("updated_at")
    return res


def get_user(user_id: str) -> Optional[Dict[str, Any]]:
    with get_connection() as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM users WHERE user_id = ?", (user_id,))
        row = cur.fetchone()
        return _format_user(dict(row)) if row else None


def get_user_by_email(email: str) -> Optional[Dict[str, Any]]:
    with get_connection() as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM users WHERE lower(email) = ?", (email.strip().lower(),))
        row = cur.fetchone()
        return _format_user(dict(row)) if row else None


def list_users() -> List[Dict[str, Any]]:
    with get_connection() as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM users ORDER BY created_at ASC")
        return [_format_user(dict(r)) for r in cur.fetchall()]


# ── Study Session Operations ─────────────────────────────────────────

def get_active_session(user_id: Optional[str] = None) -> Optional[Dict[str, Any]]:
    with get_connection() as conn:
        cur = conn.cursor()
        if user_id:
            cur.execute("""
                SELECT * FROM study_sessions
                WHERE user_id = ? AND status = 'ACTIVE'
                ORDER BY start_time DESC LIMIT 1
            """, (user_id,))
        else:
            cur.execute("""
                SELECT * FROM study_sessions
                WHERE status = 'ACTIVE'
                ORDER BY start_time DESC LIMIT 1
            """)
        row = cur.fetchone()
        return dict(row) if row else None


def start_session(
    user_id: str,
    course_name: str,
    platform: str,
    course_url: Optional[str] = None,
    task_type: Optional[str] = None,
) -> Dict[str, Any]:
    # 1. Validate user
    user = get_user(user_id)
    if not user:
        raise ValueError(f"User not found with ID '{user_id}'")

    # Prevent multiple active sessions for this user
    active = get_active_session(user_id)
    if active:
        raise ValueError(f"User '{user_id}' already has an active session: {active['session_id']}")

    # 2. Determine / infer task context
    if not task_type:
        c_lower = (course_name or "").lower()
        p_lower = (platform or "").lower()
        if any(k in c_lower for k in ["coding", "python", "cs50", "code", "programming", "software"]):
            task_type = "Coding"
        elif "reading" in c_lower or "paper" in c_lower or "book" in c_lower:
            task_type = "Reading"
        elif "writing" in c_lower or "essay" in c_lower:
            task_type = "Writing"
        elif "research" in c_lower:
            task_type = "Research"
        elif "youtube" in p_lower:
            task_type = "YouTube Learning"
        elif "coursera" in p_lower:
            task_type = "Coursera Lecture"
        else:
            task_type = "General Study"

    # 3. Generate sessionId
    session_id = f"sess_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:4]}"
    now = _now_iso()

    # 4. Save current timestamp, status = ACTIVE, course/platform/task info
    with get_connection() as conn:
        conn.execute("""
            INSERT INTO study_sessions (
                session_id, user_id, course_name, platform, course_url,
                start_time, end_time, duration, status, task_type, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, NULL, 0.0, 'ACTIVE', ?, ?)
        """, (
            session_id,
            user_id,
            course_name.strip() or "General Study",
            platform.strip() or "Coursera",
            (course_url.strip() if course_url else None),
            now,
            task_type,
            now,
        ))
        conn.commit()

    return get_session(session_id)


def get_session_metrics(session_id: str) -> Dict[str, Any]:
    """Calculate aggregate session metrics across Tab Switching, Drowsiness, and Focus Loss."""
    with get_connection() as conn:
        cur = conn.cursor()

        # 1. Tab switches
        cur.execute("""
            SELECT COUNT(*) FROM session_events
            WHERE session_id = ? AND event_type IN ('TAB_SWITCH', 'PAGE_HIDDEN')
        """, (session_id,))
        tab_switch_count = int(cur.fetchone()[0])

        # 2. Total Tab distraction duration
        cur.execute("""
            SELECT COALESCE(SUM(duration), 0.0) FROM session_events
            WHERE session_id = ? AND duration > 0 AND (
                event_type IN ('PAGE_VISIBLE', 'TAB_SWITCH')
                OR (
                    event_type = 'FOCUS_REGAINED' AND (
                        json_extract(metadata, '$.source') IN ('chrome_extension_tabs', 'page_visibility_api')
                        OR json_extract(metadata, '$.distractionDuration') IS NOT NULL
                        OR json_extract(metadata, '$.previousTab') IS NOT NULL
                        OR json_extract(metadata, '$.previousUrl') IS NOT NULL
                    )
                )
            )
        """, (session_id,))
        total_distraction_duration = round(float(cur.fetchone()[0]), 2)

        # 3. Drowsiness event count
        cur.execute("""
            SELECT COUNT(*) FROM session_events
            WHERE session_id = ? AND event_type IN ('DROWSINESS_STARTED', 'DROWSINESS')
        """, (session_id,))
        drowsy_started_count = int(cur.fetchone()[0])

        cur.execute("""
            SELECT COUNT(*) FROM session_events
            WHERE session_id = ? AND event_type = 'DROWSINESS_ENDED'
        """, (session_id,))
        drowsy_ended_count = int(cur.fetchone()[0])
        drowsiness_event_count = max(drowsy_started_count, drowsy_ended_count)

        # 4. Total Drowsiness duration
        cur.execute("""
            SELECT COALESCE(SUM(duration), 0.0) FROM session_events
            WHERE session_id = ? AND event_type = 'DROWSINESS_ENDED' AND duration > 0
        """, (session_id,))
        total_drowsiness_duration = round(float(cur.fetchone()[0]), 2)

        # 5. Focus loss event count
        cur.execute("""
            SELECT COUNT(*) FROM session_events
            WHERE session_id = ? AND event_type = 'FOCUS_LOST'
        """, (session_id,))
        focus_loss_event_count = int(cur.fetchone()[0])

        # 6. Total Focus lost duration
        cur.execute("""
            SELECT COALESCE(SUM(duration), 0.0) FROM session_events
            WHERE session_id = ? AND event_type = 'FOCUS_REGAINED' AND duration > 0 AND (
                json_extract(metadata, '$.source') = 'computer_vision'
                OR (
                    json_extract(metadata, '$.distractionDuration') IS NULL
                    AND json_extract(metadata, '$.previousTab') IS NULL
                    AND json_extract(metadata, '$.previousUrl') IS NULL
                    AND (json_extract(metadata, '$.source') IS NULL OR json_extract(metadata, '$.source') NOT IN ('chrome_extension_tabs', 'page_visibility_api'))
                    AND EXISTS (SELECT 1 FROM session_events e3 WHERE e3.session_id = ? AND e3.event_type = 'FOCUS_LOST')
                )
            )
        """, (session_id, session_id))
        total_focus_lost_duration = round(float(cur.fetchone()[0]), 2)

        return {
            "tab_switch_count": tab_switch_count,
            "tabSwitchCount": tab_switch_count,
            "total_distraction_duration": total_distraction_duration,
            "totalDistractionDuration": total_distraction_duration,
            "drowsiness_event_count": drowsiness_event_count,
            "drowsinessEventCount": drowsiness_event_count,
            "total_drowsiness_duration": total_drowsiness_duration,
            "totalDrowsinessDuration": total_drowsiness_duration,
            "focus_loss_event_count": focus_loss_event_count,
            "focusLossEventCount": focus_loss_event_count,
            "total_focus_lost_duration": total_focus_lost_duration,
            "totalFocusLostDuration": total_focus_lost_duration,
        }


def get_session_distraction_stats(session_id: str) -> Dict[str, Any]:
    """Backward compatibility wrapper for get_session_metrics."""
    return get_session_metrics(session_id)


def end_session(session_id: str) -> Dict[str, Any]:
    session = get_session(session_id)
    if not session:
        raise ValueError(f"Session with ID '{session_id}' not found")

    if session["status"] != "ACTIVE":
        # Already ended
        return session

    now = datetime.now(timezone.utc)
    now_iso = now.isoformat()

    # Calculate duration
    start_dt = datetime.fromisoformat(session["start_time"])
    duration_sec = max(0.0, (now - start_dt).total_seconds())

    # Check if there was an open distraction event that hasn't returned
    with get_connection() as conn:
        cur = conn.cursor()
        cur.execute("""
            SELECT event_type, timestamp FROM session_events
            WHERE session_id = ?
            ORDER BY timestamp DESC LIMIT 1
        """, (session_id,))
        last_evt = cur.fetchone()
        if last_evt and last_evt["event_type"] in ("PAGE_HIDDEN", "TAB_SWITCH"):
            leave_dt = datetime.fromisoformat(last_evt["timestamp"])
            distract_dur = max(0.0, (now - leave_dt).total_seconds())
            try:
                create_event(
                    session_id=session_id,
                    event_type="FOCUS_REGAINED",
                    user_id=session["user_id"],
                    metadata={"reason": "session_ended_while_away", "source": "chrome_extension_tabs"},
                    duration=round(distract_dur, 2),
                    timestamp=now_iso,
                )
            except Exception:
                pass

        # Check if there was an open drowsiness event
        cur.execute("""
            SELECT event_type, timestamp FROM session_events
            WHERE session_id = ? AND event_type IN ('DROWSINESS_STARTED', 'DROWSINESS_ENDED')
            ORDER BY timestamp DESC LIMIT 1
        """, (session_id,))
        last_drowsy = cur.fetchone()
        if last_drowsy and last_drowsy["event_type"] == "DROWSINESS_STARTED":
            drowsy_dt = datetime.fromisoformat(last_drowsy["timestamp"])
            drowsy_dur = max(0.0, (now - drowsy_dt).total_seconds())
            try:
                create_event(
                    session_id=session_id,
                    event_type="DROWSINESS_ENDED",
                    user_id=session["user_id"],
                    metadata={"source": "computer_vision", "reason": "session_ended"},
                    duration=round(drowsy_dur, 2),
                    timestamp=now_iso,
                )
            except Exception:
                pass

        # Check if there was an open focus lost event
        cur.execute("""
            SELECT event_type, timestamp FROM session_events
            WHERE session_id = ? AND event_type IN ('FOCUS_LOST', 'FOCUS_REGAINED')
            ORDER BY timestamp DESC LIMIT 1
        """, (session_id,))
        last_focus = cur.fetchone()
        if last_focus and last_focus["event_type"] == "FOCUS_LOST":
            lost_dt = datetime.fromisoformat(last_focus["timestamp"])
            lost_dur = max(0.0, (now - lost_dt).total_seconds())
            try:
                create_event(
                    session_id=session_id,
                    event_type="FOCUS_REGAINED",
                    user_id=session["user_id"],
                    metadata={"source": "computer_vision", "reason": "session_ended"},
                    duration=round(lost_dur, 2),
                    timestamp=now_iso,
                )
            except Exception:
                pass

        conn.execute("""
            UPDATE study_sessions
            SET end_time = ?, duration = ?, status = 'COMPLETED'
            WHERE session_id = ?
        """, (now_iso, round(duration_sec, 2), session_id))
        conn.commit()

    return get_session(session_id)


def update_session_heartbeat(session_id: str) -> Optional[Dict[str, Any]]:
    """Update active session activity timestamp to prevent timeout."""
    session = get_session(session_id)
    if not session or session.get("status") != "ACTIVE":
        return None
    return session


def cleanup_stale_sessions(max_idle_hours: float = 4.0) -> int:
    """Auto-terminate orphaned ACTIVE sessions older than max_idle_hours."""
    now = datetime.now(timezone.utc)
    cutoff = now - timedelta(hours=max_idle_hours)
    cutoff_iso = cutoff.isoformat()

    stale_count = 0
    with get_connection() as conn:
        cur = conn.cursor()
        cur.execute("""
            SELECT session_id, start_time FROM study_sessions
            WHERE status = 'ACTIVE' AND start_time < ?
        """, (cutoff_iso,))
        stale_rows = cur.fetchall()

    for r in stale_rows:
        try:
            end_session(r["session_id"])
            stale_count += 1
        except Exception:
            pass

    return stale_count


def get_session(session_id: str) -> Optional[Dict[str, Any]]:
    with get_connection() as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM study_sessions WHERE session_id = ?", (session_id,))
        row = cur.fetchone()
        if not row:
            return None
        d = dict(row)
        d.update(get_session_distraction_stats(session_id))
        return d


def list_sessions(user_id: Optional[str] = None, limit: int = 50) -> List[Dict[str, Any]]:
    with get_connection() as conn:
        cur = conn.cursor()
        if user_id:
            cur.execute("""
                SELECT * FROM study_sessions
                WHERE user_id = ?
                ORDER BY start_time DESC LIMIT ?
            """, (user_id, limit))
        else:
            cur.execute("""
                SELECT * FROM study_sessions
                ORDER BY start_time DESC LIMIT ?
            """, (limit,))
        sessions = [dict(r) for r in cur.fetchall()]
        for s in sessions:
            s.update(get_session_distraction_stats(s["session_id"]))
        return sessions


# ── Session Event Operations (Phase 2) ───────────────────────────────

def create_event(
    session_id: str,
    event_type: str,
    user_id: Optional[str] = None,
    metadata: Optional[Any] = None,
    duration: float = 0.0,
    timestamp: Optional[str] = None,
) -> Dict[str, Any]:
    session = get_session(session_id)
    if not session:
        raise ValueError(f"Session with ID '{session_id}' not found")

    # Validate user ownership
    if user_id and session["user_id"] != user_id:
        raise PermissionError("Session does not belong to specified user")

    # Validate active status
    if session["status"] != "ACTIVE":
        raise ValueError(f"Cannot log event to session with status '{session['status']}'. Session must be ACTIVE.")

    if not event_type or not str(event_type).strip():
        raise ValueError("eventType must not be empty")

    clean_type = str(event_type).strip().upper()
    event_id = f"evt_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:6]}"
    ts = timestamp or _now_iso()
    session_user_id = session["user_id"]

    if metadata is None:
        meta_str = "{}"
    elif isinstance(metadata, str):
        try:
            parsed = json.loads(metadata)
            meta_str = json.dumps(parsed)
        except Exception:
            meta_str = json.dumps({"raw": metadata})
    elif isinstance(metadata, dict):
        meta_str = json.dumps(metadata)
    else:
        meta_str = json.dumps({"value": metadata})

    with get_connection() as conn:
        conn.execute("""
            INSERT INTO session_events (
                event_id, session_id, user_id, timestamp, event_type, duration, metadata
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """, (event_id, session_id, session_user_id, ts, clean_type, float(duration or 0.0), meta_str))
        conn.commit()

    return get_event(event_id)


def get_event(event_id: str) -> Optional[Dict[str, Any]]:
    with get_connection() as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM session_events WHERE event_id = ?", (event_id,))
        row = cur.fetchone()
        if not row:
            return None
        d = dict(row)
        try:
            d["metadata"] = json.loads(d.get("metadata", "{}"))
        except Exception:
            pass
        return d


def list_events(session_id: str, user_id: Optional[str] = None, limit: int = 100) -> List[Dict[str, Any]]:
    session = get_session(session_id)
    if not session:
        raise ValueError(f"Session with ID '{session_id}' not found")

    if user_id and session["user_id"] != user_id:
        raise PermissionError("Session does not belong to specified user")

    with get_connection() as conn:
        cur = conn.cursor()
        cur.execute("""
            SELECT * FROM session_events
            WHERE session_id = ?
            ORDER BY timestamp DESC
            LIMIT ?
        """, (session_id, limit))
        results = []
        for r in cur.fetchall():
            d = dict(r)
            try:
                d["metadata"] = json.loads(d.get("metadata", "{}"))
            except Exception:
                pass
            results.append(d)
        return results


def list_user_events(
    user_id: str,
    event_types: Optional[List[str]] = None,
    limit: int = 200,
) -> List[Dict[str, Any]]:
    """Fetch session events across all sessions for a user."""
    with get_connection() as conn:
        cur = conn.cursor()
        if event_types:
            placeholders = ",".join("?" for _ in event_types)
            cur.execute(f"""
                SELECT * FROM session_events
                WHERE user_id = ? AND event_type IN ({placeholders})
                ORDER BY timestamp DESC
                LIMIT ?
            """, [user_id] + list(event_types) + [limit])
        else:
            cur.execute("""
                SELECT * FROM session_events
                WHERE user_id = ?
                ORDER BY timestamp DESC
                LIMIT ?
            """, (user_id, limit))
        results = []
        for r in cur.fetchall():
            d = dict(r)
            try:
                d["metadata"] = json.loads(d.get("metadata", "{}"))
            except Exception:
                pass
            results.append(d)
        return results



# ── FocusTwin Digital Twin Data Operations (Phase 1) ─────────────────

def get_or_create_attention_profile(user_id: str) -> Dict[str, Any]:
    """Retrieve or initialize the Attention Digital Twin profile for a user."""
    user = get_user(user_id)
    if not user:
        raise ValueError(f"User '{user_id}' not found")
    now = _now_iso()
    with get_connection() as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM attention_profiles WHERE user_id = ?", (user_id,))
        row = cur.fetchone()
        if not row:
            cur.execute("""
                INSERT INTO attention_profiles (
                    user_id, cold_start_state, confidence_level, sample_size,
                    best_attention_window, avg_focus_duration, avg_recovery_time,
                    notification_sensitivity, fatigue_onset_minutes,
                    task_profiles_json, app_fragmentation_json, updated_at
                ) VALUES (?, 'LEARNING', 'LOW', 0, NULL, 0.0, 0.0, 0.5, 45.0, '{}', '{}', ?)
            """, (user_id, now))
            conn.commit()
            cur.execute("SELECT * FROM attention_profiles WHERE user_id = ?", (user_id,))
            row = cur.fetchone()
        d = dict(row)
        for json_k in ["task_profiles_json", "app_fragmentation_json"]:
            try:
                d[json_k] = json.loads(d.get(json_k, "{}"))
            except Exception:
                d[json_k] = {}
        return d


def update_attention_profile(user_id: str, **kwargs) -> Dict[str, Any]:
    """Update attention digital twin profile fields."""
    existing = get_or_create_attention_profile(user_id)
    allowed = {
        "cold_start_state", "confidence_level", "sample_size",
        "best_attention_window", "avg_focus_duration", "avg_recovery_time",
        "notification_sensitivity", "fatigue_onset_minutes",
        "task_profiles_json", "app_fragmentation_json"
    }
    updates = {}
    for k, v in kwargs.items():
        if k in allowed:
            if k in ["task_profiles_json", "app_fragmentation_json"] and isinstance(v, (dict, list)):
                updates[k] = json.dumps(v)
            else:
                updates[k] = v
    if not updates:
        return existing
    updates["updated_at"] = _now_iso()
    set_clause = ", ".join(f"{k} = ?" for k in updates.keys())
    values = list(updates.values()) + [user_id]
    with get_connection() as conn:
        conn.execute(f"UPDATE attention_profiles SET {set_clause} WHERE user_id = ?", values)
        conn.commit()
    return get_or_create_attention_profile(user_id)


# ── Task Context Operations ──────────────────────────────────────────

def list_task_contexts(user_id: str) -> List[Dict[str, Any]]:
    """List all configured task contexts and metrics for a user."""
    with get_connection() as conn:
        cur = conn.cursor()
        cur.execute("""
            SELECT * FROM task_contexts
            WHERE user_id = ?
            ORDER BY total_sessions DESC, task_name ASC
        """, (user_id,))
        rows = [dict(r) for r in cur.fetchall()]
        if not rows:
            # Seed defaults on first access
            defaults = ["Coding", "Coursera Lecture", "Reading", "Assignment", "Research", "Writing", "YouTube Learning"]
            now = _now_iso()
            for t in defaults:
                ctx_id = f"ctx_{uuid.uuid4().hex[:8]}"
                cur.execute("""
                    INSERT OR IGNORE INTO task_contexts (context_id, user_id, task_name, avg_attention_score, avg_recovery_time, total_sessions, created_at)
                    VALUES (?, ?, ?, 0.0, 0.0, 0, ?)
                """, (ctx_id, user_id, t, now))
            conn.commit()
            cur.execute("SELECT * FROM task_contexts WHERE user_id = ? ORDER BY task_name ASC", (user_id,))
            rows = [dict(r) for r in cur.fetchall()]
        return rows


def upsert_task_context(
    user_id: str,
    task_name: str,
    avg_attention_score: Optional[float] = None,
    avg_recovery_time: Optional[float] = None,
    increment_session: bool = False,
) -> Dict[str, Any]:
    """Create or update a user task context."""
    t_clean = task_name.strip()
    now = _now_iso()
    with get_connection() as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM task_contexts WHERE user_id = ? AND task_name = ?", (user_id, t_clean))
        row = cur.fetchone()
        if not row:
            ctx_id = f"ctx_{uuid.uuid4().hex[:8]}"
            cur.execute("""
                INSERT INTO task_contexts (
                    context_id, user_id, task_name, avg_attention_score,
                    avg_recovery_time, total_sessions, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """, (
                ctx_id, user_id, t_clean,
                float(avg_attention_score or 0.0),
                float(avg_recovery_time or 0.0),
                1 if increment_session else 0,
                now
            ))
            conn.commit()
        else:
            d = dict(row)
            new_sessions = d["total_sessions"] + (1 if increment_session else 0)
            new_score = float(avg_attention_score) if avg_attention_score is not None else d["avg_attention_score"]
            new_recovery = float(avg_recovery_time) if avg_recovery_time is not None else d["avg_recovery_time"]
            cur.execute("""
                UPDATE task_contexts
                SET avg_attention_score = ?, avg_recovery_time = ?, total_sessions = ?
                WHERE user_id = ? AND task_name = ?
            """, (new_score, new_recovery, new_sessions, user_id, t_clean))
            conn.commit()
        cur.execute("SELECT * FROM task_contexts WHERE user_id = ? AND task_name = ?", (user_id, t_clean))
        return dict(cur.fetchone())


# ── Application Categories Operations ────────────────────────────────

def list_app_categories(user_id: Optional[str] = None) -> List[Dict[str, Any]]:
    """List app classifications with user overrides applied."""
    with get_connection() as conn:
        cur = conn.cursor()
        if user_id:
            cur.execute("""
                SELECT * FROM app_categories
                WHERE user_id = ? OR user_id IS NULL
                ORDER BY app_name ASC
            """, (user_id,))
        else:
            cur.execute("SELECT * FROM app_categories ORDER BY app_name ASC")
        return [dict(r) for r in cur.fetchall()]


def set_app_category(app_name: str, classification: str, user_id: Optional[str] = None) -> Dict[str, Any]:
    """Configure classification for an application."""
    if classification not in ("PRODUCTIVE", "NEUTRAL", "POTENTIALLY_DISTRACTING"):
        raise ValueError(f"Invalid classification '{classification}'")
    now = _now_iso()
    app_clean = app_name.strip()
    with get_connection() as conn:
        cur = conn.cursor()
        cur.execute("""
            SELECT category_id FROM app_categories
            WHERE app_name = ? AND (user_id = ? OR (user_id IS NULL AND ? IS NULL))
        """, (app_clean, user_id, user_id))
        row = cur.fetchone()
        if row:
            cat_id = row["category_id"]
            cur.execute("UPDATE app_categories SET classification = ? WHERE category_id = ?", (classification, cat_id))
        else:
            cat_id = f"cat_{uuid.uuid4().hex[:8]}"
            cur.execute("""
                INSERT INTO app_categories (category_id, user_id, app_name, classification, created_at)
                VALUES (?, ?, ?, ?, ?)
            """, (cat_id, user_id, app_clean, classification, now))
        conn.commit()
        cur.execute("SELECT * FROM app_categories WHERE category_id = ?", (cat_id,))
        return dict(cur.fetchone())


def classify_app(app_name: str, user_id: Optional[str] = None) -> str:
    """Return PRODUCTIVE, NEUTRAL, or POTENTIALLY_DISTRACTING for an app."""
    app_clean = (app_name or "").strip()
    with get_connection() as conn:
        cur = conn.cursor()
        if user_id:
            cur.execute("""
                SELECT classification FROM app_categories
                WHERE lower(app_name) = lower(?) AND user_id = ?
                LIMIT 1
            """, (app_clean, user_id))
            row = cur.fetchone()
            if row:
                return row["classification"]
        cur.execute("""
            SELECT classification FROM app_categories
            WHERE lower(app_name) = lower(?) AND user_id IS NULL
            LIMIT 1
        """, (app_clean,))
        row = cur.fetchone()
        if row:
            return row["classification"]
    # Fallback heuristic
    lower = app_clean.lower()
    if any(k in lower for k in ["code", "coursera", "edx", "docs", "terminal", "github", "stack"]):
        return "PRODUCTIVE"
    elif any(k in lower for k in ["discord", "instagram", "reddit", "twitter", "youtube", "netflix", "tiktok"]):
        return "POTENTIALLY_DISTRACTING"
    return "NEUTRAL"


# ── Notification Model Operations ────────────────────────────────────

def create_notification(
    user_id: str,
    source: str,
    category: str,
    task_at_time: str,
    session_id: Optional[str] = None,
    importance: str = "NORMAL",
    attention_at_time: float = 1.0,
    action_taken: Optional[str] = None,
    metadata: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Record a structured notification event."""
    valid_categories = {"MESSAGE", "EMAIL", "SOCIAL", "SYSTEM", "LEARNING", "CALENDAR", "URGENT", "OTHER"}
    cat_upper = category.upper() if category else "OTHER"
    if cat_upper not in valid_categories:
        cat_upper = "OTHER"
    imp_upper = importance.upper() if importance else "NORMAL"
    now = _now_iso()
    notif_id = f"notif_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:4]}"
    meta_json = json.dumps(metadata or {})
    with get_connection() as conn:
        conn.execute("""
            INSERT INTO notifications (
                notification_id, user_id, session_id, timestamp, source,
                category, importance, task_at_time, attention_at_time,
                action_taken, actual_distraction_sec, actual_recovery_sec,
                metadata, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?)
        """, (
            notif_id, user_id, session_id, now, source.strip(),
            cat_upper, imp_upper, task_at_time.strip(), float(attention_at_time),
            action_taken, meta_json, now
        ))
        conn.commit()
    return get_notification(notif_id)


def get_notification(notification_id: str) -> Optional[Dict[str, Any]]:
    with get_connection() as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM notifications WHERE notification_id = ?", (notification_id,))
        row = cur.fetchone()
        if not row:
            return None
        d = dict(row)
        try:
            d["metadata"] = json.loads(d.get("metadata", "{}"))
        except Exception:
            d["metadata"] = {}
        return d


def update_notification_outcome(
    notification_id: str,
    action_taken: str,
    actual_distraction_sec: Optional[float] = None,
    actual_recovery_sec: Optional[float] = None,
) -> Optional[Dict[str, Any]]:
    """Update decision and observed outcome for a notification."""
    with get_connection() as conn:
        conn.execute("""
            UPDATE notifications
            SET action_taken = ?,
                actual_distraction_sec = COALESCE(?, actual_distraction_sec),
                actual_recovery_sec = COALESCE(?, actual_recovery_sec)
            WHERE notification_id = ?
        """, (action_taken, actual_distraction_sec, actual_recovery_sec, notification_id))
        conn.commit()
    return get_notification(notification_id)


def list_notifications(
    user_id: str,
    session_id: Optional[str] = None,
    limit: int = 50,
) -> List[Dict[str, Any]]:
    with get_connection() as conn:
        cur = conn.cursor()
        if session_id:
            cur.execute("""
                SELECT * FROM notifications
                WHERE user_id = ? AND session_id = ?
                ORDER BY timestamp DESC LIMIT ?
            """, (user_id, session_id, limit))
        else:
            cur.execute("""
                SELECT * FROM notifications
                WHERE user_id = ?
                ORDER BY timestamp DESC LIMIT ?
            """, (user_id, limit))
        res = []
        for r in cur.fetchall():
            d = dict(r)
            try:
                d["metadata"] = json.loads(d.get("metadata", "{}"))
            except Exception:
                d["metadata"] = {}
            res.append(d)
        return res


# ── Prediction & Counterfactual Operations ───────────────────────────

def create_prediction(
    user_id: str,
    task_context: str,
    allow_attention_drop_prob: float,
    defer_attention_drop_prob: float,
    allow_distraction_sec: float,
    defer_distraction_sec: float,
    allow_recovery_sec: float,
    defer_recovery_sec: float,
    browsing_risk_allow: float,
    browsing_risk_defer: float,
    recommendation: str,
    confidence: float,
    sample_size: int,
    explanation: Any,
    notification_id: Optional[str] = None,
    session_id: Optional[str] = None,
) -> Dict[str, Any]:
    """Store a counterfactual prediction record."""
    pred_id = f"pred_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:4]}"
    now = _now_iso()
    exp_json = json.dumps(explanation) if not isinstance(explanation, str) else explanation
    with get_connection() as conn:
        conn.execute("""
            INSERT INTO predictions (
                prediction_id, user_id, session_id, notification_id,
                task_context, allow_attention_drop_prob, defer_attention_drop_prob,
                allow_distraction_sec, defer_distraction_sec, allow_recovery_sec,
                defer_recovery_sec, browsing_risk_allow, browsing_risk_defer,
                recommendation, confidence, sample_size, explanation_json,
                actual_action, actual_distraction_sec, prediction_error_sec,
                created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?)
        """, (
            pred_id, user_id, session_id, notification_id,
            task_context.strip(), float(allow_attention_drop_prob), float(defer_attention_drop_prob),
            float(allow_distraction_sec), float(defer_distraction_sec),
            float(allow_recovery_sec), float(defer_recovery_sec),
            float(browsing_risk_allow), float(browsing_risk_defer),
            recommendation.strip(), float(confidence), int(sample_size),
            exp_json, now
        ))
        conn.commit()
    return get_prediction(pred_id)


def get_prediction(prediction_id: str) -> Optional[Dict[str, Any]]:
    with get_connection() as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM predictions WHERE prediction_id = ?", (prediction_id,))
        row = cur.fetchone()
        if not row:
            return None
        d = dict(row)
        try:
            d["explanation"] = json.loads(d.get("explanation_json", "[]"))
        except Exception:
            d["explanation"] = []
        return d


def update_prediction_outcome(
    prediction_id: str,
    actual_action: str,
    actual_distraction_sec: float,
    actual_recovery_sec: Optional[float] = None,
) -> Optional[Dict[str, Any]]:
    """Log actual action and calculate prediction error (|actual - predicted|)."""
    pred = get_prediction(prediction_id)
    if not pred:
        return None
    predicted_sec = pred["allow_distraction_sec"] if actual_action == "ALLOW" else pred["defer_distraction_sec"]
    error_sec = round(abs(float(actual_distraction_sec) - float(predicted_sec)), 2)
    with get_connection() as conn:
        if actual_recovery_sec is not None:
            conn.execute("""
                UPDATE predictions
                SET actual_action = ?,
                    actual_distraction_sec = ?,
                    actual_recovery_sec = ?,
                    prediction_error_sec = ?
                WHERE prediction_id = ?
            """, (actual_action, float(actual_distraction_sec), float(actual_recovery_sec), error_sec, prediction_id))
        else:
            conn.execute("""
                UPDATE predictions
                SET actual_action = ?,
                    actual_distraction_sec = ?,
                    prediction_error_sec = ?
                WHERE prediction_id = ?
            """, (actual_action, float(actual_distraction_sec), error_sec, prediction_id))
        conn.commit()
    return get_prediction(prediction_id)


def list_predictions(
    user_id: str,
    session_id: Optional[str] = None,
    limit: int = 50,
) -> List[Dict[str, Any]]:
    with get_connection() as conn:
        cur = conn.cursor()
        if session_id:
            cur.execute("""
                SELECT * FROM predictions
                WHERE user_id = ? AND session_id = ?
                ORDER BY created_at DESC LIMIT ?
            """, (user_id, session_id, limit))
        else:
            cur.execute("""
                SELECT * FROM predictions
                WHERE user_id = ?
                ORDER BY created_at DESC LIMIT ?
            """, (user_id, limit))
        res = []
        for r in cur.fetchall():
            d = dict(r)
            try:
                d["explanation"] = json.loads(d.get("explanation_json", "[]"))
            except Exception:
                d["explanation"] = []
            res.append(d)
        return res


# ── Intervention Operations ──────────────────────────────────────────

def create_intervention(
    user_id: str,
    type: str,
    reason: str,
    session_id: Optional[str] = None,
) -> Dict[str, Any]:
    """Create a proactive intervention recommendation."""
    int_id = f"intv_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:4]}"
    now = _now_iso()
    with get_connection() as conn:
        conn.execute("""
            INSERT INTO interventions (intervention_id, user_id, session_id, type, reason, status, created_at)
            VALUES (?, ?, ?, ?, ?, 'RECOMMENDED', ?)
        """, (int_id, user_id, session_id, type.strip(), reason.strip(), now))
        conn.commit()
    return get_intervention(int_id)


def get_intervention(intervention_id: str) -> Optional[Dict[str, Any]]:
    with get_connection() as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM interventions WHERE intervention_id = ?", (intervention_id,))
        row = cur.fetchone()
        return dict(row) if row else None


def update_intervention_status(intervention_id: str, status: str) -> Optional[Dict[str, Any]]:
    if status not in ("RECOMMENDED", "ACCEPTED", "REJECTED", "EXPIRED"):
        raise ValueError(f"Invalid intervention status '{status}'")
    with get_connection() as conn:
        conn.execute("UPDATE interventions SET status = ? WHERE intervention_id = ?", (status, intervention_id))
        conn.commit()
    return get_intervention(intervention_id)


def list_interventions(
    user_id: str,
    session_id: Optional[str] = None,
    limit: int = 50,
) -> List[Dict[str, Any]]:
    with get_connection() as conn:
        cur = conn.cursor()
        if session_id:
            cur.execute("""
                SELECT * FROM interventions
                WHERE user_id = ? AND session_id = ?
                ORDER BY created_at DESC LIMIT ?
            """, (user_id, session_id, limit))
        else:
            cur.execute("""
                SELECT * FROM interventions
                WHERE user_id = ?
                ORDER BY created_at DESC LIMIT ?
            """, (user_id, limit))
        return [dict(r) for r in cur.fetchall()]

