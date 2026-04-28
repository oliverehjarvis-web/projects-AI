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

private val Context.githubSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "github_settings"
)

@Singleton
class GitHubSettings @Inject constructor(
    @ApplicationContext context: Context
) {
    private val store = context.githubSettingsDataStore

    /** GitHub Personal Access Token. Fine-grained, repo-scoped tokens recommended. */
    val pat: Flow<String> = store.data.map { it[KEY_PAT].orEmpty() }

    /** Default `owner/repo` slug pre-filled in the repo browser. */
    val defaultRepo: Flow<String> = store.data.map { it[KEY_DEFAULT_REPO].orEmpty() }

    suspend fun setPat(value: String) {
        store.edit { it[KEY_PAT] = value.trim() }
    }

    suspend fun setDefaultRepo(value: String) {
        store.edit { it[KEY_DEFAULT_REPO] = value.trim() }
    }

    private companion object {
        val KEY_PAT = stringPreferencesKey("github_pat")
        val KEY_DEFAULT_REPO = stringPreferencesKey("github_default_repo")
    }
}
