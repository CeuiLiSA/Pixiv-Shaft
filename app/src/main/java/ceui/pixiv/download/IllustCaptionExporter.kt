package ceui.pixiv.download

import ceui.lisa.activities.Shaft
import ceui.lisa.models.IllustsBean
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.ui.task.DownloadNovelTask
import timber.log.Timber

/**
 * 插画/漫画下载时，在作品进入 Manager 下载队列前，把作品简介（caption）导出一份 txt。
 *
 * 只在 [SUPPORTED_TYPES]（illust / manga）生效；小说、动图不参与。
 * 内容参照小说 TXT 的信息头风格：标题 / 作者 / 作品ID / 链接 / 标签 / 简介。
 * 落盘路径走独立的 [Bucket.Caption] 桶，默认不与图片混放。
 */
object IllustCaptionExporter {

    private const val TAG = "IllustCaptionExporter"

    /** 当前只对插画 / 漫画生效。 */
    private val SUPPORTED_TYPES = setOf("illust", "manga")

    /**
     * 导出一次作品简介。
     *
     * 应在「下载这个作品」的入口调用一次，而不是每张 page 完成时调用。
     *
     * @return true 表示确实写入了；无简介 / 不支持的 type / 按覆盖策略跳过时返回 false。
     */
    @JvmStatic
    fun export(illust: IllustsBean?): Boolean {
        if (!Shaft.sSettings.isAutoExportIllustCaption) return false
        if (illust == null) return false
        if (illust.type !in SUPPORTED_TYPES) return false
        val caption = DownloadNovelTask.replaceBrWithNewLine(illust.caption).trim()
        if (caption.isBlank()) return false
        val minLength = Shaft.sSettings.getAutoExportCaptionMinLength().coerceAtLeast(1)
        if (caption.length < minLength) return false

        return try {
            val destination = DownloadItems.illustCaptionDestination(illust)
            val handle = DownloadsRegistry.downloads.openRaw(Bucket.Caption, destination, "text/plain")
                ?: return false
            handle.stream.use { it.write(buildContent(illust, caption).toByteArray(Charsets.UTF_8)) }
            handle.onFinish()
            true
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "export caption failed illust=${illust.id}")
            false
        }
    }

    private fun buildContent(illust: IllustsBean, caption: String): String {
        val sb = StringBuilder()
        sb.append("标题：").append(illust.title.orEmpty()).append("\n\n")
        sb.append("作者：").append(illust.user?.name.orEmpty()).append("\n\n")
        sb.append("作者ID：").append(illust.user?.id ?: "").append("\n\n")
        sb.append("作品ID：").append(illust.id).append("\n\n")
        sb.append("作品链接：https://www.pixiv.net/artworks/").append(illust.id).append("\n\n")
        val tags = illust.tags.orEmpty()
            .mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
        if (tags.isNotEmpty()) {
            sb.append("标签：").append(tags.joinToString(", ")).append("\n\n")
        }
        sb.append("简介：\n").append(caption).append('\n')
        return sb.toString()
    }
}
