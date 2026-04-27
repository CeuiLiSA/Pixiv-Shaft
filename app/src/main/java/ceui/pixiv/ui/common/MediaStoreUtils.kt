package ceui.pixiv.ui.common

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import ceui.lisa.R
import ceui.lisa.utils.Common
import com.blankj.utilcode.util.ImageUtils
import com.hjq.toast.ToastUtils
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException


fun saveImageToGallery(context: Context, imageFile: File, displayName: String) {
    runCatching {
        val handle = ceui.pixiv.download.DownloadsRegistry.downloads.openRaw(
            ceui.pixiv.download.model.Bucket.Illust,
            ceui.pixiv.download.model.RelativePath.parse("ShaftImages/$displayName"),
            "image/*",
        ) ?: return@runCatching
        handle.stream.use { outputStream ->
            FileInputStream(imageFile).use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        // Tell the backend the bytes are committed: clears IS_PENDING on
        // MediaStore writes and triggers MediaScanner for SAF / legacy paths.
        // Without this, gallery apps may not see the image until next rescan.
        handle.onFinish()
        ToastUtils.show(context.getString(R.string.string_181))
    }.onFailure { ex ->
        when (ex) {
            is IOException -> Timber.e("SaveImage IOException while saving image: ${ex.message}")
            is SecurityException -> Timber.e("SaveImage SecurityException: Permission issue: ${ex.message}")
            else -> Timber.e("SaveImage Unexpected error: ${ex.message}")
        }
        ToastUtils.show(context.getString(R.string.save_image_failed, ex.message ?: ex.javaClass.simpleName))
    }
}


fun getImageIdInGallery(context: Context, displayName: String): Long? {
    val contentResolver = context.contentResolver

    // 查询 MediaStore 中匹配的图片
    return runCatching {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(displayName)

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                cursor.getLong(idColumnIndex)
            } else {
                null // 未找到匹配的图片
            }
        }
    }.onFailure { ex ->
        // 打印日志以便调试
        Timber.e(ex)
    }.getOrNull() // 如果发生异常，返回 null
}


fun deleteImageById(context: Context, imageId: Long): Boolean {
    // 获取 ContentResolver
    val contentResolver = context.contentResolver

    // 构造图片的 Uri
    val imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId)

    return runCatching {
        // 从 MediaStore 删除图片
        val rowsDeleted = contentResolver.delete(imageUri, null, null)
        if (rowsDeleted > 0) {
            // 删除成功
            true
        } else {
            // 删除失败（可能未找到指定 ID 的图片）
            false
        }
    }.onFailure { ex ->
        // 打印异常日志，便于调试
        Timber.e(ex)
    }.getOrDefault(false) // 如果发生异常，返回 false
}



fun saveToDownloadsScopedStorage(context: Context, fileName: String, content: String): Boolean {
    return try {
        val handle = ceui.pixiv.download.DownloadsRegistry.downloads.openRaw(
            ceui.pixiv.download.model.Bucket.Novel,
            ceui.pixiv.download.model.RelativePath.parse("ShaftNovels/$fileName"),
            "text/plain",
        ) ?: return false
        handle.stream.use { it.write(content.toByteArray()) }
        handle.onFinish()
        true
    } catch (e: Throwable) {
        // Low-level helper — never crashes, just reports failure to caller via
        // `false`. Caller decides whether/how to surface the error (e.g. single
        // download path toasts; batch path collects into a failures dialog).
        Timber.e(e, "saveToDownloadsScopedStorage failed for $fileName")
        false
    }
}

fun getTxtFileIdInDownloads(context: Context, displayName: String): Long? {
    val contentResolver = context.contentResolver

    return runCatching {
        val projection = arrayOf(MediaStore.Downloads._ID) // 查询 ID 列
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.MIME_TYPE} = ?"
        val selectionArgs = arrayOf(displayName, "text/plain") // 文件名和类型条件

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, // 下载目录的 URI
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumnIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    cursor.getLong(idColumnIndex) // 返回文件 ID
                } else {
                    null // 未找到匹配的文件
                }
            }
        } else {
            throw Exception("Failed to create file URI too low system version")
        }
    }.onFailure { ex ->
        // 打印异常日志，便于调试
        Timber.e(ex)
    }.getOrNull() // 如果发生异常，返回 null
}
