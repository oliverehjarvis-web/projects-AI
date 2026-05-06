package com.oli.projectsai.features.settings

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.core.inference.InferenceManager
import com.oli.projectsai.core.inference.ModelInfo
import com.oli.projectsai.core.inference.ModelPrecision
import com.oli.projectsai.core.inference.ModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ModelFile(val name: String, val path: String)

data class RecommendedModel(
    val displayName: String,
    val url: String,
    val filename: String,
    val sizeLabel: String,
    val description: String
)

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val progress: Float?) : DownloadState() // null = indeterminate
    data object Completed : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

@HiltViewModel
class ModelManagementViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inferenceManager: InferenceManager
) : ViewModel() {

    companion object {
        private const val STALL_TIMEOUT_MS = 60_000L

        val RECOMMENDED = listOf(
            RecommendedModel(
                displayName = "Gemma 4 E4B",
                url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
                filename = "gemma-4-E4B-it.litertlm",
                sizeLabel = "3.65 GB",
                description = "Balanced quality — recommended default"
            ),
            RecommendedModel(
                displayName = "Gemma 4 E2B",
                url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                filename = "gemma-4-E2B-it.litertlm",
                sizeLabel = "1.9 GB",
                description = "Faster, smaller — trade quality for speed"
            )
        )
    }

    val modelState: StateFlow<ModelState> = inferenceManager.modelState

    private val _modelFiles = MutableStateFlow<List<ModelFile>>(emptyList())
    val modelFiles: StateFlow<List<ModelFile>> = _modelFiles.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    val recommendedModels: List<RecommendedModel> = RECOMMENDED

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private var activeDownloadId: Long = -1L
    private var activeDownloadFilename: String? = null

    private val modelsDir: File
        get() = File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

    init {
        scanModelFiles()
        _downloadStates.value = RECOMMENDED.associate { model ->
            model.filename to if (File(modelsDir, model.filename).exists())
                DownloadState.Completed else DownloadState.Idle
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

    fun downloadRecommended(model: RecommendedModel) {
        downloadFromUrl(model.url, model.filename)
    }

    /**
     * Downloads a .litertlm from a user-provided URL into the models directory.
     * The filename is derived from the URL's last path segment.
     */
    fun downloadCustomUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "Enter a URL."
        val parsed = runCatching { Uri.parse(trimmed) }.getOrNull()
        if (parsed == null || parsed.scheme !in setOf("http", "https")) {
            return "URL must start with http:// or https://"
        }
        val lastSegment = parsed.lastPathSegment ?: return "Can't derive filename from URL."
        val filename = lastSegment.substringAfterLast('/').ifBlank { return "Can't derive filename from URL." }
        if (!filename.endsWith(".litertlm") &&
            !filename.endsWith(".task") &&
            !filename.endsWith(".bin")
        ) {
            return "URL must end in .litertlm, .task, or .bin"
        }
        downloadFromUrl(trimmed, filename)
        return null
    }

    private fun downloadFromUrl(url: String, filename: String) {
        if (activeDownloadId != -1L) {
            setDownloadState(filename, DownloadState.Failed("Another download is in progress."))
            return
        }
        val destFile = File(modelsDir, filename)
        if (destFile.exists()) {
            setDownloadState(filename, DownloadState.Completed)
            scanModelFiles()
            return
        }

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(filename)
            .setDescription("Downloading $filename")
            .setDestinationUri(Uri.fromFile(destFile))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        activeDownloadId = downloadManager.enqueue(request)
        activeDownloadFilename = filename
        setDownloadState(filename, DownloadState.Downloading(null))
        viewModelScope.launch { pollDownloadProgress(filename) }
    }

    private suspend fun pollDownloadProgress(filename: String) {
        var lastProgressBytes = 0L
        var lastProgressAt = System.currentTimeMillis()
        while (true) {
            val cursor = downloadManager.query(DownloadManager.Query().setFilterById(activeDownloadId))
            if (cursor == null || !cursor.moveToFirst()) {
                setDownloadState(filename, DownloadState.Failed("Download lost"))
                clearActiveDownload()
                cursor?.close()
                return
            }
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            cursor.close()
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    setDownloadState(filename, DownloadState.Completed)
                    clearActiveDownload()
                    scanModelFiles()
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    setDownloadState(filename, DownloadState.Failed("Download failed"))
                    clearActiveDownload()
                    return
                }
                else -> {
                    if (downloaded > lastProgressBytes) {
                        lastProgressBytes = downloaded
                        lastProgressAt = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - lastProgressAt > STALL_TIMEOUT_MS) {
                        downloadManager.remove(activeDownloadId)
                        clearActiveDownload()
                        setDownloadState(
                            filename,
                            DownloadState.Failed("Download stalled — check your network and retry.")
                        )
                        return
                    }
                    setDownloadState(
                        filename,
                        DownloadState.Downloading(
                            if (total > 0) downloaded.toFloat() / total else null
                        )
                    )
                }
            }
            kotlinx.coroutines.delay(1_000)
        }
    }

    fun cancelActiveDownload() {
        val filename = activeDownloadFilename ?: return
        if (activeDownloadId != -1L) {
            downloadManager.remove(activeDownloadId)
        }
        clearActiveDownload()
        setDownloadState(filename, DownloadState.Idle)
    }

    private fun clearActiveDownload() {
        activeDownloadId = -1L
        activeDownloadFilename = null
    }

    private fun setDownloadState(filename: String, state: DownloadState) {
        _downloadStates.update { it + (filename to state) }
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
