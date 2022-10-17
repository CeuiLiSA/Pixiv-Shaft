package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListBookmarkTag
import ceui.lisa.model.ListTag
import ceui.lisa.utils.Params
import io.reactivex.Observable
import io.reactivex.functions.Function

class SelectTagRepo(
        private val id: Int,
        private val type: String,
        private val tagNames: List<String>,
) : RemoteRepo<ListBookmarkTag>() {

    var listTag: ListTag? = null

    override fun initApi(): Observable<ListBookmarkTag> {

        var api1: Observable<ListBookmarkTag>? = null
        var api2: Observable<ListTag>? = null

        when(type){
            Params.TYPE_ILLUST -> {
                api1 = Retro.getAppApi().getIllustBookmarkTags(token(), id)
                api2 = Retro.getAppApi().getAllIllustBookmarkTags(token(), currentUserID(), Params.TYPE_PUBLIC)
            }
            Params.TYPE_NOVEL -> {
                api1 = Retro.getAppApi().getNovelBookmarkTags(token(), id)
                api2 = Retro.getAppApi().getAllNovelBookmarkTags(token(), currentUserID(), Params.TYPE_PUBLIC)
            }
        }

        return api2!!.flatMap(
            fun(listTag: ListTag): Observable<ListBookmarkTag> {
                this.listTag = listTag
                return api1!!
            }
        )
    }

    override fun initNextApi(): Observable<ListBookmarkTag>? {
        return null
    }

    override fun mapper(): Function<in ListBookmarkTag, ListBookmarkTag> {
        return Function { listBookmarkTag ->
            val tags = listBookmarkTag.list
            if (listTag != null) {
                tags.forEach { tag ->
                    if (listTag!!.list.any { t -> t.name == tag.name } && tagNames.contains(tag.name)) {
                        tag.isSelected = true
                    }
                }
            }

            listBookmarkTag
        }
    }
}
