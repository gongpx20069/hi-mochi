package com.example.mochi_pet.core.extensions

import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class TermuxApprovalChoice { DENY, ONCE, THIS_RUN }

data class TermuxApprovalRequest(
    val id: String,
    val tool: String,
    val arguments: JsonObject,
)

data class TermuxTaskView(
    val id: String,
    val result: ToolResultEnvelope,
)

class TermuxApprovalGate {
    private val mutex = Mutex()
    private val mutablePending = MutableStateFlow<TermuxApprovalRequest?>(null)
    val pending = mutablePending.asStateFlow()
    private var answer: CompletableDeferred<TermuxApprovalChoice>? = null
    private val mutableTasks = MutableStateFlow<List<TermuxTaskView>>(emptyList())
    val tasks = mutableTasks.asStateFlow()
    @Volatile var revision: Long = 0
        private set

    suspend fun request(tool: String, arguments: JsonObject): TermuxApprovalChoice =
        mutex.withLock {
            val response = CompletableDeferred<TermuxApprovalChoice>()
            answer = response
            mutablePending.value = TermuxApprovalRequest(UUID.randomUUID().toString(), tool, arguments)
            try {
                withTimeoutOrNull(120_000) { response.await() } ?: TermuxApprovalChoice.DENY
            } finally {
                mutablePending.value = null
                answer = null
            }
        }

    fun respond(id: String, choice: TermuxApprovalChoice) {
        if (mutablePending.value?.id == id) answer?.complete(choice)
    }

    fun cancelPending() {
        revision++
        answer?.complete(TermuxApprovalChoice.DENY)
    }

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

/** One instance per foreground registry; task-wide approval cannot survive another run. */
internal class TermuxToolSession(
    private val gate: TermuxApprovalGate,
    private val isEnabled: suspend (String) -> Boolean,
) {
    private val revision = gate.revision
    private var automatic = false
    private val ownedTasks = mutableSetOf<String>()

    fun wrap(delegate: AgentTool): AgentTool = object : AgentTool {
        override val name = delegate.name
        override val schema = delegate.schema

        override suspend fun execute(
            arguments: JsonObject,
            context: ToolExecutionContext,
        ): ToolResultEnvelope {
            if (revision != gate.revision || !isEnabled(name)) return denied()
            val id = (arguments["task_id"] as? JsonPrimitive)?.content
            val action = (arguments["action"] as? JsonPrimitive)?.content
            val ownedReadOrStop = name == "termux_task" && id in ownedTasks && action in setOf("read", "stop")
            if (!automatic && !ownedReadOrStop) {
                when (gate.request(name, arguments)) {
                    TermuxApprovalChoice.DENY -> return denied()
                    TermuxApprovalChoice.ONCE -> Unit
                    TermuxApprovalChoice.THIS_RUN -> automatic = true
                }
            }
            if (revision != gate.revision || !isEnabled(name)) return denied()
            return delegate.execute(arguments, context).also { result ->
                gate.record(result)
                if (result.status == "ok") {
                    ((result.data as? JsonObject)?.get("task_id") as? JsonPrimitive)?.content?.let(ownedTasks::add)
                }
            }
        }
    }

    private fun denied() = ToolResultEnvelope.error(
        ToolErrorCode.PERMISSION_DENIED, "Termux execution was not authorized or the tool was disabled.",
    )
}
