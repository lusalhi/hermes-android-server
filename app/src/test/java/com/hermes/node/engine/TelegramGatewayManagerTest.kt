package com.hermes.node.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramGatewayManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var manager: TelegramGatewayManager

    @Before
    fun setUp() {
        manager = TelegramGatewayManager(ioDispatcher = testDispatcher)
    }

    @Test
    fun start_withBlankToken_returnsFailure() = runTest(testDispatcher) {
        val result = manager.start("")
        assertTrue(result.isFailure)
        assertFalse(manager.isRunning)
    }

    @Test
    fun stop_resetsState() = runTest(testDispatcher) {
        manager.stop()
        assertFalse(manager.isRunning)
        assertEquals(null, manager.botUsername.value)
    }

    @Test
    fun fetchBotInfo_withInvalidToken_returnsFailure() {
        val result = manager.fetchBotInfo("invalid_token_12345")
        assertTrue(result.isFailure)
    }
}
