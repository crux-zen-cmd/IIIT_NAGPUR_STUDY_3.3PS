import os
import sys

# Configure UTF-8 output encoding for Windows consoles
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

# macOS: allow OpenCV to access the camera from a background thread
os.environ.setdefault("OPENCV_AVFOUNDATION_SKIP_AUTH", "1")

"""
Flask backend for Real-Time Attention Tracker.
Endpoints:
  GET  /                    -> serves the dashboard HTML
  GET  /video_feed          -> MJPEG stream (fallback)
  GET  /api/frame           -> single JPEG frame (canvas polling)
  GET  /api/status          -> current attention state & stats (JSON)
  GET  /api/session_summary -> session analytics summary (JSON)
  GET  /api/timeline        -> attention timeline records (JSON)
  POST /api/reset_session   -> reset session data
  GET  /api/sessions        -> list past sessions
"""

import cv2
import csv
import io
import time
import threading
from datetime import datetime, timezone, timedelta
import numpy as np
from collections import deque
from flask import Flask, Response, jsonify, render_template, make_response, request
from flask_cors import CORS

import db

# Initialize SQLite database on startup
db.init_db()

from face_detection.detector import FaceDetector
from eye_tracking.ear         import BlinkDetector, compute_ear
from eye_tracking.gaze        import GazeEstimator
from head_pose.estimator      import HeadPoseEstimator
from attention_logic.classifier import AttentionClassifier
from utils.drawing import (
    draw_face_box, draw_eye_landmarks, draw_iris,
    draw_attention_label, draw_stats_panel, draw_no_face, draw_fps,
)
from utils.session_logger import SessionLogger
from attention_logic.cv_session_tracker import SessionCVTracker
from attention_logic import (
    analytics,
    digital_twin_engine,
    fragmentation_engine,
    browsing_overrun_detector,
    task_resumption_engine,
    attention_predictor,
    counterfactual_engine,
    notification_simulator,
    closed_loop_engine,
    distraction_diagnostic_engine,
    demo_manager,
)

cv_session_tracker = SessionCVTracker()


# ── App setup ───────────────────────────────────────────────────────
app = Flask(__name__)
CORS(app)


# ── Global processing state ─────────────────────────────────────────
# ── Global processing state ─────────────────────────────────────────
class ProcessingState:
    def __init__(self):
        self.lock          = threading.Lock()
        self.frame_bytes   = None
        self.status        = {
            "state":         "No Face",
            "confidence":    0.0,
            "ear":           0.0,
            "blink_rate":    0,
            "gaze":          "unknown",
            "yaw":           0.0,
            "pitch":         0.0,
            "roll":          0.0,
            "fps":           0.0,
            "face_detected": False,
            "camera_source": "laptop",
        }
        self.running = False
        self.camera_preference = "auto"       # "auto", "mobile", "laptop"
        self.active_camera_source = "laptop"  # "laptop" or "mobile"
        self.session_camera_source = None     # "mobile", "laptop", or None
        self.cap = None                       # Active cv2.VideoCapture reference
        self.last_mobile_frame_time = 0.0
        self.mobile_frame_count = 0
        self.laptop_frame_count = 0


state = ProcessingState()

# Detector singletons (initialised on first frame or in background thread)
face_detector  = None
blink_detector = None
gaze_estimator = None
pose_estimator = None
classifier     = None
session_logger = None

pipeline_lock = threading.Lock()
fps_samples_map = {
    "laptop": deque(maxlen=10),
    "mobile": deque(maxlen=10),
}
prev_time_map = {
    "laptop": time.time(),
    "mobile": time.time(),
}
timeline_tick = time.time()


def init_detectors_if_needed():
    global face_detector, blink_detector, gaze_estimator
    global pose_estimator, classifier, session_logger
    with pipeline_lock:
        if face_detector is None:
            face_detector  = FaceDetector(max_faces=3)
        if blink_detector is None:
            blink_detector = BlinkDetector()
        if gaze_estimator is None:
            gaze_estimator = GazeEstimator()
        if pose_estimator is None:
            pose_estimator = HeadPoseEstimator()
        if classifier is None:
            classifier     = AttentionClassifier()
        if session_logger is None:
            session_logger = SessionLogger()


def process_frame_data(frame, source="laptop"):
    """
    Core AI attention & computer vision pipeline.
    Processes a single frame (from laptop webcam or mobile phone camera),
    updates global attention state, logs events to active study sessions,
    draws HUD landmarks & metrics, and saves the annotated JPEG frame.
    """
    global timeline_tick
    init_detectors_if_needed()

    with pipeline_lock:
        now = time.time()
        fps_samples = fps_samples_map.get(source, fps_samples_map["laptop"])
        prev_time = prev_time_map.get(source, now)
        fps_samples.append(1.0 / max(now - prev_time, 1e-6))
        fps = float(np.mean(fps_samples))
        prev_time_map[source] = now

        if source == "laptop":
            state.laptop_frame_count += 1
            frame_count = state.laptop_frame_count
        else:
            state.mobile_frame_count += 1
            frame_count = state.mobile_frame_count

        results   = face_detector.process(frame)
        landmarks = face_detector.get_landmarks(results, frame.shape)

        if landmarks is not None:
            bbox = face_detector.get_face_bbox(landmarks, frame.shape)

            left_eye, right_eye = face_detector.get_eye_landmarks(landmarks)
            left_ear  = compute_ear(left_eye)
            right_ear = compute_ear(right_eye)
            avg_ear   = (left_ear + right_ear) / 2.0
            blink_detector.update(avg_ear)

            left_iris, right_iris = face_detector.get_iris_landmarks(landmarks)
            gaze_dir, gx, gy = gaze_estimator.estimate(landmarks, left_iris, right_iris)

            # Head pose every 3rd frame to save CPU
            if frame_count % 3 == 0:
                pitch, yaw, roll, rvec, tvec = pose_estimator.estimate(landmarks, frame.shape)
            else:
                pitch, yaw, roll = pose_estimator.smoothed_angles()

            ear_stats  = blink_detector.get_stats()
            gaze_stats = {
                "looking_away": gaze_estimator.is_looking_away(),
                "direction":    gaze_dir,
            }
            pose_stats = pose_estimator.get_stats()
            attn_state, confidence, signals = classifier.classify(
                ear_stats, gaze_stats, pose_stats
            )

            session_logger.record(
                attn_state, confidence, avg_ear,
                ear_stats["blink_rate_per_min"],
                gaze_dir, yaw, pitch, roll,
            )

            if now - timeline_tick >= 1.0:
                classifier.record_timeline_point()
                timeline_tick = now

            draw_face_box(frame, bbox, attn_state)
            draw_eye_landmarks(frame, left_eye)
            draw_eye_landmarks(frame, right_eye)
            if left_iris is not None:
                draw_iris(frame, left_iris)
                draw_iris(frame, right_iris)
            draw_attention_label(frame, attn_state, confidence)
            draw_stats_panel(
                frame, avg_ear,
                ear_stats["blink_rate_per_min"],
                gaze_dir, pitch, yaw, roll,
            )
            # Visual badge on frame showing active camera source
            cam_badge = "MOBILE CAM" if source == "mobile" else "LAPTOP CAM"
            badge_color = (80, 240, 160) if source == "mobile" else (220, 220, 220)
            cv2.putText(
                frame, cam_badge,
                (max(10, frame.shape[1] - 180), 32),
                cv2.FONT_HERSHEY_SIMPLEX, 0.55,
                badge_color, 2, cv2.LINE_AA,
            )
            draw_fps(frame, fps)

            # Secondary / bystander faces
            secondary_bboxes = face_detector.get_secondary_face_bboxes(
                results, frame.shape, landmarks
            )
            for sbbox in secondary_bboxes:
                sx1, sy1, sx2, sy2 = sbbox
                cv2.rectangle(frame, (sx1, sy1), (sx2, sy2), (100, 100, 100), 1)
                cv2.putText(
                    frame, "Bystander",
                    (sx1 + 4, sy1 + 16),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.45,
                    (140, 140, 140), 1, cv2.LINE_AA,
                )

            with state.lock:
                state.status = {
                    "state":         attn_state,
                    "confidence":    confidence,
                    "ear":           round(float(avg_ear), 3),
                    "blink_rate":    ear_stats["blink_rate_per_min"],
                    "gaze":          gaze_dir,
                    "yaw":           round(float(yaw),   1),
                    "pitch":         round(float(pitch), 1),
                    "roll":          round(float(roll),  1),
                    "fps":           round(float(fps),   1),
                    "face_detected": True,
                    "face_count":    1 + len(secondary_bboxes),
                    "camera_source": source,
                }

            # Phase 5: CV attention monitoring for active study session
            cv_session_tracker.sync_active_session()
            cv_session_tracker.update_state(
                attn_state=attn_state,
                confidence=confidence,
                ear=avg_ear,
                blink_rate=ear_stats["blink_rate_per_min"],
                gaze=gaze_dir,
                yaw=yaw,
                pitch=pitch,
                roll=roll,
                source=f"{source}_camera",
            )
        else:
            draw_no_face(frame)
            cam_badge = "MOBILE CAM" if source == "mobile" else "LAPTOP CAM"
            badge_color = (80, 240, 160) if source == "mobile" else (220, 220, 220)
            cv2.putText(
                frame, cam_badge,
                (max(10, frame.shape[1] - 180), 32),
                cv2.FONT_HERSHEY_SIMPLEX, 0.55,
                badge_color, 2, cv2.LINE_AA,
            )
            draw_fps(frame, fps)
            with state.lock:
                state.status["face_detected"] = False
                state.status["state"]         = "No Face"
                state.status["fps"]           = round(fps, 1)
                state.status["camera_source"] = source

            # Phase 5: No face is counted as focus loss
            cv_session_tracker.sync_active_session()
            cv_session_tracker.update_state(
                attn_state="No Face",
                confidence=1.0,
                ear=0.0,
                blink_rate=0,
                gaze="unknown",
                yaw=0.0,
                pitch=0.0,
                roll=0.0,
                source=f"{source}_camera",
            )

        _, jpeg = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 75])
        with state.lock:
            state.frame_bytes = jpeg.tobytes()

        return dict(state.status)


def processing_loop():
    init_detectors_if_needed()
    cap = None

    while state.running:
        now = time.time()
        # Check if mobile camera is actively streaming, explicitly preferred, or session is mobile
        is_mobile_active = (
            state.camera_preference == "mobile" or
            state.session_camera_source == "mobile" or
            (now - state.last_mobile_frame_time < 5.0)
        )

        if is_mobile_active:
            # RELEASE and TURN OFF laptop webcam so the laptop camera is NOT running
            if cap is not None:
                try:
                    cap.release()
                except Exception:
                    pass
                cap = None
            with state.lock:
                if state.cap is not None:
                    try:
                        state.cap.release()
                    except Exception:
                        pass
                    state.cap = None
            state.active_camera_source = "mobile"
            time.sleep(0.05)
            continue

        # Laptop camera mode
        state.active_camera_source = "laptop"
        if cap is None or not cap.isOpened():
            try:
                cap = cv2.VideoCapture(0)
                if cap.isOpened():
                    cap.set(cv2.CAP_PROP_FRAME_WIDTH,  640)
                    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
                    cap.set(cv2.CAP_PROP_FPS,           30)
                    with state.lock:
                        state.cap = cap
                else:
                    time.sleep(0.2)
                    continue
            except Exception:
                time.sleep(0.5)
                continue

        ret, frame = cap.read()
        if not ret:
            time.sleep(0.05)
            continue

        frame = cv2.flip(frame, 1)
        process_frame_data(frame, source="laptop")

    if cap is not None:
        try:
            cap.release()
        except Exception:
            pass
    if face_detector:
        face_detector.close()
    if session_logger:
        session_logger.save()


def generate_frames():
    """MJPEG generator (kept as fallback)."""
    while True:
        with state.lock:
            frame = state.frame_bytes
        if frame is None:
            time.sleep(0.033)
            continue
        yield (
            b"--frame\r\n"
            b"Content-Type: image/jpeg\r\n\r\n" + frame + b"\r\n"
        )
        time.sleep(0.033)


# ── Routes ──────────────────────────────────────────────────────────

@app.route("/")
def index():
    return render_template("index.html")


@app.route("/video_feed")
def video_feed():
    return Response(
        generate_frames(),
        mimetype="multipart/x-mixed-replace; boundary=frame",
    )


@app.route("/api/frame")
def api_frame():
    """Return the latest processed frame as a single JPEG (canvas polling)."""
    with state.lock:
        frame = state.frame_bytes
    if frame is None:
        blank = np.zeros((480, 640, 3), dtype=np.uint8)
        _, jpeg = cv2.imencode(".jpg", blank)
        frame = jpeg.tobytes()
    resp = Response(frame, mimetype="image/jpeg")
    resp.headers["Cache-Control"] = "no-store, no-cache, must-revalidate"
    resp.headers["Pragma"] = "no-cache"
    return resp


@app.route("/api/upload_frame", methods=["POST"])
@app.route("/api/mobile_frame", methods=["POST"])
def api_upload_frame():
    """
    Accept incoming frame from mobile phone camera (multipart, binary, or base64 JSON).
    Processes frame through the MediaPipe & attention classifier pipeline and updates live telemetry.
    """
    now = time.time()
    state.last_mobile_frame_time = now
    state.active_camera_source = "mobile"

    image_bytes = None

    # 1. Check if multipart file upload
    if "file" in request.files:
        image_bytes = request.files["file"].read()
    elif "frame" in request.files:
        image_bytes = request.files["frame"].read()
    else:
        # 2. Check JSON with base64
        data = request.get_json(silent=True)
        if data and ("image" in data or "frame" in data or "base64" in data):
            b64_str = data.get("image") or data.get("frame") or data.get("base64")
            if "," in b64_str:
                b64_str = b64_str.split(",", 1)[1]
            try:
                import base64
                image_bytes = base64.b64decode(b64_str)
            except Exception as e:
                return jsonify({"error": f"Invalid base64 encoding: {e}"}), 400
        else:
            # 3. Check raw binary payload
            raw_data = request.get_data()
            if raw_data and len(raw_data) > 100:
                image_bytes = raw_data

    if not image_bytes:
        return jsonify({"error": "No frame data provided"}), 400

    nparr = np.frombuffer(image_bytes, np.uint8)
    frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

    if frame is None:
        return jsonify({"error": "Could not decode image"}), 400

    # Flip horizontally if front camera image (client can pass flip=0 to skip)
    should_flip = request.args.get("flip", "1") in ("1", "true", "True")
    if should_flip:
        frame = cv2.flip(frame, 1)

    # Process frame through the attention & face pipeline
    telemetry = process_frame_data(frame, source="mobile")
    if telemetry is None:
        return jsonify({"ok": False, "error": "Detectors not initialized"}), 503

    return jsonify({
        "ok": True,
        "source": "mobile",
        "telemetry": telemetry,
    }), 200


@app.route("/api/camera/source", methods=["GET", "POST"])
def api_camera_source():
    """Get or set the active camera source ('mobile', 'laptop', 'auto')."""
    if request.method == "POST":
        data = request.get_json(silent=True) or {}
        pref = data.get("source") or data.get("mode") or "auto"
        if pref in ("mobile", "laptop", "auto"):
            if pref == "mobile":
                state.camera_preference = "mobile"
                state.active_camera_source = "mobile"
                state.last_mobile_frame_time = time.time()
                with state.lock:
                    if state.cap is not None:
                        try:
                            state.cap.release()
                        except Exception:
                            pass
                        state.cap = None
            elif pref == "laptop":
                state.camera_preference = "laptop"
                state.active_camera_source = "laptop"
                state.session_camera_source = "laptop"
                state.last_mobile_frame_time = 0.0
            return jsonify({
                "ok": True,
                "preference": state.camera_preference,
                "active_source": state.active_camera_source,
            })
        return jsonify({"error": "Invalid source. Use 'mobile', 'laptop', or 'auto'"}), 400
    else:
        now = time.time()
        is_mobile_streaming = (now - state.last_mobile_frame_time < 4.0)
        return jsonify({
            "preference": state.camera_preference,
            "active_source": state.active_camera_source,
            "mobile_streaming": is_mobile_streaming,
            "last_mobile_frame_sec_ago": round(now - state.last_mobile_frame_time, 1) if state.last_mobile_frame_time > 0 else None,
        })


@app.route("/api/status")
def api_status():
    with state.lock:
        st = dict(state.status)
    st["cvSession"] = cv_session_tracker.get_status()
    st["activeCameraSource"] = state.active_camera_source
    st["cameraPreference"] = state.camera_preference
    st["isMobileStreaming"] = (time.time() - state.last_mobile_frame_time < 4.0)
    return jsonify(st)


@app.route("/api/cv/simulate_state", methods=["POST"])
def api_cv_simulate_state():
    """Simulate CV state change for testing and demonstration."""
    data = request.get_json(silent=True) or {}
    sim_state = data.get("state") or data.get("attn_state") or "Focused"
    confidence = float(data.get("confidence", 1.0))
    ear = float(data.get("ear", 0.12 if sim_state == "Drowsy" else 0.3))
    blink_rate = int(data.get("blink_rate", 35 if sim_state == "Drowsy" else 15))
    gaze = data.get("gaze", "away" if sim_state == "Distracted" else "center")
    yaw = float(data.get("yaw", 25.0 if sim_state == "Distracted" else 0.0))
    pitch = float(data.get("pitch", 0.0))
    roll = float(data.get("roll", 0.0))

    with state.lock:
        state.status["state"] = sim_state
        state.status["confidence"] = confidence
        state.status["ear"] = ear
        state.status["blink_rate"] = blink_rate
        state.status["gaze"] = gaze
        state.status["yaw"] = yaw
        state.status["pitch"] = pitch
        state.status["roll"] = roll

    hold_sec = float(data.get("hold_sec", 4.0 if sim_state != "Focused" else 0.0))

    cv_session_tracker.sync_active_session()
    if sim_state == "Focused":
        cv_session_tracker.clear_override()
        cv_session_tracker.update_state(
            attn_state="Focused",
            confidence=confidence,
            ear=ear,
            blink_rate=blink_rate,
            gaze=gaze,
            yaw=yaw,
            pitch=pitch,
            roll=roll,
            source="computer_vision",
            is_simulation=True,
        )
    else:
        cv_session_tracker.set_override(
            state_name=sim_state,
            hold_sec=hold_sec,
            confidence=confidence,
            ear=ear,
            blink_rate=blink_rate,
            gaze=gaze,
            yaw=yaw,
            pitch=pitch,
            roll=roll,
            source="computer_vision",
        )
    return jsonify({
        "ok": True,
        "simulatedState": sim_state,
        "cvTracker": cv_session_tracker.get_status(),
    }), 200


@app.route("/api/session_summary")
def api_session_summary():
    if classifier is None:
        return jsonify({})
    return jsonify(classifier.session_summary())


@app.route("/api/timeline")
def api_timeline():
    if classifier is None:
        return jsonify([])
    return jsonify(classifier.get_timeline())


@app.route("/api/reset_session", methods=["POST"])
def api_reset_session():
    if classifier:
        classifier.reset_session()
    if blink_detector:
        blink_detector.blink_count = 0
        blink_detector.blink_times.clear()
    if face_detector:
        face_detector.reset_tracking()
    if session_logger:
        session_logger.save()
    return jsonify({"ok": True})


# ── Study Sessions & User APIs (Phase 1) ────────────────────────────

@app.route("/users", methods=["GET", "POST"])
@app.route("/api/users", methods=["GET", "POST"])
def api_users():
    if request.method == "POST":
        data = request.get_json(silent=True) or {}
        name = (data.get("name") or "").strip()
        email = (data.get("email") or "").strip()
        user_id = data.get("userId") or data.get("user_id")
        if not name or not email:
            return jsonify({"error": "Name and email are required"}), 400
        try:
            user = db.create_user(name, email, user_id=user_id)
            return jsonify(user), 201
        except Exception as e:
            existing = db.get_user_by_email(email)
            if existing:
                return jsonify(existing), 200
            return jsonify({"error": str(e)}), 400
    else:
        return jsonify(db.list_users())


@app.route("/users/<user_id>", methods=["GET", "PUT"])
@app.route("/api/users/<user_id>", methods=["GET", "PUT"])
def api_user_detail(user_id):
    if request.method == "PUT":
        data = request.get_json(silent=True) or {}
        user = db.update_user(user_id, name=data.get("name"), email=data.get("email"))
        if not user:
            return jsonify({"error": "User not found"}), 404
        return jsonify(user)
    else:
        user = db.get_user(user_id)
        if not user:
            return jsonify({"error": "User not found"}), 404
        return jsonify(user)


@app.route("/sessions/start", methods=["POST"])
@app.route("/api/sessions/start", methods=["POST"])
def api_sessions_start():
    data = request.get_json(silent=True) or {}
    user_id = data.get("userId") or data.get("user_id")
    course_name = (data.get("courseName") or data.get("course_name") or "General Study").strip()
    platform = (data.get("platform") or "Coursera").strip()
    course_url = data.get("courseUrl") or data.get("course_url")
    task_type = data.get("taskType") or data.get("task_type")

    # Validate user
    if not user_id:
        users = db.list_users()
        if users:
            user_id = users[0]["user_id"]
        else:
            return jsonify({"error": "No user ID provided and no users exist"}), 400

    user = db.get_user(user_id)
    if not user:
        return jsonify({"error": f"User '{user_id}' does not exist"}), 404

    # Prevent duplicate active sessions
    active = db.get_active_session(user_id)
    if active:
        return jsonify({
            "error": "An active study session is already running for this user",
            "activeSession": active,
            "session": active,
            "sessionId": active["session_id"],
        }), 409

    device = (data.get("device") or data.get("source") or data.get("cameraSource") or "").lower()
    user_agent = request.headers.get("User-Agent", "").lower()
    is_mobile_req = (
        device == "mobile"
        or data.get("isMobile") is True
        or any(m in user_agent for m in ["android", "iphone", "ipad", "expo", "mobile"])
    )

    if is_mobile_req:
        state.session_camera_source = "mobile"
        state.camera_preference = "mobile"
        state.active_camera_source = "mobile"
        state.last_mobile_frame_time = time.time()
        # Immediately disengage and release laptop webcam so only mobile camera is open
        with state.lock:
            if state.cap is not None:
                try:
                    state.cap.release()
                except Exception:
                    pass
                state.cap = None
    else:
        state.session_camera_source = "laptop"

    try:
        session = db.start_session(
            user_id=user_id,
            course_name=course_name,
            platform=platform,
            course_url=course_url,
            task_type=task_type,
        )
        if classifier:
            classifier.reset_session()
        if face_detector:
            face_detector.reset_tracking()
        cv_session_tracker.set_session(session)
        return jsonify({
            "ok": True,
            "sessionId": session["session_id"],
            "session": session,
            "cameraSource": state.active_camera_source,
            "cameraPreference": state.camera_preference,
        }), 201
    except Exception as e:
        return jsonify({"error": str(e)}), 400


@app.route("/sessions/<session_id>/end", methods=["POST"])
@app.route("/api/sessions/<session_id>/end", methods=["POST"])
def api_sessions_end(session_id):
    try:
        session = db.end_session(session_id)
        cv_session_tracker.set_session(None)
        state.session_camera_source = None
        if session.get("status") == "COMPLETED":
            session.update(analytics.calculate_session_scores(session))
            try:
                digital_twin_engine.compute_digital_twin_profile(session["user_id"], persist=True)
            except Exception:
                pass
        return jsonify({
            "ok": True,
            "sessionId": session["session_id"],
            "session": session,
        }), 200
    except ValueError as e:
        return jsonify({"error": str(e)}), 404
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/sessions/end", methods=["POST"])
@app.route("/api/sessions/end", methods=["POST"])
def api_sessions_end_active():
    data = request.get_json(silent=True) or {}
    user_id = data.get("userId") or data.get("user_id")
    session_id = data.get("sessionId") or data.get("session_id")
    if not session_id:
        active = db.get_active_session(user_id)
        if not active:
            return jsonify({"error": "No active session found"}), 404
        session_id = active["session_id"]
    return api_sessions_end(session_id)


@app.route("/sessions/active", methods=["GET"])
@app.route("/api/sessions/active", methods=["GET"])
def api_sessions_active():
    user_id = request.args.get("userId") or request.args.get("user_id")
    active = db.get_active_session(user_id=user_id)
    return jsonify({
        "active": active is not None,
        "session": active,
    })


@app.route("/sessions/<session_id>", methods=["GET"])
@app.route("/api/sessions/<session_id>", methods=["GET"])
def api_session_detail(session_id):
    session = db.get_session(session_id)
    if not session:
        return jsonify({"error": f"Session '{session_id}' not found"}), 404
    if session.get("status") == "COMPLETED":
        session.update(analytics.calculate_session_scores(session))
    return jsonify(session)


@app.route("/sessions", methods=["GET"])
@app.route("/api/sessions", methods=["GET"])
def api_sessions():
    if request.args.get("legacy") == "true":
        if session_logger is None:
            return jsonify([])
        return jsonify(session_logger.list_sessions())
    user_id = request.args.get("userId") or request.args.get("user_id")
    raw_sessions = db.list_sessions(user_id=user_id)
    enriched = []
    for s in raw_sessions:
        item = dict(s)
        if s.get("status") == "COMPLETED":
            item.update(analytics.calculate_session_scores(s))
        enriched.append(item)
    return jsonify(enriched)


# ── Productivity Analytics & Time-of-Day Insights (Phase 6) ────────

@app.route("/api/analytics/summary", methods=["GET"])
def api_analytics_summary():
    """Returns today's metrics, best study time recommendation, and period stats."""
    user_id = request.args.get("userId") or request.args.get("user_id")
    if not user_id:
        users = db.list_users()
        if users:
            user_id = users[0]["user_id"]
    return jsonify(analytics.get_user_analytics_summary(user_id=user_id))


@app.route("/api/analytics/sessions", methods=["GET"])
def api_analytics_sessions():
    """Returns historical completed study sessions enriched with Phase 6 Focus Scores."""
    user_id = request.args.get("userId") or request.args.get("user_id")
    limit = int(request.args.get("limit", 50))
    if not user_id:
        users = db.list_users()
        if users:
            user_id = users[0]["user_id"]
    raw_sessions = db.list_sessions(user_id=user_id, limit=limit)
    enriched = []
    for s in raw_sessions:
        if s.get("status") == "COMPLETED":
            item = dict(s)
            item.update(analytics.calculate_session_scores(s))
            enriched.append(item)
    return jsonify(enriched)


@app.route("/api/analytics/charts", methods=["GET"])
def api_analytics_charts():
    """Returns pre-aggregated data series for the 5 dashboard visualizations."""
    user_id = request.args.get("userId") or request.args.get("user_id")
    if not user_id:
        users = db.list_users()
        if users:
            user_id = users[0]["user_id"]
    return jsonify(analytics.get_charts_data(user_id=user_id))


# ── Study Session Events (Phase 2) ───────────────────────────────

@app.route("/sessions/<session_id>/events", methods=["POST"])
@app.route("/api/sessions/<session_id>/events", methods=["POST"])
def api_session_log_event(session_id):
    data = request.get_json(force=True, silent=True)
    if not data:
        try:
            raw_text = request.get_data(as_text=True)
            if raw_text:
                data = json.loads(raw_text)
        except Exception:
            data = {}
    data = data or {}
    event_type = data.get("eventType") or data.get("event_type")
    user_id = data.get("userId") or data.get("user_id")
    metadata = data.get("metadata", {})
    duration = data.get("duration", 0.0)
    timestamp = data.get("timestamp")

    if not event_type:
        return jsonify({"error": "eventType is required"}), 400

    # Sync edge signals to Attention State Engine
    clean_type = event_type.upper()
    if clean_type in ("TAB_SWITCH", "PAGE_HIDDEN", "APP_SWITCH", "TASK_INTERRUPTED"):
        app_name = metadata.get("newTab") or metadata.get("appName") or metadata.get("app")
        classification = db.classify_app(app_name, user_id=user_id) if app_name else "POTENTIALLY_DISTRACTING"
        cv_session_tracker.notify_tab_switch(False, app_name=app_name, classification=classification)
    elif clean_type in ("FOCUS_REGAINED", "PAGE_VISIBLE", "TASK_RESUMED"):
        cv_session_tracker.notify_tab_switch(True)

    try:
        event = db.create_event(
            session_id=session_id,
            event_type=event_type,
            user_id=user_id,
            metadata=metadata,
            duration=duration,
            timestamp=timestamp,
        )
        return jsonify({
            "ok": True,
            "eventId": event["event_id"],
            "event": event,
        }), 201
    except PermissionError as e:
        return jsonify({"error": str(e)}), 403
    except ValueError as e:
        err_msg = str(e)
        if "not found" in err_msg.lower():
            return jsonify({"error": err_msg}), 404
        return jsonify({"error": err_msg}), 400
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/sessions/<session_id>/events", methods=["GET"])
@app.route("/api/sessions/<session_id>/events", methods=["GET"])
def api_session_list_events(session_id):
    user_id = request.args.get("userId") or request.args.get("user_id")
    limit = int(request.args.get("limit", 100))
    try:
        events = db.list_events(session_id=session_id, user_id=user_id, limit=limit)
        return jsonify(events), 200
    except PermissionError as e:
        return jsonify({"error": str(e)}), 403
    except ValueError as e:
        return jsonify({"error": str(e)}), 404
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/sessions/<session_id>/heartbeat", methods=["GET", "POST"])
@app.route("/api/sessions/<session_id>/heartbeat", methods=["GET", "POST"])
def api_session_heartbeat(session_id):
    session = db.update_session_heartbeat(session_id)
    if not session:
        return jsonify({"error": f"Active session '{session_id}' not found"}), 404
    return jsonify({"ok": True, "sessionId": session_id, "session": session}), 200


@app.route("/api/export_csv")
def api_export_csv():
    """Export full session data as a downloadable CSV file."""
    if classifier is None:
        return jsonify({"error": "Session not started"}), 503

    summary  = classifier.session_summary()
    timeline = classifier.get_timeline()

    output = io.StringIO()
    writer = csv.writer(output)

    # ── Summary section ──────────────────────────────────────
    writer.writerow(["AttentionAI Session Report"])
    writer.writerow(["Generated", time.strftime("%Y-%m-%d %H:%M:%S")])
    writer.writerow([])
    writer.writerow(["=== SESSION SUMMARY ==="])
    writer.writerow(["Metric", "Value"])
    writer.writerow(["Total Duration (s)",    summary.get("total_seconds", 0)])
    writer.writerow(["Focused (%)",            summary.get("focused_pct", 0)])
    writer.writerow(["Distracted (%)",         summary.get("distracted_pct", 0)])
    writer.writerow(["Drowsy (%)",             summary.get("drowsy_pct", 0)])
    writer.writerow(["Focused Time (s)",       summary.get("focused_seconds", 0)])
    writer.writerow(["Distracted Time (s)",    summary.get("distracted_seconds", 0)])
    writer.writerow(["Drowsy Time (s)",        summary.get("drowsy_seconds", 0)])
    writer.writerow(["Best Focus Streak (s)",  summary.get("best_focus_streak_sec", 0)])
    writer.writerow(["Longest Distraction (s)",summary.get("longest_distract_sec", 0)])
    writer.writerow(["Attention Score (0-100)",summary.get("attention_score", 0)])
    writer.writerow([])

    # ── Timeline section ──────────────────────────────────────
    writer.writerow(["=== ATTENTION TIMELINE ==="])
    writer.writerow(["Elapsed (s)", "State"])
    for point in timeline:
        writer.writerow([point.get("ts", ""), point.get("state", "")])

    csv_bytes = output.getvalue().encode("utf-8")
    filename  = time.strftime("attentionai_session_%Y%m%d_%H%M%S.csv")

    resp = make_response(csv_bytes)
    resp.headers["Content-Type"]        = "text/csv; charset=utf-8"
    resp.headers["Content-Disposition"] = f"attachment; filename={filename}"
    return resp


# ── FocusTwin Digital Twin Data APIs (Phase 1) ───────────────────────

@app.route("/api/attention-twin/profile", methods=["GET", "PUT"])
def api_attention_twin_profile():
    """Retrieve or update the user's Attention Digital Twin profile."""
    user_id = request.args.get("userId") or request.args.get("user_id")
    if not user_id and request.is_json:
        user_id = (request.get_json(silent=True) or {}).get("userId")
    if not user_id:
        users = db.list_users()
        if users:
            user_id = users[0]["user_id"]
        else:
            return jsonify({"error": "No user ID available"}), 400

    if request.method == "PUT":
        data = request.get_json(silent=True) or {}
        profile = db.update_attention_profile(user_id, **data)
        return jsonify(profile), 200
    else:
        profile = digital_twin_engine.compute_digital_twin_profile(user_id, persist=True)
        return jsonify(profile), 200


@app.route("/api/attention-twin/profile/recompute", methods=["POST"])
def api_attention_twin_recompute():
    """Force recomputation of the digital twin profile from session history."""
    data = request.get_json(silent=True) or {}
    user_id = data.get("userId") or data.get("user_id") or request.args.get("userId")
    if not user_id:
        users = db.list_users()
        user_id = users[0]["user_id"] if users else None
    if not user_id:
        return jsonify({"error": "userId is required"}), 400
    profile = digital_twin_engine.compute_digital_twin_profile(user_id, persist=True)
    return jsonify(profile), 200


@app.route("/api/attention-twin/fragmentation/analyze", methods=["POST"])
def api_attention_twin_fragmentation():
    """Analyze application switching stream for concentration fragmentation."""
    data = request.get_json(silent=True) or {}
    switches = data.get("switches", [])
    session_dur = float(data.get("sessionDurationSec", 0.0))
    res = fragmentation_engine.analyze_switches(switches, session_duration_sec=session_dur)
    return jsonify(res), 200


@app.route("/api/attention-twin/browsing/overrun-check", methods=["POST"])
def api_attention_twin_browsing_overrun():
    """Analyze browsing behavior to detect potential session overruns."""
    data = request.get_json(silent=True) or {}
    events = data.get("events", [])
    current_task = (data.get("currentTask") or "General Study").strip()
    session_dur = float(data.get("sessionDurationSec", 0.0))
    res = browsing_overrun_detector.check_overrun(events, current_task=current_task, session_duration_sec=session_dur)
    return jsonify(res), 200


@app.route("/api/attention-twin/resumption/analyze", methods=["POST"])
def api_attention_twin_resumption():
    """Analyze task resumption vs attention recovery impact from interruption events."""
    data = request.get_json(silent=True) or {}
    events = data.get("events", [])
    res = task_resumption_engine.analyze_resumptions(events)
    return jsonify(res), 200



@app.route("/api/attention-twin/state", methods=["GET"])
def api_attention_twin_state():
    """Retrieve real-time 5-state attention engine metrics, confidence, and distribution."""
    status = cv_session_tracker.get_status()
    summary = cv_session_tracker.engine.get_summary()
    return jsonify({
        "attentionState": status.get("attentionState", "FOCUSED"),
        "confidence": status.get("attentionSignals", {}).get("confidence", 1.0),
        "isMonitoring": status.get("isMonitoring", False),
        "activeSessionId": status.get("activeSessionId"),
        "signals": status.get("attentionSignals", {}),
        "stateSummary": summary,
    }), 200


@app.route("/api/attention-twin/distraction-diagnostic", methods=["GET"])
def api_attention_twin_diagnostic():
    """Diagnose root cause and event window factors for the latest attention drop."""
    user_id = request.args.get("userId") or request.args.get("user_id")
    if not user_id:
        users = db.list_users()
        user_id = users[0]["user_id"] if users else None
    if not user_id:
        return jsonify({"error": "userId is required"}), 400
    session_id = request.args.get("sessionId") or request.args.get("session_id")
    res = distraction_diagnostic_engine.diagnose_latest_distraction(user_id=user_id, session_id=session_id)
    return jsonify(res), 200


@app.route("/api/tasks", methods=["GET", "POST"])
def api_tasks():
    """List or create/update task contexts for the current user."""
    user_id = request.args.get("userId") or request.args.get("user_id")
    if not user_id and request.is_json:
        user_id = (request.get_json(silent=True) or {}).get("userId")
    if not user_id:
        users = db.list_users()
        if users:
            user_id = users[0]["user_id"]
        else:
            return jsonify({"error": "No user ID available"}), 400

    if request.method == "POST":
        data = request.get_json(silent=True) or {}
        task_name = (data.get("taskName") or data.get("task_name") or "").strip()
        if not task_name:
            return jsonify({"error": "taskName is required"}), 400
        ctx = db.upsert_task_context(
            user_id=user_id,
            task_name=task_name,
            avg_attention_score=data.get("avgAttentionScore"),
            avg_recovery_time=data.get("avgRecoveryTime"),
            increment_session=bool(data.get("incrementSession", False)),
        )
        return jsonify(ctx), 200
    else:
        tasks = db.list_task_contexts(user_id)
        return jsonify(tasks), 200


@app.route("/api/apps/categories", methods=["GET", "POST"])
def api_app_categories():
    """List or configure application categories (Productive, Neutral, Distracting)."""
    user_id = request.args.get("userId") or request.args.get("user_id")
    if request.method == "POST":
        data = request.get_json(silent=True) or {}
        app_name = (data.get("appName") or data.get("app_name") or "").strip()
        classification = (data.get("classification") or "NEUTRAL").strip().upper()
        if not app_name:
            return jsonify({"error": "appName is required"}), 400
        try:
            res = db.set_app_category(app_name=app_name, classification=classification, user_id=user_id)
            return jsonify(res), 200
        except ValueError as e:
            return jsonify({"error": str(e)}), 400
    else:
        categories = db.list_app_categories(user_id=user_id)
        return jsonify(categories), 200


@app.route("/api/notifications", methods=["GET", "POST"])
def api_notifications():
    """Create or list structured notification events."""
    if request.method == "POST":
        data = request.get_json(silent=True) or {}
        user_id = data.get("userId") or data.get("user_id")
        if not user_id:
            users = db.list_users()
            user_id = users[0]["user_id"] if users else None
        if not user_id:
            return jsonify({"error": "userId is required"}), 400

        source = (data.get("source") or "System").strip()
        category = (data.get("category") or "MESSAGE").strip()
        task_at_time = (data.get("taskAtTime") or data.get("task_at_time") or "General Study").strip()
        session_id = data.get("sessionId") or data.get("session_id")
        importance = data.get("importance", "NORMAL")
        attention_at_time = float(data.get("attentionAtTime", 1.0))
        action_taken = data.get("actionTaken") or data.get("action_taken")
        metadata = data.get("metadata", {})

        notif = db.create_notification(
            user_id=user_id,
            source=source,
            category=category,
            task_at_time=task_at_time,
            session_id=session_id,
            importance=importance,
            attention_at_time=attention_at_time,
            action_taken=action_taken,
            metadata=metadata,
        )
        return jsonify(notif), 201
    else:
        user_id = request.args.get("userId") or request.args.get("user_id")
        if not user_id:
            users = db.list_users()
            user_id = users[0]["user_id"] if users else None
        if not user_id:
            return jsonify([]), 200
        session_id = request.args.get("sessionId") or request.args.get("session_id")
        limit = int(request.args.get("limit", 50))
        return jsonify(db.list_notifications(user_id, session_id=session_id, limit=limit)), 200


@app.route("/api/notifications/<notification_id>/outcome", methods=["POST"])
def api_notification_outcome(notification_id):
    """Log decision and observed recovery outcome for a notification."""
    data = request.get_json(silent=True) or {}
    action_taken = data.get("actionTaken") or data.get("action_taken")
    if not action_taken:
        return jsonify({"error": "actionTaken (ALLOW, DEFER, DISMISSED) is required"}), 400
    actual_distraction = data.get("actualDistractionSec") or data.get("actual_distraction_sec")
    actual_recovery = data.get("actualRecoverySec") or data.get("actual_recovery_sec")
    updated = db.update_notification_outcome(
        notification_id=notification_id,
        action_taken=action_taken,
        actual_distraction_sec=float(actual_distraction) if actual_distraction is not None else None,
        actual_recovery_sec=float(actual_recovery) if actual_recovery is not None else None,
    )
    if not updated:
        return jsonify({"error": "Notification not found"}), 404
    return jsonify(updated), 200


@app.route("/api/predictions", methods=["GET", "POST"])
def api_predictions():
    """Store or list counterfactual predictions."""
    if request.method == "POST":
        data = request.get_json(silent=True) or {}
        user_id = data.get("userId") or data.get("user_id")
        if not user_id:
            users = db.list_users()
            user_id = users[0]["user_id"] if users else None
        if not user_id:
            return jsonify({"error": "userId is required"}), 400

        pred = db.create_prediction(
            user_id=user_id,
            task_context=data.get("taskContext", "Coding"),
            allow_attention_drop_prob=float(data.get("allowAttentionDrop", 0.75)),
            defer_attention_drop_prob=float(data.get("deferAttentionDrop", 0.15)),
            allow_distraction_sec=float(data.get("allowDistractionSec", 180.0)),
            defer_distraction_sec=float(data.get("deferDistractionSec", 20.0)),
            allow_recovery_sec=float(data.get("allowRecoverySec", 120.0)),
            defer_recovery_sec=float(data.get("deferRecoverySec", 15.0)),
            browsing_risk_allow=float(data.get("browsingRiskAllow", 0.4)),
            browsing_risk_defer=float(data.get("browsingRiskDefer", 0.05)),
            recommendation=data.get("recommendation", "DEFER"),
            confidence=float(data.get("confidence", 0.85)),
            sample_size=int(data.get("sampleSize", 5)),
            explanation=data.get("explanation", []),
            notification_id=data.get("notificationId"),
            session_id=data.get("sessionId"),
        )
        return jsonify(pred), 201
    else:
        user_id = request.args.get("userId") or request.args.get("user_id")
        if not user_id:
            users = db.list_users()
            user_id = users[0]["user_id"] if users else None
        if not user_id:
            return jsonify([]), 200
        session_id = request.args.get("sessionId") or request.args.get("session_id")
        limit = int(request.args.get("limit", 50))
        return jsonify(db.list_predictions(user_id, session_id=session_id, limit=limit)), 200


@app.route("/api/predictions/<prediction_id>/outcome", methods=["POST"])
def api_prediction_outcome(prediction_id):
    """Log actual action and outcome for closed-loop learning and model update."""
    data = request.get_json(silent=True) or {}
    actual_action = data.get("actualAction") or data.get("actual_action") or "ALLOW"
    actual_distraction = float(data.get("actualDistractionSec") or data.get("actual_distraction_sec") or 0.0)
    actual_recovery = data.get("actualRecoverySec") or data.get("actual_recovery_sec")
    actual_drop = data.get("actualAttentionDrop") or data.get("actual_attention_drop")

    closed_res = closed_loop_engine.record_outcome(
        prediction_id=prediction_id,
        actual_action=actual_action,
        actual_distraction_sec=actual_distraction,
        actual_recovery_sec=float(actual_recovery) if actual_recovery is not None else None,
        actual_attention_drop=float(actual_drop) if actual_drop is not None else None,
    )
    if not closed_res:
        return jsonify({"error": "Prediction not found"}), 404

    # Fetch updated prediction from SQLite to guarantee all database fields are present
    pred = db.get_prediction(prediction_id) or {}
    pred.update(closed_res)
    # Ensure snake_case aliases for legacy test suites
    pred["actual_action"] = pred.get("actualAction", actual_action)
    pred["actual_distraction_sec"] = pred.get("actualDistractionSec", actual_distraction)
    pred["prediction_error_sec"] = pred.get("predictionErrorSec", closed_res.get("predictionErrorSec"))
    return jsonify(pred), 200


@app.route("/api/predictions/predict", methods=["POST"])
def api_predictions_predict():
    """Generate a personalized attention prediction comparing against generic baseline."""
    data = request.get_json(silent=True) or {}
    user_id = data.get("userId") or data.get("user_id")
    if not user_id:
        users = db.list_users()
        user_id = users[0]["user_id"] if users else None
    if not user_id:
        return jsonify({"error": "userId is required"}), 400

    task_context = data.get("taskContext") or data.get("task_context") or "Coding"
    category = data.get("notificationCategory") or data.get("category") or "MESSAGE"
    source = data.get("notificationSource") or data.get("source") or "System"
    session_dur = float(data.get("sessionDurationSec", 0.0))
    state = data.get("currentAttentionState", "FOCUSED")
    session_id = data.get("sessionId") or data.get("session_id")
    notification_id = data.get("notificationId") or data.get("notification_id")
    persist = bool(data.get("persist", False))

    prediction = attention_predictor.predict(
        user_id=user_id,
        task_context=task_context,
        notification_category=category,
        notification_source=source,
        session_duration_sec=session_dur,
        current_attention_state=state,
        session_id=session_id,
        notification_id=notification_id,
        persist=persist,
    )
    return jsonify(prediction), 200


@app.route("/api/predictions/baseline", methods=["GET"])
def api_predictions_baseline():
    """Retrieve generic cognitive population baseline priors."""
    cat = request.args.get("category", "MESSAGE")
    baseline = attention_predictor.get_generic_baseline(category=cat)
    return jsonify(baseline), 200


# ── Counterfactual Engine & Notification Simulator (Phase 5) ────────

@app.route("/api/simulator/simulate", methods=["POST"])
def api_simulator_simulate():
    """Execute counterfactual simulation comparing ALLOW vs DEFER pathways."""
    data = request.get_json(silent=True) or {}
    user_id = data.get("userId") or data.get("user_id")
    if not user_id:
        users = db.list_users()
        user_id = users[0]["user_id"] if users else None
    if not user_id:
        return jsonify({"error": "userId is required"}), 400

    task_context = data.get("taskContext") or data.get("task_context") or "Coding"
    category = data.get("notificationCategory") or data.get("category") or "MESSAGE"
    source = data.get("notificationSource") or data.get("source") or "Discord"
    session_dur = float(data.get("sessionDurationSec", 0.0))
    if session_dur == 0.0 and (data.get("sessionDurationMinutes") or data.get("session_duration_minutes")):
        session_dur = float(data.get("sessionDurationMinutes") or data.get("session_duration_minutes")) * 60.0
    state = data.get("currentAttentionState", "FOCUSED")
    defer_min = int(data.get("deferMinutes", 15))
    session_id = data.get("sessionId") or data.get("session_id")
    notification_id = data.get("notificationId") or data.get("notification_id")
    persist = bool(data.get("persist", False))

    res = notification_simulator.simulate(
        user_id=user_id,
        task_context=task_context,
        notification_category=category,
        notification_source=source,
        session_duration_sec=session_dur,
        current_attention_state=state,
        defer_minutes=defer_min,
        session_id=session_id,
        notification_id=notification_id,
        persist=persist,
    )
    return jsonify(res), 200


@app.route("/api/simulator/batch-simulate", methods=["POST"])
def api_simulator_batch():
    """Run batch simulations across multiple scenarios and aggregate savings."""
    data = request.get_json(silent=True) or {}
    user_id = data.get("userId") or data.get("user_id")
    if not user_id:
        users = db.list_users()
        user_id = users[0]["user_id"] if users else None
    if not user_id:
        return jsonify({"error": "userId is required"}), 400

    scenarios = data.get("scenarios")
    persist = bool(data.get("persist", False))

    res = notification_simulator.batch_simulate(
        user_id=user_id,
        scenarios=scenarios,
        persist=persist,
    )
    return jsonify(res), 200


@app.route("/api/simulator/presets", methods=["GET"])
@app.route("/api/simulator/scenarios", methods=["GET"])
def api_simulator_presets():
    """Return available preset simulation scenarios."""
    return jsonify(notification_simulator.get_preset_scenarios()), 200


# ── Closed-Loop Learning & Model Update (Phase 6) ───────────────────

@app.route("/api/closed-loop/outcome", methods=["POST"])
def api_closed_loop_outcome():
    """Record observed outcome, compute prediction error, and adapt digital twin model."""
    data = request.get_json(silent=True) or {}
    pred_id = data.get("predictionId") or data.get("prediction_id")
    if not pred_id:
        return jsonify({"error": "predictionId is required"}), 400

    actual_action = (data.get("actualAction") or data.get("actual_action") or "ALLOW").strip()
    actual_distraction = float(data.get("actualDistractionSec") or data.get("actual_distraction_sec") or 0.0)
    actual_recovery = data.get("actualRecoverySec") or data.get("actual_recovery_sec")
    actual_drop = data.get("actualAttentionDrop") or data.get("actual_attention_drop")
    user_id = data.get("userId") or data.get("user_id")

    res = closed_loop_engine.record_outcome(
        prediction_id=pred_id,
        actual_action=actual_action,
        actual_distraction_sec=actual_distraction,
        actual_recovery_sec=float(actual_recovery) if actual_recovery is not None else None,
        actual_attention_drop=float(actual_drop) if actual_drop is not None else None,
        user_id=user_id,
    )
    if not res:
        return jsonify({"error": "Prediction not found"}), 404
    return jsonify(res), 200


@app.route("/api/closed-loop/summary", methods=["GET"])
def api_closed_loop_summary():
    """Return closed-loop accuracy telemetry, MAE, and LAST PREDICTION dashboard widget."""
    user_id = request.args.get("userId") or request.args.get("user_id")
    if not user_id:
        users = db.list_users()
        user_id = users[0]["user_id"] if users else None
    if not user_id:
        return jsonify({"error": "userId is required"}), 400

    summary = closed_loop_engine.get_learning_analytics(user_id=user_id)
    return jsonify(summary), 200


# ── Privacy & Data Governance (Phase 7) ─────────────────────────────

@app.route("/api/privacy/clear-twin", methods=["POST"])
def api_privacy_clear_twin():
    """Reset user digital twin profile and personal model baselines."""
    data = request.get_json(silent=True) or {}
    user_id = data.get("userId") or data.get("user_id") or request.args.get("userId")
    if not user_id:
        users = db.list_users()
        user_id = users[0]["user_id"] if users else None
    if not user_id:
        return jsonify({"error": "userId is required"}), 400

    db.update_attention_profile(
        user_id,
        cold_start_state="LEARNING",
        confidence_level="LOW",
        sample_size=0,
        notification_sensitivity=0.50,
        avg_recovery_time=35.0,
        avg_focus_duration=25.0,
        fatigue_onset_minutes=45.0,
        task_profiles_json={},
        app_fragmentation_json={},
    )
    return jsonify({"success": True, "message": "Digital Twin profile reset to baseline LEARNING state."}), 200


@app.route("/api/privacy/delete-history", methods=["POST"])
def api_privacy_delete_history():
    """Delete session history and attention events for user."""
    data = request.get_json(silent=True) or {}
    user_id = data.get("userId") or data.get("user_id") or request.args.get("userId")
    if not user_id:
        users = db.list_users()
        user_id = users[0]["user_id"] if users else None
    if not user_id:
        return jsonify({"error": "userId is required"}), 400

    with db.get_connection() as conn:
        conn.execute("DELETE FROM session_events WHERE user_id = ?", (user_id,))
        conn.execute("DELETE FROM notifications WHERE user_id = ?", (user_id,))
        conn.execute("DELETE FROM predictions WHERE user_id = ?", (user_id,))
        conn.execute("DELETE FROM interventions WHERE user_id = ?", (user_id,))
        conn.execute("DELETE FROM study_sessions WHERE user_id = ?", (user_id,))
        conn.commit()

    return jsonify({"success": True, "message": "All session events and history securely purged."}), 200


@app.route("/api/privacy/export", methods=["GET"])
def api_privacy_export():
    """Export all user attention data and twin profile in structured JSON."""
    user_id = request.args.get("userId") or request.args.get("user_id")
    if not user_id:
        users = db.list_users()
        user_id = users[0]["user_id"] if users else None
    if not user_id:
        return jsonify({"error": "userId is required"}), 400

    profile = db.get_or_create_attention_profile(user_id)
    sessions = db.list_sessions(user_id, limit=500)
    events = db.list_user_events(user_id, limit=1000)
    predictions = db.list_predictions(user_id, limit=200)

    return jsonify({
        "exportTimestamp": datetime.now(timezone.utc).isoformat(),
        "userId": user_id,
        "attentionProfile": profile,
        "studySessions": sessions,
        "eventsCount": len(events),
        "predictions": predictions,
        "privacyGuarantee": {
            "cameraProcessing": "LOCAL_CLIENT_ONLY",
            "rawVideoStored": False,
            "screenshotsEnabled": False,
            "privateMessageContentCollected": False,
        }
    }), 200


@app.route("/api/interventions", methods=["GET", "POST"])
def api_interventions():
    """Create or list proactive intervention recommendations."""
    if request.method == "POST":
        data = request.get_json(silent=True) or {}
        user_id = data.get("userId") or data.get("user_id")
        if not user_id:
            users = db.list_users()
            user_id = users[0]["user_id"] if users else None
        if not user_id:
            return jsonify({"error": "userId is required"}), 400

        intv = db.create_intervention(
            user_id=user_id,
            type=data.get("type", "DEFER_NOTIFICATION"),
            reason=data.get("reason", "Attention degradation pattern detected"),
            session_id=data.get("sessionId") or data.get("session_id"),
        )
        return jsonify(intv), 201
    else:
        user_id = request.args.get("userId") or request.args.get("user_id")
        if not user_id:
            users = db.list_users()
            user_id = users[0]["user_id"] if users else None
        if not user_id:
            return jsonify([]), 200
        session_id = request.args.get("sessionId") or request.args.get("session_id")
        limit = int(request.args.get("limit", 50))
        return jsonify(db.list_interventions(user_id, session_id=session_id, limit=limit)), 200


@app.route("/api/interventions/<intervention_id>/status", methods=["POST"])
def api_intervention_status(intervention_id):
    """Update status of an intervention (ACCEPTED, REJECTED, EXPIRED)."""
    data = request.get_json(silent=True) or {}
    status = data.get("status", "ACCEPTED")
    try:
        updated = db.update_intervention_status(intervention_id, status)
        if not updated:
            return jsonify({"error": "Intervention not found"}), 404
        return jsonify(updated), 200
    except ValueError as e:
        return jsonify({"error": str(e)}), 400


# ── Phase 8: Demo Hardening & Deterministic Flow ────────────────────

@app.route("/api/demo/seed", methods=["POST"])
def api_demo_seed():
    """Seed or refresh the isolated Demo Persona (Ada Lovelace) with 12 calibrated sessions."""
    res = demo_manager.seed_demo_persona()
    return jsonify(res), 200


@app.route("/api/demo/clean", methods=["POST"])
def api_demo_clean():
    """Permanently purge all demo records, ensuring zero residue in real user data."""
    res = demo_manager.clean_demo_data()
    return jsonify(res), 200


@app.route("/api/demo/notifications", methods=["GET"])
def api_demo_notifications():
    """Return the 5 prescribed quick-trigger simulated notification scenarios."""
    return jsonify(demo_manager.get_demo_notifications()), 200


@app.route("/api/demo/trigger-notification", methods=["POST"])
def api_demo_trigger_notification():
    """Trigger a simulated notification, predict counterfactuals, and generate recommendations."""
    data = request.get_json(silent=True) or {}
    key = data.get("notificationKey") or data.get("key") or "DISCORD_MESSAGE"
    user_id = data.get("userId") or data.get("user_id")
    dur_sec = data.get("sessionDurationSec")
    state = data.get("currentAttentionState", "FOCUSED")
    persist = bool(data.get("persist", True))

    res = demo_manager.trigger_simulated_notification(
        notification_key=key,
        user_id=user_id,
        session_duration_sec=float(dur_sec) if dur_sec is not None else None,
        current_state=state,
        persist=persist,
    )
    return jsonify(res), 200


@app.route("/api/demo/run-deterministic-flow", methods=["POST"])
def api_demo_run_deterministic_flow():
    """Execute the complete vertical slice: OBSERVE -> PERSONALIZE -> PREDICT -> COUNTERFACTUAL -> EXPLAIN -> RECOMMEND -> OBSERVE OUTCOME -> LEARN."""
    res = demo_manager.run_deterministic_flow()
    return jsonify(res), 200


# ── Startup ─────────────────────────────────────────────────────────

if __name__ == "__main__":
    state.running = True
    t = threading.Thread(target=processing_loop, daemon=True)
    t.start()

    print("\n" + "=" * 55)
    print("  🧠  Attention Tracker  |  http://localhost:5050  ")
    print("=" * 55 + "\n")

    app.run(host="0.0.0.0", port=5050, debug=False, threaded=True)
