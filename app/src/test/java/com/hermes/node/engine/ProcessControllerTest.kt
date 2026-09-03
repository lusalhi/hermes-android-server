package com.hermes.node.engine

import com.hermes.node.data.ConfigSerializer
import com.hermes.node.data.model.HermesConfig
import com.hermes.node.data.model.SkillsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessControllerTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_isStopped_withNullStreamsAndPid() {
        val controller = ProcessController(ioDispatcher = testDispatcher)
        assertEquals(ProcessState.STOPPED, controller.state.value)
        assertNull(controller.pid)
        assertNull(controller.exitCode)
        assertNull(controller.stdout)
        assertNull(controller.stderr)
        assertNull(controller.stdin)
        assertFalse(controller.isAlive)
    }

    @Test
    fun start_happyPath_launchesProcess_transitionsToRunning_capturesPidAndStreams() = runTest(testDispatcher) {
        val fakeProcess = FakeProcess(fakePid = 4321L)
        val fakeRunner = FakeProcessRunner(fakeProcess)
        val controller = ProcessController(processRunner = fakeRunner, ioDispatcher = testDispatcher)

        val config = ProcessConfig(executable = "/bin/dummy", arguments = listOf("arg1", "arg2"))
        val result = controller.start(config)

        assertTrue(result.isSuccess)
        assertEquals(4321L, result.getOrNull())
        assertEquals(4321L, controller.pid)
        assertEquals(ProcessState.RUNNING, controller.state.value)
        assertTrue(controller.isAlive)
        assertNotNull(controller.stdout)
        assertNotNull(controller.stderr)
        assertNotNull(controller.stdin)
        assertEquals(listOf("/bin/dummy", "arg1", "arg2"), fakeRunner.lastExecutedConfig?.fullCommand)

        controller.stop()
    }

    @Test
    fun start_whenAlreadyRunning_returnsFailure() = runTest(testDispatcher) {
        val fakeProcess = FakeProcess(fakePid = 1001L)
        val controller = ProcessController(processRunner = FakeProcessRunner(fakeProcess), ioDispatcher = testDispatcher)

        val config = ProcessConfig(executable = "/bin/dummy")
        val firstStart = controller.start(config)
        assertTrue(firstStart.isSuccess)
        assertEquals(ProcessState.RUNNING, controller.state.value)

        val secondStart = controller.start(config)
        assertTrue(secondStart.isFailure)
        assertTrue(secondStart.exceptionOrNull() is IllegalStateException)
        assertEquals(ProcessState.RUNNING, controller.state.value)

        controller.stop()
    }

    @Test
    fun start_whenBinaryNotFoundOrThrows_transitionsToErrorState_returnsFailure() = runTest(testDispatcher) {
        val brokenRunner = object : ProcessRunner {
            override fun run(config: ProcessConfig): Process {
                throw IOException("Cannot run program '/usr/bin/missing': error=2, No such file or directory")
            }
        }
        val controller = ProcessController(processRunner = brokenRunner, ioDispatcher = testDispatcher)

        val config = ProcessConfig(executable = "/usr/bin/missing")
        val result = controller.start(config)

        assertTrue(result.isFailure)
        assertEquals(ProcessState.ERROR, controller.state.value)
        assertNull(controller.pid)
        assertNull(controller.stdout)
        assertNull(controller.stderr)
        assertNull(controller.stdin)
        assertFalse(controller.isAlive)
    }

    @Test
    fun stop_whenRunning_sendsGracefulSigterm_transitionsToStopped() = runTest(testDispatcher) {
        val fakeProcess = FakeProcess(fakePid = 5555L, ignoreSigterm = false, exitCodeValue = 0)
        val controller = ProcessController(processRunner = FakeProcessRunner(fakeProcess), ioDispatcher = testDispatcher)

        controller.start(ProcessConfig(executable = "/bin/dummy"))
        assertEquals(ProcessState.RUNNING, controller.state.value)

        val stopResult = controller.stop(timeoutMs = 5000L)

        assertEquals(ProcessStopResult.GRACEFUL_SIGTERM, stopResult)
        assertTrue(fakeProcess.destroyCalled)
        assertFalse(fakeProcess.destroyForciblyCalled)
        assertEquals(ProcessState.STOPPED, controller.state.value)
        assertNull(controller.pid)
        assertNull(controller.stdout)
        assertNull(controller.stderr)
        assertNull(controller.stdin)
        assertFalse(controller.isAlive)
        assertEquals(0, controller.exitCode)
    }

    @Test
    fun stop_whenProcessIgnoresSigterm_timesOutAndSendsSigkill() = runTest(testDispatcher) {
        val unresponsiveProcess = FakeProcess(fakePid = 9999L, ignoreSigterm = true, exitCodeValue = 137)
        val controller = ProcessController(processRunner = FakeProcessRunner(unresponsiveProcess), ioDispatcher = testDispatcher)

        controller.start(ProcessConfig(executable = "/bin/unresponsive"))
        assertEquals(ProcessState.RUNNING, controller.state.value)

        val stopResult = controller.stop(timeoutMs = 100L)

        assertEquals(ProcessStopResult.FORCED_SIGKILL, stopResult)
        assertTrue(unresponsiveProcess.destroyCalled)
        assertTrue(unresponsiveProcess.destroyForciblyCalled)
        assertEquals(ProcessState.STOPPED, controller.state.value)
        assertNull(controller.pid)
        assertFalse(controller.isAlive)
        assertEquals(137, controller.exitCode)
    }

    @Test
    fun stop_whenAlreadyStopped_isNoOp_returnsAlreadyStopped() = runTest(testDispatcher) {
        val controller = ProcessController(ioDispatcher = testDispatcher)
        assertEquals(ProcessState.STOPPED, controller.state.value)

        val stopResult = controller.stop()
        assertEquals(ProcessStopResult.ALREADY_STOPPED, stopResult)
        assertEquals(ProcessState.STOPPED, controller.state.value)
    }

    @Test
    fun stop_whenErrorState_returnsAlreadyStopped() = runTest(testDispatcher) {
        val brokenRunner = object : ProcessRunner {
            override fun run(config: ProcessConfig): Process = throw SecurityException("Permission denied")
        }
        val controller = ProcessController(processRunner = brokenRunner, ioDispatcher = testDispatcher)
        controller.start(ProcessConfig(executable = "/root/secret"))
        assertEquals(ProcessState.ERROR, controller.state.value)

        val stopResult = controller.stop()
        assertEquals(ProcessStopResult.ALREADY_STOPPED, stopResult)
        assertEquals(ProcessState.STOPPED, controller.state.value)
    }

    @Test
    fun unexpectedExit_transitionsToTerminated_andNotifiesExitListeners() = runTest(testDispatcher) {
        val fakeProcess = FakeProcess(fakePid = 7777L, exitCodeValue = 1)
        val controller = ProcessController(processRunner = FakeProcessRunner(fakeProcess), ioDispatcher = testDispatcher)

        var listenerReceivedCode: Int? = null
        val listener: (Int) -> Unit = { code -> listenerReceivedCode = code }
        controller.addExitListener(listener)

        controller.start(ProcessConfig(executable = "/bin/crash_soon"))
        assertEquals(ProcessState.RUNNING, controller.state.value)

        // Simulate crash
        fakeProcess.simulateUnexpectedExit(139) // SIGSEGV (128 + 11)
        testScheduler.advanceUntilIdle()

        assertEquals(ProcessState.TERMINATED, controller.state.value)
        assertEquals(139, listenerReceivedCode)
        assertEquals(139, controller.exitCode)
        assertNull(controller.stdout)
        assertNull(controller.stderr)
        assertNull(controller.stdin)
        assertFalse(controller.isAlive)

        // Remove listener
        controller.removeExitListener(listener)
    }

    @Test
    fun createHermesDaemonConfig_buildsValidConfig() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_test_env_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val config = ProcessConfig.createHermesDaemonConfig(
            filesDir = tempDir,
            customEnv = mapOf("CUSTOM_VAR" to "123")
        )

        assertEquals(tempDir, config.workingDir)
        assertEquals("123", config.environment["CUSTOM_VAR"])
        assertTrue(config.environment.containsKey("HOME"))
        assertTrue(config.environment.containsKey("PREFIX"))
        assertTrue(config.environment.containsKey("PATH"))
        assertTrue(config.environment.containsKey("TMPDIR"))
        assertTrue(config.environment.containsKey("PYTHONHOME"))
        assertTrue(config.environment.containsKey("HERMES_CONFIG_PATH"))
        assertTrue(config.fullCommand.isNotEmpty())
        assertEquals(listOf("-m", "hermes", "gateway", "run"), config.arguments)

        tempDir.deleteRecursively()
    }

    @Test
    fun createHermesDaemonConfig_injectsBraveSearchEnvVars_andSyncsHermesFiles() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_brave_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val hermesConfig = HermesConfig(
            skills = SkillsConfig(
                webSearch = true,
                searchProvider = SkillsConfig.SEARCH_PROVIDER_BRAVE,
                searchApiKey = "BSA_test_secret_key"
            )
        )

        val config = ProcessConfig.createHermesDaemonConfig(
            filesDir = tempDir,
            hermesConfig = hermesConfig
        )

        assertEquals("BSA_test_secret_key", config.environment["BRAVE_SEARCH_API_KEY"])
        assertEquals("BSA_test_secret_key", config.environment["BRAVE_API_KEY"])
        assertEquals("brave", config.environment["HERMES_SEARCH_PROVIDER"])

        // Check .hermes/.env and config.yaml
        val hermesDir = File(tempDir, ".hermes")
        assertTrue(hermesDir.exists())

        val envFile = File(hermesDir, ".env")
        assertTrue(envFile.exists())
        val envContent = envFile.readText(Charsets.UTF_8)
        assertTrue(envContent.contains("HERMES_SEARCH_PROVIDER=brave"))
        assertTrue(envContent.contains("BRAVE_SEARCH_API_KEY=BSA_test_secret_key"))
        assertTrue(envContent.contains("BRAVE_API_KEY=BSA_test_secret_key"))

        val yamlFile = File(hermesDir, "config.yaml")
        assertTrue(yamlFile.exists())
        val yamlContent = yamlFile.readText(Charsets.UTF_8)
        assertTrue(yamlContent.contains("search_provider: \"brave\""))
        assertTrue(yamlContent.contains("search_api_key: \"BSA_test_secret_key\""))

        // Assert POSIX 0600 permissions
        for (file in listOf(envFile, yamlFile)) {
            try {
                val perms = java.nio.file.Files.getPosixFilePermissions(file.toPath())
                assertEquals(
                    setOf(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                    ),
                    perms
                )
            } catch (_: UnsupportedOperationException) {
                assertTrue("File should be readable by owner", file.canRead())
                assertTrue("File should be writable by owner", file.canWrite())
                assertFalse("File should not be executable", file.canExecute())
            }
        }

        tempDir.deleteRecursively()
    }

    @Test
    fun createHermesDaemonConfig_whenWebSearchDisabled_doesNotInjectSearchEnvVars_evenWithKey() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_disabled_search_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val hermesConfig = HermesConfig(
            skills = SkillsConfig(
                webSearch = false,
                searchProvider = SkillsConfig.SEARCH_PROVIDER_BRAVE,
                searchApiKey = "BSA_valid_key_but_web_search_off"
            )
        )

        val config = ProcessConfig.createHermesDaemonConfig(
            filesDir = tempDir,
            hermesConfig = hermesConfig
        )

        assertFalse(config.environment.containsKey("BRAVE_SEARCH_API_KEY"))
        assertFalse(config.environment.containsKey("BRAVE_API_KEY"))
        assertFalse(config.environment.containsKey("HERMES_SEARCH_PROVIDER"))

        val envFile = File(File(tempDir, ".hermes"), ".env")
        if (envFile.exists()) {
            val envContent = envFile.readText(Charsets.UTF_8)
            assertFalse(envContent.contains("BRAVE_SEARCH_API_KEY"))
            assertFalse(envContent.contains("HERMES_SEARCH_PROVIDER"))
        }

        val yamlFile = File(File(tempDir, ".hermes"), "config.yaml")
        if (yamlFile.exists()) {
            val yamlContent = yamlFile.readText(Charsets.UTF_8)
            assertFalse(yamlContent.contains("search_api_key"))
            assertTrue(yamlContent.contains("web_search: false"))
        }

        tempDir.deleteRecursively()
    }

    @Test
    fun syncHermesConfig_preservesExistingEnvAndYamlLines_andPurgesObsoleteSearchKeys() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_sync_preserve_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val hermesDir = File(tempDir, ".hermes").apply { mkdirs() }

        // Prepopulate .env with custom variable and an old search provider
        val envFile = File(hermesDir, ".env")
        envFile.writeText(
            """
            CUSTOM_VAR=123
            BRAVE_SEARCH_API_KEY=old_brave_key
            HERMES_SEARCH_PROVIDER=brave
            ANOTHER_VAR=hello
            """.trimIndent() + "\n",
            Charsets.UTF_8
        )

        // Prepopulate config.yaml with custom settings and old web section
        val yamlFile = File(hermesDir, "config.yaml")
        yamlFile.writeText(
            """
            model: "claude-3-5-sonnet"
            temperature: 0.7
            web:
              provider: "brave"
              api_key: "old_brave_key"
            custom_flag: true
            """.trimIndent() + "\n",
            Charsets.UTF_8
        )

        // Sync with new provider: Tavily
        val newConfig = HermesConfig(
            skills = SkillsConfig(
                webSearch = true,
                searchProvider = SkillsConfig.SEARCH_PROVIDER_TAVILY,
                searchApiKey = "tvly-new-key-456"
            )
        )
        ProcessConfig.syncHermesConfig(tempDir, newConfig)

        // Verify .env: custom vars preserved, old brave keys purged, new tavily key present
        val envContent = envFile.readText(Charsets.UTF_8)
        assertTrue(envContent.contains("CUSTOM_VAR=123"))
        assertTrue(envContent.contains("ANOTHER_VAR=hello"))
        assertTrue(envContent.contains("HERMES_SEARCH_PROVIDER=tavily"))
        assertTrue(envContent.contains("TAVILY_API_KEY=tvly-new-key-456"))
        assertFalse(envContent.contains("BRAVE_SEARCH_API_KEY"))
        assertFalse(envContent.contains("old_brave_key"))

        // Verify config.yaml: non-search lines preserved, old web section replaced
        val yamlContent = yamlFile.readText(Charsets.UTF_8)
        assertTrue(yamlContent.contains("model: \"claude-3-5-sonnet\""))
        assertTrue(yamlContent.contains("temperature: 0.7"))
        assertTrue(yamlContent.contains("custom_flag: true"))
        assertTrue(yamlContent.contains("search_provider: \"tavily\""))
        assertTrue(yamlContent.contains("search_api_key: \"tvly-new-key-456\""))
        assertFalse(yamlContent.contains("old_brave_key"))

        // Verify POSIX 0600 permissions on envFile and yamlFile
        for (file in listOf(envFile, yamlFile)) {
            try {
                val perms = java.nio.file.Files.getPosixFilePermissions(file.toPath())
                assertEquals(
                    setOf(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                    ),
                    perms
                )
            } catch (_: UnsupportedOperationException) {
                assertTrue("File should be readable by owner", file.canRead())
                assertTrue("File should be writable by owner", file.canWrite())
                assertFalse("File should not be executable", file.canExecute())
            }
        }

        tempDir.deleteRecursively()
    }

    @Test
    fun createHermesDaemonConfig_injectsTavilySearchEnvVars() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_tavily_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val hermesConfig = HermesConfig(
            skills = SkillsConfig(
                webSearch = true,
                searchProvider = SkillsConfig.SEARCH_PROVIDER_TAVILY,
                searchApiKey = "tvly-test-12345"
            )
        )

        val config = ProcessConfig.createHermesDaemonConfig(
            filesDir = tempDir,
            hermesConfig = hermesConfig
        )

        assertEquals("tvly-test-12345", config.environment["TAVILY_API_KEY"])
        assertEquals("tavily", config.environment["HERMES_SEARCH_PROVIDER"])
        assertFalse(config.environment.containsKey("BRAVE_SEARCH_API_KEY"))

        val envFile = File(File(tempDir, ".hermes"), ".env")
        assertTrue(envFile.readText(Charsets.UTF_8).contains("TAVILY_API_KEY=tvly-test-12345"))

        tempDir.deleteRecursively()
    }

    @Test
    fun createHermesDaemonConfig_injectsFirecrawlAndExaSearchEnvVars() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_fc_exa_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val fcConfig = HermesConfig(
            skills = SkillsConfig(
                searchProvider = SkillsConfig.SEARCH_PROVIDER_FIRECRAWL,
                searchApiKey = "fc-key-1"
            )
        )
        val configFc = ProcessConfig.createHermesDaemonConfig(filesDir = tempDir, hermesConfig = fcConfig)
        assertEquals("fc-key-1", configFc.environment["FIRECRAWL_API_KEY"])
        assertEquals("firecrawl", configFc.environment["HERMES_SEARCH_PROVIDER"])

        val exaConfig = HermesConfig(
            skills = SkillsConfig(
                searchProvider = SkillsConfig.SEARCH_PROVIDER_EXA,
                searchApiKey = "exa-key-2"
            )
        )
        val configExa = ProcessConfig.createHermesDaemonConfig(filesDir = tempDir, hermesConfig = exaConfig)
        assertEquals("exa-key-2", configExa.environment["EXA_API_KEY"])
        assertEquals("exa", configExa.environment["HERMES_SEARCH_PROVIDER"])

        tempDir.deleteRecursively()
    }

    @Test
    fun createHermesDaemonConfig_whenSearchKeyBlank_doesNotInjectSearchEnvVars() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_blank_search_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val hermesConfig = HermesConfig(
            skills = SkillsConfig(
                webSearch = true,
                searchProvider = SkillsConfig.SEARCH_PROVIDER_BRAVE,
                searchApiKey = "   "
            )
        )

        val config = ProcessConfig.createHermesDaemonConfig(
            filesDir = tempDir,
            hermesConfig = hermesConfig
        )

        assertFalse(config.environment.containsKey("BRAVE_SEARCH_API_KEY"))
        assertFalse(config.environment.containsKey("BRAVE_API_KEY"))
        assertFalse(config.environment.containsKey("HERMES_SEARCH_PROVIDER"))

        tempDir.deleteRecursively()
    }

    @Test
    fun createHermesDaemonConfig_readsSearchConfigFromHermesJson() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_json_search_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val configFile = File(tempDir, "hermes.json")
        val serializer = ConfigSerializer(configFile)
        serializer.serialize(
            HermesConfig(
                skills = SkillsConfig(
                    searchProvider = SkillsConfig.SEARCH_PROVIDER_TAVILY,
                    searchApiKey = "tvly-from-json"
                )
            )
        )

        val config = ProcessConfig.createHermesDaemonConfig(filesDir = tempDir)

        assertEquals("tvly-from-json", config.environment["TAVILY_API_KEY"])
        assertEquals("tavily", config.environment["HERMES_SEARCH_PROVIDER"])

        tempDir.deleteRecursively()
    }

    @Test
    fun createHermesDaemonConfig_withSharedStorageEnabled_addsBindMounts() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_proot_storage_on_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val usrBin = File(tempDir, "usr/bin").apply { mkdirs() }
        File(usrBin, "proot").apply { writeText("dummy"); setExecutable(true) }
        File(usrBin, "hermes").apply { writeText("dummy"); setExecutable(true) }

        val mockDownloadDir = File(tempDir, "sdcard_download").apply { mkdirs() }

        val hermesConfig = HermesConfig(
            skills = SkillsConfig(sharedStorageEnabled = true)
        )

        val config = ProcessConfig.createHermesDaemonConfig(
            filesDir = tempDir,
            hermesConfig = hermesConfig,
            downloadDir = mockDownloadDir
        )

        val expectedSharedMount = "${File(tempDir, "shared").absolutePath}:/shared"
        val expectedDownloadMount = "${mockDownloadDir.absolutePath}:/sdcard/Download"

        assertTrue("Expected shared mount in proot arguments", config.arguments.contains(expectedSharedMount))
        assertTrue("Expected download mount in proot arguments", config.arguments.contains(expectedDownloadMount))

        // Verify bind flags (-b) precede the mounts
        val sharedIdx = config.arguments.indexOf(expectedSharedMount)
        assertTrue(sharedIdx > 0 && config.arguments[sharedIdx - 1] == "-b")

        val downloadIdx = config.arguments.indexOf(expectedDownloadMount)
        assertTrue(downloadIdx > 0 && config.arguments[downloadIdx - 1] == "-b")

        tempDir.deleteRecursively()
    }

    @Test
    fun createHermesDaemonConfig_withSharedStorageDisabled_omitsBindMounts() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_proot_storage_off_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val usrBin = File(tempDir, "usr/bin").apply { mkdirs() }
        File(usrBin, "proot").apply { writeText("dummy"); setExecutable(true) }
        File(usrBin, "hermes").apply { writeText("dummy"); setExecutable(true) }

        val mockDownloadDir = File(tempDir, "sdcard_download").apply { mkdirs() }

        val hermesConfig = HermesConfig(
            skills = SkillsConfig(sharedStorageEnabled = false)
        )

        val config = ProcessConfig.createHermesDaemonConfig(
            filesDir = tempDir,
            hermesConfig = hermesConfig,
            downloadDir = mockDownloadDir
        )

        val forbiddenShared = "${File(tempDir, "shared").absolutePath}:/shared"
        val forbiddenDownload = "${mockDownloadDir.absolutePath}:/sdcard/Download"

        assertFalse("Shared mount must not be present when disabled", config.arguments.contains(forbiddenShared))
        assertFalse("Download mount must not be present when disabled", config.arguments.contains(forbiddenDownload))
        assertFalse(config.arguments.any { it.contains("sdcard/Download") })

        tempDir.deleteRecursively()
    }

    @Test
    fun createHermesDaemonConfig_whenDownloadDirMissing_gracefullySkips() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_proot_storage_skip_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val usrBin = File(tempDir, "usr/bin").apply { mkdirs() }
        File(usrBin, "proot").apply { writeText("dummy"); setExecutable(true) }
        File(usrBin, "hermes").apply { writeText("dummy"); setExecutable(true) }

        val nonExistentDownloadDir = File(tempDir, "non_existent_path")

        val hermesConfig = HermesConfig(
            skills = SkillsConfig(sharedStorageEnabled = true)
        )

        val config = ProcessConfig.createHermesDaemonConfig(
            filesDir = tempDir,
            hermesConfig = hermesConfig,
            downloadDir = nonExistentDownloadDir
        )

        val expectedSharedMount = "${File(tempDir, "shared").absolutePath}:/shared"

        assertTrue("Shared mount must be present", config.arguments.contains(expectedSharedMount))
        assertFalse("Missing download dir must be gracefully omitted", config.arguments.any { it.contains("sdcard/Download") })

        tempDir.deleteRecursively()
    }

    @Test
    fun createHermesDaemonConfig_autoHealsMissingToolchainShims() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_autoheal_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val usrBin = File(tempDir, "usr/bin").apply { mkdirs() }

        val sudoShim = File(usrBin, "sudo")
        val aptShim = File(usrBin, "apt")
        val aptGetShim = File(usrBin, "apt-get")

        sudoShim.delete()
        aptShim.delete()
        aptGetShim.delete()
        assertFalse(sudoShim.exists())
        assertFalse(aptShim.exists())
        assertFalse(aptGetShim.exists())

        ProcessConfig.createHermesDaemonConfig(filesDir = tempDir)

        assertTrue("sudo shim should be auto-healed", sudoShim.exists() && sudoShim.canExecute())
        assertTrue("apt shim should be auto-healed", aptShim.exists() && aptShim.canExecute())
        assertTrue("apt-get shim should be auto-healed", aptGetShim.exists() && aptGetShim.canExecute())

        tempDir.deleteRecursively()
    }

    @Test
    fun extractPid_extractsPidProperly() {
        val controller = ProcessController(ioDispatcher = testDispatcher)
        val fakeProcess = FakeProcess(fakePid = 6789L)
        val pid = controller.extractPid(fakeProcess)
        assertEquals(6789L, pid)
    }

    @Test
    fun defaultProcessRunner_instantiationAndCommandBuilding() {
        val runner = DefaultProcessRunner()
        assertNotNull(runner)
        val config = ProcessConfig(
            executable = "echo",
            arguments = listOf("hello", "world"),
            environment = mapOf("TEST" to "true"),
            redirectErrorStream = true
        )
        assertEquals(listOf("echo", "hello", "world"), config.fullCommand)
    }

    @Test
    fun start_whenProcessRunnerFails_setsLastErrorMessageWithDiagnostics() = runTest(testDispatcher) {
        val failingRunner = object : ProcessRunner {
            override fun run(config: ProcessConfig): Process {
                throw java.io.IOException("Cannot run program \"${config.executable}\": error=2, No such file or directory")
            }
        }
        val controller = ProcessController(processRunner = failingRunner, ioDispatcher = testDispatcher)
        val config = ProcessConfig(executable = "/non/existent/path/python3")

        val result = controller.start(config)

        assertTrue(result.isFailure)
        assertEquals(ProcessState.ERROR, controller.state.value)
        assertNotNull(controller.lastErrorMessage)
        assertTrue(controller.lastErrorMessage?.contains("Failed to launch '/non/existent/path/python3'") == true)
        assertTrue(controller.lastErrorMessage?.contains("file exists=false") == true)
    }

    @Test
    fun resolveExecutableCommand_whenScriptHasShebang_handlesCommandResolution() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "script_test_${System.currentTimeMillis()}").apply { mkdirs() }
        val script = File(tempDir, "mock_script.sh").apply {
            writeText("#!/non/existent/interpreter/sh\necho test\n")
            setExecutable(true)
        }

        val config = ProcessConfig(
            executable = script.absolutePath,
            arguments = listOf("arg1", "arg2")
        )

        val resolved = DefaultProcessRunner.resolveExecutableCommand(config)
        assertNotNull(resolved)
        assertTrue(resolved.isNotEmpty())

        tempDir.deleteRecursively()
    }

    private class FakeProcessRunner(private val process: Process) : ProcessRunner {
        var lastExecutedConfig: ProcessConfig? = null

        override fun run(config: ProcessConfig): Process {
            lastExecutedConfig = config
            return process
        }
    }

    private class FakeProcess(
        private val fakePid: Long = 1234L,
        private val inStream: InputStream = ByteArrayInputStream("test output\n".toByteArray()),
        private val errStream: InputStream = ByteArrayInputStream("test error\n".toByteArray()),
        private val outStream: OutputStream = ByteArrayOutputStream(),
        var exitCodeValue: Int = 0,
        var ignoreSigterm: Boolean = false
    ) : Process() {
        private var _isAlive = true
        private val exitLatch = java.util.concurrent.CountDownLatch(1)
        var destroyCalled = false
        var destroyForciblyCalled = false

        override fun getOutputStream(): OutputStream = outStream
        override fun getInputStream(): InputStream = inStream
        override fun getErrorStream(): InputStream = errStream

        override fun waitFor(): Int {
            try {
                exitLatch.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return exitCodeValue
        }

        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean {
            return try {
                exitLatch.await(timeout, unit)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                !_isAlive
            }
        }

        override fun exitValue(): Int {
            if (_isAlive) throw IllegalThreadStateException("Process is still running")
            return exitCodeValue
        }

        override fun destroy() {
            destroyCalled = true
            if (!ignoreSigterm) {
                _isAlive = false
                exitLatch.countDown()
            }
        }

        override fun destroyForcibly(): Process {
            destroyForciblyCalled = true
            _isAlive = false
            exitLatch.countDown()
            return this
        }

        fun simulateUnexpectedExit(code: Int) {
            exitCodeValue = code
            _isAlive = false
            exitLatch.countDown()
        }

        fun pid(): Long = fakePid
    }
}
