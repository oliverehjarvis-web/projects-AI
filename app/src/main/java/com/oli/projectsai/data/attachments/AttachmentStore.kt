package com.oli.projectsai.data.attachments

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies user-picked image URIs into app-private storage so the app retains access across reboots
 * and content-provider permission revocation. Returned paths are stable absolute file paths.
 */
@Singleton
class AttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dir: File
        get() = File(context.filesDir, "attachments").also { it.mkdirs() }

    suspend fun importImage(uri: Uri): String = withContext(Dispatchers.IO) {
        val ext = inferExtension(uri)
        val dest = File(dir, "img_${System.currentTimeMillis()}_${(0..9999).random()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not open image: $uri")
        dest.absolutePath
    }

    suspend fun readBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        File(path).readBytes()
    }

    private fun inferExtension(uri: Uri): String {
        val mime = context.contentResolver.getType(uri)
        return when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
    }
}
