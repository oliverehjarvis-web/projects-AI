package com.oli.projectsai.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

private val Context.globalContextDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "global_context"
)

/**
 * User-level settings that apply to every project — e.g. the user's name and free-form rules
 * for the assistant ("British English", "avoid em-dashes"). Assembled into the system prompt
 * ahead of project-specific context so the assistant honours them across every chat.
 */
@Singleton
class GlobalContextStore @Inject constructor(
    @ApplicationContext context: Context
) : SettingsStore(context.globalContextDataStore) {

    val name: Flow<String> = stringFlow(KEY_NAME)
    val rules: Flow<String> = stringFlow(KEY_RULES)

    suspend fun setName(value: String) = set(KEY_NAME, value)
    suspend fun setRules(value: String) = set(KEY_RULES, value)

    private companion object {
        val KEY_NAME = stringPreferencesKey("name")
        val KEY_RULES = stringPreferencesKey("rules")
    }
}
