package com.oli.projectsai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.data.preferences.GlobalContextStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GlobalContextViewModel @Inject constructor(
    private val store: GlobalContextStore
) : ViewModel() {

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _rules = MutableStateFlow("")
    val rules: StateFlow<String> = _rules.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

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
}
