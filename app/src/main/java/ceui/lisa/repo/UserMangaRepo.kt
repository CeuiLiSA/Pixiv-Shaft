package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.lisa.utils.Params
import io.reactivex.Observable

class UserMangaRepo @JvmOverloads constructor(
    private val userID: Int,
    private val initialOffset: Int = 0
) : RemoteRepo<ListIllust>() {

    override fun initApi(): Observable<ListIllust> {
        return if (initialOffset > 0) {
            Retro.getAppApi().getNextIllust(buildOffsetUrl(userID, Params.TYPE_MANGA, initialOffset))
        } else {
            Retro.getAppApi().getUserSubmitIllust(userID, Params.TYPE_MANGA)
        }
    }

    override fun initNextApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNextIllust(nextUrl)
    }
}
