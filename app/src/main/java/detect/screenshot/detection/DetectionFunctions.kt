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

    // ========== 触摸遮挡状态(悬浮窗检测信号) ==========
    private var isTouchObscured = false
    private var isInBackground = false

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
        var partially = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            partially = (ev.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
            obscured = obscured or partially
        }
        if (obscured != isTouchObscured) {
            isTouchObscured = obscured
            Auxiliary.log(
                "WindowTouch: obscured state changed to $obscured " +
                        "(action=0x${Integer.toHexString(ev.actionMasked)} partially=$partially)"
            )
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
                if (state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE) {
                    onDetected()
                }
            }
            screenRecordingCallback = callback
            val windowManager = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val initState = windowManager.addScreenRecordingCallback(activity.mainExecutor, callback)
            callback.accept(initState)
        }
    }

    fun stopScreenRecordingDetection() {
        screenRecordingCallback?.let {
            if (Auxiliary.ScreenRecordingDetectionAvailable) {
                try {
                    val windowManager = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    windowManager.removeScreenRecordingCallback(it)
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
    fun startEnvironmentDetection(onIssue: (DetectionItems) -> Unit) {
        stopEnvironmentDetection()
        environmentIssueCallback = onIssue

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
    fun startBehaviorDetection(onIssue: (DetectionItems) -> Unit) {
        stopBehaviorDetection()
        isBehaviorDetectionActive = true
        behaviorIssueCallback = onIssue
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

    // ---------- 窗口反射检测(自由小窗/系统级悬浮窗，共用同一轮询) ----------
    /**
     * 启动窗口检测：每秒轮询一次 [WindowReflectionDetector]。
     * 检测器基于反射信号(焦点丢失/虚拟显示器/环境探测)，不使用无障碍服务；
     * 触摸遮挡信号由 [dispatchTouchEvent] 补充。
     */
    fun startWindowDetection(onIssue: (DetectionItems) -> Unit) {
        windowDetectionRefCount++
        Auxiliary.log(
            "WindowDetection: start requested " +
                    "(refCount=$windowDetectionRefCount, polling=${windowPollingJob != null})"
        )
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
        Auxiliary.log("WindowDetection: stopped, polling cancelled")
    }

    /** 由 Activity 的 onWindowFocusChanged 转发(窗口焦点探测的精确信号) */
    fun onWindowFocusChanged(hasFocus: Boolean) {
        windowReflectionDetector?.onWindowFocusChanged(hasFocus)
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
        windowDetectionRefCount = 0
        teardownWindowDetection()
    }
}
