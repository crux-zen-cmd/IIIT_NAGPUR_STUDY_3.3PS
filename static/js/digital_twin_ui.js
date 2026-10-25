/**
 * FocusTwin — Digital Twin & Simulator UI Controller (Phase 7).
 * PS 3.3 The Attention Digital Twin (CASCADE '26).
 *
 * Implements the 8 Core Product Panels:
 * 1. Current Attention (5-State Engine)
 * 2. Digital Twin Profile (Cold Start, Sensitivity, Recovery, Peak Window)
 * 3. Live & Visual Attention Timeline
 * 4. Why Was I Distracted? (Root cause diagnostic)
 * 5. Notification Simulator (Counterfactual ALLOW vs DEFER)
 * 6. Attention Patterns (Task contexts & fragmentation)
 * 7. Prediction Accuracy & Closed-Loop Learning ('LAST PREDICTION' widget)
 * 8. Privacy & Data Governance
 */

"use strict";

(function () {
  const STATE_COLORS = {
    FOCUSED: "#22d67a",
    DRIFTING: "#eab308",
    DISTRACTED: "#f97316",
    RECOVERING: "#06b6d4",
    FATIGUE_RISK: "#ef4444",
  };

  const STATE_ICONS = {
    FOCUSED: "🎯",
    DRIFTING: "🌊",
    DISTRACTED: "👀",
    RECOVERING: "🔄",
    FATIGUE_RISK: "😴",
  };

  let activeUserId = null;
  let activeSessionId = null;

  // ── Navigation & View Switching ─────────────────────────────────
  function initNavigation() {
    const tabs = [
      { btn: "btnNavLive", view: "liveTrackingView" },
      { btn: "btnNavTwin", view: "twinSimulatorView" },
      { btn: "btnNavAnalytics", view: "analyticsView" },
      { btn: "btnNavPrivacy", view: "privacyView" },
    ];

    tabs.forEach(({ btn, view }) => {
      const bEl = document.getElementById(btn);
      if (!bEl) return;
      bEl.addEventListener("click", () => {
        tabs.forEach((t) => {
          const b = document.getElementById(t.btn);
          const v = document.getElementById(t.view);
          if (b) b.classList.remove("active");
          if (v) v.classList.add("hidden");
        });
        bEl.classList.add("active");
        const vEl = document.getElementById(view);
        if (vEl) vEl.classList.remove("hidden");

        // View-specific data refreshes
        if (view === "twinSimulatorView") {
          loadDigitalTwinProfile();
          loadSimulatorPresets();
          loadDiagnostic();
          loadAccuracySummary();
        } else if (view === "analyticsView") {
          if (window.loadAnalytics) window.loadAnalytics();
        } else if (view === "privacyView") {
          loadPrivacyProfile();
        }
      });
    });
  }

  // ── Current User Resolution ─────────────────────────────────────
  async function resolveUser() {
    if (activeUserId) return activeUserId;
    try {
      const res = await fetch("/api/users");
      const users = await res.json();
      if (users && users.length > 0) {
        activeUserId = users[0].user_id;
        const nameEl = document.getElementById("userDisplayName");
        const idEl = document.getElementById("userDisplayId");
        if (nameEl) nameEl.textContent = users[0].name || "Scholar";
        if (idEl) idEl.textContent = activeUserId;
      }
    } catch (e) {
      console.warn("User resolution warning:", e);
    }
    return activeUserId;
  }

  // ── PANEL 1: Current Attention (5-State Engine) ─────────────────
  async function updateAttentionStateHUD() {
    try {
      const res = await fetch("/api/attention-twin/state");
      if (!res.ok) return;
      const data = await res.json();

      const st = (data.attentionState || "FOCUSED").toUpperCase();
      const color = STATE_COLORS[st] || "#22d67a";
      const icon = STATE_ICONS[st] || "🎯";

      // Live header chip
      const chip = document.getElementById("hudAttentionStatePill");
      if (chip) {
        chip.textContent = `${icon} ${st}`;
        chip.style.borderColor = color;
        chip.style.color = color;
      }

      // Attention label
      const stateCard = document.getElementById("stateCard");
      const stateLabel = document.getElementById("stateLabel");
      const stateIcon = document.getElementById("stateIcon");
      if (stateCard && stateLabel && stateIcon) {
        stateLabel.textContent = st;
        stateIcon.textContent = icon;
      }

      // Active session HUD task & state badge
      const activeState = document.getElementById("activeLiveStateVal");
      if (activeState) {
        activeState.textContent = st;
        activeState.style.color = color;
      }
    } catch (e) {
      // Quiet fail on network polls
    }
  }

  // ── PANEL 2: Digital Twin Profile ──────────────────────────────
  async function loadDigitalTwinProfile() {
    const uid = await resolveUser();
    if (!uid) return;

    try {
      const res = await fetch(`/api/attention-twin/profile?userId=${uid}`);
      if (!res.ok) return;
      const profile = await res.json();

      // Cold start badge
      const csBadge = document.getElementById("twinColdStartBadge");
      const cs = profile.coldStartState || profile.cold_start_state || "LEARNING";
      if (csBadge) {
        csBadge.textContent = cs.replace("_", " ");
        csBadge.className = `twin-badge badge-${cs.toLowerCase()}`;
      }

      // Confidence
      const confVal = document.getElementById("twinConfidenceVal");
      if (confVal) {
        confVal.textContent = profile.confidenceLevel || profile.confidence_level || "MEDIUM";
      }

      // Sample size
      const sampleVal = document.getElementById("twinSampleSizeVal");
      if (sampleVal) {
        sampleVal.textContent = `${profile.sampleSize || profile.sample_size || 0} sessions`;
      }

      // Baselines
      const b = profile.baselines || {};
      const peakWindowVal = document.getElementById("twinPeakWindowVal");
      if (peakWindowVal) {
        const pw = profile.bestAttentionWindow || {};
        peakWindowVal.textContent = pw.window || pw.period || "Afternoon (12–5 PM)";
      }

      const focusDurVal = document.getElementById("twinFocusDurVal");
      if (focusDurVal) {
        focusDurVal.textContent = `${b.avgFocusDurationMinutes || 25} min`;
      }

      const sensVal = document.getElementById("twinSensitivityVal");
      if (sensVal) {
        const s = b.notificationSensitivity || 0.5;
        const sLabel = s >= 0.7 ? "High" : s <= 0.35 ? "Low" : "Moderate";
        sensVal.textContent = `${sLabel} (${Math.round(s * 100)}%)`;
      }

      const recVal = document.getElementById("twinRecoveryVal");
      if (recVal) {
        recVal.textContent = `${Math.round(b.avgRecoveryTimeSeconds || 35)}s`;
      }

      const fatigueVal = document.getElementById("twinFatigueVal");
      if (fatigueVal) {
        fatigueVal.textContent = `${Math.round(b.fatigueOnsetMinutes || 45)} min`;
      }

      const statusMsg = document.getElementById("twinStatusMsg");
      if (statusMsg) {
        statusMsg.textContent = profile.statusMessage || "Digital Twin calibrated to your personal study rhythms.";
      }
    } catch (e) {
      console.warn("Failed loading twin profile:", e);
    }
  }

  // ── PANEL 3: Live & Visual Attention Timeline ───────────────────
  async function loadAttentionTimeline() {
    try {
      const res = await fetch("/api/timeline");
      if (!res.ok) return;
      const list = await res.json();

      const container = document.getElementById("visualTimelineStream");
      if (!container) return;

      if (!list || list.length === 0) {
        container.innerHTML = `<div class="timeline-empty-hint">No timeline records logged yet. Start a study session to track milestones.</div>`;
        return;
      }

      // Render top 12 recent timeline events
      const recent = list.slice(-12).reverse();
      container.innerHTML = recent
        .map((item) => {
          const st = (item.state || "FOCUSED").toUpperCase();
          const color = STATE_COLORS[st] || "#22d67a";
          const icon = STATE_ICONS[st] || "🎯";
          const timeStr = item.timestamp
            ? new Date(item.timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" })
            : "Live";

          return `
          <div class="timeline-node">
            <span class="timeline-time">${timeStr}</span>
            <span class="timeline-badge" style="border-color:${color}; color:${color}; background:${color}15">
              ${icon} ${st}
            </span>
            <span class="timeline-desc">${item.event || item.message || "Attention maintained"}</span>
          </div>
        `;
        })
        .join("");
    } catch (e) {
      // Quiet fail
    }
  }

  // ── PANEL 4: "Why Was I Distracted?" Diagnostic ─────────────────
  async function loadDiagnostic() {
    const uid = await resolveUser();
    if (!uid) return;

    try {
      const res = await fetch(`/api/attention-twin/distraction-diagnostic?userId=${uid}`);
      if (!res.ok) return;
      const data = await res.json();

      const card = document.getElementById("diagnosticCard");
      if (!card) return;

      if (!data.hasDistraction) {
        card.innerHTML = `
          <div class="diagnostic-positive">
            <span class="diag-icon">🌟</span>
            <div>
              <h4>No Recent Focus Drops</h4>
              <p>${data.message || "Continuous concentration maintained across observed window."}</p>
            </div>
          </div>
        `;
        return;
      }

      const reasonsHtml = (data.reasons || [])
        .map((r) => `<li><span class="bullet-dot"></span>${r}</li>`)
        .join("");

      card.innerHTML = `
        <div class="diagnostic-header">
          <div class="diag-title-row">
            <span class="diag-alert-icon">⚠️</span>
            <div>
              <h4 class="diag-headline">Focus drop analyzed at ${data.dropTimeFormatted || "Recent Session"}</h4>
              <span class="diag-sub">Root cause diagnostic computed from preceding 10-minute event window</span>
            </div>
          </div>
        </div>

        <div class="diag-section">
          <h5 class="diag-section-title">Identified Contributing Factors:</h5>
          <ul class="diag-reasons-list">
            ${reasonsHtml}
          </ul>
        </div>

        <div class="diag-comparison-box">
          <span class="comp-icon">📊</span>
          <p><strong>Historical Pattern:</strong> ${data.historicalComparison || "Data indicates variable afternoon focus."}</p>
        </div>

        <div class="diag-rec-box">
          <span class="rec-icon">💡</span>
          <p><strong>Twin Recommendation:</strong> ${data.recommendation || "Take a 5-minute cognitive reset."}</p>
        </div>
      `;
    } catch (e) {
      console.warn("Diagnostic fetch error:", e);
    }
  }

  // ── PANEL 5: Notification Simulator ─────────────────────────────
  let presetScenarios = [];

  async function loadSimulatorPresets() {
    try {
      const res = await fetch("/api/simulator/presets");
      if (!res.ok) return;
      presetScenarios = await res.json();

      const sel = document.getElementById("simPresetSelect");
      if (!sel) return;

      sel.innerHTML = `<option value="">-- Choose a realistic preset scenario --</option>` +
        presetScenarios
          .map((s, idx) => `<option value="${idx}">${s.name} (${s.source} · ${s.taskContext})</option>`)
          .join("");

      sel.addEventListener("change", (e) => {
        const idx = e.target.value;
        if (idx !== "" && presetScenarios[idx]) {
          const sc = presetScenarios[idx];
          const srcInput = document.getElementById("simSourceInput");
          const catSelect = document.getElementById("simCategorySelect");
          const taskSelect = document.getElementById("simTaskSelect");
          const durInput = document.getElementById("simDurationInput");
          const deferInput = document.getElementById("simDeferInput");

          if (srcInput) srcInput.value = sc.source;
          if (catSelect) catSelect.value = sc.category;
          if (taskSelect) taskSelect.value = sc.taskContext;
          if (durInput) durInput.value = Math.round(sc.sessionDurationSec / 60);
          if (deferInput) deferInput.value = sc.deferMinutes || 15;
        }
      });
    } catch (e) {
      console.warn("Simulator presets warning:", e);
    }
  }

  let lastSimulatedPrediction = null;

  async function runSimulation() {
    const uid = await resolveUser();
    if (!uid) return;

    const source = (document.getElementById("simSourceInput")?.value || "Discord").trim();
    const category = document.getElementById("simCategorySelect")?.value || "MESSAGE";
    const task = document.getElementById("simTaskSelect")?.value || "Coding";
    const durMin = parseFloat(document.getElementById("simDurationInput")?.value || 35);
    const deferMin = parseInt(document.getElementById("simDeferInput")?.value || 15);

    const btn = document.getElementById("btnRunSimulation");
    if (btn) {
      btn.disabled = true;
      btn.textContent = "Simulating Counterfactuals...";
    }

    try {
      const res = await fetch("/api/simulator/simulate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          userId: uid,
          notificationSource: source,
          notificationCategory: category,
          taskContext: task,
          sessionDurationSec: durMin * 60,
          currentAttentionState: "FOCUSED",
          deferMinutes: deferMin,
          persist: true, // Persist so user can record outcome in closed loop!
        }),
      });

      if (!res.ok) throw new Error("Simulation failed");
      const sim = await res.json();
      lastSimulatedPrediction = sim;
      renderSimulationResult(sim);
      loadAccuracySummary(); // Refresh accuracy telemetry
    } catch (e) {
      alert("Simulation error: " + e.message);
    } finally {
      if (btn) {
        btn.disabled = false;
        btn.textContent = "⚡ Simulate Counterfactual";
      }
    }
  }

  function renderSimulationResult(sim) {
    const container = document.getElementById("simResultContainer");
    if (!container) return;

    const allow = sim.allowPath || {};
    const defer = sim.deferPath || {};
    const savings = sim.savings || {};
    const rec = sim.recommendation || "DEFER";

    const recClass = rec === "TAKE_BREAK" ? "rec-break" : rec === "ALLOW" ? "rec-allow" : "rec-defer";

    const expHtml = (sim.explanation || [])
      .map((e) => `<li><span class="bullet-dot"></span>${e}</li>`)
      .join("");

    container.innerHTML = `
      <!-- Recommendation Banner -->
      <div class="sim-rec-banner ${recClass}">
        <div class="sim-rec-icon">${rec === "TAKE_BREAK" ? "☕" : rec === "ALLOW" ? "✅" : "🛡️"}</div>
        <div class="sim-rec-content">
          <div class="sim-rec-label">TWIN DIRECTIVE: ${rec}</div>
          <div class="sim-rec-title">${sim.recommendationText}</div>
        </div>
        <div class="sim-rec-badge">${Math.round((sim.confidence || 0.8) * 100)}% Confidence</div>
      </div>

      <!-- Side-by-Side Paths -->
      <div class="sim-comparison-grid">
        <!-- ALLOW Path -->
        <div class="sim-path-card path-allow">
          <div class="path-header">
            <span class="path-title">Path A: ALLOW Now</span>
            <span class="path-badge badge-danger">Immediate Interruption</span>
          </div>
          <div class="path-metrics">
            <div class="path-metric-row">
              <span>Attention Drop Risk</span>
              <strong class="text-red">${Math.round(allow.attentionDropProbability * 100)}%</strong>
            </div>
            <div class="path-metric-row">
              <span>Expected Distraction</span>
              <strong>${allow.expectedDistractionMinutes} min (${Math.round(allow.expectedDistractionSec)}s)</strong>
            </div>
            <div class="path-metric-row">
              <span>Recovery Duration</span>
              <strong>${Math.round(allow.expectedRecoverySec)}s</strong>
            </div>
            <div class="path-metric-row">
              <span>Browsing Overrun Risk</span>
              <strong class="text-amber">${Math.round(allow.prolongedBrowsingProbability * 100)}%</strong>
            </div>
            <div class="path-metric-row">
              <span>Estimated Score Impact</span>
              <strong class="text-red">${allow.estimatedScoreImpact} pts</strong>
            </div>
          </div>
        </div>

        <!-- DEFER Path -->
        <div class="sim-path-card path-defer">
          <div class="path-header">
            <span class="path-title">Path B: DEFER ${defer.deferMinutes || 15}m</span>
            <span class="path-badge badge-success">Flow Preserved</span>
          </div>
          <div class="path-metrics">
            <div class="path-metric-row">
              <span>Attention Drop Risk</span>
              <strong class="text-green">${Math.round(defer.attentionDropProbability * 100)}%</strong>
            </div>
            <div class="path-metric-row">
              <span>Glance Distraction</span>
              <strong>${defer.expectedDistractionMinutes} min (${Math.round(defer.expectedDistractionSec)}s)</strong>
            </div>
            <div class="path-metric-row">
              <span>Recovery Duration</span>
              <strong>${Math.round(defer.expectedRecoverySec)}s</strong>
            </div>
            <div class="path-metric-row">
              <span>Browsing Overrun Risk</span>
              <strong class="text-green">${Math.round(defer.prolongedBrowsingProbability * 100)}%</strong>
            </div>
            <div class="path-metric-row">
              <span>Estimated Score Impact</span>
              <strong class="text-green">${defer.estimatedScoreImpact} pts</strong>
            </div>
          </div>
        </div>
      </div>

      <!-- Quantifiable Savings Bar -->
      <div class="sim-savings-card">
        <div class="savings-header">
          <span>🎯 Quantifiable Focus Saved by Deferring:</span>
          <strong class="text-cyan">+${savings.savedDistractionMinutes} Minutes of Deep Concentration</strong>
        </div>
        <div class="savings-chips-row">
          <span class="savings-chip">🛡️ +${savings.focusPreservationPct}% Focus Preserved</span>
          <span class="savings-chip">⚡ ${savings.savedRecoverySec}s Recovery Advantage</span>
          <span class="savings-chip">📉 -${savings.browsingRiskReductionPct}% Overrun Risk</span>
        </div>
      </div>

      <!-- Grounded Explanations -->
      <div class="sim-explain-box">
        <h5 class="explain-title">Grounded Digital Twin Evidence (WHY?):</h5>
        <ul class="diag-reasons-list">
          ${expHtml}
        </ul>
      </div>

      <!-- Interactive Closed-Loop Learning Trigger -->
      <div class="sim-closed-loop-action" id="simOutcomeSection">
        <div class="outcome-action-text">
          <strong>Test Closed-Loop Learning:</strong> Record what actually happened to train the twin model:
        </div>
        <div class="outcome-action-inputs">
          <select id="inputActualAction" class="sim-select-sm">
            <option value="ALLOW">User Chose: ALLOW</option>
            <option value="DEFER">User Chose: DEFER</option>
          </select>
          <input type="number" id="inputActualDistraction" class="sim-input-sm" placeholder="Actual Distraction (sec)" value="${Math.round(allow.expectedDistractionSec * 0.8)}" />
          <button class="btn-outcome-submit" id="btnSubmitSimOutcome">
            🎓 Record Outcome & Learn
          </button>
        </div>
        <div id="simOutcomeFeedback" class="outcome-feedback-box hidden"></div>
      </div>
    `;

    // Hook outcome recorder button
    const btnRecord = document.getElementById("btnSubmitSimOutcome");
    if (btnRecord && sim.predictionId) {
      btnRecord.addEventListener("click", () => submitSimulatedOutcome(sim.predictionId));
    }
  }

  async function submitSimulatedOutcome(predictionId) {
    const action = document.getElementById("inputActualAction")?.value || "ALLOW";
    const actualSec = parseFloat(document.getElementById("inputActualDistraction")?.value || 120);

    const feedback = document.getElementById("simOutcomeFeedback");
    const btn = document.getElementById("btnSubmitSimOutcome");

    if (btn) btn.disabled = true;

    try {
      const res = await fetch("/api/closed-loop/outcome", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          predictionId: predictionId,
          actualAction: action,
          actualDistractionSec: actualSec,
        }),
      });

      if (!res.ok) throw new Error("Failed to record outcome");
      const data = await res.json();

      if (feedback) {
        feedback.classList.remove("hidden");
        feedback.innerHTML = `
          <div class="feedback-success">
            <strong>✅ Closed-Loop Updated:</strong> Actual distraction was ${data.actualDistractionSec}s (Prediction Error: ${data.predictionErrorSec}s, Accuracy: ${data.accuracyPct}%).
            <br/><small>${data.adaptation?.rationale || "Twin calibrated based on observation."}</small>
          </div>
        `;
      }
      loadAccuracySummary(); // Refresh telemetry widgets
    } catch (e) {
      alert("Closed loop error: " + e.message);
    } finally {
      if (btn) btn.disabled = false;
    }
  }

  // ── PANEL 7: Prediction Accuracy & 'LAST PREDICTION' Widget ─────
  async function loadAccuracySummary() {
    const uid = await resolveUser();
    if (!uid) return;

    try {
      const res = await fetch(`/api/closed-loop/summary?userId=${uid}`);
      if (!res.ok) return;
      const data = await res.json();

      // Top accuracy overview
      const totalEval = document.getElementById("accTotalEvaluated");
      const maeVal = document.getElementById("accMaeVal");
      const rmseVal = document.getElementById("accRmseVal");
      const avgAccVal = document.getElementById("accAveragePct");
      const statusVal = document.getElementById("accLearningStatus");

      if (totalEval) totalEval.textContent = data.totalPredictionsEvaluated || 0;
      if (maeVal) maeVal.textContent = `${data.meanAbsoluteErrorSec || 0}s`;
      if (rmseVal) rmseVal.textContent = `${data.rootMeanSquareErrorSec || 0}s`;
      if (avgAccVal) avgAccVal.textContent = `${data.averageAccuracyPct || 100}%`;
      if (statusVal) statusVal.textContent = data.learningStatus || "LEARNING";

      // 'LAST PREDICTION' Widget
      const lp = data.lastPrediction;
      const widget = document.getElementById("lastPredictionWidget");
      if (widget) {
        if (!lp) {
          widget.innerHTML = `<div class="lp-empty">No closed-loop evaluations completed yet. Run a simulation and click "Record Outcome & Learn" to see real-time error tracking.</div>`;
        } else {
          widget.innerHTML = `
            <div class="lp-card">
              <div class="lp-header">
                <span class="lp-tag">LAST PREDICTION</span>
                <span class="lp-action-badge">${lp.actualAction || "ALLOW"}</span>
              </div>
              <div class="lp-body">
                <div class="lp-row">
                  <span class="lp-label">Predicted distraction:</span>
                  <span class="lp-val">${lp.predictedDistraction}</span>
                </div>
                <div class="lp-row">
                  <span class="lp-label">Actual:</span>
                  <span class="lp-val text-green">${lp.actualDistraction}</span>
                </div>
                <div class="lp-row">
                  <span class="lp-label">Prediction error:</span>
                  <span class="lp-val text-amber">${lp.predictionError}</span>
                </div>
                <div class="lp-row">
                  <span class="lp-label">Accuracy:</span>
                  <span class="lp-val text-cyan">${lp.accuracyPct}%</span>
                </div>
              </div>
              <div class="lp-footer">
                <span>Task: ${lp.taskContext || "Coding"}</span>
                <span class="lp-learning-pill">✓ Twin Learned & Calibrated</span>
              </div>
            </div>
          `;
        }
      }
    } catch (e) {
      console.warn("Accuracy summary warning:", e);
    }
  }

  // ── PANEL 8: Privacy & Data Governance ──────────────────────────
  async function loadPrivacyProfile() {
    const uid = await resolveUser();
    const expBtn = document.getElementById("btnExportPrivacyData");
    const clearTwinBtn = document.getElementById("btnClearTwinProfile");
    const purgeHistBtn = document.getElementById("btnPurgeHistory");

    if (expBtn && !expBtn._bound) {
      expBtn._bound = true;
      expBtn.addEventListener("click", async () => {
        window.open(`/api/privacy/export?userId=${uid}`, "_blank");
      });
    }

    if (clearTwinBtn && !clearTwinBtn._bound) {
      clearTwinBtn._bound = true;
      clearTwinBtn.addEventListener("click", async () => {
        if (!confirm("Are you sure you want to reset your Digital Twin profile back to initial LEARNING state?")) return;
        const res = await fetch("/api/privacy/clear-twin", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ userId: uid }),
        });
        if (res.ok) {
          alert("Digital Twin profile reset successfully.");
          loadDigitalTwinProfile();
        }
      });
    }

    if (purgeHistBtn && !purgeHistBtn._bound) {
      purgeHistBtn._bound = true;
      purgeHistBtn.addEventListener("click", async () => {
        if (!confirm("Are you sure you want to permanently delete all study session events and distraction history? This cannot be undone.")) return;
        const res = await fetch("/api/privacy/delete-history", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ userId: uid }),
        });
        if (res.ok) {
          alert("Session history and attention events securely purged.");
          location.reload();
        }
      });
    }
  }

  // ── PHASE 8: DEMO NOTIFICATION MODE & DETERMINISTIC FLOW ─────────
  function initDemoNotificationMode() {
    // 1. Seed Demo Persona (Ada Lovelace)
    const btnSeed = document.getElementById("btnSeedDemoPersona");
    if (btnSeed && !btnSeed._bound) {
      btnSeed._bound = true;
      btnSeed.addEventListener("click", async () => {
        btnSeed.disabled = true;
        btnSeed.textContent = "Seeding Demo Persona...";
        try {
          const res = await fetch("/api/demo/seed", { method: "POST" });
          if (!res.ok) throw new Error("Failed to seed demo persona");
          const data = await res.json();
          activeUserId = data.userId;

          const nameEl = document.getElementById("userDisplayName");
          const idEl = document.getElementById("userDisplayId");
          if (nameEl) nameEl.textContent = data.userName;
          if (idEl) idEl.textContent = data.userId;

          await loadDigitalTwinProfile();
          await loadAccuracySummary();
          await loadDiagnostic();
          await loadSimulatorPresets();

          alert(`✨ Demo Persona (${data.userName}) successfully seeded with ${data.sessionsSeeded} historical study sessions and calibrated twin!`);
        } catch (e) {
          alert("Error seeding demo: " + e.message);
        } finally {
          btnSeed.disabled = false;
          btnSeed.textContent = "✨ Seed Demo Persona";
        }
      });
    }

    // 2. Clean Demo Data
    const btnClean = document.getElementById("btnCleanDemoData");
    if (btnClean && !btnClean._bound) {
      btnClean._bound = true;
      btnClean.addEventListener("click", async () => {
        if (!confirm("Purge demo persona data? This will strictly delete demo records while keeping all real user data intact.")) return;
        try {
          const res = await fetch("/api/demo/clean", { method: "POST" });
          if (res.ok) {
            activeUserId = null;
            await resolveUser();
            await loadDigitalTwinProfile();
            await loadAccuracySummary();
            await loadDiagnostic();
            alert("🧹 Demo persona cleaned. Reverted to standard profile.");
          }
        } catch (e) {
          alert("Error cleaning demo data: " + e.message);
        }
      });
    }

    // 3. Run Deterministic Flow
    const btnFlow = document.getElementById("btnRunDeterministicFlow");
    const detCard = document.getElementById("deterministicResultCard");
    const detBody = document.getElementById("detFlowBody");
    const detClose = document.getElementById("btnCloseDeterministicCard");

    if (detClose && !detClose._bound) {
      detClose._bound = true;
      detClose.addEventListener("click", () => {
        if (detCard) detCard.classList.add("hidden");
      });
    }

    if (btnFlow && !btnFlow._bound) {
      btnFlow._bound = true;
      btnFlow.addEventListener("click", async () => {
        btnFlow.disabled = true;
        btnFlow.textContent = "Running Walkthrough...";
        try {
          const res = await fetch("/api/demo/run-deterministic-flow", { method: "POST" });
          if (!res.ok) throw new Error("Deterministic walkthrough failed");
          const data = await res.json();

          if (detCard && detBody) {
            detCard.classList.remove("hidden");
            const stepsHtml = (data.steps || [])
              .map(
                (s) => `
              <div class="det-step-item">
                <span class="step-num">Step ${s.step}</span>
                <span class="step-badge">${s.name}</span>
                <span class="step-detail">${s.detail}</span>
                <span class="step-check">✔ ${s.status}</span>
              </div>
            `
              )
              .join("");

            detBody.innerHTML = `
              <div class="det-steps-list">${stepsHtml}</div>
              <div class="det-metrics-summary">
                <div class="det-met-box">
                  <span class="det-m-lbl">Prediction Error</span>
                  <span class="det-m-val text-green">${data.metrics.predictionErrorSec}s</span>
                </div>
                <div class="det-met-box">
                  <span class="det-m-lbl">Model Accuracy</span>
                  <span class="det-m-val text-cyan">${data.metrics.accuracyPct}%</span>
                </div>
                <div class="det-met-box">
                  <span class="det-m-lbl">Total Evaluated</span>
                  <span class="det-m-val">${data.metrics.totalPredictionsEvaluated}</span>
                </div>
                <div class="det-met-box">
                  <span class="det-m-lbl">Overall MAE</span>
                  <span class="det-m-val text-amber">${data.metrics.meanAbsoluteErrorSec}s</span>
                </div>
              </div>
              <div class="det-story-box">
                <strong>CASCADE PS 3.3 Core Story:</strong>
                <p>"${data.story}"</p>
              </div>
            `;
          }
          await loadAccuracySummary();
          await loadDigitalTwinProfile();
        } catch (e) {
          alert("Flow error: " + e.message);
        } finally {
          btnFlow.disabled = false;
          btnFlow.textContent = "▶ Run Deterministic Flow";
        }
      });
    }

    // 4. Quick Trigger Demo Notification Buttons (Section 34)
    const triggerButtons = document.querySelectorAll(".btn-demo-trigger");
    const liveModal = document.getElementById("demoLiveModal");
    const modalTitle = document.getElementById("demoModalNotifTitle");
    const modalBody = document.getElementById("demoModalBody");
    const modalClose = document.getElementById("btnCloseDemoModal");

    if (modalClose && !modalClose._bound) {
      modalClose._bound = true;
      modalClose.addEventListener("click", () => {
        if (liveModal) liveModal.classList.add("hidden");
      });
    }

    triggerButtons.forEach((btn) => {
      btn.addEventListener("click", async () => {
        const key = btn.getAttribute("data-key");
        btn.classList.add("pulse-active");
        setTimeout(() => btn.classList.remove("pulse-active"), 400);

        try {
          const res = await fetch("/api/demo/trigger-notification", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              notificationKey: key,
              userId: activeUserId,
              currentAttentionState: "FOCUSED",
              persist: true,
            }),
          });
          if (!res.ok) throw new Error("Failed to trigger demo notification");
          const sim = await res.json();

          if (modalTitle) modalTitle.textContent = sim.notificationTitle || key;
          if (liveModal && modalBody) {
            liveModal.classList.remove("hidden");
            const allow = sim.allowPath || {};
            const defer = sim.deferPath || {};
            const rec = sim.recommendation || "DEFER";

            modalBody.innerHTML = `
              <div class="sim-rec-banner ${rec === 'ALLOW' ? 'rec-allow' : rec === 'TAKE_BREAK' ? 'rec-break' : 'rec-defer'}">
                <div class="sim-rec-icon">${rec === 'ALLOW' ? '✅' : rec === 'TAKE_BREAK' ? '☕' : '🛡️'}</div>
                <div class="sim-rec-content">
                  <div class="sim-rec-label">TWIN RECOMMENDATION: ${rec}</div>
                  <div class="sim-rec-title">${sim.recommendationText}</div>
                </div>
                <div class="sim-rec-badge">${Math.round((sim.confidence || 0.85) * 100)}% Confidence</div>
              </div>

              <div class="sim-comparison-grid">
                <div class="sim-path-card path-allow">
                  <div class="path-header">
                    <span class="path-title">Path A: ALLOW Now</span>
                    <span class="path-badge badge-danger">Immediate Drop</span>
                  </div>
                  <div class="path-metrics">
                    <div class="path-metric-row"><span>Attention Drop</span><strong class="text-red">${Math.round(allow.attentionDropProbability * 100)}%</strong></div>
                    <div class="path-metric-row"><span>Expected Distraction</span><strong>${allow.expectedDistractionMinutes} min</strong></div>
                    <div class="path-metric-row"><span>Recovery Time</span><strong>${Math.round(allow.expectedRecoverySec)}s</strong></div>
                    <div class="path-metric-row"><span>Overrun Risk</span><strong class="text-amber">${Math.round(allow.prolongedBrowsingProbability * 100)}%</strong></div>
                  </div>
                </div>

                <div class="sim-path-card path-defer">
                  <div class="path-header">
                    <span class="path-title">Path B: DEFER ${defer.deferMinutes || 15}m</span>
                    <span class="path-badge badge-success">Flow Preserved</span>
                  </div>
                  <div class="path-metrics">
                    <div class="path-metric-row"><span>Attention Drop</span><strong class="text-green">${Math.round(defer.attentionDropProbability * 100)}%</strong></div>
                    <div class="path-metric-row"><span>Glance Distraction</span><strong>${defer.expectedDistractionMinutes} min</strong></div>
                    <div class="path-metric-row"><span>Recovery Time</span><strong>${Math.round(defer.expectedRecoverySec)}s</strong></div>
                    <div class="path-metric-row"><span>Overrun Risk</span><strong class="text-green">${Math.round(defer.prolongedBrowsingProbability * 100)}%</strong></div>
                  </div>
                </div>
              </div>

              <div class="demo-modal-actions">
                <button class="btn-demo-action btn-danger" id="btnModalActionAllow">
                  🚨 User Chooses: ALLOW (Observe Distraction)
                </button>
                <button class="btn-demo-action btn-success" id="btnModalActionDefer">
                  🛡️ User Chooses: DEFER 15m (Preserve Flow)
                </button>
              </div>
              <div id="demoModalFeedback" class="outcome-feedback-box hidden"></div>
            `;

            // Wire action buttons for immediate closed-loop learning
            const bAllow = document.getElementById("btnModalActionAllow");
            const bDefer = document.getElementById("btnModalActionDefer");

            if (bAllow) {
              bAllow.addEventListener("click", () =>
                recordDemoModalOutcome(sim.predictionId, "ALLOW", allow.expectedDistractionSec * 0.85, allow.expectedRecoverySec)
              );
            }
            if (bDefer) {
              bDefer.addEventListener("click", () =>
                recordDemoModalOutcome(sim.predictionId, "DEFER", defer.expectedDistractionSec, defer.expectedRecoverySec)
              );
            }
          }
        } catch (e) {
          alert("Simulation error: " + e.message);
        }
      });
    });
  }

  async function recordDemoModalOutcome(predId, action, distractionSec, recoverySec) {
    const feedback = document.getElementById("demoModalFeedback");
    try {
      const res = await fetch("/api/closed-loop/outcome", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          predictionId: predId,
          actualAction: action,
          actualDistractionSec: distractionSec,
          actualRecoverySec: recoverySec,
        }),
      });
      if (!res.ok) throw new Error("Failed to record demo outcome");
      const data = await res.json();

      if (feedback) {
        feedback.classList.remove("hidden");
        feedback.innerHTML = `
          <div class="feedback-success">
            <strong>✅ Closed-Loop Learning Updated:</strong>
            User chose <strong>${action}</strong>. Actual distraction was ${Math.round(data.actualDistractionSec)}s (Prediction Error: ${data.predictionErrorSec}s, Accuracy: ${data.accuracyPct}%).
            <br/><small>${data.adaptation?.rationale || "Digital Twin sensitivity adapted based on observed outcome."}</small>
          </div>
        `;
      }
      loadAccuracySummary();
    } catch (e) {
      alert("Error: " + e.message);
    }
  }

  // ── Initialize Digital Twin UI ──────────────────────────────────
  function init() {
    initNavigation();
    initDemoNotificationMode();
    resolveUser().then(() => {
      loadDigitalTwinProfile();
      loadSimulatorPresets();
      loadAccuracySummary();
      loadAttentionTimeline();
      loadDiagnostic();
    });

    // Recompute button
    const btnRecompute = document.getElementById("btnRecomputeTwin");
    if (btnRecompute) {
      btnRecompute.addEventListener("click", async () => {
        btnRecompute.disabled = true;
        btnRecompute.textContent = "Recomputing...";
        try {
          const res = await fetch("/api/attention-twin/profile/recompute", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ userId: activeUserId }),
          });
          if (res.ok) {
            await loadDigitalTwinProfile();
            alert("Digital Twin profile successfully synthesized from session history.");
          }
        } catch (e) {
          alert("Recompute error: " + e.message);
        } finally {
          btnRecompute.disabled = false;
          btnRecompute.textContent = "🔄 Recompute Twin Profile";
        }
      });
    }

    // Refresh diagnostic button
    const btnRefreshDiag = document.getElementById("btnRefreshDiagnostic");
    if (btnRefreshDiag) {
      btnRefreshDiag.addEventListener("click", () => {
        loadDiagnostic();
      });
    }

    // Simulation run button
    const btnSim = document.getElementById("btnRunSimulation");
    if (btnSim) {
      btnSim.addEventListener("click", runSimulation);
    }

    // Periodic attention HUD & timeline polling
    setInterval(updateAttentionStateHUD, 1000);
    setInterval(loadAttentionTimeline, 3000);
  }

  // Export for testing
  window.DigitalTwinUI = {
    init,
    resolveUser,
    loadDigitalTwinProfile,
    loadSimulatorPresets,
    runSimulation,
    loadAccuracySummary,
    loadDiagnostic,
    loadAttentionTimeline,
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
