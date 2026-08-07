package com.example.mochi_pet.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import com.example.mochi_pet.core.database.entity.AgentMemoryEntity

@Dao
interface AgentMemoryDao {
    @Query(
        """
        SELECT * FROM agent_memories
        WHERE type IN ('user_msg', 'assistant_msg')
        ORDER BY created_at_epoch_millis DESC
        LIMIT :messageLimit
        """,
    )
    suspend fun listRecentMessages(
        messageLimit: Int,
    ): List<AgentMemoryEntity>

    @RawQuery
    suspend fun search(query: SupportSQLiteQuery): List<AgentMemoryEntity>

    @Query(
        """
        SELECT * FROM agent_memories
        WHERE created_at_epoch_millis < :createdAtEpochMillis
        ORDER BY created_at_epoch_millis DESC
        LIMIT :limit
        """,
    )
    suspend fun listBefore(
        createdAtEpochMillis: Long,
        limit: Int,
    ): List<AgentMemoryEntity>

    @Query(
        """
        SELECT * FROM agent_memories
        WHERE created_at_epoch_millis > :createdAtEpochMillis
        ORDER BY created_at_epoch_millis ASC
        LIMIT :limit
        """,
    )
    suspend fun listAfter(
        createdAtEpochMillis: Long,
        limit: Int,
    ): List<AgentMemoryEntity>

    @Insert
    suspend fun insertAll(memories: List<AgentMemoryEntity>)

    @Transaction
    suspend fun insertTurn(memories: List<AgentMemoryEntity>) {
        insertAll(memories)
    }
}
