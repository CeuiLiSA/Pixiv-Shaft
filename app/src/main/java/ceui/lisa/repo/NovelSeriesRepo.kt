package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.NovelSeries
import io.reactivex.Observable

class NovelSeriesRepo constructor(private val seriesID: Int) : RemoteRepo<NovelSeries>() {

    override fun initApi(): Observable<NovelSeries> {
        return Retro.getAppApi().getNovelSeries(token(), seriesID)
    }

    override fun initNextApi(): Observable<NovelSeries> {
        return Retro.getAppApi().getNextSeriesNovel(token(), nextUrl)
    }
}