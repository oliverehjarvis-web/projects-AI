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

enum class SearchDepth {
    /** Run search, then auto-fetch the top results' full-page text in one go. */
    AUTO_FETCH,
    /** Give the model an explicit <fetch> tool and let it choose what to read. */
    TOOL_LOOP
}

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

    val searchDepth: Flow<SearchDepth> = store.data.map {
        when (it[KEY_SEARCH_DEPTH]) {
            SearchDepth.TOOL_LOOP.name -> SearchDepth.TOOL_LOOP
            else -> SearchDepth.AUTO_FETCH
        }
    }

    suspend fun setSearxngUrl(value: String) {
        store.edit { it[KEY_SEARXNG_URL] = value.trim().trimEnd('/') }
    }

    suspend fun setSearchDepth(value: SearchDepth) {
        store.edit { it[KEY_SEARCH_DEPTH] = value.name }
    }

    private companion object {
        val KEY_SEARXNG_URL = stringPreferencesKey("searxng_url")
        val KEY_SEARCH_DEPTH = stringPreferencesKey("search_depth")
    }
}
