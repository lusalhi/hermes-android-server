package com.hermes.node.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hermes.node.data.ConfigRepository
import com.hermes.node.data.EncryptedConfigRepository

open class BootReceiver(
    private val configRepositoryProvider: (Context) -> ConfigRepository = { EncryptedConfigRepository.create(it) },
    private val serviceStarter: (Context) -> Boolean = { HermesServerService.start(it) }
) : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }

    internal open fun getActionFromIntent(intent: Intent?): String? = intent?.action

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            return
        }

        val action = getActionFromIntent(intent)
        if (action == null || action !in SUPPORTED_ACTIONS) {
            return
        }

        try {
            Log.i(TAG, "Received broadcast action: $action")
        } catch (_: Throwable) {}

        try {
            val configRepository = configRepositoryProvider(context)
            val isAutoStart = configRepository.isAutoStartEnabled()

            if (isAutoStart) {
                try {
                    Log.i(TAG, "Auto-start enabled in configuration. Starting HermesServerService...")
                } catch (_: Throwable) {}
                val started = serviceStarter(context)
                if (!started) {
                    try {
                        Log.e(TAG, "Failed to initiate HermesServerService on broadcast $action")
                    } catch (_: Throwable) {}
                }
            } else {
                try {
                    Log.i(TAG, "Auto-start disabled in configuration. Ignoring broadcast $action")
                } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            try {
                Log.e(TAG, "Error handling boot broadcast $action: ${e.message}", e)
            } catch (_: Throwable) {}
        }
    }
}
