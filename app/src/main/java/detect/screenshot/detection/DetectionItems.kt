package detect.screenshot.detection

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import detect.screenshot.R

/**
 * 检测条目：一条记录 = 一张异常卡片 + 一份检测逻辑。
 *
 * 每个条目携带：
 * - [start] / [stop]：检测的启动/停止回调(以 DetectionFunctions 为接收者)
 *
 * 所有检测项默认全部开启，调用方(HomeCompose)遍历 [entries] 即可
 * 完成检测启停，无需逐项配置。
 *
 * 检出结果持久显示(粘性)，只能通过"重置检测结果"按钮清除(例外：无障碍项
 * 为实时状态，随当前无障碍开关/服务集合同步，服务全部停用后卡片自动移除，
 * 见 startEnvironmentDetection)。
 * onIssue 回调第二参数为可选详情(如小窗包名)，会显示在卡片标签下方。
 */
@Immutable
enum class DetectionItems(
    @StringRes val labelRes: Int,
    val start: DetectionFunctions.(onIssue: (DetectionItems, String?) -> Unit) -> Unit = { },
    val stop: DetectionFunctions.() -> Unit = { }
) {
    // ---------- 截屏/录屏/投屏 ----------
    KEY_PRESS_SCREENSHOT(
        R.string.key_press_screenshot,
        start = { onIssue -> startKeyPressDetection { onIssue(KEY_PRESS_SCREENSHOT, null) } },
        stop = { stopKeyPressDetection() }
    ),
    SCREEN_RECORDING(
        R.string.screen_recording,
        start = { onIssue -> startScreenRecordingDetection { onIssue(SCREEN_RECORDING, null) } },
        stop = { stopScreenRecordingDetection() }
    ),
    AUDIO_RECORDING(
        R.string.audio_recording_active,
        start = { onIssue -> startAudioRecordingDetection(onIssue) },
        stop = { stopAudioRecordingDetection() }
    ),
    SCREEN_MIRRORING(
        R.string.screen_mirroring,
        start = { onIssue -> startMirroringDetection(onIssue) },
        stop = { stopMirroringDetection() }
    ),
    EXTERNAL_DISPLAY(
        R.string.external_display,
        start = { onIssue -> startExternalDisplayDetection(onIssue) },
        stop = { stopExternalDisplayDetection() }
    ),
    MEDIA_PROJECTION(
        R.string.MediaProjection_state,
        start = { onIssue -> startMediaProjectionDetection(onIssue) },
        stop = { stopMediaProjectionDetection() }
    ),
    MEDIA_PROJECTION_CONSENT(
        R.string.projection_consent,
        start = { onIssue -> startProjectionConsentDetection(onIssue) },
        stop = { stopProjectionConsentDetection() }
    ),
    RECORDING_SERVICE(
        R.string.recording_service_active,
        start = { onIssue -> startRecordingServiceDetection(onIssue) },
        stop = { stopRecordingServiceDetection() }
    ),
    MEDIA_LIBRARY(
        R.string.media_library_changed,
        start = { onIssue -> startMediaLibraryDetection { owner ->
            onIssue(MEDIA_LIBRARY, owner?.let { describeMediaOwner(it) })
        } },
        stop = { stopMediaLibraryDetection() }
    ),
    VIDEO_MEDIA_LIBRARY(
        R.string.video_media_library,
        start = { onIssue -> startVideoMediaLibraryDetection { owner ->
            onIssue(VIDEO_MEDIA_LIBRARY, owner?.let { describeMediaOwner(it) })
        } },
        stop = { stopVideoMediaLibraryDetection() }
    ),

    // ---------- 环境风险 ----------
    ADB_ENABLED(
        R.string.adb_enabled,
        start = { onIssue -> startEnvironmentDetection(onIssue) },
        stop = { stopEnvironmentDetection() }
    ),
    ADB_WIFI(R.string.adb_wifi),
    DEVELOPER_OPTIONS(R.string.developer_options),
    OVERLAY_DISPLAY(R.string.overlay_display_enabled),
    ACCESSIBILITY_SERVICE(R.string.accessibility_service),
    WIRELESS_DISPLAY_ON(R.string.wireless_display_enabled),
    INPUT_METHOD(R.string.third_party_input_method),
    AUTOFILL_SERVICE(R.string.third_party_autofill),
    VOICE_INTERACTION(R.string.third_party_voice_interaction),
    ASSISTANT_APP(R.string.third_party_assistant),
    DOCK_CONNECTED(R.string.dock_connected),

    // ---------- 媒体 ----------
    FILE_CHANGES(
        R.string.gallery_file_changed,
        start = { onIssue -> startFileChangesDetection { onIssue(FILE_CHANGES, null) } },
        stop = { stopFileChangesDetection() }
    ),
    MEDIA_ROUTER(
        R.string.MediaRouter_state,
        start = { onIssue -> startMediaRouterDetection { onIssue(MEDIA_ROUTER, null) } },
        stop = { stopMediaRouterDetection() }
    ),

    // ---------- 可疑行为 ----------
    SCREEN_SWITCH(
        R.string.screen_switch,
        start = { onIssue -> startBehaviorDetection { item, _ -> onIssue(item, null) } },
        stop = { stopBehaviorDetection() }
    ),
    MULTI_WINDOW(R.string.multi_window),
    PICTURE_IN_PICTURE(R.string.picture_in_picture),
    FLOATING_WINDOW(
        R.string.floating_window,
        start = { onIssue -> startWindowDetection(onIssue) },
        stop = { stopWindowDetection() }
    ),
    FREEFORM_WINDOW(
        R.string.freeform_window,
        start = { onIssue -> startWindowDetection(onIssue) },
        stop = { stopWindowDetection() }
    ),
    FOCUS_LOSS(
        R.string.focus_taken,
        start = { onIssue -> startWindowDetection(onIssue) },
        stop = { stopWindowDetection() }
    ),
    WINDOW_NOT_FULLY_PRESENTED(
        R.string.window_not_fully_presented,
        start = { onIssue -> startWindowDetection(onIssue) },
        stop = { stopWindowDetection() }
    ),
    ACCESSIBILITY_OVERLAY(
        R.string.accessibility_overlay,
        start = { onIssue -> startWindowDetection(onIssue) },
        stop = { stopWindowDetection() }
    ),

    // ---------- ScreenshotFaker ----------
    SCREENSHOT_FAKER(
        R.string.ScreenshotFaker,
        start = { onIssue -> startScreenshotFakerDetection { onIssue(SCREENSHOT_FAKER, null) } },
        stop = { stopScreenshotFakerDetection() }
    );
}