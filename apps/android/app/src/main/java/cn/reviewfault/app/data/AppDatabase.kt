package cn.reviewfault.app.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
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

class AppDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, "reviewfault.db", null, 1) {

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
        val sql = appContext.assets.open("001_initial.sql").bufferedReader().use { it.readText() }
        migrationStatements(sql).forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("缺少数据库迁移：$oldVersion -> $newVersion")
    }

    fun dashboard(now: Long, dayStart: Long): DashboardSummary {
        readableDatabase.rawQuery(
            """
            SELECT
              SUM(CASE WHEN scheduler_state <> 0 AND due_at < ? THEN 1 ELSE 0 END),
              SUM(CASE WHEN scheduler_state <> 0 AND due_at BETWEEN ? AND ? THEN 1 ELSE 0 END),
              SUM(CASE WHEN scheduler_state = 0 THEN 1 ELSE 0 END),
              SUM(CASE
                WHEN scheduler_state = 0 OR due_at <= ?
                THEN CASE WHEN kind = 'math_problem' THEN 480 ELSE 45 END
                ELSE 0 END)
            FROM study_item
            WHERE suspended_at IS NULL AND deleted_at IS NULL
            """.trimIndent(),
            arrayOf(dayStart.toString(), dayStart.toString(), now.toString(), now.toString()),
        ).use { cursor ->
            cursor.moveToFirst()
            return DashboardSummary(
                cursor.intOrZero(0),
                cursor.intOrZero(1),
                cursor.intOrZero(2),
                (cursor.intOrZero(3) + 59) / 60,
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
            WHERE s.suspended_at IS NULL AND s.deleted_at IS NULL
              AND ((s.scheduler_state <> 0 AND s.due_at <= ?)
                OR (s.scheduler_state = 0 AND (
                  SELECT COUNT(*) FROM review_log
                  WHERE state_before = 0 AND reviewed_at >= ?
                ) < 20))
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

    fun search(query: String): List<StudyRow> {
        val pattern = "%${query.trim().replace("%", "\\%").replace("_", "\\_")}%"
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
            WHERE s.deleted_at IS NULL AND (
              ? = '%%' OR COALESCE(m.prompt_markdown, p.prompt_markdown, '') LIKE ? ESCAPE '\'
              OR COALESCE(m.answer_markdown, p.solution_markdown, '') LIKE ? ESCAPE '\'
              OR COALESCE(p.source_name, '') LIKE ? ESCAPE '\'
            )
            ORDER BY s.updated_at DESC
            LIMIT 100
            """.trimIndent(),
            arrayOf(pattern, pattern, pattern, pattern),
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(cursor.toStudyRow())
        } }
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
    ): NativeScheduleResult {
        val result = NativeScheduler.nativeReview(
            row.state, row.difficulty, row.stabilityDays, row.dueAt,
            row.lastReviewedAt, row.repetitions, row.lapses,
            rating, reviewedAt, 0.90,
        )
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

            insertOrThrow("review_log", null, ContentValues().apply {
                put("id", uuidV7())
                put("study_item_id", row.id)
                put("reviewed_at", reviewedAt)
                put("rating", rating)
                put("duration_seconds", durationSeconds.coerceAtLeast(0))
                put("scheduler_abi_version", 1)
                put("state_before", row.state)
                put("state_after", result.state)
                put("elapsed_days", elapsedDays)
                put("scheduled_days", result.scheduledDays)
                put("retrievability_before", result.retrievabilityBefore)
                put("difficulty_before", row.difficulty)
                put("difficulty_after", result.difficulty)
                put("stability_before", row.stabilityDays)
                put("stability_after", result.stabilityDays)
                put("due_at_after", result.dueAt)
                put("device_id", deviceId)
                put("created_at", now)
            })

            if (row.kind == "math_problem" && mathAttemptResult != null) {
                insertOrThrow("attempt", null, ContentValues().apply {
                    put("id", uuidV7())
                    put("math_problem_id", row.id)
                    put("started_at", reviewedAt - durationSeconds.coerceAtLeast(0))
                    put("finished_at", reviewedAt)
                    put("result", mathAttemptResult)
                    put("created_at", now)
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
            put("version", 1)
            put("schemaVersion", 1)
            put("schedulerAbiVersion", 1)
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
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val destination = File(restoreRoot, entry.name)
                    val safeRoot = restoreRoot.canonicalPath + File.separator
                    require(destination.canonicalPath.startsWith(safeRoot)) { "备份包含非法路径" }
                    if (entry.isDirectory) {
                        destination.mkdirs()
                    } else {
                        destination.parentFile?.mkdirs()
                        destination.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
            val manifestFile = File(restoreRoot, "manifest.json")
            val manifest = JSONObject(manifestFile.readText())
            require(manifest.getString("format") == "reviewfault-backup" &&
                manifest.getInt("version") == 1 && manifest.getInt("schemaVersion") == 1 &&
                manifest.getInt("schedulerAbiVersion") == 1
            ) { "不是受支持的 ReviewFault 备份" }
            val listed = manifest.getJSONArray("files")
            for (index in 0 until listed.length()) {
                val item = listed.getJSONObject(index)
                val file = File(restoreRoot, item.getString("path"))
                require(file.isFile && file.length() == item.getLong("bytes") &&
                    sha256(file) == item.getString("sha256")
                ) { "备份文件校验失败：${item.getString("path")}" }
            }
            val restoredDatabase = File(restoreRoot, "database.sqlite")
            require(restoredDatabase.isFile) { "备份缺少数据库" }
            SQLiteDatabase.openDatabase(
                restoredDatabase.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
            ).use { checkDb ->
                require(checkDb.version == 1) { "备份数据库版本不兼容" }
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
                require(it.moveToFirst() && it.getInt(0) == 1) { "恢复后的数据库无法打开" }
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
