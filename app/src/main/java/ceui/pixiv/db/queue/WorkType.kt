package ceui.pixiv.db.queue

object WorkType {
    const val ILLUST = "illust"
    const val MANGA = "manga"

    /**
     * 动图（ugoira）。consumer 走单独 [ceui.pixiv.ui.bulk.downloadUgoira]
     * 管线：getGifPackage → zip 下载 → 解压 → 按「动图保存格式」出 .mp4 / .gif，
     * 不进 Manager.content 的页级并发模型。
     */
    const val UGOIRA = "ugoira"
}
