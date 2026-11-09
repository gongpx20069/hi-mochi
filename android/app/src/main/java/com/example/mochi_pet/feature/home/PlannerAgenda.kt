package com.example.mochi_pet.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.mochi_pet.core.model.CalendarEvent
import com.example.mochi_pet.core.model.MochiTodo
import com.example.mochi_pet.core.model.TodoStatus
import com.example.mochi_pet.core.schedule.AgentSchedule
import com.example.mochi_pet.core.schedule.AgentScheduleType
import com.example.mochi_pet.core.schedule.ScheduleDay
import com.example.mochi_pet.core.schedule.ScheduleRunState
import com.example.mochi_pet.ui.theme.AgendaKind
import com.example.mochi_pet.ui.theme.agendaColor
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

private enum class AgendaFilter(val label: String, val kind: AgendaKind?) {
    ALL("All", null), USER("My schedule", AgendaKind.USER), MOCHI("Mochi tasks", AgendaKind.MOCHI), JOURNAL("Journal", AgendaKind.JOURNAL),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlannerAgenda(
    date: LocalDate,
    today: LocalDate,
    monthMode: Boolean,
    state: PlannerSurfaceState,
    onDate: (LocalDate) -> Unit,
    onMonth: (YearMonth) -> Unit,
    onToday: () -> Unit,
    actions: PlannerUiActions,
    onCompleteTodo: (String) -> Unit,
    onRun: (String) -> Unit,
    onRemove: (String) -> Unit,
    journalDay: @Composable (LocalDate) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(monthMode) }
    LaunchedEffect(monthMode, date) { expanded = monthMode }
    var filter by rememberSaveable { mutableStateOf(AgendaFilter.ALL) }
    var adding by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var userEditor by remember { mutableStateOf(false) }
    var taskEditor by remember { mutableStateOf(false) }
    var editEvent by remember { mutableStateOf<CalendarEvent?>(null) }
    var editTodo by remember { mutableStateOf<MochiTodo?>(null) }
    var editTask by remember { mutableStateOf<AgentSchedule?>(null) }
    var details by remember { mutableStateOf<ScheduleDay?>(null) }
    if (userEditor) UserScheduleEditor(date, editEvent, editTodo, actions) { userEditor = false }
    if (taskEditor) ScheduledTaskEditor(editTask, date, actions,
        onRemove = { editTask?.let { onRemove(it.id) } }, onDismiss = { taskEditor = false })
    val rows = remember(state, filter) { agendaRows(state, filter) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)) {
        item("heading") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Agenda", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                if (date != today) TextButton(onClick = onToday) { Text("Back to today") }
                FilledTonalButton(onClick = { adding = true }) { Text("+ New") }
            }
        }
        item("dates") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (expanded) onMonth(YearMonth.from(date).minusMonths(1)) else onDate(date.minusWeeks(1)) }) {
                        Text("Previous")
                    }
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
                        MaterialText(date.format(uiDateFormatter("MMMM yyyy", "yyyy年M月")) + if (expanded) " ▴" else " ▾")
                    }
                    TextButton(onClick = { if (expanded) onMonth(YearMonth.from(date).plusMonths(1)) else onDate(date.plusWeeks(1)) }) {
                        Text("Next")
                    }
                }
                AgendaDates(date, today, expanded, state) { expanded = false; onDate(it) }
            }
        }
        item("filters") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgendaFilter.entries.forEach { option ->
                    val accent = option.kind?.let { agendaColor(it) } ?: MaterialTheme.colorScheme.primary
                    FilterChip(selected = filter == option, onClick = { filter = option },
                        label = { Text(option.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent.copy(alpha = 0.16f),
                            selectedLabelColor = accent))
                }
            }
        }
        item("day") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MaterialText(date.format(uiDateFormatter("MMM d · EEEE", "M月d日 · EEEE")),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (filter == AgendaFilter.MOCHI) TextButton(onClick = { managing = true }) { Text("Manage all tasks") }
            }
        }
        state.errorMessage?.let { error -> item("error") { MaterialText(error, color = MaterialTheme.colorScheme.error) } }
        if (state.isLoading) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else {
            if (filter in setOf(AgendaFilter.ALL, AgendaFilter.JOURNAL) && date < today) {
                item("journal-$date") { journalDay(date) }
            }
            if (filter == AgendaFilter.JOURNAL && (date >= today || date !in state.journalDates)) item("journal-empty") {
                AgendaEmpty(if (date >= today) "Journals appear after the day ends" else "No journal for this day")
            }
            if (rows.isEmpty() && filter != AgendaFilter.JOURNAL) item("empty") { AgendaEmpty("No arrangements for this day") }
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is AgendaRow.Event -> {
                        val event = row.value
                        UserScheduleCard(event.title,
                            if (event.allDay) localizeUiText("All day") else agendaTime(event.startAt),
                            event.completedAt != null, event.recurrenceRule != null,
                            onEdit = { editEvent = event; editTodo = null; userEditor = true },
                            onComplete = { actions.completeUser(event) },
                            highlighted = event.id in state.highlightIds || event.occurrenceId in state.highlightIds)
                    }
                    is AgendaRow.Todo -> {
                        val todo = row.value
                        UserScheduleCard(todo.content, todo.dueAt?.let { agendaTime(it) }
                            ?: localizeUiText("All day"), todo.status == TodoStatus.COMPLETED, false,
                            onEdit = { editTodo = todo; editEvent = null; userEditor = true },
                            onComplete = { onCompleteTodo(todo.id) }, highlighted = todo.id in state.highlightIds)
                    }
                    is AgendaRow.Task -> ScheduleAgendaCard(row.value, date < today,
                        onDetails = { details = row.value },
                        onAction = {
                            val run = row.value.runs.lastOrNull()
                            if (run != null) actions.showRun(run)
                            else row.value.plan?.let { if (date < today) details = row.value else onRun(it.id) }
                        })
                }
            }
        }
    }
    if (adding) ModalBottomSheet(onDismissRequest = { adding = false }) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("New arrangement", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { adding = false; editEvent = null; editTodo = null; userEditor = true },
                modifier = Modifier.fillMaxWidth()) { Text("My schedule", color = agendaColor(AgendaKind.USER)) }
            TextButton(onClick = { adding = false; editTask = null; taskEditor = true },
                modifier = Modifier.fillMaxWidth()) { Text("Mochi tasks", color = agendaColor(AgendaKind.MOCHI)) }
        }
    }
    if (managing) ModalBottomSheet(onDismissRequest = { managing = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Manage all tasks", style = MaterialTheme.typography.titleLarge) }
            if (state.allSchedules.isEmpty()) item { Text("No Mochi tasks") }
            items(state.allSchedules, key = { it.id }) { plan ->
                Card(onClick = { managing = false; editTask = plan; taskEditor = true }) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MaterialText(plan.name, fontWeight = FontWeight.SemiBold)
                        Text(if (plan.enabled) "Scheduled" else "Paused or ended", color = agendaColor(AgendaKind.MOCHI))
                    }
                }
            }
        }
    }
    details?.let { selected ->
        val day = state.taskDays.firstOrNull { it.id == selected.id } ?: selected
        ModalBottomSheet(onDismissRequest = { details = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MaterialText(day.name, style = MaterialTheme.typography.headlineSmall) }
                item { MaterialText(day.runs.lastOrNull()?.prompt ?: day.plan?.prompt.orEmpty()) }
                if (day.runs.isEmpty()) item { Text("Projected from the current rule; no execution record") }
                day.plan?.let { plan ->
                    item { TextButton(onClick = { details = null; onRun(plan.id) }, modifier = Modifier.fillMaxWidth()) { Text("Run now") } }
                    item { TextButton(onClick = { details = null; editTask = plan; taskEditor = true },
                        modifier = Modifier.fillMaxWidth()) { Text("Edit future plan") } }
                    item { TextButton(onClick = { details = null; actions.showResults(plan) },
                        modifier = Modifier.fillMaxWidth()) { Text("All execution results") } }
                }
                items(day.runs.reversed(), key = { it.id }) { run ->
                    TextButton(onClick = { details = null; actions.showRun(run) }, modifier = Modifier.fillMaxWidth()) {
                        MaterialText(plannerTime(run.createdAt, ZoneId.systemDefault()) + " · " + localizeUiText(run.state.name.lowercase().replaceFirstChar { it.uppercase() }))
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaDates(selected: LocalDate, today: LocalDate, expanded: Boolean, state: PlannerSurfaceState, onDate: (LocalDate) -> Unit) {
    val month = YearMonth.from(selected)
    val start = (if (expanded) month.atDay(1) else selected).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val count = if (expanded) ((month.lengthOfMonth() + month.atDay(1).dayOfWeek.value - 2) / 7 + 1) * 7 else 7
    Column {
        Row(Modifier.fillMaxWidth()) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        (0 until count).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { offset ->
                    val day = start.plusDays(offset.toLong())
                    val chosen = day == selected
                    val labels = buildList {
                        add(day.toString())
                        if (day == today) add(localizeUiText("Today"))
                        if (day in state.userDates) add(localizeUiText("My schedule"))
                        if (day in state.taskDates) add(localizeUiText("Mochi tasks"))
                        if (day in state.journalDates) add(localizeUiText("Daily journal"))
                    }.joinToString(", ")
                    Surface(onClick = { onDate(day) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                        .semantics { contentDescription = labels },
                        shape = RoundedCornerShape(14.dp),
                        color = if (chosen) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface,
                        border = if (day == today) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null) {
                        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            MaterialText(day.dayOfMonth.toString(), fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                                color = if (!expanded || YearMonth.from(day) == month) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.height(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                for ((kind, dates) in listOf(AgendaKind.USER to state.userDates, AgendaKind.MOCHI to state.taskDates, AgendaKind.JOURNAL to state.journalDates)) {
                                    if (day in dates) Surface(Modifier.size(4.dp), shape = CircleShape, color = agendaColor(kind)) {}
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaEmpty(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ScheduleAgendaCard(day: ScheduleDay, past: Boolean, onDetails: () -> Unit, onAction: () -> Unit) {
    val accent = agendaColor(AgendaKind.MOCHI)
    Card(onClick = onDetails, shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.example.mochi_pet.ui.theme.AgendaIcon(AgendaKind.MOCHI)
                Text("Mochi tasks", color = accent, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                MaterialText(agendaTime(day.time), style = MaterialTheme.typography.labelMedium)
            }
            MaterialText(day.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            day.plan?.let { plan ->
                if (plan.type == AgentScheduleType.EVERY) MaterialText(
                    localizeUiText("Interval (minutes)") + ": ${plan.intervalMinutes}", style = MaterialTheme.typography.bodySmall)
                else if (plan.type != AgentScheduleType.ONCE) Text("Repeats", style = MaterialTheme.typography.bodySmall)
                if (!past) plan.nextRunAt?.let { next ->
                    MaterialText(localizeUiText("Next execution") + " · " + plannerTime(next, plan.timezone),
                        style = MaterialTheme.typography.bodySmall, color = accent)
                }
            }
            if (day.runs.isEmpty() && past) Text("Planned · no execution record", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (day.runs.isNotEmpty()) {
                val success = day.runs.count { it.state == ScheduleRunState.SUCCESS }
                val failed = day.runs.count { it.state in setOf(ScheduleRunState.FAILED, ScheduleRunState.INTERRUPTED) }
                if (day.runs.any { it.state == ScheduleRunState.RUNNING }) Text("Running", color = accent)
                else if (day.runs.any { it.state == ScheduleRunState.QUEUED }) Text("Queued", color = accent)
                MaterialText("${localizeUiText("Executions")}: ${day.runs.size} · ${localizeUiText("Success")}: $success · ${localizeUiText("Failed")}: $failed",
                    style = MaterialTheme.typography.bodySmall, color = if (failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) {
                Text(if (day.runs.isNotEmpty()) "Results" else if (past) "Details" else "Run now", color = accent)
            }
        }
    }
}

@Composable
private fun agendaTime(instant: Instant): String =
    java.time.format.DateTimeFormatter.ofPattern(
        if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm a",
        LocalLocale.current.platformLocale,
    ).withZone(ZoneId.systemDefault()).format(instant)

internal sealed interface AgendaRow {
    val key: String
    val time: Instant
    data class Event(val value: CalendarEvent) : AgendaRow {
        override val key = "event:${value.occurrenceId}"
        override val time = if (value.allDay) Instant.MIN else value.startAt
    }
    data class Todo(val value: MochiTodo) : AgendaRow {
        override val key = "todo:${value.id}"
        override val time = value.dueAt ?: Instant.MIN
    }
    data class Task(val value: ScheduleDay) : AgendaRow {
        override val key = "task:${value.id}"
        override val time = value.time
    }
}

internal fun chronologicalAgenda(state: PlannerSurfaceState): List<AgendaRow> = buildList {
    addAll(state.events.map { AgendaRow.Event(it) })
    addAll(state.todos.map { AgendaRow.Todo(it) })
    addAll(state.taskDays.map { AgendaRow.Task(it) })
}.sortedWith(compareBy<AgendaRow> { it.time }.thenBy { it.key })

private fun agendaRows(state: PlannerSurfaceState, filter: AgendaFilter): List<AgendaRow> = chronologicalAgenda(state).filter {
    when (filter) {
        AgendaFilter.ALL -> true
        AgendaFilter.USER -> it is AgendaRow.Event || it is AgendaRow.Todo
        AgendaFilter.MOCHI -> it is AgendaRow.Task
        AgendaFilter.JOURNAL -> false
    }
}
