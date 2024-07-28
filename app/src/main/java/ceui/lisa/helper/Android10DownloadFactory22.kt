package ceui.lisa.helper

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import ceui.lisa.core.DownloadItem
import ceui.lisa.file.LegacyFile
import ceui.lisa.utils.Common
import com.blankj.utilcode.util.FileUtils
import okhttp3.Response
import rxhttp.wrapper.callback.UriFactory
import rxhttp.wrapper.utils.query
import java.io.File
import java.io.OutputStream

class Android10DownloadFactory22 constructor(
    context: Context,
    private val item: DownloadItem,
) : UriFactory(context) {

    lateinit var fileUri: Uri

    override fun query(): Uri? {
        if (item.illust.isGif) {
            val file = LegacyFile.gifZipFile(context, item.illust)
            return Uri.fromFile(file)
        }
        return if (Common.isAndroidQ()) {
            val relativePath: String = FileStorageHelper.getIllustRelativePathQ(item.illust)
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI.query(
                context,
                item.name,
                relativePath
            )
        } else {
            val file = File(FileStorageHelper.getIllustFileFullNameUnderQ(item))
            Uri.fromFile(file)
        }
    }

    override fun insert(response: Response): Uri {
        // gif全部用File操作
        if (item.illust.isGif) {
            val file = LegacyFile.gifZipFile(context, item.illust)
            if(file != null && file.exists() && file.length() > 0 && item.shouldStartNewDownload()){
                FileUtils.delete(file)
            }
            fileUri = Uri.fromFile(file)
            return fileUri
        } else {
            // 大于等于 android 10， 使用 contentResolver insert 生成文件
            if (Common.isAndroidQ()) {
                val relativePath: String = FileStorageHelper.getIllustRelativePathQ(item.illust)
                val uri = query()
                if (uri != null) {
                    // 新下载文件时删除旧文件
                    if (item.shouldStartNewDownload()) {
                        val outputStream: OutputStream =
                            context.contentResolver.openOutputStream(uri, "rwt")!!
                        outputStream.write(ByteArray(0))
                        outputStream.flush()
                        outputStream.close()
                    }
                    fileUri = uri
                    return fileUri
                }
                fileUri = ContentValues().run {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) // 下载到指定目录
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.name) // 文件名
                    // 取contentType响应头作为文件类型


                    // 可能会导致相册生成形如 aaa.png(2).jpg 的图片
//                    put(
//                        MediaStore.MediaColumns.MIME_TYPE,
//                        response.body?.contentType().toString()
//                    )
                    context.contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        this
                    )
                    // 当相同路径下的文件，在文件管理器中被手动删除时，就会插入失败
                } ?: throw NullPointerException("Uri insert failed. Try changing filename")
                return fileUri
            } else {
                // 低于 android 10， 使用 File 操作
                val parentFile = File(FileStorageHelper.getIllustAbsolutePath(item.illust))
                if (!parentFile.exists()) {
                    parentFile.mkdirs()
                }
                val imageFile = File(parentFile, item.name)
                if (imageFile.exists() && imageFile.length() > 0 && item.shouldStartNewDownload()) {
                    FileUtils.delete(imageFile)
                } else {
                    imageFile.createNewFile()
                }

                fileUri = Uri.fromFile(imageFile)
                return fileUri
            }
        }
    }
}
