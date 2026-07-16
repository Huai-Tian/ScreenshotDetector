package detector.screenshot

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import java.io.File

private const val SCREENSHOT_TIME_THRESHOLD = 15

object Auxiliary {
    const val ID_SCREENSHOT = 0
    const val ID_RECORDING = 1
    const val ID_MIRRORING = 2
    const val ID_ENVIRONMENT = 3
    const val ID_MEDIA_PROJECTION = 4
    const val ID_MEDIA_LIBRARY = 5
    const val ID_MEDIA_ROUTER = 6
    const val ID_FILE_CHANGES = 7
    const val ID_SCREENSHOT_FAKER = 8
    const val ID_BEHAVIOR = 9
    const val BEHAVIOR_POLL_INTERVAL = 2000L
    val KeyPressDetectionAvailable =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    val ScreenRecordingDetectionAvailable =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    @Suppress("unused")
    fun log(content: String) {
        Log.d("ScreenshotDetector", content)
    }

    fun isNonDefaultDisplay(display: Display) = display.displayId != Display.DEFAULT_DISPLAY

    fun hasNonDefaultDisplay(displays: Array<Display>) = displays.any { isNonDefaultDisplay(it) }

    fun checkForScreenshot(contentResolver: ContentResolver, onDetected: () -> Unit) {
        val timeThreshold = System.currentTimeMillis() / 1000 - SCREENSHOT_TIME_THRESHOLD
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ? AND (" +
                "LOWER(${MediaStore.Images.Media.DISPLAY_NAME}) LIKE ? OR " +
                "LOWER(${MediaStore.Images.Media.RELATIVE_PATH}) LIKE ?)"
        val selectionArgs = arrayOf(
            timeThreshold.toString(),
            "%screenshot%",
            "%screenshots%"
        )
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val dateAdded =
                    it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                val diff = System.currentTimeMillis() / 1000 - dateAdded
                if (diff <= SCREENSHOT_TIME_THRESHOLD) {
                    onDetected()
                }
            }
        }
    }

    fun isEnvironmentRisky(context: Context) =
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
                || Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0
        ) == 1
                || (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).isNotEmpty()

    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isScreenshotFakerPresent(context: Context): Boolean {
        try {
            context.packageManager.getPackageInfo("fake.screenshot", 0)
            return true
        } catch (_: PackageManager.NameNotFoundException) {
        }
        val dir = File(Environment.getExternalStorageDirectory(), "Pictures/ScreenshotFaker")
        return dir.exists() && dir.isDirectory
    }
}