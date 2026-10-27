package com.jarvis.assistant.ai

import com.jarvis.assistant.speech.ModelArchive
import com.jarvis.assistant.speech.ModelArchiveException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val required = listOf("am/final.mdl", "conf/model.conf", "graph/HCLr.fst")

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

    private fun validModelZip(marker: Byte = 1) = zipOf(
        "m/" to null,
        "m/am/final.mdl" to byteArrayOf(marker, 2, 3),
        "m/conf/model.conf" to "conf".toByteArray(),
        "m/graph/HCLr.fst" to byteArrayOf(9, 9),
        "m/README" to "x".toByteArray(),
    )

    private fun specFor(zip: ByteArray, maxExtracted: Long = 10_000, maxEntries: Int = 50) = ModelSpec(
        id = "test-model",
        displayName = "Test model",
        downloadUrl = "https://example.org/m.zip",
        sha256 = ModelArchive.digestFile(tmp.newFile().apply { writeBytes(zip) }).sha256,
        md5 = ModelArchive.digestFile(tmp.newFile().apply { writeBytes(zip) }).md5,
        downloadBytes = zip.size.toLong(),
        license = "test",
        source = "test",
        requiredFiles = required,
        maxExtractedBytes = maxExtracted,
        maxEntries = maxEntries,
        installedBytes = 1_000,
        allowedHosts = setOf("example.org"),
    )

    private val modelsDir by lazy { tmp.newFolder("models") }

    private fun installer(
        spec: ModelSpec,
        free: Long = Long.MAX_VALUE,
        loadCheck: (File) -> Unit = {},
    ) = ModelInstaller(modelsDir, spec, freeBytes = { free }, loadCheck = loadCheck)

    private fun assertNoStaging() {
        assertFalse(File(modelsDir, ModelInstaller.WORK_DIR).exists())
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("Expected ${T::class.java.simpleName} but got ${e.javaClass.simpleName}", e)
        }
        fail("Expected ${T::class.java.simpleName}")
        throw AssertionError()
    }

    /** Installs [good] as the existing working model and returns its installer. */
    private fun installGood(good: ByteArray = validModelZip()): ModelInstaller {
        val inst = installer(specFor(good))
        inst.install({ ByteArrayInputStream(good) })
        assertNotNull(inst.installedModelDir())
        return inst
    }

    @Test
    fun installsValidModelAndRunsLoadCheck() {
        val zip = validModelZip()
        var checked: File? = null
        val inst = installer(specFor(zip)) { checked = it }
        val stages = mutableListOf<InstallStage>()
        val dir = inst.install({ ByteArrayInputStream(zip) }, checkCancelled = { stages += it })
        assertEquals(File(modelsDir, "test-model"), dir)
        assertNotNull(inst.installedModelDir())
        assertEquals(3L, File(dir, "am/final.mdl").length())
        assertTrue(checked!!.path.contains(ModelInstaller.WORK_DIR))
        assertEquals(InstallStage.entries.toSet(), stages.toSet())
        assertNoStaging()
    }

    @Test
    fun deleteAndReinstall() {
        val zip = validModelZip()
        val inst = installer(specFor(zip))
        inst.install({ ByteArrayInputStream(zip) })
        inst.deleteAll()
        assertNull(inst.installedModelDir())
        inst.install({ ByteArrayInputStream(zip) })
        assertNotNull(inst.installedModelDir())
    }

    @Test
    fun rejectsWrongChecksumAndKeepsWorkingModel() {
        val good = validModelZip()
        installGood(good)
        val other = validModelZip(marker = 7)
        // Same length, different bytes: only the hash catches it.
        assertEquals(good.size, other.size)
        val inst = installer(specFor(good))
        val e = expect<ModelArchiveException> { inst.install({ ByteArrayInputStream(other) }) }
        assertTrue(e.message!!.contains("Checksum"))
        assertEquals(1.toByte(), File(inst.installedModelDir(), "am/final.mdl").readBytes()[0])
        assertNoStaging()
    }

    @Test
    fun rejectsMd5MismatchEvenIfSha256Matches() {
        val good = validModelZip()
        val spec = specFor(good).copy(md5 = "00000000000000000000000000000000")
        expect<ModelArchiveException> { installer(spec).install({ ByteArrayInputStream(good) }) }
        assertNull(installer(spec).installedModelDir())
    }

    @Test
    fun rejectsTruncatedDownload() {
        val good = validModelZip()
        val e = expect<ModelArchiveException> {
            installer(specFor(good)).install({ ByteArrayInputStream(good.copyOf(good.size - 10)) })
        }
        assertTrue(e.message!!.contains("incomplete"))
        assertNoStaging()
    }

    @Test
    fun rejectsOversizedDownload() {
        val good = validModelZip()
        expect<ModelArchiveException> {
            installer(specFor(good)).install({ ByteArrayInputStream(good + ByteArray(1)) })
        }
        assertNoStaging()
    }

    @Test
    fun rejectsEmptyFile() {
        val good = validModelZip()
        val e = expect<ModelArchiveException> { installer(specFor(good)).install({ ByteArrayInputStream(ByteArray(0)) }) }
        assertTrue(e.message!!.contains("empty"))
    }

    @Test
    fun rejectsCorruptArchiveWhoseHashMatches() {
        // Pinned hash of garbage bytes: verification passes, extraction must still fail safely.
        val garbage = ByteArray(200) { (it * 31).toByte() }
        expect<IOException> { installer(specFor(garbage)).install({ ByteArrayInputStream(garbage) }) }
        assertNull(installer(specFor(garbage)).installedModelDir())
        assertNoStaging()
    }

    @Test
    fun rejectsWrongModelMissingRequiredFiles() {
        val wrong = zipOf("m/am/final.mdl" to byteArrayOf(1), "m/conf/other.conf" to byteArrayOf(1))
        val e = expect<ModelArchiveException> { installer(specFor(wrong)).install({ ByteArrayInputStream(wrong) }) }
        assertTrue(e.message!!.contains("expected model files"))
        assertNoStaging()
    }

    @Test
    fun rejectsEmptyRequiredFile() {
        val zip = zipOf("m/am/final.mdl" to ByteArray(0), "m/conf/model.conf" to byteArrayOf(1), "m/graph/HCLr.fst" to byteArrayOf(1))
        expect<ModelArchiveException> { installer(specFor(zip)).install({ ByteArrayInputStream(zip) }) }
    }

    @Test
    fun rejectsArchiveExceedingUncompressedLimit() {
        val bomb = zipOf(
            "m/am/final.mdl" to ByteArray(50_000),
            "m/conf/model.conf" to byteArrayOf(1),
            "m/graph/HCLr.fst" to byteArrayOf(1),
        )
        expect<ModelArchiveException> { installer(specFor(bomb, maxExtracted = 10_000)).install({ ByteArrayInputStream(bomb) }) }
        assertNoStaging()
    }

    @Test
    fun rejectsArchiveExceedingEntryLimit() {
        val zip = validModelZip()
        expect<ModelArchiveException> { installer(specFor(zip, maxEntries = 3)).install({ ByteArrayInputStream(zip) }) }
    }

    @Test
    fun rejectsZipSlipArchiveWithMatchingHash() {
        val evil = zipOf("m/am/final.mdl" to byteArrayOf(1), "../../escape.txt" to "x".toByteArray())
        expect<ModelArchiveException> { installer(specFor(evil)).install({ ByteArrayInputStream(evil) }) }
        assertFalse(File(tmp.root, "escape.txt").exists())
        assertFalse(File(modelsDir.parentFile, "escape.txt").exists())
    }

    @Test
    fun failedLoadCheckKeepsOldModel() {
        val good = validModelZip()
        installGood(good)
        val inst = installer(specFor(good)) { throw ModelArchiveException("The speech engine could not load this model") }
        expect<ModelArchiveException> { inst.install({ ByteArrayInputStream(good) }) }
        assertNotNull(inst.installedModelDir())
        assertNoStaging()
    }

    @Test
    fun insufficientSpaceIsReportedBeforeDownloading() {
        val good = validModelZip()
        var opened = false
        val e = expect<InsufficientStorageException> {
            installer(specFor(good), free = 100).install({ opened = true; ByteArrayInputStream(good) })
        }
        assertFalse(opened)
        assertEquals(100L, e.availableBytes)
    }

    @Test
    fun unknownFreeSpaceDoesNotBlock() {
        val good = validModelZip()
        installer(specFor(good), free = -1).install({ ByteArrayInputStream(good) })
    }

    @Test
    fun cancellationAtEveryStageKeepsOldModelAndCleansStaging() {
        val good = validModelZip()
        for (stage in InstallStage.entries) {
            installGood(good)
            val inst = installer(specFor(good))
            expect<CancellationException> {
                inst.install({ ByteArrayInputStream(good) }, checkCancelled = { if (it == stage) throw CancellationException() })
            }
            val dir = inst.installedModelDir()
            assertNotNull("model lost when cancelled at $stage", dir)
            assertEquals(3L, File(dir, "am/final.mdl").length())
            assertNoStaging()
            assertFalse(File(modelsDir, ModelInstaller.BACKUP_PREFIX + "test-model").exists())
        }
    }

    @Test
    fun cancellationMidDownloadWithNoPriorModelLeavesNothing() {
        val good = validModelZip()
        val inst = installer(specFor(good))
        var calls = 0
        expect<CancellationException> {
            inst.install({ ByteArrayInputStream(good) }, checkCancelled = {
                if (it == InstallStage.Downloading && ++calls == 2) throw CancellationException()
            })
        }
        assertNull(inst.installedModelDir())
        assertEquals(emptyList<String>(), modelsDir.list()!!.toList())
    }

    @Test
    fun updateReplacesExistingModel() {
        val v1 = validModelZip(marker = 1)
        installGood(v1)
        val v2 = validModelZip(marker = 5)
        val inst = installer(specFor(v2))
        // Different spec hash: the v1 install is not considered complete for this spec.
        inst.install({ ByteArrayInputStream(v2) })
        assertEquals(5.toByte(), File(inst.installedModelDir(), "am/final.mdl").readBytes()[0])
        assertFalse(File(modelsDir, ModelInstaller.BACKUP_PREFIX + "test-model").exists())
    }

    @Test
    fun recoverRestoresBackupIfInterruptedDuringSwap() {
        val good = validModelZip()
        val inst = installGood(good)
        // Simulate process death after the active model was moved aside.
        val backup = File(modelsDir, ModelInstaller.BACKUP_PREFIX + "test-model")
        assertTrue(inst.activeDir.renameTo(backup))
        File(modelsDir, ModelInstaller.WORK_DIR).mkdirs()
        assertNull(inst.installedModelDir())
        inst.recover()
        assertNotNull(inst.installedModelDir())
        assertFalse(backup.exists())
        assertNoStaging()
    }

    @Test
    fun markerWithWrongHashIsNotInstalled() {
        val good = validModelZip()
        val inst = installGood(good)
        File(inst.activeDir, ModelInstaller.INSTALLED_MARKER).writeText("deadbeef")
        assertNull(inst.installedModelDir())
    }

    @Test
    fun realSpecMatchesUpstreamArchive() {
        val spec = ModelCatalog.VOSK_SMALL_EN_US
        assertEquals(41_205_931L, spec.downloadBytes)
        assertEquals("30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498", spec.sha256)
        assertEquals("09ab50ccd62b674cbaa231b825f9c1cb", spec.md5)
        assertTrue(spec.requiredFiles.containsAll(listOf("am/final.mdl", "conf/mfcc.conf", "graph/HCLr.fst", "ivector/final.ie")))
        // Actual archive: 20 entries, 70,898,967 bytes.
        assertTrue(spec.maxExtractedBytes in 70_898_967L..(100L * 1024 * 1024))
        assertTrue(spec.maxEntries >= 20)
        assertTrue(ModelDownloadPolicy.isAllowed(spec.downloadUrl, spec.allowedHosts))
    }
}
