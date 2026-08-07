package com.example.mochi_pet.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.example.mochi_pet.core.database.entity.TodoEntity

@Dao
interface TodoDao {
    @Upsert
    suspend fun upsert(todo: TodoEntity)

    @Query("SELECT * FROM todos WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TodoEntity?

    @Query(
        """
        SELECT * FROM todos
        WHERE scheduled_date_epoch_day = :epochDay
        ORDER BY
          CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END,
          CASE priority WHEN 'HIGH' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END,
          due_at_epoch_millis ASC,
          created_at_epoch_millis ASC
        """,
    )
    suspend fun listForDate(epochDay: Long): List<TodoEntity>

    @Query(
        """
        SELECT * FROM todos
        WHERE status = 'ACTIVE'
          AND scheduled_date_epoch_day IS NOT NULL
          AND scheduled_date_epoch_day <= :epochDay
        ORDER BY
          scheduled_date_epoch_day ASC,
          CASE priority WHEN 'HIGH' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END,
          due_at_epoch_millis ASC,
          created_at_epoch_millis ASC
        """,
    )
    suspend fun listActiveThroughDate(epochDay: Long): List<TodoEntity>

    @Query(
        """
        SELECT * FROM todos
        WHERE scheduled_date_epoch_day IS NULL AND status = :status
        ORDER BY
          CASE priority WHEN 'HIGH' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END,
          created_at_epoch_millis ASC
        """,
    )
    suspend fun listUndated(status: String): List<TodoEntity>

    @Query(
        """
        SELECT * FROM todos
        WHERE status = :status
        ORDER BY scheduled_date_epoch_day ASC, due_at_epoch_millis ASC,
          created_at_epoch_millis ASC
        """,
    )
    suspend fun listByStatus(status: String): List<TodoEntity>

    @Delete
    suspend fun delete(todo: TodoEntity)
}
