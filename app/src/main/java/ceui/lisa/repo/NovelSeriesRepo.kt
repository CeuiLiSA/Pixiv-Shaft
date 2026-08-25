package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListNovelSeries

class NovelSeriesRepo(private val userID: Int) : RemoteRepo<ListNovelSeries>() {

    override suspend fun initApi(): ListNovelSeries {
        return Retro.getAppApi().getUserNovelSeries(userID)
    }

    override suspend fun initNextApi(): ListNovelSeries {
        return Retro.getAppApi().getNextUserNovelSeries(nextUrl)
    }
}
