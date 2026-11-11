package com.example.mochi_pet.feature.journal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.mochi_pet.feature.home.Text
import com.example.mochi_pet.feature.home.localizeUiText

@Composable
internal fun JournalManagedControl(enabled: Boolean, onOpenSettings: () -> Unit) {
    var explain by remember { mutableStateOf(false) }
    val state = localizeUiText(if (enabled) "Enabled" else "Disabled")
    Column(
        Modifier.clickable(role = Role.Button, onClickLabel = localizeUiText("Managed by Daily journal")) { explain = true }
            .semantics { stateDescription = state }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Switch(checked = enabled, onCheckedChange = null, enabled = false)
        Text("Daily journal", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (explain) AlertDialog(
        onDismissRequest = { explain = false },
        title = { Text("Managed by Daily journal") },
        text = { Text("This feature follows Daily journal in Settings. There is no separate switch.") },
        confirmButton = { TextButton(onClick = { explain = false; onOpenSettings() }) { Text("Go to settings") } },
        dismissButton = { TextButton(onClick = { explain = false }) { Text("Got it") } },
    )
}
