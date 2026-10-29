package ceui.lisa.repo

import ceui.lisa.model.ListIllust
import ceui.lisa.model.ListNovel
import ceui.lisa.models.ObjectSpec
import ceui.loxia.Novel
import ceui.pixiv.api.model.Illust
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.cache.withoutViewerState
import ceui.pixiv.db.mirror.BookmarkMirrorService
import ceui.pixiv.db.mirror.MirrorContentType

/**
 * 搜索页出仓前的收藏态修正（#1063）。
 *
 * 会员专属排序这条链路（含一级缓存命中）返回的 `is_bookmarked` 不代表当前用户；原样交给 UI
 * 就会出现「明明收藏了，搜索结果里却是灰心」（进详情页拉 detail 又是红心）。
 *
 * Pixiv 没有按 id 批量查收藏态的接口，逐条打 detail 又太贵，所以这里只做确定正确的事：
 *  1. 进程内 [ObjectPool] 已经确认过、且标记为「当前用户视角」的（详情页、自己的收藏页 /
 *     feed 合过池的）沿用；被标成不可信的不算（见 [ObjectPool] 的「viewer 态可信度」）；
 *  2. 池里没有、但本地收藏镜像里有的，就是收藏了（冷启动后也成立）；
 *  3. 其余一律置 null（「不知道」）。镜像可能没回填完、或漏了别的设备刚做的收藏，
 *     所以「镜像里没有」不能当成未收藏。UI 把 null 渲染成未收藏；合池时 null 属于空值，
 *     不会盖掉池里已有的真值（见 ObjectPool.mergeKeepingExisting），也就不会再把错误值灌进池里。
 *
 * 回退到普通直发的页（popular-preview / 普通搜索）本来就是当前用户视角，**不要**经这里。
 */
internal object ViewerBookmarkState {

    suspend fun ListIllust.withViewerBookmarkState(mirror: BookmarkMirrorService): ListIllust {
        val source = illusts ?: return this
        val mirrored = mirror.bookmarkedAmong(MirrorContentType.ILLUST, source.map { it.id })
        // 收藏态在这里按「池里可信值 → 镜像」只升不降地重算，关注态一律清成未知 —— 借号账号
        // 关注没关注这个作者，跟当前用户没有关系。出仓的 bean 已经不带别人的状态，合池时 null
        // 不会盖掉池里的值，详情页对 null 字段也会自己问镜像，所以**不**给池条目打不可信标记：
        // 池里那条可能是用户刚在本会话收藏 / 详情页确认过的权威 true，标成不可信会让详情页的
        // 镜像校正（镜像没就绪时）把它抹回 null。
        return ListIllust().also { out ->
            out.setNext_url(nextUrl)
            out.illusts = source.map { bean ->
                bean.copy(
                    is_bookmarked = knownIllustBookmark(bean.id) ?: true.takeIf { bean.id in mirrored },
                    user = bean.user?.withoutViewerState(),
                )
            }
        }
    }

    suspend fun ListNovel.withViewerBookmarkState(mirror: BookmarkMirrorService): ListNovel {
        val source = novels ?: return this
        val mirrored = mirror.bookmarkedAmong(MirrorContentType.NOVEL, source.map { it.id })
        return ListNovel().also { out ->
            out.setNext_url(nextUrl)
            out.ranking_novels = ranking_novels
            out.novels = source.map { novel ->
                novel.copy(
                    is_bookmarked = knownNovelBookmark(novel.id) ?: true.takeIf { novel.id in mirrored },
                    user = novel.user?.withoutViewerState(),
                )
            }
        }
    }

    fun ListIllust.withViewerBookmarkState(known: (Long) -> Boolean?): ListIllust {
        val source = illusts ?: return this
        return ListIllust().also { out ->
            out.setNext_url(nextUrl)
            out.illusts = source.map { it.copy(is_bookmarked = known(it.id)) }
        }
    }

    fun ListNovel.withViewerBookmarkState(known: (Long) -> Boolean?): ListNovel {
        val source = novels ?: return this
        return ListNovel().also { out ->
            out.setNext_url(nextUrl)
            out.ranking_novels = ranking_novels
            out.novels = source.map { it.copy(is_bookmarked = known(it.id)) }
        }
    }

    /**
     * 池里那条得先被确认是「当前用户视角」，否则自建源 / 借号 / 历史快照写进去的脏值会被当成
     * 权威值继续传播（见 [ObjectPool] 的「viewer 态可信度」）。
     */
    private fun knownIllustBookmark(id: Long): Boolean? =
        if (!ObjectPool.isIllustViewerStateTrusted(id)) null
        else ObjectPool.get<Illust>(id).value?.is_bookmarked

    private fun knownNovelBookmark(id: Long): Boolean? =
        if (!ObjectPool.isViewerStateTrusted(id, ObjectSpec.KNovel)) null
        else ObjectPool.get<Novel>(id).value?.is_bookmarked
}
