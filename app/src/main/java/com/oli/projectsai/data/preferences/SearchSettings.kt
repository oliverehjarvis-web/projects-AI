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

private val Context.searchSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "search_settings"
)

/**
 * Local-only storage for the Brave Search API key. Kept out of the repository so the key never
 * ships in the APK; the user pastes it in Settings.
 */
@Singleton
class SearchSettings @Inject constructor(
    @ApplicationContext context: Context
) {
    private val store = context.searchSettingsDataStore

    val braveApiKey: Flow<String> = store.data.map { it[KEY_BRAVE].orEmpty() }

    suspend fun setBraveApiKey(value: String) {
        store.edit { it[KEY_BRAVE] = value.trim() }
    }

    private companion object {
        val KEY_BRAVE = stringPreferencesKey("brave_api_key")
    }
}
