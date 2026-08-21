package ceui.pixiv.snapshot

import ceui.lisa.activities.Shaft
import ceui.lisa.models.IllustsBean
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
import java.util.concurrent.ConcurrentHashMap

/**
 * 快照详情页的 feeds 数据源：读私有快照库，产出与在线 ArtworkV3 相同的 Page/Header 条目。
 * 不产出作者作品 / 相关作品等需要联网的区块。
 */
class SnapshotArtworkFeedSource(
    private val snapshotId: String,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        if (cursor != null) return FeedPage(emptyList(), null)
        val data = withContext(Dispatchers.IO) {
            SnapshotRepository.loadViewerData(Shaft.getContext(), snapshotId)
        }
        SnapshotRuntimeCache.put(snapshotId, data)
        val localized = data.localizeIllust()
        val pageItems = ArtworkV3FeedSource.buildArtworkPageItems(localized)
        val headerItems = buildLocalHeaderItems(data, localized)
        return FeedPage(pageItems + headerItems, null)
    }

    private fun buildLocalHeaderItems(
        data: SnapshotViewerData,
        illust: IllustsBean,
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
                isFollowed = data.manifest.isFollowed || data.illust.user?.isIs_followed ?: false,
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
                illustId = illust.id,
                illustTitle = illust.title.orEmpty(),
                illustAuthorId = illust.user?.id ?: 0,
                comments = previewComments,
                fetched = true,
            )
        )
        return list
    }
}

/** 进程内快照数据缓存：FeedSource 加载后供 Fragment 的 page adapter / 评论页复用。 */
object SnapshotRuntimeCache {

    private val data = ConcurrentHashMap<String, SnapshotViewerData>()

    fun put(snapshotId: String, value: SnapshotViewerData) {
        data[snapshotId] = value
    }

    fun get(snapshotId: String): SnapshotViewerData? = data[snapshotId]

    fun remove(snapshotId: String) {
        data.remove(snapshotId)
    }
}