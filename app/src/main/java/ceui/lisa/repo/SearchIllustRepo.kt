package ceui.lisa.repo

import android.text.TextUtils
import ceui.lisa.core.FilterMapper
import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.PixivSearchParamUtil
import ceui.lisa.viewmodel.SearchModel
import io.reactivex.Observable
import io.reactivex.functions.Function

class SearchIllustRepo(
    var keyword: String?,
    var sortType: String?,
    var searchType: String?,
    var starSize: String?,
    //var isPopular: Boolean,
    var isPremium: Boolean?,
    var startDate: String?,
    var endDate: String?
) : RemoteRepo<ListIllust>() {

    private var filterMapper: FilterMapper? = null

    override fun initApi(): Observable<ListIllust> {
        return if (sortType==PixivSearchParamUtil.POPULAR_SORT_VALUE&&(!(isPremium?:false))) {
            Retro.getAppApi().popularPreview(
                token(),
                keyword + if (TextUtils.isEmpty(starSize)) "" else " $starSize",
                startDate,
                endDate,
                searchType
            )
        } else {
            PixivOperate.insertSearchHistory(keyword, 0)
            Retro.getAppApi().searchIllust(
                token(),
                keyword + if (TextUtils.isEmpty(starSize)) "" else " $starSize",
                sortType,
                startDate,
                endDate,
                searchType
            )
        }
    }

    override fun initNextApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNextIllust(token(), nextUrl)
    }

    override fun mapper(): Function<in ListIllust, ListIllust> {
        if (this.filterMapper == null) {
            this.filterMapper = FilterMapper().enableFilterStarSize()
        }
        return this.filterMapper!!
    }

    fun update(searchModel: SearchModel) {
        keyword = searchModel.keyword.value
        sortType = searchModel.sortType.value
        searchType = searchModel.searchType.value
        starSize = searchModel.starSize.value
        //isPopular = pop
        isPremium = searchModel.isPremium.value
        startDate = searchModel.startDate.value
        endDate = searchModel.endDate.value

        this.filterMapper?.updateStarSizeLimit(this.getStarSizeLimit())
    }

    fun getStarSizeLimit(): Int {
        if (TextUtils.isEmpty(this.starSize)) {
            return 0
        }
        val match = Regex("""\d+""").find(starSize!!)
        if (match != null) {
            return match.value.toInt()
        }
        return 0
    }
}
