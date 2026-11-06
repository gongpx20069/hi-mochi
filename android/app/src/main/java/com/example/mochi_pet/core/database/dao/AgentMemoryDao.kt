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
            AND memories.id NOT IN (:excludedIds)
        ORDER BY memories.created_at_epoch_millis DESC
        LIMIT :limit
        """,
    )
    suspend fun search(
        matchQuery: String,
        excludedIds: Collection<String>,
        limit: Int,
    ): List<AgentMemoryEntity>

    @Query(
        """
        SELECT * FROM agent_memories
        WHERE id IN (
            SELECT (
                SELECT neighbor.id FROM agent_memories AS neighbor
                WHERE neighbor.created_at_epoch_millis < hit.created_at_epoch_millis
                ORDER BY neighbor.created_at_epoch_millis DESC
                LIMIT 1
            )
            FROM agent_memories AS hit WHERE hit.id IN (:hitIds)
            UNION ALL
            SELECT (
                SELECT neighbor.id FROM agent_memories AS neighbor
                WHERE neighbor.created_at_epoch_millis > hit.created_at_epoch_millis
                ORDER BY neighbor.created_at_epoch_millis ASC
                LIMIT 1
            )
            FROM agent_memories AS hit WHERE hit.id IN (:hitIds)
        )
        """,
    )
    suspend fun listNeighbors(
        hitIds: List<String>,
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
