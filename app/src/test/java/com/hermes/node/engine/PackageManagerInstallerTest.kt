package com.hermes.node.engine

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
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
import java.nio.file.Files

class PackageManagerInstallerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tempDir: File
    private lateinit var installer: PackageManagerInstaller

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("pkg_installer_test_").toFile()
        installer = PackageManagerInstaller(
            filesDir = tempDir,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun createTestTarGz(
        entries: Map<String, ByteArray>,
        modes: Map<String, Int> = emptyMap()
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        GzipCompressorOutputStream(baos).use { gzOut ->
            TarArchiveOutputStream(gzOut).use { tarOut ->
                for ((name, content) in entries) {
                    val entry = TarArchiveEntry(name)
                    entry.size = content.size.toLong()
                    entry.mode = modes[name] ?: 0b111_101_101 // 0755
                    tarOut.putArchiveEntry(entry)
                    tarOut.write(content)
                    tarOut.closeArchiveEntry()
                }
            }
        }
        return baos.toByteArray()
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
                    entry.mode = modes[name] ?: 0b111_101_101
                    tarOut.putArchiveEntry(entry)
                    tarOut.write(content)
                    tarOut.closeArchiveEntry()
                }
            }
        }
        return baos.toByteArray()
    }

    @Test
    fun isPackageManagerInstalled_initiallyFalse() {
        assertFalse(installer.isPackageManagerInstalled())
    }

    @Test
    fun ensureNetworkConfig_createsResolvConfAndRepositoriesWithCorrectContent() {
        val success = installer.ensureNetworkConfig()
        assertTrue(success)

        val resolvConf = File(installer.usrDir, "etc/resolv.conf")
        val repositories = File(installer.usrDir, "etc/apk/repositories")

        assertTrue(resolvConf.exists())
        assertTrue(repositories.exists())

        val resolvText = resolvConf.readText()
        assertTrue(resolvText.contains("nameserver 8.8.8.8"))
        assertTrue(resolvText.contains("nameserver 1.1.1.1"))

        val repoText = repositories.readText()
        assertTrue(repoText.contains("https://dl-cdn.alpinelinux.org/alpine/v3.20/main"))
        assertTrue(repoText.contains("https://dl-cdn.alpinelinux.org/alpine/v3.20/community"))
    }

    @Test
    fun installFromStream_validTarGz_extractsApkAndConfiguresNetwork() = runTest(testDispatcher) {
        val apkScript = "#!/bin/sh\necho 'apk v3.20'\n".toByteArray()
        val entries = mapOf(
            "bin/apk" to apkScript,
            "etc/alpine-release" to "3.20.0\n".toByteArray()
        )
        val tarGzBytes = createTestTarGz(entries)

        val progressReports = mutableListOf<Pair<Float, String>>()
        val result = installer.installFromStream(ByteArrayInputStream(tarGzBytes)) { progress, message ->
            progressReports.add(progress to message)
        }

        assertTrue("Expected installFromStream to succeed but got: $result", result.isSuccess)
        val count = result.getOrNull()
        assertTrue(count != null && count >= 2)

        val binApk = File(installer.usrDir, "bin/apk")
        val usrBinApk = File(installer.usrDir, "usr/bin/apk")
        val resolvConf = File(installer.usrDir, "etc/resolv.conf")
        val repos = File(installer.usrDir, "etc/apk/repositories")

        assertTrue(binApk.exists())
        assertTrue(binApk.canExecute())
        assertTrue(usrBinApk.exists())
        assertTrue(usrBinApk.canExecute())
        assertTrue(resolvConf.exists())
        assertTrue(repos.exists())
        assertTrue(installer.isPackageManagerInstalled())
        assertTrue(progressReports.isNotEmpty())
    }

    @Test
    fun installFromStream_validTarXz_extractsApkAndConfiguresNetwork() = runTest(testDispatcher) {
        val apkScript = "#!/bin/sh\necho 'apk xz'\n".toByteArray()
        val entries = mapOf(
            "sbin/apk" to apkScript
        )
        val tarXzBytes = createTestTarXz(entries)

        val result = installer.installFromStream(ByteArrayInputStream(tarXzBytes))
        assertTrue("Expected installFromStream (.tar.xz) to succeed: $result", result.isSuccess)

        val binApk = File(installer.usrDir, "bin/apk")
        val usrBinApk = File(installer.usrDir, "usr/bin/apk")
        assertTrue(binApk.exists())
        assertTrue(binApk.canExecute())
        assertTrue(usrBinApk.exists())
        assertTrue(usrBinApk.canExecute())
        assertTrue(installer.isPackageManagerInstalled())
    }

    @Test
    fun installFromStream_preservesUserDataInFilesDir() = runTest(testDispatcher) {
        val configFile = File(tempDir, "hermes.json").apply { writeText("{\"model\": \"test\"}") }
        val agentDataDir = File(tempDir, "agent_data").apply { mkdirs() }
        val dbFile = File(agentDataDir, "memory.sqlite").apply { writeText("SQLITE_DUMMY_DATA") }

        val entries = mapOf("bin/apk" to "#!/bin/sh\nexit 0\n".toByteArray())
        val tarGzBytes = createTestTarGz(entries)

        val result = installer.installFromStream(ByteArrayInputStream(tarGzBytes))
        assertTrue(result.isSuccess)

        // Ensure user configuration and databases in filesDir are NOT wiped
        assertTrue(configFile.exists())
        assertEquals("{\"model\": \"test\"}", configFile.readText())
        assertTrue(dbFile.exists())
        assertEquals("SQLITE_DUMMY_DATA", dbFile.readText())
    }

    @Test
    fun installFromStream_truncatedArchive_failsCleanlyWithoutCorruptingExistingBinaries() = runTest(testDispatcher) {
        // Pre-create existing critical binary
        val binDir = File(installer.usrDir, "bin").apply { mkdirs() }
        val pythonBin = File(binDir, "python3").apply {
            writeText("#!/bin/sh\necho python\n")
            setExecutable(true)
        }

        // Create truncated stream
        val entries = mapOf("bin/apk" to ByteArray(1024 * 50) { 1 })
        val fullBytes = createTestTarGz(entries)
        val truncatedBytes = fullBytes.copyOfRange(0, fullBytes.size / 3)

        val result = installer.installFromStream(ByteArrayInputStream(truncatedBytes))
        assertTrue("Expected failure for truncated archive", result.isFailure)

        // Existing binary must still be present and intact
        assertTrue(pythonBin.exists())
        assertTrue(pythonBin.canExecute())
        assertEquals("#!/bin/sh\necho python\n", pythonBin.readText())
    }

    @Test
    fun installFromStream_zipSlipEntry_rejectedWithSecurityException() = runTest(testDispatcher) {
        val baos = ByteArrayOutputStream()
        GzipCompressorOutputStream(baos).use { gzOut ->
            TarArchiveOutputStream(gzOut).use { tarOut ->
                val entry = TarArchiveEntry("../evil.txt")
                val content = "MALICIOUS".toByteArray()
                entry.size = content.size.toLong()
                tarOut.putArchiveEntry(entry)
                tarOut.write(content)
                tarOut.closeArchiveEntry()
            }
        }

        val result = installer.installFromStream(ByteArrayInputStream(baos.toByteArray()))
        assertTrue(result.isFailure)
        val evilFile = File(tempDir.parentFile, "evil.txt")
        assertFalse(evilFile.exists())
    }

    @Test
    fun aptShimDelegation_executesApkAdd_strippingYesFlags() {
        val extractor = BootstrapExtractor(filesDir = tempDir, ioDispatcher = testDispatcher)
        extractor.ensureToolchainShims()

        val logFile = File(tempDir, "apk_calls.log")
        // Create mock apk executable
        val binDir = File(installer.usrDir, "bin").apply { mkdirs() }
        val apkFile = File(binDir, "apk").apply {
            writeText("#!/bin/sh\necho \"APK: \$@\" >> \"${logFile.absolutePath}\"\nexit 0\n")
            setExecutable(true)
        }
        assertTrue(apkFile.canExecute())

        val aptShim = File(installer.usrDir, "bin/apt")
        assertTrue(aptShim.exists())
        assertTrue(aptShim.canExecute())

        // Run shim with install -y curl
        val pb = ProcessBuilder("/bin/sh", aptShim.absolutePath, "install", "-y", "curl")
        pb.environment()["PATH"] = "${binDir.absolutePath}:/bin:/usr/bin"
        pb.redirectErrorStream(true)
        val process = pb.start()

        val exitCode = process.waitFor()
        assertEquals(0, exitCode)

        assertTrue(logFile.exists())
        val logContent = logFile.readText()
        assertTrue("Log should contain 'add curl' but was: $logContent", logContent.contains("add curl"))
        assertFalse("Log should NOT contain '-y'", logContent.contains("-y"))
    }

    @Test
    fun bootstrapExtractor_checkHealth_detectsApkAndReportsHealthy() {
        val extractor = BootstrapExtractor(filesDir = tempDir, ioDispatcher = testDispatcher)

        // Setup base healthy userland
        val usrBinDir = File(extractor.usrDir, "bin").apply { mkdirs() }
        File(usrBinDir, "python3").apply { writeText("python"); setExecutable(true) }
        File(usrBinDir, "proot").apply { writeText("proot"); setExecutable(true) }
        File(usrBinDir, "hermes").apply { writeText("hermes"); setExecutable(true) }
        File(tempDir, BootstrapExtractor.MARKER_FILE_NAME).writeText("version=${BootstrapExtractor.BOOTSTRAP_VERSION}\n")

        assertFalse(extractor.isPackageManagerInstalled())
        val healthBefore = extractor.checkHealth()
        assertTrue(healthBefore is HealthCheckResult.Healthy)

        // Add apk
        File(usrBinDir, "apk").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        assertTrue(extractor.isPackageManagerInstalled())

        val healthAfter = extractor.checkHealth()
        assertTrue(healthAfter is HealthCheckResult.Healthy)

        // Check network config was ensured
        assertTrue(File(extractor.usrDir, "etc/resolv.conf").exists())
        assertTrue(File(extractor.usrDir, "etc/apk/repositories").exists())
    }

    @Test
    fun downloadAndInstall_fromHttpServer_downloadsAndInstallsSuccessfully() = runTest(testDispatcher) {
        val tarGzBytes = createTestTarGz(
            mapOf(
                "bin/apk" to "#!/bin/sh\necho apk\nexit 0\n".toByteArray(),
                "etc/alpine-release" to "3.20.0".toByteArray()
            )
        )
        val serverSocket = java.net.ServerSocket(0)
        val port = serverSocket.localPort
        val serverThread = Thread {
            try {
                val client = serverSocket.accept()
                val reader = client.getInputStream().bufferedReader()
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrBlank()) break
                }
                val out = client.getOutputStream()
                val header = "HTTP/1.1 200 OK\r\nContent-Length: ${tarGzBytes.size}\r\nContent-Type: application/gzip\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.ISO_8859_1))
                out.write(tarGzBytes)
                out.flush()
                client.close()
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        try {
            val progressList = mutableListOf<Float>()
            val installerWithIo = PackageManagerInstaller(
                filesDir = tempDir,
                ioDispatcher = kotlinx.coroutines.Dispatchers.IO
            )
            val result = installerWithIo.downloadAndInstall("http://127.0.0.1:$port/apk.tar.gz") { prog, _ ->
                progressList.add(prog)
            }
            assertTrue("Expected success but got: $result", result.isSuccess)
            assertEquals(2, result.getOrNull())
            assertTrue(installerWithIo.isPackageManagerInstalled())

            // Verify progress covered download (<=0.5) and extract (>=0.5) monotonically
            assertTrue("Expected download phase progress <= 0.50f", progressList.any { it <= 0.50f })
            assertTrue("Expected extraction phase progress >= 0.50f", progressList.any { it >= 0.50f })
            assertEquals(1.0f, progressList.last(), 0.001f)

            // Staging directories and tmp download files must be cleaned up
            val remainingStaging = tempDir.listFiles { _, name -> name.startsWith(".pkg_stage_") }
            assertTrue(remainingStaging.isNullOrEmpty())
            val tmpDir = File(tempDir, "tmp")
            val remainingTmp = tmpDir.listFiles { _, name -> name.startsWith("pkg_dl_") }
            assertTrue(remainingTmp.isNullOrEmpty())
        } finally {
            serverSocket.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun downloadAndInstall_truncatedDownload_failsWithTruncatedIOException() = runTest(testDispatcher) {
        val serverSocket = java.net.ServerSocket(0)
        val port = serverSocket.localPort
        val serverThread = Thread {
            try {
                val client = serverSocket.accept()
                val reader = client.getInputStream().bufferedReader()
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrBlank()) break
                }
                val out = client.getOutputStream()
                val header = "HTTP/1.1 200 OK\r\nContent-Length: 10000\r\nContent-Type: application/gzip\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.ISO_8859_1))
                out.write(ByteArray(50))
                out.flush()
                client.close()
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        try {
            val installerWithIo = PackageManagerInstaller(
                filesDir = tempDir,
                ioDispatcher = kotlinx.coroutines.Dispatchers.IO
            )
            val result = installerWithIo.downloadAndInstall("http://127.0.0.1:$port/truncated.tar.gz")
            assertTrue(result.isFailure)
            val ex = result.exceptionOrNull()
            assertTrue("Expected IOException with truncated message but got: $ex", ex is java.io.IOException && ex.message?.contains("truncated") == true)
        } finally {
            serverSocket.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun installFromStream_directorySymlink_committedAsSymlinkNotDirectory() = runTest(testDispatcher) {
        val baos = ByteArrayOutputStream()
        GzipCompressorOutputStream(baos).use { gzOut ->
            TarArchiveOutputStream(gzOut).use { tarOut ->
                val apkBytes = "#!/bin/sh\nexit 0\n".toByteArray()
                val apkEntry = TarArchiveEntry("bin/apk").apply {
                    size = apkBytes.size.toLong()
                    mode = 0b111_101_101
                }
                tarOut.putArchiveEntry(apkEntry)
                tarOut.write(apkBytes)
                tarOut.closeArchiveEntry()

                val libDirEntry = TarArchiveEntry("lib/").apply {
                    mode = 0b111_101_101
                }
                tarOut.putArchiveEntry(libDirEntry)
                tarOut.closeArchiveEntry()

                val symlinkEntry = TarArchiveEntry("lib64", TarArchiveEntry.LF_SYMLINK).apply {
                    linkName = "lib"
                    mode = 0b111_111_111
                }
                tarOut.putArchiveEntry(symlinkEntry)
                tarOut.closeArchiveEntry()
            }
        }

        val result = installer.installFromStream(ByteArrayInputStream(baos.toByteArray()))
        assertTrue(result.isSuccess)

        val lib64File = File(installer.usrDir, "lib64")
        assertTrue(lib64File.exists())
        assertTrue("lib64 must be a symbolic link", Files.isSymbolicLink(lib64File.toPath()))
    }
}
