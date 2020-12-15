package ceui.lisa.core

import android.content.Context
import android.net.Uri
import android.os.Environment
import ceui.lisa.activities.Shaft
import ceui.lisa.download.FileCreator
import ceui.lisa.utils.Common
import okhttp3.Response
import rxhttp.wrapper.callback.UriFactory

class Android10DownloadFactory constructor(
        context: Context,
        private val item: DownloadItem,
) : UriFactory(context) {

    lateinit var fileUri: Uri
    var fileLength: Long = 0L


    override fun insert(response: Response): Uri {
        return if (Common.isAndroidQ()) {
            val documentFile = SAFile.getDocument(context, item.illust, item.index)
            fileLength = documentFile.length()
            fileUri = documentFile.uri
            fileUri
        } else {
            val file = FileCreator.createIllustFile(item.illust, item.index)
            fileLength = file.length()
            fileUri = Uri.fromFile(file)
            fileUri
        }
    }
}