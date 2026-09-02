package detect.screenshot

import android.Manifest
import android.content.SharedPreferences
import android.content.res.Configuration
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import detect.screenshot.pages.AgreementCompose
import detect.screenshot.pages.HomeCompose
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

class MainActivity : ComponentActivity() {
    private var screenCaptureCallback: ScreenCaptureCallback? = null
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
    private lateinit var sharedPreferences: SharedPreferences
    private var showHome by mutableStateOf(false)

    // ========== 异常检测结果(粘性)：一旦检出持续显示，只能通过重置清除 ==========
    val detectedIssues = mutableStateListOf<DetectionItems>()

    // ========== 触摸遮挡状态(悬浮窗检测信号) ==========
    private var isTouchObscured = false
    private var isInBackground = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingMediaLibraryCallback?.let {
                startMediaLibraryDetection(it)
                pendingMediaLibraryCallback = null
            }
        } else {
            Toast.makeText(this, getString(R.string.require_permission), Toast.LENGTH_SHORT).show()
            pendingMediaLibraryCallback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        showHome = sharedPreferences.getBoolean("has_agreed", false)
        setContent {
            if (showHome) {
                HomeCompose(
                    activity = this,
                    issues = detectedIssues
                )
            } else {
                AgreementCompose(
                    onAgree = {
                        sharedPreferences.edit { putBoolean("has_agreed", true) }
                        showHome = true
                    },
                    onDisagree = {
                        finishAffinity()
                    }
                )
            }
        }
    }

    // ========== 拦截触摸事件，检测悬浮窗遮挡 ==========
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let {
            var obscured = (it.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                obscured = obscured or ((it.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0)
            }
            if (obscured != isTouchObscured) {
                isTouchObscured = obscured
                if (obscured && isBehaviorDetectionActive) {
                    behaviorIssueCallback?.invoke(DetectionItems.FLOATING_WINDOW)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---------- 截屏检测 ----------
    internal fun startKeyPressDetection(onDetected: () -> Unit) {
        if (Auxiliary.KeyPressDetectionAvailable) {
            stopKeyPressDetection()
            val callback = ScreenCaptureCallback {
                onDetected()
            }
            screenCaptureCallback = callback
            registerScreenCaptureCallback(mainExecutor, callback)
        }
    }

    internal fun stopKeyPressDetection() {
        screenCaptureCallback?.let {
            if (Auxiliary.KeyPressDetectionAvailable) {
                try {
                    unregisterScreenCaptureCallback(it)
                } catch (_: Exception) { /* ignore */
                }
            }
            screenCaptureCallback = null
        }
    }

    // ---------- 录屏检测 ----------
    internal fun startScreenRecordingDetection(onDetected: () -> Unit) {
        if (Auxiliary.ScreenRecordingDetectionAvailable) {
            stopScreenRecordingDetection()
            val callback = Consumer<Int> { state ->
                if (state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE) {
                    onDetected()
                }
            }
            screenRecordingCallback = callback
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val initState = windowManager.addScreenRecordingCallback(mainExecutor, callback)
            callback.accept(initState)
        }
    }

    internal fun stopScreenRecordingDetection() {
        screenRecordingCallback?.let {
            if (Auxiliary.ScreenRecordingDetectionAvailable) {
                try {
                    val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
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
    internal fun startMirroringDetection(onDetected: () -> Unit) {
        stopMirroringDetection()
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val listener = createDisplayListener(dm, onDetected)
        displayListener = listener
        dm.registerDisplayListener(listener, null)
        if (Auxiliary.hasNonDefaultDisplay(dm.displays)) {
            onDetected()
        }
    }

    internal fun stopMirroringDetection() {
        displayListener?.let {
            try {
                (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it)
            } catch (_: Exception) { /* ignore */
            }
            displayListener = null
        }
    }

    // ---------- MediaProjection 检测 ----------
    internal fun startMediaProjectionDetection(onDetected: () -> Unit) {
        stopMediaProjectionDetection()
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val listener = createDisplayListener(dm, onDetected)
        mediaProjectionListener = listener
        dm.registerDisplayListener(listener, null)
        if (Auxiliary.hasNonDefaultDisplay(dm.displays)) {
            onDetected()
        }
    }

    internal fun stopMediaProjectionDetection() {
        mediaProjectionListener?.let {
            try {
                (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it)
            } catch (_: Exception) { /* ignore */
            }
            mediaProjectionListener = null
        }
    }

    // ---------- MediaRouter 检测 ----------
    internal fun startMediaRouterDetection(onConnected: () -> Unit) {
        stopMediaRouterDetection()
        mediaRouter = MediaRouter.getInstance(this)
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

    internal fun stopMediaRouterDetection() {
        mediaRouterCallback?.let {
            mediaRouter?.removeCallback(it)
        }
        mediaRouterCallback = null
        mediaRouter = null
    }

    // ---------- 媒体库监听 ----------
    internal fun startMediaLibraryDetection(onDetected: () -> Unit) {
        if (!Auxiliary.hasStoragePermission(this)) {
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
        val contentResolver = contentResolver
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

    internal fun stopMediaLibraryDetection() {
        mediaLibraryObserver?.let {
            contentResolver.unregisterContentObserver(it)
            mediaLibraryObserver = null
        }
    }

    // ---------- FileObserver 检测 ----------
    internal fun startFileChangesDetection(onDetected: () -> Unit) {
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

    internal fun stopFileChangesDetection() {
        fileObserver?.stopWatching()
        fileObserver = null
    }

    // ---------- 环境安全检测(ADB/开发者选项/无障碍，分别上报) ----------
    internal fun startEnvironmentDetection(onIssue: (DetectionItems) -> Unit) {
        stopEnvironmentDetection()
        environmentIssueCallback = onIssue

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                checkEnvironmentState()
            }
        }
        environmentObserver = observer
        contentResolver.registerContentObserver(
            Settings.Global.CONTENT_URI,
            true,
            observer
        )

        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val listener = AccessibilityManager.AccessibilityStateChangeListener {
            checkEnvironmentState()
        }
        accessibilityListener = listener
        am.addAccessibilityStateChangeListener(listener)

        checkEnvironmentState()
    }

    internal fun stopEnvironmentDetection() {
        environmentObserver?.let {
            contentResolver.unregisterContentObserver(it)
            environmentObserver = null
        }
        accessibilityListener?.let {
            val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.removeAccessibilityStateChangeListener(it)
            accessibilityListener = null
        }
        environmentIssueCallback = null
    }

    private fun checkEnvironmentState() {
        Auxiliary.environmentIssues(this).forEach { issue ->
            environmentIssueCallback?.invoke(issue)
        }
    }

    // ---------- 可疑行为检测(切屏/小窗/画中画/悬浮窗，分别上报) ----------
    internal fun startBehaviorDetection(onIssue: (DetectionItems) -> Unit) {
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

    internal fun stopBehaviorDetection() {
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
        if (isInMultiWindowMode) {
            callback(DetectionItems.MULTI_WINDOW)
        }
        if (isInPictureInPictureMode) {
            callback(DetectionItems.PICTURE_IN_PICTURE)
        }
        if (isTouchObscured) {
            callback(DetectionItems.FLOATING_WINDOW)
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        checkBehaviorState()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        checkBehaviorState()
    }

    // ---------- ScreenshotFaker检测 ----------
    internal fun startScreenshotFakerDetection(onDetected: () -> Unit) {
        stopScreenshotFakerDetection()
        screenshotFakerCheckJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val present = Auxiliary.isScreenshotFakerPresent(this@MainActivity)
                if (present) {
                    withContext(Dispatchers.Main) {
                        onDetected()
                    }
                }
                delay(5000.milliseconds)
            }
        }
    }

    internal fun stopScreenshotFakerDetection() {
        screenshotFakerCheckJob?.cancel()
        screenshotFakerCheckJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
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
    }

    override fun onPause() {
        super.onPause()
        isInBackground = true
        checkBehaviorState()
    }

    override fun onResume() {
        super.onResume()
        isInBackground = false
    }
}