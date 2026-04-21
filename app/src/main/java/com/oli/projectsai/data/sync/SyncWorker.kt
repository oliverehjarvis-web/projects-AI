package com.oli.projectsai.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncRepository: SyncRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return when (syncRepository.syncNow()) {
            is SyncResult.Success, is SyncResult.Skipped -> Result.success()
            is SyncResult.Error -> Result.retry()
        }
    }

    companion object {
        const val TAG = "projects_ai_sync"
    }
}
