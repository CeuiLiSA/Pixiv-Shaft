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
        // 创建ContentValues用于插入图片
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            // Specify the directory path in the Pictures folder
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_DCIM}/ShaftImages"
            )
        }

        val contentResolver = context.contentResolver ?: return@runCatching // 检查contentResolver是否为null

        // 插入图片并获取URI
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return@runCatching // 如果URI为空，返回

        // 将图片数据写入输出流
        contentResolver.openOutputStream(uri)?.use { outputStream ->
            FileInputStream(imageFile).use { inputStream ->
                inputStream.copyTo(outputStream) // 复制文件内容到输出流
            }
        }
        ToastUtils.show(context.getString(R.string.string_181))
    }.onFailure { ex ->
        // 记录异常信息，方便调试
        when (ex) {
            is IOException -> Timber.e("SaveImage IOException while saving image: ${ex.message}")
            is SecurityException -> Timber.e("SaveImage SecurityException: Permission issue: ${ex.message}")
            else -> Timber.e("SaveImage Unexpected error: ${ex.message}")
        }
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
    try {
        val resolver = context.contentResolver

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName) // 文件名
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain") // 文件类型
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ShaftNovels") // 子目录
        }

        // 插入文件描述符
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw Exception("Failed to create file URI")
        } else {
            throw Exception("Failed to create file URI too low system version")
        }

        // 写入内容到文件
        resolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(content.toByteArray())
            outputStream.flush()
        }

        return true
    } catch (e: Exception) {
        e.printStackTrace()
        return false
    }
}