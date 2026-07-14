package detector.screenshot

import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display

object Auxiliary {
    const val TAG = "ScreenshotDetector"
    const val ID_SCREENSHOT = 1
    const val ID_RECORDING = 2
    const val ID_MIRRORING = 3
    const val ID_ENVIRONMENT = 4
    const val ID_MEDIA_PROJECTION = 5
    const val ID_MEDIA_LIBRARY = 6
    const val ID_MEDIA_ROUTER = 7
    const val ID_FILE_CHANGES = 8
    const val ID_SCREENSHOT_FAKER = 9
    val KeyPressDetectionAvailable =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    val ScreenRecordingDetectionAvailable =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    fun log(content: String) {
        Log.d(TAG, content)
    }

    fun isExternalDisplay(display: Display): Boolean {
        // 主屏通常是 0，但也不绝对，我们用 flag 判断更可靠
        if (display.displayId == Display.DEFAULT_DISPLAY) return false

        val flags = display.flags
        // 以下任一标志都表示可能是外部显示
        return (flags and DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) != 0 ||
                (flags and DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC) != 0 ||
                (flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS) != 0
    }

    fun isScreenCaptureDisplay(display: Display): Boolean {
        if (display.displayId == Display.DEFAULT_DISPLAY) return false
        val flags = display.flags
        // 如果是镜像，则不是屏幕捕获
        if ((flags and DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) != 0) return false
        // 以下标志通常表示录屏或屏幕共享
        return (flags and DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC) != 0 ||
                (flags and DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY) != 0 ||
                (flags and DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE) != 0
    }
}