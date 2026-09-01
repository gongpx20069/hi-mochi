package com.example.mochi_pet.feature.home

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.core.update.AppUpdate
import com.example.mochi_pet.core.agent.llm.DEFAULT_AZURE_API_VERSION
import com.example.mochi_pet.core.agent.llm.ProviderType
import com.example.mochi_pet.core.model.CalendarEvent
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.model.MochiTodo
import com.example.mochi_pet.core.model.TodoStatus
import com.example.mochi_pet.core.mcp.TENCENT_DOCS_SERVER_ID
import com.example.mochi_pet.core.navigation.MochiNavigationIntent
import com.example.mochi_pet.core.presentation.CardAction
import com.example.mochi_pet.core.presentation.CardActionType
import com.example.mochi_pet.core.presentation.CardPresentation
import com.example.mochi_pet.core.presentation.CardType
import com.example.mochi_pet.core.settings.AppLanguage
import com.example.mochi_pet.core.settings.ALLOWED_FOCUS_STANDBY_DELAYS_SECONDS
import com.example.mochi_pet.core.settings.ProviderSettingsInput
import com.example.mochi_pet.core.settings.SpeechProvider
import com.example.mochi_pet.core.settings.SpeechSettingsInput
import com.example.mochi_pet.core.schedule.AgentSchedule
import com.example.mochi_pet.core.schedule.AgentScheduleResult
import com.example.mochi_pet.core.skills.MarketSkillSummary
import com.example.mochi_pet.core.skills.MochiSkill
import com.example.mochi_pet.core.skills.DownloadedSkill
import com.example.mochi_pet.core.skills.InstallWindow
import com.example.mochi_pet.core.skills.SkillOrigin
import com.example.mochi_pet.core.tools.BuiltInToolSummary
import com.example.mochi_pet.core.tools.ManualMcpServerInput
import com.example.mochi_pet.core.tools.McpAuthMode
import com.example.mochi_pet.core.tools.McpServerSummary
import com.example.mochi_pet.core.voice.VoiceRuntime
import com.example.mochi_pet.core.voice.VoiceRuntimeState
import com.example.mochi_pet.core.wake.WakeCaptureStatus
import com.example.mochi_pet.core.wake.WakeRuntime
import com.example.mochi_pet.core.wake.WakeRuntimeState
import com.example.mochi_pet.core.web.PublicWebUrlPolicy
import com.example.mochi_pet.core.web.WebContentException
import com.example.mochi_pet.platform.browser.AgentBrowserUiState
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AddTodoRequest(
    val scheduledDate: LocalDate?,
)

private enum class PlannerSection {
    TODAY,
    CALENDAR,
}

private sealed interface MarkdownBlock {
    data class Heading(
        val level: Int,
        val text: String,
    ) : MarkdownBlock

    data class Paragraph(val text: String) : MarkdownBlock

    data class ListItem(
        val text: String,
        val ordered: Boolean,
        val number: Int?,
    ) : MarkdownBlock

    data class Quote(val text: String) : MarkdownBlock

    data class Code(val text: String) : MarkdownBlock

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
    ) : MarkdownBlock

    data object Divider : MarkdownBlock
}

@Composable
fun MochiApp(
    voiceRuntime: VoiceRuntime,
    wakeRuntime: WakeRuntime,
    voiceTriggers: Flow<Unit>,
    oauthCallbacks: Flow<String>,
    providerShareCallbacks: Flow<String>,
) {
    val context = LocalContext.current
    val application = context.applicationContext as MochiApplication
    val coroutineScope = rememberCoroutineScope()
    val factory = remember(application, voiceRuntime, wakeRuntime) {
        MochiHomeViewModel.factory(application, voiceRuntime, wakeRuntime)
    }
    val viewModel: MochiHomeViewModel = viewModel(factory = factory)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startVoiceInput()
        } else {
            viewModel.reportMicrophonePermissionDenied()
        }
    }
    val wakePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.enableWakeWord()
        } else {
            viewModel.reportWakePermissionDenied()
        }
    }
    val locationPermissionRequest by
        viewModel.locationPermissionRequest.collectAsStateWithLifecycle()
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        viewModel.resolveLocationPermission(
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_FINE_LOCATION] == true,
        )
    }
    val startVoice = {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startVoiceInput()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val enableWake = {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (
            permissions.all {
                ContextCompat.checkSelfPermission(context, it) ==
                    PackageManager.PERMISSION_GRANTED
            }
        ) {
            viewModel.enableWakeWord()
        } else {
            wakePermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    LaunchedEffect(voiceTriggers) {
        voiceTriggers.collect {
            startVoice()
        }
    }
    LaunchedEffect(oauthCallbacks) {
        oauthCallbacks.collect(viewModel::completeNotionAuthorization)
    }
    LaunchedEffect(providerShareCallbacks) {
        providerShareCallbacks.collect(viewModel::stageProviderImport)
    }
    LifecycleResumeEffect(wakeRuntime) {
        val startJob = coroutineScope.launch {
            delay(1_000)
            if (
                wakeRuntime.shouldBeEnabled &&
                wakeRuntime.state.value.status in setOf(
                    WakeCaptureStatus.DISABLED,
                    WakeCaptureStatus.ERROR,
                )
            ) {
                enableWake()
            }
        }
        onPauseOrDispose {
            startJob.cancel()
        }
    }
    LaunchedEffect(locationPermissionRequest) {
        if (locationPermissionRequest) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
            )
        }
    }

    MochiAppContent(
        viewModel = viewModel,
        onStartVoice = startVoice,
        onEnableWake = enableWake,
    )
}

@Composable
private fun MochiAppContent(
    viewModel: MochiHomeViewModel,
    onStartVoice: () -> Unit,
    onEnableWake: () -> Unit,
) {
    val surface by viewModel.surface.collectAsStateWithLifecycle()
    val plannerState by viewModel.plannerState.collectAsStateWithLifecycle()
    val conversationState by
        viewModel.conversationState.collectAsStateWithLifecycle()
    val providerSettingsState by
        viewModel.providerSettingsState.collectAsStateWithLifecycle()
    val speechSettingsState by
        viewModel.speechSettingsState.collectAsStateWithLifecycle()
    val providerShareState by
        viewModel.providerShareState.collectAsStateWithLifecycle()
    val agentSettingsState by
        viewModel.agentSettingsState.collectAsStateWithLifecycle()
    val personaState by viewModel.personaState.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val wakeState by viewModel.wakeState.collectAsStateWithLifecycle()
    val wakeFeedback by viewModel.wakeFeedback.collectAsStateWithLifecycle()
    val pipelineState by viewModel.pipelineState.collectAsStateWithLifecycle()
    val skillsState by viewModel.skillsState.collectAsStateWithLifecycle()
    val toolsState by viewModel.toolsState.collectAsStateWithLifecycle()
    val weatherState by viewModel.weatherState.collectAsStateWithLifecycle()
    val homeCard by viewModel.homeCard.collectAsStateWithLifecycle()
    val browserState by viewModel.browserState.collectAsStateWithLifecycle()
    var addTodoRequest by remember { mutableStateOf<AddTodoRequest?>(null) }
    var focusMode by rememberSaveable { mutableStateOf(false) }
    var focusStandby by rememberSaveable { mutableStateOf(false) }
    var standbyResetVersion by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val application = context.applicationContext as MochiApplication
    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current
    val activity = context as? Activity
    var availableUpdate by remember { mutableStateOf<AppUpdate?>(null) }
    val homePresentation = surface.isHomePresentation()
    val focusStandbySettings = agentSettingsState.settings
    val visiblePipelineState =
        if (pipelineState.stage == ChatPipelineStage.LISTENING) {
            pipelineState.copy(
            detail = voiceState.partialTranscript.takeIf(String::isNotBlank),
            )
        } else {
            pipelineState
        }

    LaunchedEffect(homePresentation) {
        if (!homePresentation) {
            focusMode = false
            focusStandby = false
        }
    }
    val focusStandbyEligible = isFocusStandbyEligible(
        focusMode = focusMode,
        homePresentation = homePresentation,
        enabled = focusStandbySettings.focusStandbyEnabled,
        pipelineActive = visiblePipelineState.isActive,
        voiceListening = voiceState.isListening,
        browserActive = browserState.active,
    )
    LaunchedEffect(
        focusStandbyEligible,
        focusStandbySettings.focusStandbyDelaySeconds,
        standbyResetVersion,
    ) {
        if (!focusStandbyEligible) {
            focusStandby = false
            return@LaunchedEffect
        }
        focusStandby = false
        delay(focusStandbySettings.focusStandbyDelaySeconds * 1_000L)
        focusStandby = true
    }
    LifecycleResumeEffect(focusMode) {
        onPauseOrDispose {
            if (focusMode) {
                focusStandby = false
                standbyResetVersion += 1
            }
        }
    }
    LaunchedEffect(Unit) {
        availableUpdate = application.appUpdateClient.check()
    }
    LaunchedEffect(providerShareState.shareLink) {
        val shareLink = providerShareState.shareLink ?: return@LaunchedEffect
        try {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Mochi Provider access")
                        putExtra(Intent.EXTRA_TEXT, shareLink)
                    },
                    "Share Mochi Providers",
                ),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                context,
                "No sharing app is available",
                Toast.LENGTH_SHORT,
            ).show()
        } finally {
            viewModel.consumeProviderShareLink()
        }
    }
    DisposableEffect(focusMode, activity, view) {
        val window = activity?.window
        val insetsController = window?.let {
            WindowCompat.getInsetsController(it, view)
        }
        view.keepScreenOn = focusMode
        if (focusMode) {
            insetsController?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            view.keepScreenOn = false
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(focusStandby, activity) {
        val window = activity?.window
        if (!focusStandby || window == null) {
            onDispose { }
        } else {
            val originalBrightness = window.attributes.screenBrightness
            window.attributes = window.attributes.apply {
                screenBrightness = FOCUS_STANDBY_BRIGHTNESS
            }
            onDispose {
                window.attributes = window.attributes.apply {
                    screenBrightness = originalBrightness
                }
            }
        }
    }
    BackHandler(enabled = focusMode) {
        focusStandby = false
        focusMode = false
    }
    LaunchedEffect(toolsState.authorizationUrl) {
        val authorizationUrl = toolsState.authorizationUrl ?: return@LaunchedEffect
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, authorizationUrl.toUri()),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                context,
                localizeUiText("No browser is available"),
                Toast.LENGTH_SHORT,
            ).show()
        } finally {
            viewModel.consumeToolAuthorizationUrl()
        }
    }

    val renderSurface: @Composable (Modifier) -> Unit = { modifier ->
        val performCardAction:
            (CardPresentation, CardAction) -> Unit = { card, action ->
            if (action.type == CardActionType.OPEN_SOURCE) {
                val url = action.url
                if (url == null) {
                    Toast.makeText(
                        context,
                        localizeUiText(
                            "This source cannot be opened safely",
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    coroutineScope.launch {
                        val uri = try {
                            val validated = withContext(Dispatchers.IO) {
                                PublicWebUrlPolicy.validate(url).toString()
                            }
                            validated.toUri()
                        } catch (_: WebContentException) {
                            Toast.makeText(
                                context,
                                localizeUiText(
                                    "This source cannot be opened safely",
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@launch
                        } catch (_: IllegalArgumentException) {
                            Toast.makeText(
                                context,
                                localizeUiText("This source URL is invalid"),
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@launch
                        }
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, uri),
                            )
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(
                                context,
                                localizeUiText("No browser is available"),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } catch (_: SecurityException) {
                            Toast.makeText(
                                context,
                                localizeUiText(
                                    "The browser blocked this source",
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            } else {
                viewModel.performCardAction(card, action)
            }
        }
        SurfaceContent(
            surface = surface,
            plannerState = plannerState,
            conversationState = conversationState,
            providerSettingsState = providerSettingsState,
            speechSettingsState = speechSettingsState,
            providerShareState = providerShareState,
            agentSettingsState = agentSettingsState,
            personaState = personaState,
            voiceState = voiceState,
            pipelineState = visiblePipelineState,
            wakeState = wakeState,
            wakeFeedback = wakeFeedback,
            skillsState = skillsState,
            toolsState = toolsState,
            weatherState = weatherState,
            homeCard = homeCard,
            onNavigate = viewModel::navigate,
            onSendMessage = viewModel::sendConversation,
            onCancelMessage = viewModel::cancelConversation,
            onStartVoice = onStartVoice,
            onStopVoice = viewModel::stopVoiceInput,
            onEnableWake = onEnableWake,
            onDisableWake = viewModel::disableWakeWord,
            onSaveProviderSettings = viewModel::saveProviderSettings,
            onSaveSpeechSettings = viewModel::saveSpeechSettings,
            onCreateProviderShareLink =
                viewModel::createProviderShareLink,
            onReceiveProviderShareLink =
                viewModel::stageProviderImport,
            onSetRecentConversationTurns =
                viewModel::setRecentConversationTurns,
            onSetFocusStandby = viewModel::setFocusStandby,
            onSavePersona = viewModel::savePersona,
            onSearchSkills = viewModel::searchSkills,
            onLoadPopularSkills = viewModel::loadPopularSkills,
            onPreviewSkill = viewModel::previewSkill,
            onClearSkillPreview = viewModel::clearSkillPreview,
            onInstallSkill = viewModel::installSkill,
            onEditSkill = viewModel::updateSkillContent,
            onSetSkillEnabled = viewModel::setSkillEnabled,
            onDeleteSkill = viewModel::deleteSkill,
            onCheckSkillUpdates = viewModel::checkSkillUpdates,
            onApplySkillUpdate = viewModel::applySkillUpdate,
            onSetBuiltInToolEnabled = viewModel::setBuiltInToolEnabled,
            onConnectNotion = viewModel::beginNotionAuthorization,
            onDisconnectNotion = viewModel::disconnectNotion,
            onOpenTencentDocsTokenPage =
                viewModel::openTencentDocsTokenPage,
            onConfigureTencentDocs = viewModel::configureTencentDocs,
            onDisconnectTencentDocs = viewModel::disconnectTencentDocs,
            onOpenAmapConsole = viewModel::openAmapConsole,
            onConfigureAmap = viewModel::configureAmap,
            onDisconnectAmap = viewModel::disconnectAmap,
            onSetAmapEnabled = viewModel::setAmapEnabled,
            onSetAgentBrowserEnabled = viewModel::setAgentBrowserEnabled,
            onAddMcpServer = viewModel::addManualMcpServer,
            onRemoveMcpServer = viewModel::removeManualMcpServer,
            onSetMcpServerEnabled = viewModel::setMcpServerEnabled,
            onSetMcpToolEnabled = viewModel::setMcpToolEnabled,
            onAddTodo = { date ->
                addTodoRequest = AddTodoRequest(date)
            },
            onCompleteTodo = viewModel::completeTodo,
            onSetScheduleEnabled = viewModel::setScheduleEnabled,
            onRunSchedule = viewModel::runSchedule,
            onRemoveSchedule = viewModel::removeSchedule,
            onCardAction = performCardAction,
            onFocus = {
                focusStandby = false
                standbyResetVersion += 1
                focusMode = true
            },
            modifier = modifier,
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        if (focusMode && homePresentation) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(focusMode) {
                        awaitEachGesture {
                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            focusStandby = false
                            standbyResetVersion += 1
                        }
                    }
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF17131D),
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    ),
            ) {
                if (focusStandby) {
                    FocusStandbyScreen(
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    if (browserState.active) {
                        BrowserSessionCard(
                            state = browserState,
                            webViewProvider = viewModel::browserWebView,
                            onRelease = viewModel::releaseBrowserWebView,
                            onStop = viewModel::cancelConversation,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        renderSurface(Modifier.fillMaxSize())
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            OutlinedButton(
                                onClick = {
                                    focusStandby = false
                                    focusMode = false
                                },
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Text("Exit focus")
                            }
                        }
                        ChatPipelineIndicator(state = visiblePipelineState)
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF17131D),
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    )
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MochiTopBar(
                    surface = surface,
                    wakeState = wakeState,
                    onSettings = {
                        viewModel.navigate(
                            if (surface == MochiSurface.Settings) {
                                MochiNavigationIntent.ShowFace
                            } else {
                                MochiNavigationIntent.ShowSettings
                            },
                        )
                    },
                )
                ChatPipelineIndicator(state = visiblePipelineState)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(28.dp),
                    tonalElevation = 6.dp,
                ) {
                    if (homePresentation && browserState.active) {
                        BrowserSessionCard(
                            state = browserState,
                            webViewProvider = viewModel::browserWebView,
                            onRelease = viewModel::releaseBrowserWebView,
                            onStop = viewModel::cancelConversation,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        renderSurface(
                            if (homePresentation) {
                                Modifier
                            } else {
                                Modifier.padding(18.dp)
                            },
                        )
                    }
                }
                NavigationBar(
                    surface = surface,
                    onNavigate = viewModel::navigate,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    availableUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = { availableUpdate = null },
            title = { Text("Mochi ${update.version} is available") },
            text = {
                Text(
                    update.notes.ifBlank {
                        "Open the GitHub Release page to download the APK."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        openExternalPage(context, update.releaseUrl)
                        availableUpdate = null
                    },
                ) {
                    Text("Open release")
                }
            },
            dismissButton = {
                TextButton(onClick = { availableUpdate = null }) {
                    Text("Later")
                }
            },
        )
    }
    providerShareState.pendingImportLink?.let {
        AlertDialog(
            onDismissRequest = viewModel::cancelProviderImport,
            title = { Text("Import shared Providers?") },
            text = {
                Text(
                    "This link grants access to another user's LLM and speech " +
                        "API resources. Importing replaces this device's " +
                        "current LLM and speech Provider configuration. " +
                        "Only continue if you trust the sender.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmProviderImport,
                    enabled = !providerShareState.isWorking,
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelProviderImport) {
                    Text("Cancel")
                }
            },
        )
    }
    addTodoRequest?.let { request ->
        AddTodoDialog(
            scheduledDate = request.scheduledDate,
            onDismiss = { addTodoRequest = null },
            onConfirm = { content ->
                viewModel.createTodo(content, request.scheduledDate)
                addTodoRequest = null
            },
        )
    }
}

@Composable
private fun BrowserSessionCard(
    state: AgentBrowserUiState,
    webViewProvider: (android.content.Context) -> android.webkit.WebView?,
    onRelease: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(state.sessionId) {
        onDispose(onRelease)
    }
    Box(
        modifier = modifier
            .background(Color(0xFF8FAEFF))
            .padding(3.dp)
            .semantics {
                contentDescription = localizeUiText("Agent Browser")
            },
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
            shape = RoundedCornerShape(25.dp),
        ) {
            AndroidView(
                factory = { context ->
                    checkNotNull(webViewProvider(context)) {
                        "Active browser session has no WebView"
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(
                    start = 12.dp,
                    top = 6.dp,
                    end = 6.dp,
                    bottom = 6.dp,
                ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.actor
                            ?: state.title.ifBlank { "Agent Browser" },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        text = when {
                            state.actor != null && state.title.isNotBlank() ->
                                state.title
                            else -> state.action
                                ?: state.url.ifBlank { "Preparing page" }
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                    )
                }
                TextButton(onClick = onStop) {
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
private fun MochiTopBar(
    surface: MochiSurface,
    wakeState: WakeRuntimeState,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Mochi",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = if (wakeState.enabled) {
                    "Hi Mochi is ${wakeState.status.name.lowercase()}"
                } else {
                    surface.displayName()
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        OutlinedButton(
            onClick = onSettings,
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(
                if (surface == MochiSurface.Settings) {
                    "Done"
                } else {
                    "Settings"
                },
            )
        }
    }
}

@Composable
private fun ChatPipelineIndicator(state: ChatPipelineUiState) {
    AnimatedVisibility(
        visible = state.isActive,
        enter = fadeIn(tween(220)) + expandVertically(tween(260)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(220)),
    ) {
        val accent by animateColorAsState(
            targetValue = state.stage.accentColor(),
            animationSpec = tween(320),
            label = "pipeline-accent",
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription =
                        "${localizeUiText(state.stage.displayText())}. " +
                        localizeUiText(state.stage.supportingText())
                },
            color = Color(0xFF211C28),
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 8.dp,
            shadowElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PipelinePulse(accent = accent)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.stage.displayText(),
                            color = Color(0xFFF8F2FA),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        val detail = state.detail
                            ?.take(120)
                            ?.takeIf(String::isNotBlank)
                        if (detail == null) {
                            Text(
                                text = state.stage.supportingText(),
                                color = Color(0xFFBDB3C2),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                            )
                        } else {
                            MaterialText(
                                text = detail,
                                color = Color(0xFFBDB3C2),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                            )
                        }
                    }
                }
                PipelineTrack(
                    stage = state.stage,
                    accent = accent,
                )
            }
        }
    }
}

@Composable
private fun PipelinePulse(accent: Color) {
    val transition = rememberInfiniteTransition(label = "pipeline-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pipeline-pulse-scale",
    )
    Box(
        modifier = Modifier.size(30.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                    alpha = 1.3f - pulse
                }
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.24f)),
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(accent),
        )
    }
}

@Composable
private fun PipelineTrack(
    stage: ChatPipelineStage,
    accent: Color,
) {
    val stages = remember {
        listOf(
            ChatPipelineStage.LISTENING,
            ChatPipelineStage.SKILLING,
            ChatPipelineStage.THINKING,
            ChatPipelineStage.SUBAGENT,
            ChatPipelineStage.TOOL,
            ChatPipelineStage.SUMMARY,
            ChatPipelineStage.SPEAKING,
        )
    }
    val activeIndex = stages.indexOf(stage)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        stages.forEachIndexed { index, _ ->
            val completed = index < activeIndex
            val selected = index == activeIndex
            Box(
                modifier = Modifier
                    .weight(if (selected) 1.65f else 1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            selected -> accent
                            completed -> accent.copy(alpha = 0.38f)
                            else -> Color.White.copy(alpha = 0.08f)
                        },
                    ),
            )
        }
    }
}

private fun ChatPipelineStage.displayText(): String =
    when (this) {
        ChatPipelineStage.IDLE -> ""
        ChatPipelineStage.LISTENING -> "Listening"
        ChatPipelineStage.SKILLING -> "Choosing skills"
        ChatPipelineStage.THINKING -> "Thinking"
        ChatPipelineStage.SUBAGENT -> "Delegating"
        ChatPipelineStage.TOOL -> "Working"
        ChatPipelineStage.SUMMARY -> "Composing"
        ChatPipelineStage.SPEAKING -> "Speaking"
    }

private fun ChatPipelineStage.supportingText(): String =
    when (this) {
        ChatPipelineStage.IDLE -> ""
        ChatPipelineStage.LISTENING -> "I'm here. Say what you need."
        ChatPipelineStage.SKILLING -> "Finding the best way to help."
        ChatPipelineStage.THINKING -> "Making sense of your request."
        ChatPipelineStage.SUBAGENT ->
            "Waiting for Mochi's specialist to finish."
        ChatPipelineStage.TOOL -> "Using Mochi's local tools."
        ChatPipelineStage.SUMMARY -> "Turning the result into a clear answer."
        ChatPipelineStage.SPEAKING -> "Reading the answer aloud."
    }

private fun ChatPipelineStage.accessibilityText(): String =
    "${displayText()}. ${supportingText()}"

private fun ChatPipelineStage.accentColor(): Color =
    when (this) {
        ChatPipelineStage.LISTENING -> Color(0xFF62DFC3)
        ChatPipelineStage.SKILLING -> Color(0xFFB399FF)
        ChatPipelineStage.THINKING -> Color(0xFFFFBD70)
        ChatPipelineStage.SUBAGENT -> Color(0xFF68C7FF)
        ChatPipelineStage.TOOL -> Color(0xFF8FAEFF)
        ChatPipelineStage.SUMMARY -> Color(0xFFFFA76C)
        ChatPipelineStage.SPEAKING -> Color(0xFFFF8FB4)
        ChatPipelineStage.IDLE -> Color(0xFFFF8F70)
    }

private fun MochiSurface.displayName(): String =
    when (this) {
        MochiSurface.Face -> "Your quiet companion"
        MochiSurface.DateTime -> "Date and time"
        MochiSurface.Weather -> "Local weather"
        MochiSurface.Card -> "Mochi card"
        MochiSurface.Conversation -> "Conversation"
        MochiSurface.Settings -> "Settings"
        MochiSurface.Skills -> "Skills"
        MochiSurface.Tools -> "Tools"
        MochiSurface.Today -> "Today"
        is MochiSurface.CalendarMonth ->
            month.format(uiDateFormatter("MMMM yyyy", "yyyy年M月"))
        is MochiSurface.CalendarDay ->
            date.format(uiDateFormatter("EEEE, MMMM d", "M月d日 EEEE"))
        is MochiSurface.Todo -> "Todo"
    }

private fun MochiSurface.isHomePresentation(): Boolean =
    this == MochiSurface.Face ||
        this == MochiSurface.DateTime ||
        this == MochiSurface.Weather ||
        this == MochiSurface.Card

@Composable
private fun FocusStandbyScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var now by remember { mutableStateOf(ZonedDateTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            val current = ZonedDateTime.now()
            now = current
            val untilNextMinuteMs =
                60_000L -
                    current.second * 1_000L -
                    current.nano / 1_000_000L
            delay(untilNextMinuteMs.coerceAtLeast(250L))
        }
    }

    val timePattern = if (DateFormat.is24HourFormat(context)) {
        "HH:mm"
    } else {
        "h:mm"
    }
    val time = now.format(DateTimeFormatter.ofPattern(timePattern))
    val date = now.format(
        uiDateFormatter(
            englishPattern = "EEEE, MMMM d",
            chinesePattern = "M月d日 EEEE",
        ),
    )
    val offset = focusStandbyOffset(now.toEpochSecond() / 60L)
    val contentModifier = Modifier.offset(
        x = offset.xDp.dp,
        y = offset.yDp.dp,
    )

    Box(
        modifier = modifier
            .background(Color.Black)
            .semantics {
                contentDescription = "Mochi standby, $date, $time"
            },
        contentAlignment = Alignment.Center,
    ) {
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            FocusStandbyLandscape(
                time = time,
                date = date,
                modifier = contentModifier,
            )
        } else {
            FocusStandbyPortrait(
                time = time,
                date = date,
                modifier = contentModifier,
            )
        }
    }
}

@Composable
private fun FocusStandbyPortrait(
    time: String,
    date: String,
    modifier: Modifier = Modifier,
) {
    val roundedFont = remember {
        FontFamily(
            Typeface.create("sans-serif-rounded", Typeface.BOLD),
        )
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MinimalStandbyMochi(
            modifier = Modifier.size(width = 176.dp, height = 136.dp),
        )
        Spacer(modifier = Modifier.height(34.dp))
        MaterialText(
            text = date,
            color = STANDBY_DATE_COLOR,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = roundedFont,
            letterSpacing = 0.6.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        MaterialText(
            text = time,
            color = STANDBY_PRIMARY_COLOR,
            fontSize = 114.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = roundedFont,
            letterSpacing = (-3).sp,
        )
    }
}

@Composable
private fun FocusStandbyLandscape(
    time: String,
    date: String,
    modifier: Modifier = Modifier,
) {
    val roundedFont = remember {
        FontFamily(
            Typeface.create("sans-serif-rounded", Typeface.BOLD),
        )
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(88.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MinimalStandbyMochi(
            modifier = Modifier.size(width = 218.dp, height = 166.dp),
        )
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center,
        ) {
            MaterialText(
                text = date,
                color = STANDBY_DATE_COLOR,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = roundedFont,
                letterSpacing = 0.7.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            MaterialText(
                text = time,
                color = STANDBY_PRIMARY_COLOR,
                fontSize = 136.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = roundedFont,
                letterSpacing = (-4).sp,
            )
        }
    }
}

@Composable
private fun MinimalStandbyMochi(
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val outlineWidth = 2.5.dp.toPx()
            val horizontalInset = size.width * 0.06f
            val verticalInset = size.height * 0.08f
            drawRoundRect(
                color = STANDBY_FACE_FILL_COLOR,
                topLeft = Offset(horizontalInset, verticalInset),
                size = Size(
                    width = size.width - horizontalInset * 2,
                    height = size.height - verticalInset * 2,
                ),
                cornerRadius = CornerRadius(
                    x = size.minDimension * 0.34f,
                    y = size.minDimension * 0.34f,
                ),
            )
            drawRoundRect(
                color = STANDBY_OUTLINE_COLOR,
                topLeft = Offset(horizontalInset, verticalInset),
                size = Size(
                    width = size.width - horizontalInset * 2,
                    height = size.height - verticalInset * 2,
                ),
                cornerRadius = CornerRadius(
                    x = size.minDimension * 0.34f,
                    y = size.minDimension * 0.34f,
                ),
                style = Stroke(width = outlineWidth),
            )

            fun sleepingEye(centerX: Float) {
                val eye = Path().apply {
                    moveTo(centerX - size.width * 0.075f, size.height * 0.43f)
                    cubicTo(
                        centerX - size.width * 0.035f,
                        size.height * 0.37f,
                        centerX + size.width * 0.035f,
                        size.height * 0.37f,
                        centerX + size.width * 0.075f,
                        size.height * 0.43f,
                    )
                }
                drawPath(
                    path = eye,
                    color = STANDBY_PRIMARY_COLOR,
                    style = Stroke(
                        width = 4.dp.toPx(),
                        cap = StrokeCap.Round,
                    ),
                )
                drawLine(
                    color = STANDBY_PRIMARY_COLOR,
                    start = Offset(
                        centerX - size.width * 0.068f,
                        size.height * 0.415f,
                    ),
                    end = Offset(
                        centerX - size.width * 0.095f,
                        size.height * 0.39f,
                    ),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            sleepingEye(size.width * 0.36f)
            sleepingEye(size.width * 0.64f)

            drawOval(
                color = STANDBY_CHEEK_COLOR,
                topLeft = Offset(
                    size.width * 0.19f,
                    size.height * 0.57f,
                ),
                size = Size(size.width * 0.13f, size.height * 0.055f),
            )
            drawOval(
                color = STANDBY_CHEEK_COLOR,
                topLeft = Offset(
                    size.width * 0.68f,
                    size.height * 0.57f,
                ),
                size = Size(size.width * 0.13f, size.height * 0.055f),
            )

            drawOval(
                color = STANDBY_NOSE_COLOR,
                topLeft = Offset(
                    size.width * 0.47f,
                    size.height * 0.56f,
                ),
                size = Size(size.width * 0.06f, size.height * 0.055f),
            )
            drawOval(
                color = STANDBY_MOUTH_COLOR,
                topLeft = Offset(
                    size.width * 0.465f,
                    size.height * 0.65f,
                ),
                size = Size(size.width * 0.07f, size.height * 0.065f),
                style = Stroke(
                    width = 2.5.dp.toPx(),
                ),
            )
            drawArc(
                color = STANDBY_MOUTH_HIGHLIGHT_COLOR,
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(
                    size.width * 0.478f,
                    size.height * 0.672f,
                ),
                size = Size(size.width * 0.044f, size.height * 0.025f),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        MaterialText(
            text = "Z",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 1.dp, end = 1.dp),
            color = STANDBY_SLEEP_COLOR,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Cursive,
        )
        MaterialText(
            text = "z",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 22.dp, end = 22.dp),
            color = STANDBY_SLEEP_COLOR.copy(alpha = 0.72f),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Cursive,
        )
        MaterialText(
            text = "z",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 39.dp, end = 36.dp),
            color = STANDBY_SLEEP_COLOR.copy(alpha = 0.48f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Cursive,
        )
    }
}

@Composable
private fun SurfaceContent(
    surface: MochiSurface,
    plannerState: PlannerSurfaceState,
    conversationState: ConversationUiState,
    providerSettingsState: ProviderSettingsUiState,
    speechSettingsState: SpeechSettingsUiState,
    providerShareState: ProviderShareUiState,
    agentSettingsState: AgentSettingsUiState,
    personaState: PersonaUiState,
    voiceState: VoiceRuntimeState,
    pipelineState: ChatPipelineUiState,
    wakeState: WakeRuntimeState,
    wakeFeedback: String?,
    skillsState: SkillsUiState,
    toolsState: ToolsUiState,
    weatherState: WeatherUiState,
    homeCard: CardPresentation?,
    onNavigate: (MochiNavigationIntent) -> Unit,
    onSendMessage: (String) -> Unit,
    onCancelMessage: () -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onEnableWake: () -> Unit,
    onDisableWake: () -> Unit,
    onSaveProviderSettings: (ProviderSettingsInput) -> Unit,
    onSaveSpeechSettings: (SpeechSettingsInput) -> Unit,
    onCreateProviderShareLink: () -> Unit,
    onReceiveProviderShareLink: (String) -> Unit,
    onSetRecentConversationTurns: (Int) -> Unit,
    onSetFocusStandby: (Boolean, Int) -> Unit,
    onSavePersona: (String, String, String) -> Unit,
    onSearchSkills: (String) -> Unit,
    onLoadPopularSkills: () -> Unit,
    onPreviewSkill: (MarketSkillSummary) -> Unit,
    onClearSkillPreview: () -> Unit,
    onInstallSkill: (MarketSkillSummary) -> Unit,
    onEditSkill: (String, String) -> Unit,
    onSetSkillEnabled: (String, Boolean) -> Unit,
    onDeleteSkill: (String) -> Unit,
    onCheckSkillUpdates: () -> Unit,
    onApplySkillUpdate: (String) -> Unit,
    onSetBuiltInToolEnabled: (String, Boolean) -> Unit,
    onConnectNotion: () -> Unit,
    onDisconnectNotion: () -> Unit,
    onOpenTencentDocsTokenPage: () -> Unit,
    onConfigureTencentDocs: (String) -> Unit,
    onDisconnectTencentDocs: () -> Unit,
    onOpenAmapConsole: () -> Unit,
    onConfigureAmap: (String, String) -> Unit,
    onDisconnectAmap: () -> Unit,
    onSetAmapEnabled: (Boolean) -> Unit,
    onSetAgentBrowserEnabled: (Boolean) -> Unit,
    onAddMcpServer: (ManualMcpServerInput) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onSetMcpServerEnabled: (String, Boolean) -> Unit,
    onSetMcpToolEnabled: (String, String, Boolean) -> Unit,
    onAddTodo: (LocalDate?) -> Unit,
    onCompleteTodo: (String) -> Unit,
    onSetScheduleEnabled: (String, Boolean) -> Unit,
    onRunSchedule: (String) -> Unit,
    onRemoveSchedule: (String) -> Unit,
    onCardAction: (CardPresentation, CardAction) -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when (surface) {
            MochiSurface.Face,
            MochiSurface.DateTime,
            MochiSurface.Weather,
            MochiSurface.Card,
            -> HomePresentationSurface(
                surface = surface,
                pipelineState = pipelineState,
                weatherState = weatherState,
                card = homeCard,
                onNavigate = onNavigate,
                onCardAction = onCardAction,
                onFocus = onFocus,
            )
            MochiSurface.Conversation -> ConversationSurface(
                state = conversationState,
                providerReady = providerSettingsState.summary.isReady,
                voiceState = voiceState,
                onSend = onSendMessage,
                onCancel = onCancelMessage,
                onStartVoice = onStartVoice,
                onStopVoice = onStopVoice,
                onOpenSpeechSettings = {
                    onNavigate(MochiNavigationIntent.ShowSettings)
                },
                onCardAction = onCardAction,
            )
            MochiSurface.Settings -> ProviderSettingsSurface(
                state = providerSettingsState,
                speechState = speechSettingsState,
                providerShareState = providerShareState,
                agentSettingsState = agentSettingsState,
                personaState = personaState,
                wakeState = wakeState,
                wakeFeedback = wakeFeedback,
                onEnableWake = onEnableWake,
                onDisableWake = onDisableWake,
                onSave = onSaveProviderSettings,
                onSaveSpeech = onSaveSpeechSettings,
                onCreateProviderShareLink =
                    onCreateProviderShareLink,
                onReceiveProviderShareLink =
                    onReceiveProviderShareLink,
                onSetRecentConversationTurns =
                    onSetRecentConversationTurns,
                onSetFocusStandby = onSetFocusStandby,
                onSavePersona = onSavePersona,
            )
            MochiSurface.Skills -> SkillsSurface(
                state = skillsState,
                onSearch = onSearchSkills,
                onLoadPopular = onLoadPopularSkills,
                onPreview = onPreviewSkill,
                onClearPreview = onClearSkillPreview,
                onInstall = onInstallSkill,
                onEdit = onEditSkill,
                onSetEnabled = onSetSkillEnabled,
                onDelete = onDeleteSkill,
                onCheckUpdates = onCheckSkillUpdates,
                onApplyUpdate = onApplySkillUpdate,
            )
            MochiSurface.Tools -> ToolsSurface(
                state = toolsState,
                onSetBuiltInEnabled = onSetBuiltInToolEnabled,
                onConnectNotion = onConnectNotion,
                onDisconnectNotion = onDisconnectNotion,
                onOpenTencentDocsTokenPage =
                    onOpenTencentDocsTokenPage,
                onConfigureTencentDocs = onConfigureTencentDocs,
                onDisconnectTencentDocs = onDisconnectTencentDocs,
                onOpenAmapConsole = onOpenAmapConsole,
                onConfigureAmap = onConfigureAmap,
                onDisconnectAmap = onDisconnectAmap,
                onSetAmapEnabled = onSetAmapEnabled,
                onSetAgentBrowserEnabled = onSetAgentBrowserEnabled,
                onAddServer = onAddMcpServer,
                onRemoveServer = onRemoveMcpServer,
                onSetServerEnabled = onSetMcpServerEnabled,
                onSetToolEnabled = onSetMcpToolEnabled,
            )
            MochiSurface.Today -> DaySurface(
                title = "Today",
                state = plannerState,
                plannerSection = PlannerSection.TODAY,
                onShowToday = {
                    onNavigate(MochiNavigationIntent.ShowToday)
                },
                onShowCalendar = {
                    onNavigate(
                        MochiNavigationIntent.ShowCalendarMonth(
                            YearMonth.now(),
                        ),
                    )
                },
                onBackToMonth = null,
                onAddTodo = onAddTodo,
                onCompleteTodo = onCompleteTodo,
                onSetScheduleEnabled = onSetScheduleEnabled,
                onRunSchedule = onRunSchedule,
                onRemoveSchedule = onRemoveSchedule,
            )
            is MochiSurface.CalendarMonth -> CalendarMonthSurface(
                month = surface.month,
                onNavigate = onNavigate,
            )
            is MochiSurface.CalendarDay -> DaySurface(
                title = surface.date.format(
                    uiDateFormatter("EEEE, MMMM d", "M月d日 EEEE"),
                ),
                state = plannerState,
                plannerSection = PlannerSection.CALENDAR,
                onShowToday = {
                    onNavigate(MochiNavigationIntent.ShowToday)
                },
                onShowCalendar = {
                    onNavigate(
                        MochiNavigationIntent.ShowCalendarMonth(
                            YearMonth.from(surface.date),
                        ),
                    )
                },
                onBackToMonth = {
                    onNavigate(
                        MochiNavigationIntent.ShowCalendarMonth(
                            YearMonth.from(surface.date),
                        ),
                    )
                },
                onAddTodo = onAddTodo,
                onCompleteTodo = onCompleteTodo,
                onSetScheduleEnabled = onSetScheduleEnabled,
                onRunSchedule = onRunSchedule,
                onRemoveSchedule = onRemoveSchedule,
            )
            is MochiSurface.Todo -> TodoSurface(
                state = plannerState,
                onAddTodo = { onAddTodo(surface.date) },
                onCompleteTodo = onCompleteTodo,
            )
        }
    }
}

@Composable
private fun HomePresentationSurface(
    surface: MochiSurface,
    pipelineState: ChatPipelineUiState,
    weatherState: WeatherUiState,
    card: CardPresentation?,
    onNavigate: (MochiNavigationIntent) -> Unit,
    onCardAction: (CardPresentation, CardAction) -> Unit,
    onFocus: () -> Unit,
) {
    AnimatedContent(
        targetState = surface,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            (
                fadeIn(tween(360)) +
                    scaleIn(
                        initialScale = 0.88f,
                        animationSpec = tween(
                            durationMillis = 420,
                            easing = FastOutSlowInEasing,
                        ),
                    )
                ).togetherWith(
                fadeOut(tween(220)) +
                    scaleOut(
                        targetScale = 1.08f,
                        animationSpec = tween(280),
                    ),
            )
        },
        contentKey = { it::class },
        label = "home-presentation-morph",
    ) { target ->
        when (target) {
            MochiSurface.Face -> MochiFace(
                pipelineState = pipelineState,
                onTalk = {
                    onNavigate(MochiNavigationIntent.ShowConversation)
                },
                onToday = {
                    onNavigate(MochiNavigationIntent.ShowToday)
                },
                onFocus = onFocus,
            )
            MochiSurface.DateTime -> MochiDateTime(
                onRestoreFace = {
                    onNavigate(MochiNavigationIntent.ShowFace)
                },
            )
            MochiSurface.Weather -> MochiWeather(
                state = weatherState,
                onRestoreFace = {
                    onNavigate(MochiNavigationIntent.ShowFace)
                },
                onRetry = {
                    onNavigate(MochiNavigationIntent.ShowWeather)
                },
            )
            MochiSurface.Card -> card?.let {
                MochiGeneratedCard(
                    card = it,
                    onAction = { action -> onCardAction(it, action) },
                    onRestoreFace = {
                        onNavigate(MochiNavigationIntent.ShowFace)
                    },
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun MochiFace(
    pipelineState: ChatPipelineUiState,
    onTalk: () -> Unit,
    onToday: () -> Unit,
    onFocus: () -> Unit,
) {
    val active = pipelineState.isActive
    val accent by animateColorAsState(
        targetValue = pipelineState.stage.accentColor(),
        animationSpec = tween(420),
        label = "mochi-accent",
    )
    val transition = rememberInfiniteTransition(label = "mochi-breath")
    val breath by transition.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.018f,
        animationSpec = infiniteRepeatable(
            animation = tween(2100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mochi-breath-scale",
    )
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 300.dp, height = 250.dp)
                .semantics {
                    contentDescription = if (active) {
                        localizeUiText(
                            "Mochi is " +
                                pipelineState.stage
                                    .displayText()
                                    .lowercase(),
                        )
                    } else {
                        localizeUiText("Mochi is ready.")
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.20f),
                            accent.copy(alpha = 0.05f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = size.minDimension * 0.52f,
                    ),
                    radius = size.minDimension * 0.52f,
                )
                drawCircle(
                    color = accent.copy(alpha = 0.10f),
                    radius = size.minDimension * 0.39f,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            Box(
                modifier = Modifier
                    .size(width = 244.dp, height = 186.dp)
                    .graphicsLayer {
                        scaleX = breath
                        scaleY = breath
                    }
                    .shadow(
                        elevation = 24.dp,
                        shape = RoundedCornerShape(
                            topStart = 78.dp,
                            topEnd = 78.dp,
                            bottomStart = 68.dp,
                            bottomEnd = 68.dp,
                        ),
                        ambientColor = accent.copy(alpha = 0.34f),
                        spotColor = accent.copy(alpha = 0.42f),
                    )
                    .clip(
                        RoundedCornerShape(
                            topStart = 78.dp,
                            topEnd = 78.dp,
                            bottomStart = 68.dp,
                            bottomEnd = 68.dp,
                        ),
                    )
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFFFF8F4),
                                accent.copy(alpha = 0.88f),
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.22f),
                        radius = 50.dp.toPx(),
                        center = center.copy(
                            x = center.x - 62.dp.toPx(),
                            y = center.y - 52.dp.toPx(),
                        ),
                    )
                }
                MochiExpression(stage = pipelineState.stage)
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        AnimatedVisibility(
            visible = active,
            enter = fadeIn(tween(240)),
            exit = fadeOut(tween(160)),
        ) {
            Surface(
                color = accent.copy(alpha = 0.14f),
                shape = CircleShape,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(accent),
                    )
                    Text(
                        text = pipelineState.stage.displayText(),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = !active,
            enter = fadeIn(tween(240)),
            exit = fadeOut(tween(160)),
        ) {
            Column(
                modifier = Modifier.width(292.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Good to see you",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = "Use Talk whenever you want to chat.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onTalk,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Talk to Mochi")
                    }
                    OutlinedButton(
                        onClick = onToday,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("View today")
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onFocus,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "Focus mode",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Full screen · stays awake",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MochiGeneratedCard(
    card: CardPresentation,
    onAction: (CardAction) -> Unit,
    onRestoreFace: () -> Unit,
) {
    val accent = card.type.accentColor()
    HomeInfoBackground(
        accent = accent,
        description = card.title,
    ) {
        HomeInfoHeader(
            eyebrow = card.type.displayName().uppercase(),
            accent = accent,
            onRestoreFace = onRestoreFace,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 18.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CardPresentationContent(
                card = card,
                accent = accent,
                compact = false,
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun InlineMochiCard(
    card: CardPresentation,
    onAction: (CardAction) -> Unit,
) {
    val accent = card.type.accentColor()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = accent.copy(alpha = 0.10f),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = accent.copy(alpha = 0.28f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = card.type.displayName().uppercase(),
                color = accent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
            )
            CardPresentationContent(
                card = card,
                accent = accent,
                compact = true,
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun CardPresentationContent(
    card: CardPresentation,
    accent: Color,
    compact: Boolean,
    onAction: (CardAction) -> Unit,
) {
    MaterialText(
        text = card.title,
        color = if (compact) {
            MaterialTheme.colorScheme.onSurface
        } else {
            Color(0xFF352832)
        },
        style = if (compact) {
            MaterialTheme.typography.titleMedium
        } else {
            MaterialTheme.typography.headlineMedium
        },
        fontWeight = FontWeight.Black,
    )
    card.subtitle?.let {
        MaterialText(
            text = it,
            color = if (compact) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                Color(0xFF6F5964)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    card.hero?.let {
        MaterialText(
            text = it,
            color = if (compact) accent else Color(0xFF352832),
            style = if (compact) {
                MaterialTheme.typography.headlineSmall
            } else {
                MaterialTheme.typography.displayMedium
            },
            fontWeight = FontWeight.Black,
        )
    }
    card.body?.let {
        MaterialText(
            text = it,
            color = if (compact) {
                MaterialTheme.colorScheme.onSurface
            } else {
                Color(0xFF594650)
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (compact) 8 else Int.MAX_VALUE,
        )
    }
    if (card.metrics.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            card.metrics.take(3).forEach { metric ->
                Surface(
                    modifier = Modifier.weight(1f),
                    color = if (compact) {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                    } else {
                        Color.White.copy(alpha = 0.42f)
                    },
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MaterialText(
                            text = metric.value,
                            color = if (compact) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                Color(0xFF352832)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                        )
                        MaterialText(
                            text = metric.label,
                            color = if (compact) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                Color(0xFF6F5964)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
    card.items.take(if (compact) 4 else 8).forEach { item ->
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = if (compact) {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)
            } else {
                Color.White.copy(alpha = 0.34f)
            },
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                MaterialText(
                    text = item.title,
                    color = if (compact) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        Color(0xFF352832)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                )
                item.detail?.let {
                    MaterialText(
                        text = it,
                        color = if (compact) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            Color(0xFF6F5964)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                    )
                }
            }
        }
    }
    if (card.sources.isNotEmpty()) {
        Text(
            text = "Sources",
            color = accent,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black,
        )
        card.sources.take(if (compact) 3 else 6).forEach { source ->
            MaterialText(
                text = "\u2022 ${source.title}",
                color = if (compact) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    Color(0xFF594650)
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
        }
    }
    val visibleActions = buildList {
        addAll(card.actions)
        if (
            compact &&
            card.actions.none { it.type == CardActionType.EXPAND }
        ) {
            add(
                CardAction(
                    type = CardActionType.EXPAND,
                    label = localizeUiText("Full screen"),
                ),
            )
        }
        if (
            !compact &&
            card.actions.none { it.type == CardActionType.DISMISS }
        ) {
            add(
                CardAction(
                    type = CardActionType.DISMISS,
                    label = localizeUiText("Dismiss"),
                ),
            )
        }
    }.distinctBy(CardAction::type)
    if (visibleActions.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            visibleActions.forEach { action ->
                OutlinedButton(
                    onClick = { onAction(action) },
                    shape = RoundedCornerShape(16.dp),
                ) {
                    MaterialText(
                        text = action.label,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun CardType.displayName(): String =
    when (this) {
        CardType.DAILY_BRIEFING -> "Daily briefing"
        CardType.AGENDA_TIMELINE -> "Agenda timeline"
        CardType.TODO_FOCUS -> "Todo focus"
        CardType.CONTENT -> "Content"
        CardType.RESEARCH_SUMMARY -> "Research summary"
        CardType.COMPARISON -> "Comparison"
        CardType.INSIGHT -> "Insight"
        CardType.PROGRESS -> "Progress"
    }

private fun CardType.accentColor(): Color =
    when (this) {
        CardType.DAILY_BRIEFING -> Color(0xFFFFA56F)
        CardType.AGENDA_TIMELINE -> Color(0xFF8FAEFF)
        CardType.TODO_FOCUS -> Color(0xFF72D8C0)
        CardType.CONTENT -> Color(0xFF8EA7FF)
        CardType.RESEARCH_SUMMARY -> Color(0xFFB399FF)
        CardType.COMPARISON -> Color(0xFFFF8FB4)
        CardType.INSIGHT -> Color(0xFFFFBD70)
        CardType.PROGRESS -> Color(0xFF73BFE6)
    }

@Composable
private fun MochiDateTime(onRestoreFace: () -> Unit) {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    val isLandscape =
        LocalConfiguration.current.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now()
            delay(1_000)
        }
    }
    val accent = Color(0xFFFFA56F)
    HomeInfoBackground(
        accent = accent,
        description = "Current date and time",
    ) {
        HomeInfoHeader(
            eyebrow = "LOCAL TIME",
            accent = accent,
            onRestoreFace = onRestoreFace,
        )
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1.35f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MochiClockValue(now)
                    Text(
                        text = now.format(
                            uiDateFormatter(
                                "EEEE, MMMM d",
                                "M月d日 EEEE",
                            ),
                        ),
                        color = Color(0xFF594650),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
                Column(
                    modifier = Modifier.weight(0.65f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TimeZonePill(now)
                    Text(
                        text = "Mochi will keep this clock live for you.",
                        color = Color(0xFF6F5964),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            Spacer(modifier = Modifier.weight(0.65f))
            MochiClockValue(now)
            Text(
                text = now.format(
                    uiDateFormatter("EEEE, MMMM d", "M月d日 EEEE"),
                ),
                color = Color(0xFF594650),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(14.dp))
            TimeZonePill(now)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "Mochi will keep this clock live for you.",
                color = Color(0xFF6F5964),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MochiClockValue(now: ZonedDateTime) {
    AnimatedContent(
        targetState = now.format(
            uiDateFormatter("h:mm a", "HH:mm"),
        ),
        transitionSpec = {
            (
                slideInVertically(tween(260)) { it / 2 } +
                    fadeIn(tween(220))
                ).togetherWith(
                slideOutVertically(tween(220)) { -it / 2 } +
                    fadeOut(tween(180)),
            )
        },
        label = "clock-minute",
    ) { time ->
        Text(
            text = time,
            color = Color(0xFF352832),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TimeZonePill(now: ZonedDateTime) {
    Surface(
        color = Color.White.copy(alpha = 0.36f),
        shape = CircleShape,
    ) {
        Text(
            text = now.zone.id.replace('_', ' '),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            color = Color(0xFF6F5964),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun MochiWeather(
    state: WeatherUiState,
    onRestoreFace: () -> Unit,
    onRetry: () -> Unit,
) {
    val weather = state.weather
    val isLandscape =
        LocalConfiguration.current.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
    val accent = when (weather?.weatherCode) {
        0, 1 -> Color(0xFFFFBD70)
        2, 3, 45, 48 -> Color(0xFF91A9C7)
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 ->
            Color(0xFF73BFE6)
        71, 73, 75, 77, 85, 86 -> Color(0xFFB8DDF0)
        95, 96, 99 -> Color(0xFFA58BE8)
        else -> Color(0xFF72D8C0)
    }
    HomeInfoBackground(
        accent = accent,
        description = "Current local weather",
    ) {
        HomeInfoHeader(
            eyebrow = "RIGHT NOW",
            accent = accent,
            onRestoreFace = onRestoreFace,
        )
        Spacer(modifier = Modifier.weight(0.5f))
        when {
            state.isLoading -> {
                PipelinePulse(accent = accent)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Reading the sky",
                    color = Color(0xFF352832),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Using your approximate location",
                    color = Color(0xFF6F5964),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            weather != null && isLandscape -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WeatherSummary(
                        temperature = weather.temperatureC.roundToInt(),
                        condition = weather.condition,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        WeatherMetric(
                            label = "Feels like",
                            value =
                                "${weather.apparentTemperatureC.roundToInt()}\u00B0",
                            modifier = Modifier.weight(1f),
                        )
                        WeatherMetric(
                            label = "Humidity",
                            value = "${weather.humidityPercent}%",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            weather != null -> {
                WeatherSummary(
                    temperature = weather.temperatureC.roundToInt(),
                    condition = weather.condition,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    WeatherMetric(
                        label = "Feels like",
                        value =
                            "${weather.apparentTemperatureC.roundToInt()}\u00B0",
                        modifier = Modifier.weight(1f),
                    )
                    WeatherMetric(
                        label = "Humidity",
                        value = "${weather.humidityPercent}%",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            else -> {
                Text(
                    text = "Weather unavailable",
                    color = Color(0xFF352832),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = state.errorMessage
                        ?: "Mochi could not read the local weather.",
                    color = Color(0xFF6F5964),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                )
            }
        }
        Spacer(
            modifier = if (isLandscape) {
                Modifier.height(4.dp)
            } else {
                Modifier.weight(1f)
            },
        )
        if (weather == null && !state.isLoading) {
            TextButton(onClick = onRetry) {
                Text("Try again")
            }
        } else {
            Text(
                text = "Updated for your approximate location.",
                color = Color(0xFF6F5964),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun WeatherSummary(
    temperature: Int,
    condition: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$temperature\u00B0",
            color = Color(0xFF352832),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Black,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = condition,
                color = Color(0xFF352832),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Current location",
                color = Color(0xFF6F5964),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun WeatherMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.42f),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                color = Color(0xFF352832),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = label,
                color = Color(0xFF6F5964),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun HomeInfoBackground(
    accent: Color,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isLandscape =
        LocalConfiguration.current.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFFFFBF8),
                        accent.copy(alpha = 0.54f),
                    ),
                ),
            )
            .semantics {
                contentDescription = localizeUiText(description)
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White.copy(alpha = 0.20f),
                radius = size.minDimension * 0.52f,
                center = center.copy(
                    x = size.width * 0.88f,
                    y = size.height * 0.14f,
                ),
            )
            drawCircle(
                color = accent.copy(alpha = 0.16f),
                radius = size.minDimension * 0.43f,
                center = center.copy(
                    x = size.width * 0.08f,
                    y = size.height * 0.82f,
                ),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = 28.dp,
                    vertical = if (isLandscape) 18.dp else 26.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
        }
    }
}

@Composable
private fun HomeInfoHeader(
    eyebrow: String,
    accent: Color,
    onRestoreFace: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = eyebrow,
            color = Color(0xFF6F5964),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Box(
            modifier = Modifier
                .size(width = 66.dp, height = 50.dp)
                .shadow(
                    elevation = 10.dp,
                    shape = RoundedCornerShape(22.dp),
                    ambientColor = accent.copy(alpha = 0.28f),
                    spotColor = accent.copy(alpha = 0.34f),
                )
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFFFFFAF7),
                            accent.copy(alpha = 0.88f),
                        ),
                    ),
                )
                .clickable(onClick = onRestoreFace)
                .semantics {
                    contentDescription =
                        localizeUiText("Restore Mochi's face")
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(38.dp, 24.dp)) {
                val ink = Color(0xFF352832)
                drawCircle(
                    color = ink,
                    radius = 3.dp.toPx(),
                    center = center.copy(x = center.x - 9.dp.toPx()),
                )
                drawCircle(
                    color = ink,
                    radius = 3.dp.toPx(),
                    center = center.copy(x = center.x + 9.dp.toPx()),
                )
                drawArc(
                    color = ink,
                    startAngle = 15f,
                    sweepAngle = 150f,
                    useCenter = false,
                    topLeft = center.copy(
                        x = center.x - 8.dp.toPx(),
                        y = center.y + 2.dp.toPx(),
                    ),
                    size = androidx.compose.ui.geometry.Size(
                        16.dp.toPx(),
                        9.dp.toPx(),
                    ),
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun MochiExpression(stage: ChatPipelineStage) {
    val eyeOpenness by animateFloatAsState(
        targetValue = when (stage) {
            ChatPipelineStage.SUMMARY -> 0.28f
            ChatPipelineStage.SPEAKING -> 0.72f
            else -> 1f
        },
        animationSpec = tween(280),
        label = "mochi-eye-openness",
    )
    val eyeShift by animateFloatAsState(
        targetValue = when (stage) {
            ChatPipelineStage.SKILLING -> -5f
            ChatPipelineStage.THINKING -> 5f
            ChatPipelineStage.TOOL -> 3f
            else -> 0f
        },
        animationSpec = tween(360, easing = FastOutSlowInEasing),
        label = "mochi-eye-shift",
    )
    val speakingTransition = rememberInfiniteTransition(label = "mochi-speaking")
    val speechAmount by speakingTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(260, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mochi-speech-mouth",
    )
    Canvas(modifier = Modifier.size(width = 150.dp, height = 104.dp)) {
        val ink = Color(0xFF352832)
        val blush = Color(0xFFFF7F9E).copy(alpha = 0.28f)
        val eyeY = 31.dp.toPx()
        val leftEyeX = 43.dp.toPx() + eyeShift.dp.toPx()
        val rightEyeX = size.width - 43.dp.toPx() + eyeShift.dp.toPx()
        val eyeWidth = when (stage) {
            ChatPipelineStage.LISTENING -> 17.dp.toPx()
            else -> 14.dp.toPx()
        }
        val eyeHeight = 25.dp.toPx() * eyeOpenness

        drawOval(
            color = blush,
            topLeft = androidx.compose.ui.geometry.Offset(
                10.dp.toPx(),
                54.dp.toPx(),
            ),
            size = androidx.compose.ui.geometry.Size(
                30.dp.toPx(),
                12.dp.toPx(),
            ),
        )
        drawOval(
            color = blush,
            topLeft = androidx.compose.ui.geometry.Offset(
                size.width - 40.dp.toPx(),
                54.dp.toPx(),
            ),
            size = androidx.compose.ui.geometry.Size(
                30.dp.toPx(),
                12.dp.toPx(),
            ),
        )

        if (stage == ChatPipelineStage.THINKING) {
            drawArc(
                color = ink,
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(
                    rightEyeX - 12.dp.toPx(),
                    eyeY - 2.dp.toPx(),
                ),
                size = androidx.compose.ui.geometry.Size(
                    24.dp.toPx(),
                    14.dp.toPx(),
                ),
                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
            )
        } else {
            drawRoundRect(
                color = ink,
                topLeft = androidx.compose.ui.geometry.Offset(
                    rightEyeX - eyeWidth / 2,
                    eyeY - eyeHeight / 2,
                ),
                size = androidx.compose.ui.geometry.Size(eyeWidth, eyeHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    eyeWidth / 2,
                ),
            )
        }
        drawRoundRect(
            color = ink,
            topLeft = androidx.compose.ui.geometry.Offset(
                leftEyeX - eyeWidth / 2,
                eyeY - eyeHeight / 2,
            ),
            size = androidx.compose.ui.geometry.Size(eyeWidth, eyeHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                eyeWidth / 2,
            ),
        )

        if (eyeOpenness > 0.55f) {
            listOf(leftEyeX, rightEyeX).forEach { eyeX ->
                drawCircle(
                    color = Color.White.copy(alpha = 0.78f),
                    radius = 2.6.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(
                        eyeX - 2.dp.toPx(),
                        eyeY - 5.dp.toPx(),
                    ),
                )
            }
        }

        val mouthCenterX = size.width / 2
        val mouthY = 78.dp.toPx()
        when (stage) {
            ChatPipelineStage.SPEAKING -> {
                drawOval(
                    color = ink,
                    topLeft = androidx.compose.ui.geometry.Offset(
                        mouthCenterX - 10.dp.toPx(),
                        mouthY - 5.dp.toPx(),
                    ),
                    size = androidx.compose.ui.geometry.Size(
                        20.dp.toPx(),
                        (8.dp + 12.dp * speechAmount).toPx(),
                    ),
                )
            }
            ChatPipelineStage.LISTENING -> {
                drawCircle(
                    color = ink,
                    radius = 7.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(
                        mouthCenterX,
                        mouthY + 2.dp.toPx(),
                    ),
                )
            }
            ChatPipelineStage.THINKING,
            ChatPipelineStage.SKILLING,
            ChatPipelineStage.SUBAGENT,
            ChatPipelineStage.TOOL,
            -> {
                drawLine(
                    color = ink,
                    start = androidx.compose.ui.geometry.Offset(
                        mouthCenterX - 11.dp.toPx(),
                        mouthY,
                    ),
                    end = androidx.compose.ui.geometry.Offset(
                        mouthCenterX + 11.dp.toPx(),
                        mouthY,
                    ),
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            ChatPipelineStage.IDLE,
            ChatPipelineStage.SUMMARY,
            -> {
                val smile = Path().apply {
                    moveTo(mouthCenterX - 13.dp.toPx(), mouthY - 3.dp.toPx())
                    cubicTo(
                        mouthCenterX - 7.dp.toPx(),
                        mouthY + 10.dp.toPx(),
                        mouthCenterX + 7.dp.toPx(),
                        mouthY + 10.dp.toPx(),
                        mouthCenterX + 13.dp.toPx(),
                        mouthY - 3.dp.toPx(),
                    )
                }
                drawPath(
                    path = smile,
                    color = ink,
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun DaySurface(
    title: String,
    state: PlannerSurfaceState,
    plannerSection: PlannerSection,
    onShowToday: () -> Unit,
    onShowCalendar: () -> Unit,
    onBackToMonth: (() -> Unit)?,
    onAddTodo: (LocalDate?) -> Unit,
    onCompleteTodo: (String) -> Unit,
    onSetScheduleEnabled: (String, Boolean) -> Unit,
    onRunSchedule: (String) -> Unit,
    onRemoveSchedule: (String) -> Unit,
) {
    val date = state.date
    val activeTodos = state.todos.filter { it.status == TodoStatus.ACTIVE }
    val completedTodos = state.todos.filter {
        it.status == TodoStatus.COMPLETED
    }
    val hasCarriedTodos = activeTodos.any {
        it.scheduledDate != null &&
            date != null &&
            it.scheduledDate < date
    }
    Column(modifier = Modifier.fillMaxSize()) {
        PlannerSectionTabs(
            selected = plannerSection,
            onShowToday = onShowToday,
            onShowCalendar = onShowCalendar,
        )
        Spacer(modifier = Modifier.height(12.dp))
        onBackToMonth?.let {
            TextButton(onClick = it) {
                Text(
                    "Back to ${
                        date?.format(uiDateFormatter("MMMM", "M月"))
                    }",
                )
            }
        }
        SurfaceHeader(
            title = title,
            subtitle = date?.format(
                uiDateFormatter(
                    "EEEE, MMMM d, yyyy",
                    "yyyy年M月d日 EEEE",
                ),
            ).orEmpty(),
        )
        PlannerStatus(state)
        if (!state.isLoading) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.schedules.isNotEmpty()) {
                    item { SectionTitle("Agent schedules") }
                    items(
                        state.schedules,
                        key = AgentSchedule::id,
                    ) { schedule ->
                        AgentScheduleRow(
                            schedule = schedule,
                            onSetEnabled = onSetScheduleEnabled,
                            onRun = onRunSchedule,
                            onRemove = onRemoveSchedule,
                        )
                    }
                }
                if (state.events.isNotEmpty()) {
                    item { SectionTitle("Events") }
                    items(state.events, key = CalendarEvent::id) { event ->
                        EventRow(event)
                    }
                }
                item {
                    SectionTitle(
                        if (hasCarriedTodos) {
                            "Active · includes carry-over"
                        } else {
                            "Active"
                        },
                    )
                }
                if (activeTodos.isEmpty()) {
                    item { EmptyRow("No active todos") }
                } else {
                    items(activeTodos, key = MochiTodo::id) { todo ->
                        TodoRow(
                            todo = todo,
                            displayDate = date,
                            onCompleteTodo = onCompleteTodo,
                        )
                    }
                }
                if (completedTodos.isNotEmpty()) {
                    item { SectionTitle("Completed") }
                    items(completedTodos, key = MochiTodo::id) { todo ->
                        TodoRow(
                            todo = todo,
                            displayDate = date,
                            onCompleteTodo = onCompleteTodo,
                        )
                    }
                }
            }
        }
        Button(
            onClick = { onAddTodo(date) },
            enabled = date != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add todo")
        }
    }
}

@Composable
private fun CalendarMonthSurface(
    month: YearMonth,
    onNavigate: (MochiNavigationIntent) -> Unit,
) {
    val firstDayOffset = month.atDay(1).dayOfWeek.value - 1
    val cells = buildList<LocalDate?> {
        repeat(firstDayOffset) { add(null) }
        for (day in 1..month.lengthOfMonth()) {
            add(month.atDay(day))
        }
        while (size % 7 != 0) {
            add(null)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PlannerSectionTabs(
            selected = PlannerSection.CALENDAR,
            onShowToday = {
                onNavigate(MochiNavigationIntent.ShowToday)
            },
            onShowCalendar = {
                onNavigate(
                    MochiNavigationIntent.ShowCalendarMonth(
                        YearMonth.now(),
                    ),
                )
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    onNavigate(
                        MochiNavigationIntent.ShowCalendarMonth(
                            month.minusMonths(1),
                        ),
                    )
                },
            ) {
                Text("Previous")
            }
            Text(
                text = month.format(
                    uiDateFormatter("MMMM yyyy", "yyyy年M月"),
                ),
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            TextButton(
                onClick = {
                    onNavigate(
                        MochiNavigationIntent.ShowCalendarMonth(
                            month.plusMonths(1),
                        ),
                    )
                },
            ) {
                Text("Next")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                Text(
                    text = it,
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        cells.chunked(7).forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        )
                    } else {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clickable {
                                    onNavigate(
                                        MochiNavigationIntent.ShowCalendarDay(date),
                                    )
                                },
                            color = if (date == LocalDate.now()) {
                                Color.White
                            } else {
                                Color(0xFF202020)
                            },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = date.dayOfMonth.toString(),
                                    color = if (date == LocalDate.now()) {
                                        Color.Black
                                    } else {
                                        Color.White
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun PlannerSectionTabs(
    selected: PlannerSection,
    onShowToday: () -> Unit,
    onShowCalendar: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SkillSectionTab(
            label = "Today",
            selected = selected == PlannerSection.TODAY,
            onClick = onShowToday,
            modifier = Modifier.weight(1f),
        )
        SkillSectionTab(
            label = "Calendar",
            selected = selected == PlannerSection.CALENDAR,
            onClick = onShowCalendar,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TodoSurface(
    state: PlannerSurfaceState,
    onAddTodo: () -> Unit,
    onCompleteTodo: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SurfaceHeader(
            title = "Todo",
            subtitle = state.date?.toString() ?: "Active tasks",
        )
        PlannerStatus(state)
        if (!state.isLoading) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.todos.isEmpty()) {
                    item { EmptyRow("No matching todos") }
                } else {
                    items(state.todos, key = MochiTodo::id) { todo ->
                        TodoRow(
                            todo = todo,
                            displayDate = state.date,
                            onCompleteTodo = onCompleteTodo,
                        )
                    }
                }
            }
        }
        Button(
            onClick = onAddTodo,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add todo")
        }
    }
}

@Composable
private fun SkillsSurface(
    state: SkillsUiState,
    onSearch: (String) -> Unit,
    onLoadPopular: () -> Unit,
    onPreview: (MarketSkillSummary) -> Unit,
    onClearPreview: () -> Unit,
    onInstall: (MarketSkillSummary) -> Unit,
    onEdit: (String, String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onCheckUpdates: () -> Unit,
    onApplyUpdate: (String) -> Unit,
) {
    var explore by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selectedSkill by remember { mutableStateOf<MochiSkill?>(null) }
    var pendingDelete by remember { mutableStateOf<MochiSkill?>(null) }
    var pendingUpdate by remember { mutableStateOf<MochiSkill?>(null) }
    val installedIds = state.skills.mapTo(mutableSetOf(), MochiSkill::id)

    LaunchedEffect(explore) {
        if (explore && state.searchResults.isEmpty()) {
            onLoadPopular()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SurfaceHeader(
            title = "Skills",
            subtitle = if (explore) {
                "Explore skills.sh"
            } else {
                "Built-in and installed capabilities"
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SkillSectionTab(
                label = "Installed",
                selected = !explore,
                onClick = { explore = false },
                modifier = Modifier.weight(1f),
            )
            SkillSectionTab(
                label = "Explore",
                selected = explore,
                onClick = { explore = true },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (explore) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search skills.sh") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { onSearch(query) },
                    enabled = query.isNotBlank() && !state.isSearching,
                ) {
                    Text("Search")
                }
            }
        } else {
            OutlinedButton(
                onClick = onCheckUpdates,
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Check for updates")
            }
        }
        state.feedback?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (state.isLoading || state.isSearching) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (explore) {
                item {
                    Text(
                        text = state.marketHeading,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                itemsIndexed(
                    items = state.searchResults,
                    key = { _, skill -> skill.id },
                ) { index, skill ->
                    MarketSkillCard(
                        skill = skill,
                        rank = index + 1,
                        installed = skill.id in installedIds,
                        onView = { onPreview(skill) },
                        onInstall = { onInstall(skill) },
                    )
                }
            } else {
                items(state.skills, key = MochiSkill::id) { skill ->
                    InstalledSkillCard(
                        skill = skill,
                        onView = { selectedSkill = skill },
                        onSetEnabled = onSetEnabled,
                        onDelete = { pendingDelete = skill },
                        onApplyUpdate = { pendingUpdate = skill },
                    )
                }
            }
        }
    }

    state.preview?.let { preview ->
        MarketSkillPreviewDialog(
            skill = preview,
            installed = preview.marketId in installedIds,
            onDismiss = onClearPreview,
            onInstall = {
                state.searchResults.firstOrNull {
                    it.id == preview.marketId
                }?.let(onInstall)
                onClearPreview()
            },
        )
    }
    selectedSkill?.let { skill ->
        SkillContentDialog(
            skill = skill,
            onDismiss = { selectedSkill = null },
            onSave = { content ->
                onEdit(skill.id, content)
                selectedSkill = null
            },
        )
    }
    pendingDelete?.let { skill ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${skill.name}?") },
            text = { Text("This removes the locally installed market skill.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(skill.id)
                        pendingDelete = null
                    },
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
    pendingUpdate?.let { skill ->
        AlertDialog(
            onDismissRequest = { pendingUpdate = null },
            title = { Text("Update ${skill.name}?") },
            text = {
                Text(
                    if (skill.modified) {
                        "This skill has local edits. Updating will replace " +
                            "them with the latest skills.sh version."
                    } else {
                        "Replace the installed content with the latest " +
                            "skills.sh version."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onApplyUpdate(skill.id)
                        pendingUpdate = null
                    },
                ) {
                    Text("Update")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUpdate = null }) {
                    Text("Keep local")
                }
            },
        )
    }
}

private val MOCHI_BUILT_IN_TOOL_NAMES = setOf(
    "manage_mochi_calendar",
    "manage_mochi_todo",
    "manage_mochi_schedule",
    "get_current_location",
    "get_current_weather",
    "navigate_mochi_ui",
    "run_sandboxed_javascript",
)

@Composable
private fun BuiltInToolGroupCard(
    title: String,
    subtitle: String,
    tools: List<BuiltInToolSummary>,
    expanded: Boolean,
    disabled: Boolean,
    onToggleExpanded: () -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
) {
    PlannerCard {
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "$subtitle · ${tools.size} tools",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = onToggleExpanded,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (expanded) {
                    "Hide tools (${tools.size})"
                } else {
                    "Show tools (${tools.size})"
                },
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tools.forEach { tool ->
                    BuiltInToolRow(
                        tool = tool,
                        disabled = disabled,
                        onSetEnabled = onSetEnabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun BuiltInToolCard(
    tool: BuiltInToolSummary,
    disabled: Boolean,
    onSetEnabled: (String, Boolean) -> Unit,
) {
    PlannerCard {
        BuiltInToolRow(
            tool = tool,
            disabled = disabled,
            onSetEnabled = onSetEnabled,
        )
    }
}

@Composable
private fun BuiltInToolRow(
    tool: BuiltInToolSummary,
    disabled: Boolean,
    onSetEnabled: (String, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tool.displayName,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = tool.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
            Text(
                text = tool.name,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        Switch(
            checked = tool.enabled,
            onCheckedChange = { onSetEnabled(tool.name, it) },
            enabled = !disabled,
        )
    }
}

@Composable
private fun ToolsSurface(
    state: ToolsUiState,
    onSetBuiltInEnabled: (String, Boolean) -> Unit,
    onConnectNotion: () -> Unit,
    onDisconnectNotion: () -> Unit,
    onOpenTencentDocsTokenPage: () -> Unit,
    onConfigureTencentDocs: (String) -> Unit,
    onDisconnectTencentDocs: () -> Unit,
    onOpenAmapConsole: () -> Unit,
    onConfigureAmap: (String, String) -> Unit,
    onDisconnectAmap: () -> Unit,
    onSetAmapEnabled: (Boolean) -> Unit,
    onSetAgentBrowserEnabled: (Boolean) -> Unit,
    onAddServer: (ManualMcpServerInput) -> Unit,
    onRemoveServer: (String) -> Unit,
    onSetServerEnabled: (String, Boolean) -> Unit,
    onSetToolEnabled: (String, String, Boolean) -> Unit,
) {
    var showAddServer by remember { mutableStateOf(false) }
    var showTencentDocsToken by remember { mutableStateOf(false) }
    var showAmapCredentials by remember { mutableStateOf(false) }
    var amapToolsExpanded by rememberSaveable {
        mutableStateOf(false)
    }
    var browserToolsExpanded by rememberSaveable {
        mutableStateOf(false)
    }
    var mochiToolsExpanded by rememberSaveable {
        mutableStateOf(false)
    }
    val mochiTools = state.catalog.builtInTools.filter {
        it.name in MOCHI_BUILT_IN_TOOL_NAMES
    }
    val otherBuiltInTools = state.catalog.builtInTools.filterNot {
        it.name in MOCHI_BUILT_IN_TOOL_NAMES
    }
    Column(modifier = Modifier.fillMaxSize()) {
        SurfaceHeader(
            title = "Tools",
            subtitle = "Choose what Mochi may call",
        )
        state.feedback?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = "Built-in",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (mochiTools.isNotEmpty()) {
                item {
                    BuiltInToolGroupCard(
                        title = "Mochi Built-ins",
                        subtitle = "Calendar, todos, schedules, weather, navigation, and sandbox",
                        tools = mochiTools,
                        expanded = mochiToolsExpanded,
                        disabled = state.isLoading,
                        onToggleExpanded = {
                            mochiToolsExpanded = !mochiToolsExpanded
                        },
                        onSetEnabled = onSetBuiltInEnabled,
                    )
                }
            }
            items(
                items = otherBuiltInTools,
                key = { it.name },
            ) { tool ->
                BuiltInToolCard(
                    tool = tool,
                    disabled = state.isLoading,
                    onSetEnabled = onSetBuiltInEnabled,
                )
            }
            item {
                PlannerCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Agent Browser",
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Private per-turn WebView · " +
                                    "${state.catalog.agentBrowser.tools.size} tools",
                                color =
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        Switch(
                            checked = state.catalog.agentBrowser.enabled,
                            onCheckedChange = onSetAgentBrowserEnabled,
                            enabled = !state.isLoading,
                        )
                    }
                    TextButton(
                        onClick = {
                            browserToolsExpanded = !browserToolsExpanded
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (browserToolsExpanded) {
                                "Hide tools " +
                                    "(${state.catalog.agentBrowser.tools.size})"
                            } else {
                                "Show tools " +
                                    "(${state.catalog.agentBrowser.tools.size})"
                            },
                        )
                    }
                    AnimatedVisibility(visible = browserToolsExpanded) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            state.catalog.agentBrowser.tools.forEach { tool ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement =
                                        Arrangement.SpaceBetween,
                                    verticalAlignment =
                                        Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = tool.displayName,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Text(
                                            text = tool.description,
                                            color = MaterialTheme.colorScheme
                                                .onSurfaceVariant,
                                            style =
                                                MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                        )
                                        Text(
                                            text = tool.name,
                                            color = MaterialTheme.colorScheme
                                                .onSurfaceVariant,
                                            style =
                                                MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                    Switch(
                                        checked = tool.enabled,
                                        onCheckedChange = {
                                            onSetBuiltInEnabled(tool.name, it)
                                        },
                                        enabled =
                                            state.catalog.agentBrowser.enabled &&
                                                !state.isLoading,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                    PlannerCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Amap Maps",
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = if (state.catalog.amap.connected) {
                                        "Connected · 6 map and merchant tools"
                                    } else {
                                        "Web Service Key required"
                                    },
                                    color =
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (state.catalog.amap.connected) {
                                Switch(
                                    checked = state.catalog.amap.enabled,
                                    onCheckedChange = onSetAmapEnabled,
                                    enabled = !state.isLoading,
                                )
                            }
                        }
                        if (state.catalog.amap.connected) {
                            OutlinedButton(
                                onClick = onDisconnectAmap,
                                enabled = !state.isLoading,
                            ) {
                                Text("Disconnect")
                            }
                        } else {
                            Button(
                                onClick = { showAmapCredentials = true },
                                enabled = !state.isLoading,
                            ) {
                                Text("Configure Amap")
                            }
                        }
                        if (state.catalog.amap.tools.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    amapToolsExpanded = !amapToolsExpanded
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (amapToolsExpanded) {
                                        "Hide tools " +
                                            "(${state.catalog.amap.tools.size})"
                                    } else {
                                        "Show tools " +
                                            "(${state.catalog.amap.tools.size})"
                                    },
                                )
                            }
                            AnimatedVisibility(visible = amapToolsExpanded) {
                                Column(
                                    verticalArrangement =
                                        Arrangement.spacedBy(10.dp),
                                ) {
                                    state.catalog.amap.tools.forEach { tool ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement =
                                                Arrangement.SpaceBetween,
                                            verticalAlignment =
                                                Alignment.CenterVertically,
                                        ) {
                                            Column(
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Text(
                                                    text = tool.displayName,
                                                    style = MaterialTheme
                                                        .typography.bodyMedium,
                                                    fontWeight =
                                                        FontWeight.Medium,
                                                )
                                                Text(
                                                    text = tool.description,
                                                    color = MaterialTheme
                                                        .colorScheme
                                                        .onSurfaceVariant,
                                                    style = MaterialTheme
                                                        .typography.bodySmall,
                                                    maxLines = 2,
                                                )
                                                Text(
                                                    text = tool.name,
                                                    color = MaterialTheme
                                                        .colorScheme
                                                        .onSurfaceVariant,
                                                    style = MaterialTheme
                                                        .typography.labelSmall,
                                                    fontFamily =
                                                        FontFamily.Monospace,
                                                )
                                            }
                                            Switch(
                                                checked = tool.enabled,
                                                onCheckedChange = {
                                                    onSetBuiltInEnabled(
                                                        tool.name,
                                                        it,
                                                    )
                                                },
                                                enabled =
                                                    state.catalog.amap
                                                        .connected &&
                                                        !state.isLoading,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "MCP servers",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    OutlinedButton(
                        onClick = { showAddServer = true },
                        enabled = !state.isLoading,
                    ) {
                        Text("Add MCP")
                    }
                }
            }
            items(
                items = state.catalog.servers,
                key = McpServerSummary::id,
            ) { server ->
                McpServerCard(
                    server = server,
                    disabled = state.isLoading,
                    onConnectNotion = onConnectNotion,
                    onDisconnectNotion = onDisconnectNotion,
                    onConfigureTencentDocs = {
                        showTencentDocsToken = true
                    },
                    onDisconnectTencentDocs = onDisconnectTencentDocs,
                    onRemove = { onRemoveServer(server.id) },
                    onSetEnabled = {
                        onSetServerEnabled(server.id, it)
                    },
                    onSetToolEnabled = { remoteName, enabled ->
                        onSetToolEnabled(server.id, remoteName, enabled)
                    },
                )
            }
        }
    }
    if (showAddServer) {
        AddMcpServerDialog(
            onDismiss = { showAddServer = false },
            onConfirm = {
                onAddServer(it)
                showAddServer = false
            },
        )
    }
    if (showTencentDocsToken) {
        TencentDocsTokenDialog(
            onGetToken = onOpenTencentDocsTokenPage,
            onDismiss = { showTencentDocsToken = false },
            onConfirm = { token ->
                onConfigureTencentDocs(token)
                showTencentDocsToken = false
            },
        )
    }
    if (showAmapCredentials) {
        AmapCredentialsDialog(
            onOpenConsole = onOpenAmapConsole,
            onDismiss = { showAmapCredentials = false },
            onConfirm = { webServiceKey, securityKey ->
                onConfigureAmap(webServiceKey, securityKey)
                showAmapCredentials = false
            },
        )
    }
}

@Composable
private fun McpServerCard(
    server: McpServerSummary,
    disabled: Boolean,
    onConnectNotion: () -> Unit,
    onDisconnectNotion: () -> Unit,
    onConfigureTencentDocs: () -> Unit,
    onDisconnectTencentDocs: () -> Unit,
    onRemove: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetToolEnabled: (String, Boolean) -> Unit,
) {
    var toolsExpanded by rememberSaveable(server.id) {
        mutableStateOf(false)
    }
    PlannerCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MaterialText(
                    text = server.name,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = when {
                        server.connected -> "Connected"
                        server.authMode == McpAuthMode.OAUTH ->
                            "Authorization required"
                        server.authMode == McpAuthMode.TOKEN ->
                            "Personal token required"
                        else -> "Ready"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                MaterialText(
                    text = server.endpoint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            if (server.connected) {
                Switch(
                    checked = server.enabled,
                    onCheckedChange = onSetEnabled,
                    enabled = !disabled,
                )
            }
        }
        when (server.authMode) {
            McpAuthMode.OAUTH -> {
                if (server.connected) {
                    OutlinedButton(
                        onClick = onDisconnectNotion,
                        enabled = !disabled,
                    ) {
                        Text("Disconnect")
                    }
                } else {
                    Button(
                        onClick = onConnectNotion,
                        enabled = !disabled,
                    ) {
                        Text("Connect Notion")
                    }
                }
            }
            McpAuthMode.TOKEN -> {
                if (server.connected) {
                    OutlinedButton(
                        onClick = onDisconnectTencentDocs,
                        enabled = !disabled,
                    ) {
                        Text("Disconnect")
                    }
                } else {
                    Button(
                        onClick = onConfigureTencentDocs,
                        enabled = !disabled &&
                            server.id == TENCENT_DOCS_SERVER_ID,
                    ) {
                        Text("Configure token")
                    }
                }
            }
            McpAuthMode.NONE,
            McpAuthMode.BEARER,
            -> {
                TextButton(
                    onClick = onRemove,
                    enabled = !disabled,
                ) {
                    Text("Remove server")
                }
            }
        }
        if (server.tools.isNotEmpty()) {
            TextButton(
                onClick = { toolsExpanded = !toolsExpanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (toolsExpanded) {
                        "Hide tools (${server.tools.size})"
                    } else {
                        "Show tools (${server.tools.size})"
                    },
                )
            }
            AnimatedVisibility(visible = toolsExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    server.tools.forEach { tool ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                MaterialText(
                                    text = tool.remoteName,
                                    style =
                                        MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                MaterialText(
                                    text = tool.description.ifBlank {
                                        tool.alias
                                    },
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                    style =
                                        MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                )
                            }
                            Switch(
                                checked = tool.enabled,
                                onCheckedChange = {
                                    onSetToolEnabled(tool.remoteName, it)
                                },
                                enabled = server.connected && !disabled,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TencentDocsTokenDialog(
    onGetToken: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Tencent Docs") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Open the official Tencent Docs page, copy your " +
                        "personal MCP token, then paste it below.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = onGetToken,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Get personal token")
                }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Tencent Docs MCP token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(token) },
                enabled = token.isNotBlank(),
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun AmapCredentialsDialog(
    onOpenConsole: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var webServiceKey by remember { mutableStateOf("") }
    var securityKey by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Amap Maps") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "In the Amap console, add a key for the Web Service " +
                        "platform, not Android. Web Service keys do not need " +
                        "release or debug SHA1 fingerprints. The optional " +
                        "Security Key is not a SHA1; enter it only when " +
                        "digital signatures are enabled.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = onOpenConsole,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Amap console")
                }
                OutlinedTextField(
                    value = webServiceKey,
                    onValueChange = { webServiceKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Web Service Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = securityKey,
                    onValueChange = { securityKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Security Key (optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(webServiceKey, securityKey) },
                enabled = webServiceKey.isNotBlank(),
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun AddMcpServerDialog(
    onDismiss: () -> Unit,
    onConfirm: (ManualMcpServerInput) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var bearerToken by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add MCP server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Only public HTTPS Streamable HTTP MCP endpoints " +
                        "are accepted.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("Endpoint") },
                    placeholder = { Text("https://example.com/mcp") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = bearerToken,
                    onValueChange = { bearerToken = it },
                    label = { Text("Bearer token (optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        ManualMcpServerInput(
                            name = name,
                            endpoint = endpoint,
                            bearerToken = bearerToken.ifBlank { null },
                        ),
                    )
                },
                enabled = name.isNotBlank() && endpoint.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun SkillSectionTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(16.dp),
        border = if (selected) {
            null
        } else {
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline,
            )
        },
    ) {
        Text(
            text = label,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun InstalledSkillCard(
    skill: MochiSkill,
    onView: () -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onDelete: () -> Unit,
    onApplyUpdate: (String) -> Unit,
) {
    PlannerCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onView),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (skill.origin == SkillOrigin.BUILT_IN) {
                        Text(
                            text = skill.name,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        MaterialText(
                            text = skill.name,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (skill.origin == SkillOrigin.BUILT_IN) {
                        Text(
                            text =
                            "Built-in · " +
                                if (skill.enabled) {
                                    "Enabled · Read only"
                                } else {
                                    "Disabled · Read only"
                                },
                            color =
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        MaterialText(
                            text = "${skill.source}" +
                                if (skill.modified) " · Modified" else "",
                            color =
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = {
                            onSetEnabled(skill.id, it)
                        },
                    )
                    if (skill.updateAvailable) {
                        Text(
                            text = "Update",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            if (skill.origin == SkillOrigin.BUILT_IN) {
                Text(
                    text = skill.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                MaterialText(
                    text = skill.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onView) {
                Text("View")
            }
            if (skill.origin == SkillOrigin.MARKET) {
                if (skill.updateAvailable) {
                    Button(onClick = { onApplyUpdate(skill.id) }) {
                        Text("Update")
                    }
                }
                TextButton(onClick = onDelete) {
                    Text("Remove")
                }
            }
        }
    }
}

@Composable
private fun MarketSkillCard(
    skill: MarketSkillSummary,
    rank: Int,
    installed: Boolean,
    onView: () -> Unit,
    onInstall: () -> Unit,
) {
    PlannerCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onView),
        ) {
            MaterialText(
                text = "#$rank  ${skill.name}",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            MaterialText(
                text = skill.source,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = skill.installMetricText(),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onView) {
                Text("View")
            }
            Button(
                onClick = onInstall,
                enabled = !installed,
            ) {
                Text(if (installed) "Installed" else "Install")
            }
        }
    }
}

private fun MarketSkillSummary.heatLabel(): String =
    when (installWindow) {
        InstallWindow.LAST_24_HOURS -> when {
            installs >= 20_000 -> "Very hot"
            installs >= 10_000 -> "Hot"
            installs >= 3_000 -> "Popular"
            installs >= 1_000 -> "Growing"
            else -> "New"
        }
        InstallWindow.ALL_TIME -> when {
            installs >= 500_000 -> "Very high popularity"
            installs >= 100_000 -> "High popularity"
            installs >= 10_000 -> "Popular"
            installs >= 1_000 -> "Growing"
            else -> "New"
        }
    }

private fun MarketSkillSummary.installMetricText(): String =
    when (installWindow) {
        InstallWindow.LAST_24_HOURS ->
            "${installs} installs in 24h · ${heatLabel()}"
        InstallWindow.ALL_TIME ->
            "${installs} total installs · ${heatLabel()}"
    }

@Composable
private fun SkillContentDialog(
    skill: MochiSkill,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var content by remember(skill.id) { mutableStateOf(skill.content) }
    val readOnly = skill.origin == SkillOrigin.BUILT_IN
    var editing by remember(skill.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            if (readOnly) {
                Text(skill.name)
            } else {
                MaterialText(skill.name)
            }
        },
        text = {
            Column {
                if (readOnly) {
                    Text(
                        text = skill.description,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text =
                        "Built-in · " +
                            if (skill.enabled) {
                                "Enabled · Read only"
                            } else {
                                "Disabled · Read only"
                            },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    MaterialText(
                        text = skill.source,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (skill.enabled) "Enabled" else "Disabled",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (!readOnly) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkillSectionTab(
                            label = "Preview",
                            selected = !editing,
                            onClick = { editing = false },
                            modifier = Modifier.weight(1f),
                        )
                        SkillSectionTab(
                            label = "Edit",
                            selected = editing,
                            onClick = { editing = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                if (editing) {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        minLines = 12,
                        maxLines = 18,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    SkillMarkdownContent(content)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (editing) {
                        onSave(content)
                    } else {
                        onDismiss()
                    }
                },
            ) {
                Text(if (editing) "Save" else "Done")
            }
        },
        dismissButton = if (editing) {
            {
                TextButton(onClick = { editing = false }) {
                    Text("Preview")
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun MarketSkillPreviewDialog(
    skill: DownloadedSkill,
    installed: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(skill.name) },
        text = {
            Column {
                MaterialText(
                    text = skill.source,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                SkillMarkdownContent(skill.content)
            }
        },
        confirmButton = {
            TextButton(
                onClick = if (installed) onDismiss else onInstall,
            ) {
                Text(if (installed) "Installed" else "Install")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun SkillMarkdownContent(content: String) {
    val blocks = remember(content) { parseMarkdown(content) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = inlineMarkdown(block.text),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    fontWeight = FontWeight.Bold,
                )
                is MarkdownBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                is MarkdownBlock.ListItem -> Text(
                    text = inlineMarkdown(
                        if (block.ordered) {
                            "${block.number}. ${block.text}"
                        } else {
                            "• ${block.text}"
                        },
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
                is MarkdownBlock.Quote -> Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = inlineMarkdown(block.text),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                is MarkdownBlock.Code -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = block.text,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                }
                is MarkdownBlock.Table -> MarkdownTable(block)
                MarkdownBlock.Divider -> Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outline),
                )
            }
        }
    }
}

@Composable
private fun MarkdownTable(table: MarkdownBlock.Table) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        MarkdownTableRow(
            cells = table.headers,
            header = true,
        )
        table.rows.forEach { row ->
            MarkdownTableRow(cells = row, header = false)
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<String>,
    header: Boolean,
) {
    Row {
        cells.forEach { cell ->
            Text(
                text = inlineMarkdown(cell),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .width(150.dp)
                    .background(
                        if (header) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun inlineMarkdown(text: String) = buildAnnotatedString {
    var cursor = 0
    INLINE_MARKDOWN_PATTERN.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") -> withStyle(
                SpanStyle(fontWeight = FontWeight.Bold),
            ) {
                append(token.removeSurrounding("**"))
            }
            token.startsWith("`") -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                append(token.removeSurrounding("`"))
            }
            token.startsWith("[") -> {
                val label = token.substringAfter('[').substringBefore(']')
                withStyle(
                    SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    ),
                ) {
                    append(label)
                }
            }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

private fun parseMarkdown(content: String): List<MarkdownBlock> {
    val lines = stripFrontmatter(content).lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index].trimEnd()
        when {
            line.isBlank() -> index += 1
            line.trim().startsWith("```") -> {
                val code = mutableListOf<String>()
                index += 1
                while (
                    index < lines.size &&
                    !lines[index].trim().startsWith("```")
                ) {
                    code += lines[index]
                    index += 1
                }
                if (index < lines.size) {
                    index += 1
                }
                blocks += MarkdownBlock.Code(code.joinToString("\n"))
            }
            isMarkdownTable(lines, index) -> {
                val headers = markdownCells(line)
                index += 2
                val rows = mutableListOf<List<String>>()
                while (
                    index < lines.size &&
                    lines[index].contains('|') &&
                    lines[index].isNotBlank()
                ) {
                    rows += markdownCells(lines[index])
                    index += 1
                }
                blocks += MarkdownBlock.Table(headers, rows)
            }
            line.startsWith("### ") -> {
                blocks += MarkdownBlock.Heading(3, line.removePrefix("### "))
                index += 1
            }
            line.startsWith("## ") -> {
                blocks += MarkdownBlock.Heading(2, line.removePrefix("## "))
                index += 1
            }
            line.startsWith("# ") -> {
                blocks += MarkdownBlock.Heading(1, line.removePrefix("# "))
                index += 1
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                blocks += MarkdownBlock.ListItem(
                    text = line.drop(2),
                    ordered = false,
                    number = null,
                )
                index += 1
            }
            ORDERED_LIST_PATTERN.matches(line) -> {
                val match = ORDERED_LIST_PATTERN.matchEntire(line)!!
                blocks += MarkdownBlock.ListItem(
                    text = match.groupValues[2],
                    ordered = true,
                    number = match.groupValues[1].toInt(),
                )
                index += 1
            }
            line.startsWith("> ") -> {
                blocks += MarkdownBlock.Quote(line.removePrefix("> "))
                index += 1
            }
            line.trim() in setOf("---", "***", "___") -> {
                blocks += MarkdownBlock.Divider
                index += 1
            }
            else -> {
                val paragraph = mutableListOf(line.trim())
                index += 1
                while (
                    index < lines.size &&
                    lines[index].isNotBlank() &&
                    !startsMarkdownBlock(lines, index)
                ) {
                    paragraph += lines[index].trim()
                    index += 1
                }
                blocks += MarkdownBlock.Paragraph(paragraph.joinToString(" "))
            }
        }
    }
    return blocks
}

private fun stripFrontmatter(content: String): String {
    val normalized = content.trim()
    if (!normalized.startsWith("---\n")) {
        return normalized
    }
    val end = normalized.indexOf("\n---", startIndex = 4)
    return if (end < 0) normalized else normalized.substring(end + 4).trim()
}

private fun isMarkdownTable(
    lines: List<String>,
    index: Int,
): Boolean =
    index + 1 < lines.size &&
        lines[index].contains('|') &&
        TABLE_DIVIDER_PATTERN.matches(lines[index + 1].trim())

private fun startsMarkdownBlock(
    lines: List<String>,
    index: Int,
): Boolean {
    val line = lines[index].trim()
    return line.startsWith('#') ||
        line.startsWith("- ") ||
        line.startsWith("* ") ||
        line.startsWith("> ") ||
        line.startsWith("```") ||
        ORDERED_LIST_PATTERN.matches(line) ||
        isMarkdownTable(lines, index)
}

private fun markdownCells(line: String): List<String> =
    line.trim()
        .removePrefix("|")
        .removeSuffix("|")
        .split('|')
        .map(String::trim)

private val INLINE_MARKDOWN_PATTERN = Regex(
    """(\*\*[^*]+\*\*|`[^`]+`|\[[^\]]+]\([^)]+\))""",
)
private val ORDERED_LIST_PATTERN = Regex("""(\d+)\.\s+(.+)""")
private val TABLE_DIVIDER_PATTERN = Regex(
    """\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?""",
)
private fun uiDateFormatter(
    englishPattern: String,
    chinesePattern: String,
): DateTimeFormatter {
    val locale = AppLanguage.resolveContentLocale()
    return DateTimeFormatter.ofPattern(
        if (locale.language == Locale.CHINESE.language) {
            chinesePattern
        } else {
            englishPattern
        },
        locale,
    )
}

@Composable
private fun PlannerStatus(state: PlannerSurfaceState) {
    when {
        state.isLoading -> Box(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White)
        }
        state.errorMessage != null -> Text(
            text = state.errorMessage,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun AgentScheduleRow(
    schedule: AgentSchedule,
    onSetEnabled: (String, Boolean) -> Unit,
    onRun: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val nextRun = schedule.nextRunAt?.let { instant ->
        DateTimeFormatter.ofPattern("MMM d · HH:mm")
            .withZone(schedule.timezone)
            .format(instant)
    }
    PlannerCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    color = Color(0xFF8C6CE8).copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = "AGENT",
                        color = Color(0xFFB9A4FF),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(
                            horizontal = 8.dp,
                            vertical = 3.dp,
                        ),
                    )
                }
                MaterialText(
                    text = schedule.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                MaterialText(
                    text = schedule.prompt,
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                )
                Text(
                    text = when {
                        !schedule.enabled -> "Paused"
                        schedule.lastResult == AgentScheduleResult.FAILED ->
                            "Last run failed · Next ${nextRun ?: "pending"}"
                        else -> "Next ${nextRun ?: "pending"}"
                    },
                    color = if (
                        schedule.lastResult == AgentScheduleResult.FAILED
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        Color(0xFFB9A4FF)
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Switch(
                checked = schedule.enabled,
                onCheckedChange = {
                    onSetEnabled(schedule.id, it)
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { onRun(schedule.id) }) {
                Text("Run now")
            }
            TextButton(onClick = { onRemove(schedule.id) }) {
                Text("Delete")
            }
        }
    }
}

@Composable
private fun EventRow(event: CalendarEvent) {
    val formatter = DateTimeFormatter.ofPattern("HH:mm")
        .withZone(event.timezone)
    PlannerCard {
        MaterialText(
            text = event.title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = if (event.allDay) {
                "All day"
            } else {
                formatter.format(event.startAt)
            },
            color = Color.LightGray,
        )
        event.location?.let {
            MaterialText(text = it, color = Color.Gray)
        }
    }
}

@Composable
private fun TodoRow(
    todo: MochiTodo,
    displayDate: LocalDate?,
    onCompleteTodo: (String) -> Unit,
) {
    val carriedFrom = todo.scheduledDate?.takeIf {
        todo.status == TodoStatus.ACTIVE &&
            displayDate != null &&
            it < displayDate
    }
    PlannerCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MaterialText(
                    text = todo.content,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = carriedFrom?.let {
                        "Carried from ${
                            it.format(uiDateFormatter("MMM d", "M月d日"))
                        }"
                    } ?: todo.priority.name.lowercase(),
                    color = if (carriedFrom != null) {
                        Color(0xFFFFBD70)
                    } else {
                        Color.Gray
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (todo.status == TodoStatus.ACTIVE) {
                OutlinedButton(onClick = { onCompleteTodo(todo.id) }) {
                    Text("Done")
                }
            } else {
                Text(text = "Completed", color = Color.LightGray)
            }
        }
    }
}

@Composable
private fun PlannerCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun SurfaceHeader(
    title: String,
    subtitle: String,
) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    if (subtitle.isNotEmpty()) {
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        color = Color.LightGray,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun EmptyRow(message: String) {
    Text(
        text = message,
        color = Color.Gray,
        modifier = Modifier.padding(vertical = 20.dp),
    )
}

@Composable
private fun ConversationSurface(
    state: ConversationUiState,
    providerReady: Boolean,
    voiceState: VoiceRuntimeState,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onCardAction: (CardPresentation, CardAction) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(18.dp),
            ) {
                Box(
                    modifier = Modifier.size(52.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "M",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Talk to Mochi",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = "Voice or text",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Surface(
                color = if (providerReady) {
                    Color(0xFF72D8C0).copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                },
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = if (providerReady) "Ready" else "Setup",
                    color = if (providerReady) {
                        Color(0xFF72D8C0)
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        if (!providerReady) {
            Surface(
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = "Connect an AI provider in Settings to start chatting.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.messages.isEmpty()) {
                item {
                    ConversationEmptyState()
                }
            }
            items(state.messages, key = ConversationMessage::id) { message ->
                ConversationBubble(
                    message = message,
                    onCardAction = onCardAction,
                )
            }
        }
        val errorMessage = state.errorMessage ?: voiceState.errorMessage
        errorMessage?.let {
            ConversationError(
                message = it,
                onOpenSpeechSettings = onOpenSpeechSettings
                    .takeIf { voiceState.offerSpeechSettings },
            )
        }
        if (voiceState.partialTranscript.isNotEmpty()) {
            Surface(
                color = Color(0xFF72D8C0).copy(alpha = 0.12f),
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color(0xFF72D8C0),
                        strokeWidth = 2.dp,
                    )
                    Column {
                        Text(
                            text = "Listening",
                            color = Color(0xFF72D8C0),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        MaterialText(
                            text = voiceState.partialTranscript,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Ask Mochi anything...") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSending && !voiceState.isListening,
                    minLines = 1,
                    maxLines = 4,
                    shape = RoundedCornerShape(18.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (voiceState.isListening) {
                        Button(
                            onClick = onStopVoice,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Stop listening")
                        }
                    } else {
                        OutlinedButton(
                            onClick = onStartVoice,
                            enabled =
                                providerReady &&
                                    voiceState.recognitionAvailable &&
                                    !state.isSending,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Speak")
                        }
                    }
                    if (state.isSending) {
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Cancel")
                        }
                    } else {
                        Button(
                            onClick = {
                                val message = input
                                input = ""
                                onSend(message)
                            },
                            enabled = providerReady && input.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Send")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            shape = CircleShape,
        ) {
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "M",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                )
            }
        }
        Text(
            text = "What are we doing today?",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Speak naturally or type a message below.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ConversationBubble(
    message: ConversationMessage,
    onCardAction: (CardPresentation, CardAction) -> Unit,
) {
    val isUser = message.role == ConversationRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) {
            Arrangement.End
        } else {
            Arrangement.Start
        },
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!isUser) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                shape = CircleShape,
            ) {
                Box(
                    modifier = Modifier.size(30.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "M",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(
            modifier = Modifier.fillMaxWidth(0.82f),
            horizontalAlignment = if (isUser) {
                Alignment.End
            } else {
                Alignment.Start
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isUser) "You" else "Mochi",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                MaterialText(
                    text = CONVERSATION_TIMESTAMP_FORMATTER.format(
                        message.sentAt.atZone(ZoneId.systemDefault()),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = 0.68f,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            val card = message.card
            if (isUser || card == null) {
                Surface(
                    color = if (isUser) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = if (isUser) {
                        RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
                    } else {
                        RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)
                    },
                ) {
                    MaterialText(
                        text = message.text,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 12.dp,
                        ),
                    )
                }

            } else {
                InlineMochiCard(
                    card = card,
                    onAction = { action ->
                        onCardAction(card, action)
                    },
                )
            }
        }
    }
}

private val CONVERSATION_TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
private fun ConversationError(
    message: String,
    onOpenSpeechSettings: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            onOpenSpeechSettings?.let { openSettings ->
                TextButton(onClick = openSettings) {
                    Text("Set up speech recognition")
                }
            }
        }
    }
}

@Composable
private fun ProviderSettingsSurface(
    state: ProviderSettingsUiState,
    speechState: SpeechSettingsUiState,
    providerShareState: ProviderShareUiState,
    agentSettingsState: AgentSettingsUiState,
    personaState: PersonaUiState,
    wakeState: WakeRuntimeState,
    wakeFeedback: String?,
    onEnableWake: () -> Unit,
    onDisableWake: () -> Unit,
    onSave: (ProviderSettingsInput) -> Unit,
    onSaveSpeech: (SpeechSettingsInput) -> Unit,
    onCreateProviderShareLink: () -> Unit,
    onReceiveProviderShareLink: (String) -> Unit,
    onSetRecentConversationTurns: (Int) -> Unit,
    onSetFocusStandby: (Boolean, Int) -> Unit,
    onSavePersona: (String, String, String) -> Unit,
) {
    val summary = state.summary
    var providerType by remember(summary) {
        mutableStateOf(
            if (summary.isReady) {
                summary.providerType
            } else {
                ProviderType.AZURE_OPENAI
            },
        )
    }
    var endpoint by remember(summary) { mutableStateOf(summary.endpoint) }
    var model by remember(summary) { mutableStateOf(summary.model) }
    var apiVersion by remember(summary) {
        mutableStateOf(summary.apiVersion)
    }
    var timeout by remember(summary) {
        mutableStateOf(summary.timeoutSeconds.toString())
    }
    var recentTurns by remember(agentSettingsState.settings) {
        mutableStateOf(
            agentSettingsState.settings.recentConversationTurns.toString(),
        )
    }
    var focusStandbyEnabled by remember(agentSettingsState.settings) {
        mutableStateOf(agentSettingsState.settings.focusStandbyEnabled)
    }
    var focusStandbyDelaySeconds by remember(agentSettingsState.settings) {
        mutableIntStateOf(
            agentSettingsState.settings.focusStandbyDelaySeconds,
        )
    }
    var soul by remember(personaState.context) {
        mutableStateOf(personaState.context.soul)
    }
    var user by remember(personaState.context) {
        mutableStateOf(personaState.context.user)
    }
    var agents by remember(personaState.context) {
        mutableStateOf(personaState.context.agents)
    }
    var apiKeyReplacement by remember(summary) { mutableStateOf("") }
    val speechSummary = speechState.summary
    var speechProvider by remember(speechSummary) {
        mutableStateOf(speechSummary.provider)
    }
    var iFlytekAppId by remember(speechSummary) {
        mutableStateOf(speechSummary.iFlytekAppId)
    }
    var iFlytekApiKey by remember(speechSummary) { mutableStateOf("") }
    var iFlytekApiSecret by remember(speechSummary) { mutableStateOf("") }
    var azureSpeechEndpoint by remember(speechSummary) {
        mutableStateOf(speechSummary.azureEndpoint)
    }
    var azureSpeechApiKey by remember(speechSummary) {
        mutableStateOf("")
    }
    var showReceiveProviders by remember { mutableStateOf(false) }
    var receivedProviderLink by remember { mutableStateOf("") }
    val context = LocalContext.current
    LocalConfiguration.current
    val appLanguage = AppLanguage.current()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SurfaceHeader(
            title = "Mochi settings",
            subtitle = "Configure persona, speech, and AI connections independently.",
        )
        val appLanguageSection: LazyListScope.() -> Unit = {
            item {
                Text(
                    text = "App language",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Chinese system languages use Chinese; " +
                            "all other system languages use English.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppLanguage.entries.forEach { language ->
                            if (language == appLanguage) {
                                Button(
                                    onClick = { language.apply(context) },
                                ) {
                                    Text(language.displayName())
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { language.apply(context) },
                                ) {
                                    Text(language.displayName())
                                }
                            }
                        }
                    }
                }
            }
        }
        val providerShareSection: LazyListScope.() -> Unit = {
            item {
                Text(
                    text = "Share Providers",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                PlannerCard {
                    Text(
                        text = "Creates an encrypted link containing the " +
                            "current LLM and speech Provider credentials. " +
                            "The decryption key is part of the link, so anyone " +
                            "who receives or copies it can use those API " +
                            "resources and consume their quota.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "The link does not include persona, memories, " +
                            "Tools credentials, planner data, or Android " +
                            "permissions.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    providerShareState.feedback?.let {
                        Text(
                            text = it,
                            color = if (it == "Shared Providers imported") {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onCreateProviderShareLink,
                            enabled = !providerShareState.isWorking &&
                                state.summary.isReady &&
                                speechState.summary.isReady,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                if (providerShareState.isWorking) {
                                    "Preparing..."
                                } else {
                                    "Share Providers"
                                },
                            )
                        }
                        OutlinedButton(
                            onClick = { showReceiveProviders = true },
                            enabled = !providerShareState.isWorking,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Receive Providers")
                        }
                    }
                }
            }
        }
        val personaSection: LazyListScope.() -> Unit = {
            item {
                Text(
                    text = "Mochi persona",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                PlannerCard {
                    Text(
                        text = "Local prompt files can be edited before " +
                            "connecting any AI provider.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = soul,
                        onValueChange = { soul = it },
                        label = { Text("SOUL.md") },
                        supportingText = {
                            Text("Identity, values, and communication style.")
                        },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                    )
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("USER.md") },
                        supportingText = {
                            Text("Stable user facts and preferences.")
                        },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                    )
                    OutlinedTextField(
                        value = agents,
                        onValueChange = { agents = it },
                        label = { Text("AGENTS.md") },
                        supportingText = {
                            Text("Operational rules for Mochi.")
                        },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                    )
                    personaState.feedback?.let {
                        Text(
                            text = it,
                            color =
                                if (it == "Persona files saved") {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                        )
                    }
                    OutlinedButton(
                        onClick = { onSavePersona(soul, user, agents) },
                        enabled = !personaState.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Save persona files")
                    }
                }
            }
        }
        val focusStandbySection: LazyListScope.() -> Unit = {
            item {
                Text(
                    text = "Fullscreen standby",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                PlannerCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Low-power standby",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "After Focus is idle, show a dim " +
                                    "Mochi, date, and time on pure black.",
                                color =
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = focusStandbyEnabled,
                            onCheckedChange = { enabled ->
                                focusStandbyEnabled = enabled
                                onSetFocusStandby(
                                    enabled,
                                    focusStandbyDelaySeconds,
                                )
                            },
                        )
                    }
                    if (focusStandbyEnabled) {
                        Text(
                            text = "Enter standby after",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FOCUS_STANDBY_DELAY_OPTIONS_SECONDS.forEach {
                                    delaySeconds ->
                                val label = focusStandbyDelayLabel(delaySeconds)
                                if (
                                    delaySeconds ==
                                    focusStandbyDelaySeconds
                                ) {
                                    Button(onClick = { }) {
                                        Text(label)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            focusStandbyDelaySeconds =
                                                delaySeconds
                                            onSetFocusStandby(
                                                focusStandbyEnabled,
                                                delaySeconds,
                                            )
                                        },
                                    ) {
                                        Text(label)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val speechSection: LazyListScope.() -> Unit = {
            item {
                Text(
                    text = "Speech recognition",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                PlannerCard {
                    Text(
                        text = "Optional: Android speech recognition is used " +
                            "when no cloud provider is configured. It may be " +
                            "unstable on some phones, so you might need to try " +
                            "a voice request more than once.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ProviderOptionCard(
                        title = "Android default",
                        detail = "No setup · device service may be unstable",
                        selected =
                            speechProvider == SpeechProvider.SYSTEM,
                        onClick = {
                            speechProvider = SpeechProvider.SYSTEM
                        },
                    )
                    ProviderOptionCard(
                        title = "iFlytek Speech",
                        detail = "Recommended for speech recognition in China",
                        selected =
                            speechProvider == SpeechProvider.IFLYTEK,
                        onClick = {
                            speechProvider = SpeechProvider.IFLYTEK
                        },
                    )
                    ProviderOptionCard(
                        title = "Azure Speech",
                        detail = "Azure Speech-to-Text short audio API",
                        selected =
                            speechProvider == SpeechProvider.AZURE,
                        onClick = {
                            speechProvider = SpeechProvider.AZURE
                        },
                    )
                    if (speechProvider == SpeechProvider.IFLYTEK) {
                        OutlinedTextField(
                            value = iFlytekAppId,
                            onValueChange = { iFlytekAppId = it },
                            label = { Text("iFlytek AppID") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                        )
                        OutlinedTextField(
                            value = iFlytekApiKey,
                            onValueChange = { iFlytekApiKey = it },
                            label = { Text("iFlytek APIKey") },
                            placeholder = {
                                if (speechSummary.hasIFlytekApiKey) {
                                    Text("*****")
                                }
                            },
                            supportingText = {
                                Text(
                                    if (speechSummary.hasIFlytekApiKey) {
                                        "Leave blank to keep the stored key."
                                    } else {
                                        "Encrypted using Android Keystore."
                                    },
                                )
                            },
                            visualTransformation =
                                PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                        )
                        OutlinedTextField(
                            value = iFlytekApiSecret,
                            onValueChange = { iFlytekApiSecret = it },
                            label = { Text("iFlytek APISecret") },
                            placeholder = {
                                if (speechSummary.hasIFlytekApiSecret) {
                                    Text("*****")
                                }
                            },
                            supportingText = {
                                Text(
                                    if (
                                        speechSummary
                                            .hasIFlytekApiSecret
                                    ) {
                                        "Leave blank to keep the stored secret."
                                    } else {
                                        "Encrypted using Android Keystore."
                                    },
                                )
                            },
                            visualTransformation =
                                PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                        )
                        OutlinedButton(
                            onClick = {
                                openExternalPage(
                                    context,
                                    IFLYTEK_SPEECH_SIGNUP_URL,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Open iFlytek registration")
                        }
                    }
                    if (speechProvider == SpeechProvider.AZURE) {
                        OutlinedTextField(
                            value = azureSpeechEndpoint,
                            onValueChange = { azureSpeechEndpoint = it },
                            label = { Text("Azure Speech endpoint") },
                            placeholder = {
                                Text(
                                    "https://your-resource.cognitiveservices.azure.com",
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                        )
                        OutlinedTextField(
                            value = azureSpeechApiKey,
                            onValueChange = { azureSpeechApiKey = it },
                            label = { Text("Azure Speech key") },
                            placeholder = {
                                if (speechSummary.hasAzureApiKey) {
                                    Text("*****")
                                }
                            },
                            supportingText = {
                                Text(
                                    if (speechSummary.hasAzureApiKey) {
                                        "Leave blank to keep the stored key."
                                    } else {
                                        "Encrypted using Android Keystore."
                                    },
                                )
                            },
                            visualTransformation =
                                PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                        )
                        OutlinedButton(
                            onClick = {
                                openExternalPage(
                                    context,
                                    AZURE_SPEECH_SIGNUP_URL,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Open Azure Speech setup")
                        }
                    }
                    speechState.feedback?.let {
                        Text(
                            text = it,
                            color = if (it == "Speech settings saved") {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    Button(
                        onClick = {
                            onSaveSpeech(
                                SpeechSettingsInput(
                                    provider = speechProvider,
                                    iFlytekAppId = iFlytekAppId,
                                    iFlytekApiKeyReplacement =
                                        iFlytekApiKey,
                                    iFlytekApiSecretReplacement =
                                        iFlytekApiSecret,
                                    azureEndpoint = azureSpeechEndpoint,
                                    azureApiKeyReplacement =
                                        azureSpeechApiKey,
                                ),
                            )
                            iFlytekApiKey = ""
                            iFlytekApiSecret = ""
                            azureSpeechApiKey = ""
                        },
                        enabled =
                            !speechState.isLoading &&
                                !speechState.isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (speechState.isSaving) {
                                "Saving..."
                            } else {
                                "Save speech recognition"
                            },
                        )
                    }
                }
            }
        }
        val llmSection: LazyListScope.() -> Unit = {
            item {
                Text(
                    text = "AI provider",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderOptionCard(
                        title = "Azure OpenAI",
                        detail = "Azure resource endpoint + deployment + API key",
                        selected = providerType == ProviderType.AZURE_OPENAI,
                        onClick = {
                            providerType = ProviderType.AZURE_OPENAI
                            if (endpoint == OPENAI_ENDPOINT) {
                                endpoint = ""
                            }
                            apiVersion = apiVersion.ifBlank {
                                DEFAULT_AZURE_API_VERSION
                            }
                        },
                    )
                    ProviderOptionCard(
                        title = "OpenAI",
                        detail = "api.openai.com with a model name",
                        selected = providerType == ProviderType.OPENAI,
                        onClick = {
                            providerType = ProviderType.OPENAI
                            if (endpoint.isBlank()) {
                                endpoint = OPENAI_ENDPOINT
                            }
                        },
                    )
                    ProviderOptionCard(
                        title = "Custom compatible API",
                        detail = "Any OpenAI-compatible /chat/completions API",
                        selected = providerType == ProviderType.CUSTOM,
                        onClick = { providerType = ProviderType.CUSTOM },
                    )
                }
            }
            item {
                Text(
                    text = "Connection details",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = {
                        Text(
                            if (providerType == ProviderType.AZURE_OPENAI) {
                                "Azure resource endpoint"
                            } else {
                                "API endpoint"
                            },
                        )
                    },
                    placeholder = {
                        Text(
                            if (providerType == ProviderType.AZURE_OPENAI) {
                                "https://your-resource.openai.azure.com"
                            } else {
                                OPENAI_ENDPOINT
                            },
                        )
                    },
                    supportingText = {
                        Text(
                            if (providerType == ProviderType.AZURE_OPENAI) {
                                "Azure Portal → Azure OpenAI → Keys and Endpoint"
                            } else {
                                "Mochi appends /chat/completions when needed."
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                )
            }
            item {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = {
                        Text(
                            if (providerType == ProviderType.AZURE_OPENAI) {
                                "Deployment name"
                            } else {
                                "Model name"
                            },
                        )
                    },
                    placeholder = {
                        Text(
                            if (providerType == ProviderType.AZURE_OPENAI) {
                                "Your Azure deployment name"
                            } else {
                                "gpt-4.1-mini"
                            },
                        )
                    },
                    supportingText = {
                        if (providerType == ProviderType.AZURE_OPENAI) {
                            Text(
                                "Use the deployment name, not the base model name.",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                )
            }
            if (providerType == ProviderType.AZURE_OPENAI) {
                item {
                    OutlinedTextField(
                        value = apiVersion,
                        onValueChange = { apiVersion = it },
                        label = { Text("Azure API version") },
                        supportingText = {
                            Text("Default: $DEFAULT_AZURE_API_VERSION")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = apiKeyReplacement,
                    onValueChange = { apiKeyReplacement = it },
                    label = { Text("API key") },
                    placeholder = {
                        if (summary.hasApiKey) {
                            Text("*****")
                        }
                    },
                    supportingText = {
                        Text(
                            if (summary.hasApiKey) {
                                "Leave blank to keep the stored key."
                            } else {
                                "Encrypted using Android Keystore."
                            },
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                )
            }
            item {
                Text(
                    text = "Conversation context",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                OutlinedTextField(
                    value = recentTurns,
                    onValueChange = {
                        recentTurns = it.filter(Char::isDigit).take(2)
                    },
                    label = { Text("Recent conversation turns") },
                    supportingText = {
                        Text("Default 20; allowed range 1-50.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        onSetRecentConversationTurns(
                            recentTurns.toIntOrNull() ?: 0,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save conversation context")
                }
            }
            item {
                OutlinedTextField(
                    value = timeout,
                    onValueChange = { timeout = it.filter(Char::isDigit) },
                    label = { Text("Timeout seconds") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                )
            }
            item {
                PlannerCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Wake word",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (wakeState.enabled) {
                                    "Hi Mochi is ${wakeState.status.name.lowercase()}"
                                } else {
                                    "Say Hi Mochi hands-free"
                                },
                                color =
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = if (wakeState.enabled) {
                                onDisableWake
                            } else {
                                onEnableWake
                            },
                        ) {
                            Text(if (wakeState.enabled) "Disable" else "Enable")
                        }
                    }
                    wakeState.errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    wakeFeedback?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            providerShareSection()
            llmSection()
            speechSection()
            appLanguageSection()
            personaSection()
            focusStandbySection()
        }
        (
            state.feedback
                ?: agentSettingsState.feedback
        )?.let {
            Text(
                text = it,
                color = if (
                    it == "Provider settings saved" ||
                    it == "Agent context settings saved" ||
                    it == "Fullscreen standby settings saved"
                ) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        Button(
            onClick = {
                onSave(
                    ProviderSettingsInput(
                        providerType = providerType,
                        endpoint = endpoint,
                        model = model,
                        apiVersion = apiVersion,
                        timeoutSeconds = timeout.toIntOrNull() ?: 0,
                        maxResponseBytes = summary.maxResponseBytes,
                        apiKeyReplacement = apiKeyReplacement,
                    ),
                )
                apiKeyReplacement = ""
            },
            enabled = !state.isLoading && !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.isSaving) {
                    "Saving..."
                } else if (summary.isReady) {
                    "Save connection"
                } else {
                    "Connect Mochi"
                },
            )
        }
    }
    if (showReceiveProviders) {
        AlertDialog(
            onDismissRequest = {
                showReceiveProviders = false
                receivedProviderLink = ""
            },
            title = { Text("Receive Providers") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Paste the complete Mochi Provider link received from " +
                            "someone you trust. Importing it will replace this " +
                            "device's current LLM and speech Providers.",
                    )
                    OutlinedTextField(
                        value = receivedProviderLink,
                        onValueChange = {
                            receivedProviderLink = it.trim().take(16_384)
                        },
                        label = { Text("Mochi Provider link") },
                        placeholder = {
                            Text("mochi://provider/import#v1...")
                        },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onReceiveProviderShareLink(receivedProviderLink)
                        showReceiveProviders = false
                        receivedProviderLink = ""
                    },
                    enabled = receivedProviderLink.startsWith(
                        "mochi://provider/import#v1.",
                    ),
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showReceiveProviders = false
                        receivedProviderLink = ""
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun AppLanguage.displayName(): String =
    when (this) {
        AppLanguage.SYSTEM -> "Follow system"
        AppLanguage.CHINESE -> "Chinese"
        AppLanguage.ENGLISH -> "English"
    }

@Composable
private fun ProviderOptionCard(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(18.dp),
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.primary,
            )
        } else {
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline,
            )
        },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onPrimary),
                    )
                }
            }
            Column {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private const val OPENAI_ENDPOINT = "https://api.openai.com/v1"
private const val IFLYTEK_SPEECH_SIGNUP_URL =
    "https://www.xfyun.cn/services/voicedictation"
private const val AZURE_SPEECH_SIGNUP_URL =
    "https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices"
private const val FOCUS_STANDBY_BRIGHTNESS = 0.03f
private val FOCUS_STANDBY_DELAY_OPTIONS_SECONDS =
    ALLOWED_FOCUS_STANDBY_DELAYS_SECONDS.sorted()
private val STANDBY_PRIMARY_COLOR = Color(0xFFD8D8D8)
private val STANDBY_DATE_COLOR = Color(0xFFA8A8A8)
private val STANDBY_OUTLINE_COLOR = Color(0xFF5A5A5A)
private val STANDBY_FACE_FILL_COLOR = Color(0xFF111111)
private val STANDBY_CHEEK_COLOR = Color(0xFF696969)
private val STANDBY_NOSE_COLOR = Color(0xFFB0B0B0)
private val STANDBY_MOUTH_COLOR = Color(0xFFC8C8C8)
private val STANDBY_MOUTH_HIGHLIGHT_COLOR = Color(0xFF888888)
private val STANDBY_SLEEP_COLOR = Color(0xFF909090)

private fun focusStandbyDelayLabel(delaySeconds: Int): String =
    if (delaySeconds < 60) {
        "$delaySeconds sec"
    } else {
        "${delaySeconds / 60} min"
    }

private fun openExternalPage(
    context: android.content.Context,
    url: String,
) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            localizeUiText("No browser is available"),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

@Composable
private fun AddTodoDialog(
    scheduledDate: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var content by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add todo") },
        text = {
            Column {
                scheduledDate?.let {
                    Text(
                        text = "Scheduled for $it",
                        color = Color.Gray,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Task") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(content.trim()) },
                enabled = content.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun NavigationBar(
    surface: MochiSurface,
    onNavigate: (MochiNavigationIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MochiNavigationItem(
                label = "Home",
                selected =
                    surface == MochiSurface.Face ||
                        surface == MochiSurface.DateTime ||
                        surface == MochiSurface.Weather ||
                        surface == MochiSurface.Card,
                onClick = { onNavigate(MochiNavigationIntent.ShowFace) },
                modifier = Modifier.weight(1f),
            )
            MochiNavigationItem(
                label = "Talk",
                selected = surface == MochiSurface.Conversation,
                onClick = {
                    onNavigate(MochiNavigationIntent.ShowConversation)
                },
                modifier = Modifier.weight(1f),
            )
            MochiNavigationItem(
                label = "Planner",
                selected =
                    surface == MochiSurface.Today ||
                    surface is MochiSurface.CalendarMonth ||
                        surface is MochiSurface.CalendarDay ||
                        surface is MochiSurface.Todo,
                onClick = { onNavigate(MochiNavigationIntent.ShowToday) },
                modifier = Modifier.weight(1f),
            )
            MochiNavigationItem(
                label = "Skills",
                selected = surface == MochiSurface.Skills,
                onClick = { onNavigate(MochiNavigationIntent.ShowSkills) },
                modifier = Modifier.weight(1f),
            )
            MochiNavigationItem(
                label = "Tools",
                selected = surface == MochiSurface.Tools,
                onClick = { onNavigate(MochiNavigationIntent.ShowTools) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MochiNavigationItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}
