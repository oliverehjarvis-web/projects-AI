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
class PrivacyUnlockViewModel @Inject constructor(
    private val pinStore: PinStore,
    private val privacySession: PrivacySession
) : ViewModel() {

    private val _pin = MutableStateFlow("")
    val pin: StateFlow<String> = _pin.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _unlocked = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unlocked: SharedFlow<Unit> = _unlocked.asSharedFlow()

    fun updatePin(value: String) {
        _pin.value = value.filter { it.isDigit() }.take(12)
        _error.value = null
    }

    fun submit() {
        if (_checking.value) return
        val p = _pin.value
        if (p.isEmpty()) return
        viewModelScope.launch {
            _checking.value = true
            val ok = pinStore.verify(p)
            _checking.value = false
            if (ok) {
                privacySession.unlock()
                _unlocked.tryEmit(Unit)
            } else {
                _error.value = "Incorrect PIN"
                _pin.value = ""
            }
        }
    }
}
