package ceui.pixiv.download

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import ceui.pixiv.download.backend.InFlightMediaStoreWrites
import ceui.pixiv.download.backend.MediaStoreBackend
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.RelativePath
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaStorePublicationTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var provider: PublicationProvider
    private val backend get() = MediaStoreBackend(RuntimeEnvironment.getApplication(), StorageChoice.MediaStore.Collection.Images)
    private val path = RelativePath.parse("publication.png")

    @Before fun setUp() {
        provider = Robolectric.buildContentProvider(PublicationProvider::class.java).create("media").get()
        provider.file = temp.newFile()
    }

    @Test fun `insert publication rejects both provider exceptions and zero updated rows`() {
        verifyFailure(replace = false)
    }

    @Test fun `replace publication rejects both provider exceptions and zero updated rows`() {
        verifyFailure(replace = true)
    }

    private fun verifyFailure(replace: Boolean) {
        for (throws in listOf(true, false)) {
            provider.throwOnPublish = throws
            provider.publishCount = 0
            val handle = if (replace) backend.replace(path, "image/png") else backend.open(path, "image/png")
            handle.stream.use { it.write(byteArrayOf(1, 2, 3)) }
            try {
                assertThrows(if (throws) SecurityException::class.java else IllegalStateException::class.java) {
                    handle.onFinish()
                }
                assertTrue(InFlightMediaStoreWrites.contains(42))
            } finally {
                handle.onAbort()
            }
            assertFalse(InFlightMediaStoreWrites.contains(42))
            assertEquals(if (replace) 0 else 1, provider.deletes)
            provider.deletes = 0
        }
    }

    @Test fun `successful insert and replace publish and untrack the row`() {
        for (replace in listOf(false, true)) {
            provider.publishCount = 1
            val handle = if (replace) backend.replace(path, "image/png") else backend.open(path, "image/png")
            handle.stream.close()
            handle.onFinish()
            assertFalse(InFlightMediaStoreWrites.contains(42))
            assertEquals(0, provider.deletes)
        }
    }

    @Test
    @Config(sdk = [28], shadows = [RejectingScanner::class])
    fun `legacy scanner failure preserves the completed file`() {
        val handle = backend.open(path, "image/png")
        val bytes = byteArrayOf(1, 2, 3)
        try {
            handle.stream.use { it.write(bytes) }
            handle.onFinish()
            assertArrayEquals(bytes, File(handle.uri.path!!).readBytes())
        } finally {
            File(handle.uri.path!!).delete()
        }
    }

    @Implements(MediaScannerConnection::class)
    class RejectingScanner {
        companion object {
            @JvmStatic
            @Implementation
            fun scanFile(context: Context, paths: Array<String>, mimeTypes: Array<String>?, callback: MediaScannerConnection.OnScanCompletedListener?) {
                throw SecurityException("scanner unavailable")
            }
        }
    }

    class PublicationProvider : ContentProvider() {
        lateinit var file: File
        var throwOnPublish = false
        var publishCount = 1
        var deletes = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun insert(uri: Uri, values: ContentValues?) = Uri.withAppendedPath(uri, "42")
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
            MatrixCursor(projection).apply {
                addRow(projection!!.map { if (it == MediaStore.MediaColumns._ID) 42L else "Pictures/" })
            }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            if (values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0) {
                if (throwOnPublish) throw SecurityException("publication rejected")
                return publishCount
            }
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deletes++
            return 1
        }
    }
}
