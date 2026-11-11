package com.example.mochi_pet.core.memory

import androidx.room.withTransaction
import com.example.mochi_pet.core.database.MochiDatabase
import com.example.mochi_pet.core.database.entity.AgentMemoryEntity
import com.example.mochi_pet.core.database.entity.DailyJournalEntity
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.Serializable

data class RecallAccess(val enabled: Boolean, val revision: Long)
internal const val MAX_RECALL_SEARCH_OFFSET = 10_000

@Serializable
data class RecallRequest(
    val operation: String,
    val source: String = "journals",
    val query: String = "",
    val start: String? = null,
    val end: String? = null,
    val limit: Int = 5,
    val offset: Int = 0,
    val ref: String? = null,
    val neighbors: Int = 1,
)

@Serializable
data class RecallRecord(
    val ref: String,
    val source: String,
    val text: String,
    val timestamp: String? = null,
    val role: String? = null,
    val turnId: String? = null,
    val origin: String,
    val sourceRunId: String? = null,
    val date: String? = null,
    val timezone: String? = null,
    val windowStart: String? = null,
    val windowEnd: String? = null,
    val title: String? = null,
    val generatedAt: String? = null,
    val status: String? = null,
    val textOffset: Int = 0,
    val truncated: Boolean = false,
)

@Serializable
data class RecallResult(
    val records: List<RecallRecord>,
    val nextOffset: Int? = null,
    val matching: String? = null,
    val neighbors: Int? = null,
    val refineSearch: Boolean = false,
)

interface MemoryRecallStore {
    suspend fun access(): RecallAccess
    suspend fun search(request: RecallRequest): RecallResult
    suspend fun read(request: RecallRequest): RecallResult
}

class RecallUnavailable(val changed: Boolean = false) : IllegalStateException()

class RoomMemoryRecallStore(
    private val database: MochiDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : MemoryRecallStore {
    private val dao = database.memoryRecallDao()
    private val journal = database.journalDao()

    override suspend fun access(): RecallAccess {
        val settings = journal.settings()
        return RecallAccess(settings?.enabled == true, settings?.revision ?: 0)
    }

    override suspend fun search(request: RecallRequest): RecallResult = database.withTransaction {
        val terms = MemoryLexicalSearch.prepareQuery(request.query)
        require(request.query.isBlank() || terms.matchQuery.isNotBlank()) { "query needs searchable words" }
        val startDate = request.start?.let { date(it) }
        val endDate = request.end?.let { date(it) }
        val startTime = request.start?.let { instant(it).toEpochMilli() } ?: Long.MIN_VALUE
        val endTime = request.end?.let { instant(it).toEpochMilli() } ?: Long.MAX_VALUE
        require(startTime < endTime) { "start must precede the exclusive end" }
        if (request.source == "journals") {
            require(listOfNotNull(request.start, request.end).all { it.length == 10 }) {
                "Journal bounds must be YYYY-MM-DD dates; use returned windowStart/windowEnd for conversations"
            }
        }
        val today = clock.instant().atZone(zone()).toLocalDate()
        suspend fun find(match: String?, limit: Int, offset: Int): List<RecallRecord> =
            if (request.source == "journals") {
                val from = (startDate ?: LocalDate.of(1, 1, 1)).toString()
                val until = minOf(endDate ?: today, today).toString()
                val rows = if (match == null) dao.journals(from, until, limit, offset)
                else dao.searchJournals(match, from, until, limit, offset)
                rows.map { it.record() }
            } else {
                val rows = if (match == null) dao.conversations(startTime, endTime, limit, offset)
                else dao.searchConversations(match, startTime, endTime, limit, offset)
                rows.map { it.record() }
            }
        val preferred = terms.preferredMatchQuery
        val match = when {
            request.query.isBlank() -> null
            preferred != null && find(preferred, 1, 0).isNotEmpty() -> preferred
            else -> terms.matchQuery
        }
        val rows = find(match, request.limit + 1, request.offset)
        val next = (request.offset + request.limit).takeIf { rows.size > request.limit }
        RecallResult(
            records = rows.take(request.limit).map { record ->
                val hit = terms.terms.sortedByDescending(String::length)
                    .firstNotNullOfOrNull { term -> record.text.indexOf(term, ignoreCase = true).takeIf { it >= 0 } }
                val from = safeBoundary(record.text, ((hit ?: 0) - 80).coerceAtLeast(0))
                val to = safeBoundary(record.text, (from + 400).coerceAtMost(record.text.length))
                record.copy(text = record.text.substring(from, to), textOffset = from,
                    truncated = from != 0 || to != record.text.length)
            },
            nextOffset = next?.takeIf { it <= MAX_RECALL_SEARCH_OFFSET },
            matching = when { match == null -> "date"; match == preferred -> "all_primary_terms"; else -> "any_term" },
            refineSearch = next != null && next > MAX_RECALL_SEARCH_OFFSET,
        )
    }

    override suspend fun read(request: RecallRequest): RecallResult = database.withTransaction {
        val ref = requireNotNull(request.ref)
        val rows = when {
            ref.startsWith("journal:") -> {
                val key = ref.removePrefix("journal:")
                val date = key.substringBefore('@')
                val entry = journal.get(date)?.takeIf { it.status != "DELETED" && it.text.isNotBlank() }
                    ?: throw RecallUnavailable()
                if (entry.record().ref != ref) throw RecallUnavailable(changed = true)
                require(LocalDate.parse(date) < clock.instant().atZone(zone()).toLocalDate()) { "Journal day is not closed" }
                listOf(entry.record())
            }
            ref.startsWith("conversation:") -> {
                val hit = dao.message(ref.removePrefix("conversation:")) ?: throw RecallUnavailable()
                suspend fun complete(message: AgentMemoryEntity): List<AgentMemoryEntity> {
                    val turn = message.turnId?.let { dao.turn(it) } ?: listOf(message)
                    check(turn.size <= 32) { "Stored conversation turn exceeds the recall limit" }
                    return turn
                }
                val selected = complete(hit)
                val adjacent = if (request.neighbors == 0) emptyList() else {
                    val first = selected.first()
                    val last = selected.last()
                    listOfNotNull(dao.before(first.createdAtEpochMillis, first.id),
                        dao.after(last.createdAtEpochMillis, last.id))
                        .distinctBy { it.turnId ?: it.id }
                        .flatMap { complete(it) }
                }
                (selected + adjacent).distinctBy(AgentMemoryEntity::id)
                    .sortedWith(compareBy(AgentMemoryEntity::createdAtEpochMillis, AgentMemoryEntity::id))
                    .map { it.record() }
            }
            else -> throw IllegalArgumentException("Use an exact ref returned by search")
        }
        val total = rows.sumOf { it.text.length }
        require(request.offset < total || request.offset == 0) { "Read offset exceeds the saved text" }
        var skip = request.offset
        var remaining = 6_000
        var consumed = 0
        val chunks = buildList {
            for (record in rows) {
                if (skip >= record.text.length) {
                    skip -= record.text.length
                    continue
                }
                if (remaining == 0) break
                require(safeBoundary(record.text, skip) == skip) { "Use nextOffset without changing it" }
                val end = safeBoundary(record.text, (skip + remaining).coerceAtMost(record.text.length))
                val size = end - skip
                if (size == 0) break
                add(record.copy(text = record.text.substring(skip, end), textOffset = skip,
                    truncated = skip != 0 || end != record.text.length))
                consumed += size
                remaining -= size
                skip = 0
                if (end < record.text.length) break
            }
        }
        RecallResult(chunks, (request.offset + consumed).takeIf { it < total }, neighbors = request.neighbors)
    }

    private fun instant(value: String): Instant =
        if (value.length == 10) LocalDate.parse(value).atStartOfDay(zone()).toInstant()
        else OffsetDateTime.parse(value).toInstant()

    private fun date(value: String): LocalDate =
        if (value.length == 10) LocalDate.parse(value) else instant(value).atZone(zone()).toLocalDate()

    private fun AgentMemoryEntity.record() = RecallRecord(
        ref = "conversation:$id", source = "conversations", text = content,
        timestamp = Instant.ofEpochMilli(createdAtEpochMillis).toString(), role = role, turnId = turnId,
        origin = if (sourceRunId == null) "saved_conversation" else "scheduled_task_copy",
        sourceRunId = sourceRunId,
    )

    private fun DailyJournalEntity.record(): RecallRecord {
        val day = LocalDate.parse(date)
        val tz = ZoneId.of(timezone)
        return RecallRecord(
            ref = "journal:$date@${generatedAt ?: updatedAt}", source = "journals", text = text,
            origin = "derived_journal", date = date, timezone = timezone, title = title, status = status,
            generatedAt = generatedAt?.let { Instant.ofEpochMilli(it).toString() },
            windowStart = day.atStartOfDay(tz).toInstant().toString(),
            windowEnd = day.plusDays(1).atStartOfDay(tz).toInstant().toString(),
        )
    }
}

private fun safeBoundary(text: String, index: Int): Int =
    if (index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()) index - 1 else index
