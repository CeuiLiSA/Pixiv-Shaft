package ceui.pixiv.snapshot

import ceui.lisa.models.IllustsBean
import ceui.lisa.models.ImageUrlsBean
import ceui.lisa.models.UserBean
import ceui.loxia.Comment
import ceui.loxia.User
import java.util.Locale

/** 快照实际渲染某页时使用的 URL：开原图用 original，否则回落到详情页常用 large/medium。 */
fun IllustsBean.snapshotPageUrl(index: Int, original: Boolean): String? {
    val page = if (page_count <= 1) {
        meta_single_page
    } else {
        meta_pages?.getOrNull(index)?.image_urls
    } ?: image_urls
    return if (original) {
        page?.original?.takeIf { it.isNotBlank() } ?: page?.large
    } else {
        page?.large?.takeIf { it.isNotBlank() } ?: page?.medium ?: page?.original
    }
}

/** 作者头像：优先 170px，其次 medium / max。 */
fun IllustsBean.snapshotAuthorAvatarUrl(): String? {
    val urls = user?.profile_image_urls ?: return null
    return urls.px_170x170?.takeIf { it.isNotBlank() }
        ?: urls.medium?.takeIf { it.isNotBlank() }
        ?: urls.maxImage?.takeIf { it.isNotBlank() }
}

fun User.snapshotAvatarUrl(): String? {
    val urls = profile_image_urls ?: return null
    return urls.px_170x170?.takeIf { it.isNotBlank() }
        ?: urls.medium?.takeIf { it.isNotBlank() }
        ?: urls.small?.takeIf { it.isNotBlank() }
        ?: urls.original?.takeIf { it.isNotBlank() }
}

fun Comment.snapshotAvatarUrl(): String? = user.snapshotAvatarUrl()

fun Comment.snapshotStampUrl(): String? = stamp?.stamp_url?.takeIf { it.isNotBlank() }

/** 从 URL 提取小写扩展名；取不到就退回 .img，保证 ZIP 内路径可写。 */
fun String.snapshotExtension(): String {
    val raw = substringAfterLast('?', substringAfterLast('/')).substringAfterLast('.')
    return if (raw.length in 2..5 && raw.all { it.isLetterOrDigit() }) {
        ".${raw.lowercase(Locale.US)}"
    } else {
        ".img"
    }
}