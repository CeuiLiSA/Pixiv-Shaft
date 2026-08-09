package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListSimpleUser
import io.reactivex.Observable

class SimpleUserRepo(private val illustID: Int) : RemoteRepo<ListSimpleUser>() {

    override fun initApi(): Observable<ListSimpleUser> {
        return Retro.getAppApi().getUsersWhoLikeThisIllust(illustID)
    }

    override fun initNextApi(): Observable<ListSimpleUser> {
        return Retro.getAppApi().getNextSimpleUser(nextUrl)
    }
}

class NovelBookmarkUserRepo(private val novelID: Long) : RemoteRepo<ListSimpleUser>() {

    override fun initApi(): Observable<ListSimpleUser> {
        return Retro.getAppApi().getUsersWhoLikeThisNovel(novelID)
    }

    override fun initNextApi(): Observable<ListSimpleUser> {
        return Retro.getAppApi().getNextSimpleUser(nextUrl)
    }
}
