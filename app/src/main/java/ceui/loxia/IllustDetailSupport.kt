package ceui.loxia

import ceui.lisa.http.Retro
import ceui.lisa.interfaces.Callback
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.ImageUrlsBean
import ceui.lisa.models.MetaPagesBean
import ceui.lisa.models.MetaSinglePageBean
import ceui.lisa.models.TagsBean
import ceui.lisa.models.UserBean
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.URLDecoder
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
 * app-api 判作品不可见时走网页 ajax 兜底(#592);已删 / 兜底也拿不到 / 网络失败返回
 * null —— 此时不覆盖,保留池里已有数据,由调用方降级处理。
 */
suspend fun fetchFullIllustDetail(illustId: Long): IllustsBean? {
    val fresh = try {
        Retro.getAppApi().getIllustByID(illustId).awaitFirstOrThrow().illust
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        Timber.e(e, "fetchFullIllustDetail failed illustId=%d", illustId)
        return null
    }
    val usable = if (fresh != null && fresh.id != 0 && fresh.isVisible) {
        fresh
    } else {
        // #592: app-api 应移动端商店政策把部分作品藏成 visible=false 的空壳(占位图
        // limit_unviewable),网页 ajax 不受此限 —— 兜底拉一份;真删除的作品 web 侧
        // 同样 error,自然落回 null。
        fetchIllustViaWebApi(illustId) ?: return null
    }
    ObjectPool.update(usable, isFullVersion = true)
    usable.user?.let { ObjectPool.update(it) }
    return usable
}

/**
 * issue #592: 网页 ajax /ajax/illust/{id} 拉受限作品并映射成 [IllustsBean]。
 * 多 P 的每页图址优先取 /ajax/illust/{id}/pages;该接口对 R18 需要网页 cookie,
 * 拿不到时按 pixiv 固定命名规则用 p0 原图地址推导 pN(gallery-dl 同方案)。
 * 拿不到 / 网络失败返回 null,不落池;协程取消照常向上抛。
 */
suspend fun fetchIllustViaWebApi(illustId: Long): IllustsBean? {
    return try {
        val resp = Client.webApi.getWebIllust(illustId)
        val body = resp.body
        if (resp.error == true || body == null || body.urls?.original.isNullOrEmpty()) {
            Timber.d(
                "fetchIllustViaWebApi unavailable illust=%d error=%s msg=%s",
                illustId, resp.error, resp.message
            )
            null
        } else {
            val webPages = if (body.pageCount > 1) {
                try {
                    Client.webApi.getIllustPages(illustId).body
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Timber.d(e, "fetchIllustViaWebApi pages failed illust=%d", illustId)
                    null
                }
            } else {
                null
            }
            body.toIllustsBean(illustId, webPages)
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        Timber.e(e, "fetchIllustViaWebApi failed illustId=%d", illustId)
        null
    }
}

/**
 * V2(Java/Rx 链)侧的桥:后台拉 web 兜底,成功先落池(isFullVersion),主线程回调;
 * 拿不到回调 null。见 PixivOperate.getIllustByID。
 */
fun fetchWebIllustFallbackAsync(illustId: Long, onResult: Callback<IllustsBean?>) {
    MainScope().launch {
        val bean = withContext(Dispatchers.IO) { fetchIllustViaWebApi(illustId) }
        if (bean != null) {
            ObjectPool.update(bean, isFullVersion = true)
            bean.user?.let { ObjectPool.update(it) }
        }
        onResult.doSomething(bean)
    }
}

// 网页 description 里的外链包着站内跳转 <a href="/jump.php?<urlencoded>">,
// app 的 caption 渲染(fromHtml)会把它当相对路径,解开成真实地址。
private val WEB_JUMP_LINK = Regex("(?<=href=\")/jump\\.php\\?([^\"]+)(?=\")")

/**
 * 网页 ajax 的时间戳是 UTC(+00:00),app-api 全是 JST(+09:00),而 DateParse 的展示
 * 逻辑直接取字符串本地部分 —— 不换算的话发布时间会差 9 小时。统一换算成 +09:00 形式;
 * 解析不动的字符串原样保留(展示层对非 25 位串有 FAKE_DATE 兜底)。
 */
private fun webDateToAppDate(webDate: String): String {
    return runCatching {
        val pattern = "yyyy-MM-dd'T'HH:mm:ssXXX"
        val parsed = java.text.SimpleDateFormat(pattern, java.util.Locale.US).parse(webDate)!!
        java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("GMT+09:00")
        }.format(parsed)
    }.getOrDefault(webDate)
}

private fun WebIllustUrls.toImageUrlsBean(): ImageUrlsBean {
    val web = this
    return ImageUrlsBean().apply {
        square_medium = web.thumb ?: web.thumb_mini ?: web.small
        medium = web.small ?: web.regular
        large = web.regular ?: web.original
        original = web.original
    }
}

private fun WebIllustBody.toIllustsBean(illustId: Long, webPages: List<WebIllustPage>?): IllustsBean {
    val body = this
    return IllustsBean().apply {
        id = illustId.toInt()
        title = body.illustTitle
        type = when (body.illustType) {
            2 -> "ugoira"
            1 -> "manga"
            else -> "illust"
        }
        caption = body.description?.replace(WEB_JUMP_LINK) { m ->
            // 单条链接畸形(坏 % 序列)只保留原样,不让 IllegalArgumentException
            // 把整个 web 兜底炸回「无法显示」
            runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }
                .getOrDefault(m.value)
        }
        restrict = body.restrict
        x_restrict = body.xRestrict
        sanity_level = body.sl
        illust_ai_type = body.aiType
        create_date = body.createDate?.let(::webDateToAppDate)
        page_count = body.pageCount
        width = body.width
        height = body.height
        total_view = body.viewCount
        total_bookmarks = body.bookmarkCount
        isIs_bookmarked = body.bookmarkData != null
        isVisible = true
        tools = emptyList()
        tags = body.tags?.tags?.mapNotNull { webTag ->
            webTag.tag?.let { tagName ->
                TagsBean().apply {
                    name = tagName
                    translated_name = webTag.translation?.values?.firstOrNull()
                }
            }
        }
        user = UserBean().apply {
            id = body.userId?.toIntOrNull() ?: 0
            name = body.userName
            account = body.userAccount
        }
        image_urls = body.urls?.toImageUrlsBean()
        if (body.pageCount <= 1) {
            meta_single_page = MetaSinglePageBean().apply {
                original_image_url = body.urls?.original
            }
            meta_pages = emptyList()
        } else {
            // 多 P:app-api 的 meta_single_page 本来就是空对象,保持形状一致
            meta_single_page = MetaSinglePageBean()
            meta_pages = if (webPages != null && webPages.size >= body.pageCount &&
                webPages.all { !it.urls?.original.isNullOrEmpty() }
            ) {
                webPages.map { page ->
                    MetaPagesBean().apply { image_urls = page.urls!!.toImageUrlsBean() }
                }
            } else {
                (0 until body.pageCount).map { i ->
                    MetaPagesBean().apply {
                        image_urls = ImageUrlsBean().apply {
                            square_medium = body.urls?.thumb?.replace("_p0", "_p$i")
                            medium = body.urls?.small?.replace("_p0", "_p$i")
                            large = body.urls?.regular?.replace("_p0", "_p$i")
                            original = body.urls?.original?.replace("_p0", "_p$i")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 网页 ajax `/ajax/illust/{id}/pages` 拉每一 P 的真实原图宽高（`[width, height]`,按页序）。
 * 注:详情页多 P 在真实宽高到来前,先用 {@code IllustsBean} 的 width/height当
 * 「最开始的空白容器」(见 IllustAdapter.applyRatioOrPlaceholder);这里的网页 ajax 再补每 P 真实
 * 原图宽高做精修,把后续页展示高度摆准,消除「占位比→自然比」的首帧跳(见 IllustAdapter.seedPageDimensions)。
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
