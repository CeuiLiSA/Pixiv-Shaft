package ceui.pixiv.cache

import ceui.loxia.User
import ceui.pixiv.api.model.Illust

/**
 * 把「别人的 viewer 态」从 bean 上摘掉，见 [ObjectPool] 的「viewer 态可信度」。
 *
 * 清成 **null（未知）而不是 false**：`false` 在 [ObjectPool.mergeKeepingExisting] 里是正经值，
 * 会盖掉池里权威的 `true`；`null` 是空值，只在「池里本来就没有」时才成为初值 —— 也就是
 * 「不知道」，UI 渲染成未收藏 / 未关注，等镜像或 detail 来把它升上去。
 */
fun Illust.withoutViewerState(): Illust {
    if (is_bookmarked == null && user?.is_followed == null) return this
    return copy(is_bookmarked = null, user = user?.withoutViewerState())
}

fun User.withoutViewerState(): User {
    if (is_followed == null) return this
    return copy(is_followed = null)
}
