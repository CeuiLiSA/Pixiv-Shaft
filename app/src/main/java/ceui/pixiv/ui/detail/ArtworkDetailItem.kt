package ceui.pixiv.ui.detail

import ceui.lisa.models.IllustsBean
import ceui.lisa.models.UserBean
import ceui.loxia.Comment

sealed class ArtworkDetailItem {

    data class Hero(val illust: IllustsBean) : ArtworkDetailItem()

    data class Series(val illust: IllustsBean) : ArtworkDetailItem()

    data class Desc(val caption: String) : ArtworkDetailItem()

    data class Stats(val illust: IllustsBean) : ArtworkDetailItem()

    data class Tags(val illust: IllustsBean) : ArtworkDetailItem()

    data class Artist(
        val illust: IllustsBean,
        val fullUser: UserBean?,
        val isFollowed: Boolean = fullUser?.isIs_followed ?: false
    ) : ArtworkDetailItem()

    data class DetailPanel(val illust: IllustsBean) : ArtworkDetailItem()

    data class Comments(
        val comments: List<Comment>,
        val illustId: Int,
        val illustTitle: String
    ) : ArtworkDetailItem()

    data class AuthorWorks(
        val works: List<IllustsBean>,
        val authorName: String,
        val userId: Int
    ) : ArtworkDetailItem()

    data class RelatedHeader(
        val illustId: Int,
        val illustTitle: String
    ) : ArtworkDetailItem()
}
