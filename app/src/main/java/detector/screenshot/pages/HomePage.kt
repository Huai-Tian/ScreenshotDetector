package detector.screenshot.pages

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import detector.screenshot.Auxiliary
import detector.screenshot.R
import kotlinx.coroutines.launch

// 普通函数：根据 ID 获取检测项显示名称（通过 Context 获取资源）
private fun getItemName(context: Context, id: Int): String {
    return when (id) {
        Auxiliary.ID_SCREENSHOT -> context.getString(R.string.key_press_screenshot)
        Auxiliary.ID_RECORDING -> context.getString(R.string.screen_recording)
        Auxiliary.ID_MIRRORING -> context.getString(R.string.screen_mirroring)
        Auxiliary.ID_ENVIRONMENT -> context.getString(R.string.basic_device_environment)
        Auxiliary.ID_MEDIA_PROJECTION -> context.getString(R.string.MediaProjection_state)
        Auxiliary.ID_MEDIA_LIBRARY -> context.getString(R.string.media_library)
        Auxiliary.ID_MEDIA_ROUTER -> context.getString(R.string.MediaRouter_state)
        Auxiliary.ID_FILE_CHANGES -> context.getString(R.string.file_changes)
        Auxiliary.ID_SCREENSHOT_FAKER -> context.getString(R.string.ScreenshotFaker)
        else -> "Unknown"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeCompose(
    onStartKeyPressDetection: (onDetected: () -> Unit) -> Unit = {},
    onStopKeyPressDetection: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 配置开关状态
    var option by remember { mutableStateOf(true) }
    var detectKeyPressScreenshot by remember { mutableStateOf(Auxiliary.isKeyPressScreenshotDetectionAvailable) }
    var detectScreenRecord by remember { mutableStateOf(true) }
    var detectScreenShare by remember { mutableStateOf(true) }
    var basicEnvironmentCheck by remember { mutableStateOf(true) }
    var monitorMediaProjectionState by remember { mutableStateOf(true) }
    var monitorMediaLibrary by remember { mutableStateOf(true) }
    var monitorMediaRouter by remember { mutableStateOf(false) }
    var monitorFileChanges by remember { mutableStateOf(false) }
    var detectScreenShotFaker by remember { mutableStateOf(false) }
    val requireHigherAndroid = stringResource(R.string.require_higher_android_version)

    // UI 状态
    var isLoading by remember { mutableStateOf(true) }

    // 检测项状态：Map<检测项ID, 是否异常 (true=检测到)>
    val detectionStatus = remember { mutableStateMapOf<Int, Boolean>() }

    val isDetectionConfigValid by remember {
        derivedStateOf {
            detectKeyPressScreenshot || detectScreenRecord || detectScreenShare ||
                    basicEnvironmentCheck || monitorMediaProjectionState || monitorMediaLibrary ||
                    monitorMediaRouter || monitorFileChanges || detectScreenShotFaker
        }
    }

    // 组件销毁时停止所有检测
    DisposableEffect(Unit) {
        onDispose {
            onStopKeyPressDetection()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { option = true }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.detect_again)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding) // 仅保留 Scaffold 的内边距（避开状态栏和 TopBar）
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                else -> {
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp), // 少量边距，使文字不贴边
                            verticalArrangement = Arrangement.spacedBy(
                                12.dp,
                                Alignment.CenterVertically
                            ),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            detectionStatus.entries.forEach { (id, isAbnormal) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "${getItemName(context, id)}: ",
                                        fontSize = 15.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (isAbnormal) {
                                            stringResource(R.string.status_detected)
                                        } else {
                                            stringResource(R.string.status_normal)
                                        },
                                        fontSize = 18.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isAbnormal) Color.Red else Color.Green
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 配置对话框（保持不变）
        if (option) {
            AlertDialog(
                onDismissRequest = { option = false },
                title = { Text(stringResource(R.string.config_detect_options)) },
                text = {
                    Column {
                        // 截屏检测
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(R.string.key_press_screenshot))
                            Switch(
                                checked = detectKeyPressScreenshot,
                                onCheckedChange = {
                                    if (Auxiliary.isKeyPressScreenshotDetectionAvailable) {
                                        detectKeyPressScreenshot = it
                                    } else {
                                        Toast.makeText(
                                            context,
                                            requireHigherAndroid,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                enabled = Auxiliary.isKeyPressScreenshotDetectionAvailable
                            )
                        }
                        // 录屏检测
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(R.string.screen_recording))
                            Switch(
                                checked = detectScreenRecord,
                                onCheckedChange = { detectScreenRecord = it }
                            )
                        }
                        // 投屏检测
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(R.string.screen_mirroring))
                            Switch(
                                checked = detectScreenShare,
                                onCheckedChange = { detectScreenShare = it }
                            )
                        }
                        // MediaProjection状态
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(R.string.MediaProjection_state))
                            Switch(
                                checked = monitorMediaProjectionState,
                                onCheckedChange = { monitorMediaProjectionState = it }
                            )
                        }
                        // 媒体库监控
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(R.string.media_library))
                            Switch(
                                checked = monitorMediaLibrary,
                                onCheckedChange = { monitorMediaLibrary = it }
                            )
                        }
                        // 环境检测
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(R.string.basic_device_environment))
                            Switch(
                                checked = basicEnvironmentCheck,
                                onCheckedChange = { basicEnvironmentCheck = it }
                            )
                        }
                        // 文件变化监控
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(R.string.file_changes))
                            Switch(
                                checked = monitorFileChanges,
                                onCheckedChange = { monitorFileChanges = it }
                            )
                        }
                        // MediaRouter监控
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(R.string.MediaRouter_state))
                            Switch(
                                checked = monitorMediaRouter,
                                onCheckedChange = { monitorMediaRouter = it }
                            )
                        }
                        // ScreenshotFaker检测
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(R.string.ScreenshotFaker))
                            Switch(
                                checked = detectScreenShotFaker,
                                onCheckedChange = { detectScreenShotFaker = it }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                // 1. 停止之前可能运行的检测
                                onStopKeyPressDetection()

                                // 2. 清空状态，添加启用的检测项（初始正常）
                                detectionStatus.clear()
                                if (detectKeyPressScreenshot) {
                                    detectionStatus[Auxiliary.ID_SCREENSHOT] = false
                                }
                                if (detectScreenRecord) {
                                    detectionStatus[Auxiliary.ID_RECORDING] = false
                                }
                                if (detectScreenShare) {
                                    detectionStatus[Auxiliary.ID_MIRRORING] = false
                                }
                                if (basicEnvironmentCheck) {
                                    detectionStatus[Auxiliary.ID_ENVIRONMENT] = false
                                }
                                if (monitorMediaProjectionState) {
                                    detectionStatus[Auxiliary.ID_MEDIA_PROJECTION] = false
                                }
                                if (monitorMediaLibrary) {
                                    detectionStatus[Auxiliary.ID_MEDIA_LIBRARY] = false
                                }
                                if (monitorMediaRouter) {
                                    detectionStatus[Auxiliary.ID_MEDIA_ROUTER] = false
                                }
                                if (monitorFileChanges) {
                                    detectionStatus[Auxiliary.ID_FILE_CHANGES] = false
                                }
                                if (detectScreenShotFaker) {
                                    detectionStatus[Auxiliary.ID_SCREENSHOT_FAKER] = false
                                }

                                // 停止转圈，显示检测卡片
                                isLoading = false

                                // 3. 启动截屏检测（如果启用且可用）
                                if (detectKeyPressScreenshot) {
                                    onStartKeyPressDetection {
                                        // 截屏触发，更新状态为异常
                                        detectionStatus[Auxiliary.ID_SCREENSHOT] = true
                                    }
                                }

                                // 4. 可在此启动其他检测

                                // 5. 关闭对话框
                                option = false
                            }
                        },
                        enabled = isDetectionConfigValid
                    ) {
                        Text(stringResource(R.string.confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { option = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}