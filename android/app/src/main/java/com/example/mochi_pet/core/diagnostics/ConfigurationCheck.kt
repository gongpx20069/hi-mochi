package com.example.mochi_pet.core.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

enum class CheckStatus { PASSED, ATTENTION, NOT_TESTED, DISABLED }
enum class RepairTarget { MODEL, SPEECH, PERMISSIONS, TOOLS, SKILLS, TERMUX, MIJIA, AGENTLINK }

data class ConfigurationCheck(
    val id: String,
    val title: String,
    val status: CheckStatus,
    val detail: String,
    val repair: RepairTarget? = null,
    val missingRequirements: List<String> = emptyList(),
)

data class ConfigurationProbe(
    val id: String,
    val title: String,
    val repair: RepairTarget,
    val run: suspend () -> List<ConfigurationCheck>,
)

suspend fun runConfigurationChecks(
    probes: List<ConfigurationProbe>,
    errorMessage: (Exception) -> String,
    onProgress: (String) -> Unit,
    onResult: (List<ConfigurationCheck>) -> Unit,
    timeoutMillis: Long = 15_000,
) {
    for (probe in probes) {
        onProgress(probe.title)
        val result = try {
            withTimeoutOrNull(timeoutMillis) { probe.run() }
                ?: listOf(ConfigurationCheck(
                    probe.id, probe.title, CheckStatus.ATTENTION,
                    "Check timed out. Verify the connection and try again.", probe.repair,
                ))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            listOf(ConfigurationCheck(
                probe.id, probe.title, CheckStatus.ATTENTION, errorMessage(error), probe.repair,
            ))
        }
        onResult(result)
    }
}
