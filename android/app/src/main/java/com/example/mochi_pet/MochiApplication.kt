package com.example.mochi_pet

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.mochi_pet.core.agentlink.AgentLinkResultMonitor
import com.example.mochi_pet.platform.agentlink.DataStoreAgentLinkResults
import com.example.mochi_pet.platform.agentlink.scheduleAgentLinkResultCollection
import java.io.File
import kotlinx.coroutines.delay
import com.example.mochi_pet.core.agent.llm.OkHttpOpenAiChatClient
import com.example.mochi_pet.core.agent.llm.OpenAiChatClient
import com.example.mochi_pet.core.agent.AgentPipelineObserver
import com.example.mochi_pet.core.agent.AgentRunRequest
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.database.MochiDatabase
import com.example.mochi_pet.core.database.PlannerRepository
import com.example.mochi_pet.core.database.PlannerStore
import com.example.mochi_pet.core.extensions.AndroidMochiExtensionClient
import com.example.mochi_pet.core.extensions.MochiExtensionClient
import com.example.mochi_pet.core.extensions.ExtensionToolScope
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
import com.example.mochi_pet.core.maps.AmapMapsClient
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
import com.example.mochi_pet.platform.agentlink.AndroidAgentLinkClient
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
            try {
                journalController.reconcile()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                android.util.Log.e("DailyJournal", "Could not schedule daily journals", error)
            }
        }
        applicationScope.launch {
            agentScheduleController.syncAll()
        }
        applicationScope.launch {
            userReminderController.registerReconciliation()
            plannerStore.changes.collect {
                try {
                    userReminderController.sync()
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    android.util.Log.e("MochiReminder", "Could not reconcile user reminders", error)
                }
            }
        }
        applicationScope.launch {
            var collectionScheduled = false
            while (true) {
                agentLinkResultMonitor.refresh()
                if (!collectionScheduled && agentLinkResults.state.value.tasks.any { it.receipt?.terminal != true }) {
                    try {
                        scheduleAgentLinkResultCollection(this@MochiApplication)
                        collectionScheduled = true
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        agentLinkResults.availability(false, "Remote results could not be read or saved. Check AgentLink and retry.")
                    }
                }
                delay(20_000)
            }
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

    val providerRuns = com.example.mochi_pet.core.settings.ProviderRunCoordinator()

    val journalRepository by lazy {
        com.example.mochi_pet.core.journal.JournalRepository(database, plannerStore)
    }

    val journalController by lazy {
        com.example.mochi_pet.platform.journal.AndroidJournalController(this, journalRepository)
    }

    val providerSettingsRepository: ProviderSettingsRepository by lazy {
        DataStoreProviderSettingsRepository(
            dataStore = providerDataStore,
            apiKeyCipher = AndroidKeystoreApiKeyCipher(),
            runs = providerRuns,
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
            toolCatalogRepository = toolCatalogRepository,
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

    val userReminderController by lazy {
        com.example.mochi_pet.platform.schedule.AndroidUserReminderController(this, plannerStore)
    }

    val mcpClient: McpStreamableHttpClient by lazy {
        McpStreamableHttpClient()
    }

    val javaScriptExecutor: AndroidJavaScriptExecutor by lazy {
        AndroidJavaScriptExecutor(this)
    }

    val amapMapsClient: AmapMapsClient by lazy {
        AmapMapsClient()
    }

    val mediaPlatform by lazy {
        com.example.mochi_pet.platform.media.AndroidMediaPlatform(this) {
            toolCatalogRepository.mediaAppPackages() ?:
                com.example.mochi_pet.platform.media.selectableApps(this).filter { it.music }.map { it.packageName }.toSet()
        }
    }

    private val deviceControlMutex = kotlinx.coroutines.sync.Mutex()

    val appToolRuntime by lazy {
        com.example.mochi_pet.core.apps.AppToolRuntime(mediaPlatform, deviceControlMutex)
    }

    val mediaToolRuntime by lazy {
        com.example.mochi_pet.core.media.MediaToolRuntime(
            mediaPlatform,
            deviceControlMutex,
        )
    }

    val extensionClient: MochiExtensionClient by lazy {
        AndroidMochiExtensionClient(this)
    }

    val termuxClient: MochiExtensionClient by lazy {
        AndroidMochiExtensionClient(this, com.example.mochi_pet.core.extensions.TrustedExtension.TERMUX)
    }

    val termuxRuntime = com.example.mochi_pet.core.extensions.TermuxRuntimeState()

    val webdavClient: MochiExtensionClient by lazy {
        AndroidMochiExtensionClient(this, com.example.mochi_pet.core.extensions.TrustedExtension.WEBDAV)
    }

    val agentLinkClient: AndroidAgentLinkClient by lazy {
        AndroidAgentLinkClient(this, toolDataStore, agentLinkResults) { scheduleAgentLinkResultCollection(this) }
    }

    val agentLinkResults by lazy {
        DataStoreAgentLinkResults(
            PreferenceDataStoreFactory.create(scope = applicationScope) {
                File(noBackupFilesDir, "agentlink_results.preferences_pb")
            },
            AndroidKeystoreApiKeyCipher("mochi_agentlink_results_v1"),
        )
    }

    val agentLinkResultMonitor by lazy {
        AgentLinkResultMonitor(agentLinkClient, agentLinkResults)
    }

    val toolCatalogRepository: ToolCatalogRepository by lazy {
        DataStoreToolCatalogRepository(
            dataStore = toolDataStore,
            secretCipher = AndroidKeystoreApiKeyCipher(
                keyAlias = "mochi_tool_secret_v1",
            ),
            mcpClient = mcpClient,
            extensionClient = extensionClient,
            agentLinkClient = agentLinkClient,
            termuxClient = termuxClient,
            termuxRuntime = termuxRuntime,
            webdavClient = webdavClient,
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

    val agentTaskRuntime = com.example.mochi_pet.core.tasks.AgentTaskRuntime()

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
        AgentBrowserRuntime(this, com.example.mochi_pet.core.browser.BrowserSettings(toolDataStore)).also { browser ->
            applicationScope.launch {
                com.example.mochi_pet.platform.browser.observeUserAssistanceNotification(this@MochiApplication, browser.assistance)
            }
        }
    }

    val browserCredentialVault by lazy {
        com.example.mochi_pet.core.browser.BrowserCredentialVault(
            PreferenceDataStoreFactory.create(scope = applicationScope) {
                File(noBackupFilesDir, "browser_credentials.preferences_pb")
            },
            AndroidKeystoreApiKeyCipher("mochi_browser_credentials_v1"),
        )
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
    ): Boolean {
        providerRuns.register(requireNotNull(currentCoroutineContext()[kotlinx.coroutines.Job]))
        // A persisted RUNNING receipt must never replay uncertain external side effects.
        val schedule = withContext(NonCancellable) {
            agentScheduleStore.startRun(id)
        }
        if (schedule == null) {
            if (agentScheduleStore.run(id)?.state == com.example.mochi_pet.core.schedule.ScheduleRunState.RUNNING) {
                agentScheduleStore.finishRun(
                    id, com.example.mochi_pet.core.schedule.ScheduleRunState.INTERRUPTED,
                    error = "Previous execution was interrupted; it was not replayed",
                )?.let { agentScheduleController.sync(it) }
            }
            return false
        }
        val userText = "[Scheduled Agent · ${schedule.name}]\n${schedule.prompt}"
        return try {
            currentCoroutineContext().ensureActive()
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
                    extensionScope = ExtensionToolScope.SCHEDULED,
                    includeAgentLink = false,
                    taskScheduleId = schedule.scheduleId,
                    taskTitle = schedule.name,
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
            agentMemoryRepository.saveScheduledTurn(schedule.id, userText, reply.reply)
            val updated = agentScheduleStore.finishRun(
                id = id,
                state = com.example.mochi_pet.core.schedule.ScheduleRunState.SUCCESS,
                output = reply.reply,
            )
            updated?.let { agentScheduleController.sync(it) }
            showAgentScheduleNotification(
                scheduleName = schedule.name,
                scheduleId = schedule.scheduleId,
                runId = schedule.id,
                text = reply.reply,
                success = true,
            )
            true
        } catch (error: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                agentScheduleStore.finishRun(id, com.example.mochi_pet.core.schedule.ScheduleRunState.CANCELLED)
                    ?.let { agentScheduleController.sync(it) }
            }
            throw error
        } catch (error: Exception) {
            SCHEDULE_LOGGER.log(
                Level.SEVERE,
                "scheduled_agent_failed id=$id type=" +
                    "${error::class.java.simpleName} message=" +
                    error.message.orEmpty().take(240),
                error,
            )
            val message = error.message ?: "Mochi could not complete this request"
            agentMemoryRepository.saveScheduledTurn(
                schedule.id,
                userText,
                "Scheduled task failed: $message",
            )
            val updated = agentScheduleStore.finishRun(
                id = id,
                state = com.example.mochi_pet.core.schedule.ScheduleRunState.FAILED,
                error = message,
            )
            updated?.let { agentScheduleController.sync(it) }
            showAgentScheduleNotification(
                scheduleName = schedule.name,
                scheduleId = schedule.scheduleId,
                runId = schedule.id,
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
