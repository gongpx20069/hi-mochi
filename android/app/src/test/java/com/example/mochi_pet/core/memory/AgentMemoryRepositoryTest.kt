package com.example.mochi_pet.core.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.mochi_pet.core.database.MochiDatabase
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
}
