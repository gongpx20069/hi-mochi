package com.example.mochi_pet

import com.example.mochi_pet.core.agent.AgentOrchestrator
import com.example.mochi_pet.core.agent.AgentPipelineObserver
import com.example.mochi_pet.core.agent.AgentRunner
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
    val navigationPolicy = NavigationPolicy()
    var appliedNavigation: NavigationDecision? = null
    val recordingSink = UiDirectiveSink { decision ->
        appliedNavigation = decision
        sink.apply(decision)
    }
    val builtInTools = mutableListOf(
        ManageMochiCalendarTool(plannerStore),
        ManageMochiTodoTool(plannerStore),
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
        pipelineObserver = observer,
    )
}
