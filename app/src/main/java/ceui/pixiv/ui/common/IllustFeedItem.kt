package ceui.pixiv.ui.common

import ceui.lisa.helper.IllustNovelFilter
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.pixiv.feeds.FeedItem

/** 收藏状态局部重绑的 payload 标记（按引用识别），插画 feed 卡片共用。 */
val PAYLOAD_ILLUST_LIKE_CHANGED = Any()

/**
 * 只有收藏状态变了 → 给出局部重绑 payload；其他字段有变化则回退全量绑定。
 * 各插画卡 Renderer 的 changePayload 直接引用本函数。
 * trendingScore / isRelated 是 [Illust] 的 @Transient 构造参数，参与 data class 相等性，
 * 所以热度分变了会自然落到「全量重绑」分支。
 */
fun illustLikeChangePayload(old: IllustFeedItem, new: IllustFeedItem): Any? {
    return if (old.illust.copy(is_bookmarked = new.illust.is_bookmarked) == new.illust) {
        PAYLOAD_ILLUST_LIKE_CHANGED
    } else {
        null
    }
}

/**
 * 插画 feed 条目：只包一个 immutable 的 loxia [Illust]（驱动 UI 与 DiffUtil）。
 *
 * 本文件只放「条目是什么、怎么从各种上游建出来、状态怎么变」：不碰 View、不依赖 Fragment。
 * 怎么画在 [staggerIllustRenderer]，长按菜单在 [showCardMenu]，页面怎么编排在 [IllustFeedFragment]。
 *
 * 注意仍**不能**用纯 JVM 单测覆盖：还挂着 [Shaft]（静态 settings）、[IllustNovelFilter]
 * （同步 Room 查询）、[ObjectPool]（LiveData）这三个 Android 静态依赖，而本仓没有 Robolectric。
 */
class IllustFeedItem(
    val illust: Illust,
) : FeedItem {

    override val feedKey: Any get() = illust.id

    override fun equals(other: Any?): Boolean {
        return other is IllustFeedItem && other.illust == illust
    }

    override fun hashCode(): Int = illust.hashCode()

    /**
     * 收藏状态变更：[illust] 走 copy 让相等性变化、驱动 DiffUtil 原地重绑爱心；同时把新值写回
     * [ObjectPool]——V3 详情页按 id 读池（不读列表传过去的对象），漏了它列表红心进详情就是灰心。
     *
     * 幂等：已是目标态直接原样返回（对齐 [UserFeedItem.withFollowed]）。本页自己发起的收藏会经
     * LIKED_ILLUST 广播绕回自己，没有这个守卫就会白白多跑一轮全表 diff + 全表池重扫。
     *
     * ⚠️ 本方法带副作用（写池），而它通常在 [ceui.pixiv.feeds.FeedViewModel.mutateItems] 的 transform
     * 里被调用——那里的契约是纯函数。这是被明确承认的例外（见 mutateItems 的 KDoc），依据是：
     * 副作用幂等 + VM 状态变更全在主线程（`MutableStateFlow.update` 因此不重放 lambda）。
     */
    fun withBookmarked(liked: Boolean): IllustFeedItem {
        if (illust.is_bookmarked == liked) return this
        val next = illust.withBookmarked(liked)
        // 只同步作品本身：关注态没变，不必连 user 一起 merge
        ObjectPool.update(next)
        return IllustFeedItem(next)
    }

    companion object {
        /** 过滤 + 建条目；不通过内容过滤返回 null。 */
        fun of(
            illust: Illust?,
            skipR18Filter: Boolean = false,
            skipAiFilter: Boolean = false,
            skipMuteUserFilter: Boolean = false,
            skipVisibleFilter: Boolean = false,
        ): IllustFeedItem? {
            if (illust == null) return null
            if (!passesContentFilters(illust, skipR18Filter, skipAiFilter, skipMuteUserFilter, skipVisibleFilter)) return null
            return IllustFeedItem(illust)
        }

        /**
         * 不做任何内容过滤、直接建条目。
         * 给「上游已经用 legacy Mapper/FilterMapper 过滤过」的场景用（搜索页 [ceui.pixiv.ui.search]）——
         * 那里的搜索专属过滤（R18 三态 / 仅看 AI）feeds 侧不复刻，绝不能再走 [of] 的
         * passesContentFilters（它在「仅看 AI」时会把 AI 作品误删，也会重复跑一遍过滤）。
         */
        fun raw(illust: Illust?): IllustFeedItem? {
            return illust?.let { IllustFeedItem(it) }
        }

        /**
         * 与 legacy Mapper 对齐的内容过滤链（搜索专属的 R18 三态/仅看 AI 不适用）。
         * [skipR18Filter]：R18 专属榜单端点本身就是用来看 R18 的，不用全局 R18 过滤清空内容
         * （对齐 RankIllustRepo.enableSkipR18Filter）。整页被滤空时由 FeedViewModel
         * 空页追载兜住，不会翻页停摆。
         * [skipAiFilter]：同理，给「AI 专属榜单」用——用户主动点进 AI 榜,全局「屏蔽 AI 作品」
         * 就不该把它清空（那是用户设的「我平时不想看到 AI」，不是「我点开 AI 榜也不想看」）。
         * 只让步 AI 这一条：屏蔽画师/标签、R18 过滤在 AI 榜里照常生效。
         * [skipVisibleFilter]：收藏夹「过滤无效收藏」关闭时让步——不把 visible != true 的作品
         * 当失效滤掉；仅收藏页按开关传入，其余列表默认 false 保持恒过滤。
         *
         * ⚠️ 这里面 judgeTag/judgeUserID 各是一次**同步 Room 查询**，调用方必须在后台线程
         * 跑（各 mapper 已由 PixivFeedSource 派到 Dispatchers.Default；详情回传链见
         * [IllustFeedDetailSync]），并且要容忍它抛错（Room 磁盘异常）。
         */
        fun passesContentFilters(
            illust: Illust,
            skipR18Filter: Boolean,
            skipAiFilter: Boolean = false,
            skipMuteUserFilter: Boolean = false,
            skipVisibleFilter: Boolean = false,
        ): Boolean {
            if (!skipVisibleFilter && illust.visible != true) return false
            if (IllustNovelFilter.judgeTag(illust)) return false
            // 不挂 judgeID：被「屏蔽此作品」记下的单件作品在 feeds 里是**遮罩**而不是过滤——
            // 卡片留在原位糊掉 + 盖粒子，点一下即取消屏蔽（见 [ceui.pixiv.ui.common.IllustMuteStore]）。
            // 在这里滤掉的话条目压根不存在，取消屏蔽就无处下手。judgeID 仍留给画不出遮罩的老列表。
            // 屏蔽画师过滤：在「该画师本人作品页」让步（skipMuteUserFilter）——整页都是这个画师，
            // 全滤空只会触发空页追载狂翻页（offset 30/60/90…）；你主动点进他主页就该看到其作品
            // （同 skipR18Filter / skipAiFilter「点进专属页就别用全局过滤把它清空」的思路）。
            if (!skipMuteUserFilter && IllustNovelFilter.judgeUserID(illust)) return false
            if (!skipR18Filter && IllustNovelFilter.judgeR18Filter(illust)) return false
            if (!skipAiFilter && IllustNovelFilter.shouldHideAi(illust)) return false
            return true
        }
    }
}
