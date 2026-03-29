package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListNovelOfSeries
import io.reactivex.Observable

class NovelSeriesDetailRepo constructor(private val seriesID: Int) : RemoteRepo<ListNovelOfSeries>() {

    override fun initApi(): Observable<ListNovelOfSeries> {
        return Retro.getAppApi().getNovelSeries(seriesID)
    }

    override fun initNextApi(): Observable<ListNovelOfSeries> {
        return Retro.getAppApi().getNextSeriesNovel(nextUrl)
    }
}
