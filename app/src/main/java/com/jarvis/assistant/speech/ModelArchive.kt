package com.jarvis.assistant.speech

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Thrown when a model archive is unsafe, corrupt or too large. */
class ModelArchiveException(message: String) : IOException(message)

/** Pure-JVM helpers for verifying and safely unpacking a model zip. No Android dependencies. */
object ModelArchive {

    /**
     * Resolves a zip entry name under [destDir], rejecting absolute paths and any
     * entry that would escape [destDir] ("zip slip").
     */
    fun resolveEntry(destDir: File, entryName: String): File {
        val normalized = entryName.replace('\\', '/')
        if (normalized.isEmpty() || normalized.startsWith("/") || DRIVE_PREFIX.containsMatchIn(normalized)) {
            throw ModelArchiveException("Unsafe zip entry")
        }
        val base = destDir.canonicalFile
        val target = File(base, normalized).canonicalFile
        if (target != base && !target.path.startsWith(base.path + File.separator)) {
            throw ModelArchiveException("Unsafe zip entry")
        }
        return target
    }

    /**
     * Copies [input] to [output] while computing its SHA-256 (lowercase hex).
     * [onProgress] receives total bytes copied; [checkCancelled] may throw to abort.
     */
    fun copyAndHash(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = Long.MAX_VALUE,
        checkCancelled: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            checkCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw ModelArchiveException("File is larger than expected")
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            onProgress(total)
        }
        return digest.digest().toHex()
    }

    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().toHex()
    }

    /**
     * Extracts [zip] into [destDir] with zip-slip protection and limits on entry
     * count and total uncompressed size (guards against zip bombs).
     */
    fun extract(
        zip: InputStream,
        destDir: File,
        maxTotalBytes: Long,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        checkCancelled: () -> Unit = {},
    ) {
        if (!destDir.isDirectory && !destDir.mkdirs()) throw IOException("Cannot create directory")
        var entries = 0
        var total = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        ZipInputStream(zip.buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                checkCancelled()
                if (++entries > maxEntries) throw ModelArchiveException("Archive has too many entries")
                val target = resolveEntry(destDir, entry.name)
                if (entry.isDirectory) {
                    if (!target.isDirectory && !target.mkdirs()) throw IOException("Cannot create directory")
                    continue
                }
                target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot create directory") }
                target.outputStream().use { out ->
                    while (true) {
                        val read = zis.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxTotalBytes) throw ModelArchiveException("Archive is larger than expected")
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
        if (entries == 0) throw ModelArchiveException("Archive is empty")
    }

    /**
     * Model zips usually contain one top-level folder. Returns that folder, or
     * [extractedDir] itself if files sit at the root.
     */
    fun findModelRoot(extractedDir: File, requiredRelativePaths: List<String>): File? {
        fun hasAll(dir: File) = requiredRelativePaths.all { File(dir, it).isFile }
        if (hasAll(extractedDir)) return extractedDir
        val children = extractedDir.listFiles().orEmpty()
        val single = children.singleOrNull { it.isDirectory } ?: return null
        if (children.size != 1) return null
        return single.takeIf { hasAll(it) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
    private const val BUFFER_SIZE = 64 * 1024
    const val DEFAULT_MAX_ENTRIES = 1_000
}
