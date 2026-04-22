package ceui.pixiv.ui.detail

import androidx.lifecycle.LiveData
import ceui.lisa.models.IllustsBean
import ceui.loxia.Comment

sealed class ArtworkDetailItem {

    data class Hero(val illust: IllustsBean) : ArtworkDetailItem()

    data class Series(val illust: IllustsBean) : ArtworkDetailItem()

    data class Desc(val caption: String) : ArtworkDetailItem()

    data class Stats(val illust: IllustsBean) : ArtworkDetailItem()

    data class Tags(val illust: IllustsBean) : ArtworkDetailItem()

    data class Artist(
        val illust: IllustsBean,
        val isFollowed: Boolean = illust.user?.isIs_followed ?: false
    ) : ArtworkDetailItem()

    data class DetailPanel(val illust: IllustsBean) : ArtworkDetailItem()

    data class Comments(
        val liveData: LiveData<List<Comment>?>,
        val illustId: Int,
        val illustTitle: String
    ) : ArtworkDetailItem()

    data class AuthorWorks(
        val liveData: LiveData<List<IllustsBean>?>,
        val authorName: String,
        val userId: Int
    ) : ArtworkDetailItem()

    data class RelatedHeader(
        val liveData: LiveData<Boolean?>,
        val illustId: Int,
        val illustTitle: String
    ) : ArtworkDetailItem()
}
