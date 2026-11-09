package com.example.mochi_pet.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_journals")
data class DailyJournalEntity(
    @PrimaryKey val date: String,
    val timezone: String,
    val status: String,
    val title: String,
    val text: String,
    val error: String?,
    val updatedAt: Long,
    val claim: String?,
    val conversationCount: Int? = null,
    val userScheduleCount: Int? = null,
    val taskCount: Int? = null,
    val generatedAt: Long? = null,
)

@Entity(tableName = "journal_settings")
data class JournalSettingsEntity(
    @PrimaryKey val id: Int = 0,
    val enabled: Boolean,
    val since: String?,
)
