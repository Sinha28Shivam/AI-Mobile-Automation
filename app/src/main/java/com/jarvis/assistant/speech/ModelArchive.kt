package com.jarvis.assistant.speech

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Thrown when a model archive is unsafe, corrupt or too large. The message is user-facing. */
open class ModelArchiveException(message: String) : IOException(message)

/** Digests of a copied/read file. Hex strings are lowercase. */
data class ArchiveDigests(val sha256: String, val md5: String, val sizeBytes: Long)

/** Pure-JVM helpers for verifying and safely unpacking a model zip. No Android dependencies. */
object ModelArchive {

    /**
     * Resolves a zip entry name under [destDir], rejecting absolute paths, drive/UNC
     * prefixes, NUL/colon characters and any entry that would escape [destDir] ("zip slip").
     */
    fun resolveEntry(destDir: File, entryName: String): File {
        val normalized = entryName.replace('\\', '/')
        if (normalized.isEmpty() ||
            normalized.length > MAX_ENTRY_NAME_LENGTH ||
            normalized.startsWith("/") ||
            DRIVE_PREFIX.containsMatchIn(normalized) ||
            normalized.any { it == '\u0000' || it == ':' }
        ) {
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
    ): String = copyAndDigest(input, output, maxBytes, checkCancelled, onProgress).sha256

    /** Like [copyAndHash] but returns SHA-256, MD5 and the byte count. */
    fun copyAndDigest(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = Long.MAX_VALUE,
        checkCancelled: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): ArchiveDigests {
        val sha = MessageDigest.getInstance("SHA-256")
        val md5 = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            checkCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw ModelArchiveException("File is larger than expected")
            sha.update(buffer, 0, read)
            md5.update(buffer, 0, read)
            output.write(buffer, 0, read)
            onProgress(total)
        }
        return ArchiveDigests(sha.digest().toHex(), md5.digest().toHex(), total)
    }

    fun sha256(file: File): String = digestFile(file).sha256

    /** Reads [file] from disk and returns its digests; [checkCancelled] runs per buffer. */
    fun digestFile(file: File, checkCancelled: () -> Unit = {}): ArchiveDigests =
        file.inputStream().use { input ->
            copyAndDigest(input, NullOutputStream, checkCancelled = checkCancelled)
        }

    /**
     * Extracts [zip] into [destDir] with zip-slip protection and limits on entry
     * count and total uncompressed size (guards against zip bombs). Duplicate entry
     * names are rejected. Entries are always written as regular files/directories:
     * symlink metadata in the archive is ignored, so a "link" entry can never
     * redirect later writes outside [destDir].
     */
    fun extract(
        zip: InputStream,
        destDir: File,
        maxTotalBytes: Long,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        checkCancelled: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ) {
        if (!destDir.isDirectory && !destDir.mkdirs()) throw IOException("Cannot create directory")
        var entries = 0
        var total = 0L
        val seen = HashSet<String>()
        val buffer = ByteArray(BUFFER_SIZE)
        ZipInputStream(zip.buffered()).use { zis ->
            while (true) {
                checkCancelled()
                val entry = zis.nextEntry ?: break
                if (++entries > maxEntries) throw ModelArchiveException("Archive has too many entries")
                val target = resolveEntry(destDir, entry.name)
                if (!seen.add(target.path)) throw ModelArchiveException("Archive contains duplicate entries")
                if (entry.isDirectory) {
                    if (!target.isDirectory && !target.mkdirs()) throw IOException("Cannot create directory")
                    continue
                }
                target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot create directory") }
                if (target.exists()) throw ModelArchiveException("Archive contains duplicate entries")
                target.outputStream().use { out ->
                    while (true) {
                        checkCancelled()
                        val read = zis.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxTotalBytes) throw ModelArchiveException("Archive is larger than expected")
                        out.write(buffer, 0, read)
                        onProgress(total)
                    }
                }
            }
        }
        if (entries == 0) throw ModelArchiveException("Archive is empty or not a zip file")
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

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_ENTRY_NAME_LENGTH = 512
    const val DEFAULT_MAX_ENTRIES = 1_000
}
