# 🧩 AttentionAI Study Tracker — Chrome Extension

A lightweight Manifest V3 Chrome Extension that tracks tab switching, window focus, and learning session activity on Coursera and other study platforms.

---

## 🚀 How to Install (Developer Mode)

1. Open **Google Chrome**, **Brave**, or **Microsoft Edge**.
2. Navigate to:
   ```
   chrome://extensions/
   ```
   *(or `edge://extensions/` in Edge)*
3. Enable **Developer mode** toggle in the top-right corner.
4. Click **"Load unpacked"** in the top-left corner.
5. Select the `extension/` folder in this repository:
   ```
   e:\IIIT NAG\extension
   ```
6. The extension is now loaded! You can pin it to your browser toolbar.

---

## 🎯 What It Does

1. **Tab-Switch Detection**:
   When an active study session is running (e.g. studying on Coursera), switching to another tab (e.g., YouTube, Reddit, Social Media) logs a `TAB_SWITCH` event.
2. **Distraction Duration**:
   When you return to Coursera or the study dashboard, the extension calculates the exact duration away and logs `FOCUS_REGAINED` with the duration in seconds.
3. **Window Focus / App Switching**:
   Detects when the browser window loses focus (e.g. switching to another desktop application) and logs `PAGE_HIDDEN` / `PAGE_VISIBLE`.
4. **Privacy-First**:
   Never logs full URLs, search queries, or browsing histories. Only records top-level domain names (e.g., `coursera.org`, `youtube.com`) strictly needed to measure distraction.
