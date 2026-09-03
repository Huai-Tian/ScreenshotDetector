package detect.screenshot.detection

import android.Manifest
import android.app.Activity
import android.content.Context
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import detect.screenshot.Auxiliary
import detect.screenshot.MainActivity
import detect.screenshot.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.function.Consumer
import kotlin.time.Duration.Companion.milliseconds

/**
 * 全部检测逻辑的宿主：持有各检测器的回调/观察者等运行期状态，
 * 提供各检测项的启动/停止方法(由 [DetectionItems] 的 start/stop 回调调用)。
 *
 * 由 MainActivity 持有单一实例，生命周期与 Activity 一致。
 */
class DetectionFunctions(private val activity: MainActivity) {

    // ---------- 运行期状态 ----------
    private var screenCaptureCallback: Activity.ScreenCaptureCallback? = null
    private var screenRecordingCallback: Consumer<Int>? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var mediaProjectionListener: DisplayManager.DisplayListener? = null
    private var mediaRouter: MediaRouter? = null
    private var mediaRouterCallback: MediaRouter.Callback? = null
    private var mediaLibraryObserver: ContentObserver? = null
    private var pendingMediaLibraryCallback: (() -> Unit)? = null
    private var fileObserver: FileObserver? = null
    private var lastFileObserverTime = 0L
    private var isBehaviorDetectionActive = false
    private var behaviorIssueCallback: ((DetectionItems) -> Unit)? = null
    private var environmentObserver: ContentObserver? = null
    private var accessibilityListener: AccessibilityManager.AccessibilityStateChangeListener? = null
    private var environmentIssueCallback: ((DetectionItems) -> Unit)? = null
    private var screenshotFakerCheckJob: Job? = null
    private var behaviorPollingJob: Job? = null

    // ========== 窗口反射检测(自由小窗/系统级悬浮窗) ==========
    /** 引用计数：FREEFORM_WINDOW 与 SYSTEM_FLOATING_WINDOW 共用同一轮询 */
    private var windowDetectionRefCount = 0
    private var windowPollingJob: Job? = null
    private var windowReflectionDetector: WindowReflectionDetector? = null

    // ========== 可信呈现监听(API 35+，窗口显示完整性信号) ==========
    /**
     * TrustedPresentation 回调。系统(SurfaceFlinger)持续计算本应用窗口
     * 实际被渲染的像素比例，跌出阈值即回调 false：外部悬浮窗/系统浮层
     * 遮挡、手势导航离场动画、半透明化等任何使窗口呈现不完整的因素都
     * 触发。按信号本体语义上报 WINDOW_NOT_FULLY_PRESENTED，不归因为悬浮窗
     * (悬浮窗检测由触摸遮挡与焦点归因两路独立信号承担)。
     */
    private var trustedPresentationConsumer: Consumer<Boolean>? = null

    // ========== 触摸遮挡状态(悬浮窗检测信号) ==========
    private var isTouchObscured = false
    private var isInBackground = false

    // ========== 外接显示器监听(桌面模式信号) ==========
    /**
     * DisplayManager.DisplayListener.onDisplayAdded：外接显示器接入是
     * 桌面窗口模式(Android 16 QPR3 GA)/投屏外显的确定性前置事件。
     * 检出为独立检测项 EXTERNAL_DISPLAY，与投屏检测(投出)语义互补
     * (此为"接入进来")。注册时先查既有显示器(冷启动时已接入也能检出)。
     * (SDK 37.1 已移除 ACTION_DISPLAY_ADDED 广播常量，改用 DisplayListener)
     */
    private var externalDisplayListener: DisplayManager.DisplayListener? = null

    // ---------- 环境检测/行为检测的上报回调(签名统一为携带详情) ----------
    private fun envAdapter(onIssue: (DetectionItems, String?) -> Unit): (DetectionItems) -> Unit =
        { item -> onIssue(item, null) }

    // ---------- 媒体库权限申请 ----------
    private val requestPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingMediaLibraryCallback?.let {
                startMediaLibraryDetection(it)
                pendingMediaLibraryCallback = null
            }
        } else {
            Toast.makeText(activity, activity.getString(R.string.require_permission), Toast.LENGTH_SHORT).show()
            pendingMediaLibraryCallback = null
        }
    }

    // ---------- 拦截触摸事件，检测悬浮窗遮挡 ----------
    fun dispatchTouchEvent(ev: MotionEvent) {
        var obscured = (ev.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val partially =
                (ev.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
            obscured = obscured or partially
        }
        if (obscured != isTouchObscured) {
            isTouchObscured = obscured
            if (obscured && isBehaviorDetectionActive) {
                behaviorIssueCallback?.invoke(DetectionItems.FLOATING_WINDOW)
            }
        }
    }

    // ---------- 截屏检测 ----------
    fun startKeyPressDetection(onDetected: () -> Unit) {
        if (Auxiliary.KeyPressDetectionAvailable) {
            stopKeyPressDetection()
            val callback = Activity.ScreenCaptureCallback {
                onDetected()
            }
            screenCaptureCallback = callback
            activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
        }
    }

    fun stopKeyPressDetection() {
        screenCaptureCallback?.let {
            if (Auxiliary.KeyPressDetectionAvailable) {
                try {
                    activity.unregisterScreenCaptureCallback(it)
                } catch (_: Exception) { /* ignore */
                }
            }
            screenCaptureCallback = null
        }
    }

    // ---------- 录屏检测 ----------
    fun startScreenRecordingDetection(onDetected: () -> Unit) {
        if (Auxiliary.ScreenRecordingDetectionAvailable) {
            stopScreenRecordingDetection()
            val callback = Consumer<Int> { state ->
                // SCREEN_RECORDING_STATE_VISIBLE = 1，NOT_VISIBLE = 0
                Auxiliary.log("ScreenRecording: callback state=$state")
                if (state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE) {
                    onDetected()
                }
            }
            screenRecordingCallback = callback
            val windowManager = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try {
                // 注册返回当前状态(API 契约：初始状态不会自动回调，需自行消费)，
                // 覆盖"录屏进行中启动检测器"的场景
                val initState = windowManager.addScreenRecordingCallback(
                    activity.mainExecutor, callback
                )
                Auxiliary.log("ScreenRecording: registered, initState=$initState")
                callback.accept(initState)
            } catch (e: Exception) {
                // 个别 ROM 可能拦截/抛错，记录以判断注册是否真正成功
                Auxiliary.log("ScreenRecording: register failed: $e")
            }
        } else {
            Auxiliary.log("ScreenRecording: unavailable, sdk=${Build.VERSION.SDK_INT}")
        }
    }

    fun stopScreenRecordingDetection() {
        screenRecordingCallback?.let {
            if (Auxiliary.ScreenRecordingDetectionAvailable) {
                try {
                    val windowManager = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    windowManager.removeScreenRecordingCallback(it)
                    Auxiliary.log("ScreenRecording: unregistered")
                } catch (_: Exception) { /* ignore */
                }
            }
            screenRecordingCallback = null
        }
    }

    // ---------- 公共的 DisplayListener 创建逻辑 ----------
    private fun createDisplayListener(
        dm: DisplayManager,
        onDetected: () -> Unit
    ): DisplayManager.DisplayListener {
        return object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                // 对照日志：三方录屏/投屏会创建虚拟显示器，标记时间点
                Auxiliary.log("DisplayListener: onDisplayAdded displayId=$displayId")
                var display = dm.getDisplay(displayId)
                if (display == null) {
                    display = dm.displays.find { it.displayId == displayId }
                }
                if (display != null) {
                    if (Auxiliary.isNonDefaultDisplay(display)) {
                        onDetected()
                    }
                } else {
                    if (Auxiliary.hasNonDefaultDisplay(dm.displays)) {
                        onDetected()
                    }
                }
            }

            override fun onDisplayRemoved(displayId: Int) { /* 可选 */
            }

            override fun onDisplayChanged(displayId: Int) { /* 可选 */
            }
        }
    }

    // ---------- 投屏/镜像检测 ----------
    fun startMirroringDetection(onDetected: () -> Unit) {
        stopMirroringDetection()
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = createDisplayListener(dm, onDetected)
        displayListener = listener
        dm.registerDisplayListener(listener, null)
        if (Auxiliary.hasNonDefaultDisplay(dm.displays)) {
            onDetected()
        }
    }

    fun stopMirroringDetection() {
        displayListener?.let {
            try {
                (activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it)
            } catch (_: Exception) { /* ignore */
            }
            displayListener = null
        }
    }

    // ---------- MediaProjection 检测 ----------
    fun startMediaProjectionDetection(onDetected: () -> Unit) {
        stopMediaProjectionDetection()
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = createDisplayListener(dm, onDetected)
        mediaProjectionListener = listener
        dm.registerDisplayListener(listener, null)
        if (Auxiliary.hasNonDefaultDisplay(dm.displays)) {
            onDetected()
        }
    }

    fun stopMediaProjectionDetection() {
        mediaProjectionListener?.let {
            try {
                (activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it)
            } catch (_: Exception) { /* ignore */
            }
            mediaProjectionListener = null
        }
    }

    // ---------- MediaRouter 检测 ----------
    fun startMediaRouterDetection(onConnected: () -> Unit) {
        stopMediaRouterDetection()
        mediaRouter = MediaRouter.getInstance(activity)
        val selector = MediaRouteSelector.Builder()
            .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
            .build()
        val callback = object : MediaRouter.Callback() {
            @Deprecated("Deprecated in Java")
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo) {
                if (!route.isDefault) onConnected()
            }

            @Deprecated("Deprecated in Java")
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo) { /* 可选 */
            }

            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                if (!route.isDefault) onConnected()
            }

            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) { /* 可选 */
            }

            override fun onRouteChanged(
                router: MediaRouter,
                route: MediaRouter.RouteInfo
            ) { /* 可选 */
            }
        }
        mediaRouterCallback = callback
        mediaRouter?.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        if (mediaRouter?.routes?.any { !it.isDefault } == true) {
            onConnected()
        }
    }

    fun stopMediaRouterDetection() {
        mediaRouterCallback?.let {
            mediaRouter?.removeCallback(it)
        }
        mediaRouterCallback = null
        mediaRouter = null
    }

    // ---------- 媒体库监听 ----------
    fun startMediaLibraryDetection(onDetected: () -> Unit) {
        if (!Auxiliary.hasStoragePermission(activity)) {
            pendingMediaLibraryCallback = onDetected
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            requestPermissionLauncher.launch(permission)
            return
        }
        stopMediaLibraryDetection()
        val contentResolver = activity.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Auxiliary.checkForScreenshot(contentResolver, onDetected)
            }
        }
        mediaLibraryObserver = observer
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
    }

    fun stopMediaLibraryDetection() {
        mediaLibraryObserver?.let {
            activity.contentResolver.unregisterContentObserver(it)
            mediaLibraryObserver = null
        }
    }

    // ---------- FileObserver 检测 ----------
    fun startFileChangesDetection(onDetected: () -> Unit) {
        stopFileChangesDetection()
        val screenshotsDir = File(
            Environment.getExternalStorageDirectory(),
            "Pictures/Screenshots"
        )
        if (!screenshotsDir.exists()) {
            return
        }
        val observer = object : FileObserver(screenshotsDir, CREATE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        val now = System.currentTimeMillis()
                        if (now - lastFileObserverTime > 2000) {
                            lastFileObserverTime = now
                            onDetected()
                        }
                    }, 300)
                }
            }
        }
        fileObserver = observer
        observer.startWatching()
    }

    fun stopFileChangesDetection() {
        fileObserver?.stopWatching()
        fileObserver = null
    }

    // ---------- 环境安全检测(ADB/开发者选项/无障碍，分别上报) ----------
    fun startEnvironmentDetection(onIssue: (DetectionItems, String?) -> Unit) {
        stopEnvironmentDetection()
        environmentIssueCallback = envAdapter(onIssue)

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                checkEnvironmentState()
            }
        }
        environmentObserver = observer
        activity.contentResolver.registerContentObserver(
            Settings.Global.CONTENT_URI,
            true,
            observer
        )

        val am = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val listener = AccessibilityManager.AccessibilityStateChangeListener {
            checkEnvironmentState()
        }
        accessibilityListener = listener
        am.addAccessibilityStateChangeListener(listener)

        checkEnvironmentState()
    }

    fun stopEnvironmentDetection() {
        environmentObserver?.let {
            activity.contentResolver.unregisterContentObserver(it)
            environmentObserver = null
        }
        accessibilityListener?.let {
            val am = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.removeAccessibilityStateChangeListener(it)
            accessibilityListener = null
        }
        environmentIssueCallback = null
    }

    private fun checkEnvironmentState() {
        Auxiliary.environmentIssues(activity).forEach { issue ->
            environmentIssueCallback?.invoke(issue)
        }
    }

    // ---------- 可疑行为检测(切屏/小窗/画中画/悬浮窗，分别上报) ----------
    fun startBehaviorDetection(onIssue: (DetectionItems, String?) -> Unit) {
        stopBehaviorDetection()
        isBehaviorDetectionActive = true
        behaviorIssueCallback = envAdapter(onIssue)
        checkBehaviorState()
        behaviorPollingJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                delay(Auxiliary.BEHAVIOR_POLL_INTERVAL.milliseconds)
                checkBehaviorState()
            }
        }
    }

    fun stopBehaviorDetection() {
        isBehaviorDetectionActive = false
        behaviorIssueCallback = null
        behaviorPollingJob?.cancel()
        behaviorPollingJob = null
    }

    /**
     * 逐项检查行为异常并上报。
     * 结果是粘性的(由 UI 层持久显示)，因此重复上报同一项是无害的幂等操作；
     * 轮询保证初始状态(如启动时已处于分屏)也能被检出。
     */
    private fun checkBehaviorState() {
        if (!isBehaviorDetectionActive) return
        val callback = behaviorIssueCallback ?: return
        if (isInBackground) {
            callback(DetectionItems.SCREEN_SWITCH)
        }
        if (activity.isInMultiWindowMode) {
            callback(DetectionItems.MULTI_WINDOW)
        }
        // 窗口化形态探测：与 isInMultiWindowMode 是两个独立标志、不同代码
        // 路径——部分 ROM(实测 ColorOS 16)的小窗不置 multiWindow 标志但
        // windowingMode 照常返回 FREEFORM/MULTI_WINDOW，补上该盲区。
        // Configuration.windowConfiguration 为 API 30 公开字段，但 SDK 37.1
        // 起该类移出公开 stub，改用反射读取(运行时类恒存在)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (currentWindowingMode()) {
                WINDOWING_MODE_FREEFORM,
                WINDOWING_MODE_MULTI_WINDOW -> {
                    callback(DetectionItems.MULTI_WINDOW)
                }
                // PINNED 即画中画，isInPictureInPictureMode 已覆盖
                else -> Unit
            }
        }
        if (activity.isInPictureInPictureMode) {
            callback(DetectionItems.PICTURE_IN_PICTURE)
        }
        if (isTouchObscured) {
            callback(DetectionItems.FLOATING_WINDOW)
        }
    }

    /** 由 Activity 的多窗/画中画模式变化回调转发 */
    fun onMultiWindowModeChanged() {
        checkBehaviorState()
    }

    // ---------- 窗口化形态反射读取(见 checkBehaviorState 内注释) ----------

    private companion object {
        /** android.app.WindowConfiguration.WINDOWING_MODE_* 常量值(API 30 公开，SDK 37 起移出公开 stub) */
        private const val WINDOWING_MODE_FREEFORM = 5
        private const val WINDOWING_MODE_MULTI_WINDOW = 6
    }

    /** 反射读取当前窗口化形态，失败返回 0(FULLSCREEN，即不触发任何上报) */
    private fun currentWindowingMode(): Int = runCatching {
        val configField =
            android.content.res.Configuration::class.java.getField("windowConfiguration")
        val windowConfig = configField.get(activity.resources.configuration)
        windowConfig.javaClass.getMethod("getWindowingMode").invoke(windowConfig) as Int
    }.getOrDefault(0)

    fun onPictureInPictureModeChanged() {
        checkBehaviorState()
    }

    // ---------- ScreenshotFaker检测 ----------
    fun startScreenshotFakerDetection(onDetected: () -> Unit) {
        stopScreenshotFakerDetection()
        screenshotFakerCheckJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val present = Auxiliary.isScreenshotFakerPresent(activity)
                if (present) {
                    withContext(Dispatchers.Main) {
                        onDetected()
                    }
                }
                delay(5000.milliseconds)
            }
        }
    }

    fun stopScreenshotFakerDetection() {
        screenshotFakerCheckJob?.cancel()
        screenshotFakerCheckJob = null
    }

    // ---------- 外接显示器检测(桌面模式信号) ----------

    /**
     * 注册 DisplayListener；DisplayManager.getDisplays 显示器数量 > 1
     * (内建屏之外)时立即上报——覆盖冷启动时已接入的场景。
     */
    fun startExternalDisplayDetection(onIssue: (DetectionItems, String?) -> Unit) {
        stopExternalDisplayDetection()
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        if (dm.displays.size > 1) {
            onIssue(DetectionItems.EXTERNAL_DISPLAY, null)
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                // 内建屏的 displayId 通常为 0；防御性再核一次数量，
                // 排除个别 ROM 将内建屏移除/重加的情况
                if (dm.displays.size > 1) {
                    onIssue(DetectionItems.EXTERNAL_DISPLAY, null)
                }
            }

            override fun onDisplayChanged(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        externalDisplayListener = listener
    }

    fun stopExternalDisplayDetection() {
        externalDisplayListener?.let {
            runCatching {
                (activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                    .unregisterDisplayListener(it)
            }
        }
        externalDisplayListener = null
    }

    // ---------- 窗口反射检测(自由小窗/系统级悬浮窗，共用同一轮询) ----------
    /**
     * 启动窗口检测：按轮询间隔轮询 [WindowReflectionDetector]。
     * 检测器基于反射信号(焦点丢失/虚拟显示器/环境探测)，不使用无障碍服务；
     * 触摸遮挡信号由 [dispatchTouchEvent] 补充。
     */
    fun startWindowDetection(onIssue: (DetectionItems, String?) -> Unit) {
        windowDetectionRefCount++
        if (windowDetectionRefCount > 1 || windowPollingJob != null) {
            return
        }
        val detector = WindowReflectionDetector(activity)
        windowReflectionDetector = detector
        windowPollingJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                detector.check(onIssue)
                delay(WindowReflectionDetector.POLL_INTERVAL_MS.milliseconds)
            }
        }
        startTrustedPresentationDetection(onIssue)
    }

    fun stopWindowDetection() {
        if (windowDetectionRefCount > 0) windowDetectionRefCount--
        if (windowDetectionRefCount > 0) return
        teardownWindowDetection()
    }

    private fun teardownWindowDetection() {
        windowPollingJob?.cancel()
        windowPollingJob = null
        windowReflectionDetector = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            stopTrustedPresentationDetection()
        }
    }

    /**
     * 可信呈现监听(API 35+，见字段注释)。窗口需先完成 attach(有 windowToken)
     * 才能注册，故 post 到视图就绪后执行。
     *
     * 排除两类非"显示不完整"的情形：后台离场(isInBackground)、自家弹层
     * 遮挡(下拉菜单/Dialog，见 hasOwnOverlayingWindow——应用自身 UI 的
     * 正常行为)；其余翻转一律按本体语义上报。
     */
    private fun startTrustedPresentationDetection(onIssue: (DetectionItems, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        stopTrustedPresentationDetection()
        val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 阈值：99% 像素可见、不透明、持续 250ms 才算"可信呈现"——
        // 遮挡/移开的状态迁移都会回调，取 false 边沿上报
        val thresholds = android.window.TrustedPresentationThresholds(1.0f, 0.99f, 250)
        val consumer = Consumer<Boolean> { trusted ->
            if (!trusted && !isInBackground &&
                windowReflectionDetector?.hasOwnOverlayingWindow() != true
            ) {
                onIssue(DetectionItems.WINDOW_NOT_FULLY_PRESENTED, null)
            }
        }
        activity.window.decorView.post {
            val token = activity.window.decorView.windowToken ?: return@post
            runCatching {
                wm.registerTrustedPresentationListener(
                    token, thresholds, activity.mainExecutor, consumer
                )
                trustedPresentationConsumer = consumer
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun stopTrustedPresentationDetection() {
        val consumer = trustedPresentationConsumer ?: return
        trustedPresentationConsumer = null
        val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching {
            wm.unregisterTrustedPresentationListener(consumer)
        }
    }

    /** 由 Activity 的 onWindowFocusChanged 转发(窗口焦点探测的精确信号) */
    fun onWindowFocusChanged(hasFocus: Boolean) {
        windowReflectionDetector?.onWindowFocusChanged(hasFocus)
    }

    /** 由 Activity 的 onTopResumedActivityChanged 转发(事件化焦点信号) */
    fun onTopResumedActivityChanged(isTopResumed: Boolean) {
        windowReflectionDetector?.onTopResumedActivityChanged(isTopResumed)
    }

    /** 由 Activity 的 onPause/onResume 转发 */
    fun onActivityPaused() {
        isInBackground = true
        checkBehaviorState()
    }

    fun onActivityResumed() {
        isInBackground = false
    }

    /** 停止全部检测(由 Activity onDestroy 调用) */
    fun stopAll() {
        stopKeyPressDetection()
        stopScreenRecordingDetection()
        stopMirroringDetection()
        stopMediaProjectionDetection()
        stopMediaRouterDetection()
        stopMediaLibraryDetection()
        stopFileChangesDetection()
        stopBehaviorDetection()
        stopEnvironmentDetection()
        stopScreenshotFakerDetection()
        stopExternalDisplayDetection()
        windowDetectionRefCount = 0
        teardownWindowDetection()
    }
}
