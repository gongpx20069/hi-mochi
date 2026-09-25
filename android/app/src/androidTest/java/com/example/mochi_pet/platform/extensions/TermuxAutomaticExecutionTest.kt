package com.example.mochi_pet.platform.extensions

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.extensions.ExtensionToolScope
import com.example.mochi_pet.core.model.MochiSurface
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TermuxAutomaticExecutionTest {
    @Test
    fun enabledRegistriesExecuteWithoutApproval(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(
            "Opt-in only: submits three fixed printf commands and removes their completed task records",
            InstrumentationRegistry.getArguments().getString("mochiTermuxExecutionDiagnostic") == "true",
        )
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MochiApplication
        val repository = app.toolCatalogRepository
        val summary = repository.loadSummary().termux
        assertTrue(
            "Termux prerequisite: installed=${summary.installed}, trusted=${summary.trusted}, " +
                "connected=${summary.connected}, enabled=${summary.enabled}, status=${summary.status}, detail=${summary.detail}",
            summary.enabled,
        )
        val context = ToolExecutionContext(LocalDate.now(), MochiSurface.Face)
        for (scope in ExtensionToolScope.entries) {
            val tools = repository.loadEnabledExtensionTools(scope)
            val execute = tools.single { it.name == "termux_exec" }
            val task = tools.single { it.name == "termux_task" }
            val submitted = withTimeout(15_000) {
                execute.execute(buildJsonObject { put("command", "printf MOCHI_AUTO_EXECUTION_OK") }, context)
            }
            assertEquals("Submission must succeed for $scope", "ok", submitted.status)
            val id = requireNotNull((submitted.data as? JsonObject)?.get("task_id")?.jsonPrimitive?.content)
            val completed = withTimeout(15_000) {
                var data: JsonObject
                val pendingStates = setOf("running", "stopping", "unknown")
                do {
                    val result = task.execute(buildJsonObject {
                        put("action", "read"); put("task_id", id)
                    }, context)
                    assertEquals("Read must succeed for $scope", "ok", result.status)
                    data = requireNotNull(result.data as? JsonObject)
                    if (data["state"]?.jsonPrimitive?.content in pendingStates) delay(250)
                } while (data["state"]?.jsonPrimitive?.content in pendingStates)
                data
            }
            assertEquals("Task must complete for $scope", "succeeded", completed["state"]?.jsonPrimitive?.content)
            assertEquals("MOCHI_AUTO_EXECUTION_OK", completed["stdout"]?.jsonPrimitive?.content)
            val forgotten = task.execute(buildJsonObject {
                put("action", "forget"); put("task_id", id)
            }, context)
            assertEquals("Diagnostic task cleanup must succeed", "ok", forgotten.status)
        }
    }
}
