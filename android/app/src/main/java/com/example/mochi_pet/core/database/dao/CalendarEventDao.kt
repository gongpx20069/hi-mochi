package com.example.mochi_pet.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.example.mochi_pet.core.database.entity.CalendarEventEntity

@Dao
interface CalendarEventDao {
    @Upsert
    suspend fun upsert(event: CalendarEventEntity)

    @Query("SELECT * FROM calendar_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CalendarEventEntity?

    @Query(
        """
        SELECT * FROM calendar_events
        WHERE start_at_epoch_millis < :rangeEndEpochMillis
          AND COALESCE(end_at_epoch_millis, start_at_epoch_millis + 1)
              > :rangeStartEpochMillis
        ORDER BY all_day DESC, start_at_epoch_millis ASC, id ASC
        """,
    )
    suspend fun listOverlapping(
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): List<CalendarEventEntity>

    @Delete
    suspend fun delete(event: CalendarEventEntity)
}
