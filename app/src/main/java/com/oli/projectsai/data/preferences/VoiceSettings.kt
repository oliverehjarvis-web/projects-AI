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

private val Context.voiceSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "voice_settings"
)

@Singleton
class VoiceSettings @Inject constructor(
    @ApplicationContext context: Context
) {
    private val store = context.voiceSettingsDataStore

    /** Absolute path to the on-device model used for mic transcription. */
    val voiceModelPath: Flow<String> = store.data.map { it[KEY_VOICE_MODEL_PATH].orEmpty() }

    suspend fun setVoiceModelPath(value: String) {
        store.edit { it[KEY_VOICE_MODEL_PATH] = value }
    }

    private companion object {
        val KEY_VOICE_MODEL_PATH = stringPreferencesKey("voice_model_path")
    }
}
