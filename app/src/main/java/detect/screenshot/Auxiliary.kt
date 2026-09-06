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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import detect.screenshot.detection.DetectionItems

private const val SCREENSHOT_TIME_THRESHOLD = 15

/** adb shell 的 uid 归属包名(MediaProvider 写入者归因)：screencap 等命令通道 */
private const val SHELL_PACKAGE = "com.android.shell"

/** 隐藏 AppOps 字符串：投屏持久授权(视频，OPSTR_PROJECT_MEDIA) */
private const val OPSTR_PROJECT_MEDIA = "android:project_media"

/** 隐藏 AppOps 字符串：投屏持久授权(音频，OPSTR_PROJECT_AUDIO) */
private const val OPSTR_PROJECT_AUDIO = "android:project_audio"

/**
 * android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION(API 34 引入的
 * normal 权限，编译期硬编码字符串以兼容低版本 SDK)：targetSdk 34+ 的
 * 录屏/投屏应用为启动 MediaProjection 前台服务所必须声明
 */
private const val PERMISSION_MEDIA_PROJECTION_FGS =
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"

/** PackageManager.GET_PERMISSIONS(API 1 起的公开常量，SDK 37.1 起移出公开 stub，值 1 稳定) */
private const val PM_GET_PERMISSIONS = 1

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
        MediaStore.MediaColumns.MIME_TYPE,
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
     * 媒体库卷名列表(API 30+ 枚举全部外置卷——含 SD 卡等可插拔存储的
     * 独立卷；低版本无枚举接口退回主卷 external，非主卷媒体不可查)。
     * 存入非主卷的截图/录屏同样构成检测证据，逐卷查询。
     */
    internal fun mediaVolumeNames(context: Context): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { MediaStore.getExternalVolumeNames(context).toList() }
                .getOrDefault(listOf(MediaStore.VOLUME_EXTERNAL))
        } else {
            listOf(MediaStore.VOLUME_EXTERNAL)
        }

    /** 单集合扫描的聚合结果(见 [scanMediaCollection]) */
    private class MediaScanResult {
        var featureHit = false
        var featureOwner: String? = null
        var shellFile: String? = null
    }

    /**
     * 媒体库扫描结果(见 [checkForScreenshot]/[checkForScreenRecordingVideo])：
     * - [featureHit]：特征词命中([featureOwner] 为最新特征行的写入者，
     *   归因不可用时 null，见 mediaProjection 注释)；
     * - [shellFile]：Shell 通道(写入者 com.android.shell)命中行的文件名，
     *   未命中为 null。
     */
    data class MediaScanOutcome(
        val featureHit: Boolean = false,
        val featureOwner: String? = null,
        val shellFile: String? = null
    )

    /**
     * 单集合扫描：回看窗口(取"现在-15s"与重置水位线的较大值)内逐行判定
     * 两路信号，聚合进 [result]——
     * - 特征词命中([featureMatcher] 非空时)：文件名/相对路径匹配即记
     *   featureHit，featureOwner 为最新特征行的写入者(归因不可用时 null，
     *   见 mediaProjection 注释)；
     * - Shell 通道：写入者为 com.android.shell 即记 shellFile(首个命中行
     *   的文件名)，不受特征词约束(adb screencap/screenrecord 可写任意
     *   位置任意名，故查询不按特征词过滤，判定在代码侧完成)。
     * [mimePrefix] 非空时按 MIME_TYPE 前缀过滤(Downloads 集合混有全部
     * 文件类型，如 "video/"——特征词命名的 pdf 等非视频文件不构成录屏
     * 证据)；null = 不过滤(Images/Video 集合本身按类型分表)。
     * 行按 DATE_ADDED 降序遍历。
     */
    private fun scanMediaCollection(
        contentResolver: ContentResolver,
        uri: Uri,
        notBeforeSec: Long,
        mimePrefix: String?,
        featureMatcher: ((name: String, path: String) -> Boolean)?,
        result: MediaScanResult
    ) {
        val timeThreshold = maxOf(
            System.currentTimeMillis() / 1000 - SCREENSHOT_TIME_THRESHOLD,
            notBeforeSec
        )
        val cursor = runCatching {
            contentResolver.query(
                uri,
                mediaProjection,
                mediaQueryBundle(
                    "${MediaStore.MediaColumns.DATE_ADDED} > ?",
                    arrayOf(timeThreshold.toString())
                ),
                null
            )
        }.getOrNull() ?: return
        cursor.use {
            val nameIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val dateIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val mimeIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (it.moveToNext()) {
                if (it.getLong(dateIdx) <= timeThreshold) continue
                if (mimePrefix != null &&
                    !it.getString(mimeIdx).orEmpty().startsWith(mimePrefix)
                ) continue
                val name = it.getString(nameIdx) ?: continue
                if (result.shellFile == null && ownerOf(it) == SHELL_PACKAGE) {
                    result.shellFile = name
                }
                if (!result.featureHit && featureMatcher != null) {
                    val path = it.getString(pathIdx)?.lowercase().orEmpty()
                    if (featureMatcher(name.lowercase(), path)) {
                        result.featureHit = true
                        result.featureOwner = ownerOf(it)
                    }
                }
            }
        }
    }

    /** 截图特征命名：文件名含 screenshot 或相对路径含 screenshots 目录 */
    private fun isScreenshotFeatureName(name: String, path: String): Boolean =
        name.contains("screenshot") || path.contains("screenshots")

    /** 录屏特征命名(覆盖 AOSP 与中文 ROM 命名) */
    private fun isRecordingFeatureName(name: String, path: String): Boolean =
        name.contains("screenrecord") || name.contains("screen record") ||
                name.contains("screen_record") || name.contains("屏幕录制") ||
                name.contains("录屏") || path.contains("screenrecord") ||
                path.contains("screen records")

    /**
     * 回看窗口内新增图片扫描(两路信号)：
     * - 截图特征：文件名/相对路径含 screenshot 即命中(featureOwner 为最新
     *   特征行的写入者)；
     * - Shell 通道：写入者 owner_package_name 为 com.android.shell(adb
     *   shell 的 uid 归属包；screencap 可写任意位置任意名，不受特征词
     *   约束)即命中(shellFile 为该行文件名)。
     * 逐卷扫描 Images 集合(双信号)与 Downloads 集合(Download/ 目录，仅查
     * Shell 信号并按 image/ MIME 过滤——screencap 落盘位置不受限；特征词
     * 路径不做，下载的"screenshot"命名文件非设备捕获事件)。
     * 查询在调用线程执行(多卷×多集合的 Binder+SQLite 查询，调用方应置于
     * IO 线程)。
     *
     * @param notBeforeSec 重置水位线(秒)：仅回查 DATE_ADDED 晚于该时刻的
     *   证据，防止用户"重置检测结果"后 15s 回看窗口内的旧截图被重新报出
     *   (媒体扫描器对旧文件的后续更新会持续触发 onChange)；0 = 不限制
     */
    fun checkForScreenshot(context: Context, notBeforeSec: Long): MediaScanOutcome {
        val result = MediaScanResult()
        val contentResolver = context.contentResolver
        for (volume in mediaVolumeNames(context)) {
            scanMediaCollection(
                contentResolver,
                MediaStore.Images.Media.getContentUri(volume),
                notBeforeSec,
                mimePrefix = null,
                featureMatcher = ::isScreenshotFeatureName,
                result
            )
            scanMediaCollection(
                contentResolver,
                MediaStore.Downloads.getContentUri(volume),
                notBeforeSec,
                mimePrefix = "image/",
                featureMatcher = null,
                result
            )
        }
        return MediaScanOutcome(result.featureHit, result.featureOwner, result.shellFile)
    }

    /**
     * 回看窗口内新增录屏视频扫描(两路信号)：
     * - 录屏特征：文件名/相对路径命中录屏特征词(screenrecord / 屏幕录制
     *   等，见 [isRecordingFeatureName])，featureOwner 为最新特征行写入者
     *   (归因可降级)；
     * - Shell 通道：写入者为 com.android.shell(如 adb shell screenrecord，
     *   写入位置/命名不受特征词约束)即命中(shellFile 为该行文件名)。
     * 逐卷扫描 Video 集合与 Downloads 集合(Download/ 目录的视频不进
     * Video 集合，按 video/ MIME 过滤)。查询在调用线程执行(见上)。
     * [notBeforeSec] 为重置水位线(秒)，语义见 [checkForScreenshot]。
     */
    fun checkForScreenRecordingVideo(
        context: Context,
        notBeforeSec: Long
    ): MediaScanOutcome {
        val result = MediaScanResult()
        val contentResolver = context.contentResolver
        for (volume in mediaVolumeNames(context)) {
            scanMediaCollection(
                contentResolver,
                MediaStore.Video.Media.getContentUri(volume),
                notBeforeSec,
                mimePrefix = null,
                featureMatcher = ::isRecordingFeatureName,
                result
            )
            scanMediaCollection(
                contentResolver,
                MediaStore.Downloads.getContentUri(volume),
                notBeforeSec,
                mimePrefix = "video/",
                featureMatcher = ::isRecordingFeatureName,
                result
            )
        }
        return MediaScanOutcome(result.featureHit, result.featureOwner, result.shellFile)
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
        val accessibilityPackages = runCatching {
            enabledThirdPartyAccessibilityPackages(context)
        }.getOrDefault(emptyList())
        if (accessibilityPackages.isNotEmpty()) {
            // 详情 = 完整数量 + 最多 5 个包名(与投屏授权详情同格式)
            issues += DetectionItems.ACCESSIBILITY_SERVICE to context.getString(
                R.string.accessibility_service_detail,
                accessibilityPackages.size,
                accessibilityPackages.take(5).joinToString(", ")
            )
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
     * 已启用的三方无障碍服务包名：getEnabledAccessibilityServiceList(公开
     * API，无需权限，跨应用实时 Binder 查询、无客户端缓存)。排除本应用
     * 自身的增强服务(用户知情开启，非环境风险)——仅剩自身服务时返回空
     * (调用方的清除回调据此移除卡片)。查询异常向上抛，由调用方决定降级
     * (快速轮询跳过本轮保持既有状态，全量检查按空处理)。
     */
    fun enabledThirdPartyAccessibilityPackages(context: Context): List<String> {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
            .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
            .filter { it != context.packageName }
            .distinct()
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
     * 已启用通知监听的三方应用：可读取设备上全部通知内容(验证码、消息等)。
     * 数据源与自查同款(NotificationManagerCompat 读取 Settings.Secure 的
     * 已启用列表)，跨应用枚举；排除自身(增强服务)与系统应用。
     */
    fun thirdPartyNotificationListeners(context: Context): List<String> = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .filter { it != context.packageName && isThirdPartyApp(context, it) }
            .sorted()
    }.getOrDefault(emptyList())

    /**
     * AppOps 模式查询(字符串 op)。反射调用并缓存 Method(逐包调用的热点
     * 路径，getMethod 查找昂贵)：优先 unsafeCheckOpNoThrow(公开于 API 30，
     * API 29 运行时不存在——直接调用会抛 NoSuchMethodError 且不被
     * catch(Exception) 捕获)，退回 checkOpNoThrow(自 API 19 起存在的隐藏
     * 方法，运行时类恒有，经 HiddenApiBypass 豁免可达)。失败返回
     * MODE_ERRORED 即不匹配。
     */
    private val checkOpMethod: java.lang.reflect.Method? by lazy {
        val signature = arrayOf(String::class.java, Integer.TYPE, String::class.java)
        runCatching {
            AppOpsManager::class.java.getMethod("unsafeCheckOpNoThrow", *signature)
        }.getOrElse {
            runCatching {
                AppOpsManager::class.java.getMethod("checkOpNoThrow", *signature)
            }.getOrNull()
        }
    }

    fun checkOpNoThrow(appOps: AppOpsManager, op: String, uid: Int, packageName: String): Int =
        runCatching { checkOpMethod?.invoke(appOps, op, uid, packageName) as? Int }
            .getOrNull()
            ?: AppOpsManager.MODE_ERRORED

    /** 能力面单次扫描的聚合结果(见 [capabilityScan]) */
    data class CapabilityScan(
        /** 持有免询问投屏持久授权(project_media/audio)的包名(含系统应用) */
        val projectionConsent: List<String>,
        /** 清单声明截屏前台服务权限的三方应用 */
        val captureChannel: List<String>,
        /** 持有悬浮窗特殊授权(SAW 为 MODE_ALLOWED)的三方应用 */
        val overlayCapable: List<String>
    )

    /**
     * 能力面统一扫描：单次 getInstalledPackages(GET_PERMISSIONS) 枚举产出
     * 三组结果(旧实现三路各自枚举，每 15s 达 3 次全量枚举)——
     * - 免询问投屏授权：project_media/project_audio 任一 MODE_ALLOWED
     *   (AppOps 反射查询，服务端无越包校验经 AOSP 源码核实，getPackages
     *   ForOps 等统计接口才会被 GET_APP_OPS_STATS 拦截；按 uid 去重防共享
     *   uid 重复查询)，含系统应用；
     * - 截屏通道：三方应用清单声明 FOREGROUND_SERVICE_MEDIA_PROJECTION
     *   (读 requestedPermissions 原始数组而非 checkPermission——权限在旧
     *   系统未定义时 checkPermission 恒拒，清单读取跨版本稳定)；
     * - 悬浮窗授权：三方应用 SAW op 为 MODE_ALLOWED(设置中显式开启；
     *   Settings.canDrawOverlays 仅支持自查自身，无法查他者)。
     * 三方应用判定：非系统应用且非系统应用的更新(预装 Gboard 等不报，
     * 防噪音)。旧 targetSdk 应用不声明截屏权限、targetSdk<23 默认持有
     * 悬浮窗但 op 为 MODE_DEFAULT 不统计(见 README 已知限制)。
     * 全量包枚举依赖 QUERY_ALL_PACKAGES。
     */
    fun capabilityScan(context: Context): CapabilityScan = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val consent = mutableListOf<String>()
        val channel = mutableListOf<String>()
        val overlay = mutableListOf<String>()
        val seenUids = HashSet<Int>()
        context.packageManager.getInstalledPackages(PM_GET_PERMISSIONS)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .forEach { pi ->
                val ai = pi.applicationInfo ?: return@forEach
                val pkg = pi.packageName
                if (seenUids.add(ai.uid) &&
                    (checkOpNoThrow(appOps, OPSTR_PROJECT_MEDIA, ai.uid, pkg) ==
                            AppOpsManager.MODE_ALLOWED ||
                            checkOpNoThrow(appOps, OPSTR_PROJECT_AUDIO, ai.uid, pkg) ==
                            AppOpsManager.MODE_ALLOWED)
                ) {
                    consent += pkg
                }
                if ((ai.flags and (ApplicationInfo.FLAG_SYSTEM or
                            ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                ) return@forEach
                if (pi.requestedPermissions?.contains(PERMISSION_MEDIA_PROJECTION_FGS) == true) {
                    channel += pkg
                }
                if (checkOpNoThrow(
                        appOps, AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, ai.uid, pkg
                    ) == AppOpsManager.MODE_ALLOWED
                ) {
                    overlay += pkg
                }
            }
        CapabilityScan(consent.sorted(), channel.sorted(), overlay.sorted())
    }.getOrDefault(CapabilityScan(emptyList(), emptyList(), emptyList()))

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
     * 权限接口不可查，经 AppOps OPSTR_GET_USAGE_STATS 查询)。AppOps 查询
     * 走 [checkOpNoThrow] 反射(unsafeCheckOpNoThrow 公开于 API 30，API 29
     * 直接调用会抛 NoSuchMethodError)。
     */
    fun hasUsageAccess(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        checkOpNoThrow(
            appOps,
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

    /**
     * ScreenshotFaker 特征检查：返回命中的特征来源(安装包检测——包名
     * [SCREENSHOT_FAKER_PACKAGE] 的应用已安装，返回其包名)，未命中返回 null。
     * 旧版的 Pictures/ScreenshotFaker 目录特征已移除(新版 Faker 不再
     * 使用该目录，残留目录会造成误报)。
     */
    fun screenshotFakerTrace(context: Context): String? {
        return try {
            context.packageManager.getPackageInfo(SCREENSHOT_FAKER_PACKAGE, 0)
            SCREENSHOT_FAKER_PACKAGE
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** ScreenshotFaker 的安装包名(特征检测目标，见 [screenshotFakerTrace]) */
    const val SCREENSHOT_FAKER_PACKAGE = "fake.screenshot"
}