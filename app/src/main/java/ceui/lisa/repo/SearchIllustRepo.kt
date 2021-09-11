package ceui.lisa.repo

import android.text.TextUtils
import ceui.lisa.core.FilterMapper
import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.PixivSearchParamUtil
import ceui.lisa.utils.SearchTypeUtil
import ceui.lisa.viewmodel.SearchModel
import io.reactivex.Observable
import io.reactivex.functions.Function

class SearchIllustRepo(
    var keyword: String?,
    private var sortType: String?,
    var searchType: String?,
    var starSize: String?,
    //var isPopular: Boolean,
    private var isPremium: Boolean?,
    private var startDate: String?,
    private var endDate: String?,
    private var r18Restriction: Int?
) : RemoteRepo<ListIllust>() {

    private var filterMapper: FilterMapper? = null

    override fun initApi(): Observable<ListIllust> {
        PixivOperate.insertSearchHistory(keyword, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD)
        val assembledKeyword: String = (keyword + when {
            TextUtils.isEmpty(starSize) -> ""
            else -> " $starSize"
        } + when (r18Restriction) {
            null -> ""
            else -> " ${PixivSearchParamUtil.R18_RESTRICTION_VALUE[r18Restriction!!]}"
        }).trim()

        return if (sortType == PixivSearchParamUtil.POPULAR_SORT_VALUE && (isPremium != true)) {
            Retro.getAppApi().popularPreview(
                token(),
                assembledKeyword,
                startDate,
                endDate,
                searchType
            )
        } else {
            Retro.getAppApi().searchIllust(
                token(),
                assembledKeyword,
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
        r18Restriction = searchModel.r18Restriction.value

        this.filterMapper?.updateStarSizeLimit(this.getStarSizeLimit())
    }

    private fun getStarSizeLimit(): Int {
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
