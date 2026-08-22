package ceui.pixiv.ui.common

import ceui.lisa.helper.IllustNovelFilter
import ceui.loxia.Novel
import ceui.pixiv.feeds.FeedItem

/**
 * 小说 feed 条目：只持不可变的 loxia [Novel]（data class）+ 可选的站长/热度 [trendingScore]。
 *
 * 对齐插画侧 [IllustFeedItem] 的思路，但小说全链路已 loxia 化（收藏走
 * [ceui.loxia.API.addNovelBookmark]、详情走 [DetailFeedSupport.openNovelDetail]、
 * 标签流 [ceui.pixiv.widgets.V3TagFlowView.setTags] 直接吃 loxia [ceui.loxia.Tag]），
 * 因此不再像 IllustFeedItem 那样并存 legacy 可变 bean、也不做 gson 往返——
 * 卡片渲染、收藏、跳转全部读这一个 data class。
 *
 * [trendingScore]：本月收藏/当前最热(shaft-api-v2)的热度值，露卡片左上角 "▲ N" pill；
 * 普通小说列表(最新/推荐)传 null → pill 自动隐藏。它不是 [Novel] 的字段，单独带。
 *
 * 内容相等性看 [novel]（深比较）+ [trendingScore]：热度值变了也要重绑 pill。
 */
class NovelFeedItem(
    val novel: Novel,
    val trendingScore: Float? = null,
) : FeedItem {

    override val feedKey: Any get() = novel.id

    override fun equals(other: Any?): Boolean {
        return other is NovelFeedItem && other.novel == novel && other.trendingScore == trendingScore
    }

    override fun hashCode(): Int = novel.hashCode() * 31 + (trendingScore?.hashCode() ?: 0)

    /**
     * 收藏态变更：copy 出新实例（相等性变化触发 DiffUtil 重绑爱心 + 收藏数），保留 [trendingScore]。
     * 幂等——已是目标态直接返回自身：乐观切态后 LIKED_NOVEL 广播会带着同一状态回流到
     * 本列表(含自己发的)再走一次 withBookmarked,不加这道守卫收藏数会被重复 ±1。
     */
    fun withBookmarked(liked: Boolean): NovelFeedItem {
        if ((novel.is_bookmarked == true) == liked) return this
        val delta = if (liked) 1 else -1
        val newCount = ((novel.total_bookmarks ?: 0) + delta).coerceAtLeast(0)
        return NovelFeedItem(novel.copy(is_bookmarked = liked, total_bookmarks = newCount), trendingScore)
    }

    companion object {
        /**
         * 过滤 + 建条目；整页被滤空时由 FeedViewModel 空页追载兜住。
         *
         * [skipR18Filter]：R18 专属榜单端点本身就是用来看 R18 的，别用全局 R18 过滤把它清空
         *（对齐插画侧 [ceui.pixiv.ui.common.IllustFeedItem.of] 的同名参数 / RankIllustRepo
         * 的 enableSkipR18Filter）。
         *
         * [skipSpamFilter]：给「用户自己存下来的小说」用（收藏列表）。字数/超长 tag 阈值
         *（issue #743）是冲着**发现面**上的刷屏广告去的，不是用来裁剪自己的藏书的——用户主动
         * 收藏过的东西，回来就得看得见（同 [ceui.pixiv.ui.watchlater.WatchLaterFeedFragment]
         * 「存的时候能存，回来就得看得见」那条约定）。收藏了一篇 300 字的短文，不该因为设了
         * 反刷屏下限就从收藏夹里消失。
         */
        fun of(
            novel: Novel,
            trendingScore: Float? = null,
            skipR18Filter: Boolean = false,
            skipMuteUserFilter: Boolean = false,
            skipSpamFilter: Boolean = false,
            skipAiFilter: Boolean = false,
        ): NovelFeedItem? {
            return if (passesContentFilters(novel, skipR18Filter, skipMuteUserFilter, skipSpamFilter, skipAiFilter)) {
                NovelFeedItem(novel, trendingScore)
            } else {
                null
            }
        }

        /**
         * 不做任何内容过滤、直接把小说建成条目。给「用户手动存下来的本地快照」用
         *（小说稍后再看页）：存的时候能存，回来就得看得见——R18 / 屏蔽标签 / 屏蔽画师
         * 那些全局过滤是冲着发现面去的，不能把用户主动收藏的东西二次滤掉
         *（同插画侧 [ceui.pixiv.ui.common.IllustFeedItem.rawFromBean] 的约定）。
         */
        fun rawFromNovel(novel: Novel?): NovelFeedItem? {
            if (novel == null) return null
            return NovelFeedItem(novel)
        }

        /**
         * 与 legacy [ceui.lisa.core.Mapper] 的小说分支逐条对齐（tag / id / 作者 / R18 过滤）。
         * 走 [IllustNovelFilter] 的 loxia Novel 重载，无需 NovelBean。
         */
        private fun passesContentFilters(
            novel: Novel,
            skipR18Filter: Boolean,
            skipMuteUserFilter: Boolean = false,
            skipSpamFilter: Boolean = false,
            skipAiFilter: Boolean = false,
        ): Boolean {
            if (IllustNovelFilter.judgeTag(novel)) return false
            // 不挂 judgeID：理由同插画侧——「屏蔽此作品」在 feeds 里是遮罩不是过滤，
            // 滤掉了就没法在卡片上取消（见 [ceui.pixiv.ui.common.NovelMuteStore]）。
            // 屏蔽画师过滤在「该作者本人小说页」让步（同插画侧）：整页都是这个作者，全滤空只会触发
            // 空页追载狂翻页；主动点进作者页就该看到其小说。
            if (!skipMuteUserFilter && IllustNovelFilter.judgeUserID(novel)) return false
            if (!skipR18Filter && IllustNovelFilter.judgeR18Filter(novel)) return false
            // 全局屏蔽 AI：完全不显示强度下剔除，模糊粒子化强度下保留由卡片打码（豁免作者除外）。
            if (!skipAiFilter && IllustNovelFilter.shouldHideAi(novel)) return false
            // 正文字数区间 + 超长标签名自动屏蔽（issue #743）。三个阈值默认 0（关闭）。
            // 只在收藏夹让步（skipSpamFilter，见 of 的 KDoc）；作者页/榜单不让步——刷屏号的
            // 作者页本来就该被滤掉，那正是这个功能的目标。
            if (!skipSpamFilter && IllustNovelFilter.judgeNovelSpam(novel)) return false
            return true
        }
    }
}
