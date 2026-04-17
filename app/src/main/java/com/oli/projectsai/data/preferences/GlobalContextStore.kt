package com.oli.projectsai.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
) {
    private val store = context.globalContextDataStore

    val name: Flow<String> = store.data.map { it[KEY_NAME].orEmpty() }
    val rules: Flow<String> = store.data.map { it[KEY_RULES].orEmpty() }

    suspend fun setName(value: String) {
        store.edit { it[KEY_NAME] = value }
    }

    suspend fun setRules(value: String) {
        store.edit { it[KEY_RULES] = value }
    }

    private companion object {
        val KEY_NAME = stringPreferencesKey("name")
        val KEY_RULES = stringPreferencesKey("rules")
    }
}
