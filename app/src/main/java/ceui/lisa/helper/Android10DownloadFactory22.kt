package ceui.lisa.helper

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import ceui.lisa.core.DownloadItem
import ceui.lisa.download.ImageSaver
import ceui.lisa.file.LegacyFile
import ceui.lisa.utils.Common
import com.blankj.utilcode.util.FileUtils
import com.blankj.utilcode.util.PathUtils
import okhttp3.Response
import rxhttp.wrapper.callback.UriFactory
import rxhttp.wrapper.utils.query
import java.io.File
import java.io.OutputStream

class Android10DownloadFactory22 constructor(
        context: Context,
        private val item: DownloadItem,
) : UriFactory(context) {

    private val relativePath: String = Environment.DIRECTORY_PICTURES + "/ShaftImages"
    lateinit var fileUri: Uri

    override fun query(): Uri? {
        if (Common.isAndroidQ()) {
            return MediaStore.Images.Media.EXTERNAL_CONTENT_URI.query(context, item.name, relativePath)
        } else {
            val file = File("${PathUtils.getExternalPicturesPath()}/ShaftImages/${item.name}")
            return Uri.fromFile(file)
        }
    }

    override fun insert(response: Response): Uri {
        // gif全部用File操作
        if (item.illust.isGif) {
            val file = LegacyFile().gifZipFile(context, item.illust)
            fileUri = Uri.fromFile(file)
            return fileUri
        } else {
            // 大于等于 android 10， 使用 contentResolver insert 生成文件
            if (Common.isAndroidQ()) {
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.query(context, item.name, relativePath)
                if (uri != null) {
                    val outputStream: OutputStream = context.contentResolver.openOutputStream(uri, "rwt")!!
                    outputStream.write(ByteArray(0))
                    outputStream.flush()
                    outputStream.close()
                    fileUri = uri
                    return fileUri
                }
                fileUri = ContentValues().run {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) //下载到指定目录
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)   //文件名
                    //取contentType响应头作为文件类型
                    put(MediaStore.MediaColumns.MIME_TYPE, response.body?.contentType().toString())
                    context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, this)
                    //当相同路径下的文件，在文件管理器中被手动删除时，就会插入失败
                } ?: throw NullPointerException("Uri insert failed. Try changing filename")
                return fileUri
            } else {
                // 低于 android 10， 使用 File 操作


                val parentFile = File(PathUtils.getExternalPicturesPath() + "/ShaftImages")
                if (!parentFile.exists()) {
                    parentFile.mkdir()
                }
                val imageFile = File(parentFile, item.name)
                if (imageFile.exists() && imageFile.length() > 0) {
                    FileUtils.delete(imageFile)
                } else {
                    imageFile.createNewFile()
                }

                object : ImageSaver() {
                    override fun whichFile(): File {
                        return imageFile
                    }
                }.execute()

                fileUri = Uri.fromFile(imageFile)
                return fileUri
            }
        }

    }
}