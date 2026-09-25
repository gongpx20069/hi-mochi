package com.example.mochi_pet.core.extensions

import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class TermuxTaskView(
    val id: String,
    val result: ToolResultEnvelope,
)

class TermuxRuntimeState {
    private val mutableTasks = MutableStateFlow<List<TermuxTaskView>>(emptyList())
    val tasks = mutableTasks.asStateFlow()
    private val generation = AtomicLong()
    val revision: Long get() = generation.get()

    fun invalidateSessions() { generation.incrementAndGet() }

    fun record(result: ToolResultEnvelope) {
        val data = result.data as? JsonObject ?: return
        val id = (data["task_id"] as? JsonPrimitive)?.content ?: return
        mutableTasks.update { tasks ->
            if ((data["state"] as? JsonPrimitive)?.content == "forgotten") {
                tasks.filterNot { it.id == id }
            } else {
                (listOf(TermuxTaskView(id, result)) + tasks.filterNot { it.id == id }).take(100)
            }
        }
    }
}

internal class TermuxToolSession(
    private val runtime: TermuxRuntimeState,
    private val isEnabled: suspend (String) -> Boolean,
) {
    private val revision = runtime.revision

    fun wrap(delegate: AgentTool): AgentTool = object : AgentTool {
        override val name = delegate.name
        override val schema = delegate.schema

        override suspend fun execute(
            arguments: JsonObject,
            context: ToolExecutionContext,
        ): ToolResultEnvelope {
            // Check the generation after the suspending settings read.
            if (!isEnabled(name) || revision != runtime.revision) {
                return ToolResultEnvelope.error(
                    ToolErrorCode.PERMISSION_DENIED, "Termux is disabled or this tool session was revoked.",
                )
            }
            return delegate.execute(arguments, context).also(runtime::record)
        }
    }
}
