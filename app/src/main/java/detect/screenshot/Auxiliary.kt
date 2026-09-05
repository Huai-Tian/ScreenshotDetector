package detect.screenshot

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import detect.screenshot.detection.DetectionItems
import java.io.File

private const val SCREENSHOT_TIME_THRESHOLD = 15

/**
 * 全量枚举可见的最低包数阈值：正常设备(预装+用户应用)必然远超此数；
 * 被 ColorOS"获取应用列表"开关拦截时通常仅返回自身与极少数系统包
 */
private const val APP_LIST_MIN_COUNT = 20

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

    /**
     * 媒体库查询的投影：含隐藏列 owner_package_name(写入者归因，
     * MediaProvider 可能对跨应用查询屏蔽该列，读取侧降级处理)。
     */
    private val mediaProjection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DATE_ADDED,
        "owner_package_name"
    )

    /**
     * 构建"含他人 pending 行"的媒体库查询 Bundle：
     * - QUERY_ARG_MATCH_PENDING = 1(MATCH_INCLUDE)：pending 行在写入瞬间即产生
     *   (媒体库扫描完成前)，纳入后截图/录屏检出提前数秒；
     * - 传递 SQL selection/sort(API 29 的 Bundle 查询重载)。
     * 注：MATCH_PENDING/SQL_SORT_BY 两个 key 自 SDK 37.1 起移出公开 stub
     * (QUERY_ARG_SQL_SELECTION/ARGS 仍在)，硬编码字符串值(API 29 起稳定)。
     */
    private fun mediaQueryBundle(selection: String, args: Array<String>): Bundle = Bundle().apply {
        putInt("android:query-arg-match-pending", 1)
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        putString(
            "android:query-arg-sql-sort-by",
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )
    }

    /** 读取当前行写入者包名(隐藏列被屏蔽或不存在时返回 null) */
    private fun ownerOf(cursor: android.database.Cursor): String? {
        val idx = cursor.getColumnIndex("owner_package_name")
        if (idx < 0) return null
        return cursor.getString(idx)?.takeUnless { it.isBlank() }
    }

    /**
     * 回看窗口内新增截图检查：文件名/相对路径含 screenshot 特征即命中，
     * 返回写入者包名(归因不可用时 null，见 mediaProjection 注释)。
     */
    fun checkForScreenshot(contentResolver: ContentResolver, onDetected: (owner: String?) -> Unit) {
        val timeThreshold = System.currentTimeMillis() / 1000 - SCREENSHOT_TIME_THRESHOLD
        val name = "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME})"
        val path = "LOWER(${MediaStore.MediaColumns.RELATIVE_PATH})"
        val selection = "${MediaStore.MediaColumns.DATE_ADDED} > ? AND (" +
                "$name LIKE ? OR $path LIKE ?)"
        val selectionArgs = arrayOf(
            timeThreshold.toString(),
            "%screenshot%",
            "%screenshots%"
        )
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            mediaProjection,
            mediaQueryBundle(selection, selectionArgs),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val dateAdded =
                    it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
                if (System.currentTimeMillis() / 1000 - dateAdded <= SCREENSHOT_TIME_THRESHOLD) {
                    onDetected(ownerOf(it))
                }
            }
        }
    }

    /**
     * 回看窗口内新增录屏视频检查：文件名/相对路径命中录屏特征词
     * (screenrecord / screen record / screen_record / 屏幕录制 / 录屏，
     * 覆盖 AOSP 与中文 ROM 命名)。返回写入者包名(同上，归因可降级)。
     */
    fun checkForScreenRecordingVideo(
        contentResolver: ContentResolver,
        onDetected: (owner: String?) -> Unit
    ) {
        val timeThreshold = System.currentTimeMillis() / 1000 - SCREENSHOT_TIME_THRESHOLD
        val name = "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME})"
        val path = "LOWER(${MediaStore.MediaColumns.RELATIVE_PATH})"
        val selection = "${MediaStore.MediaColumns.DATE_ADDED} > ? AND (" +
                "$name LIKE ? OR $name LIKE ? OR $name LIKE ? OR $name LIKE ? OR $name LIKE ? OR " +
                "$path LIKE ? OR $path LIKE ?)"
        val selectionArgs = arrayOf(
            timeThreshold.toString(),
            "%screenrecord%",
            "%screen record%",
            "%screen_record%",
            "%屏幕录制%",
            "%录屏%",
            "%screenrecord%",
            "%screen records%"
        )
        val cursor = contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            mediaProjection,
            mediaQueryBundle(selection, selectionArgs),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val dateAdded =
                    it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
                if (System.currentTimeMillis() / 1000 - dateAdded <= SCREENSHOT_TIME_THRESHOLD) {
                    onDetected(ownerOf(it))
                }
            }
        }
    }

    /**
     * 环境风险检查：拆分为具体异常项，返回当前存在的全部异常，附卡片详情。
     * 覆盖：ADB/无线调试/开发者选项(公开键)、模拟辅助显示/无线显示开关
     * (隐藏键)、无障碍(实时 Binder 查询，仅第三方)、读屏者通道(输入法/
     * 自动填充/语音服务/默认助手，仅三方应用)、底座/桌面模式(UiModeManager)。
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
            // 排除本应用自身的增强服务(用户知情开启，非环境风险)；
            // 仅剩自身服务时整个不上报(reportEnvironmentState 的清除回调
            // 会移除既有卡片)，卡片与详情均只反映第三方无障碍服务
            val packages = enabledServices
                .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
                .filter { it != context.packageName }
                .distinct()
            if (packages.isNotEmpty()) {
                // 详情 = 完整数量 + 最多 5 个包名(与投屏授权详情同格式)
                issues += DetectionItems.ACCESSIBILITY_SERVICE to context.getString(
                    R.string.accessibility_service_detail,
                    packages.size,
                    packages.take(5).joinToString(", ")
                )
            }
        }
        // 无线显示开关(隐藏键 wifi_display_on；开启≠正在投屏，为辅助信号)，
        // 详情附 WFD 扫描到的可用对端(反射 WifiDisplayStatus.getDisplayList)
        if (Settings.Global.getInt(context.contentResolver, "wifi_display_on", 0) == 1) {
            issues += DetectionItems.WIRELESS_DISPLAY_ON to wfdAvailableReceivers(context)
        }
        // ---------- 读屏者通道(仅报三方应用，预装系统服务不报防噪音) ----------
        // 当前输入法(公开键)：可读取全部按键输入
        reportThirdPartyService(context, Settings.Secure.getString(
            context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
        ))?.let {
            issues += DetectionItems.INPUT_METHOD to it
        }
        // 自动填充服务(隐藏键 autofill_service)：可读取全部表单内容
        reportThirdPartyService(context, Settings.Secure.getString(
            context.contentResolver, "autofill_service"
        ))?.let {
            issues += DetectionItems.AUTOFILL_SERVICE to it
        }
        // 语音交互服务(公开键，SDK 37.1 起移出公开 stub，硬编码值稳定)：语音助手常驻通道
        reportThirdPartyService(context, Settings.Secure.getString(
            context.contentResolver, "voice_interaction_service"
        ))?.let {
            issues += DetectionItems.VOICE_INTERACTION to it
        }
        // 默认助手(RoleManager API 29+；getRoleHolders 自 SDK 37.1 起移出公开
        // stub 改反射，QUERY_ROLE_HOLDERS 已在 Manifest 声明)：assist 通道是
        // 合法的整屏截图入口(长按助手手势)
        runCatching {
            val rm = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
            @Suppress("UNCHECKED_CAST")
            val holders = rm.javaClass
                .getMethod("getRoleHolders", String::class.java)
                .invoke(rm, RoleManager.ROLE_ASSISTANT) as? List<String>
            val holder = holders?.firstOrNull()
            if (holder != null && isThirdPartyApp(context, holder)) {
                issues += DetectionItems.ASSISTANT_APP to
                        context.getString(R.string.current_service_detail, holder)
            }
        }
        // 底座/桌面模式(UiModeManager 公开查询)：dock 接入是桌面窗口模式/
        // 外接显示的强前置信号，与 EXTERNAL_DISPLAY 卡互补
        runCatching {
            val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
            if (uiMode.currentModeType != Configuration.UI_MODE_TYPE_NORMAL) {
                issues += DetectionItems.DOCK_CONNECTED to null
            }
        }
        return issues
    }

    /**
     * 读屏服务条目：设置键值形如 "包名/组件名"，取包名判定三方
     * (系统应用跳过)并生成 "当前：包名" 详情；键为空/已卸载/系统应用
     * 均返回 null 不上报。
     */
    private fun reportThirdPartyService(context: Context, component: String?): String? {
        val pkg = component?.substringBefore('/')?.takeUnless { it.isBlank() } ?: return null
        if (!isThirdPartyApp(context, pkg)) return null
        return context.getString(R.string.current_service_detail, pkg)
    }

    /** 是否三方应用：非系统应用且非系统应用的更新(预装 Gboard 等不报，防噪音) */
    private fun isThirdPartyApp(context: Context, pkg: String): Boolean = runCatching {
        val ai = context.packageManager.getApplicationInfo(pkg, 0)
        (ai.flags and (ApplicationInfo.FLAG_SYSTEM or
                ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0
    }.getOrDefault(false)

    /**
     * WFD 可用对端详情：反射 WifiDisplayStatus.getDisplayList()
     * (隐藏 API，与 getWifiDisplayStatus 同门无权限校验)，空列表返回 null。
     */
    private fun wfdAvailableReceivers(context: Context): String? = runCatching {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE)
        val status = dm.javaClass.getMethod("getWifiDisplayStatus").invoke(dm) ?: return null
        val list = status.javaClass.getMethod("getDisplayList").invoke(status) as? Array<*>
            ?: return null
        val names = list.mapNotNull { display ->
            display?.let { d ->
                runCatching {
                    d.javaClass.getMethod("getFriendlyDisplayName").invoke(d) as? String
                }.getOrNull()
            }
        }.filter { it.isNotBlank() }
        if (names.isEmpty()) null
        else context.getString(R.string.wfd_available_detail, names.take(5).joinToString(", "))
    }.getOrNull()

    /** 图片媒体库权限(33+ READ_MEDIA_IMAGES / 旧版 READ_EXTERNAL_STORAGE) */
    fun hasImagesPermission(context: Context): Boolean {
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

    /** 视频媒体库权限(33+ READ_MEDIA_VIDEO / 旧版 READ_EXTERNAL_STORAGE) */
    fun hasVideoPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 图+视频媒体库权限是否齐备(权限面板"照片和视频"项的判定) */
    fun hasMediaPermissions(context: Context): Boolean =
        hasImagesPermission(context) && hasVideoPermission(context)

    /**
     * 是否已授予"使用情况访问权"(PACKAGE_USAGE_STATS 为特殊访问授权，运行时
     * 权限接口不可查，经 AppOps OPSTR_GET_USAGE_STATS 查询)。
     */
    fun hasUsageAccess(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    /**
     * 应用列表(全量枚举)是否可用。QUERY_ALL_PACKAGES 在原生 Android 为安装时
     * 权限恒可见；ColorOS 存在运行时"获取应用列表"开关，拦截点在全量枚举
     * (getInstalledPackages 返回被裁剪的极小集合)而非单包查询——实测未授权时
     * 单包 getPackageInfo 仍放行，故探测必须与消费路径(投屏授权枚举)同款
     * 调用，按返回规模判定。
     */
    fun appListVisible(context: Context): Boolean = try {
        context.packageManager.getInstalledPackages(0).size > APP_LIST_MIN_COUNT
    } catch (_: Exception) {
        false
    }

    /**
     * 本应用自身的无障碍服务是否已启用：过滤系统已启用无障碍服务列表中的
     * 本包名条目(与 environmentIssues 同数据源，实时 Binder 查询)。
     * 当前应用未声明无障碍服务时恒为 false。
     */
    fun isOwnAccessibilityServiceEnabled(context: Context): Boolean = try {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
    } catch (_: Exception) {
        false
    }

    /**
     * 本应用的通知监听服务("通知使用权")是否已启用：
     * NotificationManagerCompat 公开接口(读取 Settings.Secure 的
     * enabled_notification_listeners 已启用列表)，包含本包名即已启用。
     */
    fun hasNotificationAccess(context: Context): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    } catch (_: Exception) {
        false
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