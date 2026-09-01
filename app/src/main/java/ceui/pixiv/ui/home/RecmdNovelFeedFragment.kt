package ceui.pixiv.ui.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.RankActivity
import ceui.lisa.databinding.RecyRankNovelHorizontalBinding
import ceui.lisa.databinding.RecyRecmdHeaderBinding
import ceui.lisa.helper.IllustNovelFilter
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.GlideUtil
import ceui.lisa.view.LinearItemHorizontalDecoration
import ceui.pixiv.api.Client
import ceui.loxia.Novel
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedLoadPhase
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.pixiv.cachedPixivFeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.NovelFeedFragment
import ceui.pixiv.ui.common.NovelFeedItem
import ceui.pixiv.ui.common.NovelMuteStore
import ceui.pixiv.ui.common.openNovelDetail
import ceui.pixiv.ui.common.showNovelCardMenu
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide

/**
 * 「推荐小说」页（FragmentNewNovel 的推荐 tab，feeds 框架版，替代 legacy FragmentRecmdNovel +
 * NAdapterWithHeadView + RecmdNovelRepo）。异构列表 = 横向排行榜预览头（整行）+ 小说卡列表，
 * 卡片/收藏/跳转全部复用基类 [NovelFeedFragment]（全程 Novel 数据类，零 gson）。
 *
 * 与 legacy 对齐：首屏 ranking_novels 渲染横向排行榜预览头（NovelHeader 同款 recy_recmd_header），
 * 「查看更多」进 RankActivity 小说榜，卡片点击开小说详情。本地优先缓存（同 RecmdIllustFeedFragment）。
 */
class RecmdNovelFeedFragment : NovelFeedFragment() {

    override val feedViewModel by feedViewModels {
        // 本地优先：给稳定 slot 开磁盘缓存，冷启秒显上次首屏再拉最新覆盖。
        cachedPixivFeedSource(
            slot = "recmd-novel",
            initialFetch = { Client.appApi.getRecommendedNovelsWithRanking() },
        ) { resp, phase ->
            mapRecmdNovelPage(resp.novels, resp.ranking_novels, phase)
        }
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(rankHeaderRenderer(), novelCardRenderer())
    }

    /** 横向排行榜预览头（整行）。对齐 legacy NovelHeader：seeMore 进小说榜，点卡片开小说详情。 */
    private fun rankHeaderRenderer() =
        feedRenderer<NovelRankPreviewHeaderItem, RecyRecmdHeaderBinding>(
            inflate = RecyRecmdHeaderBinding::inflate,
            fullSpan = true,
            create = { cell ->
                cell.binding.seeMore.setOnClick {
                    startActivity(Intent(requireContext(), RankActivity::class.java).apply {
                        putExtra("dataType", "小说")
                    })
                }
                cell.binding.ranking.layoutManager =
                    LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                cell.binding.ranking.addItemDecoration(
                    LinearItemHorizontalDecoration(DensityUtil.dp2px(8.0f))
                )
                cell.binding.ranking.setHasFixedSize(true)
            },
        ) { cell ->
            bindRankStrip(cell)
        }

    /**
     * 榜单预览条的内容绑定。点击开详情，长按弹和小说卡同一套菜单（[showNovelCardMenu]），
     * 「批量操作」作用在榜单这一份数据上而不是底下那条推荐流。
     *
     * 「屏蔽此作品」在这里的语义是**整张卡消失**而不是打码：横向条没有模糊层和粒子层，
     * 没有「点一下取消屏蔽」的落脚点。名单 bind 时现读、屏蔽后当帧重建 adapter，与
     * [mapRecmdNovelPage] 下次刷新的过滤口径一致；反悔走「屏蔽记录」页。同插画侧
     * [RecmdIllustFeedFragment] 的榜单条。
     */
    private fun bindRankStrip(cell: FeedCell<NovelRankPreviewHeaderItem, RecyRecmdHeaderBinding>) {
        val item = cell.itemOrNull ?: return
        cell.binding.topRela.isVisible = true
        val novels = item.rankNovels.filterNot { NovelMuteStore.isMuted(it.id) }
        // 同一批榜单重复 bind（滚动回收再回来）不重设 adapter，保留横向滚动位置。
        // 按 id 序列而不是条目本身认「同一批」：屏蔽掉一篇后条目没变、可见集合变了，得重建。
        val batchKey = novels.map { it.id }
        if (cell.binding.ranking.tag == batchKey) return
        cell.binding.ranking.tag = batchKey
        cell.binding.ranking.adapter = RecmdNovelRankAdapter(
            novels = novels,
            onClickNovel = { novelId -> openNovelDetail(novelId) },
            onLongClickNovel = { novel ->
                showNovelCardMenu(
                    // 这批小说在 mapRecmdNovelPage 里已经滤过了，不再跑一遍内容过滤
                    NovelFeedItem(novel),
                    scopedNovels = { novels },
                    onToggleSpoiler = { muted ->
                        if (NovelMuteStore.setMuted(novel.id, muted) { novel }) {
                            bindRankStrip(cell)
                        }
                    },
                )
            },
        )
    }

    companion object {
        /**
         * 页响应 → 条目。跑在 Default 线程、被 VM 长期持有，放伴生对象保证零捕获。
         * 首屏把 ranking_novels 拼成排行榜预览头插到最前。预览头同样滤掉屏蔽的作品/标签/作者
         *（issue #543，同插画侧 mapRecmdPage）；R18 口味过滤照旧不适用。
         */
        private fun mapRecmdNovelPage(
            novels: List<Novel>,
            rankingNovels: List<Novel>,
            phase: FeedLoadPhase,
        ): List<FeedItem> {
            val listItems = novels.mapNotNull { NovelFeedItem.of(it) }
            if (!phase.isFirstPage) {
                return listItems
            }
            val rankNovels = rankingNovels.filterNot { IllustNovelFilter.judge(it) }
            return if (rankNovels.isEmpty()) {
                listItems
            } else {
                listOf(NovelRankPreviewHeaderItem(rankNovels)) + listItems
            }
        }
    }
}

/**
 * 横向排行榜预览头（整行）。内容相等性按小说 id 序列：刷新拉到同一批榜单零重绑，
 * 换了批次才重设内部 adapter。
 */
class NovelRankPreviewHeaderItem(val rankNovels: List<Novel>) : FeedItem {

    private val rankIds: List<Long> = rankNovels.map { it.id }

    override val feedKey: Any get() = "recmd_novel_rank_header"

    override fun equals(other: Any?): Boolean {
        return other is NovelRankPreviewHeaderItem && other.rankIds == rankIds
    }

    override fun hashCode(): Int = rankIds.hashCode()
}

/**
 * 排行榜预览头里的横向小说卡 adapter（数据类原生，recy_rank_novel_horizontal，替代 legacy NHAdapter）。
 * 点击开小说详情（走 novelId，不传序列化 bean），长按弹小说卡菜单。
 */
private class RecmdNovelRankAdapter(
    private val novels: List<Novel>,
    private val onClickNovel: (Long) -> Unit,
    private val onLongClickNovel: (Novel) -> Unit,
) : RecyclerView.Adapter<RecmdNovelRankAdapter.VH>() {

    class VH(val binding: RecyRankNovelHorizontalBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(
            RecyRankNovelHorizontalBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )
    }

    override fun getItemCount(): Int = novels.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val novel = novels[position]
        val b = holder.binding
        val ctx = b.root.context
        b.title.text = novel.title ?: ""
        b.author.text = novel.user?.name ?: ""
        b.novelLength.text =
            ctx.getString(R.string.v3_novel_word_count, (novel.text_length ?: 0).toString())
        Glide.with(ctx).load(GlideUtil.getUrl(novel.image_urls?.medium))
            .placeholder(R.color.v3_surface_2).into(b.illustImage)
        // 头像总是 into（不套 ?.let）：user 为 null 时 getUrl(null) 走占位，清掉复用卡的旧头像残影
        Glide.with(ctx).load(GlideUtil.getUrl(novel.user?.profile_image_urls?.medium))
            .placeholder(R.color.v3_surface_2).into(b.userHead)
        b.root.setOnClick { onClickNovel(novel.id) }
        b.root.setOnLongClickListener {
            onLongClickNovel(novel)
            true
        }
    }
}
