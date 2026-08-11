package com.example.mochi_pet

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.preferencesDataStore
import com.example.mochi_pet.core.agent.llm.OkHttpOpenAiChatClient
import com.example.mochi_pet.core.agent.llm.OpenAiChatClient
import com.example.mochi_pet.core.agent.AgentPipelineObserver
import com.example.mochi_pet.core.agent.AgentRunRequest
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.database.MochiDatabase
import com.example.mochi_pet.core.database.PlannerRepository
import com.example.mochi_pet.core.database.PlannerStore
import com.example.mochi_pet.core.memory.AgentMemoryRepository
import com.example.mochi_pet.core.memory.RoomAgentMemoryRepository
import com.example.mochi_pet.core.persona.FilePersonaRepository
import com.example.mochi_pet.core.persona.PersonaRepository
import com.example.mochi_pet.core.settings.AgentSettingsRepository
import com.example.mochi_pet.core.settings.AndroidKeystoreApiKeyCipher
import com.example.mochi_pet.core.settings.DataStoreAgentSettingsRepository
import com.example.mochi_pet.core.settings.DataStoreProviderSettingsRepository
import com.example.mochi_pet.core.settings.ProviderSettingsRepository
import com.example.mochi_pet.core.settings.ProviderShareManager
import com.example.mochi_pet.core.settings.DataStoreSpeechSettingsRepository
import com.example.mochi_pet.core.settings.SpeechSettingsRepository
import com.example.mochi_pet.core.skills.RoomSkillRepository
import com.example.mochi_pet.core.skills.SkillMarketClient
import com.example.mochi_pet.core.skills.SkillRepository
import com.example.mochi_pet.core.skills.SkillsShClient
import com.example.mochi_pet.core.mcp.McpStreamableHttpClient
import com.example.mochi_pet.core.mcp.DianpingMcpClient
import com.example.mochi_pet.core.maps.BaiduMapAgentClient
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.navigation.UiDirectiveSink
import com.example.mochi_pet.core.schedule.AgentScheduleResult
import com.example.mochi_pet.core.schedule.AgentScheduleStore
import com.example.mochi_pet.core.schedule.RoomAgentScheduleRepository
import com.example.mochi_pet.core.tools.DataStoreToolCatalogRepository
import com.example.mochi_pet.core.tools.ToolCatalogRepository
import com.example.mochi_pet.core.weather.OpenMeteoWeatherRepository
import com.example.mochi_pet.core.weather.WeatherRepository
import com.example.mochi_pet.core.update.AppUpdateClient
import com.example.mochi_pet.platform.browser.AgentBrowserRuntime
import com.example.mochi_pet.platform.location.AndroidDeviceLocationProvider
import com.example.mochi_pet.platform.location.LocationPermissionGate
import com.example.mochi_pet.platform.voice.AndroidVoiceRuntime
import com.example.mochi_pet.platform.wake.AndroidWakeRuntime
import com.example.mochi_pet.platform.javascript.AndroidJavaScriptExecutor
import com.example.mochi_pet.platform.schedule.AndroidAgentScheduleController
import com.example.mochi_pet.platform.schedule.showAgentScheduleNotification
import java.time.Instant
import java.time.LocalDate
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Application.providerDataStore by preferencesDataStore(
    name = "provider_settings",
)

private val Application.toolDataStore by preferencesDataStore(
    name = "tool_settings",
)

private val Application.agentDataStore by preferencesDataStore(
    name = "agent_settings",
)

private val Application.speechDataStore by preferencesDataStore(
    name = "speech_settings",
)

class MochiApplication : Application() {
    private val applicationScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            agentScheduleController.syncAll()
        }
    }

    private val database: MochiDatabase by lazy {
        MochiDatabase.create(this)
    }

    val plannerStore: PlannerStore by lazy {
        PlannerRepository(
            calendarEventDao = database.calendarEventDao(),
            todoDao = database.todoDao(),
        )
    }

    val providerSettingsRepository: ProviderSettingsRepository by lazy {
        DataStoreProviderSettingsRepository(
            dataStore = providerDataStore,
            apiKeyCipher = AndroidKeystoreApiKeyCipher(),
        )
    }

    val agentSettingsRepository: AgentSettingsRepository by lazy {
        DataStoreAgentSettingsRepository(agentDataStore)
    }

    val speechSettingsRepository: SpeechSettingsRepository by lazy {
        DataStoreSpeechSettingsRepository(
            dataStore = speechDataStore,
            secretCipher = AndroidKeystoreApiKeyCipher(
                keyAlias = "mochi_speech_secret_v1",
            ),
        )
    }

    val providerShareManager: ProviderShareManager by lazy {
        ProviderShareManager(
            providerRepository = providerSettingsRepository,
            speechRepository = speechSettingsRepository,
        )
    }

    val appUpdateClient: AppUpdateClient by lazy {
        AppUpdateClient()
    }

    val personaRepository: PersonaRepository by lazy {
        FilePersonaRepository(this)
    }

    val agentMemoryRepository: AgentMemoryRepository by lazy {
        RoomAgentMemoryRepository(database.agentMemoryDao())
    }

    val agentScheduleStore: AgentScheduleStore by lazy {
        RoomAgentScheduleRepository(database.agentScheduleDao())
    }

    val agentScheduleController: AndroidAgentScheduleController by lazy {
        AndroidAgentScheduleController(this)
    }

    val mcpClient: McpStreamableHttpClient by lazy {
        McpStreamableHttpClient()
    }

    val dianpingMcpClient: DianpingMcpClient by lazy {
        DianpingMcpClient()
    }

    val javaScriptExecutor: AndroidJavaScriptExecutor by lazy {
        AndroidJavaScriptExecutor(this)
    }

    val baiduMapAgentClient: BaiduMapAgentClient by lazy {
        BaiduMapAgentClient()
    }

    val toolCatalogRepository: ToolCatalogRepository by lazy {
        DataStoreToolCatalogRepository(
            dataStore = toolDataStore,
            secretCipher = AndroidKeystoreApiKeyCipher(
                keyAlias = "mochi_tool_secret_v1",
            ),
            mcpClient = mcpClient,
            dianpingMcpClient = dianpingMcpClient,
        )
    }

    val skillRepository: SkillRepository by lazy {
        RoomSkillRepository(database.skillDao())
    }

    val skillMarketClient: SkillMarketClient by lazy {
        SkillsShClient()
    }

    val openAiChatClient: OpenAiChatClient by lazy {
        OkHttpOpenAiChatClient()
    }

    val locationPermissionGate: LocationPermissionGate by lazy {
        LocationPermissionGate {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
        }
    }

    val deviceLocationProvider: AndroidDeviceLocationProvider by lazy {
        AndroidDeviceLocationProvider(
            context = this,
            permissionGate = locationPermissionGate,
        )
    }

    val weatherRepository: WeatherRepository by lazy {
        OpenMeteoWeatherRepository(
            deviceLocationProvider,
        )
    }

    val agentBrowserRuntime: AgentBrowserRuntime by lazy {
        AgentBrowserRuntime(this)
    }

    val voiceRuntime: AndroidVoiceRuntime by lazy {
        AndroidVoiceRuntime(
            context = this,
            speechSettingsRepository = speechSettingsRepository,
        )
    }

    val wakeRuntime: AndroidWakeRuntime by lazy {
        AndroidWakeRuntime(this)
    }

    suspend fun executeAgentSchedule(
        id: String,
        manual: Boolean,
    ): Boolean {
        val now = Instant.now()
        val schedule = if (manual) {
            agentScheduleStore.get(id)
        } else {
            agentScheduleStore.claimDue(id, now)
        } ?: return false
        val userText = "[Scheduled Agent · ${schedule.name}]\n${schedule.prompt}"
        return try {
            val provider = providerSettingsRepository.loadRuntimeConfig()
            val settings = agentSettingsRepository.load()
            val persona = personaRepository.load()
            val memory = agentMemoryRepository.loadContext(
                query = schedule.prompt,
                recentTurns = settings.recentConversationTurns,
            )
            agentBrowserRuntime.beginTurn()
            val reply = try {
                createAgentRunner(
                    sink = UiDirectiveSink { },
                    observer = AgentPipelineObserver { _, _ -> },
                    onWeatherLoaded = {},
                    includeBrowser = true,
                    includeBrowserInteractions = false,
                ).run(
                    AgentRunRequest(
                        provider = provider,
                        query = schedule.prompt,
                        currentEmotion = "neutral",
                        context = ToolExecutionContext(
                            currentDate = LocalDate.now(),
                            currentSurface = MochiSurface.Today,
                        ),
                        history = memory.recentMessages,
                        personaSections = persona.sections,
                        recalledMemories = memory.recalledLines,
                    ),
                )
            } finally {
                withContext(NonCancellable) {
                    agentBrowserRuntime.closeTurn()
                }
            }
            agentMemoryRepository.saveTurn(userText, reply.reply)
            val updated = agentScheduleStore.recordResult(
                id = id,
                result = AgentScheduleResult.SUCCESS,
                completedAt = Instant.now(),
                advanceSchedule = !manual,
            )
            agentScheduleController.sync(updated)
            showAgentScheduleNotification(
                scheduleName = schedule.name,
                text = reply.reply,
                success = true,
            )
            true
        } catch (error: Exception) {
            SCHEDULE_LOGGER.log(
                Level.SEVERE,
                "scheduled_agent_failed id=$id type=" +
                    "${error::class.java.simpleName} message=" +
                    error.message.orEmpty().take(240),
                error,
            )
            val message = error.message ?: "Mochi could not complete this request"
            agentMemoryRepository.saveTurn(
                userText,
                "Scheduled task failed: $message",
            )
            val updated = agentScheduleStore.recordResult(
                id = id,
                result = AgentScheduleResult.FAILED,
                completedAt = Instant.now(),
                advanceSchedule = !manual,
            )
            agentScheduleController.sync(updated)
            showAgentScheduleNotification(
                scheduleName = schedule.name,
                text = message,
                success = false,
            )
            false
        }
    }

    private companion object {
        val SCHEDULE_LOGGER = Logger.getLogger("MochiSchedule")
    }
}
