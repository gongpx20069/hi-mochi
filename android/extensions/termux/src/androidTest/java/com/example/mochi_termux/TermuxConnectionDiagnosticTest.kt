package com.example.mochi_termux

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TermuxConnectionDiagnosticTest {
    @Test
    fun checkFixedSetupPrerequisites(): Unit = runBlocking {
        assumeTrue(
            "Requires opt-in: checks Termux utilities and installs the bundled connection helper",
            InstrumentationRegistry.getArguments().getString("mochiTermuxDiagnostic") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bridge = TermuxBridge(context)
        assertTrue("Termux must be installed", bridge.installed())
        assertTrue("Android command permission must already be granted", bridge.available())
        val diagnostic = bridge.request(arrayOf("-c", """
            for executable in bash setsid base64 head cat mkfifo awk sleep mkdir chmod mv; do
                if command -v "${'$'}executable" >/dev/null; then
                    printf 'MOCHI_CHECK %s available\n' "${'$'}executable"
                else
                    printf 'MOCHI_CHECK %s missing\n' "${'$'}executable"
                fi
            done
        """.trimIndent()))
        assertEquals("Fixed prerequisite diagnostic must exit successfully", 0, diagnostic.exitCode)
        diagnostic.stdout.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            assertTrue("Unexpected diagnostic shape; output withheld",
                Regex("MOCHI_CHECK (bash|setsid|base64|head|cat|mkfifo|awk|sleep|mkdir|chmod|mv) (available|missing)")
                    .matches(line))
            Log.i("MochiTermuxCheck", line)
        }
        bridge.connect()
        assertTrue("Connection must be confirmed by callback", bridge.connected())
        Log.i("MochiTermuxCheck", "MOCHI_CHECK connection ready")
    }
}
