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

private val Context.linkedInSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "linkedin_settings"
)

/**
 * Connection details for the standalone linkedin-agent service. Kept separate from
 * RemoteSettings because the agent typically lives behind a different URL than the
 * sync server, and the user may want one configured without the other.
 */
@Singleton
class LinkedInSettings @Inject constructor(
    @ApplicationContext context: Context
) {
    private val store = context.linkedInSettingsDataStore

    val agentUrl: Flow<String> = store.data.map { it[KEY_AGENT_URL].orEmpty() }
    val agentToken: Flow<String> = store.data.map { it[KEY_AGENT_TOKEN].orEmpty() }

    suspend fun setAgentUrl(value: String) {
        store.edit { it[KEY_AGENT_URL] = value.trim().trimEnd('/') }
    }

    suspend fun setAgentToken(value: String) {
        store.edit { it[KEY_AGENT_TOKEN] = value.trim() }
    }

    private companion object {
        val KEY_AGENT_URL = stringPreferencesKey("agent_url")
        val KEY_AGENT_TOKEN = stringPreferencesKey("agent_token")
    }
}
