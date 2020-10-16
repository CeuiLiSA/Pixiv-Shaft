package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListMangaOfSeries
import io.reactivex.Observable

class MangaSeriesDetailRepo(private val seriesID: Int) : RemoteRepo<ListMangaOfSeries>() {

    override fun initApi(): Observable<ListMangaOfSeries> {
        return Retro.getAppApi().getMangaSeriesById(token(), seriesID)
    }

    override fun initNextApi(): Observable<ListMangaOfSeries>? {
        return null
    }
}