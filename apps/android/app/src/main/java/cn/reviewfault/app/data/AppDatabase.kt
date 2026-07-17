package cn.reviewfault.app.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import cn.reviewfault.app.BuildConfig
import cn.reviewfault.app.core.NativeScheduleResult
import cn.reviewfault.app.core.NativeScheduler
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.zip.ZipEntry
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
    val structuredJson: String,
)

data class DashboardSummary(
    val overdue: Int,
    val dueToday: Int,
    val newItems: Int,
    val estimatedMinutes: Int,
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

class AppDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, "reviewfault.db", null, 2) {
    // v1 review_log is intentionally read-only and is consulted only by lazy history replay.

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var current = oldVersion
        if (current < 2 && newVersion >= 2) {
            applyMigration(db, "002_v0_2.sql")
            current = 2
        }
        check(current == newVersion) { "缺少数据库迁移：$oldVersion -> $newVersion" }
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
              (SELECT COUNT(*) FROM review_event_v2 e
               JOIN memory_review_event_v2 mr ON mr.review_event_id = e.id
               WHERE mr.state_before = 0 AND e.reviewed_at >= ?)
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
                dayStart.toString()),
        ).use { cursor ->
            cursor.moveToFirst()
            val remainingNewMemory = (cursor.getInt(5) - cursor.getInt(6)).coerceAtLeast(0)
            val newMemory = cursor.intOrZero(2).coerceAtMost(remainingNewMemory)
            val newMath = cursor.intOrZero(3)
            return DashboardSummary(
                cursor.intOrZero(0),
                cursor.intOrZero(1),
                newMemory + newMath,
                (cursor.intOrZero(4) + newMemory * 45 + newMath * 480 + 59) / 60,
            )
        }
    }

    fun nextForReview(now: Long): StudyRow? {
        val dayStart = ZonedDateTime.ofInstant(Instant.ofEpochSecond(now), ZoneId.systemDefault())
            .toLocalDate().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        readableDatabase.rawQuery(
            """
            SELECT s.id, s.kind, s.subject, s.scheduler_state, s.difficulty,
                   s.stability_days, s.due_at, s.last_reviewed_at,
                   s.repetitions, s.lapses,
                   COALESCE(m.prompt_markdown, p.prompt_markdown, ''),
                   COALESCE(m.answer_markdown, p.solution_markdown, ''),
                   media.relative_path, COALESCE(m.template_type, ''),
                   CASE WHEN m.template_type = 'layered_hint' THEN m.hints_json
                        WHEN m.template_type = 'enumeration' THEN m.answer_points_json
                        ELSE '[]' END
            FROM study_item s
            LEFT JOIN memory_card m ON m.study_item_id = s.id
            LEFT JOIN math_problem p ON p.study_item_id = s.id
            LEFT JOIN math_problem_media pm
              ON pm.math_problem_id = s.id AND pm.role = 'prompt' AND pm.sort_order = 0
            LEFT JOIN media ON media.id = pm.media_id
            CROSS JOIN learning_preferences lp
            WHERE s.suspended_at IS NULL AND s.deleted_at IS NULL
              AND lp.singleton = 1
              AND ((s.kind = 'math_problem' AND lp.include_math_problems = 1) OR
                (s.kind = 'memory_card' AND lp.include_memory_cards = 1 AND (
                  (s.subject = 'data_structures' AND lp.enable_data_structures = 1) OR
                  (s.subject = 'computer_organization' AND lp.enable_computer_organization = 1) OR
                  (s.subject = 'operating_systems' AND lp.enable_operating_systems = 1) OR
                  (s.subject = 'computer_networks' AND lp.enable_computer_networks = 1))))
              AND ((s.scheduler_state <> 0 AND s.due_at <= ?)
                OR (s.scheduler_state = 0 AND (s.kind = 'math_problem' OR (
                  SELECT COUNT(*) FROM review_event_v2 e
                  JOIN memory_review_event_v2 mr ON mr.review_event_id = e.id
                  WHERE mr.state_before = 0 AND e.reviewed_at >= ?
                ) < lp.daily_new_memory_limit)))
            ORDER BY
              CASE WHEN s.scheduler_state <> 0 AND s.due_at < ? THEN 0
                   WHEN s.scheduler_state <> 0 THEN 1 ELSE 2 END,
              CASE WHEN s.scheduler_state <> 0 AND s.due_at < ?
                   THEN CASE WHEN s.kind = 'memory_card' THEN 0 ELSE 1 END ELSE 0 END,
              CASE WHEN s.kind = 'memory_card' THEN 0 ELSE 1 END,
              s.due_at,
              s.created_at
            LIMIT 1
            """.trimIndent(),
            arrayOf(now.toString(), dayStart.toString(), dayStart.toString(), dayStart.toString()),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toStudyRow() else null
        }
    }

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
            "OR COALESCE(p.source_name, '') LIKE ? ESCAPE '\\')"
        args += listOf(pattern, pattern, pattern, pattern)
        args += filter.limit.toString()
        args += filter.offset.toString()
        return readableDatabase.rawQuery(
            """
            SELECT s.id, s.kind, s.subject, s.scheduler_state, s.difficulty,
                   s.stability_days, s.due_at, s.last_reviewed_at,
                   s.repetitions, s.lapses,
                   COALESCE(m.prompt_markdown, p.prompt_markdown, ''),
                   COALESCE(m.answer_markdown, p.solution_markdown, ''),
                   media.relative_path, COALESCE(m.template_type, ''),
                   CASE WHEN m.template_type = 'layered_hint' THEN m.hints_json
                        WHEN m.template_type = 'enumeration' THEN m.answer_points_json
                        ELSE '[]' END
            FROM study_item s
            LEFT JOIN memory_card m ON m.study_item_id = s.id
            LEFT JOIN math_problem p ON p.study_item_id = s.id
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
        )
    }

    fun saveLearningPreferences(value: LearningPreferences) {
        require(value.dailyNewMemoryLimit in 0..500 && value.sessionMinutes in 1..240)
        require(value.includeMemoryCards || value.includeMathProblems)
        require(value.memoryPreset in setOf("time_saving", "balanced", "reinforced"))
        require(value.mathIntensity in setOf("intensive", "balanced", "relaxed"))
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
            put("updated_at", Instant.now().epochSecond)
        }, "singleton = 1", null)
    }

    fun softDelete(itemIds: List<String>, now: Long = Instant.now().epochSecond): DeletionState {
        if (itemIds.isEmpty()) return DeletionState(emptyList(), now, now)
        val placeholders = itemIds.joinToString { "?" }
        writableDatabase.execSQL(
            "UPDATE study_item SET deleted_at = ?, updated_at = ? WHERE deleted_at IS NULL AND id IN ($placeholders)",
            arrayOf<Any>(now, now, *itemIds.toTypedArray()),
        )
        return DeletionState(itemIds, now, now + 10)
    }

    fun restore(itemIds: List<String>) {
        if (itemIds.isEmpty()) return
        val now = Instant.now().epochSecond
        val placeholders = itemIds.joinToString { "?" }
        writableDatabase.execSQL(
            "UPDATE study_item SET deleted_at = NULL, updated_at = ? WHERE id IN ($placeholders)",
            arrayOf<Any>(now, *itemIds.toTypedArray()),
        )
    }

    fun replaceTags(itemId: String, names: List<String>) {
        val normalized = names.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() }
        val now = Instant.now().epochSecond
        writableDatabase.inTransaction {
            delete("study_item_tag", "study_item_id = ?", arrayOf(itemId))
            normalized.forEach { name ->
                val existing = rawQuery("SELECT id FROM tag WHERE name = ? COLLATE NOCASE", arrayOf(name))
                    .use { if (it.moveToFirst()) it.getString(0) else null }
                val tagId = existing ?: uuidV7().also { id ->
                    insertOrThrow("tag", null, ContentValues().apply {
                        put("id", id); put("name", name); put("created_at", now); put("updated_at", now)
                    })
                }
                execSQL("UPDATE tag SET deleted_at = NULL, updated_at = ? WHERE id = ?", arrayOf<Any>(now, tagId))
                insertOrThrow("study_item_tag", null, ContentValues().apply {
                    put("study_item_id", itemId); put("tag_id", tagId)
                })
            }
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

    fun createMemoryCard(
        templateType: String,
        prompt: String,
        answer: String,
        hints: List<String> = emptyList(),
        answerPoints: List<String> = emptyList(),
        subject: String = "operating_systems",
    ): String {
        require(prompt.isNotBlank()) { "题干不能为空" }
        require(templateType in MEMORY_TEMPLATES) { "不支持的卡片模板" }
        when (templateType) {
            "qa", "comparison" -> require(answer.isNotBlank()) { "答案不能为空" }
            "cloze" -> require(Regex("\\{\\{c\\d+::.+?}}").containsMatchIn(prompt)) {
                "填空题干缺少结构化标记"
            }
            "layered_hint" -> require(answer.isNotBlank() && hints.isNotEmpty()) {
                "分层提示卡需要答案和提示"
            }
            "enumeration" -> require(answerPoints.size >= 2) { "枚举卡至少需要两个要点" }
        }
        val now = Instant.now().epochSecond
        val id = uuidV7()
        writableDatabase.inTransaction {
            insertOrThrow("study_item", null, ContentValues().apply {
                put("id", id)
                put("kind", "memory_card")
                put("subject", subject)
                put("created_at", now)
                put("updated_at", now)
            })
            insertOrThrow("memory_card", null, ContentValues().apply {
                put("study_item_id", id)
                put("template_type", templateType)
                put("prompt_markdown", prompt.trim())
                put("answer_markdown", answer.trim())
                put("hints_json", jsonArray(hints))
                put("answer_points_json", jsonArray(answerPoints))
            })
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
    ): String {
        require(uris.isNotEmpty() && uris.size <= 5) { "每道题请选择 1–5 张图片" }
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
            insertOrThrow("math_problem", null, ContentValues().apply {
                put("study_item_id", problemId)
                put("source_name", sourceName.trim())
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
        }
        return problemId
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
    ): NativeScheduleResult {
        val preferences = learningPreferences()
        val memoryPreset = listOf("time_saving", "balanced", "reinforced")
            .indexOf(preferences.memoryPreset).coerceAtLeast(0)
        val mathIntensity = listOf("intensive", "balanced", "relaxed")
            .indexOf(preferences.mathIntensity).coerceAtLeast(0)
        var mathNative: cn.reviewfault.app.core.NativeMathScheduleResult? = null
        var mathMasteryBefore = 0
        var mathStreakBefore = 0
        val result = if (row.kind == "memory_card") {
            NativeScheduler.nativeReviewMemoryV2(
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
            mathNative = NativeScheduler.nativeReviewMathV2(
                mathMasteryBefore, mathStreakBefore, row.dueAt, row.lastReviewedAt,
                mathRepetitions, feedback,
                reason, hintRevealed, reviewedAt, mathIntensity,
            )
            val math = mathNative!!
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
                repetitions = ?, needs_history_replay = 0, updated_at = ?
                WHERE study_item_id = ?""",
                arrayOf<Any>(result.dueAt, reviewedAt, result.repetitions, now, row.id))
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
            insertOrThrow("review_event_v2", null, ContentValues().apply {
                put("id", eventId)
                put("study_item_id", row.id)
                put("algorithm", if (row.kind == "memory_card") "memory_fsrs_6" else "math_mastery_ladder")
                put("algorithm_version", 2)
                put("parameter_version", 1)
                put("preference", if (row.kind == "memory_card") preferences.memoryPreset else preferences.mathIntensity)
                put("feedback", if (row.kind == "memory_card") rating else mathNative!!.appliedFeedback)
                put("reviewed_at", reviewedAt)
                put("duration_seconds", durationSeconds.coerceAtLeast(0))
                put("due_at_before", row.dueAt)
                put("due_at_after", result.dueAt)
                put("device_id", deviceId)
                put("created_at", now)
            })

            if (row.kind == "memory_card") {
                insertOrThrow("memory_review_event_v2", null, ContentValues().apply {
                    put("review_event_id", eventId); put("state_before", row.state)
                    put("state_after", result.state)
                    put("target_retention", listOf(0.85, 0.90, 0.93)[memoryPreset])
                    put("elapsed_days", elapsedDays); put("scheduled_days", result.scheduledDays)
                    put("retrievability_before", result.retrievabilityBefore)
                    put("difficulty_before", row.difficulty); put("difficulty_after", result.difficulty)
                    put("stability_before", row.stabilityDays); put("stability_after", result.stabilityDays)
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
                insertOrThrow("math_review_event_v2", null, ContentValues().apply {
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
                    put("scheduled_days", mathNative!!.scheduledDays)
                })
            }
        }
        return result
    }

    fun exportBackup(output: OutputStream) {
        writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
            if (cursor.moveToFirst() && cursor.getInt(0) != 0) {
                error("数据库仍在写入，请稍后重试")
            }
        }
        val databaseFile = appContext.getDatabasePath("reviewfault.db")
        check(databaseFile.isFile) { "数据库文件不存在" }
        val mediaDirectory = File(appContext.filesDir, "media")
        val files = buildList {
            add("database.sqlite" to databaseFile)
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
            put("version", 2)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("schemaVersion", 2)
            put("schedulerAbiVersion", 2)
            put("exportedAt", Instant.now().epochSecond)
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
                    (backupVersion == 2 && schemaVersion == 2 && abiVersion == 2))
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
                require(checkDb.version == 2) { "备份数据库版本不兼容" }
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
                require(it.moveToFirst() && it.getInt(0) == 2) { "恢复后的数据库无法打开" }
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
        structuredJson = getString(14),
    )

    companion object {
        private const val MAX_BACKUP_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_BACKUP_ENTRIES = 10_000
        private val MEMORY_TEMPLATES = setOf(
            "qa", "cloze", "layered_hint", "enumeration", "image_occlusion", "comparison",
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
                val complete = if (inTrigger) line.equals("END;", ignoreCase = true)
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
