package ceui.pixiv.snapshot

import android.net.Uri
import ceui.lisa.activities.Shaft
import ceui.lisa.models.ImageUrlsBean
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.ProfileImageUrlsBean
import ceui.loxia.Comment
import java.io.File

const val SNAPSHOT_LOCAL_SCHEME = "shaftsnap"

/** 把 assets.json 里的相对路径编码成 Glide 可识别的本地快照 URL。 */
fun snapshotLocalUrl(snapshotId: String, rel: String): String =
    "$SNAPSHOT_LOCAL_SCHEME://$snapshotId/${rel.trimStart('/')}"

/** 解析 `shaftsnap://<snapshotId>/<rel>`，非快照 URL 返回 null。 */
fun parseSnapshotLocalUrl(url: String): Pair<String, String>? {
    if (!url.startsWith("$SNAPSHOT_LOCAL_SCHEME://")) return null
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    val snapshotId = uri.host ?: return null
    val rel = uri.path?.trimStart('/') ?: return null
    if (snapshotId.isEmpty() || rel.isEmpty()) return null
    return snapshotId to rel
}

/**
 * 把快照里的 IllustsBean 克隆并本地化：作品每一页、封面、作者头像的**所有尺寸变体**
 * 统一指向快照里那一份存档文件（`shaftsnap://`）。渲染层因此只走本地文件，绝不联网。
 *
 * 「所有变体一起改」是必须的，不是保险：快照一页只存一份文件，而消费方按哪一档来问由它
 * 自己决定 —— 大图页 [ceui.lisa.fragments.FragmentImageDetail] 恒按 ORIGINAL 取，
 * 详情页 IllustAdapter 按全局「展示原图」设置在 ORIGINAL / LARGE 之间切。逐个变体去
 * assets 里查、查不到就留着原 URL 的做法，会让没归档的那几档继续指着 i.pximg.net：
 * 在线时偷偷回源（拿到的还不是存档那张），离线时直接空白。
 *
 * 作者头像原本就是这么处理的（归档哪一档就把全部变体都改成它），这里把同一条规矩补到页图上。
 */
fun SnapshotViewerData.localizeIllust(): IllustsBean {
    val localized = Shaft.sGson.fromJson(Shaft.sGson.toJson(illust), IllustsBean::class.java)
    val pageCount = localized.page_count.coerceAtLeast(1)
    for (i in 0 until pageCount) {
        val localUrl = pageLocalUrl(i) ?: continue
        if (pageCount <= 1) {
            localized.image_urls?.applyLocalUrl(localUrl)
            localized.meta_single_page?.let { single ->
                single.original_image_url = localUrl
                single.original = localUrl
            }
        } else {
            localized.meta_pages?.getOrNull(i)?.image_urls?.applyLocalUrl(localUrl)
        }
    }
    // 多图作品的 illust 级 image_urls 是封面，不属于任何一页：一并指到 p0 的存档，
    // 免得「按封面取图」的地方（分享、缩略图）回源。
    if (pageCount > 1) {
        pageLocalUrl(0)?.let { localized.image_urls?.applyLocalUrl(it) }
    }
    localized.user?.profile_image_urls?.localizeAvatar(snapshotDir, manifest.snapshotId, assets)
    return localized
}

/** 一页只有一份存档文件，所以该页所有尺寸变体都指向它。 */
private fun ImageUrlsBean.applyLocalUrl(localUrl: String) {
    square_medium = localUrl
    medium = localUrl
    large = localUrl
    original = localUrl
    if (this is ProfileImageUrlsBean) {
        px_16x16 = localUrl
        px_50x50 = localUrl
        px_170x170 = localUrl
    }
}

/** 头像：归档了哪一档就把全部变体都换成它；一档都没归档时原样不动。 */
private fun ProfileImageUrlsBean.localizeAvatar(
    snapshotDir: File,
    snapshotId: String,
    assets: Map<String, String>,
) {
    val local = listOfNotNull(
        px_170x170, medium, large, original, square_medium, px_50x50, px_16x16,
    ).firstNotNullOfOrNull { it.localizedOrNull(snapshotDir, snapshotId, assets) } ?: return
    applyLocalUrl(local)
}

/** 把快照评论本地化：评论者头像和表情章 URL 改成 shaftsnap://。 */
fun SnapshotViewerData.localizeComment(comment: Comment): Comment {
    val localizedAvatarUrls = comment.user.profile_image_urls?.let { urls ->
        urls.copy(
            url = urls.url?.localizedOrNull(snapshotDir, manifest.snapshotId, assets),
            large = urls.large?.localizedOrNull(snapshotDir, manifest.snapshotId, assets),
            medium = urls.medium?.localizedOrNull(snapshotDir, manifest.snapshotId, assets),
            original = urls.original?.localizedOrNull(snapshotDir, manifest.snapshotId, assets),
            small = urls.small?.localizedOrNull(snapshotDir, manifest.snapshotId, assets),
            square_medium = urls.square_medium?.localizedOrNull(snapshotDir, manifest.snapshotId, assets),
            px_16x16 = urls.px_16x16?.localizedOrNull(snapshotDir, manifest.snapshotId, assets),
            px_170x170 = urls.px_170x170?.localizedOrNull(snapshotDir, manifest.snapshotId, assets),
            px_50x50 = urls.px_50x50?.localizedOrNull(snapshotDir, manifest.snapshotId, assets),
        )
    }?.let { urls ->
        val localAvatar = listOfNotNull(
            urls.px_170x170, urls.medium, urls.large, urls.original, urls.square_medium,
            urls.px_50x50, urls.px_16x16,
        ).firstOrNull { it.startsWith(SNAPSHOT_LOCAL_SCHEME + "://") }
        if (localAvatar == null) urls else urls.copy(
            url = localAvatar,
            large = localAvatar,
            medium = localAvatar,
            original = localAvatar,
            small = localAvatar,
            square_medium = localAvatar,
            px_16x16 = localAvatar,
            px_170x170 = localAvatar,
            px_50x50 = localAvatar,
        )
    }
    val localizedUser = comment.user.copy(profile_image_urls = localizedAvatarUrls)
    val localizedStamp = comment.stamp?.let { stamp ->
        stamp.copy(stamp_url = stamp.stamp_url?.localizedOrNull(snapshotDir, manifest.snapshotId, assets))
    }
    return comment.copy(user = localizedUser, stamp = localizedStamp)
}

/**
 * URL → `shaftsnap://`；这个 URL 没归档过就返回 null。
 *
 * ⚠️ 不要改成「没归档就原样返回」：调用点全是
 * `listOfNotNull(...).firstNotNullOfOrNull { it.localizedOrNull(...) }` 这种「挑一个本地的出来」，
 * 原样吐回远程 URL 会让它们挑中 i.pximg.net —— 离线空白、在线偷偷回源。
 */
private fun String.localizedOrNull(
    snapshotDir: File,
    snapshotId: String,
    assets: Map<String, String>,
): String? {
    val rel = assets[this] ?: return null
    return if (File(snapshotDir, rel).isFile) snapshotLocalUrl(snapshotId, rel) else null
}
