package cn.reviewfault.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.time.LocalDate
import cn.reviewfault.app.data.LearningPreferences
import cn.reviewfault.app.data.MathErrorDraft
import cn.reviewfault.app.data.MemoryCardDraft
import cn.reviewfault.app.data.StudyRow
import cn.reviewfault.app.data.TagRow
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: AppViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            ReviewFaultTheme { ReviewFaultApp(state, model) }
        }
    }
}

@Composable
private fun AnswerAnalysis(row: StudyRow) {
    val fields = if (row.kind == "math_problem") listOf(
        "原始错误" to row.profile.firstAttempt,
        "错误触发点" to row.profile.errorTrigger,
        "可迁移通法" to row.profile.generalMethod,
        "验算方法" to row.profile.verification,
        "后续变式" to row.profile.transferPrompt,
    ) else listOf(
        "机制" to row.profile.mechanism,
        "条件与边界" to row.profile.conditions,
        "易混辨析" to row.profile.contrast,
        "例子" to row.profile.example,
        "常见误区" to row.profile.commonTrap,
        "迁移问题" to row.profile.transferPrompt,
        "记忆钩子" to row.profile.mnemonic,
    )
    val visible = fields.filter { it.second.isNotBlank() }
    if (visible.isEmpty() && row.profile.archetype != "scale_mapping") return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (row.profile.archetype == "scale_mapping") {
            scaleMappingRows(row.profile.structuredPayload).forEach { cells ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(cells.first, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(cells.second, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(cells.third, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        visible.forEach { (label, value) ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFD4F36A),
    onPrimary = Color(0xFF090A0C),
    primaryContainer = Color(0xFF25292E),
    onPrimaryContainer = Color(0xFFE8EBEE),
    secondary = Color(0xFFC8CDD2),
    onSecondary = Color(0xFF202327),
    secondaryContainer = Color(0xFF25292D),
    onSecondaryContainer = Color(0xFFE5E8EB),
    tertiary = Color(0xFFC6CCD4),
    background = Color(0xFF090A0C),
    onBackground = Color(0xFFF0F2F3),
    surface = Color(0xFF121417),
    onSurface = Color(0xFFE9ECEE),
    surfaceVariant = Color(0xFF1A1D21),
    onSurfaceVariant = Color(0xFF9DA3A9),
    outline = Color(0xFF686E74),
    outlineVariant = Color(0xFF2A2E33),
    error = Color(0xFFE6A29B),
)
private val AppTypography = Typography(
    headlineLarge = Typography().headlineLarge.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = Typography().headlineMedium.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = Typography().headlineSmall.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = Typography().labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
private fun ReviewFaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = AppTypography) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}

@Composable
private fun ReviewFaultApp(state: AppUiState, model: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val compact = LocalConfiguration.current.screenWidthDp < 600
    BackHandler(enabled = state.destination == AppDestination.Review) {
        model.finishReviewSession()
    }
    LaunchedEffect(state.message) {
        state.message?.let { message ->
            val result = snackbar.showSnackbar(message, if (state.deletion != null) "撤销" else null)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) model.undoDeletion()
            model.clearMessage(message)
        }
    }
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AnimatedVisibility(
                visible = compact && state.destination != AppDestination.Review,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) { AppNavigation(state.destination, model::navigate) }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (!compact && state.destination != AppDestination.Review) {
                AppNavigationRail(state.destination, model::navigate)
                VerticalDivider(Modifier.fillMaxSize().width(1.dp))
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            AnimatedContent(
                targetState = state.destination,
                transitionSpec = {
                    (fadeIn() + slideInVertically { it / 32 }) togetherWith
                        (fadeOut() + slideOutVertically { -it / 48 })
                },
                label = "页面切换",
            ) { destination ->
                when (destination) {
                    AppDestination.Today -> TodayScreen(state, model)
                    AppDestination.Insights -> InsightsScreen(state)
                    AppDestination.Library -> LibraryScreen(state, model)
                    AppDestination.Add -> AddScreen(state, model)
                    AppDestination.Settings -> SettingsScreen(state, model)
                    AppDestination.Review -> ReviewScreen(state, model)
                }
            }
            }
        }
    }
}

@Composable
private fun AppNavigationRail(selected: AppDestination, navigate: (AppDestination) -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
        listOf(
            Triple(AppDestination.Today, "今日", Icons.Default.Home),
            Triple(AppDestination.Insights, "洞察", Icons.Default.Analytics),
            Triple(AppDestination.Library, "题库", Icons.AutoMirrored.Filled.LibraryBooks),
            Triple(AppDestination.Add, "添加", Icons.Default.AddCircle),
            Triple(AppDestination.Settings, "设置", Icons.Default.Settings),
        ).forEach { (destination, label, icon) ->
            NavigationRailItem(
                selected = selected == destination,
                onClick = { navigate(destination) },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun AppNavigation(selected: AppDestination, navigate: (AppDestination) -> Unit) {
    val destinations = listOf(
        Triple(AppDestination.Today, "今日", Icons.Default.Home),
        Triple(AppDestination.Insights, "洞察", Icons.Default.Analytics),
        Triple(AppDestination.Library, "题库", Icons.AutoMirrored.Filled.LibraryBooks),
        Triple(AppDestination.Add, "添加", Icons.Default.AddCircle),
        Triple(AppDestination.Settings, "设置", Icons.Default.Settings),
    )
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            destinations.forEach { (destination, label, icon) ->
                NavigationBarItem(
                    selected = selected == destination,
                    onClick = { navigate(destination) },
                    icon = { Icon(icon, contentDescription = label) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().widthIn(max = 760.dp),
        contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 32.dp),
    ) { item { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { content() } } }
}

@Composable
private fun PageHeader(eyebrow: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TodayScreen(state: AppUiState, model: AppViewModel) = Page {
    PageHeader("ReviewFault", "今天，专注一小步", "复习顺序已经排好，你只需要开始。")
    val dueTotal = state.summary.overdue + state.summary.dueToday
    val total = dueTotal + state.summary.newItems
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (total == 0) "今天已清空" else "今日学习", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (total == 0) "没有待处理任务，去积累下一次进步。"
                        else "本轮约 ${state.summary.estimatedMinutes} 分钟 · 到时自然收尾",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.summary.deferredDueMinutes > 0) {
                        Text(
                            "另有约 ${state.summary.deferredDueMinutes} 分钟到期内容，暂不加入新内容",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        if (total == 0) Icons.Default.CheckCircle else Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(12.dp).size(28.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryPill("逾期", state.summary.overdue, Modifier.weight(1f))
                SummaryPill("到期", state.summary.dueToday, Modifier.weight(1f))
                SummaryPill("新内容", state.summary.newItems, Modifier.weight(1f))
            }
            Button(
                onClick = {
                    when {
                        dueTotal > 0 -> model.startReview()
                        state.summary.newItems > 0 -> model.startNewLearning()
                        else -> model.navigate(AppDestination.Add)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(when {
                    dueTotal > 0 -> "开始专注轮次"
                    state.summary.newItems > 0 -> "学习新内容"
                    else -> "添加第一条内容"
                })
                Spacer(Modifier.width(8.dp))
                Icon(if (total == 0) Icons.Default.Add else Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, Modifier.size(19.dp))
            }
            if (dueTotal > 0 && state.summary.newItems > 0) {
                OutlinedButton(
                    onClick = model::startNewLearning,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Default.School, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("学习新内容 · ${state.summary.newItems} 条待开始")
                }
            }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text("学习负载预报", style = MaterialTheme.typography.titleMedium)
                Text("提前看见波峰，更容易保持节奏", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ForecastMetric("明日", state.summary.tomorrowDue, Modifier.weight(1f))
                ForecastMetric("未来 7 天", state.summary.nextSevenDaysDue, Modifier.weight(1f))
            }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)),
    ) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("你的学习节奏", style = MaterialTheme.typography.titleMedium)
                Text(
                    "408 ${memoryPresetLabel(state.preferences.memoryPreset)} · 数学 ${mathIntensityLabel(state.preferences.mathIntensity)} · 每次 ${state.preferences.sessionMinutes} 分钟",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { model.navigate(AppDestination.Settings) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "调整学习节奏")
            }
        }
    }
}

@Composable
private fun ForecastMetric(label: String, count: Int, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$count 条", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun InsightsScreen(state: AppUiState) = Page {
    val insights = state.insights
    PageHeader("学习洞察", "看见你的积累", "用趋势理解节奏，不用单次结果评价自己。")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        InsightMetric("今日复习", insights.reviewsToday.toString(), "次", Modifier.weight(1f))
        InsightMetric("近 7 日正确", "${insights.accuracyPercent}", "%", Modifier.weight(1f))
        InsightMetric("连续学习", insights.streakDays.toString(), "天", Modifier.weight(1f))
    }
    GlassPanel("复习活跃度", "过去 7 天完成的复习次数") {
        MiniBarChart(
            values = insights.days.map { it.reviews },
            labels = insights.days.map { it.label },
            color = MaterialTheme.colorScheme.primary,
        )
    }
    GlassPanel("未来负载", "今天起 7 天的到期内容") {
        MiniBarChart(
            values = insights.days.map { it.due },
            labels = insights.days.map { it.dueLabel },
            color = MaterialTheme.colorScheme.secondary,
        )
    }
    GlassPanel("知识库进度", "稳定度达到 14 天且至少复习 3 次，记为熟练") {
        val progress = if (insights.activeItems == 0) 0f
            else insights.masteredItems.toFloat() / insights.activeItems
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("${insights.masteredItems} / ${insights.activeItems}", style = MaterialTheme.typography.headlineMedium)
                Text("熟练内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("累计 ${insights.totalReviews} 次复习", color = MaterialTheme.colorScheme.primary)
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(8.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
    GlassPanel("学科分布", "各学科内容量与熟练占比") {
        if (insights.subjects.isEmpty()) {
            Text("添加内容后，这里会出现学科分布。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else insights.subjects.forEach { subject ->
            val progress = if (subject.total == 0) 0f else subject.mastered.toFloat() / subject.total
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(subjectLabel(subject.subject), style = MaterialTheme.typography.titleSmall)
                    Text("${subject.mastered}/${subject.total}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)),
                    color = if (subject.subject == "math") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InsightMetric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 17.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text(unit, modifier = Modifier.padding(start = 3.dp, bottom = 3.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GlassPanel(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .76f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .68f)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun MiniBarChart(values: List<Int>, labels: List<String>, color: Color) {
    val maximum = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(
        modifier = Modifier.fillMaxWidth().height(142.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { index, value ->
            val fraction by animateFloatAsState(value.toFloat() / maximum, label = "图表柱")
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(value.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier.fillMaxWidth().height((10 + 86 * fraction).dp)
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                        .background(color),
                )
                Spacer(Modifier.height(7.dp))
                Text(labels.getOrElse(index) { "" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SummaryPill(label: String, count: Int, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f)) {
        Column(Modifier.padding(vertical = 11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(targetState = count, label = "$label 数量") { value ->
                Text(value.toString(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LibraryScreen(state: AppUiState, model: AppViewModel) {
    val query by model.searchQuery.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize().widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 32.dp),
    ) {
        item { PageHeader("资料库", "题库", "找到、整理并继续打磨你的知识。") }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { model.searchQuery.value = it },
                placeholder = { Text("搜索题干、考点、来源或标签") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "题库搜索" },
            )
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val filter = state.libraryFilter
                FilterChip(selected = filter.kinds.isEmpty() && filter.status == "all", onClick = { model.setLibraryFilter() }, label = { Text("全部") })
                FilterChip(selected = filter.kinds == setOf("memory_card"), onClick = { model.setLibraryFilter(kind = "memory_card") }, label = { Text("408") })
                FilterChip(selected = filter.kinds == setOf("math_problem"), onClick = { model.setLibraryFilter(kind = "math_problem") }, label = { Text("数学") })
                FilterChip(selected = filter.status == "due", onClick = { model.setLibraryFilter(status = "due") }, label = { Text("待复习") })
            }
        }
        if (state.availableTags.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.availableTags.forEach { tag ->
                        FilterChip(
                            selected = tag.id in state.libraryFilter.tagIds,
                            onClick = { model.toggleTagFilter(tag.id) },
                            label = { Text("${tag.name} · ${tag.itemCount}") },
                        )
                    }
                }
            }
        }
        item {
            AnimatedVisibility(visible = state.selectedIds.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.error.copy(alpha = .10f)) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("已选择 ${state.selectedIds.size} 项", Modifier.weight(1f).padding(start = 6.dp))
                        OutlinedButton(onClick = model::deleteSelected) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("移入回收站")
                        }
                    }
                }
            }
        }
        if (!state.loading && state.library.isEmpty()) {
            item { EmptyLibrary(onAdd = { model.navigate(AppDestination.Add) }) }
        } else {
            items(state.library, key = StudyRow::id) { row -> LibraryCard(row, state.selectedIds.contains(row.id), model::toggleSelection) }
        }
    }
}

@Composable
private fun LibraryCard(row: StudyRow, selected: Boolean, toggle: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f)),
    ) {
        Row(Modifier.fillMaxWidth().clickable { toggle(row.id) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(selected, { toggle(row.id) }, Modifier.semantics { contentDescription = "选择题目" })
            Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    if (row.kind == "math_problem") "数学错题 · ${row.profile.knowledgePoint.ifBlank { "未标注考点" }}"
                    else "${subjectLabel(row.subject)} · ${archetypeLabel(row.profile.archetype)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(row.prompt.ifBlank { "图片题面" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                val source = listOf(row.profile.sourceTitle, row.profile.sourceChapter, row.profile.sourceLocator)
                    .filter(String::isNotBlank).joinToString(" · ")
                if (source.isNotBlank()) Text(source, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (row.tags.isNotEmpty()) Text(row.tags.take(3).joinToString("  ") { "#$it" }, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
                Text(
                    if (row.state == 0) "等待首次学习" else "已复习 ${row.repetitions} 次",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 52.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(Icons.AutoMirrored.Filled.LibraryBooks, null, Modifier.padding(16.dp).size(34.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text("题库还是空的", style = MaterialTheme.typography.titleLarge)
        Text("从一道数学错题或一张 408 记忆卡开始。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onAdd) { Text("去添加") }
    }
}

@Composable
private fun AddScreen(state: AppUiState, model: AppViewModel) = Page {
    val context = LocalContext.current
    var kind by rememberSaveable { mutableStateOf("choice") }
    var subject by rememberSaveable { mutableStateOf("operating_systems") }
    var archetype by rememberSaveable { mutableStateOf("concept") }
    var knowledgePoint by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var answer by rememberSaveable { mutableStateOf("") }
    var pointsText by rememberSaveable { mutableStateOf("") }
    var hintsText by rememberSaveable { mutableStateOf("") }
    var mechanism by rememberSaveable { mutableStateOf("") }
    var conditions by rememberSaveable { mutableStateOf("") }
    var contrast by rememberSaveable { mutableStateOf("") }
    var example by rememberSaveable { mutableStateOf("") }
    var commonTrap by rememberSaveable { mutableStateOf("") }
    var transferPrompt by rememberSaveable { mutableStateOf("") }
    var mnemonic by rememberSaveable { mutableStateOf("") }
    var sourceType by rememberSaveable { mutableStateOf("notes") }
    var sourceTitle by rememberSaveable { mutableStateOf("") }
    var sourceChapter by rememberSaveable { mutableStateOf("") }
    var sourceLocator by rememberSaveable { mutableStateOf("") }
    var sourceYear by rememberSaveable { mutableStateOf("") }
    var tagsText by rememberSaveable { mutableStateOf("") }
    var analysisExpanded by rememberSaveable { mutableStateOf(false) }
    var reviewDetailsExpanded by rememberSaveable { mutableStateOf(false) }
    var understandingExpanded by rememberSaveable { mutableStateOf(false) }
    var sourceExpanded by rememberSaveable { mutableStateOf(false) }
    var firstAttempt by rememberSaveable { mutableStateOf("") }
    var errorTrigger by rememberSaveable { mutableStateOf("") }
    var errorReason by rememberSaveable { mutableStateOf("concept") }
    var keyHint by rememberSaveable { mutableStateOf("") }
    var generalMethod by rememberSaveable { mutableStateOf("") }
    var verification by rememberSaveable { mutableStateOf("") }
    var targetSeconds by rememberSaveable { mutableStateOf("") }
    var imageUris by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var captureUri by rememberSaveable { mutableStateOf<String?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        imageUris = uris.take(5).map(Uri::toString)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) captureUri?.let { imageUris = listOf(it) }
    }
    val points = splitEditorLines(pointsText)
    val hints = splitEditorLines(hintsText)
    val tags = scientificTags(
        raw = tagsText,
        subject = if (kind == "math") "math" else subject,
        archetype = if (kind == "math") "math_error" else archetype,
        knowledgePoint = knowledgePoint,
        sourceTitle = sourceTitle,
        sourceChapter = sourceChapter,
        errorReason = if (kind == "math") errorReason else null,
    )
    PageHeader("结构化录入", "把知识变成可检验的任务", "字段会决定复习时如何提问、评分与归档。")
    if (kind == "choice") {
        AddChoiceCard(Icons.Default.Image, "记录数学错题", "题面、错因、通法、验算与迁移",
            MaterialTheme.colorScheme.primaryContainer) { kind = "math" }
        AddChoiceCard(Icons.Default.School, "新建 408 知识卡", "按概念、量纲、公式等形式主动回忆",
            MaterialTheme.colorScheme.secondaryContainer) { kind = "memory" }
    } else if (kind == "memory") {
        EditorSection("知识定位", "一张卡只检验一个清晰目标") {
            DropdownChoice("科目", listOf(
                "data_structures" to "数据结构", "computer_organization" to "组成原理",
                "operating_systems" to "操作系统", "computer_networks" to "计算机网络",
            ), subject) { subject = it }
            DropdownChoice("知识形式", listOf(
                "concept" to "概念辨析", "scale_mapping" to "量纲映射", "formula_rule" to "公式规则",
                "enumeration" to "枚举", "comparison" to "对比", "process" to "流程",
            ), archetype) { archetype = it }
            OutlinedTextField(knowledgePoint, { knowledgePoint = it }, label = { Text("考点 / 知识点") },
                placeholder = { Text("可选，例如：吞吐量与响应时间") }, modifier = Modifier.fillMaxWidth())
        }
        EditorSection("主动回忆", "先写问题和答案，其他内容稍后补充") {
            OutlinedTextField(prompt, { prompt = it }, label = { Text("回忆问题") },
                placeholder = { Text(archetypePrompt(archetype)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            OutlinedTextField(answer, { answer = it }, label = { Text("核心答案") },
                modifier = Modifier.fillMaxWidth(), minLines = 3)
        }
        OutlinedButton(onClick = { reviewDetailsExpanded = !reviewDetailsExpanded }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(if (reviewDetailsExpanded) "收起评分与提示" else "补充评分要点与分层提示")
            Spacer(Modifier.width(6.dp)); Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
        }
        AnimatedVisibility(reviewDetailsExpanded) {
            EditorSection("评分与提示", "复习时逐项核对，记录命中率") {
                OutlinedTextField(pointsText, { pointsText = it },
                    label = { Text(if (archetype == "scale_mapping") "映射项（每行：名称 | 指数 | 中文量级）" else "评分要点（每行一个）") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3)
                OutlinedTextField(hintsText, { hintsText = it }, label = { Text("分层提示（每行一层）") },
                    supportingText = { Text("从方向提示到关键线索") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            }
        }
        OutlinedButton(onClick = { understandingExpanded = !understandingExpanded }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(if (understandingExpanded) "收起理解与迁移" else "补充理解与迁移（可选）")
            Spacer(Modifier.width(6.dp)); Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
        }
        AnimatedVisibility(understandingExpanded) {
            EditorSection("理解与迁移", "核对答案后再补也可以") {
                OutlinedTextField(mechanism, { mechanism = it }, label = { Text("为什么 / 工作机制") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(conditions, { conditions = it }, label = { Text("适用条件与边界") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(contrast, { contrast = it }, label = { Text("易混概念与差异") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(example, { example = it }, label = { Text("最小例子 / 边界例子") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(commonTrap, { commonTrap = it }, label = { Text("常见误区") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(transferPrompt, { transferPrompt = it }, label = { Text("迁移问题") }, placeholder = { Text("换一个条件后，结论如何变化？") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(mnemonic, { mnemonic = it }, label = { Text("记忆钩子（可选）") }, modifier = Modifier.fillMaxWidth())
            }
        }
        OutlinedButton(onClick = { sourceExpanded = !sourceExpanded }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(if (sourceExpanded) "收起来源与标签" else "补充来源与标签（可选）")
            Spacer(Modifier.width(6.dp)); Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
        }
        AnimatedVisibility(sourceExpanded) {
            EditorSection("来源与标签", "支持按资料、章节和标签快速回溯") {
                SourceFields(sourceType, { sourceType = it }, sourceTitle, { sourceTitle = it }, sourceChapter, { sourceChapter = it }, sourceLocator, { sourceLocator = it }, sourceYear, { sourceYear = it })
                TagInput(tagsText, { tagsText = it }, state.availableTags.map(TagRow::name), "会记住已有标签，输入前缀即可补全")
            }
        }
        val memoryReady = prompt.isNotBlank() && answer.isNotBlank()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ kind = "choice" }, Modifier.height(48.dp)) { Text("返回") }
            Button({
                val resolvedAnswer = answer.ifBlank { points.joinToString("\n") }
                model.createMemoryCard(MemoryCardDraft(
                    templateType = memoryTemplate(archetype, hints, points), archetype = archetype,
                    subject = subject, knowledgePoint = knowledgePoint, prompt = prompt,
                    answer = resolvedAnswer, hints = hints, answerPoints = points,
                    mechanism = mechanism, conditions = conditions, contrast = contrast,
                    example = example, commonTrap = commonTrap, transferPrompt = transferPrompt,
                    mnemonic = mnemonic,
                    structuredPayload = structuredPayload(archetype, points, answer, conditions, example),
                    sourceType = sourceType, sourceTitle = sourceTitle, sourceChapter = sourceChapter,
                    sourceLocator = sourceLocator, sourceYear = sourceYear.toIntOrNull(), tags = tags,
                ))
            }, Modifier.height(48.dp), enabled = memoryReady) {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("保存知识卡")
            }
        }
    } else {
        EditorSection("快速记录", "先保住题面和定位信息，深度分析可选") {
            OutlinedTextField(knowledgePoint, { knowledgePoint = it }, label = { Text("考点 / 题型") },
                placeholder = { Text("例如：二重积分换元") }, modifier = Modifier.fillMaxWidth())
            SourceFields(sourceType, { sourceType = it }, sourceTitle, { sourceTitle = it },
                sourceChapter, { sourceChapter = it }, sourceLocator, { sourceLocator = it },
                sourceYear, { sourceYear = it })
            TagInput(tagsText, { tagsText = it }, state.availableTags.map(TagRow::name), "输入标签，已有标签会自动补全")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ gallery.launch("image/*") }, Modifier.height(48.dp)) {
                    Icon(Icons.Default.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("选择图片")
                }
                OutlinedButton({
                    val file = File(context.cacheDir, "capture-${System.currentTimeMillis()}.jpg")
                    val uri = Uri.parse("content://${context.packageName}.capture/capture/${file.name}")
                    captureUri = uri.toString(); camera.launch(uri)
                }, Modifier.height(48.dp)) { Text("拍照") }
            }
            Text("已选择 ${imageUris.size} / 5 张", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { analysisExpanded = !analysisExpanded }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(if (analysisExpanded) "收起错因分析" else "补充错因与迁移")
            Spacer(Modifier.width(6.dp)); Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
        }
        AnimatedVisibility(visible = analysisExpanded, enter = fadeIn() + slideInVertically { -it / 8 }, exit = fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ChoiceRow("主要错因", listOf(
                    "concept" to "概念", "approach" to "思路", "calculation" to "计算",
                    "misread" to "审题", "forgotten_fact" to "结论", "timeout" to "超时", "other" to "其他",
                ), errorReason) { errorReason = it }
                OutlinedTextField(firstAttempt, { firstAttempt = it }, label = { Text("我的错误步骤 / 原始作答") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3)
                OutlinedTextField(errorTrigger, { errorTrigger = it }, label = { Text("错误触发点：为什么会错") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(answer, { answer = it }, label = { Text("正确解法") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                OutlinedTextField(keyHint, { keyHint = it }, label = { Text("下次第一步提示") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(generalMethod, { generalMethod = it }, label = { Text("可迁移的通法") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(verification, { verification = it }, label = { Text("验算 / 合理性检查") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(transferPrompt, { transferPrompt = it }, label = { Text("变式或迁移任务") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(targetSeconds, { targetSeconds = it.filter(Char::isDigit) },
                    label = { Text("目标用时（秒）") }, modifier = Modifier.fillMaxWidth())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ kind = "choice" }, Modifier.height(48.dp)) { Text("返回") }
            Button({
                model.createMathProblem(imageUris.map(Uri::parse), MathErrorDraft(
                    knowledgePoint = knowledgePoint, sourceType = sourceType, sourceTitle = sourceTitle,
                    sourceChapter = sourceChapter, sourceLocator = sourceLocator, sourceYear = sourceYear.toIntOrNull(),
                    solution = answer, firstAttempt = firstAttempt, errorTrigger = errorTrigger,
                    errorReason = errorReason, keyHint = keyHint, generalMethod = generalMethod,
                    verification = verification, transferPrompt = transferPrompt,
                    targetSeconds = targetSeconds.toIntOrNull(), tags = tags,
                ))
            }, Modifier.height(48.dp), enabled = imageUris.isNotEmpty()) {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("保存错题")
            }
        }
    }
}

@Composable
private fun EditorSection(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SourceFields(
    sourceType: String, setSourceType: (String) -> Unit,
    title: String, setTitle: (String) -> Unit,
    chapter: String, setChapter: (String) -> Unit,
    locator: String, setLocator: (String) -> Unit,
    year: String, setYear: (String) -> Unit,
) {
    DropdownChoice("来源类型", listOf(
        "textbook" to "教材", "course" to "课程", "past_exam" to "真题",
        "practice" to "习题", "notes" to "笔记", "other" to "其他",
    ), sourceType, setSourceType)
    OutlinedTextField(title, setTitle, label = { Text("资料名称") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(chapter, setChapter, label = { Text("章节") }, modifier = Modifier.weight(1f))
        OutlinedTextField(locator, setLocator, label = { Text("页码 / 题号") }, modifier = Modifier.weight(1f))
    }
    OutlinedTextField(year, { setYear(it.filter(Char::isDigit).take(4)) }, label = { Text("年份（可选）") },
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun AddChoiceCard(icon: ImageVector, title: String, subtitle: String, accent: Color, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(8.dp), color = accent) {
                Icon(icon, contentDescription = null, Modifier.padding(14.dp).size(27.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsScreen(state: AppUiState, model: AppViewModel) {
    var newLimit by remember(state.preferences) { mutableStateOf(state.preferences.dailyNewMemoryLimit.toString()) }
    var minutes by remember(state.preferences) { mutableStateOf(state.preferences.sessionMinutes.toString()) }
    var memoryPreset by remember(state.preferences) { mutableStateOf(state.preferences.memoryPreset) }
    var mathIntensity by remember(state.preferences) { mutableStateOf(state.preferences.mathIntensity) }
    var schedulerGeneration by remember(state.preferences) {
        mutableIntStateOf(state.preferences.schedulerGeneration)
    }
    var includeMemory by remember(state.preferences) { mutableStateOf(state.preferences.includeMemoryCards) }
    var includeMath by remember(state.preferences) { mutableStateOf(state.preferences.includeMathProblems) }
    var subjects by remember(state.preferences) { mutableStateOf(state.preferences.enabledSubjects) }
    val theme = 2
    var reminder by remember(state.reminderEnabled) { mutableStateOf(state.reminderEnabled) }
    var time by remember(state.reminderTime) { mutableStateOf(state.reminderTime) }
    var syncEndpoint by remember(state.syncEndpoint) { mutableStateOf(state.syncEndpoint) }
    var accountEmail by rememberSaveable { mutableStateOf("") }
    var accountPassword by rememberSaveable { mutableStateOf("") }
    var invitationCode by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(model::exportBackup) }
    val restoreBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(model::restoreBackup) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) reminder = false
    }
    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        state.downloadedUpdatePath?.let { path ->
            if (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) {
                launchUpdateInstaller(context, path)
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 32.dp),
    ) {
        item { PageHeader("偏好", "设置", "只展开你现在想调整的部分。") }
        item {
            SettingsSection("学习计划", "新内容上限、时长与算法节奏", Icons.Default.Tune, initiallyExpanded = true) {
                OutlinedTextField(newLimit, { newLimit = it }, label = { Text("每日新 408 上限") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(minutes, { minutes = it }, label = { Text("单次学习时长（分钟）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                PreferenceSwitch("包含 408 记忆卡", includeMemory,
                    enabled = includeMath || !includeMemory) { includeMemory = it }
                PreferenceSwitch("包含数学错题", includeMath,
                    enabled = includeMemory || !includeMath) { includeMath = it }
                AnimatedVisibility(includeMemory) {
                    MultiChoiceRow(
                        "启用的 408 科目",
                        listOf(
                            "data_structures" to "数据结构",
                            "computer_organization" to "组成原理",
                            "operating_systems" to "操作系统",
                            "computer_networks" to "计算机网络",
                        ), subjects,
                    ) { subject ->
                        subjects = if (subject in subjects && subjects.size > 1) subjects - subject
                        else subjects + subject
                    }
                }
                ChoiceRow("408 节奏", listOf("time_saving" to "省时", "balanced" to "均衡", "reinforced" to "强化"), memoryPreset) { memoryPreset = it }
                ChoiceRow("数学节奏", listOf("intensive" to "密集", "balanced" to "均衡", "relaxed" to "舒缓"), mathIntensity) { mathIntensity = it }
                PreferenceSwitch(
                    "使用 v0.3 调度",
                    schedulerGeneration == 3,
                ) { schedulerGeneration = if (it) 3 else 2 }
                Text(
                    if (schedulerGeneration == 3) "记录 v3 决策快照，可随时切回 v0.2 参数"
                    else "继续使用冻结的 v0.2 参数；历史事件不会被改写",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsSection(
                "账号与同步",
                when {
                    state.accountId == null -> "未登录，本地功能不受影响"
                    state.syncInProgress -> "正在同步"
                    else -> "待上传 ${state.syncPendingCount} 项"
                },
                Icons.Default.CloudSync,
                initiallyExpanded = state.accountId == null,
            ) {
                OutlinedTextField(
                    syncEndpoint, { syncEndpoint = it }, label = { Text("同步服务地址") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (state.accountId == null) {
                    OutlinedTextField(
                        accountEmail, { accountEmail = it }, label = { Text("邮箱") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        accountPassword, { accountPassword = it }, label = { Text("密码（至少 12 位）") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        invitationCode, { invitationCode = it }, label = { Text("邀请码（注册时需要）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { model.registerAccount(syncEndpoint, accountEmail, accountPassword, invitationCode) },
                            enabled = !state.operationInProgress && accountEmail.isNotBlank() &&
                                accountPassword.length >= 12 && invitationCode.length >= 8,
                            modifier = Modifier.weight(1f).height(50.dp),
                        ) { Text("注册") }
                        Button(
                            onClick = { model.loginAccount(syncEndpoint, accountEmail, accountPassword) },
                            enabled = !state.operationInProgress && accountEmail.isNotBlank() && accountPassword.isNotBlank(),
                            modifier = Modifier.weight(1f).height(50.dp),
                        ) { Text("登录") }
                    }
                } else {
                    Text("账号 ${state.accountId.take(8)}…", style = MaterialTheme.typography.bodyMedium)
                    state.lastSyncedAt?.let { Text("上次同步 ${java.time.Instant.ofEpochSecond(it)}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = model::syncNow, enabled = !state.syncInProgress,
                            modifier = Modifier.weight(1f).height(50.dp),
                        ) { Text(if (state.syncInProgress) "同步中" else "立即同步") }
                        OutlinedButton(
                            onClick = model::logoutAccount, enabled = !state.operationInProgress,
                            modifier = Modifier.weight(1f).height(50.dp),
                        ) { Text("退出") }
                    }
                }
            }
        }
        item {
            SettingsSection("外观", "中性暗黑", Icons.Default.DarkMode) {
                Text("中性暗黑", style = MaterialTheme.typography.bodyLarge)
            }
        }
        item {
            val update = state.availableUpdate
            val updateSubtitle = when {
                state.updateInProgress -> "正在处理"
                state.downloadedUpdatePath != null -> "v${update?.version} 已下载"
                update != null -> "发现 v${update.version}"
                else -> "当前版本 v${BuildConfig.VERSION_NAME}"
            }
            SettingsSection("应用更新", updateSubtitle, Icons.Default.SystemUpdateAlt) {
                Text("当前版本 v${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = {
                        when {
                            state.downloadedUpdatePath != null -> {
                                if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                                    installPermission.launch(Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:${context.packageName}"),
                                    ))
                                } else launchUpdateInstaller(context, state.downloadedUpdatePath)
                            }
                            update != null -> model.downloadUpdate()
                            else -> model.checkForUpdates()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = !state.updateInProgress,
                ) {
                    Icon(
                        if (state.downloadedUpdatePath == null) Icons.Default.Download else Icons.Default.SystemUpdateAlt,
                        null, Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(when {
                        state.updateInProgress -> "处理中"
                        state.downloadedUpdatePath != null -> "安装 v${update?.version}"
                        update != null -> "下载 v${update.version}"
                        else -> "检查更新"
                    })
                }
            }
        }
        item {
            SettingsSection("提醒", if (reminder) "每天 $time，仅在有任务时" else "当前已关闭", Icons.Default.Notifications) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("学习提醒", style = MaterialTheme.typography.titleMedium)
                        Text("仅有待复习内容时通知", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(reminder, { enabled ->
                        reminder = enabled
                        if (enabled && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    })
                }
                AnimatedVisibility(reminder) {
                    OutlinedTextField(time, { time = it }, label = { Text("提醒时间 HH:mm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item {
            SettingsSection("数据与备份", "导出、恢复与回收站", Icons.Default.Backup) {
                OutlinedButton(
                    onClick = { exportBackup.launch("ReviewFault-${LocalDate.now()}.reviewfault") },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) {
                    Icon(Icons.Default.Backup, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("导出备份")
                }
                OutlinedButton(
                    onClick = { restoreBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) { Icon(Icons.Default.Restore, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("从备份恢复") }
                if (state.trash.isEmpty()) {
                    Text("回收站为空", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("回收站 · ${state.trash.size} 项", style = MaterialTheme.typography.titleMedium)
                    state.trash.take(5).forEach { row ->
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(row.prompt.ifBlank { "图片题面" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { model.restore(row.id) }) { Icon(Icons.Default.Restore, contentDescription = "恢复") }
                            }
                        }
                    }
                }
            }
        }
        item {
            Button(
                onClick = {
                    model.saveSettings(
                        state.preferences.copy(
                            dailyNewMemoryLimit = newLimit.toIntOrNull() ?: -1,
                            sessionMinutes = minutes.toIntOrNull() ?: 0,
                            memoryPreset = memoryPreset,
                            mathIntensity = mathIntensity,
                            schedulerGeneration = schedulerGeneration,
                            enabledSubjects = subjects,
                            includeMemoryCards = includeMemory,
                            includeMathProblems = includeMath,
                        ), theme, reminder, time,
                    )
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                enabled = !state.operationInProgress,
                shape = RoundedCornerShape(8.dp),
            ) { Text("保存更改") }
        }
    }
}

private fun launchUpdateInstaller(context: android.content.Context, path: String) {
    val directory = File(context.cacheDir, "updates")
    val file = File(path)
    require(file.canonicalPath.startsWith(directory.canonicalPath + File.separator) && file.isFile) {
        "安装包不存在"
    }
    val uri = Uri.parse("content://${context.packageName}.capture/updates/${file.name}")
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    icon: ImageVector,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(icon, null, Modifier.padding(10.dp).size(21.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ExpandMore, contentDescription = if (expanded) "收起" else "展开", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                content()
            }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, choices: List<Pair<String, String>>, selected: String, choose: (String) -> Unit) {
    DropdownChoice(title, choices, selected, choose)
}

@Composable
private fun DropdownChoice(title: String, choices: List<Pair<String, String>>, selected: String, choose: (String) -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val selectedLabel = choices.firstOrNull { it.first == selected }?.second ?: selected
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text(selectedLabel, Modifier.weight(1f))
                Icon(Icons.Default.ExpandMore, contentDescription = "展开选项")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.fillMaxWidth(.82f)) {
                choices.forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { choose(value); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun TagInput(value: String, onValueChange: (String) -> Unit, knownTags: List<String>, supportingText: String) {
    val token = value.takeLastWhile { it != ',' && it != '\n' }
    val currentToken = token.trimStart()
    val suggestions = if (currentToken.isBlank()) emptyList() else knownTags
        .filter { it.startsWith(currentToken, ignoreCase = true) && !it.equals(currentToken, ignoreCase = true) }
        .distinct().take(8)
    var dismissedForValue by remember { mutableStateOf<String?>(null) }
    Box {
        OutlinedTextField(
            value, { next -> dismissedForValue = null; onValueChange(next) },
            label = { Text("标签（逗号或换行分隔）") },
            supportingText = { Text(supportingText) },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = suggestions.isNotEmpty() && dismissedForValue != value,
            onDismissRequest = { dismissedForValue = value },
        ) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text("#$suggestion") },
                    onClick = {
                        onValueChange(value.dropLast(token.length) + suggestion + ", ")
                        dismissedForValue = null
                    },
                )
            }
        }
    }
}

@Composable
private fun MultiChoiceRow(
    title: String,
    choices: List<Pair<String, String>>,
    selected: Set<String>,
    choose: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (value, label) ->
                FilterChip(selected = value in selected, onClick = { choose(value) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun PreferenceSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    update: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = update, enabled = enabled)
    }
}

@Composable
private fun ReviewScreen(state: AppUiState, model: AppViewModel) {
    val row = state.current ?: return
    val context = LocalContext.current
    var reason by rememberSaveable(row.id) { mutableStateOf("concept") }
    val hints = remember(row.id) { structuredItems(row) }
    var shownHints by rememberSaveable(row.id) { mutableIntStateOf(0) }
    var recallDraft by rememberSaveable(row.id) { mutableStateOf("") }
    var reflection by rememberSaveable(row.id) { mutableStateOf("") }
    var confidence by rememberSaveable(row.id) { mutableIntStateOf(3) }
    var responseCommitted by rememberSaveable(row.id) { mutableStateOf(false) }
    var directReveal by rememberSaveable(row.id) { mutableStateOf(false) }
    var checkedPoints by remember(row.id) { mutableStateOf(setOf<Int>()) }
    val bitmap = remember(row.id, row.mediaPath) {
        row.mediaPath?.let { BitmapFactory.decodeFile(java.io.File(context.filesDir, it).absolutePath) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 36.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = model::finishReviewSession) {
                    Icon(Icons.Default.Close, contentDescription = "结束本轮并退出复习")
                }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(
                        if (row.kind == "math_problem") "数学 · ${row.profile.knowledgePoint.ifBlank { "专注重做" }}"
                        else "408 · ${row.profile.knowledgePoint.ifBlank { archetypeLabel(row.profile.archetype) }}",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    )
                    Text(if (state.answerRevealed) "对照答案，诚实评分" else "先独立作答，再看答案", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            val sessionProgress = if (state.sessionTargetSeconds <= 0) 0f else
                state.sessionElapsedSeconds.toFloat() / state.sessionTargetSeconds
            LinearProgressIndicator(
                progress = { (sessionProgress + if (state.answerRevealed) .08f else .03f).coerceIn(.03f, 1f) },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
            )
            Text(
                "本轮已完成 ${state.sessionReviewedCount} 条 · 跳过 ${state.sessionSkippedIds.size} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.prompt.isNotBlank()) {
            item {
                Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Text(reviewPrompt(row), Modifier.padding(21.dp), style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        if (row.profile.sourceTitle.isNotBlank() || row.tags.isNotEmpty()) {
            item {
                Text(
                    buildList {
                        if (row.profile.sourceTitle.isNotBlank()) add(row.profile.sourceTitle)
                        if (row.profile.sourceChapter.isNotBlank()) add(row.profile.sourceChapter)
                        if (row.profile.sourceLocator.isNotBlank()) add(row.profile.sourceLocator)
                        addAll(row.tags.take(2).map { "#$it" })
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        bitmap?.let { image ->
            item {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "数学题面",
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
        if (row.kind == "math_problem" && !state.answerRevealed) {
            item {
                Text("演算", style = MaterialTheme.typography.titleMedium)
                InkPad(onDocumentChanged = { model.saveInkDraft(row.id, it) })
            }
        }
        if (!state.answerRevealed) {
            if (row.kind == "memory_card") {
                item {
                    OutlinedTextField(
                        recallDraft, { recallDraft = it }, label = { Text("回忆草稿") },
                        supportingText = { Text("写关键词即可；内容只作为本次复盘证据") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3,
                    )
                }
            }
            item {
                ChoiceRow("作答前信心", (1..5).map { it.toString() to when (it) {
                    1 -> "很低"; 2 -> "较低"; 3 -> "一般"; 4 -> "较高"; else -> "很高"
                } }, confidence.toString()) { confidence = it.toInt() }
            }
            if (hints.isNotEmpty()) {
                items(shownHints) { index ->
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text("提示 ${index + 1} · ${hints[index]}", Modifier.padding(15.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                if (shownHints < hints.size) {
                    item {
                        OutlinedButton(onClick = { shownHints++ }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                            Text(if (shownHints == 0) "需要一点提示" else "再看一层提示")
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        responseCommitted = true
                        model.revealAnswer()
                    }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(8.dp)) {
                        Text(if (row.kind == "math_problem") "完成作答并核对" else "锁定回忆并核对")
                    }
                    OutlinedButton(onClick = {
                        directReveal = true
                        model.revealAnswer()
                    }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("暂时想不起来，直接看答案")
                    }
                }
            }
        } else {
            val evidenceReflection = buildList {
                if (recallDraft.isNotBlank()) add("回忆草稿：${recallDraft.trim()}")
                if (reflection.isNotBlank()) add("复盘：${reflection.trim()}")
            }.joinToString("\n\n")
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("参考答案", style = MaterialTheme.typography.titleMedium)
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f))) {
                        Text(reviewAnswer(row).ifBlank { "尚未填写参考答案" }, Modifier.padding(18.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            item { AnswerAnalysis(row) }
            if (row.answerPoints.isNotEmpty()) {
                item { Text("逐项核对", style = MaterialTheme.typography.titleMedium) }
                items(row.answerPoints.size) { index ->
                    val checked = index in checkedPoints
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            checkedPoints = checkedPoints.toMutableSet().apply {
                                if (!add(index)) remove(index)
                            }
                        }.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked, { value ->
                            checkedPoints = checkedPoints.toMutableSet().apply {
                                if (value) add(index) else remove(index)
                            }
                        })
                        Text(row.answerPoints[index], Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            item {
                OutlinedTextField(
                    reflection, { reflection = it }, label = { Text("本次复盘（可选）") },
                    placeholder = { Text("漏掉了什么？下次先想起什么？") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                )
            }
            if (row.kind == "math_problem") {
                item { ChoiceRow("如果做错，主要卡在哪里？", listOf(
                    "concept" to "概念", "approach" to "思路", "calculation" to "计算",
                    "misread" to "审题", "forgotten_fact" to "结论", "timeout" to "超时", "other" to "其他",
                ), reason) { reason = it } }
                item {
                    RatingButtons(listOf(
                        "不会" to { model.score(1, "again", hintRevealed = shownHints > 0,
                            hintLevel = shownHints, confidence = confidence, reflection = evidenceReflection,
                            answerRevealedBeforeCommit = directReveal || !responseCommitted) },
                        "做错" to { model.score(1, "wrong", errorReason = reason, hintRevealed = shownHints > 0,
                            hintLevel = shownHints, confidence = confidence, reflection = evidenceReflection,
                            answerRevealedBeforeCommit = directReveal || !responseCommitted) },
                        "勉强" to { model.score(2, "effortful", hintRevealed = shownHints > 0,
                            hintLevel = shownHints, confidence = confidence, reflection = evidenceReflection,
                            answerRevealedBeforeCommit = directReveal || !responseCommitted) },
                        "熟练" to { model.score(4, "fluent", hintRevealed = shownHints > 0,
                            hintLevel = shownHints, confidence = confidence, reflection = evidenceReflection,
                            answerRevealedBeforeCommit = directReveal || !responseCommitted) },
                    ), enabled = !state.operationInProgress)
                }
            } else {
                if (row.answerPoints.isNotEmpty()) {
                    val evidenceRating = memoryEvidenceRating(
                        checkedPoints.size, row.answerPoints.size, shownHints,
                        directReveal || !responseCommitted, confidence,
                    )
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "命中 ${checkedPoints.size} / ${row.answerPoints.size} · ${memoryRatingLabel(evidenceRating)}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Button(
                                onClick = { model.score(
                                    evidenceRating, null, hintRevealed = shownHints > 0,
                                    hintLevel = shownHints, pointHits = checkedPoints.size,
                                    pointCount = row.answerPoints.size, confidence = confidence,
                                    reflection = evidenceReflection,
                                    answerRevealedBeforeCommit = directReveal || !responseCommitted,
                                ) }, enabled = !state.operationInProgress,
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) { Text("提交本次证据") }
                        }
                    }
                } else {
                    item {
                        RatingButtons(listOf(
                            "忘记" to { model.score(1, null, hintRevealed = shownHints > 0,
                                hintLevel = shownHints, confidence = confidence, reflection = evidenceReflection,
                                answerRevealedBeforeCommit = directReveal || !responseCommitted) },
                            "困难" to { model.score(2, null, hintRevealed = shownHints > 0,
                                hintLevel = shownHints, confidence = confidence, reflection = evidenceReflection,
                                answerRevealedBeforeCommit = directReveal || !responseCommitted) },
                            "正确" to { model.score(if (shownHints > 0 || directReveal) 2 else 3, null,
                                hintRevealed = shownHints > 0, hintLevel = shownHints, confidence = confidence,
                                reflection = evidenceReflection, answerRevealedBeforeCommit = directReveal || !responseCommitted) },
                            "轻松" to { model.score(if (shownHints > 0 || directReveal) 2 else 4, null,
                                hintRevealed = shownHints > 0, hintLevel = shownHints, confidence = confidence,
                                reflection = evidenceReflection, answerRevealedBeforeCommit = directReveal || !responseCommitted) },
                        ), enabled = !state.operationInProgress)
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = model::skipCurrent,
                    enabled = !state.operationInProgress,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("本轮跳过（不评分）") }
                OutlinedButton(
                    onClick = model::finishReviewSession,
                    enabled = !state.operationInProgress,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("提前结束本轮") }
            }
        }
        item {
            OutlinedButton(
                onClick = model::deleteCurrentWithoutReview,
                enabled = !state.operationInProgress,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = .5f)),
            ) {
                Icon(Icons.Default.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("移入回收站（不评分）")
            }
        }
    }
}

@Composable
private fun RatingButtons(actions: List<Pair<String, () -> Unit>>, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("这次完成得怎样？", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            actions.forEachIndexed { index, (label, action) ->
                if (index < 2) OutlinedButton(action, Modifier.weight(1f).height(52.dp), enabled = enabled) { Text(label) }
                else Button(action, Modifier.weight(1f).height(52.dp), enabled = enabled) { Text(label) }
            }
        }
    }
}

private fun memoryPresetLabel(value: String) = when (value) {
    "time_saving" -> "省时"
    "reinforced" -> "强化"
    else -> "均衡"
}

private fun mathIntensityLabel(value: String) = when (value) {
    "intensive" -> "密集"
    "relaxed" -> "舒缓"
    else -> "均衡"
}

private fun subjectLabel(value: String) = when (value) {
    "data_structures" -> "408 · 数据结构"
    "computer_organization" -> "408 · 计算机组成原理"
    "operating_systems" -> "408 · 操作系统"
    "computer_networks" -> "408 · 计算机网络"
    else -> "408 记忆卡"
}

private fun archetypeLabel(value: String) = when (value) {
    "concept" -> "概念辨析"; "comparison" -> "对比"; "process" -> "流程"
    "enumeration" -> "枚举"; "scale_mapping" -> "量纲映射"; "formula_rule" -> "公式规则"
    "diagram" -> "图示"; "cloze" -> "填空"; "math_error" -> "错题"; else -> "问答"
}

private fun memoryEvidenceRating(hits: Int, count: Int, hintLevel: Int, directReveal: Boolean, confidence: Int): Int {
    val coverage = if (count == 0) 0.0 else hits.toDouble() / count
    val raw = when {
        coverage < .60 -> 1
        coverage < .85 -> 2
        confidence >= 4 -> 4
        else -> 3
    }
    return if ((hintLevel > 0 || directReveal) && raw > 2) 2 else raw
}

private fun memoryRatingLabel(value: Int) = when (value) {
    1 -> "需要重学"; 2 -> "提示后掌握"; 4 -> "独立且流畅"; else -> "独立掌握"
}

private fun scaleMappingRows(payload: String): List<Triple<String, String, String>> = try {
    val normalized = payload.trim()
    val values = if (normalized.startsWith("[")) {
        JSONArray(normalized)
    } else {
        JSONObject(normalized).optJSONArray("rows") ?: JSONArray()
    }
    buildList {
        for (index in 0 until values.length()) {
            val value = values.get(index)
            if (value is JSONObject) {
                add(Triple(value.optString("term", value.optString("label")),
                    value.optString("exponent"), value.optString("magnitude")))
            }
        }
    }
} catch (_: Exception) { emptyList() }

private fun structuredItems(row: StudyRow): List<String> = row.hints

private fun reviewPrompt(row: StudyRow): String {
    if (row.templateType != "cloze") return row.prompt
    return Regex("\\{\\{c\\d+::(.*?)(?:::[^}]*)?}}") .replace(row.prompt, "[…]")
}

private fun reviewAnswer(row: StudyRow): String = when (row.templateType) {
    "cloze" -> Regex("\\{\\{c\\d+::(.*?)(?:::[^}]*)?}}")
        .findAll(row.prompt).map { it.groupValues[1] }.joinToString("\n")
    "enumeration" -> row.answerPoints.joinToString("\n") { "• $it" }
    else -> row.answer
}

private fun splitEditorLines(value: String): List<String> = value.lineSequence()
    .map(String::trim).filter(String::isNotEmpty).distinct().toList()

private fun memoryTemplate(archetype: String, hints: List<String>, points: List<String>): String = when (archetype) {
    "comparison" -> "comparison"
    "enumeration", "process", "scale_mapping" -> if (points.size >= 2) "enumeration" else "qa"
    "cloze" -> "cloze"
    else -> if (hints.isEmpty()) "qa" else "layered_hint"
}

private fun archetypePrompt(archetype: String): String = when (archetype) {
    "scale_mapping" -> "从小到大写出单位、数量级和中文量级；再做反向回忆"
    "formula_rule" -> "写出公式，解释每个符号，并说明适用范围"
    "enumeration" -> "不看答案列出全部关键要点"
    "comparison" -> "按相同维度比较两个概念，并说明使用场景"
    "process" -> "按顺序复述流程，并解释关键转折条件"
    else -> "给出定义、关键属性，并指出它与易混概念的区别"
}

private fun structuredPayload(
    archetype: String,
    points: List<String>,
    answer: String,
    conditions: String,
    example: String,
): String {
    fun jsonLines(value: String) = JSONArray().apply {
        splitEditorLines(value).forEach { line -> put(line) }
    }
    return when (archetype) {
        "scale_mapping" -> JSONObject().apply { put("rows", JSONArray().apply {
            points.forEach { line ->
                val cells = line.split('|').map(String::trim)
                put(JSONObject().apply {
                    put("term", cells.getOrElse(0) { "" })
                    put("exponent", cells.getOrElse(1) { "" })
                    put("magnitude", cells.getOrElse(2) { "" })
                })
            }
        }) }.toString()
        "formula_rule" -> JSONObject().apply {
            put("formula", answer.trim()); put("conditions", jsonLines(conditions))
            put("examples", jsonLines(example))
        }.toString()
        "process" -> JSONObject().apply { put("steps", JSONArray().apply { points.forEach { step -> put(step) } }) }.toString()
        "enumeration" -> JSONObject().apply { put("items", JSONArray().apply { points.forEach { item -> put(item) } }) }.toString()
        else -> "{}"
    }
}

private fun scientificTags(
    raw: String,
    subject: String,
    archetype: String,
    knowledgePoint: String,
    sourceTitle: String,
    sourceChapter: String,
    errorReason: String?,
): List<String> {
    val manual = raw.split(Regex("[,，\\n]")).map(String::trim).filter(String::isNotEmpty)
    return buildList {
        addAll(manual)
        add("学科/${subjectTagLabel(subject)}")
        add("形式/${archetypeLabel(archetype)}")
        if (knowledgePoint.isNotBlank()) add("考点/${knowledgePoint.trim()}")
        if (sourceTitle.isNotBlank()) add("来源/${sourceTitle.trim()}")
        if (sourceChapter.isNotBlank()) add("章节/${sourceChapter.trim()}")
        if (errorReason != null) add("错因/${mathErrorLabel(errorReason)}")
    }.distinctBy(String::lowercase).take(30)
}

private fun subjectTagLabel(value: String) = when (value) {
    "math" -> "数学"; "data_structures" -> "数据结构"
    "computer_organization" -> "计算机组成原理"; "operating_systems" -> "操作系统"
    "computer_networks" -> "计算机网络"; else -> value
}

private fun mathErrorLabel(value: String) = when (value) {
    "concept" -> "概念"; "approach" -> "思路"; "calculation" -> "计算"; "misread" -> "审题"
    "forgotten_fact" -> "结论遗忘"; "timeout" -> "超时"; else -> "其他"
}
