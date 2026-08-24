package com.hermes.node.engine

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

class BootstrapExtractorTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tempDir: File
    private lateinit var extractor: BootstrapExtractor

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("bootstrap_test_").toFile()
        extractor = BootstrapExtractor(
            filesDir = tempDir,
            assetManager = null,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun createTestTarXz(
        entries: Map<String, ByteArray>,
        modes: Map<String, Int> = emptyMap()
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        XZCompressorOutputStream(baos).use { xzOut ->
            TarArchiveOutputStream(xzOut).use { tarOut ->
                for ((name, content) in entries) {
                    val entry = TarArchiveEntry(name)
                    entry.size = content.size.toLong()
                    entry.mode = modes[name] ?: 0b111_101_101 // 0755 default
                    tarOut.putArchiveEntry(entry)
                    tarOut.write(content)
                    tarOut.closeArchiveEntry()
                }
            }
        }
        return baos.toByteArray()
    }

    private fun getStandardArchiveEntries(): Map<String, ByteArray> {
        val scriptContent = "#!/bin/sh\necho ok\n".toByteArray()
        return mapOf(
            "usr/bin/python3" to scriptContent,
            "usr/bin/proot" to scriptContent,
            "usr/bin/hermes" to scriptContent,
            "usr/lib/libpython3.11.so" to "STUB_SO".toByteArray(),
            "usr/etc/hermes.conf" to "env=test".toByteArray()
        )
    }

    @Test
    fun isBootstrapInstalled_returnsFalse_whenDirEmpty() {
        assertFalse(extractor.isBootstrapInstalled())
    }

    @Test
    fun isBootstrapInstalled_returnsFalse_whenMarkerMissing() {
        val usrDir = File(tempDir, "usr/bin").apply { mkdirs() }
        File(usrDir, "python3").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "proot").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "hermes").apply { writeText("dummy"); setExecutable(true) }

        // Marker file does not exist
        assertFalse(extractor.isBootstrapInstalled())
    }

    @Test
    fun isBootstrapInstalled_returnsFalse_whenVersionMismatch() {
        val usrDir = File(tempDir, "usr/bin").apply { mkdirs() }
        File(usrDir, "python3").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "proot").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "hermes").apply { writeText("dummy"); setExecutable(true) }

        val marker = File(tempDir, BootstrapExtractor.MARKER_FILE_NAME)
        marker.writeText("version=99\n") // Incompatible version

        assertFalse(extractor.isBootstrapInstalled())
    }

    @Test
    fun isBootstrapInstalled_returnsFalse_whenCriticalBinaryMissing() {
        val usrDir = File(tempDir, "usr/bin").apply { mkdirs() }
        File(usrDir, "python3").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "proot").apply { writeText("dummy"); setExecutable(true) }
        // missing hermes binary

        val marker = File(tempDir, BootstrapExtractor.MARKER_FILE_NAME)
        marker.writeText("version=1")

        assertFalse(extractor.isBootstrapInstalled())
    }

    @Test
    fun isBootstrapInstalled_returnsTrue_whenMarkerAndAllBinariesPresent() {
        val usrDir = File(tempDir, "usr/bin").apply { mkdirs() }
        File(usrDir, "python3").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "proot").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "hermes").apply { writeText("dummy"); setExecutable(true) }

        val marker = File(tempDir, BootstrapExtractor.MARKER_FILE_NAME)
        marker.writeText("version=1\ntimestamp=123456\n")

        assertTrue(extractor.isBootstrapInstalled())
    }

    @Test
    fun extractFromStream_extractsValidTarXz_createsFilesAndSetsExecutablePermissions() = runTest(testDispatcher) {
        val tarBytes = createTestTarXz(getStandardArchiveEntries())
        val inStream = ByteArrayInputStream(tarBytes)

        val progressReports = mutableListOf<Pair<Float, String>>()
        val result = extractor.extractFromStream(inStream) { progress, message ->
            progressReports.add(progress to message)
        }

        assertTrue("Expected ExtractionResult.Success but got $result", result is ExtractionResult.Success)
        val success = result as ExtractionResult.Success
        assertEquals(extractor.usrDir.absolutePath, success.installedPath.absolutePath)

        // Check files exist
        val python3 = File(tempDir, "usr/bin/python3")
        val proot = File(tempDir, "usr/bin/proot")
        val hermes = File(tempDir, "usr/bin/hermes")
        val lib = File(tempDir, "usr/lib/libpython3.11.so")
        val conf = File(tempDir, "usr/etc/hermes.conf")

        assertTrue(python3.exists())
        assertTrue(proot.exists())
        assertTrue(hermes.exists())
        assertTrue(lib.exists())
        assertTrue(conf.exists())

        // Check executable permissions
        assertTrue(python3.canExecute())
        assertTrue(proot.canExecute())
        assertTrue(hermes.canExecute())

        // Check marker exists and has version=1
        assertTrue(extractor.markerFile.exists())
        assertTrue(extractor.markerFile.readText().contains("version=1"))
        assertTrue(extractor.isBootstrapInstalled())

        // Check progress reporting
        assertTrue(progressReports.isNotEmpty())
        assertEquals(1.0f, progressReports.last().first, 0.001f)
    }

    @Test
    fun extractFromStream_withBundledAssetArchive_succeedsAndInstallsValidUserland() = runTest(testDispatcher) {
        val assetCandidates = listOf(
            File("app/src/main/assets/bootstrap-arm64.tar.xz"),
            File("src/main/assets/bootstrap-arm64.tar.xz")
        )
        val assetFile = assetCandidates.firstOrNull { it.exists() }
        assertTrue("Bundled asset archive must exist", assetFile != null && assetFile.exists())

        FileInputStream(assetFile!!).use { fis ->
            val result = extractor.extractFromStream(fis)
            assertTrue("Bundled asset extraction must succeed: $result", result is ExtractionResult.Success)
        }

        assertTrue(extractor.isBootstrapInstalled())
        assertTrue(File(tempDir, "usr/bin/python3").canExecute())
        assertTrue(File(tempDir, "usr/bin/proot").canExecute())
        assertTrue(File(tempDir, "usr/bin/hermes").canExecute())
    }

    @Test
    fun extractFromStream_withPathTraversalEntry_failsAndDoesNotEscape() = runTest(testDispatcher) {
        val maliciousEntries = mapOf(
            "usr/bin/python3" to "#!/bin/sh\n".toByteArray(),
            "../../escaped_test_file.txt" to "MALICIOUS_CONTENT".toByteArray()
        )
        val tarBytes = createTestTarXz(maliciousEntries)
        val inStream = ByteArrayInputStream(tarBytes)

        val result = extractor.extractFromStream(inStream)

        assertTrue(result is ExtractionResult.Error)
        val error = result as ExtractionResult.Error
        assertTrue(error.message.contains("outside destination directory") || error.message.contains("Bootstrap extraction failed"))

        val outsideFile = File(tempDir.parentFile, "escaped_test_file.txt")
        assertFalse("Path traversal file must not be created", outsideFile.exists())
    }

    @Test
    fun extractFromStream_withEscapingSymlink_failsAndDoesNotEscape() = runTest(testDispatcher) {
        val baos = ByteArrayOutputStream()
        XZCompressorOutputStream(baos).use { xzOut ->
            TarArchiveOutputStream(xzOut).use { tarOut ->
                for (bin in listOf("usr/bin/python3", "usr/bin/proot", "usr/bin/hermes")) {
                    val entry = TarArchiveEntry(bin)
                    val content = "#!/bin/sh\n".toByteArray()
                    entry.size = content.size.toLong()
                    entry.mode = 0b111_101_101
                    tarOut.putArchiveEntry(entry)
                    tarOut.write(content)
                    tarOut.closeArchiveEntry()
                }

                // Add a malicious symlink targeting outside
                val symEntry = TarArchiveEntry("usr/bin/evil_symlink", TarArchiveEntry.LF_SYMLINK)
                symEntry.linkName = "/etc/passwd"
                tarOut.putArchiveEntry(symEntry)
                tarOut.closeArchiveEntry()
            }
        }

        val inStream = ByteArrayInputStream(baos.toByteArray())
        val result = extractor.extractFromStream(inStream)

        assertTrue(result is ExtractionResult.Error)
        assertFalse(extractor.isBootstrapInstalled())
    }

    @Test
    fun extractFromStream_handlesHardlinks() = runTest(testDispatcher) {
        val baos = ByteArrayOutputStream()
        XZCompressorOutputStream(baos).use { xzOut ->
            TarArchiveOutputStream(xzOut).use { tarOut ->
                for (bin in listOf("usr/bin/python3", "usr/bin/proot", "usr/bin/hermes")) {
                    val entry = TarArchiveEntry(bin)
                    val content = "#!/bin/sh\n".toByteArray()
                    entry.size = content.size.toLong()
                    entry.mode = 0b111_101_101
                    tarOut.putArchiveEntry(entry)
                    tarOut.write(content)
                    tarOut.closeArchiveEntry()
                }

                // Add a hardlink entry
                val linkEntry = TarArchiveEntry("usr/bin/python3.11", TarArchiveEntry.LF_LINK)
                linkEntry.linkName = "usr/bin/python3"
                tarOut.putArchiveEntry(linkEntry)
                tarOut.closeArchiveEntry()
            }
        }

        val inStream = ByteArrayInputStream(baos.toByteArray())
        val result = extractor.extractFromStream(inStream)

        assertTrue("Hardlink extraction must succeed", result is ExtractionResult.Success)
        assertTrue(extractor.isBootstrapInstalled())
        val linkFile = File(tempDir, "usr/bin/python3.11")
        assertTrue(linkFile.exists())
    }

    @Test
    fun extractFromStream_invalidStream_returnsErrorResult_andDoesNotLeaveMarker() = runTest(testDispatcher) {
        val invalidBytes = "NOT_A_VALID_TAR_XZ_STREAM".toByteArray()
        val inStream = ByteArrayInputStream(invalidBytes)

        val result = extractor.extractFromStream(inStream)

        assertTrue(result is ExtractionResult.Error)
        assertFalse(extractor.markerFile.exists())
        assertFalse(extractor.isBootstrapInstalled())
    }

    @Test
    fun extractFromStream_missingCriticalBinary_returnsErrorResult() = runTest(testDispatcher) {
        // Archive without 'hermes' binary
        val incompleteEntries = mapOf(
            "usr/bin/python3" to "#!/bin/sh\n".toByteArray(),
            "usr/bin/proot" to "#!/bin/sh\n".toByteArray()
        )
        val tarBytes = createTestTarXz(incompleteEntries)
        val inStream = ByteArrayInputStream(tarBytes)

        val result = extractor.extractFromStream(inStream)

        assertTrue(result is ExtractionResult.Error)
        val error = result as ExtractionResult.Error
        assertTrue(error.message.contains("Missing critical binary") || error.message.contains("Integrity"))
        assertFalse(extractor.markerFile.exists())
        assertFalse(extractor.isBootstrapInstalled())
    }

    @Test
    fun extract_withoutAssetManager_returnsErrorResult() = runTest(testDispatcher) {
        val result = extractor.extract("any_asset.tar.xz")
        assertTrue(result is ExtractionResult.Error)
        val error = result as ExtractionResult.Error
        assertTrue(error.message.contains("AssetManager is null"))
    }

    @Test
    fun extractFromStream_handlesSymlinksGracefully() = runTest(testDispatcher) {
        val baos = ByteArrayOutputStream()
        XZCompressorOutputStream(baos).use { xzOut ->
            TarArchiveOutputStream(xzOut).use { tarOut ->
                for (bin in listOf("usr/bin/python3", "usr/bin/proot", "usr/bin/hermes")) {
                    val entry = TarArchiveEntry(bin)
                    val content = "#!/bin/sh\n".toByteArray()
                    entry.size = content.size.toLong()
                    entry.mode = 0b111_101_101
                    tarOut.putArchiveEntry(entry)
                    tarOut.write(content)
                    tarOut.closeArchiveEntry()
                }

                // Add a valid relative symlink entry
                val symEntry = TarArchiveEntry("usr/bin/python", TarArchiveEntry.LF_SYMLINK)
                symEntry.linkName = "python3"
                tarOut.putArchiveEntry(symEntry)
                tarOut.closeArchiveEntry()
            }
        }

        val inStream = ByteArrayInputStream(baos.toByteArray())
        val result = extractor.extractFromStream(inStream)

        assertTrue(result is ExtractionResult.Success)
        assertTrue(extractor.isBootstrapInstalled())
    }

    @Test
    fun cleanUserland_deletesUsrDirAndMarker() {
        val usrDir = File(tempDir, "usr/bin").apply { mkdirs() }
        File(usrDir, "python3").writeText("test")
        val marker = File(tempDir, BootstrapExtractor.MARKER_FILE_NAME).apply { writeText("version=1\n") }

        assertTrue(usrDir.exists())
        assertTrue(marker.exists())

        extractor.cleanUserland()

        assertFalse(extractor.usrDir.exists())
        assertFalse(extractor.markerFile.exists())
        assertFalse(extractor.isBootstrapInstalled())
    }
}
