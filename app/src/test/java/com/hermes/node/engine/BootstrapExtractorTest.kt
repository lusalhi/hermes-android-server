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
        marker.writeText("version=${BootstrapExtractor.BOOTSTRAP_VERSION}")

        assertFalse(extractor.isBootstrapInstalled())
    }

    @Test
    fun isBootstrapInstalled_returnsTrue_whenMarkerAndAllBinariesPresent() {
        val usrDir = File(tempDir, "usr/bin").apply { mkdirs() }
        File(usrDir, "python3").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "proot").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "hermes").apply { writeText("dummy"); setExecutable(true) }

        val marker = File(tempDir, BootstrapExtractor.MARKER_FILE_NAME)
        marker.writeText("version=${BootstrapExtractor.BOOTSTRAP_VERSION}\ntimestamp=123456\n")

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

        // Check marker exists and has version=${BootstrapExtractor.BOOTSTRAP_VERSION}
        assertTrue(extractor.markerFile.exists())
        assertTrue(extractor.markerFile.readText().contains("version=${BootstrapExtractor.BOOTSTRAP_VERSION}"))
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
    fun checkHealth_returnsNotInstalled_whenDirEmptyAndMarkerMissing() {
        val health = extractor.checkHealth()
        assertTrue(health is HealthCheckResult.NotInstalled)
    }

    @Test
    fun checkHealth_returnsHealthy_whenMarkerAndAllBinariesPresentAndExecutable() {
        val usrDir = File(tempDir, "usr/bin").apply { mkdirs() }
        File(usrDir, "python3").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "proot").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "hermes").apply { writeText("dummy"); setExecutable(true) }

        val marker = File(tempDir, BootstrapExtractor.MARKER_FILE_NAME)
        marker.writeText("version=${BootstrapExtractor.BOOTSTRAP_VERSION}\ntimestamp=123456\n")

        val health = extractor.checkHealth()
        assertTrue("Expected Healthy but got $health", health is HealthCheckResult.Healthy)
    }

    @Test
    fun checkHealth_returnsCorrupted_whenCriticalBinaryMissing() {
        val usrDir = File(tempDir, "usr/bin").apply { mkdirs() }
        File(usrDir, "python3").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "proot").apply { writeText("dummy"); setExecutable(true) }
        // Missing hermes binary

        val marker = File(tempDir, BootstrapExtractor.MARKER_FILE_NAME)
        marker.writeText("version=${BootstrapExtractor.BOOTSTRAP_VERSION}\ntimestamp=123456\n")

        val health = extractor.checkHealth()
        assertTrue("Expected Corrupted but got $health", health is HealthCheckResult.Corrupted)
        val corrupted = health as HealthCheckResult.Corrupted
        assertTrue(corrupted.issues.any { it.contains("bin/hermes") })
        assertTrue(corrupted.details.contains("bin/hermes"))
    }

    @Test
    fun checkHealth_returnsCorrupted_whenVersionMismatch() {
        val usrDir = File(tempDir, "usr/bin").apply { mkdirs() }
        File(usrDir, "python3").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "proot").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "hermes").apply { writeText("dummy"); setExecutable(true) }

        val marker = File(tempDir, BootstrapExtractor.MARKER_FILE_NAME)
        marker.writeText("version=99\n")

        val health = extractor.checkHealth()
        assertTrue(health is HealthCheckResult.Corrupted)
        val corrupted = health as HealthCheckResult.Corrupted
        assertTrue(corrupted.issues.any { it.contains("version mismatch") || it.contains("expected version=${BootstrapExtractor.BOOTSTRAP_VERSION}") })
    }

    @Test
    fun checkHealth_returnsCorrupted_whenMarkerMissing_butUsrHasFiles() {
        val usrDir = File(tempDir, "usr/bin").apply { mkdirs() }
        File(usrDir, "python3").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "proot").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "hermes").apply { writeText("dummy"); setExecutable(true) }
        // Marker missing

        val health = extractor.checkHealth()
        assertTrue(health is HealthCheckResult.Corrupted)
        val corrupted = health as HealthCheckResult.Corrupted
        assertTrue(corrupted.issues.any { it.contains("marker file (.bootstrap_complete) is missing") })
    }

    @Test
    fun checkHealth_returnsCorrupted_whenBinaryIsNotRegularFile() {
        val usrDir = File(tempDir, "usr/bin").apply { mkdirs() }
        File(usrDir, "python3").apply { mkdirs() } // Directory instead of file
        File(usrDir, "proot").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "hermes").apply { writeText("dummy"); setExecutable(true) }

        val marker = File(tempDir, BootstrapExtractor.MARKER_FILE_NAME)
        marker.writeText("version=${BootstrapExtractor.BOOTSTRAP_VERSION}\n")

        val health = extractor.checkHealth()
        assertTrue(health is HealthCheckResult.Corrupted)
        val corrupted = health as HealthCheckResult.Corrupted
        assertTrue(corrupted.issues.any { it.contains("not a regular file") })
    }

    @Test
    fun repairFromStream_restoresBinaries_andPreservesUserDataFiles() = runTest(testDispatcher) {
        // 1. Create simulated user database, checkpoint, and config in filesDir
        val userDbDir = File(tempDir, "data").apply { mkdirs() }
        val userDbFile = File(userDbDir, "user_episodic.db").apply { writeText("SQLITE_HEADER_PERSISTENT_DATA") }
        val checkpointDir = File(tempDir, "checkpoints").apply { mkdirs() }
        val checkpointFile = File(checkpointDir, "checkpoint_1.chk").apply { writeText("EPISODIC_STATE_DATA") }
        val configFile = File(tempDir, "hermes.json").apply { writeText("{\"api_key\":\"secret\"}") }

        // 2. Corrupt the userland by deleting a binary and breaking marker
        val usrDir = File(tempDir, "usr/bin").apply { mkdirs() }
        File(usrDir, "python3").apply { writeText("corrupt"); setExecutable(true) }
        // proot and hermes missing
        val marker = File(tempDir, BootstrapExtractor.MARKER_FILE_NAME)
        marker.writeText("version=${BootstrapExtractor.BOOTSTRAP_VERSION}\n")

        assertTrue(extractor.checkHealth() is HealthCheckResult.Corrupted)

        // 3. Trigger repair from valid archive stream
        val tarBytes = createTestTarXz(getStandardArchiveEntries())
        val inStream = ByteArrayInputStream(tarBytes)
        val progressList = mutableListOf<Float>()

        val result = extractor.repairFromStream(inStream) { progress, _ ->
            progressList.add(progress)
        }

        assertTrue("Repair result must be Success", result is ExtractionResult.Success)

        // 4. Verify runtime is now healthy
        val health = extractor.checkHealth()
        assertTrue("Health check after repair must be Healthy", health is HealthCheckResult.Healthy)
        assertTrue(extractor.isBootstrapInstalled())

        // 5. Verify all user data files are strictly preserved
        assertTrue(userDbFile.exists())
        assertEquals("SQLITE_HEADER_PERSISTENT_DATA", userDbFile.readText())
        assertTrue(checkpointFile.exists())
        assertEquals("EPISODIC_STATE_DATA", checkpointFile.readText())
        assertTrue(configFile.exists())
        assertEquals("{\"api_key\":\"secret\"}", configFile.readText())

        // 6. Verify binaries were re-extracted and made executable
        assertTrue(File(tempDir, "usr/bin/python3").canExecute())
        assertTrue(File(tempDir, "usr/bin/proot").canExecute())
        assertTrue(File(tempDir, "usr/bin/hermes").canExecute())
    }

    @Test
    fun repair_withoutAssetManager_returnsErrorResult() = runTest(testDispatcher) {
        val result = extractor.repair("bootstrap-arm64.tar.xz")
        assertTrue(result is ExtractionResult.Error)
        val error = result as ExtractionResult.Error
        assertTrue(error.message.contains("AssetManager is null"))
    }

    @Test
    fun checkHealth_selfHealsNonExecutableBinary_whenPossible() {
        val usrDir = File(tempDir, "usr/bin").apply { mkdirs() }
        val py = File(usrDir, "python3").apply { writeText("dummy"); setExecutable(false) }
        File(usrDir, "proot").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "hermes").apply { writeText("dummy"); setExecutable(true) }

        val marker = File(tempDir, BootstrapExtractor.MARKER_FILE_NAME)
        marker.writeText("version=${BootstrapExtractor.BOOTSTRAP_VERSION}\n")

        val health = extractor.checkHealth()
        assertTrue("Expected Healthy due to self-healing execute permission", health is HealthCheckResult.Healthy)
        assertTrue(py.canExecute())
    }

    @Test
    fun repairFromStream_whenUsrDirContainsReadOnlyFiles_successfullyCleansAndRepairs() = runTest(testDispatcher) {
        val usrDir = File(tempDir, "usr/bin").apply { mkdirs() }
        val readOnlyFile = File(usrDir, "locked_script.sh").apply {
            writeText("READ_ONLY")
            setWritable(false)
        }
        val marker = File(tempDir, BootstrapExtractor.MARKER_FILE_NAME).apply { writeText("version=${BootstrapExtractor.BOOTSTRAP_VERSION}\n") }

        val tarBytes = createTestTarXz(getStandardArchiveEntries())
        val inStream = ByteArrayInputStream(tarBytes)

        val result = extractor.repairFromStream(inStream)

        assertTrue("Repair must succeed even with read-only files: $result", result is ExtractionResult.Success)
        assertTrue(extractor.isBootstrapInstalled())
        assertFalse("Old read-only file must be removed", readOnlyFile.exists())
    }

    @Test
    fun cleanUserland_deletesUsrDirAndMarker_preservesOtherFiles() {
        val usrDir = File(tempDir, "usr/bin").apply { mkdirs() }
        File(usrDir, "python3").writeText("test")
        val marker = File(tempDir, BootstrapExtractor.MARKER_FILE_NAME).apply { writeText("version=${BootstrapExtractor.BOOTSTRAP_VERSION}\n") }
        val userFile = File(tempDir, "agent_data.db").apply { writeText("keep me") }

        assertTrue(usrDir.exists())
        assertTrue(marker.exists())
        assertTrue(userFile.exists())

        extractor.cleanUserland()

        assertFalse(extractor.usrDir.exists())
        assertFalse(extractor.markerFile.exists())
        assertFalse(extractor.isBootstrapInstalled())
        assertTrue("User file must remain intact", userFile.exists())
        assertEquals("keep me", userFile.readText())
    }

    @Test
    fun ensureToolchainShims_createsExecutableShims() {
        val created = extractor.ensureToolchainShims()
        assertTrue(created)

        val sudo = File(extractor.usrDir, "bin/sudo")
        val apt = File(extractor.usrDir, "bin/apt")
        val aptGet = File(extractor.usrDir, "bin/apt-get")

        assertTrue(sudo.exists() && sudo.isFile)
        assertTrue(apt.exists() && apt.isFile)
        assertTrue(aptGet.exists() && aptGet.isFile)

        assertTrue(sudo.canExecute())
        assertTrue(apt.canExecute())
        assertTrue(aptGet.canExecute())

        // Verify mirror in usr/usr/bin
        assertTrue(File(extractor.usrDir, "usr/bin/sudo").canExecute())
        assertTrue(File(extractor.usrDir, "usr/bin/apt").canExecute())
        assertTrue(File(extractor.usrDir, "usr/bin/apt-get").canExecute())
    }

    @Test
    fun ensureToolchainShims_shimsAreFunctional() {
        extractor.ensureToolchainShims()

        val sudo = File(extractor.usrDir, "bin/sudo")
        val apt = File(extractor.usrDir, "bin/apt")
        val aptGet = File(extractor.usrDir, "bin/apt-get")

        // 1. Test sudo transparent execution
        val sudoProcess = ProcessBuilder(sudo.absolutePath, "echo", "shim_sudo_ok").start()
        val sudoOutput = sudoProcess.inputStream.bufferedReader().readText().trim()
        val sudoExit = sudoProcess.waitFor()
        assertEquals(0, sudoExit)
        assertEquals("shim_sudo_ok", sudoOutput)

        // 2. Test apt update
        val aptProcess = ProcessBuilder(apt.absolutePath, "update").start()
        val aptOutput = aptProcess.inputStream.bufferedReader().readText().trim()
        val aptExit = aptProcess.waitFor()
        assertEquals(0, aptExit)
        assertTrue(aptOutput.contains("Reading package lists... Done"))

        // 3. Test apt-get update
        val aptGetProcess = ProcessBuilder(aptGet.absolutePath, "update").start()
        val aptGetOutput = aptGetProcess.inputStream.bufferedReader().readText().trim()
        val aptGetExit = aptGetProcess.waitFor()
        assertEquals(0, aptGetExit)
        assertTrue(aptGetOutput.contains("Reading package lists... Done"))

        // 4. Test apt install intercepted notification
        val installProcess = ProcessBuilder(apt.absolutePath, "install", "-y", "jq", "ffmpeg").start()
        val installOutput = installProcess.inputStream.bufferedReader().readText().trim()
        val installExit = installProcess.waitFor()
        assertEquals(0, installExit)
        assertTrue(installOutput.contains("Hermes Shim: Package installation requested for: -y jq ffmpeg"))

        // 5. Test sudo with options before command
        val sudoFlagsProcess = ProcessBuilder(sudo.absolutePath, "-E", "echo", "shim_sudo_flags_ok").start()
        val sudoFlagsOutput = sudoFlagsProcess.inputStream.bufferedReader().readText().trim()
        val sudoFlagsExit = sudoFlagsProcess.waitFor()
        assertEquals(0, sudoFlagsExit)
        assertEquals("shim_sudo_flags_ok", sudoFlagsOutput)

        // 6. Test apt-get with option before subcommand
        val aptGetPrecedingFlagsProcess = ProcessBuilder(aptGet.absolutePath, "-y", "install", "curl").start()
        val aptGetPrecedingFlagsOutput = aptGetPrecedingFlagsProcess.inputStream.bufferedReader().readText().trim()
        val aptGetPrecedingFlagsExit = aptGetPrecedingFlagsProcess.waitFor()
        assertEquals(0, aptGetPrecedingFlagsExit)
        assertTrue(aptGetPrecedingFlagsOutput.contains("Hermes Shim: Package installation requested for: curl"))
    }

    @Test
    fun checkHealth_autoHealsMissingShims() {
        val usrDir = File(tempDir, "usr/bin").apply { mkdirs() }
        File(usrDir, "python3").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "proot").apply { writeText("dummy"); setExecutable(true) }
        File(usrDir, "hermes").apply { writeText("dummy"); setExecutable(true) }

        val marker = File(tempDir, BootstrapExtractor.MARKER_FILE_NAME)
        marker.writeText("version=${BootstrapExtractor.BOOTSTRAP_VERSION}\ntimestamp=123456\n")

        // Intentionally delete shims
        File(usrDir, "sudo").delete()
        File(usrDir, "apt").delete()
        File(usrDir, "apt-get").delete()

        assertFalse(File(usrDir, "sudo").exists())
        assertFalse(File(usrDir, "apt").exists())

        val health = extractor.checkHealth()
        assertTrue("Health check must succeed and auto-heal missing shims", health is HealthCheckResult.Healthy)

        assertTrue(File(usrDir, "sudo").exists())
        assertTrue(File(usrDir, "sudo").canExecute())
        assertTrue(File(usrDir, "apt").exists())
        assertTrue(File(usrDir, "apt").canExecute())
        assertTrue(File(usrDir, "apt-get").exists())
        assertTrue(File(usrDir, "apt-get").canExecute())
    }

    @Test
    fun extractFromStream_generatesToolchainShims() = runTest(testDispatcher) {
        val tarBytes = createTestTarXz(getStandardArchiveEntries())
        val inStream = ByteArrayInputStream(tarBytes)

        val result = extractor.extractFromStream(inStream)
        assertTrue(result is ExtractionResult.Success)

        val sudo = File(tempDir, "usr/bin/sudo")
        val apt = File(tempDir, "usr/bin/apt")
        val aptGet = File(tempDir, "usr/bin/apt-get")

        assertTrue(sudo.exists() && sudo.canExecute())
        assertTrue(apt.exists() && apt.canExecute())
        assertTrue(aptGet.exists() && aptGet.canExecute())
    }

    @Test
    fun extractFromStream_generatesHermesLauncherScript_withPythonModuleExecution() = runTest(testDispatcher) {
        val tarBytes = createTestTarXz(getStandardArchiveEntries())
        val inStream = ByteArrayInputStream(tarBytes)

        val result = extractor.extractFromStream(inStream)
        assertTrue(result is ExtractionResult.Success)

        val hermes = File(tempDir, "usr/bin/hermes")
        assertTrue(hermes.exists() && hermes.canExecute())
        val content = hermes.readText(Charsets.UTF_8)
        assertTrue("Launcher must execute python3 -m hermes", content.contains("exec python3 -m hermes \"$@\""))
    }

    @Test
    fun ensureHermesLauncher_createsExecutableHermesLauncher() {
        val created = extractor.ensureHermesLauncher(forceCreate = true)
        assertTrue(created)

        val binHermes = File(extractor.usrDir, "bin/hermes")
        val usrBinHermes = File(extractor.usrDir, "usr/bin/hermes")

        assertTrue(binHermes.exists() && binHermes.isFile && binHermes.canExecute())
        assertTrue(usrBinHermes.exists() && usrBinHermes.isFile && usrBinHermes.canExecute())

        val script = binHermes.readText(Charsets.UTF_8)
        assertTrue(script.contains("exec python3 -m hermes \"$@\""))
        assertEquals(script, usrBinHermes.readText(Charsets.UTF_8))
    }
}
