package detect.screenshot.pages

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import detect.screenshot.Auxiliary
import detect.screenshot.detection.DetectionItems
import detect.screenshot.MainActivity
import detect.screenshot.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

private val NormalStatusColor = Color(0xFF2E7D32)

private val PermDeniedColor = Color(0xFFE53935)

/** 增强服务(无障碍/通知使用权)未启用时的提示色：蓝色示意"可选增强"而非"缺失风险" */
private val EnhancementColor = Color(0xFF1E88E5)

private val IssueCardColor = Color(0xFFE0E0E0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeCompose(
    activity: MainActivity,
    issues: SnapshotStateMap<DetectionItems, String?>,
    suspicions: SnapshotStateMap<DetectionItems, String?>
) {
    val context = LocalContext.current

    var expanded by remember { mutableStateOf(false) }
    var agreement by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var openSource by remember { mutableStateOf(false) }

    // ========== 可疑痕迹弹层(顶栏眼睛按钮，仅有命中时可见) ==========
    var suspiciousExpanded by remember { mutableStateOf(false) }

    /** 上报路由：可疑痕迹类入独立容器(眼睛按钮展示)，确定性事件入主列表 */
    fun routeIssue(item: DetectionItems, detail: String?) {
        if (item.isSuspicious) suspicions[item] = detail else issues[item] = detail
    }

    // ========== 权限状态面板(顶栏安全等级图标入口) ==========
    var permExpanded by remember { mutableStateOf(false) }
    var permItems by remember { mutableStateOf(queryPermItems(activity)) }
    var imagesGranted by remember { mutableStateOf(Auxiliary.hasImagesPermission(activity)) }
    var videoGranted by remember { mutableStateOf(Auxiliary.hasVideoPermission(activity)) }

    // 图+视频一次弹窗合并申请(33+ 两个运行时权限；拒绝是用户的选择，
    // 仅当"不再询问"(rationale 不可展示)后才引导跳应用详情设置)
    val mediaPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val missing = mediaPermissionNames().filter {
            context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty() && missing.all {
                !activity.shouldShowRequestPermissionRationale(it)
            }) {
            openPermissionSettings(context, PermissionJump.APP_DETAILS)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val items = withContext(Dispatchers.Default) { queryPermItems(activity) }
            permItems = items
            // 权限落地即时补启对应检测(面板外授权/仅授其一的场景)
            val imagesNow = Auxiliary.hasImagesPermission(activity)
            val videoNow = Auxiliary.hasVideoPermission(activity)
            if (imagesNow && !imagesGranted) {
                DetectionItems.MEDIA_LIBRARY.start(activity.detectionFunctions) { item, detail ->
                    routeIssue(item, detail)
                }
            }
            if (videoNow && !videoGranted) {
                DetectionItems.VIDEO_MEDIA_LIBRARY.start(activity.detectionFunctions) { item, detail ->
                    routeIssue(item, detail)
                }
            }
            imagesGranted = imagesNow
            videoGranted = videoNow
            delay((if (permExpanded) 500L else 2_000L).milliseconds)
        }
    }

    // 图标语义(仅图标区分，不着色；所有权限均为可选，仅表示检测能力面)：
    // 常规权限(照片视频/使用情况/应用列表)未全部授权 → Warning；
    // 常规已全授但增强服务(无障碍/通知使用权)未全部开启 → Lock；全部就绪 → Shield
    val permIcon = when {
        permItems.any { !it.granted && !it.isEnhancement } -> Icons.Outlined.Warning
        permItems.any { !it.granted } -> Icons.Outlined.Lock
        else -> Icons.Outlined.Shield
    }

    fun stopAllDetections() {
        DetectionItems.entries.forEach { it.stop(activity.detectionFunctions) }
    }

    fun startAllDetections() {
        stopAllDetections()
        issues.clear()
        suspicions.clear()
        DetectionItems.entries.forEach { entry ->
            entry.start(activity.detectionFunctions) { item, detail ->
                routeIssue(item, detail)
            }
        }
    }

    DisposableEffect(Unit) {
        startAllDetections()
        activity.detectionFunctions.setEnvironmentClearCallback { item ->
            if (item.isSuspicious) suspicions.remove(item) else issues.remove(item)
        }
        onDispose { stopAllDetections() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    // 可疑痕迹入口(眼睛)：仅有可疑命中时可见，点击弹层展示全部
                    // 可疑痕迹卡片，不占用主异常列表(见 DetectionItems.isSuspicious)
                    if (suspicions.isNotEmpty()) {
                        IconButton(onClick = { suspiciousExpanded = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Visibility,
                                contentDescription = stringResource(R.string.suspicious_findings)
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { permExpanded = true }) {
                            Icon(
                                imageVector = permIcon,
                                contentDescription = stringResource(R.string.permission_status)
                            )
                        }
                        DropdownMenu(
                            expanded = permExpanded,
                            onDismissRequest = { permExpanded = false },
                            modifier = Modifier.width(300.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.permission_status),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            if (permItems.all { it.granted }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.permission_all_granted)) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Check,
                                            contentDescription = null,
                                            tint = NormalStatusColor
                                        )
                                    },
                                    onClick = { permExpanded = false }
                                )
                            } else {
                                permItems.forEach { item ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(stringResource(item.labelRes))
                                                Text(
                                                    text = stringResource(
                                                        when {
                                                            // 增强服务是"启用"而非"授权"
                                                            item.isEnhancement -> if (item.granted)
                                                                R.string.permission_enabled
                                                            else R.string.permission_not_enabled

                                                            item.granted -> R.string.permission_granted
                                                            else -> R.string.permission_not_granted
                                                        }
                                                    ),
                                                    fontSize = 12.sp,
                                                    color = when {
                                                        item.granted -> NormalStatusColor
                                                        // 增强项未启用：蓝色(可选增强，非风险)
                                                        item.isEnhancement -> EnhancementColor
                                                        else -> PermDeniedColor
                                                    }
                                                )
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = when {
                                                    item.granted -> Icons.Outlined.Check
                                                    // 增强项未启用：Shield(能力增强，非缺失风险)
                                                    item.isEnhancement -> Icons.Outlined.Shield
                                                    else -> Icons.Outlined.Warning
                                                },
                                                contentDescription = null,
                                                tint = when {
                                                    item.granted -> NormalStatusColor
                                                    item.isEnhancement -> EnhancementColor
                                                    else -> PermDeniedColor
                                                }
                                            )
                                        },
                                        onClick = {
                                            if (!item.granted) {
                                                // 权限弹窗/跳设置为自发导航，期间切屏不计
                                                activity.detectionFunctions.markSelfNavigation()
                                                if (item.jump == PermissionJump.RUNTIME_PERMISSION) {
                                                    // 运行时权限直接弹系统授权框
                                                    // (勾选"不再询问"后才跳设置，见回调)
                                                    mediaPermLauncher.launch(mediaPermissionNames())
                                                } else {
                                                    // 特殊访问授权无弹窗，只能去设置
                                                    openPermissionSettings(context, item.jump)
                                                }
                                                permExpanded = false
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = { startAllDetections() }) {
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
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center
        ) {
            when {
                issues.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.status_normal),
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = NormalStatusColor
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.monitoring),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        issues.forEach { (issue, detail) ->
                            IssueCard(issue, detail)
                        }
                    }
                }
            }
        }
    }

    if (agreement) {
        AlertDialog(
            onDismissRequest = {},
            modifier = Modifier.fillMaxHeight(),
            title = { Text(stringResource(R.string.user_agreement) + "\n" + stringResource(R.string.agreed)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) { UserAgreement() }
            },
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
                            onClick = {
                                openSource = true
                                info = false
                            },
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
    if (suspiciousExpanded && suspicions.isNotEmpty()) {
        Dialog(onDismissRequest = { suspiciousExpanded = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text(
                        text = stringResource(R.string.suspicious_findings),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(weight = 1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        suspicions.forEach { (item, detail) ->
                            IssueCard(item, detail)
                        }
                    }
                    Button(
                        onClick = { suspiciousExpanded = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
    if (openSource) {
        BackHandler { openSource = false }
        OpenSourceLicensesScreen(onBack = { openSource = false })
    }
}

private data class PermItem(
    @StringRes val labelRes: Int,
    val granted: Boolean,
    val jump: PermissionJump,
    /** 增强项(无障碍/通知使用权)：仅当其余三项均已授权时，面板图标才从 Lock 升级为 Shield */
    val isEnhancement: Boolean = false
)

private enum class PermissionJump {
    RUNTIME_PERMISSION,
    USAGE_ACCESS,
    APP_DETAILS,
    ACCESSIBILITY,
    NOTIFICATION_ACCESS
}

private fun queryPermItems(context: Context): List<PermItem> = listOf(
    PermItem(
        R.string.permission_photos_video,
        Auxiliary.hasMediaPermissions(context),
        PermissionJump.RUNTIME_PERMISSION
    ),
    PermItem(
        R.string.permission_usage_access,
        Auxiliary.hasUsageAccess(context),
        PermissionJump.USAGE_ACCESS
    ),
    PermItem(
        R.string.permission_app_list,
        Auxiliary.appListVisible(context),
        PermissionJump.APP_DETAILS
    ),
    PermItem(
        R.string.permission_accessibility,
        Auxiliary.isOwnAccessibilityServiceEnabled(context),
        PermissionJump.ACCESSIBILITY,
        isEnhancement = true
    ),
    PermItem(
        R.string.permission_notification_access,
        Auxiliary.hasNotificationAccess(context),
        PermissionJump.NOTIFICATION_ACCESS,
        isEnhancement = true
    ),
)

/** 图+视频媒体权限名(33+ 两个；旧版本同一 READ_EXTERNAL_STORAGE) */
private fun mediaPermissionNames(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

private fun openPermissionSettings(context: Context, jump: PermissionJump) {
    val appDetails = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "package:${context.packageName}".toUri()
    )
    val target = when (jump) {
        PermissionJump.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        PermissionJump.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        PermissionJump.NOTIFICATION_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        else -> appDetails
    }
    runCatching {
        context.startActivity(target)
    }.onFailure {
        runCatching { context.startActivity(appDetails) }
    }
}

@Composable
private fun IssueCard(issue: DetectionItems, detail: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(2.dp, IssueCardColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Text(
                text = stringResource(issue.labelRes),
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF333333)
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF757575),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

// 开源许可证页面
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    // 版本号为 Gradle 依赖解析的真实版本(debugRuntimeClasspath)
    val libraries = listOf(
        LibraryInfo(
            "AndroidX Activity Compose",
            "1.13.0",
            "Apache-2.0",
            "Copyright (c) 2011 The Android Open Source Project"
        ),
        LibraryInfo(
            "AndroidX Compose BOM",
            "2026.08.00",
            "Apache-2.0",
            "Copyright (c) 2011 The Android Open Source Project"
        ),
        LibraryInfo(
            "AndroidX Compose Material3",
            "1.4.0",
            "Apache-2.0",
            "Copyright (c) 2019 The Android Open Source Project"
        ),
        LibraryInfo(
            "AndroidX Compose UI",
            "1.12.0",
            "Apache-2.0",
            "Copyright (c) 2011 The Android Open Source Project"
        ),
        LibraryInfo(
            "AndroidX Compose UI Graphics",
            "1.12.0",
            "Apache-2.0",
            "Copyright (c) 2011 The Android Open Source Project"
        ),
        LibraryInfo(
            "AndroidX Compose Material Icons Core",
            "1.7.8",
            "Apache-2.0",
            "Copyright (c) 2019 The Android Open Source Project"
        ),
        LibraryInfo(
            "AndroidX Compose Material Icons Extended",
            "1.7.8",
            "Apache-2.0",
            "Copyright (c) 2019 The Android Open Source Project"
        ),
        LibraryInfo(
            "AndroidX Core KTX",
            "1.19.0",
            "Apache-2.0",
            "Copyright (c) 2011 The Android Open Source Project"
        ),
        LibraryInfo(
            "AndroidX Lifecycle Runtime KTX",
            "2.11.0",
            "Apache-2.0",
            "Copyright (c) 2011 The Android Open Source Project"
        ),
        LibraryInfo(
            "AndroidX MediaRouter",
            "1.8.1",
            "Apache-2.0",
            "Copyright (c) 2011 The Android Open Source Project"
        ),
        LibraryInfo(
            "HiddenApiBypass (LSPosed)",
            "6.1",
            "Apache-2.0",
            "Copyright (c) LSPosed Contributors"
        ),
        LibraryInfo(
            "Kotlin Coroutines",
            "1.9.0",
            "Apache-2.0",
            "Copyright (c) 2016 JetBrains s.r.o."
        ),
        LibraryInfo(
            "Kotlin Stdlib",
            "2.4.10",
            "Apache-2.0",
            "Copyright (c) 2016 JetBrains s.r.o."
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.open_source)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(libraries) { lib ->
                LicenseCard(lib)
            }
        }
    }
}

/** 单个开源库卡片(与主页 IssueCard 同视觉风格) */
@Composable
private fun LicenseCard(lib: LibraryInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(2.dp, IssueCardColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text(
                text = lib.name,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF333333)
            )
            Text(
                text = stringResource(R.string.open_source_version, lib.version),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF757575),
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "${stringResource(R.string.license)}: ${lib.license}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF757575),
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = lib.copyright,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF757575),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

data class LibraryInfo(
    val name: String,
    val version: String,
    val license: String,
    val copyright: String
)
