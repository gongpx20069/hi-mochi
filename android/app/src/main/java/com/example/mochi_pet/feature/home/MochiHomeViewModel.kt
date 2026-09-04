package com.example.mochi_pet.feature.home

import android.database.sqlite.SQLiteException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.createAgentRunner
import com.example.mochi_pet.core.agent.AgentOrchestrator
import com.example.mochi_pet.core.agent.AgentPipelineObserver
import com.example.mochi_pet.core.agent.AgentPipelineStage
import com.example.mochi_pet.core.agent.AgentReply
import com.example.mochi_pet.core.agent.AgentRunRequest
import com.example.mochi_pet.core.agent.AgentRunner
import com.example.mochi_pet.core.agent.tool.ManageMochiCalendarTool
import com.example.mochi_pet.core.agent.tool.ManageMochiTodoTool
import com.example.mochi_pet.core.agent.tool.AgentToolJson
import com.example.mochi_pet.core.agent.tool.SandboxedJavaScriptTool
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.extensions.ExtensionActivityTarget
import com.example.mochi_pet.core.extensions.MochiExtensionClient
import com.example.mochi_pet.core.agent.tool.ToolRegistry
import com.example.mochi_pet.core.browser.agentBrowserTools
import com.example.mochi_pet.core.database.PlannerStore
import com.example.mochi_pet.core.memory.AgentMemoryRepository
import com.example.mochi_pet.core.persona.PersonaRepository
import com.example.mochi_pet.core.persona.PersonaContext
import com.example.mochi_pet.core.maps.AMAP_CONSOLE_URL
import com.example.mochi_pet.core.model.CalendarEvent
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.model.MochiTodo
import com.example.mochi_pet.core.model.MochiTodoDraft
import com.example.mochi_pet.core.model.TodoStatus
import com.example.mochi_pet.core.mcp.McpException
import com.example.mochi_pet.core.navigation.MochiNavigationIntent
import com.example.mochi_pet.core.navigation.MochiNavigationReducer
import com.example.mochi_pet.core.navigation.NavigateMochiUiTool
import com.example.mochi_pet.core.navigation.NavigationDecision
import com.example.mochi_pet.core.navigation.NavigationPolicy
import com.example.mochi_pet.core.navigation.UiDirectiveSink
import com.example.mochi_pet.core.presentation.CardPlacement
import com.example.mochi_pet.core.presentation.CardAction
import com.example.mochi_pet.core.presentation.CardActionType
import com.example.mochi_pet.core.presentation.CardPresentation
import com.example.mochi_pet.core.settings.ProviderSettingsIncompleteException
import com.example.mochi_pet.core.settings.AgentSettings
import com.example.mochi_pet.core.settings.AgentSettingsRepository
import com.example.mochi_pet.core.settings.AppLanguage
import com.example.mochi_pet.core.settings.ProviderSettingsInput
import com.example.mochi_pet.core.settings.ProviderSettingsRepository
import com.example.mochi_pet.core.settings.ProviderShareManager
import com.example.mochi_pet.core.settings.ProviderShareSelection
import com.example.mochi_pet.core.settings.ProviderSettingsSummary
import com.example.mochi_pet.core.settings.SpeechSettingsInput
import com.example.mochi_pet.core.settings.SpeechSettingsRepository
import com.example.mochi_pet.core.settings.SpeechSettingsSummary
import com.example.mochi_pet.core.schedule.AgentSchedule
import com.example.mochi_pet.core.schedule.AgentScheduleController
import com.example.mochi_pet.core.schedule.AgentScheduleDraft
import com.example.mochi_pet.core.schedule.AgentScheduleStore
import com.example.mochi_pet.core.skills.MarketSkillSummary
import com.example.mochi_pet.core.skills.MochiSkill
import com.example.mochi_pet.core.skills.DownloadedSkill
import com.example.mochi_pet.core.skills.SkillMarketClient
import com.example.mochi_pet.core.skills.SkillMarketException
import com.example.mochi_pet.core.skills.SkillOrigin
import com.example.mochi_pet.core.skills.SkillReadiness
import com.example.mochi_pet.core.skills.SkillRepository
import com.example.mochi_pet.core.skills.LoadSkillTool
import com.example.mochi_pet.core.skills.requiredToolNames
import com.example.mochi_pet.core.location.DeviceLocationException
import com.example.mochi_pet.core.tools.ManualMcpServerInput
import com.example.mochi_pet.core.tools.ToolCatalogRepository
import com.example.mochi_pet.core.tools.ToolCatalogSummary
import com.example.mochi_pet.core.tools.skillReadiness
import com.example.mochi_pet.core.weather.CurrentWeather
import com.example.mochi_pet.core.weather.CurrentWeatherTool
import com.example.mochi_pet.core.weather.WeatherException
import com.example.mochi_pet.core.weather.WeatherRepository
import com.example.mochi_pet.core.web.WebContentException
import com.example.mochi_pet.core.voice.VoiceRuntime
import com.example.mochi_pet.core.voice.VoiceRuntimeState
import com.example.mochi_pet.core.wake.WakeRuntime
import com.example.mochi_pet.core.wake.WakeRuntimeState
import com.example.mochi_pet.platform.browser.AgentBrowserRuntime
import com.example.mochi_pet.platform.browser.AgentBrowserUiState
import com.example.mochi_pet.platform.location.LocationPermissionGate
import java.io.IOException
import java.time.Clock
import java.util.logging.Level
import java.util.logging.Logger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class PlannerSurfaceState(
    val isLoading: Boolean = false,
    val date: LocalDate? = null,
    val events: List<CalendarEvent> = emptyList(),
    val todos: List<MochiTodo> = emptyList(),
    val schedules: List<AgentSchedule> = emptyList(),
    val errorMessage: String? = null,
)

data class PersonaUiState(
    val context: PersonaContext = PersonaContext("", "", ""),
    val isLoading: Boolean = true,
    val feedback: String? = null,
)

data class AgentSettingsUiState(
    val settings: AgentSettings = AgentSettings(),
    val isLoading: Boolean = true,
    val feedback: String? = null,
)

data class DayRange(
    val start: Instant,
    val end: Instant,
)

enum class ConversationRole {
    USER,
    ASSISTANT,
}

data class ConversationMessage(
    val id: String,
    val role: ConversationRole,
    val text: String,
    val sentAt: Instant,
    val card: CardPresentation? = null,
)

data class ConversationUiState(
    val messages: List<ConversationMessage> = emptyList(),
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val emotion: String = "neutral",
)

enum class ChatPipelineStage {
    IDLE,
    LISTENING,
    SKILLING,
    THINKING,
    SUBAGENT,
    TOOL,
    SUMMARY,
    SPEAKING,
}

data class ChatPipelineUiState(
    val stage: ChatPipelineStage = ChatPipelineStage.IDLE,
    val detail: String? = null,
) {
    val isActive: Boolean
        get() = stage != ChatPipelineStage.IDLE
}

data class ProviderSettingsUiState(
    val summary: ProviderSettingsSummary = ProviderSettingsSummary(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val feedback: String? = null,
)

data class SpeechSettingsUiState(
    val summary: SpeechSettingsSummary = SpeechSettingsSummary(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val feedback: String? = null,
)

data class ProviderShareUiState(
    val isWorking: Boolean = false,
    val shareLink: String? = null,
    val pendingImportLink: String? = null,
    val feedback: String? = null,
)

data class SkillsUiState(
    val skills: List<MochiSkill> = emptyList(),
    val readinessById: Map<String, SkillReadiness> = emptyMap(),
    val searchResults: List<MarketSkillSummary> = emptyList(),
    val isLoading: Boolean = true,
    val isSearching: Boolean = false,
    val marketHeading: String = "Trending today",
    val preview: DownloadedSkill? = null,
    val feedback: String? = null,
)

data class ToolsUiState(
    val catalog: ToolCatalogSummary = ToolCatalogSummary(),
    val isLoading: Boolean = true,
    val feedback: String? = null,
    val authorizationUrl: String? = null,
    val extensionActivityTarget: ExtensionActivityTarget? = null,
)

data class CameraSnapshotUiState(
    val bitmap: Bitmap,
    val readyForModel: Boolean,
    val cameraName: String,
    val home: String?,
    val room: String?,
    val eventType: String?,
    val capturedAt: String?,
)

data class WeatherUiState(
    val isLoading: Boolean = false,
    val weather: CurrentWeather? = null,
    val errorMessage: String? = null,
)

fun dayRange(
    date: LocalDate,
    zoneId: ZoneId,
): DayRange =
    DayRange(
        start = date.atStartOfDay(zoneId).toInstant(),
        end = date.plusDays(1).atStartOfDay(zoneId).toInstant(),
    )

class MochiHomeViewModel(
    private val plannerStore: PlannerStore,
    private val agentScheduleStore: AgentScheduleStore? = null,
    private val agentScheduleController: AgentScheduleController? = null,
    private val providerSettingsRepository: ProviderSettingsRepository? = null,
    private val speechSettingsRepository: SpeechSettingsRepository? = null,
    private val providerShareManager: ProviderShareManager? = null,
    private val agentSettingsRepository: AgentSettingsRepository? = null,
    private val personaRepository: PersonaRepository? = null,
    private val agentMemoryRepository: AgentMemoryRepository? = null,
    private val agentRunnerBuilder:
        (suspend (
            UiDirectiveSink,
            AgentPipelineObserver,
            (CurrentWeather) -> Unit,
        ) -> AgentRunner)? = null,
    private val voiceRuntime: VoiceRuntime? = null,
    private val wakeRuntime: WakeRuntime? = null,
    private val skillRepository: SkillRepository? = null,
    private val skillMarketClient: SkillMarketClient? = null,
    private val toolCatalogRepository: ToolCatalogRepository? = null,
    private val extensionClient: MochiExtensionClient? = null,
    private val agentBrowserRuntime: AgentBrowserRuntime? = null,
    private val weatherRepository: WeatherRepository? = null,
    private val locationPermissionGate: LocationPermissionGate? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableSurface = MutableStateFlow<MochiSurface>(MochiSurface.Face)
    private val mutablePlannerState = MutableStateFlow(PlannerSurfaceState())
    private val mutableConversationState =
        MutableStateFlow(ConversationUiState())
    private val mutableProviderSettingsState =
        MutableStateFlow(ProviderSettingsUiState())
    private val mutableSpeechSettingsState =
        MutableStateFlow(SpeechSettingsUiState())
    private val mutableProviderShareState =
        MutableStateFlow(ProviderShareUiState())
    private val mutableAgentSettingsState =
        MutableStateFlow(AgentSettingsUiState())
    private val mutablePersonaState = MutableStateFlow(PersonaUiState())
    private val unavailableVoiceState = MutableStateFlow(VoiceRuntimeState())
    private val unavailableWakeState = MutableStateFlow(WakeRuntimeState())
    private val mutableWakeFeedback = MutableStateFlow<String?>(null)
    private val mutablePipelineState = MutableStateFlow(ChatPipelineUiState())
    private val mutableSkillsState = MutableStateFlow(SkillsUiState())
    private val mutableToolsState = MutableStateFlow(ToolsUiState())
    private val mutableWeatherState = MutableStateFlow(WeatherUiState())
    private val mutableHomeCard = MutableStateFlow<CardPresentation?>(null)
    private val mutableCameraSnapshot =
        MutableStateFlow<CameraSnapshotUiState?>(null)
    private val unavailableLocationPermissionRequest = MutableStateFlow(false)
    private val unavailableBrowserState =
        MutableStateFlow(AgentBrowserUiState())
    private var loadVersion = 0L
    private var interactionVersion = 0L
    private var agentJob: Job? = null

    val surface: StateFlow<MochiSurface> = mutableSurface.asStateFlow()
    val plannerState: StateFlow<PlannerSurfaceState> =
        mutablePlannerState.asStateFlow()
    val conversationState: StateFlow<ConversationUiState> =
        mutableConversationState.asStateFlow()
    val providerSettingsState: StateFlow<ProviderSettingsUiState> =
        mutableProviderSettingsState.asStateFlow()
    val speechSettingsState: StateFlow<SpeechSettingsUiState> =
        mutableSpeechSettingsState.asStateFlow()
    val providerShareState: StateFlow<ProviderShareUiState> =
        mutableProviderShareState.asStateFlow()
    val agentSettingsState: StateFlow<AgentSettingsUiState> =
        mutableAgentSettingsState.asStateFlow()
    val personaState: StateFlow<PersonaUiState> =
        mutablePersonaState.asStateFlow()
    val voiceState: StateFlow<VoiceRuntimeState> =
        voiceRuntime?.state ?: unavailableVoiceState.asStateFlow()
    val wakeState: StateFlow<WakeRuntimeState> =
        wakeRuntime?.state ?: unavailableWakeState.asStateFlow()
    val wakeFeedback: StateFlow<String?> = mutableWakeFeedback.asStateFlow()
    val pipelineState: StateFlow<ChatPipelineUiState> =
        mutablePipelineState.asStateFlow()
    val skillsState: StateFlow<SkillsUiState> = mutableSkillsState.asStateFlow()
    val toolsState: StateFlow<ToolsUiState> = mutableToolsState.asStateFlow()
    val weatherState: StateFlow<WeatherUiState> =
        mutableWeatherState.asStateFlow()
    val homeCard: StateFlow<CardPresentation?> = mutableHomeCard.asStateFlow()
    val cameraSnapshot: StateFlow<CameraSnapshotUiState?> =
        mutableCameraSnapshot.asStateFlow()
    val browserState: StateFlow<AgentBrowserUiState> =
        agentBrowserRuntime?.state ?: unavailableBrowserState.asStateFlow()
    val locationPermissionRequest: StateFlow<Boolean> =
        locationPermissionGate?.request
            ?: unavailableLocationPermissionRequest.asStateFlow()

    init {
        loadProviderSettings()
        loadSpeechSettings()
        loadAgentContext()
        loadSkills()
        loadTools()
        observeExtensionAttachments()
    }

    fun browserWebView(context: Context): WebView? =
        agentBrowserRuntime?.webViewForUi(context)

    fun releaseBrowserWebView() {
        agentBrowserRuntime?.releaseWebViewFromUi()
    }

    fun navigate(intent: MochiNavigationIntent) {
        val target = MochiNavigationReducer.reduce(intent)
        if (
            target == MochiSurface.Face ||
            target == MochiSurface.DateTime ||
            target == MochiSurface.Weather
        ) {
            mutableHomeCard.value = null
        }
        if (target != MochiSurface.Card) {
            mutableCameraSnapshot.value = null
        }
        mutableSurface.value = target
        load(target)
    }

    fun resolveLocationPermission(granted: Boolean) {
        locationPermissionGate?.resolve(granted)
    }

    fun createTodo(
        content: String,
        scheduledDate: LocalDate?,
    ) {
        viewModelScope.launch(ioDispatcher) {
            runCatching {
                plannerStore.createTodo(
                    MochiTodoDraft(
                        content = content,
                        scheduledDate = scheduledDate,
                    ),
                )
            }.onSuccess {
                load(mutableSurface.value)
            }.onFailure(::showError)
        }
    }

    fun completeTodo(id: String) {
        viewModelScope.launch(ioDispatcher) {
            runCatching {
                plannerStore.completeTodo(id)
            }.onSuccess {
                load(mutableSurface.value)
            }.onFailure(::showError)
        }
    }

    fun setScheduleEnabled(
        id: String,
        enabled: Boolean,
    ) {
        val store = agentScheduleStore ?: return
        val controller = agentScheduleController ?: return
        viewModelScope.launch(ioDispatcher) {
            runCatching {
                val existing = store.get(id)
                    ?: throw IllegalArgumentException(
                        "Agent schedule not found: $id",
                    )
                val updated = store.set(
                    id = id,
                    draft = AgentScheduleDraft(
                        name = existing.name,
                        prompt = existing.prompt,
                        type = existing.type,
                        runAt = existing.runAt,
                        localTime = existing.localTime,
                        daysOfWeek = existing.daysOfWeek,
                        intervalMinutes = existing.intervalMinutes,
                        timezone = existing.timezone,
                        enabled = enabled,
                    ),
                )
                controller.sync(updated)
            }.onSuccess {
                load(mutableSurface.value)
            }.onFailure(::showError)
        }
    }

    fun runSchedule(id: String) {
        val controller = agentScheduleController ?: return
        viewModelScope.launch(ioDispatcher) {
            runCatching {
                controller.runNow(id)
            }.onFailure(::showError)
        }
    }

    fun removeSchedule(id: String) {
        val store = agentScheduleStore ?: return
        val controller = agentScheduleController ?: return
        viewModelScope.launch(ioDispatcher) {
            runCatching {
                store.remove(id)
                controller.cancel(id)
            }.onSuccess {
                load(mutableSurface.value)
            }.onFailure(::showError)
        }
    }

    fun performCardAction(
        card: CardPresentation,
        action: CardAction,
    ) {
        when (action.type) {
            CardActionType.OPEN_TODAY ->
                navigate(MochiNavigationIntent.ShowToday)
            CardActionType.OPEN_CALENDAR -> {
                val date = action.date ?: return
                navigate(MochiNavigationIntent.ShowCalendarDay(date))
            }
            CardActionType.OPEN_TALK ->
                navigate(MochiNavigationIntent.ShowConversation)
            CardActionType.COMPLETE_TODO -> {
                val targetId = action.targetId ?: return
                completeCardTodo(card.id, targetId)
            }
            CardActionType.EXPAND -> {
                mutableHomeCard.value = card.copy(
                    placement = CardPlacement.HOME,
                )
                navigate(MochiNavigationIntent.ShowCard)
            }
            CardActionType.DISMISS -> dismissCard(card.id)
            CardActionType.OPEN_SOURCE -> Unit
        }
    }

    fun reload() {
        load(mutableSurface.value)
    }

    fun sendConversation(query: String) {
        sendConversation(query, continueListeningAfterReply = false)
    }

    private fun sendConversation(
        query: String,
        continueListeningAfterReply: Boolean,
    ) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return
        }
        val settingsRepository = providerSettingsRepository
        val runnerBuilder = agentRunnerBuilder
        if (settingsRepository == null || runnerBuilder == null) {
            mutableConversationState.update {
                it.copy(errorMessage = "Agent runtime is unavailable")
            }
            wakeRuntime?.resume()
            return
        }

        voiceRuntime?.stopSpeaking()
        agentJob?.cancel()
        val version = ++interactionVersion
        mutableConversationState.update {
            it.copy(
                messages = it.messages + ConversationMessage(
                    id = UUID.randomUUID().toString(),
                    role = ConversationRole.USER,
                    text = trimmed,
                    sentAt = clock.instant(),
                ),
                isSending = true,
                errorMessage = null,
            )
        }
        mutablePipelineState.value = ChatPipelineUiState(
            stage = ChatPipelineStage.SKILLING,
        )
        agentJob = viewModelScope.launch(ioDispatcher) {
            var interactionWeather: CurrentWeather? = null
            try {
                val provider = settingsRepository.loadRuntimeConfig()
                val agentSettings = agentSettingsRepository?.load()
                    ?: AgentSettings()
                val persona = personaRepository?.load()
                val memoryContext = agentMemoryRepository?.loadContext(
                    query = trimmed,
                    recentTurns = agentSettings.recentConversationTurns,
                )
                val sink = UiDirectiveSink { decision ->
                    if (version == interactionVersion) {
                        navigate(decision.intent)
                    }
                }
                val observer = AgentPipelineObserver { stage, detail ->
                    if (version == interactionVersion) {
                        mutablePipelineState.value = ChatPipelineUiState(
                            stage = stage.toUiStage(),
                            detail = detail,
                        )
                    }
                }
                agentBrowserRuntime?.beginTurn()
                val reply = try {
                    runnerBuilder(
                        sink,
                        observer,
                        { weather ->
                            interactionWeather = weather
                            showWeather(weather)
                        },
                    ).run(
                        AgentRunRequest(
                            provider = provider,
                            query = trimmed,
                            currentEmotion =
                                mutableConversationState.value.emotion,
                            context = ToolExecutionContext(
                                currentDate = LocalDate.now(clock),
                                currentSurface = mutableSurface.value,
                                modelImageInputAllowed =
                                    provider.imageInputEnabled,
                            ),
                            history = memoryContext?.recentMessages.orEmpty(),
                            personaSections = persona?.sections.orEmpty(),
                            recalledMemories =
                                memoryContext?.recalledLines.orEmpty(),
                        ),
                    )
                } finally {
                    withContext(NonCancellable) {
                        agentBrowserRuntime?.closeTurn()
                    }
                }
                if (version != interactionVersion) {
                    return@launch
                }
                val deliveredText = reply.presentationReply(
                    interactionWeather
                        ?: mutableWeatherState.value.weather,
                )
                val memoryError = persistSuccessfulTurn(
                    userText = trimmed,
                    assistantText = deliveredText,
                )
                deliverAssistantReply(
                    version = version,
                    text = deliveredText,
                    emotion = reply.emotion,
                    card = reply.card,
                    continueListeningAfterReply = continueListeningAfterReply,
                )
                memoryError?.let { message ->
                    mutableConversationState.update {
                        it.copy(errorMessage = message)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProviderSettingsIncompleteException) {
                if (version == interactionVersion) {
                    mutableConversationState.update {
                        it.copy(
                            isSending = false,
                            errorMessage = error.message,
                        )
                    }
                    mutablePipelineState.value = ChatPipelineUiState()
                    wakeRuntime?.resume()
                }
            } catch (error: Exception) {
                if (version == interactionVersion) {
                    AGENT_LOGGER.log(
                        Level.SEVERE,
                        "agent_request_failed type=" +
                            error::class.java.simpleName +
                            " message=" +
                            error.message.orEmpty().take(240),
                        error,
                    )
                    val weather = interactionWeather
                    if (weather != null) {
                        val fallbackReply = weather.spokenSummary()
                        val memoryError = persistSuccessfulTurn(
                            userText = trimmed,
                            assistantText = fallbackReply,
                        )
                        deliverAssistantReply(
                            version = version,
                            text = fallbackReply,
                            emotion = "neutral",
                            card = null,
                            continueListeningAfterReply =
                                continueListeningAfterReply,
                        )
                        memoryError?.let { message ->
                            mutableConversationState.update {
                                it.copy(errorMessage = message)
                            }
                        }
                    } else {
                        mutableConversationState.update {
                            it.copy(
                                isSending = false,
                                errorMessage =
                                    "Mochi could not complete this request",
                            )
                        }
                        mutablePipelineState.value = ChatPipelineUiState()
                        wakeRuntime?.resume()
                    }
                }
            }
        }
    }

    private fun deliverAssistantReply(
        version: Long,
        text: String,
        emotion: String,
        card: CardPresentation?,
        continueListeningAfterReply: Boolean,
    ) {
        when (card?.placement) {
            CardPlacement.HOME -> {
                mutableHomeCard.value = card
                navigate(MochiNavigationIntent.ShowCard)
            }
            CardPlacement.INLINE,
            CardPlacement.DEFERRED,
            CardPlacement.AUTO,
            null,
            -> Unit
        }
        mutableConversationState.update {
            it.copy(
                messages = it.messages + ConversationMessage(
                    id = UUID.randomUUID().toString(),
                    role = ConversationRole.ASSISTANT,
                    text = text,
                    sentAt = clock.instant(),
                    card = card,
                ),
                isSending = false,
                errorMessage = null,
                emotion = emotion,
            )
        }
        val runtime = voiceRuntime
        if (runtime == null) {
            mutablePipelineState.value = ChatPipelineUiState()
            wakeRuntime?.resume()
            return
        }
        mutablePipelineState.value = ChatPipelineUiState(
            stage = ChatPipelineStage.SPEAKING,
        )
        runtime.speak(text) {
            if (version != interactionVersion) {
                return@speak
            }
            if (continueListeningAfterReply) {
                startFollowUpListening(version)
            } else {
                mutablePipelineState.value = ChatPipelineUiState()
                wakeRuntime?.resume()
            }
        }
    }

    fun cancelConversation() {
        cancelAgentInteraction()
        voiceRuntime?.stopListening()
        voiceRuntime?.stopSpeaking()
        wakeRuntime?.resume()
    }

    fun startVoiceInput() {
        val runtime = voiceRuntime ?: return
        cancelAgentInteraction()
        val version = interactionVersion
        runtime.stopSpeaking()
        val startListening = startListening@ {
            if (version != interactionVersion) {
                return@startListening
            }
            mutablePipelineState.value = ChatPipelineUiState(
                stage = ChatPipelineStage.LISTENING,
            )
            runtime.startListening(
                onFinalTranscript = { transcript ->
                    if (version == interactionVersion) {
                        wakeRuntime?.resume()
                        sendConversation(
                            transcript,
                            continueListeningAfterReply = true,
                        )
                    }
                },
                onNoResult = {
                    if (version == interactionVersion) {
                        mutablePipelineState.value = ChatPipelineUiState()
                        wakeRuntime?.resume()
                    }
                },
            )
        }
        val wake = wakeRuntime
        if (wake == null) {
            startListening()
        } else {
            wake.pause(startListening)
        }
    }

    private fun startFollowUpListening(version: Long) {
        val runtime = voiceRuntime
        if (runtime == null || version != interactionVersion) {
            mutablePipelineState.value = ChatPipelineUiState()
            wakeRuntime?.resume()
            return
        }
        val startListening = startListening@ {
            if (version != interactionVersion) {
                return@startListening
            }
            mutablePipelineState.value = ChatPipelineUiState(
                stage = ChatPipelineStage.LISTENING,
            )
            runtime.startListening(
                onFinalTranscript = { transcript ->
                    if (version == interactionVersion) {
                        wakeRuntime?.resume()
                        sendConversation(
                            transcript,
                            continueListeningAfterReply = true,
                        )
                    }
                },
                onNoResult = {
                    if (version == interactionVersion) {
                        mutablePipelineState.value = ChatPipelineUiState()
                        wakeRuntime?.resume()
                    }
                },
            )
        }
        val wake = wakeRuntime
        if (wake == null) {
            startListening()
        } else {
            wake.pause(startListening)
        }
    }

    private fun cancelAgentInteraction() {
        interactionVersion += 1
        agentJob?.cancel()
        agentJob = null
        mutableConversationState.update { it.copy(isSending = false) }
        mutablePipelineState.value = ChatPipelineUiState()
    }

    fun stopVoiceInput() {
        cancelAgentInteraction()
        voiceRuntime?.stopListening()
        wakeRuntime?.resume()
    }

    fun reportMicrophonePermissionDenied() {
        mutableConversationState.update {
            it.copy(errorMessage = "Microphone permission is required")
        }
    }

    fun enableWakeWord() {
        mutableWakeFeedback.value = null
        wakeRuntime?.enable()
    }

    fun disableWakeWord() {
        wakeRuntime?.disable()
        mutableWakeFeedback.value = null
    }

    fun reportWakePermissionDenied() {
        wakeRuntime?.disable()
        mutableWakeFeedback.value =
            "Microphone and notification permissions are required"
    }

    fun saveProviderSettings(input: ProviderSettingsInput) {
        val repository = providerSettingsRepository ?: return
        mutableProviderSettingsState.update {
            it.copy(isSaving = true, feedback = null)
        }

        viewModelScope.launch(ioDispatcher) {
            try {
                val summary = repository.save(input)
                mutableProviderSettingsState.value = ProviderSettingsUiState(
                    summary = summary,
                    isLoading = false,
                    feedback = "Provider settings saved",
                )
            } catch (error: IllegalArgumentException) {
                mutableProviderSettingsState.update {
                    it.copy(
                        isSaving = false,
                        feedback = error.message ?: "Invalid provider settings",
                    )
                }
            }
        }
    }

    fun saveSpeechSettings(input: SpeechSettingsInput) {
        val repository = speechSettingsRepository ?: return
        mutableSpeechSettingsState.update {
            it.copy(isSaving = true, feedback = null)
        }

        viewModelScope.launch(ioDispatcher) {
            try {
                val summary = repository.save(input)
                mutableSpeechSettingsState.value = SpeechSettingsUiState(
                    summary = summary,
                    isLoading = false,
                    feedback = "Speech settings saved",
                )
            } catch (error: IllegalArgumentException) {
                mutableSpeechSettingsState.update {
                    it.copy(
                        isSaving = false,
                        feedback =
                            error.message ?: "Invalid speech settings",
                    )
                }
            } catch (error: IllegalStateException) {
                mutableSpeechSettingsState.update {
                    it.copy(
                        isSaving = false,
                        feedback =
                            error.message
                                ?: "Speech settings could not be saved",
                    )
                }
            }
        }
    }

    fun createProviderShareLink(selection: ProviderShareSelection) {
        val manager = providerShareManager ?: return
        mutableProviderShareState.value =
            ProviderShareUiState(isWorking = true)
        viewModelScope.launch(ioDispatcher) {
            try {
                mutableProviderShareState.value = ProviderShareUiState(
                    shareLink = manager.createShareLink(selection),
                )
            } catch (error: IllegalStateException) {
                mutableProviderShareState.value = ProviderShareUiState(
                    feedback = error.message
                        ?: "Provider settings cannot be shared",
                )
            } catch (error: IllegalArgumentException) {
                mutableProviderShareState.value = ProviderShareUiState(
                    feedback = error.message
                        ?: "Provider settings cannot be shared",
                )
            }
        }
    }

    fun consumeProviderShareLink() {
        mutableProviderShareState.update { it.copy(shareLink = null) }
    }

    fun stageProviderImport(link: String) {
        mutableProviderShareState.value = ProviderShareUiState(
            pendingImportLink = link,
        )
    }

    fun cancelProviderImport() {
        mutableProviderShareState.value = ProviderShareUiState()
    }

    fun confirmProviderImport() {
        val manager = providerShareManager ?: return
        val link = mutableProviderShareState.value.pendingImportLink ?: return
        mutableProviderShareState.value =
            ProviderShareUiState(isWorking = true)
        viewModelScope.launch(ioDispatcher) {
            try {
                manager.importShareLink(link)
                val provider = providerSettingsRepository?.loadSummary()
                val speech = speechSettingsRepository?.loadSummary()
                if (provider != null) {
                    mutableProviderSettingsState.value =
                        ProviderSettingsUiState(
                            summary = provider,
                            isLoading = false,
                        )
                }
                if (speech != null) {
                    mutableSpeechSettingsState.value =
                        SpeechSettingsUiState(
                            summary = speech,
                            isLoading = false,
                        )
                }
                toolCatalogRepository?.loadSummary()?.let { catalog ->
                    mutableToolsState.value = ToolsUiState(
                        catalog = catalog,
                        isLoading = false,
                    )
                    refreshSkillReadiness(catalog)
                }
                mutableProviderShareState.value = ProviderShareUiState(
                    feedback = "Shared Providers imported",
                )
            } catch (error: IllegalArgumentException) {
                mutableProviderShareState.value = ProviderShareUiState(
                    feedback = error.message
                        ?: "Provider share link could not be imported",
                )
            } catch (error: IllegalStateException) {
                mutableProviderShareState.value = ProviderShareUiState(
                    feedback = error.message
                        ?: "Provider share link could not be imported",
                )
            } catch (error: McpException) {
                mutableProviderShareState.value = ProviderShareUiState(
                    feedback = error.message
                        ?: "Shared MCP Tools could not be connected",
                )
            } catch (error: WebContentException) {
                mutableProviderShareState.value = ProviderShareUiState(
                    feedback = error.message
                        ?: "Shared MCP endpoint is not allowed",
                )
            }
        }
    }

    fun savePersona(
        soul: String,
        user: String,
        agents: String,
    ) {
        val repository = personaRepository ?: return
        mutablePersonaState.update {
            it.copy(isLoading = true, feedback = null)
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                val context = repository.updateAll(
                    PersonaContext(
                        soul = soul,
                        user = user,
                        agents = agents,
                    ),
                )
                mutablePersonaState.value = PersonaUiState(
                    context = context,
                    isLoading = false,
                    feedback = "Persona files saved",
                )
            } catch (error: IllegalArgumentException) {
                mutablePersonaState.update {
                    it.copy(
                        isLoading = false,
                        feedback = error.message ?: "Persona update failed",
                    )
                }
            } catch (error: IOException) {
                mutablePersonaState.update {
                    it.copy(
                        isLoading = false,
                        feedback = error.message ?: "Persona update failed",
                    )
                }
            }
        }
    }

    fun setRecentConversationTurns(turns: Int) {
        val repository = agentSettingsRepository ?: return
        viewModelScope.launch(ioDispatcher) {
            try {
                val settings = repository.setRecentConversationTurns(turns)
                mutableAgentSettingsState.value = AgentSettingsUiState(
                    settings = settings,
                    isLoading = false,
                    feedback = "Agent context settings saved",
                )
                loadPersistedConversation(settings.recentConversationTurns)
            } catch (error: IllegalArgumentException) {
                mutableAgentSettingsState.update {
                    it.copy(
                        isLoading = false,
                        feedback = error.message,
                    )
                }
            } catch (error: IOException) {
                mutableAgentSettingsState.update {
                    it.copy(
                        isLoading = false,
                        feedback = "Could not save Agent context settings",
                    )
                }
            }
        }
    }

    fun setFocusStandby(
        enabled: Boolean,
        delaySeconds: Int,
    ) {
        val repository = agentSettingsRepository ?: return
        viewModelScope.launch(ioDispatcher) {
            try {
                val settings = repository.setFocusStandby(
                    enabled = enabled,
                    delaySeconds = delaySeconds,
                )
                mutableAgentSettingsState.value = AgentSettingsUiState(
                    settings = settings,
                    isLoading = false,
                    feedback = "Fullscreen standby settings saved",
                )
            } catch (error: IllegalArgumentException) {
                mutableAgentSettingsState.update {
                    it.copy(
                        isLoading = false,
                        feedback = error.message,
                    )
                }
            } catch (error: IOException) {
                mutableAgentSettingsState.update {
                    it.copy(
                        isLoading = false,
                        feedback = "Could not save fullscreen standby settings",
                    )
                }
            }
        }
    }

    fun searchSkills(query: String) {
        val client = skillMarketClient ?: return
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            return
        }
        mutableSkillsState.update {
            it.copy(isSearching = true, feedback = null)
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                val results = client.search(normalized)
                mutableSkillsState.update {
                    it.copy(
                        searchResults = results,
                        isSearching = false,
                        marketHeading = "Search results",
                    )
                }
            } catch (error: SkillMarketException) {
                showSkillError(error, "Skill search failed")
            }
        }
    }

    fun loadPopularSkills() {
        val client = skillMarketClient ?: return
        if (
            mutableSkillsState.value.searchResults.isNotEmpty() ||
            mutableSkillsState.value.isSearching
        ) {
            return
        }
        mutableSkillsState.update {
            it.copy(isSearching = true, feedback = null)
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                val results = client.popular()
                mutableSkillsState.update {
                    it.copy(
                        searchResults = results,
                        isSearching = false,
                        marketHeading = "Trending today",
                    )
                }
            } catch (error: SkillMarketException) {
                showSkillError(error, "Popular skills could not be loaded")
            }
        }
    }

    fun previewSkill(summary: MarketSkillSummary) {
        val client = skillMarketClient ?: return
        mutableSkillsState.update {
            it.copy(isLoading = true, feedback = "Loading ${summary.name}...")
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                val preview = client.download(summary)
                mutableSkillsState.update {
                    it.copy(
                        preview = preview,
                        isLoading = false,
                        feedback = null,
                    )
                }
            } catch (error: SkillMarketException) {
                showSkillError(error, "Skill preview failed")
            }
        }
    }

    fun clearSkillPreview() {
        mutableSkillsState.update { it.copy(preview = null) }
    }

    fun installSkill(summary: MarketSkillSummary) {
        val repository = skillRepository ?: return
        val client = skillMarketClient ?: return
        mutableSkillsState.update {
            it.copy(isLoading = true, feedback = "Installing ${summary.name}...")
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                repository.install(client.download(summary))
                refreshSkills("Installed ${summary.name}; enable it when ready")
            } catch (error: SkillMarketException) {
                showSkillError(error, "Skill installation failed")
            } catch (error: IllegalArgumentException) {
                showSkillError(error, "Skill installation failed")
            } catch (error: SQLiteException) {
                showSkillError(error, "Skill installation failed")
            }
        }
    }

    fun updateSkillContent(
        id: String,
        content: String,
    ) {
        val repository = skillRepository ?: return
        viewModelScope.launch(ioDispatcher) {
            try {
                repository.updateContent(id, content)
                refreshSkills("Local skill changes saved")
            } catch (error: IllegalArgumentException) {
                showSkillError(error, "Skill update failed")
            } catch (error: SQLiteException) {
                showSkillError(error, "Skill update failed")
            } catch (error: IOException) {
                showSkillError(error, "Skill update failed")
            }
        }
    }

    fun setSkillEnabled(
        id: String,
        enabled: Boolean,
    ) {
        val repository = skillRepository ?: return
        viewModelScope.launch(ioDispatcher) {
            try {
                if (enabled) {
                    val skill = repository.listSkills()
                        .firstOrNull { it.id == id }
                        ?: throw IllegalArgumentException(
                            "Skill was not found",
                        )
                    val catalog = toolCatalogRepository?.loadSummary()
                        ?: ToolCatalogSummary()
                    val readiness = catalog.skillReadiness(
                        skill.requiredToolNames,
                    )
                    require(readiness.isReady) {
                        "Enable required Tool groups first: " +
                            readiness.missingRequirements
                                .sorted()
                                .joinToString()
                    }
                    mutableToolsState.value = ToolsUiState(
                        catalog = catalog,
                        isLoading = false,
                    )
                }
                repository.setEnabled(id, enabled)
                refreshSkills(if (enabled) "Skill enabled" else "Skill disabled")
            } catch (error: IllegalArgumentException) {
                showSkillError(error, "Skill update failed")
            } catch (error: SQLiteException) {
                showSkillError(error, "Skill update failed")
            }
        }
    }

    fun deleteSkill(id: String) {
        val repository = skillRepository ?: return
        viewModelScope.launch(ioDispatcher) {
            try {
                repository.delete(id)
                refreshSkills("Skill removed")
            } catch (error: IllegalArgumentException) {
                showSkillError(error, "Skill removal failed")
            } catch (error: SQLiteException) {
                showSkillError(error, "Skill removal failed")
            }
        }
    }

    fun checkSkillUpdates() {
        val repository = skillRepository ?: return
        val client = skillMarketClient ?: return
        mutableSkillsState.update {
            it.copy(isLoading = true, feedback = "Checking for updates...")
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                repository.listSkills()
                    .filter { it.origin == SkillOrigin.MARKET }
                    .forEach { skill ->
                        val upstream = client.downloadInstalled(skill)
                        repository.markChecked(skill.id, upstream.digest)
                    }
                refreshSkills("Update check complete")
            } catch (error: SkillMarketException) {
                showSkillError(error, "Update check failed")
            } catch (error: SQLiteException) {
                showSkillError(error, "Update check failed")
            }
        }
    }

    fun applySkillUpdate(id: String) {
        val repository = skillRepository ?: return
        val client = skillMarketClient ?: return
        viewModelScope.launch(ioDispatcher) {
            try {
                val skill = repository.listSkills()
                    .firstOrNull { it.id == id }
                    ?: throw IllegalArgumentException("Market skill not found")
                repository.applyUpstream(client.downloadInstalled(skill))
                refreshSkills("Skill updated from skills.sh")
            } catch (error: SkillMarketException) {
                showSkillError(error, "Skill update failed")
            } catch (error: IllegalArgumentException) {
                showSkillError(error, "Skill update failed")
            } catch (error: SQLiteException) {
                showSkillError(error, "Skill update failed")
            }
        }
    }

    fun setBuiltInToolEnabled(
        name: String,
        enabled: Boolean,
    ) {
        updateTools {
            requireRepository().setBuiltInEnabled(name, enabled)
        }
    }

    fun beginNotionAuthorization() {
        val repository = toolCatalogRepository ?: return
        mutableToolsState.update {
            it.copy(isLoading = true, feedback = "Preparing Notion authorization...")
        }
        viewModelScope.launch(ioDispatcher) {
            runCatching {
                repository.beginNotionAuthorization()
            }.onSuccess { url ->
                mutableToolsState.update {
                    it.copy(
                        isLoading = false,
                        feedback = "Complete authorization in Notion",
                        authorizationUrl = url,
                    )
                }
            }.onFailure(::showToolError)
        }
    }

    fun consumeToolAuthorizationUrl() {
        mutableToolsState.update { it.copy(authorizationUrl = null) }
    }

    fun completeNotionAuthorization(callbackUri: String) {
        updateTools("Notion knowledge tools connected") {
            requireRepository().completeNotionAuthorization(callbackUri)
        }
    }

    fun disconnectNotion() {
        updateTools("Notion disconnected") {
            requireRepository().disconnectNotion()
        }
    }

    fun openTencentDocsTokenPage() {
        mutableToolsState.update {
            it.copy(
                feedback = "Copy your personal Tencent Docs MCP token",
                authorizationUrl = TENCENT_DOCS_TOKEN_URL,
            )
        }
    }

    fun configureTencentDocs(token: String) {
        updateTools("Tencent Docs knowledge tools connected") {
            requireRepository().configureTencentDocs(token)
        }
    }

    fun disconnectTencentDocs() {
        updateTools("Tencent Docs disconnected") {
            requireRepository().disconnectTencentDocs()
        }
    }

    fun openAmapConsole() {
        mutableToolsState.update {
            it.copy(
                feedback = "Create an Amap Web Service Key",
                authorizationUrl = AMAP_CONSOLE_URL,
            )
        }
    }

    fun configureAmap(
        webServiceKey: String,
        securityKey: String,
    ) {
        updateTools(
            "Amap connected. Travel Planning and Merchant Discovery are ready.",
        ) {
            requireRepository().configureAmap(
                webServiceKey = webServiceKey,
                securityKey = securityKey,
            )
        }
    }

    fun disconnectAmap() {
        updateTools("Amap disconnected") {
            requireRepository().disconnectAmap()
        }
    }

    fun setAmapEnabled(enabled: Boolean) {
        updateTools {
            requireRepository().setAmapEnabled(enabled)
        }
    }

    fun setAgentBrowserEnabled(enabled: Boolean) {
        updateTools {
            requireRepository().setAgentBrowserEnabled(enabled)
        }
    }

    fun installMijiaExtension() {
        mutableToolsState.update {
            it.copy(
                feedback = "Download the trusted Mi Home extension APK",
                authorizationUrl = MIJIA_RELEASE_URL,
            )
        }
    }

    fun configureMijiaExtension() {
        val mijia = mutableToolsState.value.catalog.mijia
        val packageName = mijia.configurationPackage
        val activity = mijia.configurationActivity
        if (packageName == null || activity == null) {
            mutableToolsState.update {
                it.copy(feedback = "The Mi Home extension is unavailable")
            }
            return
        }
        mutableToolsState.update {
            it.copy(
                extensionActivityTarget = ExtensionActivityTarget(
                    packageName = packageName,
                    className = activity,
                ),
            )
        }
    }

    fun consumeExtensionActivityTarget() {
        mutableToolsState.update {
            it.copy(extensionActivityTarget = null)
        }
    }

    fun refreshTools() {
        loadTools()
    }

    fun setMijiaEnabled(enabled: Boolean) {
        updateTools {
            requireRepository().setMijiaEnabled(enabled)
        }
    }

    fun setMijiaToolEnabled(
        name: String,
        enabled: Boolean,
    ) {
        updateTools {
            requireRepository().setMijiaToolEnabled(name, enabled)
        }
    }

    fun disconnectMijia() {
        updateTools("Mi Home disconnected") {
            requireRepository().disconnectMijia()
        }
    }

    fun dismissCameraSnapshot() {
        mutableCameraSnapshot.value = null
        mutableSurface.value = MochiSurface.Face
    }

    fun addManualMcpServer(input: ManualMcpServerInput) {
        updateTools("MCP server added; choose tools to enable") {
            requireRepository().addManualServer(input)
        }
    }

    fun removeManualMcpServer(id: String) {
        updateTools("MCP server removed") {
            requireRepository().removeManualServer(id)
        }
    }

    fun setMcpServerEnabled(
        id: String,
        enabled: Boolean,
    ) {
        updateTools {
            requireRepository().setServerEnabled(id, enabled)
        }
    }

    fun setMcpToolEnabled(
        serverId: String,
        remoteName: String,
        enabled: Boolean,
    ) {
        updateTools {
            requireRepository().setMcpToolEnabled(
                serverId = serverId,
                remoteName = remoteName,
                enabled = enabled,
            )
        }
    }

    private fun loadProviderSettings() {
        val repository = providerSettingsRepository
        if (repository == null) {
            mutableProviderSettingsState.update { it.copy(isLoading = false) }
            return
        }

        viewModelScope.launch(ioDispatcher) {
            val summary = repository.loadSummary()
            mutableProviderSettingsState.value = ProviderSettingsUiState(
                summary = summary,
                isLoading = false,
            )
            if (
                !summary.isReady &&
                mutableSurface.value == MochiSurface.Face
            ) {
                mutableSurface.value = MochiSurface.Settings
            }
        }
    }

    private fun loadSpeechSettings() {
        val repository = speechSettingsRepository
        if (repository == null) {
            mutableSpeechSettingsState.update { it.copy(isLoading = false) }
            return
        }
        viewModelScope.launch(ioDispatcher) {
            val summary = repository.loadSummary()
            mutableSpeechSettingsState.value = SpeechSettingsUiState(
                summary = summary,
                isLoading = false,
            )
        }
    }

    private fun loadAgentContext() {
        val settingsRepository = agentSettingsRepository
        if (settingsRepository == null) {
            mutableAgentSettingsState.value =
                AgentSettingsUiState(isLoading = false)
            val repository = personaRepository
            if (repository == null) {
                mutablePersonaState.value = PersonaUiState(isLoading = false)
            } else {
                viewModelScope.launch(ioDispatcher) {
                    loadPersona(repository)
                }
            }
            return
        }
        viewModelScope.launch(ioDispatcher) {
            val (settings, settingsFeedback) = try {
                settingsRepository.load() to null
            } catch (error: IOException) {
                AgentSettings() to
                    "Could not load Agent settings; using defaults"
            }
            mutableAgentSettingsState.value = AgentSettingsUiState(
                settings = settings,
                isLoading = false,
                feedback = settingsFeedback,
            )
            loadPersistedConversation(settings.recentConversationTurns)
            personaRepository?.let { repository ->
                loadPersona(repository)
            } ?: run {
                mutablePersonaState.value =
                    PersonaUiState(isLoading = false)
            }
        }
    }

    private suspend fun loadPersistedConversation(turns: Int) {
        val memoryRepository = agentMemoryRepository ?: return
        val context = try {
            memoryRepository.loadContext(
                query = "",
                recentTurns = turns,
            )
        } catch (error: SQLiteException) {
            mutableConversationState.update {
                it.copy(
                    errorMessage =
                        "Could not load persisted conversation history",
                )
            }
            return
        }
        val messages = context.recentConversation.map { message ->
            ConversationMessage(
                id = UUID.randomUUID().toString(),
                role = if (message.role == "assistant") {
                    ConversationRole.ASSISTANT
                } else {
                    ConversationRole.USER
                },
                text = message.content,
                sentAt = message.createdAt,
            )
        }
        mutableConversationState.update {
            if (it.isSending) it else it.copy(messages = messages)
        }
    }

    private suspend fun loadPersona(repository: PersonaRepository) {
        mutablePersonaState.value = try {
            PersonaUiState(
                context = repository.load(),
                isLoading = false,
            )
        } catch (error: IOException) {
            PersonaUiState(
                isLoading = false,
                feedback = "Could not load persona files",
            )
        }
    }

    private suspend fun persistSuccessfulTurn(
        userText: String,
        assistantText: String,
    ): String? {
        val repository = agentMemoryRepository ?: return null
        return try {
            repository.saveTurn(
                userText = userText,
                assistantText = assistantText,
            )
            null
        } catch (error: SQLiteException) {
            "Reply succeeded, but conversation memory was not saved"
        }
    }

    private fun loadSkills() {
        val repository = skillRepository
        if (repository == null) {
            mutableSkillsState.value = SkillsUiState(isLoading = false)
            return
        }

        viewModelScope.launch(ioDispatcher) {
            refreshSkills()
        }
    }

    private fun loadTools() {
        val repository = toolCatalogRepository
        if (repository == null) {
            mutableToolsState.value = ToolsUiState(isLoading = false)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            runCatching { repository.loadSummary() }
                .onSuccess { catalog ->
                    mutableToolsState.value = ToolsUiState(
                        catalog = catalog,
                        isLoading = false,
                    )
                    refreshSkillReadiness(catalog)
                }
                .onFailure(::showToolError)
        }
    }

    private fun observeExtensionAttachments() {
        val client = extensionClient ?: return
        viewModelScope.launch {
            client.attachmentEvents.collect { attachment ->
                runCatching {
                    withContext(ioDispatcher) {
                        val bitmap = BitmapFactory.decodeByteArray(
                            attachment.bytes,
                            0,
                            attachment.bytes.size,
                        ) ?: throw IllegalStateException(
                            "Camera event image could not be decoded.",
                        )
                        if (
                            bitmap.width !=
                            attachment.descriptor.widthPixels ||
                            bitmap.height !=
                            attachment.descriptor.heightPixels
                        ) {
                            bitmap.recycle()
                            throw IllegalStateException(
                                "Camera event image dimensions are invalid.",
                            )
                        }
                        val metadata = AgentToolJson.format
                            .parseToJsonElement(
                                attachment.descriptor.metadataJson,
                            ).jsonObject
                        CameraSnapshotUiState(
                            bitmap = bitmap,
                            readyForModel = attachment.readyForModel,
                            cameraName = metadata.stringValue("camera_name")
                                ?: "Mi Home camera",
                            home = metadata.stringValue("home"),
                            room = metadata.stringValue("room"),
                            eventType = metadata.stringValue("event_type"),
                            capturedAt = metadata.stringValue("captured_at"),
                        )
                    }
                }.onSuccess { snapshot ->
                    mutableCameraSnapshot.value = snapshot
                    mutableSurface.value = MochiSurface.Card
                }.onFailure { error ->
                    mutableConversationState.update {
                        it.copy(
                            errorMessage = error.message
                                ?: "Camera event image could not be opened.",
                        )
                    }
                }
            }
        }
    }

    private fun updateTools(
        successFeedback: String? = null,
        operation: suspend () -> ToolCatalogSummary,
    ) {
        mutableToolsState.update {
            it.copy(isLoading = true, feedback = null)
        }
        viewModelScope.launch(ioDispatcher) {
            runCatching { operation() }
                .onSuccess { catalog ->
                    mutableToolsState.value = ToolsUiState(
                        catalog = catalog,
                        isLoading = false,
                        feedback = successFeedback,
                    )
                    refreshSkillReadiness(catalog)
                }
                .onFailure(::showToolError)
        }
    }

    private fun requireRepository(): ToolCatalogRepository =
        toolCatalogRepository
            ?: throw IllegalStateException("Tool catalog is unavailable")

    private fun showToolError(error: Throwable) {
        mutableToolsState.update {
            it.copy(
                isLoading = false,
                feedback = error.message ?: "Tool configuration failed",
            )
        }
    }

    private suspend fun refreshSkills(feedback: String? = null) {
        val repository = skillRepository ?: return
        try {
            val skills = repository.listSkills()
            val catalog = mutableToolsState.value.catalog
            mutableSkillsState.update {
                it.copy(
                    skills = skills,
                    readinessById = skills.associate { skill ->
                        skill.id to catalog.skillReadiness(
                            skill.requiredToolNames,
                        )
                    },
                    isLoading = false,
                    feedback = feedback,
                )
            }
        } catch (error: SQLiteException) {
            showSkillError(error, "Could not load Skills")
        }
    }

    private fun refreshSkillReadiness(catalog: ToolCatalogSummary) {
        mutableSkillsState.update { state ->
            state.copy(
                readinessById = state.skills.associate { skill ->
                    skill.id to catalog.skillReadiness(
                        skill.requiredToolNames,
                    )
                },
            )
        }
    }

    private fun showSkillError(
        error: Throwable,
        fallback: String,
    ) {
        mutableSkillsState.update {
            it.copy(
                isLoading = false,
                isSearching = false,
                feedback = error.message ?: fallback,
            )
        }
    }

    private fun load(target: MochiSurface) {
        val version = ++loadVersion
        when (target) {
            MochiSurface.Face,
            MochiSurface.DateTime,
            MochiSurface.Card,
            MochiSurface.Settings,
            MochiSurface.Skills,
            MochiSurface.Tools,
            is MochiSurface.CalendarMonth,
            -> {
                mutablePlannerState.value = PlannerSurfaceState()
                return
            }
            MochiSurface.Conversation -> {
                mutablePlannerState.value = PlannerSurfaceState()
                viewModelScope.launch(ioDispatcher) {
                    loadPersistedConversation(
                        mutableAgentSettingsState.value.settings
                            .recentConversationTurns,
                    )
                }
                return
            }
            MochiSurface.Weather -> {
                mutablePlannerState.value = PlannerSurfaceState()
                loadWeather()
                return
            }
            else -> mutablePlannerState.value = PlannerSurfaceState(
                isLoading = true,
                date = target.selectedDate(),
            )
        }

        viewModelScope.launch(ioDispatcher) {
            runCatching {
                loadPlannerState(target)
            }.onSuccess { state ->
                if (version == loadVersion && target == mutableSurface.value) {
                    mutablePlannerState.value = state
                }
            }.onFailure { error ->
                if (version == loadVersion && target == mutableSurface.value) {
                    showError(error)
                }
            }
        }
    }

    private suspend fun loadPlannerState(target: MochiSurface): PlannerSurfaceState =
        when (target) {
            MochiSurface.Today -> loadDay(LocalDate.now(clock))
            is MochiSurface.CalendarDay -> loadDay(target.date)
            is MochiSurface.Todo -> {
                val todos = if (target.date == null) {
                    plannerStore.listTodosByStatus(
                        target.status ?: TodoStatus.ACTIVE,
                    )
                } else {
                    plannerStore.listTodosForDate(target.date)
                }
                PlannerSurfaceState(
                    date = target.date,
                    todos = todos,
                )
            }
            else -> PlannerSurfaceState()
        }

    private fun loadWeather() {
        val repository = weatherRepository
        if (repository == null) {
            mutableWeatherState.value = WeatherUiState(
                errorMessage = "Weather runtime is unavailable",
            )
            return
        }
        mutableWeatherState.update {
            it.copy(isLoading = true, errorMessage = null)
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                showWeather(repository.currentWeather())
            } catch (error: DeviceLocationException) {
                showWeatherError(error.message)
            } catch (error: WeatherException) {
                showWeatherError(error.message)
            }
        }
    }

    private fun showWeatherError(message: String?) {
        mutableWeatherState.value = WeatherUiState(
            errorMessage = message ?: "Could not load weather",
        )
    }

    private fun showWeather(weather: CurrentWeather) {
        mutableWeatherState.value = WeatherUiState(weather = weather)
    }

    private fun completeCardTodo(
        cardId: String,
        todoId: String,
    ) {
        viewModelScope.launch(ioDispatcher) {
            runCatching {
                plannerStore.completeTodo(todoId)
            }.onSuccess {
                updateCard(cardId) { card ->
                    val remainingItems = card.items.filterNot {
                        it.id == todoId
                    }
                    card.copy(
                        hero = if (
                            card.items.firstOrNull()?.id == todoId
                        ) {
                            remainingItems.firstOrNull()?.title
                        } else {
                            card.hero
                        },
                        items = remainingItems,
                        actions = card.actions.filterNot {
                            it.type == CardActionType.COMPLETE_TODO &&
                                it.targetId == todoId
                        },
                    )
                }
                load(mutableSurface.value)
            }.onFailure { error ->
                mutableConversationState.update {
                    it.copy(
                        errorMessage =
                            error.message ?: "Could not complete todo",
                    )
                }
            }
        }
    }

    private fun dismissCard(cardId: String) {
        if (mutableHomeCard.value?.id == cardId) {
            mutableHomeCard.value = null
            if (mutableSurface.value == MochiSurface.Card) {
                navigate(MochiNavigationIntent.ShowFace)
            }
        }
        updateCard(cardId) { null }
    }

    private fun updateCard(
        cardId: String,
        transform: (CardPresentation) -> CardPresentation?,
    ) {
        mutableHomeCard.update { card ->
            if (card?.id == cardId) transform(card) else card
        }
        mutableConversationState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.card?.id == cardId) {
                        message.copy(card = transform(message.card))
                    } else {
                        message
                    }
                },
            )
        }
    }

    private fun AgentReply.presentationReply(
        weather: CurrentWeather?,
    ): String =
        when (uiDirective?.surface) {
            "date_time" -> currentDateTimeSpokenSummary()
            "weather" -> weather?.spokenSummary() ?: reply
            else -> reply
        }

    private fun currentDateTimeSpokenSummary(): String {
        val now = ZonedDateTime.now(clock)
        return if (
            AppLanguage.resolveContentLocale().language ==
            Locale.CHINESE.language
        ) {
            "现在是${now.hour}点${now.minute}分，今天是" +
                "${now.year}年${now.monthValue}月${now.dayOfMonth}日。"
        } else {
            "It is ${
                now.format(
                    DateTimeFormatter.ofPattern(
                        "h:mm a 'on' EEEE, MMMM d",
                        Locale.ENGLISH,
                    ),
                )
            }."
        }
    }

    private fun CurrentWeather.spokenSummary(): String =
        if (
            AppLanguage.resolveContentLocale().language ==
            Locale.CHINESE.language
        ) {
            "现在天气${weatherConditionChinese()}，温度" +
                "${temperatureC.roundToInt()}度，体感温度" +
                "${apparentTemperatureC.roundToInt()}度，湿度" +
                "${humidityPercent}%。"
        } else {
            "It is $condition and ${temperatureC.roundToInt()} degrees. " +
                "It feels like ${apparentTemperatureC.roundToInt()} degrees, " +
                "with $humidityPercent percent humidity."
        }

    private fun CurrentWeather.weatherConditionChinese(): String =
        when (weatherCode) {
            0 -> "晴朗"
            1 -> "大致晴朗"
            2 -> "局部多云"
            3 -> "阴天"
            45, 48 -> "有雾"
            51, 53, 55, 56, 57 -> "有毛毛雨"
            61, 63, 65, 66, 67 -> "有雨"
            71, 73, 75, 77 -> "有雪"
            80, 81, 82 -> "有阵雨"
            85, 86 -> "有阵雪"
            95, 96, 99 -> "有雷暴"
            else -> "多变"
        }

    private suspend fun loadDay(date: LocalDate): PlannerSurfaceState {
        val range = dayRange(date, clock.zone)
        return coroutineScope {
            val events = async(ioDispatcher) {
                plannerStore.listCalendarEvents(range.start, range.end)
            }
            val todos = async(ioDispatcher) {
                if (date == LocalDate.now(clock)) {
                    val active = plannerStore.listActiveTodosThroughDate(date)
                    val completedToday = plannerStore.listTodosForDate(date)
                        .filter { it.status == TodoStatus.COMPLETED }
                    active + completedToday
                } else {
                    plannerStore.listTodosForDate(date)
                }
            }
            val schedules = async(ioDispatcher) {
                agentScheduleStore?.listForDate(date).orEmpty()
            }
            PlannerSurfaceState(
                date = date,
                events = events.await(),
                todos = todos.await(),
                schedules = schedules.await(),
            )
        }
    }

    private fun showError(error: Throwable) {
        mutablePlannerState.value = mutablePlannerState.value.copy(
            isLoading = false,
            errorMessage = error.message ?: "Planner operation failed",
        )
    }

    private fun MochiSurface.selectedDate(): LocalDate? =
        when (this) {
            MochiSurface.Today -> LocalDate.now(clock)
            is MochiSurface.CalendarDay -> date
            is MochiSurface.Todo -> date
            else -> null
        }

    override fun onCleared() {
        interactionVersion += 1
        agentJob?.cancel()
        mutableCameraSnapshot.value = null
        voiceRuntime?.stopListening()
        voiceRuntime?.stopSpeaking()
        wakeRuntime?.resume()
        super.onCleared()
    }

    companion object {
        private const val AGENT_LOG_TAG = "MochiAgent"
        private val AGENT_LOGGER = Logger.getLogger(AGENT_LOG_TAG)
        private const val TENCENT_DOCS_TOKEN_URL =
            "https://docs.qq.com/open/auth/mcp.html"
        private const val MIJIA_RELEASE_URL =
            "https://github.com/gongpx20069/hi-mochi/releases/latest"

        fun factory(
            application: MochiApplication,
            voiceRuntime: VoiceRuntime,
            wakeRuntime: WakeRuntime,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    MochiHomeViewModel(
                        plannerStore = application.plannerStore,
                        agentScheduleStore = application.agentScheduleStore,
                        agentScheduleController =
                            application.agentScheduleController,
                        providerSettingsRepository =
                            application.providerSettingsRepository,
                        speechSettingsRepository =
                            application.speechSettingsRepository,
                        providerShareManager =
                            application.providerShareManager,
                        agentSettingsRepository =
                            application.agentSettingsRepository,
                        personaRepository = application.personaRepository,
                        agentMemoryRepository =
                            application.agentMemoryRepository,
                        agentRunnerBuilder = { sink, observer, onWeatherLoaded ->
                            application.createAgentRunner(
                                sink = sink,
                                observer = observer,
                                onWeatherLoaded = onWeatherLoaded,
                                includeBrowser = true,
                            )
                        },
                        voiceRuntime = voiceRuntime,
                        wakeRuntime = wakeRuntime,
                        skillRepository = application.skillRepository,
                        skillMarketClient = application.skillMarketClient,
                        toolCatalogRepository =
                            application.toolCatalogRepository,
                        extensionClient = application.extensionClient,
                        agentBrowserRuntime = application.agentBrowserRuntime,
                        weatherRepository = application.weatherRepository,
                        locationPermissionGate =
                            application.locationPermissionGate,
                    )
                }
            }

    }
}

private fun AgentPipelineStage.toUiStage(): ChatPipelineStage =
    when (this) {
        AgentPipelineStage.SKILLING -> ChatPipelineStage.SKILLING
        AgentPipelineStage.THINKING -> ChatPipelineStage.THINKING
        AgentPipelineStage.SUBAGENT -> ChatPipelineStage.SUBAGENT
        AgentPipelineStage.TOOL -> ChatPipelineStage.TOOL
        AgentPipelineStage.SUMMARY -> ChatPipelineStage.SUMMARY
    }

private fun kotlinx.serialization.json.JsonObject.stringValue(
    name: String,
): String? =
    this[name]?.jsonPrimitive?.contentOrNull
