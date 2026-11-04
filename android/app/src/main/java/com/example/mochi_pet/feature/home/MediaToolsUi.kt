package com.example.mochi_pet.feature.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.mochi_pet.core.tools.MediaProviderSummary
import com.example.mochi_pet.core.tools.AppControlProviderSummary
import com.example.mochi_pet.platform.media.mediaNotificationAccessGranted
import com.example.mochi_pet.platform.media.appAssistanceGranted
import com.example.mochi_pet.platform.media.selectableApps
import com.example.mochi_pet.platform.media.SelectableApp

@Composable
internal fun AppControlProviderCard(
    summary: AppControlProviderSummary,
    disabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onSetToolEnabled: (String, Boolean) -> Unit,
    onSetAppPackages: (Set<String>) -> Unit = {},
) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showAppDisclosure by remember { mutableStateOf(false) }
    var showBackgroundDisclosure by remember { mutableStateOf(false) }
    var appGranted by remember { mutableStateOf(appAssistanceGranted(context)) }
    var choosingApps by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf(emptyList<SelectableApp>()) }
    LifecycleResumeEffect(context) {
        appGranted = appAssistanceGranted(context)
        onPauseOrDispose { }
    }
    PlannerCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("App Control", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Switch(summary.enabled, onSetEnabled, enabled = !disabled, modifier = Modifier.testTag("app-group-switch"))
        }
        Text(
            "Read and operate the apps you explicitly allow.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolSettingsRow(
                title = "Allowed apps",
                detail = summary.appPackages?.let { "Selected apps: ${it.size}" } ?: "Music apps by default",
                icon = ToolGlyph.APPS,
                enabled = !disabled,
                tag = "media-app-selection",
                onClick = { apps = selectableApps(context); choosingApps = true },
            )
            ToolSettingsRow(
                title = "App assistance",
                detail = if (appGranted) "Enabled" else "Not enabled",
                icon = ToolGlyph.ASSIST,
                enabled = !disabled,
                tag = "media-app-assistance",
                onClick = { showAppDisclosure = true },
            )
            ToolSettingsRow(
                title = "Background pop-up permission",
                detail = "Open Mochi while another app is in the foreground",
                icon = ToolGlyph.RETURN,
                enabled = !disabled,
                tag = "app-background-permission",
                onClick = { showBackgroundDisclosure = true },
            )
        }
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(if (expanded) "Hide tools (${summary.tools.size})" else "Show tools (${summary.tools.size})")
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                summary.tools.forEach {
                    BuiltInToolRow(it, disabled || !summary.enabled, onSetToolEnabled)
                }
            }
        }
    }
    if (choosingApps) {
        MediaAppSelectionSheet(
            apps = apps,
            initialSelection = summary.appPackages ?: apps.filter { it.music }.map { it.packageName }.toSet(),
            onDismiss = { choosingApps = false },
            onSave = {
                onSetAppPackages(it)
                choosingApps = false
            },
        )
    }
    if (showAppDisclosure) {
        AlertDialog(
            onDismissRequest = { showAppDisclosure = false },
            title = { Text("App assistance") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Accessibility is a broad Android permission.")
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Only your selected apps", fontWeight = FontWeight.SemiBold)
                        Text("Your model reads filtered controls and chooses actions for your request. Interface text may reach your model Provider and conversation history.")
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("You stay in control", fontWeight = FontWeight.SemiBold)
                        Text("Mochi can close explicit safe popups. Handle login, payments, permission grants and other sensitive steps yourself. Remove apps or disable access at any time.")
                    }
                    Text("In Android settings, enable 'Mochi app assistance'.", color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAppDisclosure = false
                    error = null
                    try {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (_: ActivityNotFoundException) {
                        error = "Cannot open accessibility settings. Open them from Android Settings."
                    } catch (_: SecurityException) {
                        error = "Cannot open accessibility settings. Open them from Android Settings."
                    }
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { showAppDisclosure = false }) { Text("Cancel") }
            },
        )
    }
    if (showBackgroundDisclosure) {
        AlertDialog(
            onDismissRequest = { showBackgroundDisclosure = false },
            title = { Text("Background pop-up permission") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Some phones need this permission to open Mochi while another app is in the foreground.")
                    Text("On Xiaomi/Redmi: Mochi app info > Other permissions > Display pop-up windows while running in the background.")
                    Text("Grant it manually if your system offers it. Mochi cannot reliably read this permission's status.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showBackgroundDisclosure = false
                    error = null
                    try {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:${context.packageName}".toUri()))
                    } catch (_: ActivityNotFoundException) {
                        error = "Cannot open app settings. Open Mochi's app info from Android Settings."
                    } catch (_: SecurityException) {
                        error = "Cannot open app settings. Open Mochi's app info from Android Settings."
                    }
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { showBackgroundDisclosure = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
internal fun MediaProviderCard(
    summary: MediaProviderSummary,
    disabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onSetToolEnabled: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(mediaNotificationAccessGranted(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showAccessDisclosure by remember { mutableStateOf(false) }
    LifecycleResumeEffect(context) {
        granted = mediaNotificationAccessGranted(context)
        onPauseOrDispose { }
    }
    PlannerCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Media Control", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Switch(summary.enabled, onSetEnabled, enabled = !disabled, modifier = Modifier.testTag("media-group-switch"))
        }
        Text("Control playback, request songs and adjust media volume.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ToolSettingsRow(
            title = "Player access",
            detail = if (granted) "Enabled · playback control and verification" else "Not enabled · playback control and verification",
            icon = ToolGlyph.PLAYER,
            enabled = !disabled,
            tag = "media-player-access",
            onClick = { showAccessDisclosure = true },
        )
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(if (expanded) "Hide tools (${summary.tools.size})" else "Show tools (${summary.tools.size})")
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                summary.tools.forEach { BuiltInToolRow(it, disabled || !summary.enabled, onSetToolEnabled) }
            }
        }
    }
    if (showAccessDisclosure) {
        AlertDialog(
            onDismissRequest = { showAccessDisclosure = false },
            title = { Text("Player access") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Not needed for App Control or local volume. Required for media session control and playback verification.")
                    Text("Notification access is a broad Android permission. Mochi uses media sessions only, not message notifications. Track metadata may be sent to your model Provider.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAccessDisclosure = false
                    error = null
                    try {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } catch (_: ActivityNotFoundException) {
                        error = "Cannot open notification access settings. Open them from Android Settings."
                    } catch (_: SecurityException) {
                        error = "Cannot open notification access settings. Open them from Android Settings."
                    }
                }) { Text("Open notification settings") }
            },
            dismissButton = {
                TextButton(onClick = { showAccessDisclosure = false }) { Text("Cancel") }
            },
        )
    }
}
