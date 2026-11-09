package com.example.mochi_pet.feature.home

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mochi_pet.core.model.CalendarEvent
import com.example.mochi_pet.core.model.CalendarEventDraft
import com.example.mochi_pet.core.model.MochiTodo
import com.example.mochi_pet.core.model.OccurrenceScope
import com.example.mochi_pet.core.schedule.AgentSchedule
import com.example.mochi_pet.core.schedule.AgentScheduleDraft
import com.example.mochi_pet.core.schedule.AgentScheduleRun
import com.example.mochi_pet.core.schedule.AgentScheduleType
import com.example.mochi_pet.core.schedule.toDraft
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

data class PlannerUiActions(
    val saveUser: suspend (CalendarEvent?, MochiTodo?, CalendarEventDraft, OccurrenceScope) -> String?,
    val completeUser: (CalendarEvent) -> Unit,
    val deleteUser: suspend (CalendarEvent?, MochiTodo?, OccurrenceScope) -> Unit,
    val saveTask: suspend (AgentSchedule?, AgentScheduleDraft) -> Unit,
    val results: suspend (String, Int) -> List<AgentScheduleRun>,
    val result: suspend (String) -> AgentScheduleRun,
    val showResults: (AgentSchedule) -> Unit,
    val changes: Flow<Unit>,
    val showRun: (AgentScheduleRun) -> Unit = {},
)

data class PlannerResultSelection(val scheduleId: String, val title: String, val runId: String? = null)

@Composable
internal fun UserScheduleCard(
    title: String,
    time: String,
    complete: Boolean,
    repeating: Boolean,
    onEdit: () -> Unit,
    onComplete: () -> Unit,
    highlighted: Boolean = false,
) {
    val accent = com.example.mochi_pet.ui.theme.agendaColor(com.example.mochi_pet.ui.theme.AgendaKind.USER)
    Card(onClick = onEdit, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(
        containerColor = if (highlighted) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainer,
    )) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                com.example.mochi_pet.ui.theme.AgendaIcon(com.example.mochi_pet.ui.theme.AgendaKind.USER)
                Text("My schedule", style = MaterialTheme.typography.labelMedium, color = accent, modifier = Modifier.weight(1f))
                MaterialText(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (repeating || complete) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (repeating) Text("Repeats", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (complete) Text("Completed", style = MaterialTheme.typography.labelSmall)
            }
            val completionLabel = localizeUiText(if (complete) "Completed" else "Complete this occurrence")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MaterialText(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = if (complete) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Checkbox(checked = complete, onCheckedChange = { if (!complete) onComplete() }, enabled = !complete,
                    colors = CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = accent),
                    modifier = Modifier.semantics { contentDescription = completionLabel })
            }
        }
    }
}

@Composable
internal fun plannerTime(instant: Instant, zone: ZoneId): String {
    val context = LocalContext.current
    val locale = androidx.compose.ui.platform.LocalLocale.current.platformLocale
    return DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "MM-dd HH:mm" else "MM-dd h:mm a", locale)
        .withZone(zone).format(instant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserScheduleEditor(
    date: LocalDate,
    event: CalendarEvent?,
    todo: MochiTodo?,
    actions: PlannerUiActions,
    onDismiss: () -> Unit,
) {
    val zone = event?.timezone ?: ZoneId.systemDefault()
    val initial = event?.startAt ?: todo?.dueAt
    var title by remember { mutableStateOf(event?.title ?: todo?.content.orEmpty()) }
    var selectedDate by remember { mutableStateOf(initial?.atZone(zone)?.toLocalDate() ?: todo?.scheduledDate ?: date) }
    var time by remember { mutableStateOf(initial?.atZone(zone)?.toLocalTime() ?: LocalTime.of(9, 0)) }
    var allDay by remember { mutableStateOf(event?.allDay ?: (todo?.dueAt == null)) }
    var frequency by remember { mutableStateOf(event?.recurrenceRule?.substringAfter("FREQ=")?.substringBefore(';') ?: "NONE") }
    var scope by remember { mutableStateOf(OccurrenceScope.THIS) }
    var reminder by remember { mutableStateOf(event?.reminderAt != null || todo?.reminderAt != null) }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val coroutine = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().widthIn(max = 680.dp).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (event == null && todo == null) "New user schedule" else "Edit user schedule",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(title, { title = it }, label = { Text("Content") }, modifier = Modifier.fillMaxWidth(),
                enabled = !busy && !saved, minLines = 2, maxLines = 5)
            PlannerDateTime(selectedDate, time, !allDay, { selectedDate = it }, { time = it }, enabled = !busy && !saved)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("All day", modifier = Modifier.weight(1f))
                Switch(allDay, { allDay = it }, enabled = !busy && !saved)
            }
            if (event?.recurrenceRule == null) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("NONE", "DAILY", "WEEKLY", "MONTHLY", "YEARLY")) { choice ->
                        FilterChip(selected = frequency == choice, onClick = { frequency = choice }, enabled = !busy && !saved,
                            label = { Text(recurrenceLabel(choice)) })
                    }
                }
                if (frequency != "NONE") Text("Repeats for 90 days by default; low-frequency schedules include the next occurrence.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val rule = com.example.mochi_pet.core.model.CalendarRecurrence.parse(event.recurrenceRule, zone)
                Text(recurrenceLabel(rule.frequency), style = MaterialTheme.typography.bodyMedium)
                if (rule.interval > 1) MaterialText(localizeUiText("Interval") + " · ${rule.interval}",
                    style = MaterialTheme.typography.bodySmall)
                rule.until?.let { MaterialText(localizeUiText("Ends") + " · " + it.atZone(zone).toLocalDate()) }
                rule.count?.let { MaterialText(localizeUiText("Occurrence limit") + " · $it") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(scope == OccurrenceScope.THIS, { scope = OccurrenceScope.THIS }, label = { Text("This occurrence") })
                    FilterChip(scope == OccurrenceScope.FUTURE, { scope = OccurrenceScope.FUTURE }, label = { Text("This and future") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reminder")
                    Text("System notification only; no automatic speech.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(reminder, { reminder = it }, enabled = !busy && !saved)
            }
            feedback?.let { MaterialText(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(enabled = !busy && title.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                if (saved) {
                    onDismiss()
                } else {
                    coroutine.launch {
                        busy = true
                        feedback = null
                        try {
                            val start = if (allDay) selectedDate.atStartOfDay(zone).toInstant() else selectedDate.atTime(time).atZone(zone).toInstant()
                            val existingReminder = event?.reminderAt ?: todo?.reminderAt
                            val original = event?.startAt ?: todo?.dueAt
                            val remindAt = if (!reminder) null else if (existingReminder != null && original != null) {
                                start.plus(Duration.between(original, existingReminder))
                            } else if (allDay) selectedDate.atTime(9, 0).atZone(zone).toInstant() else start.minusSeconds(600)
                            val duration = if (event?.allDay == false && event.endAt != null) Duration.between(event.startAt, event.endAt) else null
                            val draft = CalendarEventDraft(
                                title = title, description = event?.description, startAt = start,
                                endAt = if (allDay) selectedDate.plusDays(1).atStartOfDay(zone).toInstant() else duration?.let { start.plus(it) },
                                allDay = allDay, timezone = zone,
                                recurrenceRule = event?.recurrenceRule ?: frequency.takeUnless { it == "NONE" }?.let { "FREQ=$it" },
                                location = event?.location, reminderAt = remindAt,
                                priority = event?.priority ?: todo?.priority ?: com.example.mochi_pet.core.model.TodoPriority.NORMAL,
                            )
                            feedback = actions.saveUser(event, todo, draft, scope)
                            saved = true
                            if (feedback == null) onDismiss()
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            feedback = error.message ?: localizeUiText("Could not save")
                        } finally {
                            busy = false
                        }
                    }
                }
            }) { Text(if (saved) "Close" else if (busy) "Saving" else "Save") }
            if ((event != null || todo != null) && !saved) {
                TextButton(enabled = !busy, onClick = { deleting = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete") }
            }
        }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { if (!busy) deleting = false },
        title = { Text("Delete user schedule?") },
        text = { Text(if (scope == OccurrenceScope.FUTURE) "This occurrence and future occurrences will be removed." else "Only this occurrence will be removed.") },
        dismissButton = { TextButton(onClick = { deleting = false }, enabled = !busy) { Text("Cancel") } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            coroutine.launch {
                busy = true
                try {
                    actions.deleteUser(event, todo, scope)
                    onDismiss()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    feedback = error.message
                    deleting = false
                } finally { busy = false }
            }
        }) { Text("Delete") } },
    )
}

@Composable
private fun PlannerDateTime(
    date: LocalDate,
    time: LocalTime,
    showTime: Boolean,
    onDate: (LocalDate) -> Unit,
    onTime: (LocalTime) -> Unit,
    enabled: Boolean,
    showDate: Boolean = true,
) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showDate) OutlinedButton(enabled = enabled, onClick = {
            DatePickerDialog(context, { _, year, month, day -> onDate(LocalDate.of(year, month + 1, day)) },
                date.year, date.monthValue - 1, date.dayOfMonth).show()
        }, modifier = Modifier.weight(1f)) { MaterialText(date.toString()) }
        if (showTime) OutlinedButton(enabled = enabled, onClick = {
            TimePickerDialog(context, { _, hour, minute -> onTime(LocalTime.of(hour, minute)) },
                time.hour, time.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
        }, modifier = Modifier.weight(1f)) {
            MaterialText(time.format(DateTimeFormatter.ofPattern(
                if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",
                androidx.compose.ui.platform.LocalLocale.current.platformLocale,
            )))
        }
    }
}

private fun recurrenceLabel(value: String): String = when (value) {
    "DAILY" -> "Daily"
    "WEEKLY" -> "Weekly"
    "MONTHLY" -> "Monthly"
    "YEARLY" -> "Yearly"
    else -> "Does not repeat"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduledTaskEditor(
    task: AgentSchedule?,
    date: LocalDate,
    actions: PlannerUiActions,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(task?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(task?.prompt.orEmpty()) }
    var type by remember { mutableStateOf(task?.type ?: AgentScheduleType.ONCE) }
    val zone = task?.timezone ?: ZoneId.systemDefault()
    var selectedDate by remember { mutableStateOf(task?.runAt?.atZone(zone)?.toLocalDate() ?: date) }
    var time by remember { mutableStateOf(task?.localTime ?: task?.runAt?.atZone(zone)?.toLocalTime() ?: LocalTime.now().plusHours(1).withSecond(0).withNano(0)) }
    var interval by remember { mutableStateOf((task?.intervalMinutes ?: 15).toString()) }
    var days by remember { mutableStateOf(task?.daysOfWeek?.ifEmpty { setOf(date.dayOfWeek) } ?: setOf(date.dayOfWeek)) }
    var enabled by remember { mutableStateOf(task?.enabled ?: true) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val coroutine = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().widthIn(max = 680.dp).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (task == null) "New Mochi task" else "Edit Mochi task", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            OutlinedTextField(title, { title = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            OutlinedTextField(prompt, { prompt = it }, label = { Text("What should Mochi do?") }, minLines = 3,
                modifier = Modifier.fillMaxWidth(), enabled = !busy)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AgentScheduleType.entries) { choice ->
                    FilterChip(type == choice, { type = choice }, enabled = !busy,
                        label = { Text(when (choice) { AgentScheduleType.ONCE -> "Once"; AgentScheduleType.DAILY -> "Daily"; AgentScheduleType.WEEKLY -> "Weekly"; AgentScheduleType.EVERY -> "Interval" }) })
                }
            }
            if (type == AgentScheduleType.EVERY) {
                OutlinedTextField(interval, { interval = it }, label = { Text("Interval in minutes (at least 15)") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            } else {
                PlannerDateTime(selectedDate, time, true, { selectedDate = it }, { time = it }, !busy, showDate = type == AgentScheduleType.ONCE)
            }
            if (type == AgentScheduleType.WEEKLY) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(java.time.DayOfWeek.entries) { day ->
                    FilterChip(day in days, { days = if (day in days) days - day else days + day }, enabled = !busy,
                        label = { MaterialText(day.getDisplayName(java.time.format.TextStyle.SHORT, androidx.compose.ui.platform.LocalLocale.current.platformLocale)) })
                }
            }
            Text(if (task == null) "Intervals default to one week; daily and weekly tasks default to 30 days."
                else "Edits apply to future runs. The end condition and previous results stay unchanged.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            task?.endsAt?.let { MaterialText(localizeUiText("Ends") + " · " + plannerTime(it, zone)) }
            if (task != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", modifier = Modifier.weight(1f)); Switch(enabled, { enabled = it }, enabled = !busy)
            }
            feedback?.let { MaterialText(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !busy && title.isNotBlank() && prompt.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                coroutine.launch {
                    busy = true
                    feedback = null
                    try {
                        val draft = (task?.toDraft() ?: AgentScheduleDraft(title, prompt, type)).copy(
                            name = title, prompt = prompt, type = type, timezone = zone, enabled = enabled,
                            runAt = if (type == AgentScheduleType.ONCE) selectedDate.atTime(time).atZone(zone).toInstant() else null,
                            localTime = if (type in setOf(AgentScheduleType.DAILY, AgentScheduleType.WEEKLY)) time else null,
                            daysOfWeek = days, intervalMinutes = if (type == AgentScheduleType.EVERY) interval.toLong() else null,
                        )
                        actions.saveTask(task, draft)
                        onDismiss()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        feedback = error.message ?: localizeUiText("Could not save")
                    } finally { busy = false }
                }
            }) { Text(if (busy) "Saving" else "Save") }
            if (task != null) TextButton(onClick = { deleting = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Delete") }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete Mochi task?") },
        text = { Text("Future executions stop. Saved results are retained.") },
        confirmButton = { TextButton(onClick = { onRemove(); onDismiss() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleResultsSheet(selection: PlannerResultSelection, actions: PlannerUiActions, onDismiss: () -> Unit) {
    var page by remember(selection) { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var runs by remember(selection) { mutableStateOf<List<AgentScheduleRun>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selection, page, refresh) {
        suspend fun load() {
            try {
                runs = if (selection.runId != null) listOf(actions.result(selection.runId)) else actions.results(selection.scheduleId, page * 20)
                error = null
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message
            } finally { loading = false }
        }
        load()
        actions.changes.collect { load() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 650.dp).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Execution results", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                MaterialText(selection.title, style = MaterialTheme.typography.titleMedium)
            }
            if (loading) item { CircularProgressIndicator() }
            error?.let { message -> item { MaterialText(message, color = MaterialTheme.colorScheme.error) } }
            if (!loading && runs.isEmpty()) item { Text("No executions yet") }
            items(runs, key = AgentScheduleRun::id) { run ->
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(run.state.name.lowercase().replaceFirstChar(Char::uppercase), color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge)
                        MaterialText(plannerTime(run.createdAt, ZoneId.systemDefault()), style = MaterialTheme.typography.labelMedium)
                        Text(if (run.manual) "Manual execution" else "Scheduled execution", style = MaterialTheme.typography.labelSmall)
                        run.output?.let { SelectionContainer { MaterialText(it, style = MaterialTheme.typography.bodyMedium) } }
                        run.error?.let { MaterialText(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { page-- }, enabled = page > 0) { Text("Previous") }
                    TextButton(onClick = { refresh++ }) { Text("Refresh") }
                    TextButton(onClick = { page++ }, enabled = runs.size == 20) { Text("Next") }
                }
            }
        }
    }
}
