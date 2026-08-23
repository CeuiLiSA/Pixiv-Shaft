package ceui.pixiv.snapshot

import android.net.Uri
import ceui.lisa.activities.Shaft
import ceui.loxia.Comment
import ceui.loxia.Illust
import ceui.loxia.ImageUrls
import java.io.File

const val SNAPSHOT_LOCAL_SCHEME = "shaftsnap"

/** 常量折叠后就是字面量 "shaftsnap://"：Glide 的 handles() 每次加载都要比一次，别在那里现拼。 */
const val SNAPSHOT_LOCAL_URL_PREFIX = "$SNAPSHOT_LOCAL_SCHEME://"

/** 把 assets.json 里的相对路径编码成 Glide 可识别的本地快照 URL。 */
fun snapshotLocalUrl(snapshotId: String, rel: String): String =
    "$SNAPSHOT_LOCAL_URL_PREFIX$snapshotId/${rel.trimStart('/')}"

/**
 * `shaftsnap://<snapshotId>/<rel>` → 快照库里的真实文件；越界、不存在都返回 null。
 *
 * 走 [safeResolve] 而不是直接拼路径：snapshotId / rel 来自 URL，必须挡住 `../` 逃逸。
 */
internal fun snapshotAssetFile(snapshotId: String, rel: String): File? =
    runCatching { safeResolve(SnapshotRepository.root(Shaft.getContext()), "$snapshotId/$rel") }
        .getOrNull()
        ?.takeIf { it.isFile }

/** 解析 `shaftsnap://<snapshotId>/<rel>`，非快照 URL 返回 null。 */
fun parseSnapshotLocalUrl(url: String): Pair<String, String>? {
    if (!url.startsWith(SNAPSHOT_LOCAL_URL_PREFIX)) return null
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    val snapshotId = uri.host ?: return null
    val rel = uri.path?.trimStart('/') ?: return null
    if (snapshotId.isEmpty() || rel.isEmpty()) return null
    return snapshotId to rel
}

/**
 * 把快照里的 [Illust] 本地化：作品每一页、封面、作者头像的**所有尺寸变体**统一指向快照里
 * 那一份存档文件（`shaftsnap://`）。渲染层因此只走本地文件，绝不联网。
 *
 * 「所有变体一起改」是必须的，不是保险：快照一页只存一份文件，而消费方按哪一档来问由它
 * 自己决定 —— 大图页 [ceui.lisa.fragments.FragmentImageDetail] 恒按 ORIGINAL 取，
 * 详情页 IllustAdapter 按全局「展示原图」设置在 ORIGINAL / LARGE 之间切。逐个变体去
 * assets 里查、查不到就留着原 URL 的做法，会让没归档的那几档继续指着 i.pximg.net：
 * 在线时偷偷回源（拿到的还不是存档那张），离线时直接空白。
 *
 * [Illust] 现在是不可变 data class，整份 bean 用 copy 链重建 —— 也就不再需要原先那次
 * 「Gson 序列化再反序列化」的深拷贝（那是为了防止改到池里那份共享的可变 bean）。
 */
fun SnapshotViewerData.localizeIllust(): Illust {
    val pageCount = illust.page_count.coerceAtLeast(1)
    val coverLocalUrl = pageLocalUrl(0)

    val localizedImageUrls = if (pageCount <= 1) {
        // 单图：illust 级 image_urls 就是这一页本身。
        pageLocalUrl(0)?.let { illust.image_urls?.allPointingTo(it) } ?: illust.image_urls
    } else {
        // 多图：illust 级 image_urls 是封面，不属于任何一页，指到 p0 的存档，
        // 免得「按封面取图」的地方（分享、缩略图）回源。
        coverLocalUrl?.let { illust.image_urls?.allPointingTo(it) } ?: illust.image_urls
    }

    val localizedSinglePage = illust.meta_single_page?.let { single ->
        pageLocalUrl(0)?.let { single.copy(original_image_url = it) } ?: single
    }

    val localizedPages = illust.meta_pages?.mapIndexed { index, page ->
        val localUrl = pageLocalUrl(index) ?: return@mapIndexed page
        page.copy(image_urls = page.image_urls?.allPointingTo(localUrl) ?: page.image_urls)
    }

    val localizedUser = illust.user?.let { user ->
        val urls = user.profile_image_urls ?: return@let user
        val localAvatar = firstLocalizableOf(urls) ?: return@let user
        user.copy(profile_image_urls = urls.allPointingTo(localAvatar))
    }

    return illust.copy(
        image_urls = localizedImageUrls,
        meta_single_page = localizedSinglePage,
        meta_pages = localizedPages,
        user = localizedUser,
    )
}

/** 一页只有一份存档文件，所以该页所有尺寸变体都指向它。 */
private fun ImageUrls.allPointingTo(localUrl: String): ImageUrls = copy(
    url = localUrl,
    large = localUrl,
    medium = localUrl,
    original = localUrl,
    small = localUrl,
    square_medium = localUrl,
    px_16x16 = localUrl,
    px_50x50 = localUrl,
    px_170x170 = localUrl,
)

/** 头像：归档了哪一档就返回它的本地 URL；一档都没归档返回 null（调用方原样保留）。 */
private fun SnapshotViewerData.firstLocalizableOf(urls: ImageUrls): String? = listOfNotNull(
    urls.px_170x170, urls.medium, urls.large, urls.original,
    urls.square_medium, urls.small, urls.url, urls.px_50x50, urls.px_16x16,
).firstNotNullOfOrNull { it.localizedOrNull(snapshotDir, manifest.snapshotId, assets) }

/** 把快照评论本地化：评论者头像和表情章 URL 改成 shaftsnap://。 */
fun SnapshotViewerData.localizeComment(comment: Comment): Comment {
    val localizedAvatarUrls = comment.user.profile_image_urls?.let { urls ->
        firstLocalizableOf(urls)?.let { urls.allPointingTo(it) } ?: urls
    }
    val localizedUser = comment.user.copy(profile_image_urls = localizedAvatarUrls)
    val localizedStamp = comment.stamp?.let { stamp ->
        stamp.stamp_url?.localizedOrNull(snapshotDir, manifest.snapshotId, assets)
            ?.let { stamp.copy(stamp_url = it) }
            ?: stamp.copy(stamp_url = null)
    }
    return comment.copy(user = localizedUser, stamp = localizedStamp)
}

/**
 * URL → `shaftsnap://`；这个 URL 没归档过就返回 null。
 *
 * ⚠️ 不要改成「没归档就原样返回」：调用点全是「挑一个本地的出来」，
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
