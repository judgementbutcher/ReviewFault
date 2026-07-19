using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Media.Imaging;
using ReviewFault.Core;
using ReviewFault.Data;
using Windows.Storage.Pickers;
using WinRT.Interop;
using System.Text.Json;
using System.Text.RegularExpressions;
using ReviewFault.Components;
using ReviewFault.Controls;
using ReviewFault.Services;
using ReviewFault.ViewModels;
using Windows.Storage;

namespace ReviewFault;

public sealed class MainWindow : Window
{
    private readonly AppViewModel viewModel = new();
    private readonly Grid Root = new()
    {
        Background = DesignTokens.BackgroundBrush,
        RequestedTheme = ElementTheme.Dark,
    };
    private readonly NavigationView Navigation = new()
    {
        PaneTitle = "ReviewFault",
        IsBackButtonVisible = NavigationViewBackButtonVisible.Collapsed,
        IsSettingsVisible = false,
        PaneDisplayMode = NavigationViewPaneDisplayMode.Left,
    };
    private bool initialized;
    private ReminderService? reminderService;
    private int reviewSessionTargetSeconds;
    private int reviewSessionReviewed;
    private int reviewSessionElapsedSeconds;
    private bool reviewSessionAllowsNewItems = true;
    private readonly HashSet<string> reviewSessionSkippedItemIds = new(StringComparer.Ordinal);

    public MainWindow()
    {
        Navigation.Content = Root;
        Navigation.MenuItems.Add(new NavigationViewItem { Content = "今日", Tag = "today", Icon = new SymbolIcon(Symbol.Home) });
        Navigation.MenuItems.Add(new NavigationViewItem { Content = "洞察", Tag = "insights", Icon = new FontIcon { Glyph = "\uE9D2" } });
        Navigation.MenuItems.Add(new NavigationViewItem { Content = "题库", Tag = "library", Icon = new SymbolIcon(Symbol.Library) });
        Navigation.MenuItems.Add(new NavigationViewItem { Content = "添加", Tag = "add", Icon = new SymbolIcon(Symbol.Add) });
        Navigation.MenuItems.Add(new NavigationViewItem { Content = "设置", Tag = "settings", Icon = new SymbolIcon(Symbol.Setting) });
        Navigation.FooterMenuItems.Add(new NavigationViewItem { Content = "回收站", Tag = "trash", Icon = new SymbolIcon(Symbol.Delete) });
        Navigation.SelectionChanged += async (_, args) =>
        {
            if (args.SelectedItemContainer?.Tag is not string destination) return;
            switch (destination)
            {
                case "today": viewModel.Navigate(AppDestination.Today); await ShowHomeAsync(); break;
                case "insights": viewModel.Navigate(AppDestination.Insights); await ShowInsightsAsync(); break;
                case "library": viewModel.Navigate(AppDestination.Library); await ShowLibraryAsync(""); break;
                case "add": viewModel.Navigate(AppDestination.Add); ShowAdd(); break;
                case "settings": viewModel.Navigate(AppDestination.Settings); await ShowSettingsAsync(); break;
                case "trash": viewModel.Navigate(AppDestination.Trash); await ShowTrashAsync(); break;
            }
        };
        Content = Navigation;
        Title = "ReviewFault";
        AppWindow.Resize(new Windows.Graphics.SizeInt32(920, 720));
        Activated += async (_, _) =>
        {
            if (initialized) return;
            initialized = true;
            await InitializeAsync();
        };
    }

    private async Task InitializeAsync()
    {
        try
        {
            await viewModel.InitializeAsync();
            reminderService = new ReminderService(viewModel.Repository, DispatcherQueue);
            Root.RequestedTheme = ElementTheme.Dark;
            await ShowHomeAsync();
        }
        catch (Exception error)
        {
            Root.Children.Clear();
            Root.Children.Add(new TextBlock
            {
                Text = "启动失败：" + error.Message,
                Margin = new Thickness(32),
                TextWrapping = TextWrapping.Wrap,
            });
        }
    }

    private async Task ShowHomeAsync()
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var dayStart = new DateTimeOffset(DateTime.Today).ToUnixTimeSeconds();
        var summary = await viewModel.Repository.DashboardAsync(now, dayStart);
        var panel = PagePanel();
        panel.Children.Add(Heading("ReviewFault", 32));
        panel.Children.Add(Body("把今天该复习的交给算法"));
        panel.Children.Add(Card(
            Heading("今日学习", 22),
            Body($"逾期 {summary.Overdue}  ·  到期 {summary.DueToday}  ·  新内容 {summary.NewItems}"),
            Body($"本轮约 {summary.EstimatedMinutes} 分钟，到时自然收尾"),
            summary.DeferredDueMinutes > 0
                ? Body($"另有约 {summary.DeferredDueMinutes} 分钟到期内容，暂不加入新内容")
                : Body("本轮可以覆盖当前到期内容"),
            ActionButton("开始专注轮次", () => StartReviewSessionAsync(
                summary.EstimatedMinutes, summary.DeferredDueMinutes == 0))));
        panel.Children.Add(Card(
            Heading("学习负载预报", 20),
            Body($"明日 {summary.TomorrowDue} 条  ·  未来 7 天 {summary.NextSevenDaysDue} 条"),
            Body("提前看见波峰，更容易保持节奏。")));
        panel.Children.Add(Heading("快速记录", 20));
        panel.Children.Add(ActionButton("导入数学题面", PickMathImageAsync));
        panel.Children.Add(ActionButton("新建 408 记忆卡", ShowMemoryEditorAsync));
        panel.Children.Add(ActionButton("浏览 / 搜索题库", () => ShowLibraryAsync("")));
        panel.Children.Add(Heading("数据与备份", 20));
        panel.Children.Add(ActionButton("导出完整备份", ExportBackupAsync));
        panel.Children.Add(ActionButton("从备份恢复", RestoreBackupAsync));
        SetPage(panel);
    }

    private async Task ShowInsightsAsync()
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var dayStart = new DateTimeOffset(DateTime.Today).ToUnixTimeSeconds();
        var insights = await viewModel.Repository.InsightsAsync(now, dayStart);
        var panel = PagePanel();
        panel.Children.Add(Body("学习洞察 · LEARNING SIGNALS"));
        panel.Children.Add(Heading("看见你的积累", 32));
        panel.Children.Add(Body("趋势用来调整节奏，不用单次结果评价自己。"));
        var metrics = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
        metrics.Children.Add(MetricCard("今日复习", insights.ReviewsToday.ToString(), "次"));
        metrics.Children.Add(MetricCard("近 7 日正确", insights.AccuracyPercent.ToString(), "%"));
        metrics.Children.Add(MetricCard("连续学习", insights.StreakDays.ToString(), "天"));
        panel.Children.Add(metrics);
        panel.Children.Add(ChartCard("复习活跃度", "过去 7 天完成的复习次数",
            insights.Days.Select(day => (day.Label, day.Reviews)).ToArray(), DesignTokens.Brand));
        panel.Children.Add(ChartCard("未来负载", "今天起 7 天的到期内容",
            insights.Days.Select(day => (day.DueLabel, day.Due)).ToArray(), DesignTokens.Purple));
        var progress = new ProgressBar
        {
            Minimum = 0, Maximum = Math.Max(1, insights.ActiveItems), Value = insights.MasteredItems,
            Height = 8, HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        panel.Children.Add(Card(
            Heading("知识库进度", 20),
            Body($"{insights.MasteredItems} / {insights.ActiveItems} 条熟练内容 · 累计 {insights.TotalReviews} 次复习"),
            Body("稳定度达到 14 天且至少复习 3 次，记为熟练。"), progress));
        var subjectPanel = new StackPanel { Spacing = 12 };
        subjectPanel.Children.Add(Heading("学科分布", 20));
        subjectPanel.Children.Add(Body("各学科内容量与熟练占比"));
        if (insights.Subjects.Count == 0) subjectPanel.Children.Add(Body("添加内容后，这里会出现学科分布。"));
        foreach (var subject in insights.Subjects)
        {
            subjectPanel.Children.Add(Body($"{SubjectLabel(subject.Subject)}  ·  {subject.Mastered}/{subject.Total}"));
            subjectPanel.Children.Add(new ProgressBar
            {
                Minimum = 0, Maximum = Math.Max(1, subject.Total), Value = subject.Mastered,
                Height = 6, HorizontalAlignment = HorizontalAlignment.Stretch,
            });
        }
        panel.Children.Add(new Border
        {
            Child = subjectPanel, Background = DesignTokens.GlassBrush,
            BorderBrush = new SolidColorBrush(DesignTokens.GlassBorder), BorderThickness = new Thickness(1),
            Padding = DesignTokens.CardPadding, CornerRadius = DesignTokens.CardRadius,
        });
        SetPage(panel);
    }

    private void ShowAdd()
    {
        var panel = PagePanel();
        panel.Children.Add(Heading("添加", 32));
        panel.Children.Add(Body("快速记录数学题，或创建结构化 408 记忆卡。"));
        panel.Children.Add(ActionButton("导入数学题面", PickMathImageAsync));
        panel.Children.Add(ActionButton("新建 408 记忆卡", ShowMemoryEditorAsync));
        SetPage(panel);
    }

    private async Task ShowSettingsAsync()
    {
        var current = await viewModel.Repository.GetLearningPreferencesAsync();
        var local = ApplicationData.Current.LocalSettings.Values;
        var newLimit = new NumberBox { Header = "每日新 408 上限", Value = current.DailyNewMemoryLimit,
            Minimum = 0, Maximum = 500, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Inline };
        var minutes = new NumberBox { Header = "单次学习时长（分钟）", Value = current.SessionMinutes,
            Minimum = 1, Maximum = 240, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Inline };
        var memory = new ComboBox { Header = "408 记忆预设", ItemsSource = new[] { "省时", "均衡", "强化" },
            SelectedIndex = current.MemoryPreset switch { "time_saving" => 0, "reinforced" => 2, _ => 1 } };
        var math = new ComboBox { Header = "数学复习强度", ItemsSource = new[] { "密集", "均衡", "舒缓" },
            SelectedIndex = current.MathIntensity switch { "intensive" => 0, "relaxed" => 2, _ => 1 } };
        var schedulerGeneration = new ToggleSwitch {
            Header = "使用 v0.3 调度（关闭后继续使用 v0.2 参数）",
            IsOn = current.SchedulerGeneration == 3,
        };
        var appearance = new ComboBox { Header = "外观（仅本设备）",
            ItemsSource = new[] { "现代暗黑" }, SelectedIndex = 0 };
        var reminder = new ToggleSwitch { Header = "本地提醒（仅有待复习内容时发送）",
            IsOn = local["reminder.enabled"] is true };
        var reminderTime = new TimePicker { Header = "提醒时间", ClockIdentifier = "24HourClock" };
        if (TimeOnly.TryParse(local["reminder.time"] as string ?? "20:00", out var parsedTime))
            reminderTime.Time = parsedTime.ToTimeSpan();
        var storedMask = local["reminder.weekdays"] is int mask ? mask : 0x7f;
        var weekdayLabels = new[] { "一", "二", "三", "四", "五", "六", "日" };
        var weekdayChecks = weekdayLabels.Select((label, index) => new CheckBox {
            Content = "周" + label, IsChecked = (storedMask & (1 << index)) != 0,
            MinHeight = DesignTokens.MinimumTarget }).ToArray();
        var weekdayRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        foreach (var check in weekdayChecks) weekdayRow.Children.Add(check);
        var panel = PagePanel();
        panel.Children.Add(Heading("设置", 32));
        panel.Children.Add(Heading("学习与算法", 20));
        panel.Children.Add(newLimit); panel.Children.Add(minutes); panel.Children.Add(memory); panel.Children.Add(math);
        panel.Children.Add(schedulerGeneration);
        panel.Children.Add(appearance); panel.Children.Add(reminder); panel.Children.Add(reminderTime);
        panel.Children.Add(Body("提醒星期")); panel.Children.Add(weekdayRow);
        panel.Children.Add(ActionButton("保存学习设置", async () =>
        {
            await viewModel.Repository.SaveLearningPreferencesAsync(new LearningPreferences(
                (int)newLimit.Value, (int)minutes.Value,
                memory.SelectedIndex switch { 0 => "time_saving", 2 => "reinforced", _ => "balanced" },
                math.SelectedIndex switch { 0 => "intensive", 2 => "relaxed", _ => "balanced" },
                true, true, schedulerGeneration.IsOn ? 3 : 2));
            const string themeValue = "dark";
            local["appearance.theme"] = themeValue;
            local["reminder.enabled"] = reminder.IsOn;
            local["reminder.time"] = TimeOnly.FromTimeSpan(reminderTime.Time).ToString("HH:mm");
            var selectedMask = 0;
            for (var index = 0; index < weekdayChecks.Length; index++)
                if (weekdayChecks[index].IsChecked == true) selectedMask |= 1 << index;
            local["reminder.weekdays"] = selectedMask;
            Root.RequestedTheme = ElementTheme.Dark;
            await (reminderService?.CheckAsync() ?? Task.CompletedTask);
            await MessageAsync("设置已保存；只影响此后的作答。");
        }));
        panel.Children.Add(Heading("数据", 20));
        panel.Children.Add(Heading("账号与同步", 20));
        var endpoint = new TextBox { Header = "同步服务地址", Text = viewModel.SyncEndpoint };
        panel.Children.Add(endpoint);
        if (viewModel.AccountId is null)
        {
            var email = new TextBox { Header = "邮箱" };
            var password = new PasswordBox { Header = "密码（至少 12 位）" };
            var invitation = new TextBox { Header = "邀请码（注册时需要）" };
            panel.Children.Add(email); panel.Children.Add(password); panel.Children.Add(invitation);
            panel.Children.Add(ActionButton("注册", async () =>
            {
                await viewModel.RegisterAsync(endpoint.Text, email.Text, password.Password, invitation.Text);
                await MessageAsync("注册申请已提交，请先完成邮箱验证再登录。");
            }));
            panel.Children.Add(ActionButton("登录并同步", async () =>
            {
                await viewModel.LoginAsync(endpoint.Text, email.Text, password.Password);
                await MessageAsync("已登录并完成首次同步。");
                await ShowSettingsAsync();
            }));
        }
        else
        {
            panel.Children.Add(Body($"账号 {viewModel.AccountId[..Math.Min(8, viewModel.AccountId.Length)]}… · " +
                $"待上传 {viewModel.PendingSyncCount} 项"));
            if (viewModel.LastSyncedAt is long last)
                panel.Children.Add(Body($"上次同步 {DateTimeOffset.FromUnixTimeSeconds(last).ToLocalTime():g}"));
            panel.Children.Add(ActionButton("立即同步", async () =>
            {
                await viewModel.SyncNowAsync(); await MessageAsync("同步完成。"); await ShowSettingsAsync();
            }));
            panel.Children.Add(ActionButton("退出账号", async () =>
            {
                await viewModel.LogoutAsync(); await MessageAsync("已退出；本地数据仍保留在此设备。");
                await ShowSettingsAsync();
            }));
        }
        panel.Children.Add(ActionButton("导出完整备份", ExportBackupAsync));
        panel.Children.Add(ActionButton("从备份恢复", RestoreBackupAsync));
        panel.Children.Add(Body("主题、提醒时间、星期和通知权限保存在本设备，不会被备份恢复覆盖。"));
        SetPage(panel);
    }

    private async Task ShowTrashAsync()
    {
        var rows = await viewModel.Repository.TrashAsync();
        var panel = PagePanel();
        panel.Children.Add(Heading("回收站", 32));
        panel.Children.Add(Body("删除不会清除复习日志或媒体，可随时恢复。"));
        if (rows.Count == 0) panel.Children.Add(Body("回收站为空"));
        foreach (var row in rows)
            panel.Children.Add(Card(Body(row.Prompt.Length == 0 ? "图片题面" : row.Prompt),
                ActionButton("恢复", async () => { await viewModel.Repository.RestoreAsync(new[] { row.Id }); await ShowTrashAsync(); })));
        SetPage(panel);
    }

    private async Task ShowReviewAsync()
    {
        if ((reviewSessionReviewed > 0 || reviewSessionSkippedItemIds.Count > 0) &&
            reviewSessionElapsedSeconds >= reviewSessionTargetSeconds)
        {
            await FinishReviewSessionAsync();
            return;
        }
        var row = await viewModel.Repository.NextForReviewAsync(
            DateTimeOffset.UtcNow.ToUnixTimeSeconds(), reviewSessionAllowsNewItems,
            reviewSessionSkippedItemIds);
        if (row is null)
        {
            await FinishReviewSessionAsync(
                reviewSessionReviewed == 0 && reviewSessionSkippedItemIds.Count == 0
                    ? "当前没有待复习内容。"
                    : null);
            return;
        }
        var startedAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var panel = PagePanel();
        panel.Children.Add(Body(row.Kind == "math_problem" ? "数学 · 重做" : "408 · 主动回忆"));
        panel.Children.Add(Body(
            $"本轮已完成 {reviewSessionReviewed} 条 · 跳过 {reviewSessionSkippedItemIds.Count} 条 · " +
            $"已用约 {(reviewSessionElapsedSeconds + 59) / 60} 分钟"));
        panel.Children.Add(Heading(
            row.Kind == "math_problem" ? "先独立完成，再看答案" : "先在脑中或纸上作答", 28));
        if (!string.IsNullOrWhiteSpace(row.Prompt)) panel.Children.Add(Card(Body(ReviewPrompt(row))));
        foreach (var mediaPath in await viewModel.Repository.MediaPathsAsync(row.Id))
        {
            panel.Children.Add(new Image
            {
                Source = new BitmapImage(new Uri(viewModel.Repository.ResolveMediaPath(mediaPath))),
                MaxHeight = 420,
                Stretch = Stretch.Uniform,
                HorizontalAlignment = HorizontalAlignment.Left,
            });
        }
        if (row.Kind == "math_problem")
            panel.Children.Add(ActionButton("补充解答、错因与关键提示", () => ShowMathDetailsEditorAsync(row)));
        InkPad? inkPad = null;
        if (row.Kind == "math_problem")
        {
            panel.Children.Add(Heading("演算", 19));
            inkPad = new InkPad();
            inkPad.DocumentChanged += async (_, document) =>
                await viewModel.Repository.SaveInkDraftAsync(row.Id, document);
            panel.Children.Add(inkPad);
        }
        var hints = StructuredItems(row);
        if (row.TemplateType == "layered_hint" && hints.Count > 0)
        {
            var shownHints = 0;
            var hintPanel = new StackPanel { Spacing = 6 };
            var hintButton = ActionButton("显示一层提示", () =>
            {
                if (shownHints < hints.Count)
                    hintPanel.Children.Add(Card(Body($"提示 {shownHints + 1}：{hints[shownHints++]}")));
                return Task.CompletedTask;
            });
            panel.Children.Add(hintPanel);
            panel.Children.Add(hintButton);
        }
        var answerPanel = new StackPanel { Spacing = 12, Visibility = Visibility.Collapsed };
        var reveal = ActionButton("显示答案 / 提交作答", () =>
        {
            answerPanel.Visibility = Visibility.Visible;
            return Task.CompletedTask;
        });
        panel.Children.Add(reveal);
        answerPanel.Children.Add(Card(
            Body("参考答案"),
            Body(string.IsNullOrWhiteSpace(ReviewAnswer(row)) ? "尚未填写解答；仍可按实际结果评分。" : ReviewAnswer(row))));
        answerPanel.Children.Add(Heading("这次完成得怎样？", 19));
        var ratings = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        (string Label, Rating Rating, string? MathResult)[] actions = row.Kind == "math_problem"
            ? new[] {
                ("不会", Rating.Again, (string?)"again"), ("做错", Rating.Again, (string?)"wrong"),
                ("勉强做对", Rating.Hard, (string?)"effortful"), ("熟练", Rating.Easy, (string?)"fluent") }
            : new[] {
                ("忘记", Rating.Again, (string?)null), ("困难", Rating.Hard, null),
                ("正确", Rating.Good, null), ("轻松", Rating.Easy, null) };
        foreach (var (label, rating, mathResult) in actions)
        {
            ratings.Children.Add(ActionButton(label, async () =>
            {
                var reviewedAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
                if (inkPad?.HasInk == true)
                    await viewModel.Repository.SaveInkDraftAsync(row.Id, inkPad.Snapshot());
                var result = await viewModel.Repository.ReviewAsync(
                    row, rating, reviewedAt, checked((int)(reviewedAt - startedAt)), mathResult);
                await viewModel.SyncNowAsync();
                reviewSessionElapsedSeconds += checked((int)(reviewedAt - startedAt));
                reviewSessionReviewed++;
                if (reviewSessionElapsedSeconds >= reviewSessionTargetSeconds)
                {
                    await FinishReviewSessionAsync(
                        $"本轮结束：完成 {reviewSessionReviewed} 条 · " +
                        $"跳过 {reviewSessionSkippedItemIds.Count} 条 · " +
                        $"约 {(reviewSessionElapsedSeconds + 59) / 60} 分钟。" +
                        $"最近一条下次间隔约 {FormatInterval(result.ScheduledDays)}。");
                    return;
                }
                await MessageAsync($"已保存，下次间隔约 {FormatInterval(result.ScheduledDays)}。");
                await ShowReviewAsync();
            }));
        }
        answerPanel.Children.Add(ratings);
        panel.Children.Add(answerPanel);
        panel.Children.Add(ActionButton("本轮跳过（不评分）", async () =>
        {
            var skippedAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            reviewSessionElapsedSeconds += checked((int)Math.Max(0, skippedAt - startedAt));
            reviewSessionSkippedItemIds.Add(row.Id);
            await ShowReviewAsync();
        }));
        panel.Children.Add(ActionButton("提前结束本轮", () => FinishReviewSessionAsync()));
        SetPage(panel);
    }

    private async Task StartReviewSessionAsync(int targetMinutes, bool allowNewItems)
    {
        reviewSessionTargetSeconds = Math.Max(1, targetMinutes) * 60;
        reviewSessionReviewed = 0;
        reviewSessionElapsedSeconds = 0;
        reviewSessionAllowsNewItems = allowNewItems;
        reviewSessionSkippedItemIds.Clear();
        Navigation.IsPaneVisible = false;
        await ShowReviewAsync();
    }

    private async Task FinishReviewSessionAsync(string? overrideMessage = null)
    {
        var summary = overrideMessage ??
            $"本轮结束：完成 {reviewSessionReviewed} 条 · " +
            $"跳过 {reviewSessionSkippedItemIds.Count} 条 · " +
            $"约 {(reviewSessionElapsedSeconds + 59) / 60} 分钟。";
        Navigation.IsPaneVisible = true;
        await ShowHomeAsync();
        await MessageAsync(summary);
    }

    private async Task ShowMemoryEditorAsync()
    {
        var template = new ComboBox
        {
            Header = "模板",
            ItemsSource = new[] { "问答", "填空", "分层提示", "枚举", "对比" },
            SelectedIndex = 0,
        };
        var subject = new ComboBox
        {
            Header = "科目",
            ItemsSource = new[] { "数据结构", "计算机组成原理", "操作系统", "计算机网络" },
            SelectedIndex = 0,
        };
        var prompt = new TextBox { Header = "问题 / 填空题干", AcceptsReturn = true };
        var answer = new TextBox { Header = "答案", AcceptsReturn = true };
        var structured = new TextBox { Header = "提示或枚举要点（每行一条）", AcceptsReturn = true };
        var content = new StackPanel { Spacing = 10 };
        content.Children.Add(subject);
        content.Children.Add(template);
        content.Children.Add(prompt);
        content.Children.Add(answer);
        content.Children.Add(structured);
        var dialog = new ContentDialog
        {
            XamlRoot = Root.XamlRoot,
            Title = "新建 408 记忆卡",
            Content = content,
            PrimaryButtonText = "保存",
            CloseButtonText = "取消",
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        var templates = new[] { "qa", "cloze", "layered_hint", "enumeration", "comparison" };
        var subjects = new[] { "data_structures", "computer_organization", "operating_systems", "computer_networks" };
        var lines = structured.Text.Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
        var validationError = MemoryValidationError(
            templates[template.SelectedIndex], prompt.Text, answer.Text, lines);
        if (validationError is not null)
        {
            await MessageAsync(validationError);
            return;
        }
        await viewModel.Repository.CreateMemoryCardAsync(
            templates[template.SelectedIndex], subjects[subject.SelectedIndex],
            prompt.Text, answer.Text, lines);
        await ShowHomeAsync();
    }

    private async Task ShowLibraryAsync(string queryText)
    {
        var rows = await viewModel.Repository.SearchAsync(queryText);
        var panel = PagePanel();
        panel.Children.Add(Heading("我的题库", 28));
        var query = new TextBox { PlaceholderText = "搜索题干、答案或来源", Text = queryText };
        panel.Children.Add(query);
        panel.Children.Add(ActionButton("搜索", () => ShowLibraryAsync(query.Text)));
        panel.Children.Add(Body($"{rows.Count} 条内容"));
        foreach (var row in rows)
        {
            panel.Children.Add(Card(
                Body(row.Kind == "math_problem" ? "数学错题" : SubjectLabel(row.Subject)),
                Heading(string.IsNullOrWhiteSpace(row.Prompt) ? "图片题面" : row.Prompt, 18),
                Body(row.State == CardState.New ? "新内容" : $"已复习 {row.Repetitions} 次"),
                ActionButton("查看", () => ShowLibraryDetailAsync(row))));
        }
        panel.Children.Add(ActionButton("返回首页", ShowHomeAsync));
        SetPage(panel);
    }

    private async Task ShowLibraryDetailAsync(StudyRow row)
    {
        var panel = PagePanel();
        panel.Children.Add(Body(row.Kind == "math_problem" ? "数学错题" : SubjectLabel(row.Subject)));
        panel.Children.Add(Heading(string.IsNullOrWhiteSpace(row.Prompt) ? "图片题面" : row.Prompt, 26));
        foreach (var mediaPath in await viewModel.Repository.MediaPathsAsync(row.Id))
            panel.Children.Add(new Image
            {
                Source = new BitmapImage(new Uri(viewModel.Repository.ResolveMediaPath(mediaPath))),
                MaxHeight = 420,
                Stretch = Stretch.Uniform,
                HorizontalAlignment = HorizontalAlignment.Left,
            });
        panel.Children.Add(Card(Body("答案 / 解答"), Body(string.IsNullOrWhiteSpace(ReviewAnswer(row)) ? "尚未填写" : ReviewAnswer(row))));
        if (row.Kind == "math_problem")
            panel.Children.Add(ActionButton("编辑错题复盘", () => ShowMathDetailsEditorAsync(row)));
        else
            panel.Children.Add(ActionButton("编辑记忆卡", () => ShowMemoryCardEditorAsync(row)));
        panel.Children.Add(ActionButton("返回题库", () => ShowLibraryAsync("")));
        SetPage(panel);
    }

    private static string SubjectLabel(string subject) => subject switch
    {
        "data_structures" => "408 · 数据结构",
        "computer_organization" => "408 · 计算机组成原理",
        "operating_systems" => "408 · 操作系统",
        "computer_networks" => "408 · 计算机网络",
        _ => subject,
    };

    private static IReadOnlyList<string> StructuredItems(StudyRow row)
    {
        try { return JsonSerializer.Deserialize<string[]>(row.StructuredJson) ?? Array.Empty<string>(); }
        catch (JsonException) { return Array.Empty<string>(); }
    }

    private static string ReviewPrompt(StudyRow row) => row.TemplateType == "cloze"
        ? Regex.Replace(row.Prompt, @"\{\{c\d+::(.*?)(?:::[^}]*)?}}", "[…]")
        : row.Prompt;

    private static string ReviewAnswer(StudyRow row) => row.TemplateType switch
    {
        "cloze" => string.Join('\n', Regex.Matches(
            row.Prompt, @"\{\{c\d+::(.*?)(?:::[^}]*)?}}")
            .Select(match => match.Groups[1].Value)),
        "enumeration" => string.Join('\n', StructuredItems(row).Select(item => "• " + item)),
        _ => row.Answer,
    };

    private static string? MemoryValidationError(
        string template, string prompt, string answer, IReadOnlyList<string> lines)
    {
        if (string.IsNullOrWhiteSpace(prompt)) return "题干不能为空";
        if (template is "qa" or "comparison" && string.IsNullOrWhiteSpace(answer))
            return "答案不能为空";
        if (template == "cloze" && !Regex.IsMatch(prompt, @"\{\{c\d+::.+?}}"))
            return "填空题干需要包含 {{c1::答案}} 标记";
        if (template == "layered_hint" &&
            (string.IsNullOrWhiteSpace(answer) || lines.Count == 0))
            return "分层提示卡需要答案和至少一层提示";
        if (template == "enumeration" && lines.Count < 2)
            return "枚举卡至少需要两个答案要点";
        return null;
    }

    private async Task ShowMathDetailsEditorAsync(StudyRow row)
    {
        var reason = new ComboBox
        {
            Header = "主要错因",
            ItemsSource = new[] { "未选择", "概念不清", "思路中断", "计算错误", "审题错误", "遗忘结论", "超时", "其他" },
            SelectedIndex = 0,
        };
        var solution = new TextBox { Header = "完整解答", Text = row.Answer, AcceptsReturn = true };
        var wrongStep = new TextBox { Header = "自己的关键错误步骤", AcceptsReturn = true };
        var hint = new TextBox { Header = "下次看到题时必须想起的一句提示", AcceptsReturn = true };
        var content = new StackPanel { Spacing = 10 };
        content.Children.Add(reason);
        content.Children.Add(solution);
        content.Children.Add(wrongStep);
        content.Children.Add(hint);
        var dialog = new ContentDialog
        {
            XamlRoot = Root.XamlRoot,
            Title = "完善数学错题",
            Content = content,
            PrimaryButtonText = "保存",
            CloseButtonText = "取消",
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        string?[] reasonValues = { null, "concept", "approach", "calculation", "misread", "forgotten_fact", "timeout", "other" };
        await viewModel.Repository.UpdateMathDetailsAsync(
            row.Id, solution.Text, wrongStep.Text, hint.Text, reasonValues[reason.SelectedIndex]);
        await ShowReviewAsync();
    }

    private async Task ShowMemoryCardEditorAsync(StudyRow row)
    {
        var prompt = new TextBox { Header = "题干", Text = row.Prompt, AcceptsReturn = true };
        var answer = new TextBox { Header = "答案", Text = row.Answer, AcceptsReturn = true };
        var content = new StackPanel { Spacing = 10 };
        content.Children.Add(prompt);
        content.Children.Add(answer);
        var dialog = new ContentDialog
        {
            XamlRoot = Root.XamlRoot,
            Title = "编辑 408 记忆卡",
            Content = content,
            PrimaryButtonText = "保存",
            CloseButtonText = "取消",
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        await viewModel.Repository.UpdateMemoryCardAsync(row.Id, prompt.Text, answer.Text);
        await ShowLibraryAsync("");
    }

    private async Task PickMathImageAsync()
    {
        var source = new TextBox { Header = "来源（可选）", PlaceholderText = "例如：张宇 1000 题 P32" };
        var sourceDialog = new ContentDialog
        {
            XamlRoot = Root.XamlRoot,
            Title = "数学错题快速录入",
            Content = source,
            PrimaryButtonText = "选择图片",
            CloseButtonText = "取消",
        };
        if (await sourceDialog.ShowAsync() != ContentDialogResult.Primary) return;
        var picker = new FileOpenPicker();
        picker.FileTypeFilter.Add(".jpg");
        picker.FileTypeFilter.Add(".jpeg");
        picker.FileTypeFilter.Add(".png");
        picker.FileTypeFilter.Add(".webp");
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(this));
        var files = await picker.PickMultipleFilesAsync();
        if (files.Count == 0) return;
        if (files.Count > 5)
        {
            await MessageAsync("每道题最多选择 5 张图片。");
            return;
        }
        await viewModel.Repository.CreateMathProblemAsync(files.Select(file => file.Path).ToArray(), source.Text);
        await ShowHomeAsync();
    }

    private async Task ExportBackupAsync()
    {
        var picker = new FileSavePicker { SuggestedFileName = $"ReviewFault-{DateTime.Today:yyyy-MM-dd}" };
        picker.FileTypeChoices.Add("ReviewFault 备份", new[] { ".reviewfault" });
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(this));
        var file = await picker.PickSaveFileAsync();
        if (file is null) return;
        await using var stream = await file.OpenStreamForWriteAsync();
        stream.SetLength(0);
        await viewModel.Repository.ExportBackupAsync(stream);
        await MessageAsync("完整备份已导出。");
    }

    private async Task RestoreBackupAsync()
    {
        var warning = new ContentDialog
        {
            XamlRoot = Root.XamlRoot,
            Title = "从备份恢复？",
            Content = "恢复会替换当前设备上的数据库和媒体。建议先导出当前备份。",
            PrimaryButtonText = "选择备份",
            CloseButtonText = "取消",
        };
        if (await warning.ShowAsync() != ContentDialogResult.Primary) return;
        var picker = new FileOpenPicker();
        picker.FileTypeFilter.Add(".reviewfault");
        picker.FileTypeFilter.Add(".zip");
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(this));
        var file = await picker.PickSingleFileAsync();
        if (file is null) return;
        await using var stream = await file.OpenStreamForReadAsync();
        await viewModel.Repository.RestoreBackupAsync(stream);
        await MessageAsync("数据已恢复。");
        await ShowHomeAsync();
    }

    private void SetPage(StackPanel content)
    {
        Root.Children.Clear();
        Root.Children.Add(new ScrollViewer
        {
            Content = content,
            HorizontalAlignment = HorizontalAlignment.Stretch,
        });
    }

    private static StackPanel PagePanel() => new()
    {
        Spacing = 16,
        Margin = new Thickness(34),
        MaxWidth = 900,
        HorizontalAlignment = HorizontalAlignment.Stretch,
        Transitions = new TransitionCollection { new EntranceThemeTransition { IsStaggeringEnabled = true } },
    };

    private static TextBlock Heading(string value, double size) => new()
    {
        Text = value,
        FontSize = size,
        FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
        Foreground = new SolidColorBrush(DesignTokens.DarkHeading),
        TextWrapping = TextWrapping.Wrap,
    };

    private static TextBlock Body(string value) => new()
    {
        Text = value,
        FontSize = 16,
        TextWrapping = TextWrapping.Wrap,
    };

    private static Border Card(params UIElement[] children)
    {
        var content = new StackPanel { Spacing = 10 };
        foreach (var child in children) content.Children.Add(child);
        return new Border
        {
            Child = content,
            Background = DesignTokens.GlassBrush,
            BorderBrush = new SolidColorBrush(DesignTokens.GlassBorder),
            BorderThickness = new Thickness(1),
            Padding = DesignTokens.CardPadding,
            CornerRadius = DesignTokens.CardRadius,
        };
    }

    private static Border MetricCard(string label, string value, string unit)
    {
        var number = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4 };
        number.Children.Add(new TextBlock
        {
            Text = value, FontSize = 30, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            Foreground = new SolidColorBrush(DesignTokens.Brand),
        });
        number.Children.Add(new TextBlock { Text = unit, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, 5) });
        var content = new StackPanel { Spacing = 4 };
        content.Children.Add(number); content.Children.Add(Body(label));
        return new Border
        {
            Child = content, Width = 190, Padding = new Thickness(18), CornerRadius = DesignTokens.CardRadius,
            Background = DesignTokens.GlassBrush, BorderBrush = new SolidColorBrush(DesignTokens.GlassBorder),
            BorderThickness = new Thickness(1),
        };
    }

    private static Border ChartCard(
        string title, string subtitle, IReadOnlyList<(string Label, int Value)> values,
        Windows.UI.Color color)
    {
        var chart = new Grid { Height = 160, ColumnSpacing = 10 };
        for (var index = 0; index < values.Count; index++)
            chart.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var maximum = Math.Max(1, values.Count == 0 ? 0 : values.Max(item => item.Value));
        for (var index = 0; index < values.Count; index++)
        {
            var item = values[index];
            var column = new StackPanel
            {
                Spacing = 5, VerticalAlignment = VerticalAlignment.Bottom,
                HorizontalAlignment = HorizontalAlignment.Stretch,
            };
            column.Children.Add(new TextBlock { Text = item.Value.ToString(), HorizontalAlignment = HorizontalAlignment.Center, FontSize = 12 });
            column.Children.Add(new Border
            {
                Height = 12 + 92d * item.Value / maximum,
                Background = new LinearGradientBrush
                {
                    StartPoint = new Windows.Foundation.Point(0, 0), EndPoint = new Windows.Foundation.Point(0, 1),
                    GradientStops =
                    {
                        new GradientStop { Color = color, Offset = 0 },
                        new GradientStop { Color = Windows.UI.Color.FromArgb(75, color.R, color.G, color.B), Offset = 1 },
                    },
                },
                CornerRadius = new CornerRadius(8, 8, 3, 3),
            });
            column.Children.Add(new TextBlock { Text = item.Label, HorizontalAlignment = HorizontalAlignment.Center, FontSize = 12, Opacity = .72 });
            Grid.SetColumn(column, index); chart.Children.Add(column);
        }
        return Card(Heading(title, 20), Body(subtitle), chart);
    }

    private static Button ActionButton(string label, Func<Task> action)
    {
        var button = new Button { Content = label, MinHeight = DesignTokens.MinimumTarget };
        button.Click += async (_, _) =>
        {
            button.IsEnabled = false;
            try { await action(); }
            finally { button.IsEnabled = true; }
        };
        return button;
    }

    private async Task MessageAsync(string message)
    {
        await new ContentDialog
        {
            XamlRoot = Root.XamlRoot,
            Title = "ReviewFault",
            Content = message,
            CloseButtonText = "知道了",
        }.ShowAsync();
    }

    private static string FormatInterval(double days) => days switch
    {
        < 1.0 / 24.0 => $"{Math.Round(days * 24 * 60)} 分钟",
        < 1.0 => $"{Math.Round(days * 24)} 小时",
        _ => $"{Math.Round(days)} 天",
    };
}
