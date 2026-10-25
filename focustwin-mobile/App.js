import React, { useState, useEffect, useRef } from "react";
import {
  StyleSheet,
  Text,
  View,
  ScrollView,
  TouchableOpacity,
  TextInput,
  Image,
  Modal,
  SafeAreaView,
  StatusBar,
  ActivityIndicator,
  Dimensions,
} from "react-native";
import { CameraView, useCameraPermissions } from "expo-camera";

const DEFAULT_SERVER_URL = "http://192.168.110.224:5050";
const { width } = Dimensions.get("window");

export default function App() {
  const [serverUrl, setServerUrl] = useState(DEFAULT_SERVER_URL);
  const [tempUrl, setTempUrl] = useState(DEFAULT_SERVER_URL);
  const [connected, setConnected] = useState(false);
  const [activeTab, setActiveTab] = useState("hud"); // 'hud' | 'session' | 'twin' | 'camera'

  // Camera tracking mode ('mobile' = phone front camera, 'laptop' = laptop webcam)
  const [cameraMode, setCameraMode] = useState("mobile");
  const [feedViewMode, setFeedViewMode] = useState("camera"); // 'camera' | 'cv'
  const [permission, requestPermission] = useCameraPermissions();
  const cameraRef = useRef(null);
  const [cameraReady, setCameraReady] = useState(false);
  const [showPip, setShowPip] = useState(true);

  // Live CV Telemetry
  const [telemetry, setTelemetry] = useState({
    state: "Focused",
    confidence: 0.95,
    ear: 0.34,
    blinks: 28,
    gaze: "center",
    yaw: 0.0,
    pitch: 0.0,
    roll: 0.0,
    fps: 24.5,
    face_detected: true,
  });

  // Session Control
  const [sessionActive, setSessionActive] = useState(false);
  const [courseName, setCourseName] = useState("Machine Learning");
  const [platform, setPlatform] = useState("Coursera");
  const [sessionTime, setSessionTime] = useState(0);
  const [sessionSummary, setSessionSummary] = useState({
    focusedPct: 98,
    focusScore: 96,
    streakSec: 420,
    distractionSec: 15,
  });

  // Digital Twin
  const [twinProfile, setTwinProfile] = useState({
    coldStartState: "LEARNING",
    confidenceLevel: "LOW",
    sampleSize: 0,
    bestAttentionWindow: "Afternoon (12 PM – 5 PM)",
    statusMessage: "FocusTwin is learning your attention patterns (0/3 baseline sessions).",
  });

  // Simulation Modal
  const [simModalVisible, setSimModalVisible] = useState(false);
  const [simResult, setSimResult] = useState(null);
  const [simLoading, setSimLoading] = useState(false);

  // Settings Modal
  const [settingsModalVisible, setSettingsModalVisible] = useState(false);

  // Live Camera frame key (forces refresh)
  const [frameKey, setFrameKey] = useState(Date.now());

  // Auto-request mobile camera permission on launch
  useEffect(() => {
    if (!permission?.granted && permission?.canAskAgain) {
      requestPermission();
    }
  }, [permission]);

  // Polling loop for telemetry & status
  useEffect(() => {
    let isMounted = true;

    const pollStatus = async () => {
      try {
        const res = await fetch(`${serverUrl}/api/status`, { headers: { "Cache-Control": "no-cache" } });
        if (!res.ok) throw new Error("Status failed");
        const data = await res.json();
        if (isMounted) {
          setConnected(true);
          setTelemetry({
            state: data.state || "Focused",
            confidence: data.confidence !== undefined ? data.confidence : 0.9,
            ear: data.ear !== undefined ? round2(data.ear) : 0.32,
            blinks: data.blinks_per_min || data.blink_rate || 24,
            gaze: data.gaze || "center",
            yaw: data.yaw !== undefined ? round1(data.yaw) : 0,
            pitch: data.pitch !== undefined ? round1(data.pitch) : 0,
            roll: data.roll !== undefined ? round1(data.roll) : 0,
            fps: data.fps || 24,
            face_detected: data.face_detected !== false,
          });

          if (data.cvSession) {
            setSessionActive(data.cvSession.isActive || false);
            if (data.cvSession.courseName) setCourseName(data.cvSession.courseName);
          }
        }
      } catch (err) {
        if (isMounted) setConnected(false);
      }
    };

    const pollTwin = async () => {
      try {
        const res = await fetch(`${serverUrl}/api/attention-twin/profile?userId=usr_yash`);
        if (res.ok) {
          const t = await res.json();
          if (isMounted && t.twinProfile) {
            setTwinProfile(t.twinProfile);
          }
        }
      } catch (_) {}
    };

    const interval = setInterval(pollStatus, 600);
    const twinInterval = setInterval(pollTwin, 4000);
    pollStatus();
    pollTwin();

    return () => {
      isMounted = false;
      clearInterval(interval);
      clearInterval(twinInterval);
    };
  }, [serverUrl]);

  // Session Timer
  useEffect(() => {
    let t = null;
    if (sessionActive) {
      t = setInterval(() => setSessionTime((prev) => prev + 1), 1000);
    } else {
      setSessionTime(0);
    }
    return () => {
      if (t) clearInterval(t);
    };
  }, [sessionActive]);

  // Periodic frame refresher for camera tab
  useEffect(() => {
    if (activeTab === "camera" && connected && (cameraMode === "laptop" || feedViewMode === "cv")) {
      const fi = setInterval(() => setFrameKey(Date.now()), 250);
      return () => clearInterval(fi);
    }
  }, [activeTab, connected, cameraMode, feedViewMode]);

  // Mobile camera front-facing frame capture loop -> uploads to FocusTwin server
  useEffect(() => {
    let isMounted = true;
    let inFlight = false;
    let timer = null;

    const shouldStream = cameraMode === "mobile" && (sessionActive || activeTab === "camera");

    if (shouldStream) {
      timer = setInterval(async () => {
        if (inFlight || !cameraRef.current || !cameraReady) return;
        inFlight = true;
        try {
          const photo = await cameraRef.current.takePictureAsync({
            base64: true,
            quality: 0.35,
            shutterSound: false,
            skipProcessing: true,
          });
          if (photo && photo.base64 && isMounted) {
            const res = await fetch(`${serverUrl}/api/upload_frame?flip=1`, {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ image: photo.base64 }),
            });
            if (res.ok && isMounted) {
              const data = await res.json();
              if (data.telemetry) {
                setTelemetry((prev) => ({
                  ...prev,
                  ...data.telemetry,
                  face_detected: data.telemetry.face_detected !== false,
                }));
              }
            }
          }
        } catch (_) {
          // ignore transient frame capture glitch
        } finally {
          inFlight = false;
        }
      }, 350);
    }

    return () => {
      isMounted = false;
      if (timer) clearInterval(timer);
    };
  }, [cameraMode, sessionActive, activeTab, cameraReady, serverUrl]);

  // Actions
  const handleStartSession = async () => {
    try {
      if (cameraMode === "mobile") {
        if (!permission || !permission.granted) {
          const permResult = await requestPermission();
          if (!permResult.granted) {
            alert("Camera permission is required so your mobile device can track your attention.");
            return;
          }
        }
        setShowPip(true);
      }

      // Tell backend to disengage laptop webcam and set camera to mobile
      await fetch(`${serverUrl}/api/camera/source`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ source: "mobile", mode: "mobile" }),
      }).catch(() => {});

      const res = await fetch(`${serverUrl}/api/sessions/start`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          userId: "usr_yash",
          courseName: courseName || "Self-Study",
          platform: platform,
          device: "mobile",
          source: "mobile",
          cameraSource: "mobile",
          isMobile: true,
        }),
      });
      if (res.ok) {
        setSessionActive(true);
        setSessionTime(0);
      }
    } catch (e) {
      alert("Failed to start session. Check server connection.");
    }
  };

  const handleEndSession = async () => {
    try {
      const res = await fetch(`${serverUrl}/api/sessions/end`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ userId: "usr_yash" }),
      });
      if (res.ok) {
        setSessionActive(false);
        const data = await res.json();
        if (data.session) {
          setSessionSummary({
            focusedPct: Math.round(data.session.focused_pct || 98),
            focusScore: Math.round(data.session.focusScore || 95),
            streakSec: Math.round(data.session.bestStreakSec || sessionTime),
            distractionSec: Math.round(data.session.distractionSec || 0),
          });
        }
      }
    } catch (e) {
      setSessionActive(false);
    }
  };

  const handleRunSimulation = async (notificationKey) => {
    setSimLoading(true);
    setSimModalVisible(true);
    try {
      const res = await fetch(`${serverUrl}/api/demo/trigger-notification`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          notificationKey: notificationKey,
          userId: "usr_yash",
          sessionDurationSec: sessionTime || 1800,
          currentAttentionState: telemetry.state.toUpperCase(),
          persist: true,
        }),
      });
      if (res.ok) {
        const data = await res.json();
        setSimResult(data);
      } else {
        throw new Error();
      }
    } catch (e) {
      setSimResult({
        isSimulatedNotification: true,
        demoNotificationLabel: "SIMULATED NOTIFICATION",
        notificationTitle: `${notificationKey} Interruption`,
        recommendation: "DEFER",
        allowPath: { attentionDropProbability: 0.88, expectedDistractionSec: 180 },
        deferPath: { attentionDropProbability: 0.12, expectedDistractionSec: 20 },
        savings: { savedDistractionMinutes: 2.7, focusPreservationPct: 86 },
        explanation: ["High focus state detected.", "Deferring preserves 2.7 minutes of uninterrupted study."],
      });
    } finally {
      setSimLoading(false);
    }
  };

  const stateTheme = getStateTheme(telemetry.state);

  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar barStyle="light-content" backgroundColor="#080c14" />

      {/* ── Top Header ────────────────────────────────────────── */}
      <View style={styles.header}>
        <View style={styles.brandRow}>
          <View style={styles.logoBadge}>
            <Text style={styles.logoIcon}>🎯</Text>
          </View>
          <View>
            <Text style={styles.brandTitle}>FocusTwin</Text>
            <Text style={styles.brandSubtitle}>Attention Digital Twin</Text>
          </View>
        </View>

        <TouchableOpacity style={styles.connectionPill} onPress={() => setSettingsModalVisible(true)}>
          <View style={[styles.statusDot, { backgroundColor: connected ? "#22d67a" : "#ef4444" }]} />
          <Text style={styles.connectionText}>{connected ? "Connected" : "Offline"}</Text>
          <Text style={styles.settingsIcon}> ⚙️</Text>
        </TouchableOpacity>
      </View>

      {/* ── Navigation Tabs ──────────────────────────────────── */}
      <View style={styles.navBar}>
        <TouchableOpacity
          style={[styles.navTab, activeTab === "hud" && styles.navTabActive]}
          onPress={() => setActiveTab("hud")}
        >
          <Text style={[styles.navTabText, activeTab === "hud" && styles.navTabTextActive]}>📹 Live HUD</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.navTab, activeTab === "session" && styles.navTabActive]}
          onPress={() => setActiveTab("session")}
        >
          <Text style={[styles.navTabText, activeTab === "session" && styles.navTabTextActive]}>🎯 Session</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.navTab, activeTab === "twin" && styles.navTabActive]}
          onPress={() => setActiveTab("twin")}
        >
          <Text style={[styles.navTabText, activeTab === "twin" && styles.navTabTextActive]}>🧠 Digital Twin</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.navTab, activeTab === "camera" && styles.navTabActive]}
          onPress={() => setActiveTab("camera")}
        >
          <Text style={[styles.navTabText, activeTab === "camera" && styles.navTabTextActive]}>📷 Feed</Text>
        </TouchableOpacity>
      </View>

      <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
        {/* ── TAB 1: LIVE HUD ─────────────────────────────────── */}
        {activeTab === "hud" && (
          <View>
            {/* Primary State Card */}
            <View style={[styles.stateHeroCard, { borderColor: stateTheme.color }]}>
              <View style={styles.stateHeroHeader}>
                <Text style={styles.stateHeroEmoji}>{stateTheme.emoji}</Text>
                <View style={styles.stateHeroInfo}>
                  <Text style={styles.stateHeroLabel}>CURRENT ATTENTION STATE</Text>
                  <Text style={[styles.stateHeroValue, { color: stateTheme.color }]}>{telemetry.state.toUpperCase()}</Text>
                </View>
                <View style={styles.fpsBadge}>
                  <Text style={styles.fpsText}>{telemetry.fps} FPS</Text>
                </View>
              </View>

              {/* Confidence Bar */}
              <View style={styles.confidenceWrap}>
                <View style={styles.confidenceLabelRow}>
                  <Text style={styles.confidenceSub}>Confidence</Text>
                  <Text style={styles.confidencePct}>{Math.round(telemetry.confidence * 100)}%</Text>
                </View>
                <View style={styles.barTrack}>
                  <View
                    style={[
                      styles.barFill,
                      { width: `${Math.round(telemetry.confidence * 100)}%`, backgroundColor: stateTheme.color },
                    ]}
                  />
                </View>
              </View>
            </View>

            {/* Metrics Row */}
            <View style={styles.metricsRow}>
              <View style={styles.metricCard}>
                <Text style={styles.metricValue}>{telemetry.ear}</Text>
                <Text style={styles.metricTitle}>Eye Aspect Ratio</Text>
                <View style={styles.miniBarTrack}>
                  <View style={[styles.miniBarFill, { width: `${Math.min(100, (telemetry.ear / 0.45) * 100)}%` }]} />
                </View>
              </View>

              <View style={styles.metricCard}>
                <Text style={styles.metricValue}>{telemetry.blinks}</Text>
                <Text style={styles.metricTitle}>Blinks / min</Text>
                <Text style={styles.metricSub}>Normal: 15–30</Text>
              </View>

              <View style={styles.metricCard}>
                <Text style={styles.metricValue}>{getGazeIcon(telemetry.gaze)}</Text>
                <Text style={styles.metricTitle}>Gaze Direction</Text>
                <Text style={styles.metricSub}>{telemetry.gaze}</Text>
              </View>
            </View>

            {/* Head Pose Gauges */}
            <View style={styles.card}>
              <Text style={styles.cardTitle}>🔄 3D Head Pose Orientation</Text>
              <View style={styles.poseRow}>
                <View style={styles.poseItem}>
                  <Text style={styles.poseLabel}>Yaw</Text>
                  <Text style={styles.poseVal}>{telemetry.yaw}°</Text>
                </View>
                <View style={styles.poseItem}>
                  <Text style={styles.poseLabel}>Pitch</Text>
                  <Text style={styles.poseVal}>{telemetry.pitch}°</Text>
                </View>
                <View style={styles.poseItem}>
                  <Text style={styles.poseLabel}>Roll</Text>
                  <Text style={styles.poseVal}>{telemetry.roll}°</Text>
                </View>
              </View>
            </View>

            {/* Active Session Mini Tracker */}
            <View style={styles.card}>
              <View style={styles.cardHeaderRow}>
                <Text style={styles.cardTitle}>🎯 Active Session Status</Text>
                <View style={[styles.statusTag, { backgroundColor: sessionActive ? "#064e3b" : "#1f2937" }]}>
                  <Text style={[styles.statusTagText, { color: sessionActive ? "#34d399" : "#9ca3af" }]}>
                    {sessionActive ? "● RECORDING" : "○ INACTIVE"}
                  </Text>
                </View>
              </View>
              <Text style={styles.sessionHeroTimer}>{formatTime(sessionTime)}</Text>
              <Text style={styles.sessionHeroSub}>{sessionActive ? `Course: ${courseName}` : "Start a session to track cognitive patterns"}</Text>
            </View>
          </View>
        )}

        {/* ── TAB 2: STUDY SESSION ────────────────────────────── */}
        {activeTab === "session" && (
          <View>
            <View style={styles.card}>
              <Text style={styles.cardTitle}>📚 Study Session Controller</Text>
              <Text style={styles.inputLabel}>Course / Subject</Text>
              <TextInput
                style={styles.textInput}
                value={courseName}
                onChangeText={setCourseName}
                placeholder="e.g. Machine Learning Specialization"
                placeholderTextColor="#4b5563"
              />

              <Text style={styles.inputLabel}>Platform</Text>
              <View style={styles.platformRow}>
                {["Coursera", "edX", "YouTube", "Custom"].map((p) => (
                  <TouchableOpacity
                    key={p}
                    style={[styles.platformPill, platform === p && styles.platformPillActive]}
                    onPress={() => setPlatform(p)}
                  >
                    <Text style={[styles.platformPillText, platform === p && styles.platformPillTextActive]}>{p}</Text>
                  </TouchableOpacity>
                ))}
              </View>

              {sessionActive ? (
                <TouchableOpacity style={styles.btnDanger} onPress={handleEndSession}>
                  <Text style={styles.btnDangerText}>⏹ End Study Session</Text>
                </TouchableOpacity>
              ) : (
                <TouchableOpacity style={styles.btnPrimary} onPress={handleStartSession}>
                  <Text style={styles.btnPrimaryText}>▶ Start Study Session</Text>
                </TouchableOpacity>
              )}
            </View>

            {/* Today's Summary Highlights */}
            <View style={styles.card}>
              <Text style={styles.cardTitle}>📊 Performance Highlights</Text>
              <View style={styles.highlightRow}>
                <Text style={styles.highlightLabel}>🏆 Best Focus Streak</Text>
                <Text style={styles.highlightVal}>{formatTime(sessionSummary.streakSec)}</Text>
              </View>
              <View style={styles.highlightRow}>
                <Text style={styles.highlightLabel}>🎯 Average Focus Score</Text>
                <Text style={styles.highlightVal}>{sessionSummary.focusScore}%</Text>
              </View>
              <View style={styles.highlightRow}>
                <Text style={styles.highlightLabel}>⏳ Total Distraction Time</Text>
                <Text style={styles.highlightVal}>{sessionSummary.distractionSec}s</Text>
              </View>
            </View>
          </View>
        )}

        {/* ── TAB 3: DIGITAL TWIN & SIMULATOR ────────────────── */}
        {activeTab === "twin" && (
          <View>
            <View style={styles.card}>
              <View style={styles.cardHeaderRow}>
                <Text style={styles.cardTitle}>🧠 Personal Attention Digital Twin</Text>
                <View style={styles.twinStateTag}>
                  <Text style={styles.twinStateText}>{twinProfile.coldStartState || "LEARNING"}</Text>
                </View>
              </View>
              <Text style={styles.twinStatusMsg}>{twinProfile.statusMessage}</Text>

              <View style={styles.twinMetricsGrid}>
                <View style={styles.twinMetricBox}>
                  <Text style={styles.twinBoxVal}>{twinProfile.sampleSize || 0}</Text>
                  <Text style={styles.twinBoxLabel}>Sessions Trained</Text>
                </View>
                <View style={styles.twinMetricBox}>
                  <Text style={styles.twinBoxVal}>{twinProfile.confidenceLevel || "LOW"}</Text>
                  <Text style={styles.twinBoxLabel}>Twin Confidence</Text>
                </View>
              </View>

              <View style={styles.circadianBox}>
                <Text style={styles.circadianTitle}>☀️ Circadian Peak Focus Window</Text>
                <Text style={styles.circadianVal}>{twinProfile.bestAttentionWindow || "Afternoon (12 PM – 5 PM)"}</Text>
              </View>
            </View>

            {/* Quick Interruption Simulator */}
            <View style={styles.card}>
              <Text style={styles.cardTitle}>🔮 Counterfactual Interruption Simulator</Text>
              <Text style={styles.cardSub}>
                Test how your digital twin predicts focus preservation between ALLOWING vs DEFERRING an interruption.
              </Text>

              <View style={styles.simBtnGrid}>
                <TouchableOpacity style={styles.simTriggerBtn} onPress={() => handleRunSimulation("DISCORD_MESSAGE")}>
                  <Text style={styles.simTriggerText}>💬 Discord</Text>
                </TouchableOpacity>
                <TouchableOpacity style={styles.simTriggerBtn} onPress={() => handleRunSimulation("WORK_EMAIL")}>
                  <Text style={styles.simTriggerText}>✉️ Email</Text>
                </TouchableOpacity>
                <TouchableOpacity style={styles.simTriggerBtn} onPress={() => handleRunSimulation("SOCIAL_NOTIFICATION")}>
                  <Text style={styles.simTriggerText}>📱 Social</Text>
                </TouchableOpacity>
              </View>
            </View>
          </View>
        )}

        {/* ── TAB 4: LIVE CAMERA FEED ─────────────────────────── */}
        {activeTab === "camera" && (
          <View style={styles.card}>
            <View style={styles.cardHeaderRow}>
              <Text style={styles.cardTitle}>📹 Live OpenCV Video Feed</Text>
              <Text style={styles.fpsText}>{telemetry.fps} FPS</Text>
            </View>
            {connected ? (
              <View style={styles.cameraWrap}>
                <Image
                  source={{ uri: `${serverUrl}/api/frame?t=${frameKey}` }}
                  style={styles.cameraImage}
                  resizeMode="contain"
                />
              </View>
            ) : (
              <View style={styles.cameraPlaceholder}>
                <Text style={styles.cameraPlaceholderText}>Connecting to camera stream at {serverUrl}...</Text>
              </View>
            )}
            <Text style={styles.cardSub}>FaceLandmarker mesh, bounding box, and gaze orientation rendered on-device.</Text>
          </View>
        )}
      </ScrollView>

      {/* ── COUNTERFACTUAL SIMULATION MODAL ─────────────────── */}
      <Modal visible={simModalVisible} transparent animationType="slide">
        <View style={styles.modalOverlay}>
          <View style={styles.modalCard}>
            <View style={styles.simModalHeader}>
              <View style={styles.simBadge}>
                <Text style={styles.simBadgeText}>SIMULATED NOTIFICATION</Text>
              </View>
              <TouchableOpacity onPress={() => setSimModalVisible(false)}>
                <Text style={styles.modalCloseIcon}>✕</Text>
              </TouchableOpacity>
            </View>

            {simLoading ? (
              <View style={{ padding: 40, alignItems: "center" }}>
                <ActivityIndicator size="large" color="#f59e0b" />
                <Text style={{ color: "#9ca3af", marginTop: 15 }}>Computing counterfactual futures...</Text>
              </View>
            ) : simResult ? (
              <ScrollView showsVerticalScrollIndicator={false}>
                <Text style={styles.simTitle}>{simResult.notificationTitle || "Simulated Notification"}</Text>

                {/* Path A vs Path B comparison */}
                <View style={styles.dualPathWrap}>
                  <View style={[styles.pathBox, { borderColor: "#ef4444" }]}>
                    <Text style={styles.pathHeader}>Path A: ALLOW Now</Text>
                    <Text style={styles.pathDrop}>
                      {Math.round((simResult.allowPath?.attentionDropProbability || 0.88) * 100)}% Drop
                    </Text>
                    <Text style={styles.pathSub}>
                      {Math.round(simResult.allowPath?.expectedDistractionSec || 180)}s distraction
                    </Text>
                  </View>

                  <View style={[styles.pathBox, { borderColor: "#22d67a" }]}>
                    <Text style={styles.pathHeader}>Path B: DEFER 15m</Text>
                    <Text style={[styles.pathDrop, { color: "#22d67a" }]}>
                      {Math.round((simResult.deferPath?.attentionDropProbability || 0.12) * 100)}% Drop
                    </Text>
                    <Text style={styles.pathSub}>
                      {Math.round(simResult.deferPath?.expectedDistractionSec || 20)}s preserved
                    </Text>
                  </View>
                </View>

                {/* Savings Pill */}
                <View style={styles.savingsBox}>
                  <Text style={styles.savingsText}>
                    ✨ Deferring saves +{simResult.savings?.savedDistractionMinutes || 2.7} min of deep focus (+
                    {simResult.savings?.focusPreservationPct || 86}% flow preserved).
                  </Text>
                </View>

                {/* Recommendation Directive */}
                <View style={styles.recommendationBox}>
                  <Text style={styles.recLabel}>TWIN DIRECTIVE</Text>
                  <Text style={styles.recDirective}>DEFER FOR 15 MINUTES</Text>
                </View>

                <TouchableOpacity style={styles.btnPrimary} onPress={() => setSimModalVisible(false)}>
                  <Text style={styles.btnPrimaryText}>Acknowledge Directive</Text>
                </TouchableOpacity>
              </ScrollView>
            ) : null}
          </View>
        </View>
      </Modal>

      {/* ── SETTINGS MODAL ──────────────────────────────────── */}
      <Modal visible={settingsModalVisible} transparent animationType="fade">
        <View style={styles.modalOverlay}>
          <View style={styles.modalCard}>
            <Text style={styles.modalHeaderTitle}>⚙️ FocusTwin Server Settings</Text>
            <Text style={styles.inputLabel}>Backend Server URL</Text>
            <TextInput
              style={styles.textInput}
              value={tempUrl}
              onChangeText={setTempUrl}
              placeholder="http://192.168.110.224:5050"
              placeholderTextColor="#4b5563"
              autoCapitalize="none"
            />
            <Text style={styles.cardSub}>
              Ensure your mobile phone is connected to the same Wi-Fi network as your computer.
            </Text>

            <View style={styles.modalActionRow}>
              <TouchableOpacity
                style={styles.btnSecondary}
                onPress={() => {
                  setTempUrl(DEFAULT_SERVER_URL);
                  setSettingsModalVisible(false);
                }}
              >
                <Text style={styles.btnSecondaryText}>Cancel</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={styles.btnPrimaryMini}
                onPress={() => {
                  setServerUrl(tempUrl);
                  setSettingsModalVisible(false);
                }}
              >
                <Text style={styles.btnPrimaryText}>Save URL</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>

      {/* ── MOBILE TRACKING CAMERA & PIP PREVIEW ──────────────── */}
      {permission?.granted && cameraMode === "mobile" && (
        <View style={showPip ? styles.pipCameraContainer : styles.hiddenCameraContainer}>
          <CameraView
            ref={cameraRef}
            facing="front"
            onCameraReady={() => setCameraReady(true)}
            style={showPip ? styles.pipCameraView : styles.hiddenCameraView}
          />
          {showPip && (
            <View style={styles.pipOverlay}>
              <View style={styles.pipHeader}>
                <View style={styles.pipIndicatorRow}>
                  <View style={styles.pipLiveDot} />
                  <Text style={styles.pipTitle}>Phone Front Camera</Text>
                </View>
                <TouchableOpacity onPress={() => setShowPip(false)} hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}>
                  <Text style={styles.pipClose}>✕</Text>
                </TouchableOpacity>
              </View>
              <Text style={styles.pipSubtitle}>
                {sessionActive ? "● Tracking Focus • Laptop Off" : "Ready • Laptop Disengaged"}
              </Text>
            </View>
          )}
        </View>
      )}

      {/* Floating button to restore PIP if user minimized it */}
      {!showPip && permission?.granted && cameraMode === "mobile" && (
        <TouchableOpacity style={styles.restorePipBtn} onPress={() => setShowPip(true)}>
          <Text style={styles.restorePipText}>📱 Show Cam PIP</Text>
        </TouchableOpacity>
      )}
    </SafeAreaView>
  );
}

// ── Helpers ────────────────────────────────────────────────────────
function round1(v) {
  return Math.round((v || 0) * 10) / 10;
}
function round2(v) {
  return Math.round((v || 0) * 100) / 100;
}
function formatTime(sec) {
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}
function getStateTheme(st) {
  switch ((st || "").toUpperCase()) {
    case "FLOW":
    case "FOCUSED":
      return { emoji: "🎯", color: "#22d67a" };
    case "DISTRACTED":
      return { emoji: "👀", color: "#f59e0b" };
    case "DROWSY":
      return { emoji: "😴", color: "#ef4444" };
    case "AWAY":
    case "NO FACE":
      return { emoji: "❓", color: "#8899bb" };
    default:
      return { emoji: "🎯", color: "#22d67a" };
  }
}
function getGazeIcon(g) {
  switch ((g || "").toLowerCase()) {
    case "left":
      return "👈";
    case "right":
      return "👉";
    case "up":
      return "⬆️";
    case "down":
      return "⬇️";
    default:
      return "👁️";
  }
}

// ── Styles ─────────────────────────────────────────────────────────
const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: "#080c14",
  },
  header: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: "rgba(255,255,255,0.08)",
  },
  brandRow: {
    flexDirection: "row",
    alignItems: "center",
  },
  logoBadge: {
    width: 38,
    height: 38,
    borderRadius: 19,
    backgroundColor: "rgba(110,231,247,0.15)",
    justifyContent: "center",
    alignItems: "center",
    marginRight: 10,
    borderWidth: 1,
    borderColor: "rgba(110,231,247,0.3)",
  },
  logoIcon: {
    fontSize: 20,
  },
  brandTitle: {
    fontSize: 18,
    fontWeight: "700",
    color: "#f3f4f6",
    letterSpacing: 0.5,
  },
  brandSubtitle: {
    fontSize: 11,
    color: "#9ca3af",
  },
  connectionPill: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: "rgba(255,255,255,0.05)",
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.1)",
  },
  statusDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginRight: 6,
  },
  connectionText: {
    fontSize: 12,
    color: "#d1d5db",
    fontWeight: "500",
  },
  settingsIcon: {
    fontSize: 12,
  },
  navBar: {
    flexDirection: "row",
    backgroundColor: "#0c121e",
    borderBottomWidth: 1,
    borderBottomColor: "rgba(255,255,255,0.06)",
  },
  navTab: {
    flex: 1,
    paddingVertical: 10,
    alignItems: "center",
  },
  navTabActive: {
    borderBottomWidth: 2,
    borderBottomColor: "#f59e0b",
  },
  navTabText: {
    fontSize: 12,
    fontWeight: "600",
    color: "#6b7280",
  },
  navTabTextActive: {
    color: "#f59e0b",
  },
  scrollContent: {
    padding: 16,
    paddingBottom: 40,
  },
  stateHeroCard: {
    backgroundColor: "#0d1525",
    borderRadius: 16,
    padding: 16,
    marginBottom: 14,
    borderWidth: 1.5,
  },
  stateHeroHeader: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: 14,
  },
  stateHeroEmoji: {
    fontSize: 36,
    marginRight: 12,
  },
  stateHeroInfo: {
    flex: 1,
  },
  stateHeroLabel: {
    fontSize: 10,
    color: "#9ca3af",
    fontWeight: "700",
    letterSpacing: 0.8,
  },
  stateHeroValue: {
    fontSize: 22,
    fontWeight: "800",
    letterSpacing: 0.5,
  },
  fpsBadge: {
    backgroundColor: "rgba(255,255,255,0.08)",
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 8,
  },
  fpsText: {
    fontSize: 11,
    color: "#facc15",
    fontWeight: "700",
  },
  confidenceWrap: {
    marginTop: 4,
  },
  confidenceLabelRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    marginBottom: 4,
  },
  confidenceSub: {
    fontSize: 11,
    color: "#9ca3af",
  },
  confidencePct: {
    fontSize: 11,
    color: "#f3f4f6",
    fontWeight: "700",
  },
  barTrack: {
    height: 6,
    backgroundColor: "rgba(255,255,255,0.1)",
    borderRadius: 3,
    overflow: "hidden",
  },
  barFill: {
    height: "100%",
    borderRadius: 3,
  },
  metricsRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    marginBottom: 14,
  },
  metricCard: {
    flex: 1,
    backgroundColor: "#0d1525",
    borderRadius: 12,
    padding: 12,
    marginHorizontal: 3,
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.08)",
    alignItems: "center",
  },
  metricValue: {
    fontSize: 18,
    fontWeight: "700",
    color: "#f3f4f6",
    marginBottom: 2,
  },
  metricTitle: {
    fontSize: 10,
    color: "#9ca3af",
    textAlign: "center",
  },
  metricSub: {
    fontSize: 9,
    color: "#6b7280",
    marginTop: 4,
  },
  miniBarTrack: {
    width: "100%",
    height: 4,
    backgroundColor: "rgba(255,255,255,0.1)",
    borderRadius: 2,
    marginTop: 6,
  },
  miniBarFill: {
    height: "100%",
    backgroundColor: "#3b82f6",
    borderRadius: 2,
  },
  card: {
    backgroundColor: "#0d1525",
    borderRadius: 14,
    padding: 16,
    marginBottom: 14,
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.08)",
  },
  cardTitle: {
    fontSize: 15,
    fontWeight: "700",
    color: "#f3f4f6",
    marginBottom: 10,
  },
  cardSub: {
    fontSize: 12,
    color: "#9ca3af",
    lineHeight: 17,
    marginTop: 4,
  },
  cardHeaderRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
  },
  poseRow: {
    flexDirection: "row",
    justifyContent: "space-around",
    paddingTop: 8,
  },
  poseItem: {
    alignItems: "center",
  },
  poseLabel: {
    fontSize: 11,
    color: "#9ca3af",
  },
  poseVal: {
    fontSize: 17,
    fontWeight: "700",
    color: "#93c5fd",
    marginTop: 2,
  },
  statusTag: {
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 6,
  },
  statusTagText: {
    fontSize: 10,
    fontWeight: "700",
  },
  sessionHeroTimer: {
    fontSize: 34,
    fontWeight: "800",
    color: "#f3f4f6",
    textAlign: "center",
    marginVertical: 8,
    fontFamily: "monospace",
  },
  sessionHeroSub: {
    fontSize: 12,
    color: "#9ca3af",
    textAlign: "center",
  },
  inputLabel: {
    fontSize: 12,
    color: "#9ca3af",
    fontWeight: "600",
    marginTop: 10,
    marginBottom: 6,
  },
  textInput: {
    backgroundColor: "#111827",
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.12)",
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    color: "#f3f4f6",
    fontSize: 14,
  },
  platformRow: {
    flexDirection: "row",
    marginBottom: 16,
  },
  platformPill: {
    flex: 1,
    paddingVertical: 8,
    backgroundColor: "#111827",
    borderRadius: 8,
    alignItems: "center",
    marginHorizontal: 3,
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.08)",
  },
  platformPillActive: {
    backgroundColor: "rgba(37,99,235,0.2)",
    borderColor: "#3b82f6",
  },
  platformPillText: {
    fontSize: 11,
    color: "#9ca3af",
    fontWeight: "600",
  },
  platformPillTextActive: {
    color: "#93c5fd",
  },
  btnPrimary: {
    backgroundColor: "#2563eb",
    paddingVertical: 13,
    borderRadius: 10,
    alignItems: "center",
    marginTop: 6,
  },
  btnPrimaryText: {
    color: "#ffffff",
    fontWeight: "700",
    fontSize: 14,
  },
  btnDanger: {
    backgroundColor: "#dc2626",
    paddingVertical: 13,
    borderRadius: 10,
    alignItems: "center",
    marginTop: 6,
  },
  btnDangerText: {
    color: "#ffffff",
    fontWeight: "700",
    fontSize: 14,
  },
  highlightRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: "rgba(255,255,255,0.04)",
  },
  highlightLabel: {
    fontSize: 13,
    color: "#9ca3af",
  },
  highlightVal: {
    fontSize: 13,
    fontWeight: "700",
    color: "#f3f4f6",
  },
  twinStateTag: {
    backgroundColor: "rgba(167,139,250,0.15)",
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 6,
    borderWidth: 1,
    borderColor: "rgba(167,139,250,0.3)",
  },
  twinStateText: {
    fontSize: 11,
    fontWeight: "700",
    color: "#c4b5fd",
  },
  twinStatusMsg: {
    fontSize: 12,
    color: "#9ca3af",
    lineHeight: 18,
    marginVertical: 10,
  },
  twinMetricsGrid: {
    flexDirection: "row",
    justifyContent: "space-between",
    marginBottom: 12,
  },
  twinMetricBox: {
    flex: 1,
    backgroundColor: "#111827",
    padding: 12,
    borderRadius: 8,
    marginHorizontal: 3,
    alignItems: "center",
  },
  twinBoxVal: {
    fontSize: 18,
    fontWeight: "800",
    color: "#facc15",
  },
  twinBoxLabel: {
    fontSize: 10,
    color: "#9ca3af",
    marginTop: 2,
  },
  circadianBox: {
    backgroundColor: "rgba(245,158,11,0.08)",
    borderWidth: 1,
    borderColor: "rgba(245,158,11,0.25)",
    padding: 12,
    borderRadius: 8,
  },
  circadianTitle: {
    fontSize: 11,
    fontWeight: "700",
    color: "#fbbf24",
  },
  circadianVal: {
    fontSize: 13,
    fontWeight: "600",
    color: "#f3f4f6",
    marginTop: 3,
  },
  simBtnGrid: {
    flexDirection: "row",
    justifyContent: "space-between",
    marginTop: 12,
  },
  simTriggerBtn: {
    flex: 1,
    backgroundColor: "#1e293b",
    paddingVertical: 10,
    borderRadius: 8,
    alignItems: "center",
    marginHorizontal: 4,
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.1)",
  },
  simTriggerText: {
    color: "#f3f4f6",
    fontWeight: "600",
    fontSize: 12,
  },
  cameraWrap: {
    width: "100%",
    height: 220,
    backgroundColor: "#000",
    borderRadius: 10,
    overflow: "hidden",
    marginVertical: 10,
  },
  cameraImage: {
    width: "100%",
    height: "100%",
  },
  cameraPlaceholder: {
    width: "100%",
    height: 220,
    backgroundColor: "#111827",
    borderRadius: 10,
    justifyContent: "center",
    alignItems: "center",
    padding: 20,
    marginVertical: 10,
  },
  cameraPlaceholderText: {
    color: "#6b7280",
    textAlign: "center",
    fontSize: 12,
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: "rgba(0,0,0,0.75)",
    justifyContent: "center",
    padding: 16,
  },
  modalCard: {
    backgroundColor: "#0d1525",
    borderRadius: 16,
    padding: 20,
    borderWidth: 1.5,
    borderColor: "rgba(110,231,247,0.3)",
    maxHeight: "85%",
  },
  modalHeaderTitle: {
    fontSize: 16,
    fontWeight: "700",
    color: "#f3f4f6",
    marginBottom: 8,
  },
  simModalHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 12,
  },
  simBadge: {
    backgroundColor: "rgba(245,158,11,0.15)",
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 6,
    borderWidth: 1,
    borderColor: "rgba(245,158,11,0.4)",
  },
  simBadgeText: {
    fontSize: 10,
    fontWeight: "800",
    color: "#fbbf24",
  },
  modalCloseIcon: {
    fontSize: 18,
    color: "#9ca3af",
    padding: 4,
  },
  simTitle: {
    fontSize: 18,
    fontWeight: "700",
    color: "#f3f4f6",
    marginBottom: 14,
  },
  dualPathWrap: {
    flexDirection: "row",
    justifyContent: "space-between",
    marginBottom: 14,
  },
  pathBox: {
    flex: 1,
    backgroundColor: "#111827",
    padding: 12,
    borderRadius: 10,
    marginHorizontal: 4,
    borderWidth: 1,
  },
  pathHeader: {
    fontSize: 11,
    fontWeight: "700",
    color: "#9ca3af",
    marginBottom: 6,
  },
  pathDrop: {
    fontSize: 18,
    fontWeight: "800",
    color: "#ef4444",
  },
  pathSub: {
    fontSize: 10,
    color: "#6b7280",
    marginTop: 2,
  },
  savingsBox: {
    backgroundColor: "rgba(34,214,122,0.1)",
    borderWidth: 1,
    borderColor: "rgba(34,214,122,0.3)",
    borderRadius: 8,
    padding: 10,
    marginBottom: 12,
  },
  savingsText: {
    fontSize: 12,
    color: "#22d67a",
    fontWeight: "600",
    lineHeight: 17,
  },
  recommendationBox: {
    backgroundColor: "#1e1b4b",
    borderWidth: 1,
    borderColor: "#6366f1",
    borderRadius: 8,
    padding: 12,
    marginBottom: 16,
    alignItems: "center",
  },
  recLabel: {
    fontSize: 10,
    fontWeight: "700",
    color: "#a5b4fc",
    letterSpacing: 0.8,
  },
  recDirective: {
    fontSize: 15,
    fontWeight: "800",
    color: "#ffffff",
    marginTop: 2,
  },
  modalActionRow: {
    flexDirection: "row",
    justifyContent: "flex-end",
    marginTop: 16,
  },
  btnSecondary: {
    paddingVertical: 10,
    paddingHorizontal: 16,
    borderRadius: 8,
    marginRight: 8,
  },
  btnSecondaryText: {
    color: "#9ca3af",
    fontSize: 13,
    fontWeight: "600",
  },
  btnPrimaryMini: {
    backgroundColor: "#2563eb",
    paddingVertical: 10,
    paddingHorizontal: 18,
    borderRadius: 8,
  },
  pipCameraContainer: {
    position: "absolute",
    bottom: 24,
    right: 16,
    width: 140,
    height: 190,
    borderRadius: 14,
    overflow: "hidden",
    borderWidth: 2,
    borderColor: "#38bdf8",
    backgroundColor: "#000",
    shadowColor: "#000",
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.5,
    shadowRadius: 8,
    elevation: 8,
    zIndex: 999,
  },
  pipCameraView: {
    width: "100%",
    height: "100%",
  },
  hiddenCameraContainer: {
    position: "absolute",
    width: 1,
    height: 1,
    opacity: 0.01,
    overflow: "hidden",
  },
  hiddenCameraView: {
    width: 1,
    height: 1,
  },
  pipOverlay: {
    position: "absolute",
    top: 0,
    left: 0,
    right: 0,
    backgroundColor: "rgba(8,12,20,0.75)",
    paddingHorizontal: 8,
    paddingVertical: 5,
  },
  pipHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
  },
  pipIndicatorRow: {
    flexDirection: "row",
    alignItems: "center",
  },
  pipLiveDot: {
    width: 6,
    height: 6,
    borderRadius: 3,
    backgroundColor: "#22d67a",
    marginRight: 4,
  },
  pipTitle: {
    color: "#93c5fd",
    fontSize: 9,
    fontWeight: "700",
  },
  pipClose: {
    color: "#9ca3af",
    fontSize: 12,
    fontWeight: "bold",
    paddingHorizontal: 4,
  },
  pipSubtitle: {
    color: "#9ca3af",
    fontSize: 8,
    marginTop: 2,
  },
  restorePipBtn: {
    position: "absolute",
    bottom: 24,
    right: 16,
    backgroundColor: "rgba(15,23,42,0.9)",
    borderWidth: 1,
    borderColor: "#38bdf8",
    borderRadius: 20,
    paddingHorizontal: 12,
    paddingVertical: 6,
    zIndex: 999,
  },
  restorePipText: {
    color: "#38bdf8",
    fontSize: 11,
    fontWeight: "600",
  },
});

