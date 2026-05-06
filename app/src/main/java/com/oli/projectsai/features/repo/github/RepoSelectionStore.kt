package com.oli.projectsai.features.repo.github

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cross-screen handoff for "user picked these files in the repo browser, now stage them
 * for the next chat turn". The browser writes a [Selection] when the user taps Inject;
 * the chat screen reads it on resume, attaches the files to the next outgoing message,
 * and clears it.
 *
 * Singleton so we don't have to thread navigation arguments through SavedStateHandle —
 * mirrors the [GenerationController] pattern already used elsewhere.
 */
@Singleton
class RepoSelectionStore @Inject constructor() {

    data class StagedFile(val path: String, val sizeBytes: Int, val text: String)

    data class Selection(
        val owner: String,
        val repo: String,
        val ref: String,
        val files: List<StagedFile>
    )

    private val _staged = MutableStateFlow<Selection?>(null)
    val staged: StateFlow<Selection?> = _staged.asStateFlow()

    fun stage(selection: Selection) {
        _staged.value = selection
    }

    fun clear() {
        _staged.value = null
    }
}
