package ceui.pixiv.ui.interpolate

import ceui.pixiv.ui.common.ModelDownloadManager

object RifeModelManager : ModelDownloadManager() {

    override val storageSubDir = "rife-models"
    override val logTag = "RifeModel"
    override val readTimeoutSeconds = 120L
}
