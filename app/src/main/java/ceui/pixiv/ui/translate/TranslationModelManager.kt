package ceui.pixiv.ui.translate

import ceui.pixiv.ui.common.ModelDownloadManager

object TranslationModelManager : ModelDownloadManager() {

    override val storageSubDir = "translation-models"
    override val logTag = "TranslationModel"
    override val readTimeoutSeconds = 120L
}
