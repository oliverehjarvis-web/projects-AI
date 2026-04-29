package com.oli.projectsai.ui.settings

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.BuildConfig
import com.oli.projectsai.data.github.GitHubClient
import com.oli.projectsai.data.preferences.GitHubSettings
import com.oli.projectsai.data.preferences.RemoteSettings
import com.oli.projectsai.data.preferences.SearchDepth
import com.oli.projectsai.data.preferences.SearchSettings
import com.oli.projectsai.data.preferences.VoiceSettings
import com.oli.projectsai.data.sync.SyncRepository
import com.oli.projectsai.data.sync.SyncResult
import com.oli.projectsai.data.update.UpdateChecker
import com.oli.projectsai.data.update.UpdateInfo
import com.oli.projectsai.inference.InferenceManager
import com.oli.projectsai.inference.ModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import javax.inject.Inject

sealed class SyncState {
    data object Idle : SyncState()
    data object Syncing : SyncState()
    data object Success : SyncState()
    data class Error(val message: String) : SyncState()
}

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    data object UpToDate : UpdateState()
    data class Downloading(val progress: Float?) : UpdateState()
    data class ReadyToInstall(val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

data class PullState(
    val modelId: String,
    val status: String,
    val progress: Int?,
    val done: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inferenceManager: InferenceManager,
    private val updateChecker: UpdateChecker,
    private val searchSettings: SearchSettings,
    private val remoteSettings: RemoteSettings,
    private val voiceSettings: VoiceSettings,
    private val githubSettings: GitHubSettings,
    private val githubClient: GitHubClient,
    private val syncRepository: SyncRepository
) : ViewModel() {

    companion object {
        private const val STALL_TIMEOUT_MS = 60_000L
    }

    val modelState: StateFlow<ModelState> = inferenceManager.modelState

    val searxngUrl: StateFlow<String> = searchSettings.searxngUrl.stateIn(
        viewModelScope, SharingStarted.Eagerly, ""
    )

    val searchDepth: StateFlow<SearchDepth> = searchSettings.searchDepth.stateIn(
        viewModelScope, SharingStarted.Eagerly, SearchDepth.AUTO_FETCH
    )

    fun setSearxngUrl(value: String) {
        viewModelScope.launch { searchSettings.setSearxngUrl(value) }
    }

    fun setSearchDepth(value: SearchDepth) {
        viewModelScope.launch { searchSettings.setSearchDepth(value) }
    }

    val serverUrl: StateFlow<String> = remoteSettings.serverUrl.stateIn(
        viewModelScope, SharingStarted.Eagerly, ""
    )
    val apiToken: StateFlow<String> = remoteSettings.apiToken.stateIn(
        viewModelScope, SharingStarted.Eagerly, ""
    )
    val remoteModel: StateFlow<String> = remoteSettings.defaultModel.stateIn(
        viewModelScope, SharingStarted.Eagerly, ""
    )
    val voiceModelPath: StateFlow<String> = voiceSettings.voiceModelPath.stateIn(
        viewModelScope, SharingStarted.Eagerly, ""
    )

    val githubPat: StateFlow<String> = githubSettings.pat.stateIn(
        viewModelScope, SharingStarted.Eagerly, ""
    )
    val githubDefaultRepo: StateFlow<String> = githubSettings.defaultRepo.stateIn(
        viewModelScope, SharingStarted.Eagerly, ""
    )

    private val _githubTestState = MutableStateFlow<String?>(null)
    val githubTestState: StateFlow<String?> = _githubTestState.asStateFlow()

    fun setGithubPat(value: String) {
        viewModelScope.launch { githubSettings.setPat(value) }
    }

    fun setGithubDefaultRepo(value: String) {
        viewModelScope.launch { githubSettings.setDefaultRepo(value) }
    }

    fun testGithubConnection() {
        viewModelScope.launch {
            _githubTestState.value = "Testing…"
            try {
                val login = githubClient.whoami()
                _githubTestState.value = "Connected as $login"
            } catch (t: Throwable) {
                _githubTestState.value = t.message ?: "Connection failed"
            }
        }
    }

    fun dismissGithubTestState() { _githubTestState.value = null }

    private val _voiceModelOptions = MutableStateFlow<List<ModelFile>>(emptyList())
    val voiceModelOptions: StateFlow<List<ModelFile>> = _voiceModelOptions.asStateFlow()

    fun refreshVoiceModelOptions() {
        val dir = File(context.getExternalFilesDir(null), "models")
        val files = dir.listFiles()
            ?.filter { it.extension in setOf("task", "bin", "litertlm") }
            ?.map { ModelFile(it.name, it.absolutePath) }
            ?: emptyList()
        _voiceModelOptions.value = files
        // Default-pick Gemma 4 E4B if nothing chosen yet and it's on disk.
        viewModelScope.launch {
            if (voiceSettings.voiceModelPath.first().isBlank()) {
                val preferred = files.firstOrNull { it.name.contains("E4B", ignoreCase = true) }
                    ?: files.firstOrNull()
                preferred?.let { voiceSettings.setVoiceModelPath(it.path) }
            }
        }
    }

    fun setVoiceModelPath(path: String) {
        viewModelScope.launch { voiceSettings.setVoiceModelPath(path) }
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    data class RemoteModel(val id: String, val label: String, val sizeGb: Double, val installed: Boolean)

    private val _remoteModels = MutableStateFlow<List<RemoteModel>>(emptyList())
    val remoteModels: StateFlow<List<RemoteModel>> = _remoteModels.asStateFlow()

    private val _remoteError = MutableStateFlow<String?>(null)
    val remoteError: StateFlow<String?> = _remoteError.asStateFlow()

    init {
        // Auto-populate the model list when the screen opens with an already-configured server.
        viewModelScope.launch {
            val url = remoteSettings.serverUrl.first()
            val token = remoteSettings.apiToken.first()
            if (url.isNotBlank() && token.isNotBlank()) fetchRemoteModels(url, token)
        }
        refreshVoiceModelOptions()
    }

    fun fetchRemoteModels(url: String, token: String) {
        if (url.isBlank() || token.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                var conn: java.net.HttpURLConnection? = null
                try {
                    conn = (java.net.URL("${url.trimEnd('/')}/v1/models").openConnection()
                            as java.net.HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                    val code = conn.responseCode
                    if (code != 200) {
                        _remoteError.value = "Server returned HTTP $code when listing models."
                        return@withContext
                    }
                    val body = conn.inputStream.bufferedReader().readText()
                    val catalogue = org.json.JSONObject(body).getJSONArray("catalogue")
                    _remoteModels.value = (0 until catalogue.length()).map { i ->
                        val m = catalogue.getJSONObject(i)
                        RemoteModel(
                            id = m.getString("id"),
                            label = m.getString("label"),
                            sizeGb = m.getDouble("size_gb"),
                            installed = m.getBoolean("installed")
                        )
                    }.sortedWith(compareByDescending<RemoteModel> { it.installed }.thenBy { it.label })
                    _remoteError.value = null
                } catch (t: Throwable) {
                    _remoteError.value = t.message ?: "Failed to fetch models"
                } finally {
                    conn?.disconnect()
                }
            }
        }
    }

    fun dismissRemoteError() { _remoteError.value = null }

    private val _pullState = MutableStateFlow<PullState?>(null)
    val pullState: StateFlow<PullState?> = _pullState.asStateFlow()
    private var pullJob: Job? = null

    fun pullModel(modelId: String) {
        if (pullJob?.isActive == true) return
        pullJob = viewModelScope.launch {
            val url = remoteSettings.serverUrl.first().trim().trimEnd('/')
            val token = remoteSettings.apiToken.first()
            if (url.isBlank() || token.isBlank()) {
                _pullState.value = PullState(modelId, "", null, done = true, error = "Configure server URL and token first.")
                return@launch
            }
            _pullState.value = PullState(modelId, "Starting…", null)
            withContext(Dispatchers.IO) {
                var conn: java.net.HttpURLConnection? = null
                try {
                    val encoded = java.net.URLEncoder.encode(modelId, "UTF-8").replace("+", "%20")
                    conn = (java.net.URL("$url/v1/models/pull/$encoded").openConnection()
                            as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 10_000
                        readTimeout = 0 // server streams indefinitely until done
                        setRequestProperty("Authorization", "Bearer $token")
                        setRequestProperty("Accept", "text/event-stream")
                        doOutput = false
                    }
                    if (conn.responseCode != 200) {
                        _pullState.value = PullState(modelId, "", null, done = true,
                            error = "Server returned HTTP ${conn.responseCode}")
                        return@withContext
                    }
                    BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (!line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            if (payload.isEmpty()) continue
                            val obj = runCatching { org.json.JSONObject(payload) }.getOrNull() ?: continue
                            val status = obj.optString("status", "")
                            val progress = if (obj.isNull("progress")) null else obj.optInt("progress")
                            val error = obj.optString("error", "").ifBlank { null }
                            if (error != null) {
                                _pullState.value = PullState(modelId, status, progress, done = true, error = error)
                                return@withContext
                            }
                            if (status == "done") {
                                _pullState.value = PullState(modelId, "Done", 100, done = true)
                                return@withContext
                            }
                            _pullState.value = PullState(modelId, status, progress)
                        }
                        // Stream ended without explicit done.
                        _pullState.value = PullState(modelId, "Done", 100, done = true)
                    }
                } catch (t: Throwable) {
                    _pullState.value = PullState(modelId, "", null, done = true,
                        error = t.message ?: "Pull failed")
                } finally {
                    conn?.disconnect()
                }
            }
            // On success, refresh catalogue and auto-select if nothing chosen yet.
            if (_pullState.value?.error == null) {
                fetchRemoteModels(url, token)
                if (remoteSettings.defaultModel.first().isBlank()) {
                    remoteSettings.setDefaultModel(modelId)
                }
            }
        }
    }

    fun dismissPullState() {
        if (pullJob?.isActive != true) _pullState.value = null
    }

    fun cancelPull() {
        pullJob?.cancel()
        pullJob = null
        _pullState.value = null
    }

    fun saveRemoteSettings(url: String, token: String, model: String) {
        viewModelScope.launch {
            remoteSettings.setServerUrl(url)
            remoteSettings.setApiToken(token)
            remoteSettings.setDefaultModel(model)
        }
    }

    fun testConnection(url: String, token: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                remoteSettings.setServerUrl(url)
                remoteSettings.setApiToken(token)
                // Wait for the write to propagate before the backend reads from RemoteSettings.
                remoteSettings.serverUrl.first { it == url.trim().trimEnd('/') }
                inferenceManager.loadModel(
                    com.oli.projectsai.inference.ModelInfo(
                        name = remoteSettings.defaultModel.first().ifBlank { "Remote server" },
                        precision = com.oli.projectsai.inference.ModelPrecision.Q4,
                        filePath = ""
                    ),
                    backendId = "remote_http"
                )
                onResult(true, "Connected")
            } catch (t: Throwable) {
                onResult(false, t.message ?: "Connection failed")
            }
        }
    }

    fun syncNow() {
        if (_syncState.value is SyncState.Syncing) return
        _syncState.value = SyncState.Syncing
        viewModelScope.launch {
            _syncState.value = when (val result = syncRepository.syncNow()) {
                is SyncResult.Success -> SyncState.Success
                is SyncResult.Skipped -> SyncState.Idle
                is SyncResult.Error -> SyncState.Error(result.message)
            }
        }
    }

    fun dismissSyncState() { _syncState.value = SyncState.Idle }

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
