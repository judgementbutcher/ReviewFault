using Microsoft.Data.Sqlite;
using ReviewFault.Core;
using System.Security.Cryptography;
using System.IO.Compression;
using System.Text.Json;

namespace ReviewFault.Data;

public sealed record StudyRow(
    string Id,
    string Kind,
    string Subject,
    CardState State,
    double Difficulty,
    double StabilityDays,
    long DueAt,
    long LastReviewedAt,
    uint Repetitions,
    uint Lapses,
    string Prompt,
    string Answer,
    string? MediaPath,
    string TemplateType,
    string StructuredJson);

public sealed record DashboardSummary(int Overdue, int DueToday, int NewItems, int EstimatedMinutes);
public sealed record LearningPreferences(
    int DailyNewMemoryLimit, int SessionMinutes, string MemoryPreset,
    string MathIntensity, bool IncludeMemoryCards, bool IncludeMathProblems);
public sealed record LibraryFilter(
    string Query = "", string? Subject = null, string? Kind = null,
    string Status = "all", bool IncludeDeleted = false, int Offset = 0, int Limit = 50);
public sealed record DeletionState(IReadOnlyList<string> ItemIds, long DeletedAt, long UndoUntil);
public sealed record TrashRow(string Id, string Kind, string Prompt, long DeletedAt);

public sealed class AppRepository
{
    // The v1 review_log remains read-only input for gradual history replay.
    private readonly string appDirectory;
    private readonly string connectionString;

    public AppRepository(string? dataDirectory = null)
    {
        appDirectory = dataDirectory ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "ReviewFault");
        Directory.CreateDirectory(appDirectory);
        connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = Path.Combine(appDirectory, "reviewfault.db"),
            Mode = SqliteOpenMode.ReadWriteCreate,
            Pooling = false,
        }.ToString();
    }

    public async Task InitializeAsync()
    {
        await using var connection = await OpenAsync();
        var version = connection.CreateCommand();
        version.CommandText = "PRAGMA user_version";
        var current = Convert.ToInt32(await version.ExecuteScalarAsync());
        if (current == 0)
        {
            var migrationPath = Path.Combine(AppContext.BaseDirectory, "schema", "001_initial.sql");
            var migration = await File.ReadAllTextAsync(migrationPath);
            var command = connection.CreateCommand();
            command.CommandText = migration;
            await command.ExecuteNonQueryAsync();
            current = 1;
        }
        if (current == 1)
        {
            var migrationPath = Path.Combine(AppContext.BaseDirectory, "schema", "002_v0_2.sql");
            var command = connection.CreateCommand();
            command.CommandText = await File.ReadAllTextAsync(migrationPath);
            await command.ExecuteNonQueryAsync();
            current = 2;
        }
        if (current != 2)
        {
            throw new InvalidOperationException($"不支持的数据库版本：{current}");
        }
        NativeScheduler.ValidateAbi();
    }

    public async Task<DashboardSummary> DashboardAsync(long now, long dayStart)
    {
        await using var connection = await OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = """
            SELECT
              COALESCE(SUM(CASE WHEN scheduler_state <> 0 AND due_at < $dayStart THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN scheduler_state <> 0 AND due_at BETWEEN $dayStart AND $now THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN scheduler_state = 0 THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN scheduler_state = 0 OR due_at <= $now
                THEN CASE WHEN kind = 'math_problem' THEN 480 ELSE 45 END ELSE 0 END), 0)
            FROM study_item
            WHERE suspended_at IS NULL AND deleted_at IS NULL
            """;
        command.Parameters.AddWithValue("$dayStart", dayStart);
        command.Parameters.AddWithValue("$now", now);
        await using var reader = await command.ExecuteReaderAsync();
        await reader.ReadAsync();
        return new DashboardSummary(
            reader.GetInt32(0), reader.GetInt32(1), reader.GetInt32(2),
            (reader.GetInt32(3) + 59) / 60);
    }

    public async Task<StudyRow?> NextForReviewAsync(long now)
    {
        var dayStart = new DateTimeOffset(DateTime.Today).ToUnixTimeSeconds();
        await using var connection = await OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = """
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
              AND ((s.scheduler_state <> 0 AND s.due_at <= $now)
                OR (s.scheduler_state = 0 AND (s.kind = 'math_problem' OR (
                  SELECT COUNT(*) FROM review_event_v2 e
                  JOIN memory_review_event_v2 m ON m.review_event_id = e.id
                  WHERE m.state_before = 0 AND e.reviewed_at >= $dayStart
                ) < (SELECT daily_new_memory_limit FROM learning_preferences WHERE singleton = 1))))
            ORDER BY
              CASE WHEN s.scheduler_state <> 0 AND s.due_at < $dayStart THEN 0
                   WHEN s.scheduler_state <> 0 THEN 1 ELSE 2 END,
              CASE WHEN s.scheduler_state <> 0 AND s.due_at < $dayStart
                   THEN CASE WHEN s.kind = 'memory_card' THEN 0 ELSE 1 END ELSE 0 END,
              CASE WHEN s.kind = 'memory_card' THEN 0 ELSE 1 END,
              s.due_at, s.created_at
            LIMIT 1
            """;
        command.Parameters.AddWithValue("$now", now);
        command.Parameters.AddWithValue("$dayStart", dayStart);
        await using var reader = await command.ExecuteReaderAsync();
        if (!await reader.ReadAsync()) return null;
        return new StudyRow(
            reader.GetString(0), reader.GetString(1), reader.GetString(2),
            (CardState)reader.GetInt32(3), reader.GetDouble(4), reader.GetDouble(5),
            reader.GetInt64(6), reader.GetInt64(7), checked((uint)reader.GetInt64(8)),
            checked((uint)reader.GetInt64(9)), reader.GetString(10), reader.GetString(11),
            reader.IsDBNull(12) ? null : reader.GetString(12),
            reader.GetString(13), reader.GetString(14));
    }

    public async Task<IReadOnlyList<StudyRow>> SearchAsync(string query)
    {
        await using var connection = await OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = """
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
              $query = '' OR COALESCE(m.prompt_markdown, p.prompt_markdown, '') LIKE $pattern ESCAPE '\'
              OR COALESCE(m.answer_markdown, p.solution_markdown, '') LIKE $pattern ESCAPE '\'
              OR COALESCE(p.source_name, '') LIKE $pattern ESCAPE '\')
            ORDER BY s.updated_at DESC LIMIT 100
            """;
        command.Parameters.AddWithValue("$query", query.Trim());
        command.Parameters.AddWithValue("$pattern", "%" + query.Trim()
            .Replace("\\", "\\\\").Replace("%", "\\%").Replace("_", "\\_") + "%");
        var rows = new List<StudyRow>();
        await using var reader = await command.ExecuteReaderAsync();
        while (await reader.ReadAsync())
        {
            rows.Add(new StudyRow(
                reader.GetString(0), reader.GetString(1), reader.GetString(2),
                (CardState)reader.GetInt32(3), reader.GetDouble(4), reader.GetDouble(5),
                reader.GetInt64(6), reader.GetInt64(7), checked((uint)reader.GetInt64(8)),
                checked((uint)reader.GetInt64(9)), reader.GetString(10), reader.GetString(11),
                reader.IsDBNull(12) ? null : reader.GetString(12),
                reader.GetString(13), reader.GetString(14)));
        }
        return rows;
    }

    public async Task<LearningPreferences> GetLearningPreferencesAsync()
    {
        await using var connection = await OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = """
            SELECT daily_new_memory_limit, session_minutes, memory_preset,
                   math_intensity, include_memory_cards, include_math_problems
            FROM learning_preferences WHERE singleton = 1
            """;
        await using var reader = await command.ExecuteReaderAsync();
        if (!await reader.ReadAsync()) throw new InvalidOperationException("学习设置不存在");
        return new LearningPreferences(reader.GetInt32(0), reader.GetInt32(1),
            reader.GetString(2), reader.GetString(3), reader.GetInt32(4) != 0,
            reader.GetInt32(5) != 0);
    }

    public async Task SaveLearningPreferencesAsync(LearningPreferences value)
    {
        if (value.DailyNewMemoryLimit is < 0 or > 500 || value.SessionMinutes is < 1 or > 240 ||
            (!value.IncludeMemoryCards && !value.IncludeMathProblems))
            throw new ArgumentException("学习设置超出范围");
        await using var connection = await OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = """
            UPDATE learning_preferences SET daily_new_memory_limit = $limit,
              session_minutes = $minutes, memory_preset = $memory,
              math_intensity = $math, include_memory_cards = $memoryEnabled,
              include_math_problems = $mathEnabled, updated_at = $now
            WHERE singleton = 1
            """;
        command.Parameters.AddWithValue("$limit", value.DailyNewMemoryLimit);
        command.Parameters.AddWithValue("$minutes", value.SessionMinutes);
        command.Parameters.AddWithValue("$memory", value.MemoryPreset);
        command.Parameters.AddWithValue("$math", value.MathIntensity);
        command.Parameters.AddWithValue("$memoryEnabled", value.IncludeMemoryCards ? 1 : 0);
        command.Parameters.AddWithValue("$mathEnabled", value.IncludeMathProblems ? 1 : 0);
        command.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToUnixTimeSeconds());
        await command.ExecuteNonQueryAsync();
    }

    public async Task<DeletionState> SoftDeleteAsync(IReadOnlyList<string> itemIds)
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        if (itemIds.Count == 0) return new DeletionState(itemIds, now, now);
        await using var connection = await OpenAsync();
        await using var transaction = await connection.BeginTransactionAsync();
        foreach (var id in itemIds)
            await ExecuteAsync(connection, transaction,
                "UPDATE study_item SET deleted_at = $now, updated_at = $now WHERE id = $id AND deleted_at IS NULL",
                ("$now", now), ("$id", id));
        await transaction.CommitAsync();
        return new DeletionState(itemIds, now, now + 10);
    }

    public async Task RestoreAsync(IReadOnlyList<string> itemIds)
    {
        await using var connection = await OpenAsync();
        await using var transaction = await connection.BeginTransactionAsync();
        foreach (var id in itemIds)
            await ExecuteAsync(connection, transaction,
                "UPDATE study_item SET deleted_at = NULL, updated_at = $now WHERE id = $id",
                ("$now", DateTimeOffset.UtcNow.ToUnixTimeSeconds()), ("$id", id));
        await transaction.CommitAsync();
    }

    public async Task<IReadOnlyList<TrashRow>> TrashAsync(int offset = 0, int limit = 50)
    {
        await using var connection = await OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = """
            SELECT s.id, s.kind, COALESCE(m.prompt_markdown, p.prompt_markdown, ''), s.deleted_at
            FROM study_item s
            LEFT JOIN memory_card m ON m.study_item_id = s.id
            LEFT JOIN math_problem p ON p.study_item_id = s.id
            WHERE s.deleted_at IS NOT NULL ORDER BY s.deleted_at DESC LIMIT $limit OFFSET $offset
            """;
        command.Parameters.AddWithValue("$limit", Math.Clamp(limit, 1, 200));
        command.Parameters.AddWithValue("$offset", Math.Max(offset, 0));
        var rows = new List<TrashRow>();
        await using var reader = await command.ExecuteReaderAsync();
        while (await reader.ReadAsync()) rows.Add(new TrashRow(
            reader.GetString(0), reader.GetString(1), reader.GetString(2), reader.GetInt64(3)));
        return rows;
    }

    public async Task ReplaceTagsAsync(string itemId, IReadOnlyList<string> names)
    {
        await using var connection = await OpenAsync();
        await using var transaction = await connection.BeginTransactionAsync();
        await ExecuteAsync(connection, transaction, "DELETE FROM study_item_tag WHERE study_item_id = $id",
            ("$id", itemId));
        foreach (var name in names.Select(value => value.Trim()).Where(value => value.Length > 0)
                     .Distinct(StringComparer.OrdinalIgnoreCase))
        {
            var tagId = NewId();
            var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            await ExecuteAsync(connection, transaction, """
                INSERT INTO tag (id, name, created_at, updated_at) VALUES ($id, $name, $now, $now)
                ON CONFLICT(name) DO UPDATE SET deleted_at = NULL, updated_at = excluded.updated_at
                """, ("$id", tagId), ("$name", name), ("$now", now));
            var lookup = connection.CreateCommand();
            lookup.Transaction = (SqliteTransaction)transaction;
            lookup.CommandText = "SELECT id FROM tag WHERE name = $name COLLATE NOCASE";
            lookup.Parameters.AddWithValue("$name", name);
            tagId = (string)(await lookup.ExecuteScalarAsync())!;
            await ExecuteAsync(connection, transaction,
                "INSERT INTO study_item_tag (study_item_id, tag_id) VALUES ($item, $tag)",
                ("$item", itemId), ("$tag", tagId));
        }
        await transaction.CommitAsync();
    }

    public async Task<string> CreateMemoryCardAsync(
        string templateType,
        string subject,
        string prompt,
        string answer,
        IReadOnlyList<string> structuredLines)
    {
        if (string.IsNullOrWhiteSpace(prompt)) throw new ArgumentException("题干不能为空");
        if (templateType is "qa" or "comparison" && string.IsNullOrWhiteSpace(answer))
            throw new ArgumentException("答案不能为空");
        if (templateType == "cloze" &&
            !System.Text.RegularExpressions.Regex.IsMatch(prompt, @"\{\{c\d+::.+?}}"))
            throw new ArgumentException("填空题干缺少结构化标记");
        if (templateType == "layered_hint" &&
            (string.IsNullOrWhiteSpace(answer) || structuredLines.Count == 0))
            throw new ArgumentException("分层提示卡需要答案和提示");
        if (templateType == "enumeration" && structuredLines.Count < 2)
            throw new ArgumentException("枚举卡至少需要两个要点");
        var id = NewId();
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        await using var connection = await OpenAsync();
        await using var transaction = await connection.BeginTransactionAsync();
        var item = connection.CreateCommand();
        item.Transaction = (SqliteTransaction)transaction;
        item.CommandText = """
            INSERT INTO study_item (id, kind, subject, created_at, updated_at)
            VALUES ($id, 'memory_card', $subject, $now, $now)
            """;
        item.Parameters.AddWithValue("$id", id);
        item.Parameters.AddWithValue("$subject", subject);
        item.Parameters.AddWithValue("$now", now);
        await item.ExecuteNonQueryAsync();

        var detail = connection.CreateCommand();
        detail.Transaction = (SqliteTransaction)transaction;
        detail.CommandText = """
            INSERT INTO memory_card (
              study_item_id, template_type, prompt_markdown, answer_markdown,
              hints_json, answer_points_json
            ) VALUES ($id, $template, $prompt, $answer, $hints, $points)
            """;
        detail.Parameters.AddWithValue("$id", id);
        detail.Parameters.AddWithValue("$template", templateType);
        detail.Parameters.AddWithValue("$prompt", prompt.Trim());
        detail.Parameters.AddWithValue("$answer", answer.Trim());
        var json = System.Text.Json.JsonSerializer.Serialize(structuredLines);
        detail.Parameters.AddWithValue("$hints", templateType == "layered_hint" ? json : "[]");
        detail.Parameters.AddWithValue("$points", templateType == "enumeration" ? json : "[]");
        await detail.ExecuteNonQueryAsync();
        await transaction.CommitAsync();
        return id;
    }

    public Task<string> CreateMathProblemAsync(string sourceFile, string sourceName) =>
        CreateMathProblemAsync(new[] { sourceFile }, sourceName);

    public async Task<string> CreateMathProblemAsync(IReadOnlyList<string> sourceFiles, string sourceName)
    {
        if (sourceFiles.Count is < 1 or > 5) throw new ArgumentException("每道题请选择 1–5 张图片");
        var mediaDirectory = Path.Combine(appDirectory, "media");
        Directory.CreateDirectory(mediaDirectory);
        var prepared = new List<(long ByteCount, string Hash, string Extension, string Destination)>();
        foreach (var sourceFile in sourceFiles)
        {
            await using var input = File.OpenRead(sourceFile);
            if (input.Length == 0) throw new ArgumentException("图片为空");
            var byteCount = input.Length;
            var hash = Convert.ToHexString(await SHA256.HashDataAsync(input)).ToLowerInvariant();
            var extension = Path.GetExtension(sourceFile).ToLowerInvariant();
            if (extension is not (".png" or ".webp" or ".jpg" or ".jpeg")) extension = ".jpg";
            var destination = Path.Combine(mediaDirectory, hash + extension);
            if (!File.Exists(destination)) File.Copy(sourceFile, destination);
            prepared.Add((byteCount, hash, extension, destination));
        }

        var problemId = NewId();
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        await using var connection = await OpenAsync();
        await using var transaction = await connection.BeginTransactionAsync();
        await ExecuteAsync(connection, transaction, """
            INSERT INTO study_item (id, kind, subject, created_at, updated_at)
            VALUES ($id, 'math_problem', 'math', $now, $now)
            """, ("$id", problemId), ("$now", now));
        await ExecuteAsync(connection, transaction, """
            INSERT INTO math_problem (study_item_id, source_name) VALUES ($id, $source)
            """, ("$id", problemId), ("$source", sourceName.Trim()));
        for (var index = 0; index < prepared.Count; index++)
        {
            var item = prepared[index];
            await ExecuteAsync(connection, transaction, """
                INSERT OR IGNORE INTO media
                  (id, sha256, mime_type, byte_count, relative_path, created_at)
                VALUES ($id, $hash, $mime, $bytes, $path, $now)
                """, ("$id", NewId()), ("$hash", item.Hash),
                ("$mime", item.Extension switch {
                    ".png" => "image/png", ".webp" => "image/webp", _ => "image/jpeg" }),
                ("$bytes", item.ByteCount),
                ("$path", Path.Combine("media", Path.GetFileName(item.Destination))), ("$now", now));
            var lookup = connection.CreateCommand();
            lookup.Transaction = (SqliteTransaction)transaction;
            lookup.CommandText = "SELECT id FROM media WHERE sha256 = $hash";
            lookup.Parameters.AddWithValue("$hash", item.Hash);
            var storedMediaId = (string)(await lookup.ExecuteScalarAsync())!;
            await ExecuteAsync(connection, transaction, """
                INSERT OR IGNORE INTO math_problem_media (math_problem_id, media_id, role, sort_order)
                VALUES ($problem, $media, 'prompt', $sort)
                """, ("$problem", problemId), ("$media", storedMediaId), ("$sort", index));
        }
        await transaction.CommitAsync();
        return problemId;
    }

    public async Task<IReadOnlyList<string>> MediaPathsAsync(string mathProblemId)
    {
        await using var connection = await OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = """
            SELECT media.relative_path FROM math_problem_media pm
            JOIN media ON media.id = pm.media_id
            WHERE pm.math_problem_id = $id AND pm.role = 'prompt' AND media.deleted_at IS NULL
            ORDER BY pm.sort_order
            """;
        command.Parameters.AddWithValue("$id", mathProblemId);
        var paths = new List<string>();
        await using var reader = await command.ExecuteReaderAsync();
        while (await reader.ReadAsync()) paths.Add(reader.GetString(0));
        return paths;
    }

    public async Task UpdateMathDetailsAsync(
        string id,
        string solution,
        string wrongStep,
        string keyHint,
        string? errorReason)
    {
        await using var connection = await OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = """
            UPDATE math_problem SET solution_markdown = $solution,
              wrong_step_markdown = $wrongStep, key_hint_markdown = $keyHint,
              default_error_reason = $reason
            WHERE study_item_id = $id;
            UPDATE study_item SET updated_at = $now WHERE id = $id;
            """;
        command.Parameters.AddWithValue("$solution", solution.Trim());
        command.Parameters.AddWithValue("$wrongStep", wrongStep.Trim());
        command.Parameters.AddWithValue("$keyHint", keyHint.Trim());
        command.Parameters.AddWithValue("$reason", (object?)errorReason ?? DBNull.Value);
        command.Parameters.AddWithValue("$id", id);
        command.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToUnixTimeSeconds());
        await command.ExecuteNonQueryAsync();
    }

    public async Task UpdateMemoryCardAsync(string id, string prompt, string answer)
    {
        if (string.IsNullOrWhiteSpace(prompt)) throw new ArgumentException("题干不能为空");
        await using var connection = await OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = """
            UPDATE memory_card SET prompt_markdown = $prompt, answer_markdown = $answer
            WHERE study_item_id = $id;
            UPDATE study_item SET updated_at = $now WHERE id = $id;
            """;
        command.Parameters.AddWithValue("$prompt", prompt.Trim());
        command.Parameters.AddWithValue("$answer", answer.Trim());
        command.Parameters.AddWithValue("$id", id);
        command.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToUnixTimeSeconds());
        await command.ExecuteNonQueryAsync();
    }

    public async Task<ScheduleResult> ReviewAsync(
        StudyRow row,
        Rating rating,
        long reviewedAt,
        int durationSeconds,
        string? mathResult,
        string? errorReason = null,
        bool hintRevealed = false)
    {
        var preferences = await GetLearningPreferencesAsync();
        var preset = preferences.MemoryPreset switch { "time_saving" => 0, "reinforced" => 2, _ => 1 };
        var intensity = preferences.MathIntensity switch { "intensive" => 0, "relaxed" => 2, _ => 1 };
        MathScheduleResult? mathSchedule = null;
        uint masteryBefore = 0, streakBefore = 0;
        ScheduleResult result;
        if (row.Kind == "memory_card")
        {
            result = NativeScheduler.ReviewMemoryV2(
                new ScheduleCard(row.State, row.Difficulty, row.StabilityDays, row.DueAt,
                    row.LastReviewedAt, row.Repetitions, row.Lapses), rating, reviewedAt, preset);
        }
        else
        {
            await using var stateConnection = await OpenAsync();
            var stateCommand = stateConnection.CreateCommand();
            stateCommand.CommandText = """
                SELECT m.mastery_level, m.fluent_streak, s.repetitions
                FROM math_schedule_state m JOIN schedule_state_v2 s USING (study_item_id)
                WHERE m.study_item_id = $id
                """;
            stateCommand.Parameters.AddWithValue("$id", row.Id);
            await using var stateReader = await stateCommand.ExecuteReaderAsync();
            if (!await stateReader.ReadAsync()) throw new InvalidOperationException("数学调度状态不存在");
            masteryBefore = checked((uint)stateReader.GetInt64(0));
            streakBefore = checked((uint)stateReader.GetInt64(1));
            var scheduleRepetitions = checked((uint)stateReader.GetInt64(2));
            var feedback = mathResult switch { "again" => 0, "wrong" => 1, "effortful" => 2, "fluent" => 3,
                _ => throw new ArgumentException("数学评分缺失") };
            var reason = errorReason switch { null => 0, "concept" => 1, "approach" => 2,
                "calculation" => 3, "misread" => 4, "forgotten_fact" => 5, "timeout" => 6, _ => 7 };
            mathSchedule = NativeScheduler.ReviewMathV2(masteryBefore, streakBefore, row.DueAt,
                row.LastReviewedAt, scheduleRepetitions, feedback, reason, hintRevealed,
                reviewedAt, intensity);
            result = new ScheduleResult(new ScheduleCard(CardState.Review,
                mathSchedule.MasteryLevel + 1, mathSchedule.ScheduledDays, mathSchedule.DueAt,
                reviewedAt, mathSchedule.Repetitions,
                row.Lapses + (uint)(feedback <= 1 ? 1 : 0)), 0,
                mathSchedule.ScheduledDays, 0);
        }
        var elapsed = row.LastReviewedAt == 0 ? 0 : (reviewedAt - row.LastReviewedAt) / 86400.0;
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        await using var connection = await OpenAsync();
        await using var transaction = await connection.BeginTransactionAsync();
        var updated = await ExecuteAsync(connection, transaction, """
            UPDATE study_item SET scheduler_state = $state, difficulty = $difficulty,
              stability_days = $stability, due_at = $due, last_reviewed_at = $reviewed,
              repetitions = $repetitions, lapses = $lapses, updated_at = $now
            WHERE id = $id AND repetitions = $oldRepetitions
            """, ("$state", (int)result.Card.State), ("$difficulty", result.Card.Difficulty),
            ("$stability", result.Card.StabilityDays), ("$due", result.Card.DueAt),
            ("$reviewed", reviewedAt), ("$repetitions", result.Card.Repetitions),
            ("$lapses", result.Card.Lapses), ("$now", now), ("$id", row.Id),
            ("$oldRepetitions", row.Repetitions));
        if (updated != 1) throw new InvalidOperationException("内容已在其他会话更新，请刷新后重试");

        await ExecuteAsync(connection, transaction, """
            UPDATE schedule_state_v2 SET due_at = $due, last_reviewed_at = $reviewed,
              repetitions = $repetitions, needs_history_replay = 0, updated_at = $now
            WHERE study_item_id = $id
            """, ("$due", result.Card.DueAt), ("$reviewed", reviewedAt),
            ("$repetitions", result.Card.Repetitions), ("$now", now), ("$id", row.Id));
        var eventId = NewId();
        await ExecuteAsync(connection, transaction, """
            INSERT INTO review_event_v2 (id, study_item_id, algorithm, algorithm_version,
              parameter_version, preference, feedback, reviewed_at, duration_seconds,
              due_at_before, due_at_after, device_id, created_at)
            VALUES ($event, $id, $algorithm, 2, 1, $preference, $feedback, $reviewed,
              $duration, $beforeDue, $afterDue, $device, $now)
            """, ("$event", eventId), ("$id", row.Id),
            ("$algorithm", row.Kind == "memory_card" ? "memory_fsrs_6" : "math_mastery_ladder"),
            ("$preference", row.Kind == "memory_card" ? preferences.MemoryPreset : preferences.MathIntensity),
            ("$feedback", row.Kind == "memory_card" ? (int)rating : mathSchedule!.AppliedFeedback),
            ("$reviewed", reviewedAt), ("$duration", Math.Max(0, durationSeconds)),
            ("$beforeDue", row.DueAt), ("$afterDue", result.Card.DueAt),
            ("$device", Environment.MachineName), ("$now", now));
        if (row.Kind == "memory_card")
        {
            await ExecuteAsync(connection, transaction, """
                UPDATE memory_schedule_state SET state = $state, difficulty = $difficulty,
                  stability_days = $stability, lapses = $lapses WHERE study_item_id = $id;
                INSERT INTO memory_review_event_v2 (review_event_id, state_before, state_after,
                  target_retention, elapsed_days, scheduled_days, retrievability_before,
                  difficulty_before, difficulty_after, stability_before, stability_after)
                VALUES ($event, $before, $after, $retention, $elapsed, $scheduled,
                  $retrievability, $difficultyBefore, $difficultyAfter, $stabilityBefore, $stabilityAfter)
                """, ("$state", (int)result.Card.State), ("$difficulty", result.Card.Difficulty),
                ("$stability", result.Card.StabilityDays), ("$lapses", result.Card.Lapses),
                ("$id", row.Id), ("$event", eventId), ("$before", (int)row.State),
                ("$after", (int)result.Card.State),
                ("$retention", preset switch { 0 => 0.85, 2 => 0.93, _ => 0.90 }),
                ("$elapsed", elapsed), ("$scheduled", result.ScheduledDays),
                ("$retrievability", result.RetrievabilityBefore),
                ("$difficultyBefore", row.Difficulty), ("$difficultyAfter", result.Card.Difficulty),
                ("$stabilityBefore", row.StabilityDays), ("$stabilityAfter", result.Card.StabilityDays));
        }
        if (row.Kind == "math_problem" && mathResult is not null)
        {
            var attemptId = NewId();
            await ExecuteAsync(connection, transaction, """
                INSERT INTO attempt (
                  id, math_problem_id, started_at, finished_at, result, error_reason, created_at
                ) VALUES ($attempt, $id, $started, $finished, $result, $reason, $now);
                UPDATE math_schedule_state SET mastery_level = $mastery,
                  fluent_streak = $streak WHERE study_item_id = $id;
                INSERT INTO math_review_event_v2 (review_event_id, attempt_id,
                  requested_feedback, applied_feedback, error_reason, hint_revealed,
                  mastery_before, mastery_after, fluent_streak_before,
                  fluent_streak_after, scheduled_days)
                VALUES ($event, $attempt, $requested, $applied, $reason, $hint,
                  $masteryBefore, $mastery, $streakBefore, $streak, $scheduled)
                """, ("$attempt", attemptId), ("$id", row.Id),
                ("$started", reviewedAt - Math.Max(0, durationSeconds)),
                ("$finished", reviewedAt), ("$result", mathResult),
                ("$reason", errorReason is null ? DBNull.Value : errorReason), ("$now", now),
                ("$mastery", mathSchedule!.MasteryLevel), ("$streak", mathSchedule.FluentStreak),
                ("$event", eventId), ("$requested", mathResult switch {
                    "again" => 0, "wrong" => 1, "effortful" => 2, _ => 3 }),
                ("$applied", mathSchedule.AppliedFeedback), ("$hint", hintRevealed ? 1 : 0),
                ("$masteryBefore", masteryBefore), ("$streakBefore", streakBefore),
                ("$scheduled", mathSchedule.ScheduledDays));
        }
        await transaction.CommitAsync();
        return result;
    }

    public string ResolveMediaPath(string relativePath) => Path.Combine(appDirectory, relativePath);

    public async Task ExportBackupAsync(Stream output)
    {
        await using (var connection = await OpenAsync())
        {
            var checkpoint = connection.CreateCommand();
            checkpoint.CommandText = "PRAGMA wal_checkpoint(FULL)";
            await checkpoint.ExecuteNonQueryAsync();
        }
        var databasePath = Path.Combine(appDirectory, "reviewfault.db");
        if (!File.Exists(databasePath)) throw new InvalidOperationException("数据库文件不存在");
        var files = new List<(string Path, string FilePath)> { ("database.sqlite", databasePath) };
        var mediaDirectory = Path.Combine(appDirectory, "media");
        if (Directory.Exists(mediaDirectory))
        {
            files.AddRange(Directory.EnumerateFiles(mediaDirectory, "*", SearchOption.AllDirectories)
                .Select(path => (
                    "media/" + Path.GetRelativePath(mediaDirectory, path).Replace('\\', '/'), path)));
        }
        var manifestFiles = new List<object>();
        foreach (var (path, filePath) in files)
        {
            var info = new FileInfo(filePath);
            manifestFiles.Add(new { path, sha256 = await Sha256Async(filePath), bytes = info.Length });
        }
        var manifest = JsonSerializer.Serialize(new
        {
            format = "reviewfault-backup",
            version = 2,
            appVersion = "0.2.1",
            schemaVersion = 2,
            schedulerAbiVersion = 2,
            exportedAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
            files = manifestFiles,
        }, new JsonSerializerOptions { WriteIndented = true });

        using var archive = new ZipArchive(output, ZipArchiveMode.Create, leaveOpen: true);
        var manifestEntry = archive.CreateEntry("manifest.json", CompressionLevel.Optimal);
        await using (var writer = new StreamWriter(manifestEntry.Open()))
            await writer.WriteAsync(manifest);
        foreach (var (path, filePath) in files)
        {
            var entry = archive.CreateEntry(path, CompressionLevel.Optimal);
            await using var destination = entry.Open();
            await using var source = File.OpenRead(filePath);
            await source.CopyToAsync(destination);
        }
    }

    public async Task RestoreBackupAsync(Stream input)
    {
        var restoreRoot = Path.Combine(Path.GetTempPath(), "ReviewFault", "restore-" + Guid.NewGuid());
        Directory.CreateDirectory(restoreRoot);
        try
        {
            using (var archive = new ZipArchive(input, ZipArchiveMode.Read, leaveOpen: true))
            {
                var safeRoot = Path.GetFullPath(restoreRoot) + Path.DirectorySeparatorChar;
                foreach (var entry in archive.Entries)
                {
                    var destination = Path.GetFullPath(Path.Combine(restoreRoot, entry.FullName));
                    if (!destination.StartsWith(safeRoot, StringComparison.OrdinalIgnoreCase))
                        throw new InvalidDataException("备份包含非法路径");
                    if (string.IsNullOrEmpty(entry.Name))
                    {
                        Directory.CreateDirectory(destination);
                        continue;
                    }
                    Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
                    await using var source = entry.Open();
                    await using var target = File.Create(destination);
                    await source.CopyToAsync(target);
                }
            }
            var manifestPath = Path.Combine(restoreRoot, "manifest.json");
            using var manifest = JsonDocument.Parse(await File.ReadAllTextAsync(manifestPath));
            var root = manifest.RootElement;
            var backupVersion = root.GetProperty("version").GetInt32();
            var schemaVersion = root.GetProperty("schemaVersion").GetInt32();
            var abiVersion = root.GetProperty("schedulerAbiVersion").GetInt32();
            if (root.GetProperty("format").GetString() != "reviewfault-backup" ||
                !((backupVersion == 1 && schemaVersion == 1 && abiVersion == 1) ||
                  (backupVersion == 2 && schemaVersion == 2 && abiVersion == 2)))
                throw new InvalidDataException("不是受支持的 ReviewFault 备份");
            foreach (var item in root.GetProperty("files").EnumerateArray())
            {
                var relative = item.GetProperty("path").GetString()!;
                var filePath = Path.GetFullPath(Path.Combine(restoreRoot, relative));
                var safeRoot = Path.GetFullPath(restoreRoot) + Path.DirectorySeparatorChar;
                if (!filePath.StartsWith(safeRoot, StringComparison.OrdinalIgnoreCase) ||
                    !File.Exists(filePath) || new FileInfo(filePath).Length != item.GetProperty("bytes").GetInt64() ||
                    await Sha256Async(filePath) != item.GetProperty("sha256").GetString())
                    throw new InvalidDataException($"备份文件校验失败：{relative}");
            }
            var restoredDatabase = Path.Combine(restoreRoot, "database.sqlite");
            var validationString = new SqliteConnectionStringBuilder
            {
                DataSource = restoredDatabase,
                Mode = SqliteOpenMode.ReadWrite,
                Pooling = false,
            }.ToString();
            await using (var validation = new SqliteConnection(validationString))
            {
                await validation.OpenAsync();
                var command = validation.CreateCommand();
                command.CommandText = "PRAGMA user_version";
                var restoredVersion = Convert.ToInt32(await command.ExecuteScalarAsync());
                if (restoredVersion == 1)
                {
                    command.CommandText = await File.ReadAllTextAsync(Path.Combine(
                        AppContext.BaseDirectory, "schema", "002_v0_2.sql"));
                    await command.ExecuteNonQueryAsync();
                    restoredVersion = 2;
                }
                command.CommandText = "PRAGMA integrity_check";
                if ((string?)await command.ExecuteScalarAsync() != "ok")
                    throw new InvalidDataException("备份数据库完整性检查失败");
                if (restoredVersion != 2)
                    throw new InvalidDataException("备份数据库版本不兼容");
                command.CommandText = "PRAGMA foreign_key_check";
                await using var invalidReferences = await command.ExecuteReaderAsync();
                if (await invalidReferences.ReadAsync())
                    throw new InvalidDataException("备份数据库存在无效引用");
            }
            ReplaceCurrentData(restoreRoot);
        }
        finally
        {
            if (Directory.Exists(restoreRoot)) Directory.Delete(restoreRoot, true);
        }
    }

    private void ReplaceCurrentData(string restoreRoot)
    {
        var rollbackRoot = Path.Combine(Path.GetTempPath(), "ReviewFault", "rollback-" + Guid.NewGuid());
        Directory.CreateDirectory(rollbackRoot);
        var databasePath = Path.Combine(appDirectory, "reviewfault.db");
        var mediaPath = Path.Combine(appDirectory, "media");
        try
        {
            if (File.Exists(databasePath)) File.Copy(databasePath, Path.Combine(rollbackRoot, "database.sqlite"));
            if (Directory.Exists(mediaPath)) CopyDirectory(mediaPath, Path.Combine(rollbackRoot, "media"));
            File.Copy(Path.Combine(restoreRoot, "database.sqlite"), databasePath, true);
            if (Directory.Exists(mediaPath)) Directory.Delete(mediaPath, true);
            var restoredMedia = Path.Combine(restoreRoot, "media");
            if (Directory.Exists(restoredMedia)) CopyDirectory(restoredMedia, mediaPath);
        }
        catch
        {
            var oldDatabase = Path.Combine(rollbackRoot, "database.sqlite");
            if (File.Exists(oldDatabase)) File.Copy(oldDatabase, databasePath, true);
            if (Directory.Exists(mediaPath)) Directory.Delete(mediaPath, true);
            var oldMedia = Path.Combine(rollbackRoot, "media");
            if (Directory.Exists(oldMedia)) CopyDirectory(oldMedia, mediaPath);
            throw;
        }
        finally
        {
            Directory.Delete(rollbackRoot, true);
        }
    }

    private static void CopyDirectory(string source, string destination)
    {
        Directory.CreateDirectory(destination);
        foreach (var directory in Directory.EnumerateDirectories(source, "*", SearchOption.AllDirectories))
            Directory.CreateDirectory(Path.Combine(destination, Path.GetRelativePath(source, directory)));
        foreach (var file in Directory.EnumerateFiles(source, "*", SearchOption.AllDirectories))
        {
            var target = Path.Combine(destination, Path.GetRelativePath(source, file));
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            File.Copy(file, target, true);
        }
    }

    private static async Task<string> Sha256Async(string path)
    {
        await using var input = File.OpenRead(path);
        var hash = await SHA256.HashDataAsync(input);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    private static string NewId()
    {
        Span<byte> bytes = stackalloc byte[16];
        RandomNumberGenerator.Fill(bytes);
        var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        for (var index = 0; index < 6; index++)
            bytes[5 - index] = (byte)(timestamp >> (index * 8));
        bytes[6] = (byte)(0x70 | (bytes[6] & 0x0f));
        bytes[8] = (byte)(0x80 | (bytes[8] & 0x3f));
        var hex = Convert.ToHexString(bytes).ToLowerInvariant();
        return $"{hex[..8]}-{hex[8..12]}-{hex[12..16]}-{hex[16..20]}-{hex[20..]}";
    }

    private async Task<SqliteConnection> OpenAsync()
    {
        var connection = new SqliteConnection(connectionString);
        await connection.OpenAsync();
        var pragma = connection.CreateCommand();
        pragma.CommandText = "PRAGMA foreign_keys = ON";
        await pragma.ExecuteNonQueryAsync();
        return connection;
    }

    private static async Task<int> ExecuteAsync(
        SqliteConnection connection,
        System.Data.Common.DbTransaction transaction,
        string sql,
        params (string Name, object Value)[] parameters)
    {
        var command = connection.CreateCommand();
        command.Transaction = (SqliteTransaction)transaction;
        command.CommandText = sql;
        foreach (var (name, value) in parameters) command.Parameters.AddWithValue(name, value);
        return await command.ExecuteNonQueryAsync();
    }
}
