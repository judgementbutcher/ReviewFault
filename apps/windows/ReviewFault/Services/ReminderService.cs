using Microsoft.UI.Dispatching;
using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;
using ReviewFault.Data;
using Windows.Storage;

namespace ReviewFault.Services;

public sealed class ReminderService
{
    private readonly AppRepository repository;
    private readonly DispatcherQueueTimer timer;
    private DateOnly? lastNotificationDate;

    public ReminderService(AppRepository repository, DispatcherQueue dispatcher)
    {
        this.repository = repository;
        timer = dispatcher.CreateTimer();
        timer.Interval = TimeSpan.FromMinutes(1);
        timer.IsRepeating = true;
        timer.Tick += async (_, _) => await CheckAsync();
        try { AppNotificationManager.Default.Register(); } catch { }
        timer.Start();
    }

    public async Task CheckAsync()
    {
        var values = ApplicationData.Current.LocalSettings.Values;
        if (values["reminder.enabled"] is not true) return;
        var nowLocal = DateTime.Now;
        var weekdayMask = values["reminder.weekdays"] is int storedMask ? storedMask : 0x7f;
        var weekdayBit = 1 << (((int)nowLocal.DayOfWeek + 6) % 7);
        if ((weekdayMask & weekdayBit) == 0) return;
        var configured = values["reminder.time"] as string ?? "20:00";
        if (!TimeOnly.TryParse(configured, out var time) ||
            nowLocal.Hour != time.Hour || nowLocal.Minute != time.Minute ||
            lastNotificationDate == DateOnly.FromDateTime(nowLocal)) return;
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var summary = await repository.DashboardAsync(now, new DateTimeOffset(DateTime.Today).ToUnixTimeSeconds());
        if (summary.Overdue + summary.DueToday == 0) return;
        var notification = new AppNotificationBuilder()
            .AddText("ReviewFault")
            .AddText($"有 {summary.Overdue + summary.DueToday} 条内容等待复习")
            .BuildNotification();
        AppNotificationManager.Default.Show(notification);
        lastNotificationDate = DateOnly.FromDateTime(nowLocal);
    }
}
