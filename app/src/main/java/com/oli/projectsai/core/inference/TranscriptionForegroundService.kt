package com.oli.projectsai.core.inference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.oli.projectsai.MainActivity
import com.oli.projectsai.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

const val TRANSCRIPTION_CHANNEL_ID = "transcription"
const val TRANSCRIPTION_NOTIFICATION_ID = 1002

private const val WAKE_LOCK_TAG = "ProjectsAI:LongFormTranscription"

@AndroidEntryPoint
class TranscriptionForegroundService : Service() {

    @Inject lateinit var controller: TranscriptionController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createTranscriptionChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                controller.cancel()
                return START_NOT_STICKY
            }
            else -> {
                if (!controller.isActive) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(
                    TRANSCRIPTION_NOTIFICATION_ID,
                    buildTranscriptionNotification(this, controller.state.value),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                acquireWakeLock()
                if (observerJob == null) {
                    val nm = getSystemService<NotificationManager>()
                    observerJob = scope.launch {
                        controller.state.collect { value ->
                            if (!value.isInFlight()) {
                                releaseWakeLock()
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            } else {
                                nm?.notify(
                                    TRANSCRIPTION_NOTIFICATION_ID,
                                    buildTranscriptionNotification(this@TranscriptionForegroundService, value)
                                )
                            }
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observerJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService<PowerManager>() ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // No timeout: long files can exceed any reasonable bound, and we hand-release in the
            // terminal-state observer above plus onDestroy.
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.oli.projectsai.TRANSCRIPTION_START"
        const val ACTION_CANCEL = "com.oli.projectsai.TRANSCRIPTION_CANCEL"
    }
}

private fun LongTranscriptionState.isInFlight(): Boolean = when (this) {
    is LongTranscriptionState.Decoding,
    is LongTranscriptionState.Transcribing,
    is LongTranscriptionState.Reconciling -> true
    else -> false
}

fun createTranscriptionChannel(context: Context) {
    val manager = context.getSystemService<NotificationManager>() ?: return
    if (manager.getNotificationChannel(TRANSCRIPTION_CHANNEL_ID) != null) return
    val channel = NotificationChannel(
        TRANSCRIPTION_CHANNEL_ID,
        "Transcription",
        NotificationManager.IMPORTANCE_LOW
    ).apply {
        description = "Long-form audio transcription progress"
        setShowBadge(false)
        setSound(null, null)
        enableVibration(false)
    }
    manager.createNotificationChannel(channel)
}

fun buildTranscriptionNotification(context: Context, state: LongTranscriptionState): Notification {
    val (title, body, progress) = when (state) {
        is LongTranscriptionState.Decoding ->
            Triple("Decoding ${state.fileName}…", "Preparing audio", null)
        is LongTranscriptionState.Transcribing ->
            Triple(
                "Transcribing ${state.fileName}",
                "Chunk ${state.completedChunks}/${state.totalChunks} · ${state.elapsedSec}s",
                state.totalChunks to state.completedChunks
            )
        is LongTranscriptionState.Reconciling ->
            Triple("Identifying speakers…", "Reconciling speaker labels", null)
        else -> Triple("Projects AI", "Transcribing", null)
    }

    val contentIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val contentPending = PendingIntent.getActivity(
        context,
        0,
        contentIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val cancelIntent = Intent(context, TranscriptionForegroundService::class.java).apply {
        action = TranscriptionForegroundService.ACTION_CANCEL
    }
    val cancelPending = PendingIntent.getService(
        context,
        2,
        cancelIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val builder = NotificationCompat.Builder(context, TRANSCRIPTION_CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title)
        .setContentText(body)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(contentPending)
        .addAction(0, "Cancel", cancelPending)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)

    progress?.let { (total, done) ->
        builder.setProgress(total, done, total == 0)
    }
    return builder.build()
}
