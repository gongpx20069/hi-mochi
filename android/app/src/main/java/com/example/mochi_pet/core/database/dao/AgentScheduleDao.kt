package com.example.mochi_pet.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.example.mochi_pet.core.database.entity.AgentScheduleEntity

@Dao
interface AgentScheduleDao {
    @Upsert
    suspend fun upsert(schedule: AgentScheduleEntity)

    @Query("SELECT * FROM agent_schedules WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AgentScheduleEntity?

    @Query(
        """
        SELECT * FROM agent_schedules
        ORDER BY enabled DESC, next_run_at_epoch_millis ASC, name ASC
        """,
    )
    suspend fun listAll(): List<AgentScheduleEntity>

    @Query(
        """
        SELECT * FROM agent_schedules
        WHERE enabled = 1 AND next_run_at_epoch_millis IS NOT NULL
        ORDER BY next_run_at_epoch_millis ASC
        """,
    )
    suspend fun listEnabled(): List<AgentScheduleEntity>

    @Query(
        """
        UPDATE agent_schedules
        SET next_run_at_epoch_millis = NULL
        WHERE id = :id
          AND enabled = 1
          AND next_run_at_epoch_millis IS NOT NULL
          AND next_run_at_epoch_millis <= :nowEpochMillis
        """,
    )
    suspend fun claimDue(
        id: String,
        nowEpochMillis: Long,
    ): Int

    @Delete
    suspend fun delete(schedule: AgentScheduleEntity)
}
