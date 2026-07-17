package cn.reviewfault.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.reviewfault.app.data.AppDatabase
import cn.reviewfault.app.data.DashboardSummary
import cn.reviewfault.app.data.DeletionState
import cn.reviewfault.app.data.LearningPreferences
import cn.reviewfault.app.data.LibraryFilter
import cn.reviewfault.app.data.StudyRow
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppDestination { Today, Library, Add, Settings, Review }

data class AppUiState(
    val destination: AppDestination = AppDestination.Today,
    val loading: Boolean = true,
    val summary: DashboardSummary = DashboardSummary(0, 0, 0, 0),
    val library: List<StudyRow> = emptyList(),
    val trash: List<StudyRow> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val current: StudyRow? = null,
    val answerRevealed: Boolean = false,
    val startedAt: Long = 0,
    val preferences: LearningPreferences = LearningPreferences(),
    val themeMode: Int = 0,
    val reminderEnabled: Boolean = false,
    val reminderTime: String = "20:00",
    val deletion: DeletionState? = null,
    val message: String? = null,
)

@OptIn(FlowPreview::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.get(application)
    private val devicePreferences =
        application.getSharedPreferences("appearance_reminders", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(AppUiState(
        themeMode = devicePreferences.getInt("theme", 0),
        reminderEnabled = devicePreferences.getBoolean("reminder_enabled", false),
        reminderTime = devicePreferences.getString("reminder_time", "20:00") ?: "20:00",
    ))
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    val searchQuery = MutableStateFlow("")
    private val libraryFilter = MutableStateFlow(LibraryFilter())

    init {
        refreshToday()
        loadSettings()
        viewModelScope.launch {
            searchQuery.debounce(300).distinctUntilChanged().collectLatest { query ->
                libraryFilter.update { it.copy(query = query, offset = 0) }
                loadLibrary()
            }
        }
    }

    fun navigate(destination: AppDestination) {
        mutableState.update { it.copy(destination = destination, current = null, answerRevealed = false) }
        when (destination) {
            AppDestination.Today -> refreshToday()
            AppDestination.Library -> loadLibrary()
            AppDestination.Settings -> { loadSettings(); loadTrash() }
            else -> Unit
        }
    }

    fun setLibraryFilter(subject: String? = null, kind: String? = null, status: String? = null) {
        libraryFilter.update { old -> old.copy(
            subjects = subject?.let(::setOf) ?: emptySet(),
            kinds = kind?.let(::setOf) ?: emptySet(),
            status = status ?: "all",
            offset = 0,
        ) }
        loadLibrary()
    }

    fun refreshToday() = io {
        val now = Instant.now().epochSecond
        val dayStart = ZonedDateTime.ofInstant(Instant.ofEpochSecond(now), ZoneId.systemDefault())
            .toLocalDate().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        mutableState.update { it.copy(summary = database.dashboard(now, dayStart), loading = false) }
    }

    fun loadLibrary() = io {
        mutableState.update { it.copy(
            library = database.search(libraryFilter.value.copy(query = searchQuery.value)),
            loading = false,
        ) }
    }

    fun toggleSelection(id: String) {
        mutableState.update { current ->
            val next = current.selectedIds.toMutableSet().apply {
                if (!add(id)) remove(id)
            }
            current.copy(selectedIds = next)
        }
    }

    fun deleteSelected() = io {
        val ids = mutableState.value.selectedIds.toList()
        if (ids.isEmpty()) return@io
        val deletion = database.softDelete(ids)
        mutableState.update { it.copy(selectedIds = emptySet(), deletion = deletion, message = "已移入回收站") }
        loadLibrary()
        loadTrash()
        refreshToday()
    }

    fun undoDeletion() = io {
        val deletion = mutableState.value.deletion ?: return@io
        if (deletion.undoUntil >= Instant.now().epochSecond) database.restore(deletion.itemIds)
        mutableState.update { it.copy(deletion = null, message = "已撤销删除") }
        loadLibrary(); loadTrash(); refreshToday()
    }

    fun restore(id: String) = io {
        database.restore(listOf(id)); loadTrash(); loadLibrary(); refreshToday()
    }

    fun startReview() = io {
        val row = database.nextForReview(Instant.now().epochSecond)
        mutableState.update { it.copy(
            destination = if (row == null) AppDestination.Today else AppDestination.Review,
            current = row, answerRevealed = false, startedAt = Instant.now().epochSecond,
            message = if (row == null) "当前没有待复习内容" else null,
        ) }
    }

    fun revealAnswer() = mutableState.update { it.copy(answerRevealed = true) }

    fun score(rating: Int, mathResult: String?, errorReason: String? = null, hintRevealed: Boolean = false) = io {
        val snapshot = mutableState.value
        val row = snapshot.current ?: return@io
        val reviewedAt = Instant.now().epochSecond
        val result = database.review(
            row, rating, reviewedAt, (reviewedAt - snapshot.startedAt).toInt().coerceAtLeast(0),
            mathResult, errorReason, hintRevealed,
        )
        mutableState.update { it.copy(message = "已保存，下次约 ${formatDays(result.scheduledDays)} 后") }
        startReview()
    }

    fun deleteCurrentWithoutReview() = io {
        val id = mutableState.value.current?.id ?: return@io
        val deletion = database.softDelete(listOf(id))
        mutableState.update { it.copy(current = null, destination = AppDestination.Today,
            deletion = deletion, message = "已移入回收站；本次未生成评分") }
        refreshToday(); loadTrash()
    }

    fun loadSettings() = io {
        mutableState.update { it.copy(preferences = database.learningPreferences()) }
    }

    fun saveSettings(preferences: LearningPreferences, themeMode: Int,
                     reminderEnabled: Boolean, reminderTime: String) = io {
        val parts = reminderTime.split(':')
        require(parts.size == 2 && parts[0].toInt() in 0..23 && parts[1].toInt() in 0..59) {
            "提醒时间必须为 HH:mm"
        }
        database.saveLearningPreferences(preferences)
        devicePreferences.edit().putInt("theme", themeMode)
            .putBoolean("reminder_enabled", reminderEnabled)
            .putString("reminder_time", reminderTime).apply()
        ReminderScheduler.update(getApplication(), reminderEnabled, parts[0].toInt(), parts[1].toInt())
        mutableState.update { it.copy(preferences = preferences, themeMode = themeMode,
            reminderEnabled = reminderEnabled, reminderTime = reminderTime,
            message = "设置已保存；算法设置只影响之后的作答") }
    }

    fun loadTrash() = io {
        mutableState.update { it.copy(trash = database.search(LibraryFilter(deletedOnly = true))) }
    }

    fun clearMessage() = mutableState.update { it.copy(message = null) }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try { block() } catch (error: Exception) {
                mutableState.update { it.copy(loading = false, message = error.message ?: "操作失败") }
            }
        }
    }

    private fun formatDays(days: Double): String = when {
        days < 1.0 / 24.0 -> "${kotlin.math.round(days * 24 * 60).toInt()} 分钟"
        days < 1 -> "${kotlin.math.round(days * 24).toInt()} 小时"
        else -> "${kotlin.math.round(days).toInt()} 天"
    }
}
