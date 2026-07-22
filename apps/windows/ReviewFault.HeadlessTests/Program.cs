using ReviewFault.Core;
using ReviewFault.Data;
using ReviewFault.Services;
using System.Security.Cryptography;
using System.IO.Compression;
using System.Text.Json;

var root = Path.Combine(Path.GetTempPath(), "ReviewFaultHeadless-" + Guid.NewGuid());
Directory.CreateDirectory(root);
try
{
    var repository = new AppRepository(root);
    await repository.InitializeAsync();
    using var validSyncClient = new SyncClient("https://sync.reviewfault.app");
    RequireThrows<ArgumentException>(() => new SyncClient("http://localhost.evil.example"),
        "sync endpoint validation rejects localhost prefix spoofing");
    var canonical = NativeScheduler.CanonicalOrderV4(new[] {
        new ReplayAction("later", "device", 2, 0, 3, 1_800_000_000),
        new ReplayAction("first", "device", 1, 0, 1, 1_800_000_100),
    });
    Require(canonical.SequenceEqual(new[] { 1, 0 }),
        "Windows P/Invoke uses the core v4 canonical order");

    var cardId = await repository.CreateMemoryCardAsync(
        "qa", "operating_systems", "什么是工作集？", "进程在某段时间内实际访问的页面集合。",
        Array.Empty<string>());
    var enumerationId = await repository.CreateMemoryCardAsync(
        "enumeration", "computer_networks", "TCP 拥塞控制的四个阶段？", "",
        new[] { "慢开始", "拥塞避免", "快重传", "快恢复" });
    await RequireThrowsAsync<ArgumentException>(
        () => repository.CreateMemoryCardAsync("cloze", "computer_networks", "没有标记", "", []),
        "invalid cloze is rejected");
    var image = Path.Combine(root, "problem.jpg");
    await File.WriteAllBytesAsync(image, Enumerable.Range(0, 512).Select(i => (byte)i).ToArray());
    var image2 = Path.Combine(root, "problem-2.png");
    await File.WriteAllBytesAsync(image2, Enumerable.Range(0, 256).Select(i => (byte)(255 - i)).ToArray());
    var mathId = await repository.CreateMathProblemAsync(new[] { image, image2 }, "集成测试");
    await repository.UpdateMathDetailsAsync(
        mathId, "参考解答", "错误步骤", "先判断定义域", "concept");

    var all = await repository.SearchAsync("");
    Require(all.Count == 3, "three study items are persisted");
    Require(all.Any(item => item.Id == cardId), "memory card can be searched");
    Require(all.Any(item => item.Id == mathId && item.Answer == "参考解答"),
        "math reflection can be searched");
    Require(all.Any(item => item.Id == enumerationId && item.TemplateType == "enumeration" &&
        (System.Text.Json.JsonSerializer.Deserialize<string[]>(item.StructuredJson) ?? []).Contains("快恢复")),
        "enumeration points stay structured");
    Require(cardId[14] == '7' && mathId[14] == '7', "business IDs are UUIDv7");
    Require((await repository.MediaPathsAsync(mathId)).Count == 2,
        "a multi-page math problem preserves both images");

    var queueNow = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
    var initialSummary = await repository.DashboardAsync(
        queueNow, new DateTimeOffset(DateTime.Today).ToUnixTimeSeconds());
    Require(initialSummary.NewItems == 3 && initialSummary.EstimatedMinutes == 10 &&
        initialSummary.DeferredDueMinutes == 0,
        "dashboard builds a bounded focus session from new memory and math content");
    Require(await repository.NextForReviewAsync(queueNow, includeNewItems: false) is null,
        "a backlog-protected session does not introduce new content");
    var next = await repository.NextForReviewAsync(queueNow);
    Require(next is not null, "a new item enters today's queue");
    var artifactsBeforeSessionSkip = await CountReviewArtifactsAsync(root);
    var afterExcludedFirst = await repository.NextForReviewAsync(
        queueNow, includeNewItems: true, excludedItemIds: new[] { next!.Id });
    Require(afterExcludedFirst is not null && afterExcludedFirst.Id != next.Id,
        "a session-local skip selects a different queue item");
    Require(await repository.NextForReviewAsync(
        queueNow, includeNewItems: true, excludedItemIds: all.Select(item => item.Id).ToArray()) is null,
        "excluding every candidate ends the current session queue");
    Require((await repository.NextForReviewAsync(queueNow))?.Id == next.Id,
        "clearing the session exclusion makes the skipped item visible again");
    Require(await CountReviewArtifactsAsync(root) == artifactsBeforeSessionSkip,
        "session exclusions do not append ratings, attempts, or review events");
    await RequireThrowsAsync<ArgumentException>(
        () => repository.NextForReviewAsync(queueNow, true, new[] { "invalid|item" }),
        "session exclusion rejects IDs that could break boundary matching");
    var reviewedAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
    var result = await repository.ReviewAsync(next!, Rating.Good, reviewedAt, 30,
        next!.Kind == "math_problem" ? "effortful" : null);
    Require(result.Card.State == CardState.Review, "Good graduates the item to review");
    Require(result.Card.DueAt > reviewedAt, "review produces a future due time");
    var mathRow = (await repository.SearchAsync("集成测试")).Single(item => item.Id == mathId);
    await repository.SaveInkDraftAsync(mathId, new byte[] { 31, 139, 8, 0, 0, 0, 0, 0 });
    var mathReview = await repository.ReviewAsync(mathRow, Rating.Good, reviewedAt + 1, 90,
        "effortful", null, false);
    Require(mathReview.AlgorithmVersion == 3 && mathReview.ScheduledDays > 0,
        "math review uses the v3 ABI and persists decision metadata");
    await repository.ReplaceTagsAsync(cardId, new[] { "进程", "易混" });
    var preferences = await repository.GetLearningPreferencesAsync();
    await repository.SaveLearningPreferencesAsync(preferences with {
        DailyNewMemoryLimit = 12, MathIntensity = "intensive" });
    Require((await repository.GetLearningPreferencesAsync()).DailyNewMemoryLimit == 12,
        "learning settings persist through schema v5");
    await repository.SaveLearningPreferencesAsync((await repository.GetLearningPreferencesAsync()) with {
        SchedulerGeneration = 2 });
    var v2Row = (await repository.SearchAsync("TCP")).Single(item => item.Id == enumerationId);
    var v2Review = await repository.ReviewAsync(v2Row, Rating.Good, reviewedAt + 2, 20, null);
    Require(v2Review.AlgorithmVersion == 2,
        "local rollout switch can continue using frozen v0.2 parameters");
    var deletion = await repository.SoftDeleteAsync(new[] { enumerationId });
    Require((await repository.TrashAsync()).Any(item => item.Id == enumerationId) &&
        deletion.UndoUntil > deletion.DeletedAt, "soft deletion enters recoverable trash");
    await repository.RestoreAsync(new[] { enumerationId });
    Require(!(await repository.TrashAsync()).Any(item => item.Id == enumerationId),
        "trash item can be restored with its schedule");

    var accountId = Guid.NewGuid().ToString(); var workspaceId = Guid.NewGuid().ToString();
    await repository.BindAccountAsync(accountId, workspaceId);
    var pendingBeforePull = (await repository.SyncIdentityAsync()).PendingCount;
    var remoteId = Guid.NewGuid().ToString(); var remoteActionId = Guid.NewGuid().ToString();
    var remoteTagId = Guid.NewGuid().ToString(); var remoteDeviceId = Guid.NewGuid().ToString();
    var remoteArtifactId = Guid.NewGuid().ToString(); var remoteAttemptId = Guid.NewGuid().ToString();
    var remoteRelationAddId = Guid.NewGuid().ToString();
    var existingMediaSha = Convert.ToHexString(SHA256.HashData(await File.ReadAllBytesAsync(image)))
        .ToLowerInvariant();
    var remoteReviewedAt = reviewedAt + 10;
    await repository.ApplyPulledOperationsAsync(workspaceId, new[] {
        new PulledOperation(Guid.NewGuid().ToString(), 1, remoteDeviceId, 1,
            "studyItem", remoteId, "create", JsonSerializer.SerializeToElement(new {
                kind = "memory_card", subject = "computer_networks", templateType = "qa",
                prompt = "远端创建的题目", answer = "远端答案", hints = Array.Empty<string>(),
                answerPoints = Array.Empty<string>(), createdAt = remoteReviewedAt - 10,
                updatedAt = remoteReviewedAt - 10,
            }), remoteReviewedAt - 10),
        new PulledOperation(Guid.NewGuid().ToString(), 2, remoteDeviceId, 2,
            "memoryCard", remoteId, "update", JsonSerializer.SerializeToElement(new {
                promptMarkdown = "远端更新的题目", answerMarkdown = "远端更新的答案",
                hints = new[] { "分层提示" }, answerPoints = Array.Empty<string>(),
                occlusions = Array.Empty<object>(),
            }), remoteReviewedAt - 5),
        new PulledOperation(Guid.NewGuid().ToString(), 3, remoteDeviceId, 3,
            "reviewAction", remoteActionId, "create", JsonSerializer.SerializeToElement(new {
                studyItemId = remoteId, algorithm = "memory_fsrs_6", feedback = 3,
                reviewedAt = remoteReviewedAt, durationSeconds = 20,
                errorReason = (string?)null, hintRevealed = false,
            }), remoteReviewedAt),
        new PulledOperation(Guid.NewGuid().ToString(), 4, remoteDeviceId, 4,
            "tag", remoteTagId, "create", JsonSerializer.SerializeToElement(new {
                name = "远端标签",
            }), remoteReviewedAt),
        new PulledOperation(remoteRelationAddId, 5, remoteDeviceId, 5,
            "relation", $"{remoteId}:{remoteTagId}", "add", JsonSerializer.SerializeToElement(new {
                relationType = "study_item_tag", sourceId = remoteId, targetId = remoteTagId,
            }), remoteReviewedAt),
        new PulledOperation(Guid.NewGuid().ToString(), 6, remoteDeviceId, 6,
            "learningPreferences", "singleton", "update", JsonSerializer.SerializeToElement(new {
                dailyNewMemoryLimit = 17, sessionMinutes = 25, memoryPreset = "reinforced",
                mathIntensity = "balanced", schedulerGeneration = 3,
            }), remoteReviewedAt),
        new PulledOperation(Guid.NewGuid().ToString(), 7, remoteDeviceId, 7,
            "attemptArtifact", remoteArtifactId, "create", JsonSerializer.SerializeToElement(new {
                attemptId = remoteAttemptId, studyItemId = mathId,
                startedAt = remoteReviewedAt - 30, finishedAt = remoteReviewedAt,
                result = "effortful", errorReason = (string?)null,
                artifactType = "annotated-image", mediaSha256 = existingMediaSha,
                mediaMimeType = "image/jpeg", mediaByteCount = 512, pageCount = 1,
            }), remoteReviewedAt),
    }, 7);
    var remoteRow = (await repository.SearchAsync("远端更新")).Single();
    Require(remoteRow.State == CardState.Review && remoteRow.DueAt > remoteReviewedAt,
        "pulled review facts deterministically rebuild the visible schedule");
    var syncedIdentity = await repository.SyncIdentityAsync();
    Require(syncedIdentity.Cursor == 7 && syncedIdentity.WorkspaceId == workspaceId,
        "pull commits the workspace cursor with remote state");
    Require(syncedIdentity.PendingCount == pendingBeforePull,
        "pulled operations do not echo back into the local outbox");
    Require(await RelationExistsAsync(root, remoteId, remoteTagId),
        "pulled relation add materializes the tag link");
    await repository.ApplyPulledOperationsAsync(workspaceId, new[] {
        new PulledOperation(Guid.NewGuid().ToString(), 8, remoteDeviceId, 8,
            "relation", $"{remoteId}:{remoteTagId}", "remove", JsonSerializer.SerializeToElement(new {
                relationType = "study_item_tag", sourceId = remoteId, targetId = remoteTagId,
                observedAdds = Array.Empty<string>(),
            }), remoteReviewedAt + 1),
    }, 8);
    Require(await RelationExistsAsync(root, remoteId, remoteTagId),
        "an observed-remove does not suppress a concurrent unobserved add");
    await repository.ApplyPulledOperationsAsync(workspaceId, new[] {
        new PulledOperation(Guid.NewGuid().ToString(), 9, remoteDeviceId, 9,
            "relation", $"{remoteId}:{remoteTagId}", "remove", JsonSerializer.SerializeToElement(new {
                relationType = "study_item_tag", sourceId = remoteId, targetId = remoteTagId,
                observedAdds = new[] { remoteRelationAddId },
            }), remoteReviewedAt + 2),
    }, 9);
    Require(!await RelationExistsAsync(root, remoteId, remoteTagId),
        "a remove suppresses the add fact it explicitly observed");
    await repository.ApplyPulledOperationsAsync(workspaceId, new[] {
        new PulledOperation(Guid.NewGuid().ToString(), 10, remoteDeviceId, 10,
            "relation", $"{remoteId}:{remoteTagId}", "add", JsonSerializer.SerializeToElement(new {
                relationType = "study_item_tag", sourceId = remoteId, targetId = remoteTagId,
            }), remoteReviewedAt + 3),
    }, 10);
    Require(await RelationExistsAsync(root, remoteId, remoteTagId),
        "a later explicit add restores the relation after an observed remove");
    syncedIdentity = await repository.SyncIdentityAsync();
    Require(syncedIdentity.Cursor == 10 && syncedIdentity.PendingCount == pendingBeforePull,
        "relation replay advances the cursor without producing an outbox echo");

    await using (var audit = new Microsoft.Data.Sqlite.SqliteConnection(
        $"Data Source={Path.Combine(root, "reviewfault.db")}"))
    {
        await audit.OpenAsync();
        var command = audit.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM review_event_v3";
        Require(Convert.ToInt32(await command.ExecuteScalarAsync()) == 2,
            "new answers append v3 events instead of mutating v1/v2 history");
        command.CommandText = "SELECT COUNT(*) FROM review_event_v2";
        Require(Convert.ToInt32(await command.ExecuteScalarAsync()) == 1,
            "rollout rollback appends a v2 event without rewriting v3 history");
        command.CommandText = "PRAGMA user_version";
        Require(Convert.ToInt32(await command.ExecuteScalarAsync()) == 5,
            "repository initializes schema v5");
        command.CommandText = "SELECT COUNT(*) FROM learning_task_v5";
        Require(Convert.ToInt32(await command.ExecuteScalarAsync()) == 4,
            "migration and new package creation both materialize v5 learning tasks");
        command.CommandText = "SELECT COUNT(*) FROM learning_evidence_v5";
        Require(Convert.ToInt32(await command.ExecuteScalarAsync()) == 3,
            "each local review appends immutable v5 learning evidence");
        command.CommandText = """
            SELECT COUNT(*) FROM learning_evidence_v5 e
            JOIN sync_outbox o ON o.entity_type = 'learningEvidence' AND o.entity_id = e.evidence_id
            """;
        Require(Convert.ToInt32(await command.ExecuteScalarAsync()) == 3,
            "v5 evidence is exported as an independent sync fact");
        command.CommandText = "SELECT COUNT(*) FROM review_action_v4";
        Require(Convert.ToInt32(await command.ExecuteScalarAsync()) == 4,
            "local and pulled reviews append canonical v4 facts");
        command.CommandText = """
            SELECT COUNT(*) FROM review_action_v4 a
            JOIN sync_outbox o ON o.entity_type = 'reviewAction' AND o.entity_id = a.action_id
            """;
        Require(Convert.ToInt32(await command.ExecuteScalarAsync()) == 3,
            "canonical review facts and outbox operations commit together");
        command.CommandText = """
            SELECT COUNT(*) FROM study_item_tag WHERE study_item_id = $item AND tag_id = $tag
            """;
        command.Parameters.AddWithValue("$item", remoteId);
        command.Parameters.AddWithValue("$tag", remoteTagId);
        Require(Convert.ToInt32(await command.ExecuteScalarAsync()) == 1,
            "pulled tag and relation operations project into the local library");
        command.Parameters.Clear();
        command.CommandText = "SELECT daily_new_memory_limit FROM learning_preferences WHERE singleton = 1";
        Require(Convert.ToInt32(await command.ExecuteScalarAsync()) == 17,
            "pulled portable learning preferences are applied");
        command.CommandText = "SELECT COUNT(*) FROM attempt_artifact WHERE id = $artifact";
        command.Parameters.AddWithValue("$artifact", remoteArtifactId);
        Require(Convert.ToInt32(await command.ExecuteScalarAsync()) == 1,
            "pulled formal attempt artifacts retain their media and attempt relationship");
    }

    await using var backup = new MemoryStream();
    await repository.ExportBackupAsync(backup);
    Require(backup.Length > 0, "backup archive is produced");
    await repository.UpdateMemoryCardAsync(cardId, "被修改的问题", "被修改的答案");
    backup.Position = 0;
    await repository.RestoreBackupAsync(backup);
    var restored = await repository.SearchAsync("工作集");
    Require(restored.Count == 1 && restored[0].Prompt == "什么是工作集？",
        "backup restore replaces later edits");

    var undeclaredBackup = new MemoryStream();
    await undeclaredBackup.WriteAsync(backup.ToArray());
    undeclaredBackup.Position = 0;
    using (var archive = new ZipArchive(undeclaredBackup, ZipArchiveMode.Update, leaveOpen: true))
    {
        var extra = archive.CreateEntry("media/not-in-manifest.jpg");
        await using var output = extra.Open();
        await output.WriteAsync(new byte[] { 1, 2, 3 });
    }
    undeclaredBackup.Position = 0;
    await RequireThrowsAsync<InvalidDataException>(
        () => repository.RestoreBackupAsync(undeclaredBackup),
        "restore rejects payload files omitted from the manifest");

    var duplicateBackup = new MemoryStream();
    await duplicateBackup.WriteAsync(backup.ToArray());
    duplicateBackup.Position = 0;
    using (var archive = new ZipArchive(duplicateBackup, ZipArchiveMode.Update, leaveOpen: true))
    {
        var duplicate = archive.CreateEntry("database.sqlite");
        await using var output = duplicate.Open();
        await output.WriteAsync(new byte[] { 0 });
    }
    duplicateBackup.Position = 0;
    await RequireThrowsAsync<InvalidDataException>(
        () => repository.RestoreBackupAsync(duplicateBackup),
        "restore rejects duplicate archive entries");
    Require((await repository.SearchAsync("工作集")).Count == 1,
        "rejected backups leave current data untouched");

    var summary = await repository.DashboardAsync(
        DateTimeOffset.UtcNow.ToUnixTimeSeconds(), new DateTimeOffset(DateTime.Today).ToUnixTimeSeconds());
    Require(summary.NewItems <= 1, "dashboard reads the reviewed restored queue state");
    Require(summary.NextSevenDaysDue >= summary.TomorrowDue,
        "seven-day forecast includes the next local calendar day");
    var insights = await repository.InsightsAsync(
        DateTimeOffset.UtcNow.ToUnixTimeSeconds(), new DateTimeOffset(DateTime.Today).ToUnixTimeSeconds());
    Require(insights.Days.Count == 7 && insights.TotalReviews >= 3,
        "insights expose seven-day activity and lifetime review totals");
    Require(insights.ActiveItems == 4 && insights.Subjects.Sum(subject => subject.Total) == 4,
        "insights subject distribution covers every active item");
    Require(insights.AccuracyPercent is >= 0 and <= 100,
        "insights accuracy remains a bounded percentage");
    Console.WriteLine("Windows repository integration tests passed");
}
finally
{
    if (Directory.Exists(root)) Directory.Delete(root, true);
}

static void Require(bool condition, string message)
{
    if (!condition) throw new InvalidOperationException("FAIL: " + message);
}

static async Task RequireThrowsAsync<TException>(Func<Task> action, string message)
    where TException : Exception
{
    try
    {
        await action();
        throw new InvalidOperationException("FAIL: " + message);
    }
    catch (TException)
    {
    }
}

static void RequireThrows<TException>(Action action, string message)
    where TException : Exception
{
    try
    {
        action();
        throw new InvalidOperationException("FAIL: " + message);
    }
    catch (TException)
    {
    }
}

static async Task<int> CountReviewArtifactsAsync(string root)
{
    await using var connection = new Microsoft.Data.Sqlite.SqliteConnection(
        $"Data Source={Path.Combine(root, "reviewfault.db")}");
    await connection.OpenAsync();
    var command = connection.CreateCommand();
    command.CommandText = "SELECT (SELECT COUNT(*) FROM review_log) + " +
        "(SELECT COUNT(*) FROM review_event_v2) + " +
        "(SELECT COUNT(*) FROM review_event_v3) + (SELECT COUNT(*) FROM attempt)";
    return Convert.ToInt32(await command.ExecuteScalarAsync());
}

static async Task<bool> RelationExistsAsync(string root, string studyItemId, string tagId)
{
    await using var connection = new Microsoft.Data.Sqlite.SqliteConnection(
        $"Data Source={Path.Combine(root, "reviewfault.db")}");
    await connection.OpenAsync();
    var command = connection.CreateCommand();
    command.CommandText = """
        SELECT EXISTS (SELECT 1 FROM study_item_tag WHERE study_item_id = $item AND tag_id = $tag)
        """;
    command.Parameters.AddWithValue("$item", studyItemId);
    command.Parameters.AddWithValue("$tag", tagId);
    return Convert.ToInt32(await command.ExecuteScalarAsync()) != 0;
}
