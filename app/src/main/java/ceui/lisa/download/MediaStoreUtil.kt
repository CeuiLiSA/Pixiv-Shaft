package ceui.lisa.download

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore

/**
 * 替代 rxhttp.wrapper.utils.query / UriUtil
 */
object MediaStoreUtil {

    /**
     * 在 MediaStore 中按文件名 + 相对路径查询已有 Uri。
     * 替代 rxhttp.wrapper.utils.query 扩展函数。
     */
    @JvmStatic
    fun query(uri: Uri, context: Context, displayName: String, relativePath: String): Uri? {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(displayName, relativePath)
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return Uri.withAppendedPath(uri, id.toString())
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    /**
     * 获取 Uri 指向文件的大小（字节数），失败返回 -1。
     * 替代 UriUtil.length(Uri, Context)。
     */
    @JvmStatic
    fun length(uri: Uri?, context: Context): Long {
        if (uri == null) return -1
        return try {
            if ("file" == uri.scheme) {
                val path = uri.path ?: return -1
                java.io.File(path).length()
            } else {
                val resolver = context.contentResolver
                val fd = resolver.openFileDescriptor(uri, "r") ?: return -1
                fd.use { it.statSize }
            }
        } catch (e: Exception) {
            -1
        }
    }
}
