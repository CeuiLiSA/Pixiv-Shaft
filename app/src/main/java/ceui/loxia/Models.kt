package ceui.loxia

import android.text.TextUtils
import ceui.lisa.interfaces.ListShow
import ceui.lisa.models.ModelObject
import ceui.lisa.models.ObjectSpec
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
    override val displayList: List<Illust> get() = illusts
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

    val officialUsers = listOf(
        pixiv,
        pxv_sensei,
        mangapixiv,
        pixivision,
        pxv_sketch,
        pixiv3,
        fanbox,
    )
}



data class Illust(
    val caption: String? = null,
    val create_date: String? = null,
    val height: Int = 0,
    val id: Long,
    val image_urls: ImageUrls? = null,
    val is_bookmarked: Boolean? = null,
    val is_muted: Boolean? = null,
    val meta_pages: List<MetaPage>? = null,
    val meta_single_page: MetaSinglePage? = null,
    val page_count: Int = 0,
    val restrict: Int? = null,
    val sanity_level: Int? = null,
    val series: Any? = null,
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

data class Tag(
    val name: String? = null,
    val translated_name: String? = null
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
    val is_followed: Boolean? = null,
    val name: String? = null,
    val profile_image_urls: ImageUrls? = null,
    val is_mail_authorized: Boolean? = null,
    val is_premium: Boolean? = null,
    val mail_address: String? = null,
    val gender: Int = UserGender.MALE,
    val require_policy_agreement: Boolean? = null,
    val x_restrict: Int? = null,
    val comment: String? = null,
) : Serializable, ModelObject {
    override val objectUniqueId: Long
        get() = id
    override val objectType: Int
        get() = ObjectSpec.KUser

    fun isOfficial(): Boolean {
        return ConstantUser.officialUsers.contains(id)
    }

    fun isPremium(): Boolean {
        return is_premium == true
    }

    fun hasGender(): Boolean {
        return gender != UserGender.UNKNOWN
    }
}

data class ImageUrls(
    val large: String? = null,
    val medium: String? = null,
    val original: String? = null,
    val square_medium: String? = null,
    val px_16x16: String? = null,
    val px_170x170: String? = null,
    val px_50x50: String? = null,
) : Serializable {

    fun findMaxSizeUrl(): String? {
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

data class WebApiError(
    val error: Boolean? = null,
    val message: String? = null,
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

data class UserResponse(
    val profile: Profile? = null,
    val profile_publicity: ProfilePublicity? = null,
    val user: User? = null,
    val workspace: Workspace? = null
) {

    fun isPremium(): Boolean {
        return profile?.is_premium == true
    }
}

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
    val total_illust_bookmarks_public: Int? = null,
    val total_illust_series: Int? = null,
    val total_illusts: Int? = null,
    val total_manga: Int? = null,
    val total_mypixiv_users: Int? = null,
    val total_novel_series: Int? = null,
    val total_novels: Int? = null,
    val twitter_account: String? = null,
    val twitter_url: String? = null,
    val webpage: Any? = null
)

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
    val novels: List<Any>? = null,
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
) : Serializable


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

data class AddCommentResponse(
    val comment: Comment? = null,
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

data class CommentResponse(
    val comments: List<Comment> = listOf(),
    val next_url: String? = null
): Serializable, KListShow<Comment> {
    override val displayList: List<Comment>
        get() = comments
    override val nextPageUrl: String?
        get() = next_url
}

data class WebResponse<T> (
    val error: Boolean? = null,
    val message: String? = null,
    val body: T? = null,
) : Serializable

data class RelatedUserBody (
    val thumbnails: List<WebIllust>? = null,
    val users: List<WebUser>? = null,
) : Serializable

data class WebRecmdBody (
    val thumbnails: List<WebIllust>? = null,
    val popularTags: TagsBody? = null,
    val recommendTags: TagsBody? = null,
    val recommendByTags: TagsBody? = null,
) : Serializable


data class TagsBody (
    val illust: List<SingleRecommend>? = null,
) : Serializable

data class SingleRecommend (
    val tag: String? = null,
    val ids: List<Long>? = null,
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

data class WebIllust(
    val alt: String? = null,
    val bookmarkData: Any? = null,
    val createDate: String? = null,
    val description: String? = null,
    val height: Int,
    val id: Long = 0L,
    val illustType: Int? = null,
    val isBookmarkable: Boolean? = null,
    val isMasked: Boolean? = null,
    val isUnlisted: Boolean? = null,
    val pageCount: Int? = null,
    val profileImageUrl: String? = null,
    val restrict: Int? = null,
    val sl: Int? = null,
    val tags: List<String>? = null,
    val title: String? = null,
    val titleCaptionTranslation: TitleCaptionTranslation? = null,
    val updateDate: String? = null,
    val url: String? = null,
    val userId: String? = null,
    val userName: String? = null,
    val width: Int,
    val xRestrict: Int? = null
) : Serializable


data class TitleCaptionTranslation(
    val workCaption: Any,
    val workTitle: Any
) : Serializable

data class WaitingPage (
    val thumbnails: ThumbnailBody? = null
) : Serializable


data class ThumbnailBody (
    val illust: List<WebIllust>? = null,
) : Serializable

data class ListIllustBody (
    val illusts: List<WebIllust>? = null,
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
    val x_restrict: Int? = null
) : Serializable, ModelObject {
    override val objectUniqueId: Long
        get() = id
    override val objectType: Int
        get() = ObjectSpec.POST
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

data class SeriesNavigation(
    val nextNovel: NextNovel? = null,
    val prevNovel: Any? = null
)

data class NextNovel(
    val contentOrder: String? = null,
    val coverUrl: String? = null,
    val id: Int? = null,
    val title: String? = null,
    val viewable: Boolean? = null,
    val viewableMessage: Any? = null
)