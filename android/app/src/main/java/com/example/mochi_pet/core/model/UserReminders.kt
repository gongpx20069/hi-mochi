package com.example.mochi_pet.core.model

enum class ReminderReadiness { READY, APPROXIMATE, NOTIFICATIONS_DISABLED }

interface UserReminderController {
    fun readiness(): ReminderReadiness
    suspend fun sync(): ReminderReadiness
}
