package com.jarvis.assistant.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import com.jarvis.assistant.speech.ModelArchive
import com.jarvis.assistant.speech.ModelArchiveException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Static description of a downloadable local model. */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val downloadUrl: String,
    val sha256: String,
    val downloadBytes: Long,
    val license: String,
    val source: String,
    val requiredFiles: List<String>,
    val maxExtractedBytes: Long,
)

sealed interface ModelState {
    data object Missing : ModelState

    /** [progress] in 0..1, or null if the total size is unknown. */
    data class Downloading(val progress: Float?) : ModelState
    data object Verifying : ModelState
    data class Ready(val sizeBytes: Long) : ModelState
    data class Error(val message: String) : ModelState

    val isBusy: Boolean get() = this is Downloading || this is Verifying
}

/**
 * Installs local models into app-private storage (filesDir/models). Nothing is
 * bundled in the APK; the user downloads (one-time, needs internet) or imports
 * the archive via the Storage Access Framework. Every archive's SHA-256 is
 * verified before it is unpacked, and unpacking is protected against zip-slip.
 *
 * Process-wide singleton so the UI and the foreground service see one state.
 */
class LocalModelManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelsDir = File(appContext.filesDir, MODELS_DIR)
    private val workDir = File(modelsDir, WORK_DIR)
    private var job: Job? = null

    val spec: ModelSpec = VOSK_SMALL_EN_US

    private val _state = MutableStateFlow<ModelState>(ModelState.Missing)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    /** Installed model directory, or null if not (fully) installed. Performs file I/O. */
    fun installedModelDir(): File? {
        val dir = File(modelsDir, spec.id)
        val marker = File(dir, INSTALLED_MARKER)
        return dir.takeIf { marker.isFile && spec.requiredFiles.all { File(dir, it).isFile } }
    }

    @Synchronized
    fun download() {
        if (job?.isActive == true) return
        job = scope.launch {
            install { checkCancelled ->
                openDownload().use { download ->
                    copyToWorkZip(download.stream, download.length ?: spec.downloadBytes, checkCancelled)
                }
            }
        }
    }

    /** Installs from a user-picked document (ACTION_OPEN_DOCUMENT). It must be the exact expected archive. */
    @Synchronized
    fun importFrom(uri: Uri) {
        if (job?.isActive == true) return
        job = scope.launch {
            install { checkCancelled ->
                val stream = appContext.contentResolver.openInputStream(uri)
                    ?: throw IOException("Could not open the selected file")
                stream.use { copyToWorkZip(it, spec.downloadBytes, checkCancelled) }
            }
        }
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
    }

    /** Deletes the installed model. The caller must ensure the service is not using it. */
    @Synchronized
    fun delete() {
        if (job?.isActive == true) return
        job = scope.launch {
            File(modelsDir, spec.id).deleteRecursively()
            workDir.deleteRecursively()
            refresh()
        }
    }

    private suspend fun install(fetch: suspend (checkCancelled: () -> Unit) -> String) {
        val context = currentCoroutineContext()
        val checkCancelled = { context.ensureActive() }
        try {
            workDir.deleteRecursively()
            if (!workDir.mkdirs()) throw IOException("Cannot create model directory")
            _state.value = ModelState.Downloading(0f)
            val actualHash = fetch(checkCancelled)

            _state.value = ModelState.Verifying
            if (!actualHash.equals(spec.sha256, ignoreCase = true)) {
                throw ModelArchiveException("File check failed: this is not the expected ${spec.displayName} archive")
            }
            val extractDir = File(workDir, "extract")
            File(workDir, ZIP_NAME).inputStream().use {
                ModelArchive.extract(it, extractDir, spec.maxExtractedBytes, checkCancelled = checkCancelled)
            }
            val root = ModelArchive.findModelRoot(extractDir, spec.requiredFiles)
                ?: throw ModelArchiveException("Archive does not contain the expected model files")
            File(root, INSTALLED_MARKER).writeText(spec.sha256)

            val target = File(modelsDir, spec.id)
            target.deleteRecursively()
            if (!root.renameTo(target)) throw IOException("Could not move model into place")
            workDir.deleteRecursively()
            refresh()
        } catch (e: CancellationException) {
            workDir.deleteRecursively()
            refresh()
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Model install failed: ${e.javaClass.simpleName}")
            workDir.deleteRecursively()
            _state.value = ModelState.Error(e.message ?: "Model install failed")
        } catch (e: SecurityException) {
            workDir.deleteRecursively()
            _state.value = ModelState.Error("Permission denied while reading the model")
        }
    }

    private fun copyToWorkZip(input: InputStream, expectedBytes: Long, checkCancelled: () -> Unit): String {
        val total = expectedBytes.takeIf { it > 0 }
        return File(workDir, ZIP_NAME).outputStream().use { out ->
            ModelArchive.copyAndHash(
                input,
                out,
                maxBytes = spec.downloadBytes * 2,
                checkCancelled = checkCancelled,
            ) { copied ->
                _state.value = ModelState.Downloading(total?.let { (copied.toFloat() / it).coerceIn(0f, 1f) })
            }
        }
    }

    private class Download(val stream: InputStream, val length: Long?, val connection: HttpURLConnection) :
        AutoCloseable {
        override fun close() {
            try {
                stream.close()
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun openDownload(): Download {
        val connection = (URL(spec.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IOException("Download failed (HTTP $code)")
        }
        val length = connection.contentLengthLong.takeIf { it > 0 }
        return Download(connection.inputStream, length, connection)
    }

    private fun refresh() {
        val dir = installedModelDir()
        _state.value = if (dir != null) {
            ModelState.Ready(dir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        } else {
            ModelState.Missing
        }
    }

    companion object {
        private const val TAG = "LocalModelManager"
        private const val MODELS_DIR = "models"
        private const val WORK_DIR = ".work"
        private const val ZIP_NAME = "download.zip"
        private const val INSTALLED_MARKER = ".installed"

        /**
         * Vosk small English model (Apache 2.0, Alpha Cephei). SHA-256 computed from
         * the official archive at the URL below; it is not published by upstream.
         */
        val VOSK_SMALL_EN_US = ModelSpec(
            id = "vosk-model-small-en-us-0.15",
            displayName = "Vosk small English (US) 0.15",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            sha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498",
            downloadBytes = 41_205_931L,
            license = "Apache 2.0, from alphacephei.com",
            source = "alphacephei.com/vosk/models",
            requiredFiles = listOf("am/final.mdl", "conf/model.conf", "conf/mfcc.conf"),
            maxExtractedBytes = 150L * 1024 * 1024,
        )

        @Volatile
        private var instance: LocalModelManager? = null

        fun get(context: Context): LocalModelManager =
            instance ?: synchronized(this) {
                instance ?: LocalModelManager(context).also { instance = it }
            }
    }
}
