package ceui.pixiv.ui.translate

import ceui.pixiv.ui.common.ModelDownloadManager

object SakuraModelManager : ModelDownloadManager() {

    override val storageSubDir = "sakura-models"
    override val logTag = "SakuraModel"
    override val readTimeoutSeconds = 300L
}
