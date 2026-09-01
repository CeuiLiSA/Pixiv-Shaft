package ceui.pixiv.db.mirror

import android.content.Context
import ceui.loxia.appServices
import ceui.pixiv.session.SessionManager

/**
 * 收藏页 → 镜像系统的唯一接线点。
 *
 * 「用户打开了自己的某个收藏页」是**开启镜像的唯一触发条件**，理由见
 * [BookmarkMirrorService.ensureShelf]：它同时是隐私边界（没点开过悄悄收藏就不会去拉它）
 * 和灵活性来源（插画/小说、公开/悄悄，全靠调用方传进来的 [contentType] 与 restrict 区分）。
 *
 * 只对**自己的**收藏生效：别人的收藏页只是随便看看，没有理由为它在本地留一份全量副本。
 */
fun Context.trackBookmarkShelfVisit(
    userId: Long,
    starType: String,
    contentType: MirrorContentType,
) {
    if (userId <= 0L || SessionManager.loggedInUid != userId) return
    appServices().bookmarkMirror.ensureShelf(
        BookmarkShelf(userId, contentType, MirrorRestrict.ofApiValue(starType)),
        reason = "打开收藏页",
    )
}
