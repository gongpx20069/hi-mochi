package com.example.mochi_pet.core.schedule

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.mochi_pet.core.database.MochiDatabase
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolRegistry
import com.example.mochi_pet.core.model.MochiSurface
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AgentScheduleTest {
    private lateinit var db: MochiDatabase
    private val now = Instant.parse("2026-08-07T00:00:00Z")
    private var current = now
    private val clock = object : Clock() {
        override fun instant() = current
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
    }
    private lateinit var repository: RoomAgentScheduleRepository
    private fun draft() = AgentScheduleDraft("Weather", "Read weather", AgentScheduleType.EVERY,
        intervalMinutes = 15, timezone = ZoneOffset.UTC)
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MochiDatabase::class.java).build()
        repository = RoomAgentScheduleRepository(db.agentScheduleDao(), clock)
    }
    @After fun close() { db.close() }

    @Test fun `daily and weekly follow wall clock and skip a DST gap`() {
        assertEquals(Instant.parse("2026-08-08T00:00:00Z"), nextAgentScheduleRun(
            draft().copy(type = AgentScheduleType.DAILY, localTime = LocalTime.of(8, 0), timezone = ZoneId.of("Asia/Shanghai")),
            Instant.parse("2026-08-07T00:30:00Z")))
        assertEquals(Instant.parse("2026-08-10T09:00:00Z"), nextAgentScheduleRun(
            draft().copy(type = AgentScheduleType.WEEKLY, localTime = LocalTime.of(9, 0), daysOfWeek = setOf(DayOfWeek.MONDAY)),
            Instant.parse("2026-08-07T10:00:00Z")))
        assertEquals(Instant.parse("2026-03-09T06:30:00Z"), nextAgentScheduleRun(
            draft().copy(type = AgentScheduleType.DAILY, localTime = LocalTime.of(2, 30), timezone = ZoneId.of("America/New_York")),
            Instant.parse("2026-03-08T06:59:00Z")))
    }

    @Test fun `finite defaults persist and content edits do not restart expiry or next run`() = runBlocking {
        val plan = repository.set(draft = draft())
        assertEquals(plan.nextRunAt!!.plusSeconds(7 * 86400).minusMillis(1), plan.endsAt)
        current = now.plusSeconds(60)
        val updated = repository.set(plan.id, plan.toDraft().copy(prompt = "Include humidity"))
        assertEquals(plan.nextRunAt, updated.nextRunAt)
        assertEquals(plan.endsAt, updated.endsAt)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.set(plan.id, plan.toDraft().copy(prompt = "Stale edit")) }
        }
        val daily = repository.set(draft = draft().copy(type = AgentScheduleType.DAILY, localTime = LocalTime.of(8, 0)))
        assertEquals(daily.nextRunAt!!.plusSeconds(30 * 86400).minusMillis(1), daily.endsAt)
    }

    @Test fun `queue advances durably and manual runs deduplicate without changing planned count`() = runBlocking {
        val plan = repository.set(draft = draft())
        val manual = requireNotNull(repository.queue(plan.id, true, now))
        assertEquals(manual.id, repository.queue(plan.id, true, now)?.id)
        assertEquals(plan.nextRunAt, repository.get(plan.id)?.nextRunAt)
        assertEquals(0, repository.get(plan.id)?.plannedRuns)
        repository.startRun(manual.id)
        repository.finishRun(manual.id, ScheduleRunState.SUCCESS, output = "Manual weather")
        current = requireNotNull(plan.nextRunAt)
        val due = requireNotNull(repository.queue(plan.id, false, current))
        assertNotNull(repository.get(plan.id)?.nextRunAt)
        assertNull(repository.queue(plan.id, false, current))
        repository.startRun(due.id)
        current = current.plusSeconds(40)
        repository.finishRun(due.id, ScheduleRunState.CANCELLED)
        assertEquals(current.plusSeconds(900), repository.get(plan.id)?.nextRunAt)
        assertEquals(AgentScheduleResult.CANCELLED, repository.get(plan.id)?.lastResult)
        assertEquals(2, repository.runs(plan.id).size)
    }

    @Test fun `old running result cannot overwrite a newer plan and queued versions are cancelled`() = runBlocking {
        val plan = repository.set(draft = draft())
        val run = requireNotNull(repository.queue(plan.id, true, now))
        repository.startRun(run.id)
        val changed = repository.set(plan.id, plan.toDraft().copy(prompt = "New content"))
        repository.finishRun(run.id, ScheduleRunState.SUCCESS, "Old content result")
        assertEquals(changed, repository.get(plan.id))
        assertEquals("Read weather", repository.run(run.id)?.prompt)
        val queued = requireNotNull(repository.queue(plan.id, true, now))
        repository.set(plan.id, changed.toDraft().copy(prompt = "Another edit"))
        assertNull(repository.startRun(queued.id))
        assertEquals(ScheduleRunState.CANCELLED, repository.run(queued.id)?.state)
    }

    @Test fun `count exhaustion and once stop while manual results remain queryable after deletion`() = runBlocking {
        val plan = repository.set(draft = draft().copy(maxRuns = 1))
        current = requireNotNull(plan.nextRunAt)
        val run = requireNotNull(repository.queue(plan.id, false, current))
        assertFalse(requireNotNull(repository.get(plan.id)).enabled)
        repository.startRun(run.id)
        repository.finishRun(run.id, ScheduleRunState.SUCCESS, "Done")
        assertNull(repository.get(plan.id)?.nextRunAt)
        repository.remove(plan.id)
        assertEquals("Done", repository.run(run.id)?.output)
        val once = repository.set(draft = draft().copy(type = AgentScheduleType.ONCE, runAt = current.plusSeconds(60)))
        repository.queue(once.id, false, current.plusSeconds(60))
        assertFalse(requireNotNull(repository.get(once.id)).enabled)
    }

    @Test fun `tool create update results and delete use explicit current contracts`() = runBlocking {
        val controller = object : AgentScheduleController {
            override suspend fun sync(schedule: AgentSchedule) = Unit
            override suspend fun cancel(id: String) = Unit
            override suspend fun runNow(id: String) = requireNotNull(repository.queue(id, true, current))
        }
        val registry = ToolRegistry(listOf(ManageMochiScheduleTool(repository, controller)))
        val context = ToolExecutionContext(LocalDate.of(2026, 8, 7), MochiSurface.Today)
        suspend fun call(operation: String, id: String? = null) = registry.execute("manage_mochi_schedule",
            buildJsonObject {
                put("operation", operation)
                if (id != null) put("id", id)
                put("name", "Weather"); put("prompt", "Read weather"); put("schedule_type", "every")
                put("interval_minutes", 15); put("revision", 0); put("confirmed", true)
            }, context)
        val created = call("create")
        assertEquals("ok", created.status)
        val id = created.data!!.jsonObject.getValue("id").jsonPrimitive.content
        assertEquals("error", call("create", id).status)
        assertEquals("ok", call("update", id).status)
        val queued = call("run", id)
        assertNotNull(queued.data!!.jsonObject["run_id"])
        assertEquals("ok", call("results", id).status)
        assertEquals("ok", call("delete", id).status)
        assertEquals("error", call("set", id).status)
    }

    @Test fun `disk reopen retains the future plan and does not reclaim a running receipt`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "recovery.db"
        context.getDatabasePath(name).parentFile?.mkdirs()
        var disk = Room.databaseBuilder(context, MochiDatabase::class.java, name).build()
        try {
            var store = RoomAgentScheduleRepository(disk.agentScheduleDao(), clock)
            val plan = store.set(draft = draft())
            current = requireNotNull(plan.nextRunAt)
            val run = requireNotNull(store.queue(plan.id, false, current))
            store.startRun(run.id)
            disk.close()
            disk = Room.databaseBuilder(context, MochiDatabase::class.java, name).build()
            store = RoomAgentScheduleRepository(disk.agentScheduleDao(), clock)
            assertNotNull(store.get(plan.id)?.nextRunAt)
            assertEquals(ScheduleRunState.RUNNING, store.run(run.id)?.state)
            assertNull(store.startRun(run.id))
            store.finishRun(run.id, ScheduleRunState.INTERRUPTED, error = "Process ended")
            assertTrue(requireNotNull(store.get(plan.id)).enabled)
            assertNotNull(store.get(plan.id)?.nextRunAt)
            assertEquals(ScheduleRunState.INTERRUPTED, store.runs(plan.id).single().state)
        } finally {
            disk.close()
            context.deleteDatabase(name)
        }
    }
}
