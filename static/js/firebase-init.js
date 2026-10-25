// Firebase Configuration & Initialization
// Project: csviiitnag

import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.0/firebase-app.js";
import { getAnalytics, isSupported, logEvent } from "https://www.gstatic.com/firebasejs/10.12.0/firebase-analytics.js";

const firebaseConfig = {
  apiKey: "AIzaSyAk2W_3KByDcauJhk65TO1MQO7Cti0kBgM",
  authDomain: "csviiitnag.firebaseapp.com",
  projectId: "csviiitnag",
  storageBucket: "csviiitnag.firebasestorage.app",
  messagingSenderId: "192292026195",
  appId: "1:192292026195:web:7b2a1bff729c9f93c013a6",
  measurementId: "G-15NED4J11D"
};

// Global handles
window.firebaseConfig = firebaseConfig;
window.firebaseApp = null;
window.firebaseAnalytics = null;
window.firebaseConnected = false;

function updateFirebaseUI(status, message, isError = false) {
  const badge = document.getElementById("firebaseStatusBadge");
  const label = document.getElementById("firebaseStatusLabel");
  if (label) {
    label.textContent = `Firebase: ${status}`;
  }
  if (badge) {
    badge.className = isError 
      ? "header-status firebase-status fb-error" 
      : (status === "Connected" ? "header-status firebase-status fb-connected" : "header-status firebase-status");
    badge.title = message;
  }
}

async function initFirebase() {
  try {
    // 1. Initialize Firebase App
    const app = initializeApp(firebaseConfig);
    window.firebaseApp = app;

    // 2. Initialize Firebase Analytics if supported in this environment
    const supported = await isSupported().catch(() => false);
    if (supported) {
      const analytics = getAnalytics(app);
      window.firebaseAnalytics = analytics;
      window.firebaseConnected = true;

      // Log initial connected heartbeat event
      logEvent(analytics, "app_connected", {
        app_name: "AttentionAI Study Tracker",
        timestamp: new Date().toISOString(),
        project_id: firebaseConfig.projectId
      });

      updateFirebaseUI("Connected", `Connected to Firebase project: ${firebaseConfig.projectId} (Analytics active)`);
      console.log(`[Firebase] Initialized & Connected successfully to '${firebaseConfig.projectId}'. App ID: ${firebaseConfig.appId}`);
    } else {
      window.firebaseConnected = true;
      updateFirebaseUI("App Active", `Connected to Firebase project: ${firebaseConfig.projectId} (Analytics unsupported in environment)`);
      console.log(`[Firebase] App initialized (${firebaseConfig.projectId}), analytics isSupported=false`);
    }
  } catch (err) {
    console.error("[Firebase] Initialization error:", err);
    updateFirebaseUI("Error", err.message || "Connection failed", true);
  }
}

// Helper to log custom study session events to Firebase Analytics
window.logFirebaseSessionEvent = function(eventName, params = {}) {
  try {
    if (window.firebaseAnalytics) {
      logEvent(window.firebaseAnalytics, eventName, {
        ...params,
        client_timestamp: new Date().toISOString()
      });
      console.log(`[Firebase Analytics] Event logged: ${eventName}`, params);
    }
  } catch (e) {
    console.warn(`[Firebase] Failed to log event '${eventName}':`, e);
  }
};

// Start initialization once DOM is ready
if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", initFirebase);
} else {
  initFirebase();
}
