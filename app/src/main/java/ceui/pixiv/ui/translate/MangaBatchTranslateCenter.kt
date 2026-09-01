package ceui.pixiv.ui.translate

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.R
import ceui.pixiv.api.model.Illust
import ceui.lisa.utils.Common
import ceui.pixiv.imageloader.ImageLoaderV3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * 「翻译整部」(issue #925)的进程级任务中心 + 译图仓库。
 *
 * 普通 class,由 [ceui.lisa.activities.Shaft] 构造一份,经
 * [ceui.pixiv.services.ServicesProvider.mangaBatchTranslateCenter] 取用。
 *
 * 为什么不放在看图页的 ViewModel:整部翻译动辄几分钟,用户点了之后就该能退出看图页
 * 去干别的,回来译图都在;跟着 Activity 生命周期走的话一返回就被取消,悬浮窗也就没意义了。
 * 所以:
 * - 任务跑在进程级 [scope],只认 [cancel](悬浮窗 ✕)和进程死亡,不认任何页面销毁
 * - [status] 驱动 [MangaBatchFloatInstaller] 挂在每个 Activity 上的悬浮窗
 * - 译图按作品 id 分桶存在 [buckets],看图页的 VM 只是对自己那一桶的投影;
 *   单页「翻译漫画」/「圈选翻译」的产物也 [publish] 进来,重进看图页还能看到
 * - 桶按 LRU 最多留 [MAX_ILLUSTS] 部,挤出去的连文件一起删,cacheDir 不会无限长
 *
 * 与单页流水线互斥:两边共用同一套 OCR 模型([models]),不能并发。单页那边跑之前先
 * [beginSinglePage] 占位、跑完 [endSinglePage];这边跑的时候 [isRunning] 为 true,入口各自先看对方。
 */
class MangaBatchTranslateCenter(app: Context, private val models: MangaTranslateModels) {

    private val app: Context = app.applicationContext

    data class BatchStatus(
        val illustId: Long,
        val title: String,
        /** 已处理完的页数(含跳过),当前正在处理第 pageDone+1 页 */
        val pageDone: Int,
        val total: Int,
        val stageText: String,
        /** 当前页内阶段进度,null = indeterminate */
        val stagePercent: Int? = null,
    )

    private companion object {
        /** 保留译图的作品数上限(挤出去的连缓存文件一起删)。 */
        const val MAX_ILLUSTS = 3

        /** 桶总数硬上限(含浏览时建的空桶),防进程级 map 随浏览量无界增长。 */
        const val MAX_BUCKETS = 24
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _status = MutableLiveData<BatchStatus?>(null)
    val status: LiveData<BatchStatus?> get() = _status

    private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    /** 正在跑的那部作品,悬浮窗点一下跳回看图页用。 */
    @Volatile
    var currentIllust: Illust? = null
        private set

    /** 看图页的单页 / 圈选流水线正在跑:共用模型,整部翻译入口此时拒绝启动。 */
    @Volatile
    private var singlePageBusy = false

    /** 单页流水线是否在跑(只读;写入走 [beginSinglePage] / [endSinglePage])。 */
    val isSinglePageBusy: Boolean get() = singlePageBusy

    /**
     * 单页 / 圈选流水线开跑前占位。主线程调。
     * @return false = 整部翻译或另一个单页任务正在用模型,调用方不该启动
     */
    fun beginSinglePage(): Boolean {
        if (isRunning || singlePageBusy) return false
        singlePageBusy = true
        return true
    }

    /** 单页流水线收尾(成功、失败、取消都要调)。 */
    /**
     * 内存压力时释放模型会话,但只在**没人用**的时候:批量任务或单页流水线跑到一半被释放,
     * 下一次 recognize 会抛 "Model not loaded",半页气泡静默丢失。释放决策放这里而不是
     * [MangaTranslateModels],因为只有本类知道谁在用。
     */
    fun releaseModelsIfIdle() {
        if (isRunning || isSinglePageBusy) return
        models.release()
    }

    fun endSinglePage() {
        singlePageBusy = false
    }

    @Volatile
    private var cancelledByUser = false

    // ---- 译图仓库 -------------------------------------------------------------

    /** 按访问顺序排列的 LinkedHashMap,当 LRU 用;主线程访问。 */
    private val buckets = LinkedHashMap<Long, MutableLiveData<Map<Int, String>>>(8, 0.75f, true)

    /**
     * 某部作品的 pageIndex → 译图路径。没有就建一个空桶 —— 看图页每次打开都会来绑一次,
     * 所以空桶也会累积,必须跟着 [evictIfNeeded] 的总量上限一起回收,否则进程级静态 map
     * 会随浏览量无限增长。
     */
    fun pathsOf(illustId: Long): LiveData<Map<Int, String>> = bucket(illustId)

    private fun bucket(illustId: Long): MutableLiveData<Map<Int, String>> =
        buckets.getOrPut(illustId) { MutableLiveData(emptyMap()) }.also { evictIfNeeded(illustId) }

    private fun evictIfNeeded(keep: Long) {
        // 1. 有译图的桶超过 MAX_ILLUSTS:从最久没访问的开始清空,连文件一起删
        val filled = buckets.entries.filter { !it.value.value.isNullOrEmpty() }
        var overFilled = filled.size - MAX_ILLUSTS
        // entries 按访问顺序,最久没碰的在前
        for (entry in filled) {
            if (overFilled <= 0) break
            if (entry.key == keep) continue
            val paths = entry.value.value.orEmpty().values.toList()
            entry.value.value = emptyMap()
            scope.launch(Dispatchers.IO) { paths.forEach { runCatching { File(it).delete() } } }
            Timber.d("MangaBatchTranslateCenter: evicted illust %d (%d files)", entry.key, paths.size)
            overFilled--
        }
        // 2. 桶总数超过 MAX_BUCKETS:从最久没访问的空桶开始整条移除,挡住「只浏览不翻译」
        //    也不断建空桶导致的无界增长。有译图的桶交给规则 1,这里跳过。
        //    (极端边角:被移除的空桶若正被某个后台看图页的 VM 观察,该页之后再翻译会新建桶、
        //     VM 观察的是旧空桶而不刷新 —— 需要 24+ 层看图页积压且专挑那一页翻译,现实中不会发生)
        var overTotal = buckets.size - MAX_BUCKETS
        if (overTotal <= 0) return
        val iterator = buckets.entries.iterator()
        while (iterator.hasNext() && overTotal > 0) {
            val entry = iterator.next()
            if (entry.key == keep || !entry.value.value.isNullOrEmpty()) continue
            iterator.remove()
            overTotal--
        }
    }

    /** 发布一页译图(主线程);同页旧产物顺手删掉。 */
    fun publish(illustId: Long, pageIndex: Int, newPath: String) {
        val live = bucket(illustId)
        val old = live.value?.get(pageIndex)
        live.value = live.value.orEmpty().toMutableMap().apply { put(pageIndex, newPath) }
        if (old != null && old != newPath) {
            scope.launch(Dispatchers.IO) { runCatching { File(old).delete() } }
        }
    }

    // ---- 整批任务 -------------------------------------------------------------

    /**
     * 启动整部翻译。已有任务在跑 / 单页在跑 → 返回 false,调用方自己 toast。
     * @param pageUrls 下标即 pageIndex;null 表示该页没有可用 url,按失败计
     */
    fun start(
        illust: Illust,
        pageUrls: List<String?>,
        ocrModel: MangaOcrModel,
        ctdModel: ComicTextDetectorModel,
    ): Boolean {
        if (isRunning || singlePageBusy) return false
        cancelledByUser = false
        currentIllust = illust
        job = scope.launch {
            try {
                runBatch(illust, pageUrls, ocrModel, ctdModel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (cancelledByUser) {
                    Timber.d(e, "MangaBatchTranslateCenter: error after cancel, ignored")
                } else {
                    Timber.e(e, "MangaBatchTranslateCenter: batch failed")
                    Common.showToast(R.string.string_ai_manga_translate_failed)
                }
            } finally {
                _status.postValue(null)
                currentIllust = null
                job = null
            }
        }
        return true
    }

    /** 悬浮窗 ✕:立刻停掉并弹「翻译已取消」;没在跑就忽略。 */
    fun cancel() {
        val j = job ?: return
        if (!j.isActive) return
        cancelledByUser = true
        j.cancel()
        Common.showToast(R.string.string_ai_manga_translate_cancelled)
    }

    private suspend fun runBatch(
        illust: Illust,
        pageUrls: List<String?>,
        ocrModel: MangaOcrModel,
        ctdModel: ComicTextDetectorModel,
    ) {
        val illustId = illust.id
        val title = illust.title.orEmpty()
        val total = pageUrls.size
        var translated = 0
        var empty = 0
        var failed = 0
        var skipped = 0
        for (pageIndex in pageUrls.indices) {
            val done = pageIndex
            val post: (MangaPageTranslatePipeline.Stage) -> Unit = { s ->
                _status.postValue(BatchStatus(illustId, title, done, total, s.text, s.progressPercent))
            }
            // 已有译图的页(之前单页翻过 / 圈选过 / 上次整批翻过)跳过,不重复烧额度
            if (bucket(illustId).value?.get(pageIndex)?.let { File(it).exists() } == true) {
                skipped++
                continue
            }
            post(MangaPageTranslatePipeline.Stage(app.getString(R.string.string_ai_manga_batch_loading_image)))
            val url = pageUrls[pageIndex]
            val file = if (url == null) null else try {
                ImageLoaderV3.obtain(url).awaitFile()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "MangaBatchTranslateCenter: page %d image load failed", pageIndex)
                null
            }
            if (file == null) {
                failed++
                continue
            }
            when (val outcome = MangaPageTranslatePipeline.translatePage(app, models, file, pageIndex, ocrModel, ctdModel, post)) {
                is MangaPageTranslatePipeline.Outcome.Done -> {
                    publish(illustId, pageIndex, outcome.outFile.absolutePath)
                    translated++
                }
                MangaPageTranslatePipeline.Outcome.OcrEmpty -> empty++
                MangaPageTranslatePipeline.Outcome.OcrFailed,
                MangaPageTranslatePipeline.Outcome.RenderFailed -> failed++
                MangaPageTranslatePipeline.Outcome.ModelLoadFailed -> {
                    if (!cancelledByUser) Common.showToast(R.string.string_ai_ocr_failed)
                    return
                }
                // 翻译接口报错(代理不通、AI 配置错)多半是系统性的,直接中止并弹对应提示,
                // 不拿后面每一页去撞同一个错
                is MangaPageTranslatePipeline.Outcome.TranslateFailed -> {
                    if (!cancelledByUser) promptTranslateFailedIfPossible(outcome.error)
                    return
                }
            }
        }
        Timber.d(
            "MangaBatchTranslateCenter: batch finished illust=%d total=%d translated=%d empty=%d failed=%d skipped=%d",
            illustId, total, translated, empty, failed, skipped,
        )
        if (!cancelledByUser) {
            var summary = app.getString(R.string.string_ai_manga_batch_summary, translated, empty, failed)
            if (skipped > 0) {
                summary = app.getString(R.string.string_ai_manga_batch_summary_skipped, summary, skipped)
            }
            Common.showToast(summary)
        }
    }
}
