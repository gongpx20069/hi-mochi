package com.example.mochi_pet.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.example.mochi_pet.core.database.entity.SkillEntity

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills ORDER BY name COLLATE NOCASE")
    suspend fun listAll(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SkillEntity?

    @Query("SELECT * FROM skills WHERE enabled = 1 ORDER BY name COLLATE NOCASE")
    suspend fun listEnabled(): List<SkillEntity>

    @Upsert
    suspend fun upsert(skill: SkillEntity)

    @Delete
    suspend fun delete(skill: SkillEntity)
}
