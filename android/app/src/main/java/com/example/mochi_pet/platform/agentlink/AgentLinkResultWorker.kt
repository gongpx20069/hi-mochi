package com.example.mochi_pet.platform.agentlink

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.example.mochi_pet.MochiApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeoutOrNull

class AgentLinkResultWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MochiApplication
        val success = withTimeoutOrNull(120_000) { app.agentLinkResultMonitor.refresh() } == true
        return if (success) Result.success() else Result.retry()
    }
}

suspend fun scheduleAgentLinkResultCollection(context: Context) {
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "agentlink-result-collection", ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<AgentLinkResultWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build(),
    ).await()
}
