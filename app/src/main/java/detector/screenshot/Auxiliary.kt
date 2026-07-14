package detector.screenshot

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

    fun log(content: String) = Log.d(TAG, content)

    fun isNonDefaultDisplay(display: Display) = display.displayId != Display.DEFAULT_DISPLAY

    fun hasNonDefaultDisplay(displays: Array<Display>) = displays.any { isNonDefaultDisplay(it) }
}