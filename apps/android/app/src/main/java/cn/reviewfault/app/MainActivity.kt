package cn.reviewfault.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.reviewfault.app.data.LearningPreferences
import cn.reviewfault.app.data.StudyRow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val model: AppViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            ReviewFaultTheme(state.themeMode) { ReviewFaultApp(state, model) }
        }
    }
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF315C49), onPrimary = Color.White,
    background = Color(0xFFF7F5EF), surface = Color.White,
    onBackground = Color(0xFF1E362B), onSurface = Color(0xFF18201C),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9BCDB4), onPrimary = Color(0xFF073824),
    background = Color(0xFF111814), surface = Color(0xFF1B251F),
    onBackground = Color(0xFFD8E8DE), onSurface = Color(0xFFE1EAE4),
)

@Composable
private fun ReviewFaultTheme(themeMode: Int, content: @Composable () -> Unit) {
    val dark = themeMode == 2 || (themeMode == 0 && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}

@Composable
private fun ReviewFaultApp(state: AppUiState, model: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { message ->
            val result = snackbar.showSnackbar(message, if (state.deletion != null) "撤销" else null)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) model.undoDeletion()
            model.clearMessage()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.destination != AppDestination.Review) AppNavigation(state.destination, model::navigate)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            when (state.destination) {
                AppDestination.Today -> TodayScreen(state, model)
                AppDestination.Library -> LibraryScreen(state, model)
                AppDestination.Add -> AddScreen()
                AppDestination.Settings -> SettingsScreen(state, model)
                AppDestination.Review -> ReviewScreen(state, model)
            }
        }
    }
}

@Composable
private fun AppNavigation(selected: AppDestination, navigate: (AppDestination) -> Unit) {
    val destinations = listOf(
        Triple(AppDestination.Today, "今日", Icons.Default.Home),
        Triple(AppDestination.Library, "题库", Icons.Default.LibraryBooks),
        Triple(AppDestination.Add, "添加", Icons.Default.AddCircle),
        Triple(AppDestination.Settings, "设置", Icons.Default.Settings),
    )
    NavigationBar {
        destinations.forEach { (destination, label, icon) ->
            NavigationBarItem(
                selected = selected == destination, onClick = { navigate(destination) },
                icon = { androidx.compose.material3.Icon(icon, contentDescription = label) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().widthIn(max = 900.dp).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp),
    ) { item { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { content() } } }
}

@Composable
private fun TodayScreen(state: AppUiState, model: AppViewModel) = Page {
    Text("ReviewFault", style = MaterialTheme.typography.headlineLarge)
    Text("把今天该复习的交给算法")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("今日学习", style = MaterialTheme.typography.headlineSmall)
            Text("逾期 ${state.summary.overdue} · 到期 ${state.summary.dueToday} · 新内容 ${state.summary.newItems}")
            Text("预计 ${state.summary.estimatedMinutes} 分钟 · 单次 ${state.preferences.sessionMinutes} 分钟")
            Button(onClick = model::startReview, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("开始复习")
            }
        }
    }
    Text("当前算法：408 ${state.preferences.memoryPreset} · 数学 ${state.preferences.mathIntensity}")
}

@Composable
private fun LibraryScreen(state: AppUiState, model: AppViewModel) {
    Column(Modifier.fillMaxSize().widthIn(max = 900.dp).padding(20.dp)) {
        Text("题库", style = MaterialTheme.typography.headlineLarge)
        OutlinedTextField(
            value = model.searchQuery.collectAsStateWithLifecycle().value,
            onValueChange = { model.searchQuery.value = it }, label = { Text("搜索题干、答案或来源") },
            singleLine = true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "题库搜索" },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            FilterChip(selected = false, onClick = { model.setLibraryFilter(kind = "memory_card") }, label = { Text("408") })
            FilterChip(selected = false, onClick = { model.setLibraryFilter(kind = "math_problem") }, label = { Text("数学") })
            FilterChip(selected = false, onClick = { model.setLibraryFilter(status = "due") }, label = { Text("到期") })
            FilterChip(selected = false, onClick = { model.setLibraryFilter() }, label = { Text("全部") })
        }
        if (state.selectedIds.isNotEmpty()) {
            Button(onClick = model::deleteSelected, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("删除所选 ${state.selectedIds.size} 项")
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            items(state.library, key = StudyRow::id) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(state.selectedIds.contains(row.id), { model.toggleSelection(row.id) },
                            Modifier.semantics { contentDescription = "选择题目" })
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(if (row.kind == "math_problem") "数学" else "408")
                            Text(row.prompt.ifBlank { "图片题面" }, maxLines = 3)
                            Text(if (row.state == 0) "新内容" else "已复习 ${row.repetitions} 次")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddScreen() = Page {
    val context = LocalContext.current
    Text("添加", style = MaterialTheme.typography.headlineLarge)
    Text("录题、拍照和完整结构化编辑继续使用兼容编辑器，保存后会立即回到 v2 题库。")
    Button(onClick = {
        context.startActivity(Intent(context, LegacyMainActivity::class.java)
            .putExtra(LegacyMainActivity.EXTRA_ROUTE, LegacyMainActivity.ROUTE_MATH))
    }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("拍照 / 相册录入数学错题") }
    OutlinedButton(onClick = {
        context.startActivity(Intent(context, LegacyMainActivity::class.java)
            .putExtra(LegacyMainActivity.EXTRA_ROUTE, LegacyMainActivity.ROUTE_MEMORY))
    }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("新建 408 记忆卡") }
}

@Composable
private fun SettingsScreen(state: AppUiState, model: AppViewModel) {
    var newLimit by remember(state.preferences) { mutableStateOf(state.preferences.dailyNewMemoryLimit.toString()) }
    var minutes by remember(state.preferences) { mutableStateOf(state.preferences.sessionMinutes.toString()) }
    var memoryPreset by remember(state.preferences) { mutableStateOf(state.preferences.memoryPreset) }
    var mathIntensity by remember(state.preferences) { mutableStateOf(state.preferences.mathIntensity) }
    var theme by remember(state.themeMode) { mutableIntStateOf(state.themeMode) }
    var reminder by remember(state.reminderEnabled) { mutableStateOf(state.reminderEnabled) }
    var time by remember(state.reminderTime) { mutableStateOf(state.reminderTime) }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) reminder = false
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().widthIn(max = 900.dp).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp),
    ) {
        item { Text("设置", style = MaterialTheme.typography.headlineLarge) }
        item { OutlinedTextField(newLimit, { newLimit = it }, label = { Text("每日新 408 上限") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(minutes, { minutes = it }, label = { Text("单次学习时长（分钟）") }, modifier = Modifier.fillMaxWidth()) }
        item { ChoiceRow("408 预设", listOf("time_saving" to "省时", "balanced" to "均衡", "reinforced" to "强化"), memoryPreset) { memoryPreset = it } }
        item { ChoiceRow("数学强度", listOf("intensive" to "密集", "balanced" to "均衡", "relaxed" to "舒缓"), mathIntensity) { mathIntensity = it } }
        item { ChoiceRow("外观", listOf("0" to "跟随系统", "1" to "浅色", "2" to "深色"), theme.toString()) { theme = it.toInt() } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("仅有待复习内容时提醒", modifier = Modifier.weight(1f))
                Switch(reminder, { enabled ->
                    reminder = enabled
                    if (enabled && Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                })
            }
        }
        item { OutlinedTextField(time, { time = it }, label = { Text("提醒时间 HH:mm") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Button(onClick = {
                model.saveSettings(state.preferences.copy(
                    dailyNewMemoryLimit = newLimit.toIntOrNull() ?: -1,
                    sessionMinutes = minutes.toIntOrNull() ?: 0,
                    memoryPreset = memoryPreset, mathIntensity = mathIntensity,
                ), theme, reminder, time)
            }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("保存设置") }
        }
        item {
            OutlinedButton(onClick = {
                context.startActivity(Intent(context, LegacyMainActivity::class.java)
                    .putExtra(LegacyMainActivity.EXTRA_ROUTE, LegacyMainActivity.ROUTE_BACKUP))
            }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("备份与恢复") }
        }
        item { Text("回收站", style = MaterialTheme.typography.headlineSmall) }
        items(state.trash, key = StudyRow::id) { row ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.prompt.ifBlank { "图片题面" }, Modifier.weight(1f), maxLines = 2)
                    OutlinedButton(onClick = { model.restore(row.id) }) { Text("恢复") }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, choices: List<Pair<String, String>>, selected: String,
                      choose: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (value, label) ->
                FilterChip(selected = selected == value, onClick = { choose(value) },
                    label = { Text(label) })
            }
        }
    }
}

@Composable
private fun ReviewScreen(state: AppUiState, model: AppViewModel) {
    val row = state.current ?: return
    var reason by remember(row.id) { mutableStateOf("concept") }
    var hintRevealed by remember(row.id) { mutableStateOf(false) }
    Page {
        Text(if (row.kind == "math_problem") "数学 · 专注重做" else "408 · 主动回忆")
        Text(row.prompt.ifBlank { "图片题面" }, style = MaterialTheme.typography.headlineSmall)
        if (!state.answerRevealed) {
            OutlinedButton(onClick = { hintRevealed = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("查看提示")
            }
            Button(onClick = model::revealAnswer, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("显示答案") }
        } else {
            Card(Modifier.fillMaxWidth()) { Text(row.answer.ifBlank { "尚未填写参考答案" }, Modifier.padding(18.dp)) }
            if (row.kind == "math_problem") {
                ChoiceRow("主要错因", listOf("concept" to "概念", "approach" to "思路",
                    "calculation" to "计算", "misread" to "审题", "timeout" to "超时"), reason) { reason = it }
                RatingButtons(listOf(
                    "不会" to { model.score(1, "again", hintRevealed = hintRevealed) },
                    "做错" to { model.score(1, "wrong", reason, hintRevealed) },
                    "勉强" to { model.score(2, "effortful", hintRevealed = hintRevealed) },
                    "熟练" to { model.score(4, "fluent", hintRevealed = hintRevealed) },
                ))
            } else RatingButtons(listOf(
                "忘记" to { model.score(1, null, hintRevealed = hintRevealed) },
                "困难" to { model.score(2, null, hintRevealed = hintRevealed) },
                "正确" to { model.score(3, null, hintRevealed = hintRevealed) },
                "轻松" to { model.score(4, null, hintRevealed = hintRevealed) },
            ))
        }
        OutlinedButton(onClick = { model.navigate(AppDestination.Today) }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("退出本次复习") }
        OutlinedButton(onClick = model::deleteCurrentWithoutReview, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("移入回收站（不评分）") }
    }
}

@Composable
private fun RatingButtons(actions: List<Pair<String, () -> Unit>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        actions.forEach { (label, action) ->
            Button(action, Modifier.weight(1f).height(52.dp)) { Text(label) }
        }
    }
}
