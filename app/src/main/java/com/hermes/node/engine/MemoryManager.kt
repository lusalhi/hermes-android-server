package com.hermes.node.engine

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Interface defining episodic memory inspection, backup archiving, downloads export,
 * share sheet integration, and factory reset capabilities.
 */
interface MemoryManagerInterface {
    fun calculateStorageUsage(): Long
    fun formatStorageSize(bytes: Long): String
    fun exportMemoryBackup(destinationZipFile: File? = null): Result<File>
    fun exportToDownloads(): Result<String>
    fun getShareIntent(): Result<Intent>
    fun clearEpisodicMemory(): Result<Boolean>
}

/**
 * Manages episodic memory inspection, ZIP backups, and factory reset for Hermes Agent.
 *
 * Inspects:
 * - filesDir/agent_data
 * - filesDir/data
 * - filesDir/checkpoints
 * - Any *.db, *.sqlite, *.sqlite3, *.db-wal, *.db-shm files in filesDir
 *
 * Strictly Preserves:
 * - filesDir/hermes.json
 * - filesDir/usr/
 * - filesDir/.bootstrap_completed
 * - Android Keystore / EncryptedSharedPreferences
 */
class MemoryManager(
    private val context: Context? = null,
    private val filesDir: File = context?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_files"),
    private val cacheDir: File = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_cache")
) : MemoryManagerInterface {

    companion object {
        private const val TAG = "MemoryManager"
        private const val BACKUP_DIR_NAME = "backups"
        private const val DOWNLOAD_SUBDIR = "HermesNode"

        private val PRESERVED_NAMES = setOf(
            "hermes.json",
            "usr",
            ".bootstrap_completed",
            "shared_prefs"
        )

        private val DB_EXTENSIONS = listOf(
            ".db",
            ".sqlite",
            ".sqlite3",
            ".db-wal",
            ".db-shm",
            ".sqlite-wal",
            ".sqlite-shm",
            ".db-journal",
            ".sqlite-journal",
            ".sqlite3-journal",
            ".sqlite3-wal",
            ".sqlite3-shm"
        )

        private val MEMORY_DIR_NAMES = setOf(
            "agent_data",
            "data",
            "checkpoints"
        )
    }

    private fun logWarn(message: String, throwable: Throwable? = null) {
        try {
            Log.w(TAG, message, throwable)
        } catch (_: Throwable) {
            System.err.println("[$TAG WARN] $message")
        }
    }

    private fun isDatabaseFile(file: File): Boolean {
        if (file.name in PRESERVED_NAMES) return false
        val lowerName = file.name.lowercase(Locale.US)
        return DB_EXTENSIONS.any { lowerName.endsWith(it) }
    }

    /**
     * Resolves the top-level episodic memory targets located in [filesDir].
     * Preserves critical system configuration and runtime assets.
     */
    fun getEpisodicMemoryRoots(): List<File> {
        if (!filesDir.exists() || !filesDir.isDirectory) return emptyList()

        val roots = mutableListOf<File>()
        for (dirName in MEMORY_DIR_NAMES) {
            val dir = File(filesDir, dirName)
            if (dir.exists()) {
                roots.add(dir)
            }
        }

        val topLevel = filesDir.listFiles() ?: emptyArray()
        for (file in topLevel) {
            if (file.isFile && isDatabaseFile(file)) {
                roots.add(file)
            }
        }
        return roots
    }

    override fun calculateStorageUsage(): Long {
        var totalBytes = 0L
        val roots = getEpisodicMemoryRoots()
        for (item in roots) {
            try {
                if (item.isDirectory) {
                    item.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            try {
                                totalBytes += file.length()
                            } catch (e: Exception) {
                                logWarn("Unable to read file length: ${file.absolutePath}", e)
                            }
                        }
                    }
                } else if (item.isFile) {
                    totalBytes += item.length()
                }
            } catch (e: Exception) {
                logWarn("Unable to access storage item: ${item.absolutePath}", e)
            }
        }
        return totalBytes
    }

    override fun formatStorageSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.1f GB", gb)
    }

    override fun exportMemoryBackup(destinationZipFile: File?): Result<File> {
        var targetFile: File? = null
        var stagingFile: File? = null
        return try {
            val fileToCreate = destinationZipFile ?: run {
                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val backupDir = File(cacheDir, BACKUP_DIR_NAME)
                if (!backupDir.exists()) backupDir.mkdirs()
                purgeStaleBackupArchives(backupDir)
                File(backupDir, "hermes-backup-$timestamp.zip")
            }
            targetFile = fileToCreate

            fileToCreate.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }

            // Write to a temporary staging file and atomically rename it into place so an
            // interrupted write never leaves a corrupt archive at the destination path.
            val temp = File(fileToCreate.parentFile, "${fileToCreate.name}.tmp")
            stagingFile = temp

            val roots = getEpisodicMemoryRoots()
            var entryCount = 0

            ZipOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { zipOut ->
                for (item in roots) {
                    if (item.isDirectory) {
                        val files = item.walkTopDown().filter { it.isFile }
                        for (file in files) {
                            val relativePath = file.relativeTo(filesDir).path.replace('\\', '/')
                            val zipEntry = ZipEntry(relativePath).apply {
                                time = file.lastModified()
                            }
                            zipOut.putNextEntry(zipEntry)
                            FileInputStream(file).use { input ->
                                input.copyTo(zipOut)
                            }
                            zipOut.closeEntry()
                            entryCount++
                        }
                    } else if (item.isFile) {
                        val relativePath = item.name
                        val zipEntry = ZipEntry(relativePath).apply {
                            time = item.lastModified()
                        }
                        zipOut.putNextEntry(zipEntry)
                        FileInputStream(item).use { input ->
                            input.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                        entryCount++
                    }
                }

                // If no memory files were found, write a manifest notice to ensure a valid non-empty archive
                if (entryCount == 0) {
                    val manifestEntry = ZipEntry("manifest.txt")
                    zipOut.putNextEntry(manifestEntry)
                    val notice = "Hermes Node Episodic Memory Backup\n" +
                            "Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n" +
                            "Notice: No episodic memory checkpoints or SQLite databases found (0 B).\n"
                    zipOut.write(notice.toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()
                }
            }

            if (fileToCreate.exists()) fileToCreate.delete()
            if (!temp.renameTo(fileToCreate)) {
                temp.copyTo(fileToCreate, overwrite = true)
                temp.delete()
            }

            Result.success(fileToCreate)
        } catch (e: Exception) {
            logWarn("Failed to create memory backup ZIP archive", e)
            targetFile?.let {
                try {
                    if (it.exists()) it.delete()
                } catch (_: Exception) {}
            }
            stagingFile?.let {
                try {
                    if (it.exists()) it.delete()
                } catch (_: Exception) {}
            }
            Result.failure(e)
        }
    }

    /**
     * Purges previously staged backup archives so sensitive conversation ZIPs do not
     * accumulate indefinitely in the cache directory. Runs before staging a new archive,
     * so the newest archive (handed to share-sheet recipients) is always the one kept.
     */
    private fun purgeStaleBackupArchives(backupDir: File) {
        try {
            val staleArchives = backupDir.listFiles { file ->
                file.isFile && file.name.startsWith("hermes-backup-") && file.name.endsWith(".zip")
            } ?: return
            for (archive in staleArchives) {
                try {
                    archive.delete()
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            logWarn("Unable to purge stale backup archives from cache", e)
        }
    }

    override fun exportToDownloads(): Result<String> {
        val tempBackupResult = exportMemoryBackup()
        if (tempBackupResult.isFailure) {
            return Result.failure(tempBackupResult.exceptionOrNull() ?: IOException("Failed to create backup archive"))
        }

        val tempZip = tempBackupResult.getOrThrow()
        val fileName = tempZip.name

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && context != null) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIR")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw IOException("Failed to create MediaStore entry for $fileName in Downloads")

                try {
                    resolver.openOutputStream(uri)?.use { outStream ->
                        FileInputStream(tempZip).use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    } ?: throw IOException("Failed to write backup stream to MediaStore")

                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    val finalizedRows = resolver.update(uri, contentValues, null, null)
                    if (finalizedRows != 1) {
                        try {
                            resolver.delete(uri, null, null)
                        } catch (_: Exception) {}
                        throw IOException("Failed to finalize MediaStore entry for $fileName in Downloads")
                    }
                } catch (writeException: Exception) {
                    try {
                        resolver.delete(uri, null, null)
                    } catch (_: Exception) {}
                    throw writeException
                }

                return Result.success("Backup exported to Downloads/$DOWNLOAD_SUBDIR/$fileName")
            } else {
                // API 28 or direct filesystem fallback
                val publicDownloads = try {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                } catch (_: Throwable) {
                    null
                }
                // Fallback only for environments where the public directory cannot be resolved
                // (e.g. JVM unit tests); production API 28 resolves the real public Downloads.
                val baseDownloads = publicDownloads ?: File(filesDir.parentFile ?: filesDir, "Downloads")

                val targetDir = File(baseDownloads, DOWNLOAD_SUBDIR)
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val destFile = File(targetDir, fileName)
                tempZip.copyTo(destFile, overwrite = true)

                // Report the actual destination so a fallback location is never
                // misreported as the public Downloads directory.
                return Result.success("Backup exported to ${destFile.parentFile?.path ?: "Downloads"}/$fileName")
            }
        } catch (e: Exception) {
            logWarn("Failed to export backup to Downloads", e)
            return Result.failure(e)
        } finally {
            try {
                if (tempZip.exists()) {
                    tempZip.delete()
                }
            } catch (_: Exception) {}
        }
    }

    override fun getShareIntent(): Result<Intent> {
        // Validate Android context before staging so a misconfigured manager never
        // leaves an orphaned archive in the cache directory.
        val ctx = context
            ?: return Result.failure(IllegalStateException("Android Context required to generate Share Sheet intent"))
        return try {
            val backupResult = exportMemoryBackup()
            if (backupResult.isFailure) {
                return Result.failure(backupResult.exceptionOrNull() ?: IOException("Failed to create backup archive for sharing"))
            }

            val zipFile = backupResult.getOrThrow()
            try {
                val authority = "${ctx.packageName}.fileprovider"
                val contentUri: Uri = FileProvider.getUriForFile(ctx, authority, zipFile)

                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    putExtra(Intent.EXTRA_SUBJECT, "Hermes Node Episodic Memory Backup")
                    clipData = ClipData.newRawUri("Hermes Backup", contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooserIntent = Intent.createChooser(sendIntent, "Share Hermes Backup").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                Result.success(chooserIntent)
            } catch (intentException: Exception) {
                // The staged archive was never handed to a recipient, so clean it up.
                try {
                    if (zipFile.exists()) zipFile.delete()
                } catch (_: Exception) {}
                throw intentException
            }
        } catch (e: Exception) {
            logWarn("Failed to generate share intent for memory backup", e)
            Result.failure(e)
        }
    }

    override fun clearEpisodicMemory(): Result<Boolean> {
        return try {
            val roots = getEpisodicMemoryRoots()
            var anyDeletionError = false

            for (item in roots) {
                if (item.exists()) {
                    val deleted = if (item.isDirectory) {
                        item.deleteRecursively()
                    } else {
                        item.delete()
                    }
                    if (!deleted) {
                        anyDeletionError = true
                        logWarn("Failed to delete memory item: ${item.absolutePath}")
                    }
                }
            }

            if (anyDeletionError) {
                Result.failure(IOException("Some episodic memory files could not be deleted"))
            } else {
                Result.success(true)
            }
        } catch (e: Exception) {
            logWarn("Error during episodic memory factory reset", e)
            Result.failure(e)
        }
    }
}
