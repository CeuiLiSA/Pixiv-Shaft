package ceui.pixiv.ui.translate

import ceui.pixiv.ui.common.ModelDownloadManager

object NllbModelManager : ModelDownloadManager() {

    override val storageSubDir = "nllb-models"
    override val logTag = "NllbModel"
    override val readTimeoutSeconds = 180L
}
