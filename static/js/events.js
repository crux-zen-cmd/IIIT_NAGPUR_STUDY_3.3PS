/**
 * AttentionAI / Study Tracker — Reusable Session Event Service (Phase 2)
 *
 * Provides a standardized, forward-compatible interface for logging
 * arbitrary study session events (tab changes, visibility, video controls,
 * focus transitions, etc.) without binding to raw page contents.
 */

"use strict";

(function (window) {
  const EVENT_TYPES = {
    TAB_SWITCH:     "TAB_SWITCH",
    PAGE_HIDDEN:    "PAGE_HIDDEN",
    PAGE_VISIBLE:   "PAGE_VISIBLE",
    DROWSINESS:     "DROWSINESS",
    FOCUS_LOST:     "FOCUS_LOST",
    FOCUS_REGAINED: "FOCUS_REGAINED",
    VIDEO_PAUSED:   "VIDEO_PAUSED",
    VIDEO_RESUMED:  "VIDEO_RESUMED",
    PAGE_CHANGED:   "PAGE_CHANGED",
  };

  /**
   * Log a structured study session event.
   *
   * @param {string} [sessionId] - Unique session ID (defaults to active session if omitted)
   * @param {string} eventType   - e.g. 'TAB_SWITCH', 'VIDEO_PAUSED'
   * @param {Object} [metadata]  - Structured JSON metadata object (e.g. { from: 'coursera', to: 'youtube' })
   * @param {number} [duration]  - Optional duration in seconds
   * @returns {Promise<Object>}  - The saved event record
   */
  async function logSessionEvent(sessionId, eventType, metadata = {}, duration = 0) {
    // Auto-resolve sessionId from active session if not explicitly provided
    let targetSessionId = sessionId;
    if (!targetSessionId && window.currentActiveSession && window.currentActiveSession.session_id) {
      targetSessionId = window.currentActiveSession.session_id;
    }

    if (!targetSessionId) {
      console.warn("[SessionEventService] Skipping event log: no active session ID available.");
      return null;
    }

    const cleanType = String(eventType || "").trim().toUpperCase();
    if (!cleanType) {
      throw new Error("eventType must be provided");
    }

    const payload = {
      eventType: cleanType,
      metadata: (typeof metadata === "object" && metadata !== null) ? metadata : { raw: metadata },
      duration: Math.max(0, Number(duration) || 0.0),
      timestamp: new Date().toISOString(),
    };

    if (window.currentUser && window.currentUser.user_id) {
      payload.userId = window.currentUser.user_id;
    }

    try {
      const resp = await fetch(`/api/sessions/${encodeURIComponent(targetSessionId)}/events`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      const data = await resp.json();
      if (!resp.ok) {
        throw new Error(data.error || `HTTP ${resp.status}`);
      }

      // Notify any listening components
      window.dispatchEvent(new CustomEvent("studySessionEventLogged", {
        detail: data.event,
      }));

      return data.event;
    } catch (err) {
      console.warn("[SessionEventService] Event queued offline:", cleanType, err.message);
      saveOfflineEvent({
        sessionId: targetSessionId,
        eventType: cleanType,
        metadata: payload.metadata,
        duration: payload.duration,
        timestamp: payload.timestamp,
        userId: payload.userId,
      });

      const syntheticEvent = {
        event_id: `offline_${Date.now()}`,
        session_id: targetSessionId,
        event_type: cleanType,
        metadata: payload.metadata,
        duration: payload.duration,
        timestamp: payload.timestamp,
      };
      window.dispatchEvent(new CustomEvent("studySessionEventLogged", {
        detail: syntheticEvent,
      }));
      return syntheticEvent;
    }
  }

  function getOfflineQueue() {
    try {
      const raw = localStorage.getItem("studyTracker_offline_queue");
      return raw ? JSON.parse(raw) : [];
    } catch (_) {
      return [];
    }
  }

  function saveOfflineEvent(ev) {
    try {
      const q = getOfflineQueue();
      if (q.length >= 60) q.shift();
      q.push(ev);
      localStorage.setItem("studyTracker_offline_queue", JSON.stringify(q));
    } catch (_) {}
  }

  async function flushOfflineEvents() {
    const q = getOfflineQueue();
    if (!q.length) return;
    const remaining = [];
    for (const ev of q) {
      try {
        await fetch(`/api/sessions/${encodeURIComponent(ev.sessionId)}/events`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(ev),
        });
      } catch (_) {
        remaining.push(ev);
      }
    }
    try {
      if (remaining.length) {
        localStorage.setItem("studyTracker_offline_queue", JSON.stringify(remaining));
      } else {
        localStorage.removeItem("studyTracker_offline_queue");
      }
    } catch (_) {}
  }

  // Auto flush offline queue periodically and when online
  if (typeof window !== "undefined") {
    window.addEventListener("online", flushOfflineEvents);
    setInterval(flushOfflineEvents, 8000);

    // Gracefully handle browser/tab closing with sendBeacon
    window.addEventListener("beforeunload", () => {
      if (window.currentActiveSession && window.currentActiveSession.session_id) {
        const payload = JSON.stringify({
          eventType: "PAGE_HIDDEN",
          userId: window.currentUser ? window.currentUser.user_id : undefined,
          metadata: { reason: "tab_closed_or_reloaded", source: "page_visibility_api" },
          timestamp: new Date().toISOString(),
          duration: 0,
        });
        if (navigator.sendBeacon) {
          navigator.sendBeacon(
            `/api/sessions/${encodeURIComponent(window.currentActiveSession.session_id)}/events`,
            payload
          );
        }
      }
    });
  }

  /**
   * Fetch all logged events for a specific session.
   *
   * @param {string} sessionId
   * @param {number} [limit=100]
   * @returns {Promise<Array>}
   */
  async function getSessionEvents(sessionId, limit = 100) {
    if (!sessionId) return [];
    try {
      const resp = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/events?limit=${limit}`);
      if (!resp.ok) {
        throw new Error(`Failed to fetch events: HTTP ${resp.status}`);
      }
      return await resp.json();
    } catch (err) {
      console.error("[SessionEventService] getSessionEvents error:", err);
      return [];
    }
  }

  /**
   * Page Visibility & Tab-Switch Tracker (Phase 3)
   *
   * Automatically monitors when the user switches tabs or hides the page,
   * calculating the exact duration away and logging TAB_SWITCH/PAGE_HIDDEN
   * and FOCUS_REGAINED/PAGE_VISIBLE events.
   */
  let isAway = false;
  let awayStartTime = 0;
  let leaveTimer = null;

  function initPageVisibilityTracker() {
    function handleVisibility(hidden, reason = "tab_switch") {
      // Only track if there is an active study session
      if (!window.currentActiveSession || !window.currentActiveSession.session_id) {
        isAway = false;
        awayStartTime = 0;
        return;
      }

      if (hidden) {
        // Debounce slightly to prevent bogus blips during rapid modal clicks
        clearTimeout(leaveTimer);
        leaveTimer = setTimeout(() => {
          if (!isAway && window.currentActiveSession) {
            isAway = true;
            awayStartTime = Date.now();
            logSessionEvent(
              window.currentActiveSession.session_id,
              reason === "blur" ? "PAGE_HIDDEN" : "TAB_SWITCH",
              {
                source: "page_visibility_api",
                previousTab: document.title || "Study Page",
                newTab: "Off-task Tab / Application",
                previousUrl: window.location.hostname || "localhost",
                newUrl: "off_tab",
                distraction: true,
              },
              0
            ).catch(() => {});
          }
        }, 300);
      } else {
        clearTimeout(leaveTimer);
        if (isAway && awayStartTime > 0 && window.currentActiveSession) {
          const durationSec = Math.max(0, (Date.now() - awayStartTime) / 1000);
          isAway = false;
          awayStartTime = 0;
          logSessionEvent(
            window.currentActiveSession.session_id,
            reason === "focus" ? "PAGE_VISIBLE" : "FOCUS_REGAINED",
            {
              source: "page_visibility_api",
              previousTab: "Off-task Tab / Application",
              newTab: document.title || "Study Page",
              previousUrl: "off_tab",
              newUrl: window.location.hostname || "localhost",
              distractionDuration: Math.round(durationSec * 100) / 100,
            },
            durationSec
          ).catch(() => {});
        }
      }
    }

    document.addEventListener("visibilitychange", () => {
      handleVisibility(document.hidden, "visibilitychange");
    });

    window.addEventListener("blur", () => {
      handleVisibility(true, "blur");
    });

    window.addEventListener("focus", () => {
      handleVisibility(false, "focus");
    });
  }

  /**
   * DOM & Video Activity Monitor (Phase 4)
   *
   * Observes video play/pause/resume/completed states, title/lecture transitions,
   * and queues events offline if network is temporarily disconnected.
   */
  const offlineQueue = [];
  const boundVideos = new WeakSet();
  let domMutationObserver = null;
  let titleMutationObserver = null;
  let currentTitle = typeof document !== "undefined" ? document.title : "";

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
      if (!window.currentActiveSession) return;
      const progress = getVideoProgress(videoEl);
      if (!hasStarted && progress.currentTime < 2.0) {
        hasStarted = true;
        logSessionEvent(
          window.currentActiveSession.session_id,
          "VIDEO_STARTED",
          { videoProgress: progress, mediaTag: "learning_video" },
          0
        ).catch(() => {});
      } else {
        logSessionEvent(
          window.currentActiveSession.session_id,
          "VIDEO_RESUMED",
          { videoProgress: progress, mediaTag: "learning_video" },
          0
        ).catch(() => {});
      }
    });

    videoEl.addEventListener("pause", () => {
      if (!window.currentActiveSession || videoEl.ended) return;
      const progress = getVideoProgress(videoEl);
      logSessionEvent(
        window.currentActiveSession.session_id,
        "VIDEO_PAUSED",
        { videoProgress: progress, mediaTag: "learning_video" },
        0
      ).catch(() => {});
    });

    videoEl.addEventListener("ended", () => {
      if (!window.currentActiveSession) return;
      hasStarted = false;
      const progress = getVideoProgress(videoEl);
      progress.progressPercent = 100.0;
      logSessionEvent(
        window.currentActiveSession.session_id,
        "VIDEO_COMPLETED",
        { videoProgress: progress, mediaTag: "learning_video" },
        0
      ).catch(() => {});
    });
  }

  function initDOMActivityMonitor() {
    if (typeof document === "undefined") return;

    // 1. Initial scan for video elements
    document.querySelectorAll("video").forEach(bindVideoListeners);

    // 2. MutationObserver for dynamically loaded video elements
    domMutationObserver = new MutationObserver((mutations) => {
      if (!window.currentActiveSession) return;
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

    domMutationObserver.observe(document.body || document.documentElement, {
      childList: true,
      subtree: true,
    });

    // 3. Title observer for lecture / module changes
    const titleEl = document.querySelector("title");
    if (titleEl) {
      let titleTimer = null;
      titleMutationObserver = new MutationObserver(() => {
        if (!window.currentActiveSession) return;
        clearTimeout(titleTimer);
        titleTimer = setTimeout(() => {
          const newTitle = document.title;
          if (newTitle && newTitle !== currentTitle && newTitle.length > 2) {
            const prev = currentTitle;
            currentTitle = newTitle;
            logSessionEvent(
              window.currentActiveSession.session_id,
              "PAGE_CHANGED",
              { previousTitle: prev.slice(0, 80), newTitle: newTitle.slice(0, 80) },
              0
            ).catch(() => {});
          }
        }, 800);
      });
      titleMutationObserver.observe(titleEl, { childList: true, characterData: true, subtree: true });
    }

    // 4. Offline sync listener
    window.addEventListener("online", async () => {
      if (!offlineQueue.length || !window.currentActiveSession) return;
      console.log("[SessionEventService] Network reconnected, flushing offline queue...");
      const pending = [...offlineQueue];
      offlineQueue.length = 0;
      for (const ev of pending) {
        try {
          await logSessionEvent(ev.sessionId, ev.eventType, ev.metadata, ev.duration);
        } catch (_) {
          offlineQueue.push(ev);
          break;
        }
      }
    });
  }

  // Initialize trackers on DOM ready
  if (typeof document !== "undefined") {
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", () => {
        initPageVisibilityTracker();
        initDOMActivityMonitor();
      });
    } else {
      initPageVisibilityTracker();
      initDOMActivityMonitor();
    }
  }

  // Export globally
  window.logSessionEvent    = logSessionEvent;
  window.getSessionEvents   = getSessionEvents;
  window.bindVideoListeners = bindVideoListeners;
  window.SessionEventService = {
    logEvent: logSessionEvent,
    getEvents: getSessionEvents,
    initTracker: initPageVisibilityTracker,
    initDOMMonitor: initDOMActivityMonitor,
    bindVideo: bindVideoListeners,
    EVENT_TYPES,
  };

})(typeof window !== "undefined" ? window : globalThis);


