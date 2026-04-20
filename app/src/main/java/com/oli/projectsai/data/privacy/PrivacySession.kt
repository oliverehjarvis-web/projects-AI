package com.oli.projectsai.data.privacy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory unlock gate for secret projects. Dies with the process — every app launch
 * re-locks so returning from recents after a cold start requires the PIN again.
 */
@Singleton
class PrivacySession @Inject constructor() {
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    fun unlock() { _isUnlocked.value = true }
    fun lock() { _isUnlocked.value = false }
}
