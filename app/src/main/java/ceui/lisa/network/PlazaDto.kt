package ceui.lisa.network

import com.google.gson.annotations.SerializedName

/**
 * 1:1 对齐 shaft-plaza-api server。字段命名故意保留 snake_case
 * (Gson 默认 FIELD strategy)，避免一层 mapping。
 *
 * 服务端 spec 见 docs `shaft-plaza-api-android.md` §3-§7。
 */

// ── 响应 (POST 单条 / GET 单帖详情 / feed item) ──────────────────────

/**
 * 单条 plaza post。`refs` 三个 kind 字段始终存在,空时是空数组。
 * `meta` 是 events DB 的快照,可能 null —— 客户端按需再去 Pixiv 取。
 *
 * `liked_by_viewer` 只在带 viewer sig 的请求里有(server 缺这字段表示"没问")。
 */
data class PlazaPost(
    val id: Long,
    val uid: Long,
    val display_name: String?,
    val text: String,
    val ts: Long,
    val refs: PlazaPostRefs,
    val like_count: Int = 0,
    val comment_count: Int = 0,
    val liked_by_viewer: Boolean? = null,
    /** 仅 GET /users/:uid/likes 的 item 带:你点赞这条帖子的 ts(ms)。其它场景 null。 */
    val liked_at: Long? = null,
)

data class PlazaPostRefs(
    val illust: List<PlazaIllustRef> = emptyList(),
    val novel: List<PlazaNovelRef> = emptyList(),
    val user: List<PlazaUserRef> = emptyList(),
)

data class PlazaIllustRef(
    val id: Long,
    val meta: PlazaIllustMeta?,
)

data class PlazaIllustMeta(
    val target_id: Long,
    val title: String?,
    val user_id: Long?,
    val user_name: String?,
    val thumb_url: String?,
)

data class PlazaNovelRef(
    val id: Long,
    val meta: PlazaNovelMeta?,
)

data class PlazaNovelMeta(
    val target_id: Long,
    val title: String?,
    val user_id: Long?,
    val user_name: String?,
)

data class PlazaUserRef(
    val id: Long,
    val meta: PlazaUserMeta?,
)

data class PlazaUserMeta(
    val target_id: Long,
    val name: String?,
    val account: String?,
    val avatar_url: String?,
)

// ── GET /api/v1/plaza/posts ── 最新 feed ─────────────────────────

data class PlazaFeedResponse(
    val items: List<PlazaPost>,
    val next_before: Long?,
)

// ── GET /api/v1/plaza/users/:uid/posts ───────────────────────────

data class PlazaUserPostsResponse(
    val uid: Long,
    val total: Int,
    val items: List<PlazaPost>,
    val next_before: Long?,
)

// ── DELETE /api/v1/plaza/posts/:id ───────────────────────────────

data class PlazaDeleteResponse(
    val ok: Boolean,
)

// ── 点赞 ────────────────────────────────────────────────────────
//
// POST   /api/v1/plaza/posts/:id/like   (added: true 表示新写入,false=已经赞过)
// DELETE /api/v1/plaza/posts/:id/like   (removed: true 表示真删了,false=本来就没赞)
// 两端都返回最新 like_count,客户端不用自己维护。

data class PlazaLikeResponse(
    val ok: Boolean,
    val added: Boolean? = null,
    val removed: Boolean? = null,
    val like_count: Int,
)

// ── 评论 ────────────────────────────────────────────────────────

data class PlazaComment(
    val id: Long,
    val post_id: Long,
    val uid: Long,
    val display_name: String?,
    val text: String,
    val ts: Long,
)

data class PlazaCommentsResponse(
    val post_id: Long,
    val total: Int,
    val items: List<PlazaComment>,
    val next_before: Long?,
)

// ── 我的点赞列表 ───────────────────────────────────────────────
//
// GET /api/v1/plaza/users/:uid/likes
// cursor `next_before` 是 **like_id**(不是 post.id),clients 翻页要把这个值
// 原样回传到下一次的 ?before=,直接传 post.id 会拿到错的页。
//
// 每个 item 的 shape 跟普通 PlazaPost 一致,额外带 `liked_at`(你点赞这条
// 帖子的时间戳)。

data class PlazaLikesResponse(
    val uid: Long,
    val total: Int,
    val items: List<PlazaPost>,
    val next_before: Long?,
)

// ── 错误响应 ────────────────────────────────────────────────────

/**
 * 4xx/5xx 响应都是 `{error: "<code>", ...optional}`。常见 code:
 *   - bad_sig:canonical body 拼错或 secret 错
 *   - ts_skew:本地时钟漂移 >30s
 *   - text_too_long:正文 >500 code points
 *   - too_many_refs_per_kind / too_many_refs_total:引用太多
 *   - rate_limited:命中速率限制 (scope=ip/uid),retryAfterSeconds 给秒数
 *   - empty_text / bad_text_chars
 */
data class PlazaErrorBody(
    val error: String,
    val detail: String? = null,
    val limit: Int? = null,
    val scope: String? = null,
    @SerializedName("retryAfterSeconds")
    val retryAfterSeconds: Long? = null,
)

/**
 * 写入/查询统一结果。Ok 携带成功 payload,Err 把 HTTP 状态 + server `error` 字段
 * (如 bad_sig / ts_skew / rate_limited) + 解析后的 body 都暴露给 UI 层做 i18n 映射。
 */
sealed class PlazaResult<out T> {
    data class Ok<T>(val value: T) : PlazaResult<T>()
    data class Err(
        val httpStatus: Int,
        val code: String,
        val body: PlazaErrorBody?,
    ) : PlazaResult<Nothing>()
}
