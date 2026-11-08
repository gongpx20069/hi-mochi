package com.example.mochi_pet.platform.schedule

import android.app.Application
import android.app.NotificationManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.mochi_pet.core.database.MochiDatabase
import com.example.mochi_pet.core.database.PlannerRepository
import com.example.mochi_pet.core.model.CalendarEventDraft
import com.example.mochi_pet.core.model.ReminderReadiness
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UserReminderTest {
    @Test fun `notification denial is explicit and completed reminders never deliver`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, MochiDatabase::class.java).build()
        try {
            val now = Instant.parse("2026-11-09T00:00:00Z")
            val clock = Clock.fixed(now, ZoneOffset.UTC)
            val store = PlannerRepository(db.calendarEventDao(), db.todoDao(), clock)
            val controller = AndroidUserReminderController(context, store, clock)
            val manager = context.getSystemService(NotificationManager::class.java)
            val notificationShadow = shadowOf(manager)
            notificationShadow.setNotificationsEnabled(false)
            val event = store.createCalendarEvent(CalendarEventDraft("Fixture", startAt = now.plusSeconds(600),
                timezone = ZoneOffset.UTC, reminderAt = now))
            assertEquals(ReminderReadiness.NOTIFICATIONS_DISABLED, controller.sync())
            assertEquals(0, manager.activeNotifications.size)
            store.completeCalendarOccurrence(event.occurrenceId, event.revision)
            notificationShadow.setNotificationsEnabled(true)
            controller.sync()
            assertEquals(0, manager.activeNotifications.size)
        } finally { db.close() }
    }

    @Test fun `delivery receipt prevents reannouncement after controller recreation`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, MochiDatabase::class.java).build()
        try {
            val now = Instant.parse("2026-11-09T00:00:00Z")
            val clock = Clock.fixed(now, ZoneOffset.UTC)
            val store = PlannerRepository(db.calendarEventDao(), db.todoDao(), clock)
            val manager = context.getSystemService(NotificationManager::class.java)
            shadowOf(manager).setNotificationsEnabled(true)
            store.createCalendarEvent(CalendarEventDraft("Fixture", startAt = now.plusSeconds(600),
                timezone = ZoneOffset.UTC, reminderAt = now))
            AndroidUserReminderController(context, store, clock).sync()
            assertEquals(1, manager.activeNotifications.size)
            manager.cancelAll()
            AndroidUserReminderController(context, store, clock).sync()
            assertEquals(0, manager.activeNotifications.size)
        } finally { db.close() }
    }
}
