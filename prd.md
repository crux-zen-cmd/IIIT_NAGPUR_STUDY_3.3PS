# AttentionAI & FocusTwin — Product Requirements & Architecture

## 1. Overview
AttentionAI is a privacy-first, on-device attention and cognitive state tracking system. It integrates computer vision biometrics, browser telemetry via a Chrome extension, a mobile companion app, and a personalized Digital Twin for counterfactual interruption prediction and closed-loop learning.

## 2. Core Capabilities
- **Real-Time Facial Landmark Analysis**: 478 3D landmarks via MediaPipe FaceMesh running at 30 FPS.
- **Eye Aspect Ratio (EAR) & Drowsiness**: Accurate detection of micro-blinks and sustained microsleep closures.
- **Gaze & Head Pose Tracking**: solvePnP 3D Euler angles (Yaw, Pitch, Roll) and iris tracking.
- **Anti-Bystander Spatial Lock**: Preserves user tracking lock in shared study environments.
- **Chrome Extension Tab-Switch Monitor**: Detects off-task tab switches and app switching lag.
- **Digital Twin & Counterfactual Engine**: Bayesian blending predicting attention degradation from incoming alerts.
- **High-Contrast Light Theme UI**: Optimized for readability and professional review.
