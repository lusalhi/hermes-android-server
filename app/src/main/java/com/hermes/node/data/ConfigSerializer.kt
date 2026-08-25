package com.hermes.node.data

import com.hermes.node.data.model.HermesConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

open class ConfigSerializer(
    val configFile: File
) {
    constructor(baseDir: File, fileName: String = CONFIG_FILE_NAME) : this(File(baseDir, fileName))

    companion object {
        const val CONFIG_FILE_NAME = "hermes.json"

        fun applyPosix0600Permissions(file: File): Boolean {
            if (!file.exists()) return false
            var success = true
            // Clear all permissions for group and others
            success = file.setReadable(false, false) && success
            success = file.setWritable(false, false) && success
            success = file.setExecutable(false, false) && success

            // Grant read and write exclusively to file owner (POSIX 0600)
            success = file.setReadable(true, true) && success
            success = file.setWritable(true, true) && success
            if (!success) {
                try {
                    android.util.Log.w("ConfigSerializer", "Warning: Failed to set strict POSIX 0600 permissions on ${file.absolutePath}")
                } catch (_: Throwable) {
                    System.err.println("Warning: Failed to set strict POSIX 0600 permissions on ${file.absolutePath}")
                }
            }
            return success
        }
    }

    open fun generateJson(config: HermesConfig): String {
        val root = JSONObject()
        root.put("version", 1)

        val providerObj = JSONObject()
        providerObj.put("name", config.provider.provider)
        providerObj.put("api_key", config.provider.apiKey)
        providerObj.put("model", config.provider.model)
        providerObj.put("base_url", config.provider.baseUrl)
        root.put("provider", providerObj)

        val gatewaysObj = JSONObject()
        val telegramObj = JSONObject()
        telegramObj.put("enabled", config.gateway.isTelegramEnabled)
        telegramObj.put("bot_token", config.gateway.telegramToken)
        gatewaysObj.put("telegram", telegramObj)
        root.put("gateways", gatewaysObj)

        val systemObj = JSONObject()
        systemObj.put("auto_start", config.system.autoStartOnBoot)
        systemObj.put("public_tunnel", config.system.publicTunnelEnabled)
        root.put("system", systemObj)

        return root.toString(2)
    }

    open fun serialize(config: HermesConfig): Result<File> = runCatching {
        val parentDir = configFile.parentFile ?: File(".")
        if (!parentDir.exists()) {
            if (!parentDir.mkdirs() && !parentDir.exists()) {
                throw IOException("Failed to create configuration directory: ${parentDir.absolutePath}")
            }
        }

        val tempFile = File(parentDir, "${configFile.name}.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        try {
            val jsonContent = generateJson(config)

            FileOutputStream(tempFile).use { fos ->
                fos.write(jsonContent.toByteArray(Charsets.UTF_8))
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Exception) {
                    // Sync may not be supported on all virtual/in-memory filesystems
                }
            }

            // Apply POSIX 0600 permissions to temp staging file
            applyPosix0600Permissions(tempFile)

            // Atomically replace destination
            val sourcePath = tempFile.toPath()
            val targetPath = configFile.toPath()
            try {
                Files.move(sourcePath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                // Safe fallback without prior deletion of original config
                Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }

            // Ensure POSIX 0600 permissions on destination file
            applyPosix0600Permissions(configFile)

            configFile
        } catch (e: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw e
        }
    }
}
