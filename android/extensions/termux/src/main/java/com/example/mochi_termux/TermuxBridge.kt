package com.example.mochi_termux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.edit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

internal enum class TermuxFailureReason { COMMAND_ACCESS, BACKGROUND_START, SERVICE_UNAVAILABLE, CHECK_FAILED }

internal class TermuxException(
    val code: String,
    message: String,
    val reason: TermuxFailureReason = TermuxFailureReason.CHECK_FAILED,
) : Exception(message)

internal data class CommandResult(val stdout: String, val exitCode: Int)

internal class TermuxBridge(private val context: Context) {
    private val preferences = context.getSharedPreferences("termux", Context.MODE_PRIVATE)

    fun installed(): Boolean = context.packageManager.getLaunchIntentForPackage("com.termux") != null

    fun available(): Boolean =
        context.checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED &&
            installed()

    fun connected(): Boolean = available() && preferences.getBoolean("connected", false)

    fun disconnect() {
        preferences.edit { putBoolean("connected", false) }
    }

    suspend fun connect() {
        disconnect()
        val script = context.assets.open("runner-v1.sh").bufferedReader().use { it.readText() }
        val install = """
            set -eu
            umask 077
            mkdir -p "${'$'}HOME/.local/state/mochi-termux"
            printf '%s' "${'$'}1" | base64 -d > "${'$'}HOME/.local/state/mochi-termux/runner-v1.tmp"
            chmod 700 "${'$'}HOME/.local/state/mochi-termux/runner-v1.tmp"
            mv "${'$'}HOME/.local/state/mochi-termux/runner-v1.tmp" "${'$'}HOME/.local/state/mochi-termux/runner-v1"
            bash "${'$'}HOME/.local/state/mochi-termux/runner-v1" probe
        """.trimIndent()
        val result = request(arrayOf("-c", install, "mochi-setup", encoded(script)))
        if (result.exitCode != 0 || result.stdout.trim() != "MOCHI_READY_V1") {
            throw TermuxException("PROVIDER_ERROR", "Termux shell prerequisites or callback test failed.")
        }
        preferences.edit { putBoolean("connected", true) }
    }

    fun taskIds(): Set<String> = preferences.getStringSet("tasks", emptySet()).orEmpty().toSet()

    @Synchronized
    fun start(command: ShellCommand): String {
        checkConnected()
        val tasks = taskIds()
        if (tasks.size >= 100) {
            throw TermuxException("CONFLICT", "Task history is full. Forget completed tasks in Tools > Termux tasks.")
        }
        val id = UUID.randomUUID().toString()
        // Record before dispatch. An uncertain submission must never be resent automatically.
        // KTX edit discards commit's result; dispatch must fail closed when persistence fails.
        @android.annotation.SuppressLint("UseKtx")
        val persisted = preferences.edit().putStringSet("tasks", tasks + id).commit()
        if (!persisted) {
            throw TermuxException("INTERNAL_ERROR", "Cannot persist task identity; no command was submitted.")
        }
        try {
            dispatch(
                arrayOf(HELPER, "run", id, encoded(command.command), command.workdir, command.timeoutSeconds.toString()),
                id,
            )
        } catch (error: TermuxException) {
            preferences.edit { putString("error:$id", error.code) }
            throw error
        }
        return id
    }

    suspend fun task(id: String, stop: Boolean): TaskOutput {
        require(validTaskId(id) && id in taskIds()) { "Unknown task ID." }
        checkConnected()
        val result = request(arrayOf(HELPER, if (stop) "stop" else "read", id))
        if (result.exitCode != 0) {
            throw TermuxException("PROVIDER_ERROR", "Task state is unknown; do not resubmit the command.")
        }
        return try {
            parseTaskOutput(result.stdout)
        } catch (_: IllegalArgumentException) {
            throw TermuxException("PROVIDER_ERROR", "Invalid task response; execution outcome is unknown.")
        }
    }

    suspend fun forget(id: String) {
        val state = task(id, false).state
        require(state in setOf("succeeded", "failed", "stopped", "timed_out")) {
            "Only tasks confirmed finished may be forgotten."
        }
        val result = request(arrayOf(HELPER, "forget", id))
        if (result.exitCode != 0) throw TermuxException("PROVIDER_ERROR", "Could not remove task output.")
        synchronized(this) {
            preferences.edit { putStringSet("tasks", taskIds() - id); remove("error:$id") }
        }
    }

    private fun checkConnected() {
        if (!connected()) throw TermuxException("PERMISSION_DENIED", "Connect Termux in Tools first.")
    }

    private suspend fun request(arguments: Array<String>): CommandResult {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<CommandResult>()
        pending[id] = deferred
        var callback: PendingIntent? = null
        return try {
            callback = dispatch(arguments, id)
            withTimeout(15_000) { deferred.await() }
        } finally {
            pending.remove(id)
            callback?.cancel()
        }
    }

    private fun dispatch(arguments: Array<String>, id: String): PendingIntent {
        if (!available()) {
            throw TermuxException("PERMISSION_DENIED", "Install Termux and grant command permission.", TermuxFailureReason.COMMAND_ACCESS)
        }
        val callbackIntent = Intent(context, TermuxResultReceiver::class.java)
            .setAction("com.example.mochi_termux.RESULT.$id")
            .putExtra("request_id", id)
        val callback = PendingIntent.getBroadcast(
            context, 0, callbackIntent,
            PendingIntent.FLAG_ONE_SHOT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0,
        )
        val intent = Intent("com.termux.RUN_COMMAND")
            .setComponent(ComponentName("com.termux", "com.termux.app.RunCommandService"))
            .putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_PREFIX/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND_CUSTOM_LOG_LEVEL", "0")
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
        try {
            if (context.startService(intent) == null) {
                throw TermuxException("PROVIDER_ERROR", "Termux command service is unavailable.", TermuxFailureReason.SERVICE_UNAVAILABLE)
            }
        } catch (_: SecurityException) {
            callback.cancel()
            throw TermuxException("PERMISSION_DENIED", "Android rejected Termux command access.", TermuxFailureReason.COMMAND_ACCESS)
        } catch (_: IllegalStateException) {
            callback.cancel()
            throw TermuxException("PROVIDER_ERROR", "Android blocked the background start. Open Termux and retry.", TermuxFailureReason.BACKGROUND_START)
        } catch (error: TermuxException) {
            callback.cancel()
            throw error
        }
        return callback
    }

    companion object {
        private val pending = ConcurrentHashMap<String, CompletableDeferred<CommandResult>>()

        fun receive(context: Context, id: String, bundle: Bundle?) {
            val deferred = pending.remove(id)
            val error = bundle?.getInt("err", -1)
            val exitCode = bundle?.getInt("exitCode", -1) ?: -1
            val stdout = bundle?.getString("stdout").orEmpty()
            if (bundle == null || error != -1 || exitCode !in 0..255 || stdout.length > 64_000) {
                deferred?.completeExceptionally(
                    TermuxException("PROVIDER_ERROR", "Termux rejected or lost the command. Check setup; do not blindly retry."),
                )
                val preferences = context.getSharedPreferences("termux", Context.MODE_PRIVATE)
                if (id in preferences.getStringSet("tasks", emptySet()).orEmpty()) {
                    preferences.edit { putString("error:$id", "PROVIDER_ERROR") }
                }
            } else {
                deferred?.complete(CommandResult(stdout, exitCode))
            }
        }
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("request_id") ?: return
        if (!validTaskId(id) || intent.action != "com.example.mochi_termux.RESULT.$id") return
        TermuxBridge.receive(context, id, intent.getBundleExtra("result"))
    }
}
