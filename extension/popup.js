"use strict";

document.addEventListener("DOMContentLoaded", async () => {
  const statusDot    = document.getElementById("statusDot");
  const statusText   = document.getElementById("statusText");
  const courseName   = document.getElementById("courseName");
  const platformPill = document.getElementById("platformPill");

  try {
    const resp = await fetch("http://localhost:5050/api/sessions/active");
    if (!resp.ok) throw new Error();
    const data = await resp.json();

    if (data.active && data.session) {
      statusDot.classList.add("active");
      statusText.textContent     = "Active Session";
      courseName.textContent     = data.session.course_name;
      platformPill.textContent   = data.session.platform;
      platformPill.style.display = "inline-block";
    } else {
      statusDot.classList.remove("active");
      statusText.textContent     = "No Active Session";
      courseName.textContent     = "Start a session in dashboard";
      platformPill.style.display = "none";
    }
  } catch (_) {
    statusDot.classList.remove("active");
    statusText.textContent     = "Backend Disconnected";
    courseName.textContent     = "Ensure server is running on :5050";
    platformPill.style.display = "none";
  }
});
