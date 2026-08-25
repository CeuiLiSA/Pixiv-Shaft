package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListMangaSeries

class MangaSeriesRepo(private val userID: Int) : RemoteRepo<ListMangaSeries>() {

    override suspend fun initApi(): ListMangaSeries {
        return Retro.getAppApi().getUserMangaSeries(userID)
    }

    override suspend fun initNextApi(): ListMangaSeries {
        return Retro.getAppApi().getNextUserMangaSeries(nextUrl)
    }
}
