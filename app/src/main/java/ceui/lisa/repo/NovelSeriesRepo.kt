package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListNovelSeries
import io.reactivex.Observable

class NovelSeriesRepo(private val userID: Int) : RemoteRepo<ListNovelSeries>() {

    override fun initApi(): Observable<ListNovelSeries> {
        return Retro.getAppApi().getUserNovelSeries(token(), userID)
    }

    override fun initNextApi(): Observable<ListNovelSeries>? {
        return Retro.getAppApi().getNextUserNovelSeries(token(), nextUrl)
    }
}