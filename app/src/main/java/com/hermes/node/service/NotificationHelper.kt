package com.hermes.node.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hermes.node.MainActivity

object NotificationHelper {
    const val CHANNEL_ID = "hermes_server_channel"
    const val CHANNEL_NAME = "Hermes Node Server"
    const val CHANNEL_DESCRIPTION = "Ongoing background daemon status and control"
    const val NOTIFICATION_ID = 1001
    const val ACTION_STOP = "com.hermes.node.action.STOP"
    const val ACTION_START = "com.hermes.node.action.START"
    private const val TAG = "NotificationHelper"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val importance = NotificationManager.IMPORTANCE_LOW
                val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                    description = CHANNEL_DESCRIPTION
                    setShowBadge(false)
                }
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.createNotificationChannel(channel)
            } catch (e: Throwable) {
                try {
                    Log.w(TAG, "Failed to create notification channel: ${e.message}")
                } catch (ignored: Throwable) {}
            }
        }
    }

    fun buildNotification(
        context: Context,
        statusText: String = "Hermes Node daemon running on port 8000"
    ): Notification {
        val contentIntent = try {
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } catch (ignored: Throwable) {
            null
        }

        val stopIntent = try {
            PendingIntent.getService(
                context,
                1,
                Intent(context, HermesServerService::class.java).apply {
                    action = ACTION_STOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } catch (ignored: Throwable) {
            null
        }

        return try {
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Hermes Node Server")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .apply {
                    if (contentIntent != null) setContentIntent(contentIntent)
                    if (stopIntent != null) addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
                }
                .build()
        } catch (e: Throwable) {
            // Fallback for JVM unit tests where Android SDK stubs return null for Builder chaining
            Notification().apply {
                flags = flags or Notification.FLAG_ONGOING_EVENT
                category = NotificationCompat.CATEGORY_SERVICE
            }
        }
    }

    fun updateNotification(context: Context, statusText: String) {
        try {
            val notification = buildNotification(context, statusText)
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            try {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted: ${e.message}")
            } catch (ignored: Throwable) {}
        } catch (e: Throwable) {
            try {
                Log.w(TAG, "Failed to update notification: ${e.message}")
            } catch (ignored: Throwable) {}
        }
    }

    fun cancelNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (e: Throwable) {
            try {
                Log.w(TAG, "Failed to cancel notification: ${e.message}")
            } catch (ignored: Throwable) {}
        }
    }
}
