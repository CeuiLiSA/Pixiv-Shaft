package ceui.pixiv.ui.translate

import ceui.pixiv.ui.common.ModelDownloadManager

object MangaOcrModelManager : ModelDownloadManager() {

    override val storageSubDir = "manga-ocr-models"
    override val logTag = "MangaOcrModel"
    override val readTimeoutSeconds = 120L
}
