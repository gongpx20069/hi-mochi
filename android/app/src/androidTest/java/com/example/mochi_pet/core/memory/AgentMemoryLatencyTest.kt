package com.example.mochi_pet.core.memory

import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mochi_pet.core.database.MochiDatabase
import com.example.mochi_pet.core.database.dao.AgentMemoryDao
import com.example.mochi_pet.core.database.entity.AgentMemoryEntity
import java.io.File
import java.time.ZoneId
import java.util.UUID
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentMemoryLatencyTest {
    @Test
    fun measureSyntheticHistory(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(
            "Requires explicit opt-in: creates a temporary synthetic history",
            InstrumentationRegistry.getArguments().getString("mochiMemoryLatency") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sizes = InstrumentationRegistry.getArguments()
            .getString("mochiMemoryLatencyMessages")?.let { value ->
                listOf(value.toInt()).also {
                    require(it.single() in listOf(1_000, 10_000, 100_000))
                }
            } ?: listOf(1_000, 10_000, 100_000)
        val name = "memory-latency-${UUID.randomUUID()}.db"
        val templates = (0..9).map { index ->
            val subject = when (index) {
                0 -> "杭州酒店预订预算八百元，需要靠近地铁。"
                1 -> "杭州旅行准备乘坐高铁，暂时不确定出发时间。"
                2 -> "通勤耳机希望佩戴舒适，优先考虑降噪效果。"
                else -> "周末想去公园散步，晚上回来准备晚饭。"
            }
            val content = subject +
                "我们先记录这次安排，比较不同选择，再确认具体时间和所需物品。" +
                "这些只是用于本地性能测量的合成对话，不代表真实用户的偏好或决定。"
            content to searchableMemoryText(content)
        }
        val cases = listOf(
            QueryCase("joint", "杭州 酒店"),
            QueryCase("broad", "安排"),
            QueryCase("fallback", "杭州 航班"),
            QueryCase("no_match", "quasarxylophone", expectRecall = false),
        )
        emit(
            JSONObject()
                .put("kind", "configuration")
                .put("recentTurns", 20)
                .put("warmups", 5)
                .put("samples", 50)
                .put("concurrency", 1)
                .put("messageCounts", JSONArray(sizes))
                .put("meanMessageChars", templates.map { it.first.length }.average())
                .put("meanMessageUtf8Bytes", templates.map { it.first.toByteArray().size }.average()),
        )
        var database = open(context, name)
        var inserted = 0
        try {
            for (size in sizes) {
                while (inserted < size) {
                    val end = minOf(inserted + 1_000, size)
                    database.agentMemoryDao().insertAll(
                        (inserted until end).map { index ->
                            val (content, searchText) = templates[index % templates.size]
                            val role = if (index % 2 == 0) "user" else "assistant"
                            AgentMemoryEntity(
                                id = "fixture-$index",
                                turnId = "turn-${index / 2}",
                                role = role,
                                type = "${role}_msg",
                                content = content,
                                searchText = searchText,
                                createdAtEpochMillis = 1_700_000_000_000L + index,
                            )
                        },
                    )
                    inserted = end
                }
                database.close()
                for (case in cases) {
                    database = open(context, name)
                    val timedDao = TimedMemoryDao(database.agentMemoryDao())
                    val repository = RoomAgentMemoryRepository(
                        timedDao,
                        promptZone = ZoneId.of("Asia/Shanghai"),
                    )
                    val first = measure(repository, timedDao, case)
                    repeat(5) { measure(repository, timedDao, case) }
                    val before = processSample(context)
                    val samples = mutableListOf<Sample>()
                    val loopStart = SystemClock.elapsedRealtimeNanos()
                    repeat(50) { samples += measure(repository, timedDao, case) }
                    val loopNanos = SystemClock.elapsedRealtimeNanos() - loopStart
                    val after = processSample(context)
                    emit(
                        JSONObject()
                            .put("kind", "result")
                            .put("messages", size)
                            .put("queryCase", case.name)
                            .put("reopenFirstMs", first.millis)
                            .put("p50Ms", samples.percentile(0.50))
                            .put("p95Ms", samples.percentile(0.95))
                            .put("maxMs", samples.maxOf(Sample::millis))
                            .put("recentMeanMs", samples.map(Sample::recentMillis).average())
                            .put("searchMeanMs", samples.map(Sample::searchMillis).average())
                            .put("neighborsMeanMs", samples.map(Sample::neighborsMillis).average())
                            .put(
                                "otherMeanMs",
                                samples.map {
                                    it.millis - it.recentMillis - it.searchMillis - it.neighborsMillis
                                }.average(),
                            )
                            .put("serialRequestsPerSecond", samples.size * 1_000_000_000.0 / loopNanos)
                            .put("recalledMessages", samples.last().recalledMessages)
                            .put("historyUtf8Bytes", samples.last().historyBytes)
                            .put("pssBeforeKb", before.pssKb)
                            .put("pssAfterKb", after.pssKb)
                            .put("javaHeapBeforeBytes", before.heapBytes)
                            .put("javaHeapAfterBytes", after.heapBytes)
                            .put("processThreadsBefore", before.threads)
                            .put("processThreadsAfter", after.threads)
                            .put("interactiveBefore", before.interactive)
                            .put("interactiveAfter", after.interactive),
                    )
                    if (case.name == "no_match") {
                        val plan = database.openHelper.readableDatabase.query(
                            """
                            EXPLAIN QUERY PLAN
                            SELECT * FROM agent_memories
                            WHERE type IN ('user_msg', 'assistant_msg')
                            ORDER BY created_at_epoch_millis DESC
                            LIMIT 40
                            """.trimIndent(),
                        ).use { cursor ->
                            buildList {
                                while (cursor.moveToNext()) add(cursor.getString(3))
                            }
                        }
                        emit(
                            JSONObject().put("kind", "queryPlan")
                                .put("messages", size)
                                .put("recent", JSONArray(plan)),
                        )
                    }
                    database.close()
                }
                database = open(context, name)
            }
        } finally {
            database.close()
            if (context.getDatabasePath(name).exists()) {
                assertTrue("Temporary fixture cleanup failed", context.deleteDatabase(name))
            }
        }
    }

    private fun open(context: Context, name: String): MochiDatabase =
        Room.databaseBuilder(context, MochiDatabase::class.java, name).build()

    private suspend fun measure(
        repository: AgentMemoryRepository,
        timedDao: TimedMemoryDao,
        case: QueryCase,
    ): Sample {
        timedDao.reset()
        val started = SystemClock.elapsedRealtimeNanos()
        val result = repository.loadContext(case.query, recentTurns = 20)
        val millis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        assertEquals(40, result.recentMessages.size)
        assertTrue(result.recalledLines.size <= 15)
        assertEquals(case.expectRecall, result.recalledLines.isNotEmpty())
        return Sample(
            millis = millis,
            recalledMessages = result.recalledLines.size,
            historyBytes = result.recentMessages.sumOf { it.content.orEmpty().toByteArray().size } +
                result.recalledLines.sumOf { it.toByteArray().size },
            recentMillis = timedDao.recentNanos / 1_000_000.0,
            searchMillis = timedDao.searchNanos / 1_000_000.0,
            neighborsMillis = timedDao.neighborsNanos / 1_000_000.0,
        )
    }

    private fun processSample(context: Context): ProcessSample {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        return ProcessSample(
            pssKb = memory.totalPss,
            heapBytes = runtime.totalMemory() - runtime.freeMemory(),
            threads = File("/proc/self/status").useLines { lines ->
                lines.first { it.startsWith("Threads:") }.substringAfter(':').trim().toInt()
            },
            interactive = context.getSystemService(PowerManager::class.java).isInteractive,
        )
    }

    private fun List<Sample>.percentile(fraction: Double): Double =
        map(Sample::millis).sorted()[ceil(size * fraction).toInt() - 1]

    private fun emit(result: JSONObject) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("stream", "\nMEMORY_LATENCY $result\n") },
        )
    }

    private data class QueryCase(
        val name: String,
        val query: String,
        val expectRecall: Boolean = true,
    )

    private data class Sample(
        val millis: Double,
        val recalledMessages: Int,
        val historyBytes: Int,
        val recentMillis: Double,
        val searchMillis: Double,
        val neighborsMillis: Double,
    )

    private data class ProcessSample(
        val pssKb: Int,
        val heapBytes: Long,
        val threads: Int,
        val interactive: Boolean,
    )

    private class TimedMemoryDao(
        private val delegate: AgentMemoryDao,
    ) : AgentMemoryDao by delegate {
        var recentNanos = 0L
            private set
        var searchNanos = 0L
            private set
        var neighborsNanos = 0L
            private set

        fun reset() {
            recentNanos = 0
            searchNanos = 0
            neighborsNanos = 0
        }

        override suspend fun listRecentMessages(messageLimit: Int): List<AgentMemoryEntity> {
            val started = SystemClock.elapsedRealtimeNanos()
            return delegate.listRecentMessages(messageLimit).also {
                recentNanos += SystemClock.elapsedRealtimeNanos() - started
            }
        }

        override suspend fun search(
            matchQuery: String,
            excludedIds: Collection<String>,
            limit: Int,
        ): List<AgentMemoryEntity> {
            val started = SystemClock.elapsedRealtimeNanos()
            return delegate.search(matchQuery, excludedIds, limit).also {
                searchNanos += SystemClock.elapsedRealtimeNanos() - started
            }
        }

        override suspend fun listNeighbors(hitIds: List<String>): List<AgentMemoryEntity> {
            val started = SystemClock.elapsedRealtimeNanos()
            return delegate.listNeighbors(hitIds).also {
                neighborsNanos += SystemClock.elapsedRealtimeNanos() - started
            }
        }
    }
}
