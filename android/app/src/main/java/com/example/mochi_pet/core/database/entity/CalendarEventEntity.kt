package com.example.mochi_pet.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "calendar_events",
    indices = [
        Index(value = ["start_at_epoch_millis"]),
        Index(value = ["updated_at_epoch_millis"]),
    ],
)
data class CalendarEventEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val description: String?,
    @ColumnInfo(name = "start_at_epoch_millis")
    val startAtEpochMillis: Long,
    @ColumnInfo(name = "end_at_epoch_millis")
    val endAtEpochMillis: Long?,
    @ColumnInfo(name = "all_day")
    val allDay: Boolean,
    @ColumnInfo(name = "timezone_id")
    val timezoneId: String,
    @ColumnInfo(name = "recurrence_rule")
    val recurrenceRule: String?,
    val location: String?,
    @ColumnInfo(name = "reminder_at_epoch_millis")
    val reminderAtEpochMillis: Long?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)
