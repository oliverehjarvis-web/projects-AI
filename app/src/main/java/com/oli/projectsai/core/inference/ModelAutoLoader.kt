package com.oli.projectsai.core.inference

import android.content.Context
import android.util.Log
import com.oli.projectsai.core.preferences.ModelSettings
import com.oli.projectsai.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Warms up the on-device backend with the most recently used model on app startup so the user
 * can fire off a chat the moment the launcher icon settles. Without this, the first prompt of
 * each session blocks behind a CPU-heavy `Engine.initialize()` that the user has to trigger
 * manually from Model Management.
 *
 * Runs at-most-once per process — [ensureLoaded] is idempotent. Silently no-ops when there's
 * no remembered model, the file's been deleted, or another model is already resident (e.g.
 * the user navigated to Model Management before we got here and picked a different one).
 */
@Singleton
class ModelAutoLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inferenceManager: InferenceManager,
    private val modelSettings: ModelSettings,
    @ApplicationScope private val scope: CoroutineScope,
) {
    @Volatile private var attempted = false

    fun ensureLoaded() {
        if (attempted) return
        attempted = true
        scope.launch {
            try {
                if (inferenceManager.modelState.value !is ModelState.Unloaded) return@launch
                val path = modelSettings.lastModelPath.first()
                val name = modelSettings.lastModelName.first()
                val precisionName = modelSettings.lastModelPrecision.first()
                if (path.isBlank()) {
                    autoSelectIfSingleFile()
                    return@launch
                }
                if (!File(path).exists()) {
                    modelSettings.clear()
                    autoSelectIfSingleFile()
                    return@launch
                }
                val precision = runCatching { ModelPrecision.valueOf(precisionName) }
                    .getOrDefault(ModelPrecision.Q4)
                inferenceManager.loadModel(
                    ModelInfo(name = name.ifBlank { File(path).name }, precision = precision, filePath = path)
                )
            } catch (t: Throwable) {
                // Auto-load is best-effort — surface the failure as a regular ModelState.Error
                // (already set by InferenceManager) and let the user retry from Model Management.
                Log.w(TAG, "Auto-load failed: ${t.message}")
            }
        }
    }

    /**
     * If the user has exactly one model on disk but never loaded it (so [ModelSettings] has
     * nothing remembered), pick it. Skipped when there are zero or several files — the choice
     * is then ambiguous and we'd rather wait for an explicit pick.
     */
    private suspend fun autoSelectIfSingleFile() {
        val modelsDir = File(context.getExternalFilesDir(null), "models")
        val files = modelsDir.listFiles()
            ?.filter { it.extension in setOf("task", "bin", "litertlm") }
            ?: emptyList()
        val only = files.singleOrNull() ?: return
        runCatching {
            inferenceManager.loadModel(
                ModelInfo(name = only.nameWithoutExtension, precision = ModelPrecision.Q4, filePath = only.absolutePath)
            )
            modelSettings.setLastModel(only.absolutePath, only.nameWithoutExtension, ModelPrecision.Q4.name)
        }
    }

    private companion object {
        private const val TAG = "ModelAutoLoader"
    }
}
