package com.example.mochi_pet.feature.home

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.mochi_pet.platform.media.SelectableApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaAppSelectionSheet(
    apps: List<SelectableApp>,
    initialSelection: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    var selected by remember { mutableStateOf(initialSelection) }
    var filter by remember { mutableStateOf("") }
    val duplicateNames = remember(apps) { apps.groupingBy { it.label }.eachCount().filterValues { it > 1 }.keys }
    val filtered = apps.filter { it.label.contains(filter, true) || it.packageName.contains(filter, true) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding().padding(horizontal = 20.dp)
            .testTag("media-app-picker"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Allowed apps", style = MaterialTheme.typography.titleLarge)
                    Text("Selected apps: ${selected.size}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val closeLabel = localizeUiText("Close")
                IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = closeLabel }) {
                    ToolGlyphIcon(ToolGlyph.CLOSE)
                }
            }
            OutlinedTextField(
                value = filter, onValueChange = { filter = it }, singleLine = true,
                placeholder = { Text("Search apps") }, modifier = Modifier.fillMaxWidth().testTag("media-app-search"),
                leadingIcon = { ToolGlyphIcon(ToolGlyph.SEARCH) },
                shape = RoundedCornerShape(16.dp),
            )
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("media-app-list"),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Text("Only selected apps can be operated. Interface text may reach your model and history.",
                        modifier = Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (filtered.isEmpty()) item {
                    Text("No matching apps", modifier = Modifier.padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(filtered, key = { it.packageName }) { app ->
                    Surface(shape = RoundedCornerShape(16.dp), color = if (app.packageName in selected)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().toggleable(
                            value = app.packageName in selected, role = Role.Checkbox,
                            onValueChange = { selected = if (it) selected + app.packageName else selected - app.packageName },
                        ).padding(horizontal = 12.dp, vertical = 10.dp).testTag("allow-app-${app.packageName}"),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            AppSelectionIcon(app.packageName)
                            Column(Modifier.weight(1f)) {
                                androidx.compose.material3.Text(app.label, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (app.label in duplicateNames) androidx.compose.material3.Text(
                                    app.packageName, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Checkbox(checked = app.packageName in selected, onCheckedChange = null)
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSave(selected) }, modifier = Modifier.weight(1f).testTag("media-app-save")) {
                    Text("Save selection")
                }
            }
        }
    }
}

@Composable
private fun AppSelectionIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap = remember(context, packageName) {
        try {
            context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
    else ToolGlyphIcon(ToolGlyph.APPS, modifier = Modifier.size(40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}
