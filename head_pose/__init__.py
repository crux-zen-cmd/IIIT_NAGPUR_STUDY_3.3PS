"""
AttentionAI Head Pose Estimation Module.
Computes 3D Euler angles (Yaw, Pitch, Roll) using solvePnP and anthropometric face models.
"""

from .estimator import HeadPoseEstimator

__all__ = ["HeadPoseEstimator"]
