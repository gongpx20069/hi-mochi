package com.example.mochi_pet.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.mochi_pet.core.agentlink.AgentLinkChatLink
import com.example.mochi_pet.core.diagnostics.CheckStatus
import com.example.mochi_pet.core.diagnostics.ConfigurationCheck
import com.example.mochi_pet.core.diagnostics.RepairTarget
import com.example.mochi_pet.core.extensions.TermuxTaskView
import com.example.mochi_pet.core.schedule.AgentScheduleResult
import com.example.mochi_pet.core.schedule.AgentScheduleType
import com.example.mochi_pet.core.tasks.AgentTaskView
import com.example.mochi_pet.core.tasks.TaskStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskCenterDialog(
    state: TaskCenterUiState,
    agents: List<AgentTaskView>,
    termux: List<TermuxTaskView>,
    onClose: () -> Unit,
    onPage: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onCheck: () -> Unit,
    onCancelCheck: () -> Unit,
    onRepair: (RepairTarget) -> Unit,
    onStopAgent: (AgentTaskView) -> Unit,
    onStopSchedule: (String) -> Unit,
    onTermux: (String, String) -> Unit,
    onChat: (AgentLinkChatLink) -> Unit,
    onConversation: () -> Unit,
    onPlanner: () -> Unit,
    onRunSchedule: (String) -> Unit,
    onScheduleEnabled: (String, Boolean) -> Unit,
) {
    val tasks = remember(state, agents, termux) { taskDashboard(state, agents, termux) }
    var filter by rememberSaveable { mutableStateOf(TaskFilter.ALL) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val selected = tasks.firstOrNull { it.key == selectedKey }
    LaunchedEffect(selectedKey, selected == null) {
        if (selectedKey != null && selected == null) selectedKey = null
    }
    val onBack = {
        when {
            selectedKey != null -> selectedKey = null
            showHelp -> showHelp = false
            state.diagnosticsPage && !state.diagnosticsStandalone -> onPage(false)
            else -> onClose()
        }
    }
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onBack)
        Surface(Modifier.fillMaxSize().testTag("task-dashboard"), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.background(Brush.verticalGradient(listOf(Color(0xFF211B2D), MaterialTheme.colorScheme.background)))) {
                Column(
                    Modifier.align(Alignment.TopCenter).safeDrawingPadding().widthIn(max = 680.dp)
                        .fillMaxSize().padding(horizontal = 20.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("MOCHI / CONTROL", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary, letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing)
                            Text(if (state.diagnosticsPage) "Configuration check" else "Task center",
                                Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold)
                        }
                        if (!state.diagnosticsPage) TextButton({ onPage(true) }) { Text("Check setup") }
                        TextButton(onClick = onBack) { Text(if (state.diagnosticsPage) "Back" else "Close") }
                    }
                    if (state.diagnosticsPage) {
                        DiagnosticsPanel(state, onCheck, onCancelCheck, onRepair, Modifier.weight(1f))
                    } else {
                        LazyColumn(
                            Modifier.weight(1f).testTag("task-center-list"),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            item(key = "overview") {
                                TaskOverview(tasks, onSelect = { filter = it })
                            }
                            item(key = "utilities") {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text(if (state.refreshing) "Syncing task status" else "Updates while open",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(onRefresh, enabled = !state.refreshing) { Text("Refresh") }
                                    TextButton({ showHelp = true }) { Text("How it works") }
                                }
                                Row(Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TaskFilter.entries.forEach { option ->
                                        FilterChip(
                                            selected = filter == option, onClick = { filter = option },
                                            modifier = Modifier.testTag("task-filter-${option.name}"),
                                            label = { Text(option.label) },
                                        )
                                    }
                                }
                            }
                            if (state.errors.isNotEmpty()) item(key = "errors") {
                                DashboardPanel(accent = MaterialTheme.colorScheme.error) {
                                    Text("Some statuses could not be updated", fontWeight = FontWeight.SemiBold)
                                    Text("Earlier snapshots remain visible. Open details or check the connection.",
                                        style = MaterialTheme.typography.bodySmall)
                                    TextButton({ onPage(true) }) { Text("Configuration check") }
                                    state.errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                            val visible = tasks.filter { it.matches(filter) }
                            if (visible.isEmpty()) item(key = "empty") {
                                DashboardPanel {
                                    Text(if (tasks.isEmpty()) "Ready when you are" else "Nothing in this view",
                                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(if (tasks.isEmpty()) "Tell Mochi what to do. Your tasks will appear here."
                                        else "Try another filter to see your other tasks.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (tasks.isEmpty()) Button(onConversation) { Text("Ask Mochi") }
                                    else TextButton({ filter = TaskFilter.ALL }) { Text("All tasks") }
                                }
                            }
                            items(visible, key = { it.key }) { task ->
                                DashboardTaskCard(task, onClick = { selectedKey = task.key })
                            }
                            item(key = "check-entry") {
                                Surface(
                                    onClick = { onPage(true) }, shape = RoundedCornerShape(22.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                                ) {
                                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        SourceMark("SYS", MaterialTheme.colorScheme.secondary)
                                        Column(Modifier.weight(1f)) {
                                            Text("Configuration check", fontWeight = FontWeight.SemiBold)
                                            Text("Find connection and setup issues", style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text("+", color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (selected != null) {
            ModalBottomSheet(
                onDismissRequest = { selectedKey = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                TaskDetails(
                    selected, state, onStopAgent, onStopSchedule, onTermux, onChat,
                    onConversation, onPlanner, onRunSchedule, onScheduleEnabled,
                    onInspectConfiguration = { selectedKey = null; onPage(true) },
                    onDismiss = { selectedKey = null },
                )
            }
        }
        if (showHelp) {
            ModalBottomSheet(
                onDismissRequest = { showHelp = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("How it works", style = MaterialTheme.typography.titleLarge)
                    Text("Agent runs update live during this app session. Scheduled work and Termux refresh while this page is visible. Remote chats refresh only on request. Closing this page does not stop tasks.")
                    Text("Stopping a parent cancels its Subagent, not already submitted Termux or remote tasks. Stop those in their own task controls. Detached Shell processes may survive Stop.")
                    Button({ showHelp = false }, Modifier.fillMaxWidth()) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun TaskOverview(tasks: List<DashboardTask>, onSelect: (TaskFilter) -> Unit) {
    val active = tasks.count { it.active }
    val attention = tasks.count { it.attention }
    val finished = tasks.count { it.matches(TaskFilter.HISTORY) }
    Surface(shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f))) {
        Column(
            Modifier.background(Brush.linearGradient(listOf(Color(0xFF35263F), Color(0xFF1B202D), Color(0xFF211B28))))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MochiRadar(active > 0)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when {
                        active > 0 -> "Mochi is on it"
                        attention > 0 -> "A little attention needed"
                        else -> "Standing by"
                    }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (active > 0) "Your tasks are moving forward" else "One place for every task",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetric(active, "In progress", MaterialTheme.colorScheme.secondary, Modifier.weight(1f)) { onSelect(TaskFilter.ACTIVE) }
                SummaryMetric(attention, "Needs attention", MaterialTheme.colorScheme.primary, Modifier.weight(1f)) { onSelect(TaskFilter.ATTENTION) }
                SummaryMetric(finished, "Finished", Color(0xFF9BD8C6), Modifier.weight(1f)) { onSelect(TaskFilter.HISTORY) }
            }
        }
    }
}

@Composable
private fun SummaryMetric(count: Int, label: String, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick, modifier.heightIn(min = 80.dp), shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.16f)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(count.toString(), color = accent, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MochiRadar(active: Boolean) {
    val accent = MaterialTheme.colorScheme.secondary
    val peach = MaterialTheme.colorScheme.primary
    val rotation = if (active) {
        val transition = rememberInfiniteTransition(label = "task-radar")
        val value by transition.animateFloat(0f, 360f,
            infiniteRepeatable(tween(8_000, easing = LinearEasing), RepeatMode.Restart), label = "orbit")
        value
    } else 32f
    Canvas(Modifier.size(68.dp)) {
        drawCircle(brush = Brush.radialGradient(listOf(peach.copy(alpha = 0.25f), Color.Transparent)))
        drawCircle(accent.copy(alpha = 0.16f), radius = size.minDimension * 0.47f, style = Stroke(1.dp.toPx()))
        drawArc(accent.copy(alpha = 0.8f), rotation, 85f, false,
            topLeft = Offset(size.width * 0.03f, size.height * 0.03f),
            size = Size(size.width * 0.94f, size.height * 0.94f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawOval(peach.copy(alpha = 0.94f), topLeft = Offset(size.width * 0.22f, size.height * 0.28f),
            size = Size(size.width * 0.56f, size.height * 0.48f))
        val eyeColor = Color(0xFF362036)
        listOf(0.40f, 0.60f).forEach { x ->
            drawLine(eyeColor, Offset(size.width * x, size.height * 0.46f),
                Offset(size.width * x, size.height * 0.53f), 3.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun DashboardTaskCard(task: DashboardTask, onClick: () -> Unit) {
    val accent = taskAccent(task)
    Surface(
        onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(task.key),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = if (task.active) 0.3f else 0.12f)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SourceMark(when (task.reference) {
                    is TaskReference.Agent -> if (task.source == "Subagent") "SUB" else "AI"
                    is TaskReference.Schedule -> "TIME"
                    is TaskReference.Shell -> ">_"
                    is TaskReference.Remote -> "LINK"
                }, accent)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(task.source, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("+", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(task.statusLabel, accent, Modifier.weight(1f, fill = false))
                task.time?.let { Text(taskTime(it), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (task.active) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(), color = accent,
                    trackColor = accent.copy(alpha = 0.08f),
                )
            }
            Text(when (val reference = task.reference) {
                is TaskReference.Agent -> reference.value.tool?.let { localizeUiText("Using tool") + " " + it }
                    ?: if (task.active) "Tap to see progress" else "Tap to view the result"
                is TaskReference.Schedule -> if (task.active) "Tap to see progress" else "Tap to manage this schedule"
                is TaskReference.Shell -> "Tap for output and controls"
                is TaskReference.Remote -> "Remote snapshot"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SourceMark(text: String, accent: Color) {
    Surface(shape = RoundedCornerShape(14.dp), color = accent.copy(alpha = 0.12f), modifier = Modifier.size(44.dp)) {
        Canvas(Modifier.padding(11.dp)) {
            val stroke = 1.8.dp.toPx()
            fun point(x: Float, y: Float) = Offset(size.width * x, size.height * y)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
                drawLine(accent, point(x1, y1), point(x2, y2), stroke, StrokeCap.Round)
            }
            when (text) {
                ">_" -> { line(.12f, .28f, .40f, .50f); line(.40f, .50f, .12f, .72f); line(.55f, .75f, .9f, .75f) }
                "TIME" -> {
                    drawCircle(accent, size.minDimension * .43f, style = Stroke(stroke))
                    line(.5f, .2f, .5f, .5f); line(.5f, .5f, .72f, .6f)
                }
                "LINK" -> {
                    drawCircle(accent, size.minDimension * .24f, point(.3f, .35f), style = Stroke(stroke))
                    drawCircle(accent, size.minDimension * .24f, point(.7f, .65f), style = Stroke(stroke))
                    line(.32f, .38f, .68f, .62f)
                }
                "SYS" -> {
                    drawCircle(accent, size.minDimension * .43f, style = Stroke(stroke))
                    line(.25f, .5f, .44f, .68f); line(.44f, .68f, .75f, .32f)
                }
                else -> {
                    line(.2f, .7f, .5f, .25f); line(.5f, .25f, .8f, .7f); line(.2f, .7f, .8f, .7f)
                    listOf(point(.2f, .7f), point(.5f, .25f), point(.8f, .7f)).forEach {
                        drawCircle(accent, size.minDimension * .12f, it)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(modifier, color = accent.copy(alpha = 0.1f), shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(5.dp).background(accent, CircleShape))
            Text(label, style = MaterialTheme.typography.labelMedium, color = accent)
        }
    }
}

@Composable
private fun taskAccent(task: DashboardTask): Color = when {
    task.attention -> MaterialTheme.colorScheme.error
    task.status == TaskStatus.SUCCEEDED -> Color(0xFF9BD8C6)
    task.active -> MaterialTheme.colorScheme.secondary
    task.reference is TaskReference.Shell -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun DashboardPanel(
    accent: Color = MaterialTheme.colorScheme.outline,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun TaskDetails(
    task: DashboardTask, state: TaskCenterUiState,
    onStopAgent: (AgentTaskView) -> Unit, onStopSchedule: (String) -> Unit,
    onTermux: (String, String) -> Unit, onChat: (AgentLinkChatLink) -> Unit,
    onConversation: () -> Unit, onPlanner: () -> Unit,
    onRunSchedule: (String) -> Unit, onScheduleEnabled: (String, Boolean) -> Unit,
    onInspectConfiguration: () -> Unit, onDismiss: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 24.dp, vertical = 12.dp).testTag("task-details"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(task.source, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
                Text(task.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onDismiss) { Text("Close details") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusPill(task.statusLabel, taskAccent(task))
            when (val reference = task.reference) {
                is TaskReference.Agent -> {
                    task.time?.let { Text(taskTime(it), style = MaterialTheme.typography.bodySmall) }
                    reference.value.tool?.let { Text(it, fontFamily = FontFamily.Monospace) }
                    if (task.attention) Text("The run failed. Check configuration and review the conversation before retrying; earlier actions may already have completed.")
                    if (task.active) {
                        Text(if (reference.value.actor == "main") "Stopping this run also cancels its active Subagent. Submitted Shell and remote tasks continue."
                            else "This Subagent belongs to a parent run. Stop affects both, not submitted Shell or remote tasks.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is TaskReference.Schedule -> {
                    val schedule = reference.value
                    Text(schedule.prompt, style = MaterialTheme.typography.bodyMedium)
                    schedule.nextRunAt?.let {
                        DetailLine("Next run", taskTime(it, schedule.timezone) + " · " + schedule.timezone.id)
                    }
                    schedule.lastResult?.let { result ->
                        DetailLine("Last result", localizeUiText(when (result) {
                            AgentScheduleResult.SUCCESS -> "Completed"
                            AgentScheduleResult.FAILED -> "Failed"
                            AgentScheduleResult.CANCELLED -> "Cancelled"
                        }))
                    }
                    if (task.active) {
                        Text("Stops this run without changing the schedule switch.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onConversation) { Text("Open conversation") }
                    TextButton(onPlanner) { Text("Open planner") }
                }
                is TaskReference.Shell -> {
                    val shell = reference.value
                    val data = shell.result.data as? JsonObject
                    val stdout = (data?.get("stdout") as? JsonPrimitive)?.content.orEmpty()
                    val stderr = (data?.get("stderr") as? JsonPrimitive)?.content.orEmpty()
                    Text("Command output", style = MaterialTheme.typography.titleMedium)
                    if (stdout.isEmpty() && stderr.isEmpty()) Text(
                        if (task.active || task.status == TaskStatus.UNKNOWN) "No output yet. Refresh to read the latest result."
                        else "No output was returned.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (stdout.isNotEmpty()) OutputBlock("Output", stdout)
                    if (stderr.isNotEmpty()) OutputBlock("Error output", stderr)
                    if ((data?.get("truncated") as? JsonPrimitive)?.content == "true") Text("Output was truncated.",
                        color = MaterialTheme.colorScheme.error)
                    if (task.active || task.status == TaskStatus.UNKNOWN) {
                        Text("Detached Shell processes may continue after Stop.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    var technical by rememberSaveable(task.key) { mutableStateOf(false) }
                    TextButton({ technical = !technical }) { Text("Technical details") }
                    if (technical) SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DetailLine("Task ID", shell.id)
                            (data?.get("exit_code") as? JsonPrimitive)?.content?.let { DetailLine("Exit code", it) }
                        }
                    }
                }
                is TaskReference.Remote -> {
                    if (reference.link.outcomeUnknown) Text("Remote outcome unknown. Read the chat before resubmitting.")
                    state.remoteCheckedAt?.let { DetailLine("Last checked", taskTime(it)) }
                    Text("Open the linked chat for live progress, results and Stop. Mochi never automatically resubmits a remote task.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (task.attention) TextButton(onInspectConfiguration) { Text("Configuration check") }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.errors.lastOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            when (val reference = task.reference) {
                is TaskReference.Agent -> {
                    Button(onConversation, Modifier.fillMaxWidth()) { Text("Open conversation") }
                    if (task.active) StopButton(if (reference.value.actor == "main") "Stop" else "Stop parent task") {
                        onStopAgent(reference.value)
                    }
                }
                is TaskReference.Schedule -> {
                    val schedule = reference.value
                    val busy = schedule.id in state.workingScheduleIds
                    if (task.active) StopButton("Stop current run", enabled = !busy) { onStopSchedule(schedule.id) }
                    else Button({ onRunSchedule(schedule.id) }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Run now") }
                    if (schedule.enabled || schedule.type != AgentScheduleType.ONCE || schedule.runAt?.isAfter(Instant.now()) == true) {
                        TextButton({ onScheduleEnabled(schedule.id, !schedule.enabled) }, Modifier.fillMaxWidth(), enabled = !busy) {
                            Text(if (schedule.enabled) "Pause future runs" else "Resume schedule")
                        }
                    }
                }
                is TaskReference.Shell -> {
                    val id = reference.value.id
                    val busy = id in state.workingTermuxIds
                    Button({ onTermux(id, "read") }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Refresh output") }
                    if (task.active || task.status == TaskStatus.UNKNOWN) StopButton("Stop", enabled = !busy) { onTermux(id, "stop") }
                    else TextButton({ onTermux(id, "forget") }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Remove finished record") }
                }
                is TaskReference.Remote -> {
                    Button({ onChat(reference.link) }, Modifier.fillMaxWidth(), enabled = state.agentLink.installed) { Text("Open in AgentLink") }
                }
            }
        }
    }
}

@Composable
private fun StopButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(label) }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OutputBlock(label: String, value: String) {
    DashboardPanel {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        SelectionContainer { Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun DiagnosticsPanel(
    state: TaskCenterUiState, onCheck: () -> Unit, onCancel: () -> Unit,
    onRepair: (RepairTarget) -> Unit, modifier: Modifier,
) {
    var showOtherChecks by rememberSaveable { mutableStateOf(false) }
    LazyColumn(modifier.testTag("configuration-check-list"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            DashboardPanel(accent = MaterialTheme.colorScheme.secondary) {
                Text("A clearer path to ready", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Check sends one fixed short request to your saved AI Provider and may consume quota. No conversation, microphone audio or commands are sent. Settings are never changed.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.checking) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondary)
                    Text(state.checkingTitle ?: "Checking", style = MaterialTheme.typography.labelLarge)
                    Button(onCancel, Modifier.fillMaxWidth()) { Text("Cancel check") }
                } else Button(onCheck, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Run configuration check") }
                if (state.checkCancelled) Text("Check cancelled. Results below may be incomplete.", color = MaterialTheme.colorScheme.error)
                state.checkedAt?.let { DetailLine("Last checked", taskTime(it)) }
            }
        }
        val attention = state.checks.filter { it.status == CheckStatus.ATTENTION || it.status == CheckStatus.NOT_TESTED }
        items(attention, key = { it.id }) { check -> ConfigurationCheckCard(check, onRepair) }
        val others = state.checks.filter { it.status == CheckStatus.PASSED || it.status == CheckStatus.DISABLED }
        if (others.isNotEmpty()) item {
            TextButton({ showOtherChecks = !showOtherChecks }, Modifier.fillMaxWidth()) {
                Text(localizeUiText(if (showOtherChecks) "Hide other checks" else "Other checks") + " (${others.size})")
            }
        }
        if (showOtherChecks) items(others, key = { it.id }) { check ->
            ConfigurationCheckCard(check, onRepair)
        }
    }
}

@Composable
internal fun ConfigurationCheckCard(check: ConfigurationCheck, onRepair: (RepairTarget) -> Unit) {
    val accent = when (check.status) {
        CheckStatus.PASSED -> Color(0xFF9BD8C6)
        CheckStatus.ATTENTION -> MaterialTheme.colorScheme.error
        CheckStatus.NOT_TESTED -> MaterialTheme.colorScheme.secondary
        CheckStatus.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    DashboardPanel(accent) {
        Text(check.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        StatusPill(when (check.status) {
            CheckStatus.PASSED -> "Passed"
            CheckStatus.ATTENTION -> "Needs attention"
            CheckStatus.NOT_TESTED -> "Not tested"
            CheckStatus.DISABLED -> "Not enabled"
        }, accent)
        Text(check.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        check.missingRequirements.forEach { Text(it, color = MaterialTheme.colorScheme.secondary) }
        check.repair?.let { target -> TextButton({ onRepair(target) }) { Text("Open related settings") } }
    }
}

private fun taskTime(time: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone).format(time)
