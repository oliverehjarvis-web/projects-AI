package com.oli.projectsai.features.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.features.privacy.data.PinStore
import com.oli.projectsai.features.privacy.data.PrivacySession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PrivateGateTarget {
    data object Loading : PrivateGateTarget
    data object Setup : PrivateGateTarget
    data object Unlock : PrivateGateTarget
    data object Projects : PrivateGateTarget
}

@HiltViewModel
class PrivateGateViewModel @Inject constructor(
    private val pinStore: PinStore,
    private val privacySession: PrivacySession
) : ViewModel() {

    private val _target = MutableStateFlow<PrivateGateTarget>(PrivateGateTarget.Loading)
    val target: StateFlow<PrivateGateTarget> = _target.asStateFlow()

    init {
        viewModelScope.launch {
            val pinSet = pinStore.isSet.first()
            _target.value = when {
                !pinSet -> PrivateGateTarget.Setup
                privacySession.isUnlocked.value -> PrivateGateTarget.Projects
                else -> PrivateGateTarget.Unlock
            }
        }
    }
}
