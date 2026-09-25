package com.example.mochi_pet.core.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationCheckTest {
    @Test fun `failed and timed out probes do not hide independent results or reveal raw errors`() = runTest {
        val results = mutableListOf<ConfigurationCheck>()
        runConfigurationChecks(
            listOf(
                ConfigurationProbe("bad", "Provider", RepairTarget.MODEL) { error("untrusted provider response") },
                ConfigurationProbe("timeout", "Extension", RepairTarget.TOOLS) { awaitCancellation() },
                ConfigurationProbe("ok", "Skill", RepairTarget.SKILLS) {
                    listOf(ConfigurationCheck("ok", "Skill", CheckStatus.PASSED, "Ready"))
                },
            ),
            errorMessage = { "Safe diagnostic failure" },
            onProgress = {},
            onResult = { results.addAll(it) },
            timeoutMillis = 15,
        )
        assertEquals(listOf(CheckStatus.ATTENTION, CheckStatus.ATTENTION, CheckStatus.PASSED), results.map { it.status })
        assertTrue(results[1].detail.contains("timed out"))
        assertFalse(results.toString().contains("untrusted provider response"))
    }

    @Test fun `cancellation propagates and never starts later probes`() = runTest {
        var laterCalled = false
        val results = mutableListOf<ConfigurationCheck>()
        try {
            runConfigurationChecks(listOf(
                ConfigurationProbe("cancel", "Provider", RepairTarget.MODEL) { throw CancellationException() },
                ConfigurationProbe("later", "Speech", RepairTarget.SPEECH) {
                    laterCalled = true
                    emptyList()
                },
            ), { "Failure" }, {}, { results.addAll(it) })
            error("Expected cancellation")
        } catch (_: CancellationException) {
            assertFalse(laterCalled)
            assertTrue(results.isEmpty())
        }
    }
}
