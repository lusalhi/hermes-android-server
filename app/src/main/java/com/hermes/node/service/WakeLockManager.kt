package com.hermes.node.service

import android.content.Context
import android.os.PowerManager
import android.util.Log

interface WakeLockManagerInterface {
    val isHeld: Boolean
    fun acquire(timeoutMs: Long? = null): Boolean
    fun release(): Boolean
}

interface WakeLockAdapter {
    val isHeld: Boolean
    fun acquire(timeoutMs: Long? = null)
    fun release()
}

class AndroidWakeLockAdapter(private val wakeLock: PowerManager.WakeLock) : WakeLockAdapter {
    init {
        try {
            wakeLock.setReferenceCounted(false)
        } catch (ignored: Throwable) {
            // Ignored in test / stub environments
        }
    }

    override val isHeld: Boolean
        get() = try {
            wakeLock.isHeld
        } catch (e: Throwable) {
            false
        }

    override fun acquire(timeoutMs: Long?) {
        if (timeoutMs != null && timeoutMs > 0) {
            wakeLock.acquire(timeoutMs)
        } else {
            wakeLock.acquire()
        }
    }

    override fun release() {
        wakeLock.release()
    }
}

class WakeLockManager(
    private val adapter: WakeLockAdapter? = null,
    context: Context? = null,
    private val tag: String = TAG
) : WakeLockManagerInterface {

    private val context: Context? = context?.applicationContext ?: context

    constructor(context: Context) : this(adapter = null, context = context, tag = TAG)
    constructor(context: Context, tag: String) : this(adapter = null, context = context, tag = tag)

    companion object {
        const val TAG = "HermesNode:WakeLock"

        fun create(context: Context, tag: String = TAG): WakeLockManager {
            val appCtx = context.applicationContext ?: context
            return try {
                val powerManager = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)?.apply {
                    setReferenceCounted(false)
                }
                val adapter = wakeLock?.let { AndroidWakeLockAdapter(it) }
                WakeLockManager(adapter = adapter, context = appCtx, tag = tag)
            } catch (e: Throwable) {
                WakeLockManager(adapter = null, context = appCtx, tag = tag)
            }
        }
    }

    private val lock = Any()
    private var activeAdapter: WakeLockAdapter? = adapter

    override val isHeld: Boolean
        get() = synchronized(lock) {
            try {
                activeAdapter?.isHeld == true
            } catch (e: Throwable) {
                false
            }
        }

    override fun acquire(timeoutMs: Long?): Boolean = synchronized(lock) {
        try {
            if (activeAdapter == null && context != null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val wl = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)?.apply {
                    setReferenceCounted(false)
                }
                if (wl != null) {
                    activeAdapter = AndroidWakeLockAdapter(wl)
                }
            }
            val ad = activeAdapter ?: return false
            if (!ad.isHeld) {
                ad.acquire(timeoutMs)
                try {
                    Log.i(TAG, "WakeLock acquired ($tag)")
                } catch (ignored: Throwable) {}
            }
            true
        } catch (e: Throwable) {
            try {
                Log.e(TAG, "Failed to acquire WakeLock: ${e.message}", e)
            } catch (ignored: Throwable) {}
            false
        }
    }

    override fun release(): Boolean = synchronized(lock) {
        try {
            val ad = activeAdapter ?: return true
            if (ad.isHeld) {
                ad.release()
                try {
                    Log.i(TAG, "WakeLock released ($tag)")
                } catch (ignored: Throwable) {}
            }
            true
        } catch (e: Throwable) {
            try {
                Log.e(TAG, "Failed to release WakeLock: ${e.message}", e)
            } catch (ignored: Throwable) {}
            false
        }
    }
}
