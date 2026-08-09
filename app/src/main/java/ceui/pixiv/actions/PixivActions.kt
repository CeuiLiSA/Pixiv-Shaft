package ceui.pixiv.actions

import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Params
import ceui.lisa.viewmodel.AppLevelViewModel
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.pixiv.actionqueue.ActionRequest
import ceui.pixiv.widgets.RateAppManager

/**
 * 收藏 / 关注的统一入口。UI 调这里，**立即返回**。
 *
 * 本地状态立刻改掉（用户看到爱心马上变红），请求进队列。真正的网络请求由
 * [PixivActionQueue] 串行、按 2 秒间隔发出，撞 429 会整队冷却并自动重试；进程被杀后
 * 下次启动继续发。成功之后由队列补埋点，终态失败时由队列回滚这里做的乐观更新并提示用户。
 *
 * 埋点刻意**不**在这里发：这一刻请求还没出去，之后可能因为限流打满重试或作品已删除
 * 而终态失败并被回滚，而埋点发出去就撤不回来。
 *
 * 之所以要有这么一层门面：仓库里原本有三套并行的收藏写法（legacy 的 `PixivOperate`、
 * V3 的 `DetailFeedSupport`、小说卡片自己的 scope），乐观更新、埋点、私密收藏设置各写各的，
 * 行为不一致。新代码一律走这里。
 */
object PixivActions {

    /**
     * 收藏的默认可见性。
     *
     * 必须读设置：仓库里每一个收藏入口（feed 卡片、小说卡片、桌面小组件、legacy 的
     * PixivOperate）都尊重「私密收藏」开关，这个门面是为了消灭这类不一致才存在的，
     * 自己写死 public 等于把用户明确要求保密的收藏公开挂到主页上。
     */
    @JvmStatic
    fun defaultBookmarkRestrict(): String =
        if (Shaft.sSettings.isPrivateStar) Params.TYPE_PRIVATE else Params.TYPE_PUBLIC

    // ── 插画 / 漫画 ──────────────────────────────────────────────────────────

    @JvmStatic
    @JvmOverloads
    fun toggleIllustBookmark(illust: Illust, restrict: String = defaultBookmarkRestrict()) {
        setIllustBookmark(illust, illust.is_bookmarked != true, restrict)
    }

    @JvmStatic
    @JvmOverloads
    fun setIllustBookmark(
        illust: Illust,
        bookmark: Boolean,
        restrict: String = defaultBookmarkRestrict(),
    ) {
        if (illust.is_bookmarked == bookmark) return

        val delta = if (bookmark) 1 else -1
        ObjectPool.update(
            illust.copy(
                is_bookmarked = bookmark,
                total_bookmarks = illust.total_bookmarks?.plus(delta),
            )
        )
        if (bookmark) RateAppManager.onUserEngaged()

        enqueueBookmark(PixivActionTypes.ILLUST_BOOKMARK, illust.id, bookmark, restrict)
    }

    // ── 小说 ────────────────────────────────────────────────────────────────

    @JvmStatic
    @JvmOverloads
    fun toggleNovelBookmark(novel: Novel, restrict: String = defaultBookmarkRestrict()) {
        setNovelBookmark(novel, novel.is_bookmarked != true, restrict)
    }

    @JvmStatic
    @JvmOverloads
    fun setNovelBookmark(
        novel: Novel,
        bookmark: Boolean,
        restrict: String = defaultBookmarkRestrict(),
    ) {
        if (novel.is_bookmarked == bookmark) return

        val delta = if (bookmark) 1 else -1
        ObjectPool.update(
            novel.copy(
                is_bookmarked = bookmark,
                total_bookmarks = novel.total_bookmarks?.plus(delta),
            )
        )
        if (bookmark) RateAppManager.onUserEngaged()

        enqueueBookmark(PixivActionTypes.NOVEL_BOOKMARK, novel.id, bookmark, restrict)
    }

    // ── 关注 ────────────────────────────────────────────────────────────────

    @JvmStatic
    @JvmOverloads
    fun setUserFollow(
        userId: Long,
        follow: Boolean,
        restrict: String = Params.TYPE_PUBLIC,
    ) {
        if (follow) {
            ObjectPool.followUser(userId)
            Shaft.appViewModel.updateFollowUserStatus(
                userId.toInt(),
                if (restrict == Params.TYPE_PUBLIC) {
                    AppLevelViewModel.FollowUserStatus.FOLLOWED_PUBLIC
                } else {
                    AppLevelViewModel.FollowUserStatus.FOLLOWED_PRIVATE
                },
            )
            RateAppManager.onUserEngaged()
        } else {
            ObjectPool.unFollowUser(userId)
            Shaft.appViewModel.updateFollowUserStatus(
                userId.toInt(),
                AppLevelViewModel.FollowUserStatus.NOT_FOLLOW,
            )
        }

        PixivActionQueue.enqueue(
            ActionRequest(
                type = PixivActionTypes.USER_FOLLOW,
                // 合并键只带 userId：同一个人的关注/取关反复横跳只留最后一次。
                dedupeKey = "${PixivActionTypes.USER_FOLLOW}:$userId",
                payload = Shaft.sGson.toJson(FollowPayload(userId, follow, restrict)),
            )
        )
    }

    private fun enqueueBookmark(type: String, id: Long, bookmark: Boolean, restrict: String) {
        PixivActionQueue.enqueue(
            ActionRequest(
                type = type,
                dedupeKey = "$type:$id",
                payload = Shaft.sGson.toJson(BookmarkPayload(id, bookmark, restrict)),
            )
        )
    }
}
