package ceui.pixiv.ui.bulk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import ceui.lisa.core.DownloadItem
import ceui.lisa.core.Manager
import ceui.lisa.database.AppDatabase
import ceui.lisa.download.IllustDownload
import ceui.lisa.http.Retro
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.DownloadLimitTypeUtil
import ceui.loxia.ObjectPool
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.db.queue.DownloadQueueEntity
import ceui.pixiv.db.queue.QueueStatus
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 批量下载持久化队列消费者。
 *
 * 严格不变量：
 *   1. 同一时刻只有一条 download_queue 记录处于 DOWNLOADING（[INVARIANT_CHECK_DOWNLOADING]）。
 *   2. 我们移交给 [Manager] 的 DownloadItem 是同步 [Manager.addTask]（不是异步 addTasks），
 *      因此 [awaitIllustSettled] 不会因 race 而误判"已完成"。
 *   3. 当 [DownloadLimitTypeUtil.canDownloadNow] 为 false 时，consumer 主动挂起，
 *      不消耗队列，不把 item 标 DOWNLOADING（避免 wifi-only + 蜂窝网络下卡死）。
 *   4. [awaitIllustSettled] 设置上限：先等 items 出现，再等它们消失/失败。
 *      任一阶段超时即抛出 → 走重试 → 最终 FAILED → 移交下一条，consumer 不会永久 hang。
 *   5. GIF 在 fetcher 阶段已被滤掉（gif 走 ugoira zip + 解压，不适合走本队列），
 *      但 processOne 仍做防御性跳过（标记 SUCCESS 不下载）。
 */
object QueueDownloadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tickle = Channel<Unit>(Channel.CONFLATED)
    private var loopJob: Job? = null
    @Volatile private var paused: Boolean = false

    // —— 调度参数 ——
    private const val MAX_RETRY = 3
    private const val POLL_INTERVAL_MS = 600L
    private const val POST_FAIL_BACKOFF_MS = 1500L
    private const val FAILED_STREAK_THRESHOLD = 3
    /** items 必须在 [WAIT_APPEAR_TIMEOUT_MS] 内出现在 Manager.content；否则视为 Manager 没接住 */
    private const val WAIT_APPEAR_TIMEOUT_MS = 10_000L
    /** items 出现后，无任何状态变化的"停滞"上限；超过则视为下载内核卡住 */
    private const val STALL_TIMEOUT_MS = 90_000L
    /** canDownloadNow=false 时挂起的 sleep 周期 */
    private const val NETWORK_GATE_SLEEP_MS = 30_000L

    private var appContext: Context? = null
    /**
     * 懒加载 dao —— 必须延后到 [loopJob] 协程（IO 线程）首次使用时才触发 DB 打开 + migration，
     * 否则 [init] 会被 [Shaft.onCreate] 在 Main 线程上拖住（v33 首次升级时 ~数百 ms）。
     */
    private val dao: DownloadQueueDao by lazy {
        val ctx = appContext ?: throw IllegalStateException("QueueDownloadManager not initialized")
        AppDatabase.getAppDatabase(ctx).downloadQueueDao()
    }

    fun init(context: Context) {
        if (loopJob != null) return
        appContext = context.applicationContext
        // 注意：这里不要触碰 dao；首次 dao 访问发生在下面的 launch 协程（IO 线程）里。
        loopJob = scope.launch {
            // 冷启动：上次崩溃残留的 DOWNLOADING/没结束的全部归位为 PENDING
            runCatching { dao.resurrectInProgress() }.onFailure { Timber.tag(TAG).e(it, "resurrectInProgress failed") }

            // 检查是否有需要恢复的批量下载 —— 不无脑继续，让用户决定
            val pending = runCatching { dao.countByStatus(QueueStatus.PENDING) }.getOrDefault(0)
            if (pending > 0) {
                paused = true   // 默认暂停；等用户在第一个 Activity 弹窗里决定
                (appContext as? Application)?.let { app ->
                    promptResumeOnFirstActivity(app, pending)
                }
                Timber.tag(TAG).i("cold start: $pending pending items, awaiting user decision")
            } else {
                paused = false
                tickle.trySend(Unit)
            }

            for (signal in tickle) {
                if (paused) continue
                consumeUntilEmpty()
            }
        }
        Timber.tag(TAG).d("QueueDownloadManager initialized")
    }

    /**
     * 注册 ActivityLifecycleCallbacks，等到第一个真正进入 RESUMED 的 Activity，弹 QMUI
     * dialog 询问用户是否继续。回调只触发一次，弹完即注销。
     *
     * Activity 必须是用户可见的 (state RESUMED) 且没在 finishing/destroyed —— 否则
     * QMUIDialog 会在错误的窗口上 attach。
     */
    private fun promptResumeOnFirstActivity(app: Application, pendingCount: Int) {
        val cb = object : Application.ActivityLifecycleCallbacks {
            @Volatile var fired = false
            override fun onActivityResumed(activity: Activity) {
                if (fired) return
                if (activity.isFinishing || activity.isDestroyed) return
                fired = true
                app.unregisterActivityLifecycleCallbacks(this)
                showResumePrompt(activity, pendingCount)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        app.registerActivityLifecycleCallbacks(cb)
    }

    private fun showResumePrompt(activity: Activity, pendingCount: Int) {
        // QMUIDialog 必须在主线程展示
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            try {
                QMUIDialog.MessageDialogBuilder(activity)
                    .setTitle("批量下载")
                    .setMessage("上次还有 ${pendingCount} 项作品没下完。\n现在继续吗？\n\n(队列会按入队顺序串行下载，不打扰你浏览)")
                    .setSkinManager(QMUISkinManager.defaultInstance(activity))
                    .addAction(0, "暂时不下", QMUIDialogAction.ACTION_PROP_NEUTRAL) { d, _ ->
                        // 保持 paused —— 用户可去 下载管理 → 批量队列 手动点 "继续"
                        Timber.tag(TAG).i("user declined cold-start resume; staying paused")
                        d.dismiss()
                    }
                    .addAction(0, "继续") { d, _ ->
                        Timber.tag(TAG).i("user confirmed cold-start resume; pending=$pendingCount")
                        resume()
                        d.dismiss()
                    }
                    .show()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "failed to show resume prompt; auto-resuming as fallback")
                // 极端情况（窗口已坏）下 fallback 到自动恢复，免得任务永远卡在 paused
                resume()
            }
        }
    }

    fun notifyNewItems() { tickle.trySend(Unit) }
    fun pause() { paused = true }
    fun resume() { paused = false; tickle.trySend(Unit) }

    private suspend fun consumeUntilEmpty() {
        while (!paused) {
            // 网关：用户配置不允许下载（如 wifi-only 但当前是蜂窝），睡一会再看
            if (!DownloadLimitTypeUtil.canDownloadNow()) {
                Timber.tag(TAG).i("canDownloadNow=false, holding consumer")
                delay(NETWORK_GATE_SLEEP_MS)
                continue
            }

            val item = runCatching { dao.nextByStatus(QueueStatus.PENDING) }.getOrNull() ?: break

            // 不变量：mark DOWNLOADING 之前必须没有任何 DOWNLOADING；防御性，正常 0
            val activeCount = runCatching { dao.countByStatus(QueueStatus.DOWNLOADING) }.getOrDefault(0)
            if (activeCount > 0) {
                Timber.tag(TAG).w("invariant: $activeCount items already DOWNLOADING; recovering by reset")
                runCatching { dao.resurrectInProgress() }
                delay(1_000L)
                continue
            }

            try {
                dao.updateStatus(item.id, QueueStatus.DOWNLOADING)
                processOne(item)
                dao.updateStatus(item.id, QueueStatus.SUCCESS, finishedAt = System.currentTimeMillis())
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                runCatching { dao.updateStatus(item.id, QueueStatus.PENDING) }
                throw cancellation
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "process failed illustId=${item.illustId} retry=${item.retryCount}")
                if (item.retryCount + 1 < MAX_RETRY) {
                    dao.bumpRetry(item.id)
                    dao.updateStatus(item.id, QueueStatus.PENDING, err = e.message)
                    delay(POST_FAIL_BACKOFF_MS)
                } else {
                    dao.updateStatus(item.id, QueueStatus.FAILED, err = e.message, finishedAt = System.currentTimeMillis())
                }
            }
        }
    }

    private suspend fun processOne(item: DownloadQueueEntity) {
        val bean = resolveIllustsBean(item.illustId)
        // 防御性：GIF 不走本队列（fetcher 已滤掉，仍可能因外部入队进来）
        if (bean.isGif) {
            Timber.tag(TAG).i("skip gif illustId=${item.illustId} (gif goes through ugoira pipeline)")
            return
        }
        val pageCount = if (bean.page_count <= 0) 1 else bean.page_count

        // 同步 addTask（每次调用都 synchronized 写 content + DB）。
        // 关键：addTask 返回时 items 一定已经在 Manager.content 里，awaitIllustSettled 不会 race 误判。
        for (i in 0 until pageCount) {
            val di = DownloadItem(bean, i)
            di.url = IllustDownload.getUrl(bean, i)
            di.showUrl = IllustDownload.getShowUrl(bean, i)
            Manager.get().addTask(di)
        }
        // 显式驱动 Manager.loop()（不依赖 startTaskWhenCreate 配置）。
        // Manager.startAll 内部 for-each content 不在 synchronized 里，跟主线程的
        // content.remove(...)（RxJava onSuccess）有 CME 风险 —— 重试 3 次缓解。
        startAllWithRetry()

        awaitIllustSettled(item.illustId, expectedPageCount = pageCount)
    }

    private suspend fun startAllWithRetry() {
        for (attempt in 1..3) {
            try {
                Manager.get().startAll()
                return
            } catch (e: ConcurrentModificationException) {
                Timber.tag(TAG).w("startAll CME (attempt=$attempt), retry…")
                delay(80L)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "startAll failed (attempt=$attempt)")
                if (attempt == 3) return // 不抛 —— stage B 的停滞超时会兜底
                delay(80L)
            }
        }
    }

    /** Manager.content 是非线程安全 List，且主线程会 remove。安全 snapshot 含重试。 */
    private fun snapshotManagerContent(): List<DownloadItem> {
        for (attempt in 1..5) {
            try {
                return ArrayList(Manager.get().content)
            } catch (e: Exception) {
                // ConcurrentModificationException / NoSuchElementException / IndexOutOfBounds 等
                if (attempt == 5) {
                    Timber.tag(TAG).w(e, "snapshotManagerContent failed after retries")
                    return emptyList()
                }
            }
        }
        return emptyList()
    }

    private suspend fun resolveIllustsBean(illustId: Long): IllustsBean {
        // ObjectPool.store 是非线程安全 HashMap（项目历史遗留），从 IO 读可能撞 CME。
        // 失败就当 cache miss，回退 API；不能让 pool 内部状态污染 consumer。
        val cached = runCatching { ObjectPool.getIllust(illustId).value }.getOrNull()
        if (cached != null) return cached

        val resp = Retro.getAppApi().getIllustByID(illustId).awaitFirstSafe()
        val bean = resp.illust
            ?: throw IllegalStateException("getIllustByID returned null for $illustId")
        // ObjectPool.updateIllust 必须 Main —— LiveData.setValue 主线程要求
        // 同样 try-catch：池内 HashMap 的写也可能撞别处的并发读
        withContext(Dispatchers.Main.immediate) {
            runCatching { ObjectPool.updateIllust(bean) }
        }
        return bean
    }

    /**
     * 两阶段 settled 等待：
     *   阶段 A：等 items 出现在 Manager.content（防 addTask 失败 / 被外部 clearAll 抹掉）。
     *   阶段 B：等 items 全部消失（成功被 remove）或全部失败。"无变化超时" 防内核卡死。
     */
    private suspend fun awaitIllustSettled(illustId: Long, expectedPageCount: Int) {
        val target = illustId.toInt()

        // —— 阶段 A：等出现 ——
        val appearDeadline = System.currentTimeMillis() + WAIT_APPEAR_TIMEOUT_MS
        while (true) {
            val snapshot = snapshotManagerContent()
            val items = snapshot.filter { it.illust?.id == target }
            if (items.isNotEmpty()) break
            if (System.currentTimeMillis() > appearDeadline) {
                throw RuntimeException("items did not appear in Manager.content for illustId=$illustId within ${WAIT_APPEAR_TIMEOUT_MS}ms")
            }
            delay(200L)
        }

        // —— 阶段 B：等消失 / 全失败 / 停滞 ——
        var failedStreak = 0
        var lastSignature = ""
        var lastChangeAt = System.currentTimeMillis()
        while (true) {
            val snapshot = snapshotManagerContent()
            val items = snapshot.filter { it.illust?.id == target }
            if (items.isEmpty()) return

            val allFailed = items.all { it.state == DownloadItem.DownloadState.FAILED }
            failedStreak = if (allFailed) failedStreak + 1 else 0
            if (failedStreak >= FAILED_STREAK_THRESHOLD) {
                throw RuntimeException("all pages failed for illustId=$illustId")
            }

            // 停滞检测：用 (size, 各 item 的 state+nonius) 当签名，签名不变 = 没进展
            val signature = items.joinToString("|") { it.uuid + ":" + it.state + ":" + it.nonius }
            if (signature != lastSignature) {
                lastSignature = signature
                lastChangeAt = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastChangeAt > STALL_TIMEOUT_MS) {
                throw RuntimeException("stalled (no progress in ${STALL_TIMEOUT_MS}ms) for illustId=$illustId, expected=$expectedPageCount remaining=${items.size}")
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    // 文档参考：consumeUntilEmpty 中调用了不变量检查
    @Suppress("unused")
    private const val INVARIANT_CHECK_DOWNLOADING = "at-most-one-DOWNLOADING"
    private const val TAG = "QueueDownloadManager"
}

/** RxJava2 Observable -> suspend 单值。 */
private suspend fun <T : Any> Observable<T>.awaitFirstSafe(): T = suspendCancellableCoroutine { cont ->
    val disposable = subscribeOn(Schedulers.io())
        .firstOrError()
        .subscribe(
            { cont.resume(it) },
            { cont.resumeWithException(it) }
        )
    cont.invokeOnCancellation { disposable.dispose() }
}
