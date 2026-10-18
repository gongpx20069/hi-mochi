package com.example.mochi_pet.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mochi_pet.R
import com.example.mochi_pet.core.browser.BrowserCredentialSummary
import com.example.mochi_pet.core.browser.BrowserLoginKind
import com.example.mochi_pet.core.browser.browserOrigin
import com.example.mochi_pet.platform.browser.BrowserCredentialSaveRequest
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
internal fun WebsiteManager(viewModel: MochiHomeViewModel, onClose: () -> Unit) {
    val browser by viewModel.browserState.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    val close = { viewModel.closeManualBrowser(); onClose() }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        BrowserManagementLayout(
            onClose = close,
            closeEnabled = true,
            onResume = if (browser.manual) viewModel::resumeManualBrowser else null,
            search = {
                OutlinedTextField(
                    value = search, onValueChange = { if (it.length <= 2_000) search = it },
                    placeholder = { Text("Search Bing or enter a website") },
                    singleLine = true, shape = MaterialTheme.shapes.extraLarge,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (search.isNotBlank()) viewModel.openManagedWebsite(search) }),
                    trailingIcon = {
                        TextButton(enabled = search.isNotBlank(), onClick = { viewModel.openManagedWebsite(search) }) { Text("Go") }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("browser-search"),
                )
            },
        ) {
            BrowserAccounts(viewModel, onOpen = { viewModel.openManagedWebsite(it.origin, it) }, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun BrowserAccounts(
    viewModel: MochiHomeViewModel,
    onOpen: (BrowserCredentialSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val credentials by viewModel.savedBrowserCredentials.collectAsStateWithLifecycle(emptyList())
    val feedback by viewModel.browserManagementFeedback.collectAsStateWithLifecycle()
    val credentialFeedback by viewModel.browserCredentialFeedback.collectAsStateWithLifecycle()
    val browser by viewModel.browserState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<BrowserCredentialSummary?>(null) }
    var addingSms by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BrowserCredentialSummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var clearSessions by remember { mutableStateOf(false) }
    var canClear by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { canClear = viewModel.canClearBrowserData() }
        Column(modifier) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Saved accounts", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    TextButton(enabled = !busy, onClick = { options = true }) { Text("...") }
                    DropdownMenu(options, onDismissRequest = { options = false }) {
                        DropdownMenuItem(text = { Text("Add SMS login account") }, onClick = { options = false; addingSms = true })
                        DropdownMenuItem(text = { Text("Clear resource cache only") }, enabled = !browser.active, onClick = {
                            options = false
                            scope.launch {
                                busy = true
                                error = null
                                try {
                                    browserUiAction({ error = it }) {
                                        viewModel.clearBrowserData(null, cacheOnly = true)
                                        status = "Resource cache cleared. Login sessions were kept."
                                    }
                                } finally { busy = false }
                            }
                        })
                        DropdownMenuItem(text = { Text("Clear all website data") }, enabled = canClear && !browser.active,
                            onClick = { options = false; clearSessions = true })
                    }
                }
            }
            (error ?: credentialFeedback ?: feedback)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (credentials.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("Saved accounts will appear here.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(Modifier.weight(1f).testTag("browser-credentials-list")) {
                items(credentials, key = { it.id }) { credential ->
                    CredentialRow(
                        credential,
                        onOpen = { onOpen(credential) },
                        onEdit = { editing = credential },
                        onDelete = { error = null; deleting = credential },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
        editing?.let { credential ->
            CredentialEditDialog(credential, viewModel, onClose = { editing = null })
        }
        if (addingSms) CredentialEditDialog(
            null, viewModel, onClose = { addingSms = false },
            initialOrigin = browser.url.toHttpUrlOrNull()?.takeIf { it.isHttps }?.let { browserOrigin(it.toString()) }.orEmpty(),
        )
        deleting?.let { credential ->
            AlertDialog(
                properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
                onDismissRequest = { if (!busy) deleting = null },
                title = { Text("Delete saved account?") },
                text = {
                    Column {
                        Text(if (credential.kind == BrowserLoginKind.SMS)
                            "Only the saved website and phone number are deleted. Your current website login is kept."
                        else "Only the saved username and password are deleted. Your current website login is kept.")
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("Cancel") } },
                confirmButton = { TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            browserUiAction({ error = it }) {
                                viewModel.deleteBrowserCredential(credential)
                                deleting = null
                            }
                        } finally { busy = false }
                    }
                }) { Text("Delete") } },
            )
        }
        if (clearSessions) AlertDialog(
            onDismissRequest = { if (!busy) clearSessions = false },
            title = { Text("Clear all website data") },
            text = {
                Column {
                    Text("This clears cookies and website storage, signing you out of websites. Saved accounts and passwords are kept.")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { clearSessions = false }) { Text("Cancel") } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        browserUiAction({ error = it }) {
                            viewModel.clearBrowserData(null, false)
                            clearSessions = false
                            status = "Website data cleared."
                        }
                    } finally { busy = false }
                }
            }) { Text("Delete") } },
        )
}

@Composable
internal fun BrowserManagementLayout(
    onClose: () -> Unit,
    closeEnabled: Boolean,
    onResume: (() -> Unit)? = null,
    search: @Composable () -> Unit,
    accounts: @Composable ColumnScope.() -> Unit,
) {
    Surface(Modifier.fillMaxSize().testTag("browser-management"), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(
                listOf(Color(0xFF302640), MaterialTheme.colorScheme.background, Color(0xFF232538)),
            )),
            contentAlignment = Alignment.TopCenter,
        ) {
            BoxWithConstraints(Modifier.safeDrawingPadding().widthIn(max = 680.dp).fillMaxSize().padding(horizontal = 20.dp)) {
                val logoSize = (maxHeight * 0.13f).coerceIn(48.dp, 88.dp)
                Column(Modifier.fillMaxSize().padding(bottom = 20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        onResume?.let {
                            TextButton(it, modifier = Modifier.testTag("browser-resume")) { Text("Return to page") }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClose, enabled = closeEnabled, modifier = Modifier.testTag("browser-management-close")) {
                            Text("Close")
                        }
                    }
                    Column(
                        Modifier.weight(1.35f).fillMaxWidth().padding(bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(0.85f).testTag("browser-brand"),
                            shape = RoundedCornerShape(32.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                        ) {
                            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(
                                    Modifier.size(logoSize).clip(RoundedCornerShape(logoSize * 0.28f))
                                        .background(Brush.linearGradient(listOf(Color(0xFFA794F4), Color(0xFF6758C9))))
                                        .testTag("browser-logo"),
                                ) {
                                    Image(painterResource(R.drawable.ic_launcher_foreground), "Mochi", Modifier.fillMaxSize())
                                }
                                Text("Search the web. Save accounts safely.", style = MaterialTheme.typography.bodySmall,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        search()
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f).testTag("browser-accounts-panel"),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    ) {
                        Column(
                            Modifier.background(Brush.linearGradient(
                                listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.015f)),
                            )).padding(horizontal = 16.dp, vertical = 8.dp),
                            content = accounts,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CredentialRow(
    credential: BrowserCredentialSummary,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var width by remember { mutableIntStateOf(0) }
    var offset by remember(credential.id) { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val animated by animateFloatAsState(offset, if (dragging) snap() else tween(160), label = "credential-reveal")
    Box(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clipToBounds().onSizeChanged { width = it.width }
            .testTag("credential-row-${credential.id}"),
    ) {
        if (offset < 0f) Row(
            Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.5f).fillMaxHeight(),
        ) {
            TextButton(onEdit, Modifier.weight(1f).fillMaxHeight()) { Text("Edit") }
            TextButton(onDelete, Modifier.weight(1f).fillMaxHeight()) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
        Surface(
            color = if (offset == 0f) Color.Transparent else MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().offset { IntOffset(animated.roundToInt(), 0) }
                .testTag("credential-content-${credential.id}")
                .pointerInput(width) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false; offset = if (offset < -width / 4f) -width / 2f else 0f },
                        onDragCancel = { dragging = false; offset = 0f },
                    ) { change, delta ->
                        change.consume()
                        offset = (offset + delta).coerceIn(-width / 2f, 0f)
                    }
                }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(localizeUiText("Edit")) { onEdit(); true },
                        CustomAccessibilityAction(localizeUiText("Delete")) { onDelete(); true },
                    )
                }
                .clickable { if (offset != 0f) offset = 0f else onOpen() },
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(credential.origin.toHttpUrlOrNull()?.host ?: credential.origin, style = MaterialTheme.typography.titleMedium)
                Text(credential.username, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (credential.kind == BrowserLoginKind.SMS) "SMS login" else "Password: ********",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun CredentialEditDialog(
    credential: BrowserCredentialSummary?,
    viewModel: MochiHomeViewModel,
    onClose: () -> Unit,
    initialOrigin: String = "",
) {
    val sms = credential == null || credential.kind == BrowserLoginKind.SMS
    var website by remember(credential?.id) { mutableStateOf(credential?.origin ?: initialOrigin) }
    var username by remember(credential?.id) { mutableStateOf(credential?.username.orEmpty()) }
    var password by remember(credential?.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (credential == null) "Save SMS login account?" else "Edit saved account") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(website, { website = it }, label = { Text("Website") }, singleLine = true)
                OutlinedTextField(username, { if (it.length <= 320) username = it }, label = { Text(if (sms) "Phone number" else "Username") },
                    singleLine = true, keyboardOptions = if (sms) KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone) else KeyboardOptions.Default)
                if (sms) Text("Only the website and phone number are saved. Verification codes are never saved; expired logins need a new code.")
                else OutlinedTextField(password, { if (it.length <= 4_096) password = it }, label = { Text("New password (blank keeps current)") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Cancel") } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                try {
                    browserUiAction({ error = it }) {
                        if (credential == null) viewModel.saveSmsBrowserAccount(website, username)
                        else viewModel.editBrowserCredential(credential, website, username, password)
                        onClose()
                    }
                } finally { busy = false }
            }
        }) { Text("Save") } },
    )
}

@Composable
internal fun CredentialSaveDialog(viewModel: MochiHomeViewModel, request: BrowserCredentialSaveRequest) {
    val sms = request.login.kind == BrowserLoginKind.SMS
    var username by remember(request.id) { mutableStateOf(request.login.username) }
    var error by remember(request.id) { mutableStateOf<String?>(null) }
    var busy by remember(request.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn, dismissOnClickOutside = false),
        onDismissRequest = { if (!busy) viewModel.dismissCredentialSave(request.id) },
        title = { Text(if (sms) "Save SMS login account?" else "Save this account and password?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(request.login.origin)
                OutlinedTextField(username, { if (it.length <= 320) username = it }, label = { Text(if (sms) "Phone number" else "Username") },
                    singleLine = true, keyboardOptions = if (sms) KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone) else KeyboardOptions.Default)
                if (sms) Text("Only the website and phone number are saved. Verification codes are never saved; expired logins need a new code.")
                else Text("Password: ********")
                Text("Encrypted on this device, never sent to the AI. Saving does not confirm that sign-in succeeded.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { viewModel.dismissCredentialSave(request.id) }) { Text("Not now") } },
        confirmButton = { TextButton(enabled = !busy && username.isNotBlank(), onClick = {
            scope.launch {
                busy = true
                try {
                    browserUiAction({ error = it }) { viewModel.saveCapturedCredential(request.id, username) }
                } finally { busy = false }
            }
        }) { Text("Save") } },
    )
}
