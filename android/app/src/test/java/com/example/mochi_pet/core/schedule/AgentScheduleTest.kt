package com.example.mochi_pet.core.schedule

import com.example.mochi_pet.core.database.MochiDatabase
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentScheduleTest {
    @Test
    fun `daily schedule uses its local timezone`() {
        val next = nextAgentScheduleRun(
            draft = AgentScheduleDraft(
                name = "Morning",
                prompt = "Brief me",
                type = AgentScheduleType.DAILY,
                localTime = LocalTime.of(8, 0),
                timezone = ZoneId.of("Asia/Shanghai"),
            ),
            after = Instant.parse("2026-08-07T00:30:00Z"),
        )

        assertEquals(
            Instant.parse("2026-08-08T00:00:00Z"),
            next,
        )
    }

    @Test
    fun `weekly schedule selects next matching day`() {
        val next = nextAgentScheduleRun(
            draft = AgentScheduleDraft(
                name = "Weekly",
                prompt = "Weekly brief",
                type = AgentScheduleType.WEEKLY,
                localTime = LocalTime.of(9, 0),
                daysOfWeek = setOf(DayOfWeek.MONDAY),
                timezone = ZoneOffset.UTC,
            ),
            after = Instant.parse("2026-08-07T10:00:00Z"),
        )

        assertEquals(
            Instant.parse("2026-08-10T09:00:00Z"),
            next,
        )
    }

    @Test
    fun `repository stores and advances recurring schedule`() = runBlocking {
        val database = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext(),
            MochiDatabase::class.java,
        ).build()
        try {
            val clock = Clock.fixed(
                Instant.parse("2026-08-07T00:00:00Z"),
                ZoneOffset.UTC,
            )
            val repository = RoomAgentScheduleRepository(
                database.agentScheduleDao(),
                clock,
            )
            val created = repository.set(
                draft = AgentScheduleDraft(
                    name = "Brief",
                    prompt = "Create a brief",
                    type = AgentScheduleType.EVERY,
                    intervalMinutes = 60,
                    timezone = ZoneOffset.UTC,
                ),
            )
            val completed = repository.recordResult(
                created.id,
                AgentScheduleResult.SUCCESS,
                Instant.parse("2026-08-07T01:00:00Z"),
            )

            assertEquals(
                Instant.parse("2026-08-07T02:00:00Z"),
                completed.nextRunAt,
            )
            assertEquals(
                listOf(created.id),
                repository.listForDate(LocalDate.of(2026, 8, 7))
                    .map(AgentSchedule::id),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `one time schedule disables after completion`() = runBlocking {
        val database = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext(),
            MochiDatabase::class.java,
        ).build()
        try {
            val repository = RoomAgentScheduleRepository(
                database.agentScheduleDao(),
                Clock.fixed(
                    Instant.parse("2026-08-07T00:00:00Z"),
                    ZoneOffset.UTC,
                ),
            )
            val created = repository.set(
                draft = AgentScheduleDraft(
                    name = "Once",
                    prompt = "Run once",
                    type = AgentScheduleType.ONCE,
                    runAt = Instant.parse("2026-08-07T02:00:00Z"),
                    timezone = ZoneOffset.UTC,
                ),
            )
            val completed = repository.recordResult(
                created.id,
                AgentScheduleResult.SUCCESS,
                Instant.parse("2026-08-07T02:01:00Z"),
            )

            assertFalse(completed.enabled)
            assertEquals(null, completed.nextRunAt)
        } finally {
            database.close()
        }

        @Test
        fun `schedule tool supports concise set list run and remove operations`() =
            runBlocking {
                val database = androidx.room.Room.inMemoryDatabaseBuilder(
                    androidx.test.core.app.ApplicationProvider
                        .getApplicationContext(),
                    MochiDatabase::class.java,
                ).build()
                try {
                    val repository = RoomAgentScheduleRepository(
                        database.agentScheduleDao(),
                        Clock.fixed(
                            Instant.parse("2026-08-07T00:00:00Z"),
                            ZoneOffset.UTC,
                        ),
                    )
                    val controller = RecordingScheduleController()
                    val tool = ManageMochiScheduleTool(repository, controller)
                    val context = com.example.mochi_pet.core.agent.tool
                        .ToolExecutionContext(
                            currentDate = LocalDate.of(2026, 8, 7),
                            currentSurface =
                                com.example.mochi_pet.core.model.MochiSurface.Today,
                        )
                    val created = tool.execute(
                        buildJsonObject {
                            put("operation", "set")
                            put("name", "Morning brief")
                            put("prompt", "Summarize the morning")
                            put("schedule_type", "daily")
                            put("local_time", "08:00")
                            put("timezone", "Asia/Shanghai")
                        },
                        context,
                    )
                    val id = created.data!!.jsonObject["id"]!!
                        .jsonPrimitive.content

                    val listed = tool.execute(
                        buildJsonObject { put("operation", "list") },
                        context,
                    )
                    assertEquals(
                        1,
                        listed.data!!.jsonObject["schedules"]!!.jsonArray.size,
                    )
                    tool.execute(
                        buildJsonObject {
                            put("operation", "run")
                            put("id", id)
                        },
                        context,
                    )
                    tool.execute(
                        buildJsonObject {
                            put("operation", "remove")
                            put("id", id)
                        },
                        context,
                    )

                    assertEquals(listOf(id), controller.ran)
                    assertEquals(listOf(id), controller.cancelled)
                    assertEquals(1, controller.synced.size)
                } finally {
                    database.close()
                }
            }
    }

    private class RecordingScheduleController : AgentScheduleController {
        val synced = mutableListOf<AgentSchedule>()
        val cancelled = mutableListOf<String>()
        val ran = mutableListOf<String>()

        override suspend fun sync(schedule: AgentSchedule) {
            synced += schedule
        }

        override suspend fun cancel(id: String) {
            cancelled += id
        }

        override suspend fun runNow(id: String) {
            ran += id
        }
    }
}
