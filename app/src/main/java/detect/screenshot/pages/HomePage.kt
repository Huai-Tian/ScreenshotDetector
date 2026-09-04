package detect.screenshot.pages

import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.MoreVert
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
import detect.screenshot.detection.DetectionItems
import detect.screenshot.MainActivity
import detect.screenshot.R

// "正常"状态文字颜色(深绿)
private val NormalStatusColor = Color(0xFF2E7D32)

// 异常卡片统一描边色(淡灰)
private val IssueCardColor = Color(0xFFE0E0E0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeCompose(
    activity: MainActivity,
    issues: SnapshotStateMap<DetectionItems, String?>
) {
    val context = LocalContext.current

    var expanded by remember { mutableStateOf(false) }
    var agreement by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var openSource by remember { mutableStateOf(false) }

    fun stopAllDetections() {
        DetectionItems.entries.forEach { it.stop(activity.detectionFunctions) }
    }

    /**
     * 开始一轮新的监测：清空既往异常结果并重新挂载全部回调。
     * 所有检测项全量开启；"重置检测结果"与"重新检测"共用该逻辑。
     */
    fun startAllDetections() {
        stopAllDetections()
        issues.clear()
        DetectionItems.entries.forEach { entry ->
            entry.start(activity.detectionFunctions) { item, detail ->
                issues[item] = detail
            }
        }
    }

    // 进入页面即开始全量检测
    DisposableEffect(Unit) {
        startAllDetections()
        // 无障碍项为实时状态(非粘性)：服务全部停用后自动移除卡片
        activity.detectionFunctions.setEnvironmentClearCallback { item ->
            issues.remove(item)
        }
        onDispose { stopAllDetections() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
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
        // 内容不足一屏时垂直居中(以顶栏下方区域为参考)，超屏时可滚动且不被顶栏遮挡
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center
        ) {
            when {
                // 监测中，暂无异常
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
                // 检出异常：卡片列表(整屏居中，可滚动)
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
    if (openSource) {
        BackHandler { openSource = false }
        OpenSourceLicensesScreen(onBack = { openSource = false })
    }
}

// 异常结果卡片：白色背景 + 灰色描边，detail 非空时附详情行(如小窗包名)
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
    val libraries = listOf(
        LibraryInfo("AndroidX Compose BOM", "Apache-2.0", "Copyright (c) 2011 The Android Open Source Project"),
        LibraryInfo("AndroidX Activity Compose", "Apache-2.0", "Copyright (c) 2011 The Android Open Source Project"),
        LibraryInfo("AndroidX Compose Material3", "Apache-2.0", "Copyright (c) 2019 The Android Open Source Project"),
        LibraryInfo("AndroidX Compose UI", "Apache-2.0", "Copyright (c) 2011 The Android Open Source Project"),
        LibraryInfo("AndroidX Compose UI Graphics", "Apache-2.0", "Copyright (c) 2011 The Android Open Source Project"),
        LibraryInfo("AndroidX Compose Material Icons Core", "Apache-2.0", "Copyright (c) 2019 The Android Open Source Project"),
        LibraryInfo("AndroidX Core KTX", "Apache-2.0", "Copyright (c) 2011 The Android Open Source Project"),
        LibraryInfo("AndroidX Lifecycle Runtime KTX", "Apache-2.0", "Copyright (c) 2011 The Android Open Source Project"),
        LibraryInfo("AndroidX MediaRouter", "Apache-2.0", "Copyright (c) 2011 The Android Open Source Project"),
        LibraryInfo("Kotlin Coroutines", "Apache-2.0", "Copyright (c) 2016 JetBrains s.r.o."),
        LibraryInfo("Kotlin Stdlib", "Apache-2.0", "Copyright (c) 2016 JetBrains s.r.o.")
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.open_source)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(libraries) { lib ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Text(
                        text = lib.name,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "${stringResource(R.string.license)}: ${lib.license}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = lib.copyright,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

data class LibraryInfo(
    val name: String,
    val license: String,
    val copyright: String
)
