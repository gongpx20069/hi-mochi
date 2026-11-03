package com.example.mochi_pet.feature.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.mochi_pet.core.tools.MediaProviderSummary
import com.example.mochi_pet.platform.media.mediaNotificationAccessGranted

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
    LifecycleResumeEffect(context) {
        granted = mediaNotificationAccessGranted(context)
        onPauseOrDispose { }
    }
    PlannerCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Media Control", fontWeight = FontWeight.Bold)
                Text(
                    if (granted) "Player access granted" else "Notification access required for players",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(summary.enabled, onSetEnabled, enabled = !disabled, modifier = Modifier.testTag("media-group-switch"))
        }
        Text(
            "Controls active players, not song search. Enabled tools may run in conversations and Scheduled Agents, not Subagents.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Notification access is a broad Android permission. Mochi uses media sessions only, not message notifications. Track metadata may be sent to your model Provider.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Volume affects this phone's music, video and Mochi speech, not ringtone, alarms or cast devices. It works without notification access.",
            style = MaterialTheme.typography.bodySmall,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = !disabled,
            onClick = {
                error = null
                try {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    error = "Cannot open notification access settings. Open them from Android Settings."
                } catch (_: SecurityException) {
                    error = "Cannot open notification access settings. Open them from Android Settings."
                }
            },
        ) { Text(if (granted) "Manage notification access" else "Grant notification access") }
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
}
