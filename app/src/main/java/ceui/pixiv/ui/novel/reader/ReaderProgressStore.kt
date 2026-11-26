package ceui.pixiv.ui.novel.reader

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.core.JavaAsync
import ceui.pixiv.ui.novel.NO_SCROLL_PERCENT
import ceui.pixiv.ui.novel.novelReadingPercent
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight MMKV-backed per-novel progress storage with a lock-free in-memory index for
 * zero-IO list binding and a reactive [revisionLive] stream for cross-tab/cross-page updates.
 *
 * - Hot path ([peekReadingPercent]): single [ConcurrentHashMap] lookup — zero JNI, zero String
 *   allocation, zero disk IO on RecyclerView fling.
 * - Warm-up ([ensureLoaded]): scans MMKV keys once on [Dispatchers.IO] at startup (and defensively
 *   when a novel list attaches), then bumps [revisionLive] if any persisted progress was loaded.
 * - Direct reads ([loadCharIndex] / [loadLastPageIndex] / [loadTotalPages] / [loadLastReadTime]):
 *   read from memory once loaded, falling back to MMKV if called before warm-up completes (e.g.
 *   cold-start deep link straight into the reader).
 */
object ReaderProgressStore {

    private const val MMKV_ID = "novel_reader_v3_progress"

    data class Progress(
        val charIndex: Int,
        val pageIndex: Int,
        val totalPages: Int,
        val lastReadTime: Long,
        val scrollPercent: Int = NO_SCROLL_PERCENT,
    )

    /**
     * 每次按需取 MMKV 实例（MMKV 原生层内部按 mmapID 复用单例，且列表热路径 [peekReadingPercent] 与
     * 预热后的读口都不碰它）；避免用 `by lazy` 永久持有单个封装对象，在多 shadow 单测环境下发生跨测试污染。
     */
    private fun store(): MMKV = MMKV.mmkvWithID(MMKV_ID)

    /** Lock-free read map for list cards; writes are coordinated with [loadLock] until [loaded]. */
    private val progressByNovel = ConcurrentHashMap<Long, Progress>()
    private val loadLock = Any()
    private val touchedBeforeLoad = HashSet<Long>()

    @Volatile
    private var loaded = false

    @Volatile
    private var loading = false

    private val revisionCounter = AtomicInteger()
    private val revisionLiveData = MutableLiveData(0)

    val revision: Int
        get() = revisionCounter.get()

    /**
     * 进度版本号的可观察版本。小说列表页挂 `viewLifecycleOwner` 订阅它做局部补绑（同
     * [ceui.pixiv.ui.common.MutedWorkStore.revisionLive] / [ceui.pixiv.snapshot.SnapshotIndex.revisionLive]），
     * ViewPager 里常驻 RESUMED 的兄弟 tab 也能立即同步，不依赖 onResume。
     */
    val revisionLive: LiveData<Int>
        get() = revisionLiveData

    private fun bumpRevision() {
        revisionLiveData.postValue(revisionCounter.incrementAndGet())
    }

    /** 应用启动或列表建视图时调用：把持久化进度一次性预热进内存 Map，不阻塞主线程。 */
    fun ensureLoaded() {
        if (loaded || loading) return
        synchronized(loadLock) {
            if (loaded || loading) return
            loading = true
        }
        JavaAsync.appScope.launch(Dispatchers.IO) {
            loadFromDisk()
        }
    }

    @VisibleForTesting
    internal fun loadFromDisk() {
        val scanned = try {
            scanPersisted(store())
        } catch (e: CancellationException) {
            synchronized(loadLock) { loading = false }
            throw e
        } catch (t: Throwable) {
            Timber.w(t, "ReaderProgressStore warm-up failed")
            synchronized(loadLock) { loading = false }
            return
        }
        var addedAny = false
        synchronized(loadLock) {
            for ((novelId, progress) in scanned) {
                if (novelId !in touchedBeforeLoad) {
                    progressByNovel[novelId] = progress
                    addedAny = true
                }
            }
            touchedBeforeLoad.clear()
            loaded = true
            loading = false
        }
        if (addedAny) {
            bumpRevision()
        }
    }

    /**
     * 列表卡片绑定热路径专用的无 IO 读口：只读内存缓存，绝不在主线程触发 MMKV 初始化或 JNI 调用。
     * 首次后台预热完成前返回 null，预热完成后通过 [revisionLive] 通知列表局部补绑。
     */
    fun peekReadingPercent(novelId: Long, textLength: Int?): Int? {
        if (novelId == 0L) return null
        val progress = progressByNovel[novelId] ?: return null
        return novelReadingPercent(
            charIndex = progress.charIndex,
            pageIndex = progress.pageIndex,
            totalPages = progress.totalPages,
            textLength = textLength,
            scrollPercent = progress.scrollPercent,
        )
    }

    fun saveProgress(
        novelId: Long,
        charIndex: Int,
        pageIndex: Int,
        totalPages: Int,
        scrollPercent: Int = NO_SCROLL_PERCENT,
    ) {
        val normalizedScrollPercent = if (scrollPercent >= 0) scrollPercent.coerceIn(0, 100) else NO_SCROLL_PERCENT
        val now = System.currentTimeMillis()
        val next = Progress(charIndex, pageIndex, totalPages, now, normalizedScrollPercent)
        val prev = synchronized(loadLock) {
            if (!loaded) touchedBeforeLoad.add(novelId)
            progressByNovel.put(novelId, next)
        }
        val mmkv = store()
        mmkv.encode("char_$novelId", charIndex)
        mmkv.encode("page_$novelId", pageIndex)
        mmkv.encode("total_$novelId", totalPages)
        mmkv.encode("time_$novelId", now)
        if (normalizedScrollPercent >= 0) {
            mmkv.encode("scroll_pct_$novelId", normalizedScrollPercent)
        } else {
            mmkv.removeValueForKey("scroll_pct_$novelId")
        }
        if (prev == null ||
            prev.charIndex != charIndex ||
            prev.pageIndex != pageIndex ||
            prev.totalPages != totalPages ||
            prev.scrollPercent != normalizedScrollPercent
        ) {
            bumpRevision()
        }
    }

    fun loadCharIndex(novelId: Long): Int {
        progressByNovel[novelId]?.let { return it.charIndex }
        return if (loaded) 0 else store().decodeInt("char_$novelId", 0)
    }

    fun loadLastPageIndex(novelId: Long): Int {
        progressByNovel[novelId]?.let { return it.pageIndex }
        return if (loaded) 0 else store().decodeInt("page_$novelId", 0)
    }

    /** 保存那一刻的总页数；滚动模式存的是 0（它没有页的概念）。 */
    fun loadTotalPages(novelId: Long): Int {
        progressByNovel[novelId]?.let { return it.totalPages }
        return if (loaded) 0 else store().decodeInt("total_$novelId", 0)
    }

    /** 纵向滚动模式保存的阅读百分比（0–100）；翻页模式或旧版存档返回 [NO_SCROLL_PERCENT]。 */
    fun loadScrollPercent(novelId: Long): Int {
        progressByNovel[novelId]?.let { return it.scrollPercent }
        return if (loaded) NO_SCROLL_PERCENT else store().decodeInt("scroll_pct_$novelId", NO_SCROLL_PERCENT)
    }

    fun loadLastReadTime(novelId: Long): Long {
        progressByNovel[novelId]?.let { return it.lastReadTime }
        return if (loaded) 0L else store().decodeLong("time_$novelId", 0L)
    }

    /**
     * 系列里最后在阅读器打开的那一篇：系列页的「继续阅读 · 第 N 篇」靠它。只按单篇进度推不出来 ——
     * 超长系列的后几页章节系列页还没翻到，看不到它们的进度。
     */
    fun saveSeriesLastNovel(seriesId: Long, novelId: Long) {
        store().encode("series_last_$seriesId", novelId)
    }

    /** 没记过返回 0。 */
    fun loadSeriesLastNovel(seriesId: Long): Long = store().decodeLong("series_last_$seriesId", 0L)

    fun clear(novelId: Long) {
        val removed = synchronized(loadLock) {
            if (!loaded) touchedBeforeLoad.add(novelId)
            progressByNovel.remove(novelId)
        }
        val mmkv = store()
        mmkv.removeValueForKey("char_$novelId")
        mmkv.removeValueForKey("page_$novelId")
        mmkv.removeValueForKey("total_$novelId")
        mmkv.removeValueForKey("time_$novelId")
        mmkv.removeValueForKey("scroll_pct_$novelId")
        if (removed != null || !loaded) {
            bumpRevision()
        }
    }

    private fun scanPersisted(mmkv: MMKV): HashMap<Long, Progress> {
        val keys = mmkv.allKeys() ?: return HashMap()
        val ids = HashSet<Long>()
        for (key in keys) {
            val id = extractNovelId(key) ?: continue
            ids.add(id)
        }
        val scanned = HashMap<Long, Progress>(ids.size * 2)
        for (id in ids) {
            val charIndex = mmkv.decodeInt("char_$id", 0)
            val pageIndex = mmkv.decodeInt("page_$id", 0)
            val totalPages = mmkv.decodeInt("total_$id", 0)
            val lastReadTime = mmkv.decodeLong("time_$id", 0L)
            val scrollPercent = mmkv.decodeInt("scroll_pct_$id", NO_SCROLL_PERCENT)
            if (charIndex > 0 || pageIndex > 0 || totalPages > 0 || scrollPercent > 0 || lastReadTime > 0L) {
                scanned[id] = Progress(charIndex, pageIndex, totalPages, lastReadTime, scrollPercent)
            }
        }
        return scanned
    }

    internal fun extractNovelId(key: String): Long? = when {
        key.startsWith("char_") -> key.substring(5).toLongOrNull()
        key.startsWith("page_") -> key.substring(5).toLongOrNull()
        key.startsWith("total_") -> key.substring(6).toLongOrNull()
        key.startsWith("time_") -> key.substring(5).toLongOrNull()
        key.startsWith("scroll_pct_") -> key.substring(11).toLongOrNull()
        else -> null
    }?.takeIf { it != 0L }

    @VisibleForTesting
    internal fun resetForTest() {
        synchronized(loadLock) {
            progressByNovel.clear()
            touchedBeforeLoad.clear()
            loaded = false
            loading = false
        }
    }
}
