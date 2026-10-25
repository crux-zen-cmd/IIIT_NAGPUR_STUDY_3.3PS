"""
Face Detection & Landmark Tracking using MediaPipe Tasks API (FaceLandmarker).
Works with mediapipe >= 0.10.30.
Extracts 478 facial landmarks (468 face + 10 iris) per frame.
"""

import os
import cv2
import mediapipe as mp
import numpy as np

from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision as mp_vision
from mediapipe.tasks.python.vision.core.vision_task_running_mode import VisionTaskRunningMode


# Path to the .task model file (downloaded alongside this project)
_MODEL_PATH = os.path.join(os.path.dirname(__file__), "..", "face_landmarker.task")


class FaceDetector:
    # Key eye landmark indices (MediaPipe 478-point mesh)
    # ── EAR calculation: 6 points per eye ────────────────────────
    LEFT_EAR_LANDMARKS  = [362, 385, 387, 263, 373, 380]
    RIGHT_EAR_LANDMARKS = [33,  160, 158, 133, 153, 144]

    # ── Iris landmarks (indices 468-477) ──────────────────────────
    LEFT_IRIS  = [474, 475, 476, 477]
    RIGHT_IRIS = [469, 470, 471, 472]

    # ── For eye outline drawing ───────────────────────────────────
    LEFT_EYE_OUTLINE  = [362, 382, 381, 380, 374, 373, 390, 249, 263,
                         466, 388, 387, 386, 385, 384, 398]
    RIGHT_EYE_OUTLINE = [33,  7,  163, 144, 145, 153, 154, 155, 133,
                         173, 157, 158, 159, 160, 161, 246]

    # ── Head pose key landmark indices ───────────────────────────
    POSE_LANDMARK_IDS = [1, 152, 226, 446, 57, 287]

    def __init__(self, max_faces: int = 3):
        model_path = os.path.abspath(_MODEL_PATH)
        if not os.path.exists(model_path):
            raise FileNotFoundError(
                f"Face Landmarker model not found at: {model_path}\n"
                "Run: curl -sL https://storage.googleapis.com/mediapipe-models/"
                "face_landmarker/face_landmarker/float16/1/face_landmarker.task "
                "-o face_landmarker.task"
            )

        base_opts = mp_python.BaseOptions(model_asset_path=model_path)
        opts = mp_vision.FaceLandmarkerOptions(
            base_options=base_opts,
            running_mode=VisionTaskRunningMode.IMAGE,
            num_faces=max_faces,
            min_face_detection_confidence=0.5,
            min_face_presence_confidence=0.5,
            min_tracking_confidence=0.5,
            output_face_blendshapes=False,
            output_facial_transformation_matrixes=False,
        )
        self._landmarker = mp_vision.FaceLandmarker.create_from_options(opts)

        # ── Primary user spatial tracking & continuity lock ───────────
        self._tracked_centroid = None   # (cx, cy)
        self._tracked_bbox     = None   # (x0, y0, x1, y1)
        self._tracked_area     = 1.0
        self._frames_tracked   = 0
        self._lost_frames      = 0
        self._primary_face_idx = 0

    def reset_tracking(self):
        """Reset primary user tracking lock (e.g. on new session or manual reset)."""
        self._tracked_centroid = None
        self._tracked_bbox     = None
        self._tracked_area     = 1.0
        self._frames_tracked   = 0
        self._lost_frames      = 0
        self._primary_face_idx = 0

    def process(self, frame_bgr: np.ndarray):
        """
        Run face landmarking on a BGR frame.
        Returns the raw FaceLandmarkerResult object.
        """
        rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
        mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
        return self._landmarker.detect(mp_image)

    @staticmethod
    def _compute_iou(boxA, boxB):
        if boxA is None or boxB is None:
            return 0.0
        xA = max(boxA[0], boxB[0])
        yA = max(boxA[1], boxB[1])
        xB = min(boxA[2], boxB[2])
        yB = min(boxA[3], boxB[3])
        interW = max(0.0, xB - xA)
        interH = max(0.0, yB - yA)
        interArea = interW * interH
        boxAArea = max(1.0, (boxA[2] - boxA[0]) * (boxA[3] - boxA[1]))
        boxBArea = max(1.0, (boxB[2] - boxB[0]) * (boxB[3] - boxB[1]))
        iou = interArea / float(boxAArea + boxBArea - interArea)
        return max(0.0, min(1.0, iou))

    def get_landmarks(self, result, frame_shape) -> "np.ndarray | None":
        """
        Returns landmarks for the PRIMARY user.
        Locks onto the primary user using spatial continuity and centrality,
        preventing bystanders or secondary persons from hijacking tracking.
        """
        if not result.face_landmarks:
            self._lost_frames += 1
            if self._lost_frames > 40:
                self.reset_tracking()
            return None

        h = int(frame_shape[0])
        w = int(frame_shape[1])
        diag = float(np.hypot(w, h))

        candidates = []
        for idx, face_lms in enumerate(result.face_landmarks):
            coords = np.array(
                [[lm.x * w, lm.y * h, lm.z * w] for lm in face_lms],
                dtype=np.float32,
            )
            x0 = float(np.min(coords[:, 0]))
            x1 = float(np.max(coords[:, 0]))
            y0 = float(np.min(coords[:, 1]))
            y1 = float(np.max(coords[:, 1]))
            cx = (x0 + x1) / 2.0
            cy = (y0 + y1) / 2.0
            area = max(1.0, (x1 - x0) * (y1 - y0))

            # Centrality: primary student sits in front of screen center
            norm_cx_dist = abs(cx - (w / 2.0)) / (w / 2.0)
            norm_cy_dist = abs(cy - (h / 2.0)) / (h / 2.0)
            center_score = max(0.0, 1.0 - (0.75 * norm_cx_dist + 0.25 * norm_cy_dist))
            norm_size    = min(1.0, area / max(1.0, 0.22 * w * h))

            # Continuity match against currently locked primary user
            continuity_score = 0.0
            proximity_score  = 0.0
            if self._tracked_centroid is not None and self._lost_frames < 30:
                dist = float(np.hypot(cx - self._tracked_centroid[0], cy - self._tracked_centroid[1]))
                proximity_score = max(0.0, 1.0 - (dist / (0.42 * diag)))
                iou_score = self._compute_iou((x0, y0, x1, y1), self._tracked_bbox)
                area_ratio = min(area, self._tracked_area) / max(area, self._tracked_area, 1.0)
                continuity_score = 0.50 * proximity_score + 0.35 * iou_score + 0.15 * area_ratio

            candidates.append({
                "idx": idx,
                "coords": coords,
                "bbox": (x0, y0, x1, y1),
                "centroid": (cx, cy),
                "area": area,
                "center_score": center_score,
                "norm_size": norm_size,
                "continuity_score": continuity_score,
                "proximity_score": proximity_score,
            })

        if len(candidates) == 1:
            best = candidates[0]
        else:
            # When multiple people appear in camera:
            if self._frames_tracked >= 3 and self._lost_frames < 25:
                # User is already established & locked in!
                # Prioritize continuity so bystander walking in cannot steal focus.
                def _tracking_score(c):
                    return 0.85 * c["continuity_score"] + 0.15 * c["center_score"]

                best = max(candidates, key=_tracking_score)
                # Fallback if primary user actually walked away
                if best["continuity_score"] < 0.15 and best["proximity_score"] < 0.20:
                    best = max(candidates, key=lambda c: 0.70 * c["center_score"] + 0.30 * c["norm_size"])
            else:
                # Initial acquisition: pick the person sitting centered in front of screen
                best = max(candidates, key=lambda c: 0.65 * c["center_score"] + 0.35 * c["norm_size"])

        # Update primary user tracking lock with exponential moving average
        self._primary_face_idx = best["idx"]
        best_cx, best_cy = best["centroid"]
        best_x0, best_y0, best_x1, best_y1 = best["bbox"]
        best_area = best["area"]

        if self._tracked_centroid is None:
            self._tracked_centroid = (best_cx, best_cy)
            self._tracked_bbox     = (best_x0, best_y0, best_x1, best_y1)
            self._tracked_area     = best_area
        else:
            alpha = 0.75
            self._tracked_centroid = (
                alpha * self._tracked_centroid[0] + (1.0 - alpha) * best_cx,
                alpha * self._tracked_centroid[1] + (1.0 - alpha) * best_cy,
            )
            self._tracked_bbox = (
                alpha * self._tracked_bbox[0] + (1.0 - alpha) * best_x0,
                alpha * self._tracked_bbox[1] + (1.0 - alpha) * best_y0,
                alpha * self._tracked_bbox[2] + (1.0 - alpha) * best_x1,
                alpha * self._tracked_bbox[3] + (1.0 - alpha) * best_y1,
            )
            self._tracked_area = alpha * self._tracked_area + (1.0 - alpha) * best_area

        self._frames_tracked += 1
        self._lost_frames = 0
        return best["coords"]

    def get_secondary_face_bboxes(self, result, frame_shape, primary_landmarks=None):
        """
        Returns list of bounding boxes for NON-primary faces.
        Accurately identifies bystanders by excluding the locked primary face index.
        """
        if not result.face_landmarks or len(result.face_landmarks) <= 1:
            return []

        h = int(frame_shape[0])
        w = int(frame_shape[1])
        padding = 20
        bboxes = []
        primary_idx = getattr(self, "_primary_face_idx", 0)

        for idx, face_lms in enumerate(result.face_landmarks):
            # The primary user is never a bystander!
            if idx == primary_idx:
                continue
            coords = np.array(
                [[lm.x * w, lm.y * h, lm.z * w] for lm in face_lms],
                dtype=np.float32,
            )
            x_min = max(0, int(np.min(coords[:, 0])) - padding)
            y_min = max(0, int(np.min(coords[:, 1])) - padding)
            x_max = min(w, int(np.max(coords[:, 0])) + padding)
            y_max = min(h, int(np.max(coords[:, 1])) + padding)
            bboxes.append((x_min, y_min, x_max, y_max))

        return bboxes

    def get_face_bbox(self, landmarks, frame_shape):
        """Compute padded bounding box from landmarks."""
        h = int(frame_shape[0])
        w = int(frame_shape[1])
        padding = 20
        x_min = max(0, int(np.min(landmarks[:, 0])) - padding)
        y_min = max(0, int(np.min(landmarks[:, 1])) - padding)
        x_max = min(w, int(np.max(landmarks[:, 0])) + padding)
        y_max = min(h, int(np.max(landmarks[:, 1])) + padding)
        return x_min, y_min, x_max, y_max

    def get_eye_landmarks(self, landmarks: np.ndarray):
        """Return left and right eye EAR landmark arrays (shape 6×3 each)."""
        return landmarks[self.LEFT_EAR_LANDMARKS], landmarks[self.RIGHT_EAR_LANDMARKS]

    def get_iris_landmarks(self, landmarks: np.ndarray):
        """Return left and right iris landmark arrays (shape 4×3 each)."""
        try:
            return landmarks[self.LEFT_IRIS], landmarks[self.RIGHT_IRIS]
        except IndexError:
            return None, None

    def close(self):
        self._landmarker.close()
