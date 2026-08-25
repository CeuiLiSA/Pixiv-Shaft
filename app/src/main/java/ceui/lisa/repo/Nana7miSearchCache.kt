package ceui.lisa.repo

import ceui.lisa.BuildConfig
import ceui.lisa.interfaces.ListShow
import ceui.lisa.utils.PixivSearchParamUtil
import ceui.loxia.Client
import ceui.loxia.Nana7miSearchCacheLookupReq
import ceui.loxia.Nana7miSearchCacheLookupResp
import ceui.loxia.Nana7miSearchCacheStoreReq
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.search.SortType
import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 借号搜索一级缓存的客户端半边（server: pixshaft-api `src/search-cache.js`）。
 *
 * 借号搜索一次要花：借号方的额度、多半一次借来账号的 renew、以及从又一个 IP 打到池子账号
 * 上的一次会员专属请求。用户返回同一个 tag、重试或反复翻页时会重复做这些事。所以借号
 * **之前**先问 pixshaft 一声：命中就直接拿那页渲染；未命中才借号，借完把那页回填，下一个发同样
 * 请求的人——**不论是谁**——就能命中。缓存是跨用户共享的：A 借号搜过，B 直接吃现成的，这是
 * 产品决定（滥用靠一次性回填凭证 + 服务端形状校验挡）。翻页同理：每页的 `next_url` 就是下一页的 key。
 *
 * 服务端不认识 Pixiv 的参数，key 由这里按「马上要发的那个请求」算 sha256：同一个请求天然同一个
 * key，谁发的都能复用。请求参数（关键字 / 排序 / 全部筛选 /
 * `search_ai_type`）任何一项不同就是不同的 key——否则后一次会看到按前一次设置过滤过的结果。
 *
 * 这条路永远不能让搜索失败：查询的任何异常（网络、非 2xx、脏响应、限流）都等价于未命中；回填
 * 发完即忘。
 */
internal object Nana7miSearchCache {

    enum class Kind(val wire: String) { ILLUST("illust"), NOVEL("novel") }
    /** 命中时服务端按它计费：首屏 = 一次搜索，翻页 = 一次翻页。 */
    enum class Page(val wire: String) { FIRST("first"), NEXT("next") }

    /** 规范串的版本前缀：改了参数拼法就升它，老 key 自然作废，不会串页。 */
    private const val KEY_VERSION = "v1"

    /** 人气类排序一天里变化很小；date 排序（带喜欢数筛选才会借号）新作会往前插，容忍度低得多。 */
    private const val MAX_AGE_POPULAR_MS = 12L * 3_600_000L
    private const val MAX_AGE_DATE_MS = 30L * 60_000L

    private val gson = Gson()
    private data class FillKey(val uid: Long, val kind: Kind, val key: String)
    private val fillTokens = ConcurrentHashMap<FillKey, String>()
    private const val MAX_PENDING_FILLS = 64

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
     * 先查缓存，命中交给 [hit]；未命中跑 [miss]。一次 pixshaft 往返换一次可能省掉的借号 + Pixiv 请求。
     */
    suspend fun <T : Any> firstOrElse(
        kind: Kind,
        key: String,
        page: Page,
        requestId: String?,
        maxAgeMs: Long,
        type: Class<T>,
        stage: String,
        hit: suspend (T) -> T = { it },
        miss: suspend () -> T,
    ): T {
        val cached = lookup(kind, key, page, requestId, maxAgeMs, type, stage)
        return if (cached != null) hit(cached) else miss()
    }

    /**
     * 命中返回解析好的页面，其余一切返回 null。取消照常向上抛。
     *
     * 命中在服务端已经按 [page] 计了费；额度满了服务端回 429（和借号同一个形状），这里当未命中
     * 处理——接下来的借号会被同样拒绝、走既有的额度提示 + 预览降级。
     */
    suspend fun <T : Any> lookup(
        kind: Kind,
        key: String,
        page: Page,
        requestId: String?,
        maxAgeMs: Long,
        type: Class<T>,
        stage: String,
    ): T? {
        val uid = requesterUidOrNull() ?: return null
        val fillKey = FillKey(uid, kind, key)
        // A new lookup supersedes any receipt left by an abandoned older flow.
        fillTokens.remove(fillKey)
        val resp = try {
            Client.pixshaft.searchCacheLookupRaw(
                Nana7miSearchCacheLookupReq(
                    uid = uid,
                    kind = kind.wire,
                    key = key,
                    maxAgeMs = maxAgeMs,
                    page = page.wire,
                    requestId = requestId,
                ),
            )
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
        val body = resp.body()
        val decoded = decode(body, type)
        if (
            decoded == null && body?.hit == false && requestId != null &&
            !body.storeToken.isNullOrBlank()
        ) {
            // This map is an optimisation, never durable state. A pathological
            // number of abandoned misses may drop receipts and merely reduce
            // future hit rate; it cannot break the successful search.
            if (fillTokens.size >= MAX_PENDING_FILLS) fillTokens.clear()
            fillTokens[fillKey] = body.storeToken
        }
        Timber.tag(LOG_TAG).d(
            "stage=%s cache=%s key=%s age_ms=%s",
            stage,
            if (decoded != null) "hit" else "miss",
            key.take(12),
            body?.ageMs?.toString() ?: "-",
        )
        return decoded
    }

    /** 纯解析，方便单测：`hit != true`、没有 page、page 解析不出 [type]、或列表缺失都是 null。 */
    fun <T : Any> decode(body: Nana7miSearchCacheLookupResp?, type: Class<T>): T? {
        if (body?.hit != true) return null
        val page = body.page ?: return null
        if (!page.isJsonObject) return null
        val parsed = try {
            gson.fromJson(page, type)
        } catch (e: RuntimeException) {
            Timber.tag(LOG_TAG).w(e, "cache page did not parse as %s", type.simpleName)
            return null
        }
        // Mapper.apply 会遍历 getList()，null 会 NPE——服务端保证列表在，但这条路的规矩是
        // 「任何不干净的东西都算未命中」，不把它交给下游去炸。
        if (parsed is ListShow<*>) {
            if (parsed.list == null) return null
            if (!isSafePixivNextUrl(parsed.nextUrl)) {
                Timber.tag(LOG_TAG).w("cache page carried an unsafe next_url")
                return null
            }
        }
        return parsed
    }

    /**
     * 回填一页。序列化在调用线程同步做（拿到结果的后台线程，几毫秒），上传扔到 IO 线程发完即忘。
     * 被拒 / 失败一律只记日志：这一页已经成功交给 UI 了，缓存的事不能反过来影响它。
     */
    fun store(kind: Kind, key: String, page: Any, stage: String) {
        val uid = requesterUidOrNull() ?: return
        val storeToken = fillTokens.remove(FillKey(uid, kind, key)) ?: return
        val element: JsonElement = try {
            gson.toJsonTree(page)
        } catch (e: RuntimeException) {
            Timber.tag(LOG_TAG).w(e, "stage=%s cache_store=serialize_failed", stage)
            return
        }
        val req = Nana7miSearchCacheStoreReq(
            uid = uid,
            kind = kind.wire,
            key = key,
            page = element,
            storeToken = storeToken,
        )
        storeScope.launch {
            try {
                val resp = Client.pixshaft.searchCacheStoreRaw(req)
                val body = resp.body()
                Timber.tag(LOG_TAG).d(
                    "stage=%s cache_store=%s key=%s reason=%s",
                    stage,
                    if (resp.isSuccessful && body?.stored == true) "stored" else "refused",
                    key.take(12),
                    body?.reason ?: resp.code().toString(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(LOG_TAG).w(
                    "stage=%s cache_store=error error_type=%s",
                    stage,
                    e.javaClass.simpleName,
                )
            }
        }
    }

    /** 回填上传专用：进程级、不随任何页面取消——那一页已经交给 UI，回填是发完即忘的。 */
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun requesterUidOrNull(): Long? {
        // Lite 不借号也不参与缓存；服务端要一个合法 uid 做限流键，没登录就不问。
        if (BuildConfig.IS_LITE) return null
        return SessionManager.loggedInUid.takeIf { it > 0L }
    }

    /** 一次页面操作一个 ID；lookup、可能的 fallback 和 request 遥测必须复用它。 */
    fun newRequestId(): String = UUID.randomUUID().toString()

    /** A cached cursor is followed with the borrowed account's Authorization. */
    private fun isSafePixivNextUrl(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        return try {
            val uri = URI(raw)
            "https".equals(uri.scheme, ignoreCase = true) &&
                    "app-api.pixiv.net".equals(uri.host, ignoreCase = true) &&
                    uri.rawUserInfo == null && uri.rawFragment == null &&
                    (uri.port == -1 || uri.port == 443)
        } catch (_: Exception) {
            false
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private const val LOG_TAG = "sadadsdasdw2"
}
