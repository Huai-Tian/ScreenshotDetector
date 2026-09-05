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
  Detects system key‑press screenshot events via `ScreenCaptureCallback` (Android 14+); on Android 11–13, with this app's Accessibility service enabled, the screenshot key (`KEYCODE_SCREENSHOT`) is recognized via the global key event stream (observed only, never consumed; key-press screenshots are not reported while this app is in the background)

- **Screen recording detection**  
  Detects screen recording activity via `ScreenRecordingCallback`

- **Audio recording detection**  
  Detects device-wide audio recording activity via `AudioManager` (screen recording with a microphone track is also covered; the detail line shows the audio source). AOSP strips the recorder's identity, so the source app is *suspected* via "most recently used app × microphone permission × foreground-service start event" cross-check, with system flow apps (Settings/launcher/permission dialog) dynamically excluded — the suspicion may be inaccurate. With this app's Accessibility service enabled, the real-time window-event stream serves as an additional candidate source, so the suspicion no longer depends on "Usage access". A system-muted microphone is noted as a suffix in the detail line

- **Recording/casting service detection** (requires Notification access)  
  Detects running recording/casting services by crossing two independent facts: an app with an ongoing (foreground-service) notification that also owns a virtual display (`DisplayInfo.ownerPackageName`) or holds persistent projection consent (`project_media`). Android 14+ forces MediaProjection foreground services to keep an ongoing notification, so an active recorder satisfies both. No naming heuristics — the card reports the package names of the cross-matched apps

- **Screen mirroring detection**  
  Detects screen mirroring or casting status via `DisplayManager`

- **Display attribution**  
  Reads display type, flags, and the virtual display creator's package name via `DisplayInfo` (hidden API, reflection) — new displays are attributed to the creating app (recording/casting apps), shown on the detection card. No per-app naming heuristics: all visible virtual displays are reported with attribution, and the user judges from the package name whether it is a recorder. Wired external displays gain an HDMI audio-routing corroboration suffix (media audio is being delivered to the external device)

- **Miracast status detection**  
  Gets the Miracast connection state and the remote display's device name via `WifiDisplayStatus` (hidden API); when the wireless display toggle is on, the card detail lists the available receivers found by scanning (via reflection `getDisplayList`)

- **MediaProjection monitoring**  
  Monitors the running status of the `MediaProjection` service

- **Persistent projection consent detection**  
  Detects apps holding unattended (persistent) projection consent (video/audio) via the hidden AppOps strings `android:project_media` / `android:project_audio` (a capability-surface signal; the card shows the full count and up to 5 granted package names)

- **Media library monitoring**  
  Monitors media library changes via `ContentObserver`: the image store identifies newly added screenshots, and the video store identifies newly added recordings by screen-recording naming features (screenrecord / 屏幕录制, covering AOSP and Chinese ROM conventions); a 15-second lookback query runs at registration, covering events right before the app was opened. The card carries the writer's attribution (`owner_package_name` hidden column; cross-app visibility varies by ROM and degrades gracefully)

- **Device environment security checks**  
  Detects developer options, USB debugging, wireless debugging (hidden key `adb_wifi_enabled`), simulated secondary display (hidden key `overlay_display_devices`), wireless display toggle (hidden key `wifi_display_on`), Accessibility mode (`getEnabledAccessibilityServiceList` enumerates enabled accessibility services cross-app; the card shows the full count and up to 5 package names, excluding this app's own enhancement service), and other risk indicators. The accessibility status refreshes in real time via 200ms polling (covering background toggles and service-set changes; the card appears/updates/removes with the current state)

- **Two-channel presentation: confirmed events vs. suspicious traces**  
  Detection items are semantically classified: **confirmed events** (screenshots, recording, casting, screen switching, mini windows, and other observable facts — "detected = it happened") go to the main issue list; **suspicious traces** (environment toggles, screen-reader channels, unattended consents, forgery-tool traces — capability surfaces and conditions, "detected = an exploitable channel exists", not proof that any capture happened) no longer mix into the main list. They are shown in a dedicated dialog behind an eye icon to the left of the permission button in the top bar — the icon only appears when at least one suspicious finding is present

- **Third-party capability-surface checks** (suspicious traces)  
  Detects third-party apps with notification access enabled (can read all notifications), apps with a screen-capture channel (manifest declaring `FOREGROUND_SERVICE_MEDIA_PROJECTION` — mandatory for targetSdk 34+ recording/casting apps), and apps that can draw overlays (holding the `SYSTEM_ALERT_WINDOW` special grant, judged by AppOps `MODE_ALLOWED`). Cards show the full count and up to 5 package names; capability surface only — it does not mean a capture is happening

- **Screen-reader channel checks**  
  Detects the third-party input method in use (can read all key input), the third-party autofill service (hidden key `autofill_service`, can read all form content), the third-party voice-interaction service, and the third-party assistant app (`RoleManager` query for the default assistant, `QUERY_ROLE_HOLDERS` permission — the assist channel is a legal full-screen screenshot entry). Only third-party apps are reported (preinstalled system services are skipped to avoid noise); the card shows the current service's package name

- **Dock / desktop mode detection**  
  Detects dock / desktop mode via `UiModeManager` (a dock is a strong precursor of desktop windowing / external displays, complementing the external display card)

- **File monitoring**  
  Monitors file changes via `FileObserver` in the screenshot/recording directories (`Pictures/Screenshots`, `Movies/ScreenRecords`, `Movies`); a directory scan runs at registration, covering files written before the app was opened (the recording directory yields an immediate file signal seconds ahead of the media-library scan)

- **Permission status panel**  
  Every permission is opt-in; the app runs normally with none granted — each item merely enables its corresponding detections, and ungranted ones degrade silently with no prompts. A top-bar security-level icon reflects the live state: Warning = any of the three general permissions (Photos and videos / Usage access / App list) not granted; Lock = all three granted but the two enhancement services (this app's Accessibility service / Notification access) not both enabled; Shield = everything ready. Tapping expands the details (500ms polling while open). Tapping an ungranted item: Photos and videos is a runtime permission and pops one combined system dialog for images + video (jumping to app details only after "Don't ask again"), while Usage access / App list / Accessibility / Notification access are special-access grants that jump to their Settings screens; the app-list item uses the same full-enumeration call as the consuming detection path and decides by result size (the ColorOS toggle intercepts full enumeration but not single-package queries)

- **Detection enhancement services**  
  Two system services enhance the detections above; with neither enabled, everything gracefully falls back to the original paths:
  - **Accessibility service** (real-time window attribution): the `TYPE_WINDOW_STATE_CHANGED` event stream feeds audio-recording source suspicion (no longer depends on Usage access); `getWindows()` cross-app window enumeration (impossible for normal apps — the platform exposes no window-enumeration path) upgrades freeform-window attribution from a usage-stats *inference* to a window-list *fact*, and adds occlusion-source attribution to the incomplete-presentation card ("Occluded by: package"); `TYPE_ACCESSIBILITY_OVERLAY` windows are reported as accessibility overlays; the global key event stream recognizes the screenshot key (Android 11–13, observed only, never consumed)
  - **Notification access** (ongoing-notification visibility): feeds the new recording/casting service detection by crossing ongoing notifications with virtual-display ownership and persistent projection consent

- **Accessibility overlay detection** (requires Accessibility service)  
  Detects the presence of `TYPE_ACCESSIBILITY_OVERLAY` windows via the accessibility window snapshot (that window type can only be created by accessibility services — the typical vehicle of screen readers and accessibility-hijacking malware); the card shows the owning package name (this app's own service excluded)

- **Auxiliary detection**  
  Assists in detecting casting and mirroring via `MediaRouter`

- **Suspicious behavior detection**  
  Detects screen switching, split-screen, picture‑in‑picture, and other suspicious operations; ROM mini windows that don't set the `multiWindow` flag are covered by reading `windowingMode` via reflection, with a `WindowMetrics` area-comparison fallback when reflection is unavailable (IME-visible frames are skipped to avoid false positives)

- **Floating window detection**  
  Detects floating windows using touch‑obscured signals (`FLAG_WINDOW_IS_OBSCURED` / `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`), plus "focus taken while self is still the foreground app" (`UsageStatsManager`)

- **Freeform window detection**  
  Detects freeform windows (ROM mini windows / free-form windows) via focus attribution: the app stays RESUMED while focus and the top app belong to another app — a normal app switch would pause the app first. The detection card shows the package name of the app running in the freeform window. With the Accessibility service enabled, attribution upgrades to a fact: the window currently holding focus in the cross-app window list is the covering window (no Usage access needed)

- **Focus loss detection**  
  Detects window focus being persistently taken by other windows while the app is in the foreground

- **Incomplete window presentation detection** (Android 15+)  
  Detects the app's window falling out of the trusted presentation state (the actually rendered pixel fraction dropping below the threshold) via `TrustedPresentationListener` — triggered by external window occlusion, system overlays, gesture-navigation exit animations, etc. The server only fires callbacks on state transitions (a window occluded from its very first frame never transitions, so no callback ever fires); a bootstrap timeout reports "untrusted since launch" when no callback arrives after registration. With the Accessibility service enabled, the card gains an occlusion-source attribution: the highest-layer external window that overlaps this app's top window ("Occluded by: package")

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
  - Operates at the **application layer**, detecting screenshot status via system APIs. It has limited detection capability against system framework-layer behaviors (e.g., LSPosed) and some detection capability against privileged-layer behaviors (e.g., Shizuku/Root).

### Detection Principles

Based on Android public system APIs:

- `ScreenCaptureCallback`: Detects key‑press screenshots (Android 14+); on Android 11–13 the screenshot key is recognized via the accessibility global key event stream (`KEYCODE_SCREENSHOT`, observed only, never consumed; not reported while this app is in the background)
- `ScreenRecordingCallback`: Detects screen recording activity (Android 15+)
- `DisplayManager`: Detects screen mirroring/casting status and virtual displays (external-display detection counts only wired external displays per `DisplayInfo.type`; virtual/Miracast/simulated displays map to their own detection items)
- `MediaProjection`: Detects screen projection service status
- `MediaRouter`: Detects external display routes
- `ContentObserver` + `MediaStore`: Detects newly added screenshots (image store) / recordings (video store, matched by screen-recording naming features); the query Bundle carries `MATCH_PENDING` to include pending rows at write time for an earlier detection, and the projection includes the `owner_package_name` hidden column for writer attribution
- `FileObserver`: Detects file creation/move in the screenshot/recording directories (`Pictures/Screenshots`, `Movies/ScreenRecords`, `Movies` — multiple directories)
- `Settings.Global` / `AccessibilityManager`: Detects ADB, developer options, and accessibility services (`getEnabledAccessibilityServiceList` requires no permission, is cross-app visible, and is a live Binder query; the card shows the enabled services' package names. The accessibility status is kept in sync by 200ms polling in both foreground and background — this item reflects current state and the card is removed once all services are disabled, while other environment items stay sticky so that toggling them off doesn't erase the evidence)
- `Settings.Secure` / `RoleManager`: Screen-reader channel checks — third-party input method (`DEFAULT_INPUT_METHOD`), third-party autofill (`autofill_service` hidden key), third-party voice interaction (`voice_interaction_service`, hard-coded value since SDK 37.1 removed it from the public stubs), third-party default assistant (`getRoleHolders`, invoked via reflection since SDK 37.1 removed it from the public stubs; `QUERY_ROLE_HOLDERS` permission)
- `NotificationManagerCompat`: Third-party apps with notification access enabled (reads the Settings.Secure enabled-list, the same source as the self-check, cross-app enumerable)
- `PackageInfo.requestedPermissions`: Apps with a screen-capture channel (scans the raw manifest-declared array for `FOREGROUND_SERVICE_MEDIA_PROJECTION` instead of checkPermission — checkPermission always denies when the permission is undefined on older systems, while manifest reads are version-stable)
- `AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW`: Apps that can draw overlays (`unsafeCheckOpNoThrow` returning `MODE_ALLOWED` means explicitly enabled in Settings; `Settings.canDrawOverlays` only supports checking one's own app, not others)
- `UiModeManager`: Dock / desktop mode detection (`currentModeType` other than NORMAL means a dock is attached)
- `AudioDeviceInfo`: HDMI audio corroboration (output devices from `getDevices` containing `TYPE_HDMI` means media audio is being delivered to the external device; appended to the external display card)
- `AudioManager.isMicrophoneMute`: Microphone-muted corroboration in the audio recording detail (a recording configuration exists but the system-level mute is in effect, so nothing valid is captured)
- `WindowMetrics` (Android 11+): Windowing-mode fallback when the `windowingMode` reflection is unavailable — current persistently much smaller than maximum (area < 90%, 3 consecutive hits) means split-screen/freeform; IME-visible frames are skipped to avoid false positives
- `AudioManager.getActiveRecordingConfigurations`: Detects device-wide audio recording activity (cross-app; AOSP returns anonymized copies to regular apps — only the audio source, not the origin's identity; the op running-state query `getOpsForPackage` is gated by `GET_APP_OPS_STATS`, so exact attribution has no app-layer path). Source suspicion: among candidate apps ranked by recency within the 5-minute lookback window (`UsageEvents` event stream + `UsageStats` aggregates, covering floating-ball/foreground-service launches; the event stream counts foreground-service starts *and* stops, the aggregates take the larger of `lastTimeUsed`/`lastTimeVisible`), system flow apps are first dynamically excluded (launcher/Settings/permission-dialog host — Settings may itself hold the microphone permission and would otherwise be mis-picked after visiting it), then candidates with a foreground-service start event in the window (`FOREGROUND_SERVICE_START` — background recording must be carried by a foreground service) are preferred, otherwise the first app with microphone capability is reported (skipping mic-less interlopers) — capability = holding the `RECORD_AUDIO` permission with the `record_audio` AppOps raw mode (`unsafeCheckOpRawNoThrow`, unevaluated, distinguishing "foreground-only" from "denied") not in a denied state
- `FLAG_WINDOW_IS_OBSCURED` and `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` (Android 12+): Detects touch‑obscured signals of floating windows
- `TrustedPresentationListener` (Android 15+): Detects the app's window falling out of the trusted presentation state, reported as incomplete window presentation; the server only fires on state transitions, so the "occluded before the app was opened" scenario is covered by a bootstrap timeout (no callback after registration)
- `UsageStatsManager` (Requires "Usage access" permission): Queries the foreground app for window detection attribution — focus taken while self is still the top app indicates a floating window; focus held by another app while the app stays RESUMED (a normal app switch would pause it first) indicates a freeform window, with a freshness filter on `lastTimeUsed` to exclude stale top-app records
- `AccessibilityService` (Requires enabling this app's accessibility service): `TYPE_WINDOW_STATE_CHANGED` events feed audio-recording source suspicion (a candidate source that works without Usage access); the global key event stream recognizes the screenshot key (`KEYCODE_SCREENSHOT`, requires `flagRequestFilterKeyEvents`, observed only, never consumed); `getWindows()` is the only app-layer cross-app window enumeration — the currently focused external window is the freeform-window attribution *fact* (preferred over the usage-stats inference), the highest-layer overlapping external window is the occlusion source of the incomplete-presentation card, and `TYPE_ACCESSIBILITY_OVERLAY` windows are reported as accessibility overlays. Only window package names and window properties are read; window content is never read
- `NotificationListenerService` (Requires "Notification access"): Feeds the recording/casting service detection — an app with an ongoing notification that also owns a virtual display or holds persistent projection consent. Only package names and the ongoing flag are read; notification content is never read

Based on Android hidden system APIs:
- `WindowManagerGlobal.mViews`: Enumerates this process's window list to rule out false positives caused by the app's own dialogs holding focus
- `DisplayManagerGlobal.getDisplayInfo`: Reads `DisplayInfo.type/flags/name` and the virtual display creator's `ownerPackageName` for display attribution. No recording-keyword inference (recording apps vary in how they name/create virtual displays, so keyword matching would miss cases) — it reports the fact "a virtual display exists + its creator's package name"
- `DisplayManager.getWifiDisplayStatus` and the WFD status-changed broadcast: Miracast connection state and the remote display's device name (the server side explicitly requires no permission); `getDisplayList` reads the available receivers found by scanning
- `AppOpsManager.checkOpNoThrow` + hidden op strings `android:project_media` / `android:project_audio`: Queries each app's persistent projection consent state (video/audio; `checkOperation` has no cross-package enforcement server-side; full package enumeration requires `QUERY_ALL_PACKAGES`)
- `Settings.Global` hidden keys: `adb_wifi_enabled` (wireless debugging), `overlay_display_devices` (simulated secondary display), `wifi_display_on` (wireless display toggle)

### Known Limitations (tested on ColorOS 16 / Android 16)

- **Screen recording detection broken**: `ScreenRecordingCallback` registers fine and its state semantics are correct (returns `NOT_VISIBLE` when not recording; the callback registration is visible in `dumpsys window`), but neither system nor third-party recording ever updates the WMS screen-recording state, so the edge-triggered callback never fires — system recording goes through a ROM-private channel bypassing the public MediaProjection path, and the virtual display created by third-party recorders is not counted as a recording source either. Third-party recording is still covered as a fallback by the MediaProjection detection and the media library / file detections
- **Freeform window detection broken**: The ROM applies privacy shielding to apps running in mini windows — no usage stats are written and no windowing flag is set — leaving the app layer unable to attribute a mini window vs. a floating window (focus-loss detection still works). Enabling this app's Accessibility service restores attribution: the covering window is identified from the cross-app window list regardless of usage stats
- **Boundary of display attribution**: a virtual display created through the default `MediaProjection` path without `VIRTUAL_DISPLAY_FLAG_PUBLIC` is private and invisible to other apps (`getDisplayIds` filters by `FLAG_PRIVATE`), so display attribution only covers visible (public) displays — the recording/casting service detection inherits this boundary on its virtual-display leg (the projection-consent leg still catches consent holders)
- **Audio recording source is a suspicion, not attribution**: AOSP returns anonymized copies to regular apps from `getActiveRecordingConfigurations` (the origin's uid/package is stripped; `getClientUid` and friends are gated by the `MODIFY_AUDIO_ROUTING` signature permission, and op running-state queries are gated by `GET_APP_OPS_STATS`), so exact attribution is impossible. The current heuristic dynamically excludes system flow apps (launcher/Settings/permission-dialog host) and prefers candidates with a foreground-service start event in the window, but background recording can still point to the wrong app (e.g. another mic-capable app with a running foreground service used in between); it depends on "Usage access" unless the Accessibility enhancement is enabled
- **Miracast detection depends on the WFD channel**: `WifiDisplayStatus` covers only Miracast/WFD casting; Google Cast and ROM-private casting protocols still rely on MediaRouter/display detections
- **Recording video detection depends on file naming**: the video media-library detection matches screen-recording naming features (screenrecord / 屏幕录制, etc.); recordings saved with unusual names are missed — the virtual-display attribution (in progress) and FileObserver (at write time) provide two fallback legs
- **Foreground limitation of the accessibility key stream**: on Android 11–13 the key-press screenshot detection relies on the accessibility global key stream, and key-press screenshots are not attributed to this app (not reported) while it is in the background; Android 14+ has no such limitation (`ScreenCaptureCallback` only fires when this app's visible windows are captured)
- **Version boundary of the capture-channel check**: judged by the manifest declaration of `FOREGROUND_SERVICE_MEDIA_PROJECTION`, it only covers targetSdk 34+ apps; older-targetSdk apps can also start a MediaProjection foreground service without this permission and are not counted
- **Boundary of the overlay-grant check**: only apps whose AppOps mode is `MODE_ALLOWED` (explicitly enabled in Settings) are counted; apps targeting SDK < 23 hold the overlay by default but their op reads `MODE_DEFAULT`, so they are not counted (rare in the wild today)

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