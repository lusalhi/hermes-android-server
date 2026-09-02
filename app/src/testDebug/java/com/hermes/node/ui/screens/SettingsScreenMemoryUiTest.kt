package com.hermes.node.ui.screens

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermes.node.viewmodel.ServerStatus
import com.hermes.node.viewmodel.ServerUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Executable UI verification of the Episodic Memory & Storage controls: callback wiring,
 * the destructive-action confirmation gate, the running-daemon backup warning, and the
 * API 28 storage permission gate.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h2400dp")
class SettingsScreenMemoryUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val consistencyWarning =
        "⚠ Server is running — backups may be less consistent. Stop the server for a consistent snapshot."

    private fun setContent(
        state: ServerUiState = ServerUiState(),
        onExportMemoryToDownloads: () -> Unit = {},
        onShowClearMemoryDialog: () -> Unit = {},
        onDismissClearMemoryDialog: () -> Unit = {},
        onConfirmClearMemory: () -> Unit = {}
    ) {
        composeRule.setContent {
            SettingsScreen(
                state = state,
                onUpdateProvider = {},
                onUpdateApiKey = {},
                onUpdateTelegramToken = {},
                onUpdateCustomModel = {},
                onUpdateCustomBaseUrl = {},
                onUpdateAutoStart = {},
                onUpdatePublicTunnel = {},
                onSaveSettings = {},
                onRefreshStorageUsage = {},
                onExportMemoryToDownloads = onExportMemoryToDownloads,
                onShareMemoryBackup = {},
                onShowClearMemoryDialog = onShowClearMemoryDialog,
                onDismissClearMemoryDialog = onDismissClearMemoryDialog,
                onConfirmClearMemory = onConfirmClearMemory,
                onDismissMemoryActionMessage = {}
            )
        }
    }

    @Test
    fun clearMemoryButton_opensConfirmationDialog_insteadOfWipingDirectly() {
        var showDialogCalled = false
        var confirmWipeCalled = false
        setContent(
            state = ServerUiState(storageSizeBytes = 2048L, storageSizeFormatted = "2.0 KB"),
            onShowClearMemoryDialog = { showDialogCalled = true },
            onConfirmClearMemory = { confirmWipeCalled = true }
        )

        composeRule.onNodeWithText("Clear Memory").performClick()

        assertTrue("Clear Memory must route through the dialog callback", showDialogCalled)
        assertFalse("Clear Memory must never wipe directly without confirmation", confirmWipeCalled)
    }

    @Test
    fun clearMemoryDialog_showsDestructiveWarning_andConfirmTriggersWipe() {
        var confirmWipeCalled = false
        var dismissCalled = false
        setContent(
            state = ServerUiState(
                storageSizeBytes = 2048L,
                storageSizeFormatted = "2.0 KB",
                showClearMemoryDialog = true
            ),
            onConfirmClearMemory = { confirmWipeCalled = true },
            onDismissClearMemoryDialog = { dismissCalled = true }
        )

        composeRule.onNodeWithText("Wipe Episodic Memory?").assertExists()
        composeRule
            .onNodeWithText(
                "This will permanently delete all episodic SQLite conversation databases and agent checkpoints. This action cannot be undone.",
                substring = true
            )
            .assertExists()

        composeRule.onNodeWithText("Wipe Memory").performClick()
        assertTrue("Confirm button must invoke the wipe callback", confirmWipeCalled)

        composeRule.onNodeWithText("Cancel").performClick()
        assertTrue("Cancel must invoke the dismiss callback", dismissCalled)
    }

    @Test
    fun whenServerRunning_backupConsistencyWarningIsDisplayed() {
        setContent(
            state = ServerUiState(
                status = ServerStatus.RUNNING,
                storageSizeBytes = 2048L,
                storageSizeFormatted = "2.0 KB"
            )
        )
        composeRule.onNodeWithText(consistencyWarning).assertIsDisplayed()
    }

    @Test
    fun whenServerStopped_noBackupConsistencyWarningIsDisplayed() {
        setContent(
            state = ServerUiState(
                status = ServerStatus.STOPPED,
                storageSizeBytes = 2048L,
                storageSizeFormatted = "2.0 KB"
            )
        )
        composeRule.onNodeWithText(consistencyWarning).assertDoesNotExist()
    }

    @Test
    fun exportButton_dispatchesExportCallback_whenStorageSizePositive() {
        var exportCalled = false
        setContent(
            state = ServerUiState(storageSizeBytes = 2048L, storageSizeFormatted = "2.0 KB"),
            onExportMemoryToDownloads = { exportCalled = true }
        )

        composeRule.onNodeWithText("Export Backup").performClick()

        assertTrue("Export Backup must dispatch the export callback", exportCalled)
    }

    @Test
    @Config(sdk = [28], qualifiers = "w800dp-h2400dp")
    fun onApi28_withoutStoragePermission_exportRequestsPermissionInsteadOfExporting() {
        var exportCalled = false
        setContent(
            state = ServerUiState(storageSizeBytes = 2048L, storageSizeFormatted = "2.0 KB"),
            onExportMemoryToDownloads = { exportCalled = true }
        )

        composeRule.onNodeWithText("Export Backup").performClick()

        assertFalse(
            "On API 28 without a runtime grant, export must wait for the permission flow",
            exportCalled
        )
    }

    @Test
    @Config(sdk = [28], qualifiers = "w800dp-h2400dp")
    fun onApi28_withStoragePermissionGranted_exportDispatchesImmediately() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        var exportCalled = false
        setContent(
            state = ServerUiState(storageSizeBytes = 2048L, storageSizeFormatted = "2.0 KB"),
            onExportMemoryToDownloads = { exportCalled = true }
        )

        composeRule.onNodeWithText("Export Backup").performClick()

        assertTrue("With the runtime grant in place, export must start immediately", exportCalled)
    }
}
