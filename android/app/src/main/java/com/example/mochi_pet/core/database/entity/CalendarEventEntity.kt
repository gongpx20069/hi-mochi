package com.example.mochi_pet.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "calendar_events",
    indices = [
        Index(value = ["start_at_epoch_millis"]),
        Index(value = ["updated_at_epoch_millis"]),
        Index(value = ["series_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = CalendarEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["series_id"],
            onDelete = ForeignKey.CASCADE,
        ),
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
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val revision: Long = 0,
    @ColumnInfo(name = "series_id")
    val seriesId: String? = null,
    @ColumnInfo(name = "occurrence_start_at_epoch_millis")
    val occurrenceStartAtEpochMillis: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val cancelled: Boolean = false,
    @ColumnInfo(defaultValue = "'NORMAL'")
    val priority: String = "NORMAL",
)
