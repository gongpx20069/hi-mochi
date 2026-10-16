package com.example.mochi_pet.platform.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.mochi_pet.MainActivity
import com.example.mochi_pet.R
import com.example.mochi_pet.core.browser.UserAssistanceCoordinator

internal suspend fun observeUserAssistanceNotification(context: Context, assistance: UserAssistanceCoordinator) {
    val manager = context.getSystemService(NotificationManager::class.java)
    val channel = "mochi_user_assistance"
    val notificationId = 4203
    manager.createNotificationChannel(NotificationChannel(
        channel, context.getString(R.string.assistance_notification_channel), NotificationManager.IMPORTANCE_DEFAULT,
    ))
    assistance.request.collect { request ->
        if (request == null) {
            manager.cancel(notificationId)
        } else if (manager.areNotificationsEnabled()) {
            val intent = PendingIntent.getActivity(
                context, notificationId, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(notificationId, Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.assistance_notification_title))
                .setContentText(context.getString(R.string.assistance_notification_text))
                .setContentIntent(intent)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build())
        } else {
            Log.w("MochiBrowser", "Assistance notification unavailable: notifications are disabled")
        }
    }
}
