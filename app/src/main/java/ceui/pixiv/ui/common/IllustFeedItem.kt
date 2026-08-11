package ceui.pixiv.ui.common

import ceui.lisa.activities.Shaft
import ceui.lisa.helper.IllustNovelFilter
import ceui.lisa.models.IllustsBean
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.pixiv.feeds.FeedItem

/** 收藏状态局部重绑的 payload 标记（按引用识别），插画 feed 卡片共用。 */
val PAYLOAD_ILLUST_LIKE_CHANGED = Any()

/**
 * 只有收藏状态变了 → 给出局部重绑 payload；其他字段有变化则回退全量绑定。
 * 各插画卡 Renderer 的 changePayload 直接引用本函数。
 */
fun illustLikeChangePayload(old: IllustFeedItem, new: IllustFeedItem): Any? {
    return if (old.bean.trendingScore == new.bean.trendingScore &&
        old.illust.copy(is_bookmarked = new.illust.is_bookmarked) == new.illust
    ) {
        PAYLOAD_ILLUST_LIKE_CHANGED
    } else {
        null
    }
}

/**
 * 插画 feed 条目：loxia [Illust]（immutable，驱动 UI 与 DiffUtil）+ legacy [IllustsBean]
 * （可变，供详情页 pager / 下载 / 收藏等 legacy 链路共享同一实例）。
 *
 * 内容相等性看 [illust]（immutable data class，深比较）+ [bean] 的 trendingScore：bean 整体是
 * legacy 可变对象、没有 equals，参与比较会让每次刷新的同内容条目都被判成「变了」而整列表白白重绑，
 * 所以只额外比 trendingScore 这一个 Float 字段（本月收藏/当前最热的热度分 pill，不在 illust 上）——
 * 刷新时同一作品热度分变了要重绑更新 pill；普通列表两侧都 null，退化成只比 illust（无副作用）。
 *
 * 本文件只放「条目是什么、怎么从各种上游建出来、状态怎么变」：不碰 View、不依赖 Fragment。
 * 怎么画在 [staggerIllustRenderer]，长按菜单在 [showCardMenu]，页面怎么编排在 [IllustFeedFragment]。
 *
 * 注意仍**不能**用纯 JVM 单测覆盖：还挂着 [Shaft]（静态 gson / settings）、[IllustNovelFilter]
 * （同步 Room 查询）、[ObjectPool]（LiveData）这三个 Android 静态依赖，而本仓没有 Robolectric。
 * 真要测得先把它们收成可注入的接口 —— 那是另一件事，本次拆分没做。
 */
class IllustFeedItem(
    val illust: Illust,
    val bean: IllustsBean,
) : FeedItem {

    override val feedKey: Any get() = illust.id

    override fun equals(other: Any?): Boolean {
        return other is IllustFeedItem && other.illust == illust &&
                other.bean.trendingScore == bean.trendingScore
    }

    override fun hashCode(): Int = illust.hashCode() * 31 + (bean.trendingScore?.hashCode() ?: 0)

    /**
     * 收藏状态变更：把这次变更同步到本作品的**每一个共享表示**——
     * - [bean]：可变对象，与详情页 pager / 下载 / legacy 收藏链路共享同一实例，就地写；
     * - [illust]：immutable，走 copy 让相等性变化，驱动 DiffUtil 原地重绑爱心；
     * - [ObjectPool]：V3 详情页按 id 读池（不读列表传过去的 bean），漏了它列表红心进详情就是灰心。
     *
     * 池这一刀过去由 [IllustFeedPoolSync] 代劳，但它按 bean **实例**去重
     *（`pooledBeans.put(id, bean) !== bean` 才合池），而本方法恰恰是就地改同一个实例——重扫时
     * 实例没变就被跳过，池永远收不到收藏态。更隐蔽的是：`ObjectPool.updateObjectPool` 命中已有
     * 条目且不同实例时走 `mergeKeepingExisting`，那是一次 gson 往返，**存进池的是第三个克隆**，
     * 与 pooledBeans 记的实例从此分家（刷新 / 本地优先冷启必然造出这个克隆）。于是池里那份既
     * 不是 bean、也没人再更新它。所以同步收藏态的责任必须落在变更点，也就是这里。
     *
     * 幂等：已是目标态直接原样返回（对齐 [UserFeedItem.withFollowed]）。本页自己发起的收藏会经
     * LIKED_ILLUST 广播绕回自己，没有这个守卫就会白白多跑一轮全表 diff + 全表池重扫。
     *
     * ⚠️ 本方法带副作用，而它通常在 [ceui.pixiv.feeds.FeedViewModel.mutateItems] 的 transform
     * 里被调用——那里的契约是纯函数。这是被明确承认的例外（见 mutateItems 的 KDoc），依据是：
     * 副作用幂等 + VM 状态变更全在主线程（`MutableStateFlow.update` 因此不重放 lambda）。
     * 另外注意 [bean] 是新旧两代条目共享的实例，就地写它意味着**内容比较看不见收藏态变化**——
     * 这里靠 [illust] 走 copy 来让相等性真的变，两者缺一不可，别只改 bean。
     */
    fun withBookmarked(liked: Boolean): IllustFeedItem {
        if (illust.is_bookmarked == liked && bean.isIs_bookmarked == liked) return this
        bean.setIs_bookmarked(liked)
        // 只同步作品本身：关注态没变，不必连 user 一起 merge
        ObjectPool.update(bean)
        return IllustFeedItem(illust.copy(is_bookmarked = liked), bean)
    }

    companion object {
        /**
         * loxia.Illust 与 IllustsBean 字段名完全一致，gson 直转（同稍后再看页的做法）。
         * 走 toJsonTree/fromJson(JsonElement) 而不是 toJson/fromJson(String)：省掉字符串
         * 编码/解析那一趟 IO，只留字段级反射转换——这个函数是本地优先冷启路径的热点
         * （单页几十条，每条一次全字段转换，字符串往返在这里是纯浪费）。
         */
        fun beanOf(illust: Illust): IllustsBean? {
            return runCatching {
                Shaft.sGson.fromJson(Shaft.sGson.toJsonTree(illust), IllustsBean::class.java)
            }.getOrNull()
        }

        /** 过滤 + 建条目；[beanOf] 拆开单独暴露是给「过滤前整页喂 DiscoveryPool」的场景。 */
        fun of(
            illust: Illust,
            bean: IllustsBean,
            skipR18Filter: Boolean = false,
            skipAiFilter: Boolean = false,
            skipMuteUserFilter: Boolean = false,
        ): IllustFeedItem? {
            if (!passesContentFilters(bean, skipR18Filter, skipAiFilter, skipMuteUserFilter)) return null
            return IllustFeedItem(illust, bean)
        }

        fun from(illust: Illust, skipR18Filter: Boolean = false): IllustFeedItem? {
            val bean = beanOf(illust) ?: return null
            return of(illust, bean, skipR18Filter)
        }

        /** 详情 pager 回传的是 legacy bean，反向转一次。 */
        fun fromBean(
            bean: IllustsBean?,
            skipR18Filter: Boolean = false,
            skipAiFilter: Boolean = false,
            skipMuteUserFilter: Boolean = false,
        ): IllustFeedItem? {
            if (bean == null || !passesContentFilters(bean, skipR18Filter, skipAiFilter, skipMuteUserFilter)) return null
            val illust = runCatching {
                Shaft.sGson.fromJson(Shaft.sGson.toJsonTree(bean), Illust::class.java)
            }.getOrNull() ?: return null
            return IllustFeedItem(illust, bean)
        }

        /**
         * 不做任何内容过滤、直接把已过滤好的 bean 建成条目（bean→loxia Illust 一次转换）。
         * 给「上游已经用 legacy Mapper/FilterMapper 过滤过」的场景用（搜索页 [ceui.pixiv.ui.search]）——
         * 那里的搜索专属过滤（R18 三态 / 仅看 AI）feeds 侧不复刻，绝不能再走 [of]/[fromBean] 的
         * passesContentFilters（它在「仅看 AI」时会把 AI 作品误删，也会重复跑一遍过滤）。
         */
        fun rawFromBean(bean: IllustsBean?): IllustFeedItem? {
            if (bean == null) return null
            val illust = runCatching {
                Shaft.sGson.fromJson(Shaft.sGson.toJsonTree(bean), Illust::class.java)
            }.getOrNull() ?: return null
            return IllustFeedItem(illust, bean)
        }

        /**
         * 与 legacy Mapper 对齐的内容过滤链（搜索专属的 R18 三态/仅看 AI 不适用）。
         * [skipR18Filter]：R18 专属榜单端点本身就是用来看 R18 的，不用全局 R18 过滤清空内容
         * （对齐 RankIllustRepo.enableSkipR18Filter）。整页被滤空时由 FeedViewModel
         * 空页追载兜住，不会翻页停摆。
         * [skipAiFilter]：同理，给「AI 专属榜单」用——用户主动点进 AI 榜,全局「屏蔽 AI 作品」
         * 就不该把它清空（那是用户设的「我平时不想看到 AI」，不是「我点开 AI 榜也不想看」）。
         * 只让步 AI 这一条：屏蔽画师/标签、R18 过滤在 AI 榜里照常生效。
         *
         * ⚠️ 这里面 judgeTag/judgeUserID 各是一次**同步 Room 查询**，调用方必须在后台线程
         * 跑（各 mapper 已由 PixivFeedSource 派到 Dispatchers.Default；详情回传链见
         * [IllustFeedDetailSync]），并且要容忍它抛错（Room 磁盘异常）。
         */
        private fun passesContentFilters(
            bean: IllustsBean,
            skipR18Filter: Boolean,
            skipAiFilter: Boolean = false,
            skipMuteUserFilter: Boolean = false,
        ): Boolean {
            if (!bean.isVisible) return false
            if (IllustNovelFilter.judgeTag(bean)) return false
            // 不挂 judgeID：被「屏蔽此作品」记下的单件作品在 feeds 里是**遮罩**而不是过滤——
            // 卡片留在原位糊掉 + 盖粒子，点一下能揭开（见 [ceui.pixiv.ui.common.IllustMuteStore]）。
            // 在这里滤掉的话条目压根不存在，取消屏蔽就无处下手。judgeID 仍留给画不出遮罩的老列表。
            // 屏蔽画师过滤：在「该画师本人作品页」让步（skipMuteUserFilter）——整页都是这个画师，
            // 全滤空只会触发空页追载狂翻页（offset 30/60/90…）；你主动点进他主页就该看到其作品
            // （同 skipR18Filter / skipAiFilter「点进专属页就别用全局过滤把它清空」的思路）。
            if (!skipMuteUserFilter && IllustNovelFilter.judgeUserID(bean)) return false
            if (!skipR18Filter && IllustNovelFilter.judgeR18Filter(bean)) return false
            if (!skipAiFilter && Shaft.sSettings.isDeleteAIIllust() && bean.isCreatedByAI) return false
            return true
        }
    }
}
