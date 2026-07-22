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
    string StructuredJson)
{
    public CardProfile Profile { get; init; } = new();
    public string HintsJson { get; init; } = "[]";
    public string AnswerPointsJson { get; init; } = "[]";
    public string TagsJson { get; init; } = "[]";
}

public sealed record CardProfile(
    string Archetype = "qa", string KnowledgePoint = "", string SourceType = "notes",
    string SourceTitle = "", string SourceChapter = "", string SourceLocator = "",
    int? SourceYear = null, string Mechanism = "", string Conditions = "",
    string Contrast = "", string Example = "", string CommonTrap = "",
    string TransferPrompt = "", string Mnemonic = "", string FirstAttempt = "",
    string ErrorTrigger = "", string GeneralMethod = "", string Verification = "",
    int? TargetSeconds = null, string StructuredPayload = "{}");

public sealed record MemoryCardDraft(
    string TemplateType, string Archetype, string Subject, string KnowledgePoint,
    string Prompt, string Answer, IReadOnlyList<string> Hints, IReadOnlyList<string> AnswerPoints,
    string Mechanism = "", string Conditions = "", string Contrast = "", string Example = "",
    string CommonTrap = "", string TransferPrompt = "", string Mnemonic = "",
    string StructuredPayload = "{}", string SourceType = "notes", string SourceTitle = "",
    string SourceChapter = "", string SourceLocator = "", int? SourceYear = null,
    IReadOnlyList<string>? Tags = null);

public sealed record MathErrorDraft(
    string KnowledgePoint = "", string SourceType = "practice", string SourceTitle = "",
    string SourceChapter = "", string SourceLocator = "", int? SourceYear = null,
    string Solution = "", string FirstAttempt = "", string ErrorTrigger = "",
    string? ErrorReason = null, string KeyHint = "", string GeneralMethod = "",
    string Verification = "", string TransferPrompt = "", int? TargetSeconds = null,
    IReadOnlyList<string>? Tags = null);

public sealed record DashboardSummary(
    int Overdue, int DueToday, int NewItems, int EstimatedMinutes,
    int DeferredDueMinutes, int TomorrowDue, int NextSevenDaysDue);
public sealed record InsightDay(string Label, string DueLabel, int Reviews, int Due);
public sealed record SubjectInsight(string Subject, int Total, int Mastered);
public sealed record InsightsSnapshot(
    int ReviewsToday, int AccuracyPercent, int StreakDays, int TotalReviews,
    int ActiveItems, int MasteredItems,
    IReadOnlyList<InsightDay> Days, IReadOnlyList<SubjectInsight> Subjects);
public sealed record LearningPreferences(
    int DailyNewMemoryLimit, int SessionMinutes, string MemoryPreset,
    string MathIntensity, bool IncludeMemoryCards, bool IncludeMathProblems,
    int SchedulerGeneration = 3);
public sealed record LibraryFilter(
    string Query = "", string? Subject = null, string? Kind = null,
    string Status = "all", bool IncludeDeleted = false, int Offset = 0, int Limit = 50);
public sealed record DeletionState(IReadOnlyList<string> ItemIds, long DeletedAt, long UndoUntil);
public sealed record TrashRow(string Id, string Kind, string Prompt, long DeletedAt);
public sealed record SyncIdentity(string DeviceId, string? WorkspaceId, long Cursor, int PendingCount);
public sealed record PulledOperation(
    string OperationId, long ServerSeq, string DeviceId, long DeviceCounter,
    string EntityType, string EntityId, string Action, JsonElement ChangedFields, long OccurredAt);
public sealed record SyncMediaObject(string Sha256, string MimeType, long ByteCount, string FilePath);
public sealed record MissingMediaObject(string Sha256, string FilePath);

public sealed class AppRepository
{
    private const string AppVersion = "0.6.0";
    private const long MaxBackupBytes = 2L * 1024 * 1024 * 1024;
    private const int MaxBackupEntries = 10_000;
    // The v1 review_log remains read-only input for gradual history replay.
    // v3 parameter checksums are foreign-keyed to algorithm_parameter_registry.
    private readonly string appDirectory;
    private readonly string connectionString;
    private string deviceId = "";

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
        if (current == 2)
        {
            var migrationPath = Path.Combine(AppContext.BaseDirectory, "schema", "003_v0_3.sql");
            var command = connection.CreateCommand();
            command.CommandText = await File.ReadAllTextAsync(migrationPath);
            await command.ExecuteNonQueryAsync();
            current = 3;
        }
        if (current == 3)
        {
            var migrationPath = Path.Combine(AppContext.BaseDirectory, "schema", "004_v0_4.sql");
            var command = connection.CreateCommand();
            command.CommandText = await File.ReadAllTextAsync(migrationPath);
            await command.ExecuteNonQueryAsync();
            current = 4;
        }
        if (current == 4)
        {
            var migrationPath = Path.Combine(AppContext.BaseDirectory, "schema", "005_v0_5.sql");
            var command = connection.CreateCommand();
            command.CommandText = await File.ReadAllTextAsync(migrationPath);
            await command.ExecuteNonQueryAsync();
            current = 5;
        }
        if (current != 5)
        {
            throw new InvalidOperationException($"不支持的数据库版本：{current}");
        }
        await EnsureDeviceIdentityAsync(connection);
        NativeScheduler.ValidateAbi();
    }

    public async Task BindAccountAsync(string accountId, string workspaceId)
    {
        if (string.IsNullOrWhiteSpace(accountId) || string.IsNullOrWhiteSpace(workspaceId))
            throw new ArgumentException("账号与 workspace 不能为空");
        await using var connection = await OpenAsync();
        await using var transaction = await connection.BeginTransactionAsync();
        var query = connection.CreateCommand(); query.Transaction = (SqliteTransaction)transaction;
        query.CommandText = "SELECT account_id, workspace_id FROM local_device WHERE singleton = 1";
        await using var reader = await query.ExecuteReaderAsync(); await reader.ReadAsync();
        var existingAccount = reader.IsDBNull(0) ? null : reader.GetString(0);
        var existingWorkspace = reader.IsDBNull(1) ? null : reader.GetString(1);
        await reader.DisposeAsync();
        if (existingAccount is not null && (existingAccount != accountId || existingWorkspace != workspaceId))
            throw new InvalidOperationException("此本地数据目录已绑定其他账号；请先导出备份并明确清除本地数据");
        await ExecuteAsync(connection, transaction, """
            UPDATE local_device SET account_id = $account, workspace_id = $workspace WHERE singleton = 1;
            INSERT OR IGNORE INTO sync_cursor (workspace_id, server_seq, updated_at)
              VALUES ($workspace, 0, $now);
            """, ("$account", accountId), ("$workspace", workspaceId),
            ("$now", DateTimeOffset.UtcNow.ToUnixTimeSeconds()));
        await transaction.CommitAsync();
    }

    private sealed record ReviewFact(ReplayAction Action, uint DurationSeconds, string? ErrorReason, bool HintRevealed);

    private async Task RebuildDirtySchedulesAsync()
    {
        var preferences = await GetLearningPreferencesAsync();
        var preset = preferences.MemoryPreset switch { "time_saving" => 0, "reinforced" => 2, _ => 1 };
        var intensity = preferences.MathIntensity switch { "intensive" => 0, "relaxed" => 2, _ => 1 };
        await using var connection = await OpenAsync();
        var dirtyCommand = connection.CreateCommand(); dirtyCommand.CommandText = """
            SELECT c.study_item_id, i.kind FROM schedule_cache_v4 c
            JOIN study_item i ON i.id = c.study_item_id WHERE c.dirty = 1
            """;
        var dirty = new List<(string Id, string Kind)>();
        await using (var reader = await dirtyCommand.ExecuteReaderAsync())
            while (await reader.ReadAsync()) dirty.Add((reader.GetString(0), reader.GetString(1)));
        if (dirty.Count == 0) return;
        await using var transaction = await connection.BeginTransactionAsync();
        foreach (var (id, kind) in dirty)
        {
            var facts = await ReviewFactsAsync(connection, transaction, id);
            var order = NativeScheduler.CanonicalOrderV4(facts.Select(value => value.Action).ToArray());
            long dueAt;
            if (kind == "memory_card")
            {
                var card = new ScheduleCard(CardState.New, 0, 0, 0, 0, 0, 0); uint consecutiveLapses = 0;
                for (var history = 0; history < order.Length; history++)
                {
                    var fact = facts[order[history]]; var effective = Math.Max(fact.Action.ReviewedAt, card.LastReviewedAt);
                    var result = NativeScheduler.ReviewMemoryV3(card, (Rating)fact.Action.Feedback,
                        effective, preset, (uint)history, 0, consecutiveLapses);
                    card = result.Card; consecutiveLapses = fact.Action.Feedback == 1 ? consecutiveLapses + 1 : 0;
                }
                dueAt = card.DueAt;
                await ExecuteAsync(connection, transaction, """
                    UPDATE schedule_state_v2 SET due_at = $due, last_reviewed_at = $last,
                      repetitions = $repetitions, updated_at = $now WHERE study_item_id = $id;
                    UPDATE memory_schedule_state SET state = $state, difficulty = $difficulty,
                      stability_days = $stability, lapses = $lapses WHERE study_item_id = $id;
                    UPDATE study_item SET scheduler_state = $state, difficulty = $difficulty,
                      stability_days = $stability, due_at = $due, last_reviewed_at = $last,
                      repetitions = $repetitions, lapses = $lapses, updated_at = $now
                      WHERE id = $id;
                    """, ("$state", (int)card.State), ("$difficulty", card.Difficulty),
                    ("$stability", card.StabilityDays), ("$due", card.DueAt), ("$last", card.LastReviewedAt),
                    ("$repetitions", card.Repetitions), ("$lapses", card.Lapses), ("$id", id),
                    ("$now", DateTimeOffset.UtcNow.ToUnixTimeSeconds()));
            }
            else
            {
                uint mastery = 0, streak = 0, repetitions = 0, failures = 0, lapses = 0;
                long last = 0; dueAt = 0; double scheduledDays = 0;
                foreach (var index in order)
                {
                    var fact = facts[index]; var effective = Math.Max(fact.Action.ReviewedAt, last);
                    var result = NativeScheduler.ReviewMathV3(mastery, streak, dueAt, last, repetitions,
                        fact.Action.Feedback, ErrorReasonCode(fact.ErrorReason), fact.HintRevealed,
                        effective, intensity, fact.DurationSeconds, 0, failures);
                    mastery = result.MasteryLevel; streak = result.FluentStreak; repetitions = result.Repetitions;
                    dueAt = result.DueAt; last = result.LastReviewedAt; scheduledDays = result.ScheduledDays;
                    if (fact.Action.Feedback <= 1) lapses++;
                    failures = fact.Action.Feedback <= 1 ? failures + 1 : 0;
                }
                await ExecuteAsync(connection, transaction, """
                    UPDATE schedule_state_v2 SET due_at = $due, last_reviewed_at = $last,
                      repetitions = $repetitions, updated_at = $now WHERE study_item_id = $id;
                    UPDATE math_schedule_state SET mastery_level = $mastery, fluent_streak = $streak
                      WHERE study_item_id = $id;
                    UPDATE study_item SET scheduler_state = $state, difficulty = $difficulty,
                      stability_days = $stability, due_at = $due, last_reviewed_at = $last,
                      repetitions = $repetitions, lapses = $lapses, updated_at = $now
                      WHERE id = $id;
                    """, ("$due", dueAt), ("$last", last), ("$repetitions", repetitions),
                    ("$id", id), ("$mastery", mastery), ("$streak", streak),
                    ("$state", repetitions == 0 ? (int)CardState.New : (int)CardState.Review),
                    ("$difficulty", repetitions == 0 ? 0 : mastery + 1),
                    ("$stability", scheduledDays), ("$lapses", lapses),
                    ("$now", DateTimeOffset.UtcNow.ToUnixTimeSeconds()));
            }
            await ExecuteAsync(connection, transaction, """
                UPDATE schedule_cache_v4 SET due_at = $due, replayed_action_count = $count,
                  dirty = 0, rebuilt_at = $now WHERE study_item_id = $id
                """, ("$due", dueAt), ("$count", facts.Count),
                ("$now", DateTimeOffset.UtcNow.ToUnixTimeSeconds()), ("$id", id));
        }
        await transaction.CommitAsync();
    }

    private static async Task<List<ReviewFact>> ReviewFactsAsync(
        SqliteConnection connection, System.Data.Common.DbTransaction transaction, string itemId)
    {
        var command = connection.CreateCommand(); command.Transaction = (SqliteTransaction)transaction;
        command.CommandText = """
            SELECT action_id, device_id, device_counter, causal_cursor, feedback, reviewed_at,
              COALESCE(duration_seconds, 0), error_reason, hint_revealed
            FROM review_action_v4 WHERE study_item_id = $id
            """;
        command.Parameters.AddWithValue("$id", itemId); var facts = new List<ReviewFact>();
        await using var reader = await command.ExecuteReaderAsync();
        while (await reader.ReadAsync()) facts.Add(new ReviewFact(new ReplayAction(
            reader.GetString(0), reader.GetString(1), checked((ulong)reader.GetInt64(2)),
            checked((ulong)reader.GetInt64(3)), reader.GetInt32(4), reader.GetInt64(5)),
            checked((uint)reader.GetInt64(6)), reader.IsDBNull(7) ? null : reader.GetString(7), reader.GetInt32(8) != 0));
        return facts;
    }

    private static int ErrorReasonCode(string? value) => value switch {
        "concept" => 1, "approach" => 2, "calculation" => 3, "misread" => 4,
        "forgotten_fact" => 5, "timeout" => 6, "other" => 7, _ => 0,
    };

    public async Task<SyncIdentity> SyncIdentityAsync()
    {
        await using var connection = await OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = """
            SELECT d.device_id, d.workspace_id,
              COALESCE((SELECT server_seq FROM sync_cursor WHERE workspace_id = d.workspace_id), 0),
              (SELECT COUNT(*) FROM sync_outbox)
            FROM local_device d WHERE singleton = 1
            """;
        await using var reader = await command.ExecuteReaderAsync(); await reader.ReadAsync();
        return new SyncIdentity(reader.GetString(0), reader.IsDBNull(1) ? null : reader.GetString(1),
            reader.GetInt64(2), reader.GetInt32(3));
    }

    public async Task<JsonElement> PendingSyncOperationsAsync(int limit = 200)
    {
        await using var connection = await OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = """
            SELECT operation_id, device_id, device_counter, base_cursor, base_revision,
              entity_type, entity_id, action, changed_fields_json, occurred_at
            FROM sync_outbox ORDER BY device_counter LIMIT $limit
            """;
        command.Parameters.AddWithValue("$limit", Math.Clamp(limit, 1, 500));
        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<object>();
        while (await reader.ReadAsync()) rows.Add(new {
            operationId = reader.GetString(0), deviceId = reader.GetString(1),
            deviceCounter = reader.GetInt64(2), baseCursor = reader.GetInt64(3),
            baseRevision = reader.GetInt64(4), entityType = reader.GetString(5),
            entityId = reader.GetString(6), action = reader.GetString(7),
            changedFields = JsonDocument.Parse(reader.GetString(8)).RootElement.Clone(),
            occurredAt = reader.GetInt64(9),
        });
        return JsonSerializer.SerializeToElement(rows);
    }

    public async Task AcknowledgeSyncOperationsAsync(IReadOnlySet<string> operationIds)
    {
        if (operationIds.Count == 0) return;
        await using var connection = await OpenAsync(); await using var transaction = await connection.BeginTransactionAsync();
        foreach (var id in operationIds)
            await ExecuteAsync(connection, transaction, "DELETE FROM sync_outbox WHERE operation_id = $id", ("$id", id));
        await transaction.CommitAsync();
    }

    public async Task<IReadOnlyList<SyncMediaObject>> MediaForSyncAsync()
    {
        await using var connection = await OpenAsync(); var command = connection.CreateCommand();
        command.CommandText = "SELECT sha256, mime_type, byte_count, relative_path FROM media WHERE deleted_at IS NULL";
        await using var reader = await command.ExecuteReaderAsync(); var values = new List<SyncMediaObject>();
        while (await reader.ReadAsync())
        {
            var path = ResolveMediaPath(reader.GetString(3));
            if (File.Exists(path)) values.Add(new SyncMediaObject(reader.GetString(0), reader.GetString(1), reader.GetInt64(2), path));
        }
        return values;
    }

    public async Task<IReadOnlyList<MissingMediaObject>> MissingMediaAsync()
    {
        await using var connection = await OpenAsync(); var command = connection.CreateCommand();
        command.CommandText = "SELECT sha256, relative_path FROM media WHERE deleted_at IS NULL";
        await using var reader = await command.ExecuteReaderAsync(); var values = new List<MissingMediaObject>();
        while (await reader.ReadAsync())
        {
            var path = ResolveMediaPath(reader.GetString(1));
            if (!File.Exists(path)) values.Add(new MissingMediaObject(reader.GetString(0), path));
        }
        return values;
    }

    public static async Task SaveDownloadedMediaAsync(MissingMediaObject media, byte[] bytes)
    {
        var actual = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        if (actual != media.Sha256) throw new InvalidDataException("下载媒体哈希不匹配");
        Directory.CreateDirectory(Path.GetDirectoryName(media.FilePath)!);
        var temporary = media.FilePath + "." + Guid.NewGuid() + ".tmp";
        await File.WriteAllBytesAsync(temporary, bytes);
        if (File.Exists(media.FilePath)) File.Delete(temporary); else File.Move(temporary, media.FilePath);
    }

    public async Task ApplyPulledOperationsAsync(
        string workspaceId, IReadOnlyList<PulledOperation> operations, long cursor)
    {
        await using var connection = await OpenAsync(); await using var transaction = await connection.BeginTransactionAsync();
        foreach (var operation in operations.OrderBy(value => value.ServerSeq))
        {
            var counterCommand = connection.CreateCommand();
            counterCommand.Transaction = (SqliteTransaction)transaction;
            counterCommand.CommandText = "SELECT next_counter FROM local_device WHERE singleton = 1";
            var remoteApplyCounterStart = Convert.ToInt64(await counterCommand.ExecuteScalarAsync());
            if (operation.EntityType == "studyItem")
                await ApplyRemoteStudyItemAsync(connection, transaction, operation);
            else if (operation.EntityType == "memoryCard")
                await ApplyRemoteMemoryCardAsync(connection, transaction, operation);
            else if (operation.EntityType == "mathProblem")
                await ApplyRemoteMathProblemAsync(connection, transaction, operation);
            else if (operation.EntityType == "cardProfile")
                await ApplyRemoteCardProfileAsync(connection, transaction, operation);
            else if (operation.EntityType == "tag")
                await ApplyRemoteTagAsync(connection, transaction, operation);
            else if (operation.EntityType == "relation")
                await ApplyRemoteRelationAsync(connection, transaction, operation);
            else if (operation.EntityType == "learningPreferences")
                await ApplyRemoteLearningPreferencesAsync(connection, transaction, operation);
            else if (operation.EntityType == "attemptArtifact")
                await ApplyRemoteAttemptArtifactAsync(connection, transaction, operation);
            else if (operation.EntityType == "reviewAction")
                await ApplyRemoteReviewActionAsync(connection, transaction, operation);
            else if (operation.EntityType == "learningEvidence")
                await ApplyRemoteLearningEvidenceAsync(connection, transaction, operation);
            // Content triggers serve ordinary local writes too. Remove only operations
            // created while projecting this pulled fact so it is not echoed to the service.
            await ExecuteAsync(connection, transaction, """
                DELETE FROM sync_outbox
                WHERE device_id = (SELECT device_id FROM local_device WHERE singleton = 1)
                  AND device_counter >= $counter;
                DELETE FROM relation_operation
                WHERE device_id = (SELECT device_id FROM local_device WHERE singleton = 1)
                  AND device_counter >= $counter;
                """, ("$counter", remoteApplyCounterStart));
            await ExecuteAsync(connection, transaction, """
                INSERT INTO sync_revision (entity_type, entity_id, revision, server_seq, deleted)
                VALUES ($type, $id, 1, $seq, $deleted)
                ON CONFLICT(entity_type, entity_id) DO UPDATE SET
                  revision = revision + 1, server_seq = MAX(server_seq, excluded.server_seq),
                  deleted = excluded.deleted
                """, ("$type", operation.EntityType), ("$id", operation.EntityId),
                ("$seq", operation.ServerSeq), ("$deleted", operation.Action == "delete" ? 1 : 0));
        }
        await ExecuteAsync(connection, transaction, """
            INSERT INTO sync_cursor (workspace_id, server_seq, updated_at) VALUES ($workspace, $cursor, $now)
            ON CONFLICT(workspace_id) DO UPDATE SET server_seq = MAX(server_seq, excluded.server_seq),
              updated_at = excluded.updated_at
            """, ("$workspace", workspaceId), ("$cursor", cursor),
            ("$now", DateTimeOffset.UtcNow.ToUnixTimeSeconds()));
        await transaction.CommitAsync();
        await RebuildDirtySchedulesAsync();
    }

    private static async Task ApplyRemoteStudyItemAsync(
        SqliteConnection connection, System.Data.Common.DbTransaction transaction, PulledOperation operation)
    {
        var fields = operation.ChangedFields;
        var lookup = connection.CreateCommand(); lookup.Transaction = (SqliteTransaction)transaction;
        lookup.CommandText = "SELECT kind FROM study_item WHERE id = $id";
        lookup.Parameters.AddWithValue("$id", operation.EntityId);
        var existing = (string?)await lookup.ExecuteScalarAsync();
        var kind = fields.TryGetProperty("kind", out var kindValue) ? kindValue.GetString()! : existing ?? "memory_card";
        var subjectName = Text(fields, "subject", kind == "math_problem" ? "math" : "operating_systems");
        var now = fields.TryGetProperty("updatedAt", out var updated) ? updated.GetInt64() : operation.OccurredAt;
        if (existing is null)
        {
            await ExecuteAsync(connection, transaction, """
                INSERT INTO study_item (id, kind, subject, created_at, updated_at, deleted_at)
                VALUES ($id, $kind, $subject, $created, $updated, $deleted)
                """, ("$id", operation.EntityId), ("$kind", kind),
                ("$subject", subjectName),
                ("$created", Long(fields, "createdAt", operation.OccurredAt)), ("$updated", now),
                ("$deleted", operation.Action == "delete" ? operation.OccurredAt : DBNull.Value));
            if (kind == "memory_card")
                await ExecuteAsync(connection, transaction, """
                    INSERT INTO memory_card (study_item_id, template_type, prompt_markdown, answer_markdown,
                      hints_json, answer_points_json) VALUES ($id, $template, $prompt, $answer, $hints, $points)
                    """, ("$id", operation.EntityId), ("$template", Text(fields, "templateType", "qa")),
                    ("$prompt", Text(fields, "prompt")), ("$answer", Text(fields, "answer")),
                    ("$hints", Raw(fields, "hints", "[]")), ("$points", Raw(fields, "answerPoints", "[]")));
            else
                await ExecuteAsync(connection, transaction, """
                    INSERT INTO math_problem (study_item_id, source_name, solution_markdown,
                      wrong_step_markdown, key_hint_markdown) VALUES ($id, $source, $solution, $wrong, $hint)
                    """, ("$id", operation.EntityId), ("$source", Text(fields, "sourceName")),
                    ("$solution", Text(fields, "solution")), ("$wrong", Text(fields, "wrongStep")),
                    ("$hint", Text(fields, "keyHint")));
            if (kind == "math_problem" && fields.TryGetProperty("media", out var media))
                await AttachRemoteMediaMetadataAsync(connection, transaction, operation.EntityId, media, operation.OccurredAt);
            await CreateLearningRouteAsync(connection, transaction, operation.EntityId, kind, subjectName, now);
            return;
        }
        await ExecuteAsync(connection, transaction, """
            UPDATE study_item SET subject = COALESCE($subject, subject), updated_at = $updated,
              deleted_at = CASE WHEN $action = 'delete' THEN $occurred
                                WHEN $action = 'restore' THEN NULL ELSE deleted_at END WHERE id = $id
            """, ("$subject", fields.TryGetProperty("subject", out var subject) ? subject.GetString()! : DBNull.Value),
            ("$updated", now), ("$action", operation.Action), ("$occurred", operation.OccurredAt), ("$id", operation.EntityId));
        if (kind == "memory_card")
            await ExecuteAsync(connection, transaction, """
                UPDATE memory_card SET prompt_markdown = COALESCE($prompt, prompt_markdown),
                  answer_markdown = COALESCE($answer, answer_markdown),
                  template_type = COALESCE($template, template_type),
                  hints_json = COALESCE($hints, hints_json),
                  answer_points_json = COALESCE($points, answer_points_json)
                  WHERE study_item_id = $id
                """, ("$prompt", OptionalText(fields, "prompt")), ("$answer", OptionalText(fields, "answer")),
                ("$template", OptionalText(fields, "templateType")),
                ("$hints", OptionalRaw(fields, "hints")), ("$points", OptionalRaw(fields, "answerPoints")),
                ("$id", operation.EntityId));
        else
            await ExecuteAsync(connection, transaction, """
                UPDATE math_problem SET source_name = COALESCE($source, source_name),
                  solution_markdown = COALESCE($solution, solution_markdown),
                  wrong_step_markdown = COALESCE($wrong, wrong_step_markdown),
                  key_hint_markdown = COALESCE($hint, key_hint_markdown) WHERE study_item_id = $id
                """, ("$source", OptionalText(fields, "sourceName")), ("$solution", OptionalText(fields, "solution")),
                ("$wrong", OptionalText(fields, "wrongStep")), ("$hint", OptionalText(fields, "keyHint")),
                ("$id", operation.EntityId));
        if (kind == "math_problem" && fields.TryGetProperty("media", out var updatedMedia))
            await AttachRemoteMediaMetadataAsync(connection, transaction, operation.EntityId, updatedMedia, operation.OccurredAt);
    }

    private static async Task AttachRemoteMediaMetadataAsync(
        SqliteConnection connection, System.Data.Common.DbTransaction transaction,
        string problemId, JsonElement values, long createdAt)
    {
        var index = 0;
        foreach (var value in values.EnumerateArray())
        {
            var sha = value.GetProperty("sha256").GetString()!; var mime = value.GetProperty("mimeType").GetString()!;
            var extension = mime switch { "image/png" => "png", "image/webp" => "webp",
                "application/gzip" => "reviewfault-ink.gz", _ => "jpg" };
            await ExecuteAsync(connection, transaction, """
                INSERT OR IGNORE INTO media (id, sha256, mime_type, byte_count, relative_path, created_at)
                VALUES ($id, $sha, $mime, $bytes, $path, $created)
                """, ("$id", NewId()), ("$sha", sha), ("$mime", mime),
                ("$bytes", value.GetProperty("byteCount").GetInt64()), ("$path", Path.Combine("media", $"{sha}.{extension}")),
                ("$created", createdAt));
            var lookup = connection.CreateCommand(); lookup.Transaction = (SqliteTransaction)transaction;
            lookup.CommandText = "SELECT id FROM media WHERE sha256 = $sha"; lookup.Parameters.AddWithValue("$sha", sha);
            var mediaId = (string)(await lookup.ExecuteScalarAsync())!;
            await ExecuteAsync(connection, transaction, """
                INSERT OR IGNORE INTO math_problem_media (math_problem_id, media_id, role, sort_order)
                VALUES ($problem, $media, 'prompt', $sort)
                """, ("$problem", problemId), ("$media", mediaId), ("$sort", index++));
        }
    }

    private static async Task ApplyRemoteMemoryCardAsync(
        SqliteConnection connection, System.Data.Common.DbTransaction transaction, PulledOperation operation)
    {
        var fields = operation.ChangedFields;
        await ExecuteAsync(connection, transaction, """
            UPDATE memory_card SET
              template_type = COALESCE($template, template_type),
              prompt_markdown = COALESCE($prompt, prompt_markdown),
              answer_markdown = COALESCE($answer, answer_markdown),
              hints_json = COALESCE($hints, hints_json),
              answer_points_json = COALESCE($points, answer_points_json),
              occlusions_json = COALESCE($occlusions, occlusions_json)
            WHERE study_item_id = $id
            """, ("$template", OptionalText(fields, "templateType")),
            ("$prompt", OptionalText(fields, "promptMarkdown")),
            ("$answer", OptionalText(fields, "answerMarkdown")),
            ("$hints", OptionalRaw(fields, "hints")),
            ("$points", OptionalRaw(fields, "answerPoints")),
            ("$occlusions", OptionalRaw(fields, "occlusions")), ("$id", operation.EntityId));
    }

    private static async Task ApplyRemoteMathProblemAsync(
        SqliteConnection connection, System.Data.Common.DbTransaction transaction, PulledOperation operation)
    {
        var fields = operation.ChangedFields;
        await ExecuteAsync(connection, transaction, """
            UPDATE math_problem SET
              source_name = COALESCE($source, source_name),
              prompt_markdown = COALESCE($prompt, prompt_markdown),
              solution_markdown = COALESCE($solution, solution_markdown),
              wrong_step_markdown = COALESCE($wrong, wrong_step_markdown),
              key_hint_markdown = COALESCE($hint, key_hint_markdown),
              default_error_reason = COALESCE($reason, default_error_reason)
            WHERE study_item_id = $id
            """, ("$source", OptionalText(fields, "sourceName")),
            ("$prompt", OptionalText(fields, "promptMarkdown")),
            ("$solution", OptionalText(fields, "solutionMarkdown")),
            ("$wrong", OptionalText(fields, "wrongStepMarkdown")),
            ("$hint", OptionalText(fields, "keyHintMarkdown")),
            ("$reason", OptionalText(fields, "defaultErrorReason")), ("$id", operation.EntityId));
    }

    private static async Task ApplyRemoteTagAsync(
        SqliteConnection connection, System.Data.Common.DbTransaction transaction, PulledOperation operation)
    {
        var name = Text(operation.ChangedFields, "name");
        if (string.IsNullOrWhiteSpace(name)) return;
        await ExecuteAsync(connection, transaction, """
            INSERT OR IGNORE INTO tag (id, name, created_at, updated_at, deleted_at)
            VALUES ($id, $name, $now, $now, $deleted);
            UPDATE tag SET name = $name, updated_at = $now, deleted_at = $deleted
            WHERE id = $id;
            """, ("$id", operation.EntityId), ("$name", name), ("$now", operation.OccurredAt),
            ("$deleted", operation.Action == "delete" ? operation.OccurredAt : DBNull.Value));
    }

    private static async Task ApplyRemoteRelationAsync(
        SqliteConnection connection, System.Data.Common.DbTransaction transaction, PulledOperation operation)
    {
        var fields = operation.ChangedFields;
        if (Text(fields, "relationType") != "study_item_tag") return;
        var source = Text(fields, "sourceId"); var target = Text(fields, "targetId");
        if (source.Length == 0 || target.Length == 0 || operation.Action is not ("add" or "remove")) return;
        var observedAdds = fields.TryGetProperty("observedAdds", out var observed) &&
            observed.ValueKind == JsonValueKind.Array ? observed.GetRawText() : "[]";
        await ExecuteAsync(connection, transaction, """
            INSERT OR IGNORE INTO relation_operation (operation_id, relation_type, source_id,
              target_id, action, device_id, device_counter, observed_adds_json, occurred_at)
            VALUES ($operation, 'study_item_tag', $source, $target, $action, $device,
              $counter, $observed, $occurred)
            """, ("$operation", operation.OperationId), ("$source", source), ("$target", target),
            ("$action", operation.Action), ("$device", operation.DeviceId),
            ("$counter", operation.DeviceCounter), ("$observed", observedAdds),
            ("$occurred", operation.OccurredAt));
        var active = connection.CreateCommand(); active.Transaction = (SqliteTransaction)transaction;
        active.CommandText = """
            SELECT EXISTS (
              SELECT 1 FROM relation_operation add_op
              WHERE add_op.relation_type = 'study_item_tag'
                AND add_op.source_id = $source AND add_op.target_id = $target
                AND add_op.action = 'add'
                AND NOT EXISTS (
                  SELECT 1 FROM relation_operation remove_op,
                    json_each(remove_op.observed_adds_json) observed
                  WHERE remove_op.relation_type = add_op.relation_type
                    AND remove_op.source_id = add_op.source_id
                    AND remove_op.target_id = add_op.target_id
                    AND remove_op.action = 'remove' AND observed.value = add_op.operation_id
                )
            )
            """;
        active.Parameters.AddWithValue("$source", source); active.Parameters.AddWithValue("$target", target);
        if (Convert.ToInt32(await active.ExecuteScalarAsync()) != 0)
            await ExecuteAsync(connection, transaction,
                """
                INSERT OR IGNORE INTO study_item_tag (study_item_id, tag_id)
                SELECT $source, $target
                WHERE EXISTS (SELECT 1 FROM study_item WHERE id = $source)
                  AND EXISTS (SELECT 1 FROM tag WHERE id = $target)
                """, ("$source", source), ("$target", target));
        else
            await ExecuteAsync(connection, transaction,
                "DELETE FROM study_item_tag WHERE study_item_id = $source AND tag_id = $target",
                ("$source", source), ("$target", target));
    }

    private static async Task ApplyRemoteLearningPreferencesAsync(
        SqliteConnection connection, System.Data.Common.DbTransaction transaction, PulledOperation operation)
    {
        var fields = operation.ChangedFields;
        await ExecuteAsync(connection, transaction, """
            UPDATE learning_preferences SET
              daily_new_memory_limit = COALESCE($limit, daily_new_memory_limit),
              session_minutes = COALESCE($minutes, session_minutes),
              memory_preset = COALESCE($memory, memory_preset),
              math_intensity = COALESCE($math, math_intensity),
              scheduler_generation = COALESCE($generation, scheduler_generation),
              updated_at = $now WHERE singleton = 1
            """, ("$limit", OptionalLong(fields, "dailyNewMemoryLimit")),
            ("$minutes", OptionalLong(fields, "sessionMinutes")),
            ("$memory", OptionalText(fields, "memoryPreset")),
            ("$math", OptionalText(fields, "mathIntensity")),
            ("$generation", OptionalLong(fields, "schedulerGeneration")),
            ("$now", operation.OccurredAt));
    }

    private static async Task ApplyRemoteAttemptArtifactAsync(
        SqliteConnection connection, System.Data.Common.DbTransaction transaction, PulledOperation operation)
    {
        var fields = operation.ChangedFields;
        var attemptId = Text(fields, "attemptId"); var studyItemId = Text(fields, "studyItemId");
        var artifactType = Text(fields, "artifactType"); var sha = Text(fields, "mediaSha256");
        var mime = Text(fields, "mediaMimeType"); var byteCount = Long(fields, "mediaByteCount", 0);
        var attemptResult = Text(fields, "result", "effortful");
        var errorReason = Text(fields, "errorReason");
        if (attemptId.Length == 0 || studyItemId.Length == 0 || sha.Length != 64 ||
            mime.Length == 0 || byteCount <= 0 ||
            artifactType is not ("reviewfault-ink-v1" or "png-preview" or "annotated-image") ||
            attemptResult is not ("again" or "wrong" or "effortful" or "fluent") ||
            errorReason is not ("" or "concept" or "approach" or "calculation" or "misread" or
                "forgotten_fact" or "timeout" or "other")) return;
        await ExecuteAsync(connection, transaction, """
            INSERT OR IGNORE INTO attempt (id, math_problem_id, started_at, finished_at,
              result, error_reason, created_at)
            SELECT $attempt, $item, $started, $finished, $result, $reason, $created
            WHERE EXISTS (SELECT 1 FROM math_problem WHERE study_item_id = $item);
            INSERT OR IGNORE INTO media (id, sha256, mime_type, byte_count, relative_path, created_at)
            VALUES ($media, $sha, $mime, $bytes, $path, $created);
            """, ("$attempt", attemptId), ("$item", studyItemId),
            ("$started", Long(fields, "startedAt", operation.OccurredAt)),
            ("$finished", OptionalLong(fields, "finishedAt")),
            ("$result", attemptResult), ("$reason", errorReason.Length == 0 ? DBNull.Value : errorReason),
            ("$created", operation.OccurredAt),
            ("$media", NewId()), ("$sha", sha), ("$mime", mime), ("$bytes", byteCount),
            ("$path", Path.Combine("media", sha + MediaExtension(mime))));
        var lookup = connection.CreateCommand(); lookup.Transaction = (SqliteTransaction)transaction;
        lookup.CommandText = "SELECT id FROM media WHERE sha256 = $sha"; lookup.Parameters.AddWithValue("$sha", sha);
        var mediaId = (string?)(await lookup.ExecuteScalarAsync());
        if (mediaId is null) return;
        await ExecuteAsync(connection, transaction, """
            INSERT OR IGNORE INTO attempt_artifact (id, attempt_id, artifact_type, media_id,
              background_media_sha256, page_count, created_at)
            SELECT $id, $attempt, $type, $media, $background, $pages, $created
            WHERE EXISTS (SELECT 1 FROM attempt WHERE id = $attempt)
            """, ("$id", operation.EntityId), ("$attempt", attemptId), ("$type", artifactType),
            ("$media", mediaId), ("$background", OptionalText(fields, "backgroundMediaSha256")),
            ("$pages", Math.Max(1, Long(fields, "pageCount", 1))), ("$created", operation.OccurredAt));
    }

    private static string MediaExtension(string mime) => mime switch {
        "image/png" => ".png", "image/webp" => ".webp",
        "application/gzip" => ".reviewfault-ink.gz", _ => ".jpg",
    };

    private static async Task ApplyRemoteReviewActionAsync(
        SqliteConnection connection, System.Data.Common.DbTransaction transaction, PulledOperation operation)
    {
        var fields = operation.ChangedFields; var itemId = fields.GetProperty("studyItemId").GetString()!;
        var lookup = connection.CreateCommand(); lookup.Transaction = (SqliteTransaction)transaction;
        lookup.CommandText = "SELECT COUNT(*) FROM study_item WHERE id = $id"; lookup.Parameters.AddWithValue("$id", itemId);
        if (Convert.ToInt32(await lookup.ExecuteScalarAsync()) == 0) return;
        await ExecuteAsync(connection, transaction, """
            INSERT OR IGNORE INTO review_action_v4 (action_id, study_item_id, algorithm, feedback,
              reviewed_at, duration_seconds, error_reason, hint_revealed, device_id, device_counter,
              causal_cursor, source_generation, created_at)
            VALUES ($action, $item, $algorithm, $feedback, $reviewed, $duration, $reason,
              $hint, $device, $counter, $cursor, 4, $created);
            UPDATE schedule_cache_v4 SET dirty = 1 WHERE study_item_id = $item;
            """, ("$action", operation.EntityId), ("$item", itemId),
            ("$algorithm", fields.GetProperty("algorithm").GetString()!),
            ("$feedback", fields.GetProperty("feedback").GetInt32()),
            ("$reviewed", fields.GetProperty("reviewedAt").GetInt64()),
            ("$duration", Long(fields, "durationSeconds", 0)), ("$reason", OptionalText(fields, "errorReason")),
            ("$hint", fields.TryGetProperty("hintRevealed", out var hint) && hint.GetBoolean() ? 1 : 0),
            ("$device", operation.DeviceId), ("$counter", operation.DeviceCounter),
            ("$cursor", operation.ServerSeq), ("$created", operation.OccurredAt));
    }

    private static async Task ApplyRemoteLearningEvidenceAsync(
        SqliteConnection connection, System.Data.Common.DbTransaction transaction, PulledOperation operation)
    {
        var fields = operation.ChangedFields; var taskId = Text(fields, "taskId");
        if (string.IsNullOrEmpty(taskId)) return;
        await ExecuteAsync(connection, transaction, """
            INSERT OR IGNORE INTO learning_evidence_v5 (evidence_id, learning_task_id, task_type,
              reviewed_at, correct, error_mask, point_hits, point_count, hint_level, answer_revealed,
              duration_seconds, duration_reliable, confidence, reflection_markdown, artifact_id,
              device_id, device_counter, causal_cursor, created_at)
            SELECT $id, $task, $type, $reviewed, $correct, $mask, $hits, $count, $hint, $answer,
              $duration, $reliable, $confidence, $reflection, $artifact, $device, $counter, $cursor, $created
            WHERE EXISTS (SELECT 1 FROM learning_task_v5 WHERE id = $task)
            """, ("$id", operation.EntityId), ("$task", taskId),
            ("$type", Text(fields, "taskType", "memory_recall")),
            ("$reviewed", Long(fields, "reviewedAt", operation.OccurredAt)),
            ("$correct", Long(fields, "correct", 0) != 0 ? 1 : 0),
            ("$mask", Long(fields, "errorMask", 0)), ("$hits", OptionalLong(fields, "pointHits")),
            ("$count", OptionalLong(fields, "pointCount")), ("$hint", Long(fields, "hintLevel", 0)),
            ("$answer", Long(fields, "answerRevealed", 0) != 0 ? 1 : 0),
            ("$duration", OptionalLong(fields, "durationSeconds")),
            ("$reliable", Long(fields, "durationReliable", 1) != 0 ? 1 : 0),
            ("$confidence", OptionalLong(fields, "confidence")), ("$reflection", Text(fields, "reflection")),
            ("$artifact", OptionalText(fields, "artifactId")), ("$device", operation.DeviceId),
            ("$counter", operation.DeviceCounter), ("$cursor", operation.ServerSeq), ("$created", operation.OccurredAt));
    }

    private static async Task ApplyRemoteCardProfileAsync(
        SqliteConnection connection, System.Data.Common.DbTransaction transaction, PulledOperation operation)
    {
        var fields = operation.ChangedFields;
        await ExecuteAsync(connection, transaction, """
            INSERT INTO card_profile_v5 (study_item_id, archetype, knowledge_point, source_type,
              source_title, source_chapter, source_locator, source_year, mechanism_markdown,
              conditions_markdown, contrast_markdown, example_markdown, common_trap_markdown,
              transfer_prompt_markdown, mnemonic, first_attempt_markdown, error_trigger_markdown,
              general_method_markdown, verification_markdown, target_seconds,
              structured_payload_json, created_at, updated_at)
            SELECT $id, $archetype, $knowledge, $sourceType, $sourceTitle, $sourceChapter,
              $sourceLocator, $sourceYear, $mechanism, $conditions, $contrast, $example,
              $trap, $transfer, $mnemonic, $firstAttempt, $errorTrigger, $method,
              $verification, $target, $payload, $now, $now
            WHERE EXISTS (SELECT 1 FROM study_item WHERE id = $id)
            ON CONFLICT(study_item_id) DO UPDATE SET archetype = excluded.archetype,
              knowledge_point = excluded.knowledge_point, source_type = excluded.source_type,
              source_title = excluded.source_title, source_chapter = excluded.source_chapter,
              source_locator = excluded.source_locator, source_year = excluded.source_year,
              mechanism_markdown = excluded.mechanism_markdown,
              conditions_markdown = excluded.conditions_markdown,
              contrast_markdown = excluded.contrast_markdown,
              example_markdown = excluded.example_markdown,
              common_trap_markdown = excluded.common_trap_markdown,
              transfer_prompt_markdown = excluded.transfer_prompt_markdown,
              mnemonic = excluded.mnemonic, first_attempt_markdown = excluded.first_attempt_markdown,
              error_trigger_markdown = excluded.error_trigger_markdown,
              general_method_markdown = excluded.general_method_markdown,
              verification_markdown = excluded.verification_markdown,
              target_seconds = excluded.target_seconds,
              structured_payload_json = excluded.structured_payload_json,
              updated_at = excluded.updated_at
            """, ("$id", operation.EntityId), ("$archetype", Text(fields, "archetype", "qa")),
            ("$knowledge", Text(fields, "knowledgePoint")), ("$sourceType", Text(fields, "sourceType", "notes")),
            ("$sourceTitle", Text(fields, "sourceTitle")), ("$sourceChapter", Text(fields, "sourceChapter")),
            ("$sourceLocator", Text(fields, "sourceLocator")), ("$sourceYear", OptionalLong(fields, "sourceYear")),
            ("$mechanism", Text(fields, "mechanism")), ("$conditions", Text(fields, "conditions")),
            ("$contrast", Text(fields, "contrast")), ("$example", Text(fields, "example")),
            ("$trap", Text(fields, "commonTrap")), ("$transfer", Text(fields, "transferPrompt")),
            ("$mnemonic", Text(fields, "mnemonic")), ("$firstAttempt", Text(fields, "firstAttempt")),
            ("$errorTrigger", Text(fields, "errorTrigger")), ("$method", Text(fields, "generalMethod")),
            ("$verification", Text(fields, "verification")), ("$target", OptionalLong(fields, "targetSeconds")),
            ("$payload", Raw(fields, "structuredPayload", "{}")), ("$now", operation.OccurredAt));
    }

    private static string Text(JsonElement value, string name, string fallback = "") =>
        value.TryGetProperty(name, out var property) && property.ValueKind == JsonValueKind.String
            ? property.GetString() ?? fallback : fallback;
    private static object OptionalText(JsonElement value, string name) =>
        value.TryGetProperty(name, out var property) && property.ValueKind == JsonValueKind.String
            ? property.GetString()! : DBNull.Value;
    private static object OptionalRaw(JsonElement value, string name) =>
        value.TryGetProperty(name, out var property) ? property.GetRawText() : DBNull.Value;
    private static object OptionalLong(JsonElement value, string name) =>
        value.TryGetProperty(name, out var property) && property.TryGetInt64(out var result)
            ? result : DBNull.Value;
    private static long Long(JsonElement value, string name, long fallback) =>
        value.TryGetProperty(name, out var property) && property.TryGetInt64(out var result) ? result : fallback;
    private static string Raw(JsonElement value, string name, string fallback) =>
        value.TryGetProperty(name, out var property) ? property.GetRawText() : fallback;

    public async Task<DashboardSummary> DashboardAsync(long now, long dayStart)
    {
        await using var connection = await OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = """
            SELECT
              COALESCE(SUM(CASE WHEN s.scheduler_state <> 0 AND s.due_at < $dayStart THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN s.scheduler_state <> 0 AND s.due_at BETWEEN $dayStart AND $now THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN s.scheduler_state = 0 AND s.kind = 'memory_card' THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN s.scheduler_state = 0 AND s.kind = 'math_problem' THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN s.scheduler_state <> 0 AND s.due_at <= $now
                THEN CASE WHEN s.kind = 'math_problem' THEN 480 ELSE 45 END ELSE 0 END), 0),
              lp.daily_new_memory_limit,
              ((SELECT COUNT(*) FROM review_event_v2 e
               JOIN memory_review_event_v2 mr ON mr.review_event_id = e.id
               WHERE mr.state_before = 0 AND e.reviewed_at >= $dayStart) +
               (SELECT COUNT(*) FROM review_event_v3 e
                JOIN memory_review_event_v3 mr ON mr.review_event_id = e.id
                WHERE mr.state_before = 0 AND e.reviewed_at >= $dayStart)),
              lp.session_minutes,
              COALESCE(SUM(CASE WHEN s.scheduler_state <> 0
                AND s.due_at >= $tomorrowStart AND s.due_at < $tomorrowEnd THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN s.scheduler_state <> 0
                AND s.due_at > $now AND s.due_at <= $weekEnd THEN 1 ELSE 0 END), 0)
            FROM learning_preferences lp
            LEFT JOIN study_item s ON s.suspended_at IS NULL AND s.deleted_at IS NULL AND (
              (s.kind = 'math_problem' AND lp.include_math_problems = 1) OR
              (s.kind = 'memory_card' AND lp.include_memory_cards = 1 AND (
                (s.subject = 'data_structures' AND lp.enable_data_structures = 1) OR
                (s.subject = 'computer_organization' AND lp.enable_computer_organization = 1) OR
                (s.subject = 'operating_systems' AND lp.enable_operating_systems = 1) OR
                (s.subject = 'computer_networks' AND lp.enable_computer_networks = 1))))
            WHERE lp.singleton = 1
            """;
        command.Parameters.AddWithValue("$dayStart", dayStart);
        command.Parameters.AddWithValue("$now", now);
        command.Parameters.AddWithValue("$tomorrowStart", dayStart + 86_400);
        command.Parameters.AddWithValue("$tomorrowEnd", dayStart + 2 * 86_400);
        command.Parameters.AddWithValue("$weekEnd", now + 7 * 86_400);
        await using var reader = await command.ExecuteReaderAsync();
        await reader.ReadAsync();
        var remainingNewMemory = Math.Max(0, reader.GetInt32(5) - reader.GetInt32(6));
        var dueSeconds = reader.GetInt32(4);
        var budgetSeconds = reader.GetInt32(7) * 60;
        var remainingSeconds = Math.Max(0, budgetSeconds - dueSeconds);
        var newMemory = 0;
        var newMath = 0;
        if (dueSeconds <= budgetSeconds)
        {
            newMemory = Math.Min(Math.Min(reader.GetInt32(2), remainingNewMemory),
                remainingSeconds / 45);
            remainingSeconds -= newMemory * 45;
            newMath = Math.Min(reader.GetInt32(3), remainingSeconds / 480);
        }
        if (dueSeconds == 0 && newMemory == 0 && newMath == 0)
        {
            if (reader.GetInt32(2) > 0 && remainingNewMemory > 0) newMemory = 1;
            else if (reader.GetInt32(3) > 0) newMath = 1;
        }
        var focusSeconds = Math.Min(dueSeconds, budgetSeconds) +
            newMemory * 45 + newMath * 480;
        return new DashboardSummary(reader.GetInt32(0), reader.GetInt32(1),
            newMemory + newMath,
            (focusSeconds + 59) / 60,
            (Math.Max(0, dueSeconds - budgetSeconds) + 59) / 60,
            reader.GetInt32(8), reader.GetInt32(9));
    }

    public async Task<InsightsSnapshot> InsightsAsync(long now, long dayStart)
    {
        await using var connection = await OpenAsync();
        var historyStart = dayStart - 6 * 86_400L;
        var reviewByDay = new Dictionary<int, int>();
        await using (var command = connection.CreateCommand())
        {
            command.CommandText = """
                WITH events AS (
                  SELECT reviewed_at FROM review_event_v2
                  UNION ALL SELECT reviewed_at FROM review_event_v3
                )
                SELECT CAST((reviewed_at - $historyStart) / 86400 AS INTEGER), COUNT(*)
                FROM events WHERE reviewed_at >= $historyStart AND reviewed_at < $historyEnd
                GROUP BY 1
                """;
            command.Parameters.AddWithValue("$historyStart", historyStart);
            command.Parameters.AddWithValue("$historyEnd", dayStart + 86_400L);
            await using var reader = await command.ExecuteReaderAsync();
            while (await reader.ReadAsync()) reviewByDay[reader.GetInt32(0)] = reader.GetInt32(1);
        }
        var dueByDay = new Dictionary<int, int>();
        await using (var command = connection.CreateCommand())
        {
            command.CommandText = """
                SELECT CAST((due_at - $dayStart) / 86400 AS INTEGER), COUNT(*)
                FROM study_item
                WHERE deleted_at IS NULL AND suspended_at IS NULL AND scheduler_state <> 0
                  AND due_at >= $dayStart AND due_at < $weekEnd
                GROUP BY 1
                """;
            command.Parameters.AddWithValue("$dayStart", dayStart);
            command.Parameters.AddWithValue("$weekEnd", dayStart + 7 * 86_400L);
            await using var reader = await command.ExecuteReaderAsync();
            while (await reader.ReadAsync()) dueByDay[reader.GetInt32(0)] = reader.GetInt32(1);
        }
        var today = DateOnly.FromDateTime(DateTimeOffset.FromUnixTimeSeconds(dayStart).LocalDateTime);
        var culture = System.Globalization.CultureInfo.GetCultureInfo("zh-CN");
        var days = Enumerable.Range(0, 7).Select(index => new InsightDay(
            culture.DateTimeFormat.GetShortestDayName(today.AddDays(index - 6).DayOfWeek),
            culture.DateTimeFormat.GetShortestDayName(today.AddDays(index).DayOfWeek),
            reviewByDay.GetValueOrDefault(index), dueByDay.GetValueOrDefault(index))).ToArray();

        var totalReviews = 0; var recentReviews = 0; var recentSuccessful = 0;
        await using (var command = connection.CreateCommand())
        {
            command.CommandText = """
                WITH events AS (
                  SELECT algorithm, feedback, reviewed_at FROM review_event_v2
                  UNION ALL SELECT algorithm, feedback, reviewed_at FROM review_event_v3
                )
                SELECT COUNT(*),
                  COALESCE(SUM(CASE WHEN reviewed_at >= $historyStart THEN 1 ELSE 0 END), 0),
                  COALESCE(SUM(CASE WHEN reviewed_at >= $historyStart AND (
                    (algorithm = 'memory_fsrs_6' AND feedback >= 3) OR
                    (algorithm = 'math_mastery_ladder' AND feedback >= 2)
                  ) THEN 1 ELSE 0 END), 0)
                FROM events
                """;
            command.Parameters.AddWithValue("$historyStart", historyStart);
            await using var reader = await command.ExecuteReaderAsync();
            await reader.ReadAsync();
            totalReviews = reader.GetInt32(0); recentReviews = reader.GetInt32(1);
            recentSuccessful = reader.GetInt32(2);
        }
        var reviewedDates = new HashSet<DateOnly>();
        await using (var command = connection.CreateCommand())
        {
            command.CommandText = """
                SELECT reviewed_at FROM review_event_v2 WHERE reviewed_at >= $start
                UNION ALL SELECT reviewed_at FROM review_event_v3 WHERE reviewed_at >= $start
                """;
            command.Parameters.AddWithValue("$start", dayStart - 366 * 86_400L);
            await using var reader = await command.ExecuteReaderAsync();
            while (await reader.ReadAsync()) reviewedDates.Add(DateOnly.FromDateTime(
                DateTimeOffset.FromUnixTimeSeconds(reader.GetInt64(0)).LocalDateTime));
        }
        var streakDate = today;
        if (!reviewedDates.Contains(streakDate)) streakDate = streakDate.AddDays(-1);
        var streakDays = 0;
        while (reviewedDates.Contains(streakDate)) { streakDays++; streakDate = streakDate.AddDays(-1); }

        var activeItems = 0; var masteredItems = 0;
        await using (var command = connection.CreateCommand())
        {
            command.CommandText = """
                SELECT COUNT(*), COALESCE(SUM(CASE WHEN repetitions >= 3 AND stability_days >= 14 THEN 1 ELSE 0 END), 0)
                FROM study_item WHERE deleted_at IS NULL AND suspended_at IS NULL
                """;
            await using var reader = await command.ExecuteReaderAsync(); await reader.ReadAsync();
            activeItems = reader.GetInt32(0); masteredItems = reader.GetInt32(1);
        }
        var subjects = new List<SubjectInsight>();
        await using (var command = connection.CreateCommand())
        {
            command.CommandText = """
                SELECT subject, COUNT(*),
                  COALESCE(SUM(CASE WHEN repetitions >= 3 AND stability_days >= 14 THEN 1 ELSE 0 END), 0)
                FROM study_item WHERE deleted_at IS NULL AND suspended_at IS NULL
                GROUP BY subject ORDER BY COUNT(*) DESC
                """;
            await using var reader = await command.ExecuteReaderAsync();
            while (await reader.ReadAsync()) subjects.Add(new SubjectInsight(
                reader.GetString(0), reader.GetInt32(1), reader.GetInt32(2)));
        }
        return new InsightsSnapshot(
            reviewByDay.GetValueOrDefault(6),
            recentReviews == 0 ? 0 : recentSuccessful * 100 / recentReviews,
            streakDays, totalReviews, activeItems, masteredItems, days, subjects);
    }

    public async Task<StudyRow?> NextForReviewAsync(
        long now,
        bool includeNewItems = true,
        IReadOnlyCollection<string>? excludedItemIds = null)
    {
        excludedItemIds ??= Array.Empty<string>();
        if (excludedItemIds.Any(id => string.IsNullOrEmpty(id) || id.Contains('|')))
            throw new ArgumentException("会话排除项包含非法 ID", nameof(excludedItemIds));
        var encodedExcludedItemIds = excludedItemIds.Count == 0
            ? ""
            : "|" + string.Join('|', excludedItemIds.OrderBy(id => id, StringComparer.Ordinal)) + "|";
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
                   CASE WHEN s.kind = 'math_problem' AND COALESCE(p.key_hint_markdown, '') <> ''
                          THEN json_array(p.key_hint_markdown)
                        WHEN m.template_type = 'layered_hint' THEN m.hints_json
                        WHEN m.template_type = 'enumeration' THEN m.answer_points_json
                        ELSE '[]' END,
                   COALESCE(m.hints_json, '[]'), COALESCE(m.answer_points_json, '[]'),
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
            FROM study_item s
            LEFT JOIN memory_card m ON m.study_item_id = s.id
            LEFT JOIN math_problem p ON p.study_item_id = s.id
            LEFT JOIN card_profile_v5 cp ON cp.study_item_id = s.id
            LEFT JOIN math_problem_media pm
              ON pm.math_problem_id = s.id AND pm.role = 'prompt' AND pm.sort_order = 0
            LEFT JOIN media ON media.id = pm.media_id
            CROSS JOIN learning_preferences lp
            JOIN learning_task_v5 task ON task.id = (
              SELECT candidate.id FROM learning_task_v5 candidate
              WHERE candidate.source_study_item_id = s.id
                AND candidate.task_state IN ('active', 'legacy')
                AND candidate.dependency_ready = 1
              ORDER BY CASE candidate.task_type WHEN 'math_repair' THEN 0 ELSE 1 END,
                candidate.due_at, candidate.id
              LIMIT 1
            )
            WHERE s.suspended_at IS NULL AND s.deleted_at IS NULL
              AND lp.singleton = 1
              AND ($excludedItemIds = '' OR instr($excludedItemIds, '|' || s.id || '|') = 0)
              AND ((s.kind = 'math_problem' AND lp.include_math_problems = 1) OR
                (s.kind = 'memory_card' AND lp.include_memory_cards = 1 AND (
                  (s.subject = 'data_structures' AND lp.enable_data_structures = 1) OR
                  (s.subject = 'computer_organization' AND lp.enable_computer_organization = 1) OR
                  (s.subject = 'operating_systems' AND lp.enable_operating_systems = 1) OR
                  (s.subject = 'computer_networks' AND lp.enable_computer_networks = 1))))
              AND task.due_at <= $now
              AND ($includeNewItems = 1 OR task.repetitions > 0 OR task.task_state = 'legacy')
              AND (substr(task.task_type, 1, 7) <> 'memory_' OR task.repetitions > 0 OR task.task_state = 'legacy' OR (
                SELECT
                  (SELECT COUNT(*) FROM review_event_v2 e
                   JOIN memory_review_event_v2 mr ON mr.review_event_id = e.id
                   WHERE mr.state_before = 0 AND e.reviewed_at >= $dayStart) +
                  (SELECT COUNT(*) FROM review_event_v3 e
                   JOIN memory_review_event_v3 mr ON mr.review_event_id = e.id
                   WHERE mr.state_before = 0 AND e.reviewed_at >= $dayStart)
              ) < lp.daily_new_memory_limit)
            ORDER BY
              CASE WHEN task.repetitions = 0 AND task.task_state <> 'legacy' THEN 1 ELSE 0 END,
              CASE task.task_type WHEN 'math_repair' THEN 0 ELSE 1 END,
              CASE WHEN task.due_at < $dayStart THEN 0 ELSE 1 END,
              task.due_at, task.created_at
            LIMIT 1
            """;
        command.Parameters.AddWithValue("$now", now);
        command.Parameters.AddWithValue("$dayStart", dayStart);
        command.Parameters.AddWithValue("$includeNewItems", includeNewItems ? 1 : 0);
        command.Parameters.AddWithValue("$excludedItemIds", encodedExcludedItemIds);
        await using var reader = await command.ExecuteReaderAsync();
        if (!await reader.ReadAsync()) return null;
        return ReadStudyRow(reader);
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
                   CASE WHEN s.kind = 'math_problem' AND COALESCE(p.key_hint_markdown, '') <> ''
                          THEN json_array(p.key_hint_markdown)
                        WHEN m.template_type = 'layered_hint' THEN m.hints_json
                        WHEN m.template_type = 'enumeration' THEN m.answer_points_json
                        ELSE '[]' END,
                   COALESCE(m.hints_json, '[]'), COALESCE(m.answer_points_json, '[]'),
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
            FROM study_item s
            LEFT JOIN memory_card m ON m.study_item_id = s.id
            LEFT JOIN math_problem p ON p.study_item_id = s.id
            LEFT JOIN card_profile_v5 cp ON cp.study_item_id = s.id
            LEFT JOIN math_problem_media pm
              ON pm.math_problem_id = s.id AND pm.role = 'prompt' AND pm.sort_order = 0
            LEFT JOIN media ON media.id = pm.media_id
            WHERE s.deleted_at IS NULL AND (
              $query = '' OR COALESCE(m.prompt_markdown, p.prompt_markdown, '') LIKE $pattern ESCAPE '\'
              OR COALESCE(m.answer_markdown, p.solution_markdown, '') LIKE $pattern ESCAPE '\'
              OR COALESCE(p.source_name, '') LIKE $pattern ESCAPE '\'
              OR COALESCE(cp.knowledge_point, '') LIKE $pattern ESCAPE '\'
              OR COALESCE(cp.source_title, '') LIKE $pattern ESCAPE '\'
              OR COALESCE(cp.source_chapter, '') LIKE $pattern ESCAPE '\'
              OR COALESCE(cp.source_locator, '') LIKE $pattern ESCAPE '\'
              OR EXISTS (SELECT 1 FROM study_item_tag qit JOIN tag qt ON qt.id = qit.tag_id
                WHERE qit.study_item_id = s.id AND qt.name LIKE $pattern ESCAPE '\'))
            ORDER BY s.updated_at DESC LIMIT 100
            """;
        command.Parameters.AddWithValue("$query", query.Trim());
        command.Parameters.AddWithValue("$pattern", "%" + query.Trim()
            .Replace("\\", "\\\\").Replace("%", "\\%").Replace("_", "\\_") + "%");
        var rows = new List<StudyRow>();
        await using var reader = await command.ExecuteReaderAsync();
        while (await reader.ReadAsync())
        {
            rows.Add(ReadStudyRow(reader));
        }
        return rows;
    }

    private static StudyRow ReadStudyRow(SqliteDataReader reader) => new(
        reader.GetString(0), reader.GetString(1), reader.GetString(2),
        (CardState)reader.GetInt32(3), reader.GetDouble(4), reader.GetDouble(5),
        reader.GetInt64(6), reader.GetInt64(7), checked((uint)reader.GetInt64(8)),
        checked((uint)reader.GetInt64(9)), reader.GetString(10), reader.GetString(11),
        reader.IsDBNull(12) ? null : reader.GetString(12), reader.GetString(13), reader.GetString(14))
    {
        HintsJson = reader.GetString(15),
        AnswerPointsJson = reader.GetString(16),
        Profile = new CardProfile(
            reader.GetString(17), reader.GetString(18), reader.GetString(19), reader.GetString(20),
            reader.GetString(21), reader.GetString(22), reader.IsDBNull(23) ? null : reader.GetInt32(23),
            reader.GetString(24), reader.GetString(25), reader.GetString(26), reader.GetString(27),
            reader.GetString(28), reader.GetString(29), reader.GetString(30), reader.GetString(31),
            reader.GetString(32), reader.GetString(33), reader.GetString(34),
            reader.IsDBNull(35) ? null : reader.GetInt32(35), reader.GetString(36)),
        TagsJson = reader.GetString(37),
    };

    public async Task<LearningPreferences> GetLearningPreferencesAsync()
    {
        await using var connection = await OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = """
            SELECT daily_new_memory_limit, session_minutes, memory_preset,
                   math_intensity, include_memory_cards, include_math_problems,
                   scheduler_generation
            FROM learning_preferences WHERE singleton = 1
            """;
        await using var reader = await command.ExecuteReaderAsync();
        if (!await reader.ReadAsync()) throw new InvalidOperationException("学习设置不存在");
        return new LearningPreferences(reader.GetInt32(0), reader.GetInt32(1),
            reader.GetString(2), reader.GetString(3), reader.GetInt32(4) != 0,
            reader.GetInt32(5) != 0, reader.GetInt32(6));
    }

    public async Task SaveLearningPreferencesAsync(LearningPreferences value)
    {
        if (value.DailyNewMemoryLimit is < 0 or > 500 || value.SessionMinutes is < 1 or > 240 ||
            (!value.IncludeMemoryCards && !value.IncludeMathProblems) ||
            value.SchedulerGeneration is < 2 or > 3)
            throw new ArgumentException("学习设置超出范围");
        await using var connection = await OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = """
            UPDATE learning_preferences SET daily_new_memory_limit = $limit,
              session_minutes = $minutes, memory_preset = $memory,
              math_intensity = $math, include_memory_cards = $memoryEnabled,
              include_math_problems = $mathEnabled, scheduler_generation = $generation,
              updated_at = $now
            WHERE singleton = 1
            """;
        command.Parameters.AddWithValue("$limit", value.DailyNewMemoryLimit);
        command.Parameters.AddWithValue("$minutes", value.SessionMinutes);
        command.Parameters.AddWithValue("$memory", value.MemoryPreset);
        command.Parameters.AddWithValue("$math", value.MathIntensity);
        command.Parameters.AddWithValue("$memoryEnabled", value.IncludeMemoryCards ? 1 : 0);
        command.Parameters.AddWithValue("$mathEnabled", value.IncludeMathProblems ? 1 : 0);
        command.Parameters.AddWithValue("$generation", value.SchedulerGeneration);
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
        {
            await ExecuteAsync(connection, transaction,
                "UPDATE study_item SET deleted_at = $now, updated_at = $now WHERE id = $id AND deleted_at IS NULL",
                ("$now", now), ("$id", id));
        }
        await transaction.CommitAsync();
        return new DeletionState(itemIds, now, now + 10);
    }

    public async Task RestoreAsync(IReadOnlyList<string> itemIds)
    {
        await using var connection = await OpenAsync();
        await using var transaction = await connection.BeginTransactionAsync();
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        foreach (var id in itemIds)
        {
            await ExecuteAsync(connection, transaction,
                "UPDATE study_item SET deleted_at = NULL, updated_at = $now WHERE id = $id",
                ("$now", now), ("$id", id));
        }
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
        await ReplaceTagsInTransactionAsync(connection, transaction, itemId, names,
            DateTimeOffset.UtcNow.ToUnixTimeSeconds());
        await transaction.CommitAsync();
    }

    private static async Task ReplaceTagsInTransactionAsync(
        SqliteConnection connection, System.Data.Common.DbTransaction transaction,
        string itemId, IReadOnlyList<string> names, long now)
    {
        await ExecuteAsync(connection, transaction, "DELETE FROM study_item_tag WHERE study_item_id = $id",
            ("$id", itemId));
        foreach (var name in names.Select(value => value.Trim()).Where(value => value.Length > 0)
                     .Distinct(StringComparer.OrdinalIgnoreCase).Take(30))
        {
            if (name.Length > 60) throw new ArgumentException("单个标签不能超过 60 个字符");
            var tagId = NewId();
            await ExecuteAsync(connection, transaction, """
                INSERT INTO tag (id, name, created_at, updated_at) VALUES ($id, $name, $now, $now)
                ON CONFLICT(name) DO UPDATE SET deleted_at = NULL, updated_at = excluded.updated_at
                """, ("$id", tagId), ("$name", name), ("$now", now));
            var lookup = connection.CreateCommand();
            lookup.Transaction = (SqliteTransaction)transaction;
            lookup.CommandText = "SELECT id FROM tag WHERE name = $name COLLATE NOCASE";
            lookup.Parameters.AddWithValue("$name", name);
            tagId = (string)(await lookup.ExecuteScalarAsync())!;
            await EnqueueSyncAsync(connection, transaction, "tag", tagId, "create", new { name }, now);
            await ExecuteAsync(connection, transaction,
                "INSERT INTO study_item_tag (study_item_id, tag_id) VALUES ($item, $tag)",
                ("$item", itemId), ("$tag", tagId));
        }
    }

    public Task<string> CreateMemoryCardAsync(
        string templateType,
        string subject,
        string prompt,
        string answer,
        IReadOnlyList<string> structuredLines) => CreateMemoryCardAsync(new MemoryCardDraft(
            templateType,
            templateType switch { "comparison" => "comparison", "enumeration" => "enumeration",
                "image_occlusion" => "diagram", "cloze" => "cloze", _ => "qa" },
            subject, "", prompt, answer,
            templateType == "layered_hint" ? structuredLines : Array.Empty<string>(),
            templateType == "enumeration" ? structuredLines : Array.Empty<string>()));

    public async Task<string> CreateMemoryCardAsync(MemoryCardDraft draft)
    {
        ValidateMemoryDraft(draft);
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
        item.Parameters.AddWithValue("$subject", draft.Subject);
        item.Parameters.AddWithValue("$now", now);
        await item.ExecuteNonQueryAsync();

        await InsertCardProfileAsync(connection, transaction, id, new CardProfile(
            draft.Archetype, draft.KnowledgePoint.Trim(), draft.SourceType, draft.SourceTitle.Trim(),
            draft.SourceChapter.Trim(), draft.SourceLocator.Trim(), draft.SourceYear,
            draft.Mechanism.Trim(), draft.Conditions.Trim(), draft.Contrast.Trim(), draft.Example.Trim(),
            draft.CommonTrap.Trim(), draft.TransferPrompt.Trim(), draft.Mnemonic.Trim(),
            StructuredPayload: string.IsNullOrWhiteSpace(draft.StructuredPayload) ? "{}" : draft.StructuredPayload), now);

        var detail = connection.CreateCommand();
        detail.Transaction = (SqliteTransaction)transaction;
        detail.CommandText = """
            INSERT INTO memory_card (
              study_item_id, template_type, prompt_markdown, answer_markdown,
              hints_json, answer_points_json
            ) VALUES ($id, $template, $prompt, $answer, $hints, $points)
            """;
        detail.Parameters.AddWithValue("$id", id);
        detail.Parameters.AddWithValue("$template", draft.TemplateType);
        detail.Parameters.AddWithValue("$prompt", draft.Prompt.Trim());
        detail.Parameters.AddWithValue("$answer", draft.Answer.Trim());
        detail.Parameters.AddWithValue("$hints", JsonSerializer.Serialize(draft.Hints));
        detail.Parameters.AddWithValue("$points", JsonSerializer.Serialize(draft.AnswerPoints));
        await detail.ExecuteNonQueryAsync();
        await ReplaceTagsInTransactionAsync(connection, transaction, id, draft.Tags ?? Array.Empty<string>(), now);
        await CreateLearningRouteAsync(connection, transaction, id, "memory_card", draft.Subject, now);
        await transaction.CommitAsync();
        return id;
    }

    private static void ValidateMemoryDraft(MemoryCardDraft draft)
    {
        string[] templates = ["qa", "cloze", "layered_hint", "enumeration", "image_occlusion", "comparison"];
        string[] archetypes = ["concept", "comparison", "process", "enumeration", "scale_mapping",
            "formula_rule", "diagram", "cloze", "qa"];
        string[] subjects = ["data_structures", "computer_organization", "operating_systems", "computer_networks"];
        string[] sourceTypes = ["textbook", "course", "past_exam", "practice", "notes", "other"];
        if (!templates.Contains(draft.TemplateType) || !archetypes.Contains(draft.Archetype) ||
            !subjects.Contains(draft.Subject) || !sourceTypes.Contains(draft.SourceType))
            throw new ArgumentException("卡片类型、科目或来源无效");
        if (string.IsNullOrWhiteSpace(draft.Prompt)) throw new ArgumentException("回忆问题不能为空");
        if (draft.SourceYear is < 1900 or > 2200) throw new ArgumentException("来源年份无效");
        using var payload = JsonDocument.Parse(string.IsNullOrWhiteSpace(draft.StructuredPayload) ? "{}" : draft.StructuredPayload);
        if (payload.RootElement.ValueKind is not (JsonValueKind.Object or JsonValueKind.Array))
            throw new ArgumentException("结构化字段格式无效");
        if (draft.TemplateType is "qa" or "comparison" && string.IsNullOrWhiteSpace(draft.Answer))
            throw new ArgumentException("核心答案不能为空");
        if (draft.TemplateType == "cloze" && !System.Text.RegularExpressions.Regex.IsMatch(
                draft.Prompt, @"\{\{c\d+::.+?}}"))
            throw new ArgumentException("填空题干缺少 {{c1::答案}} 标记");
        if (draft.TemplateType == "layered_hint" &&
            (string.IsNullOrWhiteSpace(draft.Answer) || draft.Hints.Count == 0))
            throw new ArgumentException("分层提示卡需要核心答案和提示");
        if (draft.TemplateType == "enumeration" && draft.AnswerPoints.Count < 2)
            throw new ArgumentException("枚举卡至少需要两个评分要点");
        if (draft.Archetype is "process" or "enumeration" or "scale_mapping" && draft.AnswerPoints.Count < 2)
            throw new ArgumentException("该知识形式至少需要两个可核对要点");
        if (draft.Archetype == "formula_rule" &&
            (string.IsNullOrWhiteSpace(draft.Answer) || string.IsNullOrWhiteSpace(draft.Conditions)))
            throw new ArgumentException("公式卡需要公式和适用条件");
    }

    private static Task InsertCardProfileAsync(
        SqliteConnection connection, System.Data.Common.DbTransaction transaction,
        string id, CardProfile profile, long now) => ExecuteAsync(connection, transaction, """
            INSERT INTO card_profile_v5 (study_item_id, archetype, knowledge_point, source_type,
              source_title, source_chapter, source_locator, source_year, mechanism_markdown,
              conditions_markdown, contrast_markdown, example_markdown, common_trap_markdown,
              transfer_prompt_markdown, mnemonic, first_attempt_markdown, error_trigger_markdown,
              general_method_markdown, verification_markdown, target_seconds,
              structured_payload_json, created_at, updated_at)
            VALUES ($id, $archetype, $knowledge, $sourceType, $sourceTitle, $sourceChapter,
              $sourceLocator, $sourceYear, $mechanism, $conditions, $contrast, $example,
              $trap, $transfer, $mnemonic, $firstAttempt, $errorTrigger, $method,
              $verification, $target, $payload, $now, $now)
            """, ("$id", id), ("$archetype", profile.Archetype), ("$knowledge", profile.KnowledgePoint),
            ("$sourceType", profile.SourceType), ("$sourceTitle", profile.SourceTitle),
            ("$sourceChapter", profile.SourceChapter), ("$sourceLocator", profile.SourceLocator),
            ("$sourceYear", (object?)profile.SourceYear ?? DBNull.Value), ("$mechanism", profile.Mechanism),
            ("$conditions", profile.Conditions), ("$contrast", profile.Contrast), ("$example", profile.Example),
            ("$trap", profile.CommonTrap), ("$transfer", profile.TransferPrompt), ("$mnemonic", profile.Mnemonic),
            ("$firstAttempt", profile.FirstAttempt), ("$errorTrigger", profile.ErrorTrigger),
            ("$method", profile.GeneralMethod), ("$verification", profile.Verification),
            ("$target", (object?)profile.TargetSeconds ?? DBNull.Value),
            ("$payload", profile.StructuredPayload), ("$now", now));

    public Task<string> CreateMathProblemAsync(string sourceFile, string sourceName) =>
        CreateMathProblemAsync(new[] { sourceFile }, sourceName);

    public Task<string> CreateMathProblemAsync(IReadOnlyList<string> sourceFiles, string sourceName) =>
        CreateMathProblemAsync(sourceFiles, new MathErrorDraft(SourceTitle: sourceName));

    public async Task<string> CreateMathProblemAsync(IReadOnlyList<string> sourceFiles, MathErrorDraft draft)
    {
        if (sourceFiles.Count is < 1 or > 5) throw new ArgumentException("每道题请选择 1–5 张图片");
        string[] sourceTypes = ["textbook", "course", "past_exam", "practice", "notes", "other"];
        string[] reasons = ["concept", "approach", "calculation", "misread", "forgotten_fact", "timeout", "other"];
        if (!sourceTypes.Contains(draft.SourceType) || draft.SourceYear is < 1900 or > 2200 ||
            draft.ErrorReason is not null && !reasons.Contains(draft.ErrorReason) ||
            draft.TargetSeconds is < 10 or > 7200)
            throw new ArgumentException("错题来源、年份、错因或目标用时无效");
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
        await InsertCardProfileAsync(connection, transaction, problemId, new CardProfile(
            "math_error", draft.KnowledgePoint.Trim(), draft.SourceType, draft.SourceTitle.Trim(),
            draft.SourceChapter.Trim(), draft.SourceLocator.Trim(), draft.SourceYear,
            FirstAttempt: draft.FirstAttempt.Trim(), ErrorTrigger: draft.ErrorTrigger.Trim(),
            GeneralMethod: draft.GeneralMethod.Trim(), Verification: draft.Verification.Trim(),
            TransferPrompt: draft.TransferPrompt.Trim(), TargetSeconds: draft.TargetSeconds), now);
        await ExecuteAsync(connection, transaction, """
            INSERT INTO math_problem (study_item_id, source_name, source_page, source_year,
              solution_markdown, wrong_step_markdown, key_hint_markdown, default_error_reason)
            VALUES ($id, $source, $locator, $year, $solution, $attempt, $hint, $reason)
            """, ("$id", problemId), ("$source", draft.SourceTitle.Trim()),
            ("$locator", draft.SourceLocator.Trim()), ("$year", (object?)draft.SourceYear ?? DBNull.Value),
            ("$solution", draft.Solution.Trim()), ("$attempt", draft.FirstAttempt.Trim()),
            ("$hint", draft.KeyHint.Trim()), ("$reason", (object?)draft.ErrorReason ?? DBNull.Value));
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
        await EnqueueSyncAsync(connection, transaction, "studyItem", problemId, "update", new {
            media = prepared.Select(item => new { sha256 = item.Hash, byteCount = item.ByteCount,
                mimeType = item.Extension switch { ".png" => "image/png", ".webp" => "image/webp", _ => "image/jpeg" } }),
        }, now);
        await ReplaceTagsInTransactionAsync(connection, transaction, problemId, draft.Tags ?? Array.Empty<string>(), now);
        await CreateLearningRouteAsync(connection, transaction, problemId, "math_problem", "math", now);
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

    public Task UpdateMathDetailsAsync(
        string id,
        string solution,
        string wrongStep,
        string keyHint,
        string? errorReason) => UpdateMathDetailsAsync(id, new MathErrorDraft(
            Solution: solution, FirstAttempt: wrongStep, KeyHint: keyHint, ErrorReason: errorReason));

    public async Task UpdateMathDetailsAsync(string id, MathErrorDraft draft)
    {
        await using var connection = await OpenAsync(); await using var transaction = await connection.BeginTransactionAsync();
        var command = connection.CreateCommand(); command.Transaction = (SqliteTransaction)transaction;
        command.CommandText = """
            UPDATE math_problem SET solution_markdown = $solution,
              wrong_step_markdown = $wrongStep, key_hint_markdown = $keyHint,
              default_error_reason = $reason, source_name = CASE WHEN $source = '' THEN source_name ELSE $source END,
              source_page = CASE WHEN $locator = '' THEN source_page ELSE $locator END,
              source_year = COALESCE($year, source_year)
            WHERE study_item_id = $id;
            UPDATE card_profile_v5 SET
              knowledge_point = CASE WHEN $knowledge = '' THEN knowledge_point ELSE $knowledge END,
              source_type = $sourceType,
              source_title = CASE WHEN $source = '' THEN source_title ELSE $source END,
              source_chapter = CASE WHEN $chapter = '' THEN source_chapter ELSE $chapter END,
              source_locator = CASE WHEN $locator = '' THEN source_locator ELSE $locator END,
              source_year = COALESCE($year, source_year), first_attempt_markdown = $wrongStep,
              error_trigger_markdown = $trigger, general_method_markdown = $method,
              verification_markdown = $verification, transfer_prompt_markdown = $transfer,
              target_seconds = $target, updated_at = $now WHERE study_item_id = $id;
            UPDATE study_item SET updated_at = $now WHERE id = $id;
            """;
        command.Parameters.AddWithValue("$solution", draft.Solution.Trim());
        command.Parameters.AddWithValue("$wrongStep", draft.FirstAttempt.Trim());
        command.Parameters.AddWithValue("$keyHint", draft.KeyHint.Trim());
        command.Parameters.AddWithValue("$reason", (object?)draft.ErrorReason ?? DBNull.Value);
        command.Parameters.AddWithValue("$knowledge", draft.KnowledgePoint.Trim());
        command.Parameters.AddWithValue("$sourceType", draft.SourceType);
        command.Parameters.AddWithValue("$source", draft.SourceTitle.Trim());
        command.Parameters.AddWithValue("$chapter", draft.SourceChapter.Trim());
        command.Parameters.AddWithValue("$locator", draft.SourceLocator.Trim());
        command.Parameters.AddWithValue("$year", (object?)draft.SourceYear ?? DBNull.Value);
        command.Parameters.AddWithValue("$trigger", draft.ErrorTrigger.Trim());
        command.Parameters.AddWithValue("$method", draft.GeneralMethod.Trim());
        command.Parameters.AddWithValue("$verification", draft.Verification.Trim());
        command.Parameters.AddWithValue("$transfer", draft.TransferPrompt.Trim());
        command.Parameters.AddWithValue("$target", (object?)draft.TargetSeconds ?? DBNull.Value);
        command.Parameters.AddWithValue("$id", id);
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds(); command.Parameters.AddWithValue("$now", now);
        await command.ExecuteNonQueryAsync();
        if (draft.Tags is not null) await ReplaceTagsInTransactionAsync(connection, transaction, id, draft.Tags, now);
        await transaction.CommitAsync();
    }

    public async Task UpdateMemoryCardAsync(string id, string prompt, string answer)
    {
        if (string.IsNullOrWhiteSpace(prompt)) throw new ArgumentException("题干不能为空");
        await using var connection = await OpenAsync(); await using var transaction = await connection.BeginTransactionAsync();
        var command = connection.CreateCommand(); command.Transaction = (SqliteTransaction)transaction;
        command.CommandText = """
            UPDATE memory_card SET prompt_markdown = $prompt, answer_markdown = $answer
            WHERE study_item_id = $id;
            UPDATE study_item SET updated_at = $now WHERE id = $id;
            """;
        command.Parameters.AddWithValue("$prompt", prompt.Trim());
        command.Parameters.AddWithValue("$answer", answer.Trim());
        command.Parameters.AddWithValue("$id", id);
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds(); command.Parameters.AddWithValue("$now", now);
        await command.ExecuteNonQueryAsync();
        await transaction.CommitAsync();
    }

    public async Task<ScheduleResult> ReviewAsync(
        StudyRow row,
        Rating rating,
        long reviewedAt,
        int durationSeconds,
        string? mathResult,
        string? errorReason = null,
        bool hintRevealed = false,
        int hintLevel = 0,
        bool answerRevealed = false,
        int? pointHits = null,
        int? pointCount = null,
        int confidence = 3,
        string reflection = "")
    {
        if (confidence is < 1 or > 5 || hintLevel is < 0 or > 9 ||
            (pointHits is null) != (pointCount is null) ||
            pointCount.HasValue && (pointCount.Value <= 0 || pointHits!.Value < 0 ||
                pointHits.Value > pointCount.Value))
            throw new ArgumentException("复习证据无效");
        var preferences = await GetLearningPreferencesAsync();
        var preset = preferences.MemoryPreset switch { "time_saving" => 0, "reinforced" => 2, _ => 1 };
        var intensity = preferences.MathIntensity switch { "intensive" => 0, "relaxed" => 2, _ => 1 };
        var durationQuality = durationSeconds < 5 ? 2 : durationSeconds > 3600 ? 3 : 1;
        uint historyCount = 0, recentFailures = 0;
        double calibrationImprovement = 0;
        if (preferences.SchedulerGeneration == 3)
        {
            await using var metricsConnection = await OpenAsync();
            var metrics = metricsConnection.CreateCommand();
            metrics.CommandText = """
                SELECT
                  (SELECT COUNT(*) FROM review_event_v2 WHERE study_item_id = $id) +
                  (SELECT COUNT(*) FROM review_event_v3 WHERE study_item_id = $id),
                  (SELECT COUNT(*) FROM (
                    SELECT feedback FROM (
                      SELECT feedback, reviewed_at FROM review_event_v2 WHERE study_item_id = $id
                      UNION ALL
                      SELECT feedback, reviewed_at FROM review_event_v3 WHERE study_item_id = $id
                    ) ORDER BY reviewed_at DESC LIMIT 4
                  ) WHERE feedback <= 1)
                """;
            metrics.Parameters.AddWithValue("$id", row.Id);
            await using var metricsReader = await metrics.ExecuteReaderAsync();
            await metricsReader.ReadAsync();
            historyCount = checked((uint)metricsReader.GetInt64(0));
            recentFailures = checked((uint)metricsReader.GetInt64(1));
            await metricsReader.DisposeAsync();
            if (row.Kind == "memory_card")
            {
                var calibration = metricsConnection.CreateCommand();
                calibration.CommandText = """
                    SELECT e.feedback, m.retrievability_before
                    FROM review_event_v2 e
                    JOIN memory_review_event_v2 m ON m.review_event_id = e.id
                    UNION ALL
                    SELECT e.feedback, m.retrievability_before
                    FROM review_event_v3 e
                    JOIN memory_review_event_v3 m ON m.review_event_id = e.id
                    """;
                var samples = new List<(double Observed, double Predicted)>();
                await using var calibrationReader = await calibration.ExecuteReaderAsync();
                while (await calibrationReader.ReadAsync())
                    samples.Add((calibrationReader.GetInt32(0) > 1 ? 1 : 0,
                        calibrationReader.GetDouble(1)));
                historyCount = checked((uint)samples.Count);
                if (samples.Count > 0)
                {
                    var residual = samples.Average(sample => sample.Observed - sample.Predicted);
                    var baseline = samples.Average(sample =>
                        Math.Pow(sample.Predicted - sample.Observed, 2));
                    var calibrated = samples.Average(sample =>
                        Math.Pow(Math.Clamp(sample.Predicted + residual, 0, 1) - sample.Observed, 2));
                    calibrationImprovement = baseline - calibrated;
                }
            }
        }
        MathScheduleResult? mathSchedule = null;
        uint masteryBefore = 0, streakBefore = 0;
        ScheduleResult result;
        if (row.Kind == "memory_card")
        {
            var card = new ScheduleCard(row.State, row.Difficulty, row.StabilityDays, row.DueAt,
                row.LastReviewedAt, row.Repetitions, row.Lapses);
            result = preferences.SchedulerGeneration == 3
                ? NativeScheduler.ReviewMemoryV3(card, rating, reviewedAt, preset,
                    historyCount, calibrationImprovement, recentFailures)
                : NativeScheduler.ReviewMemoryV2(card, rating, reviewedAt, preset);
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
            mathSchedule = preferences.SchedulerGeneration == 3
                ? NativeScheduler.ReviewMathV3(masteryBefore, streakBefore, row.DueAt,
                    row.LastReviewedAt, scheduleRepetitions, feedback, reason, hintRevealed,
                    reviewedAt, intensity, checked((uint)Math.Max(0, durationSeconds)),
                    durationQuality, recentFailures)
                : NativeScheduler.ReviewMathV2(masteryBefore, streakBefore, row.DueAt,
                    row.LastReviewedAt, scheduleRepetitions, feedback, reason, hintRevealed,
                    reviewedAt, intensity);
            result = new ScheduleResult(new ScheduleCard(CardState.Review,
                mathSchedule.MasteryLevel + 1, mathSchedule.ScheduledDays, mathSchedule.DueAt,
                reviewedAt, mathSchedule.Repetitions,
                row.Lapses + (uint)(feedback <= 1 ? 1 : 0)), 0,
                mathSchedule.ScheduledDays, 0, mathSchedule.AlgorithmVersion,
                mathSchedule.ParameterVersion, mathSchedule.DecisionFlags);
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
              repetitions = $repetitions, needs_history_replay = 0,
              active_algorithm_version = $algorithmVersion,
              active_parameter_version = $parameterVersion, updated_at = $now
            WHERE study_item_id = $id
            """, ("$due", result.Card.DueAt), ("$reviewed", reviewedAt),
            ("$repetitions", result.Card.Repetitions),
            ("$algorithmVersion", result.AlgorithmVersion),
            ("$parameterVersion", result.ParameterVersion), ("$now", now), ("$id", row.Id));
        var eventId = NewId();
        var algorithm = row.Kind == "memory_card" ? "memory_fsrs_6" : "math_mastery_ladder";
        var preference = row.Kind == "memory_card" ? preferences.MemoryPreset : preferences.MathIntensity;
        var appliedFeedback = row.Kind == "memory_card" ? (int)rating : mathSchedule!.AppliedFeedback;
        if (result.AlgorithmVersion == 3)
        {
            var snapshot = JsonSerializer.Serialize(new {
                scheduledDays = result.ScheduledDays,
                retrievabilityBefore = result.RetrievabilityBefore,
                decisionFlags = result.DecisionFlags,
                schedulerGeneration = preferences.SchedulerGeneration,
            });
            var quality = durationQuality switch { 2 => "too_short", 3 => "interrupted", _ => "reliable" };
            var offset = checked((int)TimeZoneInfo.Local.GetUtcOffset(
                DateTimeOffset.FromUnixTimeSeconds(reviewedAt)).TotalMinutes);
            await ExecuteAsync(connection, transaction, """
                INSERT INTO review_event_v3 (id, study_item_id, algorithm, algorithm_version,
                  parameter_version, parameter_checksum, preference, feedback, reviewed_at,
                  duration_seconds, duration_quality, client_timezone_offset_minutes,
                  due_at_before, due_at_after, decision_flags, decision_snapshot_json,
                  device_id, created_at)
                VALUES ($event, $id, $algorithm, 3, $parameterVersion, $checksum,
                  $preference, $feedback, $reviewed, $duration, $quality, $offset,
                  $beforeDue, $afterDue, $flags, $snapshot, $device, $now)
                """, ("$event", eventId), ("$id", row.Id), ("$algorithm", algorithm),
                ("$parameterVersion", result.ParameterVersion),
                ("$checksum", ParameterChecksum(algorithm, result.ParameterVersion)),
                ("$preference", preference), ("$feedback", appliedFeedback),
                ("$reviewed", reviewedAt), ("$duration", Math.Max(0, durationSeconds)),
                ("$quality", quality), ("$offset", offset), ("$beforeDue", row.DueAt),
                ("$afterDue", result.Card.DueAt), ("$flags", result.DecisionFlags),
                ("$snapshot", snapshot), ("$device", deviceId), ("$now", now));
        }
        else
        {
            await ExecuteAsync(connection, transaction, """
                INSERT INTO review_event_v2 (id, study_item_id, algorithm, algorithm_version,
                  parameter_version, preference, feedback, reviewed_at, duration_seconds,
                  due_at_before, due_at_after, device_id, created_at)
                VALUES ($event, $id, $algorithm, 2, 1, $preference, $feedback, $reviewed,
                  $duration, $beforeDue, $afterDue, $device, $now)
                """, ("$event", eventId), ("$id", row.Id), ("$algorithm", algorithm),
                ("$preference", preference), ("$feedback", appliedFeedback),
                ("$reviewed", reviewedAt), ("$duration", Math.Max(0, durationSeconds)),
                ("$beforeDue", row.DueAt), ("$afterDue", result.Card.DueAt),
                ("$device", deviceId), ("$now", now));
        }
        if (row.Kind == "memory_card")
        {
            var memoryEventTable = result.AlgorithmVersion == 3
                ? "memory_review_event_v3" : "memory_review_event_v2";
            var memoryV3Columns = result.AlgorithmVersion == 3
                ? ", personalized, learning_step, overdue_days" : "";
            var memoryV3Values = result.AlgorithmVersion == 3
                ? ", $personalized, $learningStep, $overdue" : "";
            await ExecuteAsync(connection, transaction, $$"""
                UPDATE memory_schedule_state SET state = $state, difficulty = $difficulty,
                  stability_days = $stability, lapses = $lapses WHERE study_item_id = $id;
                INSERT INTO {{memoryEventTable}} (review_event_id, state_before, state_after,
                  target_retention, elapsed_days, scheduled_days, retrievability_before,
                  difficulty_before, difficulty_after, stability_before, stability_after{{memoryV3Columns}})
                VALUES ($event, $before, $after, $retention, $elapsed, $scheduled,
                  $retrievability, $difficultyBefore, $difficultyAfter, $stabilityBefore,
                  $stabilityAfter{{memoryV3Values}})
                """, ("$state", (int)result.Card.State), ("$difficulty", result.Card.Difficulty),
                ("$stability", result.Card.StabilityDays), ("$lapses", result.Card.Lapses),
                ("$id", row.Id), ("$event", eventId), ("$before", (int)row.State),
                ("$after", (int)result.Card.State),
                ("$retention", result.TargetRetention),
                ("$elapsed", elapsed), ("$scheduled", result.ScheduledDays),
                ("$retrievability", result.RetrievabilityBefore),
                ("$difficultyBefore", row.Difficulty), ("$difficultyAfter", result.Card.Difficulty),
                ("$stabilityBefore", row.StabilityDays), ("$stabilityAfter", result.Card.StabilityDays),
                ("$personalized", result.Personalized ? 1 : 0),
                ("$learningStep", result.LearningStep ? 1 : 0), ("$overdue", result.OverdueDays));
        }
        if (row.Kind == "math_problem" && mathResult is not null)
        {
            var attemptId = NewId();
            var mathEventTable = result.AlgorithmVersion == 3
                ? "math_review_event_v3" : "math_review_event_v2";
            var mathV3Column = result.AlgorithmVersion == 3 ? ", consecutive_failures" : "";
            var mathV3Value = result.AlgorithmVersion == 3 ? ", $consecutiveFailures" : "";
            await ExecuteAsync(connection, transaction, $$"""
                INSERT INTO attempt (
                  id, math_problem_id, started_at, finished_at, result, error_reason, created_at
                ) VALUES ($attempt, $id, $started, $finished, $result, $reason, $now);
                UPDATE math_schedule_state SET mastery_level = $mastery,
                  fluent_streak = $streak WHERE study_item_id = $id;
                INSERT INTO {{mathEventTable}} (review_event_id, attempt_id,
                  requested_feedback, applied_feedback, error_reason, hint_revealed,
                  mastery_before, mastery_after, fluent_streak_before,
                  fluent_streak_after, scheduled_days{{mathV3Column}})
                VALUES ($event, $attempt, $requested, $applied, $reason, $hint,
                  $masteryBefore, $mastery, $streakBefore, $streak, $scheduled{{mathV3Value}})
                """, ("$attempt", attemptId), ("$id", row.Id),
                ("$started", reviewedAt - Math.Max(0, durationSeconds)),
                ("$finished", reviewedAt), ("$result", mathResult),
                ("$reason", errorReason is null ? DBNull.Value : errorReason), ("$now", now),
                ("$mastery", mathSchedule!.MasteryLevel), ("$streak", mathSchedule.FluentStreak),
                ("$event", eventId), ("$requested", mathResult switch {
                    "again" => 0, "wrong" => 1, "effortful" => 2, _ => 3 }),
                ("$applied", mathSchedule.AppliedFeedback), ("$hint", hintRevealed ? 1 : 0),
                ("$masteryBefore", masteryBefore), ("$streakBefore", streakBefore),
                ("$scheduled", mathSchedule.ScheduledDays),
                ("$consecutiveFailures", recentFailures));
            await FreezeInkDraftAsync(connection, transaction, row.Id, attemptId, now);
        }
        var deviceCounter = await NextDeviceCounterAsync(connection, transaction);
        var operationId = Guid.NewGuid().ToString();
        var changedFields = JsonSerializer.Serialize(new
        {
            studyItemId = row.Id,
            algorithm,
            feedback = appliedFeedback,
            reviewedAt,
            durationSeconds = Math.Max(0, durationSeconds),
            errorReason,
            hintRevealed,
        });
        await ExecuteAsync(connection, transaction, """
            INSERT INTO review_action_v4 (
              action_id, study_item_id, algorithm, feedback, reviewed_at,
              duration_seconds, error_reason, hint_revealed, device_id,
              device_counter, causal_cursor, source_generation, created_at)
            VALUES ($action, $item, $algorithm, $feedback, $reviewed, $duration,
              $reason, $hint, $device, $counter, 0, 4, $now);
            INSERT INTO sync_outbox (
              operation_id, device_id, device_counter, base_cursor, base_revision,
              entity_type, entity_id, action, changed_fields_json, occurred_at)
            VALUES ($operation, $device, $counter, 0, 0, 'reviewAction', $action,
              'create', $fields, $now);
            """, ("$action", eventId), ("$item", row.Id), ("$algorithm", algorithm),
            ("$feedback", appliedFeedback), ("$reviewed", reviewedAt),
            ("$duration", Math.Max(0, durationSeconds)),
            ("$reason", errorReason is null ? DBNull.Value : errorReason),
            ("$hint", hintRevealed ? 1 : 0), ("$device", deviceId),
            ("$counter", deviceCounter), ("$now", now),
            ("$operation", operationId), ("$fields", changedFields));
        await ExecuteAsync(connection, transaction, """
            UPDATE schedule_cache_v4 SET due_at = $due, replayed_action_count = $count,
              dirty = 0, rebuilt_at = $now WHERE study_item_id = $item
            """, ("$due", result.Card.DueAt), ("$count", row.Repetitions + 1),
            ("$now", now), ("$item", row.Id));
        await AppendLearningEvidenceV5Async(connection, transaction, row, rating, reviewedAt,
            durationSeconds, mathResult, errorReason, hintRevealed, hintLevel, answerRevealed,
            pointHits, pointCount, confidence, reflection, historyCount, recentFailures, preset, now);
        await transaction.CommitAsync();
        return result;
    }

    private static async Task CreateLearningRouteAsync(SqliteConnection connection,
        System.Data.Common.DbTransaction transaction, string studyItemId, string kind,
        string subject, long now)
    {
        var unitId = "v5-unit:" + studyItemId;
        var taskId = "v5-task:" + studyItemId + (kind == "math_problem" ? ":repair" : "");
        await ExecuteAsync(connection, transaction, """
            INSERT INTO learning_unit_v5 (id, unit_type, source_study_item_id, subject, title, created_at, updated_at)
            VALUES ($unit, $unitType, $item, $subject, '', $now, $now)
            ON CONFLICT(source_study_item_id) DO NOTHING;
            INSERT INTO learning_task_v5 (id, learning_unit_id, source_study_item_id, task_type, task_state,
              math_phase, due_at, legacy_due_at, estimated_seconds, source_generation, created_at, updated_at)
            VALUES ($task, $unit, $item, $taskType, 'active', $phase, 0, 0, $seconds, 5, $now, $now)
            ON CONFLICT(id) DO NOTHING;
            """, ("$unit", unitId), ("$unitType", kind == "math_problem" ? "math_error_cluster" : "memory_knowledge_package"),
            ("$item", studyItemId), ("$subject", subject), ("$task", taskId),
            ("$taskType", kind == "math_problem" ? "math_repair" : "memory_recall"),
            ("$phase", kind == "math_problem" ? "repair" : DBNull.Value),
            ("$seconds", kind == "math_problem" ? 480 : 60), ("$now", now));
    }

    private async Task AppendLearningEvidenceV5Async(SqliteConnection connection,
        System.Data.Common.DbTransaction transaction, StudyRow row, Rating rating, long reviewedAt,
        int durationSeconds, string? mathResult, string? errorReason, bool hintRevealed,
        int hintLevel, bool answerRevealed, int? recordedPointHits, int? recordedPointCount,
        int recordedConfidence, string reflection, uint historyCount, uint recentFailures,
        int memoryPreset, long now)
    {
        var taskQuery = connection.CreateCommand(); taskQuery.Transaction = (SqliteTransaction)transaction;
        taskQuery.CommandText = """
            SELECT id, task_type, COALESCE(math_phase, ''), due_at, last_reviewed_at, repetitions,
                   consecutive_failures
            FROM learning_task_v5 WHERE source_study_item_id = $item
              AND task_state IN ('active', 'legacy') AND dependency_ready = 1
            ORDER BY CASE task_type WHEN 'math_repair' THEN 0 ELSE 1 END,
              due_at, id LIMIT 1
            """;
        taskQuery.Parameters.AddWithValue("$item", row.Id);
        await using var reader = await taskQuery.ExecuteReaderAsync();
        if (!await reader.ReadAsync()) return;
        var taskId = reader.GetString(0); var taskType = reader.GetString(1);
        var phase = reader.GetString(2); var dueAt = reader.GetInt64(3);
        var lastReviewedAt = reader.GetInt64(4); var repetitions = checked((uint)reader.GetInt64(5));
        var failures = checked((uint)reader.GetInt64(6));
        await reader.DisposeAsync();

        string nextType = taskType, nextState = "active";
        string? nextPhase = null;
        long nextDue; uint nextRepetitions, nextFailures; bool correct; uint errorMask = 0;
        int? pointHits = recordedPointHits, pointCount = recordedPointCount;
        if (row.Kind == "memory_card")
        {
            var schedulerHits = pointHits ?? (rating >= Rating.Good ? 1 : 0);
            var schedulerCount = pointCount ?? 1;
            var scheduled = NativeScheduler.ReviewMemoryTaskV5(
                new ScheduleCard(row.State, row.Difficulty, row.StabilityDays, row.DueAt,
                    row.LastReviewedAt, row.Repetitions, row.Lapses),
                memoryPreset, reviewedAt,
                checked((uint)schedulerHits), checked((uint)schedulerCount), checked((uint)Math.Clamp(hintLevel, 0, 9)),
                answerRevealed, durationSeconds is >= 5 and <= 3600,
                checked((uint)Math.Max(0, durationSeconds)), checked((uint)recordedConfidence), historyCount, 0, recentFailures);
            correct = pointCount is null ? rating >= Rating.Good : pointHits!.Value * 1.0 / pointCount.Value >= .85;
            nextDue = scheduled.Card.DueAt; nextRepetitions = scheduled.Card.Repetitions;
            nextFailures = correct ? 0 : failures + 1;
        }
        else
        {
            errorMask = MathErrorMask(errorReason);
            correct = mathResult is "effortful" or "fluent";
            var reviewed = NativeScheduler.ReviewMathTaskV5(new MathTaskStateV5(
                MathPhase(phase), dueAt, lastReviewedAt, repetitions, failures,
                MathPhase(phase) >= 2, MathPhase(phase) >= 3, MathPhase(phase) >= 4,
                MathPhase(phase) >= 5), reviewedAt, correct, hintRevealed, true, false,
                errorMask, checked((uint)Math.Max(0, durationSeconds)), checked((uint)recordedConfidence));
            nextPhase = MathPhaseName(reviewed.State.Phase);
            nextType = MathTaskType(reviewed.State.Phase);
            nextState = reviewed.State.Phase switch { 5 => "awaiting_variant", 6 => "graduated", _ => "active" };
            nextDue = reviewed.State.DueAt; nextRepetitions = reviewed.State.Repetitions;
            nextFailures = reviewed.State.ConsecutiveFailures;
        }
        // The insert trigger owns the device-counter increment and outbox record.
        // Reading the current value here makes the immutable evidence and its sync
        // operation share one counter rather than allocating two competing values.
        var counterCommand = connection.CreateCommand();
        counterCommand.Transaction = (SqliteTransaction)transaction;
        counterCommand.CommandText = "SELECT next_counter FROM local_device WHERE singleton = 1";
        var evidenceCounter = Convert.ToInt64(await counterCommand.ExecuteScalarAsync());
        await ExecuteAsync(connection, transaction, """
            UPDATE learning_task_v5 SET task_type = $nextType, task_state = $state, math_phase = $phase,
              due_at = $due, last_reviewed_at = $reviewed, repetitions = $repetitions,
              consecutive_failures = $failures, updated_at = $now WHERE id = $task;
            INSERT INTO learning_evidence_v5 (evidence_id, learning_task_id, task_type, reviewed_at,
              correct, error_mask, point_hits, point_count, hint_level, answer_revealed,
              duration_seconds, duration_reliable, confidence, reflection_markdown,
              device_id, device_counter, created_at)
            VALUES ($evidence, $task, $type, $reviewed, $correct, $error, $hits, $count, $hint,
              $answerRevealed, $duration, $reliable, $confidence, $reflection, $device, $counter, $now);
            """, ("$nextType", nextType), ("$state", nextState), ("$phase", (object?)nextPhase ?? DBNull.Value),
            ("$due", nextDue), ("$reviewed", reviewedAt), ("$repetitions", nextRepetitions),
            ("$failures", nextFailures), ("$now", now), ("$task", taskId), ("$evidence", NewId()),
            ("$type", taskType), ("$correct", correct ? 1 : 0), ("$error", errorMask),
            ("$hits", (object?)pointHits ?? DBNull.Value), ("$count", (object?)pointCount ?? DBNull.Value),
            ("$hint", Math.Clamp(hintLevel, 0, 9)), ("$answerRevealed", answerRevealed ? 1 : 0),
            ("$duration", Math.Max(0, durationSeconds)),
            ("$reliable", durationSeconds is >= 5 and <= 3600 ? 1 : 0),
            ("$confidence", recordedConfidence), ("$reflection", reflection.Trim()), ("$device", deviceId),
            ("$counter", evidenceCounter));
    }

    private static uint MathErrorMask(string? reason) => reason switch {
        "concept" => 1u, "approach" => 2u, "calculation" => 4u, "misread" => 8u,
        "forgotten_fact" => 16u, "timeout" => 32u, "other" => 64u, _ => 0u,
    };
    private static int MathPhase(string phase) => phase switch {
        "original" => 1, "variant" => 2, "transfer" => 3, "retention" => 4,
        "awaiting_variant" => 5, "graduated" => 6, _ => 0,
    };
    private static string MathPhaseName(int phase) => phase switch {
        1 => "original", 2 => "variant", 3 => "transfer", 4 => "retention",
        5 => "awaiting_variant", 6 => "graduated", _ => "repair",
    };
    private static string MathTaskType(int phase) => phase switch {
        1 => "math_original", 2 => "math_variant", 3 => "math_transfer", 4 => "math_retention",
        _ => "math_repair",
    };

    public string ResolveMediaPath(string relativePath) => Path.Combine(appDirectory, relativePath);

    public async Task SaveInkDraftAsync(string studyItemId, byte[] gzipJson)
    {
        if (gzipJson.Length == 0 || gzipJson.Length > 25 * 1024 * 1024)
            throw new ArgumentException("演算草稿大小无效");
        await using var connection = await OpenAsync(); var command = connection.CreateCommand();
        command.CommandText = """
            INSERT INTO local_ink_draft (study_item_id, format_version, gzip_json, updated_at)
            VALUES ($id, 1, $data, $now)
            ON CONFLICT(study_item_id) DO UPDATE SET gzip_json = excluded.gzip_json,
              updated_at = excluded.updated_at
            """;
        command.Parameters.AddWithValue("$id", studyItemId); command.Parameters.AddWithValue("$data", gzipJson);
        command.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToUnixTimeSeconds());
        await command.ExecuteNonQueryAsync();
    }

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
        var exportDatabase = Path.Combine(Path.GetTempPath(), "ReviewFault",
            "backup-" + Guid.NewGuid() + ".sqlite");
        Directory.CreateDirectory(Path.GetDirectoryName(exportDatabase)!);
        File.Copy(databasePath, exportDatabase, true);
        var exportConnectionString = new SqliteConnectionStringBuilder
        {
            DataSource = exportDatabase,
            Mode = SqliteOpenMode.ReadWrite,
            Pooling = false,
        }.ToString();
        await using (var sanitized = new SqliteConnection(exportConnectionString))
        {
            await sanitized.OpenAsync();
            var clear = sanitized.CreateCommand();
            clear.CommandText = """
                DELETE FROM local_device;
                DELETE FROM sync_cursor;
                DELETE FROM sync_revision;
                DELETE FROM sync_outbox;
                DELETE FROM sync_conflict;
                DELETE FROM local_ink_draft;
                VACUUM;
                """;
            await clear.ExecuteNonQueryAsync();
        }
        var files = new List<(string Path, string FilePath)> { ("database.sqlite", exportDatabase) };
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
            version = 5,
            appVersion = AppVersion,
            schemaVersion = 5,
            schedulerAbiVersion = 5,
            exportedAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
            excludedTables = new[] { "local_device", "sync_cursor", "sync_revision",
                "sync_outbox", "sync_conflict", "local_ink_draft" },
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
        File.Delete(exportDatabase);
    }

    public async Task RestoreBackupAsync(Stream input)
    {
        var restoreRoot = Path.Combine(Path.GetTempPath(), "ReviewFault", "restore-" + Guid.NewGuid());
        Directory.CreateDirectory(restoreRoot);
        try
        {
            var extractedFiles = new HashSet<string>(StringComparer.Ordinal);
            long extractedBytes = 0;
            using (var archive = new ZipArchive(input, ZipArchiveMode.Read, leaveOpen: true))
            {
                if (archive.Entries.Count > MaxBackupEntries)
                    throw new InvalidDataException("备份文件数量过多，已拒绝恢复");
                var safeRoot = Path.GetFullPath(restoreRoot) + Path.DirectorySeparatorChar;
                foreach (var entry in archive.Entries)
                {
                    var relative = entry.FullName.Replace('\\', '/');
                    if (relative != "manifest.json" && relative != "database.sqlite" &&
                        !relative.StartsWith("media/", StringComparison.Ordinal))
                        throw new InvalidDataException($"备份包含未知文件：{relative}");
                    var destination = Path.GetFullPath(Path.Combine(restoreRoot, relative));
                    if (!destination.StartsWith(safeRoot, StringComparison.OrdinalIgnoreCase))
                        throw new InvalidDataException("备份包含非法路径");
                    if (string.IsNullOrEmpty(entry.Name))
                    {
                        Directory.CreateDirectory(destination);
                        continue;
                    }
                    if (!extractedFiles.Add(relative))
                        throw new InvalidDataException($"备份包含重复文件：{relative}");
                    extractedBytes = checked(extractedBytes + entry.Length);
                    if (extractedBytes > MaxBackupBytes)
                        throw new InvalidDataException("备份解压后超过 2 GiB，已拒绝恢复");
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
                  (backupVersion == 2 && schemaVersion == 2 && abiVersion == 2) ||
                  (backupVersion == 3 && schemaVersion == 3 && abiVersion == 3) ||
                  (backupVersion == 4 && schemaVersion == 4 && abiVersion == 4) ||
                  (backupVersion == 5 && schemaVersion == 5 && abiVersion == 5)))
                throw new InvalidDataException("不是受支持的 ReviewFault 备份");
            var listedFiles = new HashSet<string>(StringComparer.Ordinal);
            long listedBytes = 0;
            foreach (var item in root.GetProperty("files").EnumerateArray())
            {
                var relative = item.GetProperty("path").GetString()!;
                if (relative != "database.sqlite" &&
                    !relative.StartsWith("media/", StringComparison.Ordinal))
                    throw new InvalidDataException("备份清单包含非法路径");
                if (!listedFiles.Add(relative))
                    throw new InvalidDataException($"备份清单包含重复文件：{relative}");
                var declaredBytes = item.GetProperty("bytes").GetInt64();
                if (declaredBytes < 0 ||
                    (listedBytes = checked(listedBytes + declaredBytes)) > MaxBackupBytes)
                    throw new InvalidDataException("备份清单超过 2 GiB，已拒绝恢复");
                var filePath = Path.GetFullPath(Path.Combine(restoreRoot, relative));
                var safeRoot = Path.GetFullPath(restoreRoot) + Path.DirectorySeparatorChar;
                if (!filePath.StartsWith(safeRoot, StringComparison.OrdinalIgnoreCase) ||
                    !File.Exists(filePath) || new FileInfo(filePath).Length != declaredBytes ||
                    await Sha256Async(filePath) != item.GetProperty("sha256").GetString())
                    throw new InvalidDataException($"备份文件校验失败：{relative}");
            }
            listedFiles.Add("manifest.json");
            if (!extractedFiles.SetEquals(listedFiles))
                throw new InvalidDataException("备份包含未在清单中声明的文件");
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
                if (restoredVersion == 2)
                {
                    command.CommandText = await File.ReadAllTextAsync(Path.Combine(
                        AppContext.BaseDirectory, "schema", "003_v0_3.sql"));
                    await command.ExecuteNonQueryAsync();
                    restoredVersion = 3;
                }
                if (restoredVersion == 3)
                {
                    command.CommandText = await File.ReadAllTextAsync(Path.Combine(
                        AppContext.BaseDirectory, "schema", "004_v0_4.sql"));
                    await command.ExecuteNonQueryAsync();
                    restoredVersion = 4;
                }
                if (restoredVersion == 4)
                {
                    command.CommandText = await File.ReadAllTextAsync(Path.Combine(
                        AppContext.BaseDirectory, "schema", "005_v0_5.sql"));
                    await command.ExecuteNonQueryAsync();
                    restoredVersion = 5;
                }
                command.CommandText = "PRAGMA integrity_check";
                if ((string?)await command.ExecuteScalarAsync() != "ok")
                    throw new InvalidDataException("备份数据库完整性检查失败");
                if (restoredVersion != 5)
                    throw new InvalidDataException("备份数据库版本不兼容");
                command.CommandText = "PRAGMA foreign_key_check";
                await using var invalidReferences = await command.ExecuteReaderAsync();
                if (await invalidReferences.ReadAsync())
                    throw new InvalidDataException("备份数据库存在无效引用");
            }
            ReplaceCurrentData(restoreRoot);
            await using var restored = await OpenAsync();
            await EnsureDeviceIdentityAsync(restored);
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

    private static string ParameterChecksum(string algorithm, uint parameterVersion) =>
        (algorithm, parameterVersion) switch
        {
            ("memory_fsrs_6", 2) => "bd98e3fdf07a9223a39b5305fe5c14e8d9a03013ddbbce3f5d9ea15555c9c177",
            ("memory_fsrs_6", 3) => "083f217e835490d1760ee5bfc94693b1b4fb827e3ed121cbd970f401d6271019",
            ("math_mastery_ladder", 2) => "229003e5c13709bb8af1443b1d4585a025dc92db742520a82740d01a4fe9c089",
            _ => throw new InvalidOperationException("未知的调度参数版本"),
        };

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

    private async Task EnsureDeviceIdentityAsync(SqliteConnection connection)
    {
        var query = connection.CreateCommand();
        query.CommandText = "SELECT device_id FROM local_device WHERE singleton = 1";
        var existing = (string?)await query.ExecuteScalarAsync();
        if (!string.IsNullOrWhiteSpace(existing))
        {
            deviceId = existing;
            return;
        }
        if (string.IsNullOrWhiteSpace(deviceId)) deviceId = Guid.NewGuid().ToString();
        var insert = connection.CreateCommand();
        insert.CommandText = """
            INSERT INTO local_device (singleton, device_id, next_counter, created_at)
            VALUES (1, $device, 1, $now)
            """;
        insert.Parameters.AddWithValue("$device", deviceId);
        insert.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToUnixTimeSeconds());
        await insert.ExecuteNonQueryAsync();
    }

    private static async Task<long> NextDeviceCounterAsync(
        SqliteConnection connection, System.Data.Common.DbTransaction transaction)
    {
        var command = connection.CreateCommand();
        command.Transaction = (SqliteTransaction)transaction;
        command.CommandText = """
            UPDATE local_device SET next_counter = next_counter + 1 WHERE singleton = 1
            RETURNING next_counter - 1
            """;
        return Convert.ToInt64(await command.ExecuteScalarAsync());
    }

    private static async Task EnqueueSyncAsync(
        SqliteConnection connection, System.Data.Common.DbTransaction transaction,
        string entityType, string entityId, string action, object fields, long occurredAt)
    {
        var metadata = connection.CreateCommand(); metadata.Transaction = (SqliteTransaction)transaction;
        metadata.CommandText = """
            SELECT d.device_id,
              COALESCE((SELECT server_seq FROM sync_cursor WHERE workspace_id = d.workspace_id), 0),
              COALESCE((SELECT revision FROM sync_revision WHERE entity_type = $type AND entity_id = $id), 0)
            FROM local_device d WHERE singleton = 1
            """;
        metadata.Parameters.AddWithValue("$type", entityType); metadata.Parameters.AddWithValue("$id", entityId);
        await using var reader = await metadata.ExecuteReaderAsync(); await reader.ReadAsync();
        var localDevice = reader.GetString(0); var baseCursor = reader.GetInt64(1); var baseRevision = reader.GetInt64(2);
        await reader.DisposeAsync();
        var counter = await NextDeviceCounterAsync(connection, transaction);
        await ExecuteAsync(connection, transaction, """
            INSERT INTO sync_outbox (operation_id, device_id, device_counter, base_cursor,
              base_revision, entity_type, entity_id, action, changed_fields_json, occurred_at)
            VALUES ($operation, $device, $counter, $cursor, $revision, $type, $id,
              $action, $fields, $occurred)
            """, ("$operation", Guid.NewGuid().ToString()), ("$device", localDevice),
            ("$counter", counter), ("$cursor", baseCursor), ("$revision", baseRevision),
            ("$type", entityType), ("$id", entityId), ("$action", action),
            ("$fields", JsonSerializer.Serialize(fields)), ("$occurred", occurredAt));
    }

    private async Task FreezeInkDraftAsync(
        SqliteConnection connection, System.Data.Common.DbTransaction transaction,
        string studyItemId, string attemptId, long now)
    {
        var query = connection.CreateCommand(); query.Transaction = (SqliteTransaction)transaction;
        query.CommandText = "SELECT gzip_json FROM local_ink_draft WHERE study_item_id = $id";
        query.Parameters.AddWithValue("$id", studyItemId);
        var draft = await query.ExecuteScalarAsync() as byte[];
        if (draft is null || draft.Length == 0) return;
        var attemptQuery = connection.CreateCommand(); attemptQuery.Transaction = (SqliteTransaction)transaction;
        attemptQuery.CommandText = """
            SELECT started_at, finished_at, result, error_reason FROM attempt WHERE id = $id
            """;
        attemptQuery.Parameters.AddWithValue("$id", attemptId);
        await using var attemptReader = await attemptQuery.ExecuteReaderAsync();
        if (!await attemptReader.ReadAsync()) return;
        var startedAt = attemptReader.GetInt64(0);
        var finishedAt = attemptReader.IsDBNull(1) ? now : attemptReader.GetInt64(1);
        var attemptResult = attemptReader.GetString(2);
        var attemptErrorReason = attemptReader.IsDBNull(3) ? null : attemptReader.GetString(3);
        await attemptReader.DisposeAsync();
        var hash = Convert.ToHexString(SHA256.HashData(draft)).ToLowerInvariant();
        var mediaDirectory = Path.Combine(appDirectory, "media"); Directory.CreateDirectory(mediaDirectory);
        var path = Path.Combine(mediaDirectory, hash + ".reviewfault-ink.gz");
        if (!File.Exists(path)) await File.WriteAllBytesAsync(path, draft);
        var mediaId = NewId();
        await ExecuteAsync(connection, transaction, """
            INSERT OR IGNORE INTO media (id, sha256, mime_type, byte_count, relative_path, created_at)
            VALUES ($media, $hash, 'application/gzip', $bytes, $path, $now)
            """, ("$media", mediaId), ("$hash", hash), ("$bytes", draft.Length),
            ("$path", Path.Combine("media", Path.GetFileName(path))), ("$now", now));
        var lookup = connection.CreateCommand(); lookup.Transaction = (SqliteTransaction)transaction;
        lookup.CommandText = "SELECT id FROM media WHERE sha256 = $hash"; lookup.Parameters.AddWithValue("$hash", hash);
        mediaId = (string)(await lookup.ExecuteScalarAsync())!;
        var artifactId = NewId();
        await ExecuteAsync(connection, transaction, """
            INSERT INTO attempt_artifact (id, attempt_id, artifact_type, media_id, page_count, created_at)
            VALUES ($artifact, $attempt, 'reviewfault-ink-v1', $media, 1, $now);
            DELETE FROM local_ink_draft WHERE study_item_id = $item;
            """, ("$artifact", artifactId), ("$attempt", attemptId), ("$media", mediaId),
            ("$now", now), ("$item", studyItemId));
        await EnqueueSyncAsync(connection, transaction, "attemptArtifact", artifactId, "create", new {
            attemptId, studyItemId, startedAt, finishedAt, result = attemptResult,
            errorReason = attemptErrorReason, artifactType = "reviewfault-ink-v1",
            mediaSha256 = hash, mediaMimeType = "application/gzip",
            mediaByteCount = draft.Length, pageCount = 1,
        }, now);
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
