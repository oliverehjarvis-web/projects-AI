package com.oli.projectsai.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.remoteSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "remote_settings"
)

@Singleton
class RemoteSettings @Inject constructor(
    @ApplicationContext context: Context
) {
    private val store = context.remoteSettingsDataStore

    val serverUrl: Flow<String> = store.data.map { it[KEY_SERVER_URL].orEmpty() }
    val apiToken: Flow<String> = store.data.map { it[KEY_API_TOKEN].orEmpty() }
    val defaultModel: Flow<String> = store.data.map { it[KEY_DEFAULT_MODEL].orEmpty() }
    val lastSyncAt: Flow<Long> = store.data.map { it[KEY_LAST_SYNC_AT] ?: 0L }

    suspend fun setServerUrl(value: String) {
        store.edit { it[KEY_SERVER_URL] = value.trim().trimEnd('/') }
    }

    suspend fun setApiToken(value: String) {
        store.edit { it[KEY_API_TOKEN] = value.trim() }
    }

    suspend fun setDefaultModel(value: String) {
        store.edit { it[KEY_DEFAULT_MODEL] = value.trim() }
    }

    suspend fun setLastSyncAt(value: Long) {
        store.edit { it[KEY_LAST_SYNC_AT] = value }
    }

    private companion object {
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_API_TOKEN = stringPreferencesKey("api_token")
        val KEY_DEFAULT_MODEL = stringPreferencesKey("default_model")
        val KEY_LAST_SYNC_AT = longPreferencesKey("last_sync_at")
    }
}
