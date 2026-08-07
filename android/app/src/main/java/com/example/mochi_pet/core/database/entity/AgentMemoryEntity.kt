package com.example.mochi_pet.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_memories",
    indices = [
        Index(value = ["turn_id"]),
        Index(value = ["type"]),
        Index(value = ["created_at_epoch_millis"]),
    ],
)
data class AgentMemoryEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "turn_id")
    val turnId: String?,
    val role: String?,
    val type: String,
    val content: String,
    @ColumnInfo(name = "search_text")
    val searchText: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)
