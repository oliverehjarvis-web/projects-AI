package com.oli.projectsai.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

private val Context.assistantSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "assistant_settings"
)

/**
 * Per-user toggles for how the assistant surface presents itself. Currently only the default
 * expansion state for `<think>...</think>` reasoning blocks lives here, but anything that
 * affects how messages render belongs on this store rather than spread across feature files.
 */
@Singleton
class AssistantSettings @Inject constructor(
    @ApplicationContext context: Context
) : SettingsStore(context.assistantSettingsDataStore) {

    /** When true, finalised reasoning blocks open by default; when false, they stay collapsed. */
    val showReasoningByDefault: Flow<Boolean> =
        boolFlow(KEY_SHOW_REASONING, default = false)

    suspend fun setShowReasoningByDefault(value: Boolean) =
        set(KEY_SHOW_REASONING, value)

    /**
     * When true, a runaway `<think>` block is aborted past a fixed character budget; when false
     * (the default) the model may deliberate for as long as it needs — useful on slower local
     * models and complex prompts where the budget would otherwise cut off legitimate reasoning.
     * Off by default because the generation is already bounded by the context window.
     */
    val limitThinkingTime: Flow<Boolean> =
        boolFlow(KEY_LIMIT_THINKING, default = false)

    suspend fun setLimitThinkingTime(value: Boolean) =
        set(KEY_LIMIT_THINKING, value)

    private companion object {
        val KEY_SHOW_REASONING = booleanPreferencesKey("show_reasoning_by_default")
        val KEY_LIMIT_THINKING = booleanPreferencesKey("limit_thinking_time")
    }
}
