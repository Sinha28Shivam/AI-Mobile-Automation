package com.jarvis.assistant.ai

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import com.jarvis.assistant.service.ServiceStateRepository
import com.jarvis.assistant.speech.ModelArchiveException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

sealed interface ModelState {
    data object Missing : ModelState

    /** [progress] in 0..1, or null if the total size is unknown. */
    data class Downloading(val progress: Float?) : ModelState

    /** Any post-download stage (checksum, extraction, validation, install); see [LocalModelManager.progress]. */
    data object Verifying : ModelState
    data class Ready(val sizeBytes: Long) : ModelState
    data class Error(val message: String) : ModelState

    val isBusy: Boolean get() = this is Downloading || this is Verifying
}

/** Fine-grained install progress for the UI. [fraction] is null when indeterminate. */
data class InstallProgress(val stage: InstallStage, val fraction: Float?)

/**
 * Installs local models into app-private storage (filesDir/models). Nothing is
 * bundled in the APK; the user downloads (one-time, HTTPS, allow-listed host) or imports
 * the archive via the Storage Access Framework. See [ModelInstaller] for the staged,
 * recoverable install pipeline.
 *
 * Process-wide singleton so the UI and the foreground service see one state.
 */
class LocalModelManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelsDir = File(appContext.filesDir, MODELS_DIR)
    private var job: Job? = null

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    val spec: ModelSpec = VOSK_SMALL_EN_US

    private val installer = ModelInstaller(
        modelsDir = modelsDir,
        spec = spec,
        freeBytes = ::availableBytes,
        loadCheck = ::checkVoskCanLoad,
    )

    private val _state = MutableStateFlow<ModelState>(ModelState.Missing)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private val _progress = MutableStateFlow<InstallProgress?>(null)
    val progress: StateFlow<InstallProgress?> = _progress.asStateFlow()

    /**
     * Last non-fatal problem while a working model stays installed (e.g. a failed update or a
     * refused delete). [state] stays [ModelState.Ready] then, so listening keeps working.
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    init {
        scope.launch {
            installer.recover()
            refresh()
        }
    }

    /** Installed model directory, or null if not (fully) installed. Performs file I/O. */
    fun installedModelDir(): File? = installer.installedModelDir()

    @Synchronized
    fun download() {
        if (job?.isActive == true) return
        job = scope.launch { install { openDownload() } }
    }

    /** Installs from a user-picked document (ACTION_OPEN_DOCUMENT). It must be the exact expected archive. */
    @Synchronized
    fun importFrom(uri: Uri) {
        if (job?.isActive == true) return
        job = scope.launch {
            install {
                appContext.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the selected file")
            }
        }
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        // Unblocks a network read so cancellation is prompt.
        activeConnection?.disconnect()
    }

    /** True when the listening service may be holding the model open. */
    fun isModelInUse(): Boolean = ServiceStateRepository.state.value.isActive

    /**
     * Deletes the installed model once the listening service has stopped. Waits up to
     * [STOP_WAIT_MS] for a just-requested stop to complete; otherwise refuses with
     * [STOP_LISTENING_FIRST] and keeps the model.
     */
    @Synchronized
    fun delete() {
        if (job?.isActive == true) return
        _notice.value = null
        job = scope.launch {
            val stopped = withTimeoutOrNull(STOP_WAIT_MS) {
                ServiceStateRepository.state.first { !it.isActive }
            } != null
            if (!stopped) {
                _notice.value = STOP_LISTENING_FIRST
                return@launch
            }
            // The pipeline frees recognizers and the native model asynchronously after Stop.
            delay(RELEASE_GRACE_MS)
            if (isModelInUse()) {
                _notice.value = STOP_LISTENING_FIRST
                return@launch
            }
            installer.deleteAll()
            refresh()
        }
    }

    private suspend fun install(open: () -> InputStream) {
        val context = currentCoroutineContext()
        _notice.value = null
        try {
            modelsDir.mkdirs()
            _state.value = ModelState.Downloading(0f)
            installer.install(
                open = open,
                checkCancelled = { context.ensureActive() },
                onProgress = { stage, fraction ->
                    _progress.value = InstallProgress(stage, fraction)
                    _state.value = when (stage) {
                        InstallStage.Preparing, InstallStage.Downloading -> ModelState.Downloading(fraction ?: 0f)
                        else -> ModelState.Verifying
                    }
                },
            )
            _progress.value = null
            refresh()
        } catch (e: CancellationException) {
            _progress.value = null
            refresh()
            throw e
        } catch (e: Exception) {
            _progress.value = null
            if (!context.isActive) {
                // cancel() disconnected the socket; the read failed because of the cancellation.
                refresh()
                return
            }
            Log.w(TAG, "Model install failed: ${e.javaClass.simpleName}")
            val message = ModelInstallErrors.userMessage(e)
            if (installedModelDir() != null) {
                refresh()
                _notice.value = "Update failed; your installed model was kept. $message"
            } else {
                _state.value = ModelState.Error(message)
            }
        } finally {
            activeConnection = null
        }
    }

    private fun openDownload(): InputStream {
        var url = spec.downloadUrl
        repeat(ModelDownloadPolicy.MAX_REDIRECTS + 1) {
            if (!ModelDownloadPolicy.isAllowed(url, spec.allowedHosts)) {
                throw ModelArchiveException("Blocked an unexpected download location")
            }
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                useCaches = false
            }
            activeConnection = connection
            val code = connection.responseCode
            when (code) {
                in 300..399 -> {
                    val next = ModelDownloadPolicy.resolveRedirect(url, connection.getHeaderField("Location"))
                    connection.disconnect()
                    url = next ?: throw IOException("Download failed (bad redirect)")
                }
                in 200..299 -> {
                    val length = connection.contentLengthLong
                    if (length > 0 && length != spec.downloadBytes) {
                        connection.disconnect()
                        throw ModelArchiveException("Server returned an unexpected file size")
                    }
                    return ConnectionStream(connection)
                }
                else -> {
                    connection.disconnect()
                    throw IOException("Download failed (HTTP $code)")
                }
            }
        }
        throw IOException("Download failed (too many redirects)")
    }

    private class ConnectionStream(private val connection: HttpURLConnection) :
        FilterInputStream(connection.inputStream) {
        override fun close() {
            try {
                super.close()
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun availableBytes(dir: File): Long = try {
        StatFs(dir.path).availableBytes
    } catch (_: IllegalArgumentException) {
        -1L
    }

    /** Proves the native runtime can open the staged model before it becomes active. */
    private fun checkVoskCanLoad(dir: File) {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            Model(dir.absolutePath).close()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Vosk native library unavailable")
            throw ModelArchiveException("Speech engine is not supported on this device")
        } catch (e: OutOfMemoryError) {
            throw ModelArchiveException("Not enough memory to load the speech model")
        } catch (e: IOException) {
            Log.w(TAG, "Vosk load check failed: ${e.javaClass.simpleName}")
            throw ModelArchiveException("The speech engine could not load this model")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Vosk load check failed: ${e.javaClass.simpleName}")
            throw ModelArchiveException("The speech engine could not load this model")
        }
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
        private const val STOP_WAIT_MS = 5_000L
        private const val RELEASE_GRACE_MS = 500L
        const val STOP_LISTENING_FIRST = "Stop listening first, then delete the model"

        val VOSK_SMALL_EN_US: ModelSpec get() = ModelCatalog.VOSK_SMALL_EN_US

        @Volatile
        private var instance: LocalModelManager? = null

        fun get(context: Context): LocalModelManager =
            instance ?: synchronized(this) {
                instance ?: LocalModelManager(context).also { instance = it }
            }
    }
}

/** Maps install failures to short, actionable messages without private details. */
object ModelInstallErrors {
    fun userMessage(e: Throwable): String = when (e) {
        is InsufficientStorageException -> "${e.message}. Free up space and try again."
        is ModelArchiveException -> "${e.message}. Try again; if it repeats, re-download the file."
        is UnknownHostException, is ConnectException -> "No internet connection. Connect (Wi-Fi recommended) and try again."
        is SocketTimeoutException -> "The download timed out. Try again on a stable connection."
        is SSLException -> "Secure connection failed. Check the phone's date/time and network, then try again."
        is SecurityException -> "Permission denied while reading the model file."
        is IOException -> (e.message?.takeIf { it.startsWith("Download failed") || it.startsWith("Could not") }
            ?: "Could not save the model") + ". Try again."
        else -> "Model install failed. Try again."
    }
}
