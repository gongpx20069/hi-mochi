package com.example.mochi_pet.feature.home

import androidx.lifecycle.SavedStateHandle
import com.example.mochi_pet.core.agent.AgentReply
import com.example.mochi_pet.core.agent.AgentRunner
import com.example.mochi_pet.core.agent.llm.OpenAiProviderConfig
import com.example.mochi_pet.core.agent.llm.ProviderNetworkException
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
import com.example.mochi_pet.core.navigation.UiDirectiveSink
import com.example.mochi_pet.core.navigation.NavigateMochiUiTool
import com.example.mochi_pet.core.navigation.NavigationPolicy
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
import com.example.mochi_pet.core.update.ApkDownload
import com.example.mochi_pet.core.update.ExtensionApk
import com.example.mochi_pet.core.agentlink.AgentLinkUiAction
import com.example.mochi_pet.core.voice.VoiceRuntime
import com.example.mochi_pet.core.voice.SpeechPlaybackResult
import com.example.mochi_pet.core.voice.SpeechPurpose
import com.example.mochi_pet.core.voice.SpeechVoice
import com.example.mochi_pet.core.voice.WakeBriefingResult
import com.example.mochi_pet.core.agentlink.AgentLinkResultState
import com.example.mochi_pet.core.agentlink.ResultsFake
import com.example.mochi_pet.core.agentlink.followed
import com.example.mochi_pet.core.agentlink.receipt
import com.example.mochi_pet.core.voice.IFLYTEK_BASIC_VOICES
import com.example.mochi_pet.core.settings.SpeechProvider
import com.example.mochi_pet.core.settings.SpeechSettingsRepository
import com.example.mochi_pet.core.settings.SpeechSettingsSummary
import com.example.mochi_pet.core.settings.SpeechSettingsInput
import com.example.mochi_pet.core.settings.SpeechRuntimeConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.example.mochi_pet.core.voice.VoiceRuntimeState
import com.example.mochi_pet.core.wake.WakeCaptureStatus
import com.example.mochi_pet.core.wake.WakeKeyword
import com.example.mochi_pet.core.wake.WakeRuntime
import com.example.mochi_pet.core.wake.WakeRuntimeState
import java.time.Clock
import java.io.InterruptedIOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MochiHomeViewModelTest {
    @Test fun `extension install actions open the resolved APK and lookup failures show feedback`() {
        val requested = mutableListOf<ExtensionApk>()
        var fail = false
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(), ioDispatcher = Dispatchers.Unconfined, mainDispatcher = Dispatchers.Unconfined,
            extensionDownload = {
                requested += it
                if (fail) throw java.io.IOException("Synthetic lookup failure")
                ApkDownload("https://example.com/${it.name}.apk", it == ExtensionApk.AGENTLINK)
            },
        )
        viewModel.installMijiaExtension()
        assertEquals("https://example.com/MIJIA.apk", viewModel.toolsState.value.authorizationUrl)
        viewModel.onTermuxAction(TermuxUiAction.Install)
        assertEquals("https://example.com/TERMUX.apk", viewModel.toolsState.value.authorizationUrl)
        viewModel.onWebdavAction(WebdavUiAction.Install)
        assertEquals("https://example.com/WEBDAV.apk", viewModel.toolsState.value.authorizationUrl)
        viewModel.onAgentLinkAction(AgentLinkUiAction.Install)
        assertEquals("https://example.com/AGENTLINK.apk", viewModel.toolsState.value.authorizationUrl)
        assertTrue(viewModel.toolsState.value.feedback.orEmpty().contains("pre-release"))
        assertEquals(ExtensionApk.entries.toList(), requested)
        fail = true
        viewModel.installMijiaExtension()
        assertEquals(null, viewModel.toolsState.value.authorizationUrl)
        assertFalse(viewModel.toolsState.value.isDownloadingApk)
        assertEquals("APK download is unavailable. Check your connection and try again.", viewModel.toolsState.value.feedback)
    }

    @Test fun `superseded or navigated away APK lookups cannot launch a browser or clear newer progress`() {
        val first = CompletableDeferred<ApkDownload>()
        val second = CompletableDeferred<ApkDownload>()
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(), ioDispatcher = Dispatchers.Unconfined, mainDispatcher = Dispatchers.Unconfined,
            extensionDownload = { kind -> withContext(NonCancellable) {
                if (kind == ExtensionApk.MIJIA) first.await() else second.await()
            } },
        )
        viewModel.navigate(MochiNavigationIntent.ShowTools)
        viewModel.installMijiaExtension()
        viewModel.onTermuxAction(TermuxUiAction.Install)
        first.complete(ApkDownload("https://example.com/old.apk", false))
        assertEquals(null, viewModel.toolsState.value.authorizationUrl)
        assertTrue(viewModel.toolsState.value.isDownloadingApk)
        viewModel.navigate(MochiNavigationIntent.ShowFace)
        second.complete(ApkDownload("https://example.com/late.apk", false))
        assertEquals(null, viewModel.toolsState.value.authorizationUrl)
        assertFalse(viewModel.toolsState.value.isDownloadingApk)
    }

    @Test fun `focus navigation is idempotent and preserves the current Home view across an Agent turn`() {
        val saved = SavedStateHandle(mapOf("focus_mode" to true))
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(), providerSettingsRepository = ProviderSettingsRepositoryFake(),
            savedStateHandle = saved, ioDispatcher = Dispatchers.Unconfined, mainDispatcher = Dispatchers.Unconfined,
            agentRunnerBuilder = { sink, _, _ -> AgentRunner {
                sink.apply(NavigationDecision(UiDirective("date_time", focusMode = true), MochiNavigationIntent.SetFocusMode(true)))
                AgentReply("Done.", "neutral")
            } },
        )
        assertTrue(viewModel.focusMode.value)
        viewModel.navigate(MochiNavigationIntent.ShowDateTime)
        viewModel.sendConversation("Enter focus mode")
        viewModel.navigate(MochiNavigationIntent.SetFocusMode(true))
        assertEquals(MochiSurface.DateTime, viewModel.surface.value)
        assertTrue(viewModel.focusMode.value)
        assertEquals(true, saved.get<Boolean>("focus_mode"))
        viewModel.navigate(MochiNavigationIntent.SetFocusMode(false))
        viewModel.navigate(MochiNavigationIntent.SetFocusMode(false))
        assertEquals(MochiSurface.DateTime, viewModel.surface.value)
        assertFalse(viewModel.focusMode.value)
        viewModel.setFocusMode(true)
        viewModel.navigate(MochiNavigationIntent.ShowSettings)
        assertFalse(viewModel.focusMode.value)
        viewModel.navigate(MochiNavigationIntent.SetFocusMode(false))
        assertEquals(MochiSurface.Settings, viewModel.surface.value)
        viewModel.navigate(MochiNavigationIntent.SetFocusMode(true))
        assertEquals(MochiSurface.Face, viewModel.surface.value)
        assertTrue(viewModel.focusMode.value)
    }

    @Test fun `choosing the current system phrase pins it and permits returning to system`() {
        val wake = WakeRuntimeFake()
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(), wakeRuntime = wake, ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.selectWakeKeyword(WakeKeyword.ENGLISH)
        assertEquals(WakeKeyword.ENGLISH, wake.state.value.keywordOverride)
        viewModel.selectWakeKeyword(null)
        assertEquals(null, wake.state.value.keywordOverride)
    }

    @Test fun `speech switching cancels voice interaction but leaves text Agent running`() = kotlinx.coroutines.runBlocking {
        for (voiceInput in listOf(false, true)) {
            val speech = com.example.mochi_pet.core.settings.DataStoreSpeechSettingsRepository(
                com.example.mochi_pet.core.settings.SpeechPreferencesDataStore(),
                com.example.mochi_pet.core.settings.SpeechFakeCipher(),
            )
            val profile = speech.saveProfile(com.example.mochi_pet.core.settings.SpeechProfileInput(
                name = "Other", settings = SpeechSettingsInput(SpeechProvider.AZURE,
                    azureEndpoint = "https://fixture.cognitiveservices.azure.com", azureApiKeyReplacement = "fixture-key"),
            )).profiles.last()
            val started = CompletableDeferred<Unit>()
            var stopped = false
            val viewModel = MochiHomeViewModel(
                plannerStore = PlannerStoreFake(), providerSettingsRepository = ProviderSettingsRepositoryFake(),
                speechSettingsRepository = speech, voiceRuntime = VoiceRuntimeFake("Synthetic voice request"),
                ioDispatcher = Dispatchers.Unconfined,
                agentRunnerBuilder = { _, _, _ -> AgentRunner {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { stopped = true }
                } },
            )
            if (voiceInput) viewModel.startVoiceInput() else viewModel.sendConversation("Synthetic text request")
            started.await()
            viewModel.handleSpeechProfileAction(SpeechProfileAction.Activate(profile.id))
            assertEquals(voiceInput, stopped)
            assertEquals(!voiceInput, viewModel.conversationState.value.isSending)
            assertEquals(profile.id, viewModel.speechSettingsState.value.profiles.activeId)
            viewModel.cancelConversation()
        }
    }

    @Test
    fun `connection switch cancels foreground work and stops playback without error reply`() = kotlinx.coroutines.runBlocking {
        val runs = com.example.mochi_pet.core.settings.ProviderRunCoordinator()
        val settings = ProviderSettingsRepositoryFake()
        val repository = object : ProviderSettingsRepository by settings {
            override suspend fun activateProfile(id: String) =
                runs.change { cancel -> cancel(); settings.loadProfiles() }
        }
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val voice = VoiceRuntimeFake("")
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(), providerSettingsRepository = repository,
            providerRuns = runs, voiceRuntime = voice, ioDispatcher = Dispatchers.Unconfined,
            agentRunnerBuilder = { _, _, _ -> AgentRunner {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            } },
        )
        viewModel.sendConversation("Synthetic pending request")
        started.await()
        voice.stopSpeakingCount = 0
        viewModel.handleProviderProfileAction(ProviderProfileAction.Activate("test"))
        kotlinx.coroutines.withTimeout(5_000) { stopped.await() }
        assertFalse(viewModel.conversationState.value.isSending)
        assertEquals(null, viewModel.conversationState.value.errorMessage)
        assertEquals("AI connection selected", viewModel.providerSettingsState.value.feedback)
        assertTrue(voice.stopSpeakingCount > 0)
    }

    @Test
    fun `profile load failure is shown without resetting storage`() {
        val repository = object : ProviderSettingsRepository by ProviderSettingsRepositoryFake() {
            override suspend fun loadProfiles(): com.example.mochi_pet.core.settings.ProviderProfilesSummary =
                throw IllegalStateException("synthetic storage failure")
        }
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(), providerSettingsRepository = repository,
            ioDispatcher = Dispatchers.Unconfined,
        )
        assertFalse(viewModel.providerSettingsState.value.isLoading)
        assertEquals(
            "AI connections could not be loaded. Stored settings have not been replaced.",
            viewModel.providerSettingsState.value.feedback,
        )
        assertEquals(MochiSurface.Settings, viewModel.surface.value)
    }

    @Test
    fun `text and voice cap model requests without changing stored provider timeout`() {
        for (voiceInput in listOf(false, true)) {
            val settings = ProviderSettingsRepositoryFake()
            val timeouts = mutableListOf<Long>()
            val viewModel = MochiHomeViewModel(
                plannerStore = PlannerStoreFake(),
                providerSettingsRepository = settings,
                agentRunnerBuilder = { _, _, _ ->
                    AgentRunner { request ->
                        timeouts += request.provider.timeoutSeconds
                        AgentReply("Done.", "neutral")
                    }
                },
                voiceRuntime = VoiceRuntimeFake("Hello"),
                ioDispatcher = Dispatchers.Unconfined,
            )

            if (voiceInput) viewModel.startVoiceInput() else viewModel.sendConversation("Hello")

            assertEquals(listOf(20L), timeouts)
            kotlinx.coroutines.runBlocking {
                assertEquals(60L, settings.loadRuntimeConfig().timeoutSeconds)
            }
        }
    }

    @Test
    fun `wake briefing completion and deliberate dismissal acknowledge different states`() {
        for (result in WakeBriefingResult.entries) {
            val repository = ResultsFake(AgentLinkResultState(listOf(followed().copy(receipt = receipt())), true,
                readableMachineIds = setOf("machine")))
            val voice = BriefingVoiceFake()
            val viewModel = MochiHomeViewModel(
                plannerStore = PlannerStoreFake(), voiceRuntime = voice,
                agentLinkResults = repository, ioDispatcher = Dispatchers.Unconfined,
            )
            viewModel.startVoiceInput(acknowledgeWake = true)
            assertEquals(1, voice.listenCount)
            assertEquals(0, voice.acknowledgements)
            voice.briefingResult!!(result)
            val task = repository.state.value.tasks.single()
            assertEquals(result != WakeBriefingResult.DISMISSED, task.unread)
            assertEquals(result !in setOf(WakeBriefingResult.COMPLETED, WakeBriefingResult.DISMISSED), task.pendingAnnouncement)
        }
    }

    @Test
    fun `second wake dismisses current batch keeps history and starts listening without another brief`() {
        val repository = ResultsFake(AgentLinkResultState(listOf(followed().copy(receipt = receipt())), true,
            readableMachineIds = setOf("machine")))
        val voice = BriefingVoiceFake()
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(), voiceRuntime = voice,
            agentLinkResults = repository, ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.startVoiceInput(acknowledgeWake = true)
        val oldCallback = voice.briefingResult!!
        viewModel.startVoiceInput(acknowledgeWake = true)
        assertEquals(2, voice.listenCount)
        assertEquals(0, voice.acknowledgements)
        assertEquals(1, repository.state.value.tasks.size)
        assertFalse(repository.state.value.tasks.single().unread)
        assertFalse(repository.state.value.tasks.single().pendingAnnouncement)
        oldCallback(WakeBriefingResult.COMPLETED)
        assertFalse(repository.state.value.tasks.single().unread)
    }

    @Test
    fun `voice preview uses draft ID without saving history or opening recognition`() {
        val voice = VoiceRuntimeFake("", autoCompleteSpeech = false)
        val speech = PreviewSpeechSettingsFake()
        val wake = WakeRuntimeFake()
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(),
            speechSettingsRepository = speech,
            voiceRuntime = voice,
            wakeRuntime = wake,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.previewSpeechVoice(SpeechProvider.IFLYTEK, "x4_yezi")
        assertEquals(listOf("x4_yezi"), voice.previewVoiceIds)
        assertEquals(listOf(SpeechPurpose.PREVIEW), voice.speechPurposes)
        assertEquals(1, wake.pauseCount)
        assertEquals(0, wake.resumeCount)
        voice.speechCallbacks.single()(SpeechPlaybackResult.COMPLETED)
        assertEquals(1, wake.resumeCount)
        assertEquals(setOf("x4_yezi"), viewModel.speechVoiceState.value.previewedIds)
        assertEquals(0, speech.saveCount)
        assertEquals(0, voice.listenCount)
        assertTrue(viewModel.conversationState.value.messages.isEmpty())
    }

    @Test
    fun `old preview completion cannot finish a newer preview and navigation stops it`() {
        val voice = VoiceRuntimeFake("", autoCompleteSpeech = false)
        val wake = WakeRuntimeFake()
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(),
            speechSettingsRepository = PreviewSpeechSettingsFake(),
            voiceRuntime = voice,
            wakeRuntime = wake,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.previewSpeechVoice(SpeechProvider.IFLYTEK, "x4_yezi")
        viewModel.previewSpeechVoice(SpeechProvider.IFLYTEK, "aisjiuxu")
        assertEquals(0, wake.resumeCount)
        val resumed = wake.resumeCount
        voice.speechCallbacks.first()(SpeechPlaybackResult.COMPLETED)
        assertEquals("aisjiuxu", viewModel.speechVoiceState.value.previewVoiceId)
        assertEquals(resumed, wake.resumeCount)
        viewModel.navigate(MochiNavigationIntent.ShowConversation)
        assertEquals(null, viewModel.speechVoiceState.value.previewVoiceId)
        assertTrue(voice.stopSpeakingCount >= 2)
        voice.speechCallbacks.last()(SpeechPlaybackResult.COMPLETED)
        assertTrue(viewModel.speechVoiceState.value.previewedIds.isEmpty())
    }

    @Test
    fun `preview failure exposes safe code and preserves disabled wake state`() {
        val voice = VoiceRuntimeFake("", autoCompleteSpeech = false)
        val wake = WakeRuntimeFake().apply { state.value = WakeRuntimeState() }
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(),
            speechSettingsRepository = PreviewSpeechSettingsFake(),
            voiceRuntime = voice,
            wakeRuntime = wake,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.previewSpeechVoice(SpeechProvider.IFLYTEK, "x4_yezi")
        voice.state.value = voice.state.value.copy(errorMessage = "safe failure", diagnosticCode = "iflytek 11200")
        voice.speechCallbacks.single()(SpeechPlaybackResult.FAILED)
        assertEquals("iflytek 11200", viewModel.speechVoiceState.value.diagnosticCode)
        assertEquals("safe failure", viewModel.speechVoiceState.value.previewError)
        assertEquals(0, wake.pauseCount)
        assertEquals(0, wake.resumeCount)
        assertEquals(0, voice.listenCount)
    }

    @Test
    fun `late catalog response cannot replace the new provider catalog`() {
        val oldCatalog = CompletableDeferred<List<SpeechVoice>>()
        val azureVoice = SpeechVoice("en-US-JennyNeural", "Jenny", "en-US")
        val voice = VoiceRuntimeFake("").apply {
            voiceCatalogLoader = {
                if (it == SpeechProvider.IFLYTEK) withContext(NonCancellable) {
                    oldCatalog.await()
                } else {
                    listOf(azureVoice)
                }
            }
        }
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(),
            voiceRuntime = voice,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.loadSpeechVoices(SpeechProvider.IFLYTEK)
        viewModel.loadSpeechVoices(SpeechProvider.AZURE)
        oldCatalog.complete(IFLYTEK_BASIC_VOICES)
        assertEquals(SpeechProvider.AZURE, viewModel.speechVoiceState.value.provider)
        assertEquals(listOf(azureVoice), viewModel.speechVoiceState.value.voices)
    }

    @Test
    fun `provider timeout appears as an error without adding assistant history`() {
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(),
            providerSettingsRepository = ProviderSettingsRepositoryFake(),
            agentRunnerBuilder = { _, _, _ ->
                AgentRunner {
                    throw ProviderNetworkException(
                        "Provider network request failed",
                        InterruptedIOException("timeout"),
                    )
                }
            },
            clock = fixedClock(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.sendConversation("Turn off the television")

        assertEquals(
            "The AI service timed out. Check your network and provider status.",
            viewModel.conversationState.value.errorMessage,
        )
        assertEquals(
            listOf("Turn off the television"),
            viewModel.conversationState.value.messages
                .map(ConversationMessage::text),
        )
        assertFalse(viewModel.conversationState.value.isSending)
        assertEquals(ChatPipelineStage.IDLE, viewModel.pipelineState.value.stage)
    }

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
    fun `date changes retain the dated snapshot and ignore superseded loads`() {
        val date = LocalDate.now(fixedClock())
        val next = date.plusDays(1)
        val latest = date.plusDays(2)
        val firstTodo = todo(date).copy(id = "first")
        val nextTodo = todo(next).copy(id = "next")
        val latestTodo = todo(latest).copy(id = "latest")
        val pending = mapOf(next to CompletableDeferred<Unit>(), latest to CompletableDeferred<Unit>())
        val store = PlannerStoreFake(todos = listOf(firstTodo, nextTodo, latestTodo),
            beforeAgendaLoad = { pending[it]?.await() })
        val viewModel = MochiHomeViewModel(
            plannerStore = store, clock = fixedClock(),
            ioDispatcher = Dispatchers.Unconfined, mainDispatcher = Dispatchers.Unconfined,
        )
        viewModel.navigate(MochiNavigationIntent.ShowToday)
        val original = viewModel.plannerState.value
        viewModel.navigate(MochiNavigationIntent.ShowCalendarDay(next))
        assertEquals(original.copy(isLoading = true), viewModel.plannerState.value)
        viewModel.navigate(MochiNavigationIntent.ShowCalendarDay(latest))
        assertEquals(original.copy(isLoading = true), viewModel.plannerState.value)
        pending.getValue(latest).complete(Unit)
        assertEquals(latest, viewModel.plannerState.value.date)
        assertEquals(listOf(latestTodo), viewModel.plannerState.value.todos)
        assertFalse(viewModel.plannerState.value.isLoading)
        val completed = viewModel.plannerState.value
        pending.getValue(next).complete(Unit)
        assertEquals(completed, viewModel.plannerState.value)
    }

    @Test
    fun `failed date load retains the previous date and reports the failure`() {
        val date = LocalDate.now(fixedClock())
        val pending = CompletableDeferred<Unit>()
        val store = PlannerStoreFake(todos = listOf(todo(date)),
            beforeAgendaLoad = { if (it != date) pending.await() })
        val viewModel = MochiHomeViewModel(
            plannerStore = store, clock = fixedClock(),
            ioDispatcher = Dispatchers.Unconfined, mainDispatcher = Dispatchers.Unconfined,
        )
        viewModel.navigate(MochiNavigationIntent.ShowToday)
        val original = viewModel.plannerState.value
        viewModel.navigate(MochiNavigationIntent.ShowCalendarDay(date.plusDays(1)))
        pending.completeExceptionally(IllegalStateException("Synthetic planner read failure"))
        assertEquals(original.copy(errorMessage = "Synthetic planner read failure"), viewModel.plannerState.value)
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
            mainDispatcher = Dispatchers.Unconfined,
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
        assertEquals(
            listOf("Opening tomorrow."),
            voiceRuntime.spokenTexts,
        )
        assertEquals(2, voiceRuntime.listenCount)
        assertEquals(2, wakeRuntime.pauseCount)
        assertEquals(2, wakeRuntime.resumeCount)
        assertEquals(
            ChatPipelineStage.IDLE,
            viewModel.pipelineState.value.stage,
        )
    }

    @Test
    fun `voice exit finishes reply without follow up and stale requests cannot affect a new session`() {
        for (voiceInput in listOf(true, false)) {
            val voice = VoiceRuntimeFake("That's all", autoCompleteSpeech = false)
            val wake = WakeRuntimeFake()
            var oldSink: UiDirectiveSink? = null
            val viewModel = MochiHomeViewModel(
                plannerStore = PlannerStoreFake(),
                providerSettingsRepository = ProviderSettingsRepositoryFake(),
                voiceRuntime = voice,
                wakeRuntime = wake,
                clock = fixedClock(),
                ioDispatcher = Dispatchers.Unconfined,
                mainDispatcher = Dispatchers.Unconfined,
                agentRunnerBuilder = { sink, _, _ -> AgentRunner { request ->
                    if (request.query == "That's all") {
                        oldSink = sink
                        val tool = NavigateMochiUiTool(NavigationPolicy(), sink)
                        repeat(2) {
                            tool.execute(buildJsonObject {
                                put("operate", "end_voice_session")
                                put("reason", "explicit_ui_request")
                            }, request.context)
                        }
                        sink.apply(NavigationDecision(UiDirective("date_time"), MochiNavigationIntent.ShowDateTime))
                    }
                    AgentReply("Call me when you need me.", "neutral")
                } },
            )
            viewModel.navigate(MochiNavigationIntent.ShowDateTime)
            viewModel.setFocusMode(true)
            if (voiceInput) viewModel.startVoiceInput() else viewModel.sendConversation("That's all")

            val firstListenCount = if (voiceInput) 1 else 0
            assertEquals(firstListenCount, voice.listenCount)
            assertEquals(ChatPipelineStage.SPEAKING, viewModel.pipelineState.value.stage)
            assertEquals("Call me when you need me.", voice.spokenText)
            assertEquals("Call me when you need me.", viewModel.conversationState.value.messages.last().text)
            assertEquals(MochiSurface.DateTime, viewModel.surface.value)
            assertTrue(viewModel.focusMode.value)
            val oldPlayback = voice.speechCallbacks.single()
            oldPlayback(SpeechPlaybackResult.COMPLETED)
            assertEquals(firstListenCount, voice.listenCount)
            assertEquals(ChatPipelineStage.IDLE, viewModel.pipelineState.value.stage)
            assertTrue(wake.resumeCount > 0)

            voice.transcripts.clear()
            voice.transcripts.addLast("Another request")
            viewModel.startVoiceInput(acknowledgeWake = voiceInput)
            if (voiceInput) voice.speechCallbacks.last()(SpeechPlaybackResult.COMPLETED)
            assertEquals(firstListenCount + 1, voice.listenCount)
            kotlinx.coroutines.runBlocking {
                checkNotNull(oldSink).apply(NavigationDecision(
                    UiDirective("end_voice_session", followUpListening = false),
                    MochiNavigationIntent.EndVoiceSession,
                ))
            }
            oldPlayback(SpeechPlaybackResult.COMPLETED)
            assertEquals(ChatPipelineStage.SPEAKING, viewModel.pipelineState.value.stage)
            voice.speechCallbacks.last()(SpeechPlaybackResult.COMPLETED)
            assertEquals(firstListenCount + 2, voice.listenCount)
            assertEquals(ChatPipelineStage.IDLE, viewModel.pipelineState.value.stage)
        }
    }

    @Test
    fun `failed synthesis preserves reply without opening follow up microphone`() {
        val voiceRuntime = VoiceRuntimeFake(
            "Turn off the television",
            speechResult = SpeechPlaybackResult.FAILED,
        )
        val wakeRuntime = WakeRuntimeFake()
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(),
            providerSettingsRepository = ProviderSettingsRepositoryFake(),
            agentRunnerBuilder = { _, _, _ ->
                AgentRunner { AgentReply("Done.", "neutral") }
            },
            voiceRuntime = voiceRuntime,
            wakeRuntime = wakeRuntime,
            clock = fixedClock(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.startVoiceInput()

        assertEquals(1, voiceRuntime.listenCount)
        assertEquals("Done.", viewModel.conversationState.value.messages.last().text)
        assertEquals(ChatPipelineStage.IDLE, viewModel.pipelineState.value.stage)
        assertTrue(wakeRuntime.resumeCount > 0)
    }

    @Test
    fun `wake word acknowledgement is spoken but not added to history`() {
        val voiceRuntime = VoiceRuntimeFake("Turn on the light")
        val viewModel = MochiHomeViewModel(
            plannerStore = PlannerStoreFake(),
            providerSettingsRepository = ProviderSettingsRepositoryFake(),
            agentRunnerBuilder = { _, _, _ ->
                AgentRunner { AgentReply("Done.", "neutral") }
            },
            voiceRuntime = voiceRuntime,
            wakeRuntime = WakeRuntimeFake(),
            clock = fixedClock(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.startVoiceInput(acknowledgeWake = true)

        assertEquals(
            listOf("嗯？", "Done."),
            voiceRuntime.spokenTexts,
        )
        assertEquals(
            listOf(SpeechPurpose.WAKE_ACKNOWLEDGEMENT, SpeechPurpose.REPLY),
            voiceRuntime.speechPurposes,
        )
        assertEquals(
            listOf("Turn on the light", "Done."),
            viewModel.conversationState.value.messages
                .map(ConversationMessage::text),
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
    fun `weather fallback respects voice session exit after agent failure`() {
        for (endVoiceSession in listOf(false, true)) {
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
                agentRunnerBuilder = { sink, _, onWeatherLoaded ->
                    AgentRunner {
                        onWeatherLoaded(weather)
                        if (endVoiceSession) {
                            sink.apply(NavigationDecision(
                                UiDirective("end_voice_session", followUpListening = false),
                                MochiNavigationIntent.EndVoiceSession,
                            ))
                        }
                        error("Agent failed after weather tool")
                    }
                },
                voiceRuntime = voiceRuntime,
                wakeRuntime = wakeRuntime,
                clock = fixedClock(),
                ioDispatcher = Dispatchers.Unconfined,
                mainDispatcher = Dispatchers.Unconfined,
            )

            viewModel.startVoiceInput()

            assertTrue(voiceRuntime.spokenText?.contains("29") == true)
            assertTrue(voiceRuntime.spokenText?.contains("74") == true)
            assertEquals(if (endVoiceSession) 1 else 2, voiceRuntime.listenCount)
            assertEquals(null, viewModel.conversationState.value.errorMessage)
        }
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
    private val beforeAgendaLoad: suspend (LocalDate) -> Unit = {},
) : com.example.mochi_pet.core.database.TestPlannerStore() {
    private val todos = todos.toMutableList()
    var requestedTodoDate: LocalDate? = null
    val completedTodoIds = mutableListOf<String>()

    override suspend fun listAgendaTodos(date: LocalDate, today: LocalDate): List<MochiTodo> {
        beforeAgendaLoad(date)
        return super.listAgendaTodos(date, today)
    }

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

    override suspend fun updateCalendarEvent(event: CalendarEvent, indefiniteRecurrence: Boolean): CalendarEvent =
        unsupported()

    override suspend fun getCalendarEvent(id: String): CalendarEvent? =
        unsupported()

    override suspend fun deleteCalendarEvent(id: String) = unsupported<Unit>()

    override suspend fun createTodo(draft: MochiTodoDraft): MochiTodo =
        unsupported()

    override suspend fun updateTodo(todo: MochiTodo): MochiTodo = unsupported()

    override suspend fun getTodo(id: String): MochiTodo? = unsupported()

    override suspend fun completeTodo(id: String, expectedRevision: Long?): MochiTodo {
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

    override suspend fun deleteTodo(id: String, expectedRevision: Long?) = unsupported<Unit>()

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

    override suspend fun loadProfiles() = com.example.mochi_pet.core.settings.ProviderProfilesSummary(
        listOf(com.example.mochi_pet.core.settings.ProviderProfileSummary(
            "test", "Test", com.example.mochi_pet.core.settings.ProviderPreset.CUSTOM, summary,
        )), "test",
    )
    override suspend fun saveProfile(input: com.example.mochi_pet.core.settings.ProviderProfileInput) = loadProfiles()
    override suspend fun activateProfile(id: String) = loadProfiles()
    override suspend fun deleteProfile(id: String) = loadProfiles()
    override suspend fun importProfiles(inputs: List<com.example.mochi_pet.core.settings.ProviderProfileInput>, activeIndex: Int) = loadProfiles()
    override suspend fun loadProfileRuntimeConfig(id: String) = loadRuntimeConfig()

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

private class BriefingVoiceFake : VoiceRuntime {
    override val state = MutableStateFlow(VoiceRuntimeState())
    var listenCount = 0
    var acknowledgements = 0
    var briefingResult: ((WakeBriefingResult) -> Unit)? = null
    override fun startListening(onFinalTranscript: (String) -> Unit, onNoResult: () -> Unit) { listenCount++ }
    override fun startListeningWithBriefing(
        text: String, onBriefingResult: (WakeBriefingResult) -> Unit,
        onFinalTranscript: (String) -> Unit, onNoResult: () -> Unit,
    ) {
        briefingResult = onBriefingResult
        listenCount++
    }
    override fun stopListening() = Unit
    override fun stopSpeaking() = Unit
    override fun speak(text: String, purpose: SpeechPurpose, previewVoiceId: String?, previewProfileId: String?, onCompleted: (SpeechPlaybackResult) -> Unit) {
        acknowledgements++
        onCompleted(SpeechPlaybackResult.COMPLETED)
    }
}

private class VoiceRuntimeFake(
    transcript: String,
    private val speechResult: SpeechPlaybackResult = SpeechPlaybackResult.COMPLETED,
    private val autoCompleteSpeech: Boolean = true,
) : VoiceRuntime {
    val transcripts = ArrayDeque(listOf(transcript))
    override val state = MutableStateFlow(
        VoiceRuntimeState(recognitionAvailable = true, ttsReady = true),
    )
    var spokenText: String? = null
    val spokenTexts = mutableListOf<String>()
    val speechPurposes = mutableListOf<SpeechPurpose>()
    val previewVoiceIds = mutableListOf<String?>()
    val speechCallbacks = mutableListOf<(SpeechPlaybackResult) -> Unit>()
    var stopSpeakingCount = 0
    var voiceCatalogLoader: suspend (SpeechProvider) -> List<SpeechVoice> = { IFLYTEK_BASIC_VOICES }

    override suspend fun availableVoices(provider: SpeechProvider, profileId: String?) = voiceCatalogLoader(provider)
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
        purpose: SpeechPurpose,
        previewVoiceId: String?,
        previewProfileId: String?,
        onCompleted: (SpeechPlaybackResult) -> Unit,
    ) {
        spokenText = text
        spokenTexts += text
        speechPurposes += purpose
        previewVoiceIds += previewVoiceId
        speechCallbacks += onCompleted
        if (autoCompleteSpeech) onCompleted(speechResult)
    }

    override fun stopSpeaking() { stopSpeakingCount += 1 }
}

private class PreviewSpeechSettingsFake : SpeechSettingsRepository by com.example.mochi_pet.core.settings.DataStoreSpeechSettingsRepository(
    com.example.mochi_pet.core.settings.SpeechPreferencesDataStore(),
    com.example.mochi_pet.core.settings.SpeechFakeCipher(),
) {
    var saveCount = 0
    override suspend fun loadSummary() = SpeechSettingsSummary(
        provider = SpeechProvider.IFLYTEK,
        iFlytekAppId = "test-app",
        hasIFlytekApiKey = true,
        hasIFlytekApiSecret = true,
        iFlytekVoice = "x4_xiaoyan",
    )
    override suspend fun save(input: SpeechSettingsInput): SpeechSettingsSummary {
        saveCount += 1
        return loadSummary()
    }
    override suspend fun loadRuntimeConfig() = SpeechRuntimeConfig.IFlytek("test-app", "test-key", "test-secret")
    override suspend fun loadProfiles() = com.example.mochi_pet.core.settings.SpeechProfilesSummary(
        listOf(com.example.mochi_pet.core.settings.SpeechProfileSummary("preview", "Preview", loadSummary())), "preview",
    )
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
        purpose: SpeechPurpose,
        previewVoiceId: String?,
        previewProfileId: String?,
        onCompleted: (SpeechPlaybackResult) -> Unit,
    ) = onCompleted(SpeechPlaybackResult.COMPLETED)

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
        purpose: SpeechPurpose,
        previewVoiceId: String?,
        previewProfileId: String?,
        onCompleted: (SpeechPlaybackResult) -> Unit,
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

    override fun selectKeyword(keyword: WakeKeyword?) {
        state.value = state.value.copy(keyword = keyword ?: WakeKeyword.ENGLISH, keywordOverride = keyword)
    }

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
