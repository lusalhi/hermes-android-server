package com.hermes.node.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.util.Locale

interface BatteryOptimizationHelperInterface {
    fun isIgnoringBatteryOptimizations(context: Context): Boolean
    fun createRequestExemptionIntent(context: Context): Intent
    fun createBatteryOptimizationSettingsIntent(): Intent
    fun requestExemption(context: Context): Boolean
    fun getDontKillMyAppUrl(manufacturer: String? = Build.MANUFACTURER): String
}

open class BatteryOptimizationHelper : BatteryOptimizationHelperInterface {

    companion object {
        const val TAG = "BatteryOptHelper"
        const val DONT_KILL_MY_APP_BASE = "https://dontkillmyapp.com"
        const val ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        const val ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS

        val KNOWN_OEM_PATHS = listOf(
            "samsung" to "samsung",
            "xiaomi" to "xiaomi",
            "redmi" to "xiaomi",
            "poco" to "xiaomi",
            "blackshark" to "xiaomi",
            "huawei" to "huawei",
            "honor" to "huawei",
            "oppo" to "oppo",
            "realme" to "realme",
            "oneplus" to "oneplus",
            "vivo" to "vivo",
            "iqoo" to "vivo",
            "meizu" to "meizu",
            "asus" to "asus",
            "lenovo" to "lenovo",
            "motorola" to "motorola",
            "moto" to "motorola",
            "nokia" to "nokia",
            "sony" to "sony",
            "google" to "google",
            "pixel" to "google",
            "transsion" to "transsion",
            "tecno" to "transsion",
            "infinix" to "transsion",
            "itel" to "transsion",
            "nothing" to "nothing",
            "lge" to "lg",
            "lg" to "lg",
            "unihertz" to "unihertz",
            "blackview" to "blackview",
            "ulefone" to "ulefone",
            "htc" to "htc",
            "zte" to "zte"
        )
    }

    override fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            } else {
                true
            }
        } catch (e: Throwable) {
            try {
                Log.w(TAG, "Failed to check battery optimization status: ${e.message}")
            } catch (_: Throwable) {}
            true
        }
    }

    override fun createRequestExemptionIntent(context: Context): Intent {
        return Intent(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    override fun createBatteryOptimizationSettingsIntent(): Intent {
        return Intent(ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    override fun requestExemption(context: Context): Boolean {
        // Try direct package exemption request intent first
        try {
            val directIntent = createRequestExemptionIntent(context)
            context.startActivity(directIntent)
            return true
        } catch (e: ActivityNotFoundException) {
            try {
                Log.w(TAG, "Direct exemption intent not supported, falling back to settings: ${e.message}")
            } catch (_: Throwable) {}
        } catch (e: SecurityException) {
            try {
                Log.w(TAG, "Direct exemption intent permission denied, falling back to settings: ${e.message}")
            } catch (_: Throwable) {}
        } catch (e: Throwable) {
            try {
                Log.w(TAG, "Unexpected error launching direct exemption intent: ${e.message}")
            } catch (_: Throwable) {}
        }

        // Fallback to general battery optimization settings screen
        return try {
            val fallbackIntent = createBatteryOptimizationSettingsIntent()
            context.startActivity(fallbackIntent)
            true
        } catch (e: Throwable) {
            try {
                Log.e(TAG, "Failed to launch battery optimization settings: ${e.message}")
            } catch (_: Throwable) {}
            false
        }
    }

    override fun getDontKillMyAppUrl(manufacturer: String?): String {
        val safeManufacturer = manufacturer ?: ""
        val normalized = safeManufacturer.trim().lowercase(Locale.US)
        if (normalized.isEmpty()) {
            return DONT_KILL_MY_APP_BASE
        }
        val matchedPath = KNOWN_OEM_PATHS.firstOrNull { (key, _) ->
            normalized == key || normalized.contains(key) || key.contains(normalized)
        }?.second

        return if (matchedPath != null) {
            "$DONT_KILL_MY_APP_BASE/$matchedPath"
        } else {
            DONT_KILL_MY_APP_BASE
        }
    }
}
