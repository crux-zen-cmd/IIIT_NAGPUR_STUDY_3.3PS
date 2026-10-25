/**
 * AttentionAI — Background Service Worker (Manifest V3)
 *
 * Real-time tab switch detection, in-tab URL change tracking,
 * window focus monitoring, and distraction duration logging.
 * Sends events to the AttentionAI backend API on http://127.0.0.1:5050
 */

"use strict";

const BACKEND_URLS = [
  "http://127.0.0.1:5050",
  "http://localhost:5050"
];
let activeBackendUrl = BACKEND_URLS[0];

// State
let activeSession        = null;
let isAway               = false;
let distractionStartTime = 0;
let previousTabMeta      = { title: "Study Dashboard", domain: "127.0.0.1" };
let currentTabMeta       = { title: "", domain: "" };
let tabSwitchCount       = 0;

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
function isStudyTab(urlStr, title = "") {
  if (!urlStr) return false;
  const domain = extractDomain(urlStr).toLowerCase();
  const lowerUrl = urlStr.toLowerCase();

  // Always consider AttentionAI dashboard as a study tab
  if (domain.includes("localhost") || domain.includes("127.0.0.1")) {
    return true;
  }

  // If no session started yet, treat common learning sites as study tabs
  if (!activeSession) {
    return domain.includes("coursera.org") || domain.includes("edx.org") || domain.includes("khanacademy.org");
  }

  const platform = (activeSession.platform || "coursera").toLowerCase();
  const courseUrl = (activeSession.course_url || "").toLowerCase();

  if (domain.includes("coursera.org") && platform.includes("coursera")) return true;
  if (domain.includes("edx.org") && platform.includes("edx")) return true;
  if (domain.includes("youtube.com") && platform.includes("youtube")) return true;
  if (courseUrl && (lowerUrl.includes(courseUrl) || domain.includes(extractDomain(courseUrl)))) return true;

  return domain.includes(platform);
}

/**
 * Fetch current active session from local backend with fallback.
 */
async function syncActiveSession() {
  for (const baseUrl of BACKEND_URLS) {
    try {
      const resp = await fetch(`${baseUrl}/api/sessions/active`);
      if (resp.ok) {
        activeBackendUrl = baseUrl;
        const data = await resp.json();
        if (data.active && data.session) {
          activeSession = data.session;
          chrome.storage.local.set({ activeSession: data.session, tabSwitchCount });
          updateBadge("ON", "#10b981");
        } else {
          activeSession = null;
          isAway = false;
          distractionStartTime = 0;
          chrome.storage.local.remove("activeSession");
          updateBadge("", "#64748b");
        }
        return;
      }
    } catch (_) {
      // Try next endpoint
    }
  }
}

/**
 * Update Chrome Extension icon badge
 */
function updateBadge(text, color) {
  if (!chrome.action) return;
  chrome.action.setBadgeText({ text: text || "" });
  if (color) {
    chrome.action.setBadgeBackgroundColor({ color });
  }
}

/**
 * Send event to backend API and notify dashboard tabs
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
    await fetch(`${activeBackendUrl}/api/sessions/${encodeURIComponent(activeSession.session_id)}/events`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
  } catch (err) {
    console.warn("[AttentionAI Extension] Failed to post event:", err);
  }

  // Notify any open AttentionAI dashboard tabs in real-time
  broadcastToDashboardTabs({
    type: "ATTENTION_EVENT_LOGGED",
    eventType,
    duration,
    metadata,
    tabSwitchCount,
  });
}

/**
 * Broadcast message to AttentionAI tabs so the UI increments live
 */
function broadcastToDashboardTabs(message) {
  try {
    chrome.tabs.query({}, (tabs) => {
      if (!tabs || !tabs.length) return;
      for (const tab of tabs) {
        if (tab.url && (tab.url.includes("localhost:5050") || tab.url.includes("127.0.0.1:5050"))) {
          chrome.tabs.sendMessage(tab.id, message).catch(() => {});
        }
      }
    });
  } catch (_) {}
}

/**
 * Handle tab transition (both tab switch and in-tab URL change)
 */
async function handleTabTransition(tabUrl, tabTitle) {
  if (!tabUrl) return;
  await syncActiveSession();

  const newDomain = extractDomain(tabUrl);
  const newTitle  = (tabTitle || newDomain).slice(0, 70);
  const onStudyTab = isStudyTab(tabUrl, tabTitle);

  if (!onStudyTab) {
    // User switched AWAY to a non-study tab (distraction / off-task)
    tabSwitchCount++;
    chrome.storage.local.set({ tabSwitchCount });
    updateBadge("AWAY", "#ef4444");

    if (!isAway) {
      isAway = true;
      distractionStartTime = Date.now();
      await postEvent("TAB_SWITCH", {
        previousTab: previousTabMeta.title,
        newTab: newTitle,
        previousUrl: previousTabMeta.domain,
        newUrl: newDomain,
        distraction: true,
        tabSwitchCount,
      }, 0);
    }
  } else {
    // User returned back to study tab
    updateBadge("FOCUS", "#10b981");
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
        tabSwitchCount,
      }, durationSec);
    }
  }

  previousTabMeta = currentTabMeta.domain ? { ...currentTabMeta } : previousTabMeta;
  currentTabMeta  = { title: newTitle, domain: newDomain };
}

// ── 1. Tab Activation (User clicks different tab) ───────────────────
chrome.tabs.onActivated.addListener(async (activeInfo) => {
  try {
    const tab = await chrome.tabs.get(activeInfo.tabId);
    if (!tab || !tab.url) return;
    await handleTabTransition(tab.url, tab.title);
  } catch (err) {
    console.warn("[AttentionAI Extension] onActivated error:", err);
  }
});

// ── 2. In-Tab URL Change (User types new URL in same tab) ───────────
chrome.tabs.onUpdated.addListener(async (tabId, changeInfo, tab) => {
  if (!changeInfo.url) return;
  try {
    // Only process if this is the currently active tab
    const [activeTab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (activeTab && activeTab.id === tabId) {
      await handleTabTransition(changeInfo.url, tab.title);
    }
  } catch (err) {
    console.warn("[AttentionAI Extension] onUpdated error:", err);
  }
});

// ── 3. Window Focus Change (Alt-Tab to another app or minimize) ─────
chrome.windows.onFocusChanged.addListener(async (windowId) => {
  await syncActiveSession();
  if (!activeSession) return;

  if (windowId === chrome.windows.WINDOW_ID_NONE) {
    // Entire browser window lost focus
    updateBadge("AWAY", "#ef4444");
    if (!isAway) {
      isAway = true;
      tabSwitchCount++;
      distractionStartTime = Date.now();
      await postEvent("PAGE_HIDDEN", {
        reason: "window_blur",
        activeTab: currentTabMeta.domain || "browser",
        tabSwitchCount,
      }, 0);
    }
  } else {
    // Browser window regained focus
    updateBadge("FOCUS", "#10b981");
    if (isAway) {
      const durationSec = Math.max(0, (Date.now() - distractionStartTime) / 1000);
      isAway = false;
      distractionStartTime = 0;
      await postEvent("PAGE_VISIBLE", {
        reason: "window_focus",
        activeTab: currentTabMeta.domain || "browser",
        distractionDuration: Math.round(durationSec * 100) / 100,
        tabSwitchCount,
      }, durationSec);
    }
  }
});

// ── 4. Content Script Message Dispatcher ────────────────────────────
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg.type === "CONTENT_VISIBILITY_CHANGE") {
    syncActiveSession().then(() => {
      if (!activeSession) return;
      if (msg.hidden && !isAway) {
        isAway = true;
        tabSwitchCount++;
        distractionStartTime = Date.now();
        postEvent("PAGE_HIDDEN", { url: extractDomain(msg.url), tabSwitchCount }, 0);
      } else if (!msg.hidden && isAway) {
        const dur = Math.max(0, (Date.now() - distractionStartTime) / 1000);
        isAway = false;
        distractionStartTime = 0;
        postEvent("PAGE_VISIBLE", { url: extractDomain(msg.url), distractionDuration: dur, tabSwitchCount }, dur);
      }
    });
    sendResponse({ ok: true });
  } else if (msg.type === "CHECK_EXTENSION_STATUS") {
    sendResponse({
      installed: true,
      activeSession: !!activeSession,
      tabSwitchCount,
      backendUrl: activeBackendUrl,
    });
  }
  return true;
});

// ── 5. Keep-Alive Alarm (Prevents MV3 Service Worker Inactivity) ────
chrome.alarms.create("attentionHeartbeat", { periodInMinutes: 0.35 });
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === "attentionHeartbeat") {
    syncActiveSession();
  }
});

// Initial session check
syncActiveSession();
