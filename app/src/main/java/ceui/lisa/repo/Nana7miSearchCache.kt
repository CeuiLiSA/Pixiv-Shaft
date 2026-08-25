package ceui.lisa.repo

import ceui.lisa.BuildConfig
import ceui.lisa.utils.PixivSearchParamUtil
import ceui.loxia.Client
import ceui.loxia.Nana7miSearchCacheLookupReq
import ceui.loxia.Nana7miSearchCacheLookupResp
import ceui.loxia.Nana7miSearchCacheStoreReq
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.search.SortType
import com.google.gson.Gson
import com.google.gson.JsonElement
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 借号搜索一级缓存的客户端半边（server: pixshaft-api `src/search-cache.js`）。
 *
 * 借号搜索一次要花：借号方的额度、多半一次借来账号的 renew、以及从又一个 IP 打到池子账号
 * 上的一次会员专属请求。绝大部分是重复劳动——同一个 tag 的人气排序一天里很多人搜。所以借号
 * **之前**先问 pixshaft 一声：命中就直接拿那页渲染；未命中才借号，借完把那页回填，下一个发同样
 * 请求的人（不限于自己）就能命中。翻页同理：每页的 `next_url` 就是下一页的 key。
 *
 * 服务端不认识 Pixiv 的参数，key 由这里按「马上要发的那个请求」算 sha256：同一个请求天然同一个
 * key，谁存的谁都能用。请求参数（关键字 / 排序 / 全部筛选 / `search_ai_type`）任何一项不同就是
 * 不同的 key——这是对的，不然 B 会看到按 A 的设置过滤过的结果。
 *
 * 这条路永远不能让搜索失败：查询的任何异常（网络、非 2xx、脏响应、限流）都等价于未命中；回填
 * 发完即忘。
 */
internal object Nana7miSearchCache {

    enum class Kind(val wire: String) { ILLUST("illust"), NOVEL("novel") }

    /** 规范串的版本前缀：改了参数拼法就升它，老 key 自然作废，不会串页。 */
    private const val KEY_VERSION = "v1"

    /** 人气类排序一天里变化很小；date 排序（带喜欢数筛选才会借号）新作会往前插，容忍度低得多。 */
    private const val MAX_AGE_POPULAR_MS = 12L * 3_600_000L
    private const val MAX_AGE_DATE_MS = 30L * 60_000L

    private val gson = Gson()

    fun maxAgeMsFor(sortType: String?): Long = when (sortType) {
        PixivSearchParamUtil.POPULAR_SORT_VALUE,
        SortType.POPULAR_MALE_DESC,
        SortType.POPULAR_FEMALE_DESC,
        -> MAX_AGE_POPULAR_MS

        else -> MAX_AGE_DATE_MS
    }

    /**
     * 首屏 key。[params] 是即将发给 Pixiv 的 query 参数（名 → 值），顺序由调用方固定；null 值
     * 表示 Retrofit 不会发这个参数，直接跳过——「没传」和「传了空串」对 Pixiv 是两个请求。
     */
    fun firstPageKey(kind: Kind, params: List<Pair<String, Any?>>): String {
        val canonical = buildString {
            append(KEY_VERSION).append('|').append(kind.wire).append("|first")
            for ((name, value) in params) {
                if (value == null) continue
                append('|').append(name).append('=').append(encode(value.toString()))
            }
        }
        return sha256Hex(canonical)
    }

    /** 翻页 key：`next_url` 本身就带全部参数 + offset，不绑定账号，任何会员号打出来都是同一页。 */
    fun nextPageKey(kind: Kind, nextUrl: String): String =
        sha256Hex("$KEY_VERSION|${kind.wire}|next|$nextUrl")

    /**
     * 先查缓存，命中直接发射；未命中订阅 [miss]。查询在订阅线程上同步进行（RemoteRepo 已经把
     * 它放在 `Schedulers.newThread()`），一次 pixshaft 往返换一次可能省掉的借号 + Pixiv 请求。
     */
    fun <T : Any> firstOrElse(
        kind: Kind,
        key: String,
        maxAgeMs: Long,
        type: Class<T>,
        stage: String,
        onHit: (T) -> Unit = {},
        miss: () -> Observable<T>,
    ): Observable<T> = Observable.defer {
        val cached = lookup(kind, key, maxAgeMs, type, stage)
        if (cached != null) {
            onHit(cached)
            Observable.just(cached)
        } else {
            miss()
        }
    }

    /** 命中返回解析好的页面，其余一切返回 null。取消照常向上抛。 */
    fun <T : Any> lookup(kind: Kind, key: String, maxAgeMs: Long, type: Class<T>, stage: String): T? {
        val uid = requesterUidOrNull() ?: return null
        val resp = try {
            runBlocking {
                Client.pixshaft.searchCacheLookupRaw(
                    Nana7miSearchCacheLookupReq(uid = uid, kind = kind.wire, key = key, maxAgeMs = maxAgeMs),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(LOG_TAG).w(
                "stage=%s cache=error error_type=%s",
                stage,
                e.javaClass.simpleName,
            )
            return null
        }
        if (!resp.isSuccessful) {
            Timber.tag(LOG_TAG).w("stage=%s cache=http_%d", stage, resp.code())
            return null
        }
        val page = decode(resp.body(), type)
        Timber.tag(LOG_TAG).d(
            "stage=%s cache=%s age_ms=%s",
            stage,
            if (page != null) "hit" else "miss",
            resp.body()?.ageMs?.toString() ?: "-",
        )
        return page
    }

    /** 纯解析，方便单测：`hit != true`、没有 page、或 page 解析不出 [type] 都是 null。 */
    fun <T : Any> decode(body: Nana7miSearchCacheLookupResp?, type: Class<T>): T? {
        if (body?.hit != true) return null
        val page = body.page ?: return null
        if (!page.isJsonObject) return null
        return try {
            gson.fromJson(page, type)
        } catch (e: RuntimeException) {
            Timber.tag(LOG_TAG).w(e, "cache page did not parse as %s", type.simpleName)
            null
        }
    }

    /**
     * 回填一页。序列化在调用线程同步做（拿到结果的后台线程，几毫秒），上传扔到 IO 线程发完即忘。
     * 被拒 / 失败一律只记日志：这一页已经成功交给 UI 了，缓存的事不能反过来影响它。
     */
    fun store(kind: Kind, key: String, page: Any, stage: String) {
        val uid = requesterUidOrNull() ?: return
        val element: JsonElement = try {
            gson.toJsonTree(page)
        } catch (e: RuntimeException) {
            Timber.tag(LOG_TAG).w(e, "stage=%s cache_store=serialize_failed", stage)
            return
        }
        val req = Nana7miSearchCacheStoreReq(uid = uid, kind = kind.wire, key = key, page = element)
        Observable.fromCallable { runBlocking { Client.pixshaft.searchCacheStoreRaw(req) } }
            .subscribeOn(Schedulers.io())
            .subscribe(
                { resp ->
                    val body = resp.body()
                    Timber.tag(LOG_TAG).d(
                        "stage=%s cache_store=%s reason=%s",
                        stage,
                        if (resp.isSuccessful && body?.stored == true) "stored" else "refused",
                        body?.reason ?: resp.code().toString(),
                    )
                },
                { e ->
                    Timber.tag(LOG_TAG).w(
                        "stage=%s cache_store=error error_type=%s",
                        stage,
                        e.javaClass.simpleName,
                    )
                },
            )
    }

    private fun requesterUidOrNull(): Long? {
        // Lite 不借号也不参与缓存；服务端要一个合法 uid 做限流键，没登录就不问。
        if (BuildConfig.IS_LITE) return null
        return SessionManager.loggedInUid.takeIf { it > 0L }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private const val LOG_TAG = "sadadsdasdw2"
}
