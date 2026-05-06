package com.oli.projectsai.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.core.preferences.GlobalContextStore
import com.oli.projectsai.core.inference.ChatMessage
import com.oli.projectsai.core.inference.GenerationConfig
import com.oli.projectsai.core.inference.InferenceManager
import com.oli.projectsai.core.inference.ModelState
import com.oli.projectsai.core.inference.SummarisationPrompts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GlobalContextViewModel @Inject constructor(
    private val store: GlobalContextStore,
    private val inferenceManager: InferenceManager
) : ViewModel() {

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _rules = MutableStateFlow("")
    val rules: StateFlow<String> = _rules.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _isRefining = MutableStateFlow(false)
    val isRefining: StateFlow<Boolean> = _isRefining.asStateFlow()

    private val _refineError = MutableStateFlow<String?>(null)
    val refineError: StateFlow<String?> = _refineError.asStateFlow()

    init {
        viewModelScope.launch {
            _name.value = store.name.first()
            _rules.value = store.rules.first()
        }
    }

    fun updateName(value: String) {
        _name.value = value
        _saved.value = false
    }

    fun updateRules(value: String) {
        _rules.value = value
        _saved.value = false
    }

    fun save() {
        viewModelScope.launch {
            store.setName(_name.value.trim())
            store.setRules(_rules.value.trim())
            _saved.value = true
        }
    }

    fun refineRules() {
        val raw = _rules.value
        if (raw.isBlank()) return
        if (inferenceManager.modelState.value !is ModelState.Loaded) {
            _refineError.value = "Load a model first to use context refinement."
            return
        }
        viewModelScope.launch {
            _isRefining.value = true
            try {
                val (system, user) = SummarisationPrompts.buildGlobalRulesRefinePrompt(raw)
                val out = StringBuilder()
                inferenceManager.generate(
                    systemPrompt = system,
                    messages = listOf(ChatMessage(role = "user", content = user)),
                    config = GenerationConfig()
                ).collect { chunk -> out.append(chunk) }
                val refined = out.toString().trim()
                if (refined.isNotBlank()) updateRules(refined)
            } catch (t: Throwable) {
                _refineError.value = "Refinement failed: ${t.message ?: "unknown error"}"
            } finally {
                _isRefining.value = false
            }
        }
    }

    fun clearRefineError() {
        _refineError.value = null
    }
}
