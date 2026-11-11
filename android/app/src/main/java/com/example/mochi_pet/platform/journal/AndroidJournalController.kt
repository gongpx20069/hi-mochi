package com.example.mochi_pet.platform.journal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.core.agent.llm.OkHttpOpenAiChatClient
import com.example.mochi_pet.core.agent.llm.ProviderNetworkException
import com.example.mochi_pet.core.journal.DailyJournalController
import com.example.mochi_pet.core.journal.JournalDraft
import com.example.mochi_pet.core.journal.JournalGenerator
import com.example.mochi_pet.core.journal.JournalRepository
import com.example.mochi_pet.core.journal.JournalStatus
import com.example.mochi_pet.core.settings.AppLanguage
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AndroidJournalController(
    private val context: Context,
    private val repository: JournalRepository,
) : DailyJournalController {
    private val gate = Mutex()
    private val work get() = WorkManager.getInstance(context)

    override suspend fun setEnabled(enabled: Boolean) = gate.withLock {
        repository.setEnabled(enabled)
        if (enabled) reconcileLocked() else work.cancelAllWorkByTag(TAG).await()
        Unit
    }

    override suspend fun reconcile() = gate.withLock { reconcileLocked() }

    private suspend fun reconcileLocked() {
        if (!repository.settings.first().enabled) return
        val now = Instant.now()
        val next = now.atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant()
        work.enqueueUniqueWork("journal-tick-${next.toEpochMilli()}", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<JournalReconcileWorker>()
                .addTag(TAG).setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS).build()).await()
        repository.prepare().forEach {
            if (it.status == JournalStatus.GENERATING &&
                work.getWorkInfosForUniqueWorkFlow(name(it.date)).first().all { info -> info.state.isFinished }) {
                repository.interrupt(it)
            } else enqueue(it.date, ExistingWorkPolicy.KEEP)
        }
    }

    override suspend fun retry(date: LocalDate) = gate.withLock {
        repository.retry(date)
        enqueue(date, ExistingWorkPolicy.REPLACE)
    }

    override suspend fun delete(date: LocalDate) = gate.withLock {
        repository.delete(date)
        work.cancelUniqueWork(name(date)).await()
        Unit
    }

    private suspend fun enqueue(date: LocalDate, policy: ExistingWorkPolicy) {
        work.enqueueUniqueWork(name(date), policy,
            OneTimeWorkRequestBuilder<JournalWorker>()
                .addTag(TAG)
                .setInputData(Data.Builder().putString("date", date.toString())
                    .putBoolean("manual", policy == ExistingWorkPolicy.REPLACE).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()).await()
    }

    private fun name(date: LocalDate) = "daily-journal-$date"
    private companion object { const val TAG = "daily-journal" }
}

class JournalReconcileWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        (applicationContext as MochiApplication).journalController.reconcile()
        return Result.success()
    }
}

class JournalSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        WorkManager.getInstance(context).enqueueUniqueWork("journal-reconcile", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<JournalReconcileWorker>().build())
    }
}

class JournalWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val date = inputData.getString("date")?.let(LocalDate::parse) ?: return Result.failure()
        val app = applicationContext as MochiApplication
        val store = app.journalRepository
        val entry = store.claim(date, id.toString(), inputData.getBoolean("manual", false)) ?: return Result.success()
        return try {
            app.providerRuns.register(currentCoroutineContext().job)
            withTimeout(TimeUnit.MINUTES.toMillis(8)) {
                val evidence = store.evidence(entry)
                val result = if (evidence.conversations.isEmpty() && evidence.userSchedules.isEmpty() && evidence.mochiTasks.isEmpty()) {
                    JournalDraft("empty", "", "")
                } else {
                    JournalGenerator(OkHttpOpenAiChatClient()).generate(date, entry.zone,
                        AppLanguage.localizedContext(app).resources.configuration.locales[0].toLanguageTag(),
                        evidence, app.providerSettingsRepository.loadRuntimeConfig())
                }
                store.finish(entry, id.toString(), result, evidence)
            }
            Result.success()
        } catch (_: TimeoutCancellationException) {
            store.fail(entry, id.toString(), "Journal generation timed out")
            Result.failure()
        } catch (error: CancellationException) {
            withContext(NonCancellable) { store.fail(entry, id.toString(), "Journal generation was interrupted") }
            throw error
        } catch (_: ProviderNetworkException) {
            val retry = runAttemptCount < 2
            store.fail(entry, id.toString(), "Could not connect to the AI service", retryable = retry)
            if (retry) Result.retry() else Result.failure()
        } catch (error: Exception) {
            android.util.Log.w("DailyJournal", "Journal generation failed: ${error.javaClass.simpleName}")
            store.fail(entry, id.toString(), "Could not generate the journal. Check the AI connection and retry.")
            Result.failure()
        }
    }
}
