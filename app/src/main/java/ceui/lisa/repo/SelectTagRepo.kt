package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListBookmarkTag
import io.reactivex.Observable

class SelectTagRepo(private val illustID: Int) : RemoteRepo<ListBookmarkTag>() {

    override fun initApi(): Observable<ListBookmarkTag> {
        return Retro.getAppApi().getIllustBookmarkTags(token(), illustID)
    }

    override fun initNextApi(): Observable<ListBookmarkTag>? {
        return null
    }
}
