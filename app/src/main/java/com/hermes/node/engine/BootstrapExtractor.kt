package com.hermes.node.engine

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.coroutines.coroutineContext

sealed class ExtractionResult {
    data class Success(val installedPath: File) : ExtractionResult()
    data class Error(val message: String, val cause: Throwable? = null) : ExtractionResult()
}

sealed class HealthCheckResult {
    data object Healthy : HealthCheckResult()
    data object NotInstalled : HealthCheckResult()
    data class Corrupted(
        val issues: List<String>,
        val details: String = issues.joinToString("; ")
    ) : HealthCheckResult()
}

open class BootstrapExtractor(
    val filesDir: File,
    private val assetManager: AssetManager? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        filesDir = context.filesDir,
        assetManager = context.assets,
        ioDispatcher = ioDispatcher
    )

    companion object {
        const val BOOTSTRAP_ASSET_NAME = "bootstrap-arm64.tar.xz"
        const val MARKER_FILE_NAME = ".bootstrap_complete"
        const val USR_DIR_NAME = "usr"
        const val BOOTSTRAP_VERSION = 1
        private const val MIN_REQUIRED_DISK_BYTES = 20L * 1024 * 1024 // 20 MB

        val CRITICAL_BINARIES = listOf(
            "bin/python3",
            "bin/proot",
            "bin/hermes"
        )
    }

    val usrDir: File
        get() = File(filesDir, USR_DIR_NAME)

    val markerFile: File
        get() = File(filesDir, MARKER_FILE_NAME)

    /**
     * Diagnostic verification of runtime userland environment.
     * Verifies presence of marker file, version alignment, usr directory existence,
     * and presence + executable POSIX permissions on all critical binaries.
     */
    open fun checkHealth(): HealthCheckResult {
        val markerExists = markerFile.exists()
        val usrExists = usrDir.exists() && usrDir.isDirectory
        val usrHasEntries = usrExists && !(usrDir.list().isNullOrEmpty())

        // If neither marker nor non-empty usr directory exists, it is a fresh uninstalled state
        if (!markerExists && !usrHasEntries) {
            return HealthCheckResult.NotInstalled
        }

        val issues = mutableListOf<String>()

        // 1. Validate Marker File
        if (!markerExists) {
            issues.add("Bootstrap marker file (.bootstrap_complete) is missing")
        } else {
            try {
                val markerContent = markerFile.readText()
                val hasValidVersion = markerContent.lines().any { line ->
                    val trimmed = line.trim()
                    trimmed == "version=$BOOTSTRAP_VERSION" || trimmed == "VERSION=$BOOTSTRAP_VERSION"
                }
                if (!hasValidVersion) {
                    issues.add("Bootstrap version mismatch (expected version=$BOOTSTRAP_VERSION)")
                }
            } catch (e: Exception) {
                issues.add("Failed to read bootstrap marker file: ${e.message}")
            }
        }

        // 2. Validate usr directory
        if (!usrExists) {
            issues.add("Userland directory ($USR_DIR_NAME) is missing or not a directory")
        } else {
            // 3. Validate critical binaries
            for (binRelPath in CRITICAL_BINARIES) {
                val binFile = File(usrDir, binRelPath)
                if (!binFile.exists()) {
                    issues.add("Missing critical binary: $binRelPath")
                } else if (!binFile.isFile) {
                    issues.add("Critical binary is not a regular file: $binRelPath")
                } else if (!binFile.canExecute()) {
                    // Try to restore execute permission
                    binFile.setExecutable(true, false)
                    if (!binFile.canExecute()) {
                        issues.add("Critical binary is not executable: $binRelPath")
                    }
                }
            }
        }

        return if (issues.isEmpty()) {
            HealthCheckResult.Healthy
        } else {
            HealthCheckResult.Corrupted(issues = issues, details = issues.joinToString("; "))
        }
    }

    /**
     * Checks if the ARM64 PRoot userland environment is properly installed and healthy.
     */
    open fun isBootstrapInstalled(): Boolean {
        return checkHealth() is HealthCheckResult.Healthy
    }

    /**
     * Performs a clean re-installation of the ARM64 userland environment.
     * Safely purges only the usr/ directory and marker file, strictly preserving
     * all user databases, checkpoints, configs, and other files in filesDir.
     */
    open suspend fun repair(
        assetName: String = BOOTSTRAP_ASSET_NAME,
        onProgress: ((progress: Float, message: String) -> Unit)? = null
    ): ExtractionResult = withContext(ioDispatcher) {
        try {
            // Pre-clean validation: Disk space check
            val usableSpace = filesDir.usableSpace
            if (usableSpace in 1L until MIN_REQUIRED_DISK_BYTES) {
                return@withContext ExtractionResult.Error(
                    "Insufficient disk space: ${usableSpace / (1024 * 1024)} MB available, required at least ${MIN_REQUIRED_DISK_BYTES / (1024 * 1024)} MB"
                )
            }

            if (assetManager == null) {
                return@withContext ExtractionResult.Error("AssetManager is null; cannot extract asset '$assetName'")
            }

            val inputStream = try {
                assetManager.open(assetName)
            } catch (e: Exception) {
                return@withContext ExtractionResult.Error(
                    "Bootstrap archive '$assetName' not found in assets",
                    e
                )
            }

            onProgress?.invoke(0.02f, "Cleaning corrupted runtime userland...")
            if (!cleanUserland()) {
                return@withContext ExtractionResult.Error("Failed to cleanly purge previous userland directory")
            }
            extractFromStream(inputStream, onProgress)
        } catch (e: Exception) {
            ExtractionResult.Error("Runtime repair failed: ${e.message}", e)
        }
    }

    open suspend fun repairFromStream(
        inputStream: InputStream,
        onProgress: ((progress: Float, message: String) -> Unit)? = null
    ): ExtractionResult = withContext(ioDispatcher) {
        try {
            val usableSpace = filesDir.usableSpace
            if (usableSpace in 1L until MIN_REQUIRED_DISK_BYTES) {
                return@withContext ExtractionResult.Error(
                    "Insufficient disk space: ${usableSpace / (1024 * 1024)} MB available, required at least ${MIN_REQUIRED_DISK_BYTES / (1024 * 1024)} MB"
                )
            }

            onProgress?.invoke(0.02f, "Cleaning corrupted runtime userland...")
            if (!cleanUserland()) {
                return@withContext ExtractionResult.Error("Failed to cleanly purge previous userland directory")
            }
            extractFromStream(inputStream, onProgress)
        } catch (e: Exception) {
            ExtractionResult.Error("Runtime repair failed: ${e.message}", e)
        }
    }

    /**
     * Extracts the bootstrap archive from the APK assets.
     */
    open suspend fun extract(
        assetName: String = BOOTSTRAP_ASSET_NAME,
        onProgress: ((progress: Float, message: String) -> Unit)? = null
    ): ExtractionResult = withContext(ioDispatcher) {
        // Disk space check
        val usableSpace = filesDir.usableSpace
        if (usableSpace in 1L until MIN_REQUIRED_DISK_BYTES) {
            return@withContext ExtractionResult.Error(
                "Insufficient disk space: ${usableSpace / (1024 * 1024)} MB available, required at least ${MIN_REQUIRED_DISK_BYTES / (1024 * 1024)} MB"
            )
        }

        if (assetManager == null) {
            return@withContext ExtractionResult.Error("AssetManager is null; cannot extract asset '$assetName'")
        }
        val inputStream = try {
            assetManager.open(assetName)
        } catch (e: Exception) {
            return@withContext ExtractionResult.Error(
                "Bootstrap archive '$assetName' not found in assets",
                e
            )
        }
        extractFromStream(inputStream, onProgress)
    }

    /**
     * Extracts the bootstrap archive from any provided input stream.
     */
    open suspend fun extractFromStream(
        inputStream: InputStream,
        onProgress: ((progress: Float, message: String) -> Unit)? = null
    ): ExtractionResult = withContext(ioDispatcher) {
        // Disk space check
        val usableSpace = filesDir.usableSpace
        if (usableSpace in 1L until MIN_REQUIRED_DISK_BYTES) {
            return@withContext ExtractionResult.Error(
                "Insufficient disk space: ${usableSpace / (1024 * 1024)} MB available, required at least ${MIN_REQUIRED_DISK_BYTES / (1024 * 1024)} MB"
            )
        }
        try {
            // Invalidate marker during extraction
            if (markerFile.exists()) {
                markerFile.delete()
            }

            onProgress?.invoke(0.05f, "Preparing destination directory...")
            if (!filesDir.exists()) {
                filesDir.mkdirs()
            }

            // Stream and decompress archive
            onProgress?.invoke(0.10f, "Decompressing ARM64 userland archive...")
            val canonicalBase = filesDir.canonicalPath

            var extractedEntries = 0
            val estimatedTotalEntries = 30

            BufferedInputStream(inputStream).use { bis ->
                XZCompressorInputStream(bis).use { xzIn ->
                    TarArchiveInputStream(xzIn).use { tarIn ->
                        var entry: TarArchiveEntry? = tarIn.nextEntry
                        while (entry != null) {
                            coroutineContext.ensureActive()

                            val entryName = entry.name.removePrefix("./").removePrefix("/")
                            if (entryName.isNotEmpty()) {
                                val destFile = File(filesDir, entryName)

                                // Security check: Zip Slip / Directory Traversal prevention
                                val canonicalDest = destFile.canonicalPath
                                if (canonicalDest != canonicalBase && !canonicalDest.startsWith(canonicalBase + File.separator)) {
                                    throw SecurityException("Archive entry '$entryName' is outside destination directory: $canonicalDest")
                                }

                                if (entry.isDirectory) {
                                    destFile.mkdirs()
                                    destFile.setReadable(true, false)
                                    destFile.setExecutable(true, false)
                                    destFile.setWritable(true, true)
                                } else if (entry.isSymbolicLink) {
                                    val linkTarget = entry.linkName
                                    // Security check on symlink target
                                    validateLinkTarget(destFile, linkTarget, canonicalBase)

                                    destFile.parentFile?.mkdirs()
                                    if (destFile.exists() || Files.isSymbolicLink(destFile.toPath())) {
                                        Files.deleteIfExists(destFile.toPath())
                                    }
                                    try {
                                        Files.createSymbolicLink(destFile.toPath(), Paths.get(linkTarget))
                                    } catch (_: Exception) {
                                        // If symlinks not supported by OS/filesystem, continue gracefully
                                    }
                                } else if (entry.isLink) {
                                    // Hardlink handling
                                    val linkTarget = entry.linkName.removePrefix("./").removePrefix("/")
                                    val sourceFile = File(filesDir, linkTarget)
                                    destFile.parentFile?.mkdirs()
                                    if (destFile.exists() || Files.isSymbolicLink(destFile.toPath())) {
                                        Files.deleteIfExists(destFile.toPath())
                                    }
                                    try {
                                        Files.createLink(destFile.toPath(), sourceFile.toPath())
                                    } catch (_: Exception) {
                                        // Fallback to copying source content if hardlink creation fails
                                        if (sourceFile.exists()) {
                                            sourceFile.inputStream().use { src ->
                                                FileOutputStream(destFile).use { dst ->
                                                    src.copyTo(dst)
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    destFile.parentFile?.let { parent ->
                                        if (!parent.exists()) {
                                            parent.mkdirs()
                                            parent.setReadable(true, false)
                                            parent.setExecutable(true, false)
                                            parent.setWritable(true, true)
                                        }
                                    }

                                    if (destFile.exists() || Files.isSymbolicLink(destFile.toPath())) {
                                        Files.deleteIfExists(destFile.toPath())
                                    }

                                    FileOutputStream(destFile).use { fos ->
                                        tarIn.copyTo(fos, bufferSize = 8192)
                                    }

                                    destFile.setReadable(true, false)
                                    destFile.setWritable(true, true)
                                    if (shouldBeExecutable(entryName, entry.mode)) {
                                        destFile.setExecutable(true, false)
                                    }
                                }

                                extractedEntries++
                                val progress = (0.10f + (extractedEntries.toFloat() / (extractedEntries + 10).coerceAtLeast(estimatedTotalEntries)) * 0.70f).coerceIn(0.10f, 0.85f)
                                onProgress?.invoke(progress, "Extracting: $entryName")
                            }

                            entry = tarIn.nextEntry
                        }
                    }
                }
            }

            onProgress?.invoke(0.88f, "Setting POSIX execution permissions...")
            enforcePermissions(usrDir)

            onProgress?.invoke(0.95f, "Verifying binary integrity...")
            for (binRelPath in CRITICAL_BINARIES) {
                val binFile = File(usrDir, binRelPath)
                if (!binFile.exists() || !binFile.isFile) {
                    return@withContext ExtractionResult.Error(
                        "Integrity verification failed: Missing critical binary '${binFile.absolutePath}'"
                    )
                }
                binFile.setExecutable(true, false)
                if (!binFile.canExecute()) {
                    return@withContext ExtractionResult.Error(
                        "Integrity verification failed: Cannot execute binary '${binFile.absolutePath}'"
                    )
                }
            }

            // Write completion marker
            markerFile.writeText("version=$BOOTSTRAP_VERSION\ntimestamp=${System.currentTimeMillis()}\n")
            markerFile.setReadable(true, true)
            markerFile.setWritable(true, true)

            onProgress?.invoke(1.0f, "Bootstrap installation completed successfully.")
            ExtractionResult.Success(usrDir)
        } catch (e: Exception) {
            // Ensure no invalid marker remains
            if (markerFile.exists()) {
                markerFile.delete()
            }
            ExtractionResult.Error("Bootstrap extraction failed: ${e.message}", e)
        }
    }

    private fun validateLinkTarget(destFile: File, linkTarget: String, canonicalBase: String) {
        val targetPath = if (linkTarget.startsWith("/")) {
            File(linkTarget).canonicalPath
        } else {
            destFile.parentFile?.resolve(linkTarget)?.canonicalPath ?: return
        }
        if (targetPath != canonicalBase && !targetPath.startsWith(canonicalBase + File.separator)) {
            throw SecurityException("Symlink target '$linkTarget' escapes destination directory: $targetPath")
        }
    }

    /**
     * Recursively enforces 755 (rwxr-xr-x) permissions on directories,
     * and on all binaries in bin/ and libexec/.
     */
    fun enforcePermissions(dir: File) {
        if (!dir.exists()) return

        dir.walkTopDown().forEach { file ->
            if (file.isDirectory) {
                file.setReadable(true, false)
                file.setExecutable(true, false)
                file.setWritable(true, true)
            } else {
                file.setReadable(true, false)
                val relPath = file.relativeToOrNull(filesDir)?.path ?: return@forEach
                if (shouldBeExecutable(relPath, 0)) {
                    file.setExecutable(true, false)
                }
            }
        }
    }

    private fun shouldBeExecutable(path: String, mode: Int): Boolean {
        if (path.contains("/bin/") || path.startsWith("bin/") || path.startsWith("usr/bin/")) return true
        if (path.contains("/libexec/") || path.startsWith("libexec/") || path.startsWith("usr/libexec/")) return true
        return (mode and 0b001_001_001) != 0
    }

    open fun cleanUserland(): Boolean {
        return try {
            if (markerFile.exists()) {
                markerFile.delete()
            }
            if (usrDir.exists()) {
                usrDir.walkBottomUp().forEach { file ->
                    try {
                        file.setWritable(true, true)
                    } catch (_: Exception) {}
                }
                usrDir.deleteRecursively()
            } else {
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
