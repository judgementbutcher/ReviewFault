package cn.reviewfault.app.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import cn.reviewfault.app.BuildConfig
import cn.reviewfault.app.core.NativeScheduleResult
import cn.reviewfault.app.core.NativeScheduler
import cn.reviewfault.app.sync.PulledOperation
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class StudyRow(
    val id: String,
    val kind: String,
    val subject: String,
    val state: Int,
    val difficulty: Double,
    val stabilityDays: Double,
    val dueAt: Long,
    val lastReviewedAt: Long,
    val repetitions: Int,
    val lapses: Int,
    val prompt: String,
    val answer: String,
    val mediaPath: String?,
    val templateType: String,
    val hints: List<String>,
    val answerPoints: List<String>,
    val profile: CardProfile,
    val tags: List<String>,
)

data class CardProfile(
    val archetype: String = "qa",
    val knowledgePoint: String = "",
    val sourceType: String = "notes",
    val sourceTitle: String = "",
    val sourceChapter: String = "",
    val sourceLocator: String = "",
    val sourceYear: Int? = null,
    val mechanism: String = "",
    val conditions: String = "",
    val contrast: String = "",
    val example: String = "",
    val commonTrap: String = "",
    val transferPrompt: String = "",
    val mnemonic: String = "",
    val firstAttempt: String = "",
    val errorTrigger: String = "",
    val generalMethod: String = "",
    val verification: String = "",
    val targetSeconds: Int? = null,
    val structuredPayload: String = "{}",
)

data class MemoryCardDraft(
    val templateType: String,
    val archetype: String,
    val subject: String,
    val knowledgePoint: String,
    val prompt: String,
    val answer: String,
    val hints: List<String> = emptyList(),
    val answerPoints: List<String> = emptyList(),
    val mechanism: String = "",
    val conditions: String = "",
    val contrast: String = "",
    val example: String = "",
    val commonTrap: String = "",
    val transferPrompt: String = "",
    val mnemonic: String = "",
    val structuredPayload: String = "{}",
    val sourceType: String = "notes",
    val sourceTitle: String = "",
    val sourceChapter: String = "",
    val sourceLocator: String = "",
    val sourceYear: Int? = null,
    val tags: List<String> = emptyList(),
)

data class MathErrorDraft(
    val knowledgePoint: String = "",
    val sourceType: String = "practice",
    val sourceTitle: String = "",
    val sourceChapter: String = "",
    val sourceLocator: String = "",
    val sourceYear: Int? = null,
    val prompt: String = "",
    val solution: String = "",
    val firstAttempt: String = "",
    val errorTrigger: String = "",
    val errorReason: String? = null,
    val keyHint: String = "",
    val generalMethod: String = "",
    val verification: String = "",
    val transferPrompt: String = "",
    val targetSeconds: Int? = null,
    val tags: List<String> = emptyList(),
)

data class TagRow(val id: String, val name: String, val itemCount: Int)

data class DashboardSummary(
    val overdue: Int,
    val dueToday: Int,
    val newItems: Int,
    val estimatedMinutes: Int,
    val deferredDueMinutes: Int = 0,
    val tomorrowDue: Int = 0,
    val nextSevenDaysDue: Int = 0,
)

data class InsightDay(
    val label: String,
    val dueLabel: String,
    val reviews: Int,
    val due: Int,
)

data class SubjectInsight(
    val subject: String,
    val total: Int,
    val mastered: Int,
)

data class InsightsSnapshot(
    val reviewsToday: Int = 0,
    val accuracyPercent: Int = 0,
    val streakDays: Int = 0,
    val totalReviews: Int = 0,
    val activeItems: Int = 0,
    val masteredItems: Int = 0,
    val days: List<InsightDay> = emptyList(),
    val subjects: List<SubjectInsight> = emptyList(),
)

data class LearningPreferences(
    val dailyNewMemoryLimit: Int = 20,
    val sessionMinutes: Int = 20,
    val enabledSubjects: Set<String> = setOf(
        "data_structures", "computer_organization", "operating_systems", "computer_networks",
    ),
    val includeMemoryCards: Boolean = true,
    val includeMathProblems: Boolean = true,
    val memoryPreset: String = "balanced",
    val mathIntensity: String = "balanced",
    val schedulerGeneration: Int = 3,
)

data class LibraryFilter(
    val query: String = "",
    val subjects: Set<String> = emptySet(),
    val kinds: Set<String> = emptySet(),
    val tagIds: Set<String> = emptySet(),
    val status: String = "all",
    val includeDeleted: Boolean = false,
    val deletedOnly: Boolean = false,
    val now: Long = Instant.now().epochSecond,
    val offset: Int = 0,
    val limit: Int = 50,
)

data class DeletionState(val itemIds: List<String>, val deletedAt: Long, val undoUntil: Long)

data class SyncIdentity(
    val deviceId: String, val workspaceId: String?, val cursor: Long, val pendingCount: Int,
)
data class SyncMediaObject(val sha256: String, val mimeType: String, val byteCount: Long, val file: File)
data class MissingMediaObject(val sha256: String, val file: File)
private data class AttemptSyncFact(
    val startedAt: Long, val finishedAt: Long, val result: String, val errorReason: String?,
)
private data class EvidenceTaskTarget(
    val id: String, val type: String, val repetitions: Int, val consecutiveFailures: Int,
)

class AppDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, "reviewfault.db", null, 5) {
    // v1 review_log is intentionally read-only and is consulted only by lazy history replay.
    // v3 parameter checksums are foreign-keyed to algorithm_parameter_registry.

    private val appContext = context.applicationContext
    private val deviceId: String by lazy {
        val preferences = appContext.getSharedPreferences("identity", Context.MODE_PRIVATE)
        preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("device_id", it).apply()
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        applyMigration(db, "001_initial.sql")
        applyMigration(db, "002_v0_2.sql")
        applyMigration(db, "003_v0_3.sql")
        applyMigration(db, "004_v0_4.sql")
        applyMigration(db, "005_v0_5.sql")
        ensureLocalDevice(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var current = oldVersion
        if (current < 2 && newVersion >= 2) {
            applyMigration(db, "002_v0_2.sql")
            current = 2
        }
        if (current < 3 && newVersion >= 3) {
            applyMigration(db, "003_v0_3.sql")
            current = 3
        }
        if (current < 4 && newVersion >= 4) {
            applyMigration(db, "004_v0_4.sql")
            current = 4
        }
        if (current < 5 && newVersion >= 5) {
            applyMigration(db, "005_v0_5.sql")
            current = 5
        }
        ensureLocalDevice(db)
        check(current == newVersion) { "缺少数据库迁移：$oldVersion -> $newVersion" }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (db.version == 5 && !db.isReadOnly) ensureLocalDevice(db)
    }

    private fun ensureLocalDevice(db: SQLiteDatabase) {
        db.insertWithOnConflict("local_device", null, ContentValues().apply {
            put("singleton", 1); put("device_id", deviceId)
            put("next_counter", 1); put("created_at", Instant.now().epochSecond)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun bindAccount(accountId: String, workspaceId: String) {
        require(accountId.isNotBlank() && workspaceId.isNotBlank()) { "账号与 workspace 不能为空" }
        writableDatabase.inTransaction {
            val existing = rawQuery(
                "SELECT account_id, workspace_id FROM local_device WHERE singleton = 1", null,
            ).use { cursor -> check(cursor.moveToFirst());
                (if (cursor.isNull(0)) null else cursor.getString(0)) to
                    (if (cursor.isNull(1)) null else cursor.getString(1)) }
            require(existing.first == null || existing == (accountId to workspaceId)) {
                "此本地数据目录已绑定其他账号；请先导出备份并明确清除本地数据"
            }
            execSQL(
                "UPDATE local_device SET account_id = ?, workspace_id = ? WHERE singleton = 1",
                arrayOf(accountId, workspaceId),
            )
            execSQL(
                "INSERT OR IGNORE INTO sync_cursor (workspace_id, server_seq, updated_at) VALUES (?, 0, ?)",
                arrayOf<Any>(workspaceId, Instant.now().epochSecond),
            )
        }
    }

    fun syncIdentity(): SyncIdentity = readableDatabase.rawQuery(
        """
        SELECT d.device_id, d.workspace_id,
          COALESCE((SELECT server_seq FROM sync_cursor WHERE workspace_id = d.workspace_id), 0),
          (SELECT COUNT(*) FROM sync_outbox)
        FROM local_device d WHERE d.singleton = 1
        """.trimIndent(), null,
    ).use { cursor ->
        check(cursor.moveToFirst())
        SyncIdentity(
            cursor.getString(0), if (cursor.isNull(1)) null else cursor.getString(1),
            cursor.getLong(2), cursor.getInt(3),
        )
    }

    fun pendingSyncOperations(limit: Int = 200): JSONArray {
        val result = JSONArray()
        readableDatabase.rawQuery(
            """
            SELECT operation_id, device_id, device_counter, base_cursor, base_revision,
              entity_type, entity_id, action, changed_fields_json, occurred_at
            FROM sync_outbox ORDER BY device_counter LIMIT ?
            """.trimIndent(), arrayOf(limit.coerceIn(1, 500).toString()),
        ).use { cursor -> while (cursor.moveToNext()) result.put(JSONObject().apply {
            put("operationId", cursor.getString(0)); put("deviceId", cursor.getString(1))
            put("deviceCounter", cursor.getLong(2)); put("baseCursor", cursor.getLong(3))
            put("baseRevision", cursor.getLong(4)); put("entityType", cursor.getString(5))
            put("entityId", cursor.getString(6)); put("action", cursor.getString(7))
            put("changedFields", JSONObject(cursor.getString(8))); put("occurredAt", cursor.getLong(9))
        }) }
        return result
    }

    fun acknowledgeSyncOperations(operationIds: Set<String>) {
        if (operationIds.isEmpty()) return
        writableDatabase.inTransaction {
            operationIds.forEach { delete("sync_outbox", "operation_id = ?", arrayOf(it)) }
        }
    }

    fun mediaForSync(): List<SyncMediaObject> = readableDatabase.rawQuery(
        "SELECT sha256, mime_type, byte_count, relative_path FROM media WHERE deleted_at IS NULL", null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) {
        val file = File(appContext.filesDir, cursor.getString(3))
        if (file.isFile) add(SyncMediaObject(cursor.getString(0), cursor.getString(1), cursor.getLong(2), file))
    } } }

    fun missingMedia(): List<MissingMediaObject> = readableDatabase.rawQuery(
        "SELECT sha256, relative_path FROM media WHERE deleted_at IS NULL", null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) {
        val file = File(appContext.filesDir, cursor.getString(1))
        if (!file.isFile) add(MissingMediaObject(cursor.getString(0), file))
    } } }

    fun saveDownloadedMedia(media: MissingMediaObject, bytes: ByteArray) {
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        require(actual == media.sha256) { "下载媒体哈希不匹配" }
        media.file.parentFile?.mkdirs()
        val temporary = File(media.file.parentFile, ".${media.file.name}.${UUID.randomUUID()}.tmp")
        temporary.writeBytes(bytes)
        if (media.file.exists()) temporary.delete()
        else check(temporary.renameTo(media.file)) { "无法保存同步媒体" }
    }

    fun advanceSyncCursor(workspaceId: String, cursor: Long) {
        writableDatabase.execSQL(
            """
            INSERT INTO sync_cursor (workspace_id, server_seq, updated_at) VALUES (?, ?, ?)
            ON CONFLICT(workspace_id) DO UPDATE SET server_seq = MAX(server_seq, excluded.server_seq),
              updated_at = excluded.updated_at
            """.trimIndent(), arrayOf<Any>(workspaceId, cursor, Instant.now().epochSecond),
        )
    }

    fun applyPulledOperations(workspaceId: String, operations: List<PulledOperation>, cursor: Long) {
        writableDatabase.inTransaction {
            operations.sortedBy(PulledOperation::serverSeq).forEach { operation ->
                val remoteApplyCounterStart = rawQuery(
                    "SELECT next_counter FROM local_device WHERE singleton = 1", null,
                ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
                when (operation.entityType) {
                    "studyItem" -> applyRemoteStudyItem(operation)
                    "memoryCard" -> applyRemoteMemoryCard(operation)
                    "mathProblem" -> applyRemoteMathProblem(operation)
                    "cardProfile" -> applyRemoteCardProfile(operation)
                    "tag" -> applyRemoteTag(operation)
                    "relation" -> applyRemoteRelation(operation)
                    "learningPreferences" -> applyRemoteLearningPreferences(operation)
                    "attemptArtifact" -> applyRemoteAttemptArtifact(operation)
                    "reviewAction" -> applyRemoteReviewAction(operation)
                    "learningEvidence" -> applyRemoteLearningEvidence(operation)
                    // Artifact bytes are transferred by the media loop. Retaining its revision here
                    // prevents a metadata-only pull from being mistaken for a missing operation.
                }
                // Content triggers serve ordinary local writes too. Discard only the
                // operations produced while projecting this remote fact.
                execSQL(
                    """
                    DELETE FROM sync_outbox
                    WHERE device_id = (SELECT device_id FROM local_device WHERE singleton = 1)
                      AND device_counter >= ?;
                    DELETE FROM relation_operation
                    WHERE device_id = (SELECT device_id FROM local_device WHERE singleton = 1)
                      AND device_counter >= ?;
                    """.trimIndent(),
                    arrayOf<Any>(remoteApplyCounterStart, remoteApplyCounterStart),
                )
                execSQL(
                    """
                    INSERT INTO sync_revision (entity_type, entity_id, revision, server_seq, deleted)
                    VALUES (?, ?, 1, ?, ?)
                    ON CONFLICT(entity_type, entity_id) DO UPDATE SET
                      revision = revision + 1, server_seq = MAX(server_seq, excluded.server_seq),
                      deleted = excluded.deleted
                    """.trimIndent(),
                    arrayOf<Any>(operation.entityType, operation.entityId, operation.serverSeq,
                        if (operation.action == "delete") 1 else 0),
                )
            }
            execSQL(
                """
                INSERT INTO sync_cursor (workspace_id, server_seq, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(workspace_id) DO UPDATE SET server_seq = MAX(server_seq, excluded.server_seq),
                  updated_at = excluded.updated_at
                """.trimIndent(), arrayOf<Any>(workspaceId, cursor, Instant.now().epochSecond),
            )
        }
        rebuildDirtySchedules()
    }

    private data class ReviewFact(
        val actionId: String, val deviceId: String, val deviceCounter: Long, val causalCursor: Long,
        val feedback: Int, val reviewedAt: Long, val durationSeconds: Int, val errorReason: String?,
        val hintRevealed: Boolean,
    )

    private fun rebuildDirtySchedules() {
        val dirty = readableDatabase.rawQuery(
            """
            SELECT c.study_item_id, i.kind FROM schedule_cache_v4 c
            JOIN study_item i ON i.id = c.study_item_id WHERE c.dirty = 1
            """.trimIndent(), null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) } }
        if (dirty.isEmpty()) return
        val preferences = learningPreferences()
        val memoryPreset = listOf("time_saving", "balanced", "reinforced").indexOf(preferences.memoryPreset).coerceAtLeast(0)
        val mathIntensity = listOf("intensive", "balanced", "relaxed").indexOf(preferences.mathIntensity).coerceAtLeast(0)
        writableDatabase.inTransaction {
            dirty.forEach { (itemId, kind) ->
                val facts = reviewFacts(itemId)
                val order = NativeScheduler.nativeCanonicalOrderV4(
                    facts.map(ReviewFact::actionId).toTypedArray(), facts.map(ReviewFact::deviceId).toTypedArray(),
                    facts.map(ReviewFact::deviceCounter).toLongArray(), facts.map(ReviewFact::causalCursor).toLongArray(),
                    facts.map(ReviewFact::feedback).toIntArray(), facts.map(ReviewFact::reviewedAt).toLongArray(),
                )
                if (kind == "memory_card") {
                    var memoryState = 0; var difficulty = 0.0; var stability = 0.0; var dueAt = 0L
                    var lastReviewedAt = 0L; var repetitions = 0; var totalLapses = 0; var consecutiveLapses = 0
                    order.forEachIndexed { historyCount, index ->
                        val fact = facts[index]; val effective = maxOf(fact.reviewedAt, lastReviewedAt)
                        val result = NativeScheduler.nativeReviewMemoryV3(
                            memoryState, difficulty, stability, dueAt, lastReviewedAt,
                            repetitions, totalLapses, fact.feedback, effective, memoryPreset,
                            historyCount, 0.0, consecutiveLapses,
                        )
                        memoryState = result.state; difficulty = result.difficulty; stability = result.stabilityDays
                        dueAt = result.dueAt; lastReviewedAt = effective; repetitions = result.repetitions
                        totalLapses = result.lapses
                        consecutiveLapses = if (fact.feedback == 1) consecutiveLapses + 1 else 0
                    }
                    execSQL("""
                        UPDATE schedule_state_v2 SET due_at = ?, last_reviewed_at = ?, repetitions = ?,
                          updated_at = ? WHERE study_item_id = ?;
                        UPDATE memory_schedule_state SET state = ?, difficulty = ?, stability_days = ?, lapses = ?
                          WHERE study_item_id = ?;
                        UPDATE study_item SET scheduler_state = ?, difficulty = ?, stability_days = ?,
                          due_at = ?, last_reviewed_at = ?, repetitions = ?, lapses = ?, updated_at = ?
                          WHERE id = ?;
                        """.trimIndent(), arrayOf<Any>(dueAt, lastReviewedAt, repetitions,
                        Instant.now().epochSecond, itemId, memoryState, difficulty, stability,
                        totalLapses, itemId, memoryState, difficulty, stability, dueAt,
                        lastReviewedAt, repetitions, totalLapses, Instant.now().epochSecond, itemId))
                } else {
                    var mastery = 0; var streak = 0; var dueAt = 0L; var lastReviewedAt = 0L; var repetitions = 0
                    var failures = 0; var totalLapses = 0; var scheduledDays = 0.0
                    order.forEach { index -> val fact = facts[index]; val effective = maxOf(fact.reviewedAt, lastReviewedAt)
                        val result = NativeScheduler.nativeReviewMathV3(
                            mastery, streak, dueAt, lastReviewedAt, repetitions, fact.feedback,
                            errorReasonCode(fact.errorReason), fact.hintRevealed, effective, mathIntensity,
                            fact.durationSeconds, 0, failures,
                        )
                        mastery = result.masteryLevel; streak = result.fluentStreak; dueAt = result.dueAt
                        lastReviewedAt = effective; repetitions = result.repetitions; scheduledDays = result.scheduledDays
                        if (fact.feedback <= 1) totalLapses++
                        failures = if (fact.feedback <= 1) failures + 1 else 0
                    }
                    execSQL("""
                        UPDATE schedule_state_v2 SET due_at = ?, last_reviewed_at = ?, repetitions = ?,
                          updated_at = ? WHERE study_item_id = ?;
                        UPDATE math_schedule_state SET mastery_level = ?, fluent_streak = ? WHERE study_item_id = ?;
                        UPDATE study_item SET scheduler_state = ?, difficulty = ?, stability_days = ?,
                          due_at = ?, last_reviewed_at = ?, repetitions = ?, lapses = ?, updated_at = ?
                          WHERE id = ?;
                        """.trimIndent(), arrayOf<Any>(dueAt, lastReviewedAt, repetitions,
                        Instant.now().epochSecond, itemId, mastery, streak, itemId,
                        if (repetitions == 0) 0 else 2, if (repetitions == 0) 0.0 else mastery + 1.0,
                        scheduledDays, dueAt, lastReviewedAt, repetitions, totalLapses,
                        Instant.now().epochSecond, itemId))
                }
                execSQL("UPDATE schedule_cache_v4 SET due_at = ?, replayed_action_count = ?, dirty = 0, rebuilt_at = ? WHERE study_item_id = ?",
                    arrayOf<Any>(if (kind == "memory_card") scheduleDue(itemId) else scheduleDue(itemId), facts.size,
                        Instant.now().epochSecond, itemId))
            }
        }
    }

    private fun SQLiteDatabase.reviewFacts(itemId: String): List<ReviewFact> = rawQuery(
        """
        SELECT action_id, device_id, device_counter, causal_cursor, feedback, reviewed_at,
          COALESCE(duration_seconds, 0), error_reason, hint_revealed
        FROM review_action_v4 WHERE study_item_id = ?
        """.trimIndent(), arrayOf(itemId),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(ReviewFact(
        cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getLong(3), cursor.getInt(4),
        cursor.getLong(5), cursor.getInt(6), if (cursor.isNull(7)) null else cursor.getString(7), cursor.getInt(8) != 0,
    )) } }

    private fun SQLiteDatabase.scheduleDue(itemId: String): Long = rawQuery(
        "SELECT due_at FROM schedule_state_v2 WHERE study_item_id = ?", arrayOf(itemId),
    ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }

    private fun errorReasonCode(value: String?): Int = when (value) {
        "concept" -> 1; "approach" -> 2; "calculation" -> 3; "misread" -> 4
        "forgotten_fact" -> 5; "timeout" -> 6; "other" -> 7; else -> 0
    }

    private fun SQLiteDatabase.applyRemoteStudyItem(operation: PulledOperation) {
        val fields = operation.changedFields
        val existing = rawQuery("SELECT kind FROM study_item WHERE id = ?", arrayOf(operation.entityId))
            .use { if (it.moveToFirst()) it.getString(0) else null }
        val kind = fields.optString("kind", existing ?: "memory_card")
        val now = fields.optLong("updatedAt", operation.occurredAt)
        if (existing == null) {
            insertOrThrow("study_item", null, ContentValues().apply {
                put("id", operation.entityId); put("kind", kind)
                put("subject", fields.optString("subject", if (kind == "math_problem") "math" else "operating_systems"))
                put("created_at", fields.optLong("createdAt", operation.occurredAt)); put("updated_at", now)
                if (operation.action == "delete") put("deleted_at", operation.occurredAt)
            })
            if (kind == "memory_card") insertOrThrow("memory_card", null, ContentValues().apply {
                put("study_item_id", operation.entityId); put("template_type", fields.optString("templateType", "qa"))
                put("prompt_markdown", fields.optString("prompt")); put("answer_markdown", fields.optString("answer"))
                put("hints_json", fields.optJSONArray("hints")?.toString() ?: "[]")
                put("answer_points_json", fields.optJSONArray("answerPoints")?.toString() ?: "[]")
            }) else insertOrThrow("math_problem", null, ContentValues().apply {
                put("study_item_id", operation.entityId); put("source_name", fields.optString("sourceName"))
                put("solution_markdown", fields.optString("solution")); put("wrong_step_markdown", fields.optString("wrongStep"))
                put("key_hint_markdown", fields.optString("keyHint"))
            })
            if (kind == "math_problem") attachRemoteMediaMetadata(operation.entityId, fields.optJSONArray("media"), operation.occurredAt)
        } else {
            update("study_item", ContentValues().apply {
                fields.optString("subject").takeIf(String::isNotBlank)?.let { put("subject", it) }
                put("updated_at", now)
                if (operation.action == "delete") put("deleted_at", operation.occurredAt)
                if (operation.action == "restore") putNull("deleted_at")
            }, "id = ?", arrayOf(operation.entityId))
            if (kind == "memory_card") update("memory_card", ContentValues().apply {
                if (fields.has("templateType")) put("template_type", fields.getString("templateType"))
                if (fields.has("prompt")) put("prompt_markdown", fields.getString("prompt"))
                if (fields.has("answer")) put("answer_markdown", fields.getString("answer"))
                if (fields.has("hints")) put("hints_json", fields.getJSONArray("hints").toString())
                if (fields.has("answerPoints")) put("answer_points_json", fields.getJSONArray("answerPoints").toString())
            }, "study_item_id = ?", arrayOf(operation.entityId))
            else update("math_problem", ContentValues().apply {
                if (fields.has("sourceName")) put("source_name", fields.getString("sourceName"))
                if (fields.has("solution")) put("solution_markdown", fields.getString("solution"))
                if (fields.has("wrongStep")) put("wrong_step_markdown", fields.getString("wrongStep"))
                if (fields.has("keyHint")) put("key_hint_markdown", fields.getString("keyHint"))
            }, "study_item_id = ?", arrayOf(operation.entityId))
            if (kind == "math_problem" && fields.has("media"))
                attachRemoteMediaMetadata(operation.entityId, fields.optJSONArray("media"), operation.occurredAt)
        }
    }

    private fun SQLiteDatabase.attachRemoteMediaMetadata(
        problemId: String, values: JSONArray?, createdAt: Long,
    ) {
        if (values == null) return
        for (index in 0 until values.length()) {
            val value = values.getJSONObject(index); val sha = value.getString("sha256")
            val mime = value.getString("mimeType"); val extension = when (mime) {
                "image/png" -> "png"; "image/webp" -> "webp"; "application/gzip" -> "reviewfault-ink.gz"
                else -> "jpg"
            }
            insertWithOnConflict("media", null, ContentValues().apply {
                put("id", uuidV7()); put("sha256", sha); put("mime_type", mime)
                put("byte_count", value.getLong("byteCount")); put("relative_path", "media/$sha.$extension")
                put("created_at", createdAt)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            val mediaId = rawQuery("SELECT id FROM media WHERE sha256 = ?", arrayOf(sha)).use {
                check(it.moveToFirst()); it.getString(0)
            }
            insertWithOnConflict("math_problem_media", null, ContentValues().apply {
                put("math_problem_id", problemId); put("media_id", mediaId); put("role", "prompt"); put("sort_order", index)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    private fun SQLiteDatabase.applyRemoteMemoryCard(operation: PulledOperation) {
        val fields = operation.changedFields
        update("memory_card", ContentValues().apply {
            if (fields.has("templateType")) put("template_type", fields.getString("templateType"))
            if (fields.has("promptMarkdown")) put("prompt_markdown", fields.getString("promptMarkdown"))
            if (fields.has("answerMarkdown")) put("answer_markdown", fields.getString("answerMarkdown"))
            if (fields.has("hints")) put("hints_json", fields.getJSONArray("hints").toString())
            if (fields.has("answerPoints")) put("answer_points_json", fields.getJSONArray("answerPoints").toString())
            if (fields.has("occlusions")) put("occlusions_json", fields.getJSONArray("occlusions").toString())
        }, "study_item_id = ?", arrayOf(operation.entityId))
    }

    private fun SQLiteDatabase.applyRemoteMathProblem(operation: PulledOperation) {
        val fields = operation.changedFields
        update("math_problem", ContentValues().apply {
            if (fields.has("sourceName")) put("source_name", fields.getString("sourceName"))
            if (fields.has("promptMarkdown")) put("prompt_markdown", fields.getString("promptMarkdown"))
            if (fields.has("solutionMarkdown")) put("solution_markdown", fields.getString("solutionMarkdown"))
            if (fields.has("wrongStepMarkdown")) put("wrong_step_markdown", fields.getString("wrongStepMarkdown"))
            if (fields.has("keyHintMarkdown")) put("key_hint_markdown", fields.getString("keyHintMarkdown"))
            if (fields.has("defaultErrorReason")) put("default_error_reason", fields.getString("defaultErrorReason"))
        }, "study_item_id = ?", arrayOf(operation.entityId))
    }

    private fun SQLiteDatabase.applyRemoteTag(operation: PulledOperation) {
        val name = operation.changedFields.optString("name").trim()
        if (name.isEmpty()) return
        insertWithOnConflict("tag", null, ContentValues().apply {
            put("id", operation.entityId); put("name", name)
            put("created_at", operation.occurredAt); put("updated_at", operation.occurredAt)
            if (operation.action == "delete") put("deleted_at", operation.occurredAt)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        update("tag", ContentValues().apply {
            put("name", name); put("updated_at", operation.occurredAt)
            if (operation.action == "delete") put("deleted_at", operation.occurredAt) else putNull("deleted_at")
        }, "id = ?", arrayOf(operation.entityId))
    }

    private fun SQLiteDatabase.applyRemoteRelation(operation: PulledOperation) {
        val fields = operation.changedFields
        if (fields.optString("relationType") != "study_item_tag") return
        val source = fields.optString("sourceId"); val target = fields.optString("targetId")
        if (source.isEmpty() || target.isEmpty() || operation.action !in setOf("add", "remove")) return
        insertWithOnConflict("relation_operation", null, ContentValues().apply {
            put("operation_id", operation.operationId); put("relation_type", "study_item_tag")
            put("source_id", source); put("target_id", target); put("action", operation.action)
            put("device_id", operation.deviceId); put("device_counter", operation.deviceCounter)
            put("observed_adds_json", fields.optJSONArray("observedAdds")?.toString() ?: "[]")
            put("occurred_at", operation.occurredAt)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        val active = rawQuery("""
            SELECT EXISTS (SELECT 1 FROM relation_operation add_op
              WHERE add_op.relation_type = 'study_item_tag' AND add_op.source_id = ?
                AND add_op.target_id = ? AND add_op.action = 'add'
                AND NOT EXISTS (SELECT 1 FROM relation_operation remove_op,
                  json_each(remove_op.observed_adds_json) observed
                  WHERE remove_op.relation_type = add_op.relation_type
                    AND remove_op.source_id = add_op.source_id
                    AND remove_op.target_id = add_op.target_id
                    AND remove_op.action = 'remove' AND observed.value = add_op.operation_id))
            """.trimIndent(), arrayOf(source, target)).use { it.moveToFirst() && it.getInt(0) != 0 }
        if (active) execSQL("""
            INSERT OR IGNORE INTO study_item_tag (study_item_id, tag_id)
            SELECT ?, ? WHERE EXISTS (SELECT 1 FROM study_item WHERE id = ?)
              AND EXISTS (SELECT 1 FROM tag WHERE id = ?)
            """.trimIndent(), arrayOf<Any>(source, target, source, target))
        else delete(
            "study_item_tag", "study_item_id = ? AND tag_id = ?", arrayOf(source, target),
        )
    }

    private fun SQLiteDatabase.applyRemoteLearningPreferences(operation: PulledOperation) {
        val fields = operation.changedFields
        update("learning_preferences", ContentValues().apply {
            if (fields.has("dailyNewMemoryLimit")) put("daily_new_memory_limit", fields.getInt("dailyNewMemoryLimit"))
            if (fields.has("sessionMinutes")) put("session_minutes", fields.getInt("sessionMinutes"))
            if (fields.has("memoryPreset")) put("memory_preset", fields.getString("memoryPreset"))
            if (fields.has("mathIntensity")) put("math_intensity", fields.getString("mathIntensity"))
            if (fields.has("schedulerGeneration")) put("scheduler_generation", fields.getInt("schedulerGeneration"))
            put("updated_at", operation.occurredAt)
        }, "singleton = 1", null)
    }

    private fun SQLiteDatabase.applyRemoteAttemptArtifact(operation: PulledOperation) {
        val fields = operation.changedFields
        val attemptId = fields.optString("attemptId"); val studyItemId = fields.optString("studyItemId")
        val artifactType = fields.optString("artifactType"); val sha = fields.optString("mediaSha256")
        val mime = fields.optString("mediaMimeType"); val bytes = fields.optLong("mediaByteCount")
        val attemptResult = fields.optString("result", "effortful")
        val errorReason = fields.optString("errorReason")
        if (attemptId.isEmpty() || studyItemId.isEmpty() || sha.length != 64 || mime.isEmpty() || bytes <= 0 ||
            artifactType !in setOf("reviewfault-ink-v1", "png-preview", "annotated-image") ||
            attemptResult !in setOf("again", "wrong", "effortful", "fluent") ||
            (errorReason.isNotEmpty() && errorReason !in setOf("concept", "approach", "calculation", "misread",
                "forgotten_fact", "timeout", "other"))) return
        val itemExists = rawQuery("SELECT 1 FROM math_problem WHERE study_item_id = ?", arrayOf(studyItemId))
            .use(Cursor::moveToFirst)
        if (!itemExists) return
        insertWithOnConflict("attempt", null, ContentValues().apply {
            put("id", attemptId); put("math_problem_id", studyItemId)
            put("started_at", fields.optLong("startedAt", operation.occurredAt))
            put("finished_at", fields.optLong("finishedAt", operation.occurredAt))
            put("result", attemptResult)
            errorReason.takeIf(String::isNotBlank)?.let { put("error_reason", it) }
            put("created_at", operation.occurredAt)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        val extension = when (mime) {
            "image/png" -> ".png"; "image/webp" -> ".webp"
            "application/gzip" -> ".reviewfault-ink.gz"; else -> ".jpg"
        }
        insertWithOnConflict("media", null, ContentValues().apply {
            put("id", uuidV7()); put("sha256", sha); put("mime_type", mime); put("byte_count", bytes)
            put("relative_path", "media/$sha$extension"); put("created_at", operation.occurredAt)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        val mediaId = rawQuery("SELECT id FROM media WHERE sha256 = ?", arrayOf(sha)).use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return
        insertWithOnConflict("attempt_artifact", null, ContentValues().apply {
            put("id", operation.entityId); put("attempt_id", attemptId); put("artifact_type", artifactType)
            put("media_id", mediaId); put("page_count", fields.optInt("pageCount", 1).coerceAtLeast(1))
            fields.optString("backgroundMediaSha256").takeIf(String::isNotBlank)?.let {
                put("background_media_sha256", it)
            }
            put("created_at", operation.occurredAt)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun SQLiteDatabase.applyRemoteReviewAction(operation: PulledOperation) {
        val fields = operation.changedFields
        val itemId = fields.getString("studyItemId")
        val itemExists = rawQuery("SELECT 1 FROM study_item WHERE id = ?", arrayOf(itemId))
            .use(Cursor::moveToFirst)
        if (!itemExists) return
        insertWithOnConflict("review_action_v4", null, ContentValues().apply {
            put("action_id", operation.entityId); put("study_item_id", itemId)
            put("algorithm", fields.getString("algorithm")); put("feedback", fields.getInt("feedback"))
            put("reviewed_at", fields.getLong("reviewedAt")); put("duration_seconds", fields.optInt("durationSeconds"))
            fields.optString("errorReason").takeIf(String::isNotBlank)?.let { put("error_reason", it) }
            put("hint_revealed", if (fields.optBoolean("hintRevealed")) 1 else 0)
            put("device_id", operation.deviceId); put("device_counter", operation.deviceCounter)
            put("causal_cursor", operation.serverSeq); put("source_generation", 4); put("created_at", operation.occurredAt)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        execSQL("UPDATE schedule_cache_v4 SET dirty = 1 WHERE study_item_id = ?", arrayOf(itemId))
    }

    private fun SQLiteDatabase.applyRemoteLearningEvidence(operation: PulledOperation) {
        val fields = operation.changedFields
        val taskId = fields.optString("taskId")
        if (taskId.isBlank() || !rawQuery("SELECT 1 FROM learning_task_v5 WHERE id = ?", arrayOf(taskId))
                .use(Cursor::moveToFirst)) return
        insertWithOnConflict("learning_evidence_v5", null, ContentValues().apply {
            put("evidence_id", operation.entityId); put("learning_task_id", taskId)
            put("task_type", fields.optString("taskType", "memory_recall"))
            put("reviewed_at", fields.optLong("reviewedAt", operation.occurredAt))
            put("correct", if (fields.optBoolean("correct")) 1 else 0)
            put("error_mask", fields.optInt("errorMask", 0)); put("hint_level", fields.optInt("hintLevel", 0))
            put("answer_revealed", if (fields.optBoolean("answerRevealed")) 1 else 0)
            put("duration_reliable", if (fields.optBoolean("durationReliable", true)) 1 else 0)
            fields.takeIf { it.has("pointHits") && !it.isNull("pointHits") }?.let { put("point_hits", it.getInt("pointHits")) }
            fields.takeIf { it.has("pointCount") && !it.isNull("pointCount") }?.let { put("point_count", it.getInt("pointCount")) }
            fields.takeIf { it.has("durationSeconds") && !it.isNull("durationSeconds") }?.let { put("duration_seconds", it.getInt("durationSeconds")) }
            fields.takeIf { it.has("confidence") && !it.isNull("confidence") }?.let { put("confidence", it.getInt("confidence")) }
            put("reflection_markdown", fields.optString("reflection")); put("device_id", operation.deviceId)
            put("device_counter", operation.deviceCounter); put("causal_cursor", operation.serverSeq)
            put("created_at", operation.occurredAt)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun SQLiteDatabase.applyRemoteCardProfile(operation: PulledOperation) {
        if (!rawQuery("SELECT 1 FROM study_item WHERE id = ?", arrayOf(operation.entityId))
                .use(Cursor::moveToFirst)) return
        val fields = operation.changedFields
        val values = ContentValues().apply {
            put("archetype", fields.optString("archetype", "qa"))
            put("knowledge_point", fields.optString("knowledgePoint"))
            put("source_type", fields.optString("sourceType", "notes"))
            put("source_title", fields.optString("sourceTitle"))
            put("source_chapter", fields.optString("sourceChapter"))
            put("source_locator", fields.optString("sourceLocator"))
            if (fields.has("sourceYear") && !fields.isNull("sourceYear")) put("source_year", fields.getInt("sourceYear"))
            else putNull("source_year")
            put("mechanism_markdown", fields.optString("mechanism"))
            put("conditions_markdown", fields.optString("conditions"))
            put("contrast_markdown", fields.optString("contrast"))
            put("example_markdown", fields.optString("example"))
            put("common_trap_markdown", fields.optString("commonTrap"))
            put("transfer_prompt_markdown", fields.optString("transferPrompt"))
            put("mnemonic", fields.optString("mnemonic"))
            put("first_attempt_markdown", fields.optString("firstAttempt"))
            put("error_trigger_markdown", fields.optString("errorTrigger"))
            put("general_method_markdown", fields.optString("generalMethod"))
            put("verification_markdown", fields.optString("verification"))
            if (fields.has("targetSeconds") && !fields.isNull("targetSeconds")) put("target_seconds", fields.getInt("targetSeconds"))
            else putNull("target_seconds")
            put("structured_payload_json", fields.opt("structuredPayload")?.toString() ?: "{}")
            put("updated_at", operation.occurredAt)
        }
        val updated = update("card_profile_v5", values, "study_item_id = ?", arrayOf(operation.entityId))
        if (updated == 0) {
            values.put("study_item_id", operation.entityId)
            values.put("created_at", operation.occurredAt)
            insertOrThrow("card_profile_v5", null, values)
        }
    }

    private fun SQLiteDatabase.enqueueSync(
        entityType: String, entityId: String, action: String, fields: JSONObject, occurredAt: Long,
    ) {
        val identity = rawQuery(
            """
            SELECT d.device_id, d.next_counter,
              COALESCE((SELECT server_seq FROM sync_cursor WHERE workspace_id = d.workspace_id), 0),
              COALESCE((SELECT revision FROM sync_revision WHERE entity_type = ? AND entity_id = ?), 0)
            FROM local_device d WHERE singleton = 1
            """.trimIndent(), arrayOf(entityType, entityId),
        ).use { cursor -> check(cursor.moveToFirst());
            listOf(cursor.getString(0), cursor.getLong(1), cursor.getLong(2), cursor.getLong(3)) }
        execSQL("UPDATE local_device SET next_counter = next_counter + 1 WHERE singleton = 1")
        insertOrThrow("sync_outbox", null, ContentValues().apply {
            put("operation_id", UUID.randomUUID().toString()); put("device_id", identity[0] as String)
            put("device_counter", identity[1] as Long); put("base_cursor", identity[2] as Long)
            put("base_revision", identity[3] as Long); put("entity_type", entityType); put("entity_id", entityId)
            put("action", action); put("changed_fields_json", fields.toString()); put("occurred_at", occurredAt)
        })
    }

    private fun applyMigration(db: SQLiteDatabase, assetName: String) {
        val sql = appContext.assets.open(assetName).bufferedReader().use { it.readText() }
        migrationStatements(sql).forEach(db::execSQL)
    }

    fun dashboard(now: Long, dayStart: Long): DashboardSummary {
        readableDatabase.rawQuery(
            """
            SELECT
              COALESCE(SUM(CASE WHEN s.scheduler_state <> 0 AND s.due_at < ? THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN s.scheduler_state <> 0 AND s.due_at BETWEEN ? AND ? THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN s.scheduler_state = 0 AND s.kind = 'memory_card' THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN s.scheduler_state = 0 AND s.kind = 'math_problem' THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN s.scheduler_state <> 0 AND s.due_at <= ?
                THEN CASE WHEN s.kind = 'math_problem' THEN 480 ELSE 45 END ELSE 0 END), 0),
              lp.daily_new_memory_limit,
              ((SELECT COUNT(*) FROM review_event_v2 e
               JOIN memory_review_event_v2 mr ON mr.review_event_id = e.id
               WHERE mr.state_before = 0 AND e.reviewed_at >= ?) +
               (SELECT COUNT(*) FROM review_event_v3 e
                JOIN memory_review_event_v3 mr ON mr.review_event_id = e.id
                WHERE mr.state_before = 0 AND e.reviewed_at >= ?))
              , lp.session_minutes,
              COALESCE(SUM(CASE WHEN s.scheduler_state <> 0
                AND s.due_at >= ? AND s.due_at < ? THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN s.scheduler_state <> 0
                AND s.due_at > ? AND s.due_at <= ? THEN 1 ELSE 0 END), 0)
            FROM learning_preferences lp
            LEFT JOIN study_item s ON s.suspended_at IS NULL AND s.deleted_at IS NULL AND (
              (s.kind = 'math_problem' AND lp.include_math_problems = 1) OR
              (s.kind = 'memory_card' AND lp.include_memory_cards = 1 AND (
                (s.subject = 'data_structures' AND lp.enable_data_structures = 1) OR
                (s.subject = 'computer_organization' AND lp.enable_computer_organization = 1) OR
                (s.subject = 'operating_systems' AND lp.enable_operating_systems = 1) OR
                (s.subject = 'computer_networks' AND lp.enable_computer_networks = 1))))
            WHERE lp.singleton = 1
            """.trimIndent(),
            arrayOf(dayStart.toString(), dayStart.toString(), now.toString(), now.toString(),
                dayStart.toString(), dayStart.toString(),
                (dayStart + 86_400).toString(), (dayStart + 2 * 86_400).toString(),
                now.toString(), (now + 7 * 86_400).toString()),
        ).use { cursor ->
            cursor.moveToFirst()
            val remainingNewMemory = (cursor.getInt(5) - cursor.getInt(6)).coerceAtLeast(0)
            val dueSeconds = cursor.intOrZero(4)
            val budgetSeconds = cursor.intOrZero(7) * 60
            var remainingSeconds = (budgetSeconds - dueSeconds).coerceAtLeast(0)
            var newMemory = 0
            var newMath = 0
            if (dueSeconds <= budgetSeconds) {
                newMemory = cursor.intOrZero(2).coerceAtMost(remainingNewMemory)
                    .coerceAtMost(remainingSeconds / 45)
                remainingSeconds -= newMemory * 45
                newMath = cursor.intOrZero(3).coerceAtMost(remainingSeconds / 480)
            }
            if (dueSeconds == 0 && newMemory == 0 && newMath == 0) {
                if (cursor.intOrZero(2) > 0 && remainingNewMemory > 0) newMemory = 1
                else if (cursor.intOrZero(3) > 0) newMath = 1
            }
            val focusSeconds = dueSeconds.coerceAtMost(budgetSeconds) +
                newMemory * 45 + newMath * 480
            return DashboardSummary(
                cursor.intOrZero(0),
                cursor.intOrZero(1),
                newMemory + newMath,
                (focusSeconds + 59) / 60,
                ((dueSeconds - budgetSeconds).coerceAtLeast(0) + 59) / 60,
                cursor.intOrZero(8),
                cursor.intOrZero(9),
            )
        }
    }

    fun insights(now: Long, dayStart: Long): InsightsSnapshot {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochSecond(dayStart).atZone(zone).toLocalDate()
        val historyStart = dayStart - 6 * 86_400L
        val reviewByDay = mutableMapOf<Int, Int>()
        readableDatabase.rawQuery(
            """
            WITH events AS (
              SELECT reviewed_at FROM review_event_v2
              UNION ALL SELECT reviewed_at FROM review_event_v3
            )
            SELECT CAST((reviewed_at - ?) / 86400 AS INTEGER), COUNT(*)
            FROM events WHERE reviewed_at >= ? AND reviewed_at < ?
            GROUP BY 1
            """.trimIndent(),
            arrayOf(historyStart.toString(), historyStart.toString(), (dayStart + 86_400).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) reviewByDay[cursor.getInt(0)] = cursor.getInt(1)
        }
        val dueByDay = mutableMapOf<Int, Int>()
        readableDatabase.rawQuery(
            """
            SELECT CAST((due_at - ?) / 86400 AS INTEGER), COUNT(*)
            FROM study_item
            WHERE deleted_at IS NULL AND suspended_at IS NULL AND scheduler_state <> 0
              AND due_at >= ? AND due_at < ?
            GROUP BY 1
            """.trimIndent(),
            arrayOf(dayStart.toString(), dayStart.toString(), (dayStart + 7 * 86_400).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) dueByDay[cursor.getInt(0)] = cursor.getInt(1)
        }
        val days = (0..6).map { index ->
            InsightDay(
                label = today.minusDays((6 - index).toLong()).dayOfWeek
                    .getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.SIMPLIFIED_CHINESE),
                dueLabel = today.plusDays(index.toLong()).dayOfWeek
                    .getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.SIMPLIFIED_CHINESE),
                reviews = reviewByDay[index] ?: 0,
                due = dueByDay[index] ?: 0,
            )
        }
        var totalReviews = 0
        var recentReviews = 0
        var recentSuccessful = 0
        readableDatabase.rawQuery(
            """
            WITH events AS (
              SELECT algorithm, feedback, reviewed_at FROM review_event_v2
              UNION ALL SELECT algorithm, feedback, reviewed_at FROM review_event_v3
            )
            SELECT COUNT(*),
              COALESCE(SUM(CASE WHEN reviewed_at >= ? THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN reviewed_at >= ? AND (
                (algorithm = 'memory_fsrs_6' AND feedback >= 3) OR
                (algorithm = 'math_mastery_ladder' AND feedback >= 2)
              ) THEN 1 ELSE 0 END), 0)
            FROM events
            """.trimIndent(),
            arrayOf(historyStart.toString(), historyStart.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                totalReviews = cursor.getInt(0)
                recentReviews = cursor.getInt(1)
                recentSuccessful = cursor.getInt(2)
            }
        }
        val reviewedDates = mutableSetOf<LocalDate>()
        readableDatabase.rawQuery(
            """
            SELECT reviewed_at FROM review_event_v2 WHERE reviewed_at >= ?
            UNION ALL SELECT reviewed_at FROM review_event_v3 WHERE reviewed_at >= ?
            """.trimIndent(),
            arrayOf((dayStart - 366 * 86_400L).toString(), (dayStart - 366 * 86_400L).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) reviewedDates +=
                Instant.ofEpochSecond(cursor.getLong(0)).atZone(zone).toLocalDate()
        }
        var streakDays = 0
        var streakDate = today
        if (streakDate !in reviewedDates) streakDate = streakDate.minusDays(1)
        while (streakDate in reviewedDates) {
            streakDays++
            streakDate = streakDate.minusDays(1)
        }
        var activeItems = 0
        var masteredItems = 0
        readableDatabase.rawQuery(
            """
            SELECT COUNT(*), COALESCE(SUM(CASE WHEN repetitions >= 3 AND stability_days >= 14 THEN 1 ELSE 0 END), 0)
            FROM study_item WHERE deleted_at IS NULL AND suspended_at IS NULL
            """.trimIndent(), null,
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                activeItems = cursor.getInt(0)
                masteredItems = cursor.getInt(1)
            }
        }
        val subjects = mutableListOf<SubjectInsight>()
        readableDatabase.rawQuery(
            """
            SELECT subject, COUNT(*),
              COALESCE(SUM(CASE WHEN repetitions >= 3 AND stability_days >= 14 THEN 1 ELSE 0 END), 0)
            FROM study_item WHERE deleted_at IS NULL AND suspended_at IS NULL
            GROUP BY subject ORDER BY COUNT(*) DESC
            """.trimIndent(), null,
        ).use { cursor ->
            while (cursor.moveToNext()) subjects += SubjectInsight(
                cursor.getString(0), cursor.getInt(1), cursor.getInt(2),
            )
        }
        return InsightsSnapshot(
            reviewsToday = reviewByDay[6] ?: 0,
            accuracyPercent = if (recentReviews == 0) 0 else (recentSuccessful * 100 / recentReviews),
            streakDays = streakDays,
            totalReviews = totalReviews,
            activeItems = activeItems,
            masteredItems = masteredItems,
            days = days,
            subjects = subjects,
        )
    }

    fun nextForReview(
        now: Long,
        includeNewItems: Boolean = true,
        excludedItemIds: Set<String> = emptySet(),
    ): StudyRow? {
        require(excludedItemIds.none { it.isEmpty() || '|' in it }) {
            "会话排除项包含非法 ID"
        }
        val encodedExcludedItemIds = if (excludedItemIds.isEmpty()) "" else
            excludedItemIds.sorted().joinToString(separator = "|", prefix = "|", postfix = "|")
        val dayStart = ZonedDateTime.ofInstant(Instant.ofEpochSecond(now), ZoneId.systemDefault())
            .toLocalDate().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        readableDatabase.rawQuery(
            """
            SELECT $STUDY_ROW_COLUMNS
            FROM study_item s
            LEFT JOIN memory_card m ON m.study_item_id = s.id
            LEFT JOIN math_problem p ON p.study_item_id = s.id
            LEFT JOIN card_profile_v5 cp ON cp.study_item_id = s.id
            LEFT JOIN math_problem_media pm
              ON pm.math_problem_id = s.id AND pm.role = 'prompt' AND pm.sort_order = 0
            LEFT JOIN media ON media.id = pm.media_id
            CROSS JOIN learning_preferences lp
            WHERE s.suspended_at IS NULL AND s.deleted_at IS NULL
              AND lp.singleton = 1
              AND (? = '' OR instr(?, '|' || s.id || '|') = 0)
              AND ((s.kind = 'math_problem' AND lp.include_math_problems = 1) OR
                (s.kind = 'memory_card' AND lp.include_memory_cards = 1 AND (
                  (s.subject = 'data_structures' AND lp.enable_data_structures = 1) OR
                  (s.subject = 'computer_organization' AND lp.enable_computer_organization = 1) OR
                  (s.subject = 'operating_systems' AND lp.enable_operating_systems = 1) OR
                  (s.subject = 'computer_networks' AND lp.enable_computer_networks = 1))))
              AND ((s.scheduler_state <> 0 AND s.due_at <= ?)
                OR (? = 1 AND s.scheduler_state = 0 AND (s.kind = 'math_problem' OR (
                  SELECT
                    (SELECT COUNT(*) FROM review_event_v2 e
                     JOIN memory_review_event_v2 mr ON mr.review_event_id = e.id
                     WHERE mr.state_before = 0 AND e.reviewed_at >= ?) +
                    (SELECT COUNT(*) FROM review_event_v3 e
                     JOIN memory_review_event_v3 mr ON mr.review_event_id = e.id
                     WHERE mr.state_before = 0 AND e.reviewed_at >= ?)
                ) < lp.daily_new_memory_limit)))
            ORDER BY
              CASE WHEN s.scheduler_state <> 0 AND s.due_at < ? THEN 0
                   WHEN s.scheduler_state <> 0 THEN 1 ELSE 2 END,
              CASE WHEN s.scheduler_state <> 0 AND s.due_at < ?
                   THEN CASE WHEN s.kind = 'memory_card' THEN 0 ELSE 1 END ELSE 0 END,
              CASE WHEN s.kind = 'math_problem' AND s.chapter_id IS NOT NULL AND
                s.chapter_id = (SELECT previous.chapter_id FROM study_item previous
                  WHERE previous.id = (SELECT study_item_id FROM (
                    SELECT study_item_id, reviewed_at FROM review_event_v3
                    UNION ALL SELECT study_item_id, reviewed_at FROM review_event_v2
                  ) ORDER BY reviewed_at DESC LIMIT 1)) THEN 1 ELSE 0 END,
              CASE WHEN s.kind = 'memory_card' THEN 0 ELSE 1 END,
              s.due_at,
              s.created_at
            LIMIT 1
            """.trimIndent(),
            arrayOf(encodedExcludedItemIds, encodedExcludedItemIds,
                now.toString(), includeNewItems.toInt().toString(),
                dayStart.toString(), dayStart.toString(),
                dayStart.toString(), dayStart.toString()),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toStudyRow() else null
        }
    }

    fun nextUnlearned(): StudyRow? = readableDatabase.rawQuery(
        """
        SELECT $STUDY_ROW_COLUMNS
        FROM study_item s
        LEFT JOIN memory_card m ON m.study_item_id = s.id
        LEFT JOIN math_problem p ON p.study_item_id = s.id
        LEFT JOIN card_profile_v5 cp ON cp.study_item_id = s.id
        LEFT JOIN math_problem_media pm
          ON pm.math_problem_id = s.id AND pm.role = 'prompt' AND pm.sort_order = 0
        LEFT JOIN media ON media.id = pm.media_id
        CROSS JOIN learning_preferences lp
        WHERE s.suspended_at IS NULL AND s.deleted_at IS NULL AND s.scheduler_state = 0
          AND lp.singleton = 1
          AND ((s.kind = 'math_problem' AND lp.include_math_problems = 1) OR
            (s.kind = 'memory_card' AND lp.include_memory_cards = 1 AND (
              (s.subject = 'data_structures' AND lp.enable_data_structures = 1) OR
              (s.subject = 'computer_organization' AND lp.enable_computer_organization = 1) OR
              (s.subject = 'operating_systems' AND lp.enable_operating_systems = 1) OR
              (s.subject = 'computer_networks' AND lp.enable_computer_networks = 1))))
        ORDER BY s.created_at, s.id
        LIMIT 1
        """.trimIndent(), null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toStudyRow() else null }

    fun search(query: String): List<StudyRow> = search(LibraryFilter(query = query, limit = 100))

    fun search(filter: LibraryFilter): List<StudyRow> {
        require(filter.limit in 1..200 && filter.offset >= 0) { "分页参数无效" }
        val pattern = "%${filter.query.trim().replace("\\", "\\\\")
            .replace("%", "\\%").replace("_", "\\_")}%"
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        clauses += when {
            filter.deletedOnly -> "s.deleted_at IS NOT NULL"
            filter.includeDeleted -> "1 = 1"
            else -> "s.deleted_at IS NULL"
        }
        if (filter.subjects.isNotEmpty()) {
            clauses += "s.subject IN (${filter.subjects.joinToString { "?" }})"
            args += filter.subjects.sorted()
        }
        if (filter.kinds.isNotEmpty()) {
            clauses += "s.kind IN (${filter.kinds.joinToString { "?" }})"
            args += filter.kinds.sorted()
        }
        if (filter.tagIds.isNotEmpty()) {
            clauses += "EXISTS (SELECT 1 FROM study_item_tag sit WHERE sit.study_item_id = s.id " +
                "AND sit.tag_id IN (${filter.tagIds.joinToString { "?" }}))"
            args += filter.tagIds.sorted()
        }
        when (filter.status) {
            "new" -> clauses += "s.scheduler_state = 0"
            "due" -> { clauses += "s.scheduler_state <> 0 AND s.due_at <= ?"; args += filter.now.toString() }
            "suspended" -> clauses += "s.suspended_at IS NOT NULL"
            "all" -> Unit
            else -> error("不支持的题库状态")
        }
        clauses += "(? = '%%' OR COALESCE(m.prompt_markdown, p.prompt_markdown, '') LIKE ? ESCAPE '\\' " +
            "OR COALESCE(m.answer_markdown, p.solution_markdown, '') LIKE ? ESCAPE '\\' " +
            "OR COALESCE(p.source_name, '') LIKE ? ESCAPE '\\' " +
            "OR COALESCE(cp.knowledge_point, '') LIKE ? ESCAPE '\\' " +
            "OR COALESCE(cp.source_title, '') LIKE ? ESCAPE '\\' " +
            "OR COALESCE(cp.source_chapter, '') LIKE ? ESCAPE '\\' " +
            "OR COALESCE(cp.source_locator, '') LIKE ? ESCAPE '\\' " +
            "OR EXISTS (SELECT 1 FROM study_item_tag qit JOIN tag qt ON qt.id = qit.tag_id " +
            "WHERE qit.study_item_id = s.id AND qt.name LIKE ? ESCAPE '\\'))"
        args += List(9) { pattern }
        args += filter.limit.toString()
        args += filter.offset.toString()
        return readableDatabase.rawQuery(
            """
            SELECT $STUDY_ROW_COLUMNS
            FROM study_item s
            LEFT JOIN memory_card m ON m.study_item_id = s.id
            LEFT JOIN math_problem p ON p.study_item_id = s.id
            LEFT JOIN card_profile_v5 cp ON cp.study_item_id = s.id
            LEFT JOIN math_problem_media pm
              ON pm.math_problem_id = s.id AND pm.role = 'prompt' AND pm.sort_order = 0
            LEFT JOIN media ON media.id = pm.media_id
            WHERE ${clauses.joinToString(" AND ")}
            ORDER BY s.updated_at DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            args.toTypedArray(),
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(cursor.toStudyRow())
        } }
    }

    fun tags(): List<TagRow> = readableDatabase.rawQuery(
        """
        SELECT t.id, t.name, COUNT(sit.study_item_id)
        FROM tag t LEFT JOIN study_item_tag sit ON sit.tag_id = t.id
        LEFT JOIN study_item s ON s.id = sit.study_item_id AND s.deleted_at IS NULL
        WHERE t.deleted_at IS NULL
        GROUP BY t.id, t.name HAVING COUNT(s.id) > 0
        ORDER BY COUNT(s.id) DESC, t.name COLLATE NOCASE
        LIMIT 100
        """.trimIndent(), null,
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(TagRow(cursor.getString(0), cursor.getString(1), cursor.getInt(2)))
    } }

    fun learningPreferences(): LearningPreferences = readableDatabase.rawQuery(
        "SELECT * FROM learning_preferences WHERE singleton = 1", null,
    ).use { cursor ->
        check(cursor.moveToFirst())
        val subjects = buildSet {
            if (cursor.getInt(cursor.getColumnIndexOrThrow("enable_data_structures")) != 0) add("data_structures")
            if (cursor.getInt(cursor.getColumnIndexOrThrow("enable_computer_organization")) != 0) add("computer_organization")
            if (cursor.getInt(cursor.getColumnIndexOrThrow("enable_operating_systems")) != 0) add("operating_systems")
            if (cursor.getInt(cursor.getColumnIndexOrThrow("enable_computer_networks")) != 0) add("computer_networks")
        }
        LearningPreferences(
            cursor.getInt(cursor.getColumnIndexOrThrow("daily_new_memory_limit")),
            cursor.getInt(cursor.getColumnIndexOrThrow("session_minutes")), subjects,
            cursor.getInt(cursor.getColumnIndexOrThrow("include_memory_cards")) != 0,
            cursor.getInt(cursor.getColumnIndexOrThrow("include_math_problems")) != 0,
            cursor.getString(cursor.getColumnIndexOrThrow("memory_preset")),
            cursor.getString(cursor.getColumnIndexOrThrow("math_intensity")),
            cursor.getInt(cursor.getColumnIndexOrThrow("scheduler_generation")),
        )
    }

    fun saveLearningPreferences(value: LearningPreferences) {
        require(value.dailyNewMemoryLimit in 0..500 && value.sessionMinutes in 1..240)
        require(value.includeMemoryCards || value.includeMathProblems)
        require(value.memoryPreset in setOf("time_saving", "balanced", "reinforced"))
        require(value.mathIntensity in setOf("intensive", "balanced", "relaxed"))
        require(value.schedulerGeneration in 2..3)
        writableDatabase.update("learning_preferences", ContentValues().apply {
            put("daily_new_memory_limit", value.dailyNewMemoryLimit)
            put("session_minutes", value.sessionMinutes)
            put("enable_data_structures", ("data_structures" in value.enabledSubjects).toInt())
            put("enable_computer_organization", ("computer_organization" in value.enabledSubjects).toInt())
            put("enable_operating_systems", ("operating_systems" in value.enabledSubjects).toInt())
            put("enable_computer_networks", ("computer_networks" in value.enabledSubjects).toInt())
            put("include_memory_cards", value.includeMemoryCards.toInt())
            put("include_math_problems", value.includeMathProblems.toInt())
            put("memory_preset", value.memoryPreset)
            put("math_intensity", value.mathIntensity)
            put("scheduler_generation", value.schedulerGeneration)
            put("updated_at", Instant.now().epochSecond)
        }, "singleton = 1", null)
    }

    fun softDelete(itemIds: List<String>, now: Long = Instant.now().epochSecond): DeletionState {
        if (itemIds.isEmpty()) return DeletionState(emptyList(), now, now)
        val placeholders = itemIds.joinToString { "?" }
        writableDatabase.inTransaction {
            execSQL(
                "UPDATE study_item SET deleted_at = ?, updated_at = ? WHERE deleted_at IS NULL AND id IN ($placeholders)",
                arrayOf<Any>(now, now, *itemIds.toTypedArray()),
            )
        }
        return DeletionState(itemIds, now, now + 10)
    }

    fun restore(itemIds: List<String>) {
        if (itemIds.isEmpty()) return
        val now = Instant.now().epochSecond
        val placeholders = itemIds.joinToString { "?" }
        writableDatabase.inTransaction {
            execSQL(
                "UPDATE study_item SET deleted_at = NULL, updated_at = ? WHERE id IN ($placeholders)",
                arrayOf<Any>(now, *itemIds.toTypedArray()),
            )
        }
    }

    fun replaceTags(itemId: String, names: List<String>) {
        val now = Instant.now().epochSecond
        writableDatabase.inTransaction {
            replaceTagsInTransaction(itemId, names, now)
        }
    }

    private fun SQLiteDatabase.replaceTagsInTransaction(itemId: String, names: List<String>, now: Long) {
        val normalized = names.map(String::trim).filter(String::isNotEmpty)
            .distinctBy { it.lowercase() }.take(30)
        delete("study_item_tag", "study_item_id = ?", arrayOf(itemId))
        normalized.forEach { name ->
            require(name.length <= 60) { "单个标签不能超过 60 个字符" }
            val existing = rawQuery("SELECT id FROM tag WHERE name = ? COLLATE NOCASE", arrayOf(name))
                .use { if (it.moveToFirst()) it.getString(0) else null }
            val tagId = existing ?: uuidV7().also { id ->
                insertOrThrow("tag", null, ContentValues().apply {
                    put("id", id); put("name", name); put("created_at", now); put("updated_at", now)
                })
            }
            execSQL("UPDATE tag SET deleted_at = NULL, updated_at = ? WHERE id = ?", arrayOf<Any>(now, tagId))
            enqueueSync("tag", tagId, "create", JSONObject().put("name", name), now)
            insertOrThrow("study_item_tag", null, ContentValues().apply {
                put("study_item_id", itemId); put("tag_id", tagId)
            })
        }
    }

    fun mediaPaths(mathProblemId: String): List<String> = readableDatabase.rawQuery(
        """
        SELECT media.relative_path FROM math_problem_media pm
        JOIN media ON media.id = pm.media_id
        WHERE pm.math_problem_id = ? AND pm.role = 'prompt' AND media.deleted_at IS NULL
        ORDER BY pm.sort_order
        """.trimIndent(),
        arrayOf(mathProblemId),
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(cursor.getString(0))
    } }

    fun saveInkDraft(studyItemId: String, gzipJson: ByteArray) {
        require(gzipJson.isNotEmpty()) { "笔迹草稿为空" }
        writableDatabase.insertWithOnConflict("local_ink_draft", null, ContentValues().apply {
            put("study_item_id", studyItemId); put("format_version", 1)
            put("gzip_json", gzipJson); put("updated_at", Instant.now().epochSecond)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun freezeInkDraft(studyItemId: String) {
        val draft = readableDatabase.rawQuery(
            "SELECT gzip_json FROM local_ink_draft WHERE study_item_id = ?", arrayOf(studyItemId),
        ).use { if (it.moveToFirst()) it.getBlob(0) else null } ?: return
        val attemptId = readableDatabase.rawQuery(
            "SELECT id FROM attempt WHERE math_problem_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
            arrayOf(studyItemId),
        ).use { if (it.moveToFirst()) it.getString(0) else null } ?: return
        val attempt = readableDatabase.rawQuery(
            "SELECT started_at, finished_at, result, error_reason FROM attempt WHERE id = ?",
            arrayOf(attemptId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else AttemptSyncFact(
                cursor.getLong(0), if (cursor.isNull(1)) Instant.now().epochSecond else cursor.getLong(1),
                cursor.getString(2), if (cursor.isNull(3)) null else cursor.getString(3),
            )
        } ?: return
        val document = GZIPInputStream(draft.inputStream()).use { input ->
            JSONObject(input.bufferedReader().readText())
        }
        val pageCount = document.getJSONArray("pages").length()
        val mediaDirectory = File(appContext.filesDir, "media").apply { mkdirs() }
        val temporary = File(mediaDirectory, ".ink-${UUID.randomUUID()}.tmp").apply { writeBytes(draft) }
        val hash = sha256(temporary)
        val finalFile = File(mediaDirectory, "$hash.ink.gz")
        if (finalFile.exists()) temporary.delete() else check(temporary.renameTo(finalFile))
        val preview = renderInkPreview(document)
        val previewTemporary = File(mediaDirectory, ".ink-preview-${UUID.randomUUID()}.tmp")
            .apply { writeBytes(preview) }
        val previewHash = sha256(previewTemporary)
        val previewFile = File(mediaDirectory, "$previewHash.png")
        if (previewFile.exists()) previewTemporary.delete() else check(previewTemporary.renameTo(previewFile))
        val now = Instant.now().epochSecond
        writableDatabase.inTransaction {
            var mediaId = rawQuery("SELECT id FROM media WHERE sha256 = ?", arrayOf(hash)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (mediaId == null) {
                mediaId = uuidV7()
                insertOrThrow("media", null, ContentValues().apply {
                    put("id", mediaId); put("sha256", hash); put("mime_type", "application/gzip")
                    put("byte_count", draft.size); put("relative_path", "media/${finalFile.name}")
                    put("created_at", now)
                })
            }
            val artifactId = uuidV7()
            insertOrThrow("attempt_artifact", null, ContentValues().apply {
                put("id", artifactId); put("attempt_id", attemptId)
                put("artifact_type", "reviewfault-ink-v1"); put("media_id", mediaId)
                put("page_count", pageCount); put("created_at", now)
            })
            var previewMediaId = rawQuery("SELECT id FROM media WHERE sha256 = ?", arrayOf(previewHash)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (previewMediaId == null) {
                previewMediaId = uuidV7()
                insertOrThrow("media", null, ContentValues().apply {
                    put("id", previewMediaId); put("sha256", previewHash); put("mime_type", "image/png")
                    put("byte_count", preview.size); put("width", 512); put("height", 384)
                    put("relative_path", "media/${previewFile.name}"); put("created_at", now)
                })
            }
            val previewArtifactId = uuidV7()
            insertOrThrow("attempt_artifact", null, ContentValues().apply {
                put("id", previewArtifactId); put("attempt_id", attemptId); put("artifact_type", "png-preview")
                put("media_id", previewMediaId); put("page_count", pageCount); put("created_at", now)
            })
            val attemptFields = JSONObject().apply {
                put("attemptId", attemptId); put("studyItemId", studyItemId)
                put("startedAt", attempt.startedAt); put("finishedAt", attempt.finishedAt)
                put("result", attempt.result); put("errorReason", attempt.errorReason ?: JSONObject.NULL)
                put("pageCount", pageCount)
            }
            enqueueSync("attemptArtifact", artifactId, "create", JSONObject(attemptFields.toString()).apply {
                put("artifactType", "reviewfault-ink-v1"); put("mediaSha256", hash)
                put("mediaMimeType", "application/gzip"); put("mediaByteCount", draft.size)
            }, now)
            enqueueSync("attemptArtifact", previewArtifactId, "create", JSONObject(attemptFields.toString()).apply {
                put("artifactType", "png-preview"); put("mediaSha256", previewHash)
                put("mediaMimeType", "image/png"); put("mediaByteCount", preview.size)
            }, now)
            delete("local_ink_draft", "study_item_id = ?", arrayOf(studyItemId))
        }
    }

    private fun renderInkPreview(document: JSONObject): ByteArray {
        val bitmap = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val strokes = document.getJSONArray("pages").getJSONObject(0).getJSONArray("strokes")
        for (index in 0 until strokes.length()) {
            val stroke = strokes.getJSONObject(index)
            val points = stroke.getJSONArray("points")
            if (points.length() == 0) continue
            val path = Path()
            points.getJSONObject(0).let { path.moveTo(it.getDouble("x").toFloat() * 512, it.getDouble("y").toFloat() * 384) }
            for (pointIndex in 1 until points.length()) points.getJSONObject(pointIndex).let {
                path.lineTo(it.getDouble("x").toFloat() * 512, it.getDouble("y").toFloat() * 384)
            }
            val tool = stroke.getString("tool")
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                color = if (tool == "eraser") Color.WHITE else Color.parseColor(stroke.getString("color"))
                alpha = if (tool == "highlighter") 90 else 255
                strokeWidth = stroke.getDouble("width").toFloat() * 384
            }
            canvas.drawPath(path, paint)
        }
        return java.io.ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
        }
    }

    fun createMemoryCard(
        templateType: String,
        prompt: String,
        answer: String,
        hints: List<String> = emptyList(),
        answerPoints: List<String> = emptyList(),
        subject: String = "operating_systems",
    ): String = createMemoryCard(MemoryCardDraft(
        templateType = templateType, archetype = when (templateType) {
            "comparison" -> "comparison"; "enumeration" -> "enumeration"
            "image_occlusion" -> "diagram"; "cloze" -> "cloze"; else -> "qa"
        }, subject = subject, knowledgePoint = "", prompt = prompt, answer = answer,
        hints = hints, answerPoints = answerPoints,
    ))

    fun createMemoryCard(draft: MemoryCardDraft): String {
        validateMemoryDraft(draft)
        val now = Instant.now().epochSecond
        val id = uuidV7()
        writableDatabase.inTransaction {
            insertOrThrow("study_item", null, ContentValues().apply {
                put("id", id)
                put("kind", "memory_card")
                put("subject", draft.subject)
                put("created_at", now)
                put("updated_at", now)
            })
            insertOrThrow("card_profile_v5", null, profileValues(id, draft, now))
            insertOrThrow("memory_card", null, ContentValues().apply {
                put("study_item_id", id)
                put("template_type", draft.templateType)
                put("prompt_markdown", draft.prompt.trim())
                put("answer_markdown", draft.answer.trim())
                put("hints_json", jsonArray(draft.hints))
                put("answer_points_json", jsonArray(draft.answerPoints))
            })
            replaceTagsInTransaction(id, draft.tags, now)
        }
        return id
    }

    fun createMathProblemFromImage(
        resolver: ContentResolver,
        uri: Uri,
        sourceName: String = "",
    ): String = createMathProblemFromImages(resolver, listOf(uri), sourceName)

    fun createMathProblemFromImages(
        resolver: ContentResolver,
        uris: List<Uri>,
        sourceName: String = "",
    ): String = createMathProblemFromImages(
        resolver, uris, MathErrorDraft(sourceTitle = sourceName),
    )

    fun createMathProblemFromImages(
        resolver: ContentResolver,
        uris: List<Uri>,
        draft: MathErrorDraft,
    ): String {
        require(uris.isNotEmpty() && uris.size <= 5) { "每道题请选择 1–5 张图片" }
        validateMathDraft(draft)
        val mediaDirectory = File(appContext.filesDir, "media").apply { mkdirs() }
        val prepared = uris.map { uri ->
            val mime = resolver.getType(uri) ?: "image/jpeg"
            val extension = when (mime) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val temporary = File(mediaDirectory, ".import-${UUID.randomUUID()}.tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            var byteCount = 0L
            try {
                val input = resolver.openInputStream(uri) ?: error("无法读取所选图片")
                input.use { source ->
                    temporary.outputStream().buffered().use { destination ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                            destination.write(buffer, 0, count)
                            byteCount += count
                        }
                    }
                }
            } catch (error: Exception) {
                temporary.delete()
                throw error
            }
            require(byteCount > 0) { temporary.delete(); "图片为空" }
            val digestText = digest.digest().joinToString("") { "%02x".format(it) }
            val file = File(mediaDirectory, "$digestText.$extension")
            if (file.exists()) {
                temporary.delete()
            } else if (!temporary.renameTo(file)) {
                temporary.copyTo(file)
                temporary.delete()
            }
            PreparedMedia(byteCount, digestText, mime, file)
        }
        val now = Instant.now().epochSecond
        val problemId = uuidV7()
        writableDatabase.inTransaction {
            insertOrThrow("study_item", null, ContentValues().apply {
                put("id", problemId)
                put("kind", "math_problem")
                put("subject", "math")
                put("created_at", now)
                put("updated_at", now)
            })
            insertOrThrow("card_profile_v5", null, profileValues(problemId, draft, now))
            insertOrThrow("math_problem", null, ContentValues().apply {
                put("study_item_id", problemId)
                put("source_name", draft.sourceTitle.trim())
                put("source_page", draft.sourceLocator.trim())
                if (draft.sourceYear != null) put("source_year", draft.sourceYear)
                put("prompt_markdown", draft.prompt.trim())
                put("solution_markdown", draft.solution.trim())
                put("wrong_step_markdown", draft.firstAttempt.trim())
                put("key_hint_markdown", draft.keyHint.trim())
                if (draft.errorReason == null) putNull("default_error_reason")
                else put("default_error_reason", draft.errorReason)
            })
            prepared.forEachIndexed { index, item ->
                insertWithOnConflict("media", null, ContentValues().apply {
                    put("id", uuidV7())
                    put("sha256", item.sha256)
                    put("mime_type", item.mime)
                    put("byte_count", item.byteCount)
                    put("relative_path", "media/${item.file.name}")
                    put("created_at", now)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                val storedMediaId = rawQuery(
                    "SELECT id FROM media WHERE sha256 = ?", arrayOf(item.sha256),
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getString(0)
                }
                insertWithOnConflict("math_problem_media", null, ContentValues().apply {
                    put("math_problem_id", problemId)
                    put("media_id", storedMediaId)
                    put("role", "prompt")
                    put("sort_order", index)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            enqueueSync("studyItem", problemId, "update", JSONObject().apply {
                put("media", JSONArray(prepared.map { item -> JSONObject().apply {
                    put("sha256", item.sha256); put("byteCount", item.byteCount); put("mimeType", item.mime)
                } }))
            }, now)
            replaceTagsInTransaction(problemId, draft.tags, now)
        }
        return problemId
    }

    private fun validateMemoryDraft(draft: MemoryCardDraft) {
        require(draft.prompt.isNotBlank()) { "回忆问题不能为空" }
        require(draft.templateType in MEMORY_TEMPLATES) { "不支持的卡片模板" }
        require(draft.archetype in MEMORY_ARCHETYPES) { "不支持的知识形式" }
        require(draft.subject in MEMORY_SUBJECTS) { "请选择 408 科目" }
        require(draft.sourceType in SOURCE_TYPES) { "不支持的来源类型" }
        require(draft.sourceYear == null || draft.sourceYear in 1900..2200) { "来源年份无效" }
        requireValidPayload(draft.structuredPayload)
        when (draft.templateType) {
            "qa", "comparison" -> require(draft.answer.isNotBlank()) { "核心答案不能为空" }
            "cloze" -> require(Regex("\\{\\{c\\d+::.+?}}").containsMatchIn(draft.prompt)) {
                "填空题干缺少 {{c1::答案}} 标记"
            }
            "layered_hint" -> require(draft.answer.isNotBlank() && draft.hints.isNotEmpty()) {
                "分层提示卡需要核心答案和至少一层提示"
            }
            "enumeration" -> require(draft.answerPoints.size >= 2) { "枚举卡至少需要两个评分要点" }
        }
        // The first save is intentionally lightweight. Optional scoring points,
        // conditions, and transfer notes can be added as the card matures.
    }

    private fun validateMathDraft(draft: MathErrorDraft) {
        require(draft.sourceType in SOURCE_TYPES) { "不支持的来源类型" }
        require(draft.sourceYear == null || draft.sourceYear in 1900..2200) { "来源年份无效" }
        require(draft.errorReason == null || draft.errorReason in MATH_ERROR_REASONS) { "错因类型无效" }
        require(draft.targetSeconds == null || draft.targetSeconds in 10..7200) { "目标用时应在 10 秒到 120 分钟之间" }
    }

    private fun requireValidPayload(payload: String) {
        val value = payload.trim().ifEmpty { "{}" }
        require(runCatching {
            if (value.startsWith("[")) JSONArray(value) else JSONObject(value)
        }.isSuccess) { "结构化字段格式无效" }
    }

    private fun profileValues(id: String, draft: MemoryCardDraft, now: Long) = ContentValues().apply {
        put("study_item_id", id); put("archetype", draft.archetype)
        put("knowledge_point", draft.knowledgePoint.trim()); put("source_type", draft.sourceType)
        put("source_title", draft.sourceTitle.trim()); put("source_chapter", draft.sourceChapter.trim())
        put("source_locator", draft.sourceLocator.trim())
        if (draft.sourceYear == null) putNull("source_year") else put("source_year", draft.sourceYear)
        put("mechanism_markdown", draft.mechanism.trim()); put("conditions_markdown", draft.conditions.trim())
        put("contrast_markdown", draft.contrast.trim()); put("example_markdown", draft.example.trim())
        put("common_trap_markdown", draft.commonTrap.trim())
        put("transfer_prompt_markdown", draft.transferPrompt.trim()); put("mnemonic", draft.mnemonic.trim())
        put("structured_payload_json", draft.structuredPayload.trim().ifEmpty { "{}" })
        put("created_at", now); put("updated_at", now)
    }

    private fun profileValues(id: String, draft: MathErrorDraft, now: Long) = ContentValues().apply {
        put("study_item_id", id); put("archetype", "math_error")
        put("knowledge_point", draft.knowledgePoint.trim()); put("source_type", draft.sourceType)
        put("source_title", draft.sourceTitle.trim()); put("source_chapter", draft.sourceChapter.trim())
        put("source_locator", draft.sourceLocator.trim())
        if (draft.sourceYear == null) putNull("source_year") else put("source_year", draft.sourceYear)
        put("first_attempt_markdown", draft.firstAttempt.trim())
        put("error_trigger_markdown", draft.errorTrigger.trim())
        put("general_method_markdown", draft.generalMethod.trim())
        put("verification_markdown", draft.verification.trim())
        put("transfer_prompt_markdown", draft.transferPrompt.trim())
        if (draft.targetSeconds == null) putNull("target_seconds") else put("target_seconds", draft.targetSeconds)
        put("created_at", now); put("updated_at", now)
    }

    fun updateMathDetails(
        id: String,
        solution: String,
        wrongStep: String,
        keyHint: String,
        errorReason: String?,
    ) {
        val now = Instant.now().epochSecond
        writableDatabase.inTransaction {
            val detailUpdated = update("math_problem", ContentValues().apply {
                put("solution_markdown", solution.trim())
                put("wrong_step_markdown", wrongStep.trim())
                put("key_hint_markdown", keyHint.trim())
                if (errorReason == null) putNull("default_error_reason") else put("default_error_reason", errorReason)
            }, "study_item_id = ?", arrayOf(id))
            require(detailUpdated == 1) { "数学题不存在" }
            update("study_item", ContentValues().apply { put("updated_at", now) }, "id = ?", arrayOf(id))
        }
    }

    fun updateMemoryCard(id: String, prompt: String, answer: String) {
        require(prompt.isNotBlank()) { "题干不能为空" }
        val now = Instant.now().epochSecond
        writableDatabase.inTransaction {
            val detailUpdated = update("memory_card", ContentValues().apply {
                put("prompt_markdown", prompt.trim())
                put("answer_markdown", answer.trim())
            }, "study_item_id = ?", arrayOf(id))
            require(detailUpdated == 1) { "记忆卡不存在" }
            update("study_item", ContentValues().apply { put("updated_at", now) }, "id = ?", arrayOf(id))
        }
    }

    fun review(
        row: StudyRow,
        rating: Int,
        reviewedAt: Long,
        durationSeconds: Int,
        mathAttemptResult: String? = null,
        errorReason: String? = null,
        hintRevealed: Boolean = false,
        hintLevel: Int = if (hintRevealed) 1 else 0,
        pointHits: Int? = null,
        pointCount: Int? = null,
        confidence: Int = 3,
        reflection: String = "",
        answerRevealedBeforeCommit: Boolean = false,
    ): NativeScheduleResult {
        require(confidence in 1..5) { "信心等级应为 1–5" }
        require(hintLevel in 0..9) { "提示层级无效" }
        require((pointHits == null && pointCount == null) ||
            (pointHits != null && pointCount != null && pointCount > 0 && pointHits in 0..pointCount)) {
            "要点评估无效"
        }
        val preferences = learningPreferences()
        val memoryPreset = listOf("time_saving", "balanced", "reinforced")
            .indexOf(preferences.memoryPreset).coerceAtLeast(0)
        val mathIntensity = listOf("intensive", "balanced", "relaxed")
            .indexOf(preferences.mathIntensity).coerceAtLeast(0)
        var mathNative: cn.reviewfault.app.core.NativeMathScheduleResult? = null
        var mathMasteryBefore = 0
        var mathStreakBefore = 0
        var algorithmVersion = 2
        var parameterVersion = 1
        var decisionFlags = 0
        var targetRetention = listOf(0.85, 0.90, 0.93)[memoryPreset]
        var personalized = false
        var learningStep = false
        var overdueDays = 0.0
        val durationQuality = when {
            durationSeconds < 5 -> 2
            durationSeconds > 3600 -> 3
            else -> 1
        }
        val recentFailures = if (preferences.schedulerGeneration == 3) {
            readableDatabase.rawQuery(
                """SELECT COUNT(*) FROM (
                    SELECT feedback FROM (
                      SELECT feedback, reviewed_at FROM review_event_v3 WHERE study_item_id = ?
                      UNION ALL
                      SELECT feedback, reviewed_at FROM review_event_v2 WHERE study_item_id = ?
                    ) ORDER BY reviewed_at DESC LIMIT 4
                  ) WHERE feedback <= ?""",
                arrayOf(row.id, row.id, "1"),
            ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        } else 0
        val result = if (row.kind == "memory_card") {
            if (preferences.schedulerGeneration == 3) {
                val calibrationRows = readableDatabase.rawQuery(
                    """SELECT e.feedback, m.retrievability_before
                       FROM review_event_v2 e JOIN memory_review_event_v2 m ON m.review_event_id = e.id
                       UNION ALL
                       SELECT e.feedback, m.retrievability_before
                       FROM review_event_v3 e JOIN memory_review_event_v3 m ON m.review_event_id = e.id""",
                    null,
                ).use { cursor -> buildList {
                    while (cursor.moveToNext()) add((if (cursor.getInt(0) > 1) 1.0 else 0.0) to cursor.getDouble(1))
                } }
                val residual = if (calibrationRows.isEmpty()) 0.0 else calibrationRows
                    .sumOf { (observed, predicted) -> observed - predicted } / calibrationRows.size
                val baselineError = calibrationRows.sumOf { (observed, predicted) ->
                    (predicted - observed) * (predicted - observed)
                }
                val calibratedError = calibrationRows.sumOf { (observed, predicted) ->
                    val adjusted = (predicted + residual).coerceIn(0.0, 1.0)
                    (adjusted - observed) * (adjusted - observed)
                }
                val calibrationImprovement = if (calibrationRows.isEmpty()) 0.0
                    else (baselineError - calibratedError) / calibrationRows.size
                val native = NativeScheduler.nativeReviewMemoryV3(
                    row.state, row.difficulty, row.stabilityDays, row.dueAt,
                    row.lastReviewedAt, row.repetitions, row.lapses,
                    rating, reviewedAt, memoryPreset, calibrationRows.size,
                    calibrationImprovement, recentFailures,
                )
                algorithmVersion = native.algorithmVersion
                parameterVersion = native.parameterVersion
                decisionFlags = native.decisionFlags
                targetRetention = native.targetRetention
                personalized = native.personalized
                learningStep = native.learningStep
                overdueDays = native.overdueDays
                NativeScheduleResult(
                    native.state, native.difficulty, native.stabilityDays, native.dueAt,
                    native.repetitions, native.lapses, native.scheduledDays,
                    native.retrievabilityBefore,
                )
            } else NativeScheduler.nativeReviewMemoryV2(
                row.state, row.difficulty, row.stabilityDays, row.dueAt,
                row.lastReviewedAt, row.repetitions, row.lapses,
                rating, reviewedAt, memoryPreset,
            )
        } else {
            val mathRepetitions = readableDatabase.rawQuery(
                """SELECT m.mastery_level, m.fluent_streak, s.due_at,
                    s.last_reviewed_at, s.repetitions
                   FROM math_schedule_state m JOIN schedule_state_v2 s USING (study_item_id)
                   WHERE m.study_item_id = ?""", arrayOf(row.id),
            ).use { cursor ->
                check(cursor.moveToFirst())
                mathMasteryBefore = cursor.getInt(0)
                mathStreakBefore = cursor.getInt(1)
                cursor.getInt(4)
            }
            val feedback = when (mathAttemptResult) {
                "again" -> 0; "wrong" -> 1; "effortful" -> 2; "fluent" -> 3
                else -> error("数学评分缺失")
            }
            val reason = when (errorReason) {
                null -> 0; "concept" -> 1; "approach" -> 2; "calculation" -> 3
                "misread" -> 4; "forgotten_fact" -> 5; "timeout" -> 6; else -> 7
            }
            val math = if (preferences.schedulerGeneration == 3) {
                NativeScheduler.nativeReviewMathV3(
                    mathMasteryBefore, mathStreakBefore, row.dueAt, row.lastReviewedAt,
                    mathRepetitions, feedback, reason, hintRevealed, reviewedAt,
                    mathIntensity, durationSeconds.coerceAtLeast(0), durationQuality,
                    recentFailures,
                ).also {
                    algorithmVersion = it.algorithmVersion
                    parameterVersion = it.parameterVersion
                    decisionFlags = it.decisionFlags
                }.let {
                    cn.reviewfault.app.core.NativeMathScheduleResult(
                        it.masteryLevel, it.fluentStreak, it.dueAt, it.repetitions,
                        it.scheduledDays, it.appliedFeedback,
                    )
                }
            } else NativeScheduler.nativeReviewMathV2(
                mathMasteryBefore, mathStreakBefore, row.dueAt, row.lastReviewedAt,
                mathRepetitions, feedback, reason, hintRevealed, reviewedAt, mathIntensity,
            )
            mathNative = math
            NativeScheduleResult(
                2, (math.masteryLevel + 1).toDouble(), math.scheduledDays,
                math.dueAt, math.repetitions, row.lapses + if (feedback <= 1) 1 else 0,
                math.scheduledDays, 0.0,
            )
        }
        val elapsedDays = if (row.lastReviewedAt == 0L) 0.0
        else (reviewedAt - row.lastReviewedAt).toDouble() / 86_400.0
        val now = Instant.now().epochSecond

        writableDatabase.inTransaction {
            val updated = update("study_item", ContentValues().apply {
                put("scheduler_state", result.state)
                put("difficulty", result.difficulty)
                put("stability_days", result.stabilityDays)
                put("due_at", result.dueAt)
                put("last_reviewed_at", reviewedAt)
                put("repetitions", result.repetitions)
                put("lapses", result.lapses)
                put("updated_at", now)
            }, "id = ? AND repetitions = ?", arrayOf(row.id, row.repetitions.toString()))
            check(updated == 1) { "内容已在其他会话中更新，请刷新后重试" }

            execSQL("""UPDATE schedule_state_v2 SET due_at = ?, last_reviewed_at = ?,
                repetitions = ?, needs_history_replay = 0, active_algorithm_version = ?,
                active_parameter_version = ?, updated_at = ?
                WHERE study_item_id = ?""",
                arrayOf<Any>(result.dueAt, reviewedAt, result.repetitions, algorithmVersion,
                    parameterVersion, now, row.id))
            if (row.kind == "memory_card") {
                execSQL("""UPDATE memory_schedule_state SET state = ?, difficulty = ?,
                    stability_days = ?, lapses = ? WHERE study_item_id = ?""",
                    arrayOf<Any>(result.state, result.difficulty, result.stabilityDays, result.lapses, row.id))
            } else {
                execSQL("""UPDATE math_schedule_state SET mastery_level = ?, fluent_streak = ?
                    WHERE study_item_id = ?""",
                    arrayOf<Any>(mathNative!!.masteryLevel, mathNative!!.fluentStreak, row.id))
            }

            val eventId = uuidV7()
            val algorithm = if (row.kind == "memory_card") "memory_fsrs_6" else "math_mastery_ladder"
            val preference = if (row.kind == "memory_card") preferences.memoryPreset else preferences.mathIntensity
            val appliedFeedback = if (row.kind == "memory_card") rating else mathNative!!.appliedFeedback
            if (algorithmVersion == 3) {
                val checksum = parameterChecksum(algorithm, parameterVersion)
                val qualityLabel = listOf("unknown", "reliable", "too_short", "interrupted")[durationQuality]
                val timezoneOffset = ZoneId.systemDefault().rules
                    .getOffset(Instant.ofEpochSecond(reviewedAt)).totalSeconds / 60
                val snapshot = JSONObject().apply {
                    put("scheduledDays", result.scheduledDays)
                    put("retrievabilityBefore", result.retrievabilityBefore)
                    put("decisionFlags", decisionFlags)
                    put("schedulerGeneration", preferences.schedulerGeneration)
                }.toString()
                insertOrThrow("review_event_v3", null, ContentValues().apply {
                    put("id", eventId); put("study_item_id", row.id); put("algorithm", algorithm)
                    put("algorithm_version", algorithmVersion); put("parameter_version", parameterVersion)
                    put("parameter_checksum", checksum); put("preference", preference)
                    put("feedback", appliedFeedback); put("reviewed_at", reviewedAt)
                    put("duration_seconds", durationSeconds.coerceAtLeast(0))
                    put("duration_quality", qualityLabel)
                    put("client_timezone_offset_minutes", timezoneOffset)
                    put("due_at_before", row.dueAt); put("due_at_after", result.dueAt)
                    put("decision_flags", decisionFlags); put("decision_snapshot_json", snapshot)
                    put("device_id", deviceId); put("created_at", now)
                })
            } else {
                insertOrThrow("review_event_v2", null, ContentValues().apply {
                    put("id", eventId); put("study_item_id", row.id); put("algorithm", algorithm)
                    put("algorithm_version", 2); put("parameter_version", 1)
                    put("preference", preference); put("feedback", appliedFeedback)
                    put("reviewed_at", reviewedAt); put("duration_seconds", durationSeconds.coerceAtLeast(0))
                    put("due_at_before", row.dueAt); put("due_at_after", result.dueAt)
                    put("device_id", deviceId); put("created_at", now)
                })
            }

            if (row.kind == "memory_card") {
                insertOrThrow(if (algorithmVersion == 3) "memory_review_event_v3" else "memory_review_event_v2",
                    null, ContentValues().apply {
                    put("review_event_id", eventId); put("state_before", row.state)
                    put("state_after", result.state)
                    put("target_retention", targetRetention)
                    put("elapsed_days", elapsedDays); put("scheduled_days", result.scheduledDays)
                    put("retrievability_before", result.retrievabilityBefore)
                    put("difficulty_before", row.difficulty); put("difficulty_after", result.difficulty)
                    put("stability_before", row.stabilityDays); put("stability_after", result.stabilityDays)
                    if (algorithmVersion == 3) {
                        put("personalized", personalized.toInt())
                        put("learning_step", learningStep.toInt())
                        put("overdue_days", overdueDays)
                    }
                })
            } else if (mathAttemptResult != null) {
                val attemptId = uuidV7()
                insertOrThrow("attempt", null, ContentValues().apply {
                    put("id", attemptId)
                    put("math_problem_id", row.id)
                    put("started_at", reviewedAt - durationSeconds.coerceAtLeast(0))
                    put("finished_at", reviewedAt)
                    put("result", mathAttemptResult)
                    if (errorReason == null) putNull("error_reason") else put("error_reason", errorReason)
                    put("created_at", now)
                })
                insertOrThrow(if (algorithmVersion == 3) "math_review_event_v3" else "math_review_event_v2",
                    null, ContentValues().apply {
                    val requested = when (mathAttemptResult) {
                        "again" -> 0; "wrong" -> 1; "effortful" -> 2; else -> 3
                    }
                    put("review_event_id", eventId); put("attempt_id", attemptId)
                    put("requested_feedback", requested); put("applied_feedback", mathNative!!.appliedFeedback)
                    if (errorReason == null) putNull("error_reason") else put("error_reason", errorReason)
                    put("hint_revealed", hintRevealed.toInt())
                    put("mastery_before", mathMasteryBefore)
                    put("mastery_after", mathNative!!.masteryLevel)
                    put("fluent_streak_before", mathStreakBefore)
                    put("fluent_streak_after", mathNative!!.fluentStreak)
                    if (algorithmVersion == 3) put("consecutive_failures", recentFailures)
                    put("scheduled_days", mathNative!!.scheduledDays)
                })
            }
            val deviceCounter = rawQuery(
                "SELECT next_counter FROM local_device WHERE singleton = 1", null,
            ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
            execSQL("UPDATE local_device SET next_counter = next_counter + 1 WHERE singleton = 1")
            insertOrThrow("review_action_v4", null, ContentValues().apply {
                put("action_id", eventId); put("study_item_id", row.id); put("algorithm", algorithm)
                put("feedback", appliedFeedback); put("reviewed_at", reviewedAt)
                put("duration_seconds", durationSeconds.coerceAtLeast(0))
                if (errorReason == null) putNull("error_reason") else put("error_reason", errorReason)
                put("hint_revealed", hintRevealed.toInt()); put("device_id", deviceId)
                put("device_counter", deviceCounter); put("causal_cursor", 0)
                put("source_generation", 4); put("created_at", now)
            })
            val operationId = UUID.randomUUID().toString()
            val changedFields = JSONObject().apply {
                put("studyItemId", row.id); put("algorithm", algorithm)
                put("feedback", appliedFeedback); put("reviewedAt", reviewedAt)
                put("durationSeconds", durationSeconds.coerceAtLeast(0))
                put("errorReason", errorReason ?: JSONObject.NULL)
                put("hintRevealed", hintRevealed)
            }.toString()
            insertOrThrow("sync_outbox", null, ContentValues().apply {
                put("operation_id", operationId); put("device_id", deviceId)
                put("device_counter", deviceCounter); put("base_cursor", 0)
                put("base_revision", 0); put("entity_type", "reviewAction")
                put("entity_id", eventId); put("action", "create")
                put("changed_fields_json", changedFields); put("occurred_at", now)
            })
            execSQL("UPDATE schedule_cache_v4 SET due_at = ?, replayed_action_count = ?, dirty = 0, rebuilt_at = ? WHERE study_item_id = ?",
                arrayOf<Any>(result.dueAt, row.repetitions + 1, now, row.id))
            appendLearningEvidenceV5(
                row = row, reviewedAt = reviewedAt, durationSeconds = durationSeconds,
                rating = appliedFeedback, mathAttemptResult = mathAttemptResult,
                errorReason = errorReason, hintLevel = hintLevel,
                pointHits = pointHits, pointCount = pointCount, confidence = confidence,
                reflection = reflection, answerRevealedBeforeCommit = answerRevealedBeforeCommit,
                dueAt = result.dueAt, now = now,
            )
        }
        return result
    }

    private fun SQLiteDatabase.appendLearningEvidenceV5(
        row: StudyRow,
        reviewedAt: Long,
        durationSeconds: Int,
        rating: Int,
        mathAttemptResult: String?,
        errorReason: String?,
        hintLevel: Int,
        pointHits: Int?,
        pointCount: Int?,
        confidence: Int,
        reflection: String,
        answerRevealedBeforeCommit: Boolean,
        dueAt: Long,
        now: Long,
    ) {
        val task = rawQuery(
            """SELECT id, task_type, repetitions, consecutive_failures FROM learning_task_v5
               WHERE source_study_item_id = ? AND task_state IN ('active', 'legacy')
                 AND dependency_ready = 1
               ORDER BY CASE task_type WHEN 'math_repair' THEN 0 ELSE 1 END, due_at, id LIMIT 1""",
            arrayOf(row.id),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else EvidenceTaskTarget(
                cursor.getString(0), cursor.getString(1), cursor.getInt(2), cursor.getInt(3),
            )
        } ?: return
        val correct = if (row.kind == "memory_card") rating >= 3
            else mathAttemptResult == "effortful" || mathAttemptResult == "fluent"
        val errorMask = when (errorReason) {
            "concept" -> 1; "approach" -> 2; "calculation" -> 4; "misread" -> 8
            "forgotten_fact" -> 16; "timeout" -> 32; "other" -> 64; else -> 0
        }
        val evidenceCounter = rawQuery(
            "SELECT next_counter FROM local_device WHERE singleton = 1", null,
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
        update("learning_task_v5", ContentValues().apply {
            put("task_state", "active"); put("due_at", dueAt); put("last_reviewed_at", reviewedAt)
            put("repetitions", task.repetitions + 1)
            put("consecutive_failures", if (correct) 0 else task.consecutiveFailures + 1)
            put("updated_at", now)
        }, "id = ?", arrayOf(task.id))
        insertOrThrow("learning_evidence_v5", null, ContentValues().apply {
            put("evidence_id", uuidV7()); put("learning_task_id", task.id); put("task_type", task.type)
            put("reviewed_at", reviewedAt); put("correct", correct.toInt()); put("error_mask", errorMask)
            if (pointHits == null) putNull("point_hits") else put("point_hits", pointHits)
            if (pointCount == null) putNull("point_count") else put("point_count", pointCount)
            put("hint_level", hintLevel); put("answer_revealed", answerRevealedBeforeCommit.toInt())
            put("duration_seconds", durationSeconds.coerceAtLeast(0))
            put("duration_reliable", (durationSeconds in 5..3600).toInt())
            put("confidence", confidence); put("reflection_markdown", reflection.trim())
            put("device_id", deviceId); put("device_counter", evidenceCounter); put("causal_cursor", 0)
            put("created_at", now)
        })
    }

    fun exportBackup(output: OutputStream) {
        writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
            if (cursor.moveToFirst() && cursor.getInt(0) != 0) {
                error("数据库仍在写入，请稍后重试")
            }
        }
        val databaseFile = appContext.getDatabasePath("reviewfault.db")
        check(databaseFile.isFile) { "数据库文件不存在" }
        val exportDatabase = File(appContext.cacheDir, "backup-${UUID.randomUUID()}.sqlite")
        databaseFile.copyTo(exportDatabase, overwrite = true)
        SQLiteDatabase.openDatabase(
            exportDatabase.absolutePath, null, SQLiteDatabase.OPEN_READWRITE,
        ).use { sanitized ->
            sanitized.execSQL("DELETE FROM local_device")
            sanitized.execSQL("DELETE FROM sync_cursor")
            sanitized.execSQL("DELETE FROM sync_revision")
            sanitized.execSQL("DELETE FROM sync_outbox")
            sanitized.execSQL("DELETE FROM sync_conflict")
            sanitized.execSQL("DELETE FROM local_ink_draft")
            sanitized.execSQL("VACUUM")
        }
        val mediaDirectory = File(appContext.filesDir, "media")
        val files = buildList {
            add("database.sqlite" to exportDatabase)
            if (mediaDirectory.exists()) {
                mediaDirectory.walkTopDown().filter(File::isFile).forEach { file ->
                    add("media/${file.relativeTo(mediaDirectory).invariantSeparatorsPath}" to file)
                }
            }
        }
        val manifestFiles = JSONArray()
        files.forEach { (path, file) ->
            manifestFiles.put(JSONObject().apply {
                put("path", path)
                put("sha256", sha256(file))
                put("bytes", file.length())
            })
        }
        val manifest = JSONObject().apply {
            put("format", "reviewfault-backup")
            put("version", 5)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("schemaVersion", 5)
            put("schedulerAbiVersion", 5)
            put("exportedAt", Instant.now().epochSecond)
            put("excludedTables", JSONArray(listOf(
                "local_device", "sync_cursor", "sync_revision", "sync_outbox",
                "sync_conflict", "local_ink_draft",
            )))
            put("files", manifestFiles)
        }.toString(2)
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            files.forEach { (path, file) ->
                zip.putNextEntry(ZipEntry(path))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        exportDatabase.delete()
    }

    fun restoreBackup(input: InputStream) {
        val restoreRoot = File(appContext.cacheDir, "restore-${UUID.randomUUID()}")
        check(restoreRoot.mkdirs()) { "无法创建恢复临时目录" }
        try {
            val extractedFiles = mutableSetOf<String>()
            var extractedBytes = 0L
            var entryCount = 0
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    require(entryCount <= MAX_BACKUP_ENTRIES) { "备份文件数量过多，已拒绝恢复" }
                    val entryName = entry.name.replace('\\', '/')
                    require(entryName == "manifest.json" || entryName == "database.sqlite" ||
                        entryName.startsWith("media/")) { "备份包含未知文件：$entryName" }
                    val destination = File(restoreRoot, entryName)
                    val safeRoot = restoreRoot.canonicalPath + File.separator
                    require(destination.canonicalPath.startsWith(safeRoot)) { "备份包含非法路径" }
                    if (entry.isDirectory) {
                        destination.mkdirs()
                    } else {
                        require(extractedFiles.add(entryName)) { "备份包含重复文件：$entryName" }
                        destination.parentFile?.mkdirs()
                        destination.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                extractedBytes += count
                                require(extractedBytes <= MAX_BACKUP_BYTES) {
                                    "备份解压后超过 2 GiB，已拒绝恢复"
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val manifestFile = File(restoreRoot, "manifest.json")
            val manifest = JSONObject(manifestFile.readText())
            val backupVersion = manifest.getInt("version")
            val schemaVersion = manifest.getInt("schemaVersion")
            val abiVersion = manifest.getInt("schedulerAbiVersion")
            require(manifest.getString("format") == "reviewfault-backup" &&
                ((backupVersion == 1 && schemaVersion == 1 && abiVersion == 1) ||
                    (backupVersion == 2 && schemaVersion == 2 && abiVersion == 2) ||
                    (backupVersion == 3 && schemaVersion == 3 && abiVersion == 3) ||
                    (backupVersion == 4 && schemaVersion == 4 && abiVersion == 4) ||
                    (backupVersion == 5 && schemaVersion == 5 && abiVersion == 5))
            ) { "不是受支持的 ReviewFault 备份" }
            val listed = manifest.getJSONArray("files")
            val listedFiles = mutableSetOf<String>()
            var listedBytes = 0L
            for (index in 0 until listed.length()) {
                val item = listed.getJSONObject(index)
                val relative = item.getString("path")
                require(relative == "database.sqlite" || relative.startsWith("media/")) {
                    "备份清单包含非法路径"
                }
                require(listedFiles.add(relative)) { "备份清单包含重复文件：$relative" }
                val bytes = item.getLong("bytes")
                require(bytes >= 0) { "备份清单包含非法文件大小" }
                listedBytes += bytes
                require(listedBytes <= MAX_BACKUP_BYTES) { "备份清单超过 2 GiB，已拒绝恢复" }
                val file = File(restoreRoot, relative)
                val safeRoot = restoreRoot.canonicalPath + File.separator
                require(file.canonicalPath.startsWith(safeRoot) && file.isFile &&
                    file.length() == bytes &&
                    sha256(file) == item.getString("sha256")
                ) { "备份文件校验失败：$relative" }
            }
            require(extractedFiles == listedFiles + "manifest.json") {
                "备份包含未在清单中声明的文件"
            }
            val restoredDatabase = File(restoreRoot, "database.sqlite")
            require(restoredDatabase.isFile) { "备份缺少数据库" }
            SQLiteDatabase.openDatabase(
                restoredDatabase.absolutePath, null, SQLiteDatabase.OPEN_READWRITE,
            ).use { checkDb ->
                if (checkDb.version == 1) {
                    checkDb.setForeignKeyConstraintsEnabled(true)
                    applyMigration(checkDb, "002_v0_2.sql")
                    checkDb.version = 2
                }
                if (checkDb.version == 2) {
                    applyMigration(checkDb, "003_v0_3.sql")
                    checkDb.version = 3
                }
                if (checkDb.version == 3) {
                    applyMigration(checkDb, "004_v0_4.sql")
                    checkDb.version = 4
                }
                if (checkDb.version == 4) {
                    applyMigration(checkDb, "005_v0_5.sql")
                    checkDb.version = 5
                }
                require(checkDb.version == 5) { "备份数据库版本不兼容" }
                checkDb.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    require(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                        "备份数据库完整性检查失败"
                    }
                }
                checkDb.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                    require(!cursor.moveToFirst()) { "备份数据库存在无效引用" }
                }
            }
            replaceCurrentData(restoreRoot)
        } finally {
            restoreRoot.deleteRecursively()
        }
    }

    private fun replaceCurrentData(restoreRoot: File) {
        close()
        val databaseFile = appContext.getDatabasePath("reviewfault.db")
        val mediaDirectory = File(appContext.filesDir, "media")
        val rollbackRoot = File(appContext.cacheDir, "rollback-${UUID.randomUUID()}")
        rollbackRoot.mkdirs()
        val oldDatabase = File(rollbackRoot, "database.sqlite")
        val oldMedia = File(rollbackRoot, "media")
        try {
            if (databaseFile.exists()) databaseFile.copyTo(oldDatabase, overwrite = true)
            if (mediaDirectory.exists()) mediaDirectory.copyRecursively(oldMedia, overwrite = true)
            File(databaseFile.path + "-wal").delete()
            File(databaseFile.path + "-shm").delete()
            File(restoreRoot, "database.sqlite").copyTo(databaseFile, overwrite = true)
            mediaDirectory.deleteRecursively()
            val restoredMedia = File(restoreRoot, "media")
            if (restoredMedia.exists()) restoredMedia.copyRecursively(mediaDirectory, overwrite = true)
            readableDatabase.rawQuery("SELECT schema_version FROM schema_metadata", null).use {
                require(it.moveToFirst() && it.getInt(0) == 5) { "恢复后的数据库无法打开" }
            }
        } catch (error: Exception) {
            close()
            if (oldDatabase.exists()) oldDatabase.copyTo(databaseFile, overwrite = true)
            mediaDirectory.deleteRecursively()
            if (oldMedia.exists()) oldMedia.copyRecursively(mediaDirectory, overwrite = true)
            throw error
        } finally {
            rollbackRoot.deleteRecursively()
        }
    }

    private fun Cursor.toStudyRow() = StudyRow(
        id = getString(0),
        kind = getString(1),
        subject = getString(2),
        state = getInt(3),
        difficulty = getDouble(4),
        stabilityDays = getDouble(5),
        dueAt = getLong(6),
        lastReviewedAt = getLong(7),
        repetitions = getInt(8),
        lapses = getInt(9),
        prompt = getString(10),
        answer = getString(11),
        mediaPath = if (isNull(12)) null else getString(12),
        templateType = getString(13),
        hints = jsonStringList(getString(14)),
        answerPoints = jsonStringList(getString(15)),
        profile = CardProfile(
            archetype = getString(16), knowledgePoint = getString(17), sourceType = getString(18),
            sourceTitle = getString(19), sourceChapter = getString(20), sourceLocator = getString(21),
            sourceYear = if (isNull(22)) null else getInt(22), mechanism = getString(23),
            conditions = getString(24), contrast = getString(25), example = getString(26),
            commonTrap = getString(27), transferPrompt = getString(28), mnemonic = getString(29),
            firstAttempt = getString(30), errorTrigger = getString(31), generalMethod = getString(32),
            verification = getString(33), targetSeconds = if (isNull(34)) null else getInt(34),
            structuredPayload = getString(35),
        ),
        tags = jsonStringList(getString(36)),
    )

    private fun jsonStringList(value: String): List<String> = try {
        val array = JSONArray(value)
        buildList { for (index in 0 until array.length()) add(array.getString(index)) }
    } catch (_: Exception) { emptyList() }

    companion object {
        private val STUDY_ROW_COLUMNS = """
            s.id, s.kind, s.subject, s.scheduler_state, s.difficulty,
            s.stability_days, s.due_at, s.last_reviewed_at, s.repetitions, s.lapses,
            COALESCE(m.prompt_markdown, p.prompt_markdown, ''),
            COALESCE(m.answer_markdown, p.solution_markdown, ''),
            media.relative_path, COALESCE(m.template_type, ''),
            CASE WHEN s.kind = 'math_problem' AND COALESCE(p.key_hint_markdown, '') <> ''
                 THEN json_array(p.key_hint_markdown) ELSE COALESCE(m.hints_json, '[]') END,
            COALESCE(m.answer_points_json, '[]'),
            COALESCE(cp.archetype, CASE WHEN s.kind = 'math_problem' THEN 'math_error' ELSE 'qa' END),
            COALESCE(cp.knowledge_point, ''), COALESCE(cp.source_type, 'notes'),
            COALESCE(NULLIF(cp.source_title, ''), p.source_name, ''),
            COALESCE(cp.source_chapter, ''), COALESCE(cp.source_locator, ''), cp.source_year,
            COALESCE(cp.mechanism_markdown, ''), COALESCE(cp.conditions_markdown, ''),
            COALESCE(cp.contrast_markdown, ''), COALESCE(cp.example_markdown, ''),
            COALESCE(cp.common_trap_markdown, ''), COALESCE(cp.transfer_prompt_markdown, ''),
            COALESCE(cp.mnemonic, ''),
            COALESCE(NULLIF(cp.first_attempt_markdown, ''), p.wrong_step_markdown, ''),
            COALESCE(cp.error_trigger_markdown, ''), COALESCE(cp.general_method_markdown, ''),
            COALESCE(cp.verification_markdown, ''), cp.target_seconds,
            COALESCE(cp.structured_payload_json, '{}'),
            COALESCE((SELECT json_group_array(tag_name) FROM (
              SELECT t.name AS tag_name FROM study_item_tag sit JOIN tag t ON t.id = sit.tag_id
              WHERE sit.study_item_id = s.id AND t.deleted_at IS NULL ORDER BY t.name COLLATE NOCASE
            )), '[]')
        """.trimIndent()
        private const val MAX_BACKUP_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_BACKUP_ENTRIES = 10_000
        private val MEMORY_TEMPLATES = setOf(
            "qa", "cloze", "layered_hint", "enumeration", "image_occlusion", "comparison",
        )
        private val MEMORY_ARCHETYPES = setOf(
            "concept", "comparison", "process", "enumeration", "scale_mapping",
            "formula_rule", "diagram", "cloze", "qa",
        )
        private val MEMORY_SUBJECTS = setOf(
            "data_structures", "computer_organization", "operating_systems", "computer_networks",
        )
        private val SOURCE_TYPES = setOf("textbook", "course", "past_exam", "practice", "notes", "other")
        private val MATH_ERROR_REASONS = setOf(
            "concept", "approach", "calculation", "misread", "forgotten_fact", "timeout", "other",
        )

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: AppDatabase(context).also { instance = it }
        }

        private fun migrationStatements(script: String): List<String> {
            val statements = mutableListOf<String>()
            val current = StringBuilder()
            var inTrigger = false
            script.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("--") ||
                    line.startsWith("PRAGMA foreign_keys", ignoreCase = true) ||
                    line.startsWith("PRAGMA user_version", ignoreCase = true) ||
                    line.equals("BEGIN IMMEDIATE;", ignoreCase = true) ||
                    line.equals("COMMIT;", ignoreCase = true)
                ) return@forEach
                if (line.startsWith("CREATE TRIGGER", ignoreCase = true)) inTrigger = true
                current.append(rawLine).append('\n')
                val complete = if (inTrigger) line.endsWith("END;", ignoreCase = true)
                    else line.endsWith(';')
                if (complete) {
                    statements += current.toString().trim().removeSuffix(";")
                    current.clear()
                    inTrigger = false
                }
            }
            check(current.isBlank()) { "数据库迁移脚本不完整" }
            return statements
        }

        private fun jsonArray(values: List<String>): String = values.joinToString(
            prefix = "[", postfix = "]",
        ) { value ->
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + "\""
        }

        private fun parameterChecksum(algorithm: String, parameterVersion: Int): String = when {
            algorithm == "memory_fsrs_6" && parameterVersion == 2 ->
                "bd98e3fdf07a9223a39b5305fe5c14e8d9a03013ddbbce3f5d9ea15555c9c177"
            algorithm == "memory_fsrs_6" && parameterVersion == 3 ->
                "083f217e835490d1760ee5bfc94693b1b4fb827e3ed121cbd970f401d6271019"
            algorithm == "math_mastery_ladder" && parameterVersion == 2 ->
                "229003e5c13709bb8af1443b1d4585a025dc92db742520a82740d01a4fe9c089"
            else -> error("未知的调度参数版本")
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun uuidV7(): String {
            val bytes = ByteArray(16)
            val timestamp = System.currentTimeMillis()
            for (index in 0 until 6) {
                bytes[5 - index] = (timestamp ushr (index * 8)).toByte()
            }
            val random = ByteArray(10).also(SecureRandom()::nextBytes)
            bytes[6] = (0x70 or (random[0].toInt() and 0x0f)).toByte()
            bytes[7] = random[1]
            bytes[8] = (0x80 or (random[2].toInt() and 0x3f)).toByte()
            random.copyInto(bytes, destinationOffset = 9, startIndex = 3)
            val hex = bytes.joinToString("") { "%02x".format(it) }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20)}"
        }
    }

    private data class PreparedMedia(
        val byteCount: Long,
        val sha256: String,
        val mime: String,
        val file: File,
    )
}

private inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try {
        val result = block()
        setTransactionSuccessful()
        result
    } finally {
        endTransaction()
    }
}

private fun Cursor.intOrZero(index: Int): Int = if (isNull(index)) 0 else getInt(index)
private fun Boolean.toInt(): Int = if (this) 1 else 0
