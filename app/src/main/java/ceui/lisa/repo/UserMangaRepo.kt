package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.lisa.utils.Params
import io.reactivex.Observable

class UserMangaRepo(private val userID: Int) : RemoteRepo<ListIllust>() {

    override fun initApi(): Observable<ListIllust> {
        return Retro.getAppApi().getUserSubmitIllust(token(), userID, Params.TYPE_MANGA)
    }

    override fun initNextApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNextIllust(token(), nextUrl)
    }
}
