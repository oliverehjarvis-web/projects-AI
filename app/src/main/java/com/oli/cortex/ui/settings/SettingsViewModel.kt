package com.oli.cortex.ui.settings

import androidx.lifecycle.ViewModel
import com.oli.cortex.inference.InferenceBackend
import com.oli.cortex.inference.InferenceManager
import com.oli.cortex.inference.ModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val inferenceManager: InferenceManager
) : ViewModel() {

    val modelState: StateFlow<ModelState> = inferenceManager.modelState

    val backends: StateFlow<List<InferenceBackend>> = MutableStateFlow(
        inferenceManager.getAvailableBackends()
    )
}
