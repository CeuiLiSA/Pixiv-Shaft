package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListMangaSeries
import io.reactivex.Observable

class MangaSeriesRepo(private val userID: Int) : RemoteRepo<ListMangaSeries>() {

    override fun initApi(): Observable<ListMangaSeries> {
        return Retro.getAppApi().getUserMangaSeries(token(), userID)
    }

    override fun initNextApi(): Observable<ListMangaSeries>? {
        return null
    }
}