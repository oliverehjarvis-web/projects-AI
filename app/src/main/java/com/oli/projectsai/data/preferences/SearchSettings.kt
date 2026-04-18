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
 * Local-only storage for the SearXNG base URL (e.g. a Tailscale address). Kept out of the
 * repository so machine-specific endpoints never ship in the APK; the user types it in Settings.
 */
@Singleton
class SearchSettings @Inject constructor(
    @ApplicationContext context: Context
) {
    private val store = context.searchSettingsDataStore

    val searxngUrl: Flow<String> = store.data.map { it[KEY_SEARXNG_URL].orEmpty() }

    suspend fun setSearxngUrl(value: String) {
        store.edit { it[KEY_SEARXNG_URL] = value.trim().trimEnd('/') }
    }

    private companion object {
        val KEY_SEARXNG_URL = stringPreferencesKey("searxng_url")
    }
}
