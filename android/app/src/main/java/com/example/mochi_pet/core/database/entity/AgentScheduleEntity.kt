package com.example.mochi_pet.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_schedules",
    indices = [
        Index(value = ["enabled"]),
        Index(value = ["next_run_at_epoch_millis"]),
    ],
)
data class AgentScheduleEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val prompt: String,
    val type: String,
    @ColumnInfo(name = "run_at_epoch_millis")
    val runAtEpochMillis: Long?,
    @ColumnInfo(name = "local_time")
    val localTime: String?,
    @ColumnInfo(name = "days_of_week")
    val daysOfWeek: String,
    @ColumnInfo(name = "interval_minutes")
    val intervalMinutes: Long?,
    val timezone: String,
    val enabled: Boolean,
    @ColumnInfo(name = "next_run_at_epoch_millis")
    val nextRunAtEpochMillis: Long?,
    @ColumnInfo(name = "last_result")
    val lastResult: String?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)
