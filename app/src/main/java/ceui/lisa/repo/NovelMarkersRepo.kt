package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListNovelMarkers

class NovelMarkersRepo : RemoteRepo<ListNovelMarkers>() {

    override suspend fun initApi(): ListNovelMarkers {
        return Retro.getAppApiSuspend().getNovelMarkers()
    }

    override suspend fun initNextApi(): ListNovelMarkers {
        return Retro.getAppApiSuspend().getNextNovelMarkers(nextUrl)
    }
}
