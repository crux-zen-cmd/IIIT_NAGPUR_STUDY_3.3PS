"use strict";

document.addEventListener("DOMContentLoaded", async () => {
  const statusDot      = document.getElementById("statusDot");
  const statusText     = document.getElementById("statusText");
  const courseName     = document.getElementById("courseName");
  const platformPill   = document.getElementById("platformPill");
  const tabSwitchBadge = document.getElementById("tabSwitchBadge");

  // Read stored tab switch count
  if (chrome && chrome.storage && chrome.storage.local) {
    chrome.storage.local.get(["tabSwitchCount"], (res) => {
      const count = res.tabSwitchCount || 0;
      if (tabSwitchBadge) tabSwitchBadge.textContent = `🔀 ${count} Tab Switch${count === 1 ? '' : 'es'}`;
    });
  }

  const ENDPOINTS = ["http://127.0.0.1:5050", "http://localhost:5050"];
  let connected = false;

  for (const base of ENDPOINTS) {
    try {
      const resp = await fetch(`${base}/api/sessions/active`);
      if (!resp.ok) continue;
      const data = await resp.json();
      connected = true;

      if (data.active && data.session) {
        statusDot.classList.add("active");
        statusText.textContent     = "Active Study Session";
        courseName.textContent     = data.session.course_name;
        platformPill.textContent   = data.session.platform;
        platformPill.style.display = "inline-block";
      } else {
        statusDot.classList.remove("active");
        statusText.textContent     = "Ready · No Active Session";
        courseName.textContent     = "Start a study session to track tabs";
        platformPill.style.display = "none";
      }
      break;
    } catch (_) {}
  }

  if (!connected) {
    statusDot.classList.remove("active");
    statusText.textContent     = "Backend Disconnected";
    courseName.textContent     = "Ensure server is running on :5050";
    platformPill.style.display = "none";
  }
});
