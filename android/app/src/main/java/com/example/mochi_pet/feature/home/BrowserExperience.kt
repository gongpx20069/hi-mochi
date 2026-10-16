package com.example.mochi_pet.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mochi_pet.core.browser.BrowserMode
import com.example.mochi_pet.core.browser.UserAssistanceAnswer
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
internal fun BrowserExperience(
    viewModel: MochiHomeViewModel,
) {
    val request by viewModel.assistanceRequest.collectAsStateWithLifecycle()
    val state by viewModel.browserState.collectAsStateWithLifecycle()
    val feedback by viewModel.assistanceFeedback.collectAsStateWithLifecycle()
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
    if (pending != null) {
        var answer by remember(pending.id) { mutableStateOf("") }
        val cancel = { viewModel.answerAssistance(pending.id, UserAssistanceAnswer("cancelled")) }
        Dialog(
            onDismissRequest = cancel,
            properties = DialogProperties(
                usePlatformDefaultWidth = !pending.browser,
                securePolicy = SecureFlagPolicy.SecureOn,
                dismissOnClickOutside = false,
            ),
        ) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(
                    Modifier.then(if (pending.browser) Modifier.fillMaxSize() else Modifier)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Mochi needs your help", style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                        Text(pending.message)
                        if (pending.browser) {
                            Text(state.url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: pending.origin.orEmpty())
                            Text("Enter credentials only in the website. Mochi cannot read or control the page while you are here.")
                            Text(if (state.mode == BrowserMode.DESKTOP) "Desktop website" else "Mobile website")
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
                                TextButton({ viewModel.answerAssistance(pending.id, UserAssistanceAnswer("completed", option)) }) {
                                    Text(option)
                                }
                            }
                            OutlinedTextField(
                                value = answer,
                                onValueChange = { if (it.length <= 2_000) answer = it },
                                label = { Text("Your answer (no passwords or verification codes)") },
                                modifier = Modifier.fillMaxWidth(),
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
}

@Composable
internal fun WebsiteManager(viewModel: MochiHomeViewModel, onClose: () -> Unit) {
    val sites by viewModel.browserSites.collectAsStateWithLifecycle(emptyList())
    val browser by viewModel.browserState.collectAsStateWithLifecycle()
    val browserFeedback by viewModel.browserManagementFeedback.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var supported by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { supported = viewModel.canClearBrowserData() }
    fun runAction(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = null
            failed = false
            try {
                action()
            } catch (error: IllegalArgumentException) {
                failed = true
                message = error.message
            } catch (error: IllegalStateException) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                failed = true
                message = error.message
            } catch (error: IOException) {
                failed = true
                message = error.message ?: "Website data could not be updated."
            } finally {
                busy = false
            }
        }
    }
    BrowserManagementDialog(onClose = { if (!busy) onClose() }) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MOCHI / WEB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Text("Websites and logins", Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            TextButton(enabled = !busy, onClick = onClose) { Text("Close") }
        }
        LazyColumn(
            Modifier.weight(1f).testTag("browser-sites-list"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                DashboardPanel(accent = MaterialTheme.colorScheme.secondary) {
                    Text("Sign in once. Let Mochi continue.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Login is shared with Mochi and reused automatically. Mochi chooses the best browser view for each task.")
                    Text("Saved websites do not mean you are currently logged in.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (browser.active) item { Text("Finish or stop the active browser task before managing website data.") }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            browserFeedback?.let { text ->
                item { Text(text, color = MaterialTheme.colorScheme.error) }
            }
            message?.let { text ->
                item {
                    Surface(
                        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) { Text(text, Modifier.fillMaxWidth().padding(14.dp)) }
                }
            }
            item {
                DashboardPanel {
                    Text("Open a website", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = host, onValueChange = { host = it },
                        label = { Text("Website host, for example example.com") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = !busy && !browser.active && host.isNotBlank(),
                        onClick = { viewModel.openManagedWebsite(host.trim()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open website / manage login") }
                }
            }
            item { Text("Saved websites", style = MaterialTheme.typography.titleMedium) }
            if (sites.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No websites yet", style = MaterialTheme.typography.titleMedium)
                    Text("Open a website above, or ask Mochi to browse one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(sites, key = { it.host }) { site ->
                DashboardPanel {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                                Text(site.host.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(site.host, style = MaterialTheme.typography.titleMedium)
                            if (site.lastVisited > 0) {
                                Text("Last visited", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(site.lastVisited)),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    OutlinedButton(
                        enabled = !busy && !browser.active,
                        onClick = { viewModel.openManagedWebsite(site.host) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open website / manage login") }
                    TextButton(
                        enabled = supported && !busy && !browser.active,
                        onClick = { deleteTarget = site.host },
                    ) { Text("Clear website data", color = MaterialTheme.colorScheme.error) }
                }
            }
            item {
                DashboardPanel {
                    Text("Local website data", style = MaterialTheme.typography.titleMedium)
                    Text("Cache speeds up loading. Website data keeps login sessions on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!supported) Text("Update Android System WebView to enable complete website-data deletion. Partial logout is not offered.")
                    OutlinedButton(enabled = !busy && !browser.active, modifier = Modifier.fillMaxWidth(), onClick = {
                        runAction {
                            viewModel.clearBrowserData(null, cacheOnly = true)
                            message = "Resource cache cleared. Login sessions were kept."
                        }
                    }) { Text("Clear resource cache only") }
                    TextButton(enabled = supported && !busy && !browser.active, onClick = { deleteTarget = "" }) {
                        Text("Clear all website data", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item { Box(Modifier.size(6.dp)) }
        }
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(if (target.isEmpty()) "Clear all website data" else target) },
            text = { Text("This deletes local cookies, cache and website storage, including shared login for the parent domain and its subdomains. Websites may require login again. Remote accounts are not deleted or revoked.") },
            dismissButton = { TextButton({ deleteTarget = null }) { Text("Cancel") } },
            confirmButton = { TextButton({
                deleteTarget = null
                runAction {
                    viewModel.clearBrowserData(target.takeIf(String::isNotEmpty), cacheOnly = false)
                    message = "Website data cleared."
                }
            }) { Text("Delete") } },
        )
    }
}

@Composable
private fun BrowserManagementDialog(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onClose)
        Surface(Modifier.fillMaxSize().testTag("browser-management"), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.background(Brush.verticalGradient(listOf(Color(0xFF211B2D), MaterialTheme.colorScheme.background)))) {
                Column(
                    Modifier.align(Alignment.TopCenter).safeDrawingPadding().widthIn(max = 680.dp)
                        .fillMaxSize().padding(horizontal = 20.dp),
                    content = content,
                )
            }
        }
    }
}
