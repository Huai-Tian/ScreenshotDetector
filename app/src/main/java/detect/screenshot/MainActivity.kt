package detect.screenshot

import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import detect.screenshot.detection.DetectionFunctions
import detect.screenshot.detection.DetectionItems
import detect.screenshot.pages.AgreementCompose
import detect.screenshot.pages.HomeCompose

class MainActivity : ComponentActivity() {
    private lateinit var sharedPreferences: SharedPreferences
    private var showHome by mutableStateOf(false)

    // ========== 检测逻辑宿主 ==========
    val detectionFunctions = DetectionFunctions(this)

    // ========== 异常检测结果(粘性)：检测项 -> 详情(可空，如小窗包名) ==========
    // 一旦检出持续显示，只能通过重置清除；重复上报时详情取最新值
    val detectedIssues = mutableStateMapOf<DetectionItems, String?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        showHome = sharedPreferences.getBoolean("has_agreed", false)
        setContent {
            if (showHome) {
                HomeCompose(
                    activity = this,
                    issues = detectedIssues
                )
            } else {
                AgreementCompose(
                    onAgree = {
                        sharedPreferences.edit { putBoolean("has_agreed", true) }
                        showHome = true
                    },
                    onDisagree = {
                        finishAffinity()
                    }
                )
            }
        }
    }

    // ========== 触摸事件转发给检测逻辑(悬浮窗遮挡检测) ==========
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { detectionFunctions.dispatchTouchEvent(it) }
        return super.dispatchTouchEvent(ev)
    }

    // ========== 窗口焦点变化转发给检测逻辑(反射窗口检测的焦点探测) ==========
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        detectionFunctions.onWindowFocusChanged(hasFocus)
    }

    // ========== 顶层 Resumed 变化转发(API 29+ 事件化焦点信号，与轮询互补) ==========
    override fun onTopResumedActivityChanged(isTopResumed: Boolean) {
        super.onTopResumedActivityChanged(isTopResumed)
        detectionFunctions.onTopResumedActivityChanged(isTopResumed)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        detectionFunctions.onMultiWindowModeChanged()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        detectionFunctions.onPictureInPictureModeChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        detectionFunctions.stopAll()
    }

    override fun onStop() {
        super.onStop()
        detectionFunctions.onActivityStopped()
    }

    override fun onResume() {
        super.onResume()
        detectionFunctions.onActivityResumed()
    }
}
