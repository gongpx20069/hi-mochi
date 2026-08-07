package com.example.mochi_pet.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "skills",
    indices = [
        Index(value = ["source"]),
        Index(value = ["enabled"]),
    ],
)
data class SkillEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String,
    val content: String,
    val source: String,
    @ColumnInfo(name = "source_url")
    val sourceUrl: String,
    @ColumnInfo(name = "upstream_version")
    val upstreamVersion: String?,
    @ColumnInfo(name = "upstream_digest")
    val upstreamDigest: String,
    @ColumnInfo(name = "local_digest")
    val localDigest: String,
    val enabled: Boolean,
    val modified: Boolean,
    @ColumnInfo(name = "update_available")
    val updateAvailable: Boolean,
    @ColumnInfo(name = "installed_at_epoch_millis")
    val installedAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "last_checked_at_epoch_millis")
    val lastCheckedAtEpochMillis: Long?,
)
