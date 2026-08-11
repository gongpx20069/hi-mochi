package com.example.mochi_pet.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

@Fts4(tokenizer = FtsOptions.TOKENIZER_SIMPLE)
@Entity(tableName = "agent_memory_fts")
data class AgentMemoryFtsEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val rowId: Int = 0,
    @ColumnInfo(name = "memory_id")
    val memoryId: String,
    @ColumnInfo(name = "search_text")
    val searchText: String,
)
