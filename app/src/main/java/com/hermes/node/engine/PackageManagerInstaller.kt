package com.hermes.node.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.coroutines.coroutineContext

/**
 * Engine component for dynamically downloading, extracting, and configuring
 * the Alpine Linux package manager (apk) rootfs into ${usrDir}.
 */
open class PackageManagerInstaller(
    val filesDir: File,
    val usrDir: File = File(filesDir, BootstrapExtractor.USR_DIR_NAME),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        filesDir = context.filesDir,
        usrDir = File(context.filesDir, BootstrapExtractor.USR_DIR_NAME),
        ioDispatcher = ioDispatcher
    )

    companion object {
        private const val TAG = "PackageManagerInstaller"
        const val DEFAULT_PACKAGE_MANAGER_URL =
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.0-aarch64.tar.gz"

        const val RESOLV_CONF_CONTENT = "nameserver 8.8.8.8\nnameserver 1.1.1.1\n"
        const val REPOSITORIES_CONTENT =
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/main\nhttps://dl-cdn.alpinelinux.org/alpine/v3.20/community\n"
    }

    /**
     * Checks if the apk package manager is installed and executable in usrDir.
     */
    open fun isPackageManagerInstalled(): Boolean {
        val binApk = File(usrDir, "bin/apk")
        val usrBinApk = File(usrDir, "usr/bin/apk")
        return (binApk.exists() && binApk.canExecute()) || (usrBinApk.exists() && usrBinApk.canExecute())
    }

    /**
     * Configures DNS (/etc/resolv.conf) and Alpine repository mirrors (/etc/apk/repositories).
     */
    open fun ensureNetworkConfig(targetDir: File = usrDir): Boolean {
        return try {
            val etcDir = File(targetDir, "etc")
            if (!etcDir.exists()) {
                etcDir.mkdirs()
            }
            val resolvConf = File(etcDir, "resolv.conf")
            resolvConf.writeText(RESOLV_CONF_CONTENT, Charsets.UTF_8)
            resolvConf.setReadable(true, false)
            resolvConf.setWritable(true, true)

            val apkDir = File(etcDir, "apk")
            if (!apkDir.exists()) {
                apkDir.mkdirs()
            }
            val reposFile = File(apkDir, "repositories")
            reposFile.writeText(REPOSITORIES_CONTENT, Charsets.UTF_8)
            reposFile.setReadable(true, false)
            reposFile.setWritable(true, true)
            true
        } catch (e: Throwable) {
            try {
                Log.w(TAG, "Failed to ensure network config: ${e.message}")
            } catch (_: Throwable) {}
            false
        }
    }

    /**
     * Extracts a package manager archive stream (.tar.gz, .tar.xz, or .tar) into ${usrDir}.
     * Staging is used so that truncated or corrupted archive streams will not corrupt
     * existing binaries or user databases in ${filesDir}.
     */
    open suspend fun installFromStream(
        inputStream: InputStream,
        onProgress: ((progress: Float, message: String) -> Unit)? = null
    ): Result<Int> = withContext(ioDispatcher) {
        val stagingDir = File(filesDir, ".pkg_stage_${System.currentTimeMillis()}_${(1..9999).random()}")
        try {
            onProgress?.invoke(0.50f, "Preparing extraction staging area...")
            if (!stagingDir.exists()) {
                stagingDir.mkdirs()
            }
            if (!usrDir.exists()) {
                usrDir.mkdirs()
            }

            val canonicalStagingBase = stagingDir.canonicalPath
            val bis = BufferedInputStream(inputStream)

            // Auto-detect compression format from magic header bytes
            bis.mark(1024)
            val header = ByteArray(6)
            val bytesRead = bis.read(header)
            bis.reset()

            if (bytesRead < 2) {
                return@withContext Result.failure(IOException("Archive stream is empty or truncated"))
            }

            val decompressedStream: InputStream = when {
                header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte() -> {
                    GzipCompressorInputStream(bis)
                }
                bytesRead >= 6 &&
                        header[0] == 0xFD.toByte() &&
                        header[1] == '7'.code.toByte() &&
                        header[2] == 'z'.code.toByte() &&
                        header[3] == 'X'.code.toByte() &&
                        header[4] == 'Z'.code.toByte() &&
                        header[5] == 0x00.toByte() -> {
                    XZCompressorInputStream(bis)
                }
                else -> {
                    // Plain tar stream
                    bis
                }
            }

            var extractedEntries = 0
            val estimatedEntries = 50

            TarArchiveInputStream(decompressedStream).use { tarIn ->
                var entry: TarArchiveEntry? = tarIn.nextEntry
                while (entry != null) {
                    coroutineContext.ensureActive()
                    val entryName = entry.name.removePrefix("./").removePrefix("/")
                    if (entryName.isNotEmpty()) {
                        val destFile = File(stagingDir, entryName)
                        val canonicalDest = destFile.canonicalPath

                        // Security check: Zip Slip / Directory Traversal prevention
                        if (canonicalDest != canonicalStagingBase && !canonicalDest.startsWith(canonicalStagingBase + File.separator)) {
                            throw SecurityException("Archive entry '$entryName' is outside destination directory: $canonicalDest")
                        }

                        if (entry.isDirectory) {
                            destFile.mkdirs()
                            destFile.setReadable(true, false)
                            destFile.setExecutable(true, false)
                            destFile.setWritable(true, true)
                        } else if (entry.isSymbolicLink) {
                            destFile.parentFile?.mkdirs()
                            if (destFile.exists() || Files.isSymbolicLink(destFile.toPath())) {
                                Files.deleteIfExists(destFile.toPath())
                            }
                            try {
                                Files.createSymbolicLink(destFile.toPath(), Paths.get(entry.linkName))
                            } catch (_: Exception) {
                                // Continue gracefully if filesystem does not support symlinks
                            }
                        } else if (entry.isLink) {
                            val linkTarget = entry.linkName.removePrefix("./").removePrefix("/")
                            val sourceFile = File(stagingDir, linkTarget)
                            destFile.parentFile?.mkdirs()
                            if (destFile.exists() || Files.isSymbolicLink(destFile.toPath())) {
                                Files.deleteIfExists(destFile.toPath())
                            }
                            try {
                                Files.createLink(destFile.toPath(), sourceFile.toPath())
                            } catch (_: Exception) {
                                if (sourceFile.exists()) {
                                    sourceFile.copyTo(destFile, overwrite = true)
                                }
                            }
                        } else {
                            destFile.parentFile?.let { parent ->
                                if (!parent.exists()) {
                                    parent.mkdirs()
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
                        val progress = (0.50f + (extractedEntries.toFloat() / (extractedEntries + 20).coerceAtLeast(estimatedEntries)) * 0.35f).coerceIn(0.50f, 0.85f)
                        onProgress?.invoke(progress, "Extracting: $entryName")
                    }
                    entry = tarIn.nextEntry
                }
            }

            if (extractedEntries == 0) {
                return@withContext Result.failure(IOException("No entries extracted from archive stream"))
            }

            onProgress?.invoke(0.85f, "Installing binaries into userland...")

            // Ensure apk binaries exist in staging
            ensureApkInStaging(stagingDir)

            // Commit from staging into usrDir cleanly
            commitStagedFiles(stagingDir, usrDir)

            onProgress?.invoke(0.92f, "Configuring DNS and Alpine repositories...")
            ensureNetworkConfig(usrDir)

            // Enforce POSIX executable permissions (0755) on bin/apk and usr/bin/apk
            enforceApkPermissions()

            if (!isPackageManagerInstalled()) {
                return@withContext Result.failure(
                    IOException("Package manager installation validation failed: apk binary missing or not executable in $usrDir")
                )
            }

            onProgress?.invoke(1.0f, "Package manager installation completed.")
            Result.success(extractedEntries)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            try {
                Log.e(TAG, "Package manager installation failed: ${e.message}", e)
            } catch (_: Throwable) {}
            Result.failure(e)
        } finally {
            try {
                if (stagingDir.exists()) {
                    stagingDir.deleteRecursively()
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Downloads an archive stream from [url] and installs it into ${usrDir}.
     */
    open suspend fun downloadAndInstall(
        url: String = DEFAULT_PACKAGE_MANAGER_URL,
        onProgress: ((progress: Float, message: String) -> Unit)? = null
    ): Result<Int> = withContext(ioDispatcher) {
        val tmpDir = File(filesDir, "tmp").apply { if (!exists()) mkdirs() }
        val downloadFile = File(tmpDir, "pkg_dl_${System.currentTimeMillis()}.tmp")

        try {
            onProgress?.invoke(0.05f, "Connecting to package manager mirror...")
            var currentUrl = url
            var connection: HttpURLConnection? = null
            var redirects = 0
            val maxRedirects = 5

            while (redirects < maxRedirects) {
                coroutineContext.ensureActive()
                val u = URL(currentUrl)
                val conn = u.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Hermes-Android-Node")
                conn.setRequestProperty("Accept-Encoding", "identity")
                conn.connect()

                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("HTTP redirect ($code) without Location header")
                    currentUrl = if (location.startsWith("http")) location else URL(u, location).toString()
                    conn.disconnect()
                    redirects++
                    continue
                }

                if (code !in 200..299) {
                    conn.disconnect()
                    throw IOException("HTTP error $code: ${conn.responseMessage}")
                }

                connection = conn
                break
            }

            val conn = connection ?: throw IOException("Too many redirects connecting to $url")

            try {
                val contentLength = conn.contentLengthLong
                conn.inputStream.use { netIn ->
                    FileOutputStream(downloadFile).use { fileOut ->
                        val buffer = ByteArray(16384)
                        var bytesRead: Int
                        var totalDownloaded = 0L
                        while (netIn.read(buffer).also { bytesRead = it } != -1) {
                            coroutineContext.ensureActive()
                            fileOut.write(buffer, 0, bytesRead)
                            totalDownloaded += bytesRead
                            if (contentLength > 0) {
                                val progress = ((totalDownloaded.toFloat() / contentLength) * 0.45f + 0.05f).coerceIn(0.05f, 0.50f)
                                onProgress?.invoke(
                                    progress,
                                    "Downloading: ${totalDownloaded / 1024} KB / ${contentLength / 1024} KB"
                                )
                            } else {
                                onProgress?.invoke(0.25f, "Downloading: ${totalDownloaded / 1024} KB")
                            }
                        }
                        if (contentLength > 0 && totalDownloaded < contentLength) {
                            throw IOException("Download truncated: expected $contentLength bytes, received $totalDownloaded bytes")
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            onProgress?.invoke(0.50f, "Decompressing package manager archive...")
            downloadFile.inputStream().use { fileIn ->
                installFromStream(fileIn, onProgress)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            try {
                Log.e(TAG, "Package manager download/install failed: ${e.message}", e)
            } catch (_: Throwable) {}
            Result.failure(e)
        } finally {
            try {
                if (downloadFile.exists()) {
                    downloadFile.delete()
                }
            } catch (_: Throwable) {}
        }
    }

    private fun shouldBeExecutable(path: String, mode: Int): Boolean {
        if (path.contains("/bin/") || path.startsWith("bin/") || path.startsWith("usr/bin/") || path.startsWith("sbin/")) {
            return true
        }
        if (path.contains("/libexec/") || path.startsWith("libexec/")) {
            return true
        }
        return (mode and 0b001_001_001) != 0
    }

    private fun ensureApkInStaging(stagingDir: File) {
        val sbinApk = File(stagingDir, "sbin/apk")
        val binApk = File(stagingDir, "bin/apk")
        val usrBinApk = File(stagingDir, "usr/bin/apk")

        if (sbinApk.exists()) {
            if (!binApk.exists()) {
                binApk.parentFile?.mkdirs()
                sbinApk.copyTo(binApk, overwrite = true)
            }
            if (!usrBinApk.exists()) {
                usrBinApk.parentFile?.mkdirs()
                sbinApk.copyTo(usrBinApk, overwrite = true)
            }
        }

        if (binApk.exists() && !usrBinApk.exists()) {
            usrBinApk.parentFile?.mkdirs()
            binApk.copyTo(usrBinApk, overwrite = true)
        } else if (usrBinApk.exists() && !binApk.exists()) {
            binApk.parentFile?.mkdirs()
            usrBinApk.copyTo(binApk, overwrite = true)
        }
    }

    private fun commitStagedFiles(stagingDir: File, targetDir: File) {
        stagingDir.walkTopDown().forEach { file ->
            val relPath = file.relativeTo(stagingDir).path
            if (relPath.isNotEmpty()) {
                val destFile = File(targetDir, relPath)
                if (Files.isSymbolicLink(file.toPath())) {
                    destFile.parentFile?.mkdirs()
                    if (destFile.exists() || Files.isSymbolicLink(destFile.toPath())) {
                        Files.deleteIfExists(destFile.toPath())
                    }
                    try {
                        Files.createSymbolicLink(destFile.toPath(), Files.readSymbolicLink(file.toPath()))
                    } catch (_: Exception) {
                        try {
                            file.copyTo(destFile, overwrite = true)
                        } catch (_: Exception) {}
                    }
                } else if (file.isDirectory) {
                    destFile.mkdirs()
                    destFile.setReadable(true, false)
                    destFile.setExecutable(true, false)
                    destFile.setWritable(true, true)
                } else {
                    destFile.parentFile?.mkdirs()
                    if (Files.isSymbolicLink(destFile.toPath())) {
                        Files.deleteIfExists(destFile.toPath())
                    }
                    file.copyTo(destFile, overwrite = true)
                    destFile.setReadable(true, false)
                    destFile.setWritable(true, true)
                    if (file.canExecute() || shouldBeExecutable(relPath, 0)) {
                        destFile.setExecutable(true, false)
                    }
                }
            }
        }
    }

    private fun enforceApkPermissions() {
        val binApk = File(usrDir, "bin/apk")
        val usrBinApk = File(usrDir, "usr/bin/apk")

        if (binApk.exists() && !usrBinApk.exists()) {
            usrBinApk.parentFile?.mkdirs()
            try {
                binApk.copyTo(usrBinApk, overwrite = true)
            } catch (_: Exception) {}
        } else if (usrBinApk.exists() && !binApk.exists()) {
            binApk.parentFile?.mkdirs()
            try {
                usrBinApk.copyTo(binApk, overwrite = true)
            } catch (_: Exception) {}
        }

        if (binApk.exists()) {
            binApk.setReadable(true, false)
            binApk.setWritable(true, true)
            binApk.setExecutable(true, false)
        }

        if (usrBinApk.exists()) {
            usrBinApk.setReadable(true, false)
            usrBinApk.setWritable(true, true)
            usrBinApk.setExecutable(true, false)
        }
    }
}
