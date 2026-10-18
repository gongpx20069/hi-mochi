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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mochi_pet.core.browser.UserAssistanceAnswer
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
        var answer by remember(pending.id) { mutableStateOf("") }
        val cancel = { viewModel.answerAssistance(pending.id, UserAssistanceAnswer("cancelled")) }
        Dialog(
            onDismissRequest = cancel,
            properties = DialogProperties(
                usePlatformDefaultWidth = !pending.browser, securePolicy = SecureFlagPolicy.SecureOn,
                dismissOnClickOutside = false,
            ),
        ) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(
                    Modifier.then(if (pending.browser) Modifier.fillMaxSize() else Modifier).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Mochi needs your help", style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                        Text(pending.message)
                        if (pending.browser) {
                            Text(state.url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: pending.origin.orEmpty())
                            Text("The Agent is paused. Passwords stay on this device; saving requires your confirmation.")
                        }
                        feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    if (pending.browser) {
                        AndroidView(
                            factory = { context -> checkNotNull(viewModel.browserWebView(context)) },
                            onRelease = viewModel::releaseBrowserWebView,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    } else {
                        Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                            pending.options.forEach { option ->
                                TextButton({ viewModel.answerAssistance(pending.id, UserAssistanceAnswer("completed", option)) }) { Text(option) }
                            }
                            OutlinedTextField(
                                value = answer, onValueChange = { if (it.length <= 2_000) answer = it },
                                label = { Text("Your answer (no passwords or verification codes)") }, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(cancel) { Text("Cancel") }
                        Button(
                            onClick = { viewModel.answerAssistance(pending.id, UserAssistanceAnswer("completed", answer)) },
                            enabled = pending.browser || answer.isNotBlank(),
                        ) { Text("Continue") }
                    }
                }
            }
        }
    }
    saveRequest?.let { CredentialSaveDialog(viewModel, it) }
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
