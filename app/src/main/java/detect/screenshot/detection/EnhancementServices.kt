package detect.screenshot.detection

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Rect
import android.service.notification.NotificationListenerService
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.ConcurrentHashMap

/** 窗口快照条目（无障碍 getWindows 的精简投影） */
data class WindowSnapshot(
    val pkg: String?,
    val type: Int,
    val layer: Int,
    val isActive: Boolean,
    val isFocused: Boolean,
    val bounds: Rect
)

/**
 * 增强服务共享状态：无障碍/通知监听服务由系统绑定并管理生命周期，
 * 两者把数据写入此处，检测逻辑轮询读取。服务未连接时各信号为空，
 * 全部增强自动退回原有检测路径（优雅降级）。
 */
object EnhancementState {

    /** TYPE_WINDOW_STATE_CHANGED 事件流：包名 -> 最近窗口展示时刻 */
    private val windowEvents = ConcurrentHashMap<String, Long>()

    @Volatile var accessibilityInstance: DetectorAccessibilityService? = null

    @Volatile var notificationInstance: DetectorNotificationListenerService? = null

    fun noteWindowState(pkg: String, atMs: Long) {
        // 防无界增长：超量时清理过期条目
        if (windowEvents.size > 200) {
            val cutoff = atMs - 10 * 60_000L
            windowEvents.entries.removeIf { it.value < cutoff }
        }
        windowEvents[pkg] = atMs
    }

    fun windowEventsSnapshot(lookbackMs: Long): Map<String, Long> {
        val cutoff = System.currentTimeMillis() - lookbackMs
        return windowEvents.filterValues { it >= cutoff }
    }
}

/**
 * 无障碍检测增强服务（可选，需用户在设置中开启）：
 * - TYPE_WINDOW_STATE_CHANGED 事件流：实时前台应用包名（供录音来源推测，
 *   不依赖"使用情况访问权"）；
 * - getWindows() 窗口枚举（flagRetrieveInteractiveWindows）：跨应用窗口
 *   归因（自由小窗/遮挡来源）——普通应用无法枚举系统窗口(平台限制，
 *   见 WindowReflectionDetector 注释)，这是引入本服务的核心价值。
 * 仅读取窗口包名与窗口属性，不读取窗口内容。
 */
@SuppressLint("AccessibilityPolicy")
class DetectorAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        EnhancementState.accessibilityInstance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let {
                EnhancementState.noteWindowState(it, System.currentTimeMillis())
            }
        }
    }

    override fun onInterrupt() { /* 无反馈型服务 */ }

    override fun onDestroy() {
        EnhancementState.accessibilityInstance = null
        super.onDestroy()
    }

    /** 当前系统窗口快照（ROM 未授予枚举能力时为空，调用方自行降级） */
    fun windowSnapshot(): List<WindowSnapshot> = runCatching {
        windows.orEmpty().map { w ->
            WindowSnapshot(
                pkg = w.root?.packageName?.toString(),
                type = w.type,
                layer = w.layer,
                isActive = w.isActive,
                isFocused = w.isFocused,
                bounds = Rect().also { w.getBoundsInScreen(it) }
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * 通知监听增强服务（可选，需用户在设置中开启"通知使用权"）：
 * 提供设备级常驻(ongoing)通知的包名集合——Android 14+ MediaProjection 强制
 * 前台服务通知，与既有"虚拟显示器归属/投屏持久授权"事实交叉形成
 * "录屏/投屏服务运行中"检测项。只读包名与 ongoing 标记，不读通知内容。
 */
class DetectorNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        EnhancementState.notificationInstance = this
    }

    override fun onListenerDisconnected() {
        EnhancementState.notificationInstance = null
        super.onListenerDisconnected()
    }

    /** 当前存在常驻(ongoing)通知的应用包名集合 */
    fun ongoingPackages(): Set<String> = runCatching {
        activeNotifications.orEmpty()
            .filter { it.isOngoing }
            .map { it.packageName }
            .toSet()
    }.getOrDefault(emptySet())
}
