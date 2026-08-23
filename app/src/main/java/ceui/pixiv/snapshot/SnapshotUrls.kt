package ceui.pixiv.snapshot

import ceui.lisa.download.IllustDownload
import ceui.lisa.utils.Params
import ceui.loxia.Comment
import ceui.loxia.Illust
import ceui.loxia.User
import java.util.Locale

/**
 * 快照的 URL 口径全部走仓库既有的 [IllustDownload.getUrl]，不再自己拼一套。
 *
 * 这一点是硬约束而不是风格问题：渲染侧问的就是 IllustDownload（大图页
 * [ceui.lisa.fragments.FragmentImageDetail] 恒按 ORIGINAL 取，详情页 IllustAdapter 按全局
 * 「展示原图」设置在 ORIGINAL / LARGE 之间切），归档侧一旦自己另拼一套，
 * 存进去的和取出来的就不是同一个 URL —— 单图作品尤其容易踩：
 * [ceui.loxia.MetaSinglePage] 只有 original_image_url，非原图那几档只存在于 illust 级
 * [ceui.loxia.ImageUrls] 里。
 */
private fun Illust.urlAt(index: Int, resolution: String): String? =
    runCatching { IllustDownload.getUrl(this, index, resolution) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }

/** 生成快照时第 [index] 页要下载哪一档：勾了原图取 ORIGINAL，否则取 LARGE，都拿不到再逐级兜底。 */
fun Illust.snapshotPageUrl(index: Int, original: Boolean): String? {
    val preferred = if (original) Params.IMAGE_RESOLUTION_ORIGINAL else Params.IMAGE_RESOLUTION_LARGE
    return urlAt(index, preferred)
        ?: urlAt(index, Params.IMAGE_RESOLUTION_ORIGINAL)
        ?: urlAt(index, Params.IMAGE_RESOLUTION_LARGE)
        ?: urlAt(index, Params.IMAGE_RESOLUTION_MEDIUM)
}

/**
 * 第 [index] 页在渲染侧可能被请求到的**全部**尺寸变体。
 *
 * 快照一页只存一份文件，但消费方按哪一档来问是它自己决定的。所以 assets 做成多对一：
 * 这一页的每个变体 URL 都指向那份存档，任何调用点都不会漏到网络上去。
 */
fun Illust.snapshotPageVariantUrls(index: Int): List<String> = listOfNotNull(
    urlAt(index, Params.IMAGE_RESOLUTION_ORIGINAL),
    urlAt(index, Params.IMAGE_RESOLUTION_LARGE),
    urlAt(index, Params.IMAGE_RESOLUTION_MEDIUM),
    urlAt(index, Params.IMAGE_RESOLUTION_SQUARE_MEDIUM),
).distinct()

/** 多图作品的封面（illust 级 image_urls）不属于任何一页，单独列出来一并指到 p0。 */
fun Illust.snapshotCoverVariantUrls(): List<String> {
    val urls = image_urls ?: return emptyList()
    return listOfNotNull(urls.original, urls.large, urls.medium, urls.square_medium)
        .filter { it.isNotBlank() }
        .distinct()
}

/** 作者头像：优先 170px，其次 medium / 任意最大可用。 */
fun Illust.snapshotAuthorAvatarUrl(): String? = user?.snapshotAvatarUrl()

fun User.snapshotAvatarUrl(): String? {
    val urls = profile_image_urls ?: return null
    return urls.px_170x170?.takeIf { it.isNotBlank() }
        ?: urls.medium?.takeIf { it.isNotBlank() }
        ?: urls.small?.takeIf { it.isNotBlank() }
        ?: urls.original?.takeIf { it.isNotBlank() }
        ?: urls.findMaxSizeUrl()?.takeIf { it.isNotBlank() }
}

fun Comment.snapshotAvatarUrl(): String? = user.snapshotAvatarUrl()

fun Comment.snapshotStampUrl(): String? = stamp?.stamp_url?.takeIf { it.isNotBlank() }

/**
 * 从 URL 提取小写扩展名；取不到就退回 .img，保证 ZIP 内路径可写。
 *
 * 顺序是「先砍查询串，再取末段，再取后缀」。反过来写（先取 `?` 之后的东西）拿到的是
 * 查询串本身：`a/1_p0.png?v=2` 会得到 `v=2`，判不出扩展名，整张图存成 `p0.img`。
 */
fun String.snapshotExtension(): String {
    val raw = substringBefore('?').substringAfterLast('/').substringAfterLast('.')
    return if (raw.length in 2..5 && raw.all { it.isLetterOrDigit() }) {
        ".${raw.lowercase(Locale.US)}"
    } else {
        ".img"
    }
}
