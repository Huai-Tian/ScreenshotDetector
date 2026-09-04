package detect.screenshot.detection

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.view.View
import android.view.accessibility.AccessibilityWindowInfo
import androidx.lifecycle.Lifecycle
import detect.screenshot.Auxiliary
import detect.screenshot.MainActivity
import detect.screenshot.R
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 窗口检测器：反射信号为主，无障碍窗口快照为增强(可选，需用户开启本应用
 * 无障碍服务；未开启时各增强路径自动退回反射/用量统计路径，不需要 ROOT)。
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
 *   无障碍增强：窗口快照中当前持焦点的外部窗口即为覆盖者(事实归因，
 *   优先于用量统计推测，且不依赖"使用情况访问权")。
 *
 * 平台限制(经 AOSP 8.0/13/16 源码核实)：
 * 普通应用无法枚举系统全部窗口——IWindowManager 没有窗口枚举接口；
 * WindowInfosListener 需要 ACCESS_SURFACE_FLINGER；getTasks 被服务端
 * REAL_GET_TASKS 签名权限拦截。无障碍 getWindows 是唯一的应用层
 * 跨应用窗口枚举路径(本检测器的增强来源)。
 */
class WindowReflectionDetector(private val activity: MainActivity) {

    companion object {
        /** 轮询间隔 */
        const val POLL_INTERVAL_MS = 200L

        /** 连续失去焦点的轮询次数阈值(约0.6秒：200ms × 3) */
        const val FOCUS_LOSS_THRESHOLD = 3

        /** 从未获得过焦点时的更高阈值(约3秒)，排除启动瞬态的同时捕捉持续抢占 */
        const val FOCUS_LOSS_THRESHOLD_NEVER = 15

        /** 归因回看窗口(事件流与聚合值同窗口)，新鲜度过滤保证长窗口安全 */
        private const val ATTRIBUTION_LOOKBACK_MS = 60_000L

        /**
         * SystemUI 包名(各 ROM 通用稳定)：其瞬态 UI(侧边栏手势/最近任务面板等)
         * 不作为小窗归因目标——事件流里这些瞬态可能晚于小窗内应用被记录
         */
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
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
        // 新鲜度基线兜底：从未获得焦点时 lastFocusHeldMs 为 0 会让任何近期
        // 使用过的其他应用都通过新鲜度过滤(如冷启动前 launcher 的使用记录
        // 会被误归因为小窗)，以检测器创建时刻为初始基线
        lastFocusHeldMs = System.currentTimeMillis()
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

    /**
     * 由 Activity.onTopResumedActivityChanged 转发(API 29+ 事件信号)：
     * top resumed 即本 Activity 是全局顶层，等价持有窗口焦点。
     * 与轮询互补——事件即时到达无轮询延迟；轮询兜底事件未覆盖的
     * 边界(如 top resumed 与窗口焦点短暂不一致的过渡帧)。
     */
    fun onTopResumedActivityChanged(isTopResumed: Boolean) {
        if (isTopResumed) {
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
     * 超过阈值后按用量统计归因(小窗优先，悬浮窗兜底)：
     * - 存在"新鲜的其他应用"(最近前台化/使用时刻晚于本应用最后一次
     *   持有焦点，即失焦之后才被使用)：本应用仍 RESUMED 而焦点在他人
     *   ——正常应用切换必先 onPause 使本过滤失效，能到达此处说明对方
     *   以窗口形式覆盖本应用(ROM 小窗/自由窗口) → FREEFORM_WINDOW，
     *   详情携带包名。归因取"最大的新鲜其他应用"而非全量最大值：
     *   新版窗口语义下开小窗会使底层应用经历生命周期抖动，本应用自己
     *   的记录可能被刷新到小窗应用之后；SystemUI 的瞬态 UI(侧边栏手势
     *   等)亦排除。数据源为事件流+聚合值双源合并(见各查询方法文档)；
     * - 无新鲜其他应用但用量统计的顶层是自己：焦点被抢但顶层仍是自己
     *   ——抢焦点的是悬浮窗/系统浮层(状态栏面板、侧边栏展开等)而非应用
     *   切换 → FLOATING_WINDOW；
     * - 用量统计不可用(未授使用情况访问权)：无法归因，仅 FOCUS_LOSS。
     *
     * 新鲜度过滤(见 [lastFocusHeldMs])同时排除两类陈旧数据：
     * - 关闭小窗后用量统计的 topApp 仍停留在小窗应用，此后任何焦点丢失
     *   (如下拉状态栏)都会把旧包名再次误报为小窗；
     * - 检测器创建前(从未获得焦点时)其他应用的历史使用记录。
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
            // 无障碍事实归因(增强，优先于用量统计推测)：窗口快照中当前持有
            // 焦点的外部窗口即覆盖者——本应用仍 RESUMED 而焦点在他人
            accessibilityFocusAttribution()?.let { (pkg, isAppWindow) ->
                if (isAppWindow) {
                    onIssue(
                        DetectionItems.FREEFORM_WINDOW,
                        activity.getString(R.string.freeform_window_detail, pkg)
                    )
                } else {
                    onIssue(DetectionItems.FLOATING_WINDOW, null)
                }
                return
            }
            // 归因(见方法文档)：小窗优先(新鲜的其他应用)，悬浮窗兜底
            val stats = queryForegroundByEvents() + queryUsageStats()
            val freshOther = stats
                .filter { it.first != activity.packageName && it.first != SYSTEM_UI_PACKAGE }
                .filter { it.second > lastFocusHeldMs }
                .maxByOrNull { it.second }
            when {
                freshOther != null -> {
                    onIssue(
                        DetectionItems.FREEFORM_WINDOW,
                        activity.getString(R.string.freeform_window_detail, freshOther.first)
                    )
                }

                stats.maxByOrNull { it.second }?.first == activity.packageName -> {
                    // 焦点被抢但用量统计的顶层应用仍是自己——抢焦点的是
                    // 悬浮窗/系统浮层(状态栏面板、侧边栏展开等)而非应用切换
                    onIssue(DetectionItems.FLOATING_WINDOW, null)
                }

                else -> Unit
            }
        }
    }

    // ---------- 前台应用查询(顶层是否是自己) ----------

    /**
     * 无障碍事实归因(增强)：窗口快照中当前持有焦点的外部窗口——本应用仍
     * RESUMED 而焦点在他人，该窗口即为覆盖本应用的窗口(正常应用切换会先
     * onPause 使本检测前置条件失效，能到达此处说明对方以窗口形式共存)。
     * - 其他应用的 TYPE_APPLICATION 窗口 → 自由小窗(详情携带包名，事实)；
     * - SystemUI 瞬态(状态栏/侧边栏)或非应用窗口(输入法/放大部分叠加层等)
     *   → 悬浮窗。
     * 无障碍未开启或快照中无持焦点外部窗口时返回 null(退回用量统计推测)。
     */
    private fun accessibilityFocusAttribution(): Pair<String, Boolean>? {
        val snapshot = EnhancementState.accessibilityInstance?.windowSnapshot().orEmpty()
        val focused = snapshot.firstOrNull { it.isFocused && it.pkg != activity.packageName }
            ?: return null
        val pkg = focused.pkg ?: return null
        val isAppWindow = focused.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                pkg != SYSTEM_UI_PACKAGE
        return pkg to isAppWindow
    }

    /**
     * 归因数据源 1(主)：UsageEvents 事件流。Activity 前台化
     * (MOVE_TO_FOREGROUND)时立即写入，是应用锁类软件获取前台应用的
     * 标准方法。聚合值(queryUsageStats)是懒提交的——部分 ROM(实测
     * ColorOS 16)对小窗内应用根本不更新聚合，但事件流照常记录，
     * 双源互补提高跨 ROM 可靠性。
     *
     * 返回窗口期内各包最近一次前台化时刻。
     */
    private fun queryForegroundByEvents(): List<Pair<String, Long>> {
        if (!hasUsageAccess()) {
            return emptyList()
        }
        return try {
            val usm =
                activity.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - ATTRIBUTION_LOOKBACK_MS, now)
            val latest = HashMap<String, Long>()
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                    event.timeStamp > (latest[event.packageName] ?: 0L)
                ) {
                    latest[event.packageName] = event.timeStamp
                }
            }
            latest.map { it.key to it.value }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 归因数据源 2(兜底)：UsageStats 聚合值，各应用最后使用时刻。
     * 与事件流合并使用(取新鲜的其他应用，见 [checkFocusProbe])。
     * 直接反射 IActivityTaskManager.getTasks() 会被服务端 REAL_GET_TASKS
     * 签名权限拦截(HiddenApiBypass 只绕客户端隐藏 API 限制，绕不过 Binder
     * 权限检查)，故采用 UsageStatsManager 公开 API。
     *
     * 需要用户在设置中授予"使用情况访问权"(PACKAGE_USAGE_STATS)；
     * 未授予时返回空列表(小窗的该信号不可用，焦点检测不受影响)。
     */
    private fun queryUsageStats(): List<Pair<String, Long>> {
        if (!hasUsageAccess()) {
            return emptyList()
        }
        return try {
            val usm =
                activity.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            usm.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                now - ATTRIBUTION_LOOKBACK_MS,
                now
            )
                .filter { it.lastTimeUsed > 0 }
                .map { it.packageName to it.lastTimeUsed }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 是否已授予"使用情况访问权"(统一走 Auxiliary，供权限面板复用) */
    private fun hasUsageAccess(): Boolean = Auxiliary.hasUsageAccess(activity)

    // ---------- 反射工具 ----------

    /**
     * 本进程是否存在主窗口之外的窗口(下拉菜单/Dialog 等自身弹层)。
     * TrustedPresentation 只报告遮挡比例、不含遮挡者身份——自家弹层
     * 盖住主窗口同样触发回调，窗口显示不完整检测上报前用它排除自遮挡。
     */
    fun hasOwnOverlayingWindow(): Boolean =
        ownViewRoots().any { it !== activity.window.decorView }

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
