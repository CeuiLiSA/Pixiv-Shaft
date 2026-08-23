package ceui.pixiv.download

import ceui.lisa.activities.Shaft
import ceui.loxia.Illust
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.ui.task.DownloadNovelTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
     * 落盘串行跑在 IO 线程：调用方多半是 UI 点击（[ceui.lisa.download.IllustDownload]
     * 各入口）或 Main 协程，SAF 的 createFile / MediaStore 的 replace 都是多次 binder
     * 往返，不能压在主线程上。限 1 并发是为了同一作品被连点两次时不会两条写同一
     * 路径互相踩（Replace 策略下会一边删一边建）。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /**
     * 导出一次作品简介（异步、fire-and-forget）。
     *
     * 应在「下载这个作品」的入口调用一次，而不是每张 page 完成时调用。
     * 开关关闭 / 无简介 / 不支持的 type / 字数不够时在调用线程上直接返回，不起协程。
     */
    @JvmStatic
    fun export(illust: Illust?) {
        val prepared = prepare(illust, force = false) ?: return
        scope.launch { write(prepared.first, prepared.second) }
    }

    /**
     * 手动导出一次作品简介：供简介区「下载简介」按钮使用，不受自动导出开关限制，
     * 也不受自动导出的最少字数限制（只要简介非空即可）。
     *
     * 挂起直到落盘完成；写入仍在 [scope] 的 IO 线程上执行，返回时已切回调用协程
     * （Fragment 侧是 Main），方便直接弹 toast。
     */
    suspend fun exportManual(illust: Illust?): Boolean {
        val prepared = prepare(illust, force = true) ?: return false
        return scope.async { write(prepared.first, prepared.second) }.await()
    }

    /** 返回 null 表示本次不导出；否则返回 (作品, 清洗后的简介)。 */
    private fun prepare(illust: Illust?, force: Boolean): Pair<Illust, String>? {
        if (!force && !Shaft.sSettings.isAutoExportIllustCaption) return null
        if (illust == null) return null
        if (illust.type !in SUPPORTED_TYPES) return null
        val caption = DownloadNovelTask.replaceBrWithNewLine(illust.caption).trim()
        if (caption.isBlank()) return null
        if (!force) {
            val minLength = Shaft.sSettings.autoExportCaptionMinLength.coerceAtLeast(1)
            if (caption.length < minLength) return null
        }
        return illust to caption
    }

    private fun write(illust: Illust, caption: String): Boolean {
        return try {
            val destination = DownloadItems.illustCaptionDestination(illust)
            // openRaw 返回 null = Skip 策略且文件已存在，按策略跳过（不算失败）。
            val handle = DownloadsRegistry.downloads.openRaw(Bucket.Caption, destination, "text/plain")
                ?: return true
            try {
                handle.stream.use { it.write(buildContent(illust, caption).toByteArray(Charsets.UTF_8)) }
                handle.onFinish()
                true
            } catch (t: Throwable) {
                // 写一半失败要把 pending 行撤掉，否则 MediaStore 会留下 0 字节的
                // `.pending-` 孤儿文件（同 ExportUtils.saveToDownloads 的处理）。
                handle.onAbort()
                throw t
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "export caption failed illust=${illust.id}")
            false
        }
    }

    private fun buildContent(illust: Illust, caption: String): String {
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
