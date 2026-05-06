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

private val Context.githubSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "github_settings"
)

@Singleton
class GitHubSettings @Inject constructor(
    @ApplicationContext context: Context
) : SettingsStore(context.githubSettingsDataStore) {

    /** GitHub Personal Access Token. Fine-grained, repo-scoped tokens recommended. */
    val pat: Flow<String> = stringFlow(KEY_PAT)

    /** Default `owner/repo` slug pre-filled in the repo browser. */
    val defaultRepo: Flow<String> = stringFlow(KEY_DEFAULT_REPO)

    suspend fun setPat(value: String) = set(KEY_PAT, value.trim())
    suspend fun setDefaultRepo(value: String) = set(KEY_DEFAULT_REPO, value.trim())

    private companion object {
        val KEY_PAT = stringPreferencesKey("github_pat")
        val KEY_DEFAULT_REPO = stringPreferencesKey("github_default_repo")
    }
}
