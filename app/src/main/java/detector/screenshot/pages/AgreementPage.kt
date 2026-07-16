package detector.screenshot.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import detector.screenshot.R
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgreementCompose(
    onAgree: () -> Unit,
    onDisagree: () -> Unit
) {
    var equationText by remember { mutableStateOf("") }
    var correctRoots by remember { mutableStateOf(listOf<Double>()) }
    var inputText by remember { mutableStateOf("") }

    fun generateEquation() {
        var a: Double
        var b: Double
        var c: Double
        var delta: Double
        var attempts = 0
        val maxAttempts = 200

        do {
            a = (Random.nextInt(-499, 500) / 10.0).let { if (it == 0.0) 1.0 else it }
            b = (Random.nextInt(-499, 500) / 10.0)
            c = (Random.nextInt(-499, 500) / 10.0)
            delta = b * b - 4 * a * c
            attempts++
            if (attempts >= maxAttempts) {
                val target = (b * b) / (4 * a)
                c = target + if (Random.nextBoolean()) 0.1 else -0.1
                c = (c * 10).toInt() / 10.0
                delta = b * b - 4 * a * c
                if (delta < 0) {
                    c = 0.0
                    delta = b * b - 4 * a * c
                }
                break
            }
        } while (delta < 0)

        val sqrtDelta = sqrt(delta)
        val root1 = (-b + sqrtDelta) / (2 * a)
        val root2 = (-b - sqrtDelta) / (2 * a)

        fun roundToOneDecimal(value: Double): Double = ((value + 1e-9) * 10).roundToInt() / 10.0


        val r1 = roundToOneDecimal(root1)
        val r2 = roundToOneDecimal(root2)
        correctRoots = listOf(r1, r2)

        // 格式化方程字符串（仅显示）
        fun formatNumber(v: Double): String {
            val intPart = v.toInt()
            return if (v == intPart.toDouble()) intPart.toString() else "%.1f".format(v)
        }

        fun formatCuff(value: Double, isFirst: Boolean): String {
            if (value == 0.0) return ""
            val sign = when {
                value < 0 -> if (isFirst) "-" else "- "
                else -> if (isFirst) "" else "+ "
            }
            val absVal = kotlin.math.abs(value)
            val num = formatNumber(absVal)
            return when {
                isFirst && absVal == 1.0 -> if (value < 0) "-" else ""
                !isFirst && absVal == 1.0 -> if (value < 0) "- " else "+ "
                else -> "$sign$num"
            }.trim()
        }

        val aStr = formatCuff(a, true)
        val bStr = formatCuff(b, false)
        val cStr = formatCuff(c, false)

        val parts = mutableListOf<String>()
        if (aStr.isNotEmpty()) parts.add("${aStr}x²") else parts.add("x²")
        if (bStr.isNotEmpty()) parts.add("${bStr}x")
        if (cStr.isNotEmpty()) parts.add(cStr)
        if (parts.isEmpty()) parts.add("0")

        equationText = parts.joinToString(" ") + " = 0"
    }

    LaunchedEffect(Unit) {
        generateEquation()
    }

    val inputCorrect by remember {
        derivedStateOf {
            val userInput = inputText.toDoubleOrNull()
            if (userInput == null) false
            else {
                correctRoots.any { root ->
                    kotlin.math.abs(root - userInput) < 1e-6
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.user_agreement)) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(start = 24.dp, end = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val density = LocalDensity.current
            val windowHeightPx = LocalWindowInfo.current.containerSize.height
            val windowHeightDp = with(density) { windowHeightPx.toDp() }
            val maxHeight = windowHeightDp * 0.9f

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                UserAgreement()
                Text(
                    text = stringResource(R.string.solve_equation),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    fontSize = 14.sp,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = "求解方程：$equationText",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.x_value)) },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onDisagree,
                        modifier = Modifier.weight(0.6f)
                    ) {
                        Text(stringResource(R.string.disagree_and_exit))
                    }
                    Button(
                        onClick = onAgree,
                        modifier = Modifier.weight(0.4f),
                        enabled = inputCorrect
                    ) {
                        Text(stringResource(R.string.agree_and_continue))
                    }
                }
            }
        }
    }
}

@Composable
fun UserAgreement() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "截图检测（以下简称“本软件”）用户协议由本软件开发者（以下简称“我们”）与用户签订。用户应认真阅读、充分理解本协议中各条款，请用户审慎阅读并选择接受或不接受本协议（未成年人应在法定监护人陪同下阅读）。",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )
        Text(
            text = "除非您接受本协议条款，否则您无权下载、安装或使用本软件及其相关服务。您的安装、使用行为将视为对本协议的接受，并同意接受本协议各项条款的约束。",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
        Text(
            text = "1、关于本软件\n" +
                    "本软件是一款专注于屏幕行为检测的安全工具。本软件通过调用系统标准 API，帮助用户实时感知设备上可能发生的截屏、录屏、投屏等屏幕内容捕获行为，并监测设备环境中的潜在风险状态，从而提升用户的隐私与数据安全意识。",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
        Text(
            text = "2、用户禁止行为\n" +
                    "无论在任何情况下，用户都不得作出本软件禁止的行为：\n" +
                    "（1）禁止利用本软件进行任何涉及色情、暴力、种族歧视、恐怖主义等不良内容的传播和宣传；\n" +
                    "（2）禁止出售本软件的任何部分，包括但不限于软件本体，源代码及使用方法等。",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
        Text(
            text = "3、免责声明\n" +
                    "（1）本软件旨在个人测试与学习开发，软件检测结果仅供安全研究参考，请勿用于商业用途，请勿用于非法用途；\n" +
                    "（2）本软件源代码公开，您需要仔细甄别当前软件是否经过他人修改或篡改；\n" +
                    "（3）本软件完全基于您个人意愿使用，您应该对自己的使用行为和所有结果承担全部责任；\n" +
                    "（4）本软件并不保证与所有操作系统或硬件设备兼容。我们不对因使用本软件而产生的任何技术或安全问题承担责任；\n" +
                    "（5）本软件不需要任何网络权限，所有检测功能均在本地设备上运行，不会上传、存储或共享任何用户数据。我们不收集任何个人信息、截图内容、设备标识符或使用行为数据。您的所有数据仅存于本地；\n" +
                    "（6）用户因第三方如手机故障、系统不稳定性及其他不可抗力原因而遭受的经济损失，我们不承担责任。",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
        Text(
            text = "4、其他条款\n" +
                    "（1）我们保留随时修改、增加、删除本协议中的内容而不另行通知的权利。用户可以在本软件的最新版本中查阅相关条款协议。本协议条款变更后，如果用户继续使用本软件，即视为用户已同意修改后的协议。如果用户不同意修改后的协议，应当立即停止使用本软件；\n" +
                    "（2）本协议所有的条款标题仅为阅读方便，本身并无实际含义，不能作为本协议涵义解释的依据；\n" +
                    "（3）我们保留对于本协议的最终解释权。",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
    }
}