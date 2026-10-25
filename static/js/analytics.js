/**
 * AttentionAI — Productivity Analytics & Visualization Engine (Phase 6)
 *
 * Renders 5 Canvas-based charts:
 * 1. Focus Score Over Time
 * 2. Study Duration by Day (Last 7 Days)
 * 3. Focus by Time of Day
 * 4. Distraction vs Focus Breakdown
 * 5. Drowsiness Trend
 *
 * Also populates Today's summary cards, Peak Study Period insights,
 * and Completed Session History.
 */

"use strict";

(function (global) {
  let activeAnalyticsTab = "overview";
  let cachedAnalyticsData = null;
  let cachedChartsData = null;

  // Color tokens
  const COLORS = {
    focused:     "#22d67a",
    distracted:  "#f59e0b",
    drowsy:      "#ef4444",
    cyan:        "#6ee7f7",
    purple:      "#a78bfa",
    bgCard:      "rgba(15, 23, 42, 0.8)",
    border:      "rgba(148, 163, 184, 0.15)",
    textPrimary: "#f8fafc",
    textMuted:   "#94a3b8",
    gridLine:    "rgba(148, 163, 184, 0.08)",
  };

  /**
   * Helper: Scale canvas for HiDPI/Retina displays.
   */
  function setupHiDPICanvas(canvas) {
    if (!canvas) return null;
    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    const w = rect.width || canvas.width;
    const h = rect.height || canvas.height;

    canvas.width = w * dpr;
    canvas.height = h * dpr;
    canvas.style.width = `${w}px`;
    canvas.style.height = `${h}px`;

    const ctx = canvas.getContext("2d");
    ctx.scale(dpr, dpr);
    return { ctx, width: w, height: h };
  }

  function formatTime(sec) {
    if (!sec || sec < 1) return "0s";
    const s = Math.round(sec);
    const m = Math.floor(s / 60);
    const h = Math.floor(m / 60);
    if (h > 0) return `${h}h ${m % 60}m`;
    if (m > 0) return `${m}m ${s % 60}s`;
    return `${s}s`;
  }

  /**
   * ── Fetch Analytics Summary & Populate UI ──────────────────────
   */
  async function loadAnalytics() {
    const userId = localStorage.getItem("studyTracker_userId");
    const userParam = userId ? `?userId=${encodeURIComponent(userId)}` : "";

    try {
      const [sumResp, chartsResp, sessResp] = await Promise.all([
        fetch(`/api/analytics/summary${userParam}`),
        fetch(`/api/analytics/charts${userParam}`),
        fetch(`/api/analytics/sessions${userParam}&limit=30`),
      ]);

      if (!sumResp.ok || !chartsResp.ok) return;

      const summary = await sumResp.json();
      const charts = await chartsResp.json();
      const sessions = sessResp.ok ? await sessResp.json() : [];

      cachedAnalyticsData = summary;
      cachedChartsData = charts.charts;

      renderTodayOverview(summary.today);
      renderBestStudyTime(summary.bestStudyTime);
      renderSessionHistory(sessions);
      renderAllCharts(charts.charts);
    } catch (err) {
      console.error("[Analytics] Error loading analytics:", err);
    }
  }

  /**
   * ── Render Today's Overview Cards ──────────────────────────────
   */
  function renderTodayOverview(today = {}) {
    const elStudyTime   = document.getElementById("anTodayStudyTime");
    const elFocusedTime = document.getElementById("anTodayFocusedTime");
    const elDistract    = document.getElementById("anTodayDistractionTime");
    const elDrowsy      = document.getElementById("anTodayDrowsinessTime");
    const elTabSwitches = document.getElementById("anTodayTabSwitches");
    const elFocusScore  = document.getElementById("anTodayFocusScore");

    if (elStudyTime)   elStudyTime.textContent   = formatTime(today.totalStudyTime);
    if (elFocusedTime) elFocusedTime.textContent = formatTime(today.focusedTime);
    if (elDistract)    elDistract.textContent    = formatTime(today.distractionTime);
    if (elDrowsy)      elDrowsy.textContent      = formatTime(today.drowsinessDuration);
    if (elTabSwitches) elTabSwitches.textContent = today.tabSwitches || 0;
    if (elFocusScore) {
      const score = Math.round(today.focusScore || 100);
      elFocusScore.textContent = `${score}%`;
      elFocusScore.className = `an-score-badge ${score >= 80 ? 'good' : score >= 60 ? 'warn' : 'bad'}`;
    }
  }

  /**
   * ── Render Peak Productivity Time Card ────────────────────────
   */
  function renderBestStudyTime(best = {}) {
    const box = document.getElementById("anBestTimeBanner");
    const title = document.getElementById("anBestTimeTitle");
    const desc = document.getElementById("anBestTimeDesc");
    const score = document.getElementById("anBestTimeScore");

    if (!box || !title || !desc) return;

    if (best.hasRecommendation) {
      box.classList.remove("an-banner-insufficient");
      box.classList.add("an-banner-optimal");
      title.textContent = `🌟 Peak Focus Window: ${best.bestWindow || best.bestPeriod}`;
      desc.textContent = best.message || "Your focus is highest during this window.";
      if (score) score.textContent = `Avg Score: ${Math.round(best.avgFocusScore)}%`;
    } else {
      box.classList.remove("an-banner-optimal");
      box.classList.add("an-banner-insufficient");
      title.textContent = "⏳ Insufficient Historical Data";
      desc.textContent = best.message || "Complete more sessions to unlock your peak study period.";
      if (score) score.textContent = "Need ≥ 2 sessions";
    }
  }

  /**
   * ── Render Session History Table ───────────────────────────────
   */
  function renderSessionHistory(sessions = []) {
    const tbody = document.getElementById("anSessionHistoryBody");
    if (!tbody) return;

    if (!sessions || sessions.length === 0) {
      tbody.innerHTML = `<tr><td colspan="7" class="an-empty-row">No completed study sessions yet</td></tr>`;
      return;
    }

    tbody.innerHTML = sessions.map(s => {
      const dt = new Date(s.start_time);
      const dateStr = dt.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
      const timeStr = dt.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
      const score = Math.round(s.focusScore != null ? s.focusScore : 100);
      const scoreCls = score >= 80 ? 'pill-good' : score >= 60 ? 'pill-warn' : 'pill-bad';

      return `
        <tr>
          <td>
            <div class="sess-title-cell">
              <strong>${s.course_name || 'Study Session'}</strong>
              <span class="sess-platform-tag">${s.platform || 'General'}</span>
            </div>
          </td>
          <td>
            <span class="sess-time-cell">${dateStr} · ${timeStr}</span>
            <span class="sess-tod-tag">${s.timeOfDay || 'Day'}</span>
          </td>
          <td><strong>${formatTime(s.duration || s.totalDuration)}</strong></td>
          <td>
            <span class="sess-score-pill ${scoreCls}">${score}%</span>
          </td>
          <td>${formatTime(s.distractionDuration || s.totalDistractionDuration || 0)} (${s.tabSwitchCount || 0} tabs)</td>
          <td>${formatTime(s.drowsinessDuration || s.totalDrowsinessDuration || 0)}</td>
          <td>
            <div class="sess-breakdown-bar" title="Focused: ${s.focusedPercentage || 0}%, Distracted: ${s.distractionPercentage || 0}%, Drowsy: ${s.drowsinessPercentage || 0}%">
              <div class="seg-focus" style="width:${s.focusedPercentage || 100}%"></div>
              <div class="seg-distract" style="width:${s.distractionPercentage || 0}%"></div>
              <div class="seg-drowsy" style="width:${s.drowsinessPercentage || 0}%"></div>
            </div>
          </td>
        </tr>
      `;
    }).join("");
  }

  /**
   * ── Render All 5 Visualizations ────────────────────────────────
   */
  function renderAllCharts(chartsData) {
    if (!chartsData) return;
    drawFocusScoreOverTime(chartsData.focusScoreOverTime);
    drawStudyDurationByDay(chartsData.studyDurationByDay);
    drawFocusByTimeOfDay(chartsData.focusByTimeOfDay);
    drawDistractionVsFocus(chartsData.distractionVsFocus);
    drawDrowsinessTrend(chartsData.drowsinessTrend);
  }

  // ── Chart 1: Focus Score Over Time ───────────────────────────────
  function drawFocusScoreOverTime(data = []) {
    const canvas = document.getElementById("chartFocusOverTime");
    if (!canvas) return;
    const setup = setupHiDPICanvas(canvas);
    if (!setup) return;
    const { ctx, width, height } = setup;

    ctx.clearRect(0, 0, width, height);

    const pad = { top: 25, right: 25, bottom: 35, left: 45 };
    const chartW = width - pad.left - pad.right;
    const chartH = height - pad.top - pad.bottom;

    // Draw background grid lines
    ctx.strokeStyle = COLORS.gridLine;
    ctx.lineWidth = 1;
    for (let p = 0; p <= 100; p += 25) {
      const y = pad.top + chartH - (p / 100) * chartH;
      ctx.beginPath();
      ctx.moveTo(pad.left, y);
      ctx.lineTo(width - pad.right, y);
      ctx.stroke();

      ctx.fillStyle = COLORS.textMuted;
      ctx.font = "10px Inter, sans-serif";
      ctx.textAlign = "right";
      ctx.fillText(`${p}%`, pad.left - 8, y + 3);
    }

    if (!data.length) {
      ctx.fillStyle = COLORS.textMuted;
      ctx.textAlign = "center";
      ctx.font = "12px Inter, sans-serif";
      ctx.fillText("No session focus scores recorded yet", width / 2, height / 2);
      return;
    }

    const n = data.length;
    const pts = data.map((d, i) => {
      const x = n === 1 ? pad.left + chartW / 2 : pad.left + (i / (n - 1)) * chartW;
      const y = pad.top + chartH - (Math.max(0, Math.min(100, d.focusScore)) / 100) * chartH;
      return { x, y, score: d.focusScore, date: d.date };
    });

    // Gradient fill under the curve
    if (pts.length > 1) {
      const grad = ctx.createLinearGradient(0, pad.top, 0, pad.top + chartH);
      grad.addColorStop(0, "rgba(110, 231, 247, 0.35)");
      grad.addColorStop(1, "rgba(110, 231, 247, 0.0)");

      ctx.beginPath();
      ctx.moveTo(pts[0].x, pad.top + chartH);
      pts.forEach(p => ctx.lineTo(p.x, p.y));
      ctx.lineTo(pts[pts.length - 1].x, pad.top + chartH);
      ctx.closePath();
      ctx.fillStyle = grad;
      ctx.fill();
    }

    // Line curve
    ctx.strokeStyle = COLORS.cyan;
    ctx.lineWidth = 2.5;
    ctx.beginPath();
    pts.forEach((p, i) => {
      if (i === 0) ctx.moveTo(p.x, p.y);
      else ctx.lineTo(p.x, p.y);
    });
    ctx.stroke();

    // Data points & date labels
    pts.forEach((p, i) => {
      ctx.fillStyle = "#0f172a";
      ctx.beginPath();
      ctx.arc(p.x, p.y, 4.5, 0, Math.PI * 2);
      ctx.fill();

      ctx.strokeStyle = COLORS.cyan;
      ctx.lineWidth = 2;
      ctx.stroke();

      // Show label for first, middle, last
      if (i === 0 || i === pts.length - 1 || i === Math.floor(pts.length / 2)) {
        ctx.fillStyle = COLORS.textMuted;
        ctx.font = "9px Inter, sans-serif";
        ctx.textAlign = "center";
        ctx.fillText(p.date || `S${i + 1}`, p.x, height - 12);
      }
    });
  }

  // ── Chart 2: Study Duration by Day (Last 7 Days) ──────────────────
  function drawStudyDurationByDay(data = []) {
    const canvas = document.getElementById("chartDurationByDay");
    if (!canvas) return;
    const setup = setupHiDPICanvas(canvas);
    if (!setup) return;
    const { ctx, width, height } = setup;

    ctx.clearRect(0, 0, width, height);

    const pad = { top: 25, right: 20, bottom: 35, left: 45 };
    const chartW = width - pad.left - pad.right;
    const chartH = height - pad.top - pad.bottom;

    const maxVal = Math.max(10, ...data.map(d => d.totalMinutes || 0));

    // Y Axis grid
    ctx.strokeStyle = COLORS.gridLine;
    ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
      const v = Math.round((maxVal / 4) * i);
      const y = pad.top + chartH - (i / 4) * chartH;
      ctx.beginPath();
      ctx.moveTo(pad.left, y);
      ctx.lineTo(width - pad.right, y);
      ctx.stroke();

      ctx.fillStyle = COLORS.textMuted;
      ctx.font = "10px Inter, sans-serif";
      ctx.textAlign = "right";
      ctx.fillText(`${v}m`, pad.left - 6, y + 3);
    }

    if (!data.length) return;

    const barGroupW = chartW / data.length;
    const barW = Math.max(12, Math.min(28, barGroupW * 0.5));

    data.forEach((d, i) => {
      const cx = pad.left + i * barGroupW + barGroupW / 2;
      const x = cx - barW / 2;
      const barH = (d.totalMinutes / maxVal) * chartH;
      const y = pad.top + chartH - barH;

      // Draw background bar
      ctx.fillStyle = "rgba(167, 139, 250, 0.15)";
      ctx.fillRect(x, pad.top, barW, chartH);

      // Draw filled total bar
      const grad = ctx.createLinearGradient(0, y, 0, pad.top + chartH);
      grad.addColorStop(0, "#a78bfa");
      grad.addColorStop(1, "#6366f1");
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.roundRect(x, y, barW, Math.max(2, barH), [4, 4, 0, 0]);
      ctx.fill();

      // Day label
      ctx.fillStyle = COLORS.textMuted;
      ctx.font = "10px Inter, sans-serif";
      ctx.textAlign = "center";
      const shortDay = (d.day || "").split(" ")[0];
      ctx.fillText(shortDay, cx, height - 12);

      // Value label on top
      if (d.totalMinutes > 0) {
        ctx.fillStyle = COLORS.textPrimary;
        ctx.font = "9px Inter, sans-serif";
        ctx.fillText(`${d.totalMinutes}m`, cx, y - 5);
      }
    });
  }

  // ── Chart 3: Focus by Time of Day ─────────────────────────────────
  function drawFocusByTimeOfDay(data = []) {
    const canvas = document.getElementById("chartFocusByTimeOfDay");
    if (!canvas) return;
    const setup = setupHiDPICanvas(canvas);
    if (!setup) return;
    const { ctx, width, height } = setup;

    ctx.clearRect(0, 0, width, height);

    const pad = { top: 25, right: 30, bottom: 35, left: 50 };
    const chartW = width - pad.left - pad.right;
    const chartH = height - pad.top - pad.bottom;

    // Y Axis grid
    ctx.strokeStyle = COLORS.gridLine;
    ctx.lineWidth = 1;
    for (let p = 0; p <= 100; p += 25) {
      const y = pad.top + chartH - (p / 100) * chartH;
      ctx.beginPath();
      ctx.moveTo(pad.left, y);
      ctx.lineTo(width - pad.right, y);
      ctx.stroke();

      ctx.fillStyle = COLORS.textMuted;
      ctx.font = "10px Inter, sans-serif";
      ctx.textAlign = "right";
      ctx.fillText(`${p}%`, pad.left - 8, y + 3);
    }

    if (!data.length) return;

    const barGroupW = chartW / data.length;
    const barW = Math.max(16, Math.min(36, barGroupW * 0.55));

    data.forEach((d, i) => {
      const cx = pad.left + i * barGroupW + barGroupW / 2;
      const x = cx - barW / 2;
      const barH = (d.avgFocusScore / 100) * chartH;
      const y = pad.top + chartH - barH;

      // Color coding: green if >= 80%, amber if >= 60%, red if < 60%
      const color = d.avgFocusScore >= 80 ? COLORS.focused : d.avgFocusScore >= 60 ? COLORS.distracted : (d.sessionCount > 0 ? COLORS.drowsy : "rgba(148, 163, 184, 0.2)");

      ctx.fillStyle = color;
      ctx.beginPath();
      ctx.roundRect(x, y, barW, Math.max(3, barH), [4, 4, 0, 0]);
      ctx.fill();

      // Period label
      ctx.fillStyle = COLORS.textMuted;
      ctx.font = "10px Inter, sans-serif";
      ctx.textAlign = "center";
      ctx.fillText(d.period || "", cx, height - 12);

      // Score on top
      if (d.sessionCount > 0) {
        ctx.fillStyle = COLORS.textPrimary;
        ctx.font = "bold 9px Inter, sans-serif";
        ctx.fillText(`${Math.round(d.avgFocusScore)}%`, cx, y - 5);
      } else {
        ctx.fillStyle = "rgba(148, 163, 184, 0.4)";
        ctx.font = "9px Inter, sans-serif";
        ctx.fillText("—", cx, pad.top + chartH - 8);
      }
    });
  }

  // ── Chart 4: Distraction vs Focus Breakdown ────────────────────────
  function drawDistractionVsFocus(data = {}) {
    const canvas = document.getElementById("chartDistractionVsFocus");
    if (!canvas) return;
    const setup = setupHiDPICanvas(canvas);
    if (!setup) return;
    const { ctx, width, height } = setup;

    ctx.clearRect(0, 0, width, height);

    const cx = width * 0.35;
    const cy = height * 0.5;
    const R = Math.min(cx - 20, cy - 15);
    const r = R * 0.55;

    const f = data.focusedPct || 0;
    const d = data.distractedPct || 0;
    const s = data.drowsyPct || 0;
    const sum = f + d + s || 1;

    const slices = [
      { label: "Focused",    pct: f, color: COLORS.focused },
      { label: "Distracted", pct: d, color: COLORS.distracted },
      { label: "Drowsy",     pct: s, color: COLORS.drowsy },
    ];

    let start = -Math.PI / 2;
    slices.forEach(slice => {
      const angle = (slice.pct / sum) * Math.PI * 2;
      ctx.beginPath();
      ctx.arc(cx, cy, R, start, start + angle);
      ctx.arc(cx, cy, r, start + angle, start, true);
      ctx.closePath();
      ctx.fillStyle = slice.color;
      ctx.fill();
      start += angle;
    });

    // Center text
    ctx.fillStyle = COLORS.textPrimary;
    ctx.font = "bold 16px Inter, sans-serif";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText(`${Math.round(f)}%`, cx, cy - 4);
    ctx.font = "9px Inter, sans-serif";
    ctx.fillStyle = COLORS.textMuted;
    ctx.fillText("Focused", cx, cy + 12);

    // Legend on the right side
    const legX = width * 0.65;
    const legY = height * 0.28;
    slices.forEach((slice, idx) => {
      const y = legY + idx * 24;
      ctx.fillStyle = slice.color;
      ctx.fillRect(legX, y - 8, 10, 10);

      ctx.fillStyle = COLORS.textPrimary;
      ctx.font = "11px Inter, sans-serif";
      ctx.textAlign = "left";
      ctx.textBaseline = "middle";
      ctx.fillText(`${slice.label}: ${Math.round(slice.pct)}%`, legX + 16, y - 3);
    });
  }

  // ── Chart 5: Drowsiness Trend ──────────────────────────────────────
  function drawDrowsinessTrend(data = []) {
    const canvas = document.getElementById("chartDrowsinessTrend");
    if (!canvas) return;
    const setup = setupHiDPICanvas(canvas);
    if (!setup) return;
    const { ctx, width, height } = setup;

    ctx.clearRect(0, 0, width, height);

    const pad = { top: 25, right: 25, bottom: 35, left: 45 };
    const chartW = width - pad.left - pad.right;
    const chartH = height - pad.top - pad.bottom;

    const maxDur = Math.max(10, ...data.map(d => d.drowsinessDuration || 0));

    // Y Axis grid
    ctx.strokeStyle = COLORS.gridLine;
    ctx.lineWidth = 1;
    for (let i = 0; i <= 3; i++) {
      const v = Math.round((maxDur / 3) * i);
      const y = pad.top + chartH - (i / 3) * chartH;
      ctx.beginPath();
      ctx.moveTo(pad.left, y);
      ctx.lineTo(width - pad.right, y);
      ctx.stroke();

      ctx.fillStyle = COLORS.textMuted;
      ctx.font = "10px Inter, sans-serif";
      ctx.textAlign = "right";
      ctx.fillText(`${v}s`, pad.left - 6, y + 3);
    }

    if (!data.length) {
      ctx.fillStyle = COLORS.textMuted;
      ctx.textAlign = "center";
      ctx.font = "12px Inter, sans-serif";
      ctx.fillText("Zero drowsiness episodes detected!", width / 2, height / 2);
      return;
    }

    const n = data.length;
    const pts = data.map((d, i) => {
      const x = n === 1 ? pad.left + chartW / 2 : pad.left + (i / (n - 1)) * chartW;
      const y = pad.top + chartH - (Math.max(0, d.drowsinessDuration) / maxDur) * chartH;
      return { x, y, dur: d.drowsinessDuration, date: d.date };
    });

    // Area fill
    if (pts.length > 1) {
      const grad = ctx.createLinearGradient(0, pad.top, 0, pad.top + chartH);
      grad.addColorStop(0, "rgba(239, 68, 68, 0.35)");
      grad.addColorStop(1, "rgba(239, 68, 68, 0.0)");

      ctx.beginPath();
      ctx.moveTo(pts[0].x, pad.top + chartH);
      pts.forEach(p => ctx.lineTo(p.x, p.y));
      ctx.lineTo(pts[pts.length - 1].x, pad.top + chartH);
      ctx.closePath();
      ctx.fillStyle = grad;
      ctx.fill();
    }

    // Line
    ctx.strokeStyle = COLORS.drowsy;
    ctx.lineWidth = 2.5;
    ctx.beginPath();
    pts.forEach((p, i) => {
      if (i === 0) ctx.moveTo(p.x, p.y);
      else ctx.lineTo(p.x, p.y);
    });
    ctx.stroke();

    // Dots
    pts.forEach((p, i) => {
      ctx.fillStyle = "#0f172a";
      ctx.beginPath();
      ctx.arc(p.x, p.y, 4, 0, Math.PI * 2);
      ctx.fill();

      ctx.strokeStyle = COLORS.drowsy;
      ctx.lineWidth = 2;
      ctx.stroke();

      if (i === 0 || i === pts.length - 1) {
        ctx.fillStyle = COLORS.textMuted;
        ctx.font = "9px Inter, sans-serif";
        ctx.textAlign = "center";
        ctx.fillText(p.date || "", p.x, height - 12);
      }
    });
  }

  // ── Tab Switching Navigation (Live vs Analytics) ───────────────────
  function initNavigation() {
    const btnNavLive = document.getElementById("btnNavLive");
    const btnNavAnalytics = document.getElementById("btnNavAnalytics");
    const liveView = document.getElementById("liveTrackingView");
    const analyticsView = document.getElementById("analyticsView");

    if (btnNavLive && btnNavAnalytics && liveView && analyticsView) {
      btnNavLive.addEventListener("click", () => {
        btnNavLive.classList.add("active");
        btnNavAnalytics.classList.remove("active");
        liveView.classList.remove("hidden");
        analyticsView.classList.add("hidden");
      });

      btnNavAnalytics.addEventListener("click", () => {
        btnNavAnalytics.classList.add("active");
        btnNavLive.classList.remove("active");
        analyticsView.classList.remove("hidden");
        liveView.classList.add("hidden");
        loadAnalytics();
      });
    }

    const btnRefreshAnalytics = document.getElementById("btnRefreshAnalytics");
    if (btnRefreshAnalytics) {
      btnRefreshAnalytics.addEventListener("click", () => {
        loadAnalytics();
      });
    }

    // Auto-redraw charts on window resize
    window.addEventListener("resize", () => {
      if (cachedChartsData && analyticsView && !analyticsView.classList.contains("hidden")) {
        renderAllCharts(cachedChartsData);
      }
    });
  }

  // Auto initialize on DOM ready
  if (typeof document !== "undefined") {
    document.addEventListener("DOMContentLoaded", () => {
      initNavigation();
      loadAnalytics();
    });
  }

  // Export
  global.ProductivityAnalytics = {
    load: loadAnalytics,
    redraw: renderAllCharts,
  };

})(typeof window !== "undefined" ? window : globalThis);
