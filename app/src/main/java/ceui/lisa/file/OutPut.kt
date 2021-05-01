package ceui.lisa.file

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import ceui.lisa.helper.FileStorageHelper
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.Settings
import com.blankj.utilcode.util.FileUtils
import rxhttp.wrapper.utils.query
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

object OutPut {

    @JvmStatic
    fun outPutGif(context: Context, from: File, illust: IllustsBean) {
        if (Common.isAndroidQ()) {
            val relativePath = FileStorageHelper.getIllustRelativePathQ(illust)
            var uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.query(context, from.name, relativePath)
            if (uri != null) {
                val outputStream: OutputStream = context.contentResolver.openOutputStream(uri, "rwt")!!
                outputStream.write(ByteArray(0))
                outputStream.flush()
                outputStream.close()
            } else {
                uri = ContentValues().run {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) // 下载到指定目录
                    put(MediaStore.MediaColumns.DISPLAY_NAME, from.name) // 文件名
                    // 取contentType响应头作为文件类型
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
                    context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, this)
                    // 当相同路径下的文件，在文件管理器中被手动删除时，就会插入失败
                }
            }

            try {
                val bis = BufferedInputStream(FileInputStream(from))
                if (uri != null) {
                    val outputStream: OutputStream = context.contentResolver.openOutputStream(uri)!!
                    val bos = BufferedOutputStream(outputStream)
                    val buffer = ByteArray(1024)
                    var bytes = bis.read(buffer)
                    while (bytes >= 0) {
                        bos.write(buffer, 0, bytes)
                        bos.flush()
                        bytes = bis.read(buffer)
                    }
                    bos.close()
                }
                bis.close()
                Common.showToast("GIF保存成功")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            val parentFile = File(FileStorageHelper.getIllustAbsolutePath(illust))
            if (!parentFile.exists()) {
                parentFile.mkdirs()
            }

            val gifResult = File(parentFile, from.name)
            FileUtils.copy(from, gifResult)
            Common.showToast("GIF保存成功")
        }
    }

    @JvmStatic
    fun outPutNovel(context: Context, from: File, fileName: String) {
        if (Common.isAndroidQ()) {
            val relativePath = FileStorageHelper.getNovelRelativePathQ()
            var uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI.query(context, fileName, relativePath)
            if (uri != null) {
                val outputStream: OutputStream = context.contentResolver.openOutputStream(uri, "rwt")!!
                outputStream.write(ByteArray(0))
                outputStream.flush()
                outputStream.close()
            } else {
                uri = ContentValues().run {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) // 下载到指定目录
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName) // 文件名
                    // 取contentType响应头作为文件类型
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, this)
                    // 当相同路径下的文件，在文件管理器中被手动删除时，就会插入失败
                }
            }

            try {
                val bis = BufferedInputStream(FileInputStream(from))
                if (uri != null) {
                    val outputStream: OutputStream = context.contentResolver.openOutputStream(uri)!!
                    val bos = BufferedOutputStream(outputStream)
                    val buffer = ByteArray(1024)
                    var bytes = bis.read(buffer)
                    while (bytes >= 0) {
                        bos.write(buffer, 0, bytes)
                        bos.flush()
                        bytes = bis.read(buffer)
                    }
                    bos.close()
                }
                bis.close()
                Common.showToast("小说文件保存成功")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            val parentFile = File(Settings.FILE_PATH_NOVEL)
            if (!parentFile.exists()) {
                parentFile.mkdirs()
            }
            val gifResult = File(parentFile, fileName)
            FileUtils.copy(from, gifResult)
            Common.showToast("小说文件保存成功")
        }
    }
}
