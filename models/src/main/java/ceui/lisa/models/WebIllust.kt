package ceui.lisa.models

import java.io.Serializable

data class WebIllust(
    val alt: String? = null,
    val bookmarkData: Any? = null,
    val createDate: String? = null,
    val description: String? = null,
    val height: Int,
    val id: Long = 0L,
    val illustType: Int? = null,
    val isBookmarkable: Boolean? = null,
    val images: Urls? = null,
    val isMasked: Boolean? = null,
    val isUnlisted: Boolean? = null,
    val pageCount: Int? = null,
    val profileImageUrl: String? = null,
    val restrict: Int? = null,
    val sl: Int? = null,
    val tags: List<MiniTag>? = null,
    val title: String? = null,
    val updateDate: String? = null,
    val url: String? = null,
    val userId: String? = null,
    val userName: String? = null,
    val width: Int,
    val xRestrict: Int? = null
) : Serializable

data class Urls(
    val small: String? = null,
    val medium: String? = null,
    val original: String? = null,
) : Serializable

data class WebIllustHolder(
    val illust: WebIllust? = null,
    val id: Long? = null,
    val user: WebUser? = null
) : Serializable

data class MiniTag(
    val tag: String? = null,
    val userId: Long? = null,
) : Serializable
