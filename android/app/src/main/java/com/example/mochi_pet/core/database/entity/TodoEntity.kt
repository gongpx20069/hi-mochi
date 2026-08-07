package com.example.mochi_pet.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "todos",
    indices = [
        Index(value = ["status"]),
        Index(value = ["scheduled_date_epoch_day"]),
        Index(value = ["updated_at_epoch_millis"]),
    ],
)
data class TodoEntity(
    @PrimaryKey
    val id: String,
    val content: String,
    val status: String,
    val priority: String,
    @ColumnInfo(name = "scheduled_date_epoch_day")
    val scheduledDateEpochDay: Long?,
    @ColumnInfo(name = "due_at_epoch_millis")
    val dueAtEpochMillis: Long?,
    @ColumnInfo(name = "reminder_at_epoch_millis")
    val reminderAtEpochMillis: Long?,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)
