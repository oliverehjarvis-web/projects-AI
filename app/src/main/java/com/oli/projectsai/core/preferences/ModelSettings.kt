package com.oli.projectsai.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

private val Context.modelSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "model_settings"
)

/**
 * Remembers the most recently loaded on-device model so the app can warm it up automatically
 * on the next cold start instead of waiting for the user to walk into Model Management.
 */
@Singleton
class ModelSettings @Inject constructor(
    @ApplicationContext context: Context
) : SettingsStore(context.modelSettingsDataStore) {

    val lastModelPath: Flow<String> = stringFlow(KEY_LAST_MODEL_PATH)
    val lastModelName: Flow<String> = stringFlow(KEY_LAST_MODEL_NAME)
    val lastModelPrecision: Flow<String> = stringFlow(KEY_LAST_MODEL_PRECISION)

    suspend fun setLastModel(path: String, name: String, precision: String) {
        set(KEY_LAST_MODEL_PATH, path)
        set(KEY_LAST_MODEL_NAME, name)
        set(KEY_LAST_MODEL_PRECISION, precision)
    }

    suspend fun clear() {
        set(KEY_LAST_MODEL_PATH, "")
        set(KEY_LAST_MODEL_NAME, "")
        set(KEY_LAST_MODEL_PRECISION, "")
    }

    private companion object {
        val KEY_LAST_MODEL_PATH = stringPreferencesKey("last_model_path")
        val KEY_LAST_MODEL_NAME = stringPreferencesKey("last_model_name")
        val KEY_LAST_MODEL_PRECISION = stringPreferencesKey("last_model_precision")
    }
}
