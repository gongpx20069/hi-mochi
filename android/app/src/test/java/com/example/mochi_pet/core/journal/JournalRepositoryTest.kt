package com.example.mochi_pet.core.journal

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.mochi_pet.core.database.MochiDatabase
import com.example.mochi_pet.core.database.PlannerRepository
import com.example.mochi_pet.core.database.entity.AgentMemoryEntity
import com.example.mochi_pet.core.database.entity.AgentScheduleRunEntity
import com.example.mochi_pet.core.model.CalendarEventDraft
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class JournalRepositoryTest {
    private var now = Instant.parse("2026-11-09T10:00:00Z")
    private var zone: ZoneId = ZoneOffset.UTC
    private val clock = object : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), MochiDatabase::class.java,
    ).build()
    private val planner = PlannerRepository(database.calendarEventDao(), database.todoDao(), clock)
    private val repository = JournalRepository(database, planner, clock) { zone }
    private val day = LocalDate.of(2026, 11, 9)
    private val draft = JournalDraft("written", "Launch", "Chose a launch date.")
    @After fun close() { database.close() }

    private suspend fun yesterday(): DailyJournal {
        repository.setEnabled(true)
        now = Instant.parse("2026-11-10T00:01:00Z")
        return repository.prepare().single()
    }

    @Test fun `default off no early generation and unique bounded catchup from enabling`(): Unit = runBlocking {
        assertFalse(repository.settings.first().enabled)
        assertTrue(repository.prepare().isEmpty())
        repository.setEnabled(true)
        assertTrue(repository.prepare().isEmpty())
        now = Instant.parse("2026-11-10T00:01:00Z")
        assertEquals(listOf(day), repository.prepare().map { it.date })
        assertEquals(listOf(day), repository.prepare().map { it.date })
        assertNull(repository.claim(day.plusDays(1), "today"))
        now = Instant.parse("2026-11-25T00:01:00Z")
        val pending = repository.prepare()
        assertEquals(7, pending.count { it.date >= LocalDate.of(2026, 11, 18) })
        assertNull(repository.claim(day, "expired"))
        assertEquals(JournalStatus.FAILED, repository.get(day)?.status)
        repository.retry(day)
        assertNotNull(repository.claim(day, "manual", manual = true))
    }

    @Test fun `claims fence concurrent work disable reenable deletion and stale replies`(): Unit = runBlocking {
        yesterday()
        val first = requireNotNull(repository.claim(day, "first"))
        assertNull(repository.claim(day, "other"))
        assertNotNull(repository.claim(day, "first"))
        repository.setEnabled(false)
        repository.setEnabled(true)
        repository.finish(first, "first", draft)
        assertEquals(JournalStatus.FAILED, repository.get(day)?.status)
        repository.retry(day)
        val second = requireNotNull(repository.claim(day, "second"))
        repository.finish(first, "first", draft)
        assertEquals(JournalStatus.GENERATING, repository.get(day)?.status)
        repository.delete(day)
        repository.finish(second, "second", draft)
        repository.prepare()
        assertEquals(JournalStatus.DELETED, repository.get(day)?.status)
        assertEquals("", repository.get(day)?.text)
        assertFalse(repository.observeMonth(java.time.YearMonth.from(day)).first().contains(day))
    }

    @Test fun `regeneration retains good text on failure and abandoned work can be retried`(): Unit = runBlocking {
        yesterday()
        repository.finish(requireNotNull(repository.claim(day, "one")), "one", draft)
        repository.retry(day)
        val retry = requireNotNull(repository.claim(day, "two"))
        repository.fail(retry, "two", "Network unavailable")
        assertEquals(draft.text, repository.get(day)?.text)
        assertEquals(JournalStatus.FAILED, repository.get(day)?.status)
        repository.retry(day)
        val abandoned = requireNotNull(repository.claim(day, "three"))
        repository.interrupt(abandoned)
        assertEquals(JournalStatus.FAILED, repository.get(day)?.status)
        repository.retry(day)
        repository.finish(requireNotNull(repository.claim(day, "four")), "four", JournalDraft("empty", "", ""))
        assertEquals(JournalStatus.EMPTY, repository.get(day)?.status)
        assertEquals("", repository.get(day)?.text)
    }

    @Test fun `saved evidence pages whole natural day excludes scheduled copies and uses cutoff states`(): Unit = runBlocking {
        repository.setEnabled(true)
        val event = planner.createCalendarEvent(CalendarEventDraft("Review", startAt = now, timezone = zone,
            recurrenceRule = "FREQ=DAILY;COUNT=3"))
        val start = Instant.parse("2026-11-09T00:00:00Z").toEpochMilli()
        val end = start + 86_400_000
        database.agentMemoryDao().insertMemoryRows((0..104).map {
            AgentMemoryEntity("message-$it", null, "user", "user_msg", "Message $it", "", start + it)
        } + listOf(
            AgentMemoryEntity("before", null, "user", "user_msg", "Yesterday", "", start - 1),
            AgentMemoryEntity("after", null, "user", "user_msg", "Tomorrow", "", end),
            AgentMemoryEntity("copy", null, "assistant", "assistant_msg", "Copied run", "", start + 1, "run"),
        ))
        database.agentScheduleDao().saveRun(AgentScheduleRunEntity(
            "run", "plan", 0, "Fetch", "Read a report", false, start,
            "SUCCESS", start, start + 1, end + 1, "Tomorrow's result", null,
        ))
        database.agentScheduleDao().saveRun(AgentScheduleRunEntity(
            "queued", "plan2", 0, "Queued", "Not yet started", false, start,
            "SUCCESS", start, end + 1, end + 2, "Later output", null,
        ))
        now = Instant.ofEpochMilli(end + 1)
        planner.completeCalendarOccurrence(event.occurrenceId, event.revision)
        planner.createCalendarEvent(CalendarEventDraft("Retrospective addition", startAt = Instant.ofEpochMilli(start), timezone = zone))
        val evidence = repository.evidence(repository.prepare().single())
        assertEquals(105, evidence.conversations.size)
        assertEquals("Message 104", evidence.conversations.last().text)
        assertEquals("planned", evidence.userSchedules.single().status)
        assertEquals(true, evidence.userSchedules.single().recurring)
        assertEquals("running", evidence.mochiTasks.single { it.title == "Fetch" }.status)
        assertEquals("queued", evidence.mochiTasks.single { it.title == "Queued" }.status)
        assertFalse(evidence.mochiTasks.any { it.text.contains("output") || it.text.contains("result") })
    }

    @Test fun `day window honors DST and timezone rollback cannot claim an unfinished stored day`(): Unit = runBlocking {
        zone = ZoneId.of("America/New_York")
        now = Instant.parse("2026-11-01T04:00:00Z")
        repository.setEnabled(true)
        val start = now
        val end = Instant.parse("2026-11-02T05:00:00Z")
        database.agentMemoryDao().insertMemoryRows(listOf(start, end.minusMillis(1), end).mapIndexed { index, instant ->
            AgentMemoryEntity("dst-$index", null, "user", "user_msg", "Message $index", "", instant.toEpochMilli())
        })
        now = end
        val entry = repository.prepare().single()
        assertEquals(2, repository.evidence(entry).conversations.size)
        zone = ZoneId.of("Asia/Tokyo")
        now = end.minusSeconds(3600)
        assertNull(repository.claim(entry.date, "early"))
        now = end
        assertNotNull(repository.claim(entry.date, "ready"))
    }
}
