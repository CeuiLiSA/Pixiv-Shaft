@file:JvmName("BulkActions")

package ceui.pixiv.ui.bulk

import androidx.fragment.app.FragmentActivity
import ceui.lisa.R
import ceui.pixiv.db.queue.WorkType

/**
 * 给 Java 调用方用的薄壳：从 java 直接构造 [PaginatedObjectSource] / 操作 `Flow<FetchEvent>`
 * 太别扭，统一收口在这里。
 *
 * 命名约定：`startXxxBulkDownload(...)` —— 一调即起 dialog。
 */

/**
 * 我的（或指定用户的）收藏插画批量下载。restrict 取自 [ceui.lisa.utils.Params.TYPE_PUBLIC]
 * / [ceui.lisa.utils.Params.TYPE_PRIVATE]。
 */
fun startBookmarkIllustBulkDownload(
    activity: FragmentActivity,
    userId: Long,
    restrict: String,
    taskName: String,
) {
    val source = MyBookmarksSource(userId = userId, restrict = restrict)
    FetchProgressDialog.show(
        activity.supportFragmentManager,
        bulkEnqueueIllusts(activity, source, taskName),
    )
}

/**
 * 某作者全部作品（插画 / 漫画）批量下载。`type` 取 [WorkType.ILLUST] / [WorkType.MANGA]。
 * task 名按 `bulk_task_name` 模板拼，未知作者名传空串会得到「下载  的全部插画」——
 * 调用方有 fallback（例如 "user"）就传过来。
 */
fun startAuthorWorksBulkDownload(
    activity: FragmentActivity,
    userId: Long,
    type: String,
    authorName: String,
) {
    val typeLabel = activity.getString(
        if (type == WorkType.MANGA) R.string.bulk_type_manga else R.string.bulk_type_illust
    )
    val taskName = activity.getString(R.string.bulk_task_name, authorName, typeLabel)
    val source = AuthorWorksSource(userId = userId, type = type)
    FetchProgressDialog.show(
        activity.supportFragmentManager,
        bulkEnqueueIllusts(activity, source, taskName),
    )
}
