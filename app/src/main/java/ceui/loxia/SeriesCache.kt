package ceui.loxia

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 系列（漫画 illust-series / 小说 novel-series）的进程内缓存。
 *
 * 目的：阅读器左右翻页找相邻话、左下角选话 sheet，原本各自每次都把整条系列（最多 ~10 页
 * / 300 篇）重新拉一遍。有了这层缓存，第一个用到的人拉一次、其余全部命中，翻页 / 开 sheet
 * 都不再打网络。
 *
 * 线程约定：缓存把整条系列的**单话实体**直接存在 [SeriesData.items] 里，**不写 ObjectPool**
 * ——loadXxx 一般跑在 Dispatchers.IO(见两个 sheet / ComicSeriesNavigator),而 ObjectPool
 * 的 MutableLiveData.setValue 只能主线程、它的 store 也不是线程安全的 map,从后台线程灌会
 * 撞坏 map + 触发 setValue 异常回退成 postValue 绕过 merge。所以消费方一律从 items 直接读。
 *
 * 语义对齐 ObjectPool：进程存活期缓存，不做过期失效；只在 [invalidate] / forceRefresh 时
 * 重建。系列详情页下拉刷新会 invalidate 本系列。
 */
object SeriesCache {

    /**
     * @param items 整条系列的单话实体，按接口返回顺序（漫画降序=最新在前 / 小说升序）。
     * @param total 总话数：漫画取 series_work_count，小说取 content_count。
     * @param firstEpisodeId 第1话 id（illust_series_first_illust / novel_series_first_novel），判列表方向用。
     * @param fullyLoaded 是否翻到 next_url==null；false 表示撞了 [DEFAULT_MAX_PAGES] 上限，可能还有更旧的话没进来。
     */
    data class SeriesData<T>(
        val seriesId: Long,
        val detail: NovelSeriesDetail?,
        val items: List<T>,
        val total: Int,
        val firstEpisodeId: Long?,
        val fullyLoaded: Boolean,
    )

    private const val DEFAULT_MAX_PAGES = 10

    private val illustStore = HashMap<Long, SeriesData<Illust>>()
    private val novelStore = HashMap<Long, SeriesData<Novel>>()
    private val illustMutex = Mutex()
    private val novelMutex = Mutex()

    fun peekIllustSeries(seriesId: Long): SeriesData<Illust>? =
        synchronized(illustStore) { illustStore[seriesId] }

    fun peekNovelSeries(seriesId: Long): SeriesData<Novel>? =
        synchronized(novelStore) { novelStore[seriesId] }

    /**
     * 拿漫画系列。命中缓存直接返回；否则接力翻页拉全，缓存后返回。mutex 让并发调用（翻页 +
     * sheet 同时触发）串行化，后到者等前者拉完直接复用，不重复打网络。
     */
    suspend fun loadIllustSeries(
        seriesId: Long,
        maxPages: Int = DEFAULT_MAX_PAGES,
        forceRefresh: Boolean = false,
    ): SeriesData<Illust> {
        if (!forceRefresh) peekIllustSeries(seriesId)?.let { return it }
        return illustMutex.withLock {
            if (!forceRefresh) peekIllustSeries(seriesId)?.let { return@withLock it }
            val items = mutableListOf<Illust>()
            var detail: NovelSeriesDetail? = null
            var firstId: Long? = null
            var total = 0
            var fully = false
            var lastOrder: Int? = null
            for (page in 0 until maxPages) {
                val resp = Client.appApi.getIllustSeries(seriesId, lastOrder)
                if (page == 0) {
                    detail = resp.illust_series_detail
                    total = detail?.series_work_count ?: 0
                    firstId = resp.illust_series_first_illust?.id
                }
                resp.illusts?.let { items.addAll(it) }
                if (resp.next_url == null) {
                    fully = true
                    break
                }
                lastOrder = items.size
            }
            val entry = SeriesData(seriesId, detail, items.toList(), total, firstId, fully)
            synchronized(illustStore) { illustStore[seriesId] = entry }
            entry
        }
    }

    /** 小说系列同 [loadIllustSeries]；总话数取 content_count，第1话取 novel_series_first_novel。 */
    suspend fun loadNovelSeries(
        seriesId: Long,
        maxPages: Int = DEFAULT_MAX_PAGES,
        forceRefresh: Boolean = false,
    ): SeriesData<Novel> {
        if (!forceRefresh) peekNovelSeries(seriesId)?.let { return it }
        return novelMutex.withLock {
            if (!forceRefresh) peekNovelSeries(seriesId)?.let { return@withLock it }
            val items = mutableListOf<Novel>()
            var detail: NovelSeriesDetail? = null
            var firstId: Long? = null
            var total = 0
            var fully = false
            var lastOrder: Int? = null
            for (page in 0 until maxPages) {
                val resp = Client.appApi.getNovelSeries(seriesId, lastOrder)
                if (page == 0) {
                    detail = resp.novel_series_detail
                    total = detail?.content_count ?: 0
                    firstId = resp.novel_series_first_novel?.id
                }
                resp.novels?.let { items.addAll(it) }
                if (resp.next_url == null) {
                    fully = true
                    break
                }
                lastOrder = items.size
            }
            val entry = SeriesData(seriesId, detail, items.toList(), total, firstId, fully)
            synchronized(novelStore) { novelStore[seriesId] = entry }
            entry
        }
    }

    /**
     * 本篇在系列列表里的 1-based 位置，下载文件名的 `{series_order}` 用
     * （issue #964）。与系列页批量下载的编号同源同序，正常情况下也等于
     * pixiv 的官方话数（webview payload 的 contentOrder / 详情的
     * content_count——#964 实测三者一致）。注意它可能和**作者写在标题里的
     * 编号**错位：作者插入「24.5」这类番外后标题编号会落后于实际话数，
     * 那是标题文本，不作为序号依据。命中缓存零网络；未命中按
     * [loadNovelSeries] 翻页拉取。找不到时返回 null（列表被 maxPages 截断的
     * 超长系列同样如此——宁可不编号也不编错号；能在已加载前缀里找到的话，
     * 前缀是从第 1 篇起连续的，位置一定正确）。
     */
    suspend fun novelPositionInSeries(seriesId: Long, novelId: Long): Int? {
        val data = loadNovelSeries(seriesId)
        val idx = data.items.indexOfFirst { it.id == novelId }
        return if (idx >= 0) idx + 1 else null
    }

    fun invalidate(seriesId: Long) {
        synchronized(illustStore) { illustStore.remove(seriesId) }
        synchronized(novelStore) { novelStore.remove(seriesId) }
    }
}
