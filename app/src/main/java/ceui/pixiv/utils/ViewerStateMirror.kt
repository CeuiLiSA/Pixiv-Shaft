package ceui.pixiv.utils

import ceui.lisa.activities.Shaft
import ceui.pixiv.api.model.Illust
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.services.appServices
import timber.log.Timber

private const val VIEWER_STATE_TAG = "ViewerStateMirror"

/** 生产用的镜像查询口：内容类型 + 一批 id → 命中的 id。 */
private suspend fun defaultMirrorLookup(type: MirrorContentType, ids: List<Long>): Set<Long> =
    Shaft.getContext().appServices().bookmarkMirror.bookmarkedAmong(type, ids)

/**
 * 池里这条作品的 viewer 态不可信时（自建源上报快照 / 借号 / 历史快照喂进来的），
 * 用本地收藏与关注镜像校正一次 —— **只升不降**：镜像里有就是 true，没有就保持未知（null），
 * 绝不写 false（镜像没回填完 ≠ 没收藏，见 [ceui.pixiv.db.mirror.BookmarkMirrorService.bookmarkedAmong]）。
 *
 * 为什么必须校正而不是回源：`isFullDetail()` 只看图片字段，自建源 bean 天然满足，
 * 详情页会直接返回池里那份、一次 `v1/illust/detail` 都不发 —— 脏的收藏 / 关注态在进程内
 * 没有第二次机会被改回来，只能重启。镜像把这个机会从「网络」搬到「本地」。
 *
 * 写回池之后，详情页（V3 与 legacy）现有的 ObjectPool 观察者会自动重绘爱心与作者栏关注态，
 * 调用方不需要自己再渲染一遍。
 *
 * **逐字段**判定，不是整条短路：可信来源给出过的字段不动，「不知道」（null）的字段才问镜像。
 * 为什么必须逐字段：用户在列表卡上点过红心之后，这条会被标成可信（收藏态是他自己点的），
 * 但关注态仍是未知 —— 那一次收藏动作不该把关注态一起冻住。
 *
 * 两个字段都已知（可信且非空）时原样返回，零查询零请求。主线程调用（写池走 setValue）。
 *
 * @param mirrorLookup 镜像查询口，默认走 [BookmarkMirrorService.bookmarkedAmong]；单测用它绕开
 *   Room 与全局服务，验证「镜像就绪时上屏是什么」。
 */
suspend fun reconcileViewerStateFromMirror(
    illust: Illust,
    writeBack: Boolean = true,
    mirrorLookup: suspend (MirrorContentType, List<Long>) -> Set<Long> = ::defaultMirrorLookup,
): Illust {
    val trusted = ObjectPool.isIllustViewerStateTrusted(illust.id)
    val authorId = illust.user?.id ?: 0L
    val needBookmark = !(trusted && illust.is_bookmarked != null)
    val needFollow = authorId > 0L && !(trusted && illust.user?.is_followed != null)
    if (!needBookmark && !needFollow) return illust

    val bookmarkedState = if (needBookmark) {
        true.takeIf { illust.id in mirrorLookup(MirrorContentType.ILLUST, listOf(illust.id)) }
    } else {
        illust.is_bookmarked
    }
    val followedState = if (needFollow) {
        true.takeIf { authorId in mirrorLookup(MirrorContentType.USER, listOf(authorId)) }
    } else {
        illust.user?.is_followed
    }
    // writeBack = false 给「只是读一份快照」的调用方（快照生成不该改动 ObjectPool）。
    if (writeBack) ObjectPool.applyMirroredViewerState(illust.id, bookmarkedState, followedState)
    Timber.tag(VIEWER_STATE_TAG).d(
        "viewer 态按镜像校正 illustId=%d bookmarked=%s followed=%s",
        illust.id, bookmarkedState, followedState,
    )
    return illust.copy(
        is_bookmarked = bookmarkedState,
        user = illust.user?.copy(is_followed = followedState),
    )
}