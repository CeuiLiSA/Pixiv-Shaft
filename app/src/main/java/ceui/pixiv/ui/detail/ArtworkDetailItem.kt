package ceui.pixiv.ui.detail

import ceui.lisa.models.IllustsBean
import ceui.lisa.models.UserBean
import ceui.loxia.Comment
import ceui.loxia.Illust

sealed class ArtworkDetailItem {

    data class Hero(val illust: IllustsBean) : ArtworkDetailItem()

    data class Series(val illust: IllustsBean) : ArtworkDetailItem()

    data class Desc(val caption: String) : ArtworkDetailItem()

    data class Stats(val illust: IllustsBean) : ArtworkDetailItem()

    data class Tags(val illust: IllustsBean) : ArtworkDetailItem()

    data class Artist(
        val illust: IllustsBean,
        val fullUser: UserBean?
    ) : ArtworkDetailItem()

    data class DetailPanel(val illust: IllustsBean) : ArtworkDetailItem()

    data class Comments(
        val comments: List<Comment>,
        val illustId: Int,
        val illustTitle: String
    ) : ArtworkDetailItem()

    data class AuthorWorks(
        val works: List<Illust>,
        val authorName: String
    ) : ArtworkDetailItem()

    data class RelatedHeader(
        val illustId: Int,
        val illustTitle: String
    ) : ArtworkDetailItem()
}
