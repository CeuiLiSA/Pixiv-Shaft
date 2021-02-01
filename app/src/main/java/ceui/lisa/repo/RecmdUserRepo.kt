package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListUser
import ceui.lisa.utils.Dev
import io.reactivex.Observable

class RecmdUserRepo(private val isHorizontal: Boolean) : RemoteRepo<ListUser>() {

    override fun initApi(): Observable<ListUser> {
        return Retro.getAppApi().getRecmdUser(token())
    }

    override fun initNextApi(): Observable<ListUser>? {
        if (isHorizontal) {
            return null
        }
        return Retro.getAppApi().getNextUser(token(), nextUrl)
    }

    override fun localData(): Boolean {
        if (isHorizontal) {
            return Dev.isDev
        }
        return super.localData()
    }
}
