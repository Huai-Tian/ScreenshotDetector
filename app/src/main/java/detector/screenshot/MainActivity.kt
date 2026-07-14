package detector.screenshot

import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import detector.screenshot.pages.HomeCompose
import java.util.function.Consumer

class MainActivity : ComponentActivity() {
    private var screenCaptureCallback: ScreenCaptureCallback? = null
    private var screenRecordingCallback: Consumer<Int>? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var mediaProjectionListener: DisplayManager.DisplayListener? = null

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
                onStopMediaProjectionDetection = ::stopMediaProjectionDetection
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
                } catch (_: Exception) { /* ignore */ }
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
                } catch (_: Exception) { /* ignore */ }
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
            } catch (_: Exception) { /* ignore */ }
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
            } catch (_: Exception) { /* ignore */ }
            mediaProjectionListener = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopKeyPressDetection()
        stopScreenRecordingDetection()
        stopMirroringDetection()
        stopMediaProjectionDetection()
    }
}