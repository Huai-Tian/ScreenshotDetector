package detect.screenshot.detection

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import detect.screenshot.Auxiliary
import detect.screenshot.MainActivity
import detect.screenshot.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.function.Consumer
import kotlin.time.Duration.Companion.milliseconds

/**
 * 全部检测逻辑的宿主：持有各检测器的回调/观察者等运行期状态，
 * 提供各检测项的启动/停止方法(由 [DetectionItems] 的 start/stop 回调调用)。
 *
 * 由 MainActivity 持有单一实例，生命周期与 Activity 一致。
 */
class DetectionFunctions(private val activity: MainActivity) {

    // ---------- 运行期状态 ----------
    private var screenCaptureCallback: Activity.ScreenCaptureCallback? = null
    private var screenRecordingCallback: Consumer<Int>? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var mediaProjectionListener: DisplayManager.DisplayListener? = null
    private var mediaRouter: MediaRouter? = null
    private var mediaRouterCallback: MediaRouter.Callback? = null
    private var mediaLibraryObserver: ContentObserver? = null
    private var pendingMediaLibraryCallback: (() -> Unit)? = null
    private var fileObserver: FileObserver? = null
    private var lastFileObserverTime = 0L
    private var isBehaviorDetectionActive = false
    private var behaviorIssueCallback: ((DetectionItems) -> Unit)? = null
    private var environmentObserver: ContentObserver? = null
    private var accessibilityListener: AccessibilityManager.AccessibilityStateChangeListener? = null
    private var environmentIssueCallback: ((DetectionItems, String?) -> Unit)? = null
    private var environmentPollingJob: Job? = null

    /** 无障碍项的"状态清除"回调(HomePage 注入)：服务全部停用后移除卡片 */
    private var environmentClearCallback: ((DetectionItems) -> Unit)? = null
    private var screenshotFakerCheckJob: Job? = null
    private var behaviorPollingJob: Job? = null

    // ========== 免询问投屏授权轮询(AppOps 能力面) ==========
    private var projectionConsentJob: Job? = null

    // ========== Miracast(WFD) 状态广播接收器 ==========
    private var wifiDisplayStatusReceiver: BroadcastReceiver? = null

    // ========== 设备录音活动回调 ==========
    private var audioRecordingCallback: AudioManager.AudioRecordingCallback? = null

    init {
        // 隐藏 API 全量豁免须先于任何检测启动执行(幂等，WindowReflectionDetector
        // 中的重复调用无害)：DisplayInfo/WifiDisplayStatus 反射、AppOps 字符串 op
        // 查询等均依赖此豁免
        applyHiddenApiExemption()
    }

    private fun applyHiddenApiExemption() {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
    }

    // ========== 窗口反射检测(自由小窗/系统级悬浮窗) ==========
    /** 引用计数：FREEFORM_WINDOW 与 SYSTEM_FLOATING_WINDOW 共用同一轮询 */
    private var windowDetectionRefCount = 0
    private var windowPollingJob: Job? = null
    private var windowReflectionDetector: WindowReflectionDetector? = null

    // ========== 可信呈现监听(API 35+，窗口显示完整性信号) ==========
    /**
     * TrustedPresentation 回调。系统(SurfaceFlinger)持续计算本应用窗口
     * 实际被渲染的像素比例，跌出阈值即回调 false：外部悬浮窗/系统浮层
     * 遮挡、手势导航离场动画、半透明化等任何使窗口呈现不完整的因素都
     * 触发。按信号本体语义上报 WINDOW_NOT_FULLY_PRESENTED，不归因为悬浮窗
     * (悬浮窗检测由触摸遮挡与焦点归因两路独立信号承担)。
     */
    private var trustedPresentationConsumer: Consumer<Boolean>? = null

    /** bootstrap 超时回调(注册后未收到任何回调时的冷启动兜底上报，见下方方法) */
    private var trustedPresentationBootstrap: Runnable? = null

    /** 注册后是否收到过任意 TrustedPresentation 回调(正常无遮挡启动 ~0.5s 内收到 true) */
    private var trustedPresentationCallbackSeen = false

    /** 注册代数：stop 先于异步注册 post 执行时(重新检测/页面销毁)，使过期 post 失效 */
    private var trustedPresentationGeneration = 0

    // ========== 触摸遮挡状态(悬浮窗检测信号) ==========
    private var isTouchObscured = false
    private var isInBackground = false

    /**
     * 自发导航标志：本应用主动发起的系统权限对话框/引导跳转设置期间为 true，
     * 此间离开前台不算切屏(用户并未主动切走)。仅在 onActivityResumed 清除——
     * 权限结果回调先于 onResume 到达，若在回调里清除，轮询可能落在
     * "回调已到、尚未 onResume"的间隙而误报切屏。
     */
    private var isSelfNavigation = false

    // ========== 外接显示器监听(桌面模式信号) ==========
    /**
     * DisplayManager.DisplayListener.onDisplayAdded：外接显示器接入是
     * 桌面窗口模式(Android 16 QPR3 GA)/投屏外显的确定性前置事件。
     * 检出为独立检测项 EXTERNAL_DISPLAY，与投屏检测(投出)语义互补
     * (此为"接入进来")。注册时先查既有显示器(冷启动时已接入也能检出)。
     * (SDK 37.1 已移除 ACTION_DISPLAY_ADDED 广播常量，改用 DisplayListener)
     */
    private var externalDisplayListener: DisplayManager.DisplayListener? = null

    // ---------- 行为检测的上报回调签名适配(丢弃详情参数) ----------
    private fun envAdapter(onIssue: (DetectionItems, String?) -> Unit): (DetectionItems) -> Unit =
        { item -> onIssue(item, null) }

    // ---------- 媒体库权限申请 ----------
    private val requestPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingMediaLibraryCallback?.let {
                startMediaLibraryDetection(it)
                pendingMediaLibraryCallback = null
            }
        } else {
            // 拒绝授权是用户的选择，不提示；权限状态面板会持续显示该项未授权
            pendingMediaLibraryCallback = null
        }
    }

    // ---------- 拦截触摸事件，检测悬浮窗遮挡 ----------
    fun dispatchTouchEvent(ev: MotionEvent) {
        var obscured = (ev.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val partially =
                (ev.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
            obscured = obscured or partially
        }
        if (obscured != isTouchObscured) {
            isTouchObscured = obscured
            if (obscured && isBehaviorDetectionActive) {
                behaviorIssueCallback?.invoke(DetectionItems.FLOATING_WINDOW)
            }
        }
    }

    // ---------- 截屏检测 ----------
    fun startKeyPressDetection(onDetected: () -> Unit) {
        if (Auxiliary.KeyPressDetectionAvailable) {
            stopKeyPressDetection()
            val callback = Activity.ScreenCaptureCallback {
                onDetected()
            }
            screenCaptureCallback = callback
            activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
        }
    }

    fun stopKeyPressDetection() {
        screenCaptureCallback?.let {
            if (Auxiliary.KeyPressDetectionAvailable) {
                try {
                    activity.unregisterScreenCaptureCallback(it)
                } catch (_: Exception) { /* ignore */
                }
            }
            screenCaptureCallback = null
        }
    }

    // ---------- 录屏检测 ----------
    fun startScreenRecordingDetection(onDetected: () -> Unit) {
        if (Auxiliary.ScreenRecordingDetectionAvailable) {
            stopScreenRecordingDetection()
            val callback = Consumer<Int> { state ->
                // SCREEN_RECORDING_STATE_VISIBLE = 1，NOT_VISIBLE = 0
                Auxiliary.log("ScreenRecording: callback state=$state")
                if (state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE) {
                    onDetected()
                }
            }
            screenRecordingCallback = callback
            val windowManager = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try {
                // 注册返回当前状态(API 契约：初始状态不会自动回调，需自行消费)，
                // 覆盖"录屏进行中启动检测器"的场景
                val initState = windowManager.addScreenRecordingCallback(
                    activity.mainExecutor, callback
                )
                Auxiliary.log("ScreenRecording: registered, initState=$initState")
                callback.accept(initState)
            } catch (e: Exception) {
                // 个别 ROM 可能拦截/抛错，记录以判断注册是否真正成功
                Auxiliary.log("ScreenRecording: register failed: $e")
            }
        } else {
            Auxiliary.log("ScreenRecording: unavailable, sdk=${Build.VERSION.SDK_INT}")
        }
    }

    fun stopScreenRecordingDetection() {
        screenRecordingCallback?.let {
            if (Auxiliary.ScreenRecordingDetectionAvailable) {
                try {
                    val windowManager = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    windowManager.removeScreenRecordingCallback(it)
                    Auxiliary.log("ScreenRecording: unregistered")
                } catch (_: Exception) { /* ignore */
                }
            }
            screenRecordingCallback = null
        }
    }

    // ---------- 显示器归因(隐藏 API DisplayInfo，经 HiddenApiBypass 反射) ----------

    /**
     * 反射 DisplayManagerGlobal.getDisplayInfo(displayId) 获取显示器归因信息。
     * 服务端可见性与 getDisplays 同门(私有显示器本就不可见，无新增盲区)；
     * DisplayInfo 携带 type/flags/name 与虚拟显示器创建者 ownerPackageName/
     * ownerUid——凡本应用可见的显示器，其归因字段同样可读。
     */
    @SuppressLint("PrivateApi")
    private fun displayInfo(displayId: Int): Any? = runCatching {
        val cls = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val instance = cls.getMethod("getInstance").invoke(null)
        cls.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(instance, displayId)
    }.getOrNull()

    private fun infoInt(info: Any, field: String): Int? =
        runCatching { info.javaClass.getField(field).get(info) as? Int }.getOrNull()

    private fun infoString(info: Any, field: String): String? =
        runCatching { info.javaClass.getField(field).get(info) as? String }.getOrNull()

    /** 显示器归因描述(卡片详情行)：类型 + 虚拟显示器创建者包名 + 安全模式标记 */
    private fun describeDisplayInfo(info: Any): String {
        val secureSuffix =
            if (((infoInt(info, "flags") ?: 0) and DISPLAY_FLAG_SECURE) != 0) {
                activity.getString(R.string.display_secure_suffix)
            } else {
                ""
            }
        return when (infoInt(info, "type")) {
            DISPLAY_TYPE_WIFI -> activity.getString(R.string.display_type_wifi) + secureSuffix
            DISPLAY_TYPE_EXTERNAL -> activity.getString(R.string.display_type_external)
            DISPLAY_TYPE_OVERLAY -> activity.getString(R.string.display_type_overlay)
            DISPLAY_TYPE_VIRTUAL -> {
                val owner = infoString(info, "ownerPackageName")
                val name = infoString(info, "name")
                val subject = when {
                    !owner.isNullOrBlank() -> owner
                    !name.isNullOrBlank() -> name
                    else -> null
                }
                (if (subject != null) {
                    activity.getString(R.string.virtual_display_detail, subject)
                } else {
                    activity.getString(R.string.display_type_virtual)
                }) + secureSuffix
            }

            else -> activity.getString(R.string.display_type_unknown)
        }
    }

    /**
     * 对单个显示器归因上报：item 承接"显示器存在"语义(镜像/投影)。
     * 不做录屏特征词推断——各录屏/投屏软件创建虚拟显示器的命名与方式
     * 各不相同，特征词匹配必然漏报；统一上报"存在虚拟显示器 + 创建者
     * 包名"的事实，是否为录屏由用户按包名自行甄别。
     * 模拟辅助显示器(OVERLAY)归环境检测项 OVERLAY_DISPLAY，不在此上报。
     * DisplayInfo 反射不可用时退回旧行为(存在可见非默认显示器即上报，无详情)。
     */
    private fun reportDisplay(
        displayId: Int,
        item: DetectionItems,
        onIssue: (DetectionItems, String?) -> Unit
    ) {
        val info = displayInfo(displayId)
        if (info == null) {
            val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            if (Auxiliary.hasNonDefaultDisplay(dm.displays)) {
                onIssue(item, null)
            }
            return
        }
        val type = infoInt(info, "type") ?: return
        if (type == DISPLAY_TYPE_INTERNAL || type == DISPLAY_TYPE_OVERLAY) return
        onIssue(item, describeDisplayInfo(info))
    }

    /** 扫描当前全部可见显示器并对非默认者归因上报(注册时冷启动扫描) */
    private fun scanVisibleDisplays(item: DetectionItems, onIssue: (DetectionItems, String?) -> Unit) {
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.displays
            .filter { it.displayId != Display.DEFAULT_DISPLAY }
            .forEach { reportDisplay(it.displayId, item, onIssue) }
    }

    // ---------- 公共的 DisplayListener 创建逻辑 ----------
    private fun createDisplayListener(
        item: DetectionItems,
        onIssue: (DetectionItems, String?) -> Unit
    ): DisplayManager.DisplayListener {
        return object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                // 对照日志：三方录屏/投屏会创建虚拟显示器，标记时间点
                Auxiliary.log("DisplayListener: onDisplayAdded displayId=$displayId")
                reportDisplay(displayId, item, onIssue)
            }

            override fun onDisplayRemoved(displayId: Int) { /* 可选 */
            }

            override fun onDisplayChanged(displayId: Int) { /* 可选 */
            }
        }
    }

    // ---------- 投屏/镜像检测 ----------
    fun startMirroringDetection(onIssue: (DetectionItems, String?) -> Unit) {
        stopMirroringDetection()
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = createDisplayListener(DetectionItems.SCREEN_MIRRORING, onIssue)
        displayListener = listener
        dm.registerDisplayListener(listener, null)
        scanVisibleDisplays(DetectionItems.SCREEN_MIRRORING, onIssue)
        // Miracast(WFD) 精确状态：注册时快照 + 状态变化隐藏广播(服务端无权限校验)
        reportWifiDisplayStatus(onIssue)
        registerWifiDisplayStatusReceiver(onIssue)
    }

    fun stopMirroringDetection() {
        displayListener?.let {
            try {
                (activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it)
            } catch (_: Exception) { /* ignore */
            }
            displayListener = null
        }
        wifiDisplayStatusReceiver?.let {
            runCatching { activity.unregisterReceiver(it) }
            wifiDisplayStatusReceiver = null
        }
    }

    // ---------- Miracast(WFD) 投屏状态 ----------
    /**
     * 反射 DisplayManager.getWifiDisplayStatus(隐藏 API，服务端明示无需权限)，
     * 对端处于连接中/已连接时上报 SCREEN_MIRRORING，详情携带对端设备名。
     */
    private fun reportWifiDisplayStatus(onIssue: (DetectionItems, String?) -> Unit) {
        val status = runCatching {
            val dm = activity.getSystemService(Context.DISPLAY_SERVICE)
            dm.javaClass.getMethod("getWifiDisplayStatus").invoke(dm)
        }.getOrNull() ?: return
        val state = runCatching {
            status.javaClass.getMethod("getActiveDisplayState").invoke(status) as? Int
        }.getOrNull() ?: return
        if (state == WFD_STATE_CONNECTING || state == WFD_STATE_CONNECTED) {
            val detail = activeWifiDisplayName(status)?.let {
                activity.getString(R.string.wifi_display_detail, it)
            }
            onIssue(DetectionItems.SCREEN_MIRRORING, detail)
        }
    }

    /** WFD 对端设备名(优先用户可读名，反射 WifiDisplay) */
    private fun activeWifiDisplayName(status: Any): String? {
        val display = runCatching {
            status.javaClass.getMethod("getActiveDisplay").invoke(status)
        }.getOrNull() ?: return null
        return runCatching {
            display.javaClass.getMethod("getFriendlyDisplayName").invoke(display) as? String
        }.getOrNull() ?: runCatching {
            display.javaClass.getMethod("getDeviceName").invoke(display) as? String
        }.getOrNull()
    }

    /** 注册 WFD 状态变化广播(受保护系统广播，普通应用可收) */
    private fun registerWifiDisplayStatusReceiver(onIssue: (DetectionItems, String?) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                reportWifiDisplayStatus(onIssue)
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                activity,
                receiver,
                IntentFilter(ACTION_WIFI_DISPLAY_STATUS_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            wifiDisplayStatusReceiver = receiver
        }
    }

    // ---------- MediaProjection 检测 ----------
    fun startMediaProjectionDetection(onIssue: (DetectionItems, String?) -> Unit) {
        stopMediaProjectionDetection()
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = createDisplayListener(DetectionItems.MEDIA_PROJECTION, onIssue)
        mediaProjectionListener = listener
        dm.registerDisplayListener(listener, null)
        scanVisibleDisplays(DetectionItems.MEDIA_PROJECTION, onIssue)
    }

    fun stopMediaProjectionDetection() {
        mediaProjectionListener?.let {
            try {
                (activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it)
            } catch (_: Exception) { /* ignore */
            }
            mediaProjectionListener = null
        }
    }

    // ---------- MediaRouter 检测 ----------
    fun startMediaRouterDetection(onConnected: () -> Unit) {
        stopMediaRouterDetection()
        mediaRouter = MediaRouter.getInstance(activity)
        val selector = MediaRouteSelector.Builder()
            .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
            .build()
        val callback = object : MediaRouter.Callback() {
            @Deprecated("Deprecated in Java")
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo) {
                if (!route.isDefault) onConnected()
            }

            @Deprecated("Deprecated in Java")
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo) { /* 可选 */
            }

            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                if (!route.isDefault) onConnected()
            }

            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) { /* 可选 */
            }

            override fun onRouteChanged(
                router: MediaRouter,
                route: MediaRouter.RouteInfo
            ) { /* 可选 */
            }
        }
        mediaRouterCallback = callback
        mediaRouter?.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        if (mediaRouter?.routes?.any { !it.isDefault } == true) {
            onConnected()
        }
    }

    fun stopMediaRouterDetection() {
        mediaRouterCallback?.let {
            mediaRouter?.removeCallback(it)
        }
        mediaRouterCallback = null
        mediaRouter = null
    }

    // ---------- 媒体库监听 ----------
    fun startMediaLibraryDetection(onDetected: () -> Unit) {
        if (!Auxiliary.hasStoragePermission(activity)) {
            pendingMediaLibraryCallback = onDetected
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            // 系统权限对话框以独立 Activity 覆盖本应用(触发 onPause)，
            // 属自发导航，期间切屏检测静默(见 isSelfNavigation 注释)
            isSelfNavigation = true
            requestPermissionLauncher.launch(permission)
            return
        }
        stopMediaLibraryDetection()
        val contentResolver = activity.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Auxiliary.checkForScreenshot(contentResolver, onDetected)
            }
        }
        mediaLibraryObserver = observer
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
        // 冷启动回查：ContentObserver 只覆盖注册后的变化，注册时补查一次
        // (checkForScreenshot 自带 15s 回看窗口)，覆盖"打开本应用前 ≤15s 的截图"
        Auxiliary.checkForScreenshot(contentResolver, onDetected)
    }

    fun stopMediaLibraryDetection() {
        mediaLibraryObserver?.let {
            activity.contentResolver.unregisterContentObserver(it)
            mediaLibraryObserver = null
        }
    }

    // ---------- FileObserver 检测 ----------
    fun startFileChangesDetection(onDetected: () -> Unit) {
        stopFileChangesDetection()
        val screenshotsDir = File(
            Environment.getExternalStorageDirectory(),
            "Pictures/Screenshots"
        )
        if (!screenshotsDir.exists()) {
            return
        }
        val observer = object : FileObserver(screenshotsDir, CREATE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        val now = System.currentTimeMillis()
                        if (now - lastFileObserverTime > 2000) {
                            lastFileObserverTime = now
                            onDetected()
                        }
                    }, 300)
                }
            }
        }
        fileObserver = observer
        observer.startWatching()
        // 冷启动回查：FileObserver 只覆盖注册后的事件，扫描目录中回看窗口内
        // 落盘的截图文件(与媒体库截图判定窗口对齐)
        val lookback = System.currentTimeMillis() - FILE_LOOKBACK_MS
        if (screenshotsDir.listFiles()?.any { it.lastModified() >= lookback } == true) {
            onDetected()
        }
    }

    fun stopFileChangesDetection() {
        fileObserver?.stopWatching()
        fileObserver = null
    }

    // ---------- 环境安全检测(ADB/开发者选项/无障碍，分别上报) ----------
    fun startEnvironmentDetection(onIssue: (DetectionItems, String?) -> Unit) {
        stopEnvironmentDetection()
        environmentIssueCallback = onIssue

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                checkEnvironmentState()
            }
        }
        environmentObserver = observer
        activity.contentResolver.registerContentObserver(
            Settings.Global.CONTENT_URI,
            true,
            observer
        )

        val am = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val listener = AccessibilityManager.AccessibilityStateChangeListener {
            checkEnvironmentState()
        }
        accessibilityListener = listener
        am.addAccessibilityStateChangeListener(listener)

        // 无障碍状态轮询(200ms)：已启用服务集合存于 Settings.Secure，
        // Global 观察者与全局开关监听均不覆盖"服务集合变化"；此外开关操作
        // 必然发生在本应用后台(需前往设置)，事件回调对后台应用不可靠。
        // getEnabledAccessibilityServiceList 为实时 Binder 查询(无客户端
        // 缓存，经 AOSP 源码核实)，轮询保证前后台状态即时同步(在设置里
        // 开关无障碍后返回即已刷新)
        environmentPollingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(ENVIRONMENT_POLL_INTERVAL_MS.milliseconds)
                val issues = runCatching { Auxiliary.environmentIssues(activity) }
                    .getOrNull() ?: continue
                withContext(Dispatchers.Main) {
                    reportEnvironmentState(issues)
                }
            }
        }
        checkEnvironmentState()
    }

    /** 注入环境项状态清除回调(无障碍项实时刷新：服务全部停用后移除卡片) */
    fun setEnvironmentClearCallback(callback: ((DetectionItems) -> Unit)?) {
        environmentClearCallback = callback
    }

    fun stopEnvironmentDetection() {
        environmentObserver?.let {
            activity.contentResolver.unregisterContentObserver(it)
            environmentObserver = null
        }
        accessibilityListener?.let {
            val am = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.removeAccessibilityStateChangeListener(it)
            accessibilityListener = null
        }
        environmentPollingJob?.cancel()
        environmentPollingJob = null
        environmentIssueCallback = null
        // environmentClearCallback 有意保留：需跨停止/重启周期存活
        // ("重新检测"会 stop+start)，由页面生命周期负责注入
    }

    private fun checkEnvironmentState() {
        reportEnvironmentState(Auxiliary.environmentIssues(activity))
    }

    /**
     * 上报环境状态。无障碍项为实时状态(非粘性)：已无启用的无障碍服务时
     * 通过清除回调移除卡片；其余环境项仍为粘性(安全证据语义，防止关闭
     * 即抹除痕迹)。
     */
    private fun reportEnvironmentState(issues: List<Pair<DetectionItems, String?>>) {
        environmentIssueCallback?.let { cb ->
            issues.forEach { (item, detail) -> cb(item, detail) }
        }
        if (issues.none { it.first == DetectionItems.ACCESSIBILITY_SERVICE }) {
            environmentClearCallback?.invoke(DetectionItems.ACCESSIBILITY_SERVICE)
        }
    }

    // ---------- 可疑行为检测(切屏/小窗/画中画/悬浮窗，分别上报) ----------
    fun startBehaviorDetection(onIssue: (DetectionItems, String?) -> Unit) {
        stopBehaviorDetection()
        isBehaviorDetectionActive = true
        behaviorIssueCallback = envAdapter(onIssue)
        checkBehaviorState()
        behaviorPollingJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                delay(Auxiliary.BEHAVIOR_POLL_INTERVAL.milliseconds)
                checkBehaviorState()
            }
        }
    }

    fun stopBehaviorDetection() {
        isBehaviorDetectionActive = false
        behaviorIssueCallback = null
        behaviorPollingJob?.cancel()
        behaviorPollingJob = null
    }

    /**
     * 逐项检查行为异常并上报。
     * 结果是粘性的(由 UI 层持久显示)，因此重复上报同一项是无害的幂等操作；
     * 轮询保证初始状态(如启动时已处于分屏)也能被检出。
     */
    private fun checkBehaviorState() {
        if (!isBehaviorDetectionActive) return
        val callback = behaviorIssueCallback ?: return
        if (isInBackground && !isSelfNavigation) {
            callback(DetectionItems.SCREEN_SWITCH)
        }
        if (activity.isInMultiWindowMode) {
            callback(DetectionItems.MULTI_WINDOW)
        }
        // 窗口化形态探测：与 isInMultiWindowMode 是两个独立标志、不同代码
        // 路径——部分 ROM(实测 ColorOS 16)的小窗不置 multiWindow 标志但
        // windowingMode 照常返回 FREEFORM/MULTI_WINDOW，补上该盲区。
        // Configuration.windowConfiguration 为 API 30 公开字段，但 SDK 37.1
        // 起该类移出公开 stub，改用反射读取(运行时类恒存在)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (currentWindowingMode()) {
                WINDOWING_MODE_FREEFORM,
                WINDOWING_MODE_MULTI_WINDOW -> {
                    callback(DetectionItems.MULTI_WINDOW)
                }
                // PINNED 即画中画，isInPictureInPictureMode 已覆盖
                else -> Unit
            }
        }
        if (activity.isInPictureInPictureMode) {
            callback(DetectionItems.PICTURE_IN_PICTURE)
        }
        if (isTouchObscured) {
            callback(DetectionItems.FLOATING_WINDOW)
        }
    }

    /** 由 Activity 的多窗/画中画模式变化回调转发 */
    fun onMultiWindowModeChanged() {
        checkBehaviorState()
    }

    // ---------- 窗口化形态反射读取(见 checkBehaviorState 内注释) ----------

    private companion object {
        /** android.app.WindowConfiguration.WINDOWING_MODE_* 常量值(API 30 公开，SDK 37 起移出公开 stub) */
        private const val WINDOWING_MODE_FREEFORM = 5
        private const val WINDOWING_MODE_MULTI_WINDOW = 6

        // ---------- android.view.Display.TYPE_* 常量值(公开值，Android 10-17 稳定) ----------
        private const val DISPLAY_TYPE_INTERNAL = 1
        private const val DISPLAY_TYPE_EXTERNAL = 2
        private const val DISPLAY_TYPE_WIFI = 3
        private const val DISPLAY_TYPE_OVERLAY = 4
        private const val DISPLAY_TYPE_VIRTUAL = 5

        /** DisplayInfo.flags: 创建者以安全模式创建(录屏类应用常见) */
        private const val DISPLAY_FLAG_SECURE = 1 shl 1

        /** WifiDisplayStatus.getActiveDisplayState 返回值(公开值，API 17 起稳定) */
        private const val WFD_STATE_CONNECTING = 1
        private const val WFD_STATE_CONNECTED = 2

        /** 隐藏广播：WFD 状态变化(DisplayManager.ACTION_WIFI_DISPLAY_STATUS_CHANGED) */
        private const val ACTION_WIFI_DISPLAY_STATUS_CHANGED =
            "android.hardware.display.action.WIFI_DISPLAY_STATUS_CHANGED"

        /** 隐藏 AppOps 字符串：投屏持久授权(AppOpsManager.OPSTR_PROJECT_MEDIA) */
        private const val OPSTR_PROJECT_MEDIA = "android:project_media"

        /** 隐藏 AppOps 字符串：麦克风运行时权限(RECORD_AUDIO 对应 op) */
        private const val OPSTR_RECORD_AUDIO = "android:record_audio"

        /** 录音来源推测的回看窗口(长窗口配合录音权限双条件过滤防误报) */
        private const val AUDIO_SUSPECT_LOOKBACK_MS = 5 * 60_000L

        /** 可信呈现 bootstrap 超时：正常无遮挡启动 ~0.5s 内必收到 true 回调(服务端 375ms 重算 + 250ms 稳定阈值)，留足余量防首帧卡顿误报 */
        private const val TRUSTED_PRESENTATION_BOOTSTRAP_MS = 3_000L

        /** 文件监听冷启动回看窗口(与媒体库截图判定窗口 Auxiliary.SCREENSHOT_TIME_THRESHOLD 对齐) */
        private const val FILE_LOOKBACK_MS = 15_000L

        /** 环境状态轮询间隔(无障碍实时刷新：在设置中开关后返回即已同步) */
        private const val ENVIRONMENT_POLL_INTERVAL_MS = 200L

        /** SystemUI 包名(排除其瞬态 UI 对录音归因的干扰) */
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }

    /** 反射读取当前窗口化形态，失败返回 0(FULLSCREEN，即不触发任何上报) */
    private fun currentWindowingMode(): Int = runCatching {
        val configField =
            android.content.res.Configuration::class.java.getField("windowConfiguration")
        val windowConfig = configField.get(activity.resources.configuration)
        windowConfig.javaClass.getMethod("getWindowingMode").invoke(windowConfig) as Int
    }.getOrDefault(0)

    fun onPictureInPictureModeChanged() {
        checkBehaviorState()
    }

    // ---------- ScreenshotFaker检测 ----------
    fun startScreenshotFakerDetection(onDetected: () -> Unit) {
        stopScreenshotFakerDetection()
        screenshotFakerCheckJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val present = Auxiliary.isScreenshotFakerPresent(activity)
                if (present) {
                    withContext(Dispatchers.Main) {
                        onDetected()
                    }
                }
                delay(5000.milliseconds)
            }
        }
    }

    fun stopScreenshotFakerDetection() {
        screenshotFakerCheckJob?.cancel()
        screenshotFakerCheckJob = null
    }

    // ---------- 外接显示器检测(桌面模式信号) ----------

    /**
     * 注册 DisplayListener，按 DisplayInfo.type 归因：仅 TYPE_EXTERNAL(有线外接)
     * 计入本检测项，虚拟/Miracast/模拟显示器分别由投影/镜像与环境检测项覆盖；
     * 反射不可用时退回"显示器数量 > 1"判定(旧行为)。注册时先扫既有显示器，
     * 覆盖冷启动时已接入的场景。
     */
    fun startExternalDisplayDetection(onIssue: (DetectionItems, String?) -> Unit) {
        stopExternalDisplayDetection()
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.displays
            .filter { it.displayId != Display.DEFAULT_DISPLAY }
            .forEach { reportExternalDisplay(it.displayId, onIssue) }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                reportExternalDisplay(displayId, onIssue)
            }

            override fun onDisplayChanged(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        externalDisplayListener = listener
    }

    private fun reportExternalDisplay(displayId: Int, onIssue: (DetectionItems, String?) -> Unit) {
        val info = displayInfo(displayId)
        if (info == null) {
            // 反射不可用：退回数量>1 判定(旧行为)
            val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            if (dm.displays.size > 1) {
                onIssue(DetectionItems.EXTERNAL_DISPLAY, null)
            }
            return
        }
        if (infoInt(info, "type") == DISPLAY_TYPE_EXTERNAL) {
            onIssue(DetectionItems.EXTERNAL_DISPLAY, describeDisplayInfo(info))
        }
    }

    fun stopExternalDisplayDetection() {
        externalDisplayListener?.let {
            runCatching {
                (activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                    .unregisterDisplayListener(it)
            }
        }
        externalDisplayListener = null
    }

    // ---------- 免询问投屏授权检测(隐藏 AppOps 字符串 android:project_media) ----------

    /**
     * 枚举已安装应用，查询 project_media 的 AppOps 模式：MODE_ALLOWED 表示该
     * 应用持有免用户询问直接投屏的持久授权(MediaProjection 复用授权流程写入)。
     * checkOpNoThrow 服务端无越包校验(经 AOSP 源码核实，getPackagesForOps 等
     * 统计接口才会被 GET_APP_OPS_STATS 拦截)；全量包枚举依赖 QUERY_ALL_PACKAGES。
     * 语义为"能力面"而非进行时，故低频轮询(15s)。
     */
    fun startProjectionConsentDetection(onIssue: (DetectionItems, String?) -> Unit) {
        stopProjectionConsentDetection()
        projectionConsentJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val granted = queryProjectionConsent()
                if (granted.isNotEmpty()) {
                    // 详情 = 完整数量 + 最多 5 个包名(与无障碍详情同格式)
                    val detail = activity.getString(
                        R.string.projection_consent_detail,
                        granted.size,
                        granted.take(5).joinToString(", ")
                    )
                    withContext(Dispatchers.Main) {
                        onIssue(DetectionItems.MEDIA_PROJECTION_CONSENT, detail)
                    }
                }
                delay(15_000.milliseconds)
            }
        }
    }

    fun stopProjectionConsentDetection() {
        projectionConsentJob?.cancel()
        projectionConsentJob = null
    }

    private fun queryProjectionConsent(): List<String> = runCatching {
        val appOps = activity.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        activity.packageManager.getInstalledPackages(0)
            .asSequence()
            .mapNotNull { it.applicationInfo }
            .filter { it.packageName != activity.packageName }
            .distinctBy { it.uid }
            .filter {
                checkOpNoThrow(appOps, OPSTR_PROJECT_MEDIA, it.uid, it.packageName) ==
                        AppOpsManager.MODE_ALLOWED
            }
            .map { it.packageName }
            .toList()
    }.getOrDefault(emptyList())

    /**
     * AppOps.checkOpNoThrow(String,int,String) 反射调用(API 30+ 公开，
     * API 29 名为 unsafeCheckOpNoThrow；失败返回 MODE_ERRORED 即不匹配)
     */
    private fun checkOpNoThrow(
        appOps: AppOpsManager,
        op: String,
        uid: Int,
        packageName: String
    ): Int = runCatching {
        val cls = appOps.javaClass
        val signature = arrayOf(String::class.java, Integer.TYPE, String::class.java)
        val method = runCatching { cls.getMethod("checkOpNoThrow", *signature) }
            .getOrElse { cls.getMethod("unsafeCheckOpNoThrow", *signature) }
        method.invoke(appOps, op, uid, packageName) as Int
    }.getOrDefault(AppOpsManager.MODE_ERRORED)

    // ---------- 设备录音活动检测 ----------

    /**
     * AudioManager.getActiveRecordingConfigurations(公开 API，无需权限)：设备级
     * 活跃录音配置，跨应用可见——AOSP 对普通应用返回匿名化副本(录音存在性
     * 可见，来源 uid/包名被剥离)，无法精确归因到应用。详情 = 音源 + 推测来源：
     * 以"回看窗口内最近前台化的其他应用 × 持有麦克风权限"交叉推测嫌疑人
     * (录音通常由用户在录音应用前台时启动，随后切到本应用观察；后台偷录
     * 场景推测可能指向错误对象，故明确标注"推测")。带麦克风音轨的三方录屏
     * 会被本项覆盖。事件回调 + 注册时快照。
     */
    fun startAudioRecordingDetection(onIssue: (DetectionItems, String?) -> Unit) {
        stopAudioRecordingDetection()
        val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                reportAudioRecording(configs.orEmpty(), onIssue)
            }
        }
        am.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
        audioRecordingCallback = callback
        val snapshot = runCatching { am.activeRecordingConfigurations }
            .getOrDefault(emptyList())
        reportAudioRecording(snapshot, onIssue)
    }

    fun stopAudioRecordingDetection() {
        audioRecordingCallback?.let {
            runCatching {
                (activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .unregisterAudioRecordingCallback(it)
            }
        }
        audioRecordingCallback = null
    }

    private fun reportAudioRecording(
        configs: List<AudioRecordingConfiguration>,
        onIssue: (DetectionItems, String?) -> Unit
    ) {
        if (configs.isEmpty()) return
        val source = configs.firstOrNull()?.clientAudioSource
            ?.let { audioSourceName(it) }
        val suspect = suspectRecordingPackage()
        val detail = when {
            source != null && suspect != null ->
                activity.getString(R.string.audio_recording_detail_suspect, source, suspect)

            source != null ->
                activity.getString(R.string.audio_recording_detail, source)

            else -> suspect?.let {
                activity.getString(R.string.audio_recording_suspect_only, it)
            }
        }
        onIssue(DetectionItems.AUDIO_RECORDING, detail)
    }

    /**
     * 推测录音来源应用：AOSP 匿名化剥离了录音者身份，精确归因无应用层路径
     * (getClientUid/getClientPackageName 为 @SystemApi + MODIFY_AUDIO_ROUTING
     * 签名权限；getOpsForPackage 的 op 运行状态查询也被 GET_APP_OPS_STATS
     * 拦截，经 AOSP 源码核实)。推测链(按优先级)：
     * 1. 排除系统流程性应用(桌面/系统设置/权限对话框宿主/SystemUI，包名
     *    动态解析)——它们夹在录音应用与检测器之间被"使用"，但不可能是
     *    录音者(实测 ColorOS 的系统设置持有 RECORD_AUDIO 权限，仅按麦克风
     *    能力过滤会在从设置返回后误中)；
     * 2. 优先取回看窗口内有前台服务启动事件的候选——后台录音必须由前台
     *    服务承载(API 34+ 强制麦克风前台服务类型)，录音经由服务启动的
     *    时刻以 FOREGROUND_SERVICE_START 事件写入，流程性到访没有该事件；
     * 3. 其余按最近使用排序，取首个具备麦克风能力的应用(按序跳过无麦克风
     *    能力的夹层应用)。
     * 任一环节不可用则返回 null(退回仅音源)。
     */
    private fun suspectRecordingPackage(): String? {
        val excluded = systemFlowPackages() + activity.packageName
        val candidates = queryRecentUsageCandidates().filter { it.pkg !in excluded }
        candidates.filter { it.fgsStarted }.firstOrNull { isMicCapable(it.pkg) }?.let { return it.pkg }
        return candidates.firstOrNull { isMicCapable(it.pkg) }?.pkg
    }

    /**
     * 系统流程性应用包名(动态解析，跨 ROM 稳定)：桌面启动器(CATEGORY_HOME)、
     * 系统设置主入口(ACTION_SETTINGS)、运行时权限对话框宿主(权限控制器，
     * API 29+)、SystemUI(硬编码，各 ROM 通用稳定)。这些应用的"使用"只代表
     * 用户经过了系统 UI，不代表录音。
     */
    private fun systemFlowPackages(): Set<String> = runCatching {
        val pm = activity.packageManager
        val pkgs = mutableSetOf(SYSTEM_UI_PACKAGE)
        // 权限控制器(API 29+ 公开方法，SDK 37.1 起移出公开 stub，反射调用)
        runCatching {
            pm.javaClass.getMethod("getPermissionControllerPackageName").invoke(pm) as? String
        }.getOrNull()?.let { pkgs.add(it) }
        pm.queryIntentActivities(
            Intent(Settings.ACTION_SETTINGS).addCategory(Intent.CATEGORY_DEFAULT), 0
        ).forEach { pkgs.add(it.activityInfo.packageName) }
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).forEach { pkgs.add(it.activityInfo.packageName) }
        pkgs
    }.getOrDefault(setOf(SYSTEM_UI_PACKAGE))

    /**
     * 应用是否具备录音能力：持有 RECORD_AUDIO 权限，且 record_audio AppOps
     * 非拒绝态。AppOps 优先读未评估的原始模式(unsafeCheckOpRawNoThrow，
     * API 33+)——评估模式会把"仅前台允许"(MODE_FOREGROUND，ROM 隐私框架
     * 常见值)在应用处于后台时评估为 IGNORED，与"用户已拒绝"无法区分，导致
     * 正在录音的应用被误杀；原始模式能区分两者。原始模式不可用时退回评估
     * 模式(仅拒绝 IGNORED/ERRORED)。
     */
    private fun isMicCapable(pkg: String): Boolean = runCatching {
        val pm = activity.packageManager
        if (pm.checkPermission(
                Manifest.permission.RECORD_AUDIO, pkg
            ) != PackageManager.PERMISSION_GRANTED
        ) return false
        val appInfo = pm.getApplicationInfo(pkg, 0)
        val appOps = activity.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = checkOpRawNoThrow(appOps, OPSTR_RECORD_AUDIO, appInfo.uid, pkg)
            ?: checkOpNoThrow(appOps, OPSTR_RECORD_AUDIO, appInfo.uid, pkg)
        mode != AppOpsManager.MODE_IGNORED && mode != AppOpsManager.MODE_ERRORED
    }.getOrDefault(false)

    /**
     * AppOps 原始(未评估)模式查询：优先 checkOpRawNoThrow(新名)，
     * 退回 unsafeCheckOpRawNoThrow(API 33 起的旧名)，均不可用返回 null。
     */
    private fun checkOpRawNoThrow(
        appOps: AppOpsManager,
        op: String,
        uid: Int,
        packageName: String
    ): Int? = runCatching {
        val signature = arrayOf(String::class.java, Integer.TYPE, String::class.java)
        val method = runCatching { appOps.javaClass.getMethod("checkOpRawNoThrow", *signature) }
            .getOrElse { appOps.javaClass.getMethod("unsafeCheckOpRawNoThrow", *signature) }
        method.invoke(appOps, op, uid, packageName) as? Int
    }.getOrNull()

    /** 回看窗口内的使用候选：包名、最近使用时刻、是否存在前台服务启动事件 */
    private data class UsageCandidate(val pkg: String, val lastUsed: Long, val fgsStarted: Boolean)

    /**
     * 查询回看窗口内被使用的其他应用候选，按最近使用时间降序(排除自身与
     * SystemUI 瞬态)。双源合并(与小窗归因同模式)：
     * - UsageEvents 事件流：Activity 前台化(MOVE_TO_FOREGROUND)与前台服务启动
     *   (FOREGROUND_SERVICE_START，实测 ColorOS 录音经由 RecorderService 前台
     *   服务承载，该事件比活动前台化更贴近"录音开始"时刻，且覆盖侧边栏/
     *   快捷开关等无界面启动路径)即时写入，并标记该候选存在前台服务；
     * - UsageStats 聚合值 lastTimeUsed：任意组件使用均计入的兜底。
     * 需"使用情况访问权"，未授予时两源皆空(推测不可用，仍正常上报音源)。
     */
    private fun queryRecentUsageCandidates(): List<UsageCandidate> = runCatching {
        val usm = activity.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val latest = HashMap<String, Long>()
        val fgsStarted = HashSet<String>()
        val events = usm.queryEvents(now - AUDIO_SUSPECT_LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName
            if (pkg == activity.packageName || pkg == SYSTEM_UI_PACKAGE) continue
            val interesting = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.FOREGROUND_SERVICE_START
            if (interesting && event.timeStamp > (latest[pkg] ?: 0L)) {
                latest[pkg] = event.timeStamp
            }
            if (event.eventType == UsageEvents.Event.FOREGROUND_SERVICE_START) {
                fgsStarted.add(pkg)
            }
        }
        usm.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            now - AUDIO_SUSPECT_LOOKBACK_MS,
            now
        ).orEmpty()
            .filter {
                it.packageName != activity.packageName &&
                        it.packageName != SYSTEM_UI_PACKAGE &&
                        it.lastTimeUsed > 0
            }
            .forEach {
                if (it.lastTimeUsed > (latest[it.packageName] ?: 0L)) {
                    latest[it.packageName] = it.lastTimeUsed
                }
            }
        latest.entries
            .sortedByDescending { it.value }
            .map { UsageCandidate(it.key, it.value, it.key in fgsStarted) }
            .take(10)
    }.getOrDefault(emptyList())

    /** 录音音源名(MediaRecorder.AudioSource 常量值，匿名化副本保留该字段) */
    private fun audioSourceName(source: Int): String = when (source) {
        0 -> "DEFAULT"
        1 -> "MIC"
        2 -> "VOICE_UPLINK"
        3 -> "VOICE_DOWNLINK"
        4 -> "VOICE_CALL"
        5 -> "CAMCORDER"
        6 -> "VOICE_RECOGNITION"
        7 -> "VOICE_COMMUNICATION"
        9 -> "UNPROCESSED"
        10 -> "VOICE_PERFORMANCE"
        else -> source.toString()
    }

    // ---------- 窗口反射检测(自由小窗/系统级悬浮窗，共用同一轮询) ----------
    /**
     * 启动窗口检测：按轮询间隔轮询 [WindowReflectionDetector]。
     * 检测器基于反射信号(焦点丢失/虚拟显示器/环境探测)，不使用无障碍服务；
     * 触摸遮挡信号由 [dispatchTouchEvent] 补充。
     */
    fun startWindowDetection(onIssue: (DetectionItems, String?) -> Unit) {
        windowDetectionRefCount++
        if (windowDetectionRefCount > 1 || windowPollingJob != null) {
            return
        }
        val detector = WindowReflectionDetector(activity)
        windowReflectionDetector = detector
        windowPollingJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                detector.check(onIssue)
                delay(WindowReflectionDetector.POLL_INTERVAL_MS.milliseconds)
            }
        }
        startTrustedPresentationDetection(onIssue)
    }

    fun stopWindowDetection() {
        if (windowDetectionRefCount > 0) windowDetectionRefCount--
        if (windowDetectionRefCount > 0) return
        teardownWindowDetection()
    }

    private fun teardownWindowDetection() {
        windowPollingJob?.cancel()
        windowPollingJob = null
        windowReflectionDetector = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            stopTrustedPresentationDetection()
        }
    }

    /**
     * 可信呈现监听(API 35+，见字段注释)。窗口需先完成 attach(有 windowToken)
     * 才能注册，故 post 到视图就绪后执行。
     *
     * 排除两类非"显示不完整"的情形：后台离场(isInBackground)、自家弹层
     * 遮挡(下拉菜单/Dialog，见 hasOwnOverlayingWindow——应用自身 UI 的
     * 正常行为)；其余翻转一律按本体语义上报。
     *
     * 冷启动兜底(bootstrap)：服务端(TrustedPresentationListenerController，
     * 经 AOSP 源码核实)只在状态"迁移"时回调——窗口从注册首帧起就被遮挡
     * (先开悬浮窗再打开本应用)时状态恒为 untrusted、无迁移、永不回调。
     * 正常无遮挡启动会在 ~0.5s 内收到 true 回调，超时(3s)仍未收到任何
     * 回调即判"启动即呈现不完整"上报。
     */
    private fun startTrustedPresentationDetection(onIssue: (DetectionItems, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        stopTrustedPresentationDetection()
        val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 阈值：99% 像素可见、不透明、持续 250ms 才算"可信呈现"——
        // 遮挡/移开的状态迁移都会回调，取 false 边沿上报
        val thresholds = android.window.TrustedPresentationThresholds(1.0f, 0.99f, 250)
        trustedPresentationCallbackSeen = false
        val consumer = Consumer<Boolean> { trusted ->
            trustedPresentationCallbackSeen = true
            if (!trusted && !isInBackground &&
                windowReflectionDetector?.hasOwnOverlayingWindow() != true
            ) {
                onIssue(DetectionItems.WINDOW_NOT_FULLY_PRESENTED, null)
            }
        }
        val generation = trustedPresentationGeneration
        activity.window.decorView.post {
            // stop 可能先于本 post 执行(重新检测/页面销毁)，代数不符即放弃注册
            if (generation != trustedPresentationGeneration) return@post
            val token = activity.window.decorView.windowToken ?: return@post
            runCatching {
                wm.registerTrustedPresentationListener(
                    token, thresholds, activity.mainExecutor, consumer
                )
                trustedPresentationConsumer = consumer
                scheduleTrustedPresentationBootstrap(onIssue)
            }
        }
    }

    /** 可信呈现冷启动兜底：注册后超时未收到任何回调 → 上报(见 start 方法文档) */
    private fun scheduleTrustedPresentationBootstrap(onIssue: (DetectionItems, String?) -> Unit) {
        val bootstrap = Runnable {
            trustedPresentationBootstrap = null
            if (!trustedPresentationCallbackSeen && !isInBackground &&
                windowReflectionDetector?.hasOwnOverlayingWindow() != true
            ) {
                onIssue(DetectionItems.WINDOW_NOT_FULLY_PRESENTED, null)
            }
        }
        trustedPresentationBootstrap = bootstrap
        activity.window.decorView.postDelayed(bootstrap, TRUSTED_PRESENTATION_BOOTSTRAP_MS)
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun stopTrustedPresentationDetection() {
        trustedPresentationGeneration++
        trustedPresentationBootstrap?.let {
            activity.window.decorView.removeCallbacks(it)
        }
        trustedPresentationBootstrap = null
        val consumer = trustedPresentationConsumer ?: return
        trustedPresentationConsumer = null
        val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching {
            wm.unregisterTrustedPresentationListener(consumer)
        }
    }

    /** 由 Activity 的 onWindowFocusChanged 转发(窗口焦点探测的精确信号) */
    fun onWindowFocusChanged(hasFocus: Boolean) {
        windowReflectionDetector?.onWindowFocusChanged(hasFocus)
    }

    /** 由 Activity 的 onTopResumedActivityChanged 转发(事件化焦点信号) */
    fun onTopResumedActivityChanged(isTopResumed: Boolean) {
        windowReflectionDetector?.onTopResumedActivityChanged(isTopResumed)
    }

    /**
     * 由 Activity 的 onStop 转发：完全不可见才视为切屏。仅 onPause 的情形
     * (系统浮层/ColorOS 隐私提示横幅/下拉通知栏/浮动权限询问框等部分遮挡)
     * 不计——此前按 onPause 判定，任何瞬时浮层都会误报切屏。
     */
    fun onActivityStopped() {
        isInBackground = true
        checkBehaviorState()
    }

    /** 标记即将发生的自发导航(权限面板引导跳转设置)，期间切屏不计 */
    fun markSelfNavigation() {
        isSelfNavigation = true
    }

    fun onActivityResumed() {
        isInBackground = false
        isSelfNavigation = false
    }

    /** 停止全部检测(由 Activity onDestroy 调用) */
    fun stopAll() {
        stopKeyPressDetection()
        stopScreenRecordingDetection()
        stopMirroringDetection()
        stopMediaProjectionDetection()
        stopMediaRouterDetection()
        stopMediaLibraryDetection()
        stopFileChangesDetection()
        stopBehaviorDetection()
        stopEnvironmentDetection()
        stopScreenshotFakerDetection()
        stopExternalDisplayDetection()
        stopProjectionConsentDetection()
        stopAudioRecordingDetection()
        windowDetectionRefCount = 0
        teardownWindowDetection()
    }
}
