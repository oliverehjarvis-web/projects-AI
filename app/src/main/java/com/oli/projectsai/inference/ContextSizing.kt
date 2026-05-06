package com.oli.projectsai.inference

import android.app.ActivityManager
import android.content.Context
import com.oli.projectsai.data.preferences.RemoteSettings
import com.oli.projectsai.net.HttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recommends a context-window size that fits in the available RAM on whichever device
 * will run the model. Used by the project-edit screen's "Auto" button so the user
 * doesn't have to do KV-cache math by hand.
 *
 * The output is rounded down to one of the values the picker offers, so the user
 * can still see and tweak the result.
 */
@Singleton
class ContextSizing @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remoteSettings: RemoteSettings,
    private val httpClient: HttpClient
) {
    companion object {
        // Picker steps that the project-edit UI exposes. Auto-pick snaps down to one of these.
        val PICKER_STEPS = listOf(2_048, 4_096, 8_192, 16_384, 32_768, 65_536, 131_072)

        // Buffers we keep free of the KV cache, irrespective of model.
        private const val PHONE_FREE_BUDGET_GB = 3f       // OS + foreground app + room for Android's own cache pressure
        private const val NAS_FREE_BUDGET_GB = 3f         // headroom for other NAS workloads + activations

        // Rough Q4 weight footprint by parameter count, GB. Used when the server can't
        // tell us a more accurate number from the Ollama model card.
        private fun weightsGbForParams(paramsB: Float): Float = paramsB * 0.6f
    }

    data class Recommendation(
        val tokens: Int,
        val rationale: String
    )

    suspend fun forLocal(modelInfo: ModelInfo): Recommendation = withContext(Dispatchers.Default) {
        val totalGb = totalDeviceRamGb()
        // Phones rarely give an app more than half their RAM in practice.
        val usableGb = (totalGb / 2f).coerceAtLeast(2f)
        val weightsGb = weightsGbForParams(paramsBFromModelInfo(modelInfo))
        val kvBudgetGb = (usableGb - weightsGb - PHONE_FREE_BUDGET_GB).coerceAtLeast(0.5f)
        val tokens = tokensForBudget(kvBudgetGb, kvPerTokenKb = 50)
        Recommendation(
            tokens = snapToPickerStep(tokens),
            rationale = "Phone RAM ≈ ${"%.1f".format(totalGb)} GB; ≈ ${"%.1f".format(kvBudgetGb)} GB free for KV cache after weights and OS overhead."
        )
    }

    suspend fun forRemote(modelInfo: ModelInfo): Recommendation = withContext(Dispatchers.IO) {
        val info = fetchServerInfo(modelInfo.name) ?: return@withContext Recommendation(
            tokens = 32_768,
            rationale = "Could not reach /v1/server_info — falling back to a safe 32k default."
        )
        val totalGb = info.optDouble("ram_total_gb", 0.0).toFloat()
        val availableGb = info.optDouble("ram_available_gb", 0.0).toFloat()
        val paramsB: Float = info.optDouble("model_params_b", 0.0).toFloat().takeIf { it > 0f }
            ?: paramsBFromModelInfo(modelInfo)
        val kvPerTokenKb = info.optInt("kv_per_token_kb", 80)

        val weightsGb = weightsGbForParams(paramsB)
        // Prefer ram_available_gb (live) over total minus weights (assumes weights aren't loaded).
        val liveBudgetGb = (availableGb - NAS_FREE_BUDGET_GB).coerceAtLeast(0.5f)
        val staticBudgetGb = (totalGb - weightsGb - NAS_FREE_BUDGET_GB).coerceAtLeast(0.5f)
        val kvBudgetGb = minOf(liveBudgetGb, staticBudgetGb)

        val tokens = tokensForBudget(kvBudgetGb, kvPerTokenKb)
        Recommendation(
            tokens = snapToPickerStep(tokens),
            rationale = "NAS RAM ≈ ${"%.0f".format(totalGb)} GB total / ${"%.0f".format(availableGb)} GB free; " +
                "${"%.1f".format(kvBudgetGb)} GB headroom at ${kvPerTokenKb} KB/token."
        )
    }

    private fun totalDeviceRamGb(): Float {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 4f
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem.toFloat() / (1024f * 1024f * 1024f)
    }

    private fun tokensForBudget(kvBudgetGb: Float, kvPerTokenKb: Int): Int {
        if (kvPerTokenKb <= 0) return PICKER_STEPS.first()
        val tokens = (kvBudgetGb * 1024f * 1024f) / kvPerTokenKb.toFloat()
        return tokens.toInt().coerceAtLeast(2_048)
    }

    private fun snapToPickerStep(tokens: Int): Int {
        // Largest step that fits.
        return PICKER_STEPS.lastOrNull { it <= tokens } ?: PICKER_STEPS.first()
    }

    /**
     * Best-effort guess at parameter count from the model's filename. Gemma 4 e4b
     * → 4B; Gemma 4 26b a4b → 26B. Falls back to 4B for unknowns since the local
     * backend is most often hosting a small model.
     */
    private fun paramsBFromModelInfo(modelInfo: ModelInfo): Float {
        val name = modelInfo.name.lowercase()
        Regex("""(\d+(?:\.\d+)?)\s*b""").find(name)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
        return 4f
    }

    private suspend fun fetchServerInfo(modelName: String): JSONObject? {
        val url = remoteSettings.serverUrl.first().ifBlank { return null }
        val token = remoteSettings.apiToken.first()
        val encoded = URLEncoder.encode(modelName, "UTF-8")
        return runCatching {
            val body = httpClient.get(
                url = "$url/v1/server_info?model=$encoded",
                bearer = token.takeIf { it.isNotBlank() },
                connectTimeoutMs = 5_000,
                readTimeoutMs = 5_000
            )
            JSONObject(body)
        }.getOrNull()
    }
}
