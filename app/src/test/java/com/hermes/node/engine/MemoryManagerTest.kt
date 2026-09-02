package com.hermes.node.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class MemoryManagerTest {

    private lateinit var tempFilesDir: File
    private lateinit var tempCacheDir: File
    private lateinit var memoryManager: MemoryManager

    @Before
    fun setUp() {
        val baseTmp = System.getProperty("java.io.tmpdir") ?: "/tmp"
        val timestamp = System.currentTimeMillis()
        tempFilesDir = File(baseTmp, "hermes_mem_files_$timestamp")
        tempCacheDir = File(baseTmp, "hermes_mem_cache_$timestamp")
        tempFilesDir.mkdirs()
        tempCacheDir.mkdirs()

        memoryManager = MemoryManager(
            context = null,
            filesDir = tempFilesDir,
            cacheDir = tempCacheDir
        )
    }

    @After
    fun tearDown() {
        tempFilesDir.deleteRecursively()
        tempCacheDir.deleteRecursively()
    }

    @Test
    fun calculateStorageUsage_whenNoFilesExist_returnsZero() {
        val bytes = memoryManager.calculateStorageUsage()
        assertEquals(0L, bytes)
        assertEquals("0 B", memoryManager.formatStorageSize(bytes))
    }

    @Test
    fun calculateStorageUsage_withDatabasesAndCheckpoints_returnsSumOfBytes() {
        // Create agent_data directory with database
        val agentDataDir = File(tempFilesDir, "agent_data")
        agentDataDir.mkdirs()
        val agentDb = File(agentDataDir, "state.db")
        agentDb.writeBytes(ByteArray(1024)) // 1 KB

        // Create checkpoints directory with checkpoint files
        val checkpointsDir = File(tempFilesDir, "checkpoints")
        checkpointsDir.mkdirs()
        val ckptFile = File(checkpointsDir, "session_1.json")
        ckptFile.writeBytes(ByteArray(2048)) // 2 KB

        // Create top-level sqlite files in filesDir
        val topDb = File(tempFilesDir, "hermes_chat.sqlite")
        topDb.writeBytes(ByteArray(4096)) // 4 KB
        val topWal = File(tempFilesDir, "hermes_chat.db-wal")
        topWal.writeBytes(ByteArray(512)) // 512 B

        val totalExpected = 1024L + 2048L + 4096L + 512L
        val bytes = memoryManager.calculateStorageUsage()
        assertEquals(totalExpected, bytes)
    }

    @Test
    fun calculateStorageUsage_ignoresNonMemoryFiles_suchAsHermesJsonAndUsr() {
        // Create config file (MUST BE PRESERVED AND NOT COUNTED)
        val configFile = File(tempFilesDir, "hermes.json")
        configFile.writeText("{\"apiKey\": \"secret\"}")

        // Create userland file (MUST BE PRESERVED AND NOT COUNTED)
        val usrDir = File(tempFilesDir, "usr/bin")
        usrDir.mkdirs()
        val pythonBin = File(usrDir, "python3")
        pythonBin.writeBytes(ByteArray(50000))

        // Create bootstrap marker (MUST BE PRESERVED AND NOT COUNTED)
        val marker = File(tempFilesDir, ".bootstrap_completed")
        marker.writeText("ok")

        // Create memory file
        val dataDir = File(tempFilesDir, "data")
        dataDir.mkdirs()
        val memDb = File(dataDir, "memory.sqlite3")
        memDb.writeBytes(ByteArray(3000))

        val bytes = memoryManager.calculateStorageUsage()
        assertEquals(3000L, bytes)
    }

    @Test
    fun formatStorageSize_formatsVariousSizesCorrectly() {
        assertEquals("0 B", memoryManager.formatStorageSize(0L))
        assertEquals("0 B", memoryManager.formatStorageSize(-10L))
        assertEquals("512 B", memoryManager.formatStorageSize(512L))
        assertEquals("1.0 KB", memoryManager.formatStorageSize(1024L))
        assertEquals("4.2 MB", memoryManager.formatStorageSize((4.2 * 1024 * 1024).toLong()))
        assertEquals("14.2 MB", memoryManager.formatStorageSize((14.2 * 1024 * 1024).toLong()))
        assertEquals("1.5 GB", memoryManager.formatStorageSize((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun exportMemoryBackup_createsValidZipFileWithMemoryFiles() {
        // Set up episodic memory files
        val agentDataDir = File(tempFilesDir, "agent_data")
        agentDataDir.mkdirs()
        val db1 = File(agentDataDir, "agent.db")
        db1.writeText("agent-db-contents")

        val ckptDir = File(tempFilesDir, "checkpoints")
        ckptDir.mkdirs()
        val ckpt1 = File(ckptDir, "step1.chk")
        ckpt1.writeText("step1-checkpoint-data")

        val topDb = File(tempFilesDir, "conversations.sqlite")
        topDb.writeText("conversations-sqlite-data")

        // Excluded files
        val config = File(tempFilesDir, "hermes.json")
        config.writeText("sensitive-config")

        val result = memoryManager.exportMemoryBackup()
        assertTrue(result.isSuccess)
        val zipFile = result.getOrThrow()
        assertTrue(zipFile.exists())
        assertTrue(zipFile.length() > 0)

        // Inspect ZIP entries
        val zip = ZipFile(zipFile)
        val entries = zip.entries().asSequence().map { it.name }.toSet()

        assertTrue(entries.contains("agent_data/agent.db"))
        assertTrue(entries.contains("checkpoints/step1.chk"))
        assertTrue(entries.contains("conversations.sqlite"))
        assertFalse(entries.contains("hermes.json"))
        zip.close()
    }

    @Test
    fun exportMemoryBackup_whenEmpty_createsValidZipWithManifest() {
        val result = memoryManager.exportMemoryBackup()
        assertTrue(result.isSuccess)
        val zipFile = result.getOrThrow()
        assertTrue(zipFile.exists())

        val zip = ZipFile(zipFile)
        val entries = zip.entries().asSequence().map { it.name }.toList()
        assertEquals(1, entries.size)
        assertEquals("manifest.txt", entries[0])

        val content = zip.getInputStream(zip.getEntry("manifest.txt")).bufferedReader().readText()
        assertTrue(content.contains("Hermes Node Episodic Memory Backup"))
        assertTrue(content.contains("0 B"))
        zip.close()
    }

    @Test
    fun clearEpisodicMemory_deletesDbFilesAndCheckpointDirs() {
        val agentDataDir = File(tempFilesDir, "agent_data")
        agentDataDir.mkdirs()
        File(agentDataDir, "state.db").writeText("data")

        val dataDir = File(tempFilesDir, "data")
        dataDir.mkdirs()
        File(dataDir, "records.sqlite").writeText("data")

        val ckptDir = File(tempFilesDir, "checkpoints")
        ckptDir.mkdirs()
        File(ckptDir, "ckpt.bin").writeText("data")

        val topDb = File(tempFilesDir, "test.db")
        topDb.writeText("db")
        val topWal = File(tempFilesDir, "test.db-wal")
        topWal.writeText("wal")

        assertTrue(agentDataDir.exists())
        assertTrue(dataDir.exists())
        assertTrue(ckptDir.exists())
        assertTrue(topDb.exists())
        assertTrue(topWal.exists())

        val result = memoryManager.clearEpisodicMemory()
        assertTrue(result.isSuccess)

        assertFalse(agentDataDir.exists())
        assertFalse(dataDir.exists())
        assertFalse(ckptDir.exists())
        assertFalse(topDb.exists())
        assertFalse(topWal.exists())
        assertEquals(0L, memoryManager.calculateStorageUsage())
    }

    @Test
    fun clearEpisodicMemory_strictlyPreservesHermesJsonAndUsrAndBootstrapCompleted() {
        // Setup preserved files
        val configFile = File(tempFilesDir, "hermes.json")
        configFile.writeText("{\"apiKey\": \"secret-key-12345\"}")

        val usrDir = File(tempFilesDir, "usr/bin")
        usrDir.mkdirs()
        val pythonBin = File(usrDir, "python3")
        pythonBin.writeText("#!/bin/sh\necho python")

        val marker = File(tempFilesDir, ".bootstrap_completed")
        marker.writeText("1")

        // Setup memory files
        val agentDataDir = File(tempFilesDir, "agent_data")
        agentDataDir.mkdirs()
        File(agentDataDir, "agent.db").writeText("agent-db")

        val topSqlite = File(tempFilesDir, "chat.sqlite3")
        topSqlite.writeText("sqlite-data")

        val clearResult = memoryManager.clearEpisodicMemory()
        assertTrue(clearResult.isSuccess)

        // Preserved files must still exist with intact content
        assertTrue(configFile.exists())
        assertEquals("{\"apiKey\": \"secret-key-12345\"}", configFile.readText())

        assertTrue(pythonBin.exists())
        assertEquals("#!/bin/sh\necho python", pythonBin.readText())

        assertTrue(marker.exists())
        assertEquals("1", marker.readText())

        // Memory files must be deleted
        assertFalse(agentDataDir.exists())
        assertFalse(topSqlite.exists())
        assertEquals(0L, memoryManager.calculateStorageUsage())
    }

    @Test
    fun exportToDownloads_createsBackupInDownloadsDirectory() {
        val agentDataDir = File(tempFilesDir, "agent_data")
        agentDataDir.mkdirs()
        File(agentDataDir, "agent.db").writeText("download-test-data")

        val result = memoryManager.exportToDownloads()
        assertTrue(result.isSuccess)
        val message = result.getOrThrow()
        assertTrue(message.contains("HermesNode"))

        val downloadsDir = File(tempFilesDir.parentFile ?: tempFilesDir, "Downloads/HermesNode")
        assertTrue(downloadsDir.exists())
        val files = downloadsDir.listFiles() ?: emptyArray()
        assertTrue(files.any { it.name.startsWith("hermes-backup-") && it.name.endsWith(".zip") })

        downloadsDir.parentFile?.deleteRecursively()
    }

    @Test
    fun getShareIntent_withoutContext_returnsFailure() {
        val result = memoryManager.getShareIntent()
        assertFalse(result.isSuccess)
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun calculateStorageUsage_includesAllJournalAndWalVariants() {
        File(tempFilesDir, "main.sqlite3").writeBytes(ByteArray(100))
        File(tempFilesDir, "main.sqlite3-wal").writeBytes(ByteArray(200))
        File(tempFilesDir, "main.sqlite3-shm").writeBytes(ByteArray(300))
        File(tempFilesDir, "legacy.db-journal").writeBytes(ByteArray(400))
        File(tempFilesDir, "other.sqlite-journal").writeBytes(ByteArray(500))
        File(tempFilesDir, "custom.sqlite3-journal").writeBytes(ByteArray(600))

        val expected = 100L + 200L + 300L + 400L + 500L + 600L
        val bytes = memoryManager.calculateStorageUsage()
        assertEquals(expected, bytes)
    }

    @Test
    fun exportToDownloads_cleansUpTempStagingZipInCacheDir() {
        val agentDataDir = File(tempFilesDir, "agent_data")
        agentDataDir.mkdirs()
        File(agentDataDir, "test.db").writeText("cleanup-test-data")

        val result = memoryManager.exportToDownloads()
        assertTrue(result.isSuccess)

        val stagingBackupsDir = File(tempCacheDir, "backups")
        if (stagingBackupsDir.exists()) {
            val remainingZips = stagingBackupsDir.listFiles() ?: emptyArray()
            assertTrue("Expected staging ZIPs to be deleted, found: ${remainingZips.map { it.name }}", remainingZips.isEmpty())
        }
    }

    @Test
    fun constructor_withoutContext_fallsBackToJvmTempDir() {
        val defaultManager = MemoryManager()
        val bytes = defaultManager.calculateStorageUsage()
        assertTrue(bytes >= 0L)
    }

    @Test
    fun exportMemoryBackup_purgesStaleShareArchivesBeforeStaging() {
        val backupsDir = File(tempCacheDir, "backups")
        backupsDir.mkdirs()
        val staleArchive = File(backupsDir, "hermes-backup-20200101-000000.zip")
        staleArchive.writeText("stale-archive")

        val agentDataDir = File(tempFilesDir, "agent_data")
        agentDataDir.mkdirs()
        File(agentDataDir, "purge.db").writeText("purge-test-data")

        val result = memoryManager.exportMemoryBackup()

        assertTrue(result.isSuccess)
        assertFalse("Stale archive should have been purged", staleArchive.exists())
        val remaining = backupsDir.listFiles() ?: emptyArray()
        assertTrue("New archive should be staged", remaining.isNotEmpty())
        remaining.forEach { assertTrue(it.name.endsWith(".zip")) }
    }

    @Test
    fun exportMemoryBackup_writesAtomically_leavesNoTempResidue() {
        val agentDataDir = File(tempFilesDir, "agent_data")
        agentDataDir.mkdirs()
        File(agentDataDir, "atomic.db").writeText("atomic-write-data")

        val result = memoryManager.exportMemoryBackup()

        assertTrue(result.isSuccess)
        val archive = result.getOrThrow()
        assertTrue(archive.exists())
        val parentFiles = archive.parentFile?.listFiles() ?: emptyArray()
        assertTrue(
            "Unexpected temp residue: ${parentFiles.map { it.name }}",
            parentFiles.none { it.name.endsWith(".tmp") }
        )
        ZipFile(archive).use { zip ->
            assertNotNull(zip.getEntry("agent_data/atomic.db"))
        }
    }

    @Test
    fun exportToDownloads_reportsActualExportDestinationInMessage() {
        val agentDataDir = File(tempFilesDir, "agent_data")
        agentDataDir.mkdirs()
        File(agentDataDir, "message.db").writeText("message-test-data")

        val result = memoryManager.exportToDownloads()

        assertTrue(result.isSuccess)
        val message = result.getOrThrow()
        val downloadsDir = File(tempFilesDir.parentFile ?: tempFilesDir, "Downloads/HermesNode")
        assertTrue(
            "Message should contain the actual destination path: $message",
            message.contains(downloadsDir.path)
        )
    }

    @Test
    fun getShareIntent_withoutContext_returnsFailureWithoutStagingArchive() {
        val result = memoryManager.getShareIntent()

        assertFalse(result.isSuccess)
        assertNotNull(result.exceptionOrNull())
        val backupsDir = File(tempCacheDir, "backups")
        if (backupsDir.exists()) {
            val staged = backupsDir.listFiles() ?: emptyArray()
            assertTrue("No archive should be staged when context is missing", staged.isEmpty())
        }
    }
}
