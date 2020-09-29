package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListNovel
import io.reactivex.Observable

class SearchNovelRepo(
        val keyword: String?,
        val sortType: String?,
        val searchType: String?
) : RemoteRepo<ListNovel>() {

    override fun initApi(): Observable<ListNovel> {
        return Retro.getAppApi().searchNovel(token(), keyword, sortType, searchType)
    }

    override fun initNextApi(): Observable<ListNovel> {
        return Retro.getAppApi().getNextNovel(token(), nextUrl)
    }
}