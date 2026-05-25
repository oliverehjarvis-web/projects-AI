package com.oli.projectsai.core.repository

import com.oli.projectsai.core.db.dao.TranscriptionDao
import com.oli.projectsai.core.db.entity.Transcription
import com.oli.projectsai.core.db.entity.TranscriptionSource
import com.oli.projectsai.core.inference.ChatMessage
import com.oli.projectsai.core.inference.GenerationConfig
import com.oli.projectsai.core.inference.InferenceManager
import com.oli.projectsai.core.inference.SummarisationPrompts
import com.oli.projectsai.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranscriptionRepository @Inject constructor(
    private val transcriptionDao: TranscriptionDao,
    private val inferenceManager: InferenceManager,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Reactive history. A blank [query] returns everything, newest first. */
    fun history(query: String): Flow<List<Transcription>> =
        transcriptionDao.searchFlow(query.trim())

    fun transcription(id: Long): Flow<Transcription?> = transcriptionDao.getByIdFlow(id)

    suspend fun saveShortForm(text: String): Long =
        save(text, TranscriptionSource.SHORT_FORM, audioFileName = null)

    suspend fun saveLongForm(text: String, audioFileName: String?): Long =
        save(text, TranscriptionSource.LONG_FORM, audioFileName)

    /**
     * Inserts a transcription with a provisional title derived from its first words, then kicks off
     * AI title generation on the application scope so it survives the user navigating away. The
     * provisional title means the row is always recognisable even if no model is loaded or
     * generation fails.
     */
    private suspend fun save(
        text: String,
        source: TranscriptionSource,
        audioFileName: String?,
    ): Long {
        val id = transcriptionDao.insert(
            Transcription(
                text = text,
                title = deriveTitle(text),
                source = source,
                audioFileName = audioFileName,
            )
        )
        scope.launch { generateTitle(id, text) }
        return id
    }

    private suspend fun generateTitle(id: Long, text: String) {
        val title = runCatching {
            val (system, user) = SummarisationPrompts.buildTranscriptTitlePrompt(text.take(TITLE_INPUT_CHARS))
            val out = StringBuilder()
            inferenceManager.generate(
                systemPrompt = system,
                messages = listOf(ChatMessage(role = "user", content = user)),
                config = GenerationConfig(applyDefaultPreamble = false, maxOutputTokens = 32, temperature = 0.3f),
            ).collect { out.append(it) }
            out.toString().cleanTitle()
        }.getOrNull()
        if (!title.isNullOrBlank()) transcriptionDao.updateTitle(id, title)
    }

    /**
     * Generates and persists a summary for the transcription. The caller (detail ViewModel) owns
     * the loading/error UI; this throws on failure rather than swallowing it so the screen can tell
     * the user. Requires a loaded model — [InferenceManager.generate] throws if none is active.
     */
    suspend fun summarise(id: Long): String {
        val current = transcriptionDao.getById(id) ?: error("Transcription no longer exists.")
        val (system, user) = SummarisationPrompts.buildTranscriptSummaryPrompt(
            current.text.take(SUMMARY_INPUT_CHARS)
        )
        val out = StringBuilder()
        inferenceManager.generate(
            systemPrompt = system,
            messages = listOf(ChatMessage(role = "user", content = user)),
            config = GenerationConfig(applyDefaultPreamble = false),
        ).collect { out.append(it) }
        val summary = out.toString().trim()
        if (summary.isBlank()) error("The model returned an empty summary.")
        transcriptionDao.updateSummary(id, summary)
        return summary
    }

    suspend fun rename(id: Long, title: String) {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty()) transcriptionDao.updateTitle(id, trimmed)
    }

    suspend fun tagProject(id: Long, projectId: Long?) = transcriptionDao.updateProjectId(id, projectId)

    suspend fun delete(id: Long) = transcriptionDao.softDelete(id)

    private companion object {
        const val TITLE_INPUT_CHARS = 2000
        const val SUMMARY_INPUT_CHARS = 12000

        /** First words of the transcript — the fallback shown until the model titles it. */
        fun deriveTitle(text: String): String {
            val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (firstLine.isEmpty()) return "Untitled transcript"
            val words = firstLine.split(Regex("\\s+")).take(8).joinToString(" ")
            return if (words.length < firstLine.length) "$words…" else words
        }

        /** Models occasionally wrap titles in quotes or add a trailing period; strip that. */
        fun String.cleanTitle(): String =
            trim()
                .lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                .trim()
                .trim('"', '\'', '`')
                .trimEnd('.')
                .take(80)
                .trim()
    }
}
