package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListTag
import io.reactivex.Observable

class BookedTagRepo(
    private val type: Int,
    private val starType: String?,
) : RemoteRepo<ListTag>() {

    override fun initApi(): Observable<ListTag> {
        if (type == 1) {
            return Retro.getAppApi().getAllNovelBookmarkTags(token(), currentUserID(), starType)
        }
        return Retro.getAppApi().getAllIllustBookmarkTags(token(), currentUserID(), starType)
    }

    override fun initNextApi(): Observable<ListTag> {
        return Retro.getAppApi().getNextTags(token(), nextUrl)
    }
}
