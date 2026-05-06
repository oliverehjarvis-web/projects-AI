package com.oli.projectsai.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

private val Context.remoteSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "remote_settings"
)

@Singleton
class RemoteSettings @Inject constructor(
    @ApplicationContext context: Context
) : SettingsStore(context.remoteSettingsDataStore) {

    val serverUrl: Flow<String> = stringFlow(KEY_SERVER_URL)
    val apiToken: Flow<String> = stringFlow(KEY_API_TOKEN)
    val defaultModel: Flow<String> = stringFlow(KEY_DEFAULT_MODEL)
    val lastSyncAt: Flow<Long> = longFlow(KEY_LAST_SYNC_AT)

    suspend fun setServerUrl(value: String) = set(KEY_SERVER_URL, value.trim().trimEnd('/'))
    suspend fun setApiToken(value: String) = set(KEY_API_TOKEN, value.trim())
    suspend fun setDefaultModel(value: String) = set(KEY_DEFAULT_MODEL, value.trim())
    suspend fun setLastSyncAt(value: Long) = set(KEY_LAST_SYNC_AT, value)

    private companion object {
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_API_TOKEN = stringPreferencesKey("api_token")
        val KEY_DEFAULT_MODEL = stringPreferencesKey("default_model")
        val KEY_LAST_SYNC_AT = longPreferencesKey("last_sync_at")
    }
}
