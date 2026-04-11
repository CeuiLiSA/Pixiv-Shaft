package ceui.pixiv.ui.upscale

import android.content.Context
import ceui.pixiv.ui.common.DownloadableModel
import ceui.pixiv.ui.common.ModelDownloadManager

object RembgModelManager : ModelDownloadManager() {

    override val storageSubDir = "rembg-models"
    override val logTag = "RembgModel"

    override fun isModelReady(context: Context, model: DownloadableModel): Boolean {
        if (model is RembgModel && model.bundledInApk) return true
        return super.isModelReady(context, model)
    }
}
