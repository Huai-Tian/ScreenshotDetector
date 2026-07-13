package detector.screenshot

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import detector.screenshot.pages.HomeCompose

class MainActivity : ComponentActivity() {
    private var screenCaptureCallback: ScreenCaptureCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HomeCompose(
                onStartKeyPressDetection = { onDetected ->
                    if (Auxiliary.isKeyPressScreenshotDetectionAvailable) startKeyPressDetection(
                        onDetected
                    )
                },
                onStopKeyPressDetection = {
                    stopKeyPressDetection()
                }
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun startKeyPressDetection(onDetected: () -> Unit) {
        stopKeyPressDetection() // 先取消旧的
        val callback = ScreenCaptureCallback {
            onDetected()
        }
        screenCaptureCallback = callback
        registerScreenCaptureCallback(mainExecutor, callback)
    }

    private fun stopKeyPressDetection() {
        screenCaptureCallback?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    unregisterScreenCaptureCallback(it)
                } catch (_: Exception) {
                    // 忽略已取消注册的情况
                }
            }
            screenCaptureCallback = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopKeyPressDetection()
    }
}