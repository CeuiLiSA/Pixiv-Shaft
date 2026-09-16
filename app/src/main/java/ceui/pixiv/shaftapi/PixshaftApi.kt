package ceui.pixiv.shaftapi

import ceui.loxia.Tag
import ceui.pixiv.api.model.AccountResponse
import ceui.pixiv.api.model.Illust
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * pixshaft-api (https://pixshaft.com) — remote browse history for the logged-in
 * viewer. Field names match the server JSON exactly (snake_case) so the default
 * Gson converter maps them without @SerializedName.
 */
interface PixshaftApi {

    @POST("v1/history/{uid}")
    suspend fun reportHistory(
        @Path("uid") uid: Long,
        @Body body: HistoryReportBody,
    ): HistoryReportAck

    @GET("v1/history/{uid}")
    suspend fun listHistory(
        @Path("uid") uid: Long,
        @Query("type") type: String?,
        @Query("q") q: String?,
        @Query("before") before: String?,
        @Query("limit") limit: Int,
    ): HistoryListResponse

    @DELETE("v1/history/{uid}/{type}/{id}")
    suspend fun deleteHistory(
        @Path("uid") uid: Long,
        @Path("type") type: String,
        @Path("id") id: Long,
    ): HistoryDeleteAck

    @DELETE("v1/history/{uid}")
    suspend fun clearHistory(
        @Path("uid") uid: Long,
        @Query("type") type: String?,
    ): HistoryDeleteAck

    /**
     * Report the current browse-history cloud-sync toggle. Called on every flip
     * (and once on login) so the admin console can list opted-out users — the
     * settings blob goes to a different backend (moonAPI), so the server can't
     * learn this any other way. Best-effort; unsigned like the rest of /v1/history.
     */
    @POST("v1/history/{uid}/sync-pref")
    suspend fun reportHistorySyncPref(
        @Path("uid") uid: Long,
        @Body body: SyncPrefBody,
    ): SyncPrefAck

    /**
     * 冷启动配置：客户端自己决定不了的开关（服务端 `src/app-config.js`）。
     *
     * [uid] 是调用方自己的 pixiv uid，用来选灰度分桶（白名单/黑名单），不是身份凭证。
     * 未登录传 null 直接不带该参数。[flavor] 通过 Header 传 Gradle flavor（google/github），
     * 不改变旧版 `/v1/config` 的 URL 契约。服务端对 google 恒定关闭借号搜索，并且不查询/
     * 返回借号套餐。
     */
    @GET("v1/config")
    suspend fun appConfig(
        @Query("uid") uid: Long?,
        @Header("X-Shaft-Flavor") flavor: String,
    ): AppConfigResponse

    /**
     * 应用内推送的已读回执（服务端 `src/inapp-push.js`）。
     *
     * `/v1/config` 带回来的那条推送展示过一次之后调这个，服务端记下「这个 uid 看过了」，
     * 之后任何设备、重装之后都不再下发。签名和 `/v1/account/…` 一样签请求体（见 [Client]）。
     * 幂等：重复回执是空操作；404 = 这条推送已被后台删掉，同样算「办完了」。
     */
    @POST("v1/push/ack")
    suspend fun ackInAppPush(@Body req: InAppPushAckReq): Response<InAppPushAckResp>

    /**
     * 一页「热度标签」精选插画。
     *
     * 这份内置榜以前是 APK 里 183MB 的 `assets/pixiv_prime/prime_tag_for_<sha256>.txt`
     * （压缩后仍占安装包约 19MB），点一个标签就把整包 300 条读进内存。现在数据搬到
     * pixshaft-api，App 只留 `prime_index.json` 目录（离线可渲染货架），插画按页取。
     *
     * [key] 就是老文件名里那段 sha256，仍由 index 的 `file_path` 带着。
     * 服务端 `limit` 上限 30；[PrimeTagIllustPage.next_offset] 为 null 即到底。
     */
    @GET("v1/prime/tags/{key}/illusts")
    suspend fun primeTagIllusts(
        @Path("key") key: String,
        @Query("offset") offset: Int,
        @Query("limit") limit: Int,
    ): PrimeTagIllustPage

    // ── account backup / Nana7mi ──
    // All account calls are signed with X-Shaft-Sign by the OkHttp interceptor in
    // ClientManager (the `/v1/account/` path match). Server: src/account.js.

    /** Backup (logged-in): mail a 6-digit code to verify ownership of [email]. */
    @POST("v1/account/bind/request")
    suspend fun bindRequest(@Body body: EmailReq): EmailAck

    /** Backup: with a valid code, store the encrypted [BindConfirmReq.account]. */
    @POST("v1/account/bind/confirm")
    suspend fun bindConfirm(@Body body: BindConfirmReq): BindConfirmAck

    @POST("v1/account/online")
    suspend fun bindOnline(@Body body: BindOnlineReq): BindOnlineAck

    /**
     * Fetch one Nana7mi response. [Nana7miResponse.expired] is authoritative for
     * whether the client must refresh it. This call only fetches data; token
     * refresh remains a client-side responsibility.
     */
    @POST("v1/account/nana7mi")
    suspend fun fetchNana7miRaw(
        @Body body: Nana7miRequest,
    ): Response<Nana7miResponse>

    /**
     * 这个号的借号额度用了多少（服务端 `src/account.js` 的两只桶）。
     *
     * 纯读接口：不借号、不动任何配额锚点——「打开用量页」不等于「开始用」，所以看多少次
     * 都不会消耗额度。和 [fetchNana7miRaw] 同一个签名信封（uid 由拦截器签，不是身份凭证）。
     */
    @POST("v1/account/nana7mi/quota")
    suspend fun fetchNana7miQuotaRaw(
        @Body body: Nana7miRequest,
    ): Response<Nana7miQuotaResponse>

    /**
     * 云翻译（服务端 `src/translate.js`）：把要翻的文本交给 PixShaft 服务器，服务器再转给它
     * 自己配置的 OpenAI 兼容上游。模型、提示词、思考参数全在服务端定，客户端**只发文本和
     * 目标语言**；按源文本字符数扣两只桶的额度，429 的形状和借号完全一样。
     * 和其它 `/v1/account/` 路由同一个签名信封（拦截器签请求体）。
     */
    @POST("v1/account/translate")
    suspend fun translateRaw(
        @Body body: TranslateRequest,
    ): Response<TranslateResponse>

    /** Explicit opt-in; old APKs continue using the JSON endpoint. */
    @retrofit2.SkipCallbackExecutor
    @retrofit2.http.Streaming
    @retrofit2.http.Headers("Accept: text/event-stream")
    @POST("v1/account/translate")
    fun translateStreamRaw(
        @Body body: TranslateRequest,
        @Header("X-Shaft-Translate-Trace") trace: String? = null,
    ): retrofit2.Call<okhttp3.ResponseBody>

    /** 云翻译额度只读接口，形状同 [fetchNana7miQuotaRaw]，多一个 `enabled`。 */
    @POST("v1/account/translate/quota")
    suspend fun fetchTranslateQuotaRaw(
        @Body body: Nana7miRequest,
    ): Response<TranslateQuotaResponse>

    /**
     * 换一条爱发电下单链接，链接里已经带好了「这是谁买的」。
     *
     * 服务端把 uid 和档位做成一段签过名的令牌，塞进下单页的 `custom_order_id` 和留言里；
     * 付完款，爱发电把订单推回服务端，令牌还在上面，套餐就自动发到这个 uid 头上。所以
     * 客户端**不要**自己去拼爱发电的 URL —— 那样拼出来的单子没有身份，只能落到后台等人工。
     *
     * [Nana7miCheckoutReq.client] 只送服务端不可能知道的东西（哪个版本、从哪个界面点进来的、
     * 什么机器）。档位、到期时间、两只桶的用量服务端自己有，它会在这一刻自己快照一份 ——
     * 让客户端复述一遍只会多出一份会漂的事实。
     */
    @POST("v1/account/afdian/checkout")
    suspend fun afdianCheckout(
        @Body body: Nana7miCheckoutReq,
    ): Response<Nana7miCheckoutResp>

    /**
     * 拿订单号认领一笔在爱发电站内直接下的单（没走本页链接、身上没有身份令牌的那种）。
     *
     * 服务端拿订单号去爱发电回查、确认已付款且没被用过，才发到这个 uid 头上。订单号本身就是
     * 凭证：27 位带时间戳，只能花一次。状态码分四种：404 没这单、409 已经有主/需要人工、
     * 503 爱发电或写库暂时不行（可重试）、400 格式不对。
     */
    @POST("v1/account/afdian/claim")
    suspend fun afdianClaim(
        @Body body: Nana7miClaimReq,
    ): Response<Nana7miClaimResp>

    /** Quarantine the exact borrowed refresh token that Pixiv rejected. */
    @POST("v1/account/nana7mi/invalid")
    suspend fun invalidateNana7mi(
        @Body body: Nana7miInvalidReq,
    ): Nana7miInvalidAck

    /**
     * Report one flow- or request-level event from the borrowed-search feature.
     *
     * Superseded by [reportNana7miSearchTelemetryBatch]; kept because installed
     * builds still have one-event-per-row telemetry queued locally.
     */
    @POST("v1/account/nana7mi/telemetry")
    suspend fun reportNana7miSearchTelemetry(
        @Body body: Nana7miSearchTelemetryReq,
    ): Nana7miSearchTelemetryAck

    /**
     * Report one buffered flush of borrowed-search events (server cap: 50 per
     * request). Idempotent per `eventId`, exactly like the single-event route,
     * so re-sending a whole batch after a network failure is safe.
     */
    @POST("v1/account/nana7mi/telemetry/batch")
    suspend fun reportNana7miSearchTelemetryBatch(
        @Body body: Nana7miSearchTelemetryBatchReq,
    ): Nana7miSearchTelemetryBatchAck

    /**
     * 借号搜索一级缓存（server: src/search-cache.js）。借号**之前**先问一声：命中就直接拿
     * 这一页渲染，不派发、不 renew、不打 Pixiv；未命中和从前一样。服务端不认识 Pixiv 的
     * 参数——[Nana7miSearchCacheLookupReq.key] 是客户端按「马上要发的那个请求」算出来的
     * sha256（[ceui.lisa.repo.Nana7miSearchCache]）。缓存跨用户共享：谁回填的谁都能命中；
     * miss 响应还会给一次性的 [Nana7miSearchCacheLookupResp.storeToken]，回填必须凭它。
     *
     * 任何异常（关掉了、坏了、限流）都长得和未命中一样：这条路永远不能让搜索失败。
     */
    @POST("v1/account/nana7mi/search-cache/lookup")
    suspend fun searchCacheLookupRaw(
        @Body body: Nana7miSearchCacheLookupReq,
    ): Response<Nana7miSearchCacheLookupResp>

    /** 借号搜索成功后凭 miss receipt 回填；之后任何人发相同请求都能命中。发完即忘。 */
    @POST("v1/account/nana7mi/search-cache/store")
    suspend fun searchCacheStoreRaw(
        @Body body: Nana7miSearchCacheStoreReq,
    ): Response<Nana7miSearchCacheStoreResp>

    /** Restore (login page): mail a code IF [email] has a backup ([RestoreRequestAck.found]). */
    @POST("v1/account/restore/request")
    suspend fun restoreRequest(@Body body: EmailReq): RestoreRequestAck

    /** Restore: with a valid code, hand back the stored account JSON. */
    @POST("v1/account/restore/confirm")
    suspend fun restoreConfirm(@Body body: RestoreConfirmReq): RestoreConfirmResp

    /** Backup: does this logged-in [UidReq.uid] already have a cloud backup? */
    @POST("v1/account/bind/status")
    suspend fun bindStatus(@Body body: UidReq): BindStatusResp

    /** Backup: unbind — delete this account's cloud backup. */
    @POST("v1/account/bind/delete")
    suspend fun bindDelete(@Body body: UidReq): BindDeleteAck
}

/**
 * 一页 Prime 标签插画。[illusts] 是快照里 pixiv 原样的作品对象，和实时搜索结果同一个模型；
 * [next_offset] 是下一页游标，null = 该标签已翻完。
 */
data class PrimeTagIllustPage(
    val key: String = "",
    val tag: Tag? = null,
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
    val illusts: List<Illust> = emptyList(),
    val next_offset: Int? = null,
)

/**
 * 服务端基础配置。业务开关可空：字段缺失表示「服务端没意见」，客户端保留上一次已知值。
 * 协议能力是例外：缺失就代表老服务端，必须按 false 处理，具体见 [nana7miRequestIdEnabled]。
 */
data class AppConfigResponse(
    val uid: Long? = null,
    val nana7miSearchEnabled: Boolean? = null,
    /**
     * 服务端是否接受 Nana7mi 请求体里的 `requestId`。缺失表示老服务端，客户端必须省略该字段，
     * 因为旧版 `/v1/account/nana7mi` 对 `{ uid }` 之外的字段会直接 400。
     */
    val nana7miRequestIdEnabled: Boolean? = null,
    /**
     * 这个 uid 的订阅档位。**只有带签名的请求才拿得到**（见 [Client] 里的签名拦截器）——
     * 这条路由本身是公开的，档位却是「谁在付钱、付到哪天」，不能让任何人拿 uid 就查。
     * 没带签名、未登录、或服务端查不到，都是 null；null 是「不知道」，不是「免费用户」。
     */
    val plan: Nana7miPlan? = null,
    /**
     * 这次冷启动要给他看的那条应用内推送；null = 没有（或没签名 / Lite / 服务端查失败）。
     * 服务端一次只给**一条**、且只给没回执过的，所以这里永远不会是一个列表。
     */
    val push: InAppPush? = null,
    /**
     * 服务端的云翻译代理开着（总开关 + 上游已配置）。缺失 = 老服务端，客户端保留上次答案
     * （默认关）——没被宣告过的路由不去打。
     */
    val cloudTranslateEnabled: Boolean? = null,
    /** 云翻译当前用的厂商和模型全名（「OpenAI · gpt-5.4-mini」），功能关着时为 null。纯展示。 */
    val cloudTranslateEngine: CloudTranslateEngine? = null,
    val serverTime: Long? = null,
)

/** 服务端 `translateEngine()`：可以给用户看的两样东西，没有 key、没有地址。 */
data class CloudTranslateEngine(
    val vendor: String? = null,
    val model: String? = null,
    /** 服务端配置的后备引擎；旧服务端不返回，实际响应只返回本次使用的引擎。 */
    val fallback: CloudTranslateEngine? = null,
) {
    /** 「OpenAI · gpt-5.4-mini」；两样都缺就 null，调用方别画一个空标签。 */
    val display: String?
        get() {
            val primary = name ?: return fallback?.name
            return fallback?.name?.let { "$primary → $it" } ?: primary
        }

    private val name: String?
        get() = listOfNotNull(vendor?.takeIf { it.isNotBlank() }, model?.takeIf { it.isNotBlank() })
            .takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * 后台写的一条应用内推送（pixshaft-api 控制台「应用内推送」tab）。
 *
 * 每个字段都可空：这是服务端随 `/v1/config` 捎带的，解析不能因为少个字段就把整份配置
 * 掀了。[id] 是回执和「本地已看」的键，缺了它这条推送就没法去重，直接不展示。
 */
data class InAppPush(
    val id: Long? = null,
    val title: String? = null,
    val body: String? = null,
    /** 按钮文案；null 用默认的「去看看」。只有 [actionUrl] 非空时才有按钮。 */
    val actionLabel: String? = null,
    /** `https://…`（能在 app 内处理的 pixiv 链接就在 app 内开，否则 Custom Tab）、`pixiv://…` 或 `shaftintent://…`。 */
    val actionUrl: String? = null,
    /** `paid` / `pro` / `max` / `all` —— 服务端已经按它筛过，客户端只是原样带着。 */
    val audience: String? = null,
    val startsAt: Long? = null,
    val endsAt: Long? = null,
)

data class InAppPushAckReq(val uid: Long, val id: Long)

data class InAppPushAckResp(val ok: Boolean = false, val first: Boolean? = null)

/**
 * 发一条已读回执。返回「是否已了结」：2xx 和任何 4xx 都算了结（400/404 重发多少次结果
 * 都一样），只有 5xx / 网络错误才返回 false 让调用方下次再补。永不抛（取消除外）。
 */
suspend fun PixshaftApi.acknowledgeInAppPush(uid: Long, id: Long): Boolean {
    if (uid <= 0L || id <= 0L) return true
    return try {
        val response = ackInAppPush(InAppPushAckReq(uid, id))
        response.isSuccessful || response.code() in 400..499
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Exception) {
        false
    }
}

/**
 * 服务端算额度时按的档位。
 *
 * 两组字段回答的是两个不同的问题，别混：
 *  - [key] / [multiplier] / [expiresAt] —— **按什么计量**，和额度接口里的 `max` 永远一致；
 *  - [owned] / [ownedExpiresAt] —— **他真的买了什么**。
 *
 * 试运营期间服务端会给所有人一个下限（`NANA7MI_PLAN_FLOOR`），这时没付过钱的人 [key]
 * 也是 `max`，[source] 为 `trial`。**付费页要高亮哪一档看 [owned]**，拿 [key] 去高亮
 * 会把一个没付钱的人显示成 Max 订户。
 *
 * 每个字段都可空：老服务端不带 = 没意见，照仓库既有约定处理，不能把 null 当成 free。
 */
data class Nana7miPlan(
    /** `free` / `pro` / `max` —— 按什么计量。 */
    val key: String? = null,
    val label: String? = null,
    val multiplier: Int? = null,
    /** 计量档位的到期时间；试运营给的档没有到期日（运营开关），这里是 null。 */
    val expiresAt: Long? = null,
    /** `free` / `subscription` / `trial` —— 这一档是怎么来的。 */
    val source: String? = null,
    /** 他真的买了什么。判断升降档、付费页高亮都看这个。 */
    val owned: String? = null,
    /** 他买的那一档的到期时间。 */
    val ownedExpiresAt: Long? = null,
) {
    /** 真金白银买过、且还在有效期内。试运营白给的不算。 */
    val isPaid: Boolean
        get() = owned != null && owned != PLAN_FREE

    /**
     * 侧边栏那颗徽章上印什么；免费、未知、以及**这个版本还不认识的新档位**都不印。
     *
     * 只认写死的两档而不是直接用服务端的 [label]：这串字要塞进侧边栏用户名旁边，服务端
     * 哪天给个长名字就会把用户名挤没。不认识的新档位宁可不显示 —— 少一颗徽章是小事，
     * 版式塌了是大事，而且「免费什么都不展示」本来就是默认行为。
     */
    val badgeLabel: String?
        get() = when (owned) {
            PLAN_PRO -> "PRO"
            PLAN_MAX -> "MAX"
            else -> null
        }

    /**
     * 他**买的那一档**叫什么、是几倍。UI 上凡是说「当前方案」的地方都用它。
     *
     * [key]/[label]/[multiplier] 说的是「按什么计量」，试运营期间服务端会把所有人抬上去，
     * 拿那组去写「当前方案」会告诉一个没付钱的人他买了 Max。
     *
     * 计量档位正好就是他买的那档时（也就是没有试运营抬高时），直接用服务端给的名字和倍率
     * —— 服务端才是权威，客户端不该自己猜。只有两者不一致时才落到本地这张表上，它是为了
     * 「试运营期间也要如实说出他买的是什么」而存在的，不是第二份真相。
     */
    val ownedDisplay: Pair<String, Int>?
        get() {
            if (owned != null && owned == key && label != null && multiplier != null) {
                return label to multiplier
            }
            return when (owned) {
                PLAN_FREE -> "Free" to 1
                PLAN_PRO -> "Pro" to 5
                PLAN_MAX -> "Max" to 20
                else -> null
            }
        }
}

const val PLAN_FREE = "free"
const val PLAN_PRO = "pro"
const val PLAN_MAX = "max"

data class EmailReq(val email: String)

data class EmailAck(val ok: Boolean = false)

data class BindConfirmReq(
    val email: String,
    val code: String,
    val account: AccountResponse,
)

/**
 * @param premiumAgeMs 距离本机上一次**真去 pixiv 读**这个号的会员状态过了多久（毫秒），
 *   读不出来就省略。服务端拿它减自己的钟，算出这份 is_premium 是什么时候看到的。
 *
 *   传时长而不是时间戳，是因为设备的墙钟不可信（同样的理由让服务端的额度窗口也不敢用
 *   客户端时间）：一台钟快一天的手机报个绝对时间，会显得比任何证据都新。时长走开机后的
 *   单调钟，改时区、NTP 校正、用户手动改表都不影响它。
 *
 *   服务端只在**这个号被借号方发现不是会员之后**才看这一项：那之后只有比那次拒绝更晚的
 *   观测才能把号放回池子，光把同一份上报重发一遍不行——「登录时冻结的 is_premium 一遍遍
 *   重报」正是这个 bug 的成因。老版本不带这个字段＝未知，不会因此掉出池子，只是被拒过的
 *   号要等下一次真读到会员状态才回得来。
 */
data class BindOnlineReq(
    val uid: Long,
    val account: AccountResponse,
    val premiumAgeMs: Long? = null,
)

data class BindConfirmAck(
    val ok: Boolean = false,
    val uid: Long? = null,
)

data class BindOnlineAck(
    val ok: Boolean = false,
    val uid: Long? = null,
)

data class Nana7miRequest(
    val uid: Long,
    /** 与缓存 lookup / 请求遥测共用；重试或缓存回退不会再开一笔搜索费用。 */
    val requestId: String? = null,
)

/**
 * @param months 只是下单页上月数选择器的预填值，不是承诺 —— 买家在爱发电那边还能改，
 *   最后发几个月按订单回来的实际月数算。
 */
data class Nana7miCheckoutReq(
    val uid: Long,
    val plan: String,
    val months: Int = 1,
    val client: Nana7miCheckoutClient? = null,
)

/** 服务端不可能自己知道的那几样。全部可空：少一样也不该让下单失败。 */
data class Nana7miCheckoutClient(
    val appVersion: String? = null,
    val versionCode: Int? = null,
    /** 从哪个界面点进来的，例如 `usage_page`。用来分辨「主动来买」和「被额度挡住才来买」。 */
    val entry: String? = null,
    val device: String? = null,
    val locale: String? = null,
    val android: String? = null,
)

data class Nana7miCheckoutResp(
    val url: String? = null,
    val plan: String? = null,
    val months: Int? = null,
    val uid: Long? = null,
)

data class Nana7miClaimReq(
    val uid: Long,
    val outTradeNo: String,
)

data class Nana7miClaimResp(
    val ok: Boolean? = null,
    val error: String? = null,
    val plan: Nana7miPlan? = null,
    val alreadyFulfilled: Boolean? = null,
)

/**
 * 认领的结果，每一种都对应一句不同的话；只有 [Retry] 值得让用户再按一次。
 */
sealed class Nana7miClaimResult {
    /** 到账了（或者这单本来就是他的、早就到账了）。[plan] 是服务端此刻认的档位。 */
    data class Success(val plan: Nana7miPlan?) : Nana7miClaimResult()

    /** 404：爱发电不认识这个单号。 */
    data object NotFound : Nana7miClaimResult()

    /** 409 already_claimed：这单已经归别人 / 已经花掉了。 */
    data object Taken : Nana7miClaimResult()

    /** 409 grant_refused：服务端知道这单是谁的但拒绝自动发（降级、¥0 单）—— 要人工。 */
    data object Refused : Nana7miClaimResult()

    /** 400 bad_order_no：这串东西根本不像个单号。 */
    data object BadNumber : Nana7miClaimResult()

    /** 400 order_not_paid：单号是对的，但这笔的钱还没付完。 */
    data object NotPaid : Nana7miClaimResult()

    /**
     * 400 order_not_a_plan：单号是对的，但这笔买的不是 Pro / Max 方案。
     *
     * 爱发电主页上「赞助」入口就贴在方案旁边，买错的人手里那个号是**真的**，
     * 让他去检查有没有抄错只会让他一遍遍重抄同一串数字。
     */
    data object NotAPlan : Nana7miClaimResult()

    /** 429 / 5xx / 网络：不是他的问题，可以再试。 */
    data class Retry(val cause: Exception? = null) : Nana7miClaimResult()
}

suspend fun PixshaftApi.claimAfdianOrder(uid: Long, outTradeNo: String): Nana7miClaimResult {
    if (uid <= 0L) return Nana7miClaimResult.BadNumber
    return try {
        val response = afdianClaim(Nana7miClaimReq(uid, outTradeNo))
        if (response.isSuccessful) {
            return Nana7miClaimResult.Success(response.body()?.plan)
        }
        // 错误体是 JSON `{ error }`；拿不到就只按状态码分。
        val error = runCatching {
            response.errorBody()?.string()?.let { raw ->
                JsonParser.parseString(raw).asJsonObject.get("error")?.asString
            }
        }.getOrNull()
        when (response.code()) {
            // 400 里混着三件完全不同的事，只有第一件是「你手里那个号有问题」。另外两件
            // 他抄的号是对的 —— 一笔还没付完，一笔买的根本不是方案。都翻译成「格式不对」，
            // 人只会对着一串正确的数字反复重抄。
            400 -> when (error) {
                "order_not_paid" -> Nana7miClaimResult.NotPaid
                "order_not_a_plan" -> Nana7miClaimResult.NotAPlan
                else -> Nana7miClaimResult.BadNumber
            }
            // 404 只有带着 `order_not_found` 才是「爱发电不认识这个单号」；别的 404（路由没
            // 挂、边缘代理答的）是服务端的事，让用户「稍后再试」而不是去怀疑自己抄错了。
            404 -> if (error == "order_not_found") Nana7miClaimResult.NotFound else Nana7miClaimResult.Retry()
            409 -> if (error == "grant_refused") Nana7miClaimResult.Refused else Nana7miClaimResult.Taken
            // 剩下的全归「不是他的问题」：429 是每 uid 6 次/分钟的闸，连按几下就到；5xx 和
            // 边缘代理可能冒出来的任何状态码都是我们这边的事。只有 400 才该让他去改输入。
            else -> Nana7miClaimResult.Retry()
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        Nana7miClaimResult.Retry(e)
    }
}

data class Nana7miInvalidReq(
    val uid: Long,
    val refreshTokenHash: String,
)

data class Nana7miInvalidAck(
    val ok: Boolean = false,
    val uid: Long? = null,
    val disabled: Boolean = false,
)

data class Nana7miSearchTelemetryReq(
    val eventId: String,
    val flowId: String,
    val eventType: String,
    val occurredAt: Long,
    val requesterUid: Long,
    val borrowedUid: Long?,
    val query: String,
    val contentType: String,
    val page: String,
    val route: String,
    val outcome: String,
    val flowOutcome: String?,
    val reason: String?,
    val errorType: String?,
    val httpStatus: Int?,
    val durationMs: Long?,
    val appVersion: String,
    val appChannel: String,
)

data class Nana7miSearchTelemetryAck(
    val ok: Boolean = false,
    val eventId: String? = null,
    val duplicate: Boolean = false,
)

data class Nana7miSearchTelemetryBatchReq(
    val events: List<Nana7miSearchTelemetryReq>,
)

/**
 * [accepted] events passed server-side validation, of which [stored] were new and
 * [duplicate] had already been recorded; [rejected] were dropped as malformed and
 * will never be accepted, so the client must not resend them.
 */
data class Nana7miSearchTelemetryBatchAck(
    val ok: Boolean = false,
    val accepted: Int = 0,
    val stored: Int = 0,
    val duplicate: Int = 0,
    val rejected: Int = 0,
)

/**
 * [maxAgeMs]：这次调用能容忍多旧的一页；服务端还有一个更硬的上限（默认 24h）。
 * [page]：`first` / `next` —— 命中时服务端按它计费（整次搜索 / 一次翻页），和真借号一样。
 */
data class Nana7miSearchCacheLookupReq(
    val uid: Long,
    val kind: String,
    val key: String,
    val maxAgeMs: Long,
    val page: String,
    /** 本页 lookup、可能发生的官方回退、以及 request 遥测共用的幂等 ID。 */
    val requestId: String? = null,
)

/**
 * [page] 是当初存进去的那份搜索响应原文（`{ illusts|novels: [...], next_url }`），
 * 用 Gson 按对应的列表模型解析即可；`hit == false` 时为 null。
 */
data class Nana7miSearchCacheLookupResp(
    val hit: Boolean? = null,
    val page: JsonElement? = null,
    val storedAt: Long? = null,
    val ageMs: Long? = null,
    /** miss 后短时有效的一次性回填凭证；旧服务端不返回时客户端直接跳过回填。 */
    val storeToken: String? = null,
    /** 命中已计费之后的额度（同 /v1/account/nana7mi 返回的 quotas）；额度满了整个响应是 429。 */
    val quotas: List<Nana7miQuotaWindow>? = null,
    val plan: Nana7miPlan? = null,
)

data class Nana7miSearchCacheStoreReq(
    val uid: Long,
    val kind: String,
    val key: String,
    val page: JsonElement,
    val storeToken: String,
)

/** 被拒（`stored == false`）也是 200：没有任何值得重试的东西，[reason] 只是给日志看。 */
data class Nana7miSearchCacheStoreResp(
    val stored: Boolean? = null,
    val reason: String? = null,
)

/**
 * 一只配额桶的当前状态。字段名和服务端 JSON 一致。
 *
 * 两只桶都是**固定桶**（服务端 `NANA7MI_BORROW_WINDOWS`）：到了 [resetsAt] 整份额度归零，
 * 不是滑动窗口的「回一格」。所以文案该写「X 后重置」，不能写「X 后 +1 次」。
 */
data class Nana7miQuotaWindow(
    /** `session`（5 小时）/ `weekly`（7 天）。新桶由服务端加，客户端照 [windowHours] 渲染即可。 */
    val key: String = "",
    /** 借号被这只桶拦下时 429 里的 `scope`，用来把拒绝原因对回具体某一行。 */
    val scope: String = "",
    val windowHours: Int = 0,
    /**
     * 已用「次数」，**是小数**：新搜索算 1 次，往下翻一页算 0.2 次（服务端
     * `NANA7MI_COST_PAGE`）。所以这里不能声明成 Int —— Gson 拿 1.2 去填 Int 会直接抛，
     * 整页变成「加载失败」。
     */
    val used: Double = 0.0,
    val max: Int = 0,
    /** null = 这只桶没启用（服务端设了 0），**不是** 0。0 才是「用完了」。 */
    val remaining: Double? = null,
    /** 整份额度回满的时刻（epoch ms）。null = 当前没有开着的桶，没有可倒计时的东西。 */
    val resetsAt: Long? = null,
)

/**
 * [serverTime] 是服务端此刻的时间。倒计时必须拿它当基准算，不能用设备时钟——两边差几分钟，
 * 「还剩多久重置」就会明显不对，而用户手机的时间我们管不了。
 */
data class Nana7miQuotaResponse(
    val uid: Long? = null,
    val serverTime: Long? = null,
    /** 和 [AppConfigResponse.plan] 同一个东西。这条路由本来就要签名，所以一直给得到。 */
    val plan: Nana7miPlan? = null,
    val quotas: List<Nana7miQuotaWindow> = emptyList(),
)

sealed class Nana7miQuotaResult {
    /** [serverTime] 已经解析好，调用方直接拿它减 [Nana7miQuotaWindow.resetsAt]。 */
    data class Success(
        val quotas: List<Nana7miQuotaWindow>,
        val serverTime: Long,
        /** 服务端此刻认的档位。比冷启动缓存的那份新，用量页优先用它。 */
        val plan: Nana7miPlan? = null,
        /** 只有云翻译额度会带：当前翻译引擎，用量页把它写进分组标题。 */
        val engine: CloudTranslateEngine? = null,
    ) : Nana7miQuotaResult()

    data class HttpFailure(val status: Int) : Nana7miQuotaResult()
    data class NetworkFailure(val cause: java.io.IOException) : Nana7miQuotaResult()
    data class InvalidResponse(val cause: Exception? = null) : Nana7miQuotaResult()

    /** 只有云翻译额度会回这个：服务端明确说功能关着，用量页不该画一组永远用不上的桶。 */
    data object Disabled : Nana7miQuotaResult()
}

/**
 * 安全版用量查询：取消照常向上抛，网络/HTTP/脏响应都变成显式结果值——和 [fetchNana7mi]
 * 一个路子。这是个纯展示接口，任何一种失败都只该让页面显示「加载失败」，不该崩。
 */
suspend fun PixshaftApi.fetchNana7miQuota(uid: Long): Nana7miQuotaResult {
    if (uid <= 0L) return Nana7miQuotaResult.InvalidResponse()
    return try {
        val response = fetchNana7miQuotaRaw(Nana7miRequest(uid))
        if (!response.isSuccessful) return Nana7miQuotaResult.HttpFailure(response.code())
        val body = response.body() ?: return Nana7miQuotaResult.InvalidResponse()
        if (body.quotas.isEmpty()) return Nana7miQuotaResult.InvalidResponse()
        // serverTime 缺失（老服务端）时退回本机时钟：倒计时可能偏一点，但整页还能看，
        // 比因为少一个字段就把页面判成加载失败强。
        Nana7miQuotaResult.Success(
            body.quotas,
            body.serverTime ?: System.currentTimeMillis(),
            body.plan,
        )
    } catch (ce: CancellationException) {
        throw ce
    } catch (io: java.io.IOException) {
        Nana7miQuotaResult.NetworkFailure(io)
    } catch (e: Exception) {
        Nana7miQuotaResult.InvalidResponse(e)
    }
}

// ──────────────────────────── 云翻译 ────────────────────────────

/** 服务端只读 `uid` / `texts` / `lang`，多余字段一律忽略，所以这里也只有这三个。 */
data class TranslateRequest(
    val uid: Long,
    val texts: List<String>,
    /** 服务端白名单：zh-CN / zh-TW / en / ja / ko / ru / tr，即 app 自己的 UI 语言。 */
    val lang: String,
)

data class TranslateResponse(
    val uid: Long? = null,
    /** 与请求 `texts` 等长、同序；服务端已经校验过长度，对不上会直接回 502。 */
    val translations: List<String>? = null,
    val engine: CloudTranslateEngine? = null,
    val serverTime: Long? = null,
    val plan: Nana7miPlan? = null,
    /** 扣完这一笔之后的读数，和 [Nana7miQuotaWindow] 同形，只是单位是字符。 */
    val quotas: List<Nana7miQuotaWindow> = emptyList(),
)

data class TranslateQuotaResponse(
    val uid: Long? = null,
    val serverTime: Long? = null,
    val enabled: Boolean? = null,
    val engine: CloudTranslateEngine? = null,
    val plan: Nana7miPlan? = null,
    val quotas: List<Nana7miQuotaWindow> = emptyList(),
)

sealed class TranslateResult {
    data class Success(
        val translations: List<String>,
        val quotas: List<Nana7miQuotaWindow>,
        val serverTime: Long,
        val plan: Nana7miPlan? = null,
        val engine: CloudTranslateEngine? = null,
    ) : TranslateResult()

    /** 429：既可能是两只额度桶（[Nana7miResult.RateLimited.isQuota]），也可能是每分钟限流。 */
    data class RateLimited(val limit: Nana7miResult.RateLimited) : TranslateResult()

    /** 503 `translate_disabled`：服务端把功能关了。下一次 `/v1/config` 会把开关同步回来。 */
    data object Disabled : TranslateResult()

    /** 其它非 2xx。[error] 是服务端的 snake_case 错误码（`upstream_timeout` 之类），拿来给用户看。 */
    data class HttpFailure(val status: Int, val error: String? = null) : TranslateResult()
    data class NetworkFailure(val cause: java.io.IOException) : TranslateResult()
    data class InvalidResponse(val cause: Exception? = null) : TranslateResult()
}

private data class ErrorBody(val error: String? = null)

/**
 * 安全版云翻译：取消照常上抛，其余失败都变成结果值。[texts] 为空直接回空成功，不打网络。
 */
suspend fun PixshaftApi.translateTexts(uid: Long, texts: List<String>, lang: String): TranslateResult {
    if (uid <= 0L) return TranslateResult.InvalidResponse()
    if (texts.isEmpty()) return TranslateResult.Success(emptyList(), emptyList(), System.currentTimeMillis())
    return try {
        val response = translateRaw(TranslateRequest(uid, texts, lang))
        decodeTranslationResponse(response, texts.size)
    } catch (ce: CancellationException) {
        throw ce
    } catch (io: java.io.IOException) {
        TranslateResult.NetworkFailure(io)
    } catch (e: Exception) {
        TranslateResult.InvalidResponse(e)
    }
}

internal fun decodeTranslationResponse(response: Response<TranslateResponse>, expected: Int): TranslateResult {
    return when {
        response.isSuccessful -> {
            val body = response.body() ?: return TranslateResult.InvalidResponse()
            val translations = body.translations
            if (translations == null || translations.size != expected) {
                return TranslateResult.InvalidResponse()
            }
            // Gson 会把 JSON null 原样塞进 List<String>，下游 isNotEmpty() 就是 NPE。服务端
            // 已把 null 写成 ""，这里再兜一层，别让一个坏元素把整批翻译变成崩溃。
            TranslateResult.Success(
                translations.map { (it as String?) ?: "" },
                body.quotas,
                body.serverTime ?: System.currentTimeMillis(),
                body.plan,
                body.engine,
            )
        }
        response.code() == 429 -> TranslateResult.RateLimited(parseRateLimited(response))
        else -> {
            val error = parseErrorCode(response)
            if (response.code() == 503 && error == "translate_disabled") {
                TranslateResult.Disabled
            } else {
                TranslateResult.HttpFailure(response.code(), error)
            }
        }
    }
}

private fun parseErrorCode(response: Response<*>): String? = try {
    response.errorBody()?.string()?.takeIf { it.isNotBlank() }?.let {
        rateLimitGson.fromJson(it, ErrorBody::class.java)?.error
    }
} catch (ce: CancellationException) {
    throw ce
} catch (e: Exception) {
    null
}

/**
 * 云翻译额度，只读。和 [fetchNana7miQuota] 同一个结果类型，用量页两组桶共用一套渲染；
 * 服务端说 `enabled:false` 时回 [Nana7miQuotaResult.Disabled]，那一组不画。
 */
suspend fun PixshaftApi.fetchTranslateQuota(uid: Long): Nana7miQuotaResult {
    if (uid <= 0L) return Nana7miQuotaResult.InvalidResponse()
    return try {
        val response = fetchTranslateQuotaRaw(Nana7miRequest(uid))
        if (!response.isSuccessful) return Nana7miQuotaResult.HttpFailure(response.code())
        val body = response.body() ?: return Nana7miQuotaResult.InvalidResponse()
        if (body.enabled == false) return Nana7miQuotaResult.Disabled
        if (body.quotas.isEmpty()) return Nana7miQuotaResult.InvalidResponse()
        Nana7miQuotaResult.Success(
            body.quotas,
            body.serverTime ?: System.currentTimeMillis(),
            body.plan,
            body.engine,
        )
    } catch (ce: CancellationException) {
        throw ce
    } catch (io: java.io.IOException) {
        Nana7miQuotaResult.NetworkFailure(io)
    } catch (e: Exception) {
        Nana7miQuotaResult.InvalidResponse(e)
    }
}

sealed class Nana7miCheckoutResult {
    /** [url] 直接丢给浏览器就行，身份已经烤在里面了。 */
    data class Success(val url: String) : Nana7miCheckoutResult()

    /** 服务端还没配好爱发电（503），或者这一档暂时不卖。 */
    data object Unavailable : Nana7miCheckoutResult()
    data class Failure(val cause: Exception? = null) : Nana7miCheckoutResult()
}

/**
 * 要一条带身份的下单链接。
 *
 * 失败一律降级成显式结果值：这条路径上任何一种失败都只该让按钮回弹并提示一句，绝不能
 * 把用户从「我正想付钱」的那一刻踢进一个崩溃。
 *
 * **拿不到链接时不要退回去打开爱发电主页**。那种单子回来是没有身份的，只能落到后台
 * 等人工认领 —— 用户付了钱却看不到额度变化，比让他等一会儿再试糟得多。
 */
suspend fun PixshaftApi.requestAfdianCheckout(
    uid: Long,
    plan: String,
    months: Int = 1,
    client: Nana7miCheckoutClient? = null,
): Nana7miCheckoutResult {
    if (uid <= 0L) return Nana7miCheckoutResult.Failure()
    return try {
        val response = afdianCheckout(Nana7miCheckoutReq(uid, plan, months, client))
        if (response.code() == 503) return Nana7miCheckoutResult.Unavailable
        if (!response.isSuccessful) return Nana7miCheckoutResult.Failure()
        val url = response.body()?.url
        if (url.isNullOrBlank()) Nana7miCheckoutResult.Failure()
        else Nana7miCheckoutResult.Success(url)
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        Nana7miCheckoutResult.Failure(e)
    }
}

data class Nana7miResponse(
    val uid: Long? = null,
    val account: AccountResponse? = null,
    val updatedAt: Long? = null,
    val expiresAt: Long? = null,
    val expired: Boolean? = null,
)

/** Validated data handed to business code; all wire-level nullable fields are resolved. */
data class Nana7miPayload(
    val uid: Long,
    val account: AccountResponse,
    val updatedAt: Long,
    val expiresAt: Long,
    val expired: Boolean,
)

sealed class Nana7miResult {
    data class Success(val value: Nana7miPayload) : Nana7miResult()

    /** This app flavor does not participate in Nana7mi account borrowing. No request was made. */
    data object DisabledForLite : Nana7miResult()

    /** The server accepted the borrow request but currently has no account to dispatch. */
    data object NoAccount : Nana7miResult()

    /**
     * The borrowed account is not (or is no longer) a Pixiv premium member, so it cannot serve the
     * premium-only sorts this whole feature exists for. Search must fall back to the preview
     * endpoint, and [account] gets reported online so the server reclassifies the row and stops
     * dispatching it (`saveOnline` pauses dispatch for any report whose `is_premium` is not true).
     */
    data class NotPremium(val uid: Long, val account: AccountResponse) : Nana7miResult()
    /**
     * 服务端拒了这次借号。两种来源，靠 [scope] 区分，客户端必须分得开：
     *  - `uid_5h` / `uid_weekly` —— 配额桶满了，[resetsAt] 是整份额度回满的时刻，最长几小时到几天，
     *    值得告诉用户；
     *  - `global` / `ip` / `uid` —— 每分钟限流器，最多 60 秒，等一下就好，不该打扰用户。
     *
     * 全部可空：老服务端（或限流器那条路径）不带这些字段，缺了就当不知道，不能瞎猜。
     */
    data class RateLimited(
        val retryAfterSeconds: Long?,
        val scope: String? = null,
        val resetsAt: Long? = null,
        val serverTime: Long? = null,
    ) : Nana7miResult() {

        /** 是不是配额桶满（而不是每分钟限流）。只有这种才值得提示用户。 */
        val isQuota: Boolean get() = scope == "uid_5h" || scope == "uid_weekly"
    }
    data class HttpFailure(val status: Int) : Nana7miResult()
    data object InvalidRequest : Nana7miResult()
    data class NetworkFailure(val cause: java.io.IOException) : Nana7miResult()
    data class InvalidResponse(val cause: Exception? = null) : Nana7miResult()
}

/**
 * Safe Nana7mi call for business code. Cancellation still propagates, while
 * network, HTTP and malformed-payload failures become explicit result values.
 */
/** 429 的响应体（服务端 `denyRate` 和配额拒绝共用这个形状，字段各有多寡）。 */
private data class Nana7miRateLimitBody(
    val scope: String? = null,
    val retryAfterSeconds: Long? = null,
    val serverTime: Long? = null,
    val quotas: List<Nana7miQuotaWindow>? = null,
)

private val rateLimitGson by lazy { com.google.gson.Gson() }

/**
 * 429 的细节在 body 里，不在头里：`Retry-After` 只有秒数，说不出是哪一档卡住的，也说不出
 * 什么时候回满。解析失败就退回只读头 —— 提示不出来是小事，把一次搜索搞崩是大事。
 */
private fun parseRateLimited(response: Response<*>): Nana7miResult.RateLimited {
    val headerRetry = response.headers()["Retry-After"]?.toLongOrNull()
    val body = try {
        response.errorBody()?.string()?.takeIf { it.isNotBlank() }?.let {
            rateLimitGson.fromJson(it, Nana7miRateLimitBody::class.java)
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        null
    } ?: return Nana7miResult.RateLimited(headerRetry)

    // resetsAt 取被点名那一档的 —— 提示要说的是「这一档什么时候回满」，不是随便哪一档。
    val resetsAt = body.quotas?.firstOrNull { it.scope == body.scope }?.resetsAt
    return Nana7miResult.RateLimited(
        retryAfterSeconds = body.retryAfterSeconds ?: headerRetry,
        scope = body.scope,
        resetsAt = resetsAt,
        serverTime = body.serverTime,
    )
}

suspend fun PixshaftApi.fetchNana7mi(uid: Long, requestId: String? = null): Nana7miResult {
    if (uid <= 0L) return Nana7miResult.InvalidRequest
    return try {
        val response = fetchNana7miRaw(Nana7miRequest(uid, requestId))
        when (response.code()) {
            404 -> Nana7miResult.NoAccount
            429 -> parseRateLimited(response)
            else -> {
                if (!response.isSuccessful) return Nana7miResult.HttpFailure(response.code())
                val body = response.body() ?: return Nana7miResult.InvalidResponse()
                val responseUid = body.uid ?: return Nana7miResult.InvalidResponse()
                val account = body.account ?: return Nana7miResult.InvalidResponse()
                val updatedAt = body.updatedAt ?: return Nana7miResult.InvalidResponse()
                val expiresAt = body.expiresAt ?: return Nana7miResult.InvalidResponse()
                val expired = body.expired ?: return Nana7miResult.InvalidResponse()
                if (
                    responseUid <= 0L ||
                    account.user?.id != responseUid ||
                    account.access_token.isNullOrBlank() ||
                    account.refresh_token.isNullOrBlank() ||
                    updatedAt <= 0L ||
                    expiresAt < updatedAt
                ) {
                    return Nana7miResult.InvalidResponse()
                }
                // 借号的全部意义就是会员专属 sort，非会员号发出去必然被 pixiv 拒。服务端的派发池
                // 本来就只收 is_premium = 1，所以这里是纵深防御（也能自愈分类错乱的行）。
                // 只认显式的 false：字段缺失(null)当作未知，照旧交给 pixiv 裁决——绝不能凭 null
                // 把一个号踢出池子。
                if (account.user?.is_premium == false) {
                    return Nana7miResult.NotPremium(responseUid, account)
                }
                Nana7miResult.Success(
                    Nana7miPayload(responseUid, account, updatedAt, expiresAt, expired),
                )
            }
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (io: java.io.IOException) {
        Nana7miResult.NetworkFailure(io)
    } catch (e: Exception) {
        Nana7miResult.InvalidResponse(e)
    }
}

data class RestoreRequestAck(
    val ok: Boolean = false,
    val found: Boolean = false,
)

data class RestoreConfirmReq(
    val email: String,
    val code: String,
)

data class RestoreConfirmResp(
    val uid: Long? = null,
    val account: AccountResponse? = null,
    val updatedAt: Long = 0L,
)

data class UidReq(val uid: Long)

data class BindStatusResp(
    val bound: Boolean = false,
    val emailMasked: String? = null,
    val updatedAt: Long = 0L,
)

data class BindDeleteAck(
    val ok: Boolean = false,
    val deleted: Int = 0,
)

data class SyncPrefBody(val enabled: Boolean)

data class SyncPrefAck(
    val uid: Long = 0L,
    val enabled: Boolean = true,
    val changes: Int = 0,
    val updatedAt: Long = 0L,
)

data class HistoryReportBody(
    val items: List<HistoryReportItem>,
)

data class HistoryReportItem(
    val target_type: String,
    val target_id: Long,
    val payload: JsonElement?,
    // 真实浏览时刻(ms)。服务端只在它比已存行更新时才覆盖/置顶,所以回填存量本地
    // 历史不会把云端顺序刷成「刚刚看过」(#989)。null → 服务端打点(旧行为)。
    val viewed_at: Long? = null,
)

data class HistoryReportAck(
    val upserted: Int = 0,
    val total: Int = 0,
)

data class HistoryListResponse(
    val uid: Long = 0L,
    val items: List<HistoryEntry> = emptyList(),
    val nextCursor: String? = null,
)

data class HistoryEntry(
    val target_type: String = "",
    val target_id: Long = 0L,
    val viewed_at: Long = 0L,
    val view_count: Int = 0,
    val title: String? = null,
    val name: String? = null,
    val thumb_url: String? = null,
    val payload: JsonElement? = null,
)

data class HistoryDeleteAck(
    val ok: Boolean = false,
    val deleted: Int = 0,
)
