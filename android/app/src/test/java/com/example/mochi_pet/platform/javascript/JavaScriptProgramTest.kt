package com.example.mochi_pet.platform.javascript

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaScriptProgramTest {
    @Test
    fun `program parses bounded JSON input and executes strict function body`() {
        val program = buildSandboxedProgram(
            code = "return input.name;",
            input = buildJsonObject {
                put("name", "Mochi")
            },
        )

        assertTrue(program.contains("\"use strict\""))
        assertTrue(program.contains("JSON.parse"))
        assertTrue(program.contains("return input.name;"))
        assertTrue(program.contains("result === undefined"))
        assertTrue(program.contains("return result;"))
    }
}
