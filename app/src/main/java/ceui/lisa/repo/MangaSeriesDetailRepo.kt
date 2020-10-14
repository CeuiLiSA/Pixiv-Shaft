package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListMangaSeries
import ceui.lisa.model.ListMangaSeriesDetail
import io.reactivex.Observable

class MangaSeriesDetailRepo(private val seriesID: Int) : RemoteRepo<ListMangaSeriesDetail>() {

    override fun initApi(): Observable<ListMangaSeriesDetail> {
        return Retro.getAppApi().getMangaSeriesById(token(), seriesID)
    }

    override fun initNextApi(): Observable<ListMangaSeriesDetail>? {
        return null
    }
}