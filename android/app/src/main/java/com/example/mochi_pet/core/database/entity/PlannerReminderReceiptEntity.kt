package com.example.mochi_pet.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "planner_reminder_receipts")
data class PlannerReminderReceiptEntity(@PrimaryKey val id: String, val deliveredAt: Long)
