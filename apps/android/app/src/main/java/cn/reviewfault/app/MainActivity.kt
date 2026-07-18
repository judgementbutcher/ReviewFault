package cn.reviewfault.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.reviewfault.app.data.LearningPreferences
import cn.reviewfault.app.data.StudyRow
import org.json.JSONArray

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: AppViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            ReviewFaultTheme(state.themeMode) { ReviewFaultApp(state, model) }
        }
    }
}

private val Ink = Color(0xFF173528)
private val Moss = Color(0xFF315C49)
private val Sage = Color(0xFFA8D8BF)
private val Lavender = Color(0xFF756B90)
private val LightScheme = lightColorScheme(
    primary = Moss,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EBDD),
    onPrimaryContainer = Ink,
    secondary = Lavender,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E3F4),
    onSecondaryContainer = Color(0xFF2B2636),
    background = Color(0xFFF7F5EF),
    onBackground = Ink,
    surface = Color(0xFFFFFDF8),
    onSurface = Color(0xFF1A211D),
    surfaceVariant = Color(0xFFE7EAE5),
    onSurfaceVariant = Color(0xFF59635D),
    outline = Color(0xFF87918B),
    outlineVariant = Color(0xFFD2D8D3),
    error = Color(0xFFB3261E),
)
private val DarkScheme = darkColorScheme(
    primary = Sage,
    onPrimary = Color(0xFF073824),
    primaryContainer = Color(0xFF214C39),
    onPrimaryContainer = Color(0xFFD5F1E1),
    secondary = Color(0xFFCFC3EC),
    onSecondary = Color(0xFF342D47),
    secondaryContainer = Color(0xFF4C455F),
    onSecondaryContainer = Color(0xFFEAE3F9),
    background = Color(0xFF0E1712),
    onBackground = Color(0xFFDCE9E1),
    surface = Color(0xFF18221C),
    onSurface = Color(0xFFE3ECE6),
    surfaceVariant = Color(0xFF29332D),
    onSurfaceVariant = Color(0xFFBAC5BE),
    outline = Color(0xFF849089),
    outlineVariant = Color(0xFF36423B),
    error = Color(0xFFFFB4AB),
)
private val AppTypography = Typography(
    headlineLarge = Typography().headlineLarge.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = Typography().headlineMedium.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = Typography().headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = Typography().labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
private fun ReviewFaultTheme(themeMode: Int, content: @Composable () -> Unit) {
    val dark = themeMode == 2 || (themeMode == 0 && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = AppTypography) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

@Composable
private fun ReviewFaultApp(state: AppUiState, model: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
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
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AnimatedVisibility(
                visible = state.destination != AppDestination.Review,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) { AppNavigation(state.destination, model::navigate) }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            AnimatedContent(
                targetState = state.destination,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "页面切换",
            ) { destination ->
                when (destination) {
                    AppDestination.Today -> TodayScreen(state, model)
                    AppDestination.Library -> LibraryScreen(state, model)
                    AppDestination.Add -> AddScreen(model)
                    AppDestination.Settings -> SettingsScreen(state, model)
                    AppDestination.Review -> ReviewScreen(state, model)
                }
            }
        }
    }
}

@Composable
private fun AppNavigation(selected: AppDestination, navigate: (AppDestination) -> Unit) {
    val destinations = listOf(
        Triple(AppDestination.Today, "今日", Icons.Default.Home),
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
    val total = state.summary.overdue + state.summary.dueToday + state.summary.newItems
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(24.dp),
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
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
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
                onClick = { if (total == 0) model.navigate(AppDestination.Add) else model.startReview() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(if (total == 0) "添加第一条内容" else "开始专注轮次")
                Spacer(Modifier.width(8.dp))
                Icon(if (total == 0) Icons.Default.Add else Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, Modifier.size(19.dp))
            }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(17.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("学习负载预报", style = MaterialTheme.typography.titleMedium)
                Text("提前看见波峰，更容易保持节奏", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("明日 ${state.summary.tomorrowDue} 条", style = MaterialTheme.typography.titleSmall)
                Text("未来 7 天 ${state.summary.nextSevenDaysDue} 条", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
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
private fun SummaryPill(label: String, count: Int, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f)) {
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
                placeholder = { Text("搜索题干、答案或来源") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
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
        item {
            AnimatedVisibility(visible = state.selectedIds.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.error.copy(alpha = .10f)) {
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
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f)),
    ) {
        Row(Modifier.fillMaxWidth().clickable { toggle(row.id) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(selected, { toggle(row.id) }, Modifier.semantics { contentDescription = "选择题目" })
            Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    if (row.kind == "math_problem") "数学错题" else subjectLabel(row.subject),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(row.prompt.ifBlank { "图片题面" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
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
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(Icons.AutoMirrored.Filled.LibraryBooks, null, Modifier.padding(16.dp).size(34.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text("题库还是空的", style = MaterialTheme.typography.titleLarge)
        Text("从一道数学错题或一张 408 记忆卡开始。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onAdd) { Text("去添加") }
    }
}

@Composable
private fun AddScreen(model: AppViewModel) = Page {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.navigate(AppDestination.Today)
    }
    fun launch(route: String) {
        launcher.launch(Intent(context, LegacyMainActivity::class.java).putExtra(LegacyMainActivity.EXTRA_ROUTE, route))
    }
    PageHeader("快速记录", "把卡住你的留下来", "先保存最必要的信息，细节可以在复习时补齐。")
    AddChoiceCard(
        icon = Icons.Default.Image,
        title = "记录数学错题",
        subtitle = "拍照或选择 1–5 张截图，约 30 秒完成",
        accent = MaterialTheme.colorScheme.primaryContainer,
        onClick = { launch(LegacyMainActivity.ROUTE_MATH) },
    )
    AddChoiceCard(
        icon = Icons.Default.School,
        title = "新建 408 记忆卡",
        subtitle = "问答、填空、分层提示或枚举",
        accent = MaterialTheme.colorScheme.secondaryContainer,
        onClick = { launch(LegacyMainActivity.ROUTE_MEMORY) },
    )
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(11.dp))
            Text("记录不必一次做到完美。题面和问题先入库，答案、错因和提示可以在真正复习时补充。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddChoiceCard(icon: ImageVector, title: String, subtitle: String, accent: Color, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(17.dp), color = accent) {
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
    var theme by remember(state.themeMode) { mutableIntStateOf(state.themeMode) }
    var reminder by remember(state.reminderEnabled) { mutableStateOf(state.reminderEnabled) }
    var time by remember(state.reminderTime) { mutableStateOf(state.reminderTime) }
    val context = LocalContext.current
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.loadTrash(); model.refreshToday()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) reminder = false
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
            SettingsSection("外观", "跟随系统、浅色或深色", Icons.Default.DarkMode) {
                ChoiceRow("显示模式", listOf("0" to "跟随系统", "1" to "浅色", "2" to "深色"), theme.toString()) { theme = it.toInt() }
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
                    onClick = {
                        backupLauncher.launch(Intent(context, LegacyMainActivity::class.java).putExtra(LegacyMainActivity.EXTRA_ROUTE, LegacyMainActivity.ROUTE_BACKUP))
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) {
                    Icon(Icons.Default.Backup, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("备份与恢复")
                }
                if (state.trash.isEmpty()) {
                    Text("回收站为空", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("回收站 · ${state.trash.size} 项", style = MaterialTheme.typography.titleMedium)
                    state.trash.take(5).forEach { row ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)) {
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
                shape = RoundedCornerShape(17.dp),
            ) { Text("保存更改") }
        }
    }
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
        shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer) {
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
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (value, label) ->
                FilterChip(selected = selected == value, onClick = { choose(value) }, label = { Text(label) })
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
    var reason by remember(row.id) { mutableStateOf("concept") }
    val hints = remember(row.id) { structuredItems(row) }
    var shownHints by remember(row.id) { mutableIntStateOf(0) }
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
                    Text(if (row.kind == "math_problem") "数学 · 专注重做" else "408 · 主动回忆", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Text(reviewPrompt(row), Modifier.padding(21.dp), style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        bitmap?.let { image ->
            item {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "数学题面",
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
        if (!state.answerRevealed) {
            if (hints.isNotEmpty()) {
                items(shownHints) { index ->
                    Surface(shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
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
                Button(onClick = model::revealAnswer, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
                    Text("显示答案")
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("参考答案", style = MaterialTheme.typography.titleMedium)
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f))) {
                        Text(reviewAnswer(row).ifBlank { "尚未填写参考答案" }, Modifier.padding(18.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (row.kind == "math_problem") {
                item { ChoiceRow("如果做错，主要卡在哪里？", listOf("concept" to "概念", "approach" to "思路", "calculation" to "计算", "misread" to "审题", "timeout" to "超时"), reason) { reason = it } }
                item {
                    RatingButtons(listOf(
                        "不会" to { model.score(1, "again", hintRevealed = shownHints > 0) },
                        "做错" to { model.score(1, "wrong", reason, shownHints > 0) },
                        "勉强" to { model.score(2, "effortful", hintRevealed = shownHints > 0) },
                        "熟练" to { model.score(4, "fluent", hintRevealed = shownHints > 0) },
                    ), enabled = !state.operationInProgress)
                }
            } else {
                item {
                    RatingButtons(listOf(
                        "忘记" to { model.score(1, null, hintRevealed = shownHints > 0) },
                        "困难" to { model.score(2, null, hintRevealed = shownHints > 0) },
                        "正确" to { model.score(3, null, hintRevealed = shownHints > 0) },
                        "轻松" to { model.score(4, null, hintRevealed = shownHints > 0) },
                    ), enabled = !state.operationInProgress)
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

private fun structuredItems(row: StudyRow): List<String> = try {
    val array = JSONArray(row.structuredJson)
    buildList { for (index in 0 until array.length()) add(array.getString(index)) }
} catch (_: Exception) { emptyList() }

private fun reviewPrompt(row: StudyRow): String {
    if (row.templateType != "cloze") return row.prompt
    return Regex("\\{\\{c\\d+::(.*?)(?:::[^}]*)?}}") .replace(row.prompt, "[…]")
}

private fun reviewAnswer(row: StudyRow): String = when (row.templateType) {
    "cloze" -> Regex("\\{\\{c\\d+::(.*?)(?:::[^}]*)?}}")
        .findAll(row.prompt).map { it.groupValues[1] }.joinToString("\n")
    "enumeration" -> structuredItems(row).joinToString("\n") { "• $it" }
    else -> row.answer
}
