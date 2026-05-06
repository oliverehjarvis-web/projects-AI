package com.oli.projectsai.core.inference

import com.oli.projectsai.di.ApplicationScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/** Default Gemma 4 E4B context window. Overridden per-model when known. */
const val DEFAULT_CONTEXT_LENGTH = 8192

sealed class ModelState {
    data object Unloaded : ModelState()
    data class Loading(val modelInfo: ModelInfo) : ModelState()
    data class Loaded(val modelInfo: ModelInfo) : ModelState()
    data class Error(val message: String) : ModelState()
}

@Singleton
class InferenceManager @Inject constructor(
    private val localBackend: LocalLiteRtBackend,
    private val remoteBackend: RemoteHttpBackend,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val _modelState = MutableStateFlow<ModelState>(ModelState.Unloaded)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    /**
     * Bumps every time a model is successfully loaded. UI code uses this to invalidate
     * cached token counts — after a model swap the calibrated tokenizer ratio changes.
     */
    private val _tokenizerVersion = MutableStateFlow(0L)
    val tokenizerVersion: StateFlow<Long> = _tokenizerVersion.asStateFlow()

    val contextLimitFlow: StateFlow<Int> = _modelState
        .map { (it as? ModelState.Loaded)?.modelInfo?.contextLength ?: DEFAULT_CONTEXT_LENGTH }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_CONTEXT_LENGTH)

    private val backends: Map<String, InferenceBackend> = mapOf(
        localBackend.id to localBackend,
        remoteBackend.id to remoteBackend
    )

    fun getBackend(id: String): InferenceBackend? = backends[id]

    fun getAvailableBackends(): List<InferenceBackend> = backends.values.filter { it.isAvailable }

    fun getActiveBackend(): InferenceBackend? = backends.values.firstOrNull { it.isLoaded }

    suspend fun loadModel(modelInfo: ModelInfo, backendId: String = "local_litertlm") {
        val backend = backends[backendId]
            ?: throw IllegalArgumentException("Unknown backend: $backendId")

        _modelState.value = ModelState.Loading(modelInfo)
        try {
            backend.loadModel(modelInfo)
            _modelState.value = ModelState.Loaded(modelInfo)
            _tokenizerVersion.value = _tokenizerVersion.value + 1
        } catch (ie: InferenceError) {
            _modelState.value = ModelState.Error(ie.message ?: "Failed to load model")
            throw ie
        } catch (e: Exception) {
            _modelState.value = ModelState.Error(e.message ?: "Failed to load model")
            throw InferenceError.LoadFailed(e)
        }
    }

    suspend fun unloadModel() {
        backends.values.forEach { if (it.isLoaded) it.unloadModel() }
        _modelState.value = ModelState.Unloaded
    }

    suspend fun generate(
        systemPrompt: String,
        messages: List<ChatMessage>,
        config: GenerationConfig = GenerationConfig(),
        backendId: String? = null
    ): Flow<String> {
        val backend = if (backendId != null) {
            backends[backendId] ?: throw IllegalArgumentException("Unknown backend: $backendId")
        } else {
            getActiveBackend() ?: throw InferenceError.ModelNotLoaded
        }
        return backend.generate(systemPrompt, messages, config)
    }

    suspend fun transcribe(pcm16MonoBytes: ByteArray, promptOverride: String? = null): String {
        val backend = getActiveBackend() ?: throw InferenceError.ModelNotLoaded
        return backend.transcribe(pcm16MonoBytes, promptOverride)
    }

    /**
     * Loads [voiceModel] into the on-device backend if nothing is loaded yet, leaving the
     * engine warm for subsequent transcribe calls. Does NOT touch [_modelState] — the chat
     * UI keeps showing whichever backend/model the user actually picked for chat.
     */
    suspend fun prepareLocalForTranscription(voiceModel: ModelInfo) {
        if (!localBackend.isLoaded) localBackend.loadModel(voiceModel)
    }

    /** Transcribes via the on-device backend regardless of which backend is active for chat. */
    suspend fun transcribeViaLocal(
        pcm16MonoBytes: ByteArray,
        promptOverride: String? = null
    ): String {
        if (!localBackend.isLoaded) throw InferenceError.ModelNotLoaded
        return localBackend.transcribe(pcm16MonoBytes, promptOverride)
    }

    /** True when the local backend has a model resident and ready for transcription. */
    val localBackendReady: Boolean get() = localBackend.isLoaded

    /** Returns 0 when no backend is active rather than crashing — callers must tolerate this. */
    suspend fun countTokens(text: String, backendId: String? = null): Int {
        val backend = if (backendId != null) {
            backends[backendId] ?: throw IllegalArgumentException("Unknown backend: $backendId")
        } else {
            getActiveBackend() ?: return 0
        }
        return backend.countTokens(text)
    }
}
