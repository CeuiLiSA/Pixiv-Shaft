package ceui.lisa.helper

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import ceui.lisa.core.DownloadItem
import ceui.lisa.download.DownloadFileFactory
import ceui.lisa.download.MediaStoreUtil
import ceui.lisa.file.LegacyFile
import ceui.lisa.utils.Common
import com.blankj.utilcode.util.FileUtils
import java.io.File
import java.io.OutputStream

class Android10DownloadFactory22 constructor(
    private val context: Context,
    private val item: DownloadItem,
) : DownloadFileFactory {

    private lateinit var _fileUri: Uri

    override fun query(): Uri? {
        if (item.illust.isGif) {
            val file = LegacyFile.gifZipFile(context, item.illust)
            return Uri.fromFile(file)
        }
        return if (Common.isAndroidQ()) {
            val relativePath: String = FileStorageHelper.getIllustRelativePathQ(item.illust)
            MediaStoreUtil.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                context,
                item.name,
                relativePath
            )
        } else {
            val file = File(FileStorageHelper.getIllustFileFullNameUnderQ(item))
            Uri.fromFile(file)
        }
    }

    override fun insert(): Uri {
        // gif全部用File操作
        if (item.illust.isGif) {
            val file = LegacyFile.gifZipFile(context, item.illust)
            if(file != null && file.exists() && file.length() > 0 && item.shouldStartNewDownload()){
                FileUtils.delete(file)
            }
            _fileUri = Uri.fromFile(file)
            return _fileUri
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
                    _fileUri = uri
                    return _fileUri
                }
                _fileUri = ContentValues().run {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                    context.contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        this
                    )
                } ?: throw NullPointerException("Uri insert failed. Try changing filename")
                return _fileUri
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

                _fileUri = Uri.fromFile(imageFile)
                return _fileUri
            }
        }
    }

    override fun getFileUri(): Uri = _fileUri
}
