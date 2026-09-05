package com.hermes.node.engine

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
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
        const val BOOTSTRAP_VERSION = 8
        private const val MIN_REQUIRED_DISK_BYTES = 20L * 1024 * 1024 // 20 MB

        val CRITICAL_BINARIES = listOf(
            "bin/python3",
            "bin/proot",
            "bin/hermes"
        )

        val SUDO_SHIM_SCRIPT = """#!/bin/sh
# /usr/bin/sudo
while [ ${'$'}# -gt 0 ]; do
  case "${'$'}1" in
    --)
      shift
      break
      ;;
    -u|-g|-p|-C|-U)
      shift 2
      ;;
    -*)
      shift
      ;;
    *)
      break
      ;;
  esac
done
if [ ${'$'}# -eq 0 ]; then
  exit 0
fi
exec "${'$'}@"
"""

        val APT_SHIM_SCRIPT = """#!/bin/sh
# /usr/bin/apt and /usr/bin/apt-get
while [ ${'$'}# -gt 0 ]; do
  case "${'$'}1" in
    -*)
      shift
      ;;
    *)
      break
      ;;
  esac
done

SUBCMD="${'$'}1"
if [ -n "${'$'}1" ]; then
  shift
fi

if [ "${'$'}SUBCMD" = "update" ]; then
  echo "Reading package lists... Done"
  if command -v pkg >/dev/null 2>&1; then
    exec pkg update -y "${'$'}@"
  elif command -v apk >/dev/null 2>&1; then
    exec apk update "${'$'}@"
  fi
  exit 0
elif [ "${'$'}SUBCMD" = "install" ]; then
  echo "Hermes Shim: Package installation requested for: ${'$'}@"
  if command -v pkg >/dev/null 2>&1; then
    exec pkg install -y "${'$'}@"
  elif command -v apk >/dev/null 2>&1; then
    APK_ARGS=""
    for arg in "${'$'}@"; do
      case "${'$'}arg" in
        -y|--yes|--assume-yes|-q|--quiet)
          ;;
        *)
          APK_ARGS="${'$'}APK_ARGS ${'$'}arg"
          ;;
      esac
    done
    exec apk add ${'$'}APK_ARGS
  fi
  exit 0
else
  exit 0
fi
"""

        val TOOLCHAIN_SHIMS: Map<String, String> = mapOf(
            "bin/sudo" to SUDO_SHIM_SCRIPT,
            "bin/apt" to APT_SHIM_SCRIPT,
            "bin/apt-get" to APT_SHIM_SCRIPT
        )

        val HERMES_LAUNCHER_SCRIPT = """#!/bin/sh
# /usr/bin/hermes launcher with Android userland & PRoot support

DIR="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
USR_DIR="${'$'}(cd "${'$'}DIR/.." && pwd)"

HERMES_AGENT_DIR="/usr/lib/hermes-agent"
if [ ! -d "${'$'}HERMES_AGENT_DIR" ]; then
    HERMES_AGENT_DIR="${'$'}USR_DIR/lib/hermes-agent"
fi
if [ ! -d "${'$'}HERMES_AGENT_DIR" ]; then
    HERMES_AGENT_DIR="${'$'}USR_DIR/usr/lib/hermes-agent"
fi

if [ -d "/usr/lib/python3.12" ]; then
    export PYTHONHOME="/usr"
elif [ -d "${'$'}USR_DIR/usr/lib/python3.12" ]; then
    export PYTHONHOME="${'$'}USR_DIR/usr"
elif [ -d "${'$'}USR_DIR/lib/python3.12" ]; then
    export PYTHONHOME="${'$'}USR_DIR"
fi

export PYTHONPATH="${'$'}HERMES_AGENT_DIR:/usr/lib/python3.12/site-packages:/usr/lib/python3.12/lib-dynload:${'$'}USR_DIR/usr/lib/hermes-agent:${'$'}USR_DIR/usr/lib/python3.12/site-packages:${'$'}USR_DIR/usr/lib/python3.12/lib-dynload:${'$'}USR_DIR/lib/hermes-agent:${'$'}USR_DIR/lib/python3.12/site-packages:${'$'}PYTHONPATH"
export LD_LIBRARY_PATH="/lib:/usr/lib:${'$'}USR_DIR/lib:${'$'}USR_DIR/usr/lib:${'$'}LD_LIBRARY_PATH"
export PATH="/bin:/usr/bin:/sbin:/usr/sbin:${'$'}USR_DIR/bin:${'$'}USR_DIR/usr/bin:${'$'}PATH"
export PYTHONUNBUFFERED="1"

PYTHON_BIN=""
if [ -f "${'$'}DIR/python3" ]; then
    PYTHON_BIN="${'$'}DIR/python3"
elif [ -f "${'$'}USR_DIR/bin/python3" ]; then
    PYTHON_BIN="${'$'}USR_DIR/bin/python3"
elif [ -n "${'$'}PREFIX" ] && [ -f "${'$'}PREFIX/bin/python3" ]; then
    PYTHON_BIN="${'$'}PREFIX/bin/python3"
elif [ -f "/usr/bin/python3" ]; then
    PYTHON_BIN="/usr/bin/python3"
elif [ -f "/bin/python3" ]; then
    PYTHON_BIN="/bin/python3"
elif command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN="${'$'}(command -v python3)"
fi

if [ -f "${'$'}HERMES_AGENT_DIR/hermes" ]; then
    exec "${'$'}PYTHON_BIN" "${'$'}HERMES_AGENT_DIR/hermes" "${'$'}@"
elif [ -f "/lib/ld-musl-aarch64.so.1" ]; then
    exec "${'$'}PYTHON_BIN" -m hermes "${'$'}@"
elif [ -f "${'$'}USR_DIR/lib/ld-musl-aarch64.so.1" ]; then
    exec "${'$'}USR_DIR/lib/ld-musl-aarch64.so.1" --library-path "${'$'}USR_DIR/lib:${'$'}USR_DIR/usr/lib" "${'$'}PYTHON_BIN" -m hermes "${'$'}@"
else
    exec python3 -m hermes "${'$'}@"
fi
"""
    }

    val usrDir: File
        get() = File(filesDir, USR_DIR_NAME)

    val markerFile: File
        get() = File(filesDir, MARKER_FILE_NAME)

    /**
     * Ensures /usr/bin/hermes launcher script delegates directly to Python:
     * exec python3 -m hermes "$@"
     */
    open fun ensureHermesLauncher(targetDir: File = usrDir, forceCreate: Boolean = false): Boolean {
        val binHermes = File(targetDir, "bin/hermes")
        val usrBinHermes = File(targetDir, "usr/bin/hermes")
        if (!forceCreate && !binHermes.exists() && !usrBinHermes.exists()) {
            return false
        }
        return try {
            val script = HERMES_LAUNCHER_SCRIPT
            for (file in listOf(binHermes, usrBinHermes)) {
                file.parentFile?.mkdirs()
                file.setWritable(true, true)
                file.writeText(script, Charsets.UTF_8)
                file.setReadable(true, false)
                file.setWritable(true, true)
                file.setExecutable(true, false)
            }
            binHermes.canExecute() && usrBinHermes.canExecute()
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Ensures POSIX toolchain shims (sudo, apt, apt-get) are installed into ${usrDir}/bin
     * with executable permissions (0755), self-healing any missing or corrupted binaries.
     */
    open fun ensureToolchainShims(): Boolean {
        val binDir = File(usrDir, "bin")
        if (!binDir.exists()) {
            binDir.mkdirs()
        }
        val usrBinDir = File(usrDir, "usr/bin")
        if (!usrBinDir.exists()) {
            usrBinDir.mkdirs()
        }

        var allOk = true
        for ((relPath, script) in TOOLCHAIN_SHIMS) {
            val shimFile = File(usrDir, relPath)
            val mirrorFile = File(usrBinDir, shimFile.name)
            try {
                if (!shimFile.exists() || !shimFile.isFile || shimFile.length() < 10L) {
                    shimFile.parentFile?.mkdirs()
                    shimFile.writeText(script, Charsets.UTF_8)
                    try {
                        Log.i("BootstrapExtractor", "Auto-healed toolchain shim: $relPath")
                    } catch (_: Throwable) {}
                }
                shimFile.setReadable(true, false)
                shimFile.setWritable(true, true)
                shimFile.setExecutable(true, false)

                if (!shimFile.exists() || !shimFile.canExecute()) {
                    allOk = false
                }

                // Also ensure mirror in usr/usr/bin for absolute /usr/bin inside PRoot rootfs
                if (!mirrorFile.exists() || !mirrorFile.isFile || mirrorFile.length() < 10L) {
                    mirrorFile.parentFile?.mkdirs()
                    mirrorFile.writeText(script, Charsets.UTF_8)
                }
                mirrorFile.setReadable(true, false)
                mirrorFile.setWritable(true, true)
                mirrorFile.setExecutable(true, false)

                if (!mirrorFile.exists() || !mirrorFile.canExecute()) {
                    allOk = false
                }
            } catch (e: Throwable) {
                allOk = false
                try {
                    Log.w("BootstrapExtractor", "Failed to ensure toolchain shim $relPath: ${e.message}")
                } catch (_: Throwable) {}
            }
        }
        val binHermes = File(usrDir, "bin/hermes")
        val usrBinHermes = File(usrBinDir, "hermes")
        if (binHermes.exists() || usrBinHermes.exists()) {
            ensureHermesLauncher(usrDir)
        }
        ensurePythonSymlinks(usrDir)
        return allOk
    }

    /**
     * Ensures Python standard library and shared libraries are accessible
     * via symlinks from usrDir/lib to usrDir/usr/lib.
     */
    open fun ensurePythonSymlinks(targetDir: File = usrDir): Boolean {
        return try {
            val libDir = File(targetDir, "lib").apply { if (!exists()) mkdirs() }
            val usrLibDir = File(targetDir, "usr/lib")
            
            // Link python3.12, python3.11, and hermes-agent
            listOf("python3.12", "python3.11", "hermes-agent").forEach { pyName ->
                val link = File(libDir, pyName)
                val target = File(usrLibDir, pyName)
                if (target.exists() && !link.exists()) {
                    try {
                        Files.createSymbolicLink(link.toPath(), Paths.get("../usr/lib/$pyName"))
                    } catch (_: Throwable) {}
                }
            }

            // Link all .so files from usr/lib to lib if missing
            if (usrLibDir.exists() && usrLibDir.isDirectory) {
                usrLibDir.listFiles()?.forEach { soFile ->
                    if (soFile.name.contains(".so") && !File(libDir, soFile.name).exists()) {
                        try {
                            Files.createSymbolicLink(File(libDir, soFile.name).toPath(), Paths.get("../usr/lib/${soFile.name}"))
                        } catch (_: Throwable) {}
                    }
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

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
            // Auto-heal missing or corrupted toolchain shims
            ensureToolchainShims()

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

            // Check for legacy mock userland scripts (which sleep and block execution)
            val pythonBin = File(usrDir, "bin/python3")
            if (pythonBin.exists() && pythonBin.length() < 1000L) {
                try {
                    val content = pythonBin.readText()
                    if (content.contains("Hermes ARM64 Userland") || (content.contains("while true; do") && content.contains("sleep"))) {
                        issues.add("Legacy mock userland script detected in usr/bin/python3 (requires upgrade to production Python runtime)")
                    }
                } catch (_: Throwable) {}
            }
            val prootBin = File(usrDir, "bin/proot")
            if (prootBin.exists() && prootBin.length() < 1000L) {
                try {
                    val content = prootBin.readText()
                    if (content.contains("PRoot v5.3.0") || (content.contains("while true; do") && content.contains("sleep"))) {
                        issues.add("Legacy mock userland script detected in usr/bin/proot (requires upgrade to production PRoot binary)")
                    }
                } catch (_: Throwable) {}
            }

            // 4. Validate toolchain shims
            val usrBinDir = File(usrDir, "usr/bin")
            for ((relPath, _) in TOOLCHAIN_SHIMS) {
                val shimFile = File(usrDir, relPath)
                if (!shimFile.exists()) {
                    issues.add("Missing toolchain shim: $relPath")
                } else if (!shimFile.canExecute()) {
                    issues.add("Toolchain shim is not executable: $relPath")
                }

                val mirrorFile = File(usrBinDir, shimFile.name)
                if (!mirrorFile.exists()) {
                    issues.add("Missing mirror toolchain shim: usr/bin/${shimFile.name}")
                } else if (!mirrorFile.canExecute()) {
                    issues.add("Mirror toolchain shim is not executable: usr/bin/${shimFile.name}")
                }
            }

            // 5. Extended package manager detection & self-healing
            val binApk = File(usrDir, "bin/apk")
            val usrBinApk = File(usrDir, "usr/bin/apk")
            if (binApk.exists() && !binApk.canExecute()) {
                binApk.setExecutable(true, false)
            }
            if (usrBinApk.exists() && !usrBinApk.canExecute()) {
                usrBinApk.setExecutable(true, false)
            }
            if (isPackageManagerInstalled()) {
                val resolvConf = File(usrDir, "etc/resolv.conf")
                val reposFile = File(usrDir, "etc/apk/repositories")
                if (!resolvConf.exists() || !reposFile.exists()) {
                    ensureNetworkConfig(usrDir)
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
     * Configures DNS resolution (/etc/resolv.conf) and Alpine Linux package manager repositories (/etc/apk/repositories).
     */
    open fun ensureNetworkConfig(targetDir: File = usrDir): Boolean {
        return try {
            val etcDir = File(targetDir, "etc")
            if (!etcDir.exists()) {
                etcDir.mkdirs()
            }
            val resolvConf = File(etcDir, "resolv.conf")
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n", Charsets.UTF_8)
            resolvConf.setReadable(true, false)
            resolvConf.setWritable(true, true)

            val apkDir = File(etcDir, "apk")
            if (!apkDir.exists()) {
                apkDir.mkdirs()
            }
            val reposFile = File(apkDir, "repositories")
            reposFile.writeText(
                "https://dl-cdn.alpinelinux.org/alpine/v3.20/main\nhttps://dl-cdn.alpinelinux.org/alpine/v3.20/community\n",
                Charsets.UTF_8
            )
            reposFile.setReadable(true, false)
            reposFile.setWritable(true, true)
            true
        } catch (e: Throwable) {
            try {
                Log.w("BootstrapExtractor", "Failed to ensure network config: ${e.message}")
            } catch (_: Throwable) {}
            false
        }
    }

    /**
     * Checks if extended package manager (apk) is installed and executable in userland.
     */
    open fun isPackageManagerInstalled(): Boolean {
        val binApk = File(usrDir, "bin/apk")
        val usrBinApk = File(usrDir, "usr/bin/apk")
        return (binApk.exists() && binApk.canExecute()) || (usrBinApk.exists() && usrBinApk.canExecute())
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
            val result = extractFromStream(inputStream, onProgress)
            if (result is ExtractionResult.Success) {
                ensureToolchainShims()
            }
            result
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
            val result = extractFromStream(inputStream, onProgress)
            if (result is ExtractionResult.Success) {
                ensureToolchainShims()
            }
            result
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
            ensureToolchainShims()
            ensureHermesLauncher(usrDir)

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

        // Guarantee bin/sh and usr/bin/sh exist as physical executable binaries
        val binBusybox = File(dir, "bin/busybox")
        val binSh = File(dir, "bin/sh")
        if (binBusybox.exists() && (!binSh.exists() || binSh.length() == 0L)) {
            try {
                binSh.delete()
                binBusybox.copyTo(binSh, overwrite = true)
            } catch (_: Throwable) {}
        }
        val usrBinSh = File(dir, "usr/bin/sh")
        if (binBusybox.exists() && (!usrBinSh.exists() || usrBinSh.length() == 0L)) {
            try {
                usrBinSh.delete()
                binBusybox.copyTo(usrBinSh, overwrite = true)
            } catch (_: Throwable) {}
        }

        // Explicitly guarantee dynamic linker and critical binaries are executable
        listOf(
            File(dir, "lib/ld-musl-aarch64.so.1"),
            File(dir, "usr/lib/ld-musl-aarch64.so.1"),
            File(dir, "bin/busybox"),
            File(dir, "bin/sh"),
            File(dir, "bin/proot"),
            File(dir, "bin/python3"),
            File(dir, "bin/hermes"),
            File(dir, "bin/apk"),
            File(dir, "bin/bash"),
            File(dir, "usr/bin/busybox"),
            File(dir, "usr/bin/sh"),
            File(dir, "usr/bin/proot"),
            File(dir, "usr/bin/python3"),
            File(dir, "usr/bin/hermes"),
            File(dir, "usr/bin/apk"),
            File(dir, "usr/bin/bash"),
            File(dir, "libexec/proot/loader"),
            File(dir, "usr/libexec/proot/loader")
        ).forEach { f ->
            if (f.exists()) {
                f.setReadable(true, false)
                f.setExecutable(true, false)
            }
        }
    }

    private fun shouldBeExecutable(path: String, mode: Int): Boolean {
        if (path.contains("/bin/") || path.startsWith("bin/") || path.startsWith("usr/bin/")) return true
        if (path.contains("/sbin/") || path.startsWith("sbin/") || path.startsWith("usr/sbin/")) return true
        if (path.contains("/libexec/") || path.startsWith("libexec/") || path.startsWith("usr/libexec/")) return true
        if (path.contains("ld-musl") || path.contains("ld-linux") || path.endsWith(".so.1") || path.contains("/lib/ld-")) return true
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
