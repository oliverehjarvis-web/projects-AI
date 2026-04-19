package com.oli.projectsai.inference

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GenerationForegroundService : Service() {

    @Inject lateinit var controller: GenerationController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createGenerationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                controller.cancel()
                return START_NOT_STICKY
            }
            else -> {
                val active = controller.activeGeneration.value
                if (active == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(
                    GENERATION_NOTIFICATION_ID,
                    buildGenerationNotification(this, active),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                if (observerJob == null) {
                    val nm = getSystemService<NotificationManager>()
                    observerJob = scope.launch {
                        controller.activeGeneration.collect { value ->
                            if (value == null) {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            } else {
                                nm?.notify(
                                    GENERATION_NOTIFICATION_ID,
                                    buildGenerationNotification(this@GenerationForegroundService, value)
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
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.oli.projectsai.GENERATION_START"
        const val ACTION_CANCEL = "com.oli.projectsai.GENERATION_CANCEL"
    }
}
