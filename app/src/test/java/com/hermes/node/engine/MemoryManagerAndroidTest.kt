package com.hermes.node.engine

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * Robolectric verification of the Android-framework integration paths of [MemoryManager]:
 * the FileProvider share-intent contract and the API 29+ MediaStore Downloads export.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryManagerAndroidTest {

    private fun newManager(context: Context): MemoryManager {
        val agentData = File(context.filesDir, "agent_data")
        agentData.mkdirs()
        File(agentData, "memory.db").writeText("robolectric-memory-data-0123456789")
        return MemoryManager(context = context)
    }

    @Test
    fun getShareIntent_withContext_returnsFileProviderShareContract() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = newManager(context)

        val result = manager.getShareIntent()

        assertTrue(result.isSuccess)
        val chooser = result.getOrThrow()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(
            "Chooser must carry FLAG_GRANT_READ_URI_PERMISSION",
            chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        )

        val sendIntent = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull("Chooser must wrap the ACTION_SEND intent", sendIntent)
        assertEquals(Intent.ACTION_SEND, sendIntent!!.action)
        assertEquals("application/zip", sendIntent.type)

        val streamUri = sendIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertNotNull("Share intent must carry EXTRA_STREAM", streamUri)
        assertEquals("content", streamUri!!.scheme)
        assertEquals("${context.packageName}.fileprovider", streamUri.authority)
        assertNotNull("Share intent must attach ClipData for legacy grant propagation", sendIntent.clipData)
        assertTrue(
            "Send intent must carry FLAG_GRANT_READ_URI_PERMISSION",
            sendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        )

        // The staged archive must remain readable for share-sheet recipients.
        val staged = File(context.cacheDir, "backups").listFiles() ?: emptyArray()
        assertTrue("Staged backup archive must exist for recipients", staged.any { it.name.endsWith(".zip") })
    }

    @Test
    fun exportToDownloads_onApi29Plus_publishesThroughMediaStoreAndFinalizesPending() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = RecordingDownloadsProvider()
        ShadowContentResolver.registerProviderInternal("media", provider)
        // The provider mints deterministic URIs (…/downloads/<n>); capture the bytes the
        // manager streams into the first MediaStore entry.
        val firstEntryUri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "0")
        val capturedStream = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(firstEntryUri, capturedStream)
        val manager = newManager(context)

        val result = manager.exportToDownloads()

        assertTrue("Expected successful export, got: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(1, provider.inserted.size)
        val insertedValues = provider.inserted.first()
        assertEquals(1, insertedValues.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals("application/zip", insertedValues.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals(
            "${Environment.DIRECTORY_DOWNLOADS}/HermesNode",
            insertedValues.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)
        )

        assertEquals("Pending flag must be finalized via update", 1, provider.updated.size)
        assertEquals(
            0,
            provider.updated.first().second.getAsInteger(MediaStore.MediaColumns.IS_PENDING)
        )
        assertTrue("Published entry must not be deleted on success", provider.deleted.isEmpty())
        assertTrue(
            "Backup bytes must be written to the MediaStore stream",
            capturedStream.size() > 0
        )
    }

    @Test
    fun exportToDownloads_onApi29Plus_whenFinalizationFails_reportsFailureAndCleansUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = RecordingDownloadsProvider()
        provider.updateResult = 0
        ShadowContentResolver.registerProviderInternal("media", provider)
        val manager = newManager(context)

        val result = manager.exportToDownloads()

        assertFalse("Failed finalization must surface as a failure", result.isSuccess)
        assertTrue(
            "Failed pending entry must be deleted to avoid an inaccessible MediaStore row",
            provider.deleted.isNotEmpty()
        )
        val backupsDir = File(context.cacheDir, "backups")
        val remaining = backupsDir.listFiles() ?: emptyArray()
        assertTrue(
            "Staging cache must be purged after failure, found: ${remaining.map { it.name }}",
            remaining.none { it.name.endsWith(".zip") }
        )
    }

    /**
     * In-memory ContentProvider standing in for the system MediaStore Downloads collection so
     * MemoryManager's real MediaStore branch (insert, stream copy, IS_PENDING finalization,
     * failure cleanup) can be executed and observed in a local unit test.
     */
    private class RecordingDownloadsProvider : ContentProvider() {
        val inserted = mutableListOf<ContentValues>()
        val updated = mutableListOf<Pair<Uri, ContentValues>>()
        val deleted = mutableListOf<Uri>()
        private val streamFiles = mutableMapOf<Uri, File>()
        var updateResult = 1

        override fun onCreate(): Boolean = true

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            val newUri = Uri.withAppendedPath(uri, inserted.size.toString())
            // Snapshot: callers may mutate the ContentValues instance after the call.
            inserted.add(ContentValues(values ?: ContentValues()))
            streamFiles[newUri] = File.createTempFile("mediastore", ".zip")
            return newUri
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int {
            updated.add(uri to ContentValues(values ?: ContentValues()))
            return updateResult
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleted.add(uri)
            return 1
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? = null

        override fun getType(uri: Uri): String? = "application/zip"

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            val file = streamFiles[uri] ?: File.createTempFile("mediastore-fallback", ".zip")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }
    }
}
