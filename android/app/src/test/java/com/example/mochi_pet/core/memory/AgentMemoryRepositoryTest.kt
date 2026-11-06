package com.example.mochi_pet.core.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.mochi_pet.core.database.MochiDatabase
import com.example.mochi_pet.core.database.dao.AgentMemoryDao
import com.example.mochi_pet.core.database.entity.AgentMemoryEntity
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentMemoryRepositoryTest {
    private lateinit var database: MochiDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            MochiDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `direct SQLite search excludes recent window and keeps neighbors`() =
        runBlocking {
            val dao = database.agentMemoryDao()
            dao.insertAll(
                listOf(
                    memory("1", "user", "我准备去杭州旅行", 1),
                    memory("2", "assistant", "可以先安排西湖路线", 2),
                    memory("3", "user", "住宿希望靠近地铁", 3),
                    memory("4", "assistant", "武林广场交通方便", 4),
                    memory("5", "user", "今天只聊晚饭", 5),
                    memory("6", "assistant", "可以吃面条", 6),
                ),
            )
            val repository = RoomAgentMemoryRepository(
                dao = dao,
                promptZone = ZoneId.of("Asia/Shanghai"),
            )

            val context = repository.loadContext(
                query = "杭州的旅行安排",
                recentTurns = 1,
            )

            assertEquals(
                listOf("user", "assistant"),
                context.recentMessages.map { it.role },
            )
            assertTrue(
                context.recalledLines.any { it.contains("杭州旅行") },
            )
            assertTrue(
                context.recalledLines.any { it.contains("西湖路线") },
            )
            assertEquals(
                "- [1970-01-01T08:00:00.001+08:00] " +
                    "User: 我准备去杭州旅行",
                context.recalledLines.first(),
            )
            assertTrue(
                context.recalledLines.none { it.contains("今天只聊晚饭") },
            )
        }

    @Test
    fun `tokenization preserves mixed Latin Han and numeric runs`() {
        val terms = memoryTerms("Mochi上海8月旅行")

        assertTrue(
            "Unexpected terms: $terms",
            listOf("mochi", "上海", "8", "旅行", "月").all(terms::contains),
        )
        assertEquals(terms.size, terms.distinct().size)
    }

    @Test
    fun `candidate lookup omits broad unigrams when specific terms exist`() {
        val lookupTerms = MemoryLexicalSearch.candidateLookupTerms(
            memoryTerms("杭州旅行"),
        )

        assertTrue("杭州" in lookupTerms)
        assertTrue("旅行" in lookupTerms)
        assertTrue(lookupTerms.none { it.length == 1 })
    }

    @Test
    fun `transactional FTS index recalls mixed language memory`() =
        runBlocking {
            val dao = database.agentMemoryDao()
            dao.insertAll(
                listOf(
                    memory(
                        id = "mixed",
                        role = "user",
                        content = "Mochi上海旅行",
                        timestamp = 1,
                    ),
                    memory("2", "assistant", "先看看外滩", 2),
                    memory("3", "user", "今天吃什么", 3),
                    memory("4", "assistant", "可以吃面条", 4),
                ),
            )
            val repository = RoomAgentMemoryRepository(dao = dao)

            val context = repository.loadContext(
                query = "Mochi",
                recentTurns = 1,
            )

            assertTrue(
                context.recalledLines.any { it.contains("Mochi上海旅行") },
            )
        }

    @Test
    fun `FTS excludes recent IDs before filling the candidate limit`() =
        runBlocking {
            val dao = database.agentMemoryDao()
            dao.insertAll(
                (1..260).map {
                    memory("$it", "user", "hotel reservation $it", it.toLong())
                },
            )
            val excluded = (161..260).map(Int::toString)

            val candidates = dao.search(
                matchQuery = "\"hotel\"",
                excludedIds = excluded,
                limit = 150,
            )

            assertEquals((160 downTo 11).map(Int::toString), candidates.map { it.id })
        }

    @Test
    fun `old joint matches survive more than 150 newer partial matches`() =
        runBlocking {
            val dao = database.agentMemoryDao()
            dao.insertAll(
                listOf(memory("old", "user", "hotel reservation confirmed", 1)) +
                    (2..172).map {
                        memory("$it", "user", "train reservation $it", it.toLong())
                    } +
                    listOf(
                        memory("recent-user", "user", "dinner", 200),
                        memory("recent-assistant", "assistant", "noodles", 201),
                    ),
            )
            val recordingDao = RecordingMemoryDao(dao)

            val context = RoomAgentMemoryRepository(recordingDao).loadContext(
                query = "hotel reservation",
                recentTurns = 1,
            )

            assertTrue(context.recalledLines.any { it.contains("hotel reservation confirmed") })
            assertEquals(listOf(1, 149), recordingDao.searchResults.map { it.size })
            assertEquals(
                150,
                recordingDao.searchResults.flatten().map { it.id }.distinct().size,
            )
            assertEquals("172", recordingDao.searchResults.last().first().id)
            assertEquals(1, recordingDao.neighborReads)
        }

    @Test
    fun `joint Chinese lookup does not require synthetic cross word bigrams`() =
        runBlocking {
            val dao = database.agentMemoryDao()
            dao.insertAll(
                listOf(memory("old", "user", "酒店靠近杭州西湖", 1)) +
                    (2..172).map {
                        memory("$it", "user", "杭州天气晴朗", it.toLong())
                    } +
                    listOf(
                        memory("recent-user", "user", "晚饭吃什么", 200),
                        memory("recent-assistant", "assistant", "吃面条", 201),
                    ),
            )
            val recordingDao = RecordingMemoryDao(dao)

            val context = RoomAgentMemoryRepository(recordingDao).loadContext(
                query = "杭州酒店",
                recentTurns = 1,
            )

            assertEquals(listOf("old"), recordingDao.searchResults.first().map { it.id })
            assertTrue(context.recalledLines.any { it.contains("酒店靠近杭州西湖") })
        }

    @Test
    fun `full preferred pool skips broad lookup`() =
        runBlocking {
            val dao = database.agentMemoryDao()
            dao.insertAll(
                (1..170).map {
                    memory("$it", "user", "hotel reservation $it", it.toLong())
                },
            )
            val recordingDao = RecordingMemoryDao(dao)

            RoomAgentMemoryRepository(recordingDao).loadContext(
                query = "hotel reservation",
                recentTurns = 1,
            )

            assertEquals(1, recordingDao.searchResults.size)
            assertEquals(
                (168 downTo 19).map(Int::toString),
                recordingDao.searchResults.single().map { it.id },
            )
        }

    @Test
    fun `five hits expand only one neighbor each side in one read`() =
        runBlocking {
            val dao = database.agentMemoryDao()
            dao.insertAll(
                (0..5).flatMap { block ->
                    (0..4).map { offset ->
                        memory(
                            id = "$block-$offset",
                            role = if (offset % 2 == 0) "user" else "assistant",
                            content = if (offset == 2) "needle $block" else "context $block-$offset",
                            timestamp = (block * 5 + offset).toLong(),
                        )
                    }
                } +
                    listOf(
                        memory("recent-user", "user", "dinner", 100),
                        memory("recent-assistant", "assistant", "noodles", 101),
                    ),
            )
            val recordingDao = RecordingMemoryDao(dao)

            val context = RoomAgentMemoryRepository(recordingDao).loadContext(
                query = "needle",
                recentTurns = 1,
            )

            assertEquals(
                (1..5).flatMap { block ->
                    listOf("context $block-1", "needle $block", "context $block-3")
                },
                context.recalledLines.map { it.substringAfter(": ", "") },
            )
            assertEquals(1, recordingDao.searchResults.size)
            assertEquals(1, recordingDao.neighborReads)
        }

    @Test
    fun `overlapping windows deduplicate and do not repeat recent boundary`() =
        runBlocking {
            val dao = database.agentMemoryDao()
            dao.insertAll(
                listOf(
                    memory("1", "user", "context", 1),
                    memory("2", "assistant", "hotel first", 2),
                    memory("3", "user", "hotel second", 3),
                    memory("4", "user", "hotel recent", 4),
                    memory("5", "assistant", "recent response", 5),
                ),
            )

            val context = RoomAgentMemoryRepository(dao).loadContext(
                query = "hotel",
                recentTurns = 1,
            )

            assertEquals(
                listOf("context", "hotel first", "hotel second"),
                context.recalledLines.map { it.substringAfter(": ", "") },
            )
            assertEquals(
                listOf("hotel recent", "recent response"),
                context.recentMessages.map { it.content },
            )
        }

    @Test
    fun `empty query and no matches do not read neighbors`() =
        runBlocking {
            val recordingDao = RecordingMemoryDao(database.agentMemoryDao())
            val repository = RoomAgentMemoryRepository(recordingDao)

            assertTrue(repository.loadContext("?!", 1).recalledLines.isEmpty())
            assertTrue(recordingDao.searchResults.isEmpty())
            assertTrue(repository.loadContext("hotel reservation", 1).recalledLines.isEmpty())
            assertEquals(listOf(0, 0), recordingDao.searchResults.map { it.size })
            assertEquals(0, recordingDao.neighborReads)
        }

    @Test
    fun `ranking favors complete phrase over newer partial matches`() {
        val exact = memory(
            id = "exact",
            role = "user",
            content = "我的上海旅行计划包括外滩",
            timestamp = 1,
        )
        val partial = memory(
            id = "partial",
            role = "user",
            content = "上海今天天气很好",
            timestamp = 2,
        )

        val ranked = MemoryLexicalSearch.rank(
            query = "上海旅行计划",
            queryTerms = memoryTerms("上海旅行计划"),
            candidates = listOf(partial, exact),
        )
        assertEquals("exact", ranked.first().id)
    }

    @Test
    fun `ranking removes candidates without a lexical match`() {
        val ranked = MemoryLexicalSearch.rank(
            query = "上海旅行",
            queryTerms = memoryTerms("上海旅行"),
            candidates = listOf(
                memory(
                    id = "unrelated",
                    role = "user",
                    content = "今天晚饭吃面条",
                    timestamp = 1,
                ),
            ),
        )

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `ranking handles Chinese English mixed names and dates`() {
        val cases = listOf(
            RankingCase(
                query = "上海的旅行安排",
                expectedId = "chinese",
                candidates = listOf(
                    memory("chinese", "user", "上海旅行安排包括外滩", 1),
                    memory("chinese-noise", "user", "上海今天会下雨", 2),
                ),
            ),
            RankingCase(
                query = "hotel reservation",
                expectedId = "english",
                candidates = listOf(
                    memory(
                        "english",
                        "user",
                        "The hotel reservation is near the station",
                        1,
                    ),
                    memory(
                        "english-noise",
                        "user",
                        "The train reservation is confirmed",
                        2,
                    ),
                ),
            ),
            RankingCase(
                query = "Mochi上海",
                expectedId = "mixed",
                candidates = listOf(
                    memory("mixed", "user", "Mochi上海旅行计划", 1),
                    memory("mixed-noise", "user", "Mochi今天休息", 2),
                ),
            ),
            RankingCase(
                query = "小王",
                expectedId = "name",
                candidates = listOf(
                    memory("name", "user", "周末和小王吃饭", 1),
                    memory("name-noise", "user", "周末在家吃饭", 2),
                ),
            ),
            RankingCase(
                query = "8月12日",
                expectedId = "date",
                candidates = listOf(
                    memory("date", "user", "8月12日去杭州", 1),
                    memory("date-noise", "user", "12月8日去杭州", 2),
                ),
            ),
        )

        cases.forEach { case ->
            val ranked = MemoryLexicalSearch.rank(
                query = case.query,
                queryTerms = memoryTerms(case.query),
                candidates = case.candidates,
            )

            assertEquals(case.query, case.expectedId, ranked.first().id)
        }
    }

    private fun memory(
        id: String,
        role: String,
        content: String,
        timestamp: Long,
    ): AgentMemoryEntity =
        AgentMemoryEntity(
            id = id,
            turnId = "turn-$timestamp",
            role = role,
            type = "${role}_msg",
            content = content,
            searchText = searchableMemoryText(content),
            createdAtEpochMillis = timestamp,
        )
    private data class RankingCase(
        val query: String,
        val expectedId: String,
        val candidates: List<AgentMemoryEntity>,
    )

    private class RecordingMemoryDao(
        private val delegate: AgentMemoryDao,
    ) : AgentMemoryDao by delegate {
        val searchResults = mutableListOf<List<AgentMemoryEntity>>()
        var neighborReads = 0
            private set

        override suspend fun search(
            matchQuery: String,
            excludedIds: Collection<String>,
            limit: Int,
        ): List<AgentMemoryEntity> =
            delegate.search(matchQuery, excludedIds, limit).also(searchResults::add)

        override suspend fun listNeighbors(hitIds: List<String>): List<AgentMemoryEntity> {
            neighborReads++
            return delegate.listNeighbors(hitIds)
        }
    }
}
