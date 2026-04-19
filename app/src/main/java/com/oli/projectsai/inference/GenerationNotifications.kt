package com.oli.projectsai.inference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.oli.projectsai.R
import com.oli.projectsai.ui.MainActivity

const val GENERATION_CHANNEL_ID = "generation"
const val GENERATION_NOTIFICATION_ID = 1001
const val EXTRA_OPEN_CHAT_ID = "open_chat_id"

fun createGenerationChannel(context: Context) {
    val manager = context.getSystemService<NotificationManager>() ?: return
    if (manager.getNotificationChannel(GENERATION_CHANNEL_ID) != null) return
    val channel = NotificationChannel(
        GENERATION_CHANNEL_ID,
        "Generation",
        NotificationManager.IMPORTANCE_LOW
    ).apply {
        description = "Ongoing on-device model generation"
        setShowBadge(false)
        setSound(null, null)
        enableVibration(false)
    }
    manager.createNotificationChannel(channel)
}

fun buildGenerationNotification(context: Context, active: ActiveGeneration?): Notification {
    val titleHint = active?.titleHint?.takeIf { it.isNotBlank() } ?: "Chat"
    val body = active?.searchStatus ?: titleHint

    val contentIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        active?.let { putExtra(EXTRA_OPEN_CHAT_ID, it.chatId) }
    }
    val contentPending = PendingIntent.getActivity(
        context,
        0,
        contentIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val cancelIntent = Intent(context, GenerationForegroundService::class.java).apply {
        action = GenerationForegroundService.ACTION_CANCEL
    }
    val cancelPending = PendingIntent.getService(
        context,
        1,
        cancelIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    return NotificationCompat.Builder(context, GENERATION_CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Projects AI — generating…")
        .setContentText(body)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(contentPending)
        .addAction(0, "Cancel", cancelPending)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()
}
