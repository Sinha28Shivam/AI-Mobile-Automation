package com.jarvis.assistant.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zipOf(vararg entries: Pair<String, ByteArray?>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                data?.let { zip.write(it) }
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun assertUnsafe(name: String) {
        try {
            ModelArchive.resolveEntry(tmp.root, name)
            fail("Expected '$name' to be rejected")
        } catch (e: ModelArchiveException) {
            // expected
        }
    }

    @Test
    fun resolvesNormalEntriesInsideDestination() {
        val file = ModelArchive.resolveEntry(tmp.root, "model/am/final.mdl")
        assertEquals(File(tmp.root.canonicalFile, "model/am/final.mdl").canonicalFile, file)
        assertEquals(tmp.root.canonicalFile, ModelArchive.resolveEntry(tmp.root, "model/.."))
    }

    @Test
    fun rejectsZipSlipEntries() {
        assertUnsafe("../evil.txt")
        assertUnsafe("model/../../evil.txt")
        assertUnsafe("..\\evil.txt")
        assertUnsafe("/etc/passwd")
        assertUnsafe("C:/Windows/evil.dll")
        assertUnsafe("")
    }

    @Test
    fun rejectsSiblingDirectoryWithSharedPrefix() {
        val dest = File(tmp.root, "models")
        dest.mkdirs()
        try {
            ModelArchive.resolveEntry(dest, "../models-evil/x")
            fail("sibling prefix must be rejected")
        } catch (e: ModelArchiveException) {
            // expected
        }
    }

    @Test
    fun copyAndHashMatchesKnownSha256() {
        val out = ByteArrayOutputStream()
        val hash = ModelArchive.copyAndHash(ByteArrayInputStream("abc".toByteArray()), out)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
        assertEquals("abc", out.toString())
    }

    @Test(expected = ModelArchiveException::class)
    fun copyAndHashEnforcesMaxBytes() {
        ModelArchive.copyAndHash(ByteArrayInputStream(ByteArray(100)), ByteArrayOutputStream(), maxBytes = 10)
    }

    @Test
    fun sha256OfFileMatchesStreamHash() {
        val file = tmp.newFile("x.bin").apply { writeBytes("abc".toByteArray()) }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ModelArchive.sha256(file))
    }

    @Test
    fun extractsAndFindsSingleTopLevelModelFolder() {
        val zip = zipOf(
            "vosk-model/" to null,
            "vosk-model/am/final.mdl" to byteArrayOf(1, 2, 3),
            "vosk-model/conf/model.conf" to "x".toByteArray(),
        )
        val dest = tmp.newFolder("out")
        ModelArchive.extract(ByteArrayInputStream(zip), dest, maxTotalBytes = 1_000)
        val root = ModelArchive.findModelRoot(dest, listOf("am/final.mdl", "conf/model.conf"))
        assertEquals(File(dest, "vosk-model"), root)
        assertEquals(3L, File(root, "am/final.mdl").length())
    }

    @Test
    fun findModelRootReturnsNullWhenFilesMissing() {
        val zip = zipOf("vosk-model/am/final.mdl" to byteArrayOf(1))
        val dest = tmp.newFolder("out")
        ModelArchive.extract(ByteArrayInputStream(zip), dest, maxTotalBytes = 1_000)
        assertNull(ModelArchive.findModelRoot(dest, listOf("am/final.mdl", "conf/model.conf")))
    }

    @Test
    fun extractRejectsZipSlipAndWritesNothingOutside() {
        val zip = zipOf("../escaped.txt" to "pwned".toByteArray())
        val dest = tmp.newFolder("out")
        try {
            ModelArchive.extract(ByteArrayInputStream(zip), dest, maxTotalBytes = 1_000)
            fail("zip slip must be rejected")
        } catch (e: ModelArchiveException) {
            // expected
        }
        assertTrue(!File(tmp.root, "escaped.txt").exists())
    }

    @Test(expected = ModelArchiveException::class)
    fun extractEnforcesUncompressedSizeLimit() {
        val zip = zipOf("big.bin" to ByteArray(10_000))
        ModelArchive.extract(ByteArrayInputStream(zip), tmp.newFolder("out"), maxTotalBytes = 1_000)
    }

    @Test(expected = ModelArchiveException::class)
    fun extractEnforcesEntryLimit() {
        val zip = zipOf("a" to byteArrayOf(1), "b" to byteArrayOf(1), "c" to byteArrayOf(1))
        ModelArchive.extract(ByteArrayInputStream(zip), tmp.newFolder("out"), maxTotalBytes = 1_000, maxEntries = 2)
    }

    @Test
    fun rejectsWindowsAndEncodedTraversalForms() {
        assertUnsafe("C:\\Windows\\evil.dll")
        assertUnsafe("c:evil.dll")
        assertUnsafe("\\\\server\\share\\evil")
        assertUnsafe("\\evil.txt")
        assertUnsafe("model\\..\\..\\evil.txt")
        assertUnsafe("model/file:stream")
        assertUnsafe("model/\u0000evil")
        assertUnsafe("a/".repeat(300))
    }

    @Test
    fun rejectsSiblingPrefixWithBackslashes() {
        val dest = File(tmp.root, "vosk").apply { mkdirs() }
        File(tmp.root, "vosk-evil").mkdirs()
        try {
            ModelArchive.resolveEntry(dest, "..\\vosk-evil\\x")
            fail("sibling prefix must be rejected")
        } catch (e: ModelArchiveException) {
            // expected
        }
    }

    @Test
    fun symlinkLikeEntryIsWrittenAsPlainFileInsideDestination() {
        // Unix zip symlinks store the link target as the entry's data; ZipInputStream has no
        // symlink support, so it must land as a regular file and never redirect later writes.
        val zip = zipOf("m/link" to "../../outside".toByteArray(), "m/after.txt" to "a".toByteArray())
        val dest = tmp.newFolder("out")
        ModelArchive.extract(ByteArrayInputStream(zip), dest, maxTotalBytes = 1_000)
        val link = File(dest, "m/link")
        assertTrue(link.isFile)
        assertEquals("../../outside", link.readText())
        assertTrue(File(dest, "m/after.txt").isFile)
        assertTrue(!File(tmp.root, "outside").exists())
    }

    @Test(expected = ModelArchiveException::class)
    fun extractRejectsDuplicateEntries() {
        val bytes = ByteArrayOutputStream()
        // ZipOutputStream refuses duplicate names, so write two equivalent paths.
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("m/a.txt")); zip.write(1); zip.closeEntry()
            zip.putNextEntry(ZipEntry("m/./a.txt")); zip.write(2); zip.closeEntry()
        }
        ModelArchive.extract(ByteArrayInputStream(bytes.toByteArray()), tmp.newFolder("out"), maxTotalBytes = 1_000)
    }

    @Test
    fun extractHonoursCancellation() {
        val zip = zipOf("a" to byteArrayOf(1), "b" to byteArrayOf(1))
        var calls = 0
        try {
            ModelArchive.extract(ByteArrayInputStream(zip), tmp.newFolder("out"), maxTotalBytes = 1_000, checkCancelled = {
                if (++calls == 2) throw java.util.concurrent.CancellationException()
            })
            fail("expected cancellation")
        } catch (e: java.util.concurrent.CancellationException) {
            // expected
        }
    }

    @Test
    fun copyAndDigestReportsMd5AndSize() {
        val d = ModelArchive.copyAndDigest(ByteArrayInputStream("abc".toByteArray()), ByteArrayOutputStream())
        assertEquals("900150983cd24fb0d6963f7d28e17f72", d.md5)
        assertEquals(3L, d.sizeBytes)
        assertEquals(d, ModelArchive.digestFile(tmp.newFile("y.bin").apply { writeBytes("abc".toByteArray()) }))
    }

    @Test(expected = ModelArchiveException::class)
    fun extractRejectsNonZipData() {
        ModelArchive.extract(ByteArrayInputStream("this is not a zip".toByteArray()), tmp.newFolder("out"), maxTotalBytes = 1_000)
    }

    @Test(expected = ModelArchiveException::class)
    fun extractRejectsEmptyArchive() {
        ModelArchive.extract(ByteArrayInputStream(ByteArray(0)), tmp.newFolder("out"), maxTotalBytes = 1_000)
    }
}
