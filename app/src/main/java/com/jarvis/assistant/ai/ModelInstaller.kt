package com.jarvis.assistant.ai

import com.jarvis.assistant.speech.ModelArchive
import com.jarvis.assistant.speech.ModelArchiveException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException

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
    /** MD5 published by upstream in model-list.json; checked in addition to [sha256]. */
    val md5: String? = null,
    /** Hosts the download (and any redirect) may use. HTTPS only. */
    val allowedHosts: Set<String> = emptySet(),
    val maxEntries: Int = ModelArchive.DEFAULT_MAX_ENTRIES,
    /** Approximate unpacked size, used for the free-space check. */
    val installedBytes: Long = maxExtractedBytes,
)

/** Install pipeline stages, in order. Cancellation is checked in every stage. */
enum class InstallStage(val label: String) {
    Preparing("Checking free space"),
    Downloading("Downloading"),
    Verifying("Verifying checksum"),
    Extracting("Extracting"),
    Validating("Validating model files"),
    Installing("Installing"),
}

/** Thrown when there is not enough free storage. */
class InsufficientStorageException(val requiredBytes: Long, val availableBytes: Long) :
    ModelArchiveException("Not enough free storage: need about ${requiredBytes / MB} MB, ${availableBytes / MB} MB free")

private const val MB = 1024L * 1024L

object ModelCatalog {
    /**
     * Vosk small English model (Apache 2.0, Alpha Cephei).
     *
     * Trust basis: upstream does not publish a SHA-256. The SHA-256 below was computed
     * from the archive served at [ModelSpec.downloadUrl] (independently re-downloaded and
     * re-hashed on 2026-10-27). The MD5 and byte size come from upstream's own
     * https://alphacephei.com/vosk/models/model-list.json and matched the same download.
     * Both are checked, so a mismatch with either the pinned hash or upstream's manifest
     * is rejected.
     */
    val VOSK_SMALL_EN_US = ModelSpec(
        id = "vosk-model-small-en-us-0.15",
        displayName = "Vosk small English (US) 0.15",
        downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        sha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498",
        md5 = "09ab50ccd62b674cbaa231b825f9c1cb",
        downloadBytes = 41_205_931L,
        license = "Apache 2.0, from alphacephei.com",
        source = "alphacephei.com/vosk/models",
        requiredFiles = listOf(
            "am/final.mdl",
            "conf/model.conf",
            "conf/mfcc.conf",
            "graph/HCLr.fst",
            "graph/Gr.fst",
            "graph/disambig_tid.int",
            "graph/phones/word_boundary.int",
            "ivector/final.dubm",
            "ivector/final.ie",
            "ivector/final.mat",
            "ivector/global_cmvn.stats",
            "ivector/online_cmvn.conf",
            "ivector/splice.conf",
        ),
        // Actual archive: 20 entries, 70,898,967 bytes unpacked.
        maxExtractedBytes = 100L * MB,
        maxEntries = 64,
        installedBytes = 71L * MB,
        allowedHosts = setOf("alphacephei.com", "www.alphacephei.com"),
    )
}

/** Pure URL policy for the model download and its redirects. */
object ModelDownloadPolicy {
    const val MAX_REDIRECTS = 5

    /** True only for absolute https URLs whose host is exactly in [allowedHosts], default port, no user-info. */
    fun isAllowed(url: String, allowedHosts: Set<String>): Boolean {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return false
        }
        if (!uri.isAbsolute || !"https".equals(uri.scheme, ignoreCase = true)) return false
        if (uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase() ?: return false
        return host in allowedHosts.map { it.lowercase() }
    }

    /** Resolves a Location header against [current]; null if unparseable. */
    fun resolveRedirect(current: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        return try {
            URI(current).resolve(location.trim()).toString()
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: URISyntaxException) {
            null
        }
    }
}

/**
 * Installs a model archive into [modelsDir] without ever damaging a working model:
 * the archive is copied and hashed in a private staging directory, extracted into
 * staging, validated (required files + [loadCheck]), then swapped into place with
 * renames. Failure or cancellation at any stage only deletes staging.
 *
 * Pure JVM (no Android types) so it is fully unit-tested.
 */
class ModelInstaller(
    private val modelsDir: File,
    private val spec: ModelSpec,
    private val freeBytes: (File) -> Long,
    private val loadCheck: (File) -> Unit,
) {
    val activeDir: File get() = File(modelsDir, spec.id)
    private val workDir: File get() = File(modelsDir, WORK_DIR)
    private val backupDir: File get() = File(modelsDir, BACKUP_PREFIX + spec.id)

    /** Active model dir if fully installed (marker matches the spec hash and required files are present). */
    fun installedModelDir(): File? {
        val dir = activeDir
        return dir.takeIf { isCompleteInstall(it) }
    }

    /**
     * Removes leftovers of an interrupted install. If the process died between the two
     * swap renames, the backup is restored so the last known-good model comes back.
     */
    fun recover() {
        if (!isCompleteInstall(activeDir) && isCompleteInstall(backupDir)) {
            activeDir.deleteRecursively()
            backupDir.renameTo(activeDir)
        }
        if (isCompleteInstall(activeDir)) backupDir.deleteRecursively()
        workDir.deleteRecursively()
    }

    /**
     * Runs the full pipeline. [open] supplies the archive stream (download or import).
     * [checkCancelled] is called with the current stage and must throw
     * (e.g. a CancellationException) to abort. Returns the installed directory.
     */
    fun install(
        open: () -> InputStream,
        checkCancelled: (InstallStage) -> Unit = {},
        onProgress: (InstallStage, Float?) -> Unit = { _, _ -> },
    ): File {
        try {
            checkCancelled(InstallStage.Preparing)
            onProgress(InstallStage.Preparing, null)
            workDir.deleteRecursively()
            if (!workDir.mkdirs()) throw IOException("Cannot create model directory")
            requireSpace(spec.downloadBytes + spec.installedBytes + SPACE_MARGIN_BYTES)

            val zip = File(workDir, ZIP_NAME)
            onProgress(InstallStage.Downloading, 0f)
            val digests = open().use { input ->
                zip.outputStream().use { out ->
                    ModelArchive.copyAndDigest(
                        input,
                        out,
                        maxBytes = spec.downloadBytes,
                        checkCancelled = { checkCancelled(InstallStage.Downloading) },
                    ) { copied -> onProgress(InstallStage.Downloading, (copied.toFloat() / spec.downloadBytes).coerceIn(0f, 1f)) }
                }
            }

            checkCancelled(InstallStage.Verifying)
            onProgress(InstallStage.Verifying, null)
            verify(digests.sizeBytes, digests.sha256, digests.md5)
            // Re-hash what is on disk so the extracted bytes are exactly the verified bytes.
            val onDisk = ModelArchive.digestFile(zip) { checkCancelled(InstallStage.Verifying) }
            verify(onDisk.sizeBytes, onDisk.sha256, onDisk.md5)

            checkCancelled(InstallStage.Extracting)
            requireSpace(spec.installedBytes + SPACE_MARGIN_BYTES)
            onProgress(InstallStage.Extracting, 0f)
            val extractDir = File(workDir, EXTRACT_DIR)
            zip.inputStream().use {
                ModelArchive.extract(
                    it,
                    extractDir,
                    maxTotalBytes = spec.maxExtractedBytes,
                    maxEntries = spec.maxEntries,
                    checkCancelled = { checkCancelled(InstallStage.Extracting) },
                ) { written -> onProgress(InstallStage.Extracting, (written.toFloat() / spec.installedBytes).coerceIn(0f, 1f)) }
            }
            zip.delete()

            checkCancelled(InstallStage.Validating)
            onProgress(InstallStage.Validating, null)
            val root = ModelArchive.findModelRoot(extractDir, spec.requiredFiles)
                ?: throw ModelArchiveException("Archive does not contain the expected model files")
            val unreadable = spec.requiredFiles.any { File(root, it).let { f -> !f.canRead() || f.length() == 0L } }
            if (unreadable) throw ModelArchiveException("Model files are empty or unreadable")
            checkCancelled(InstallStage.Validating)
            loadCheck(root)
            File(root, INSTALLED_MARKER).writeText(spec.sha256)

            checkCancelled(InstallStage.Installing)
            onProgress(InstallStage.Installing, null)
            swapIntoPlace(root)
            workDir.deleteRecursively()
            return activeDir
        } catch (e: Throwable) {
            workDir.deleteRecursively()
            throw e
        }
    }

    /** Deletes the active model, backups and staging. */
    fun deleteAll() {
        activeDir.deleteRecursively()
        backupDir.deleteRecursively()
        workDir.deleteRecursively()
    }

    private fun verify(size: Long, sha256: String, md5: String) {
        if (size == 0L) throw ModelArchiveException("The file is empty")
        if (size != spec.downloadBytes) {
            throw ModelArchiveException("Download incomplete or wrong file (size ${size} of ${spec.downloadBytes} bytes)")
        }
        val shaOk = sha256.equals(spec.sha256, ignoreCase = true)
        val md5Ok = spec.md5 == null || md5.equals(spec.md5, ignoreCase = true)
        if (!shaOk || !md5Ok) {
            throw ModelArchiveException("Checksum mismatch: this is not the expected ${spec.displayName} archive")
        }
    }

    private fun requireSpace(required: Long) {
        val available = freeBytes(modelsDir)
        if (available in 0 until required) throw InsufficientStorageException(required, available)
    }

    /** Keeps the current model as a backup until the new one is in place, then removes it. */
    private fun swapIntoPlace(root: File) {
        backupDir.deleteRecursively()
        val hadActive = activeDir.exists()
        if (hadActive && !activeDir.renameTo(backupDir)) throw IOException("Could not move the current model aside")
        if (!root.renameTo(activeDir)) {
            if (hadActive) backupDir.renameTo(activeDir)
            throw IOException("Could not move model into place")
        }
        backupDir.deleteRecursively()
    }

    private fun isCompleteInstall(dir: File): Boolean {
        val marker = File(dir, INSTALLED_MARKER)
        if (!marker.isFile) return false
        val markerOk = try {
            marker.readText().trim().equals(spec.sha256, ignoreCase = true)
        } catch (_: IOException) {
            false
        }
        return markerOk && spec.requiredFiles.all { File(dir, it).isFile }
    }

    companion object {
        const val WORK_DIR = ".work"
        const val BACKUP_PREFIX = ".old-"
        const val ZIP_NAME = "download.zip"
        const val EXTRACT_DIR = "extract"
        const val INSTALLED_MARKER = ".installed"
        const val SPACE_MARGIN_BYTES = 10L * MB
    }
}
