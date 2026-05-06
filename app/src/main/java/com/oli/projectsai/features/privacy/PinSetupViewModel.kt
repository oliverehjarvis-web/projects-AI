package com.oli.projectsai.features.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.features.privacy.data.PinStore
import com.oli.projectsai.features.privacy.data.PrivacySession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PinSetupViewModel @Inject constructor(
    private val pinStore: PinStore,
    private val privacySession: PrivacySession
) : ViewModel() {

    private val _pin = MutableStateFlow("")
    val pin: StateFlow<String> = _pin.asStateFlow()

    private val _confirm = MutableStateFlow("")
    val confirm: StateFlow<String> = _confirm.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val done: SharedFlow<Unit> = _done.asSharedFlow()

    fun updatePin(value: String) {
        _pin.value = value.filter { it.isDigit() }.take(MAX_LEN)
        _error.value = null
    }

    fun updateConfirm(value: String) {
        _confirm.value = value.filter { it.isDigit() }.take(MAX_LEN)
        _error.value = null
    }

    fun submit() {
        val p = _pin.value
        val c = _confirm.value
        if (p.length < MIN_LEN) {
            _error.value = "PIN must be at least $MIN_LEN digits"
            return
        }
        if (p != c) {
            _error.value = "PINs don't match"
            return
        }
        viewModelScope.launch {
            pinStore.set(p)
            privacySession.unlock()
            _done.tryEmit(Unit)
        }
    }

    private companion object {
        const val MIN_LEN = 4
        const val MAX_LEN = 12
    }
}
