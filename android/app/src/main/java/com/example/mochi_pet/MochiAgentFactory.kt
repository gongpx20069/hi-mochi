package com.example.mochi_pet

import android.util.Log
import com.example.mochi_pet.core.agent.AgentDiagnosticEvent
import com.example.mochi_pet.core.agent.AgentDiagnosticEventType
import com.example.mochi_pet.core.agent.AgentDiagnosticLogger
import com.example.mochi_pet.core.agent.AgentOrchestrator
import com.example.mochi_pet.core.agent.AgentPipelineObserver
import com.example.mochi_pet.core.agent.AgentPipelineStage
import com.example.mochi_pet.core.agent.AgentRunRequest
import com.example.mochi_pet.core.agent.AgentRunner
import com.example.mochi_pet.core.agent.DelegateAgentTool
import com.example.mochi_pet.core.agent.SerialSubagentCoordinator
import com.example.mochi_pet.core.agent.SubagentExecutor
import com.example.mochi_pet.core.agent.SubagentType
import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ManageMochiCalendarTool
import com.example.mochi_pet.core.agent.tool.ManageMochiTodoTool
import com.example.mochi_pet.core.agent.tool.SandboxedJavaScriptTool
import com.example.mochi_pet.core.agent.tool.ToolRegistry
import com.example.mochi_pet.core.browser.agentBrowserTools
import com.example.mochi_pet.core.browser.readOnlyAgentBrowserTools
import com.example.mochi_pet.core.maps.baiduMapAgentTools
import com.example.mochi_pet.core.navigation.NavigateMochiUiTool
import com.example.mochi_pet.core.navigation.NavigationDecision
import com.example.mochi_pet.core.navigation.NavigationPolicy
import com.example.mochi_pet.core.navigation.UiDirectiveSink
import com.example.mochi_pet.core.location.CurrentLocationTool
import com.example.mochi_pet.core.schedule.ManageMochiScheduleTool
import com.example.mochi_pet.core.skills.LoadSkillTool
import com.example.mochi_pet.core.weather.CurrentWeather
import com.example.mochi_pet.core.weather.CurrentWeatherTool

suspend fun MochiApplication.createAgentRunner(
    sink: UiDirectiveSink,
    observer: AgentPipelineObserver,
    onWeatherLoaded: (CurrentWeather) -> Unit,
    includeBrowser: Boolean,
    includeBrowserInteractions: Boolean = includeBrowser,
): AgentRunner {
    val diagnosticLogger = androidAgentDiagnosticLogger()
    val navigationPolicy = NavigationPolicy()
    var appliedNavigation: NavigationDecision? = null
    val recordingSink = UiDirectiveSink { decision ->
        appliedNavigation = decision
        sink.apply(decision)
    }
    val builtInTools = mutableListOf(
        ManageMochiCalendarTool(plannerStore),
        ManageMochiTodoTool(plannerStore),
        CurrentLocationTool(deviceLocationProvider),
        CurrentWeatherTool(weatherRepository, onWeatherLoaded),
        SandboxedJavaScriptTool(javaScriptExecutor),
        ManageMochiScheduleTool(
            agentScheduleStore,
            agentScheduleController,
        ),
    )
    if (includeBrowserInteractions) {
        builtInTools += NavigateMochiUiTool(
            navigationPolicy,
            recordingSink,
        )
    }
    if (includeBrowser) {
        builtInTools += if (includeBrowserInteractions) {
            agentBrowserTools(agentBrowserRuntime)
        } else {
            readOnlyAgentBrowserTools(agentBrowserRuntime)
        }
    }
    val enabledBuiltIns = builtInTools.filter { tool ->
        toolCatalogRepository.isBuiltInEnabled(tool.name)
    }.toMutableList()
    toolCatalogRepository.loadBaiduMapToken()?.let { token ->
        enabledBuiltIns += baiduMapAgentTools(
            client = baiduMapAgentClient,
            token = token,
        ).filter { tool ->
            toolCatalogRepository.isBuiltInEnabled(tool.name)
        }
    }
    val tools = (
        enabledBuiltIns + toolCatalogRepository.loadEnabledMcpTools()
    ).toMutableList()
    val availableSkills = skillRepository.listEnabledMetadata(
        tools.mapTo(mutableSetOf()) { it.name },
    )
    if (availableSkills.isNotEmpty()) {
        tools += LoadSkillTool(
            repository = skillRepository,
            availableToolNames = tools.mapTo(mutableSetOf()) { it.name },
        )
    }
    return AgentOrchestrator(
        chatClient = openAiChatClient,
        toolRegistry = ToolRegistry(tools),
        navigationPolicy = navigationPolicy,
        uiDirectiveSink = recordingSink,
        appliedNavigationDecision = { appliedNavigation },
        skillCatalogProvider = { availableSkills },
        toolRegistryProvider = { parentRequest ->
            val coordinator = SerialSubagentCoordinator(
                executor = SubagentExecutor { type, task, context ->
                    executeSubagent(
                        type = type,
                        task = task,
                        context = context,
                        parentRequest = parentRequest,
                        parentObserver = observer,
                        includeBrowser = includeBrowser,
                        includeBrowserInteractions =
                            includeBrowserInteractions,
                    )
                },
            )
            ToolRegistry(tools + DelegateAgentTool(coordinator))
        },
        pipelineObserver = observer,
        diagnosticLogger = diagnosticLogger,
    )
}

private suspend fun MochiApplication.executeSubagent(
    type: SubagentType,
    task: String,
    context: com.example.mochi_pet.core.agent.tool.ToolExecutionContext,
    parentRequest: AgentRunRequest,
    parentObserver: AgentPipelineObserver,
    includeBrowser: Boolean,
    includeBrowserInteractions: Boolean,
): String {
    val tools = mutableListOf<AgentTool>()
    if (includeBrowser) {
        tools += if (includeBrowserInteractions) {
            agentBrowserTools(agentBrowserRuntime)
        } else {
            readOnlyAgentBrowserTools(agentBrowserRuntime)
        }
    }
    if (type == SubagentType.ANALYST) {
        tools += SandboxedJavaScriptTool(javaScriptExecutor)
    }
    val enabledTools = tools.filter { tool ->
        toolCatalogRepository.isBuiltInEnabled(tool.name)
    }.toMutableList()
    enabledTools += toolCatalogRepository.loadEnabledReadOnlyMcpTools()
    val availableSkills = skillRepository.listEnabledMetadata(
        enabledTools.mapTo(mutableSetOf()) { it.name },
    )
    if (availableSkills.isNotEmpty()) {
        enabledTools += LoadSkillTool(
            repository = skillRepository,
            availableToolNames =
                enabledTools.mapTo(mutableSetOf()) { it.name },
        )
    }

    parentObserver.onStage(AgentPipelineStage.SUBAGENT, type.displayName)
    agentBrowserRuntime.setActor(type.displayName)
    return try {
        AgentOrchestrator(
            chatClient = openAiChatClient,
            toolRegistry = ToolRegistry(enabledTools),
            navigationPolicy = NavigationPolicy(),
            uiDirectiveSink = UiDirectiveSink { },
            skillCatalogProvider = { availableSkills },
            maxToolRounds = 30,
            pipelineObserver = AgentPipelineObserver { stage, detail ->
                parentObserver.onStage(
                    AgentPipelineStage.SUBAGENT,
                    buildSubagentPipelineDetail(type, stage, detail),
                )
            },
            diagnosticActor = type.id,
            diagnosticLogger = androidAgentDiagnosticLogger(),
        ).run(
            AgentRunRequest(
                provider = parentRequest.provider,
                query = task,
                currentEmotion = "neutral",
                context = context,
                history = emptyList(),
                personaSections = listOf(type.instructions),
                recalledMemories = emptyList(),
                availableSkills = availableSkills,
            ),
        ).reply
    } finally {
        agentBrowserRuntime.setActor(null)
    }
}

private fun buildSubagentPipelineDetail(
    type: SubagentType,
    stage: AgentPipelineStage,
    detail: String?,
): String {
    val activity = when (stage) {
        AgentPipelineStage.SKILLING -> "choosing skills"
        AgentPipelineStage.THINKING -> "planning"
        AgentPipelineStage.SUBAGENT -> "delegating"
        AgentPipelineStage.TOOL -> detail ?: "using tools"
        AgentPipelineStage.SUMMARY -> "preparing findings"
    }
    return "${type.displayName} · $activity"
}

private fun androidAgentDiagnosticLogger(): AgentDiagnosticLogger =
    AgentDiagnosticLogger { event ->
        val message = event.toLogMessage()
        when (event.type) {
            AgentDiagnosticEventType.RUN_FAILED,
            AgentDiagnosticEventType.RUN_CANCELLED,
            -> Log.w(AGENT_DIAGNOSTIC_TAG, message)

            else -> Log.i(AGENT_DIAGNOSTIC_TAG, message)
        }
    }

private fun AgentDiagnosticEvent.toLogMessage(): String = buildString {
    append("event=")
    append(type.name.lowercase())
    append(" run_id=")
    append(runId)
    append(" actor=")
    append(actor)
    when (type) {
        AgentDiagnosticEventType.RUN_STARTED -> {
            append(" available_tools=")
            append(availableToolCount)
            append(" max_tool_rounds=")
            append(maxToolRounds)
        }

        AgentDiagnosticEventType.MODEL_ROUND_STARTED -> {
            append(" model_round=")
            append(modelRound)
            append(" completed_tool_rounds=")
            append(toolRound)
        }

        AgentDiagnosticEventType.TOOL_STARTED,
        AgentDiagnosticEventType.TOOL_FINISHED,
        -> {
            append(" model_round=")
            append(modelRound)
            append(" tool_round=")
            append(toolRound)
            append(" tool_call=")
            append(toolCall)
            append(" tool=")
            append(toolName)
            toolStatus?.let {
                append(" status=")
                append(it)
            }
            toolCode?.let {
                append(" code=")
                append(it)
            }
            errorType?.let {
                append(" error=")
                append(it)
            }
            if (type == AgentDiagnosticEventType.TOOL_FINISHED) {
                append(" duration_ms=")
                append(durationMs)
            }
        }

        AgentDiagnosticEventType.RUN_COMPLETED,
        AgentDiagnosticEventType.RUN_CANCELLED,
        AgentDiagnosticEventType.RUN_FAILED,
        -> {
            append(" model_rounds=")
            append(modelRound)
            append(" tool_rounds=")
            append(toolRound)
            append(" tool_calls=")
            append(toolCall)
            append(" duration_ms=")
            append(durationMs)
            errorType?.let {
                append(" error=")
                append(it)
            }
        }
    }
}

private const val AGENT_DIAGNOSTIC_TAG = "MochiAgent"
