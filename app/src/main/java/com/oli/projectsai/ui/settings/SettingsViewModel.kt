package com.oli.projectsai.ui.settings

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.BuildConfig
import com.oli.projectsai.data.preferences.SearchSettings
import com.oli.projectsai.data.update.UpdateChecker
import com.oli.projectsai.data.update.UpdateInfo
import com.oli.projectsai.inference.InferenceBackend
import com.oli.projectsai.inference.InferenceManager
import com.oli.projectsai.inference.ModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    data object UpToDate : UpdateState()
    data class Downloading(val progress: Float?) : UpdateState()
    data class ReadyToInstall(val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inferenceManager: InferenceManager,
    private val updateChecker: UpdateChecker,
    private val searchSettings: SearchSettings
) : ViewModel() {

    companion object {
        private const val STALL_TIMEOUT_MS = 60_000L
    }

    val modelState: StateFlow<ModelState> = inferenceManager.modelState

    val backends: StateFlow<List<InferenceBackend>> = MutableStateFlow(
        inferenceManager.getAvailableBackends()
    )

    val searxngUrl: StateFlow<String> = searchSettings.searxngUrl.stateIn(
        viewModelScope, SharingStarted.Eagerly, ""
    )

    fun setSearxngUrl(value: String) {
        viewModelScope.launch { searchSettings.setSearxngUrl(value) }
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private var activeDownloadId = -1L

    fun checkForUpdate() {
        if (_updateState.value is UpdateState.Checking) return
        _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            try {
                val info = updateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
                _updateState.value = if (info != null) UpdateState.Available(info) else UpdateState.UpToDate
            } catch (t: Throwable) {
                _updateState.value = UpdateState.Error(t.message ?: "Update check failed")
            }
        }
    }

    fun downloadAndInstall(info: UpdateInfo) {
        if (_updateState.value is UpdateState.Downloading) return
        val updatesDir = File(context.getExternalFilesDir(null), "updates").also { it.mkdirs() }
        val destFile = File(updatesDir, "projects-ai-${info.version}.apk")

        val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
            .setTitle("Projects AI ${info.version}")
            .setDescription("Downloading update…")
            .setDestinationUri(Uri.fromFile(destFile))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        activeDownloadId = downloadManager.enqueue(request)
        _updateState.value = UpdateState.Downloading(null)
        viewModelScope.launch { pollDownload(destFile) }
    }

    private suspend fun pollDownload(destFile: File) {
        var lastProgressBytes = 0L
        var lastProgressAt = System.currentTimeMillis()
        while (true) {
            val snapshot = downloadManager.query(DownloadManager.Query().setFilterById(activeDownloadId))
                ?.use { c ->
                    if (c.moveToFirst()) {
                        Triple(
                            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        )
                    } else null
                }
            if (snapshot == null) {
                _updateState.value = UpdateState.Error("Download lost")
                return
            }
            val (status, downloaded, total) = snapshot

            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    _updateState.value = UpdateState.ReadyToInstall(destFile)
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    _updateState.value = UpdateState.Error("Download failed")
                    return
                }
                else -> {
                    if (downloaded > lastProgressBytes) {
                        lastProgressBytes = downloaded
                        lastProgressAt = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - lastProgressAt > STALL_TIMEOUT_MS) {
                        downloadManager.remove(activeDownloadId)
                        activeDownloadId = -1L
                        _updateState.value = UpdateState.Error("Download stalled — check your network and retry.")
                        return
                    }
                    _updateState.value = UpdateState.Downloading(
                        if (total > 0) downloaded.toFloat() / total else null
                    )
                }
            }
            delay(1_000)
        }
    }

    fun launchInstaller(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun cancelDownload() {
        if (activeDownloadId != -1L) {
            downloadManager.remove(activeDownloadId)
            activeDownloadId = -1L
        }
        _updateState.value = UpdateState.Idle
    }

    fun dismissUpdateState() {
        _updateState.value = UpdateState.Idle
    }
}
