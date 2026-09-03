package detect.screenshot.detection

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
import android.view.View
import androidx.lifecycle.Lifecycle
import detect.screenshot.Auxiliary
import detect.screenshot.MainActivity
import detect.screenshot.R
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 纯反射的窗口检测器(不使用无障碍服务，不需要 ROOT)。
 *
 * 检测项与信号一一对应，互不辅助：
 * - FOCUS_LOSS(焦点被抢占)：焦点探测 —— RESUMED+亮屏但窗口持续失焦
 * - FLOATING_WINDOW(悬浮窗)：焦点被抢但用量统计显示自己仍是顶层应用
 *   (抢焦点的是悬浮窗/系统浮层——状态栏面板、侧边栏展开等，而非应用切换)；
 *   触摸不可信信号(FLAG_WINDOW_IS_OBSCURED)在 DetectionFunctions
 *   .dispatchTouchEvent 中补充
 * - FREEFORM_WINDOW(自由小窗)：焦点被抢 + 用量统计显示顶层应用是其他
 *   应用且本应用仍 RESUMED——正常应用切换会先 onPause，能保持 RESUMED
 *   说明对方以窗口形式覆盖本应用(ROM 小窗/自由窗口)，详情携带包名。
 *   该信号跨 ROM 可靠(不依赖虚拟屏命名/multiWindow 标志)。
 *
 * 平台限制(经 AOSP 8.0/13/16 源码核实)：
 * 普通应用无法枚举系统全部窗口——IWindowManager 没有窗口枚举接口；
 * WindowInfosListener 需要 ACCESS_SURFACE_FLINGER；getTasks 被服务端
 * REAL_GET_TASKS 签名权限拦截。
 */
class WindowReflectionDetector(private val activity: MainActivity) {

    companion object {
        /** 轮询间隔 */
        const val POLL_INTERVAL_MS = 200L

        /** 连续失去焦点的轮询次数阈值(约0.6秒：200ms × 3) */
        const val FOCUS_LOSS_THRESHOLD = 3

        /** 从未获得过焦点时的更高阈值(约3秒)，排除启动瞬态的同时捕捉持续抢占 */
        const val FOCUS_LOSS_THRESHOLD_NEVER = 15
    }

    private var unfocusedPolls = 0
    /** 是否曾获得过窗口焦点(用于排除启动瞬态，见 [checkFocusProbe]) */
    private var focusEverGained = false

    /**
     * 本应用最后一次持有窗口焦点的墙钟时刻(与 UsageStats.lastTimeUsed 同口径)。
     * 用于小窗归因的新鲜度过滤：lastTimeUsed 只在组件 pause/resume 边界更新，
     * 小窗覆盖期间本应用不经历生命周期变化，自己的 lastTimeUsed 会冻结在小窗
     * 打开之前——关闭小窗后，用量统计的 topApp 仍停留在小窗应用，此后任何
     * 焦点丢失(如下拉状态栏)都会把旧包名再次误判为小窗。
     * 要求 topApp.lastTimeUsed 晚于本时刻(对方是在本应用失焦之后才被使用的)
     * 即可排除：关小窗时本应用重获焦点会刷新本时刻，旧包名因早于本时刻被排除。
     */
    private var lastFocusHeldMs = 0L

    init {
        applyHiddenApiExemption()
    }

    /**
     * 通过 HiddenApiBypass 豁免隐藏 API 限制(API 28+ 的非 SDK 接口限制会拦截
     * Display.getDisplayInfo()/WindowManagerGlobal.mViews 等反射调用)。
     * 豁免后普通反射即可访问灰名单/黑名单接口。
     */
    private fun applyHiddenApiExemption() {
        try {
            // "" 前缀匹配所有签名 → 全量豁免
            HiddenApiBypass.addHiddenApiExemptions("")
        } catch (_: Exception) {
            // 豁免失败：反射调用可能被拦截，各探测点已有降级路径
        }
    }

    /** 由 Activity.onWindowFocusChanged 转发，标记曾获得过焦点 */
    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) {
            focusEverGained = true
            unfocusedPolls = 0
            lastFocusHeldMs = System.currentTimeMillis()
        }
    }

    /** 轮询入口，由 DetectionFunctions 按轮询间隔调用 */
    fun check(onIssue: (DetectionItems, String?) -> Unit) {
        checkFocusProbe(onIssue)
    }

    // ---------- 焦点探测 ----------

    /**
     * RESUMED + 亮屏但窗口持续失去焦点 → 有其他窗口(系统级悬浮窗/其他应用
     * 的窗口/通知栏等)抢占了焦点。自有 Dialog 的情况通过"进程内任意窗口持有
     * 焦点"反射排除。
     *
     * 启动瞬态处理：Activity 刚启动时窗口焦点尚未就绪，故焦点计数仅从
     * "曾获得过焦点"开始；若从未获得过焦点(悬浮窗在启动前就抢走焦点)，
     * 采用更高的阈值以排除瞬态。
     *
     * 超过阈值后按 topApp(用量统计口径的顶层应用)二路归因：
     * - topApp == 自己：焦点被抢但顶层应用仍是自己——抢焦点的是悬浮窗/
     *   系统浮层(状态栏面板、侧边栏展开等)而非应用切换 → FLOATING_WINDOW；
     * - topApp == 其他应用(且新鲜度过滤通过)：本应用仍 RESUMED 而焦点在
     *   他人——正常应用切换必先 onPause 使本过滤失效，能到达此处说明对方
     *   以窗口形式覆盖本应用(ROM 小窗/自由窗口)，ColorOS 等既不设
     *   multiWindow 标志也不建特征命名虚拟屏，这是其唯一可靠的应用层信号
     *   → FREEFORM_WINDOW，详情携带顶层应用包名；
     * - topApp 未知(未授使用情况访问权)：无法归因，仅 FOCUS_LOSS。
     *
     * 小窗归因附新鲜度过滤(见 [lastFocusHeldMs])：topApp 的 lastTimeUsed
     * 必须晚于本应用最后一次持有焦点的时刻，否则视为陈旧数据不归因——
     * 关闭小窗后用量统计的 topApp 仍停留在小窗应用，若不加过滤，此后任何
     * 焦点丢失(如下拉状态栏)都会把旧包名再次误报为小窗。
     */
    private fun checkFocusProbe(onIssue: (DetectionItems, String?) -> Unit) {
        val interactive =
            (activity.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (activity.hasWindowFocus()) {
            focusEverGained = true
            unfocusedPolls = 0
            lastFocusHeldMs = System.currentTimeMillis()
            return
        }
        // 进程内任意窗口(含自有 Dialog)持有焦点 → 焦点在自家应用内
        if (ownViewRoots().any { it.hasWindowFocus() }) {
            unfocusedPolls = 0
            lastFocusHeldMs = System.currentTimeMillis()
            return
        }
        val resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (!resumed || !interactive || activity.isInPictureInPictureMode) {
            unfocusedPolls = 0
            return
        }
        unfocusedPolls++
        val threshold = if (focusEverGained) FOCUS_LOSS_THRESHOLD else FOCUS_LOSS_THRESHOLD_NEVER
        if (unfocusedPolls >= threshold) {
            // 独立信号：焦点被抢占
            onIssue(DetectionItems.FOCUS_LOSS, null)
            // 按 topApp 归因(见方法文档)
            val topApp = queryTopApp()
            when {
                topApp?.first == activity.packageName -> {
                    // 焦点被抢但用量统计的顶层应用仍是自己——抢焦点的是
                    // 悬浮窗/系统浮层(状态栏面板、侧边栏展开等)而非应用切换
                    onIssue(DetectionItems.FLOATING_WINDOW, null)
                }

                topApp != null && topApp.second > lastFocusHeldMs -> {
                    // 新鲜度过滤排除陈旧 topApp(关小窗后旧包名残留)
                    onIssue(
                        DetectionItems.FREEFORM_WINDOW,
                        activity.getString(R.string.freeform_window_detail, topApp.first)
                    )
                }

                else -> Unit
            }
        }
    }

    // ---------- 前台应用查询(顶层是否是自己) ----------

    /**
     * 查询当前前台应用(用量统计口径下最近使用的包及其最后使用时刻)，
     * 用于小窗归因及其新鲜度过滤。
     * 直接反射 IActivityTaskManager.getTasks() 会被服务端 REAL_GET_TASKS
     * 签名权限拦截(HiddenApiBypass 只绕客户端隐藏 API 限制，绕不过 Binder
     * 权限检查)，故采用 UsageStatsManager 公开 API。
     *
     * 需要用户在设置中授予"使用情况访问权"(PACKAGE_USAGE_STATS)；
     * 未授予时返回 null(小窗的该信号不可用，焦点检测不受影响)。
     */
    private fun queryTopApp(): Pair<String, Long>? {
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
                ?.let { it.packageName to it.lastTimeUsed }
        } catch (_: Exception) {
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
    } catch (_: Exception) {
        false
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
    } catch (_: Exception) {
        emptyList()
    }

    /** 保留的日志函数：需要调试时在此恢复输出 */
    @Suppress("unused")
    private fun log(msg: String) = Auxiliary.log("WindowReflect: $msg")
}
