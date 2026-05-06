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

private val Context.voiceSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "voice_settings"
)

@Singleton
class VoiceSettings @Inject constructor(
    @ApplicationContext context: Context
) : SettingsStore(context.voiceSettingsDataStore) {

    /** Absolute path to the on-device model used for mic transcription. */
    val voiceModelPath: Flow<String> = stringFlow(KEY_VOICE_MODEL_PATH)

    suspend fun setVoiceModelPath(value: String) = set(KEY_VOICE_MODEL_PATH, value)

    private companion object {
        val KEY_VOICE_MODEL_PATH = stringPreferencesKey("voice_model_path")
    }
}
