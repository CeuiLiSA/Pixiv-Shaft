package ceui.lisa.repo

import ceui.lisa.activities.Shaft
import ceui.lisa.core.FilterMapper
import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.lisa.utils.PixivOperate
import ceui.lisa.viewmodel.SearchModel
import io.reactivex.Observable
import io.reactivex.functions.Function

class SearchIllustRepo(
        var keyword: String?,
        var sortType: String?,
        var searchType: String?,
        var isPopular: Boolean
) : RemoteRepo<ListIllust>() {

    override fun initApi(): Observable<ListIllust> {
        return if (isPopular) {
            Retro.getAppApi().popularPreview(token(), keyword)
        } else {
            PixivOperate.insertSearchHistory(keyword, 0)
            Retro.getAppApi().searchIllust(token(), keyword +
                    if (Shaft.sSettings.searchFilter.contains("无限制"))
                        ""
                    else
                        " " + Shaft.sSettings.searchFilter,
                    sortType,
                    searchType)
        }
    }

    override fun initNextApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNextIllust(token(), nextUrl)
    }

    override fun mapper(): Function<in ListIllust, ListIllust> {
        return FilterMapper()
    }

    fun update(searchModel: SearchModel, pop: Boolean) {
        keyword = searchModel.keyword.value
        sortType = searchModel.sortType.value
        searchType = searchModel.searchType.value
        isPopular = pop
    }
}