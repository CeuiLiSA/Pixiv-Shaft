package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListUser
import io.reactivex.Observable

class RelatedUserRepo(
    private val userID: Int,
) : RemoteRepo<ListUser>() {

    override fun initApi(): Observable<ListUser> {
        return Retro.getAppApi().getRelatedUsers(token(), userID)
    }

    override fun initNextApi(): Observable<ListUser> {
        return Retro.getAppApi().getNextUser(token(), nextUrl)
    }
}
