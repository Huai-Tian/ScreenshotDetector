package detect.screenshot

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
import detect.screenshot.detection.DetectionItems
import java.io.File

private const val SCREENSHOT_TIME_THRESHOLD = 15

object Auxiliary {
    const val BEHAVIOR_POLL_INTERVAL = 1000L
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

    /**
     * 环境风险检查：拆分为具体异常项(ADB/无线调试/开发者选项/模拟辅助显示/
     * 无障碍/无线显示开关)，返回当前存在的全部异常，附卡片详情(仅无障碍项
     * 有详情：已启用无障碍服务的应用包名列表)。
     * 其中无线调试、模拟辅助显示、无线显示开关为 Settings.Global 隐藏键
     * (常量未公开，键值经 AOSP 源码核实，直接以字符串读取)。
     * 无障碍详情来自 AccessibilityManager.getEnabledAccessibilityServiceList
     * (公开 API，无需权限，可跨应用枚举已启用的无障碍服务及其包名)。
     */
    fun environmentIssues(context: Context): List<Pair<DetectionItems, String?>> {
        val issues = mutableListOf<Pair<DetectionItems, String?>>()
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1) {
            issues += DetectionItems.ADB_ENABLED to null
        }
        // 无线调试(隐藏键 adb_wifi_enabled，API 30+；旧版本无此键恒为默认 0)
        if (Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1) {
            issues += DetectionItems.ADB_WIFI to null
        }
        if (Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1
        ) {
            issues += DetectionItems.DEVELOPER_OPTIONS to null
        }
        // 模拟辅助显示(隐藏键 overlay_display_devices，开发者选项子项；非空即已启用)
        if (!Settings.Global.getString(context.contentResolver, "overlay_display_devices")
                .isNullOrBlank()
        ) {
            issues += DetectionItems.OVERLAY_DISPLAY to null
        }
        val accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        if (enabledServices.isNotEmpty()) {
            // 注：本方法被 200ms 轮询高频调用，勿在此打日志
            val packages = enabledServices
                .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
                .distinct()
            if (packages.isNotEmpty()) {
                // 详情 = 完整数量 + 最多 5 个包名(与投屏授权详情同格式)
                issues += DetectionItems.ACCESSIBILITY_SERVICE to context.getString(
                    R.string.accessibility_service_detail,
                    packages.size,
                    packages.take(5).joinToString(", ")
                )
            } else {
                issues += DetectionItems.ACCESSIBILITY_SERVICE to null
            }
        }
        // 无线显示开关(隐藏键 wifi_display_on；开启≠正在投屏，为辅助信号)
        if (Settings.Global.getInt(context.contentResolver, "wifi_display_on", 0) == 1) {
            issues += DetectionItems.WIRELESS_DISPLAY_ON to null
        }
        return issues
    }

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