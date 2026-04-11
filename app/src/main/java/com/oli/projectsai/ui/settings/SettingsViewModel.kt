package com.oli.projectsai.ui.settings

import androidx.lifecycle.ViewModel
import com.oli.projectsai.inference.InferenceBackend
import com.oli.projectsai.inference.InferenceManager
import com.oli.projectsai.inference.ModelState
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
