package ceui.pixiv.ui.detail

import android.text.TextUtils
import ceui.lisa.activities.Shaft
import ceui.lisa.core.Mapper
import ceui.lisa.database.AppDatabase
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.loxia.Illust
import ceui.loxia.Client
import ceui.loxia.Comment
import ceui.loxia.ObjectPool
import ceui.loxia.fetchFullIllustDetail
import ceui.loxia.isFullDetail
import ceui.pixiv.db.RecordType
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.ui.common.IllustFeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * ArtworkV3 详情页的 feeds 数据源。零捕获:只持有 [illustId]。
 *
 * - 首页 `load(null)`:解析出完整详情 bean → 拼「顶部大图页 + header 区块 + 相关作品第 1 页」,
 *   cursor = 相关 nextUrl;
 * - 翻页 `load(cursor)`:拉相关下一页。
 *
 * 相关作品**不随首屏加载**——首屏只出大图 + header,相关等区块滚到可见才懒载(见 [ArtworkSection]),
 * 对齐用户「进页时别发多余请求」的诉求。
 */
class ArtworkV3FeedSource(
    private val illustId: Long,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        if (cursor != null) {
            // 翻页:相关作品第 2 页起(第 1 页由「相关」区块滚到可见时才懒加载,见 ArtworkSection.RELATED)。
            Timber.tag(ARTWORK_LAZY_TAG).d("load(cursor) 相关翻页 illustId=%d cursor=%s", illustId, cursor)
            val (related, nextUrl) = fetchArtworkRelated(illustId, cursor)
            return FeedPage(related, nextUrl)
        }

        // 首屏只出「顶部大图 + header」;相关作品**不在此拉**——否则刚点进页面就立刻发 related
        // 请求(用户明明还没往下翻)。等「相关作品」区块真正滚到可见,ArtworkSection.RELATED 才拉
        // 第 1 页并原子追加条目/交接游标，重新开启分页。cursor=null 表示暂无下一页。
        Timber.tag(ARTWORK_LAZY_TAG).d("进页 load(null) 开始 illustId=%d", illustId)
        val illust = resolveFullIllust() ?: return FeedPage(emptyList(), null)
        Timber.tag(ARTWORK_LAZY_TAG).d(
            "进页只出「大图 + header」,不拉 related/comments/authorWorks illustId=%d", illustId,
        )
        return FeedPage(buildArtworkPageItems(illust) + buildArtworkHeaderItems(illust), null)
    }

    /**
     * 解析完整详情 bean,依次:
     * 1. 池里已是完整版 → 直接用(从列表页进来的常态,零请求);
     * 2. 回 v1/illust/detail 拉完整版(顺带覆盖池)——覆盖精简来源 / 深链接;
     * 3. 拉取失败 / 已删,但池里有(精简)bean → 降级用它(issue #569:按 Tag 筛选等);
     * 4. 池空 + 拉取失败(离线深链接 / widget 冷启)→ 退回浏览历史 DB,
     *    入池后返回,避免离线时看过的作品也整页空白(对齐 legacy loadData 的兜底链)。
     *
     * ⚠️ 这一步是**首屏的阻塞路径**:它一走网络,整页就没有任何条目可提交,详情页(骨架图为 null)
     * 会画出全屏转圈。所以判据只能问一件事——「池里这条 bean 够不够把首屏画出来」,也就是
     * [isFullDetail] 的图片字段。**不要**再往这里挂「顺便补个字段」类的条件:那类补拉一律走后台
     * (见 [ArtworkV3ViewModel] 的 caption 补拉),落池后靠 observer 增量改条目。
     */
    private suspend fun resolveFullIllust(): Illust? {
        val existing = ObjectPool.get<Illust>(illustId).value
        if (existing != null && existing.isFullDetail()) {
            Timber.tag(ARTWORK_LAZY_TAG).d("resolveFullIllust: 池里已是完整版,零 API 直接用 illustId=%d", illustId)
            return existing
        }
        Timber.tag(ARTWORK_LAZY_TAG).d(
            "resolveFullIllust: 池里%s,回 v1/illust/detail 拉完整版(唯一进页 API) illustId=%d",
            if (existing == null) "无此作品" else "只有精简版", illustId,
        )
        fetchFullIllustDetail(illustId)?.let { return it }
        Timber.tag(ARTWORK_LAZY_TAG).w("resolveFullIllust: 拉完整版失败,降级/兜底 illustId=%d", illustId)
        if (existing != null) return existing
        return withContext(Dispatchers.IO) {
            val cached = runCatching {
                AppDatabase.getAppDatabase(Shaft.getContext()).generalDao()
                    .getByRecordTypeAndId(RecordType.VIEW_ILLUST_HISTORY, illustId)
                    ?.typedObject<Illust>()
            }.getOrNull()
            // 顺带覆盖池让 FAB VM 的 observer fire。
            // ObjectPool.update 内部 setValue 抛后台线程异常会自愈成 postValue,IO 线程调用安全。
            cached?.also { ObjectPool.updateIllust(it) }
        }
    }

    companion object {
        /** 顶部大图页条目:ugoira 单条;静态图折叠时只 p0,否则全 P。 */
        fun buildArtworkPageItems(illust: Illust): List<FeedItem> {
            if (illust.isGif()) return listOf(ArtworkUgoiraItem(illust.id))
            val pageCount = illust.page_count.coerceAtLeast(1)
            val visible = if (CollapsibleIllustAdapter.shouldCollapse(pageCount)) 1 else pageCount
            return (0 until visible).map { ArtworkPageItem(illust.id, it) }
        }

        /** header 区块条目,顺序对齐 legacy ArtworkV3ViewModel.doBuildHeaderItems。 */
        fun buildArtworkHeaderItems(illust: Illust): List<FeedItem> {
            val list = mutableListOf<FeedItem>()
            list.add(ArtworkHeroItem(illust))
            if (illust.series != null && !TextUtils.isEmpty(illust.series.title)) {
                list.add(ArtworkSeriesItem(illust))
            }
            list.add(ArtworkArtistItem(illust))
            // 产出条件仍然只看 caption:没有简介的作品(pixiv 上是多数)一旦也产出这块,
            // 详情页就会多出「简介表头 + 翻译按钮 + 一个空正文」约 120dp 的空区块。
            // 标题跟着一起带下去,只是为了让简介块的翻译按钮能连标题一起翻。
            if (!TextUtils.isEmpty(illust.caption)) {
                list.add(ArtworkDescItem(illust.caption.orEmpty(), illust.title.orEmpty()))
            }
            list.add(ArtworkTagsItem(illust))
            list.add(ArtworkStatsItem(illust))
            list.add(ArtworkDetailPanelItem(illust))
            list.add(ArtworkCommentsItem(illust.id.toInt(), illust.title ?: "", illust.user?.id ?: 0L))
            list.add(ArtworkAuthorWorksItem(illust.user?.name ?: "", illust.user?.id ?: 0L))
            // 相关作品头初始 state=null(加载中);等区块滚到可见才拉,见 ArtworkSection.RELATED
            list.add(ArtworkRelatedHeaderItem(illust.id.toInt(), illust.title ?: ""))
            return list
        }
    }
}

/** 详情页懒加载诊断日志统一 tag:`adb logcat -s ArtworkV3Lazy` 即可验证「按需加载」。 */
internal const val ARTWORK_LAZY_TAG = "ArtworkV3Lazy"

// ── 详情页各区块的纯数据抓取(data layer):无 View / 无 Fragment 依赖,可独立测。 ──
// 懒加载区块的「何时拉」由 [ArtworkSection] 编排,这里只管「怎么拉」。每个 fetcher 进来先打一条
// 「API 发出」——进页时若看不到这些,就证明相关 / 评论 / 作者作品都没在进页时白发请求。

/**
 * 相关作品一页(共享给数据源翻页 [ArtworkV3FeedSource.load] 与 [ArtworkSection.RELATED] 的懒加载)。
 * 走全局 Mapper 就地过滤(屏蔽 tag / 作者 / 作品 + R18 + AI);Mapper 已过滤,raw
 * 不二次过滤(否则「仅看 AI」等会误删)。main-safe。
 */
internal suspend fun fetchArtworkRelated(
    illustId: Long,
    cursor: String?,
): Pair<List<FeedItem>, String?> = withContext(Dispatchers.IO) {
    Timber.tag(ARTWORK_LAZY_TAG).d("API 发出: 相关作品 illustId=%d cursor=%s", illustId, cursor)
    val url = cursor ?: "https://app-api.pixiv.net/v2/illust/related?illust_id=$illustId"
    val body = Client.appApi.generalGet(url)
    val parsed = Shaft.sGson.fromJson(body.string(), ListIllust::class.java)
        ?: return@withContext emptyList<FeedItem>() to null
    if (parsed.illusts != null) {
        Mapper<ListIllust>().apply(parsed)
    }
    val items = parsed.list.orEmpty().mapNotNull { IllustFeedItem.raw(it) }
    items to parsed.next_url
}

/**
 * 评论预览(前 3 条)。错误交给调用方 [ArtworkSection.COMMENTS](永久 404 标失败态,
 * 其余交 SectionLoader 重试),取消必须透传。main-safe。
 */
internal suspend fun fetchArtworkComments(illustId: Long): List<Comment> = withContext(Dispatchers.IO) {
    Timber.tag(ARTWORK_LAZY_TAG).d("API 发出: 评论预览 illustId=%d", illustId)
    Client.appApi.getIllustComments(illustId).comments.take(3)
}

/** 作者其他作品(前 10 条,排除当前作品)。走全局 Mapper 过滤。main-safe。 */
internal suspend fun fetchAuthorWorks(
    userId: Long,
    excludeIllustId: Long,
): List<Illust> = withContext(Dispatchers.IO) {
    Timber.tag(ARTWORK_LAZY_TAG).d("API 发出: 作者其他作品 userId=%d", userId)
    val resp = Retro.getAppApi().getUserSubmitIllust(userId, "illust")
    if (resp.illusts != null) Mapper<ListIllust>().apply(resp)
    resp.list?.filter { it.id != excludeIllustId }?.take(10) ?: emptyList()
}
