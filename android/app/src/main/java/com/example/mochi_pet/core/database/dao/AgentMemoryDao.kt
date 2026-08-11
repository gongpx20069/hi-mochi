package com.example.mochi_pet.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.example.mochi_pet.core.database.entity.AgentMemoryFtsEntity
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

    @Query(
        """
        SELECT memories.*
        FROM agent_memories AS memories
        INNER JOIN agent_memory_fts
            ON agent_memory_fts.memory_id = memories.id
        WHERE agent_memory_fts.search_text MATCH :matchQuery
        ORDER BY memories.created_at_epoch_millis DESC
        LIMIT :limit
        """,
    )
    suspend fun search(
        matchQuery: String,
        limit: Int,
    ): List<AgentMemoryEntity>

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
    suspend fun insertMemoryRows(memories: List<AgentMemoryEntity>)

    @Transaction
    suspend fun insertAll(memories: List<AgentMemoryEntity>) {
        insertMemoryRows(memories)
        insertSearchRows(
            memories.map { memory ->
                AgentMemoryFtsEntity(
                    memoryId = memory.id,
                    searchText = memory.searchText,
                )
            },
        )
    }

    @Insert
    suspend fun insertSearchRows(memories: List<AgentMemoryFtsEntity>)

    @Transaction
    suspend fun insertTurn(memories: List<AgentMemoryEntity>) =
        insertAll(memories)
}
