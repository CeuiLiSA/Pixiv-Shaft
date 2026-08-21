package ceui.pixiv.snapshot

import android.net.Uri
import ceui.lisa.activities.Shaft
import ceui.lisa.models.ImageUrlsBean
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.MetaSinglePageBean
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
 * 把一份快照里的 IllustsBean 克隆并本地化：所有 assets.json 能映射到的图片 URL 都改成
 * `shaftsnap://`。渲染层因此只走本地文件，绝不联网。
 */
fun SnapshotViewerData.localizeIllust(): IllustsBean {
    val localized = Shaft.sGson.fromJson(Shaft.sGson.toJson(illust), IllustsBean::class.java)
    localized.image_urls?.localize(snapshotDir, manifest.snapshotId, assets)
    localized.meta_single_page?.localize(snapshotDir, manifest.snapshotId, assets)
    localized.meta_pages?.forEach { page -> page.image_urls?.localize(snapshotDir, manifest.snapshotId, assets) }
    localized.user?.profile_image_urls?.let { urls ->
        val local = listOfNotNull(
            urls.px_170x170, urls.medium, urls.large, urls.original, urls.square_medium,
            urls.px_50x50, urls.px_16x16,
        ).mapNotNull { it.localizedPath(snapshotDir, manifest.snapshotId, assets) }.firstOrNull()
        if (local != null) {
            urls.px_16x16 = local
            urls.px_50x50 = local
            urls.px_170x170 = local
            urls.square_medium = local
            urls.medium = local
            urls.large = local
            urls.original = local
        }
    }
    return localized
}

/**
 * 生成专门给现有 ImageDetailActivity / FragmentImageDetail 用的 bean：
 * 每一页的 large/original 都指向快照里实际存在的那一个本地文件，避免查看器按 ORIGINAL 回源。
 */
fun SnapshotViewerData.localizeForViewer(): IllustsBean {
    val bean = Shaft.sGson.fromJson(Shaft.sGson.toJson(illust), IllustsBean::class.java)
    fun local(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val rel = assets[url] ?: return url
        return snapshotLocalUrl(manifest.snapshotId, rel)
    }
    bean.image_urls?.let { urls ->
        val chosen = if (manifest.includeOriginal) urls.original else urls.large ?: urls.medium
        local(chosen)?.let { localUrl ->
            urls.original = localUrl
            urls.large = localUrl
            urls.medium = localUrl
        }
    }
    if (bean.page_count <= 1) {
        val candidates = if (manifest.includeOriginal) {
            listOf(
                bean.meta_single_page?.original_image_url,
                bean.image_urls?.original,
                bean.image_urls?.large,
                bean.image_urls?.medium,
            )
        } else {
            listOf(
                bean.image_urls?.large,
                bean.image_urls?.medium,
                bean.meta_single_page?.original_image_url,
                bean.image_urls?.original,
            )
        }
        val localUrl = candidates.mapNotNull { local(it) }.firstOrNull()
        if (localUrl != null) {
            bean.meta_single_page?.original_image_url = localUrl
            bean.meta_single_page?.original = localUrl
            bean.image_urls?.original = localUrl
            bean.image_urls?.large = localUrl
            bean.image_urls?.medium = localUrl
        }
    } else {
        bean.meta_pages?.forEach { page ->
            val chosen = if (manifest.includeOriginal) page.image_urls?.original
                else page.image_urls?.large ?: page.image_urls?.medium
            local(chosen)?.let { localUrl ->
                page.image_urls?.original = localUrl
                page.image_urls?.large = localUrl
                page.image_urls?.medium = localUrl
            }
        }
    }
    bean.user?.profile_image_urls?.let { urls ->
        local(urls.px_16x16)?.let { urls.px_16x16 = it }
        local(urls.px_50x50)?.let { urls.px_50x50 = it }
        local(urls.px_170x170)?.let { urls.px_170x170 = it }
        local(urls.square_medium)?.let { urls.square_medium = it }
        local(urls.medium)?.let { urls.medium = it }
        local(urls.large)?.let { urls.large = it }
        local(urls.original)?.let { urls.original = it }
        val localAvatar = listOfNotNull(
            urls.px_170x170, urls.medium, urls.large, urls.original, urls.square_medium,
            urls.px_50x50, urls.px_16x16,
        ).firstOrNull { it.startsWith(SNAPSHOT_LOCAL_SCHEME + "://") }
        if (localAvatar != null) {
            urls.px_16x16 = localAvatar
            urls.px_50x50 = localAvatar
            urls.px_170x170 = localAvatar
            urls.square_medium = localAvatar
            urls.medium = localAvatar
            urls.large = localAvatar
            urls.original = localAvatar
        }
    }
    return bean
}

/** 把快照评论本地化：评论者头像和表情章 URL 改成 shaftsnap://。 */
fun SnapshotViewerData.localizeComment(comment: Comment): Comment {
    val localizedAvatarUrls = comment.user.profile_image_urls?.let { urls ->
        urls.copy(
            url = urls.url?.localizedPath(snapshotDir, manifest.snapshotId, assets),
            large = urls.large?.localizedPath(snapshotDir, manifest.snapshotId, assets),
            medium = urls.medium?.localizedPath(snapshotDir, manifest.snapshotId, assets),
            original = urls.original?.localizedPath(snapshotDir, manifest.snapshotId, assets),
            small = urls.small?.localizedPath(snapshotDir, manifest.snapshotId, assets),
            square_medium = urls.square_medium?.localizedPath(snapshotDir, manifest.snapshotId, assets),
            px_16x16 = urls.px_16x16?.localizedPath(snapshotDir, manifest.snapshotId, assets),
            px_170x170 = urls.px_170x170?.localizedPath(snapshotDir, manifest.snapshotId, assets),
            px_50x50 = urls.px_50x50?.localizedPath(snapshotDir, manifest.snapshotId, assets),
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
        stamp.copy(stamp_url = stamp.stamp_url?.localizedPath(snapshotDir, manifest.snapshotId, assets))
    }
    return comment.copy(user = localizedUser, stamp = localizedStamp)
}

private fun ImageUrlsBean.localize(
    snapshotDir: java.io.File,
    snapshotId: String,
    assets: Map<String, String>,
) {
    square_medium = square_medium?.localizedPath(snapshotDir, snapshotId, assets)
    medium = medium?.localizedPath(snapshotDir, snapshotId, assets)
    large = large?.localizedPath(snapshotDir, snapshotId, assets)
    original = original?.localizedPath(snapshotDir, snapshotId, assets)
    if (this is ProfileImageUrlsBean) {
        px_16x16 = px_16x16?.localizedPath(snapshotDir, snapshotId, assets)
        px_50x50 = px_50x50?.localizedPath(snapshotDir, snapshotId, assets)
        px_170x170 = px_170x170?.localizedPath(snapshotDir, snapshotId, assets)
    }
}

private fun MetaSinglePageBean.localize(
    snapshotDir: java.io.File,
    snapshotId: String,
    assets: Map<String, String>,
) {
    original_image_url = original_image_url?.localizedPath(snapshotDir, snapshotId, assets)
    original = original?.localizedPath(snapshotDir, snapshotId, assets)
}

private fun String.localizedPath(
    snapshotDir: java.io.File,
    snapshotId: String,
    assets: Map<String, String>,
): String? {
    val rel = assets[this] ?: return this
    return if (File(snapshotDir, rel).isFile) snapshotLocalUrl(snapshotId, rel) else this
}