package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListNovel
import ceui.lisa.viewmodel.SearchModel
import io.reactivex.Observable

class SearchNovelRepo(
    var keyword: String?,
    var sortType: String?,
    var searchType: String?
) : RemoteRepo<ListNovel>() {

    override fun initApi(): Observable<ListNovel> {
        return Retro.getAppApi().searchNovel(token(), keyword, sortType, searchType)
    }

    override fun initNextApi(): Observable<ListNovel> {
        return Retro.getAppApi().getNextNovel(token(), nextUrl)
    }

    fun update(searchModel: SearchModel) {
        keyword = searchModel.keyword.value
        sortType = searchModel.sortType.value
        searchType = searchModel.searchType.value
    }
}
