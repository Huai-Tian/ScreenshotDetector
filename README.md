# ScreenshotDetector

[简体中文](README_ZH.md) | English

---

## 📖 Introduction

**ScreenshotDetector** is a tool for detecting screenshot, screen recording, and screen sharing activities on the device.

It operates at the application layer, detecting screen capture events via system APIs, and offers a contrasting research perspective to [ScreenshotFaker](https://github.com/Huai-Tian/ScreenshotFaker).

**Note**: This tool relies on application-layer detection and has limited capability against system framework-level behaviors. It is intended as an auxiliary detection reference.

---

## ✨ Features

- **Key‑press screenshot detection**  
  Detects system key‑press screenshot events via `ScreenCaptureCallback`

- **Screen recording detection**  
  Detects screen recording activity via `ScreenRecordingCallback`

- **Screen mirroring detection**  
  Detects screen mirroring or casting status via `DisplayManager`

- **MediaProjection monitoring**  
  Monitors the running status of the `MediaProjection` service

- **Media library monitoring**  
  Monitors media library changes via `ContentObserver`

- **Device environment security checks**  
  Detects developer options, USB debugging, Accessibility mode, and other risk indicators

- **File monitoring**  
  Monitors file changes via `FileObserver`

- **Auxiliary detection**  
  Assists in detecting casting and mirroring via `MediaRouter`

- **Suspicious behavior detection**  
  Detects screen switching, picture‑in‑picture, small‑window mode, and other suspicious operations

- **ScreenshotFaker feature detection**  
  Detects whether ScreenshotFaker‑related traces exist on the device

- **More features coming soon...**

---

## ⚙️ Technical Details

### Technical Comparison with ScreenshotFaker

- **ScreenshotFaker**:
    - LSPosed mode: Operates at the **system framework layer**, hooking system APIs to intercept and forge screenshot events
    - Shizuku/Root mode: Operates at the **privileged layer**, leveraging system API calls to replace screenshot content and bypass detection

- **ScreenshotDetector**:
    - Operates at the **application layer**, detecting screenshot status via public system APIs. It has limited detection capability against system framework-layer behaviors (e.g., LSPosed) and some detection capability against privileged-layer behaviors (e.g., Shizuku/Root).

### Detection Principles

This tool is implemented based on Android public system APIs:

- `ScreenCaptureCallback`: Detects key‑press screenshots (Android 10+)
- `ScreenRecordingCallback`: Detects screen recording activity (Android 15+)
- `DisplayManager`: Detects screen mirroring/casting status
- `MediaProjection`: Detects screen projection service status

---

## 🚫 Non‑Commercial Statement

This project is initiated by the developer out of personal interest and for technical research purposes, and is **non-commercial** in nature:

- **Permanently Free**:  
  This project is completely free, with **no paid features, memberships, subscriptions, or in-app purchases**. All features are fully accessible to all users.

- **No Sponsorship Channels**:  
  The author has **never opened any sponsorship channels**, nor does the author **accept any financial donations** — to maintain the project's neutrality and purity.

- **Non-Profit Purpose**:  
  This project involves no commercial operations, and the author derives no direct or indirect financial benefit from it.

- **Resale Prohibited**:  
  Resale, redistribution for profit, or commercial use of this project is strictly prohibited. Please obtain it only from this repository (GitHub) or other officially designated channels. The developer assumes no responsibility for any issues arising from unofficial sources.

- **Research-Oriented**:  
  This project is consistently positioned for **security research, privacy protection, and software testing** — providing a research tool for the community, not a commercial product. Any commercial use of this project is the user's own initiative and is unrelated to this project.

---

## ⚖️ Disclaimer

- **Purpose Limitation**:  
  This project is intended for **privacy protection, security research, software testing, and educational purposes** only.  
  Do not use this project for any illegal purposes (including but not limited to exam cheating and data falsification).

- **No Warranty**:  
  This software is provided under the terms of its license, **without any express or implied warranties**, including but not limited to the warranties of merchantability, fitness for a particular purpose, and non-infringement.

- **Limitation of Liability**:  
  To the fullest extent permitted by applicable law, **in no event shall the author or contributors be liable** for any direct, indirect, incidental, special, or consequential damages arising out of or in connection with the use or inability to use this software, even if advised of the possibility of such damages.

- **User Responsibility**:  
  Users assume all legal responsibilities arising from the use of this project.

- **Final Interpretation**:  
  The final interpretation of this disclaimer belongs to the author of this project.

---

## 💬 Contact

- QQ: https://qm.qq.com/q/j2NM49cd8c
- Telegram: https://t.me/ScreenshotFaker

You are welcome to submit issues, suggestions, or bug reports via GitHub Issues.

---

## ⭐ Support the Project

If you find this project helpful, or if you recognize its value in technical research, consider giving it a ⭐ on GitHub.

Your support helps more people discover this project, and also lets the author feel the significance of continued maintenance.

Thank you for your recognition.