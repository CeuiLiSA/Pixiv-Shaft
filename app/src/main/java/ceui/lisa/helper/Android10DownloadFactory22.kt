package ceui.lisa.helper

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import ceui.lisa.activities.Shaft
import ceui.lisa.core.DownloadItem
import ceui.lisa.download.ImageSaver
import ceui.lisa.file.LegacyFile
import ceui.lisa.utils.Common
import ceui.lisa.utils.Settings
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

    lateinit var fileUri: Uri

    override fun query(): Uri? {
        if (Common.isAndroidQ()) {
            val relativePath: String = Environment.DIRECTORY_PICTURES + "/ShaftImages"
            val relativePathR18: String = Environment.DIRECTORY_PICTURES + "/ShaftImages-R18"
            if (item.illust.isR18File && Shaft.sSettings.isR18DivideSave) {
                return MediaStore.Images.Media.EXTERNAL_CONTENT_URI.query(context, item.name, relativePathR18)
            } else {
                return MediaStore.Images.Media.EXTERNAL_CONTENT_URI.query(context, item.name, relativePath)
            }
        } else {
            if (item.illust.isR18File && Shaft.sSettings.isR18DivideSave) {
                val file = File("${PathUtils.getExternalPicturesPath()}/ShaftImages-R18/${item.name}")
                return Uri.fromFile(file)
            } else {
                val file = File("${PathUtils.getExternalPicturesPath()}/ShaftImages/${item.name}")
                return Uri.fromFile(file)
            }
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
                val relativePath: String = Environment.DIRECTORY_PICTURES + "/ShaftImages"
                val relativePathR18: String = Environment.DIRECTORY_PICTURES + "/ShaftImages-R18"
                if (item.illust.isR18File && Shaft.sSettings.isR18DivideSave) {
                    val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.query(context, item.name, relativePathR18)
                    if (uri != null) {
                        val outputStream: OutputStream = context.contentResolver.openOutputStream(uri, "rwt")!!
                        outputStream.write(ByteArray(0))
                        outputStream.flush()
                        outputStream.close()
                        fileUri = uri
                        return fileUri
                    }
                    fileUri = ContentValues().run {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathR18) //下载到指定目录
                        put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)   //文件名
                        //取contentType响应头作为文件类型
                        put(MediaStore.MediaColumns.MIME_TYPE, response.body?.contentType().toString())
                        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, this)
                        //当相同路径下的文件，在文件管理器中被手动删除时，就会插入失败
                    } ?: throw NullPointerException("Uri insert failed. Try changing filename")
                    return fileUri
                } else {
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
                }

            } else {
                // 低于 android 10， 使用 File 操作
                val parentFile: File
                if (item.illust.isR18File && Shaft.sSettings.isR18DivideSave) {
                    parentFile = File(Settings.FILE_PATH_SINGLE_R18)
                } else {
                    parentFile = File(Settings.FILE_PATH_SINGLE)
                }
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
                }.execute(context)

                fileUri = Uri.fromFile(imageFile)
                return fileUri
            }
        }

    }
}