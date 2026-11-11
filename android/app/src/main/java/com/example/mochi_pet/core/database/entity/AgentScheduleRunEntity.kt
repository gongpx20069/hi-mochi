package com.example.mochi_pet.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "agent_schedule_runs", indices = [Index(value = ["schedule_id", "created_at"])])
data class AgentScheduleRunEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "schedule_id") val scheduleId: String,
    val revision: Long,
    val name: String,
    val prompt: String,
    val manual: Boolean,
    @ColumnInfo(name = "planned_at") val plannedAt: Long?,
    val state: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    val output: String?,
    val error: String?,
)
