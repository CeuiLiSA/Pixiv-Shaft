package ceui.pixiv.actions

import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Params
import ceui.lisa.viewmodel.AppLevelViewModel
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.pixiv.actionqueue.ActionRequest
import ceui.pixiv.events.EventReporter
import ceui.pixiv.widgets.RateAppManager

/**
 * 收藏 / 关注的统一入口。UI 调这里，**立即返回**。
 *
 * 三件事按顺序发生：本地状态立刻改掉（用户看到爱心马上变红）→ 埋点 → 请求进队列。
 * 真正的网络请求由 [PixivActionQueue] 串行、按 2 秒间隔发出，撞 429 会整队冷却并自动重试；
 * 进程被杀后下次启动继续发。终态失败时队列会回滚这里做的乐观更新并提示用户。
 *
 * 之所以要有这么一层门面：仓库里原本有三套并行的收藏写法（legacy 的 `PixivOperate`、
 * V3 的 `DetailFeedSupport`、小说卡片自己的 scope），乐观更新、埋点、私密收藏设置各写各的，
 * 行为不一致。新代码一律走这里。
 */
object PixivActions {

    // ── 插画 / 漫画 ──────────────────────────────────────────────────────────

    @JvmStatic
    @JvmOverloads
    fun toggleIllustBookmark(illust: Illust, restrict: String = Params.TYPE_PUBLIC) {
        setIllustBookmark(illust, illust.is_bookmarked != true, restrict)
    }

    @JvmStatic
    @JvmOverloads
    fun setIllustBookmark(
        illust: Illust,
        bookmark: Boolean,
        restrict: String = Params.TYPE_PUBLIC,
    ) {
        if (illust.is_bookmarked == bookmark) return

        val delta = if (bookmark) 1 else -1
        ObjectPool.update(
            illust.copy(
                is_bookmarked = bookmark,
                total_bookmarks = illust.total_bookmarks?.plus(delta),
            )
        )

        // pixiv 把漫画存成 type == "manga" 的 illust，按语义目标分开埋点。
        val target = if (illust.type == "manga") EventReporter.Target.MANGA else EventReporter.Target.ILLUST
        EventReporter.report(
            if (bookmark) EventReporter.Type.BOOKMARK else EventReporter.Type.UNBOOKMARK,
            target,
            illust.id,
            illust,
        )
        if (bookmark) RateAppManager.onUserEngaged()

        enqueueBookmark(PixivActionTypes.ILLUST_BOOKMARK, illust.id, bookmark, restrict)
    }

    // ── 小说 ────────────────────────────────────────────────────────────────

    @JvmStatic
    @JvmOverloads
    fun toggleNovelBookmark(novel: Novel, restrict: String = Params.TYPE_PUBLIC) {
        setNovelBookmark(novel, novel.is_bookmarked != true, restrict)
    }

    @JvmStatic
    @JvmOverloads
    fun setNovelBookmark(
        novel: Novel,
        bookmark: Boolean,
        restrict: String = Params.TYPE_PUBLIC,
    ) {
        if (novel.is_bookmarked == bookmark) return

        val delta = if (bookmark) 1 else -1
        ObjectPool.update(
            novel.copy(
                is_bookmarked = bookmark,
                total_bookmarks = novel.total_bookmarks?.plus(delta),
            )
        )
        EventReporter.report(
            if (bookmark) EventReporter.Type.BOOKMARK else EventReporter.Type.UNBOOKMARK,
            EventReporter.Target.NOVEL,
            novel.id,
            novel,
        )
        if (bookmark) RateAppManager.onUserEngaged()

        enqueueBookmark(PixivActionTypes.NOVEL_BOOKMARK, novel.id, bookmark, restrict)
    }

    // ── 关注 ────────────────────────────────────────────────────────────────

    /**
     * @param userForReport 仅用于埋点上报时带上完整 user 信息，可空。
     */
    @JvmStatic
    @JvmOverloads
    fun setUserFollow(
        userId: Long,
        follow: Boolean,
        restrict: String = Params.TYPE_PUBLIC,
        userForReport: User? = null,
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

        EventReporter.report(
            if (follow) EventReporter.Type.FOLLOW else EventReporter.Type.UNFOLLOW,
            EventReporter.Target.USER,
            userId,
            userForReport,
        )

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
