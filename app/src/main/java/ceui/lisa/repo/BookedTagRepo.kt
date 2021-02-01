package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListTag
import io.reactivex.Observable

class BookedTagRepo(private val starType: String?) : RemoteRepo<ListTag>() {

    override fun initApi(): Observable<ListTag> {
        return Retro.getAppApi().getBookmarkTags(token(), currentUserID(), starType)
    }

    override fun initNextApi(): Observable<ListTag> {
        return Retro.getAppApi().getNextTags(token(), nextUrl)
    }
}
