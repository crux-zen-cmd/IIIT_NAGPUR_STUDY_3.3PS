"""
AttentionAI Eye Tracking Module.
Provides Eye Aspect Ratio (EAR) calculation, blink detection, and iris gaze estimation.
"""

from .ear import compute_ear, BlinkDetector
from .gaze import GazeEstimator

__all__ = ["compute_ear", "BlinkDetector", "GazeEstimator"]
