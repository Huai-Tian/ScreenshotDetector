package detector.screenshot

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat

private const val SCREENSHOT_TIME_THRESHOLD = 15

object Auxiliary {
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

        // 使用 LOWER() 忽略大小写，提高匹配率
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
                // 再次确认时间差在阈值内（防止查询条件误匹配）
                if (diff <= SCREENSHOT_TIME_THRESHOLD) {
                    onDetected()
                }
            }
        }
    }

    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 检查图片权限
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 12 及以下检查存储权限
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}