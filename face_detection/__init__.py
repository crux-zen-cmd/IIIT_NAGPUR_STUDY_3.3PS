"""
AttentionAI Face Detection & Landmark Tracking Module.
MediaPipe FaceMesh detector with spatial continuity lock and anti-bystander filter.
"""

from .detector import FaceDetector

__all__ = ["FaceDetector"]
