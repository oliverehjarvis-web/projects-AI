package com.oli.cortex.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class ModelState {
    data object Unloaded : ModelState()
    data class Loading(val modelInfo: ModelInfo) : ModelState()
    data class Loaded(val modelInfo: ModelInfo) : ModelState()
    data class Error(val message: String) : ModelState()
}

@Singleton
class InferenceManager @Inject constructor(
    private val localBackend: LocalMediaPipeBackend
    // TODO: Add RemoteHttpBackend when implementing remote inference
) {
    private val _modelState = MutableStateFlow<ModelState>(ModelState.Unloaded)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val backends: Map<String, InferenceBackend> = mapOf(
        localBackend.id to localBackend
        // TODO: "remote_http" to remoteBackend
    )

    fun getBackend(id: String): InferenceBackend? = backends[id]

    fun getAvailableBackends(): List<InferenceBackend> = backends.values.filter { it.isAvailable }

    fun getActiveBackend(): InferenceBackend? = backends.values.firstOrNull { it.isLoaded }

    suspend fun loadModel(modelInfo: ModelInfo, backendId: String = "local_mediapipe") {
        val backend = backends[backendId]
            ?: throw IllegalArgumentException("Unknown backend: $backendId")

        _modelState.value = ModelState.Loading(modelInfo)
        try {
            backend.loadModel(modelInfo)
            _modelState.value = ModelState.Loaded(modelInfo)
        } catch (e: Exception) {
            _modelState.value = ModelState.Error(e.message ?: "Failed to load model")
            throw e
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
            getActiveBackend() ?: throw IllegalStateException("No model loaded")
        }
        return backend.generate(systemPrompt, messages, config)
    }

    suspend fun countTokens(text: String, backendId: String? = null): Int {
        val backend = if (backendId != null) {
            backends[backendId] ?: throw IllegalArgumentException("Unknown backend: $backendId")
        } else {
            getActiveBackend() ?: backends.values.first()
        }
        return backend.countTokens(text)
    }
}
