package ceui.lisa.repo

import ceui.lisa.model.ListIllust
import ceui.lisa.model.ListNovel
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.ObjectPool

/**
 * 借号搜索页出仓前的「视角修正」（#1063）。
 *
 * 借号打到的官方结果——以及从跨用户一级缓存里拿到的那页——每条作品的 `is_bookmarked` 都是
 * **借来那个号 / 回填者**的收藏态，和当前用户毫无关系；原样交给 UI 就会出现「明明收藏了，
 * 搜索结果里却是灰心」（进详情页用自己的号拉 detail 又是红心）。
 *
 * Pixiv 没有按 id 批量查收藏态的接口，逐条打 detail 又太贵，所以这里只做两件确定正确的事：
 *  1. 进程内 [ObjectPool] 已经用自己的号确认过的（详情页、自己的收藏页 / feed 合过池的）沿用；
 *  2. 其余一律置 null（「不知道」）。UI 把 null 渲染成未收藏；合池时 null 属于空值，
 *     不会盖掉池里已有的真值（见 ObjectPool.mergeKeepingExisting），也就不会再把借号视角灌进池里。
 *
 * 借号失败回退到自己号直发的页（popular-preview / 普通搜索）本来就是当前用户视角，**不要**经这里。
 */
internal object BorrowedSearchPage {

    fun ListIllust.withViewerBookmarkState(
        known: (Long) -> Boolean? = ::knownIllustBookmark,
    ): ListIllust {
        val source = illusts ?: return this
        return ListIllust().also { out ->
            out.setNext_url(nextUrl)
            out.illusts = source.map { it.copy(is_bookmarked = known(it.id)) }
        }
    }

    fun ListNovel.withViewerBookmarkState(
        known: (Long) -> Boolean? = ::knownNovelBookmark,
    ): ListNovel {
        val source = novels ?: return this
        return ListNovel().also { out ->
            out.setNext_url(nextUrl)
            out.ranking_novels = ranking_novels
            out.novels = source.map { it.copy(is_bookmarked = known(it.id)) }
        }
    }

    private fun knownIllustBookmark(id: Long): Boolean? =
        ObjectPool.get<Illust>(id).value?.is_bookmarked

    private fun knownNovelBookmark(id: Long): Boolean? =
        ObjectPool.get<Novel>(id).value?.is_bookmarked
}
