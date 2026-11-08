package com.example.mochi_pet.platform.schedule

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.mochi_pet.MainActivity
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.R
import com.example.mochi_pet.core.database.PlannerStore
import com.example.mochi_pet.core.model.ReminderReadiness
import com.example.mochi_pet.core.model.TodoStatus
import com.example.mochi_pet.core.model.UserReminderController
import com.example.mochi_pet.core.settings.AppLanguage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidUserReminderController(
    private val context: Context,
    private val store: PlannerStore,
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
) : UserReminderController {
    private val mutex = Mutex()
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override fun readiness(): ReminderReadiness {
        val channel = notifications.getNotificationChannel(CHANNEL)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            channel?.importance == NotificationManager.IMPORTANCE_NONE) return ReminderReadiness.NOTIFICATIONS_DISABLED
        return if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) ReminderReadiness.READY else ReminderReadiness.APPROXIMATE
    }

    fun registerReconciliation() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "user-reminder-reconcile", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UserReminderReconcileWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    override suspend fun sync(): ReminderReadiness = mutex.withLock {
        val chinese = AppLanguage.localizedContext(context).resources.configuration.locales[0].language == "zh"
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL, if (chinese) "用户日程" else "User schedules", NotificationManager.IMPORTANCE_DEFAULT,
        ))
        val intent = PendingIntent.getBroadcast(context, 0,
            Intent(context, UserReminderReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.cancel(intent)
        val ready = readiness()
        if (ready == ReminderReadiness.NOTIFICATIONS_DISABLED) return@withLock ready
        val now = clock.instant()
        val start = now.minusSeconds(86400)
        val end = now.plusSeconds(7 * 86400)
        val pending = store.listCalendarReminders(start, end).map { event ->
            Reminder("${event.occurrenceId}@${event.reminderAt}", event.occurrenceId, event.title,
                requireNotNull(event.reminderAt), event.startAt.atZone(event.timezone).toLocalDate())
        } + store.listTodosByStatus(TodoStatus.ACTIVE).mapNotNull { todo ->
            todo.reminderAt?.takeIf { it >= start && it < end }?.let {
                Reminder("${todo.id}@$it", todo.id, todo.content, it, todo.scheduledDate ?: it.atZone(ZoneId.systemDefault()).toLocalDate())
            }
        }
        val remaining = pending.filterNot { store.reminderDelivered(it.key) }.sortedBy { it.at }
        for (reminder in remaining.filter { it.at <= now }) {
            val open = Intent(context, MainActivity::class.java)
                .setAction("com.example.mochi_pet.OPEN_USER_SCHEDULE")
                .setData("mochi://planner/${reminder.date}?item=${android.net.Uri.encode(reminder.occurrenceId)}".toUri())
                .putExtra(EXTRA_PLANNER_DATE, reminder.date.toString())
                .putExtra("planner_occurrence_id", reminder.occurrenceId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val content = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(reminder.title)
                .setContentText(reminder.date.toString()).setContentIntent(content)
                .setOnlyAlertOnce(true).setAutoCancel(true).build()
            try {
                notifications.notify(reminder.key, 0, notification)
            } catch (error: SecurityException) {
                android.util.Log.w("MochiReminder", "Notification permission changed during delivery", error)
                return@withLock ReminderReadiness.NOTIFICATIONS_DISABLED
            }
            store.recordReminder(reminder.key)
        }
        remaining.firstOrNull { it.at > now }?.let {
            if (ready == ReminderReadiness.READY) {
                try {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.at.toEpochMilli(), intent)
                } catch (error: SecurityException) {
                    android.util.Log.w("MochiReminder", "Exact alarm permission changed; using approximate delivery", error)
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.at.toEpochMilli(), intent)
                    return@withLock ReminderReadiness.APPROXIMATE
                }
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.at.toEpochMilli(), intent)
            }
        }
        ready
    }

    private data class Reminder(val key: String, val occurrenceId: String, val title: String, val at: Instant, val date: LocalDate)

    companion object {
        private const val CHANNEL = "user_schedules"
        const val EXTRA_PLANNER_DATE = "planner_date"
    }
}

class UserReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                (context.applicationContext as MochiApplication).userReminderController.sync()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                android.util.Log.e("MochiReminder", "User reminder delivery failed", error)
            } finally {
                pending.finish()
            }
        }
    }
}

class UserReminderReconcileWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        (applicationContext as MochiApplication).userReminderController.sync()
        return Result.success()
    }
}
