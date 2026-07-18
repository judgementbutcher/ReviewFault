using ReviewFault.Core;
using ReviewFault.Data;
using System.IO.Compression;

var root = Path.Combine(Path.GetTempPath(), "ReviewFaultHeadless-" + Guid.NewGuid());
Directory.CreateDirectory(root);
try
{
    var repository = new AppRepository(root);
    await repository.InitializeAsync();

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

    var next = await repository.NextForReviewAsync(DateTimeOffset.UtcNow.ToUnixTimeSeconds());
    Require(next is not null, "a new item enters today's queue");
    var reviewedAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
    var result = await repository.ReviewAsync(next!, Rating.Good, reviewedAt, 30,
        next!.Kind == "math_problem" ? "effortful" : null);
    Require(result.Card.State == CardState.Review, "Good graduates the item to review");
    Require(result.Card.DueAt > reviewedAt, "review produces a future due time");
    var mathRow = (await repository.SearchAsync("集成测试")).Single(item => item.Id == mathId);
    var mathReview = await repository.ReviewAsync(mathRow, Rating.Good, reviewedAt + 1, 90,
        "effortful", null, false);
    Require(mathReview.AlgorithmVersion == 3 && mathReview.ScheduledDays > 0,
        "math review uses the v3 ABI and persists decision metadata");
    await repository.ReplaceTagsAsync(cardId, new[] { "进程", "易混" });
    var preferences = await repository.GetLearningPreferencesAsync();
    await repository.SaveLearningPreferencesAsync(preferences with {
        DailyNewMemoryLimit = 12, MathIntensity = "intensive" });
    Require((await repository.GetLearningPreferencesAsync()).DailyNewMemoryLimit == 12,
        "learning settings persist in schema v3");
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
        Require(Convert.ToInt32(await command.ExecuteScalarAsync()) == 3,
            "repository initializes schema v3");
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
