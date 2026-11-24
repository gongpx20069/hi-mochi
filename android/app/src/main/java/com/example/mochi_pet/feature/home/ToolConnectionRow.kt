package com.example.mochi_pet.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolConnectionRow(
    hasConnection: Boolean,
    connectLabel: String,
    detail: String,
    enabled: Boolean,
    tag: String,
    onConnect: () -> Unit,
    management: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasConnection) { if (!hasConnection) open = false }
    ToolSettingsRow(
        title = if (hasConnection) "Connection settings" else connectLabel,
        detail = detail,
        icon = ToolGlyph.WEBSITES,
        enabled = enabled,
        tag = tag,
        onClick = { if (hasConnection) open = true else onConnect() },
    )
    if (open && hasConnection) ModalBottomSheet(
        onDismissRequest = { open = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connection settings", style = MaterialTheme.typography.titleLarge)
            Column(
                Modifier.weight(1f, fill = false).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(detail, style = MaterialTheme.typography.bodyMedium)
                management { open = false }
            }
            TextButton({ open = false }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}
