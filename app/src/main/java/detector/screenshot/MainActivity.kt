package detector.screenshot

import android.Manifest
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
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import detector.screenshot.pages.HomeCompose
import java.io.File
import java.util.function.Consumer

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

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingMediaLibraryCallback?.let {
                startMediaLibraryDetection(it)
                pendingMediaLibraryCallback = null
            }
        } else {
            Toast.makeText(this, "需要存储权限才能监控截图", Toast.LENGTH_SHORT).show()
            pendingMediaLibraryCallback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HomeCompose(
                onStartKeyPressDetection = ::startKeyPressDetection,
                onStopKeyPressDetection = ::stopKeyPressDetection,
                onStartScreenRecordingDetection = ::startScreenRecordingDetection,
                onStopScreenRecordingDetection = ::stopScreenRecordingDetection,
                onStartMirroringDetection = ::startMirroringDetection,
                onStopMirroringDetection = ::stopMirroringDetection,
                onStartMediaProjectionDetection = ::startMediaProjectionDetection,
                onStopMediaProjectionDetection = ::stopMediaProjectionDetection,
                onStartMediaRouterDetection = ::startMediaRouterDetection,
                onStopMediaRouterDetection = ::stopMediaRouterDetection,
                onStartMediaLibraryDetection = ::startMediaLibraryDetection,
                onStopMediaLibraryDetection = ::stopMediaLibraryDetection,
                onStartFileChangesDetection = ::startFileChangesDetection,
                onStopFileChangesDetection = ::stopFileChangesDetection
            )
        }
    }

    // ---------- 截屏检测 ----------
    private fun startKeyPressDetection(onDetected: () -> Unit) {
        if (Auxiliary.KeyPressDetectionAvailable) {
            stopKeyPressDetection()
            val callback = ScreenCaptureCallback { onDetected() }
            screenCaptureCallback = callback
            registerScreenCaptureCallback(mainExecutor, callback)
        }
    }

    private fun stopKeyPressDetection() {
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
    private fun startScreenRecordingDetection(onDetected: () -> Unit, onStopped: () -> Unit) {
        if (Auxiliary.ScreenRecordingDetectionAvailable) {
            stopScreenRecordingDetection()
            val callback = Consumer<Int> { state ->
                when (state) {
                    WindowManager.SCREEN_RECORDING_STATE_VISIBLE -> onDetected()
                    WindowManager.SCREEN_RECORDING_STATE_NOT_VISIBLE -> onStopped()
                }
            }
            screenRecordingCallback = callback
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.addScreenRecordingCallback(mainExecutor, callback)
        }
    }

    private fun stopScreenRecordingDetection() {
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
        onDetected: () -> Unit,
        onStopped: () -> Unit
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

            override fun onDisplayRemoved(displayId: Int) {
                if (!Auxiliary.hasNonDefaultDisplay(dm.displays)) {
                    onStopped()
                }
            }

            override fun onDisplayChanged(displayId: Int) {
                // 可选
            }
        }
    }

    // ---------- 投屏/镜像检测 ----------
    private fun startMirroringDetection(onDetected: () -> Unit, onStopped: () -> Unit) {
        stopMirroringDetection()
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val listener = createDisplayListener(dm, onDetected, onStopped)
        displayListener = listener
        dm.registerDisplayListener(listener, null)
        if (Auxiliary.hasNonDefaultDisplay(dm.displays)) {
            onDetected()
        }
    }

    private fun stopMirroringDetection() {
        displayListener?.let {
            try {
                (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it)
            } catch (_: Exception) { /* ignore */
            }
            displayListener = null
        }
    }

    // ---------- MediaProjection 检测 ----------
    private fun startMediaProjectionDetection(onDetected: () -> Unit, onStopped: () -> Unit) {
        stopMediaProjectionDetection()
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val listener = createDisplayListener(dm, onDetected, onStopped)
        mediaProjectionListener = listener
        dm.registerDisplayListener(listener, null)
        if (Auxiliary.hasNonDefaultDisplay(dm.displays)) {
            onDetected()
        }
    }

    private fun stopMediaProjectionDetection() {
        mediaProjectionListener?.let {
            try {
                (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it)
            } catch (_: Exception) { /* ignore */
            }
            mediaProjectionListener = null
        }
    }

    // ---------- MediaRouter 检测 ----------
    private fun startMediaRouterDetection(onConnected: () -> Unit, onDisconnected: () -> Unit) {
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
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo) {
                if (mediaRouter?.routes?.any { !it.isDefault } == false) {
                    onDisconnected()
                }
            }

            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                if (!route.isDefault) onConnected()
            }

            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
                if (mediaRouter?.routes?.any { !it.isDefault } == false) {
                    onDisconnected()
                }
            }

            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                // 可选
            }
        }
        mediaRouterCallback = callback
        mediaRouter?.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)

        if (mediaRouter?.routes?.any { !it.isDefault } == true) {
            onConnected()
        }
    }

    private fun stopMediaRouterDetection() {
        mediaRouterCallback?.let {
            mediaRouter?.removeCallback(it)
        }
        mediaRouterCallback = null
        mediaRouter = null
    }

    // ---------- 媒体库监听 ----------
    private fun startMediaLibraryDetection(onDetected: () -> Unit) {
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
                super.onChange(selfChange, uri)
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

    private fun stopMediaLibraryDetection() {
        mediaLibraryObserver?.let {
            contentResolver.unregisterContentObserver(it)
            mediaLibraryObserver = null
        }
    }

    // ---------- FileObserver 检测 ----------
    private fun startFileChangesDetection(onDetected: () -> Unit) {
        stopFileChangesDetection()
        val screenshotsDir = File(
            Environment.getExternalStorageDirectory(),
            "Pictures/Screenshots"
        )
        if (!screenshotsDir.exists()) return
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

    private fun stopFileChangesDetection() {
        fileObserver?.stopWatching()
        fileObserver = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopKeyPressDetection()
        stopScreenRecordingDetection()
        stopMirroringDetection()
        stopMediaProjectionDetection()
        stopMediaRouterDetection()
        stopMediaLibraryDetection()
    }
}