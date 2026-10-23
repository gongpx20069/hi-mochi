package com.example.mochi_pet.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mochi_pet.core.browser.UserAssistanceAnswer
import com.example.mochi_pet.core.browser.UserAssistanceRequest
import com.example.mochi_pet.core.browser.BrowserLoginKind
import com.example.mochi_pet.core.browser.browserOrigin
import com.example.mochi_pet.platform.browser.AgentBrowserException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
internal fun BrowserExperience(viewModel: MochiHomeViewModel) {
    val request by viewModel.assistanceRequest.collectAsStateWithLifecycle()
    val state by viewModel.browserState.collectAsStateWithLifecycle()
    val feedback by viewModel.assistanceFeedback.collectAsStateWithLifecycle()
    val saveRequest by viewModel.credentialSaveRequest.collectAsStateWithLifecycle()
    val manualVisible by viewModel.manualBrowserVisible.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pending = request
    DisposableEffect(pending?.id, lifecycle) {
        fun speak() { pending?.let { viewModel.speakAssistance(it.id) } }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) speak()
            if (event == Lifecycle.Event.ON_PAUSE) pending?.let { viewModel.stopAssistanceSpeech(it.id) }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) speak()
        onDispose {
            lifecycle.removeObserver(observer)
            pending?.let { viewModel.stopAssistanceSpeech(it.id) }
        }
    }
    if (state.manual && manualVisible) {
        ManualBrowser(viewModel)
    } else if (pending != null) {
        key(pending.id) {
            if (pending.browser) BrowserHandoff(viewModel, pending, feedback)
            else AssistanceQuestionSheet(pending.message, pending.options, feedback,
                onCancel = { viewModel.answerAssistance(pending.id, UserAssistanceAnswer("cancelled")) },
                onAnswer = { viewModel.answerAssistance(pending.id, UserAssistanceAnswer("completed", it)) })
        }
    }
    saveRequest?.let { CredentialSaveDialog(viewModel, it) }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BrowserHandoff(viewModel: MochiHomeViewModel, pending: UserAssistanceRequest, speechError: String?) {
    val state by viewModel.browserState.collectAsStateWithLifecycle()
    val credentials by viewModel.savedBrowserCredentials.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var filling by remember { mutableStateOf(false) }
    var feedback by remember(state.url) { mutableStateOf<String?>(null) }
    val origin = state.url.toHttpUrlOrNull()?.takeIf { it.isHttps }?.let { browserOrigin(it.toString()) }
    fun back() {
        if (filling) return
        scope.launch {
            browserUiAction({ feedback = it }) {
                if (!viewModel.assistanceBrowserBack(pending.id)) menu = true
            }
        }
    }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn, dismissOnClickOutside = false,
        decorFitsSystemWindows = false,
    )) {
        BackHandler(onBack = ::back)
        BrowserHandoffLayout(
            origin = origin ?: pending.origin.orEmpty(), message = pending.message, loading = state.loading,
            feedback = feedback ?: state.error ?: speechError, menu = menu, onMenu = { menu = it },
            onBack = ::back, onStop = viewModel::cancelConversation, onAccounts = { accounts = true },
            enabled = !filling,
            onReturn = { viewModel.answerAssistance(pending.id, UserAssistanceAnswer("completed")) },
        ) {
            key(state.sessionId) {
                AndroidView(
                    factory = { context -> checkNotNull(viewModel.browserWebView(context)) },
                    onRelease = viewModel::releaseBrowserWebView,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (accounts) ModalBottomSheet(
            onDismissRequest = { accounts = false },
            properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saved accounts", style = MaterialTheme.typography.titleLarge)
                Text(origin.orEmpty(), style = MaterialTheme.typography.bodySmall)
                Text("Fill only this page. Nothing is submitted automatically.", style = MaterialTheme.typography.bodyMedium)
                val matching = credentials.filter { it.origin == origin }
                if (matching.isEmpty()) Text("No saved accounts for this page.")
                matching.forEach { credential ->
                    OutlinedButton(
                        enabled = !filling, modifier = Modifier.fillMaxWidth().testTag("handoff-account-${credential.id}"),
                        onClick = {
                            filling = true
                            scope.launch {
                                try {
                                    browserUiAction({ feedback = it }) {
                                        viewModel.fillAssistanceCredential(pending.id, credential)
                                        feedback = if (credential.kind == BrowserLoginKind.SMS)
                                            "Phone number filled. Request a new code and sign in yourself."
                                        else "Account filled. Review the form and sign in yourself."
                                    }
                                } finally { filling = false; accounts = false }
                            }
                        },
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(credential.username)
                            Text(if (credential.kind == BrowserLoginKind.SMS) "SMS login" else "Password account",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
internal fun BrowserHandoffLayout(
    origin: String, message: String, loading: Boolean, feedback: String?,
    menu: Boolean, onMenu: (Boolean) -> Unit, onBack: () -> Unit, onStop: () -> Unit,
    onAccounts: () -> Unit, enabled: Boolean, onReturn: () -> Unit, page: @Composable () -> Unit,
) {
    var details by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize().testTag("browser-handoff")) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack, enabled = enabled, modifier = Modifier.testTag("handoff-back")
                    .semantics { contentDescription = localizeUiText("Back") }) {
                    BrowserNavigationIcon(BrowserGlyph.BACK)
                }
                Text(origin.removePrefix("https://"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box {
                    IconButton({ onMenu(true) }, modifier = Modifier.testTag("handoff-menu")
                        .semantics { contentDescription = localizeUiText("More options") }) { Text("...") }
                    DropdownMenu(menu, onDismissRequest = { onMenu(false) }) {
                        DropdownMenuItem(text = { Text("Stop Agent") }, onClick = { onMenu(false); onStop() })
                    }
                }
            }
            if (!WindowInsets.isImeVisible) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Your turn · Mochi is paused", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text(message, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton({ details = true }, modifier = Modifier.testTag("handoff-details")
                        .semantics { contentDescription = localizeUiText("Request details") }) { Text("ⓘ") }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth().weight(1f).testTag("handoff-page")) { page() }
            feedback?.let {
                Text(it, Modifier.fillMaxWidth().heightIn(max = 72.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
            Surface(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))) {
                FlowRow(Modifier.padding(6.dp),
                    maxItemsInEachRow = if (LocalDensity.current.fontScale > 1.3f) 1 else 2,
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onAccounts, enabled = enabled, modifier = Modifier.weight(1f).testTag("handoff-accounts")) { Text("Accounts") }
                    Button(onReturn, enabled = enabled, modifier = Modifier.weight(1.5f).testTag("handoff-return")) { Text("Return to Mochi") }
                }
            }
        }
    }
    if (details) ModalBottomSheet(
        onDismissRequest = { details = false },
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Request details", style = MaterialTheme.typography.titleLarge)
            Text(origin)
            Text(message)
            Text("The Agent is paused. Passwords stay on this device; saving requires your confirmation.")
            Text("Return control when you are ready. Mochi will check the page before continuing.")
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AssistanceQuestionSheet(
    message: String, options: List<String>, feedback: String?, onCancel: () -> Unit, onAnswer: (String) -> Unit,
) {
    var answer by remember { mutableStateOf("") }
    var writing by remember { mutableStateOf(options.isEmpty()) }
    ModalBottomSheet(
        onDismissRequest = onCancel,
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).imePadding().padding(horizontal = 20.dp)
            .testTag("assistance-question")) {
            Text("A quick question", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                options.forEach { option ->
                    OutlinedButton({ onAnswer(option) }, modifier = Modifier.fillMaxWidth()) { Text(option) }
                }
                if (writing) OutlinedTextField(
                    value = answer, onValueChange = { if (it.length <= 2_000) answer = it },
                    label = { Text("Your answer (no passwords or verification codes)") },
                    modifier = Modifier.fillMaxWidth().testTag("assistance-answer"),
                ) else TextButton({ writing = true }) { Text("Write another answer") }
                feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onCancel) { Text("Cancel") }
                if (writing) Button({ onAnswer(answer) }, enabled = answer.isNotBlank(),
                    modifier = Modifier.testTag("assistance-send")) { Text("Send") }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ManualBrowser(viewModel: MochiHomeViewModel) {
    val state by viewModel.browserState.collectAsStateWithLifecycle()
    val managementFeedback by viewModel.browserManagementFeedback.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var feedback by remember { mutableStateOf<String?>(null) }
    var showAccounts by remember { mutableStateOf(false) }
    fun back() { scope.launch { browserUiAction({ feedback = it }) { viewModel.manualBrowserBack() } } }
    Dialog(
        onDismissRequest = ::back,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        BackHandler(onBack = ::back)
        Surface(Modifier.fillMaxSize().testTag("manual-browser")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                key(state.sessionId) {
                    AndroidView(
                        factory = { context -> checkNotNull(viewModel.browserWebView(context)) },
                        onRelease = viewModel::releaseBrowserWebView,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
                (feedback ?: state.error ?: managementFeedback)?.let { Text(it, Modifier.padding(horizontal = 12.dp)) }
                if (!state.credentialSaveAvailable) Text("Update System WebView to enable password-save prompts.", Modifier.padding(horizontal = 12.dp))
                BrowserNavigationBar(
                    canGoBack = state.canGoBack,
                    canGoForward = state.canGoForward,
                    onBack = ::back,
                    onForward = {
                        scope.launch {
                            feedback = null
                            browserUiAction({ feedback = it }) { viewModel.manualBrowserForward() }
                        }
                    },
                    onHome = viewModel::showBrowserHome,
                    onAccounts = { showAccounts = true },
                )
                if (showAccounts) ModalBottomSheet(
                    onDismissRequest = { showAccounts = false },
                    properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                ) {
                    BrowserWebsites(
                        viewModel,
                        onOpen = { credential ->
                            showAccounts = false
                            viewModel.openManagedWebsite(credential.origin, credential)
                        },
                        onOpenSite = { url -> showAccounts = false; viewModel.openManagedWebsite(url) },
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.65f).padding(horizontal = 20.dp)
                            .testTag("browser-websites-popup"),
                    )
                }
            }
        }
    }
}

@Composable
internal fun BrowserNavigationBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onAccounts: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().testTag("browser-navigation"),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onBack, enabled = canGoBack,
                    modifier = Modifier.size(48.dp).testTag("browser-back").semantics { contentDescription = localizeUiText("Back") }) {
                    BrowserNavigationIcon(BrowserGlyph.BACK)
                }
                IconButton(onForward, enabled = canGoForward,
                    modifier = Modifier.size(48.dp).testTag("browser-forward").semantics { contentDescription = localizeUiText("Forward") }) {
                    BrowserNavigationIcon(BrowserGlyph.FORWARD)
                }
                VerticalDivider(Modifier.height(24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                TextButton(onHome, Modifier.weight(1f).heightIn(min = 52.dp).testTag("browser-home"),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        BrowserNavigationIcon(BrowserGlyph.HOME)
                        Text("Home", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                    }
                }
                FilledTonalButton(onAccounts, Modifier.weight(1f).heightIn(min = 52.dp).testTag("browser-accounts"),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        BrowserNavigationIcon(BrowserGlyph.ACCOUNTS)
                        Text("Sites", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

private enum class BrowserGlyph { BACK, FORWARD, HOME, ACCOUNTS }

@Composable
private fun BrowserNavigationIcon(glyph: BrowserGlyph) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val path = Path().apply {
                when (glyph) {
                    BrowserGlyph.BACK -> { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }
                    BrowserGlyph.FORWARD -> { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
                    BrowserGlyph.HOME -> {
                        moveTo(3f, 11f); lineTo(12f, 3f); lineTo(21f, 11f)
                        moveTo(5f, 10f); lineTo(5f, 21f); lineTo(10f, 21f); lineTo(10f, 15f)
                        lineTo(14f, 15f); lineTo(14f, 21f); lineTo(19f, 21f); lineTo(19f, 10f)
                    }
                    BrowserGlyph.ACCOUNTS -> {
                        moveTo(11f, 10f); lineTo(21f, 10f); lineTo(21f, 14f)
                        moveTo(17f, 10f); lineTo(17f, 13f)
                    }
                }
            }
            val stroke = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(path, color, style = stroke)
            if (glyph == BrowserGlyph.ACCOUNTS) drawCircle(color, 4.5f, Offset(6.5f, 10f), style = stroke)
        }
    }
}

internal suspend fun browserUiAction(onError: (String) -> Unit, block: suspend () -> Unit) {
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: IllegalArgumentException) {
        onError(error.message ?: "Invalid browser input.")
    } catch (error: IllegalStateException) {
        onError(error.message ?: "Browser state changed. Try again.")
    } catch (error: AgentBrowserException) {
        onError(error.message ?: "The browser operation failed.")
    } catch (_: IOException) {
        onError("Could not update website data. Try again.")
    }
}
