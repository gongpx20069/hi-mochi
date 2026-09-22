package com.example.mochi_termux

import com.example.mochi_extension.ExtensionApiValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxCommandsTest {
    @Test
    fun `unrestricted shell remains data and has explicit resource limits`() {
        val command = ShellCommand.parse(Json.parseToJsonElement(
            """{"command":"printf '%s' \"hello\" | cat > output.txt","workdir":"/tmp/a b","timeout_seconds":1800}""",
        ).jsonObject)
        assertEquals("/tmp/a b", command.workdir)
        assertEquals(1800, command.timeoutSeconds)
        assertTrue(command.command.contains("|"))
        assertEquals(120, ShellCommand.parse(Json.parseToJsonElement("""{"command":"true"}""").jsonObject).timeoutSeconds)
    }

    @Test
    fun `malformed arguments and model confirmation are rejected`() {
        listOf(
            """{"command":""}""",
            """{"command":"true","confirmed":true}""",
            """{"command":"true","timeout_seconds":0}""",
            """{"command":"true","timeout_seconds":1801}""",
            """{"command":"true","timeout_seconds":"120"}""",
            """{"command":"true","workdir":"relative"}""",
            """{"command":42}""",
            """{"command":"a\u0000b"}""",
        ).forEach { raw ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                ShellCommand.parse(Json.parseToJsonElement(raw).jsonObject)
            }
        }
    }

    @Test
    fun `task output is bounded and distinguishes exit status from transport`() {
        val result = parseTaskOutput("MOCHI_TASK_V1\nfailed\n7\n${encoded("hello")}\n${encoded("failure")}\n1\n")
        assertEquals(7, result.exitCode)
        assertEquals("hello", result.stdout)
        assertEquals("failure", result.stderr)
        assertTrue(result.truncated)
        assertNull(parseTaskOutput("MOCHI_TASK_V1\nunknown\n-\n\n\n0\n").exitCode)
        listOf(
            "MOCHI_TASK_V1\nsuccess\n0\n\n\n0\n",
            "MOCHI_TASK_V1\nrunning\n256\n\n\n0\n",
            "MOCHI_TASK_V1\nrunning\n-\nINVALID%%\n\n0\n",
        ).forEach {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { parseTaskOutput(it) }
        }
    }

    @Test
    fun `tools conform to extension schema and are not read only`() {
        assertNull(ExtensionApiValidator.toolDefinitionsError(TERMUX_TOOLS))
        assertEquals(setOf("termux_exec", "termux_task"), TERMUX_TOOLS.map { it.name }.toSet())
        assertTrue(TERMUX_TOOLS.all { it.riskLevel == "sensitive" })
        assertTrue(!validTaskId("../another-task"))
    }
}
