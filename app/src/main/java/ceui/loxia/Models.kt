package ceui.loxia

import android.os.Parcelable
import android.text.TextUtils
import ceui.lisa.models.ModelObject
import ceui.lisa.models.NovelBean
import ceui.lisa.models.NovelDetail.NovelMarkerBean
import ceui.lisa.models.ObjectSpec
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize
import java.io.Serializable


data class AccountResponse(
    val access_token: String? = null,
    val expires_in: Int? = null,
    val refresh_token: String? = null,
    val scope: String? = null,
    val token_type: String? = null,
    val user: User? = null
) : Serializable

data class IllustResponse(
    val illusts: List<Illust> = listOf(),
    val next_url: String? = null
) : Serializable, KListShow<Illust> {
    override val displayList: List<Illust> get() = illusts
    override val nextPageUrl: String? get() = next_url
}

data class HomeIllustResponse(
    val illusts: List<Illust> = listOf(),
    val ranking_illusts: List<Illust> = listOf(),
    val next_url: String? = null
) : Serializable, KListShow<Illust> {
    override val displayList: List<Illust> get() = ranking_illusts + illusts
    override val nextPageUrl: String? get() = next_url
}

object ObjectType {
    const val ILLUST = "illust"
    const val MANGA = "manga"
    const val GIF = "ugoira"
    const val NOVEL = "novel"
}

object ConstantUser {
    const val pixiv = 11L //pixiv事務局
    const val pxv_sensei = 17391869L //pixiv描き方-sensei
    const val mangapixiv = 14792128L //MANGA pixiv
    const val pixivision = 12848282L //pixivision
    const val pxv_sketch = 15241365L //pixiv Sketch
    const val pixiv3 = 1085317L // pixiv MARKET事務局
    const val fanbox = 20390859L // pixivFANBOX公式

    const val CeuiLiSA = 31660292L
    const val VOLUNTEER_USER_1 = 89989626L // 千年孤狼
    const val VOLUNTEER_USER_2 = 81263065L // 虎鲸

    val officialUsers = listOf(
        pixiv,
        pxv_sensei,
        mangapixiv,
        pixivision,
        pxv_sketch,
        pixiv3,
        fanbox,
    )

    val volunteerUsers = listOf(
        CeuiLiSA,
        VOLUNTEER_USER_1,
        VOLUNTEER_USER_2,
    )
}

data class WebIllust(
    val alt: String? = null,
    val bookmarkData: Any? = null,
    val createDate: String? = null,
    val description: String? = null,
    val height: Int,
    val id: Long = 0L,
    val illustType: Int? = null,
    val isBookmarkable: Boolean? = null,
    val images: ImageUrls? = null,
    val isMasked: Boolean? = null,
    val isUnlisted: Boolean? = null,
    val pageCount: Int = 0,
    val aiType: Int = 0,
    val profileImageUrl: String? = null,
    val restrict: Int? = null,
    val sl: Int? = null,
    val title: String? = null,
    val updateDate: String? = null,
    val url: String? = null,
    val url_w: String? = null,
    val url_sm: String? = null,
    val url_s: String? = null,
    val urls: Map<String, String?>? = null,
    val userId: Long = 0L,
    val userName: String? = null,
    val width: Int,
    val xRestrict: Int? = null,
) : Serializable {

    fun toIllust(): Illust {
        return Illust(
            id = id,
            caption = alt,
            create_date = createDate,
            height = height,
            illust_ai_type = aiType,
            image_urls = ImageUrls(
                original = url,
                large = url_w,
                medium = url_sm,
                square_medium = url_s,
            ),
            is_bookmarked = isBookmarkable != true,
            is_muted = isUnlisted,
            meta_pages = null,
            meta_single_page = null,
            page_count = pageCount,
            restrict = restrict,
            sanity_level = sl,
            series = null,
            title = title,
            tools = null,
            total_bookmarks = null,
            total_view = null,
            type = null,
            user = User(
                account = "@${userId}",
                id = userId
            ),
            visible = isMasked != true,
            width = width,
            x_restrict = xRestrict
        )
    }
}

data class Illust(
    val caption: String? = null,
    val create_date: String? = null,
    val height: Int = 0,
    val id: Long,
    val image_urls: ImageUrls? = null,
    val is_bookmarked: Boolean? = null,
    val illust_ai_type: Int = 0,
    val is_muted: Boolean? = null,
    val meta_pages: List<MetaPage>? = null,
    val meta_single_page: MetaSinglePage? = null,
    val page_count: Int = 0,
    val restrict: Int? = null,
    val sanity_level: Int? = null,
    val series: Series? = null,
    val tags: List<Tag>? = null,
    val title: String? = null,
    val tools: List<String>? = null,
    val total_bookmarks: Int? = null,
    val total_view: Int? = null,
    val type: String? = null,
    val user: User? = null,
    val visible: Boolean? = null,
    val width: Int = 0,
    val x_restrict: Int? = null,
) : Serializable, ModelObject {

    fun isAuthurExist(): Boolean {
        return user?.exist() == true
    }

    override val objectUniqueId: Long
        get() = id
    override val objectType: Int
        get() = ObjectSpec.Illust

    fun displayCreateDate(): String {
        return DateParse.displayCreateDate(create_date)
    }

    fun isGif(): Boolean {
        return TextUtils.equals(type, ObjectType.GIF)
    }

    fun isManga(): Boolean {
        return TextUtils.equals(type, ObjectType.MANGA)
    }

    fun isDisabled(): Boolean {
        return user?.id == 0L
    }

    fun maxUrl(): String? {
        if (page_count > 0) {
            if (page_count == 1) {
                return meta_single_page?.original_image_url
            } else {
                return meta_pages?.getOrNull(0)?.image_urls?.original
            }
        } else {
            return null
        }
    }
}

data class MetaPage(
    val image_urls: ImageUrls? = null
) : Serializable

data class MetaSinglePage(
    val original_image_url: String? = null
) : Serializable


data class WebTag(
    val tag: String? = null,
    val tag_translation: String? = null,
    val cnt: Int? = null,
    val ids: List<Long>? = null,
) : Serializable {
    val tagName: String? get() {
        return tag ?: tag_translation
    }
}

data class Tag(
    val name: String? = null,
    val translated_name: String? = null
) : Serializable {
    val tagName: String? get() {
        return name ?: translated_name
    }
}

data class FrequentTag(
    val tag: String? = null,
    val tag_translation: String? = null,
) : Serializable

/**
 * 画师全量作品标签 —— `/ajax/user/{id}/illusts/tags?all=1&lang=zh` 的 body 项,
 * 网页版画师页「高级搜索」面板的数据源。
 *
 * 两个反直觉的点:
 * - **服务端不按 [cnt] 排序**(实测原始顺序既不是频次也不是五十音),要按热度展示必须自己排;
 * - [tag_translation] 只有带 `lang` 参数时才有值,且并非每个 tag 都有译名(实测约六成),
 *   没有译名的条目应直接把 `#原tag` 当主标题显示,而不是留一行空白。
 */
data class UserWorkTag(
    val tag: String? = null,
    val tag_translation: String? = null,
    val tag_yomigana: String? = null,
    val cnt: Int = 0,
) : Serializable

data class WebUserDetail(
    val userId: String? = null,
    val name: String? = null,
    val image: String? = null,
    val imageBig: String? = null,
    val premium: Boolean? = null,
    val isFollowed: Boolean? = null,
    val isMypixiv: Boolean? = null,
    val isBlocking: Boolean? = null,
    val followedBack: Boolean? = null,
    val canSendMessage: Boolean? = null,
    val background: WebUserBackground? = null,
    val following: Int? = null,
    val mypixivCount: Int? = null,
    val comment: String? = null,
    val commentHtml: String? = null,
    val webpage: String? = null,
    val social: Map<String, WebSocialLink>? = null,
    val region: WebPrivacyField? = null,
    val gender: WebPrivacyField? = null,
    val workspace: Map<String, String?>? = null,
    val official: Boolean? = null,
    val publisher: Boolean? = null,
) : Serializable

data class WebUserBackground(
    val url: String? = null,
    val isPrivate: Boolean? = null,
) : Serializable

data class WebSocialLink(
    val url: String? = null,
) : Serializable

data class WebPrivacyField(
    val name: String? = null,
    val privacyLevel: String? = null,
) : Serializable

object UserGender {

    const val UNKNOWN = 0
    const val MALE = 1
    const val FEMALE = 2

    fun random(): Int {
        return listOf(UNKNOWN, MALE, FEMALE).random()
    }
}

data class User(
    val account: String? = null,
    val id: Long = 0L,
    val user_id: Long = 0L,
    val is_followed: Boolean? = null,
    val name: String? = null,
    val pixiv_id: String? = null,
    val profile_image_urls: ImageUrls? = null,
    val is_mail_authorized: Boolean? = null,
    val is_premium: Boolean? = null,
    val mail_address: String? = null,
    val gender: Int = UserGender.MALE,
    val require_policy_agreement: Boolean? = null,
    val x_restrict: Int? = null,
    val comment: String? = null,
    // user/detail v2 新增:对方是否屏蔽了当前用户的访问 / 是否开启「接受约稿(request)」
    val is_access_blocking_user: Boolean? = null,
    val is_accept_request: Boolean? = null,
) : Serializable, ModelObject {
    override val objectUniqueId: Long
        get() = id
    override val objectType: Int
        get() = ObjectSpec.KUser

    fun isOfficial(): Boolean {
        return ConstantUser.officialUsers.contains(id)
    }

    fun isVolunteer(): Boolean {
        return ConstantUser.volunteerUsers.contains(id)
    }


    fun hasGender(): Boolean {
        return gender != UserGender.UNKNOWN
    }

    fun exist(): Boolean {
        return name?.isNotEmpty() == true || account?.isNotEmpty() == true
    }
}

data class ImageUrls(
    val url: String? = null,
    val large: String? = null,
    val medium: String? = null,
    val original: String? = null,
    val small: String? = null,
    val square_medium: String? = null,
    val px_16x16: String? = null,
    val px_170x170: String? = null,
    val px_50x50: String? = null,
) : Serializable {

    fun findMaxSizeUrl(): String? {
        if (url != null) {
            return url
        }

        if (original != null) {
            return original
        }

        if (large != null) {
            return large
        }

        if (medium != null) {
            return medium
        }

        if (square_medium != null) {
            return square_medium
        }

        if (small != null) {
            return small
        }

        if (px_170x170 != null) {
            return px_170x170
        }

        if (px_50x50 != null) {
            return px_50x50
        }

        if (px_16x16 != null) {
            return px_16x16
        }

        return null
    }
}

data class ErrorResponse(
    val error: Error? = null
) : Serializable

data class Error(
    val message: String? = null,
    val reason: String? = null,
    val user_message: String? = null,
    val user_message_details: UserMessageDetails? = null
) : Serializable {

    fun displayMessage(): String? {
        if (message?.isNotEmpty() == true) {
            return message
        }

        if (reason?.isNotEmpty() == true) {
            return reason
        }

        if (user_message?.isNotEmpty() == true) {
            return user_message
        }

        return null
    }
}

class UserMessageDetails : Serializable

data class Profile(
    val address_id: Int? = null,
    val background_image_url: String? = null,
    val birth: String? = null,
    val birth_day: String? = null,
    val birth_year: Int? = null,
    val country_code: String? = null,
    val gender: String? = null,
    val is_premium: Boolean? = null,
    val is_using_custom_profile_image: Boolean? = null,
    val job: String? = null,
    val job_id: Int? = null,
    val pawoo_url: Any? = null,
    val region: String? = null,
    val total_follow_users: Int? = null,
    val total_illust_bookmarks_public: Int = 0,
    val total_illust_series: Int? = null,
    val total_illusts: Int = 0,
    val total_manga: Int = 0,
    val total_mypixiv_users: Int? = null,
    val total_novel_series: Int? = null,
    val total_novels: Int? = null,
    val twitter_account: String? = null,
    val twitter_url: String? = null,
    val webpage: Any? = null,
    // user/detail v2 新增:徽章,形如 {"type":"premium","url":null},常为 null
    val badge: Badge? = null
) {

    fun isPremium(): Boolean {
        return is_premium == true
    }
}

data class Badge(
    val type: String? = null,
    val url: String? = null,
) : Serializable

data class ProfilePublicity(
    val birth_day: String? = null,
    val birth_year: String? = null,
    val gender: String? = null,
    val job: String? = null,
    val pawoo: Boolean? = null,
    val region: String? = null
)

data class Workspace(
    val chair: String? = null,
    val comment: String? = null,
    val desk: String? = null,
    val desktop: String? = null,
    val monitor: String? = null,
    val mouse: String? = null,
    val music: String? = null,
    val pc: String? = null,
    val printer: String? = null,
    val scanner: String? = null,
    val tablet: String? = null,
    val tool: String? = null,
    val workspace_image_url: Any? = null
)

interface KListShow<T> {

    val displayList: List<T>

    val nextPageUrl: String?
}

data class UserPreview(
    val illusts: List<Illust> = listOf(),
    val is_muted: Boolean? = null,
    val novels: List<Novel>? = null,
    val user: User? = null
) : Serializable

data class UserPreviewResponse(
    val user_previews: List<UserPreview> = listOf(),
    val next_url: String? = null
) : Serializable, KListShow<UserPreview> {
    override val displayList: List<UserPreview> get() = user_previews
    override val nextPageUrl: String? get() = next_url
}

data class TrendingTagsResponse(
    val trend_tags: List<TrendingTag> = listOf(),
    val next_url: String? = null
) : Serializable, KListShow<TrendingTag> {
    override val displayList: List<TrendingTag>
        get() = trend_tags
    override val nextPageUrl: String?
        get() = next_url
}

data class TrendingTag(
    val tag: String? = null,
    val translated_name: String? = null,
    val illust: Illust? = null,
) : Serializable {
    fun buildTag(): Tag {
        return Tag(name = tag, translated_name = translated_name)
    }
}


data class ArticlesResponse(
    val spotlight_articles: List<Article> = listOf(),
    val next_url: String? = null
) : Serializable, KListShow<Article> {
    override val displayList: List<Article> get() = spotlight_articles
    override val nextPageUrl: String? get() = next_url
}

data class Article(
    val id: Long,
    val title: String? = null,
    val pure_title: String? = null,
    val thumbnail: String? = null,
    val article_url: String? = null,
    val publish_date: String? = null,
    val category: String? = null,
    val subcategory_label: String? = null
) : Serializable, ModelObject {
    override val objectUniqueId: Long
        get() = id
    override val objectType: Int
        get() = ObjectSpec.ARTICLE
}

data class SingleIllustResponse(
    val illust: Illust? = null,
) : Serializable

data class SingleNovelResponse(
    val novel: Novel? = null,
) : Serializable

data class GifInfoResponse(
    val illustId: Long,
    val ugoira_metadata: UgoiraMetaData? = null,
) : Serializable, ModelObject {
    override val objectUniqueId: Long
        get() = illustId
    override val objectType: Int
        get() = ObjectSpec.GIF_INFO
}

data class UgoiraMetaData(
    val zip_urls: ZipUrl? = null,
    val frames: List<GifFrame>? = null
) : Serializable

data class ZipUrl(
    val medium: String? = null,
) : Serializable

data class GifFrame(
    val file: String? = null,
    val delay: Int? = null,
) : Serializable

data class Comment(
    val comment: String? = null,
    val date: String? = null,
    val has_replies: Boolean = false,
    val id: Long = 0,
    val stamp: Stamp? = null,
    val user: User = User()
) : Serializable {

    fun displayCommentDate(): String {
        return DateParse.displayCreateDate(date)
    }
}

data class Stamp(
    val stamp_id: Long = 0,
    val stamp_url: String? = null,
)

data class StampsResponse(
    val stamps: List<Stamp> = listOf(),
)

data class CommentResponse(
    val comments: List<Comment> = listOf(),
    val next_url: String? = null
): Serializable, KListShow<Comment> {
    override val displayList: List<Comment>
        get() = comments
    override val nextPageUrl: String?
        get() = next_url
}

data class PostCommentResponse(
    val comment: Comment? = null,
): Serializable

data class WebResponse<T> (
    val error: Boolean? = null,
    val message: String? = null,
    val body: T? = null,
) : Serializable

// issue #959: pixiv 官方「拉黑」(网页端 ブロック,不是本地屏蔽)。
// /ajax/block/list?target_id=N 会把目标本人也放进 block_items,那一条 isTarget=true,
// 读它的 isBlocked 就是当前拉黑态 —— 不必翻完整张名单。
data class BlockListBody(
    val block_items: List<BlockItem>? = null,
    val has_more_blocks: Boolean = false,
) : Serializable

data class BlockItem(
    val userId: String? = null,
    val label: String? = null,
    val isBlocked: Boolean = false,
    val isTarget: Boolean = false,
) : Serializable

/** /ajax/block/save 的请求体,action 只接受 block / unblock。 */
data class BlockSaveRequest(
    val user_id: String,
    val action: String,
) : Serializable

// issue #1023: 网页版作品页标签区的「可编辑态」(/ajax/tags/illust/{id})。
// app-api 的 illust.tags 只有名字和译名,不带任何权限信息,所以要不要显示编辑入口、
// 哪些标签能删,只能问网页这条。
//
// [writable] 是「当前这个人现在能不能加标签」的合成结论 —— 未登录网页、或作者关掉了
// 「公开让其他会员编辑标签」([isLocked]=true),它都是 false;匿名请求恒为 false。
// 所以判断入口可见性只看它一个字段就够,不必自己再与 isLocked 做一次逻辑。
data class WorkTagsBody(
    val authorId: String? = null,
    val isLocked: Boolean = false,
    val tags: List<WorkEditableTag>? = null,
    val writable: Boolean = false,
) : Serializable

// [locked]=作者指定的标签,谁都删不掉;[deletable]=当前这个人能不能删这一条
// (通常是自己加的那些)。两者不是互补关系,分别判断。
data class WorkEditableTag(
    val tag: String? = null,
    val locked: Boolean = false,
    val deletable: Boolean = false,
    val userId: String? = null,
    val userName: String? = null,
    val translation: Map<String, String>? = null,
) : Serializable {

    /**
     * 译名。pixiv 这里的 key 恒是 `"en"`,值却是请求 `lang` 对应语言的译名(lang=zh 时装的是
     * 中文),所以不能按 key 取,直接拿第一个 value。
     */
    val translatedName: String?
        get() = translation?.values?.firstOrNull()?.takeIf { it.isNotBlank() }
}

/** /ajax/tags/illust/{id}/add · /delete 的请求体,一次只收一个标签。 */
data class WorkTagEditRequest(
    val tag: String,
) : Serializable

// 网页 ajax /ajax/illust/{id}/pages 的 body 元素:每一 P 的真实原图宽高与图片地址。
// 宽高供详情页多 P 下载前预置展示高度(见 IllustAdapter.seedPageDimensions);
// urls 供 #592 受限作品 web 兜底时拼 meta_pages。
data class WebIllustPage(
    val width: Int = 0,
    val height: Int = 0,
    val urls: WebIllustUrls? = null,
) : Serializable

// issue #592: 网页 ajax /ajax/illust/{id} 的 body。app-api 对部分作品(常见于简介带贩售/
// 外链的,不限 R18)返回 visible=false 的空壳,网页 ajax 不受限;只取映射 IllustsBean
// 所需的字段。两处 urls 形状略有不同:detail 是 mini/thumb,pages 是 thumb_mini,合用一个类。
data class WebIllustBody(
    val illustTitle: String? = null,
    val description: String? = null,
    val illustType: Int = 0,
    val createDate: String? = null,
    val restrict: Int = 0,
    val xRestrict: Int = 0,
    val sl: Int = 0,
    val urls: WebIllustUrls? = null,
    val tags: WebIllustTags? = null,
    val userId: String? = null,
    val userName: String? = null,
    val userAccount: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val pageCount: Int = 0,
    val bookmarkCount: Int = 0,
    val viewCount: Int = 0,
    val aiType: Int = 0,
    val bookmarkData: Any? = null,
) : Serializable

data class WebIllustUrls(
    val mini: String? = null,
    val thumb: String? = null,
    val thumb_mini: String? = null,
    val small: String? = null,
    val regular: String? = null,
    val original: String? = null,
) : Serializable

data class WebIllustTags(
    val tags: List<WebIllustTag>? = null,
) : Serializable

// translation 的 key 是站点返回语言(实际观察恒为 "en" 但值随 lang 参数变),取首个值即可。
data class WebIllustTag(
    val tag: String? = null,
    val translation: Map<String, String>? = null,
) : Serializable

// issue #569: 网页版「按 Tag 筛选画师作品」接口 /ajax/user/{id}/illusts/tag 的响应体。
// works 里是精简 work 对象(方图 url + 字符串 tags + 宽高),由 UserIllustByTagFeedSource.toIllustsBean 映射成 IllustsBean。
data class UserTagIllustBody(
    val works: List<UserTagIllust>? = null,
    val total: Int = 0,
) : Serializable

data class UserTagIllust(
    val id: Long = 0L,
    val title: String? = null,
    val illustType: Int = 0,
    val xRestrict: Int = 0,
    val aiType: Int = 0,
    val url: String? = null,
    val tags: List<String>? = null,
    val userId: Long = 0L,
    val userName: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val pageCount: Int = 0,
    val createDate: String? = null,
    val profileImageUrl: String? = null,
) : Serializable

// issue #996: 网页版「按 Tag 筛选作者小说」接口 /ajax/user/{id}/novels/tag 的响应体,
// 形状对齐 UserTagIllustBody;works 里是精简小说对象(600x600 novel-cover-master 封面 +
// 字符串 tags + 字数/收藏数),由 UserNovelByTagFeedFragment 的 toNovel 映射成 loxia Novel。
data class UserTagNovelBody(
    val works: List<UserTagNovel>? = null,
    val total: Int = 0,
) : Serializable

data class UserTagNovel(
    val id: Long = 0L,
    val title: String? = null,
    val xRestrict: Int = 0,
    val aiType: Int = 0,
    val url: String? = null,
    val tags: List<String>? = null,
    val userId: Long = 0L,
    val userName: String? = null,
    val profileImageUrl: String? = null,
    val textCount: Int = 0,
    val description: String? = null,
    val bookmarkCount: Int = 0,
    // 登录视角已收藏时是 {id, private} 对象,匿名/未收藏为 null —— 只用「是否为 null」判收藏态
    val bookmarkData: Any? = null,
    val createDate: String? = null,
    // 系列作品才有,单篇为 null;id 是字符串形态("1462193"),映射时 toLongOrNull 兜住空串
    val seriesId: String? = null,
    val seriesTitle: String? = null,
) : Serializable

/**
 * issue #1016: 网页版小说搜索开了「シリーズ単位で表示」(`/ajax/search/novels/{word}` + `gs=1`)
 * 之后的响应体。app-api 的 `/v1/search/novel` 没有这个能力，同一个系列的上百章会一条条铺满
 * 搜索结果——归纳模式只有网页端有。
 */
data class WebNovelSearchBody(
    val novel: WebNovelSearchSection? = null,
) : Serializable

data class WebNovelSearchSection(
    val data: List<WebNovelCollection>? = null,
    val total: Int = 0,
    /** 最后一页页码（匿名视角封顶 10 页）——翻页到它为止。 */
    val lastPage: Int = 0,
) : Serializable

/**
 * 归纳模式下的一条结果。**这是个混合列表**：
 *   - 系列条目：[novelId] 为 null，[id] 就是小说系列 id（可直接喂 `/v1/novel/series/{id}`），
 *     带 [episodeCount] / [isConcluded]；
 *   - 单篇条目：[novelId] 非空且才是真正的小说 id，[id] 是 pixiv 给单篇造的 collection id
 *     （与系列 id 同号段，**不能**当小说 id 用）。
 *
 * 判别一律用 [novelId] 是否为空，别用 `isOneshot`——后者在两种条目上都出现过。
 */
data class WebNovelCollection(
    val id: String? = null,
    val novelId: String? = null,
    val title: String? = null,
    val caption: String? = null,
    val cover: WebNovelCover? = null,
    val tags: List<String>? = null,
    val xRestrict: Int = 0,
    val aiType: Int = 0,
    val userId: Long = 0L,
    val userName: String? = null,
    val profileImageUrl: String? = null,
    val bookmarkCount: Int = 0,
    val isConcluded: Boolean = false,
    /** 系列条目：总话数 / 已公开话数（未公开的付费话不算）。 */
    val episodeCount: Int = 0,
    val publishedEpisodeCount: Int = 0,
    val textLength: Int = 0,
    val publishedTextLength: Int = 0,
    val createDateTime: String? = null,
    val latestPublishDateTime: String? = null,
    val publishedDateTime: String? = null,
    // 登录视角已收藏时是 {id, private} 对象，匿名/未收藏为 null —— 同 [UserTagNovel.bookmarkData]
    val bookmarkData: Any? = null,
) : Serializable

data class WebNovelCover(
    val urls: WebNovelCoverUrls? = null,
) : Serializable

/** 封面多尺寸。JSON 的 key 数字开头（`240mw` / `480mw`），只能靠 SerializedName 对上。 */
data class WebNovelCoverUrls(
    @SerializedName("240mw") val width240: String? = null,
    @SerializedName("480mw") val width480: String? = null,
    val original: String? = null,
) : Serializable


data class WebUser(
    val userId: Long? = null,
    val partial: Long? = null,
    val comment: String? = null,
    val name: String? = null,
    val image: String? = null,
    val imageBig: String? = null,
    val followedBack: Boolean? = null,
    val premium: Boolean? = null,
    val isFollowed: Boolean? = null,
    val isMypixiv: Boolean? = null,
    val isBlocking: Boolean? = null,
    val acceptRequest: Boolean? = null
) : Serializable


data class Novel(
    val caption: String? = null,
    val create_date: String? = null,
    val id: Long,
    val image_urls: ImageUrls? = null,
    val is_bookmarked: Boolean? = null,
    val is_muted: Boolean? = null,
    val is_mypixiv_only: Boolean? = null,
    val is_original: Boolean? = null,
    val is_x_restricted: Boolean? = null,
    val page_count: Int? = null,
    val restrict: Int? = null,
    val series: Series? = null,
    val tags: List<Tag>? = null,
    val text_length: Int? = null,
    val title: String? = null,
    val total_bookmarks: Int? = null,
    val total_comments: Int? = null,
    val total_view: Int? = null,
    val user: User? = null,
    val visible: Boolean? = null,
    val x_restrict: Int? = null,
    val novel_ai_type: Int = 0,   // 0=未知 / 1=人类 / 2=AI（issue #909 仅看 AI 客户端过滤用）
) : Serializable, ModelObject {
    override val objectUniqueId: Long
        get() = id
    override val objectType: Int
        get() = ObjectSpec.KNovel
}

data class Series (
    val id: Long,
    val title: String? = null,
) : Serializable

data class NovelResponse(
    val novels: List<Novel> = listOf(),
    val next_url: String? = null
) : Serializable, KListShow<Novel> {
    override val displayList: List<Novel> get() = novels
    override val nextPageUrl: String? get() = next_url
}

/**
 * 推荐小说响应（feeds 版）：比 [NovelResponse] 多首屏的 [ranking_novels]（排行榜预览头，
 * 只第一页带）。对齐 legacy ListNovel.getRanking_novels()。
 */
data class NovelRecommendResponse(
    val novels: List<Novel> = listOf(),
    val ranking_novels: List<Novel> = listOf(),
    val next_url: String? = null
) : Serializable, KListShow<Novel> {
    override val displayList: List<Novel> get() = novels
    override val nextPageUrl: String? get() = next_url
}

data class NovelText(
    val coverUrl: String? = null,
    val glossaryItems: List<Any>? = null,
    val id: String? = null,
    val illusts: List<Any>? = null,
    val images: List<Any>? = null,
    val marker: Any? = null,
    val replaceableItemIds: List<Any>? = null,
    val seriesId: String? = null,
    val seriesNavigation: SeriesNavigation? = null,
    val text: String? = null,
    val userId: String? = null
)

/**
 * `/ajax/novel/{id}/recommend/init` 的 body（issue #1005）。返回的 novel 对象是网页版
 * 卡片格式，与 loxia [Novel] 不同构——只解 id（和用来剔除无效条目的 isMasked），
 * 完整数据由调用方经 app-api novel/detail 补水。
 */
data class NovelRecommendInitBody(
    val novels: List<WebRecommendNovel>? = null,
)

data class WebRecommendNovel(
    val id: String? = null,
    /** true = 被屏蔽/不可见的占位条目，detail 也拉不到，直接跳过。 */
    val isMasked: Boolean? = null,
)

data class SeriesNavigation(
    val nextNovel: NovelBean? = null,
    val prevNovel: NovelBean? = null
)


data class WebNovel(
    val aiType: Int? = null,
    val caption: String? = null,
    val coverUrl: String? = null,
    val glossaryItems: List<Any?>? = null,
    val id: String? = null,
    val text: String? = null,
    val isOriginal: Boolean? = null,
    val marker: NovelMarkerBean? = null,
    val illusts: Map<String, WebIllustHolder>? = null,
    val images: Map<String, NovelImages>? = null,
    val replaceableItemIds: List<Any?>? = null,
    val seriesId: String? = null,
    val seriesIsWatched: Boolean? = null,
    val seriesNavigation: SeriesNavigation? = null,
    val seriesTitle: String? = null,
    val tags: List<String?>? = null,
    val title: String? = null,
    val userId: String? = null
)

data class WebIllustHolder(
    val illust: WebIllust? = null,
    val id: Long? = null,
    val user: WebUser? = null
) : Serializable


data class NovelImages(
    val novelImageId: Long? = null,
    val sl: Int? = null,
    val urls: Map<String, String>? = null,
) {
    companion object {

    }

    object Size {
        const val Size240mw = "240mw"
        const val Size480mw = "480mw"
        const val Size1200x1200 = "1200x1200"
        const val Size128x128 = "128x128"
        const val SizeOriginal = "original"
    }
}

@Parcelize
data class SelfProfile(
    val profile: User = User(),
    val user_state: KUserState = KUserState()
) : Parcelable

@Parcelize
data class KUserState(
    val is_mail_authorized: Boolean = false,
    val has_mail_address: Boolean = false,
    val has_changed_pixiv_id: Boolean = false,
    val can_change_pixiv_id: Boolean = false,
    val has_password: Boolean = false,
    val require_policy_agreement: Boolean = false,
    val no_login_method: Boolean = false,
    val is_user_restricted: Boolean = false,
    val is_official_event_notifications_enabled: Boolean = false,
) : Parcelable

@Parcelize
data class NovelSeriesDetail(
    val id: Long = 0L,
    val title: String? = null,
    val caption: String? = null,
    val display_text: String? = null,
    val user: User? = null,
    val is_original: Boolean? = null,
    val is_concluded: Boolean? = null,
    val watchlist_added: Boolean? = null,
    val content_count: Int = 0,
    // 漫画系列接口 /v1/illust/series 的话数字段是 series_work_count（不是小说的
    // content_count），复用本模型解析 illust_series_detail 时用它拿总话数。
    val series_work_count: Int = 0,
    val novel_ai_type: Int = 0,
    val total_character_count: Int = 0,
) : Parcelable


@Parcelize
data class NovelSeriesResp(
    val novel_series_detail: NovelSeriesDetail? = null,
    val novel_series_first_novel: Novel? = null,
    val novel_series_latest_novel: Novel? = null,
    val novels: List<Novel>? = null,
    val next_url: String? = null
) : Parcelable, KListShow<Novel> {
    override val displayList: List<Novel>
        get() = novels ?: listOf()
    override val nextPageUrl: String?
        get() = next_url
}


@Parcelize
data class IllustSeriesResp(
    val illust_series_detail: NovelSeriesDetail? = null,
    val illust_series_first_illust: Illust? = null,
    val illust_series_latest_illust: Illust? = null,
    val illusts: List<Illust>? = null,
    val next_url: String? = null
) : Parcelable, KListShow<Illust> {
    override val displayList: List<Illust>
        get() = illusts ?: listOf()
    override val nextPageUrl: String?
        get() = next_url
}
/**
 * 「追更列表」的一个条目 —— 注意它是一个**系列**（漫画系列 / 小说系列），不是单个作品：
 * `v1/watchlist/manga|novel` 的响应顶层字段就叫 `series`。所以 [id] 是系列 id，
 * 要开「最新一话」得用 [latest_content_id]（那才是作品 id）。
 *
 * [mask_text] 非空即「被屏蔽/下架」的占位条目：服务端此时把 [title] 给空串、[url] 给 null、
 * [user] 的 id 给 0，只留一句说明文案（对齐 legacy WatchlistMangaAdapter.isInvalidItem 的判定）。
 */
data class WatchlistSeries(
    val id: Long = 0,
    val title: String = "",
    /** 系列封面。被屏蔽的条目为 null。 */
    val url: String? = null,
    /** 非空 = 被屏蔽/下架，此时只显示这句话。 */
    val mask_text: String? = null,
    val published_content_count: Int = 0,
    /** ISO 时间串；卡片只显示前 10 位（日期部分），对齐 legacy 的 substring(0, 10)。 */
    val last_published_content_datetime: String? = null,
    /** 最新一话的**作品** id（不是系列 id）。 */
    val latest_content_id: Long? = null,
    val user: User? = null,
) : Serializable {

    /** 被屏蔽/下架的占位条目（对齐 legacy isInvalidItem：标题空 + 无封面 + 有 mask + user.id=0）。 */
    val isMasked: Boolean
        get() = title.isEmpty() && url == null && mask_text != null && (user?.id ?: 0L) == 0L
}

data class WatchlistResponse(
    /** 服务端字段名就是 series —— 追更列表装的是系列。 */
    val series: List<WatchlistSeries> = listOf(),
    val next_url: String? = null,
) : Serializable, KListShow<WatchlistSeries> {
    override val displayList: List<WatchlistSeries> get() = series
    override val nextPageUrl: String? get() = next_url
}
