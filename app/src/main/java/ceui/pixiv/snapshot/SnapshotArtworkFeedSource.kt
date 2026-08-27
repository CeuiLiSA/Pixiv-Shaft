package ceui.pixiv.snapshot

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.loxia.Illust
import ceui.loxia.Comment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.ui.detail.ArtworkArtistItem
import ceui.pixiv.ui.detail.ArtworkCommentsItem
import ceui.pixiv.ui.detail.ArtworkDescItem
import ceui.pixiv.ui.detail.ArtworkDetailPanelItem
import ceui.pixiv.ui.detail.ArtworkHeroItem
import ceui.pixiv.ui.detail.ArtworkPageItem
import ceui.pixiv.ui.detail.ArtworkSeriesItem
import ceui.pixiv.ui.detail.ArtworkStatsItem
import ceui.pixiv.ui.detail.ArtworkTagsItem
import ceui.pixiv.ui.detail.ArtworkV3FeedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 快照详情页的 feeds 数据源：读快照库，产出与在线 ArtworkV3 相同的 Page/Header 条目。
 * 不产出作者作品 / 相关作品等需要联网的区块。
 *
 * [isAuto] 用于 cache miss 时路由到 AutoSnapshotRepository 或 SnapshotRepository。
 */
class SnapshotArtworkFeedSource(
    private val snapshotId: String,
    private val isAuto: Boolean,
) : FeedSource<String> {

    // FeedSource 契约要求实现 main-safe：load 在主线程被调用，磁盘读、Gson 深拷贝、
    // 逐条评论本地化都得留在 IO 上，不能只把 loadViewerData 那一段切过去。
    override suspend fun load(cursor: String?): FeedPage<String> = withContext(Dispatchers.IO) {
        if (cursor != null) return@withContext FeedPage(emptyList(), null)
        // 快照是不可变的，缓存里有就别再读一遍磁盘 + 解一遍整份 JSON(含全部评论)。
        val data = SnapshotRuntimeCache.get(snapshotId)
            ?: loadViewerData(Shaft.getContext())
        SnapshotRuntimeCache.put(snapshotId, data)
        val localized = data.localizeIllust()
        val pageItems = ArtworkV3FeedSource.buildArtworkPageItems(localized)
        val headerItems = buildLocalHeaderItems(data, localized)
        FeedPage(pageItems + headerItems, null)
    }

    private fun loadViewerData(context: Context): SnapshotViewerData {
        return if (isAuto) {
            AutoSnapshotRepository.loadAutoViewerData(context, snapshotId)
        } else {
            SnapshotRepository.loadViewerData(context, snapshotId)
        }
    }

    private fun buildLocalHeaderItems(
        data: SnapshotViewerData,
        illust: Illust,
    ): List<FeedItem> {
        val list = mutableListOf<FeedItem>()
        list.add(ArtworkHeroItem(illust))
        if (illust.series != null && !illust.series.title.isNullOrEmpty()) {
            list.add(ArtworkSeriesItem(illust))
        }
        list.add(
            ArtworkArtistItem(
                illust,
                // 兼容旧快照：manifest 没写 isFollowed 时回落到 illust.json 里存的那一份。
                isFollowed = data.manifest.isFollowed || data.illust.user?.is_followed ?: false,
                isPrivateFollow = false,
            )
        )
        if (!illust.caption.isNullOrEmpty()) {
            list.add(ArtworkDescItem(illust.caption, illust.title.orEmpty()))
        }
        list.add(ArtworkTagsItem(illust))
        list.add(ArtworkStatsItem(illust))
        list.add(ArtworkDetailPanelItem(illust))
        val previewComments = data.comments?.threads
            ?.take(3)
            ?.map { thread -> data.localizeComment(thread.comment) }
            .orEmpty()
        list.add(
            ArtworkCommentsItem(
                illustId = illust.id.toInt(),
                illustTitle = illust.title.orEmpty(),
                illustAuthorId = illust.user?.id ?: 0L,
                comments = previewComments,
                fetched = true,
            )
        )
        return list
    }
}

/**
 * 进程内快照数据缓存：FeedSource 加载后供 Fragment 的 page adapter / 评论页复用。
 *
 * 有界 LRU：每条 entry 是一整份解析好的快照（illust bean + 全部评论 + assets 表），
 * 无界持有会让内存随「本次进程里打开过多少个快照」单调增长，且永不释放。
 * 上限取 [MAX_ENTRIES] —— 详情页 / 大图页 / 评论页同时只会用到当前这一个快照，
 * 留几格余量即可；被淘汰的快照下次打开时各读取点都有磁盘回落，不影响正确性。
 */
object SnapshotRuntimeCache {

    private const val MAX_ENTRIES = 4

    private val data = object : LinkedHashMap<String, SnapshotViewerData>(
        MAX_ENTRIES, 0.75f, /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SnapshotViewerData>) =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun put(snapshotId: String, value: SnapshotViewerData) {
        data[snapshotId] = value
    }

    @Synchronized
    fun get(snapshotId: String): SnapshotViewerData? = data[snapshotId]

    @Synchronized
    fun remove(snapshotId: String) {
        data.remove(snapshotId)
    }
}