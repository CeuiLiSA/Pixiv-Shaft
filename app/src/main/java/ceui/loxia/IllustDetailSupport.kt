package ceui.loxia

import ceui.lisa.http.Retro
import ceui.lisa.models.IllustsBean
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * issue #569: 详情页渲染 / 下载所需的字段是否齐全。
 *
 * 网页「按 Tag 筛选」等精简来源只给方图缩略图,缺分页图(meta_pages / meta_single_page),
 * 用它直接建多图分页或下原图会崩或残缺,需回 v1/illust/detail 拉完整版。
 * 头像缺失不在此判定内——已由 GlideUtil/IllustDownload 的空值兜底处理,不再单独触发整页重拉。
 */
fun IllustsBean.isFullDetail(): Boolean {
    return if (page_count <= 1) {
        meta_single_page?.original_image_url?.isNotEmpty() == true
    } else {
        (meta_pages?.size ?: 0) >= page_count
    }
}

/**
 * issue #960: pixiv 的列表接口(动态 v2/illust/follow、收藏列表等)会不定期对部分作品返回
 * 空 caption(pixiv 侧行为,上游 pixivpy#363 同现象),而 [isFullDetail] 只看图片字段——
 * 列表 bean 一律判 full,详情页据此跳过回源,简介就时有时无。
 * caption 非空可直接信;为空时只有 detail 接口整体覆盖过([ObjectPool.hasFullIllustVersion])
 * 才算「确认过真没有简介」,否则详情页应回 v1/illust/detail 补拉一次。
 */
fun IllustsBean.hasTrustedCaption(): Boolean {
    return !caption.isNullOrEmpty() || ObjectPool.hasFullIllustVersion(id.toLong())
}

/**
 * 回 v1/illust/detail 拉完整版,整体覆盖(isFullVersion)进 ObjectPool 并返回。
 * 作品已删 / 不可见 / 网络失败返回 null —— 此时不覆盖,保留池里已有数据,由调用方降级处理。
 */
suspend fun fetchFullIllustDetail(illustId: Long): IllustsBean? {
    return try {
        val fresh = Retro.getAppApi().getIllustByID(illustId).awaitFirstOrThrow().illust
        if (fresh != null && fresh.id != 0 && fresh.isVisible) {
            ObjectPool.update(fresh, isFullVersion = true)
            fresh.user?.let { ObjectPool.update(it) }
            fresh
        } else {
            null
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        Timber.e(e, "fetchFullIllustDetail failed illustId=%d", illustId)
        null
    }
}

/**
 * 网页 ajax `/ajax/illust/{id}/pages` 拉每一 P 的真实原图宽高（`[width, height]`,按页序）。
 * app-api 的 meta_pages 只有 image_urls、不带宽高;详情页多 P 靠它在下载前预置每页展示 ratio,
 * 消除「兜底高→自然高」的首帧跳(见 IllustAdapter.seedPageDimensions)。
 *
 * SFW 作品无 cookie 也能拿;R18 / 受限 / 删除作品或缺 cookie 时接口返回 error/空 → 返回 null,
 * 调用方沿用「图片解码后异步定高」的兜底,**不影响使用**。协程取消照常向上抛,不吞。
 */
suspend fun fetchIllustPageDimensions(illustId: Long): List<IntArray>? {
    return try {
        val resp = Client.webApi.getIllustPages(illustId)
        val pages = resp.body
        if (resp.error == true || pages.isNullOrEmpty()) {
            Timber.d(
                "fetchIllustPageDimensions unavailable illust=%d error=%s size=%s",
                illustId, resp.error, pages?.size
            )
            null
        } else {
            pages.map { intArrayOf(it.width, it.height) }
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        Timber.d(e, "fetchIllustPageDimensions failed illust=%d", illustId)
        null
    }
}

private suspend fun <T : Any> Observable<T>.awaitFirstOrThrow(): T =
    suspendCancellableCoroutine { cont ->
        val disposable = subscribeOn(Schedulers.io())
            .firstOrError()
            .subscribe(
                { if (cont.isActive) cont.resume(it) },
                { if (cont.isActive) cont.resumeWithException(it) },
            )
        cont.invokeOnCancellation { disposable.dispose() }
    }
