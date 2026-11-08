package com.example.mochi_pet.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import com.example.mochi_pet.core.model.checkPlannerRevision
import com.example.mochi_pet.core.database.entity.AgentScheduleEntity
import com.example.mochi_pet.core.database.entity.AgentScheduleRunEntity

@Dao
interface AgentScheduleDao {
    @Query("SELECT COUNT(*) FROM agent_schedules UNION ALL SELECT COUNT(*) FROM agent_schedule_runs")
    fun observeChanges(): kotlinx.coroutines.flow.Flow<List<Int>>

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

    @Upsert
    suspend fun saveRun(run: AgentScheduleRunEntity)

    @Query("SELECT * FROM agent_schedule_runs WHERE id = :id")
    suspend fun run(id: String): AgentScheduleRunEntity?

    @Query("SELECT * FROM agent_schedule_runs WHERE schedule_id = :id ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun runs(id: String, limit: Int, offset: Int): List<AgentScheduleRunEntity>

    @Query("SELECT * FROM agent_schedule_runs WHERE schedule_id = :id AND state IN ('QUEUED', 'RUNNING') LIMIT 1")
    suspend fun activeRun(id: String): AgentScheduleRunEntity?

    @Query("SELECT * FROM agent_schedule_runs WHERE state IN ('QUEUED', 'RUNNING')")
    suspend fun activeRuns(): List<AgentScheduleRunEntity>

    @Query("""
        UPDATE agent_schedule_runs SET state = 'RUNNING', started_at = :now
        WHERE id = :id AND state = 'QUEUED' AND EXISTS (
            SELECT 1 FROM agent_schedules WHERE agent_schedules.id = agent_schedule_runs.schedule_id
            AND agent_schedules.revision = agent_schedule_runs.revision
            AND (agent_schedule_runs.manual = 1 OR ends_at_epoch_millis IS NULL OR ends_at_epoch_millis >= :now))
    """)
    suspend fun startRun(id: String, now: Long): Int

    @Transaction
    suspend fun savePlan(schedule: AgentScheduleEntity, expectedRevision: Long?, expectedState: AgentScheduleEntity?) {
        val current = getById(schedule.id)
        checkPlannerRevision(if (expectedRevision == null) current == null else current?.revision == expectedRevision)
        checkPlannerRevision(current == expectedState)
        upsert(schedule)
    }

    @Transaction
    suspend fun reserve(run: AgentScheduleRunEntity, advanced: AgentScheduleEntity?): AgentScheduleRunEntity? {
        val current = getById(run.scheduleId) ?: return null
        checkPlannerRevision(current.revision == run.revision)
        if (!run.manual && (current.nextRunAtEpochMillis != run.plannedAt || !current.enabled)) return null
        val active = activeRun(run.scheduleId)
        if (run.manual && active != null) return active
        val reserved = if (active == null) run else run.copy(state = "SKIPPED_BUSY", finishedAt = run.createdAt)
        if (advanced != null) upsert(advanced)
        saveRun(reserved)
        return reserved
    }

    @Transaction
    suspend fun finish(run: AgentScheduleRunEntity, update: (AgentScheduleEntity) -> AgentScheduleEntity) {
        val currentRun = run(run.id) ?: return
        if (currentRun.state !in setOf("QUEUED", "RUNNING")) return
        saveRun(run)
        val plan = getById(run.scheduleId)
        if (plan != null && plan.revision == run.revision) upsert(update(plan))
    }

    @Delete
    suspend fun delete(schedule: AgentScheduleEntity)
}
