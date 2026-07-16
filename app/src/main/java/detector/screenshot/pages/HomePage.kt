package detector.screenshot.pages

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri

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
        Auxiliary.ID_BEHAVIOR -> context.getString(R.string.suspicious_behavior)
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
    onStopMediaRouterDetection: () -> Unit = {},
    onStartMediaLibraryDetection: (onDetected: () -> Unit) -> Unit = {},
    onStopMediaLibraryDetection: () -> Unit = {},
    onStartFileChangesDetection: (onDetected: () -> Unit) -> Unit = {},
    onStopFileChangesDetection: () -> Unit = {},
    onStartEnvironmentDetection: (onRisky: () -> Unit, onSafe: () -> Unit) -> Unit = { _, _ -> },
    onStopEnvironmentDetection: () -> Unit = {},
    onStartBehaviorDetection: (onRisky: () -> Unit, onSafe: () -> Unit) -> Unit = { _, _ -> },
    onStopBehaviorDetection: () -> Unit = {},
    onDialogShow: () -> Unit = {},
    onDialogDismiss: () -> Unit = {},
    onStartScreenshotFakerDetection: (onDetected: () -> Unit, onStopped: () -> Unit) -> Unit = { _, _ -> },
    onStopScreenshotFakerDetection: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var option by remember { mutableStateOf(true) }

    LaunchedEffect(option) {
        if (option) onDialogShow()
        else onDialogDismiss()
    }

    var detectKeyPressScreenshot by remember { mutableStateOf(Auxiliary.KeyPressDetectionAvailable) }
    var detectScreenRecord by remember { mutableStateOf(Auxiliary.ScreenRecordingDetectionAvailable) }
    var detectScreenShare by remember { mutableStateOf(true) }
    var basicEnvironmentCheck by remember { mutableStateOf(true) }
    var monitorMediaProjectionState by remember { mutableStateOf(true) }
    var monitorMediaLibrary by remember { mutableStateOf(true) }
    var monitorMediaRouter by remember { mutableStateOf(false) }
    var monitorFileChanges by remember { mutableStateOf(false) }
    var detectSuspiciousBehavior by remember { mutableStateOf(false) }
    var detectScreenShotFaker by remember { mutableStateOf(false) }

    val detectionStatus = remember { mutableStateMapOf<Int, Boolean>() }
    var expanded by remember { mutableStateOf(false) }
    var agreement by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val isDetectionConfigValid by remember {
        derivedStateOf {
            detectKeyPressScreenshot || detectScreenRecord || detectScreenShare ||
                    basicEnvironmentCheck || monitorMediaProjectionState || monitorMediaLibrary ||
                    monitorMediaRouter || monitorFileChanges || detectSuspiciousBehavior || detectScreenShotFaker
        }
    }

    fun stopAllDetections() {
        onStopKeyPressDetection()
        onStopScreenRecordingDetection()
        onStopMirroringDetection()
        onStopMediaProjectionDetection()
        onStopMediaRouterDetection()
        onStopMediaLibraryDetection()
        onStopFileChangesDetection()
        onStopBehaviorDetection()
        onStopEnvironmentDetection()
        onStopScreenshotFakerDetection()
    }

    DisposableEffect(Unit) {
        onDispose { stopAllDetections() }
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
                    Box {
                        IconButton(
                            onClick = { expanded = !expanded }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.more)
                            )
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier
                                .width(180.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.user_agreement)) },
                                onClick = {
                                    expanded = false
                                    agreement = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.info)) },
                                onClick = {
                                    expanded = false
                                    info = true
                                }
                            )
                        }
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
                    if (detectionStatus.isNotEmpty()) {
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
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = stringResource(R.string.no_detection_items_yet),
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

        }
    }

    if (option) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.config_detect_options)) },
            text = {
                val density = LocalDensity.current
                val windowHeightPx = LocalWindowInfo.current.containerSize.height
                val windowHeightDp = with(density) { windowHeightPx.toDp() }
                val maxHeight = windowHeightDp * 0.6f
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.key_press_screenshot))
                        Switch(
                            checked = detectKeyPressScreenshot,
                            onCheckedChange = { detectKeyPressScreenshot = it },
                            enabled = Auxiliary.KeyPressDetectionAvailable
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.screen_recording))
                        Switch(
                            checked = detectScreenRecord,
                            onCheckedChange = { detectScreenRecord = it },
                            enabled = Auxiliary.ScreenRecordingDetectionAvailable
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.screen_mirroring))
                        Switch(
                            checked = detectScreenShare,
                            onCheckedChange = { detectScreenShare = it }
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.MediaProjection_state))
                        Switch(
                            checked = monitorMediaProjectionState,
                            onCheckedChange = { monitorMediaProjectionState = it }
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.media_library))
                        Switch(
                            checked = monitorMediaLibrary,
                            onCheckedChange = { monitorMediaLibrary = it }
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.basic_device_environment))
                        Switch(
                            checked = basicEnvironmentCheck,
                            onCheckedChange = { basicEnvironmentCheck = it }
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.file_changes))
                        Switch(
                            checked = monitorFileChanges,
                            onCheckedChange = { monitorFileChanges = it }
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.MediaRouter_state))
                        Switch(
                            checked = monitorMediaRouter,
                            onCheckedChange = { monitorMediaRouter = it }
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.suspicious_behavior))
                        Switch(
                            checked = detectSuspiciousBehavior,
                            onCheckedChange = { detectSuspiciousBehavior = it }
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.ScreenshotFaker))
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
                            stopAllDetections()
                            detectionStatus.clear()

                            if (detectKeyPressScreenshot) {
                                detectionStatus[Auxiliary.ID_SCREENSHOT] = false
                                onStartKeyPressDetection {
                                    detectionStatus[Auxiliary.ID_SCREENSHOT] = true
                                }
                            }
                            if (detectScreenRecord) {
                                detectionStatus[Auxiliary.ID_RECORDING] = false
                                onStartScreenRecordingDetection(
                                    { detectionStatus[Auxiliary.ID_RECORDING] = true },
                                    { detectionStatus[Auxiliary.ID_RECORDING] = false }
                                )
                            }
                            if (detectScreenShare) {
                                detectionStatus[Auxiliary.ID_MIRRORING] = false
                                onStartMirroringDetection(
                                    { detectionStatus[Auxiliary.ID_MIRRORING] = true },
                                    { detectionStatus[Auxiliary.ID_MIRRORING] = false }
                                )
                            }
                            if (monitorMediaProjectionState) {
                                detectionStatus[Auxiliary.ID_MEDIA_PROJECTION] = false
                                onStartMediaProjectionDetection(
                                    { detectionStatus[Auxiliary.ID_MEDIA_PROJECTION] = true },
                                    { detectionStatus[Auxiliary.ID_MEDIA_PROJECTION] = false }
                                )
                            }
                            if (monitorMediaLibrary) {
                                detectionStatus[Auxiliary.ID_MEDIA_LIBRARY] = false
                                onStartMediaLibraryDetection {
                                    detectionStatus[Auxiliary.ID_MEDIA_LIBRARY] = true
                                }
                            }
                            if (monitorFileChanges) {
                                detectionStatus[Auxiliary.ID_FILE_CHANGES] = false
                                onStartFileChangesDetection {
                                    detectionStatus[Auxiliary.ID_FILE_CHANGES] = true
                                }
                            }
                            if (monitorMediaRouter) {
                                detectionStatus[Auxiliary.ID_MEDIA_ROUTER] = false
                                onStartMediaRouterDetection(
                                    { detectionStatus[Auxiliary.ID_MEDIA_ROUTER] = true },
                                    { detectionStatus[Auxiliary.ID_MEDIA_ROUTER] = false }
                                )
                            }
                            if (detectSuspiciousBehavior) {
                                detectionStatus[Auxiliary.ID_BEHAVIOR] = false
                                onStartBehaviorDetection(
                                    { detectionStatus[Auxiliary.ID_BEHAVIOR] = true },
                                    { detectionStatus[Auxiliary.ID_BEHAVIOR] = false }
                                )
                            }
                            if (basicEnvironmentCheck) {
                                detectionStatus[Auxiliary.ID_ENVIRONMENT] = false
                                onStartEnvironmentDetection(
                                    { detectionStatus[Auxiliary.ID_ENVIRONMENT] = true },
                                    { detectionStatus[Auxiliary.ID_ENVIRONMENT] = false }
                                )
                            }
                            if (detectScreenShotFaker) {
                                detectionStatus[Auxiliary.ID_SCREENSHOT_FAKER] = false
                                onStartScreenshotFakerDetection(
                                    { detectionStatus[Auxiliary.ID_SCREENSHOT_FAKER] = true },
                                    { detectionStatus[Auxiliary.ID_SCREENSHOT_FAKER] = false }
                                )
                            }

                            option = false
                        }
                    },
                    enabled = isDetectionConfigValid
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        option = false
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    if (agreement) {
        AlertDialog(
            onDismissRequest = {},
            modifier = Modifier.fillMaxHeight(),
            title = { Text(stringResource(R.string.user_agreement) + "\n" + stringResource(R.string.agreed)) },
            text = { UserAgreement() },
            confirmButton = {
                Button(
                    onClick = { agreement = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
    if (info) {
        Dialog(onDismissRequest = { info = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Text(
                        text = stringResource(R.string.description_first),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 5.dp)
                    )
                    Text(
                        text = stringResource(R.string.description_second),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { info = false },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.open_source), fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                val url = "https://github.com/Huai-Tian/ScreenshotDetector"
                                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                                context.startActivity(intent)
                                info = false
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            Text("GitHub", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}