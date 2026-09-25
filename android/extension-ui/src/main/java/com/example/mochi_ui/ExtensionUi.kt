package com.example.mochi_ui

import android.content.Context
import android.content.res.Configuration
import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

// All application consumers disable language splitting; library lint cannot see their bundle configuration.
@SuppressLint("AppBundleLocaleChanges")
fun Context.extensionUiContext(languageTag: String?): Context =
    createConfigurationContext(Configuration(resources.configuration).apply {
        if (languageTag in setOf("zh", "zh-CN", "en", "en-US")) {
            setLocale(Locale.forLanguageTag(if (languageTag == "zh") "zh-CN" else languageTag))
        }
    })

@Composable
fun ExtensionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun ExtensionHeading(
    title: String,
    status: String,
    trailing: @Composable () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
fun ExtensionSetupScreen(
    title: String,
    steps: List<String>,
    step: Int,
    backLabel: String,
    onBack: () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean = true,
    busy: Boolean = false,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val gutter = if (windowWidth >= 600.dp) 24.dp else 20.dp
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize().padding(horizontal = gutter)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(backLabel) }
                    Text(
                        title, Modifier.weight(1f).padding(start = 8.dp).semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        steps.forEachIndexed { index, label ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LinearProgressIndicator(
                                    progress = { if (index <= step) 1f else 0f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    "${index + 1}. $label",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (index == step) FontWeight.Bold else FontWeight.Normal,
                                    color = if (index == step) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    ExtensionCard(content = content)
                }
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    footer?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(
                        onClick = onPrimary,
                        enabled = primaryEnabled && !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(20.dp),
                    ) { Text(primaryLabel) }
                }
            }
        }
    }
}
