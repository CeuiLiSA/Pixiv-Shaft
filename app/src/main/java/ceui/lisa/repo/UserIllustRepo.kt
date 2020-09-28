package ceui.lisa.repo

import ceui.lisa.core.FilterMapper
import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.lisa.utils.Params
import io.reactivex.Observable
import io.reactivex.functions.Function

class UserIllustRepo(private val userID: Int) : RemoteRepo<ListIllust>() {

    override fun initApi(): Observable<ListIllust> {
        return Retro.getAppApi().getUserSubmitIllust(token(), userID, Params.TYPE_ILLUST)
    }

    override fun initNextApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNextIllust(token(), nextUrl)
    }

    override fun mapper(): Function<in ListIllust, ListIllust> {
        return FilterMapper()
    }
}