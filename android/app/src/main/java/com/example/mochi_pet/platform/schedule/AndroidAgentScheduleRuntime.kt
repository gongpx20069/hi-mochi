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
import androidx.work.await
import androidx.core.net.toUri
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.R
import com.example.mochi_pet.core.schedule.AgentSchedule
import com.example.mochi_pet.core.schedule.AgentScheduleController
import com.example.mochi_pet.core.schedule.AgentScheduleRun
import com.example.mochi_pet.core.schedule.ScheduleRunState
import com.example.mochi_pet.core.schedule.toDraft
import java.time.Instant
import java.time.Duration
import kotlinx.coroutines.flow.first
import com.example.mochi_pet.core.tasks.TaskStatus

class AndroidAgentScheduleController(
    private val context: Context,
) : AgentScheduleController {
    private val alarmManager =
        context.getSystemService(AlarmManager::class.java)

    override suspend fun sync(schedule: AgentSchedule) {
        cancelAlarm(schedule.id)
        if (schedule.type == com.example.mochi_pet.core.schedule.AgentScheduleType.EVERY &&
            (context.applicationContext as MochiApplication).agentScheduleStore.activeRuns()
                .any { it.scheduleId == schedule.id && !it.manual }) return
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
            .cancelAllWorkByTag(workName(id)).await()
    }

    override suspend fun runNow(id: String): AgentScheduleRun =
        requireNotNull(dispatch(id, manual = true))

    suspend fun taskStatuses(ids: List<String>): Map<String, TaskStatus> =
        ids.mapNotNull { id ->
            val work = WorkManager.getInstance(context)
                .getWorkInfosByTagFlow(workName(id)).first()
                .firstOrNull { !it.state.isFinished }
            work?.let {
                id to if (it.state == androidx.work.WorkInfo.State.RUNNING) {
                    TaskStatus.RUNNING
                } else {
                    TaskStatus.QUEUED
                }
            }
        }.toMap()

    suspend fun stopRun(id: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(workName(id)).await()
        val application = context.applicationContext as MochiApplication
        application.agentScheduleStore.activeRuns().filter { it.scheduleId == id }.forEach {
            application.agentScheduleStore.finishRun(it.id, ScheduleRunState.CANCELLED)?.let { plan -> sync(plan) }
        }
    }

    suspend fun syncAll() {
        val application = context.applicationContext as MochiApplication
        val store = application.agentScheduleStore
        store.activeRuns().forEach { run ->
            val work = WorkManager.getInstance(context).getWorkInfoByIdFlow(java.util.UUID.fromString(run.id)).first()
            when {
                work == null && run.state == ScheduleRunState.QUEUED -> enqueueRun(run)
                work == null || work.state.isFinished -> store.finishRun(
                    run.id, ScheduleRunState.INTERRUPTED, error = "Execution ended without a final result; it was not replayed",
                )
            }
        }
        store.list().forEach { plan ->
            val repaired = if (plan.enabled && plan.nextRunAt == null) {
                store.set(plan.id, plan.toDraft())
            } else plan
            sync(repaired)
        }
    }

    suspend fun dispatch(id: String, manual: Boolean): AgentScheduleRun? {
        val store = (context.applicationContext as MochiApplication).agentScheduleStore
        val run = store.queue(id, manual, Instant.now()) ?: return null
        if (run.state == ScheduleRunState.QUEUED) enqueueRun(run)
        store.get(id)?.let { sync(it) }
        return run
    }

    private suspend fun enqueueRun(run: AgentScheduleRun) {
        val data = Data.Builder()
            .putString(KEY_RUN_ID, run.id)
            .build()
        val request = OneTimeWorkRequestBuilder<AgentScheduleWorker>()
            .setId(java.util.UUID.fromString(run.id))
            .addTag(workName(run.scheduleId))
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "schedule-run-${run.id}",
            ExistingWorkPolicy.KEEP,
            request,
        ).await()
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
        const val KEY_RUN_ID = "run_id"
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
        val request = OneTimeWorkRequestBuilder<AgentScheduleDispatchWorker>()
            .setInputData(Data.Builder().putString(AndroidAgentScheduleController.KEY_SCHEDULE_ID, id).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("schedule-dispatch-$id", ExistingWorkPolicy.KEEP, request)
    }
}

class AgentScheduleDispatchWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(AndroidAgentScheduleController.KEY_SCHEDULE_ID) ?: return Result.failure()
        val application = applicationContext as MochiApplication
        if (application.agentScheduleStore.get(id) == null) return Result.success()
        application.agentScheduleController.dispatch(id, manual = false)
        return Result.success()
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
        val application = applicationContext as MochiApplication
        application.agentScheduleController.syncAll()
        application.userReminderController.sync()
        return Result.success()
    }
}

class AgentScheduleWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(
            AndroidAgentScheduleController.KEY_RUN_ID,
        ) ?: return Result.failure()
        val application = applicationContext as MochiApplication
        return if (application.executeAgentSchedule(id)) {
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
    scheduleId: String,
    runId: String,
) {
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            SCHEDULE_CHANNEL_ID,
            "Agent schedules",
            NotificationManager.IMPORTANCE_DEFAULT,
        ),
    )
    val launchIntent = Intent(this, com.example.mochi_pet.MainActivity::class.java)
        .setData("mochi://planner/results/${android.net.Uri.encode(runId)}".toUri())
        .putExtra("planner_run_id", runId).putExtra("planner_schedule_id", scheduleId)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val contentIntent = PendingIntent.getActivity(this, 0, launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
        manager.notify(runId, 0, notification)
    } catch (error: SecurityException) {
        Log.w(
            "MochiSchedule",
            "notification_permission_denied",
            error,
        )
    }
}

private const val SCHEDULE_CHANNEL_ID = "agent_schedules"
