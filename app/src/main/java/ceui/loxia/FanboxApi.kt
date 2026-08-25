package ceui.loxia

import ceui.lisa.utils.Common
import com.google.gson.Gson
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * pixiv FANBOX(api.fanbox.cc)。
 *
 * 认证走 **cookie**,不是 OAuth —— FANBOX 和 app-api 是两套体系,主 app 的 Bearer 在这里无效。
 * 实测只要带上 WebView 里那份 `FANBOXSESSID` 就是完整登录态(`post.listHome`、
 * `plan.listSupporting`、`bell.countUnread` 全 200),所以 [FanboxHeaderInterceptor] 直接从
 * `CookieManager` 取,不另存一份。用户没在 WebView 里登录过 FANBOX 时 [postListHome] 返回 401。
 *
 * 另一个必需项是 `Origin: https://www.fanbox.cc` —— 不带这个头一律 400,和登录态无关。
 *
 * 正文(`post.info`)**不在这个接口里** —— 那个端点被 Cloudflare 单独挡了非浏览器客户端,
 * OkHttp 一律 403,只能走 [FanboxWebBridge]。这里放的都是 OkHttp 能直连的部分。
 */
interface FanboxApi {

    /** 首页「投稿」流。需要登录态;未登录 401。 */
    @GET("post.listHome")
    suspend fun postListHome(@Query("limit") limit: Int = 10): FanboxPostListResponse

    /** 翻页:服务端在 [FanboxPostList.nextUrl] 里给的是 api.fanbox.cc 绝对 URL,照着打即可。 */
    @GET
    suspend fun postListHomeByUrl(@Url url: String): FanboxPostListResponse

    /**
     * 首页「为您推荐的创作者」。未登录也能拿到(内容会变成通用推荐)。
     * 响应里**没有翻页游标**,就是单页。
     */
    @GET("creator.listRecommended")
    suspend fun creatorListRecommended(@Query("limit") limit: Int = 10): FanboxCreatorListResponse

    /**
     * 帖子元数据。响应是 `body.post`,post 对象里**没有 body 字段** ——
     * 正文要另外走 [FanboxWebBridge] 打 post.info。这里是正文取不到时的兜底。
     */
    @GET("post.get")
    suspend fun postGet(@Query("postId") postId: String): FanboxPostDetailResponse

    /** 帖子评论。详情页唯一还能拿到的「内容」—— post.info 恒 403,正文取不到。 */
    @GET("post.getComments")
    suspend fun postGetComments(
        @Query("postId") postId: String,
        @Query("limit") limit: Int = 20,
    ): FanboxCommentResponse

    /** 创作者的赞助方案。网页版付费墙那个「方案列表」按钮背后就是它。 */
    @GET("plan.listCreator")
    suspend fun planListCreator(@Query("creatorId") creatorId: String): FanboxPlanListResponse
}

/**
 * post.info —— 唯一带正文的接口,而且**只能从 WebView 里发**(见 [FanboxWebBridge])。
 * 响应外壳和 post.get 一样是 `body.post`,只是 post 对象里多了 `type` / `body`,
 * 封面字段也换成了平的 `coverImageUrl`(不是列表接口那个 `cover.url`)。
 *
 * [bridge] 从 [ServicesProvider.fanboxWebBridge] 拿(`context.appServices().fanboxWebBridge`),
 * 显式传进来而不是内部抓全局,是为了让这条链路不依赖任何进程级单例。
 *
 * 返回 null = 被 CF 挡了 / 没登录 / 超时 / 解析不出来,调用方应退回 [FanboxApi.postGet]。
 */
suspend fun fetchFanboxPostInfo(bridge: FanboxWebBridge, postId: String): FanboxPost? {
    val raw = bridge.get("https://api.fanbox.cc/post.info?postId=$postId") ?: return null
    return runCatching {
        fanboxGson.fromJson(raw, FanboxPostDetailResponse::class.java).body?.post
    }.getOrElse {
        Common.showLog("fetchFanboxPostInfo 解析失败 postId=$postId: $it")
        null
    }
}

private val fanboxGson by lazy { Gson() }

data class FanboxPostDetailResponse(
    val body: FanboxPostWrapper?
)

data class FanboxPostWrapper(
    val post: FanboxPost?
)

data class FanboxCommentResponse(
    val body: FanboxCommentBody?
)

data class FanboxCommentBody(
    val viewMode: String?,
    val commentList: FanboxCommentList?,
)

data class FanboxCommentList(
    val items: List<FanboxComment>?,
    val nextUrl: String?,
)

/** [replies] 是楼中楼;服务端已经嵌好,不用再请求一次。 */
data class FanboxComment(
    val id: String,
    val body: String?,
    val createdDatetime: String?,
    val likeCount: Int,
    val isLiked: Boolean,
    val user: FanboxUser?,
    val replies: List<FanboxComment>?,
)

data class FanboxPlanListResponse(
    val body: FanboxPlanList?
)

data class FanboxPlanList(
    val plans: List<FanboxPlan>?
)

data class FanboxPlan(
    val id: String,
    val title: String?,
    val fee: Int,
    val description: String?,
    val coverImageUrl: String?,
    val creatorId: String?,
    val hasAdultContent: Boolean,
)

data class FanboxPostListResponse(
    val body: FanboxPostList?
)

data class FanboxPostList(
    val items: List<FanboxPost>?,
    val nextUrl: String?,
)

/**
 * 一条投稿。**列表接口和 post.get 都不返回正文**,[excerpt] 常常也是空串 —— 付费墙后的帖子
 * ([isRestricted] 为 true)服务端只给到封面和标题。
 *
 * [type] / [body] 只有走 [FanboxWebBridge] 打 post.info 才有;其余入口一律为 null。
 */
data class FanboxPost(
    val id: String,
    val title: String?,
    val feeRequired: Int,
    val publishedDatetime: String?,
    val tags: List<String>?,
    val likeCount: Int,
    val commentCount: Int,
    val isRestricted: Boolean,
    val user: FanboxUser?,
    val creatorId: String?,
    val hasAdultContent: Boolean,
    val cover: FanboxCover?,
    val excerpt: String?,
    val type: String?,
    val body: FanboxPostBody?,
    val coverImageUrl: String?,
) {
    /**
     * 封面。列表接口给的是结构化的 [cover],post.info 给的是平的 [coverImageUrl],
     * 同一个 model 两种来源,取值统一走这里。
     */
    val coverUrl: String get() = cover?.url?.takeIf { it.isNotEmpty() } ?: coverImageUrl.orEmpty()
}

/**
 * 正文。字段按 [FanboxPost.type] 分家(schema 抄自网页 bundle 里那份 TypeBox 定义):
 *
 * - `article`:[blocks] + 四张 map,块里只存 id,资源要去 map 里查。
 * - `image`:[text] + [images];`file`:[text] + [files];`text`:只有 [text]。
 * - `video`:[text] + video(没做,原生播不了 YouTube/Vimeo 那些外链)。
 * - `entry`:[html],是整段富文本 HTML,没有结构化块。
 *
 * 受限帖子(未赞助)服务端整块给 null —— 有 post 无 body 是正常态,不是解析失败。
 */
data class FanboxPostBody(
    val text: String?,
    val html: String?,
    val images: List<FanboxImage>?,
    val files: List<FanboxFile>?,
    val blocks: List<FanboxBlock>?,
    val imageMap: Map<String, FanboxImage>?,
    val fileMap: Map<String, FanboxFile>?,
    val embedMap: Map<String, FanboxEmbed>?,
    val urlEmbedMap: Map<String, FanboxUrlEmbed>?,
)

/**
 * 正文块。[type] 取值 `p` / `header` / `image` / `file` / `embed` / `url_embed`,
 * 每种只填自己那一个字段。
 */
data class FanboxBlock(
    val type: String?,
    val text: String?,
    val imageId: String?,
    val fileId: String?,
    val embedId: String?,
    val urlEmbedId: String?,
    val links: List<FanboxBlockLink>?,
    val styles: List<FanboxBlockStyle>?,
)

/** 段落里的一段装饰。服务端目前只发 `bold` 一种。 */
data class FanboxBlockStyle(
    val type: String?,
    val offset: Int,
    val length: Int,
)

/** 段落里的一段超链接。offset/length 是 UTF-16 码元下标,可以直接喂 Spannable。 */
data class FanboxBlockLink(
    val offset: Int,
    val length: Int,
    val url: String?,
)

data class FanboxImage(
    val id: String?,
    val extension: String?,
    val width: Int,
    val height: Int,
    val originalUrl: String?,
    val thumbnailUrl: String?,
)

data class FanboxFile(
    val id: String?,
    val name: String?,
    val extension: String?,
    val size: Long,
    val url: String?,
)

/** 旧式站外嵌入(twitter / youtube 之类),只给服务商和内容 id,链接要自己拼。 */
data class FanboxEmbed(
    val id: String?,
    val serviceProvider: String?,
    val contentId: String?,
)

/**
 * 新式嵌入。[type] 为 `default` 时才有 [url];`fanbox.post` / `fanbox.creator`
 * 指向站内,`html` / `html.card` 是一段 HTML —— 后三种这里只当作「打不开的卡片」处理。
 */
data class FanboxUrlEmbed(
    val id: String?,
    val type: String?,
    val url: String?,
    val host: String?,
)

data class FanboxUser(
    val userId: String?,
    val name: String?,
    val iconUrl: String?,
)

data class FanboxCover(
    val type: String?,
    val url: String?,
)

data class FanboxCreatorListResponse(
    val body: FanboxCreatorList?
)

data class FanboxCreatorList(
    val creators: List<FanboxCreator>?
)

/**
 * 推荐位上的创作者。[profileItems] 是主页展示图,网页版拿它铺卡片顶部那一排缩略图;
 * [category] 服务端经常给 null,不要拿它当必有字段。
 */
data class FanboxCreator(
    val user: FanboxUser?,
    val creatorId: String?,
    val description: String?,
    val hasAdultContent: Boolean,
    val coverImageUrl: String?,
    val profileItems: List<FanboxProfileItem>?,
    val isFollowed: Boolean,
    val isSupported: Boolean,
    val hasPublishedPost: Boolean,
    val category: String?,
)

data class FanboxProfileItem(
    val id: String?,
    val type: String?,
    val imageUrl: String?,
    val thumbnailUrl: String?,
)
