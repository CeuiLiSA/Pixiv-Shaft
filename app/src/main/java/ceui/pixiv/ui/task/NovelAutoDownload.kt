package ceui.pixiv.ui.task

import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.fragments.WebNovelParser
import ceui.loxia.Novel
import ceui.pixiv.api.Client
import ceui.pixiv.ui.novel.reader.NovelTextCache
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.ExportResult
import ceui.pixiv.ui.novel.reader.export.NovelExportManager
import ceui.pixiv.ui.novel.reader.paginate.ContentParser
import com.hjq.toast.Toaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Collections

/**
 * 「收藏后自动下载」的小说支（设置 → 收藏与互动）。入口只有
 * [ceui.pixiv.actions.PixivActions] 一处，见那边的 `autoDownloadNovel`。
 *
 * ## 为什么要排队
 *
 * 插画那支把任务丢进 `Manager` 的下载队列，本身就是串行 + 有节流的；小说没有这样一条
 * 队列（[DownloadNovelTask] 早已没有调用方，批量下载各自起 task）。而收藏是可以连点的
 * ——在小说列表里一路点心，直接并发去抓正文，pixiv 对 `getNovelText` 很快就回 429
 * （[BatchDownloadNovelsTask] 为此在每篇之间留了 1.5 秒，这里沿用同一个间隔）。
 * 所以这里自己拿一条 [Channel] 串起来，按 id 去重。
 *
 * ## 落盘走的是阅读器那条导出路径
 *
 * 不另写一套写文件的代码：[NovelExportManager] 已经负责按用户的命名预设渲染路径、
 * 套「下载内容信息头设置」的信息头、查 `SeriesCache` 补 `{series_order}`、并把结果记进
 * 下载历史。自动下载不该产出一种别处见不到的文件。
 *
 * ## 提示口径
 *
 * 成功弹一句落盘路径，**且判「下载完成App内提示」开关**：那个开关灭的正是「逐张刷屏」
 * （见 [ceui.pixiv.ui.bulk.QueueDownloadManager] 与 `Manager` 里同样的判法），而收藏一篇
 * 弹一条就是逐张刷屏本身 —— 不判它，等于用户关掉的提示照弹。
 *
 * 失败一律只留 Timber 日志：这是收藏的**副作用**，不是用户主动发起的下载，断网时每点一次心
 * 弹一次「下载失败」只是噪音，而收藏本身（走 [ceui.pixiv.actions.PixivActionQueue]）是能
 * 离线排队的。
 */
object NovelAutoDownload {

    /**
     * 两次抓正文之间的最小间隔，对齐 [BatchDownloadNovelsTask] 里同一个常量的取值。
     */
    private const val MIN_INTERVAL_MS = 1500L

    /**
     * **必须自带 [CoroutineExceptionHandler]**：SupervisorJob 只隔离兄弟协程、不吞异常，
     * 漏网的抛出会冒到默认处理器上崩进程（同 PixivActions 的 bulkScope）。
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, t -> Timber.tag(TAG).e(t, "auto download scope crashed") }
    )

    private val queue = Channel<Novel>(Channel.UNLIMITED)

    /**
     * 排队中（含正在下载）的 novelId。取消收藏再收藏一次、或同一篇在两个列表里各点一下，
     * 都只该抓一次正文。
     */
    private val queued: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

    init {
        scope.launch {
            for (novel in queue) {
                try {
                    download(novel)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    // 逐篇兜底：一篇失败（正文解析不出来 / 断网）不能让整条 worker 结束，
                    // 否则本次进程内后面所有的自动下载都静默失效。
                    Timber.tag(TAG).w(t, "auto download failed, novel=%d", novel.id)
                } finally {
                    queued.remove(novel.id)
                }
                delay(MIN_INTERVAL_MS)
            }
        }
    }

    /** 立即返回，真正的抓取 + 落盘在 [scope] 里串行进行。 */
    fun enqueue(novel: Novel) {
        if (!queued.add(novel.id)) return
        if (queue.trySend(novel).isFailure) {
            queued.remove(novel.id)
        }
    }

    private suspend fun download(novel: Novel) {
        val context = Shaft.getContext() ?: return
        // 详情页会在后台把正文预热进 NovelTextCache，阅读器也往里写 —— 从这两处收藏时
        // 命中缓存，一次网络请求都不用发。
        val entry = NovelTextCache.get(novel.id) ?: run {
            val html = Client.appApi.getNovelText(novel.id).string()
            val web = WebNovelParser.parsePixivObject(html)?.novel
                ?: throw RuntimeException("invalid web novel")
            NovelTextCache.Entry(web, ContentParser.tokenize(web)).also {
                NovelTextCache.put(novel.id, it)
            }
        }
        // 只有显式默认 TXT 才能转 EPUB；“每次询问”在后台回退 TXT，其他默认格式原样使用。
        val configuredFormat = NovelExportManager.resolveConfiguredFormat() ?: ExportFormat.Txt
        val format = if (NovelExportManager.shouldAutoEpubForDefaultTxt(configuredFormat, entry.tokens)) {
            ExportFormat.Epub
        } else {
            configuredFormat
        }
        when (val result = NovelExportManager.export(
            context = context,
            format = format,
            novel = novel,
            webNovel = entry.webNovel,
            tokens = entry.tokens,
        )) {
            is ExportResult.Success -> {
                if (Shaft.sSettings.isToastDownloadResult) withContext(Dispatchers.Main) {
                    Toaster.show(context.getString(R.string.msg_export_success, result.displayPath))
                }
            }
            // 覆盖策略是「跳过已存在」时这里也会走到（openRaw 返回 null），属正常情况，
            // 所以只记日志不提示。
            is ExportResult.Failure -> Timber.tag(TAG).w(
                result.cause, "auto download rejected, novel=%d reason=%s", novel.id, result.message
            )
        }
    }

    private const val TAG = "NovelAutoDownload"
}
