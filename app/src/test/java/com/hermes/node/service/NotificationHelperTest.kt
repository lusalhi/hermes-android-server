package com.hermes.node.service

import android.app.Notification
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationHelperTest {

    @Test
    fun constants_haveExpectedValues() {
        assertEquals("hermes_server_channel", NotificationHelper.CHANNEL_ID)
        assertEquals("Hermes Node Server", NotificationHelper.CHANNEL_NAME)
        assertEquals("Ongoing background daemon status and control", NotificationHelper.CHANNEL_DESCRIPTION)
        assertEquals(1001, NotificationHelper.NOTIFICATION_ID)
        assertEquals("com.hermes.node.action.STOP", NotificationHelper.ACTION_STOP)
        assertEquals("com.hermes.node.action.START", NotificationHelper.ACTION_START)
    }

    @Test
    fun helper_object_isNotNull() {
        assertNotNull(NotificationHelper)
    }

    @Test
    fun buildNotification_producesOngoingNotification_withExpectedProperties() {
        val context = ContextWrapper(null)
        val notification = NotificationHelper.buildNotification(context, "Hermes Node daemon running on port 8000")

        assertNotNull(notification)
        assertTrue("Notification should have ongoing flag set", (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
    }
}
