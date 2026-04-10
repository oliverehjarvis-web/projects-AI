package com.oli.cortex.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.cortex.inference.InferenceManager
import com.oli.cortex.inference.ModelInfo
import com.oli.cortex.inference.ModelPrecision
import com.oli.cortex.inference.ModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ModelFile(val name: String, val path: String)

@HiltViewModel
class ModelManagementViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inferenceManager: InferenceManager
) : ViewModel() {

    val modelState: StateFlow<ModelState> = inferenceManager.modelState

    private val _modelFiles = MutableStateFlow<List<ModelFile>>(emptyList())
    val modelFiles: StateFlow<List<ModelFile>> = _modelFiles.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val modelsDir: File
        get() = File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

    init {
        scanModelFiles()
    }

    private fun scanModelFiles() {
        val files = modelsDir.listFiles()
            ?.filter { it.extension == "task" || it.extension == "bin" }
            ?.map { ModelFile(it.name, it.absolutePath) }
            ?: emptyList()
        _modelFiles.value = files
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            try {
                _loadError.value = null
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot read file")

                val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "model.task"
                val destFile = File(modelsDir, fileName)

                inputStream.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                scanModelFiles()
            } catch (e: Exception) {
                _loadError.value = "Import failed: ${e.message}"
            }
        }
    }

    fun loadModel(path: String, name: String, precision: ModelPrecision) {
        viewModelScope.launch {
            try {
                _loadError.value = null
                inferenceManager.loadModel(
                    ModelInfo(
                        name = name,
                        precision = precision,
                        filePath = path
                    )
                )
            } catch (e: Exception) {
                _loadError.value = "Load failed: ${e.message}"
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            inferenceManager.unloadModel()
        }
    }
}
