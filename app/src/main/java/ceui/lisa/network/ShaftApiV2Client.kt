package ceui.lisa.network

import ceui.lisa.BuildConfig
import com.google.gson.Gson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber

/**
 * 统一的 shaft-api-v2 客户端单例。
 *
 * 包括:
 *   - Retrofit / OkHttp 基建
 *   - 高层入口 (trending / events / plaza)
 *   - plaza 写请求的 HMAC 签名 + canonical wire body 拼装(spec 见
 *     docs/shaft-plaza-api-android.md §1)
 *   - plaza posts 内存 cache (详情页 fallback,见 [cachedPlazaPost])
 *   - plaza 事件总线 ([plazaPostsCreated] / [plazaPostsDeleted] SharedFlow,
 *     跨 ViewModel 同步无需 setFragmentResult / LocalBroadcast)
 */
object ShaftApiV2Client {

    // Same server as ShaftEventsClient — read endpoints (trending / health) ride
    // the SHAFT_EVENTS_BASE_URL gradle property so custom builds pointing at a
    // private server work without two separate overrides.
    @JvmField val BASE_URL: String = run {
        val raw = BuildConfig.SHAFT_EVENTS_BASE_URL
        if (raw.endsWith('/')) raw else "$raw/"
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor { Timber.tag("ShaftApiV2").i(it) }
                .apply {
                    level = if (BuildConfig.IS_DEBUG_MODE)
                        HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.BASIC
                }
        )
        .build()

    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val service: ShaftApiV2 = retrofit.create(ShaftApiV2::class.java)

    // ── Plaza state ───────────────────────────────────────────────────────────

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val gson = Gson()

    /**
     * 进程级事件总线 —— 任何路径(feed / detail / 分享深链)成功发了帖,所有
     * 活的 PlazaViewModel 立即收到并 prepend 到自己 feed 顶,无需手动 reload。
     * 跟 chat ShaftChatGateway.incoming 同一套 push-based 模式。
     *
     * - replay=0:新创建的 ViewModel 不会收到上次的事件 (下次刷新会包含,避免 stale)
     * - extraBufferCapacity=8:连发突发时 suspending emit 缓冲
     */
    private val _plazaPostsCreated = MutableSharedFlow<PlazaPost>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    val plazaPostsCreated: SharedFlow<PlazaPost> = _plazaPostsCreated.asSharedFlow()

    /** 删帖事件总线。任何路径成功删一条 / server 404 都广播,VM 同步 filter。 */
    private val _plazaPostsDeleted = MutableSharedFlow<Long>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    val plazaPostsDeleted: SharedFlow<Long> = _plazaPostsDeleted.asSharedFlow()

    /**
     * 帖子互动状态更新事件总线 —— 详情页 toggle like / 发评论后广播,feed
     * adapter 监听更新对应 item 的 like_count / comment_count / liked_by_viewer
     * 跨页面同步。Replay=0,纯增量事件。
     */
    private val _plazaPostsUpdated = MutableSharedFlow<PlazaPost>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    val plazaPostsUpdated: SharedFlow<PlazaPost> = _plazaPostsUpdated.asSharedFlow()

    /** Internal:供 VM 在 like/comment 成功后调用,广播 + 同时刷新 plazaPostCache。 */
    fun broadcastPostUpdated(post: PlazaPost) {
        cachePlazaPost(post)
        _plazaPostsUpdated.tryEmit(post)
    }

    /**
     * 进程级内存缓存。list/get/create 自动填充,详情页 / 深链可以先从这里
     * 拿快照避免空白闪烁。process death 自然清空,单进程量级有限,不做 LRU 限制。
     */
    private val plazaPostCache = ConcurrentHashMap<Long, PlazaPost>()

    fun cachedPlazaPost(id: Long): PlazaPost? = plazaPostCache[id]

    private fun cachePlazaPost(post: PlazaPost) { plazaPostCache[post.id] = post }
    private fun cachePlazaPosts(posts: List<PlazaPost>) { posts.forEach(::cachePlazaPost) }

    // ── Plaza high-level entrypoints ─────────────────────────────────────────

    /**
     * 给 GET 请求生成 viewer 三元组。viewer sig 5 分钟有效期(read skew),
     * 同一个 viewerUid 同一分钟内多次调用 ts 都一样所以 sig 可复用,但成本
     * 极低没必要 cache。viewerUid <= 0 表示未登录,返回 null 不带 viewer 参数,
     * server 不会附 liked_by_viewer 字段(行为 = 匿名读)。
     */
    private fun viewerTriple(viewerUid: Long): Triple<String, String, String>? {
        if (viewerUid <= 0L) return null
        val secret = BuildConfig.SHAFT_EVENTS_HMAC
        val uidStr = viewerUid.toString()
        val tsStr = System.currentTimeMillis().toString()
        val sig = PlazaSig.signViewer(secret, uidStr, tsStr)
        return Triple(uidStr, tsStr, sig)
    }

    suspend fun listPlazaFeed(
        limit: Int = 20,
        before: Long? = null,
        viewerUid: Long = 0L,
    ): PlazaResult<PlazaFeedResponse> {
        val v = viewerTriple(viewerUid)
        val r = runCatchingPlaza {
            service.listPlazaPosts(limit, before, v?.first, v?.second, v?.third)
        }
        if (r is PlazaResult.Ok) cachePlazaPosts(r.value.items)
        return r
    }

    suspend fun getPlazaPost(id: Long, viewerUid: Long = 0L): PlazaResult<PlazaPost> {
        val v = viewerTriple(viewerUid)
        val r = runCatchingPlaza {
            service.getPlazaPost(id, v?.first, v?.second, v?.third)
        }
        if (r is PlazaResult.Ok) cachePlazaPost(r.value)
        return r
    }

    suspend fun listUserPlazaPosts(
        uid: Long,
        limit: Int = 20,
        before: Long? = null,
        viewerUid: Long = 0L,
    ): PlazaResult<PlazaUserPostsResponse> {
        val v = viewerTriple(viewerUid)
        val r = runCatchingPlaza {
            service.listUserPlazaPosts(uid, limit, before, v?.first, v?.second, v?.third)
        }
        if (r is PlazaResult.Ok) cachePlazaPosts(r.value.items)
        return r
    }

    /**
     * "我的点赞" 列表。HMAC 鉴权,server 强制 path uid == sig uid,只能拉自己的。
     * `before` cursor 是 like_id —— 翻页务必把上一页 next_before 原样回传,
     * 直接传 post.id 会拿到错的页。
     */
    suspend fun listMyPlazaLikes(
        uid: Long,
        limit: Int = 20,
        before: Long? = null,
    ): PlazaResult<PlazaLikesResponse> {
        if (uid <= 0L) return PlazaResult.Err(0, "login_required", null)
        val secret = BuildConfig.SHAFT_EVENTS_HMAC
        val uidStr = uid.toString()
        val tsStr = System.currentTimeMillis().toString()
        val sig = PlazaSig.signLikesRead(secret, uidStr, tsStr)
        val r = runCatchingPlaza {
            service.listMyPlazaLikes(uid, ts = tsStr, sig = sig, limit = limit, before = before)
        }
        if (r is PlazaResult.Ok) cachePlazaPosts(r.value.items)
        return r
    }

    /**
     * 发广场帖。
     *
     * @param illustMetas 已知 illust 的完整 IllustsBean 快照(可选),用来让 server
     *   在入库时直接 seed illust_meta —— GET 出去就是 enriched 的,不用等用户走
     *   /events/batch 才回填。**不参与 sig**(advisory hint),所以老 client 不带也
     *   能发帖。同理 novelMetas / userMetas 也是可选 hints。
     */
    suspend fun createPlazaPost(
        uid: Long,
        text: String,
        illust: List<Long> = emptyList(),
        novel: List<Long> = emptyList(),
        user: List<Long> = emptyList(),
        illustMetas: Map<Long, Any> = emptyMap(),
        novelMetas: Map<Long, Any> = emptyMap(),
        userMetas: Map<Long, Any> = emptyMap(),
    ): PlazaResult<PlazaPost> {
        val secret = BuildConfig.SHAFT_EVENTS_HMAC
        val uidStr = uid.toString()
        val tsStr = System.currentTimeMillis().toString()
        val sig = PlazaSig.signPost(secret, uidStr, tsStr, text, illust, novel, user)

        // 严格拼 wire body。canonical body 跟 sig 是绑定的,不能过 Gson(key 顺序
        // 由 hash table 决定 → bodyHash mismatch → 401 bad_sig)。
        val canonicalBody = PlazaSig.canonicalPostBody(text, illust, novel, user)
        val wireBody = buildString {
            append('{')
            append("\"uid\":").append(PlazaSig.jsonEscape(uidStr))
            append(",\"ts\":").append(PlazaSig.jsonEscape(tsStr))
            append(",\"sig\":").append(PlazaSig.jsonEscape(sig))
            // canonical body 形如 `{"text":...,"refs":{...}}`,去掉外层 {} 嵌进来
            append(',')
            append(canonicalBody.substring(1, canonicalBody.length - 1))
            // ref_metas 是 server 的 advisory hint —— 走 Gson 序列化 OK,因为
            // server 验签只看 canonical body(text + refs),不读 ref_metas。
            if (illustMetas.isNotEmpty() || novelMetas.isNotEmpty() || userMetas.isNotEmpty()) {
                append(",\"ref_metas\":")
                append(refMetasJson(illustMetas, novelMetas, userMetas))
            }
            append('}')
        }
        val result = runCatchingPlaza { service.createPlazaPost(wireBody.toRequestBody(jsonMediaType)) }
        if (result is PlazaResult.Ok) {
            cachePlazaPost(result.value)
            _plazaPostsCreated.tryEmit(result.value)
        }
        return result
    }

    private fun refMetasJson(
        illust: Map<Long, Any>,
        novel: Map<Long, Any>,
        user: Map<Long, Any>,
    ): String {
        val map = linkedMapOf<String, Map<String, Any>>()
        if (illust.isNotEmpty()) map["illust"] = illust.mapKeys { it.key.toString() }
        if (novel.isNotEmpty())  map["novel"]  = novel.mapKeys  { it.key.toString() }
        if (user.isNotEmpty())   map["user"]   = user.mapKeys   { it.key.toString() }
        return gson.toJson(map)
    }

    // ── Likes ────────────────────────────────────────────────────────────

    /**
     * 点赞 / 取消点赞。server 是幂等的(重复 like / 重复 unlike 都 OK),返回的
     * like_count 是权威值,客户端不要自己 +1 -1 漂移。
     */
    suspend fun likePlazaPost(uid: Long, postId: Long): PlazaResult<PlazaLikeResponse> {
        val secret = BuildConfig.SHAFT_EVENTS_HMAC
        val uidStr = uid.toString()
        val tsStr = System.currentTimeMillis().toString()
        val sig = PlazaSig.signLike(secret, uidStr, tsStr, postId)
        val body = "{\"uid\":\"$uidStr\",\"ts\":\"$tsStr\",\"sig\":\"$sig\"}"
        return runCatchingPlaza { service.likePlazaPost(postId, body.toRequestBody(jsonMediaType)) }
    }

    suspend fun unlikePlazaPost(uid: Long, postId: Long): PlazaResult<PlazaLikeResponse> {
        val secret = BuildConfig.SHAFT_EVENTS_HMAC
        val uidStr = uid.toString()
        val tsStr = System.currentTimeMillis().toString()
        val sig = PlazaSig.signUnlike(secret, uidStr, tsStr, postId)
        val body = "{\"uid\":\"$uidStr\",\"ts\":\"$tsStr\",\"sig\":\"$sig\"}"
        return runCatchingPlaza { service.unlikePlazaPost(postId, body.toRequestBody(jsonMediaType)) }
    }

    // ── Comments ─────────────────────────────────────────────────────────

    suspend fun listPlazaComments(
        postId: Long,
        limit: Int = 20,
        before: Long? = null,
    ): PlazaResult<PlazaCommentsResponse> {
        return runCatchingPlaza { service.listPlazaComments(postId, limit, before) }
    }

    suspend fun createPlazaComment(
        uid: Long,
        postId: Long,
        text: String,
    ): PlazaResult<PlazaComment> {
        val secret = BuildConfig.SHAFT_EVENTS_HMAC
        val uidStr = uid.toString()
        val tsStr = System.currentTimeMillis().toString()
        val sig = PlazaSig.signComment(secret, uidStr, tsStr, postId, text)

        // canonical body 只有 text 一个字段,sig 绑了它的 hash。wire body
        // 形如 {"uid":..,"ts":..,"sig":..,"text":..}。
        val wireBody = buildString {
            append('{')
            append("\"uid\":").append(PlazaSig.jsonEscape(uidStr))
            append(",\"ts\":").append(PlazaSig.jsonEscape(tsStr))
            append(",\"sig\":").append(PlazaSig.jsonEscape(sig))
            append(",\"text\":").append(PlazaSig.jsonEscape(text))
            append('}')
        }
        return runCatchingPlaza {
            service.createPlazaComment(postId, wireBody.toRequestBody(jsonMediaType))
        }
    }

    suspend fun deletePlazaComment(uid: Long, commentId: Long): PlazaResult<PlazaDeleteResponse> {
        val secret = BuildConfig.SHAFT_EVENTS_HMAC
        val uidStr = uid.toString()
        val tsStr = System.currentTimeMillis().toString()
        val sig = PlazaSig.signCommentDelete(secret, uidStr, tsStr, commentId)
        val body = "{\"uid\":\"$uidStr\",\"ts\":\"$tsStr\",\"sig\":\"$sig\"}"
        return runCatchingPlaza {
            service.deletePlazaComment(commentId, body.toRequestBody(jsonMediaType))
        }
    }

    suspend fun deletePlazaPost(uid: Long, postId: Long): PlazaResult<PlazaDeleteResponse> {
        val secret = BuildConfig.SHAFT_EVENTS_HMAC
        val uidStr = uid.toString()
        val tsStr = System.currentTimeMillis().toString()
        val sig = PlazaSig.signDelete(secret, uidStr, tsStr, postId)
        val body = "{\"uid\":\"$uidStr\",\"ts\":\"$tsStr\",\"sig\":\"$sig\"}"
        val r = runCatchingPlaza { service.deletePlazaPost(postId, body.toRequestBody(jsonMediaType)) }
        // server 200 / 404 (not_found 防 enumeration) 都视为"已经不在了"。
        val isGone = r is PlazaResult.Ok || (r is PlazaResult.Err && r.code == "not_found")
        if (isGone) {
            plazaPostCache.remove(postId)
            _plazaPostsDeleted.tryEmit(postId)
        }
        return r
    }

    private suspend fun <T> runCatchingPlaza(block: suspend () -> T): PlazaResult<T> {
        return try {
            PlazaResult.Ok(block())
        } catch (e: HttpException) {
            val raw = try { e.response()?.errorBody()?.string() } catch (_: Throwable) { null }
            val parsed = raw?.let {
                try { gson.fromJson(it, PlazaErrorBody::class.java) } catch (_: Throwable) { null }
            }
            Timber.tag("Plaza").w("http %d %s err=%s detail=%s", e.code(), e.message(), parsed?.error, parsed?.detail)
            PlazaResult.Err(e.code(), parsed?.error ?: "http_${e.code()}", parsed)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // ViewModelScope cancel 时正常向上冒,不能转成 Err —— 否则
            // Fragment 已 detach 还会 trySend Toast。
            throw ce
        } catch (t: Throwable) {
            Timber.tag("Plaza").w(t, "network failure")
            PlazaResult.Err(0, "network", null)
        }
    }
}
