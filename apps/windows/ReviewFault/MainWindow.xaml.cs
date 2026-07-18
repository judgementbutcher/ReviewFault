using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using ReviewFault.Core;
using ReviewFault.Data;
using Windows.Storage.Pickers;
using WinRT.Interop;
using System.Text.Json;
using System.Text.RegularExpressions;
using ReviewFault.Components;
using ReviewFault.Services;
using ReviewFault.ViewModels;
using Windows.Storage;

namespace ReviewFault;

public sealed class MainWindow : Window
{
    private readonly AppViewModel viewModel = new();
    private readonly Grid Root = new()
    {
        Background = new SolidColorBrush(DesignTokens.LightBackground),
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

    public MainWindow()
    {
        Navigation.Content = Root;
        Navigation.MenuItems.Add(new NavigationViewItem { Content = "今日", Tag = "today", Icon = new SymbolIcon(Symbol.Home) });
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
            var storedTheme = ApplicationData.Current.LocalSettings.Values["appearance.theme"] as string ?? "system";
            Root.RequestedTheme = storedTheme switch {
                "light" => ElementTheme.Light, "dark" => ElementTheme.Dark, _ => ElementTheme.Default };
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
            Body($"预计 {summary.EstimatedMinutes} 分钟"),
            ActionButton("开始复习", async () => await ShowReviewAsync())));
        panel.Children.Add(Heading("快速记录", 20));
        panel.Children.Add(ActionButton("导入数学题面", PickMathImageAsync));
        panel.Children.Add(ActionButton("新建 408 记忆卡", ShowMemoryEditorAsync));
        panel.Children.Add(ActionButton("浏览 / 搜索题库", () => ShowLibraryAsync("")));
        panel.Children.Add(Heading("数据与备份", 20));
        panel.Children.Add(ActionButton("导出完整备份", ExportBackupAsync));
        panel.Children.Add(ActionButton("从备份恢复", RestoreBackupAsync));
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
            ItemsSource = new[] { "跟随系统", "浅色", "深色" },
            SelectedIndex = (local["appearance.theme"] as string) switch { "light" => 1, "dark" => 2, _ => 0 } };
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
            var themeValue = appearance.SelectedIndex switch { 1 => "light", 2 => "dark", _ => "system" };
            local["appearance.theme"] = themeValue;
            local["reminder.enabled"] = reminder.IsOn;
            local["reminder.time"] = TimeOnly.FromTimeSpan(reminderTime.Time).ToString("HH:mm");
            var selectedMask = 0;
            for (var index = 0; index < weekdayChecks.Length; index++)
                if (weekdayChecks[index].IsChecked == true) selectedMask |= 1 << index;
            local["reminder.weekdays"] = selectedMask;
            Root.RequestedTheme = themeValue switch {
                "light" => ElementTheme.Light, "dark" => ElementTheme.Dark, _ => ElementTheme.Default };
            await (reminderService?.CheckAsync() ?? Task.CompletedTask);
            await MessageAsync("设置已保存；只影响此后的作答。");
        }));
        panel.Children.Add(Heading("数据", 20));
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
        var row = await viewModel.Repository.NextForReviewAsync(DateTimeOffset.UtcNow.ToUnixTimeSeconds());
        if (row is null)
        {
            await MessageAsync("当前没有到期内容，可以先新建一张卡片。");
            return;
        }
        var startedAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var panel = PagePanel();
        panel.Children.Add(Body(row.Kind == "math_problem" ? "数学 · 重做" : "408 · 主动回忆"));
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
                var result = await viewModel.Repository.ReviewAsync(
                    row, rating, reviewedAt, checked((int)(reviewedAt - startedAt)), mathResult);
                await MessageAsync($"已保存，下次间隔约 {FormatInterval(result.ScheduledDays)}。");
                await ShowReviewAsync();
            }));
        }
        answerPanel.Children.Add(ratings);
        panel.Children.Add(answerPanel);
        panel.Children.Add(ActionButton("返回首页", ShowHomeAsync));
        SetPage(panel);
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
    };

    private static TextBlock Heading(string value, double size) => new()
    {
        Text = value,
        FontSize = size,
        FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            Foreground = new SolidColorBrush(DesignTokens.LightHeading),
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
            Background = new SolidColorBrush(Windows.UI.Color.FromArgb(255, 255, 255, 255)),
            Padding = DesignTokens.CardPadding,
            CornerRadius = DesignTokens.CardRadius,
        };
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
