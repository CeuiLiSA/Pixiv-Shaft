package ceui.lisa.repo

import ceui.lisa.BuildConfig
import ceui.lisa.interfaces.ListShow
import ceui.lisa.utils.PixivSearchParamUtil
import ceui.pixiv.api.Client
import ceui.pixiv.session.SessionManager
import ceui.pixiv.shaftapi.Nana7miSearchCacheLookupReq
import ceui.pixiv.shaftapi.Nana7miSearchCacheLookupResp
import ceui.pixiv.shaftapi.Nana7miSearchCacheStoreReq
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
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
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

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24L * HOUR_MS

    /** 结果随时会变的搜索（窗口里有今天/昨天的作品、或按时间排序）：只合并几乎同时发起的同一搜索。 */
    const val MAX_AGE_FRESH_MS = 30L * 60_000L

    /** 与服务端 `SEARCH_CACHE_SERVE_MAX_AGE_MS` 同值：再长服务端也会截断。 */
    const val MAX_AGE_STABLE_MS = 7L * DAY_MS

    private val gson = Gson()
    private data class FillKey(val uid: Long, val kind: Kind, val key: String)
    private val fillTokens = ConcurrentHashMap<FillKey, String>()
    private const val MAX_PENDING_FILLS = 64

    /**
     * 这次搜索能接受多旧的缓存页。[startDate]/[endDate] 是**实际发出去**的 YYYY-MM-DD（相对档
     * 已按今天算好），null = 不传。
     *
     * 人气排序：排名的变化速度取决于窗口里的作品有多新——刚发的作品几小时就能冲进前排，一年前的
     * 作品一周也挪不了几位。所以按窗口往回能伸到多远分档：不限期间（点 tag 进搜索页的默认档）或
     * 整个窗口都在一个月以前 → 7 天；近一年 → 1 天；近一月 → 12 小时；近一周 → 2 小时；
     * 近 24 小时 → 30 分钟。窗口以今天结尾时 key 本身带日期、跨天自然换 key，这里只管当天之内。
     *
     * 时间排序（非会员只有带喜欢数筛选才会借号）：列表头就是最新的作品，窗口伸到最近一周的一律
     * 30 分钟；只有整个窗口已经结束一周以上才放宽到 1 天（新作进不来，只剩跨过喜欢数门槛的零星插入）。
     *
     * 日期解析不了就按最严的一档处理：宁可少命中，不给出逻辑不对的结果。
     */
    fun maxAgeMsFor(
        sortType: String?,
        startDate: String?,
        endDate: String?,
        today: LocalDate,
    ): Long {
        val daysSinceEnd = if (endDate == null) 0L else daysBefore(endDate, today) ?: return MAX_AGE_FRESH_MS
        val popular = sortType == PixivSearchParamUtil.POPULAR_SORT_VALUE ||
                sortType == SortType.POPULAR_MALE_DESC ||
                sortType == SortType.POPULAR_FEMALE_DESC
        if (!popular) {
            return if (endDate != null && daysSinceEnd > 7) DAY_MS else MAX_AGE_FRESH_MS
        }
        if (startDate == null || daysSinceEnd >= 30) return MAX_AGE_STABLE_MS
        val daysSinceStart = daysBefore(startDate, today) ?: return MAX_AGE_FRESH_MS
        return when {
            daysSinceStart <= 2 -> MAX_AGE_FRESH_MS
            daysSinceStart <= 8 -> 2L * HOUR_MS
            daysSinceStart <= 32 -> 12L * HOUR_MS
            daysSinceStart <= 366 -> DAY_MS
            else -> MAX_AGE_STABLE_MS
        }
    }

    /** [date] 在 [today] 之前多少天（未来日期为负）；不是 YYYY-MM-DD 返回 null。 */
    private fun daysBefore(date: String, today: LocalDate): Long? = try {
        ChronoUnit.DAYS.between(LocalDate.parse(date), today)
    } catch (_: DateTimeParseException) {
        null
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
        val lookup = suspend {
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
        }
        val resp = try {
            if (requestId == null) lookup() else retryNana7miNetworkCall(
                stage = "${stage}_cache_lookup",
                source = lookup,
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
            "stage=%s cache=%s key=%s age_ms=%s max_age_ms=%d",
            stage,
            if (decoded != null) "hit" else "miss",
            key.take(12),
            body?.ageMs?.toString() ?: "-",
            maxAgeMs,
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
