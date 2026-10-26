package ceui.pixiv.actions

import android.content.Context
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.fragments.WebNovelParser
import ceui.loxia.Novel
import ceui.pixiv.actionqueue.ActionEvent
import ceui.pixiv.actionqueue.ActionHandler
import ceui.pixiv.actionqueue.ActionOutcome
import ceui.pixiv.actionqueue.ActionRequest
import ceui.pixiv.actionqueue.PendingAction
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.NovelSeriesResp
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.download.header.HeaderConfigRepo
import ceui.pixiv.download.header.NovelHeaderRenderer
import ceui.pixiv.download.model.RelativePath
import ceui.pixiv.download.toast.DownloadToastKind
import ceui.pixiv.download.toast.DownloadToasts
import ceui.pixiv.ui.common.getTxtFileIdInDownloads
import ceui.pixiv.ui.novel.reader.export.EpubImageRetryPolicy
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.ExportResult
import ceui.pixiv.ui.novel.reader.export.ExportUtils
import ceui.pixiv.ui.novel.reader.export.NovelExportManager
import ceui.pixiv.ui.novel.reader.paginate.ContentParser
import ceui.pixiv.ui.task.DownloadNovelTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import timber.log.Timber
import java.util.UUID

/**
 * 格式按枚举名存、读时自己查表：payload 落库要跨版本存活，而 gson 反射枚举常量依赖 R8 不改动它，
 * 这个前提没有 keep 规则兜着。认不出（版本回退后读到新版的格式）返回 null。
 */
private fun exportFormatOf(name: String?): ExportFormat? =
    ExportFormat.entries.firstOrNull { it.name == name }

/**
 * 一篇小说的下载。批量 / 单篇 / 系列选择下载都拆成「一篇一条」进下载队列，
 * 由 [PixivActionQueue] 的下载队列串行执行 —— 页面退出不再连坐取消，进程被杀也能续上。
 *
 * @param format      [ExportFormat] 的枚举名，见 [exportFormatOf]。
 * @param seriesOrder 本篇在**系列**中的 1-based 位置（不是在本批里的位置 —— 勾选第 3、5、9 章时
 *                    必须还是 3、5、9）。喂给路径模板的 `{series_order}`（#964）和信息头的
 *                    「第 X / Y 篇」（#710）。不是同一系列的批量为 null。
 * @param seriesTotal 系列总篇数，只在 [seriesOrder] 非空时有意义。
 * @param batchId     同一次点击入队的那一批共用，反馈侧按它攒「全部完成 / N 篇失败」。
 * @param index       本篇在这一批里的 1-based 序号，进度 toast 用。
 * @param total       这一批的篇数。
 */
internal data class NovelDownloadPayload(
    val novel: Novel,
    val format: String,
    val seriesOrder: Int? = null,
    val seriesTotal: Int? = null,
    val batchId: String,
    val index: Int,
    val total: Int,
)

/** 系列页「批量下载」：先在队列里拉全章节列表，再展开成逐篇的 [NovelDownloadPayload]。 */
internal data class NovelSeriesDownloadPayload(
    val seriesId: Long,
    /** [ExportFormat] 的枚举名，见 [exportFormatOf]。 */
    val format: String,
)

/**
 * 把一批小说拆成逐篇的下载请求。
 *
 * 合并键带上格式：同一篇先后要 TXT 和 EPUB 是两件事，不该互相吃掉。
 */
internal fun novelDownloadRequests(
    novels: List<Novel>,
    format: ExportFormat,
    seriesPositions: Map<Long, Int>? = null,
    seriesTotal: Int? = null,
): List<ActionRequest> {
    val batchId = UUID.randomUUID().toString()
    return novels.mapIndexed { i, novel ->
        val order = seriesPositions?.get(novel.id)
        ActionRequest(
            type = PixivActionTypes.NOVEL_DOWNLOAD,
            dedupeKey = "${PixivActionTypes.NOVEL_DOWNLOAD}:${novel.id}:${format.name}",
            payload = Shaft.sGson.toJson(
                NovelDownloadPayload(
                    novel = novel,
                    format = format.name,
                    seriesOrder = order,
                    seriesTotal = seriesTotal.takeIf { order != null },
                    batchId = batchId,
                    index = i + 1,
                    total = novels.size,
                )
            ),
        )
    }
}

internal fun novelSeriesDownloadRequest(seriesId: Long, format: ExportFormat): ActionRequest =
    ActionRequest(
        type = PixivActionTypes.NOVEL_SERIES_DOWNLOAD,
        dedupeKey = "${PixivActionTypes.NOVEL_SERIES_DOWNLOAD}:$seriesId:${format.name}",
        payload = Shaft.sGson.toJson(NovelSeriesDownloadPayload(seriesId, format.name)),
    )

/**
 * 抓正文 + 落盘。幂等契约靠「已存在就跳过 / 按覆盖策略写」满足：进程被杀后重跑同一篇，
 * 最坏是多打一次 `getNovelText`。
 *
 * 只有抓正文这一步按 [toActionOutcome] 分类（429 整队冷却、断网不扣预算）；解析和写盘的失败
 * 重试也不会变好，直接判终态失败。
 */
internal class NovelDownloadHandler(private val isOnline: () -> Boolean) : ActionHandler {

    @Suppress("SENSELESS_COMPARISON")
    override suspend fun execute(action: PendingAction): ActionOutcome {
        val payload = action.parsePayload<NovelDownloadPayload>()
            // gson 绕过 Kotlin 空安全：缺字段时 novel 会是 null。
            ?.takeIf { it.novel != null }
            ?: return badPayload(action)
        val format = exportFormatOf(payload.format) ?: return badPayload(action)
        val ctx = Shaft.getContext()
        val novel = payload.novel
        val destination = DownloadItems.novelDestinationFromLoxia(
            novel,
            extOverride = format.extension,
            seriesOrder = payload.seriesOrder,
            seriesTotal = payload.seriesTotal,
        )
        // MediaStore Downloads 里已有同名 TXT 就不打网络；SAF 等位置要到写盘时才知道。
        if (format == ExportFormat.Txt && getTxtFileIdInDownloads(ctx, destination.filename) != null) {
            Timber.tag(TAG).d("%s already exists, skipping", destination.filename)
            return ActionOutcome.Success
        }

        val html = try {
            Client.appApi.getNovelText(novel.id).string()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return t.toActionOutcome(isOnline())
        }

        return try {
            persist(ctx, payload, format, destination, html)
            ActionOutcome.Success
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "novel download failed: %d (%s)", novel.id, novel.title)
            ActionOutcome.Fail(t.message ?: t.javaClass.simpleName, t)
        }
    }

    private suspend fun persist(
        ctx: Context,
        payload: NovelDownloadPayload,
        format: ExportFormat,
        destination: RelativePath,
        html: String,
    ) {
        val novel = payload.novel
        val wNovel = WebNovelParser.parsePixivObject(html)?.novel
            ?: throw RuntimeException("invalid web novel")

        // 非 TXT 格式直接复用阅读器导出链路，避免在批量下载里再维护一套 MD/EPUB/PDF 实现。
        if (format != ExportFormat.Txt) {
            when (val result = NovelExportManager.export(
                context = ctx,
                format = format,
                novel = novel,
                webNovel = wNovel,
                tokens = ContentParser.tokenize(wNovel),
                seriesOrder = payload.seriesOrder,
                seriesTotal = payload.seriesTotal,
                // 不带 Activity：失败时取当时的前台页弹重试框，应用在后台则只记一笔。
                retryPolicy = EpubImageRetryPolicy.Batch(),
            )) {
                is ExportResult.Success, is ExportResult.Skipped -> Unit
                is ExportResult.Failure -> throw RuntimeException(result.message)
            }
            return
        }

        val buffer = StringBuffer().apply {
            append("\n\n")
            append("<===== Shaft Novel Start =====>")
            append("\n\n")
            append(
                NovelHeaderRenderer.render(
                    novel = novel,
                    preset = HeaderConfigRepo.activePreset(),
                    isSeriesChapter = novel.series != null,
                    seriesIndex = payload.seriesOrder,
                    seriesTotal = payload.seriesTotal,
                )
            )
            append("\n")
            append("正文：")
            append("\n\n")
            append(DownloadNovelTask.replaceBrWithNewLine(wNovel.text))
            append("\n\n")
            append("<===== Shaft Novel End =====>")
            append("\n\n")
        }

        // null = 覆盖策略为「已存在则跳过」且目标已存在；写入失败直接抛出，带着真实原因判终态失败。
        val uri = ExportUtils.saveToDownloads(ctx, destination, format.mimeType) {
            it.write(buffer.toString().toByteArray(Charsets.UTF_8))
        }
        if (uri == null) {
            Timber.tag(TAG).d("%s already exists, skipping", destination.filename)
            return
        }
        NovelExportManager.recordDownload(novel, uri)
    }

    private companion object {
        const val TAG = "NovelDownloadHandler"
    }
}

/**
 * 系列页「批量下载」：分页拉全章节，再按系列顺序展开成逐篇下载。
 *
 * 拉列表这一段也必须在队列里：长系列要翻十几页、每页隔 1.5 秒，挂在页面上的话用户一退页
 * 连列表都拉不完。展开用 [enqueue]（落库后才返回）—— 本条判成功就会被删掉，章节行必须先落盘。
 * 进程在展开中途被杀时本条会重跑，已入队的章节靠合并键去重。
 */
internal class NovelSeriesDownloadHandler(
    private val isOnline: () -> Boolean,
    private val enqueue: suspend (ActionRequest) -> Unit,
) : ActionHandler {

    override suspend fun execute(action: PendingAction): ActionOutcome {
        val payload = action.parsePayload<NovelSeriesDownloadPayload>() ?: return badPayload(action)
        val format = exportFormatOf(payload.format) ?: return badPayload(action)

        val novels = mutableListOf<Novel>()
        try {
            var page: NovelSeriesResp = Client.appApi.getNovelSeries(payload.seriesId)
            novels += page.displayList
            while (true) {
                val next = page.nextPageUrl
                if (next.isNullOrEmpty()) break
                delay(PAGE_GAP_MS)
                page = Shaft.sGson.fromJson(
                    Client.appApi.generalGet(next).string(),
                    NovelSeriesResp::class.java,
                )
                novels += page.displayList
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return t.toActionOutcome(isOnline())
        }

        val ordered = novels.distinctBy { it.id }
        if (ordered.isEmpty()) {
            return ActionOutcome.Fail(
                Shaft.getContext()?.getString(R.string.merge_download_failed_empty) ?: "empty series"
            )
        }
        // 拉到的就是整个系列的有序章节，位置 = 下标 + 1。
        val positions = ordered.withIndex().associate { (i, n) -> n.id to i + 1 }
        novelDownloadRequests(ordered, format, positions, ordered.size).forEach { enqueue(it) }
        return ActionOutcome.Success
    }

    private companion object {
        /** 翻页间隔，沿用原系列下载的取值。 */
        const val PAGE_GAP_MS = 1_500L
    }
}

/**
 * 下载队列的用户反馈：逐篇进度、整批结果、系列展开失败。
 *
 * 页面可能早就不在了，所以反馈只能由队列宿主发。整批计数只活在内存里：进程被杀后续跑的那批
 * 照样有逐篇进度（序号取自 payload），只是凑不齐整批结果那一条。
 *
 * 只在 [PixivActionQueue] 的事件收集协程里调用，串行，不需要加锁。
 */
internal class NovelDownloadFeedback(private val app: Context) {

    private class Tally(var done: Int = 0, var failed: Int = 0)

    private val tallies = HashMap<String, Tally>()

    fun onEvent(event: ActionEvent) {
        val action = event.action
        when (event) {
            is ActionEvent.Succeeded -> if (action.type == PixivActionTypes.NOVEL_DOWNLOAD) {
                onNovelSettled(action, failureReason = null)
            }

            is ActionEvent.Failed -> {
                Timber.tag(TAG).w(
                    "download action failed: type=%s reason=%s", action.type, event.reason,
                )
                when (action.type) {
                    PixivActionTypes.NOVEL_DOWNLOAD -> onNovelSettled(action, failureReason = event.reason)
                    PixivActionTypes.NOVEL_SERIES_DOWNLOAD -> DownloadToasts.show(
                        DownloadToastKind.NOVEL_SERIES_EXPORT,
                        app.getString(R.string.msg_operation_fail, event.reason),
                    )
                }
            }

            // 还会再试，不打扰用户。
            is ActionEvent.Retrying -> Unit
        }
    }

    /**
     * @param failureReason 终态失败的原因，成功为 null。失败那一篇当场报出标题和原因 ——
     *        发起的页面多半已经关了，没有地方再弹一张失败明细。
     */
    private fun onNovelSettled(action: PendingAction, failureReason: String?) {
        val payload = action.parsePayload<NovelDownloadPayload>() ?: return
        if (failureReason == null) {
            DownloadToasts.show(
                DownloadToastKind.NOVEL_PROGRESS,
                app.getString(R.string.batch_download_progress, payload.index, payload.total),
            )
        } else {
            DownloadToasts.show(
                DownloadToastKind.NOVEL_BATCH_RESULT,
                app.getString(
                    R.string.batch_download_failure_line, payload.novel.title.orEmpty(), failureReason,
                ),
            )
        }
        val tally = tallies.getOrPut(payload.batchId) { Tally() }
        tally.done++
        if (failureReason != null) tally.failed++
        if (tally.done < payload.total) return
        tallies.remove(payload.batchId)
        DownloadToasts.show(
            DownloadToastKind.NOVEL_BATCH_RESULT,
            if (tally.failed == 0) {
                app.getString(R.string.batch_download_all_ok)
            } else {
                app.getString(R.string.batch_download_some_failed, tally.failed)
            },
        )
    }

    private companion object {
        const val TAG = "NovelDownloadFeedback"
    }
}
