package com.example.mochi_pet.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import com.example.mochi_pet.core.database.entity.CalendarEventEntity
import kotlinx.coroutines.flow.Flow
import com.example.mochi_pet.core.model.checkPlannerRevision

@Dao
interface CalendarEventDao {
    @Upsert
    suspend fun upsert(event: CalendarEventEntity)

    @Query("SELECT * FROM calendar_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CalendarEventEntity?

    @Query(
        """
        SELECT * FROM calendar_events
        WHERE series_id IS NULL AND (
          (start_at_epoch_millis < :rangeEndEpochMillis AND
            (recurrence_rule IS NOT NULL OR COALESCE(end_at_epoch_millis, start_at_epoch_millis + 1) > :rangeStartEpochMillis))
          OR id IN (SELECT series_id FROM calendar_events
            WHERE start_at_epoch_millis < :rangeEndEpochMillis
            AND COALESCE(end_at_epoch_millis, start_at_epoch_millis + 1) > :rangeStartEpochMillis))
        ORDER BY all_day DESC, start_at_epoch_millis ASC, id ASC
        """,
    )
    suspend fun listOverlapping(
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): List<CalendarEventEntity>

    @Query("SELECT * FROM calendar_events WHERE series_id = :id")
    suspend fun exceptions(id: String): List<CalendarEventEntity>

    @Query("SELECT * FROM calendar_events WHERE series_id IS NULL AND (reminder_at_epoch_millis IS NOT NULL OR id IN (SELECT series_id FROM calendar_events WHERE reminder_at_epoch_millis IS NOT NULL))")
    suspend fun reminderSeries(): List<CalendarEventEntity>

    @Query("SELECT COUNT(*) FROM calendar_events UNION ALL SELECT COUNT(*) FROM todos")
    fun observeChanges(): Flow<List<Int>>

    @Query("SELECT EXISTS(SELECT 1 FROM planner_reminder_receipts WHERE id = :id)")
    suspend fun reminderDelivered(id: String): Boolean

    @Query("INSERT OR IGNORE INTO planner_reminder_receipts (id, deliveredAt) VALUES (:id, :now)")
    suspend fun recordReminder(id: String, now: Long)

    @Query("UPDATE calendar_events SET revision = revision + 1 WHERE id = :id AND revision = :revision")
    suspend fun claimRevision(id: String, revision: Long): Int

    @Transaction
    suspend fun replace(event: CalendarEventEntity, expectedRevision: Long) {
        val previous = getById(event.id)
        checkPlannerRevision(claimRevision(event.id, expectedRevision) == 1)
        upsert(event)
        if (previous?.recurrenceRule == null && previous?.completedAtEpochMillis != null && event.recurrenceRule != null) {
            upsert(event.copy(id = "${event.id}@${event.startAtEpochMillis}", seriesId = event.id,
                occurrenceStartAtEpochMillis = event.startAtEpochMillis, recurrenceRule = null,
                completedAtEpochMillis = previous.completedAtEpochMillis))
        }
    }

    @Transaction
    suspend fun saveException(event: CalendarEventEntity, expectedRevision: Long) {
        checkPlannerRevision(claimRevision(requireNotNull(event.seriesId), expectedRevision) == 1)
        upsert(event)
    }

    @Transaction
    suspend fun splitSeries(original: CalendarEventEntity, future: CalendarEventEntity, expectedRevision: Long, from: Long) {
        replace(original, expectedRevision)
        upsert(future)
        exceptions(original.id).filter { it.completedAtEpochMillis != null && requireNotNull(it.occurrenceStartAtEpochMillis) >= from }
            .forEach { completed ->
                val instant = requireNotNull(completed.occurrenceStartAtEpochMillis) + future.startAtEpochMillis - from
                upsert(completed.copy(id = "${future.id}@$instant", seriesId = future.id,
                    occurrenceStartAtEpochMillis = instant, completedAtEpochMillis = null, cancelled = true, recurrenceRule = null))
            }
        removeFutureExceptions(original.id, from)
    }

    @Query("DELETE FROM calendar_events WHERE series_id = :id AND occurrence_start_at_epoch_millis >= :from AND completed_at_epoch_millis IS NULL")
    suspend fun removeFutureExceptions(id: String, from: Long)

    @Transaction
    suspend fun deleteVersion(id: String, revision: Long) {
        checkPlannerRevision(claimRevision(id, revision) == 1)
        delete(requireNotNull(getById(id)))
    }

    @Query("DELETE FROM todos WHERE id = :id AND revision = :revision")
    suspend fun removePromotedTodo(id: String, revision: Long): Int

    @Transaction
    suspend fun promoteTodo(event: CalendarEventEntity, expectedRevision: Long, completedAt: Long? = null) {
        checkPlannerRevision(getById(event.id) == null && removePromotedTodo(event.id, expectedRevision) == 1)
        upsert(event)
        if (event.recurrenceRule != null && completedAt != null) {
            upsert(event.copy(id = "${event.id}@${event.startAtEpochMillis}", seriesId = event.id,
                occurrenceStartAtEpochMillis = event.startAtEpochMillis, recurrenceRule = null, completedAtEpochMillis = completedAt))
        }
    }

    @Delete
    suspend fun delete(event: CalendarEventEntity)
}
