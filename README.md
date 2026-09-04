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

- **Audio recording detection**  
  Detects device-wide audio recording activity via `AudioManager` (screen recording with a microphone track is also covered; the detail line shows the audio source). AOSP strips the recorder's identity, so the source app is *suspected* via "most recently used app × microphone permission × foreground-service start event" cross-check, with system flow apps (Settings/launcher/permission dialog) dynamically excluded — the suspicion may be inaccurate

- **Screen mirroring detection**  
  Detects screen mirroring or casting status via `DisplayManager`

- **Display attribution**  
  Reads display type, flags, and the virtual display creator's package name via `DisplayInfo` (hidden API, reflection) — new displays are attributed to the creating app (recording/casting apps), shown on the detection card. No per-app naming heuristics: all visible virtual displays are reported with attribution, and the user judges from the package name whether it is a recorder

- **Miracast status detection**  
  Gets the Miracast connection state and the remote display's device name via `WifiDisplayStatus` (hidden API)

- **MediaProjection monitoring**  
  Monitors the running status of the `MediaProjection` service

- **Persistent projection consent detection**  
  Detects apps holding unattended (persistent) projection consent via the hidden AppOps string `android:project_media` (a capability-surface signal; the card shows the full count and up to 5 granted package names)

- **Media library monitoring**  
  Monitors media library changes via `ContentObserver` to identify newly added screenshots; a 15-second lookback query runs at registration, covering screenshots taken right before the app was opened

- **Device environment security checks**  
  Detects developer options, USB debugging, wireless debugging (hidden key `adb_wifi_enabled`), simulated secondary display (hidden key `overlay_display_devices`), wireless display toggle (hidden key `wifi_display_on`), Accessibility mode (`getEnabledAccessibilityServiceList` enumerates enabled accessibility services cross-app; the card shows the full count and up to 5 package names), and other risk indicators. The accessibility status refreshes in real time via 200ms polling (covering background toggles and service-set changes; the card appears/updates/removes with the current state)

- **File monitoring**  
  Monitors file changes via `FileObserver` in the screenshots directory; a directory scan runs at registration, covering screenshot files written before the app was opened

- **Auxiliary detection**  
  Assists in detecting casting and mirroring via `MediaRouter`

- **Suspicious behavior detection**  
  Detects screen switching, split-screen, picture‑in‑picture, and other suspicious operations

- **Floating window detection**  
  Detects floating windows using touch‑obscured signals (`FLAG_WINDOW_IS_OBSCURED` / `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`), plus "focus taken while self is still the foreground app" (`UsageStatsManager`)

- **Freeform window detection**  
  Detects freeform windows (ROM mini windows / free-form windows) via focus attribution: the app stays RESUMED while focus and the top app belong to another app — a normal app switch would pause the app first. The detection card shows the package name of the app running in the freeform window

- **Focus loss detection**  
  Detects window focus being persistently taken by other windows while the app is in the foreground

- **Incomplete window presentation detection** (Android 15+)  
  Detects the app's window falling out of the trusted presentation state (the actually rendered pixel fraction dropping below the threshold) via `TrustedPresentationListener` — triggered by external window occlusion, system overlays, gesture-navigation exit animations, etc. Reported by the signal's own semantics without attributing a specific cause. The server only fires callbacks on state transitions (a window occluded from its very first frame never transitions, so no callback ever fires); a bootstrap timeout reports "untrusted since launch" when no callback arrives after registration

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

Based on Android public system APIs:

- `ScreenCaptureCallback`: Detects key‑press screenshots (Android 14+)
- `ScreenRecordingCallback`: Detects screen recording activity (Android 15+)
- `DisplayManager`: Detects screen mirroring/casting status and virtual displays (external-display detection counts only wired external displays per `DisplayInfo.type`; virtual/Miracast/simulated displays map to their own detection items)
- `MediaProjection`: Detects screen projection service status
- `MediaRouter`: Detects external display routes
- `ContentObserver` + `MediaStore`: Detects newly added screenshots in the media library
- `FileObserver`: Detects file creation/move in the screenshots directory
- `Settings.Global` / `AccessibilityManager`: Detects ADB, developer options, and accessibility services (`getEnabledAccessibilityServiceList` requires no permission, is cross-app visible, and is a live Binder query; the card shows the enabled services' package names. The accessibility status is kept in sync by 200ms polling in both foreground and background — this item reflects current state and the card is removed once all services are disabled, while other environment items stay sticky so that toggling them off doesn't erase the evidence)
- `AudioManager.getActiveRecordingConfigurations`: Detects device-wide audio recording activity (cross-app; AOSP returns anonymized copies to regular apps — only the audio source, not the origin's identity; the op running-state query `getOpsForPackage` is gated by `GET_APP_OPS_STATS`, so exact attribution has no app-layer path). Source suspicion: among candidate apps ranked by recency within the 5-minute lookback window (`UsageEvents` event stream + `UsageStats` aggregates, covering floating-ball/foreground-service launches), system flow apps are first dynamically excluded (launcher/Settings/permission-dialog host — Settings may itself hold the microphone permission and would otherwise be mis-picked after visiting it), then candidates with a foreground-service start event in the window (`FOREGROUND_SERVICE_START` — background recording must be carried by a foreground service) are preferred, otherwise the first app with microphone capability is reported (skipping mic-less interlopers) — capability = holding the `RECORD_AUDIO` permission with the `record_audio` AppOps raw mode (`unsafeCheckOpRawNoThrow`, unevaluated, distinguishing "foreground-only" from "denied") not in a denied state
- `FLAG_WINDOW_IS_OBSCURED` and `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` (Android 12+): Detects touch‑obscured signals of floating windows
- `TrustedPresentationListener` (Android 15+): Detects the app's window falling out of the trusted presentation state, reported as incomplete window presentation; the server only fires on state transitions, so the "occluded before the app was opened" scenario is covered by a bootstrap timeout (no callback after registration)
- `UsageStatsManager` (requires "Usage access" permission): Queries the foreground app for window detection attribution — focus taken while self is still the top app indicates a floating window; focus held by another app while the app stays RESUMED (a normal app switch would pause it first) indicates a freeform window, with a freshness filter on `lastTimeUsed` to exclude stale top-app records

Based on Android hidden system APIs (invoked via reflection after exempting non‑SDK interface restrictions with [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)):
- `WindowManagerGlobal.mViews`: Enumerates this process's window list to rule out false positives caused by the app's own dialogs holding focus
- `DisplayManagerGlobal.getDisplayInfo`: Reads `DisplayInfo.type/flags/name` and the virtual display creator's `ownerPackageName` for display attribution. No recording-keyword inference (recording apps vary in how they name/create virtual displays, so keyword matching would miss cases) — it reports the fact "a virtual display exists + its creator's package name"
- `DisplayManager.getWifiDisplayStatus` and the WFD status-changed broadcast: Miracast connection state and the remote display's device name (the server side explicitly requires no permission)
- `AppOpsManager.checkOpNoThrow` + hidden op string `android:project_media`: Queries each app's persistent projection consent state (`checkOperation` has no cross-package enforcement server-side; full package enumeration requires `QUERY_ALL_PACKAGES`)
- `Settings.Global` hidden keys: `adb_wifi_enabled` (wireless debugging), `overlay_display_devices` (simulated secondary display), `wifi_display_on` (wireless display toggle)

### Known Limitations (tested on ColorOS 16 / Android 16)

- **Screen recording detection broken**: `ScreenRecordingCallback` registers fine and its state semantics are correct (returns `NOT_VISIBLE` when not recording; the callback registration is visible in `dumpsys window`), but neither system nor third-party recording ever updates the WMS screen-recording state, so the edge-triggered callback never fires — system recording goes through a ROM-private channel bypassing the public MediaProjection path, and the virtual display created by third-party recorders is not counted as a recording source either. Third-party recording is still covered as a fallback by the MediaProjection detection and the media library / file detections
- **Freeform window detection broken**: The ROM applies privacy shielding to apps running in mini windows — no usage stats are written and no windowing flag is set — leaving the app layer unable to attribute a mini window vs. a floating window (focus-loss detection still works)
- **Boundary of display attribution**: a virtual display created through the default `MediaProjection` path without `VIRTUAL_DISPLAY_FLAG_PUBLIC` is private and invisible to other apps (`getDisplayIds` filters by `FLAG_PRIVATE`), so display attribution only covers visible (public) displays
- **Audio recording source is a suspicion, not attribution**: AOSP returns anonymized copies to regular apps from `getActiveRecordingConfigurations` (the origin's uid/package is stripped; `getClientUid` and friends are gated by the `MODIFY_AUDIO_ROUTING` signature permission, and op running-state queries are gated by `GET_APP_OPS_STATS`), so exact attribution is impossible. The current heuristic dynamically excludes system flow apps (launcher/Settings/permission-dialog host) and prefers candidates with a foreground-service start event in the window, but background recording can still point to the wrong app (e.g. another mic-capable app with a running foreground service used in between), and it depends on "Usage access"
- **Miracast detection depends on the WFD channel**: `WifiDisplayStatus` covers only Miracast/WFD casting; Google Cast and ROM-private casting protocols still rely on MediaRouter/display detections

---

## 🚫 Non‑Commercial Statement

This project is initiated by the developer out of personal interest and for technical research purposes, and is **non-commercial** in nature:

- **Permanently Free**:  
  This project is completely free, with **no paid features, memberships, subscriptions, or in-app purchases**. All features are fully accessible to all users.

- **No Sponsorship Channels**:  
  The author has **never opened any sponsorship channels**, nor does the author **accept any financial donations** — to maintain the project's neutrality and purity.

- **Non-Profit Purpose**:  
  This project involves no commercial operations, and the author derives no direct or indirect financial benefit from it.

- **Research-Oriented**:  
  This project is consistently positioned for **security research, software testing, and educational purposes** — providing a research tool for the community, not a commercial product. Any commercial use of this project is the user's own initiative and is unrelated to this project.

- **Resale Prohibited**:  
  Resale, redistribution for profit, or commercial use of this project is strictly prohibited. Please obtain it only from this repository (GitHub) or other officially designated channels. The developer assumes no responsibility for any issues arising from unofficial sources.

---

## ⚖️ Disclaimer

- **Purpose Limitation**:  
  This project is intended for **security research, software testing, and educational purposes** only.  
  Do not use this project for any illegal purposes.

- **Consequences Warning**:  
  The detection results provided by this software are **for security research reference only and should not be relied upon as absolute security evidence**. You should assess the risks before using it. The developer and contributors **are not responsible for any loss or consequences arising from reliance on the detection results**.

- **No Warranty**:  
  This software is provided under the terms of its license, **without any express or implied warranties**, including but not limited to the warranties of merchantability, fitness for a particular purpose, and non-infringement.

- **Compatibility Disclaimer**:  
  This software **does not guarantee full compatibility with all OS versions, device models, or third-party applications**. The developer assumes no responsibility for functional issues or losses caused by system differences, application updates, or other uncontrollable factors.

- **Limitation of Liability**:  
  To the fullest extent permitted by applicable law, **in no event shall the author or contributors be liable** for any direct, indirect, incidental, special, or consequential damages arising out of or in connection with the use or inability to use this software, even if advised of the possibility of such damages.

- **User Responsibility**:  
  Users assume all legal responsibilities arising from the use of this project.

- **Final Interpretation**:  
  The final interpretation of this disclaimer belongs to the author of this project.

---

## 🙏 Acknowledgements

- LSPosed (HiddenApiBypass)

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