package ceui.lisa.file

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import ceui.lisa.download.ImageSaver
import ceui.lisa.utils.Common
import com.blankj.utilcode.util.FileUtils
import com.blankj.utilcode.util.PathUtils
import rxhttp.wrapper.utils.query
import java.io.*

object OutPut {

    private val relativePath: String = Environment.DIRECTORY_PICTURES + "/ShaftImages"

    @JvmStatic
    fun outPutGif(context: Context, from: File) {
        if (Common.isAndroidQ()) {
            var uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.query(context, from.name, relativePath)
            if (uri != null) {
                val outputStream: OutputStream = context.contentResolver.openOutputStream(uri, "rwt")!!
                outputStream.write(ByteArray(0))
                outputStream.flush()
                outputStream.close()
            } else {
                uri = ContentValues().run {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) //下载到指定目录
                    put(MediaStore.MediaColumns.DISPLAY_NAME, from.name)   //文件名
                    //取contentType响应头作为文件类型
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
                    context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, this)
                    //当相同路径下的文件，在文件管理器中被手动删除时，就会插入失败
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
            val parent = File(PathUtils.getExternalPicturesPath() + "/ShaftImages")
            if (!parent.exists()) {
                parent.mkdir()
            }
            val gifResult = File(parent, from.name)
            FileUtils.copy(from, gifResult)
            object : ImageSaver() {
                override fun whichFile(): File {
                    return gifResult
                }
            }.execute()
            Common.showToast("GIF保存成功")
        }
    }
}