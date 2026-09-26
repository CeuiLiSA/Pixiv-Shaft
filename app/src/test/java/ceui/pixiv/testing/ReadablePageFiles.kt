package ceui.pixiv.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import org.robolectric.Robolectric

/**
 * 让 `content://` 在 Robolectric 里**真的打得开**。
 *
 * RecordedPageProbe 判断「这条下载记录的文件还在不在」靠的是
 * `ContentResolver.openFileDescriptor`。ShadowContentResolver 只 shadow 了
 * `openInputStream` / `openOutputStream`，**没有** shadow `openFileDescriptor` ——
 * 所以那个调用会走真实现去找 ContentProvider，光注册一条 InputStream 是喂不到它的。
 * 这里注册一个真的 provider，把 `content://<authority>/<编码后的绝对路径>` 映射到磁盘上的文件。
 */
object ReadablePageFiles {

    const val AUTHORITY = "shaft-test-pages"

    private val files = HashMap<String, File>()

    /** 造一个可读的 `content://` uri，字节会真的写进临时文件。 */
    fun readable(bytes: ByteArray = byteArrayOf(1, 2, 3)): Uri {
        val file = File.createTempFile("readable-page-", ".jpg").apply {
            writeBytes(bytes)
            deleteOnExit()
        }
        return of(file)
    }

    /** 把一个已存在的文件包成可读的 `content://` uri。 */
    fun of(file: File): Uri {
        files[file.absolutePath] = file
        // 必须走 buildContentProvider：registerProviderInternal 只往 map 里塞、不 attach，
        // 拿到的 IContentProvider 是空的，openFileDescriptor 根本到不了 openFile。
        Robolectric.buildContentProvider(PageFileProvider::class.java).create(AUTHORITY)
        return Uri.parse("content://$AUTHORITY/${Uri.encode(file.absolutePath)}")
    }

    /** 一个永远打不开的 uri：文件早被用户在文件管理器里删了。 */
    fun missing(path: String): Uri = Uri.parse("content://$AUTHORITY/$path")

    class PageFileProvider : ContentProvider() {

        override fun onCreate(): Boolean = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val path = uri.path?.removePrefix("/")?.let(Uri::decode)
            val file = path?.let { files[it] } ?: throw FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String = "image/jpeg"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }
}