package com.example.mochi_pet.core.journal

import androidx.room.withTransaction
import com.example.mochi_pet.core.database.MochiDatabase
import com.example.mochi_pet.core.database.PlannerStore
import com.example.mochi_pet.core.database.entity.DailyJournalEntity
import com.example.mochi_pet.core.schedule.RoomAgentScheduleRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.map

class JournalRepository(
    private val database: MochiDatabase,
    private val planner: PlannerStore,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val dao = database.journalDao()
    val settings = dao.observeSettings().map { JournalSettings(it?.enabled == true, it?.since?.let(LocalDate::parse)) }
    fun observe(date: LocalDate) = dao.observe(date.toString()).map { it?.toDomain() }
    fun observeMonth(month: java.time.YearMonth) =
        dao.observeMonth(month.atDay(1).toString(), month.plusMonths(1).atDay(1).toString())
            .map { dates -> dates.map(LocalDate::parse).toSet() }
    suspend fun get(date: LocalDate) = dao.get(date.toString())?.toDomain()
    fun today(): LocalDate = clock.instant().atZone(zone()).toLocalDate()
    suspend fun setEnabled(enabled: Boolean) = dao.setEnabled(enabled, today().toString(), clock.millis())
    suspend fun delete(date: LocalDate) = dao.delete(date.toString(), clock.millis())
    suspend fun interrupt(entry: DailyJournal) = dao.interrupt(entry.date.toString(), entry.updatedAt.toEpochMilli(), clock.millis())
    suspend fun retry(date: LocalDate) {
        require(date < today()) { "Journals are available for past days only" }
        check(dao.retry(date.toString(), clock.millis()) == 1) { "Enable Daily journal before retrying" }
    }

    suspend fun prepare(): List<DailyJournal> {
        val settings = dao.settings() ?: return emptyList()
        if (!settings.enabled) return emptyList()
        val today = today()
        val start = maxOf(LocalDate.parse(requireNotNull(settings.since)), today.minusDays(7))
        val dates = generateSequence(start) { it.plusDays(1) }.takeWhile { it < today }.toList()
        dao.prepare(dates.map {
            DailyJournalEntity(it.toString(), zone().id, "WAITING", "", "", null, clock.millis(), null)
        }, requireNotNull(settings.since))
        return dao.pending().map { it.toDomain() }.filter { it.date < today }
    }

    suspend fun claim(date: LocalDate, claim: String, manual: Boolean = false): DailyJournal? {
        val entry = get(date) ?: return null
        if (date >= today() || clock.instant() < date.plusDays(1).atStartOfDay(entry.zone).toInstant()) return null
        if (!manual && date < today().minusDays(7)) {
            dao.expire(date.toString(), clock.millis())
            return null
        }
        if (dao.claim(date.toString(), claim, clock.millis()) != 1) return null
        return get(date)
    }

    suspend fun finish(entry: DailyJournal, claim: String, result: JournalDraft, evidence: JournalEvidence) {
        val value = result.validated()
        val noData = evidence.conversations.isEmpty() && evidence.userSchedules.isEmpty() && evidence.mochiTasks.isEmpty()
        require(!noData || value.status == "empty") { "A journal without sources must be empty" }
        dao.publish(entry.date.toString(), claim, if (noData) "NO_DATA" else if (value.status == "empty") "EMPTY" else "READY",
            value.title, value.text, clock.millis(), evidence.conversations.size, evidence.userSchedules.size, evidence.mochiTasks.size)
    }

    suspend fun fail(entry: DailyJournal, claim: String, message: String, retryable: Boolean = false) {
        dao.finish(entry.date.toString(), claim, if (retryable) "WAITING" else "FAILED",
            entry.title, entry.text, message, clock.millis())
    }

    suspend fun evidence(entry: DailyJournal): JournalEvidence = database.withTransaction {
        val start = entry.date.atStartOfDay(entry.zone).toInstant()
        val end = entry.date.plusDays(1).atStartOfDay(entry.zone).toInstant()
        val conversations = mutableListOf<JournalRecord>()
        var offset = 0
        var chars = 0L
        do {
            val page = dao.conversations(start.toEpochMilli(), end.toEpochMilli(), offset)
            for (row in page) {
                chars += row.content.length
                require(chars <= 512_000) { "Too much material for automatic journal generation" }
                conversations += JournalRecord(Instant.ofEpochMilli(row.createdAtEpochMillis).toString(),
                    row.content, speaker = row.role)
            }
            offset += page.size
        } while (page.size == 100)
        val events = planner.listCalendarEvents(start, end).filter { it.createdAt < end }.map {
            JournalRecord(it.startAt.toString(), it.description.orEmpty(), title = it.title,
                recurring = it.recurrenceRule != null,
                status = if (it.completedAt != null && it.completedAt < end) "completed" else "planned")
        }
        val todos = planner.listTodosInRange(entry.date, entry.date.plusDays(1)).filter { it.createdAt < end }.map {
            JournalRecord(it.dueAt?.toString() ?: entry.date.toString(), it.content,
                recurring = false, status = if (it.completedAt != null && it.completedAt < end) "completed" else "planned")
        }
        val plans = database.agentScheduleDao().listAll().associateBy { it.id }
        val executions = dao.runs(start.toEpochMilli(), end.toEpochMilli())
        val runs = executions.map {
            val finished = it.finishedAt?.let(Instant::ofEpochMilli)
            JournalRecord((finished?.takeIf { value -> value < end } ?: Instant.ofEpochMilli(it.createdAt)).toString(),
                if (finished != null && finished < end) it.output ?: it.error.orEmpty() else it.prompt,
                title = it.name, recurring = plans[it.scheduleId]?.let { plan -> plan.type != "ONCE" },
                status = when {
                    finished != null && finished < end -> it.state.lowercase()
                    it.startedAt != null && it.startedAt < end.toEpochMilli() -> "running"
                    else -> "queued"
                })
        }
        val executedIds = executions.mapTo(mutableSetOf()) { it.scheduleId }
        val planned = RoomAgentScheduleRepository(database.agentScheduleDao(), clock).agenda(entry.date, entry.zone)
            .filter { it.id !in executedIds && it.plannedAt != null }.map { day ->
            val plan = requireNotNull(day.plan)
            JournalRecord(requireNotNull(day.plannedAt).toString(), plan.prompt, title = plan.name,
                recurring = plan.type != com.example.mochi_pet.core.schedule.AgentScheduleType.ONCE,
                status = "projected_from_current_rule_not_verified")
        }
        JournalEvidence(conversations, events + todos, runs + planned)
    }
}

private fun DailyJournalEntity.toDomain() = DailyJournal(LocalDate.parse(date), ZoneId.of(timezone),
    JournalStatus.valueOf(status), title, text, error, Instant.ofEpochMilli(updatedAt),
    conversationCount, userScheduleCount, taskCount, generatedAt?.let(Instant::ofEpochMilli))
