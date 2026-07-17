package cn.reviewfault.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import cn.reviewfault.app.data.AppDatabase
import java.time.LocalDateTime
import java.time.ZoneId

object ReminderScheduler {
    private const val REQUEST_CODE = 40802

    fun update(context: Context, enabled: Boolean, hour: Int, minute: Int) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE, Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarm.cancel(pending)
        if (!enabled) return
        var next = LocalDateTime.now().withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!next.isAfter(LocalDateTime.now())) next = next.plusDays(1)
        alarm.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            AlarmManager.INTERVAL_DAY,
            pending,
        )
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val now = java.time.Instant.now().epochSecond
        val start = java.time.ZonedDateTime.now().toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        val summary = AppDatabase.get(context).dashboard(now, start)
        if (summary.overdue + summary.dueToday == 0) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                "study-reminders", "学习提醒", NotificationManager.IMPORTANCE_DEFAULT,
            ))
        }
        val launch = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(context, "study-reminders")
            .setSmallIcon(cn.reviewfault.app.R.drawable.app_icon)
            .setContentTitle("ReviewFault")
            .setContentText("有 ${summary.overdue + summary.dueToday} 条内容等待复习")
            .setContentIntent(launch)
            .setAutoCancel(true)
            .build()
        manager.notify(408, notification)
    }
}
