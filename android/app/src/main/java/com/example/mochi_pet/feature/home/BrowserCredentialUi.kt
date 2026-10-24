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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mochi_pet.R
import com.example.mochi_pet.core.browser.BrowserCredentialSummary
import com.example.mochi_pet.core.browser.BrowserLoginKind
import com.example.mochi_pet.core.browser.BrowserWebsite
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
            BrowserWebsites(
                viewModel, onOpen = { viewModel.openManagedWebsite(it.origin, it) },
                onOpenSite = { viewModel.openManagedWebsite(it) }, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun BrowserWebsites(
    viewModel: MochiHomeViewModel,
    onOpen: (BrowserCredentialSummary) -> Unit,
    onOpenSite: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val credentials by viewModel.savedBrowserCredentials.collectAsStateWithLifecycle(emptyList())
    val sites by viewModel.browserSites.collectAsStateWithLifecycle(emptyList())
    val feedback by viewModel.browserManagementFeedback.collectAsStateWithLifecycle()
    val credentialFeedback by viewModel.browserCredentialFeedback.collectAsStateWithLifecycle()
    val browser by viewModel.browserState.collectAsStateWithLifecycle()
    val dataVersion by viewModel.browserDataVersion.collectAsStateWithLifecycle()
    val dataDeleting by viewModel.browserDataDeleting.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var websites by remember { mutableStateOf(emptyList<BrowserWebsite>()) }
    var selectedDomain by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<BrowserCredentialSummary?>(null) }
    var addingSms by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BrowserCredentialSummary?>(null) }
    var deletingSite by remember { mutableStateOf<BrowserWebsite?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var inventoryError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var clearSessions by remember { mutableStateOf(false) }
    var canClear by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { canClear = viewModel.canClearBrowserData() }
    LaunchedEffect(sites, credentials, browser.loading, browser.active, dataVersion, dataDeleting) {
        if (dataDeleting) return@LaunchedEffect
        loading = true
        inventoryError = null
        try {
            browserUiAction({ inventoryError = it }) {
                websites = viewModel.managedBrowserWebsites(sites, credentials)
                if (websites.none { it.domain == selectedDomain }) selectedDomain = null
            }
        } finally { loading = false }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Websites", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                TextButton(enabled = !busy && !dataDeleting, onClick = { options = true }) { Text("...") }
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
        (error ?: inventoryError ?: credentialFeedback)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        }
        (status ?: feedback)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)) }
        if (busy || loading || dataDeleting) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (websites.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (!loading && inventoryError == null) Text("Websites with saved accounts or local data appear here.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else BrowserWebsiteList(websites, { selectedDomain = it.domain }, Modifier.weight(1f))
    }
    websites.firstOrNull { it.domain == selectedDomain }?.let { website ->
        ModalBottomSheet(
            onDismissRequest = { selectedDomain = null },
            properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            BrowserWebsiteDetails(
                website = website,
                onClose = { selectedDomain = null },
                onOpenSite = { selectedDomain = null; onOpenSite(website.openUrl) },
                onOpen = { selectedDomain = null; onOpen(it) },
                onEdit = { editing = it },
                onDelete = { error = null; deleting = it },
                onDeleteData = { deletingSite = website },
                dataDeleteEnabled = canClear && (!browser.active || browser.manual) && !dataDeleting,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.65f).padding(horizontal = 20.dp),
            )
            if (!canClear) Text("Update Android System WebView to enable complete website-data deletion. Partial logout is not offered.",
                Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
            else if (browser.active && !browser.manual) Text("Stop the active browser task before clearing website data.",
                Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
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
            confirmButton = { TextButton(enabled = !busy, modifier = Modifier.testTag("browser-delete-account-confirm"), onClick = {
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
    deletingSite?.let { website ->
        AlertDialog(
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            onDismissRequest = { deletingSite = null },
            title = { Text("Delete this website's data?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(website.domain)
                    Text("This deletes cookies, cache and website storage for this domain and its subdomains. You may be signed out. Saved accounts are kept.")
                    if (browser.manual) Text("The current browser page will close before deletion.")
                }
            },
            dismissButton = { TextButton(onClick = { deletingSite = null }) { Text("Cancel") } },
            confirmButton = { TextButton(
                enabled = !dataDeleting,
                modifier = Modifier.testTag("browser-delete-site-confirm"),
                onClick = { deletingSite = null; viewModel.deleteManagedBrowserSite(website.domain) },
            ) { Text("Delete") } },
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
internal fun BrowserWebsiteList(websites: List<BrowserWebsite>, onOpen: (BrowserWebsite) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.testTag("browser-websites-list")) {
        items(websites, key = { it.domain }) { website ->
            Row(
                Modifier.fillMaxWidth().testTag("browser-website-${website.domain}").clickable { onOpen(website) }.padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                        Text(website.domain.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(website.domain, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (website.hasSiteData) BrowserDataBadge("Cookies", siteData = true)
                        if (website.credentials.isNotEmpty()) BrowserDataBadge(
                            "${localizeUiText("Credentials")} · ${website.credentials.size}", siteData = false,
                        )
                    }
                }
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
internal fun BrowserWebsiteDetails(
    website: BrowserWebsite,
    onClose: () -> Unit,
    onOpenSite: () -> Unit,
    onOpen: (BrowserCredentialSummary) -> Unit,
    onEdit: (BrowserCredentialSummary) -> Unit,
    onDelete: (BrowserCredentialSummary) -> Unit,
    onDeleteData: () -> Unit,
    dataDeleteEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag("browser-website-details")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(website.domain, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClose) { Text("Close") }
        }
        Text("Stored locally. Login may expire.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onOpenSite, modifier = Modifier.testTag("browser-open-site")) { Text("Open website") }
        LazyColumn(Modifier.weight(1f).testTag("browser-site-entries")) {
            if (website.hasSiteData) item(key = "site-data") {
                WebsiteDataRow(website.domain, onDeleteData, dataDeleteEnabled)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
            items(website.credentials, key = { it.id }) { credential ->
                CredentialRow(credential, { onOpen(credential) }, { onEdit(credential) }, { onDelete(credential) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun BrowserDataBadge(label: String, siteData: Boolean) {
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val color = if (siteData) {
        if (light) Color(0xFF235C8F) else Color(0xFF8DCBEE)
    } else {
        if (light) Color(0xFF66409F) else Color(0xFFD1BCFF)
    }
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.12f)) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun WebsiteDataRow(domain: String, onDelete: () -> Unit, deleteEnabled: Boolean = true) {
    HalfRevealRow(domain, "site-data", onOpen = null, onEdit = null, onDelete = onDelete, deleteEnabled = deleteEnabled) {
        BrowserDataBadge("Cookies / site data", siteData = true)
        Text("Shared website storage", style = MaterialTheme.typography.titleMedium)
        Text("May keep you signed in. No saved password.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
    HalfRevealRow(credential.id, "credential", onOpen, onEdit, onDelete) {
        BrowserDataBadge(if (credential.kind == BrowserLoginKind.SMS) "SMS login" else "Password account", siteData = false)
        Text(credential.username, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(credential.origin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (credential.kind == BrowserLoginKind.PASSWORD) Text("Password: ********",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun HalfRevealRow(
    id: String,
    tag: String,
    onOpen: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onDelete: () -> Unit,
    deleteEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var width by remember { mutableIntStateOf(0) }
    var reveal by remember(id) { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val animated by animateFloatAsState(reveal, if (dragging) snap() else tween(160), label = "browser-row-reveal")
    Box(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clipToBounds().onSizeChanged { width = it.width }
            .testTag("$tag-row-$id"),
    ) {
        if (reveal > 0f) Row(
            Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.5f).fillMaxHeight(),
        ) {
            onEdit?.let { TextButton(it, Modifier.weight(1f).fillMaxHeight().testTag("$tag-edit-$id")) { Text("Edit") } }
            TextButton(onDelete, Modifier.weight(1f).fillMaxHeight().testTag("$tag-delete-$id"), enabled = deleteEnabled) {
                Text("Delete", color = if (deleteEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(
            color = if (reveal == 0f) Color.Transparent else MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().offset { IntOffset((-width * animated).roundToInt(), 0) }
                .testTag("$tag-content-$id")
                .pointerInput(width) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false; reveal = if (reveal > 0.25f) 0.5f else 0f },
                        onDragCancel = { dragging = false; reveal = 0f },
                    ) { change, delta ->
                        change.consume()
                        if (width > 0) reveal = (reveal - delta / width).coerceIn(0f, 0.5f)
                    }
                }
                .semantics {
                    customActions = buildList {
                        onEdit?.let { add(CustomAccessibilityAction(localizeUiText("Edit")) { it(); true }) }
                        if (deleteEnabled) add(CustomAccessibilityAction(localizeUiText("Delete")) { onDelete(); true })
                    }
                }
                .clickable(enabled = reveal > 0f || onOpen != null) { if (reveal > 0f) reveal = 0f else onOpen?.invoke() },
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
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
