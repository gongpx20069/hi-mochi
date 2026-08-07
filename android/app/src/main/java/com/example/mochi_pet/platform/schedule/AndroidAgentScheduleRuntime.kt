package com.example.mochi_pet.platform.schedule

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.R
import com.example.mochi_pet.core.schedule.AgentSchedule
import com.example.mochi_pet.core.schedule.AgentScheduleController
import java.time.Duration

class AndroidAgentScheduleController(
    private val context: Context,
) : AgentScheduleController {
    private val alarmManager =
        context.getSystemService(AlarmManager::class.java)

    override suspend fun sync(schedule: AgentSchedule) {
        cancelAlarm(schedule.id)
        val runAt = schedule.nextRunAt ?: return
        val intent = alarmIntent(schedule.id)
        val triggerAtMillis = runAt.toEpochMilli()
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        ) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                intent,
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                intent,
            )
        }
    }

    override suspend fun cancel(id: String) {
        cancelAlarm(id)
        WorkManager.getInstance(context)
            .cancelUniqueWork(workName(id))
    }

    override suspend fun runNow(id: String) {
        enqueue(id, manual = true)
    }

    suspend fun syncAll() {
        val application = context.applicationContext as MochiApplication
        application.agentScheduleStore.list().forEach { sync(it) }
    }

    fun enqueue(
        id: String,
        manual: Boolean,
    ) {
        val data = Data.Builder()
            .putString(KEY_SCHEDULE_ID, id)
            .putBoolean(KEY_MANUAL, manual)
            .build()
        val request = OneTimeWorkRequestBuilder<AgentScheduleWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(id),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun cancelAlarm(id: String) {
        alarmManager.cancel(alarmIntent(id))
    }

    private fun alarmIntent(id: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            Intent(context, AgentScheduleAlarmReceiver::class.java).apply {
                action = ACTION_RUN_SCHEDULE
                putExtra(KEY_SCHEDULE_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun workName(id: String) = "agent-schedule-$id"

    companion object {
        const val ACTION_RUN_SCHEDULE =
            "com.example.mochi_pet.action.RUN_AGENT_SCHEDULE"
        const val KEY_SCHEDULE_ID = "schedule_id"
        const val KEY_MANUAL = "manual"
    }
}

class AgentScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val id = intent.getStringExtra(
            AndroidAgentScheduleController.KEY_SCHEDULE_ID,
        ) ?: return
        AndroidAgentScheduleController(context.applicationContext)
            .enqueue(id, manual = false)
    }
}

class AgentScheduleSystemReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in SYSTEM_ACTIONS) {
            return
        }
        val request = OneTimeWorkRequestBuilder<AgentScheduleReconcileWorker>()
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            RECONCILE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private companion object {
        const val RECONCILE_WORK_NAME = "agent-schedule-reconcile"
        val SYSTEM_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action." +
                "SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}

class AgentScheduleReconcileWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        AndroidAgentScheduleController(applicationContext).syncAll()
        return Result.success()
    }
}

class AgentScheduleWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(
            AndroidAgentScheduleController.KEY_SCHEDULE_ID,
        ) ?: return Result.failure()
        val manual = inputData.getBoolean(
            AndroidAgentScheduleController.KEY_MANUAL,
            false,
        )
        val application = applicationContext as MochiApplication
        return if (application.executeAgentSchedule(id, manual)) {
            Result.success()
        } else {
            Result.failure()
        }
    }
}

fun Context.showAgentScheduleNotification(
    scheduleName: String,
    text: String,
    success: Boolean,
) {
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            SCHEDULE_CHANNEL_ID,
            "Agent schedules",
            NotificationManager.IMPORTANCE_DEFAULT,
        ),
    )
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    val contentIntent = launchIntent?.let {
        PendingIntent.getActivity(
            this,
            scheduleName.hashCode(),
            it,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE,
        )
    }
    val notification = NotificationCompat.Builder(this, SCHEDULE_CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(
            if (success) scheduleName else "$scheduleName failed",
        )
        .setContentText(text.take(180))
        .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1_000)))
        .setAutoCancel(true)
        .setContentIntent(contentIntent)
        .build()
    try {
        manager.notify(scheduleName.hashCode(), notification)
    } catch (error: SecurityException) {
        Log.w(
            "MochiSchedule",
            "notification_permission_denied",
            error,
        )
    }
}

private const val SCHEDULE_CHANNEL_ID = "agent_schedules"
