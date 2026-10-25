/**
 * AttentionAI — Dashboard JavaScript
 * Polls /api/status every 300ms and /api/session_summary every 2s.
 * Draws donut chart + timeline sparkline on <canvas>.
 */

"use strict";

// ── DOM refs ────────────────────────────────────────────────────
const liveDot        = document.getElementById("liveDot");
const liveLabel      = document.getElementById("liveLabel");
const fpsBadge       = document.getElementById("fpsBadge");
const stateCard      = document.getElementById("stateCard");
const stateIcon      = document.getElementById("stateIcon");
const stateLabel     = document.getElementById("stateLabel");
const confidenceBar  = document.getElementById("confidenceBar");
const confidenceText = document.getElementById("confidenceText");
const earValue       = document.getElementById("earValue");
const earBar         = document.getElementById("earBar");
const blinkValue     = document.getElementById("blinkValue");
const gazeIcon       = document.getElementById("gazeIcon");
const gazeValue      = document.getElementById("gazeValue");
const yawValue       = document.getElementById("yawValue");
const pitchValue     = document.getElementById("pitchValue");
const rollValue      = document.getElementById("rollValue");
const gaugeYawFill   = document.getElementById("gaugeYawFill");
const gaugePitchFill = document.getElementById("gaugePitchFill");
const gaugeRollFill  = document.getElementById("gaugeRollFill");
const sessionTimer   = document.getElementById("sessionTimer");
const focusedPct     = document.getElementById("focusedPct");
const lgFocused      = document.getElementById("lgFocused");
const lgDistracted   = document.getElementById("lgDistracted");
const lgDrowsy       = document.getElementById("lgDrowsy");
const donutCanvas    = document.getElementById("donutChart");
const timelineCanvas = document.getElementById("timelineChart");
const videoCanvas    = document.getElementById("videoCanvas");   // ← canvas feed
const btnReset       = document.getElementById("btnReset");
const toast          = document.getElementById("toast");
const noFaceOverlay  = document.getElementById("noFaceOverlay");
const videoWrapper   = document.getElementById("videoWrapper");

// Camera mode controls
const btnSourceLaptop        = document.getElementById("btnSourceLaptop");
const btnSourceMobile        = document.getElementById("btnSourceMobile");
const cameraSourceBadge      = document.getElementById("cameraSourceBadge");
const deviceCameraVideo      = document.getElementById("deviceCameraVideo");
const mobileOffscreenCanvas  = document.getElementById("mobileOffscreenCanvas");
const camIndicatorDot        = document.getElementById("camIndicatorDot");
const camIndicatorText       = document.getElementById("camIndicatorText");

let activeCameraMode         = "laptop"; // 'laptop' | 'mobile'
let deviceMediaStream        = null;
let mobileUploadTimer        = null;
let isUploadingMobileFrame   = false;
const isMobileDevice         = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent) || (window.innerWidth <= 768);

const donutCtx    = donutCanvas.getContext("2d");
const timelineCtx = timelineCanvas.getContext("2d");
const videoCtx    = videoCanvas.getContext("2d");

// ── State ───────────────────────────────────────────────────────
const CONFIG = {
  POLL_STATUS_MS:  300,
  POLL_SESSION_MS: 2000,
  POLL_TIMELINE_MS: 2500,
};

const STATE_META = {
  "Focused":    { icon: "🎯", cls: "focused",    color: "#22d67a" },
  "Distracted": { icon: "👀", cls: "distracted", color: "#f59e0b" },
  "Drowsy":     { icon: "😴", cls: "drowsy",     color: "#ef4444" },
  "No Face":    { icon: "❓", cls: "",            color: "#8899bb" },
};

const GAZE_ICONS = {
  center:  "👁️", left: "👈", right: "👉",
  up: "⬆️", down: "⬇️", unknown: "❓",
};

let sessionStartTime = Date.now();
let timerInterval    = null;
let isConnected      = false;
let donutData        = { focused: 0, distracted: 0, drowsy: 0 };
let timelineData     = [];

// ── Helpers ─────────────────────────────────────────────────────
function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

function angleToBarPercent(deg, maxDeg = 45) {
  // Maps -maxDeg..+maxDeg → 0..100 centered at 50
  const ratio = clamp(deg / maxDeg, -1, 1);
  return 50 + ratio * 50;
}

function showToast(msg, duration = 2500) {
  toast.textContent = msg;
  toast.classList.remove("hidden");
  clearTimeout(toast._timer);
  toast._timer = setTimeout(() => toast.classList.add("hidden"), duration);
}

// ── Session Timer ────────────────────────────────────────────────
function startTimer() {
  sessionStartTime = Date.now();
  clearInterval(timerInterval);
  timerInterval = setInterval(() => {
    const elapsed = Math.floor((Date.now() - sessionStartTime) / 1000);
    const hh = String(Math.floor(elapsed / 3600)).padStart(2, "0");
    const mm = String(Math.floor((elapsed % 3600) / 60)).padStart(2, "0");
    const ss = String(elapsed % 60).padStart(2, "0");
    sessionTimer.textContent = `${hh}:${mm}:${ss}`;
  }, 1000);
}

// ── Update UI from /api/status ───────────────────────────────────
function applyStatus(data) {
  const meta = STATE_META[data.state] || STATE_META["No Face"];

  // Connection indicator
  if (!isConnected) {
    isConnected = true;
    liveDot.classList.add("active");
    liveLabel.textContent = "Live";
  }

  // FPS
  fpsBadge.textContent = `${data.fps} FPS`;

  // Camera source badge & indicators
  const effectiveSource = (data.cameraPreference === "mobile" || data.activeCameraSource === "mobile" || data.camera_source === "mobile") ? "mobile" : (data.camera_source || "laptop");
  if (cameraSourceBadge) {
    if (effectiveSource === "mobile") {
      cameraSourceBadge.textContent = "📱 Mobile Cam";
      cameraSourceBadge.style.color = "#38bdf8";
      cameraSourceBadge.style.borderColor = "rgba(56, 189, 248, 0.4)";
      if (btnSourceMobile) btnSourceMobile.classList.add("active");
      if (btnSourceLaptop) btnSourceLaptop.classList.remove("active");
      if (camIndicatorText) {
        camIndicatorText.innerHTML = "Tracking Camera: <strong>📱 Phone Front Camera (Laptop Disengaged)</strong>";
      }
    } else {
      cameraSourceBadge.textContent = "💻 Laptop Cam";
      cameraSourceBadge.style.color = "#22d67a";
      cameraSourceBadge.style.borderColor = "rgba(34, 214, 122, 0.25)";
      if (btnSourceLaptop && activeCameraMode !== "mobile") btnSourceLaptop.classList.add("active");
      if (btnSourceMobile && activeCameraMode !== "mobile") btnSourceMobile.classList.remove("active");
      if (camIndicatorText && activeCameraMode !== "mobile") {
        camIndicatorText.innerHTML = "Tracking Camera: <strong>💻 Laptop Webcam</strong>";
      }
    }
  }

  // No-face overlay
  if (!data.face_detected) {
    noFaceOverlay.classList.remove("hidden");
  } else {
    noFaceOverlay.classList.add("hidden");
  }

  // State card
  stateCard.className = `state-card ${meta.cls}`;
  stateIcon.textContent = meta.icon;
  stateLabel.textContent = data.state;

  const pct = Math.round((data.confidence || 0) * 100);
  confidenceBar.style.width = `${pct}%`;
  confidenceText.textContent = `Confidence: ${pct}%`;

  // EAR
  earValue.textContent = data.ear.toFixed(3);
  const earPct = clamp((data.ear / 0.35) * 100, 0, 100);
  earBar.style.width = `${earPct}%`;

  // Blink rate
  blinkValue.textContent = data.blink_rate;

  // Gaze
  const gDir = data.gaze || "unknown";
  gazeIcon.textContent = GAZE_ICONS[gDir] || "👁";
  gazeValue.textContent = gDir.charAt(0).toUpperCase() + gDir.slice(1);

  // Head pose
  yawValue.textContent   = `${data.yaw}°`;
  pitchValue.textContent = `${data.pitch}°`;
  rollValue.textContent  = `${data.roll}°`;

  const yPct   = angleToBarPercent(data.yaw);
  const piPct  = angleToBarPercent(data.pitch);
  const roPct  = angleToBarPercent(data.roll);

  setGauge(gaugeYawFill,   yPct);
  setGauge(gaugePitchFill, piPct);
  setGauge(gaugeRollFill,  roPct);
}

function setGauge(el, pct) {
  // Center at 50% → display as bar extending left or right from center
  const half = Math.abs(pct - 50);
  if (pct >= 50) {
    el.style.left  = "50%";
    el.style.width = `${half}%`;
  } else {
    el.style.left  = `${pct}%`;
    el.style.width = `${half}%`;
  }
}

// ── Donut Chart ──────────────────────────────────────────────────
function drawDonut(f, d, dr) {
  const cx = donutCanvas.width  / 2;
  const cy = donutCanvas.height / 2;
  const R  = 68;
  const r  = 44;
  const total = f + d + dr || 1;

  donutCtx.clearRect(0, 0, donutCanvas.width, donutCanvas.height);

  const slices = [
    { value: f,  color: "#22d67a" },
    { value: d,  color: "#f59e0b" },
    { value: dr, color: "#ef4444" },
  ];

  let startAngle = -Math.PI / 2;
  for (const { value, color } of slices) {
    const sweep = (value / total) * 2 * Math.PI;
    donutCtx.beginPath();
    donutCtx.moveTo(cx, cy);
    donutCtx.arc(cx, cy, R, startAngle, startAngle + sweep);
    donutCtx.closePath();
    donutCtx.fillStyle = color;
    donutCtx.fill();
    startAngle += sweep;
  }

  // Inner cutout
  donutCtx.beginPath();
  donutCtx.arc(cx, cy, r, 0, 2 * Math.PI);
  donutCtx.fillStyle = "#111827";
  donutCtx.fill();

  // Thin border ring
  donutCtx.beginPath();
  donutCtx.arc(cx, cy, R, 0, 2 * Math.PI);
  donutCtx.strokeStyle = "rgba(255,255,255,0.04)";
  donutCtx.lineWidth = 2;
  donutCtx.stroke();
}

function applySession(data) {
  const f  = data.focused_pct    || 0;
  const d  = data.distracted_pct || 0;
  const dr = data.drowsy_pct     || 0;

  donutData = { focused: f, distracted: d, drowsy: dr };
  drawDonut(f, d, dr);

  focusedPct.textContent   = `${f}%`;
  lgFocused.textContent    = `${f}%`;
  lgDistracted.textContent = `${d}%`;
  lgDrowsy.textContent     = `${dr}%`;

  // ── Performance Highlights ─────────────────────────────────────
  function fmtSec(s) {
    if (!s || s < 1) return "—";
    const m   = Math.floor(s / 60);
    const sec = Math.round(s % 60);
    return m > 0 ? `${m}m ${sec}s` : `${sec}s`;
  }

  const streak  = data.best_focus_streak_sec || 0;
  const distLen = data.longest_distract_sec  || 0;
  const score   = (data.attention_score !== undefined) ? data.attention_score : null;

  document.getElementById("hlFocusStreakVal").textContent = fmtSec(streak);
  document.getElementById("hlDistractVal").textContent    = fmtSec(distLen);

  if (score !== null) {
    document.getElementById("hlScoreVal").textContent = `${score} / 100`;
    document.getElementById("hlScoreBar").style.width = `${score}%`;
  }
}

// ── Timeline Sparkline ────────────────────────────────────────────
const STATE_Y = { "Focused": 0.1, "Distracted": 0.5, "Drowsy": 0.9, "No Face": 0.5 };
const STATE_C = { "Focused": "#22d67a", "Distracted": "#f59e0b", "Drowsy": "#ef4444", "No Face": "#8899bb" };

function drawTimeline(records) {
  const cw = timelineCanvas.offsetWidth  || timelineCanvas.width;
  const ch = timelineCanvas.height;
  timelineCanvas.width = cw;

  timelineCtx.clearRect(0, 0, cw, ch);

  if (!records || records.length < 2) {
    timelineCtx.fillStyle = "rgba(255,255,255,0.06)";
    const msg = "Collecting data…";
    timelineCtx.font = "12px Inter, sans-serif";
    timelineCtx.textAlign = "center";
    timelineCtx.fillText(msg, cw / 2, ch / 2 + 4);
    return;
  }

  const maxTs = records[records.length - 1].ts;
  const minTs = records[0].ts;
  const tsRange = maxTs - minTs || 1;

  // Draw grid lines
  timelineCtx.strokeStyle = "rgba(255,255,255,0.04)";
  timelineCtx.lineWidth = 1;
  [0.1, 0.5, 0.9].forEach(yf => {
    const y = yf * ch;
    timelineCtx.beginPath();
    timelineCtx.moveTo(0, y); timelineCtx.lineTo(cw, y);
    timelineCtx.stroke();
  });

  // Draw each segment with its color
  for (let i = 1; i < records.length; i++) {
    const prev = records[i - 1];
    const curr = records[i];
    const x0 = ((prev.ts - minTs) / tsRange) * cw;
    const x1 = ((curr.ts - minTs) / tsRange) * cw;
    const y0 = STATE_Y[prev.state] * ch;
    const y1 = STATE_Y[curr.state] * ch;

    timelineCtx.beginPath();
    timelineCtx.moveTo(x0, y0);
    timelineCtx.lineTo(x1, y1);
    timelineCtx.strokeStyle = STATE_C[curr.state] || "#8899bb";
    timelineCtx.lineWidth = 2.5;
    timelineCtx.lineJoin = "round";
    timelineCtx.stroke();
  }

  // Dot at last point
  const last = records[records.length - 1];
  const lx = cw - 4;
  const ly = STATE_Y[last.state] * ch;
  timelineCtx.beginPath();
  timelineCtx.arc(lx, ly, 4, 0, 2 * Math.PI);
  timelineCtx.fillStyle = STATE_C[last.state] || "#8899bb";
  timelineCtx.fill();

  // Labels
  timelineCtx.font = "9px JetBrains Mono, monospace";
  timelineCtx.fillStyle = "rgba(255,255,255,0.3)";
  timelineCtx.textAlign = "left";
  timelineCtx.fillText("Focused   ─", 6, 0.1 * ch + 3);
  timelineCtx.fillText("Distracted─", 6, 0.5 * ch + 3);
  timelineCtx.fillText("Drowsy    ─", 6, 0.9 * ch + 3);
}

// ── Polling ──────────────────────────────────────────────────────
async function pollStatus() {
  try {
    const r = await fetch("/api/status");
    if (!r.ok) throw new Error();
    const data = await r.json();
    applyStatus(data);
    if (typeof window.handleAlerts === "function") {
      window.handleAlerts(data.state, CONFIG.POLL_STATUS_MS);
    }
  } catch (e) {
    if (isConnected) {
      liveLabel.textContent = "Reconnecting…";
      liveDot.classList.remove("active");
      isConnected = false;
    }
  }
}

async function pollSession() {
  try {
    const r = await fetch("/api/session_summary");
    if (r.ok) applySession(await r.json());
  } catch (_) {}
}

async function pollTimeline() {
  try {
    const r = await fetch("/api/timeline");
    if (r.ok) {
      timelineData = await r.json();
      drawTimeline(timelineData);
    }
  } catch (_) {}
}

// ── Reset Session ─────────────────────────────────────────────────
btnReset.addEventListener("click", async () => {
  try {
    await fetch("/api/reset_session", { method: "POST" });
    startTimer();
    timelineData = [];
    drawDonut(0, 0, 0);
    showToast("✅ Session reset successfully");
  } catch (e) {
    showToast("❌ Failed to reset session");
  }
});

// ── Export CSV ─────────────────────────────────────────────────
const btnExportCSV = document.getElementById("btnExportCSV");
if (btnExportCSV) {
  btnExportCSV.addEventListener("click", async () => {
    btnExportCSV.classList.add("downloading");
    btnExportCSV.textContent = "⏳ Downloading...";
    try {
      const resp = await fetch("/api/export_csv");
      if (!resp.ok) throw new Error("export failed");
      const blob     = await resp.blob();
      const url      = URL.createObjectURL(blob);
      const filename = (resp.headers.get("Content-Disposition") || "attentionai.csv")
        .split("filename=")[1] || "attentionai_session.csv";
      const a = document.createElement("a");
      a.href     = url;
      a.download = filename;
      a.click();
      URL.revokeObjectURL(url);
      showToast("📅 Session exported successfully");
    } catch (e) {
      showToast("❌ Export failed");
    } finally {
      btnExportCSV.classList.remove("downloading");
      btnExportCSV.textContent = "📅 Export CSV";
    }
  });
}

// ── Phase 1: User Profile & Study Session Controller ───────────────
let currentUser          = null;
let currentActiveSession = null;
let studyTimerInterval   = null;

// User Profile DOM
const userDisplayName        = document.getElementById("userDisplayName");
const userDisplayId          = document.getElementById("userDisplayId");
const userProfileBadge       = document.getElementById("userProfileBadge");
const btnToggleProfileModal  = document.getElementById("btnToggleProfileModal");
const profileModal           = document.getElementById("profileModal");
const btnCloseProfileModal   = document.getElementById("btnCloseProfileModal");
const modalUserId            = document.getElementById("modalUserId");
const btnCopyUserId          = document.getElementById("btnCopyUserId");
const modalUserName          = document.getElementById("modalUserName");
const modalUserEmail         = document.getElementById("modalUserEmail");
const btnSaveProfile         = document.getElementById("btnSaveProfile");
const btnCreateNewUser       = document.getElementById("btnCreateNewUser");

// Study Session DOM
const sessionInactiveGroup   = document.getElementById("sessionInactiveGroup");
const inputCourseName        = document.getElementById("inputCourseName");
const selectPlatform         = document.getElementById("selectPlatform");
const inputCourseUrl         = document.getElementById("inputCourseUrl");
const btnStartSession        = document.getElementById("btnStartSession");

const sessionActiveGroup     = document.getElementById("sessionActiveGroup");
const activeSessionIdTag     = document.getElementById("activeSessionIdTag");
const activeCourseTitle      = document.getElementById("activeCourseTitle");
const activePlatformPill     = document.getElementById("activePlatformPill");
const activeSessionTimer     = document.getElementById("activeSessionTimer");
const btnEndSession          = document.getElementById("btnEndSession");

const lastSessionNotice      = document.getElementById("lastSessionNotice");
const lastCourseName         = document.getElementById("lastCourseName");
const lastPlatform           = document.getElementById("lastPlatform");
const lastDuration           = document.getElementById("lastDuration");

function formatSeconds(totalSec) {
  const s = Math.max(0, Math.floor(totalSec));
  const hh = String(Math.floor(s / 3600)).padStart(2, "0");
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, "0");
  const ss = String(s % 60).padStart(2, "0");
  return `${hh}:${mm}:${ss}`;
}

function formatDurationHuman(sec) {
  const s = Math.round(sec);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const remS = s % 60;
  if (m < 60) return `${m}m ${remS}s`;
  const h = Math.floor(m / 60);
  const remM = m % 60;
  return `${h}h ${remM}m`;
}

function startStudySessionTimer(startTimeIso) {
  clearInterval(studyTimerInterval);
  const startMs = new Date(startTimeIso).getTime();

  function update() {
    const elapsed = Math.max(0, Math.floor((Date.now() - startMs) / 1000));
    activeSessionTimer.textContent = formatSeconds(elapsed);

    // Live focus score update as duration grows
    const tabSwitches = parseInt(document.getElementById("activeTabSwitchVal")?.textContent || "0", 10);
    const tabDistract = parseFloat(document.getElementById("activeDistractionDurVal")?.getAttribute("data-sec") || "0");
    const focusLost = parseFloat(document.getElementById("activeFocusLostVal")?.getAttribute("data-sec") || "0");
    const drowsy = parseFloat(document.getElementById("activeDrowsyVal")?.getAttribute("data-sec") || "0");
    const totalDist = tabDistract + focusLost;

    const pD = Math.min(100, (totalDist / Math.max(1, elapsed)) * 100);
    const pS = Math.min(100, (drowsy / Math.max(1, elapsed)) * 100);
    const score = Math.max(0, Math.min(100, Math.round(100.0 - (pD * 0.6) - (pS * 0.8) - Math.min(15, tabSwitches * 1.5))));
    const hudFocus = document.getElementById("activeLiveFocusVal");
    if (hudFocus) hudFocus.textContent = `${score}%`;
  }

  update();
  studyTimerInterval = setInterval(update, 1000);
}

const sessionEventsBox   = document.getElementById("sessionEventsBox");
const eventsCountBadge   = document.getElementById("eventsCountBadge");
const eventsScrollList   = document.getElementById("eventsScrollList");
const eventsEmptyHint    = document.getElementById("eventsEmptyHint");
const btnTestTabSwitch   = document.getElementById("btnTestTabSwitch");
const btnTestVideoPause  = document.getElementById("btnTestVideoPause");
const btnTestFocusLost   = document.getElementById("btnTestFocusLost");

const EVENT_ICONS = {
  TAB_SWITCH:         "🔀",
  PAGE_HIDDEN:        "🙈",
  PAGE_VISIBLE:       "👀",
  DROWSINESS:         "😴",
  DROWSINESS_STARTED: "😴",
  DROWSINESS_ENDED:   "⚡",
  FOCUS_LOST:         "⚠️",
  FOCUS_REGAINED:     "🎯",
  VIDEO_STARTED:      "▶",
  VIDEO_PAUSED:       "⏸",
  VIDEO_RESUMED:      "▶",
  VIDEO_COMPLETED:    "🏁",
  PAGE_CHANGED:       "📄",
  CONTENT_INTERACTED: "👆",
};

function renderEventRow(evt) {
  const row = document.createElement("div");
  row.className = "event-item-row";
  const icon = EVENT_ICONS[evt.event_type] || "📌";
  const timeStr = evt.timestamp ? new Date(evt.timestamp).toLocaleTimeString() : "";
  let metaHtml = "";
  if (evt.metadata && typeof evt.metadata === "object") {
    const pairs = Object.entries(evt.metadata).slice(0, 2);
    metaHtml = pairs.map(([k, v]) => `<span class="event-meta-pill">${k}: ${typeof v === "object" ? JSON.stringify(v) : v}</span>`).join(" ");
  }
  if (evt.duration && evt.duration > 0) {
    metaHtml += ` <span class="event-meta-pill" style="color:var(--focused)">${evt.duration}s</span>`;
  }
  row.innerHTML = `
    <span class="event-item-type">${icon} ${evt.event_type}</span>
    <div class="event-item-meta">
      ${metaHtml}
      <span class="event-item-time">${timeStr}</span>
    </div>
  `;
  return row;
}

async function loadSessionEvents(sessionId) {
  if (!sessionId || typeof window.getSessionEvents !== "function") return;
  try {
    const events = await window.getSessionEvents(sessionId, 50);
    eventsScrollList.innerHTML = "";
    if (!events || events.length === 0) {
      eventsScrollList.innerHTML = '<div class="events-empty-hint">No events logged yet for this session</div>';
      eventsCountBadge.textContent = "0 events";
    } else {
      eventsCountBadge.textContent = `${events.length} event${events.length === 1 ? '' : 's'}`;
      events.forEach(evt => eventsScrollList.appendChild(renderEventRow(evt)));
    }

    // Phase 3 & 5 Derived Session Metrics
    const tabSwitches = events ? events.filter(e => e.event_type === "TAB_SWITCH" || e.event_type === "PAGE_HIDDEN").length : 0;
    const tabDistractSec = events ? events.filter(e => e.event_type === "PAGE_VISIBLE" || (e.event_type === "FOCUS_REGAINED" && e.metadata && (e.metadata.source === "chrome_extension_tabs" || e.metadata.source === "page_visibility_api"))).reduce((sum, e) => sum + (e.duration || 0), 0) : 0;

    const drowsyEvents = events ? events.filter(e => e.event_type === "DROWSINESS_STARTED" || e.event_type === "DROWSINESS").length : 0;
    const drowsySec = events ? events.filter(e => e.event_type === "DROWSINESS_ENDED").reduce((sum, e) => sum + (e.duration || 0), 0) : 0;

    const focusLostEvents = events ? events.filter(e => e.event_type === "FOCUS_LOST").length : 0;
    const focusLostSec = events ? events.filter(e => e.event_type === "FOCUS_REGAINED" && (!e.metadata || e.metadata.source !== "chrome_extension_tabs")).reduce((sum, e) => sum + (e.duration || 0), 0) : 0;

    const tabSwitchEl = document.getElementById("activeTabSwitchVal");
    const distractDurEl = document.getElementById("activeDistractionDurVal");
    const drowsyEl = document.getElementById("activeDrowsyVal");
    const focusLostEl = document.getElementById("activeFocusLostVal");

    if (tabSwitchEl) tabSwitchEl.textContent = tabSwitches;
    if (distractDurEl) {
      distractDurEl.textContent = formatDurationHuman(tabDistractSec);
      distractDurEl.setAttribute("data-sec", tabDistractSec);
    }
    if (drowsyEl) {
      drowsyEl.textContent = `${drowsyEvents} (${Math.round(drowsySec)}s)`;
      drowsyEl.setAttribute("data-sec", drowsySec);
    }
    if (focusLostEl) {
      focusLostEl.textContent = `${focusLostEvents} (${Math.round(focusLostSec)}s)`;
      focusLostEl.setAttribute("data-sec", focusLostSec);
    }

    // Phase 7 Live Monitoring HUD calculation
    const totalDistractSec = tabDistractSec + focusLostSec;
    const elapsedSec = currentActiveSession ? Math.max(1, Math.floor((Date.now() - new Date(currentActiveSession.start_time).getTime()) / 1000)) : 1;
    const pDistract = Math.min(100, (totalDistractSec / elapsedSec) * 100);
    const pDrowsy = Math.min(100, (drowsySec / elapsedSec) * 100);
    const distPenalty = pDistract * 0.60;
    const drowPenalty = pDrowsy * 0.80;
    const tabPenalty = Math.min(15.0, tabSwitches * 1.5);
    const liveFocusScore = Math.max(0, Math.min(100, Math.round(100.0 - distPenalty - drowPenalty - tabPenalty)));

    const hudFocus = document.getElementById("activeLiveFocusVal");
    const hudDistract = document.getElementById("activeLiveDistractionVal");
    const hudDrowsy = document.getElementById("activeLiveDrowsyVal");
    const hudTabs = document.getElementById("activeLiveTabSwitchesVal");

    if (hudFocus) hudFocus.textContent = `${liveFocusScore}%`;
    if (hudDistract) hudDistract.textContent = formatDurationHuman(totalDistractSec);
    if (hudDrowsy) hudDrowsy.textContent = `${Math.round(drowsySec)}s`;
    if (hudTabs) hudTabs.textContent = tabSwitches;

  } catch (e) {
    console.error("[StudyTracker] loadSessionEvents error:", e);
  }
}

window.addEventListener("studySessionEventLogged", (e) => {
  const evt = e.detail;
  if (!evt) return;
  const hint = eventsScrollList.querySelector(".events-empty-hint");
  if (hint) hint.remove();
  eventsScrollList.insertBefore(renderEventRow(evt), eventsScrollList.firstChild);
  const count = eventsScrollList.querySelectorAll(".event-item-row").length;
  eventsCountBadge.textContent = `${count} event${count === 1 ? '' : 's'}`;

  // Recalculate metrics
  if (currentActiveSession) {
    loadSessionEvents(currentActiveSession.session_id);
  }
});

if (btnTestTabSwitch) {
  btnTestTabSwitch.addEventListener("click", async () => {
    if (!currentActiveSession) return;
    try {
      await window.logSessionEvent(currentActiveSession.session_id, "TAB_SWITCH", { from: "Coursera", to: "YouTube" });
      showToast("🔀 Logged TAB_SWITCH event");
    } catch (err) {
      showToast(`❌ ${err.message}`);
    }
  });
}

if (btnTestVideoPause) {
  btnTestVideoPause.addEventListener("click", async () => {
    if (!currentActiveSession) return;
    try {
      await window.logSessionEvent(currentActiveSession.session_id, "VIDEO_PAUSED", {
        videoProgress: { currentTime: 142.5, duration: 600.0, progressPercent: 23.8 },
        player: "coursera-html5",
      });
      showToast("⏸ Logged VIDEO_PAUSED event");
    } catch (err) {
      showToast(`❌ ${err.message}`);
    }
  });
}

const btnTestVideoStart = document.getElementById("btnTestVideoStart");
if (btnTestVideoStart) {
  btnTestVideoStart.addEventListener("click", async () => {
    if (!currentActiveSession) return;
    try {
      await window.logSessionEvent(currentActiveSession.session_id, "VIDEO_STARTED", {
        videoProgress: { currentTime: 0.0, duration: 600.0, progressPercent: 0.0 },
        lecture: "Introduction to Neural Networks",
      });
      showToast("▶ Logged VIDEO_STARTED event");
    } catch (err) {
      showToast(`❌ ${err.message}`);
    }
  });
}

const btnTestVideoEnd = document.getElementById("btnTestVideoEnd");
if (btnTestVideoEnd) {
  btnTestVideoEnd.addEventListener("click", async () => {
    if (!currentActiveSession) return;
    try {
      await window.logSessionEvent(currentActiveSession.session_id, "VIDEO_COMPLETED", {
        videoProgress: { currentTime: 600.0, duration: 600.0, progressPercent: 100.0 },
        lecture: "Introduction to Neural Networks",
      });
      showToast("🏁 Logged VIDEO_COMPLETED event");
    } catch (err) {
      showToast(`❌ ${err.message}`);
    }
  });
}

const btnTestPageChange = document.getElementById("btnTestPageChange");
if (btnTestPageChange) {
  btnTestPageChange.addEventListener("click", async () => {
    if (!currentActiveSession) return;
    try {
      await window.logSessionEvent(currentActiveSession.session_id, "PAGE_CHANGED", {
        previousTitle: "Module 1: Binary Classification",
        newTitle: "Module 2: Logistic Regression Cost Function",
        domain: "coursera.org",
      });
      showToast("📄 Logged PAGE_CHANGED event");
    } catch (err) {
      showToast(`❌ ${err.message}`);
    }
  });
}

const btnSimDrowsy = document.getElementById("btnSimDrowsy");
if (btnSimDrowsy) {
  btnSimDrowsy.addEventListener("click", async () => {
    try {
      await fetch("/api/cv/simulate_state", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ state: "Drowsy" }),
      });
      showToast("😴 Simulated Drowsy CV state");
      if (currentActiveSession) loadSessionEvents(currentActiveSession.session_id);
    } catch (err) {
      showToast(`❌ ${err.message}`);
    }
  });
}

const btnSimDistracted = document.getElementById("btnSimDistracted");
if (btnSimDistracted) {
  btnSimDistracted.addEventListener("click", async () => {
    try {
      await fetch("/api/cv/simulate_state", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ state: "Distracted" }),
      });
      showToast("👀 Simulated Distracted CV state");
      if (currentActiveSession) loadSessionEvents(currentActiveSession.session_id);
    } catch (err) {
      showToast(`❌ ${err.message}`);
    }
  });
}

const btnSimFocused = document.getElementById("btnSimFocused");
if (btnSimFocused) {
  btnSimFocused.addEventListener("click", async () => {
    try {
      await fetch("/api/cv/simulate_state", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ state: "Focused" }),
      });
      showToast("🎯 Returned to Focused state");
      if (currentActiveSession) loadSessionEvents(currentActiveSession.session_id);
    } catch (err) {
      showToast(`❌ ${err.message}`);
    }
  });
}


function renderActiveSession(session) {
  currentActiveSession = session;
  window.currentActiveSession = session;
  sessionInactiveGroup.classList.add("hidden");
  sessionActiveGroup.classList.remove("hidden");

  activeSessionIdTag.textContent = session.session_id;
  activeCourseTitle.textContent  = session.course_name;
  activePlatformPill.textContent = session.platform;

  startStudySessionTimer(session.start_time);
  loadSessionEvents(session.session_id);
}

function renderInactiveSession() {
  currentActiveSession = null;
  window.currentActiveSession = null;
  clearInterval(studyTimerInterval);
  sessionActiveGroup.classList.add("hidden");
  sessionInactiveGroup.classList.remove("hidden");
  loadLastCompletedSession();
}

async function loadLastCompletedSession() {
  if (!currentUser) return;
  try {
    const resp = await fetch(`/api/sessions?userId=${encodeURIComponent(currentUser.user_id)}`);
    if (!resp.ok) return;
    const list = await resp.json();
    const completed = list.filter(s => s.status === "COMPLETED");
    if (completed.length > 0) {
      const last = completed[0];
      lastCourseName.textContent = last.course_name;
      lastPlatform.textContent   = last.platform;
      lastDuration.textContent   = `⏱ ${formatDurationHuman(last.duration || 0)}`;
      lastSessionNotice.classList.remove("hidden");
    } else {
      lastSessionNotice.classList.add("hidden");
    }
  } catch (_) {}
}

async function checkActiveSession() {
  if (!currentUser) return;
  try {
    const resp = await fetch(`/api/sessions/active?userId=${encodeURIComponent(currentUser.user_id)}`);
    if (!resp.ok) return;
    const data = await resp.json();
    if (data.active && data.session) {
      renderActiveSession(data.session);
    } else {
      renderInactiveSession();
    }
  } catch (_) {
    renderInactiveSession();
  }
}

async function initUserProfile() {
  try {
    const resp = await fetch("/api/users");
    if (!resp.ok) throw new Error("Failed to fetch users");
    const users = await resp.json();

    const savedId = localStorage.getItem("studyTracker_userId");
    if (savedId) {
      currentUser = users.find(u => u.user_id === savedId) || users[0];
    } else if (users.length > 0) {
      currentUser = users[0];
    }

    if (currentUser) {
      localStorage.setItem("studyTracker_userId", currentUser.user_id);
      applyUserToUI(currentUser);
      await checkActiveSession();
      await loadTodaySessions();
    }
  } catch (err) {
    console.error("[StudyTracker] initUserProfile error:", err);
  }
}

function applyUserToUI(user) {
  if (!user) return;
  userDisplayName.textContent = user.name;
  userDisplayId.textContent   = user.user_id;
  modalUserId.textContent     = user.user_id;
  modalUserName.value         = user.name;
  modalUserEmail.value        = user.email;
}

// ── Profile Modal Events ──────────────────────────────────────────
function openProfileModal() {
  if (currentUser) applyUserToUI(currentUser);
  profileModal.classList.remove("hidden");
}
function closeProfileModal() {
  profileModal.classList.add("hidden");
}

btnToggleProfileModal.addEventListener("click", openProfileModal);
userProfileBadge.addEventListener("click", openProfileModal);
btnCloseProfileModal.addEventListener("click", closeProfileModal);
profileModal.addEventListener("click", (e) => {
  if (e.target === profileModal) closeProfileModal();
});

// ── Mobile Expo App Modal ───────────────────────────────────────
const btnExpoModal = document.getElementById("btnExpoModal");
const expoModalBackdrop = document.getElementById("expoModalBackdrop");
const btnCloseExpoModal = document.getElementById("btnCloseExpoModal");
if (btnExpoModal && expoModalBackdrop) {
  btnExpoModal.addEventListener("click", () => expoModalBackdrop.classList.remove("hidden"));
}
if (btnCloseExpoModal && expoModalBackdrop) {
  btnCloseExpoModal.addEventListener("click", () => expoModalBackdrop.classList.add("hidden"));
}
if (expoModalBackdrop) {
  expoModalBackdrop.addEventListener("click", (e) => {
    if (e.target === expoModalBackdrop) expoModalBackdrop.classList.add("hidden");
  });
}

btnCopyUserId.addEventListener("click", async () => {
  if (currentUser) {
    await navigator.clipboard.writeText(currentUser.user_id);
    showToast(`📋 Copied User ID: ${currentUser.user_id}`);
  }
});

btnSaveProfile.addEventListener("click", async () => {
  if (!currentUser) return;
  const name = modalUserName.value.trim();
  const email = modalUserEmail.value.trim();
  if (!name || !email) {
    showToast("⚠️ Name and email are required");
    return;
  }
  try {
    const resp = await fetch(`/api/users/${currentUser.user_id}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name, email }),
    });
    if (!resp.ok) throw new Error();
    currentUser = await resp.json();
    applyUserToUI(currentUser);
    closeProfileModal();
    showToast("✅ Profile saved successfully");
  } catch (_) {
    showToast("❌ Failed to update profile");
  }
});

btnCreateNewUser.addEventListener("click", async () => {
  const name = prompt("Enter new user name:", "Student");
  if (!name || !name.trim()) return;
  const email = prompt("Enter email address:", `student_${Date.now().toString().slice(-4)}@studytracker.local`);
  if (!email || !email.trim()) return;

  try {
    const resp = await fetch("/api/users", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: name.trim(), email: email.trim() }),
    });
    if (!resp.ok) {
      const err = await resp.json();
      throw new Error(err.error || "Creation failed");
    }
    currentUser = await resp.json();
    localStorage.setItem("studyTracker_userId", currentUser.user_id);
    applyUserToUI(currentUser);
    closeProfileModal();
    showToast(`✅ Created new user: ${currentUser.name}`);
    await checkActiveSession();
  } catch (err) {
    showToast(`❌ ${err.message || "Could not create user"}`);
  }
});

// ── Start / End Session Actions ───────────────────────────────────
btnStartSession.addEventListener("click", async () => {
  if (!currentUser) {
    showToast("⚠️ Please select or create a user profile first");
    return;
  }
  const courseName = inputCourseName.value.trim() || "General Study";
  const platform   = selectPlatform.value || "Coursera";
  const courseUrl  = inputCourseUrl.value.trim();

  const isMobileSession = isMobileDevice || activeCameraMode === "mobile";

  // When starting on mobile, ensure mobile camera is requested and opened
  if (isMobileSession && (!deviceMediaStream || activeCameraMode !== "mobile")) {
    try {
      await switchCameraMode("mobile", false);
    } catch (_) {}
  }

  try {
    const resp = await fetch("/api/sessions/start", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        userId: currentUser.user_id,
        courseName,
        platform,
        courseUrl,
        device: isMobileSession ? "mobile" : "laptop",
        source: isMobileSession ? "mobile" : "laptop",
        cameraSource: isMobileSession ? "mobile" : "laptop",
        isMobile: isMobileSession,
      }),
    });

    const data = await resp.json();
    if (resp.status === 409) {
      showToast("⚠️ Resumed your existing active session");
      const existing = data.session || data.activeSession;
      if (existing) {
        renderActiveSession(existing);
      } else {
        await checkActiveSession();
      }
      return;
    }

    if (!resp.ok) {
      throw new Error(data.error || "Failed to start session");
    }

    renderActiveSession(data.session);
    showToast(`🎯 Study Session Started: ${data.session.course_name}`);
    loadTodaySessions();
    if (typeof window.logFirebaseSessionEvent === "function") {
      window.logFirebaseSessionEvent("study_session_start", {
        session_id: data.session.session_id,
        course_name: data.session.course_name,
        platform: data.session.platform,
      });
    }
  } catch (err) {
    showToast(`❌ ${err.message}`);
  } finally {
    btnStartSession.disabled = false;
    btnStartSession.textContent = "▶ Start Study Session";
  }
});

btnEndSession.addEventListener("click", async () => {
  if (!currentActiveSession) return;

  btnEndSession.disabled = true;
  btnEndSession.textContent = "⏳ Ending...";

  try {
    const resp = await fetch(`/api/sessions/${encodeURIComponent(currentActiveSession.session_id)}/end`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
    });

    const data = await resp.json();
    if (!resp.ok) {
      throw new Error(data.error || "Failed to end session");
    }

    const durationStr = formatDurationHuman(data.session.duration || 0);
    const score = data.session.focusScore != null ? Math.round(data.session.focusScore) : 100;
    renderInactiveSession();
    showToast(`✅ Session Ended! Duration: ${durationStr} · Focus: ${score}%`);
    loadTodaySessions();
    if (typeof window.logFirebaseSessionEvent === "function") {
      window.logFirebaseSessionEvent("study_session_end", {
        session_id: data.session.session_id,
        duration: data.session.duration,
        focus_score: score,
      });
    }
    if (window.ProductivityAnalytics && typeof window.ProductivityAnalytics.load === "function") {
      window.ProductivityAnalytics.load();
    }
  } catch (err) {
    showToast(`❌ ${err.message}`);
  } finally {
    btnEndSession.disabled = false;
    btnEndSession.textContent = "⏹ End Session";
  }
});

// ── Course Quick Presets ──────────────────────────────────────────
document.querySelectorAll(".btn-preset").forEach(btn => {
  btn.addEventListener("click", () => {
    if (inputCourseName) inputCourseName.value = btn.dataset.course || "";
    if (selectPlatform)  selectPlatform.value  = btn.dataset.platform || "Coursera";
    if (inputCourseUrl)  inputCourseUrl.value  = btn.dataset.url || "";
    showToast(`📚 Selected: ${btn.dataset.course}`);
  });
});

// ── Today's Sessions Summary (Phase 7) ────────────────────────────
async function loadTodaySessions() {
  const userId = currentUser ? currentUser.user_id : localStorage.getItem("studyTracker_userId");
  const userParam = userId ? `?userId=${encodeURIComponent(userId)}` : "";

  try {
    const [sumResp, sessResp] = await Promise.all([
      fetch(`/api/analytics/summary${userParam}`),
      fetch(`/api/analytics/sessions${userParam}&limit=30`),
    ]);

    if (!sumResp.ok || !sessResp.ok) return;

    const summary = await sumResp.json();
    const allSessions = await sessResp.json();

    const nowLocal = new Date();
    const todayYMD = `${nowLocal.getFullYear()}-${String(nowLocal.getMonth() + 1).padStart(2, '0')}-${String(nowLocal.getDate()).padStart(2, '0')}`;

    const todaySessions = allSessions.filter(s => {
      if (!s.start_time) return false;
      const sDate = new Date(s.start_time);
      const sYMD = `${sDate.getFullYear()}-${String(sDate.getMonth() + 1).padStart(2, '0')}-${String(sDate.getDate()).padStart(2, '0')}`;
      return sYMD === todayYMD;
    });

    const countEl = document.getElementById("todaySessionsCount");
    const listEl = document.getElementById("todaySessionsList");
    const bestTimeEl = document.getElementById("todayBestTimeText");

    if (countEl) countEl.textContent = `${todaySessions.length}`;

    if (bestTimeEl) {
      const best = summary.bestStudyTime;
      if (best && best.hasRecommendation) {
        bestTimeEl.textContent = `🌟 Best Time: ${best.bestWindow || best.bestPeriod} (${Math.round(best.avgFocusScore)}%)`;
      } else {
        bestTimeEl.textContent = `🌟 Best Time: Need ≥ 2 sessions`;
      }
    }

    if (listEl) {
      if (!todaySessions.length) {
        listEl.innerHTML = `<div class="today-empty-hint">No sessions completed today yet. Start a session to track focus.</div>`;
      } else {
        listEl.innerHTML = todaySessions.map((s, idx) => {
          const startDt = new Date(s.start_time);
          const endDt = s.end_time ? new Date(s.end_time) : null;
          const sTime = startDt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
          const eTime = endDt ? endDt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : "Active";
          const score = Math.round(s.focusScore != null ? s.focusScore : 100);
          const scoreCls = score >= 80 ? 'pill-good' : score >= 60 ? 'pill-warn' : 'pill-bad';

          return `
            <div class="today-session-item">
              <div class="today-sess-info">
                <span class="today-sess-title">Session #${todaySessions.length - idx} · ${s.course_name || 'Study Session'}</span>
                <span class="today-sess-time">${sTime} – ${eTime} · ${s.timeOfDay || 'Day'}</span>
              </div>
              <div class="today-sess-score-wrap">
                <span class="today-sess-score ${scoreCls}">Focus: ${score}%</span>
              </div>
            </div>
          `;
        }).join("");
      }
    }
  } catch (err) {
    console.warn("[StudyTracker] loadTodaySessions error:", err);
  }
}

// ── Camera Source Switching & Mobile Streaming ──────────────────────
async function switchCameraMode(mode, showNotice = true) {
  if (mode === "mobile") {
    try {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        showToast("⚠️ Device camera API not available in this context.");
        return;
      }
      if (deviceMediaStream) {
        deviceMediaStream.getTracks().forEach((t) => t.stop());
      }
      deviceMediaStream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: "user", width: { ideal: 640 }, height: { ideal: 480 } },
        audio: false,
      });
      if (deviceCameraVideo) {
        deviceCameraVideo.srcObject = deviceMediaStream;
        await deviceCameraVideo.play();
      }

      activeCameraMode = "mobile";
      if (btnSourceMobile) btnSourceMobile.classList.add("active");
      if (btnSourceLaptop) btnSourceLaptop.classList.remove("active");
      if (cameraSourceBadge) {
        cameraSourceBadge.textContent = "📱 Mobile Cam";
        cameraSourceBadge.style.color = "#38bdf8";
        cameraSourceBadge.style.borderColor = "rgba(56, 189, 248, 0.4)";
      }
      if (camIndicatorText) {
        camIndicatorText.innerHTML = "Tracking Camera: <strong>📱 Phone Front Camera (Laptop Disengaged)</strong>";
      }

      // Tell backend to disengage laptop webcam
      fetch("/api/camera/source", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ source: "mobile" }),
      }).catch(() => {});

      if (mobileUploadTimer) clearInterval(mobileUploadTimer);
      mobileUploadTimer = setInterval(uploadDeviceCameraFrame, 130);

      if (showNotice) {
        showToast("📱 Mobile Front Camera Active! Laptop camera is disengaged.");
      }
    } catch (err) {
      console.error("[Camera] getUserMedia failed:", err);
      if (!isMobileDevice) {
        switchCameraMode("laptop", false);
      }
    }
  } else {
    // Laptop mode
    activeCameraMode = "laptop";
    if (mobileUploadTimer) {
      clearInterval(mobileUploadTimer);
      mobileUploadTimer = null;
    }
    if (deviceMediaStream) {
      deviceMediaStream.getTracks().forEach((t) => t.stop());
      deviceMediaStream = null;
    }
    if (btnSourceLaptop) btnSourceLaptop.classList.add("active");
    if (btnSourceMobile) btnSourceMobile.classList.remove("active");
    if (cameraSourceBadge) {
      cameraSourceBadge.textContent = "💻 Laptop Cam";
      cameraSourceBadge.style.color = "#22d67a";
      cameraSourceBadge.style.borderColor = "rgba(34, 214, 122, 0.25)";
    }
    if (camIndicatorText) {
      camIndicatorText.innerHTML = "Tracking Camera: <strong>💻 Laptop Webcam</strong>";
    }

    // Tell backend to re-engage laptop webcam
    fetch("/api/camera/source", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ source: "laptop" }),
    }).catch(() => {});

    if (showNotice) {
      showToast("💻 Switched to Laptop Webcam.");
    }
  }
}

async function uploadDeviceCameraFrame() {
  if (isUploadingMobileFrame || !deviceCameraVideo || deviceCameraVideo.readyState < 2) return;
  if (!mobileOffscreenCanvas) return;
  isUploadingMobileFrame = true;

  try {
    const offCtx = mobileOffscreenCanvas.getContext("2d");
    offCtx.drawImage(deviceCameraVideo, 0, 0, mobileOffscreenCanvas.width, mobileOffscreenCanvas.height);

    mobileOffscreenCanvas.toBlob(
      async (blob) => {
        try {
          if (!blob) return;
          const formData = new FormData();
          formData.append("file", blob, "frame.jpg");
          const res = await fetch("/api/upload_frame?flip=1", {
            method: "POST",
            body: formData,
          });
          if (res.ok) {
            const data = await res.json();
            if (data.telemetry) {
              applyStatus(data.telemetry);
            }
          }
        } catch (_) {
        } finally {
          isUploadingMobileFrame = false;
        }
      },
      "image/jpeg",
      0.65
    );
  } catch (_) {
    isUploadingMobileFrame = false;
  }
}

if (btnSourceLaptop) {
  btnSourceLaptop.addEventListener("click", () => switchCameraMode("laptop"));
}
if (btnSourceMobile) {
  btnSourceMobile.addEventListener("click", () => switchCameraMode("mobile"));
}

// Auto-switch to mobile camera if running in mobile browser
if (isMobileDevice) {
  setTimeout(() => {
    switchCameraMode("mobile", true);
  }, 400);
}

// ── Canvas Frame Polling ─────────────────────────────────────────────
// Fetches /api/frame as JPEG blob and draws to canvas — works in ALL browsers.
let framePollActive = true;

async function framePollLoop() {
  while (framePollActive) {
    try {
      const r = await fetch("/api/frame", { cache: "no-store" });
      if (!r.ok) throw new Error("frame fetch failed");
      const blob = await r.blob();
      if (window.createImageBitmap) {
        const bmp = await createImageBitmap(blob);
        if (videoCanvas.width !== bmp.width || videoCanvas.height !== bmp.height) {
          videoCanvas.width  = bmp.width;
          videoCanvas.height = bmp.height;
        }
        videoCtx.drawImage(bmp, 0, 0);
        bmp.close();
      } else {
        await new Promise((resolve) => {
          const url = URL.createObjectURL(blob);
          const img = new Image();
          img.onload = () => {
            if (videoCanvas.width !== img.width || videoCanvas.height !== img.height) {
              videoCanvas.width  = img.width;
              videoCanvas.height = img.height;
            }
            videoCtx.drawImage(img, 0, 0);
            URL.revokeObjectURL(url);
            resolve();
          };
          img.onerror = () => {
            URL.revokeObjectURL(url);
            resolve();
          };
          img.src = url;
        });
      }
    } catch (e) {
      if (videoCanvas && videoCtx) {
        videoCtx.fillStyle = "#080c14";
        videoCtx.fillRect(0, 0, videoCanvas.width, videoCanvas.height);
        videoCtx.fillStyle = "rgba(255,255,255,0.2)";
        videoCtx.font = "14px Inter, sans-serif";
        videoCtx.textAlign = "center";
        videoCtx.fillText("Camera streaming...", videoCanvas.width / 2, videoCanvas.height / 2);
      }
    }
    // Throttle to ~25 fps (40ms)
    await new Promise(r => setTimeout(r, 40));
  }
}

// ── Init ──────────────────────────────────────────────────────────
startTimer();
drawDonut(0, 0, 0);
drawTimeline([]);
initUserProfile(); // Phase 1: Initialize User Profile & Session state

setInterval(pollStatus,   CONFIG.POLL_STATUS_MS);
setInterval(pollSession,  CONFIG.POLL_SESSION_MS);
setInterval(pollTimeline, CONFIG.POLL_TIMELINE_MS);

pollStatus();
pollSession();
pollTimeline();
framePollLoop();   // start canvas video feed

// Resize timeline on window resize
window.addEventListener("resize", () => drawTimeline(timelineData));

// ── Generate Background Particles ────────────────────────────────
const particlesEl = document.getElementById("bgParticles");
for (let i = 0; i < 18; i++) {
  const dot = document.createElement("div");
  const size = Math.random() * 3 + 1;
  Object.assign(dot.style, {
    position: "absolute",
    width:  `${size}px`,
    height: `${size}px`,
    borderRadius: "50%",
    background: `hsla(${190 + Math.random() * 80}, 80%, 70%, ${0.1 + Math.random() * 0.2})`,
    top:  `${Math.random() * 100}%`,
    left: `${Math.random() * 100}%`,
    animation: `drift${Math.random() > 0.5 ? "1" : "2"} ${10 + Math.random() * 15}s ease-in-out infinite alternate`,
    animationDelay: `${Math.random() * 5}s`,
  });
  particlesEl.appendChild(dot);
}
