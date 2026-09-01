package ceui.pixiv.ui.library

import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedPagingPolicy
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.db.mirror.AgeFilter
import ceui.pixiv.db.mirror.AiFilter
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.NovelFeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 收藏库的数据源：**纯本地**，一次查一页镜像表。
 *
 * ## 游标为什么是 String
 *
 * 本页复用 [ceui.pixiv.ui.common.IllustFeedFragment]（白拿标准瀑布流卡、长按菜单、
 * 详情回传、收藏红心广播同步），而它把游标类型钉死成 `String`。本地分页真正需要的
 * 只是一个 offset，所以这里把 offset 编码进字符串。
 * 页面同时把 `detailContinuationCursor` 覆写成 null —— 详情页的续拉会把游标当
 * `@Url` 直接请求，本地 offset 绝不能流到那条路上去。
 *
 * ## 为什么用 offset 而不是 keyset
 *
 * 排序键是用户随手换的（收藏顺序 / 发布时间 / 热度 / 字数 / 随机…），keyset 要为每种
 * 排序各写一套「上一页最后一行的值」的比较条件，复杂度和出错面都不成比例。而本地表在
 * 一次浏览会话里几乎不动（后台镜像每 5 秒才可能写一次），offset 漂移的风险极低；
 * 每种排序又都补了 `targetId DESC` 作全序兜底键（见 [ceui.pixiv.db.mirror.BookmarkMirrorQuery]），
 * 并列行的次序不会在两次查询之间抖动。
 *
 * ## 内容过滤跟着「它替代的那个页面」走
 *
 * **不是 `raw`（完全不过滤）**。本页替代的是「我的插画/小说收藏」，而那两个页面的既定
 * 立场是：反刷屏阈值（小说的字数/超长 tag）不该裁剪用户自己的藏书，但**屏蔽标签 / 屏蔽
 * 画师 / R18 / AI 屏蔽照常生效** —— 那几条是用户对特定对象或类别的显式表态，换个入口
 * 就绕过去，等于这个新页面偷偷把用户明确说过不想看的东西放了进来。
 *
 * 两处让步，都用仓库既有的那条规则（「点进专属页就别再用全局过滤把它清空」）：
 * - 用户在筛选面板里**显式选了分级**（R-18 / R-18G / 全年龄）→ 跳过全局 R18 过滤，
 *   否则那个控件会变成一个永远筛出 0 条的死按钮；
 * - 显式选了 AI 档同理。
 *
 * 「作品状态」那一维交给 SQL（[BookmarkMirrorQuery]）：它还要能**反过来只看失效的**，
 * 好让用户找出来清理，所以这里一律 `skipVisibleFilter`，不做二次挡。
 *
 * ## 零 Fragment 捕获
 *
 * 只持有 [viewModel]（同一 ViewModelStore、同生命周期），筛选条件每次 [load] 现读 ——
 * 用户在 sheet 里改完条件，页面调一次 `forceRefresh()` 就是新结果，数据源不用重建。
 */
class BookmarkLibraryFeedSource(
    private val viewModel: BookmarkLibraryViewModel,
    /** 决定每行 payload 解析成插画卡还是小说卡。同一张表、同一套查询，只有这一步不同。 */
    private val contentType: MirrorContentType,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        val offset = cursor?.toIntOrNull() ?: 0
        val filter = viewModel.filter.value
        if (filter.shelfKey.isEmpty()) {
            // VM 还没 bind（理论上不会：Fragment 在 onViewCreated 里先 bind）。
            // 与其抛，不如给一页空的：页面会显示空态而不是错误态。
            Timber.tag(TAG).w("VM 尚未绑定书架，返回空页")
            return FeedPage(emptyList(), null)
        }
        val rows = BookmarkLibraryRepo.page(filter, PAGE_SIZE, offset)
        // ⚠️ 必须切线程：[FeedViewModel] 是在 viewModelScope（**主线程**）里直接 await
        // `source.load()` 的，`load` 里没有 withContext 的那部分就是主线程代码。一页 60 条
        // 完整 Illust JSON 的 gson 反序列化在中端机上是几十到上百毫秒，落在滚动路径上就是
        // 肉眼可见的掉帧。同仓 PixivFeedSource 的 mapper 也是为这条契约才 withContext(Default)。
        val items: List<FeedItem> = withContext(Dispatchers.Default) {
            rows.mapNotNull { row ->
                when (contentType) {
                    MirrorContentType.ILLUST ->
                        IllustFeedItem.of(
                            BookmarkLibraryRepo.toIllust(row),
                            skipR18Filter = filter.age != AgeFilter.ANY,
                            skipAiFilter = filter.ai != AiFilter.ANY,
                            skipVisibleFilter = true,
                        )
                    MirrorContentType.NOVEL ->
                        BookmarkLibraryRepo.toNovel(row)?.let { novel ->
                            NovelFeedItem.of(
                                novel,
                                skipR18Filter = filter.age != AgeFilter.ANY,
                                skipAiFilter = filter.ai != AiFilter.ANY,
                                // 对齐 LikeNovelFeedFragment：字数/超长 tag 那套反刷屏阈值
                                // （issue #743）是冲着发现面上的广告去的，不该把用户自己
                                // 收藏过的短文从藏书里抹掉。
                                skipSpamFilter = true,
                            )
                        }
                }
            }
        }
        // 不足一页 = 到底了。注意判据是**查回来的行数**而不是映射后的条目数：
        // 中间夹着几条反序列化失败的坏行时，用条目数会提前判定到底，把后面的收藏全吞掉。
        val nextCursor = if (rows.size < PAGE_SIZE) null else (offset + rows.size).toString()
        return FeedPage(items, nextCursor)
    }

    /**
     * 本地翻页没有网络代价，所以**间隔闸门**（`minPageIntervalMs`）可以整个去掉 ——
     * 那一条是为了别把 pixiv 打成 429 才存在的，对着自己的 SQLite 只会让用户滑到一半白等。
     *
     * 但**页数预算必须留着**，不能直接用 [FeedPagingPolicy.Unlimited]：预算除了限流，
     * 还是唯一约束「列表能长到多大」的东西。[FeedViewModel] 是把翻过的页**累加**在内存里的，
     * 而收藏几万件的用户在这里一路甩下去是零成本的 —— 3 万条 `Illust`（每条带 tags /
     * user / image_urls）能吃掉上百 MB，直接 OOM。留着预算：连着甩满
     * [BURST_PAGE_BUDGET] 页（约 1800 件）就停下来等用户点 footer，而只要停手超过
     * `burstIdleResetMs` 预算就归零 —— 正常人怎么翻都碰不到，跑飞的那种翻法会被截住。
     */
    override fun pagingPolicy(): FeedPagingPolicy = LOCAL_POLICY

    private companion object {
        const val TAG = "BookmarkLibrary"

        /** 本地一页 60 条：查询是索引扫描，反序列化才是大头，60 条约几毫秒。 */
        const val PAGE_SIZE = 60

        /** 一串连着翻最多累积多少页（× [PAGE_SIZE] ≈ 1800 件）。 */
        const val BURST_PAGE_BUDGET = 30

        val LOCAL_POLICY = FeedPagingPolicy(
            maxAutoPages = BURST_PAGE_BUDGET,
            minPageIntervalMs = 0L,
        )
    }
}
