package com.oli.projectsai.ui.settings

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.inference.InferenceManager
import com.oli.projectsai.inference.ModelInfo
import com.oli.projectsai.inference.ModelPrecision
import com.oli.projectsai.inference.ModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ModelFile(val name: String, val path: String)

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float?) : DownloadState() // null = indeterminate
    object Completed : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

@HiltViewModel
class ModelManagementViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inferenceManager: InferenceManager
) : ViewModel() {

    companion object {
        const val GEMMA4_URL = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
        const val GEMMA4_FILENAME = "gemma-4-E4B-it.litertlm"
        private const val STALL_TIMEOUT_MS = 60_000L
    }

    val modelState: StateFlow<ModelState> = inferenceManager.modelState

    private val _modelFiles = MutableStateFlow<List<ModelFile>>(emptyList())
    val modelFiles: StateFlow<List<ModelFile>> = _modelFiles.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private var activeDownloadId: Long = -1L

    private val modelsDir: File
        get() = File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

    init {
        scanModelFiles()
        // Reflect already-downloaded state on re-open
        if (File(modelsDir, GEMMA4_FILENAME).exists()) {
            _downloadState.value = DownloadState.Completed
        }
    }

    private fun scanModelFiles() {
        val files = modelsDir.listFiles()
            ?.filter { it.extension in setOf("task", "bin", "litertlm") }
            ?.map { ModelFile(it.name, it.absolutePath) }
            ?: emptyList()
        _modelFiles.value = files
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            try {
                _loadError.value = null
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot read file")

                val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "model.task"
                val destFile = File(modelsDir, fileName)

                inputStream.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                scanModelFiles()
            } catch (e: Exception) {
                _loadError.value = "Import failed: ${e.message}"
            }
        }
    }

    fun downloadGemma4() {
        if (_downloadState.value is DownloadState.Downloading) return
        val destFile = File(modelsDir, GEMMA4_FILENAME)
        if (destFile.exists()) {
            _downloadState.value = DownloadState.Completed
            scanModelFiles()
            return
        }

        val request = DownloadManager.Request(Uri.parse(GEMMA4_URL))
            .setTitle("Gemma 4 E4B")
            .setDescription("Downloading AI model (3.65 GB)...")
            .setDestinationUri(Uri.fromFile(destFile))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        activeDownloadId = downloadManager.enqueue(request)
        _downloadState.value = DownloadState.Downloading(null)
        viewModelScope.launch { pollDownloadProgress() }
    }

    private suspend fun pollDownloadProgress() {
        var lastProgressBytes = 0L
        var lastProgressAt = System.currentTimeMillis()
        while (true) {
            val cursor = downloadManager.query(DownloadManager.Query().setFilterById(activeDownloadId))
            if (cursor == null || !cursor.moveToFirst()) {
                _downloadState.value = DownloadState.Failed("Download lost")
                cursor?.close()
                return
            }
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            cursor.close()
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    _downloadState.value = DownloadState.Completed
                    scanModelFiles()
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    _downloadState.value = DownloadState.Failed("Download failed")
                    return
                }
                else -> {
                    if (downloaded > lastProgressBytes) {
                        lastProgressBytes = downloaded
                        lastProgressAt = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - lastProgressAt > STALL_TIMEOUT_MS) {
                        downloadManager.remove(activeDownloadId)
                        activeDownloadId = -1L
                        _downloadState.value = DownloadState.Failed(
                            "Download stalled — check your network and retry."
                        )
                        return
                    }
                    _downloadState.value = DownloadState.Downloading(
                        if (total > 0) downloaded.toFloat() / total else null
                    )
                }
            }
            kotlinx.coroutines.delay(1_000)
        }
    }

    fun cancelDownload() {
        if (activeDownloadId != -1L) {
            downloadManager.remove(activeDownloadId)
            activeDownloadId = -1L
        }
        _downloadState.value = DownloadState.Idle
    }

    fun loadModel(path: String, name: String, precision: ModelPrecision) {
        viewModelScope.launch {
            try {
                _loadError.value = null
                inferenceManager.loadModel(
                    ModelInfo(
                        name = name,
                        precision = precision,
                        filePath = path
                    )
                )
            } catch (e: Exception) {
                _loadError.value = "Load failed: ${e.message}"
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            inferenceManager.unloadModel()
        }
    }
}
