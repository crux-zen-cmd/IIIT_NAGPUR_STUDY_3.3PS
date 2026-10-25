/**
 * AttentionAI — DOM Activity & Learning Signal Monitor (Phase 4)
 *
 * Runs inside learning platforms (Coursera, edX, etc.) and study pages.
 * Extracts meaningful learning signals (video states, title/lecture transitions,
 * content interactions) without uploading raw HTML or capturing private data.
 */

"use strict";

(function () {
  const BACKEND_URL = "http://localhost:5050";

  // State
  let activeSession   = null;
  let isMonitoring    = false;
  let mutationObserver = null;
  let titleObserver   = null;
  let currentTitle    = document.title;
  let currentUrlPath  = window.location.pathname;
  let lastInteractionTime = 0;
  const offlineQueue  = [];
  const boundVideos   = new WeakSet();

  /**
   * Safe domain and title sanitizer (privacy protection).
   */
  function sanitizeMetadata(meta = {}) {
    const sanitized = {};
    for (const [key, val] of Object.entries(meta)) {
      if (typeof val === "string") {
        // Strip out any query strings, tokens, or emails
        sanitized[key] = val.split("?")[0].slice(0, 100);
      } else if (typeof val === "number" || typeof val === "boolean") {
        sanitized[key] = val;
      }
    }
    return sanitized;
  }

  /**
   * Queue-backed event dispatcher with offline fallback and deduplication.
   */
  async function dispatchLearningEvent(eventType, metadata = {}, duration = 0) {
    if (!activeSession || !activeSession.session_id) return;

    const payload = {
      eventType,
      userId: activeSession.user_id,
      duration: Math.max(0, Math.round(duration * 100) / 100),
      metadata: sanitizeMetadata(metadata),
      timestamp: new Date().toISOString(),
    };

    try {
      const resp = await fetch(
        `${BACKEND_URL}/api/sessions/${encodeURIComponent(activeSession.session_id)}/events`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload),
        }
      );
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);

      // If successful, flush any pending offline queue items
      flushOfflineQueue();
    } catch (err) {
      // Network temporarily unavailable — queue locally
      console.warn("[DOMMonitor] Network error, queuing event:", eventType);
      enqueueOfflineEvent(payload);
    }
  }

  function enqueueOfflineEvent(payload) {
    // Avoid unbounded queue growth
    if (offlineQueue.length >= 50) offlineQueue.shift();
    offlineQueue.push(payload);
    if (chrome && chrome.storage && chrome.storage.local) {
      chrome.storage.local.set({ offlineEventQueue: offlineQueue });
    }
  }

  async function flushOfflineQueue() {
    if (!offlineQueue.length || !activeSession) return;
    const pending = [...offlineQueue];
    offlineQueue.length = 0;

    for (const payload of pending) {
      try {
        await fetch(
          `${BACKEND_URL}/api/sessions/${encodeURIComponent(activeSession.session_id)}/events`,
          {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
          }
        );
      } catch (_) {
        // Still offline — put back
        offlineQueue.push(payload);
        break;
      }
    }
  }

  // ── Video Monitoring ──────────────────────────────────────────────

  function getVideoProgress(videoEl) {
    const cur = videoEl.currentTime || 0;
    const dur = videoEl.duration || 0;
    const pct = dur > 0 ? Math.round((cur / dur) * 1000) / 10 : 0;
    return {
      currentTime: Math.round(cur * 10) / 10,
      duration: Math.round(dur * 10) / 10,
      progressPercent: pct,
    };
  }

  function bindVideoListeners(videoEl) {
    if (!videoEl || boundVideos.has(videoEl)) return;
    boundVideos.add(videoEl);

    let hasStarted = false;

    videoEl.addEventListener("play", () => {
      if (!isMonitoring) return;
      const progress = getVideoProgress(videoEl);
      if (!hasStarted && progress.currentTime < 2.0) {
        hasStarted = true;
        dispatchLearningEvent("VIDEO_STARTED", {
          videoProgress: progress,
          mediaUrl: window.location.hostname,
        });
      } else {
        dispatchLearningEvent("VIDEO_RESUMED", {
          videoProgress: progress,
          mediaUrl: window.location.hostname,
        });
      }
    });

    videoEl.addEventListener("pause", () => {
      if (!isMonitoring || videoEl.ended) return;
      const progress = getVideoProgress(videoEl);
      dispatchLearningEvent("VIDEO_PAUSED", {
        videoProgress: progress,
        mediaUrl: window.location.hostname,
      });
    });

    videoEl.addEventListener("ended", () => {
      if (!isMonitoring) return;
      hasStarted = false;
      const progress = getVideoProgress(videoEl);
      progress.progressPercent = 100.0;
      dispatchLearningEvent("VIDEO_COMPLETED", {
        videoProgress: progress,
        mediaUrl: window.location.hostname,
      });
    });
  }

  function scanAndBindVideos() {
    document.querySelectorAll("video").forEach(bindVideoListeners);
  }

  // ── MutationObserver for Dynamic Content & SPA Route Changes ──────

  function initMutationObserver() {
    scanAndBindVideos();

    // 1. Observe DOM additions for dynamically rendered video elements
    mutationObserver = new MutationObserver((mutations) => {
      if (!isMonitoring) return;
      for (const mut of mutations) {
        if (mut.addedNodes && mut.addedNodes.length) {
          for (const node of mut.addedNodes) {
            if (node.nodeType === Node.ELEMENT_NODE) {
              if (node.tagName === "VIDEO") {
                bindVideoListeners(node);
              } else if (node.querySelectorAll) {
                node.querySelectorAll("video").forEach(bindVideoListeners);
              }
            }
          }
        }
      }
    });

    mutationObserver.observe(document.body || document.documentElement, {
      childList: true,
      subtree: true,
    });

    // 2. Observe title mutations (Coursera SPA lecture transitions)
    const titleEl = document.querySelector("title");
    if (titleEl) {
      let titleDebounce = null;
      titleObserver = new MutationObserver(() => {
        if (!isMonitoring) return;
        clearTimeout(titleDebounce);
        titleDebounce = setTimeout(() => {
          const newTitle = document.title;
          if (newTitle && newTitle !== currentTitle && newTitle.length > 2) {
            const prev = currentTitle;
            currentTitle = newTitle;
            dispatchLearningEvent("PAGE_CHANGED", {
              previousTitle: prev.slice(0, 80),
              newTitle: newTitle.slice(0, 80),
              domain: window.location.hostname,
            });
          }
        }, 800);
      });
      titleObserver.observe(titleEl, { childList: true, characterData: true, subtree: true });
    }
  }

  // ── Learning Interaction Throttler (Clicks / Content Engagement) ──

  function initInteractionListener() {
    let interactionDebounce = null;

    document.addEventListener("click", (e) => {
      if (!isMonitoring) return;
      // Filter out passwords, form fields, text inputs
      const tag = (e.target && e.target.tagName || "").toLowerCase();
      if (["input", "textarea", "select"].includes(tag) || e.target.isContentEditable) {
        return;
      }

      const now = Date.now();
      // Throttle to at most once every 60 seconds
      if (now - lastInteractionTime > 60000) {
        lastInteractionTime = now;
        dispatchLearningEvent("CONTENT_INTERACTED", {
          interactionType: "click",
          domain: window.location.hostname,
        });
      }
    }, { passive: true });
  }

  // ── Session Sync & Lifecycle Management ────────────────────────────

  async function checkSessionStatus() {
    try {
      const resp = await fetch(`${BACKEND_URL}/api/sessions/active`);
      if (!resp.ok) {
        stopMonitoring();
        return;
      }
      const data = await resp.json();
      if (data.active && data.session) {
        if (!activeSession || activeSession.session_id !== data.session.session_id) {
          startMonitoring(data.session);
        }
      } else {
        stopMonitoring();
      }
    } catch (_) {
      // Backend offline
    }
  }

  function startMonitoring(session) {
    activeSession = session;
    if (isMonitoring) return;
    isMonitoring = true;
    console.log("[DOMMonitor] Active session started, monitoring learning signals:", session.session_id);

    initMutationObserver();
    initInteractionListener();

    // Check if network just recovered
    window.addEventListener("online", flushOfflineQueue);
  }

  function stopMonitoring() {
    if (!isMonitoring) return;
    isMonitoring = false;
    activeSession = null;
    console.log("[DOMMonitor] Session inactive, monitoring halted.");

    if (mutationObserver) {
      mutationObserver.disconnect();
      mutationObserver = null;
    }
    if (titleObserver) {
      titleObserver.disconnect();
      titleObserver = null;
    }
  }

  // Listen for storage changes from background worker
  if (chrome && chrome.storage && chrome.storage.onChanged) {
    chrome.storage.onChanged.addListener((changes, area) => {
      if (area === "local" && changes.activeSession) {
        if (changes.activeSession.newValue) {
          startMonitoring(changes.activeSession.newValue);
        } else {
          stopMonitoring();
        }
      }
    });
  }

  // Listen for messages from background.js and forward to webpage
  if (chrome && chrome.runtime && chrome.runtime.onMessage) {
    chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
      if (msg && msg.type === "ATTENTION_EVENT_LOGGED") {
        window.dispatchEvent(new CustomEvent("attention:extension_event", { detail: msg }));
        window.dispatchEvent(new CustomEvent("attention:tab_switch", { detail: msg }));
      }
      sendResponse({ received: true });
      return true;
    });
  }

  // Mark webpage so AttentionAI dashboard knows extension is installed
  try {
    document.documentElement.setAttribute("data-attention-extension", "connected");
    window.__ATTENTION_AI_EXTENSION_INSTALLED__ = true;
  } catch (_) {}

  // Periodic session health-check
  setInterval(checkSessionStatus, 8000);
  checkSessionStatus();

  // Export for testing / direct invocation
  window.DOMActivityMonitor = {
    start: startMonitoring,
    stop: stopMonitoring,
    scanVideos: scanAndBindVideos,
    dispatch: dispatchLearningEvent,
  };
})();
