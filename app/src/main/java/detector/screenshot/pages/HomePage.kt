package detector.screenshot.pages

import android.content.Context
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
    onStopKeyPressDetection: () -> Unit = {},
    onStartScreenRecordingDetection: (onDetected: () -> Unit, onStopped: () -> Unit) -> Unit = { _, _ -> },
    onStopScreenRecordingDetection: () -> Unit = {},
    onStartMirroringDetection: (onDetected: () -> Unit, onStopped: () -> Unit) -> Unit = { _, _ -> },
    onStopMirroringDetection: () -> Unit = {},
    onStartMediaProjectionDetection: (onDetected: () -> Unit, onStopped: () -> Unit) -> Unit = { _, _ -> },
    onStopMediaProjectionDetection: () -> Unit = {},
    onStartMediaRouterDetection: (onConnected: () -> Unit, onDisconnected: () -> Unit) -> Unit = { _, _ -> },
    onStopMediaRouterDetection: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var option by remember { mutableStateOf(true) }
    var detectKeyPressScreenshot by remember { mutableStateOf(Auxiliary.KeyPressDetectionAvailable) }
    var detectScreenRecord by remember { mutableStateOf(Auxiliary.ScreenRecordingDetectionAvailable) }
    var detectScreenShare by remember { mutableStateOf(true) }
    var basicEnvironmentCheck by remember { mutableStateOf(true) }
    var monitorMediaProjectionState by remember { mutableStateOf(true) }
    var monitorMediaLibrary by remember { mutableStateOf(true) }
    var monitorMediaRouter by remember { mutableStateOf(false) }
    var monitorFileChanges by remember { mutableStateOf(false) }
    var detectScreenShotFaker by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    val detectionStatus = remember { mutableStateMapOf<Int, Boolean>() }
    val isDetectionConfigValid by remember {
        derivedStateOf {
            detectKeyPressScreenshot || detectScreenRecord || detectScreenShare ||
                    basicEnvironmentCheck || monitorMediaProjectionState || monitorMediaLibrary ||
                    monitorMediaRouter || monitorFileChanges || detectScreenShotFaker
        }
    }
    fun stopAllDetections() {
        onStopKeyPressDetection()
        onStopScreenRecordingDetection()
        onStopMirroringDetection()
        onStopMediaProjectionDetection()
        onStopMediaRouterDetection()
    }
    DisposableEffect(Unit) {
        onDispose {
            stopAllDetections()
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
                .padding(padding)
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
                                .padding(16.dp),
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
                                            stringResource(R.string.status_abnormal)
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
                                    detectKeyPressScreenshot = it
                                },
                                enabled = Auxiliary.KeyPressDetectionAvailable
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
                                onCheckedChange = {
                                    detectScreenRecord = it
                                },
                                enabled = Auxiliary.ScreenRecordingDetectionAvailable
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
                                stopAllDetections()
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
                                isLoading = false

                                // 3. 启动截屏检测（如果启用且可用）
                                if (detectKeyPressScreenshot) {
                                    onStartKeyPressDetection {
                                        detectionStatus[Auxiliary.ID_SCREENSHOT] = true
                                    }
                                }
                                if (detectScreenRecord) {
                                    onStartScreenRecordingDetection(
                                        { detectionStatus[Auxiliary.ID_RECORDING] = true },
                                        { detectionStatus[Auxiliary.ID_RECORDING] = false }
                                    )
                                }
                                if (detectScreenShare) {
                                    onStartMirroringDetection(
                                        { detectionStatus[Auxiliary.ID_MIRRORING] = true },
                                        { detectionStatus[Auxiliary.ID_MIRRORING] = false }
                                    )
                                }
                                if (monitorMediaProjectionState) {
                                    onStartMediaProjectionDetection(
                                        { detectionStatus[Auxiliary.ID_MEDIA_PROJECTION] = true },
                                        { detectionStatus[Auxiliary.ID_MEDIA_PROJECTION] = false }
                                    )
                                }
                                if (monitorMediaRouter){
                                    onStartMediaRouterDetection(
                                        { detectionStatus[Auxiliary.ID_MEDIA_ROUTER] = true },
                                        { detectionStatus[Auxiliary.ID_MEDIA_ROUTER] = false }
                                    )
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