package ceui.pixiv.download

import android.content.Context
import androidx.annotation.MainThread
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.core.Manager
import ceui.lisa.core.ManagerReactive
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.DownloadEntity
import ceui.lisa.download.FileCreator
import ceui.lisa.utils.Common
import ceui.lisa.utils.PixivOperate
import ceui.pixiv.api.model.Illust
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.download.model.DownloadRecordSlim
import ceui.pixiv.imageloader.PageImageSourceResolver
import ceui.pixiv.imageloader.awaitFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 二级大图页「保存这一张」的进程级执行者 + 状态源(#1312)。
 *
 * 不进入 QueueDownloadManager；下载管理的「单张保存」子页只展示本执行者的状态。
 * 这里只管三件事:
 * - **执行**:任务跑在进程级 [scope],退出大图页照样存完,也不捕获任何页面
 * - **状态**:[states] 按 (作品 id, 页) 给出「保存中(进度)/ 失败」,大图页订阅它画下载按钮。
 *   失败一直留着(按钮显示重试,点一下 [save] 重来),存成功或取消才消失
 * - **取消**:[cancel] 只在等原图加载 / 等批量下载让出这张图期间有效;开始写盘后不认取消,
 *   免得留下半截文件,或文件写好了却没记进「已下载」
 * - **恢复**:原图保存意图和失败原因落在私有持久目录,Application 启动时恢复;进度按实际来源重算。
 *
 * 与批量下载的互斥:写盘前拿 [Manager] 的同 url 单主锁(见 [saveOriginal])。
 */
@Singleton
class ImagePageSaver internal constructor(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context,
        Dispatchers.IO,
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val app: Context = context.applicationContext

    data class PageKey(val illustId: Long, val page: Int)

    data class Request(
        val illust: Illust,
        val page: Int,
        val target: Target,
        val queuedAt: Long = System.currentTimeMillis(),
    )

    enum class Phase { LOADING, WAITING_TO_WRITE, WRITING }

    /** 存哪张图:原图(取大图页已加载的那份,不重下),或这一页当前显示的译图文件。 */
    sealed interface Target {
        data class Original(val imageUrl: String) : Target

        data class Translated(val file: File) : Target
    }

    sealed interface PageState {
        val target: Target

        /** [percent] 是原图加载进度;来源已在本地时直接是 100。 */
        data class Saving(
            override val target: Target,
            val percent: Int,
            val phase: Phase = Phase.LOADING,
        ) : PageState

        data class Failed(override val target: Target, val error: String = "") : PageState
    }

    private val _states = MutableStateFlow<Map<PageKey, PageState>>(emptyMap())
    val states: StateFlow<Map<PageKey, PageState>> = _states.asStateFlow()

    // Only model data is retained: never keep an Activity, View, or callback for a retry.
    private val requests = ConcurrentHashMap<PageKey, Request>()
    private val errors = ConcurrentHashMap<PageKey, String>()

    // Constructed on IO, after Application settings/storage are ready. Never persist in cacheDir.
    private val journal by lazy {
        ImagePageSaveJournal(File(app.noBackupFilesDir, "image-page-saves.properties"))
    }
    private val journalMutex = Mutex()
    private val restored = CompletableDeferred<Unit>()
    private val touchedWhileRestoring = mutableSetOf<PageKey>()
    private val removing = mutableSetOf<PageKey>()
    private var started = false

    /** Application startup calls this even if the user never opens the download screen. */
    @MainThread
    fun start() {
        if (started) return
        started = true
        scope.launch {
            try {
                val entries = withContext(ioDispatcher) {
                    journal.read().map { entry ->
                        val illust = Shaft.sGson.fromJson(entry.illustJson, Illust::class.java)
                        require(illust != null && illust.id > 0 && !illust.isGif() &&
                            entry.page in 0 until illust.page_count.coerceAtLeast(1)) {
                            "Invalid saved artwork/page"
                        }
                        Request(illust, entry.page, Target.Original(entry.imageUrl), entry.queuedAt) to entry.error
                    }
                }
                for ((request, error) in entries) {
                    val key = PageKey(request.illust.id, request.page)
                    // A fresh click while the journal was loading wins over its older snapshot.
                    if (key in touchedWhileRestoring || requests.containsKey(key)) continue
                    if (error != null) {
                        requests[key] = request
                        _states.update { it + (key to PageState.Failed(request.target, error)) }
                    } else {
                        enqueue(request, recovering = true)
                    }
                }
                touchedWhileRestoring.clear()
                restored.complete(Unit)
            } catch (ex: Exception) {
                if (ex is CancellationException) {
                    restored.cancel(ex)
                    throw ex
                }
                // Keep the file for diagnosis/recovery; never overwrite an unreadable journal.
                Timber.e(ex, "[ImagePageSaver] restore failed")
                restored.completeExceptionally(ex)
                Common.showToast(app.getString(R.string.save_image_failed, ex.message.orEmpty()))
            }
        }
    }

    /** Snapshot only task transitions, not every progress tick. All writers serialize here. */
    private suspend fun persistRequests(
        changes: Map<PageKey, PageState?> = emptyMap(),
        afterCommit: () -> Unit = {},
    ) = journalMutex.withLock {
        val snapshot = withContext(Dispatchers.Main.immediate) {
            requests.mapNotNull { (key, request) ->
                val target = request.target as? Target.Original ?: return@mapNotNull null
                val state = (if (key in changes) changes[key] else _states.value[key])
                    ?: return@mapNotNull null
                Triple(request, target.imageUrl, (state as? PageState.Failed)?.error)
            }
        }
        withContext(ioDispatcher) {
            journal.write(snapshot.map { (request, url, error) ->
                ImagePageSaveJournal.Entry(Shaft.sGson.toJson(request.illust), request.page,
                    url, request.queuedAt, error)
            })
        }
        // Update memory before releasing the lock, so the next writer cannot resurrect this key.
        withContext(Dispatchers.Main.immediate) { afterCommit() }
    }

    private suspend fun finish(key: PageKey, target: Target, failed: Boolean, error: String) =
        withContext(NonCancellable) {
            val finalState = if (failed) PageState.Failed(target, error) else null
            try {
                restored.await()
                persistRequests(mapOf(key to finalState)) {
                    if (!failed) requests.remove(key)
                    _states.update { if (finalState != null) it + (key to finalState) else it - key }
                }
                true
            } catch (ex: Exception) {
                Timber.e(ex, "[ImagePageSaver] persist completion failed")
                // Do not acknowledge a cancellation/removal that was not saved to disk.
                _states.update { it + (key to PageState.Failed(target, ex.message.orEmpty())) }
                Common.showToast(app.getString(R.string.save_image_failed, ex.message.orEmpty()))
                false
            }
        }

    private suspend fun removeFailure(key: PageKey) = withContext(NonCancellable) {
        try {
            restored.await()
            persistRequests(mapOf(key to null)) {
                requests.remove(key)
                errors.remove(key)
                _states.update { it - key }
            }
        } catch (ex: Exception) {
            Timber.e(ex, "[ImagePageSaver] persist removal failed")
            Common.showToast(app.getString(R.string.save_image_failed, ex.message.orEmpty()))
        } finally {
            removing.remove(key)
        }
    }

    fun request(key: PageKey): Request? = requests[key]

    @MainThread
    fun retry(key: PageKey): Boolean {
        if (_states.value[key] !is PageState.Failed) return false
        val request = requests[key] ?: return false
        return save(request.illust, request.page, request.target)
    }

    @MainThread
    fun dismissFailure(key: PageKey): Boolean {
        if (_states.value[key] !is PageState.Failed || !removing.add(key)) return false
        scope.launch { removeFailure(key) }
        return true
    }

    /**
     * 还能取消的保存。[cancel] 与 [claimWrite] 谁先把条目摘掉谁说了算:取消先摘到就不写盘,
     * 写盘先摘到就不再认取消 —— 两边都只做一次原子 remove,不会出现「取消了却已经写完」。
     */
    private val cancellable = ConcurrentHashMap<PageKey, Job>()

    /** 本进程存过的译图文件。译图每次产出都是新文件、也不进「已下载」库,译图页的「已保存」只能靠它判断。 */
    private val savedTranslations = ConcurrentHashMap.newKeySet<String>()

    fun isTranslationSaved(file: File): Boolean = file.path in savedTranslations

    /**
     * 开始(或重试)保存这一页。这一页正在存时什么都不做,返回 false。
     * 成功后按设置「下载后自动收藏」,收藏心由大图页对 ObjectPool 的观察刷新。
     */
    @MainThread
    fun save(illust: Illust, page: Int, target: Target): Boolean {
        start()
        return enqueue(Request(illust, page, target), recovering = false)
    }

    @MainThread
    private fun enqueue(request: Request, recovering: Boolean): Boolean {
        val (illust, page, target) = request
        val key = PageKey(illust.id, page)
        if (_states.value[key] is PageState.Saving || key in removing) return false
        if (!recovering && !restored.isCompleted) touchedWhileRestoring += key
        requests[key] = request
        errors.remove(key)
        _states.update { it + (key to PageState.Saving(target, 0)) }
        // LAZY:先登记再开跑。Main.immediate 会当场执行到第一个挂起点,登记晚了取消就找不到它。
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                // 收尾放 finally:意料之外的异常(照旧抛出去)也要把「保存中」解掉记成失败,
                // 否则按钮一直转圈、点了也取消不掉。
                var failed = true
                var cancelled = false
                try {
                    restored.await()
                    // No fetch or target write starts before the intent is durably recorded.
                    persistRequests()
                    val alreadySaved = recovering && target is Target.Original &&
                        withContext(ioDispatcher) {
                            page in RecordedPageProbe.usablePagesSince(app, illust.id, request.queuedAt)
                        }
                    val ok =
                        alreadySaved || when (target) {
                            is Target.Original -> saveOriginal(key, illust, page, target.imageUrl)
                            is Target.Translated -> saveTranslated(key, illust, page, target.file)
                        }
                    failed = !ok
                    if (ok && !alreadySaved) {
                        // 先记再解「保存中」:解掉那一刻大图页就按它判断译图页的绿勾
                        if (target is Target.Translated) savedTranslations.add(target.file.path)
                        Common.showToast(R.string.string_181)
                        if (Shaft.sSettings.isAutoPostLikeWhenDownload && !illust.isBookmarked) {
                            PixivOperate.postLikeDefaultStarType(illust)
                        }
                    }
                } catch (e: CancellationException) {
                    failed = false
                    cancelled = true
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "[ImagePageSaver] unexpected save failure")
                    toastFailure(key, e)
                } finally {
                    cancellable.remove(key, coroutineContext.job)
                    val error = errors.remove(key).orEmpty()
                    if (finish(key, target, failed, error) && cancelled) {
                        Common.showToast(R.string.save_image_cancelled)
                    }
                }
            }
        cancellable[key] = job
        job.start()
        return true
    }

    /** 取消这一页的保存。已经开始写盘(或根本没在存)时返回 false,保存照常走完。 */
    @MainThread
    fun cancel(key: PageKey): Boolean {
        val job = cancellable.remove(key) ?: return false
        job.cancel()
        return true
    }

    /** 进入写盘:从此不再认取消。取消已经抢先时抛 [CancellationException],调用方不得写盘。 */
    private fun claimWrite(key: PageKey) {
        if (cancellable.remove(key) == null) throw CancellationException("save cancelled before writing")
        publishPhase(key, Phase.WRITING)
    }

    private fun publishPhase(key: PageKey, phase: Phase) {
        _states.update { states ->
            val current = states[key]
            if (current is PageState.Saving) states + (key to current.copy(phase = phase)) else states
        }
    }

    private fun publishProgress(key: PageKey, percent: Int) {
        _states.update { states ->
            val current = states[key]
            if (current is PageState.Saving && current.percent != percent) {
                states + (key to current.copy(percent = percent))
            } else {
                states
            }
        }
    }

    private fun toastFailure(key: PageKey, error: Throwable?) {
        val detail = error?.message ?: error?.javaClass?.simpleName ?: "load error"
        errors[key] = detail
        Common.showToast(app.getString(R.string.save_image_failed, detail))
    }

    /**
     * 保存译图:按用户插画模板命名并加 [TRANSLATED_FILENAME_SUFFIX],与原图并存、互不覆盖。
     * 译图是派生图片,不写「已下载」库 —— 那张表的记录会被详情页当原图本地副本复用。
     */
    private suspend fun saveTranslated(key: PageKey, illust: Illust, page: Int, file: File): Boolean =
        withContext(ioDispatcher) {
            claimWrite(key)
            runCatching {
                // open 返回 null = Skip 策略且同名译图已存在 → 视为已保存
                val handle =
                    DownloadsRegistry.downloads.openDerived(
                        DownloadItems.illustTranslated(illust, page),
                        TRANSLATED_FILENAME_SUFFIX,
                    ) ?: return@runCatching true
                try {
                    handle.stream.use { out -> FileInputStream(file).use { it.copyTo(out) } }
                    handle.onFinish()
                } catch (t: Throwable) {
                    handle.onAbort()
                    throw t
                }
                true
            }
                .getOrElse { ex ->
                    if (ex is CancellationException) throw ex
                    Timber.e(ex, "[ImagePageSaver] saveTranslated failed page=%d", page)
                    withContext(Dispatchers.Main) { toastFailure(key, ex) }
                    false
                }
        }

    /**
     * 保存原图:复用大图页已加载的原图(与显示层同一 imageloader 共享任务,不重新下载),走**新**下载后端
     * [DownloadsRegistry] 按用户命名模板/存储配置写盘;并记一条 [DownloadEntity],让「已下载」列表与详情本地复用
     * (findDownloadedPageUri 仍查 DB)保持一致。
     */
    private suspend fun saveOriginal(key: PageKey, illust: Illust, page: Int, imageUrl: String): Boolean =
        withContext(ioDispatcher) {
            IllustCaptionExporter.export(illust)
            var resolveError: Throwable? = null
            val file =
                try {
                    PageImageSourceResolver.resolve(app, illust, page, imageUrl)
                        .awaitFile(app) { publishProgress(key, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "[ImagePageSaver] await loaded file failed page=%d", page)
                    resolveError = e
                    null
                }
            if (file == null) {
                withContext(Dispatchers.Main) { toastFailure(key, resolveError) }
                return@withContext false
            }
            publishProgress(key, 100)
            publishPhase(key, Phase.WAITING_TO_WRITE)

            // 批量下载正在写同一张图时先等它收尾(#1312):两边写的是同一个模板目标,替换策略下同时写会
            // 多出 " (1)" 副本,或一方补写完标签后被另一方剩下的字节改坏。借 Manager 的同 url 单主锁。
            // 我们占着的那一小段写盘窗口里 Manager 刚好开传这张图,会按「stage key busy」把那一页判失败:
            // 批量队列会自动重试,详情页「下载全部」直接进 Manager 的不会,停在失败等用户点重试
            // (文件本身这边已经写好并记了账)。要在下面落副本之前拿到:本地来源可能正是队列在写的那个目标文件。
            val manager = Manager.get()
            while (!manager.tryAcquireUrlWrite(imageUrl)) delay(URL_WRITE_POLL_MS)
            try {
                claimWrite(key)
                // 这一页已下载时,本地来源可能正是这次要写的目标本身(同模板同路径)。替换策略下后端会先删 /
                // 截断目标再写,边读边写就把用户的原图抹成 0 字节。私有目录之外的来源先落一份副本再写。
                val staged =
                    if (file.isInsideAppPrivateDir()) null
                    else runCatching {
                        File.createTempFile("save_src", null, app.cacheDir).also { file.copyTo(it, overwrite = true) }
                    }.getOrElse {
                        if (it is CancellationException) throw it
                        Timber.w(it, "[ImagePageSaver] stage local source failed page=%d", page)
                        withContext(Dispatchers.Main) { toastFailure(key, it) }
                        return@withContext false
                    }
                val source = staged ?: file

                runCatching {
                    // open() 返回 null = Skip 策略且文件已存在 → 视为已保存,无需重写。
                    val handle =
                        DownloadsRegistry.downloads.open(DownloadItems.illustPage(illust, page))
                            ?: return@runCatching true
                    try {
                        handle.stream.use { out -> FileInputStream(source).use { it.copyTo(out) } }
                        handle.onFinish()
                    } catch (t: Throwable) {
                        handle.onAbort()
                        throw t
                    }
                    // 可选:标签写进 JPEG XMP 关键词(issue #938)。放在 commit 之后、onAbort 作用域之外,
                    // 免得写元数据抛错误删掉已成功的文件。整段再包一层 runCatching:即便标签取值意外抛错,
                    // 也不能连累下面「已下载」写库(否则文件在盘上却不记账)。开关默认关时直接跳过,零开销。
                    if (Shaft.sSettings.isWriteTagsToImageExif()) {
                        runCatching {
                            ExifKeywordWriter.writeIfEnabled(
                                app,
                                handle.uri,
                                FileCreator.customFileName(illust, page),
                                illust.tags.orEmpty().mapNotNull { it?.name },
                            )
                        }
                    }
                    // 与 Manager 成功分支一致地写库(fileName 用 FileCreator=模板命名,filePath 用写盘 uri)。
                    val entity =
                        DownloadEntity().apply {
                            illustGson = DownloadRecordSlim.createIllustJson(illust)
                            fileName = FileCreator.customFileName(illust, page)
                            downloadTime = System.currentTimeMillis()
                            filePath = handle.uri.toString()
                            // v41 的 page 列 —— 与 Manager 成功分支一致，让按 (illustId, page) 的
                            // 查询也能命中「保存这一张」写下的记录。
                            this.page = page
                        }
                    // insertDownload 会从 illustGson 顶层 id 补上 illustId（走 v38 索引）。
                    AppDatabase.getAppDatabase(app).downloadDao().insertDownload(entity)
                    ManagerReactive.pokeDoneTable()
                    true
                }
                    .getOrElse { ex ->
                        if (ex is CancellationException) throw ex
                        Timber.e(ex, "[ImagePageSaver] saveOriginal failed page=%d", page)
                        withContext(Dispatchers.Main) { toastFailure(key, ex) }
                        false
                    }
                    .also { staged?.delete() }
            } finally {
                manager.releaseUrlWrite(imageUrl)
            }
        }

    /** 查看器缓存 / 快照存档都在私有目录里,不可能是下载后端的写入目标,无需另拷一份。 */
    private fun File.isInsideAppPrivateDir(): Boolean {
        val path = canonicalPath
        return listOfNotNull(app.cacheDir, app.filesDir, app.externalCacheDir).any {
            path.startsWith(it.canonicalPath + File.separator)
        }
    }

    private companion object {
        /** 译图文件名后缀,插在用户模板文件名与扩展名之间,避免覆盖原图。 */
        const val TRANSLATED_FILENAME_SUFFIX = "_translated"

        /** 批量下载正占着同一张图时,保存隔多久再问一次能不能写。 */
        const val URL_WRITE_POLL_MS = 250L
    }
}
