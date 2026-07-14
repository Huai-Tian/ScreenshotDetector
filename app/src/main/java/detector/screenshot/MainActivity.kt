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

    private fun startKeyPressDetection(onDetected: () -> Unit) {
        if (Auxiliary.KeyPressDetectionAvailable) {
            stopKeyPressDetection() // 先取消旧的
            val callback = ScreenCaptureCallback {
                onDetected()
            }
            screenCaptureCallback = callback
            registerScreenCaptureCallback(mainExecutor, callback)
        }
    }

    private fun stopKeyPressDetection() {
        screenCaptureCallback?.let {
            if (Auxiliary.KeyPressDetectionAvailable) {
                try {
                    unregisterScreenCaptureCallback(it)
                } catch (_: Exception) {
                    // 忽略已取消注册的情况
                }
            }
            screenCaptureCallback = null
        }
    }

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
                } catch (_: Exception) {
                }
            }
            screenRecordingCallback = null
        }
    }

    private fun startMirroringDetection(onDetected: () -> Unit, onStopped: () -> Unit) {
        stopMirroringDetection() // 先移除旧的

        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                val display = dm.getDisplay(displayId)
                if (display != null && Auxiliary.isExternalDisplay(display)) {
                    onDetected()
                }
            }

            override fun onDisplayRemoved(displayId: Int) {
                // 检查是否还有外部显示
                val displays = dm.displays
                val hasExternal = displays.any { Auxiliary.isExternalDisplay(it) }
                if (!hasExternal) {
                    onStopped()
                }
            }

            override fun onDisplayChanged(displayId: Int) {
                // 可选：处理变更，一般不需要
            }
        }
        displayListener = listener
        dm.registerDisplayListener(listener, null)

        // 立即检查当前状态
        val displays = dm.displays
        if (displays.any { Auxiliary.isExternalDisplay(it) }) {
            onDetected()
        }
    }

    private fun stopMirroringDetection() {
        displayListener?.let {
            try {
                val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
                dm.unregisterDisplayListener(it)
            } catch (_: Exception) {
            }
            displayListener = null
        }
    }

    private fun startMediaProjectionDetection(onDetected: () -> Unit, onStopped: () -> Unit) {
        stopMediaProjectionDetection()
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                val display = dm.getDisplay(displayId)
                if (display != null && Auxiliary.isScreenCaptureDisplay(display)) {
                    onDetected()
                }
            }

            override fun onDisplayRemoved(displayId: Int) {
                val displays = dm.displays
                if (!displays.any { Auxiliary.isScreenCaptureDisplay(it) }) {
                    onStopped()
                }
            }

            override fun onDisplayChanged(displayId: Int) {}
        }
        mediaProjectionListener = listener
        dm.registerDisplayListener(listener, null)
        // 立即检查
        if (dm.displays.any { Auxiliary.isScreenCaptureDisplay(it) }) {
            onDetected()
        }
    }

    private fun stopMediaProjectionDetection() {
        mediaProjectionListener?.let {
            try {
                (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it)
            } catch (_: Exception) {
            }
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