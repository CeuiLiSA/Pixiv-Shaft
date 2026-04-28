package ceui.loxia

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class StreetResponse(
    override val error: Boolean? = null,
    override val message: String? = null,
    override val body: StreetBody? = null,
) : WebApiResponse<StreetBody>

data class StreetBody(
    val contents: List<StreetContent>? = null,
    val nextParams: StreetNextParams? = null,
) : Serializable

data class StreetContent(
    val kind: String? = null,
    val thumbnails: List<StreetThumbnail>? = null,
    val pickup: StreetPickup? = null,
    val trendTags: List<StreetTrendTag>? = null,
    /** separator only */
    val id: String? = null,
) : Serializable

data class StreetThumbnail(
    val type: String? = null,
    val id: String? = null,
    val title: String? = null,
    val tags: List<StreetTag>? = null,
    val restrict: Int? = null,
    val xRestrict: Int? = null,
    val userId: String? = null,
    val userName: String? = null,
    val profileImageUrl: String? = null,
    val createDate: String? = null,
    val updateDate: String? = null,
    val aiType: Int? = null,
    val bookmarkable: Boolean? = null,
    val showTags: Boolean? = null,
    /** illust / manga */
    val pageCount: Int? = null,
    val pages: List<StreetPage>? = null,
    /** manga */
    val episodeCount: Int? = null,
    /** novel */
    val url: String? = null,
    val description: String? = null,
    val text: String? = null,
    val textCount: Int? = null,
    val wordCount: Int? = null,
    val bookmarkCount: Int? = null,
    val isOriginal: Boolean? = null,
    /** collection */
    val language: String? = null,
    val caption: String? = null,
) : Serializable

data class StreetTag(
    val name: String? = null,
    val translatedName: String? = null,
) : Serializable

data class StreetPage(
    val width: Int? = null,
    val height: Int? = null,
    val urls: StreetPageUrls? = null,
) : Serializable

data class StreetPageUrls(
    @SerializedName("1200x1200_standard") val standard: String? = null,
    @SerializedName("540x540") val medium: String? = null,
    @SerializedName("360x360") val small: String? = null,
) : Serializable {
    val best: String? get() = standard ?: medium ?: small
}

data class StreetPickup(
    val type: String? = null,
    val userId: String? = null,
    val userName: String? = null,
    val profileImageUrl: String? = null,
    val comment: String? = null,
    val commentCount: Int? = null,
) : Serializable

data class StreetTrendTag(
    val name: String? = null,
    val translatedName: String? = null,
    val taggedCount: Int? = null,
    val url: String? = null,
) : Serializable

data class StreetNextParams(
    val page: Int? = null,
    val content_index_prev: Int? = null,
    val li: String? = null,
    val lm: String? = null,
    val ln: String? = null,
    val lc: String? = null,
) : Serializable

data class StreetRequest(
    val k: String? = null,
    val vhi: String? = null,
    val vhm: String? = null,
    val vhn: String? = null,
    val vhc: String? = null,
) : Serializable
