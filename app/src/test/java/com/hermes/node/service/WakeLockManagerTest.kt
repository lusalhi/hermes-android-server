package com.hermes.node.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class WakeLockManagerTest {

    private lateinit var fakeAdapter: FakeWakeLockAdapter
    private lateinit var wakeLockManager: WakeLockManager

    @Before
    fun setUp() {
        fakeAdapter = FakeWakeLockAdapter()
        wakeLockManager = WakeLockManager(adapter = fakeAdapter)
    }

    @Test
    fun initialState_isNotHeld() {
        assertFalse(wakeLockManager.isHeld)
        assertEquals(0, fakeAdapter.acquireCount)
        assertEquals(0, fakeAdapter.releaseCount)
    }

    @Test
    fun acquire_whenNotHeld_acquiresSuccessfully() {
        val result = wakeLockManager.acquire()
        assertTrue(result)
        assertTrue(wakeLockManager.isHeld)
        assertEquals(1, fakeAdapter.acquireCount)
    }

    @Test
    fun acquire_withTimeout_passesTimeoutToAdapter() {
        val result = wakeLockManager.acquire(timeoutMs = 5000L)
        assertTrue(result)
        assertTrue(wakeLockManager.isHeld)
        assertEquals(1, fakeAdapter.acquireCount)
        assertEquals(5000L, fakeAdapter.lastTimeoutMs)
    }

    @Test
    fun acquire_whenAlreadyHeld_isIdempotent() {
        wakeLockManager.acquire()
        assertTrue(wakeLockManager.isHeld)
        assertEquals(1, fakeAdapter.acquireCount)

        // Second acquire while already held
        val result2 = wakeLockManager.acquire()
        assertTrue(result2)
        assertTrue(wakeLockManager.isHeld)
        assertEquals(1, fakeAdapter.acquireCount) // Did not double-acquire
    }

    @Test
    fun release_whenHeld_releasesSuccessfully() {
        wakeLockManager.acquire()
        assertTrue(wakeLockManager.isHeld)

        val result = wakeLockManager.release()
        assertTrue(result)
        assertFalse(wakeLockManager.isHeld)
        assertEquals(1, fakeAdapter.releaseCount)
    }

    @Test
    fun release_whenNotHeld_isSafeAndPreventsUnderLock() {
        assertFalse(wakeLockManager.isHeld)

        // Releasing while not held should not throw or call adapter release
        val result = wakeLockManager.release()
        assertTrue(result)
        assertFalse(wakeLockManager.isHeld)
        assertEquals(0, fakeAdapter.releaseCount)
    }

    @Test
    fun acquire_whenAdapterThrows_returnsFalseGracefully() {
        fakeAdapter.shouldThrowOnAcquire = true

        val result = wakeLockManager.acquire()
        assertFalse(result)
        assertFalse(wakeLockManager.isHeld)
    }

    @Test
    fun release_whenAdapterThrows_returnsFalseGracefully() {
        wakeLockManager.acquire()
        assertTrue(wakeLockManager.isHeld)

        fakeAdapter.shouldThrowOnRelease = true

        val result = wakeLockManager.release()
        assertFalse(result)
    }

    @Test
    fun withoutAdapterOrContext_acquireAndReleaseReturnFalseSafely() {
        val emptyManager = WakeLockManager(adapter = null, context = null)
        assertFalse(emptyManager.isHeld)

        val acquireResult = emptyManager.acquire()
        assertFalse(acquireResult)
        assertFalse(emptyManager.isHeld)

        val releaseResult = emptyManager.release()
        assertTrue(releaseResult)
    }

    @Test
    fun threadSafety_concurrentAcquireAndRelease() {
        val threads = 8
        val iterations = 50
        val latch = CountDownLatch(threads)

        for (i in 0 until threads) {
            thread {
                try {
                    for (j in 0 until iterations) {
                        wakeLockManager.acquire()
                        wakeLockManager.release()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertTrue("All concurrent operations should complete within timeout", completed)
        assertFalse(wakeLockManager.isHeld)
    }

    private class FakeWakeLockAdapter(
        var shouldThrowOnAcquire: Boolean = false,
        var shouldThrowOnRelease: Boolean = false
    ) : WakeLockAdapter {
        private var _isHeld = false
        var acquireCount = 0
        var releaseCount = 0
        var lastTimeoutMs: Long? = null

        override val isHeld: Boolean
            get() = _isHeld

        override fun acquire(timeoutMs: Long?) {
            if (shouldThrowOnAcquire) throw RuntimeException("Simulated acquire failure")
            _isHeld = true
            acquireCount++
            lastTimeoutMs = timeoutMs
        }

        override fun release() {
            if (shouldThrowOnRelease) throw RuntimeException("Simulated release failure")
            if (!_isHeld) throw IllegalStateException("WakeLock under-locked")
            _isHeld = false
            releaseCount++
        }
    }
}
