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

    @Test(expected = ModelArchiveException::class)
    fun extractRejectsEmptyArchive() {
        ModelArchive.extract(ByteArrayInputStream(ByteArray(0)), tmp.newFolder("out"), maxTotalBytes = 1_000)
    }
}
