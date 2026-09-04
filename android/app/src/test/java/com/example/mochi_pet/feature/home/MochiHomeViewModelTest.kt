package com.example.mochi_pet.feature.home

import com.example.mochi_pet.core.agent.AgentReply
import com.example.mochi_pet.core.agent.AgentRunner
import com.example.mochi_pet.core.agent.llm.OpenAiProviderConfig
import com.example.mochi_pet.core.database.PlannerStore
import com.example.mochi_pet.core.model.CalendarEvent
import com.example.mochi_pet.core.model.CalendarEventDraft
import com.example.mochi_pet.core.model.MochiTodo
import com.example.mochi_pet.core.model.MochiTodoDraft
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.model.TodoPriority
import com.example.mochi_pet.core.model.TodoStatus
import com.example.mochi_pet.core.navigation.MochiNavigationIntent
import com.example.mochi_pet.core.navigation.NavigationDecision
import com.example.mochi_pet.core.navigation.UiDirective
import com.example.mochi_pet.core.presentation.CardAction
import com.example.mochi_pet.core.presentation.CardActionType
import com.example.mochi_pet.core.presentation.CardItem
import com.example.mochi_pet.core.presentation.CardPlacement
import com.example.mochi_pet.core.presentation.CardPresentation
import com.example.mochi_pet.core.presentation.CardType
import com.example.mochi_pet.core.settings.ProviderSettingsInput
import com.example.mochi_pet.core.settings.ProviderSettingsRepository
import com.example.mochi_pet.core.settings.ProviderSettingsSummary
import com.example.mochi_pet.core.weather.CurrentWeather
import com.example.mochi_pet.core.weather.WeatherRepository
import com.example.mochi_pet.core.voice.VoiceRuntime
import com.example.mochi_pet.core.voice.VoiceRuntimeState
import com.example.mochi_pet.core.wake.WakeCaptureStatus
import com.example.mochi_pet.core.wake.WakeRuntime
import com.example.mochi_pet.core.wake.WakeRuntimeState
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MochiHomeViewModelTest {
    @Test
    fun `provider image permission reaches every foreground agent run`() {
        val permissions = mutableListOf<Boolean>()
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(),
            providerSettingsRepository = ProviderSettingsRepositoryFake(
                imageInputEnabled = true,
            ),
            agentRunnerBuilder = { _, _, _ ->
                AgentRunner { request ->
                    permissions += request.context.modelImageInputAllowed
                    AgentReply("Done", "neutral")
                }
            },
            clock = fixedClock(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.sendConversation("Turn on camera motion detection")
        viewModel.sendConversation("Show the latest door camera event image")

        assertEquals(listOf(true, true), permissions)
    }

    @Test
    fun `show weather loads current conditions on Home`() {
        val weather = CurrentWeather(
            temperatureC = 29.0,
            apparentTemperatureC = 32.0,
            humidityPercent = 71,
            weatherCode = 1,
            observedAt = "2026-08-02T00:45",
            timezone = "Asia/Shanghai",
        )
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(),
            weatherRepository = WeatherRepository { weather },
            clock = fixedClock(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.navigate(MochiNavigationIntent.ShowWeather)

        assertEquals(MochiSurface.Weather, viewModel.surface.value)
        assertEquals(weather, viewModel.weatherState.value.weather)
        assertFalse(viewModel.weatherState.value.isLoading)
    }

    @Test
    fun `show today loads matching planner data`() {
        val date = LocalDate.of(2026, 7, 31)
        val event = calendarEvent()
        val todo = todo(date)
        val store = PlannerStoreFake(
            events = listOf(event),
            todos = listOf(todo),
        )
        val viewModel = MochiHomeViewModel(
            plannerStore = store,
            clock = Clock.fixed(
                Instant.parse("2026-07-31T10:00:00Z"),
                ZoneId.of("Asia/Shanghai"),
            ),
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.navigate(MochiNavigationIntent.ShowToday)

        assertFalse(viewModel.plannerState.value.isLoading)
        assertEquals(date, viewModel.plannerState.value.date)
        assertEquals(listOf(event), viewModel.plannerState.value.events)
        assertEquals(listOf(todo), viewModel.plannerState.value.todos)
        assertEquals(date, store.requestedTodoDate)
    }

    @Test
    fun `agent reply is appended and navigation is applied`() {
        val store = PlannerStoreFake()
        val repository = ProviderSettingsRepositoryFake()
        val viewModel = MochiHomeViewModel(
            plannerStore = store,
            providerSettingsRepository = repository,
            agentRunnerBuilder = { sink, _, _ ->
                AgentRunner {
                    sink.apply(
                        NavigationDecision(
                            directive = UiDirective(surface = "today"),
                            intent = MochiNavigationIntent.ShowToday,
                        ),
                    )
                    AgentReply("Here is today.", "happy")
                }
            },
            clock = fixedClock(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.sendConversation("What is planned today?")

        assertEquals(
            MochiSurface.Today,
            viewModel.surface.value,
        )
        assertEquals(
            "Here is today.",
            viewModel.conversationState.value.messages.last().text,
        )
        assertEquals("happy", viewModel.conversationState.value.emotion)
    }

    @Test
    fun `Home card reply opens generated Home presentation`() {
        val card = CardPresentation(
            type = CardType.INSIGHT,
            placement = CardPlacement.HOME,
            title = "A useful insight",
            body = "Keep the next step small.",
        )
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(),
            providerSettingsRepository = ProviderSettingsRepositoryFake(),
            agentRunnerBuilder = { _, _, _ ->
                AgentRunner {
                    AgentReply(
                        reply = "Keep the next step small.",
                        emotion = "neutral",
                        card = card,
                    )
                }
            },
            clock = fixedClock(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.sendConversation("Give me one insight")

        assertEquals(MochiSurface.Card, viewModel.surface.value)
        assertEquals(card, viewModel.homeCard.value)
        assertEquals(
            card,
            viewModel.conversationState.value.messages.last().card,
        )
    }

    @Test
    fun `inline card stays attached to assistant message`() {
        val card = CardPresentation(
            type = CardType.RESEARCH_SUMMARY,
            placement = CardPlacement.INLINE,
            title = "Research",
        )
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(),
            providerSettingsRepository = ProviderSettingsRepositoryFake(),
            agentRunnerBuilder = { _, _, _ ->
                AgentRunner {
                    AgentReply(
                        reply = "Here is the research.",
                        emotion = "neutral",
                        card = card,
                    )
                }
            },
            clock = fixedClock(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.navigate(MochiNavigationIntent.ShowConversation)

        viewModel.sendConversation("Research this")

        assertEquals(MochiSurface.Conversation, viewModel.surface.value)
        assertEquals(
            card,
            viewModel.conversationState.value.messages.last().card,
        )
    }

    @Test
    fun `inline card expands to Home without creating another card`() {
        val card = CardPresentation(
            type = CardType.INSIGHT,
            placement = CardPlacement.INLINE,
            title = "One card",
            body = "Shared content.",
        )
        val viewModel = cardViewModel(card)
        viewModel.navigate(MochiNavigationIntent.ShowConversation)
        viewModel.sendConversation("Show this")

        viewModel.performCardAction(
            card = card,
            action = CardAction(
                type = CardActionType.EXPAND,
                label = "Full screen",
            ),
        )

        assertEquals(MochiSurface.Card, viewModel.surface.value)
        assertEquals(card.id, viewModel.homeCard.value?.id)
        assertEquals(CardPlacement.HOME, viewModel.homeCard.value?.placement)
        assertEquals(
            card.id,
            viewModel.conversationState.value.messages.last().card?.id,
        )
    }

    @Test
    fun `dismissing Home card restores its text fallback`() {
        val card = CardPresentation(
            type = CardType.INSIGHT,
            placement = CardPlacement.HOME,
            title = "Temporary card",
            body = "Fallback answer.",
        )
        val viewModel = cardViewModel(card)
        viewModel.sendConversation("Show this")

        viewModel.performCardAction(
            card = card,
            action = CardAction(
                type = CardActionType.DISMISS,
                label = "Dismiss",
            ),
        )

        assertEquals(MochiSurface.Face, viewModel.surface.value)
        assertEquals(null, viewModel.homeCard.value)
        val message = viewModel.conversationState.value.messages.last()
        assertEquals("Fallback answer.", message.text)
        assertEquals(null, message.card)
    }

    @Test
    fun `completing todo updates every placement of the card`() {
        val todo = todo(LocalDate.of(2026, 7, 31))
        val action = CardAction(
            type = CardActionType.COMPLETE_TODO,
            label = "Complete",
            targetId = todo.id,
        )
        val card = CardPresentation(
            type = CardType.TODO_FOCUS,
            placement = CardPlacement.HOME,
            title = "Focus",
            hero = todo.content,
            items = listOf(CardItem(id = todo.id, title = todo.content)),
            actions = listOf(action),
        )
        val store = PlannerStoreFake(todos = listOf(todo))
        val viewModel = cardViewModel(card, store)
        viewModel.sendConversation("Show my focus")

        viewModel.performCardAction(card, action)

        assertEquals(listOf(todo.id), store.completedTodoIds)
        assertTrue(viewModel.homeCard.value?.items.orEmpty().isEmpty())
        assertTrue(viewModel.homeCard.value?.actions.orEmpty().isEmpty())
        val messageCard =
            viewModel.conversationState.value.messages.last().card
        assertTrue(messageCard?.items.orEmpty().isEmpty())
        assertTrue(messageCard?.actions.orEmpty().isEmpty())
    }

    @Test
    fun `new interaction cancels stale agent result`() {
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(),
            providerSettingsRepository = ProviderSettingsRepositoryFake(),
            agentRunnerBuilder = { _, _, _ ->
                AgentRunner { request ->
                    if (request.query == "first") {
                        awaitCancellation()
                    }
                    AgentReply("second reply", "neutral")
                }
            },
            clock = fixedClock(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.sendConversation("first")
        viewModel.sendConversation("second")

        val state = viewModel.conversationState.value
        assertFalse(state.isSending)
        assertEquals(
            listOf("first", "second", "second reply"),
            state.messages.map(ConversationMessage::text),
        )
    }

    @Test
    fun `final voice transcript runs agent and speaks reply`() {
        val voiceRuntime = VoiceRuntimeFake("Show tomorrow")
        val wakeRuntime = WakeRuntimeFake()
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(),
            providerSettingsRepository = ProviderSettingsRepositoryFake(),
            agentRunnerBuilder = { _, _, _ ->
                AgentRunner { AgentReply("Opening tomorrow.", "neutral") }
            },
            voiceRuntime = voiceRuntime,
            wakeRuntime = wakeRuntime,
            clock = fixedClock(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.startVoiceInput()

        assertEquals(
            listOf("Show tomorrow", "Opening tomorrow."),
            viewModel.conversationState.value.messages
                .map(ConversationMessage::text),
        )
        assertEquals("Opening tomorrow.", voiceRuntime.spokenText)
        assertEquals(2, voiceRuntime.listenCount)
        assertEquals(2, wakeRuntime.pauseCount)
        assertEquals(2, wakeRuntime.resumeCount)
        assertEquals(
            ChatPipelineStage.IDLE,
            viewModel.pipelineState.value.stage,
        )
    }

    @Test
    fun `wake input interrupts speaking and starts listening`() {
        val voiceRuntime = InterruptibleVoiceRuntimeFake()
        val wakeRuntime = WakeRuntimeFake()
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(),
            providerSettingsRepository = ProviderSettingsRepositoryFake(),
            agentRunnerBuilder = { _, _, _ ->
                AgentRunner { AgentReply("A long spoken reply", "neutral") }
            },
            voiceRuntime = voiceRuntime,
            wakeRuntime = wakeRuntime,
            clock = fixedClock(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.sendConversation("Start talking")

        assertEquals(
            ChatPipelineStage.SPEAKING,
            viewModel.pipelineState.value.stage,
        )
        assertEquals(0, wakeRuntime.pauseCount)
        voiceRuntime.stopSpeakingCount = 0

        viewModel.startVoiceInput()

        assertEquals(1, voiceRuntime.stopSpeakingCount)
        assertEquals(1, wakeRuntime.pauseCount)
        assertEquals(
            ChatPipelineStage.LISTENING,
            viewModel.pipelineState.value.stage,
        )
    }

    @Test
    fun `stopping voice input immediately clears listening pipeline`() {
        val voiceRuntime = HoldingVoiceRuntimeFake()
        val wakeRuntime = WakeRuntimeFake()
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(),
            voiceRuntime = voiceRuntime,
            wakeRuntime = wakeRuntime,
            clock = fixedClock(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.startVoiceInput()
        assertEquals(
            ChatPipelineStage.LISTENING,
            viewModel.pipelineState.value.stage,
        )

        viewModel.stopVoiceInput()

        assertEquals(
            ChatPipelineStage.IDLE,
            viewModel.pipelineState.value.stage,
        )
        assertFalse(voiceRuntime.state.value.isListening)
        assertEquals(1, voiceRuntime.stopCount)
        assertEquals(1, wakeRuntime.resumeCount)
    }

    @Test
    fun `weather tool result is spoken and resumes listening after agent failure`() {
        val voiceRuntime = VoiceRuntimeFake("How is the weather?")
        val wakeRuntime = WakeRuntimeFake()
        val weather = CurrentWeather(
            temperatureC = 29.0,
            apparentTemperatureC = 33.0,
            humidityPercent = 74,
            weatherCode = 2,
            observedAt = "2026-07-31T18:00",
            timezone = "Asia/Shanghai",
        )
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(),
            providerSettingsRepository = ProviderSettingsRepositoryFake(),
            agentRunnerBuilder = { _, _, onWeatherLoaded ->
                AgentRunner {
                    onWeatherLoaded(weather)
                    error("Agent failed after weather tool")
                }
            },
            voiceRuntime = voiceRuntime,
            wakeRuntime = wakeRuntime,
            clock = fixedClock(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.startVoiceInput()

        assertTrue(voiceRuntime.spokenText?.contains("29") == true)
        assertTrue(voiceRuntime.spokenText?.contains("74") == true)
        assertEquals(2, voiceRuntime.listenCount)
        assertEquals(null, viewModel.conversationState.value.errorMessage)
    }

    @Test
    fun `today includes active todos carried from prior dates`() {
        val today = LocalDate.of(2026, 7, 31)
        val carried = todo(today.minusDays(1))
        val completedToday = todo(today).copy(
            id = "completed",
            status = TodoStatus.COMPLETED,
            completedAt = Instant.parse("2026-07-31T09:00:00Z"),
        )
        val future = todo(today.plusDays(1)).copy(id = "future")
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(
                todos = listOf(carried, completedToday, future),
            ),
            clock = fixedClock(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.navigate(MochiNavigationIntent.ShowToday)

        assertEquals(
            listOf(carried.id, completedToday.id),
            viewModel.plannerState.value.todos.map(MochiTodo::id),
        )
    }
}

private class PlannerStoreFake(
    private val events: List<CalendarEvent> = emptyList(),
    todos: List<MochiTodo> = emptyList(),
) : PlannerStore {
    private val todos = todos.toMutableList()
    var requestedTodoDate: LocalDate? = null
    val completedTodoIds = mutableListOf<String>()

    override suspend fun listCalendarEvents(
        rangeStart: Instant,
        rangeEnd: Instant,
    ): List<CalendarEvent> = events

    override suspend fun listTodosForDate(date: LocalDate): List<MochiTodo> {
        requestedTodoDate = date
        return todos.filter { it.scheduledDate == date }
    }

    override suspend fun listActiveTodosThroughDate(
        date: LocalDate,
    ): List<MochiTodo> =
        todos.filter {
            it.status == TodoStatus.ACTIVE &&
                it.scheduledDate != null &&
                it.scheduledDate <= date
        }

    override suspend fun createCalendarEvent(
        draft: CalendarEventDraft,
    ): CalendarEvent = unsupported()

    override suspend fun updateCalendarEvent(event: CalendarEvent): CalendarEvent =
        unsupported()

    override suspend fun getCalendarEvent(id: String): CalendarEvent? =
        unsupported()

    override suspend fun deleteCalendarEvent(id: String) = unsupported<Unit>()

    override suspend fun createTodo(draft: MochiTodoDraft): MochiTodo =
        unsupported()

    override suspend fun updateTodo(todo: MochiTodo): MochiTodo = unsupported()

    override suspend fun getTodo(id: String): MochiTodo? = unsupported()

    override suspend fun completeTodo(id: String): MochiTodo {
        val index = todos.indexOfFirst { it.id == id }
        if (index < 0) {
            return unsupported()
        }
        completedTodoIds += id
        val completed = todos[index].copy(status = TodoStatus.COMPLETED)
        todos[index] = completed
        return completed
    }

    override suspend fun listUndatedTodos(status: TodoStatus): List<MochiTodo> =
        unsupported()

    override suspend fun listTodosByStatus(status: TodoStatus): List<MochiTodo> =
        unsupported()

    override suspend fun deleteTodo(id: String) = unsupported<Unit>()

    private fun <T> unsupported(): T =
        throw UnsupportedOperationException("Not required by this test")
}

private class ProviderSettingsRepositoryFake(
    imageInputEnabled: Boolean = false,
) : ProviderSettingsRepository {
    private val summary = ProviderSettingsSummary(
        endpoint = "https://example.test/v1",
        model = "test-model",
        imageInputEnabled = imageInputEnabled,
        hasApiKey = true,
    )

    override suspend fun loadSummary(): ProviderSettingsSummary = summary

    override suspend fun save(
        input: ProviderSettingsInput,
    ): ProviderSettingsSummary = summary

    override suspend fun clearApiKey(): ProviderSettingsSummary = summary

    override suspend fun loadRuntimeConfig(): OpenAiProviderConfig =
        OpenAiProviderConfig(
            endpoint = summary.endpoint,
            apiKey = "test-key",
            model = summary.model,
            imageInputEnabled = summary.imageInputEnabled,
        )
}

private fun fixedClock(): Clock =
    Clock.fixed(
        Instant.parse("2026-07-31T10:00:00Z"),
        ZoneId.of("Asia/Shanghai"),
    )

private fun cardViewModel(
    card: CardPresentation,
    plannerStore: PlannerStore = PlannerStoreFake(),
): MochiHomeViewModel =
    MochiHomeViewModel(
        plannerStore = plannerStore,
        providerSettingsRepository = ProviderSettingsRepositoryFake(),
        agentRunnerBuilder = { _, _, _ ->
            AgentRunner {
                AgentReply(
                    reply = card.body ?: card.title,
                    emotion = "neutral",
                    card = card,
                )
            }
        },
        clock = fixedClock(),
        ioDispatcher = Dispatchers.Unconfined,
    )

private class VoiceRuntimeFake(
    transcript: String,
) : VoiceRuntime {
    private val transcripts = ArrayDeque(listOf(transcript))
    override val state = MutableStateFlow(
        VoiceRuntimeState(recognitionAvailable = true, ttsReady = true),
    )
    var spokenText: String? = null
    var listenCount = 0

    override fun startListening(
        onFinalTranscript: (String) -> Unit,
        onNoResult: () -> Unit,
    ) {
        listenCount += 1
        val transcript = transcripts.removeFirstOrNull()
        if (transcript == null) {
            onNoResult()
        } else {
            onFinalTranscript(transcript)
        }
    }

    override fun stopListening() = Unit

    override fun speak(
        text: String,
        onCompleted: () -> Unit,
    ) {
        spokenText = text
        onCompleted()
    }

    override fun stopSpeaking() = Unit
}

private class HoldingVoiceRuntimeFake : VoiceRuntime {
    private val mutableState = MutableStateFlow(
        VoiceRuntimeState(recognitionAvailable = true, ttsReady = true),
    )
    override val state = mutableState
    var stopCount = 0

    override fun startListening(
        onFinalTranscript: (String) -> Unit,
        onNoResult: () -> Unit,
    ) {
        mutableState.value = mutableState.value.copy(isListening = true)
    }

    override fun stopListening() {
        stopCount += 1
        mutableState.value = mutableState.value.copy(
            isListening = false,
            partialTranscript = "",
        )
    }

    override fun speak(
        text: String,
        onCompleted: () -> Unit,
    ) = onCompleted()

    override fun stopSpeaking() = Unit
}

private class InterruptibleVoiceRuntimeFake : VoiceRuntime {
    override val state = MutableStateFlow(
        VoiceRuntimeState(recognitionAvailable = true, ttsReady = true),
    )
    var stopSpeakingCount = 0

    override fun startListening(
        onFinalTranscript: (String) -> Unit,
        onNoResult: () -> Unit,
    ) {
        state.value = state.value.copy(isListening = true)
    }

    override fun stopListening() {
        state.value = state.value.copy(isListening = false)
    }

    override fun speak(
        text: String,
        onCompleted: () -> Unit,
    ) = Unit

    override fun stopSpeaking() {
        stopSpeakingCount += 1
    }
}

private class WakeRuntimeFake : WakeRuntime {
    override val state = MutableStateFlow(
        WakeRuntimeState(WakeCaptureStatus.LISTENING),
    )
    var pauseCount = 0
    var resumeCount = 0

    override fun enable() = Unit

    override fun disable() = Unit

    override fun pause(onPaused: () -> Unit) {
        pauseCount += 1
        onPaused()
    }

    override fun resume() {
        resumeCount += 1
    }
}

private fun calendarEvent(): CalendarEvent {
    val now = Instant.parse("2026-07-31T10:00:00Z")
    return CalendarEvent(
        id = "event_1",
        title = "Review",
        description = null,
        startAt = Instant.parse("2026-07-31T11:00:00Z"),
        endAt = Instant.parse("2026-07-31T12:00:00Z"),
        allDay = false,
        timezone = ZoneId.of("Asia/Shanghai"),
        recurrenceRule = null,
        location = null,
        reminderAt = null,
        createdAt = now,
        updatedAt = now,
    )
}

private fun todo(date: LocalDate): MochiTodo {
    val now = Instant.parse("2026-07-31T10:00:00Z")
    return MochiTodo(
        id = "todo_1",
        content = "Finish planner UI",
        status = TodoStatus.ACTIVE,
        priority = TodoPriority.NORMAL,
        scheduledDate = date,
        dueAt = null,
        reminderAt = null,
        completedAt = null,
        createdAt = now,
        updatedAt = now,
    )
}
