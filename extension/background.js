/**
 * AttentionAI — Background Service Worker (Manifest V3)
 *
 * Tracks tab activation, window focus changes, and distraction duration
 * during active study sessions. Sends events to the AttentionAI backend.
 */

"use strict";

const BACKEND_URL = "http://localhost:5050";

// State
let activeSession = null;
let isAway = false;
let distractionStartTime = 0;
let previousTabMeta = { title: "Study Page", domain: "coursera.org" };
let currentTabMeta = { title: "", domain: "" };

/**
 * Sanitize URL to protect privacy: extract domain/hostname only.
 */
function extractDomain(urlStr) {
  if (!urlStr) return "unknown";
  try {
    const u = new URL(urlStr);
    return u.hostname.replace(/^www\./, "");
  } catch (_) {
    return "unknown";
  }
}

/**
 * Determine if a tab qualifies as a study tab for the active session.
 */
function isStudyTab(urlStr, title) {
  if (!urlStr) return false;
  const domain = extractDomain(urlStr).toLowerCase();
  const lowerUrl = urlStr.toLowerCase();

  // Always consider AttentionAI dashboard as a study tab
  if (domain.includes("localhost") || domain.includes("127.0.0.1")) {
    return true;
  }

  if (!activeSession) return false;

  const platform = (activeSession.platform || "coursera").toLowerCase();
  const courseUrl = (activeSession.course_url || "").toLowerCase();

  if (domain.includes("coursera.org") && platform.includes("coursera")) return true;
  if (domain.includes("edx.org") && platform.includes("edx")) return true;
  if (courseUrl && (lowerUrl.includes(courseUrl) || domain.includes(extractDomain(courseUrl)))) return true;

  return domain.includes(platform);
}

/**
 * Fetch current active session from local backend.
 */
async function syncActiveSession() {
  try {
    const resp = await fetch(`${BACKEND_URL}/api/sessions/active`);
    if (!resp.ok) return;
    const data = await resp.json();
    if (data.active && data.session) {
      activeSession = data.session;
      chrome.storage.local.set({ activeSession: data.session });
    } else {
      activeSession = null;
      isAway = false;
      distractionStartTime = 0;
      chrome.storage.local.remove("activeSession");
    }
  } catch (err) {
    // Backend offline or unreachable — ignore silently
  }
}

/**
 * Send event to backend API.
 */
async function postEvent(eventType, metadata = {}, duration = 0) {
  if (!activeSession || !activeSession.session_id) return;
  const payload = {
    eventType,
    userId: activeSession.user_id,
    duration: Math.max(0, Math.round(duration * 100) / 100),
    metadata,
    timestamp: new Date().toISOString(),
  };

  try {
    await fetch(`${BACKEND_URL}/api/sessions/${encodeURIComponent(activeSession.session_id)}/events`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
  } catch (err) {
    console.warn("[Background] Failed to post event:", err);
  }
}

// ── Tab Switch Listener ─────────────────────────────────────────────
chrome.tabs.onActivated.addListener(async (activeInfo) => {
  await syncActiveSession();
  if (!activeSession) return;

  try {
    const tab = await chrome.tabs.get(activeInfo.tabId);
    if (!tab || !tab.url) return;

    const newDomain = extractDomain(tab.url);
    const newTitle = (tab.title || newDomain).slice(0, 60);
    const onStudyTab = isStudyTab(tab.url, tab.title);

    if (!onStudyTab) {
      // User switched AWAY to a non-study tab (distraction)
      if (!isAway) {
        isAway = true;
        distractionStartTime = Date.now();
        await postEvent("TAB_SWITCH", {
          previousTab: previousTabMeta.title,
          newTab: newTitle,
          previousUrl: previousTabMeta.domain,
          newUrl: newDomain,
          distraction: true,
        }, 0);
      }
    } else {
      // User returned back to study tab
      if (isAway) {
        const durationSec = Math.max(0, (Date.now() - distractionStartTime) / 1000);
        isAway = false;
        distractionStartTime = 0;
        await postEvent("FOCUS_REGAINED", {
          previousTab: currentTabMeta.title || "Off-task Tab",
          newTab: newTitle,
          previousUrl: currentTabMeta.domain,
          newUrl: newDomain,
          distractionDuration: Math.round(durationSec * 100) / 100,
        }, durationSec);
      }
    }

    previousTabMeta = currentTabMeta.domain ? { ...currentTabMeta } : previousTabMeta;
    currentTabMeta = { title: newTitle, domain: newDomain };

  } catch (err) {
    console.warn("[Background] onActivated error:", err);
  }
});

// ── Window Focus Listener (App Switching / Minimize) ────────────────
chrome.windows.onFocusChanged.addListener(async (windowId) => {
  await syncActiveSession();
  if (!activeSession) return;

  if (windowId === chrome.windows.WINDOW_ID_NONE) {
    // Entire browser window lost focus (user switched to another application)
    if (!isAway) {
      isAway = true;
      distractionStartTime = Date.now();
      await postEvent("PAGE_HIDDEN", {
        reason: "window_blur",
        activeTab: currentTabMeta.domain || "browser",
      }, 0);
    }
  } else {
    // Browser window regained focus
    if (isAway) {
      const durationSec = Math.max(0, (Date.now() - distractionStartTime) / 1000);
      isAway = false;
      distractionStartTime = 0;
      await postEvent("PAGE_VISIBLE", {
        reason: "window_focus",
        activeTab: currentTabMeta.domain || "browser",
        distractionDuration: Math.round(durationSec * 100) / 100,
      }, durationSec);
    }
  }
});

// ── Message Listener from Content Scripts ───────────────────────────
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg.type === "CONTENT_VISIBILITY_CHANGE") {
    syncActiveSession().then(() => {
      if (!activeSession) return;
      if (msg.hidden && !isAway) {
        isAway = true;
        distractionStartTime = Date.now();
        postEvent("PAGE_HIDDEN", { url: extractDomain(msg.url) }, 0);
      } else if (!msg.hidden && isAway) {
        const dur = Math.max(0, (Date.now() - distractionStartTime) / 1000);
        isAway = false;
        distractionStartTime = 0;
        postEvent("PAGE_VISIBLE", { url: extractDomain(msg.url), distractionDuration: dur }, dur);
      }
    });
    sendResponse({ ok: true });
  }
  return true;
});

// Periodically sync active session every 10 seconds
setInterval(syncActiveSession, 10000);
syncActiveSession();
