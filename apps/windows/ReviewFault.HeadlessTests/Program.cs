using ReviewFault.Core;
using ReviewFault.Data;

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
    await RequireThrowsAsync(
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
    await repository.ReplaceTagsAsync(cardId, new[] { "进程", "易混" });
    var preferences = await repository.GetLearningPreferencesAsync();
    await repository.SaveLearningPreferencesAsync(preferences with {
        DailyNewMemoryLimit = 12, MathIntensity = "intensive" });
    Require((await repository.GetLearningPreferencesAsync()).DailyNewMemoryLimit == 12,
        "learning settings persist in schema v2");
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
        command.CommandText = "SELECT COUNT(*) FROM review_event_v2";
        Require(Convert.ToInt32(await command.ExecuteScalarAsync()) == 1,
            "new answers append v2 events instead of mutating v1 history");
        command.CommandText = "PRAGMA user_version";
        Require(Convert.ToInt32(await command.ExecuteScalarAsync()) == 2,
            "repository initializes schema v2");
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

    var summary = await repository.DashboardAsync(
        DateTimeOffset.UtcNow.ToUnixTimeSeconds(), new DateTimeOffset(DateTime.Today).ToUnixTimeSeconds());
    Require(summary.NewItems >= 2, "dashboard reads restored queue state");
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

static async Task RequireThrowsAsync(Func<Task> action, string message)
{
    try
    {
        await action();
        throw new InvalidOperationException("FAIL: " + message);
    }
    catch (ArgumentException)
    {
    }
}
