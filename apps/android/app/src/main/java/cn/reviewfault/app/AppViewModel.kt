package cn.reviewfault.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import cn.reviewfault.app.data.AppDatabase
import cn.reviewfault.app.data.DashboardSummary
import cn.reviewfault.app.data.DeletionState
import cn.reviewfault.app.data.LearningPreferences
import cn.reviewfault.app.data.InsightsSnapshot
import cn.reviewfault.app.data.LibraryFilter
import cn.reviewfault.app.data.MathErrorDraft
import cn.reviewfault.app.data.MemoryCardDraft
import cn.reviewfault.app.data.StudyRow
import cn.reviewfault.app.data.TagRow
import cn.reviewfault.app.sync.AccountTokens
import cn.reviewfault.app.sync.AuthSession
import cn.reviewfault.app.sync.SecureTokenStore
import cn.reviewfault.app.sync.SyncClient
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppDestination { Today, Insights, Library, Add, Settings, Review }

data class AppUiState(
    val destination: AppDestination = AppDestination.Today,
    val loading: Boolean = true,
    val summary: DashboardSummary = DashboardSummary(0, 0, 0, 0),
    val insights: InsightsSnapshot = InsightsSnapshot(),
    val library: List<StudyRow> = emptyList(),
    val availableTags: List<TagRow> = emptyList(),
    val trash: List<StudyRow> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val libraryFilter: LibraryFilter = LibraryFilter(),
    val current: StudyRow? = null,
    val answerRevealed: Boolean = false,
    val startedAt: Long = 0,
    val sessionTargetSeconds: Int = 0,
    val sessionElapsedSeconds: Int = 0,
    val sessionReviewedCount: Int = 0,
    val sessionAllowsNewItems: Boolean = true,
    val sessionSkippedIds: Set<String> = emptySet(),
    val preferences: LearningPreferences = LearningPreferences(),
    val themeMode: Int = 0,
    val reminderEnabled: Boolean = false,
    val reminderTime: String = "20:00",
    val deletion: DeletionState? = null,
    val message: String? = null,
    val operationInProgress: Boolean = false,
    val syncEndpoint: String = "https://sync.reviewfault.app",
    val accountId: String? = null,
    val syncPendingCount: Int = 0,
    val lastSyncedAt: Long? = null,
    val syncInProgress: Boolean = false,
    val availableUpdate: AvailableUpdate? = null,
    val downloadedUpdatePath: String? = null,
    val updateInProgress: Boolean = false,
)

@OptIn(FlowPreview::class)
class AppViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val database = AppDatabase.get(application)
    private val devicePreferences =
        application.getSharedPreferences("appearance_reminders", Context.MODE_PRIVATE)
    private val tokenStore = SecureTokenStore(application)
    private val syncPreferences = application.getSharedPreferences("sync_settings", Context.MODE_PRIVATE)
    private var tokens: AccountTokens? = tokenStore.load()
    private val mutableState = MutableStateFlow(AppUiState(
        destination = savedState.get<String>("destination")?.let(AppDestination::valueOf)
            ?: AppDestination.Today,
        answerRevealed = savedState["answerRevealed"] ?: false,
        startedAt = savedState["startedAt"] ?: 0L,
        selectedIds = (savedState.get<ArrayList<String>>("selectedIds") ?: arrayListOf()).toSet(),
        themeMode = devicePreferences.getInt("theme", 2),
        reminderEnabled = devicePreferences.getBoolean("reminder_enabled", false),
        reminderTime = devicePreferences.getString("reminder_time", "20:00") ?: "20:00",
        syncEndpoint = syncPreferences.getString("endpoint", "https://sync.reviewfault.app")
            ?: "https://sync.reviewfault.app",
        accountId = tokens?.accountId,
    ))
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    val searchQuery = MutableStateFlow(savedState["searchQuery"] ?: "")
    private val filterState = MutableStateFlow(LibraryFilter())
    @Volatile private var pendingInk: Pair<String, ByteArray>? = null

    init {
        refreshToday()
        loadSettings()
        refreshSyncState()
        if (tokens != null) syncNow()
        viewModelScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L)
                if (tokens != null) syncNow()
            }
        }
        viewModelScope.launch {
            searchQuery.debounce(300).distinctUntilChanged().collectLatest { query ->
                filterState.update { it.copy(query = query, now = Instant.now().epochSecond, offset = 0) }
                loadLibrary()
            }
        }
        viewModelScope.launch {
            mutableState.collectLatest { state ->
                savedState["destination"] = state.destination.name
                savedState["answerRevealed"] = state.answerRevealed
                savedState["startedAt"] = state.startedAt
                savedState["selectedIds"] = ArrayList(state.selectedIds)
                savedState["currentId"] = state.current?.id
            }
        }
        savedState.get<String>("currentId")?.let { currentId ->
            io {
                val restored = database.search(LibraryFilter(limit = 5000)).firstOrNull { it.id == currentId }
                mutableState.update { state ->
                    if (restored == null) state.copy(destination = AppDestination.Today)
                    else state.copy(current = restored, loading = false)
                }
            }
        }
        viewModelScope.launch {
            searchQuery.collectLatest { savedState["searchQuery"] = it }
        }
        if (mutableState.value.destination == AppDestination.Add) loadTags()
    }

    fun navigate(destination: AppDestination) {
        mutableState.update { it.copy(
            destination = destination,
            current = null,
            answerRevealed = false,
            selectedIds = if (destination == AppDestination.Library) it.selectedIds else emptySet(),
        ) }
        when (destination) {
            AppDestination.Today -> refreshToday()
            AppDestination.Insights -> refreshInsights()
            AppDestination.Library -> loadLibrary()
            AppDestination.Add -> loadTags()
            AppDestination.Settings -> { loadSettings(); loadTrash() }
            else -> Unit
        }
    }

    private fun loadTags() = io {
        mutableState.update { it.copy(availableTags = database.tags()) }
    }

    fun setLibraryFilter(subject: String? = null, kind: String? = null, status: String? = null) {
        val next = filterState.value.copy(
            subjects = subject?.let(::setOf) ?: emptySet(),
            kinds = kind?.let(::setOf) ?: emptySet(),
            status = status ?: "all",
            now = Instant.now().epochSecond,
            offset = 0,
        )
        filterState.value = next
        mutableState.update { it.copy(libraryFilter = next) }
        loadLibrary()
    }

    fun toggleTagFilter(tagId: String) {
        val current = filterState.value
        val nextTags = current.tagIds.toMutableSet().apply { if (!add(tagId)) remove(tagId) }
        val next = current.copy(tagIds = nextTags, offset = 0, now = Instant.now().epochSecond)
        filterState.value = next
        mutableState.update { it.copy(libraryFilter = next) }
        loadLibrary()
    }

    fun refreshToday() = io {
        refreshTodayNow()
    }

    fun refreshInsights() = io {
        val now = Instant.now().epochSecond
        val dayStart = ZonedDateTime.ofInstant(Instant.ofEpochSecond(now), ZoneId.systemDefault())
            .toLocalDate().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        mutableState.update { it.copy(insights = database.insights(now, dayStart), loading = false) }
    }

    private fun refreshTodayNow() {
        val now = Instant.now().epochSecond
        val dayStart = ZonedDateTime.ofInstant(Instant.ofEpochSecond(now), ZoneId.systemDefault())
            .toLocalDate().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        mutableState.update { it.copy(summary = database.dashboard(now, dayStart), loading = false) }
    }

    fun loadLibrary() = io {
        val filter = filterState.value.copy(
            query = searchQuery.value,
            now = Instant.now().epochSecond,
        )
        mutableState.update { it.copy(
            library = database.search(filter),
            availableTags = database.tags(),
            libraryFilter = filter,
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

    fun deleteSelected() = io(exclusive = true) {
        val ids = mutableState.value.selectedIds.toList()
        if (ids.isEmpty()) return@io
        val deletion = database.softDelete(ids)
        mutableState.update { it.copy(selectedIds = emptySet(), deletion = deletion, message = "已移入回收站") }
        loadLibrary()
        loadTrash()
        refreshToday()
    }

    fun undoDeletion() = io(exclusive = true) {
        val deletion = mutableState.value.deletion ?: return@io
        val canUndo = deletion.undoUntil >= Instant.now().epochSecond
        if (canUndo) database.restore(deletion.itemIds)
        mutableState.update { it.copy(
            deletion = null,
            message = if (canUndo) "已撤销删除" else "撤销时限已过，可在设置的回收站中恢复",
        ) }
        loadLibrary(); loadTrash(); refreshToday()
    }

    fun restore(id: String) = io(exclusive = true) {
        database.restore(listOf(id))
        mutableState.update { it.copy(message = "已从回收站恢复") }
        loadTrash(); loadLibrary(); refreshToday()
    }

    fun startReview() = io(exclusive = true) {
        mutableState.update { current ->
            current.copy(
                sessionTargetSeconds = current.summary.estimatedMinutes.coerceAtLeast(1) * 60,
                sessionElapsedSeconds = 0,
                sessionReviewedCount = 0,
                sessionAllowsNewItems = current.summary.deferredDueMinutes == 0,
                sessionSkippedIds = emptySet(),
            )
        }
        startReviewNow()
    }

    fun startNewLearning() = io(exclusive = true) {
        val row = database.nextUnlearned()
        if (row == null) {
            mutableState.update { it.copy(message = "当前没有等待首次学习的内容") }
            return@io
        }
        mutableState.update { it.copy(
            destination = AppDestination.Review,
            current = row,
            answerRevealed = false,
            startedAt = Instant.now().epochSecond,
            sessionTargetSeconds = it.preferences.sessionMinutes.coerceAtLeast(1) * 60,
            sessionElapsedSeconds = 0,
            sessionReviewedCount = 0,
            sessionAllowsNewItems = true,
            sessionSkippedIds = emptySet(),
        ) }
    }

    private fun startReviewNow(message: String? = null) {
        val session = mutableState.value
        if ((session.sessionReviewedCount > 0 || session.sessionSkippedIds.isNotEmpty()) &&
            session.sessionElapsedSeconds >= session.sessionTargetSeconds) {
            finishReviewSessionNow()
            return
        }
        val row = database.nextForReview(
            Instant.now().epochSecond,
            session.sessionAllowsNewItems,
            session.sessionSkippedIds,
        )
        if (row == null) {
            finishReviewSessionNow(
                if (session.sessionReviewedCount == 0 && session.sessionSkippedIds.isEmpty())
                    "当前没有待复习内容"
                else null,
            )
            return
        }
        mutableState.update { it.copy(
            destination = AppDestination.Review,
            current = row, answerRevealed = false, startedAt = Instant.now().epochSecond,
            message = message,
        ) }
    }

    fun skipCurrent() = io(exclusive = true) {
        val snapshot = mutableState.value
        val row = snapshot.current ?: return@io
        val skippedAt = Instant.now().epochSecond
        val durationSeconds = (skippedAt - snapshot.startedAt).toInt().coerceAtLeast(0)
        mutableState.update { it.copy(
            current = null,
            answerRevealed = false,
            sessionElapsedSeconds = it.sessionElapsedSeconds + durationSeconds,
            sessionSkippedIds = it.sessionSkippedIds + row.id,
        ) }
        startReviewNow("已跳过；本轮不会再次出现，也没有生成评分")
    }

    fun finishReviewSession() = io(exclusive = true) {
        finishReviewSessionNow()
    }

    private fun finishReviewSessionNow(overrideMessage: String? = null) {
        refreshTodayNow()
        mutableState.update { current ->
            val summary = "本轮结束：完成 ${current.sessionReviewedCount} 条 · " +
                "跳过 ${current.sessionSkippedIds.size} 条 · ${formatMinutes(current.sessionElapsedSeconds)}"
            current.copy(
                destination = AppDestination.Today,
                current = null,
                answerRevealed = false,
                message = overrideMessage ?: summary,
            )
        }
    }

    fun revealAnswer() = mutableState.update { it.copy(answerRevealed = true) }

    fun score(rating: Int, mathResult: String?, errorReason: String? = null,
              hintRevealed: Boolean = false, hintLevel: Int = if (hintRevealed) 1 else 0,
              pointHits: Int? = null, pointCount: Int? = null, confidence: Int = 3,
              reflection: String = "", answerRevealedBeforeCommit: Boolean = false) = io(exclusive = true) {
        val snapshot = mutableState.value
        val row = snapshot.current ?: return@io
        val reviewedAt = Instant.now().epochSecond
        val durationSeconds = (reviewedAt - snapshot.startedAt).toInt().coerceAtLeast(0)
        pendingInk?.takeIf { it.first == row.id }?.let { database.saveInkDraft(row.id, it.second) }
        val result = database.review(
            row, rating, reviewedAt, durationSeconds,
            mathResult, errorReason, hintRevealed, hintLevel, pointHits, pointCount,
            confidence, reflection, answerRevealedBeforeCommit,
        )
        if (row.kind == "math_problem") database.freezeInkDraft(row.id)
        pendingInk = null
        mutableState.update { it.copy(
            sessionElapsedSeconds = it.sessionElapsedSeconds + durationSeconds,
            sessionReviewedCount = it.sessionReviewedCount + 1,
        ) }
        refreshTodayNow()
        startReviewNow("已保存，下次约 ${formatDays(result.scheduledDays)} 后")
        syncNow()
    }

    fun deleteCurrentWithoutReview() = io(exclusive = true) {
        val id = mutableState.value.current?.id ?: return@io
        val deletion = database.softDelete(listOf(id))
        mutableState.update { it.copy(current = null, destination = AppDestination.Today,
            deletion = deletion, message = "已移入回收站；本次未生成评分") }
        refreshToday(); loadTrash()
        syncNow()
    }

    fun loadSettings() = io {
        mutableState.update { it.copy(preferences = database.learningPreferences()) }
    }

    fun saveSettings(preferences: LearningPreferences, themeMode: Int,
                     reminderEnabled: Boolean, reminderTime: String) = io(exclusive = true) {
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
        refreshTodayNow()
        syncNow()
    }

    fun loadTrash() = io {
        mutableState.update { it.copy(trash = database.search(LibraryFilter(deletedOnly = true))) }
    }

    fun saveInkDraft(studyItemId: String, gzipJson: ByteArray) {
        pendingInk = studyItemId to gzipJson
        io { database.saveInkDraft(studyItemId, gzipJson) }
    }

    fun registerAccount(endpoint: String, email: String, password: String, invitationCode: String) =
        io(exclusive = true) {
            saveSyncEndpoint(endpoint)
            SyncClient(endpoint).register(email, password, invitationCode)
            mutableState.update { it.copy(message = "注册申请已提交，请先完成邮箱验证再登录") }
        }

    fun loginAccount(endpoint: String, email: String, password: String) = io(exclusive = true) {
        saveSyncEndpoint(endpoint)
        val identity = database.syncIdentity()
        val session = SyncClient(endpoint).login(
            email, password, identity.deviceId, "${Build.MANUFACTURER} ${Build.MODEL}",
        )
        database.bindAccount(session.accountId, session.workspaceId)
        saveSession(session)
        mutableState.update { it.copy(accountId = session.accountId, message = "已登录，正在同步") }
        syncNowInternal()
    }

    fun syncNow() = io {
        if (mutableState.value.syncInProgress || tokens == null) return@io
        syncNowInternal()
    }

    private fun syncNowInternal() {
        mutableState.update { it.copy(syncInProgress = true) }
        try {
            val endpoint = mutableState.value.syncEndpoint
            val client = SyncClient(endpoint)
            val identity = database.syncIdentity()
            var session = tokens ?: return
            if (session.accessExpiresAt <= Instant.now().epochSecond + 60) {
                val refreshed = client.refresh(identity.deviceId, session.refreshToken)
                require(refreshed.accountId == session.accountId && refreshed.workspaceId == session.workspaceId) {
                    "刷新令牌返回了不同账号"
                }
                saveSession(refreshed)
                session = tokens!!
            }
            client.uploadMedia(session.accessToken, database.mediaForSync())
            while (true) {
                val pending = database.pendingSyncOperations()
                if (pending.length() == 0) break
                val acknowledged = client.push(session.accessToken, pending)
                require(acknowledged.isNotEmpty()) { "服务端未确认任何本地操作" }
                database.acknowledgeSyncOperations(acknowledged)
            }
            var cursor = database.syncIdentity().cursor
            do {
                val pulled = client.pull(session.accessToken, cursor)
                database.applyPulledOperations(session.workspaceId, pulled.operations, pulled.cursor)
                cursor = pulled.cursor
            } while (pulled.operations.size == 500)
            database.missingMedia().forEach { media ->
                database.saveDownloadedMedia(media, client.downloadMedia(session.accessToken, media.sha256))
            }
            val finishedAt = Instant.now().epochSecond
            syncPreferences.edit().putLong("last_synced_at", finishedAt).apply()
            refreshSyncState()
            mutableState.update { it.copy(lastSyncedAt = finishedAt, message = "同步完成") }
            refreshTodayNow(); loadLibrary()
        } finally { mutableState.update { it.copy(syncInProgress = false) } }
    }

    fun logoutAccount() = io(exclusive = true) {
        val session = tokens
        if (session != null) runCatching {
            SyncClient(mutableState.value.syncEndpoint).logout(session.accessToken)
        }
        tokenStore.clear(); tokens = null
        mutableState.update { it.copy(accountId = null, message = "已退出；本地数据仍保留在此设备") }
    }

    private fun saveSyncEndpoint(endpoint: String) {
        SyncClient(endpoint)
        val normalized = endpoint.trim().trimEnd('/')
        syncPreferences.edit().putString("endpoint", normalized).apply()
        mutableState.update { it.copy(syncEndpoint = normalized) }
    }

    private fun saveSession(session: AuthSession) {
        val stored = AccountTokens(
            session.accountId, session.workspaceId, session.accessToken,
            session.accessExpiresAt, session.refreshToken,
        )
        tokenStore.save(stored); tokens = stored
    }

    private fun refreshSyncState() {
        val identity = database.syncIdentity()
        val last = syncPreferences.getLong("last_synced_at", 0).takeIf { it > 0 }
        mutableState.update { it.copy(
            accountId = tokens?.accountId, syncPendingCount = identity.pendingCount, lastSyncedAt = last,
        ) }
    }

    fun createMemoryCard(template: String, prompt: String, answer: String) = io(exclusive = true) {
        database.createMemoryCard(template, prompt, answer)
        mutableState.update { it.copy(destination = AppDestination.Today, message = "记忆卡已保存") }
        refreshTodayNow()
        syncNow()
    }

    fun createMemoryCard(draft: MemoryCardDraft) = io(exclusive = true) {
        database.createMemoryCard(draft)
        mutableState.update { it.copy(destination = AppDestination.Today, message = "知识卡已保存，可立即开始首次学习") }
        refreshTodayNow(); loadLibrary(); syncNow()
    }

    fun createMathProblem(uris: List<Uri>, source: String) = io(exclusive = true) {
        database.createMathProblemFromImages(getApplication<Application>().contentResolver, uris, source)
        mutableState.update { it.copy(destination = AppDestination.Today, message = "数学错题已保存") }
        refreshTodayNow()
        syncNow()
    }

    fun createMathProblem(uris: List<Uri>, draft: MathErrorDraft) = io(exclusive = true) {
        database.createMathProblemFromImages(getApplication<Application>().contentResolver, uris, draft)
        mutableState.update { it.copy(destination = AppDestination.Today, message = "数学错题已保存，可立即开始首次学习") }
        refreshTodayNow(); loadLibrary(); syncNow()
    }

    fun checkForUpdates() = io {
        if (mutableState.value.updateInProgress) return@io
        mutableState.update { it.copy(updateInProgress = true, downloadedUpdatePath = null) }
        try {
            val update = UpdateService(getApplication()).check(BuildConfig.VERSION_NAME)
            mutableState.update { it.copy(
                availableUpdate = update,
                message = if (update == null) "当前已是最新版本（v${BuildConfig.VERSION_NAME}）" else "发现新版本 v${update.version}",
            ) }
        } finally {
            mutableState.update { it.copy(updateInProgress = false) }
        }
    }

    fun downloadUpdate() = io {
        if (mutableState.value.updateInProgress) return@io
        val update = mutableState.value.availableUpdate ?: return@io
        mutableState.update { it.copy(updateInProgress = true) }
        try {
            val file = UpdateService(getApplication()).download(update)
            mutableState.update { it.copy(
                downloadedUpdatePath = file.absolutePath,
                message = "v${update.version} 已下载，请确认安装",
            ) }
        } finally {
            mutableState.update { it.copy(updateInProgress = false) }
        }
    }

    fun exportBackup(uri: Uri) = io(exclusive = true) {
        getApplication<Application>().contentResolver.openOutputStream(uri, "w")!!.use {
            database.exportBackup(it)
        }
        mutableState.update { it.copy(message = "v5 备份已导出") }
    }

    fun restoreBackup(uri: Uri) = io(exclusive = true) {
        getApplication<Application>().contentResolver.openInputStream(uri)!!.use {
            database.restoreBackup(it)
        }
        mutableState.update { it.copy(destination = AppDestination.Today, message = "备份已恢复，将在下次同步时合并") }
        refreshTodayNow(); loadTrash()
        syncNow()
    }

    fun clearMessage(message: String) = mutableState.update {
        if (it.message == message) it.copy(message = null, deletion = null) else it
    }

    private fun io(exclusive: Boolean = false, block: suspend () -> Unit) {
        if (exclusive && mutableState.value.operationInProgress) return
        if (exclusive) mutableState.update { it.copy(operationInProgress = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try { block() } catch (error: Exception) {
                mutableState.update { it.copy(loading = false, message = error.message ?: "操作失败") }
            } finally {
                if (exclusive) mutableState.update { it.copy(operationInProgress = false) }
            }
        }
    }

    private fun formatDays(days: Double): String = when {
        days < 1.0 / 24.0 -> "${kotlin.math.round(days * 24 * 60).toInt()} 分钟"
        days < 1 -> "${kotlin.math.round(days * 24).toInt()} 小时"
        else -> "${kotlin.math.round(days).toInt()} 天"
    }

    private fun formatMinutes(seconds: Int): String {
        val minutes = (seconds + 59) / 60
        return if (minutes <= 1) "不到 1 分钟" else "$minutes 分钟"
    }
}
