package detect.screenshot.detection

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.view.Display
import android.view.View
import androidx.lifecycle.Lifecycle
import detect.screenshot.Auxiliary
import detect.screenshot.MainActivity
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 纯反射的窗口检测器(不使用无障碍服务，不需要 ROOT)。
 *
 * 检测项与信号一一对应，互不辅助：
 * - FOCUS_LOSS(焦点被抢占)：焦点探测 —— RESUMED+亮屏但窗口持续失焦
 * - FLOATING_WINDOW(悬浮窗)：焦点被抢但用量统计显示自己仍是顶层应用
 *   (抢焦点的是悬浮窗而非应用切换)；触摸不可信信号(FLAG_WINDOW_IS_OBSCURED)
 *   在 DetectionFunctions.dispatchTouchEvent 中
 * - FREEFORM_WINDOW(自由小窗)：虚拟显示器探测 —— 名称带 freeform 特征的虚拟屏
 *
 * 平台限制(经 AOSP 8.0/13/16 源码核实，日志中也会提示)：
 * 普通应用无法枚举系统全部窗口——IWindowManager 没有窗口枚举接口；
 * WindowInfosListener 需要 ACCESS_SURFACE_FLINGER；getTasks 被服务端
 * REAL_GET_TASKS 签名权限拦截。
 *
 * 所有探测点均输出详细日志(前缀 WindowReflect:)便于真机调试。
 */
class WindowReflectionDetector(private val activity: MainActivity) {

    companion object {
        /** 轮询间隔 */
        const val POLL_INTERVAL_MS = 1000L

        /** 连续失去焦点的轮询次数阈值(约3秒) */
        const val FOCUS_LOSS_THRESHOLD = 3

        /** 从未获得过焦点时的更高阈值(约15秒)，排除启动瞬态的同时捕捉持续抢占 */
        const val FOCUS_LOSS_THRESHOLD_NEVER = 15

        /** 自由小窗特征的虚拟显示器名称关键词(按反射读到的名称匹配) */
        private val FREEFORM_NAME_KEYWORDS = listOf(
            "freeform", "free form", "小窗", "mini", "multi-instance", "multi_instance"
        )
    }

    private var pollCount = 0
    private var unfocusedPolls = 0
    /** 是否曾获得过窗口焦点(用于排除启动瞬态，见 [checkFocusProbe]) */
    private var focusEverGained = false
    /** hidden API 豁免是否成功 */
    private var hiddenApiExempted = false
    private var knownDisplayIds = setOf(Display.DEFAULT_DISPLAY)
    private var introLogged = false

    init {
        applyHiddenApiExemption()
    }

    /**
     * 通过 HiddenApiBypass 豁免隐藏 API 限制(API 28+ 的非 SDK 接口限制会拦截
     * Display.getDisplayInfo()/WindowManagerGlobal.mViews 等反射调用)。
     * 豁免后普通反射即可访问灰名单/黑名单接口。
     */
    private fun applyHiddenApiExemption() {
        hiddenApiExempted = try {
            // "" 前缀匹配所有签名 → 全量豁免
            HiddenApiBypass.addHiddenApiExemptions("")
        } catch (e: Exception) {
            Auxiliary.log("WindowReflect: hidden api exemption failed: $e")
            false
        }
        Auxiliary.log("WindowReflect: hidden api exemption applied=$hiddenApiExempted")
    }

    /** 由 Activity.onWindowFocusChanged 转发，标记曾获得过焦点 */
    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) {
            focusEverGained = true
            unfocusedPolls = 0
        }
    }

    /** 轮询入口，由 DetectionFunctions 每秒调用一次 */
    fun check(onIssue: (DetectionItems) -> Unit) {
        pollCount++
        if (!introLogged) {
            introLogged = true
            logReflectionIntro()
        }
        checkFocusProbe(onIssue)
        checkDisplaysProbe(onIssue)
    }

    // ---------- 焦点探测 ----------

    /**
     * RESUMED + 亮屏但窗口持续失去焦点 → 有其他窗口(系统级悬浮窗/其他应用
     * 的窗口/通知栏等)抢占了焦点。自有 Dialog 的情况通过"进程内任意窗口持有
     * 焦点"反射排除。
     *
     * 启动瞬态处理：Activity 刚启动时窗口焦点尚未就绪(日志中 consecutive=1
     * 后立即恢复即此情况)，故焦点计数仅从"曾获得过焦点"开始；若从未获得过
     * 焦点(悬浮窗在启动前就抢走焦点)，采用更高的阈值以排除瞬态。
     */
    private fun checkFocusProbe(onIssue: (DetectionItems) -> Unit) {
        val interactive =
            (activity.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (activity.hasWindowFocus()) {
            focusEverGained = true
            if (unfocusedPolls != 0) {
                log("focus probe: window focus restored after $unfocusedPolls polls")
            }
            unfocusedPolls = 0
            return
        }
        // 进程内任意窗口(含自有 Dialog)持有焦点 → 焦点在自家应用内
        if (ownViewRoots().any { it.hasWindowFocus() }) {
            if (unfocusedPolls != 0) {
                log("focus probe: reset (own dialog/window holds focus)")
            }
            unfocusedPolls = 0
            return
        }
        val resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (!resumed || !interactive || activity.isInPictureInPictureMode) {
            unfocusedPolls = 0
            return
        }
        unfocusedPolls++
        val focusedView = try {
            activity.currentFocus?.javaClass?.simpleName
        } catch (_: Exception) {
            "unknown"
        }
        val threshold = if (focusEverGained) FOCUS_LOSS_THRESHOLD else FOCUS_LOSS_THRESHOLD_NEVER
        log(
            "focus probe: focus lost while resumed&interactive, consecutive=$unfocusedPolls " +
                    "focusEverGained=$focusEverGained threshold=$threshold " +
                    "multiWindow=${activity.isInMultiWindowMode} currentFocus=$focusedView " +
                    "(possible: 系统级悬浮窗/其他应用小窗/通知栏/系统弹窗)"
        )
        if (unfocusedPolls >= threshold) {
            // 独立信号：焦点被抢占
            log(
                "*** FOCUS_LOSS reported <- persistent focus loss " +
                        "($unfocusedPolls consecutive polls, focusEverGained=$focusEverGained, " +
                        "multiWindow=${activity.isInMultiWindowMode})"
            )
            onIssue(DetectionItems.FOCUS_LOSS)
            // 悬浮窗信号：焦点被抢但顶层应用仍是自己 → 抢焦点的是悬浮窗而非应用切换
            val topApp = queryTopApp()
            if (topApp == activity.packageName) {
                log(
                    "*** FLOATING_WINDOW reported <- focus taken while self is top app " +
                            "(topApp=$topApp)"
                )
                onIssue(DetectionItems.FLOATING_WINDOW)
            } else {
                log(
                    "focus probe: topApp=$topApp " +
                            "(another app on top or no usage stats access), " +
                            "floating window signal unavailable"
                )
            }
        }
    }

    // ---------- 前台应用查询(顶层是否是自己) ----------

    /**
     * 查询当前前台应用(用量统计口径下最近使用的包)，用于悬浮窗信号：
     * 焦点被抢但顶层应用仍是自己 → 抢焦点的是悬浮窗而非应用切换。
     * 直接反射 IActivityTaskManager.getTasks() 会被服务端 REAL_GET_TASKS
     * 签名权限拦截(HiddenApiBypass 只绕客户端隐藏 API 限制，绕不过 Binder
     * 权限检查)，故采用 UsageStatsManager 公开 API。
     *
     * 需要用户在设置中授予"使用情况访问权"(PACKAGE_USAGE_STATS)；
     * 未授予时返回 null(悬浮窗的该信号不可用，焦点检测不受影响)。
     */
    private fun queryTopApp(): String? {
        if (!hasUsageAccess()) {
            return null
        }
        return try {
            val usm =
                activity.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 15000, now)
                .filter { it.lastTimeUsed > 0 }
                .maxByOrNull { it.lastTimeUsed }
                ?.packageName
        } catch (e: Exception) {
            log("queryUsageStats failed: $e")
            null
        }
    }

    /** 是否已授予"使用情况访问权" */
    private fun hasUsageAccess(): Boolean = try {
        val appOps = activity.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            activity.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        log("usage access check failed: $e")
        false
    }

    // ---------- 虚拟显示器探测 ----------

    /**
     * 枚举所有显示器，反射读取 DisplayInfo 的名称。
     * 自由小窗在部分 ROM 上运行在独立虚拟显示器上(名称带 freeform 等特征)；
     * 其余虚拟显示器多为投屏/录屏(已由投屏检测项负责)，此处仅记录日志。
     */
    private fun checkDisplaysProbe(onIssue: (DetectionItems) -> Unit) {
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = dm.getDisplays()
        val ids = displays.map { it.displayId }.toSet()
        displays.forEach { display ->
            val id = display.displayId
            if (id == Display.DEFAULT_DISPLAY) return@forEach
            val name = displayName(display)
            val isNew = id !in knownDisplayIds
            log(
                "display[$id] name=\"$name\" ${if (isNew) "(NEW)" else ""} " +
                        "size=${display.width}x${display.height}"
            )
            if (FREEFORM_NAME_KEYWORDS.any { name.lowercase().contains(it) }) {
                log(
                    "*** FREEFORM_WINDOW reported <- freeform-like virtual display " +
                            "(id=$id name=\"$name\")"
                )
                onIssue(DetectionItems.FREEFORM_WINDOW)
            } else if (isNew) {
                log(
                    "display[$id] is not freeform-like, deferring to mirroring detection " +
                            "(name=\"$name\")"
                )
            }
        }
        knownDisplayIds = ids
    }

    // ---------- 反射工具 ----------

    /** 本进程内全部窗口的根 View(WindowManagerGlobal.mViews) */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    @Suppress("UNCHECKED_CAST")
    private fun ownViewRoots(): List<View> = try {
        val wmgClass = Class.forName("android.view.WindowManagerGlobal")
        val instance = wmgClass.getMethod("getInstance").invoke(null)
        (wmgClass.getDeclaredField("mViews").apply { isAccessible = true }
            .get(instance) as? List<View>) ?: emptyList()
    } catch (e: Exception) {
        log("WindowManagerGlobal reflection failed: $e")
        emptyList()
    }

    /** 反射 Display.getDisplayInfo() 读取隐藏的 DisplayInfo.name 字段 */
    private fun displayName(display: Display): String {
        return try {
            val info = Display::class.java.getMethod("getDisplayInfo").invoke(display)
            (info.javaClass.getField("name").get(info) as? String) ?: "null"
        } catch (e: Exception) {
            log(
                "getDisplayInfo reflection failed (exempted=$hiddenApiExempted): " +
                        "${e.javaClass.simpleName}: ${e.message}"
            )
            "unavailable(${e.javaClass.simpleName})"
        }
    }

    /** 首轮输出隐藏 API 可用性与平台限制说明，便于真机调试 */
    private fun logReflectionIntro() {
        log(
            "=== reflection window detector start " +
                    "(api=${Build.VERSION.SDK_INT} manufacturer=${Build.MANUFACTURER} model=${Build.MODEL}) ==="
        )
        val wmgOk = try {
            "ok(views=${ownViewRoots().size})"
        } catch (_: Exception) {
            "failed"
        }
        val displayInfoOk = try {
            Display::class.java.getMethod("getDisplayInfo")
            "ok"
        } catch (_: Exception) {
            "failed"
        }
        log("hidden api availability: WindowManagerGlobal=$wmgOk Display.getDisplayInfo=$displayInfoOk")
        log(
            "top app probe: usageStatsAccess=${hasUsageAccess()} " +
                    "(未授权时焦点丢失无法佐证顶层应用，可在设置-特殊应用权限-使用情况访问权中开启)"
        )
        log(
            "platform note: 普通应用无法枚举系统全部窗口(IWindowManager无枚举接口/" +
                    "WindowInfosListener需ACCESS_SURFACE_FLINGER/getTasks被REAL_GET_TASKS权限拦截)，" +
                    "本检测基于焦点/虚拟显示器/触摸遮挡降级信号"
        )
    }

    private fun log(msg: String) = Auxiliary.log("WindowReflect: $msg")
}
