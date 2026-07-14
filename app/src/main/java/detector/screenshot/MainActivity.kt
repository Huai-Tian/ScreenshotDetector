package detector.screenshot

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HomeCompose(
                onStartKeyPressDetection = ::startKeyPressDetection,
                onStopKeyPressDetection = ::stopKeyPressDetection,
                onStartScreenRecordingDetection = ::startScreenRecordingDetection,
                onStopScreenRecordingDetection = ::stopScreenRecordingDetection
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

    override fun onDestroy() {
        super.onDestroy()
        stopKeyPressDetection()
        stopScreenRecordingDetection()
    }
}